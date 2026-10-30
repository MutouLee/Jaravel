package io.jaravel.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import io.jaravel.storage.disk.MemoryFilesystem;
import io.jaravel.support.Arr;

/**
 * 按名字管理磁盘(docs/11 §3.2)。
 *
 * <pre>{@code
 * var disks = new FilesystemManager()
 *         .register("local", m -> new LocalFilesystem(Path.of("storage/app")))
 *         .register("memory", m -> new MemoryFilesystem())
 *         .defaultDisk("local");
 * }</pre>
 *
 * <h2>v0.4 实现了哪些驱动</h2>
 * <table>
 *   <caption>docs/11 §3.2 的表 vs 实现</caption>
 *   <tr><th>驱动</th><th>状态</th></tr>
 *   <tr><td>{@code local}</td><td>✅</td></tr>
 *   <tr><td>{@code public}</td><td>✅(就是 local + url + 可执行类型检查)</td></tr>
 *   <tr><td>{@code memory}</td><td>✅</td></tr>
 *   <tr><td>{@code s3}</td><td>❌ 需要 AWS SDK v2</td></tr>
 *   <tr><td>{@code sftp}</td><td>❌ 需要 SSHJ</td></tr>
 *   <tr><td>{@code oss} / {@code cos}</td><td>❌ 扩展包</td></tr>
 * </table>
 *
 * <p>{@code s3} 没做而不是半做,理由:它不只是「换一个读写实现」——
 * 预签名 URL、按对象的 ACL、分段上传、最终一致性都是 S3 特有的语义,
 * 而一个只实现了 get/put 的 s3 驱动会让
 * {@code temporaryUrl} 与 {@code setVisibility} 变成抛异常的空壳 ——
 * 那比没有这个驱动更糟,因为用户会以为它能用。
 *
 * <p>配了未实现的驱动时**报错并说清**,而不是退回 local ——
 * 退回的后果是「我以为文件在 S3 上」,而它们在容器的本地盘里,
 * 容器一重启就没了。
 */
public final class FilesystemManager {

    private final Map<String, Function<FilesystemManager, Filesystem>> factories =
            new LinkedHashMap<>();
    private final Map<String, Filesystem> resolved = new LinkedHashMap<>();

    /** fake 模式下被替换掉的磁盘 */
    private final Map<String, Filesystem> faked = new LinkedHashMap<>();

    private String defaultDisk;

    // ================= 注册 =================

    public FilesystemManager register(String name,
                                      Function<FilesystemManager, Filesystem> factory) {
        factories.put(name, factory);
        resolved.remove(name);
        return this;
    }

    public FilesystemManager register(String name, Filesystem disk) {
        return register(name, m -> disk);
    }

    public FilesystemManager defaultDisk(String name) {
        requireKnown(name);
        this.defaultDisk = name;
        return this;
    }

    public String defaultDiskName() {
        return defaultDisk;
    }

    public java.util.Set<String> registeredDisks() {
        return java.util.Set.copyOf(factories.keySet());
    }

    // ================= 解析 =================

    public Filesystem disk() {
        if (defaultDisk == null) {
            throw new IllegalStateException("""
                    没有设默认磁盘。
                      在 config/filesystems.yml 里配 filesystems.default,
                      或者调 FilesystemManager.defaultDisk("local")。""");
        }
        return disk(defaultDisk);
    }

    public Filesystem disk(String name) {
        // fake 优先 —— 它的用途就是替换掉真实磁盘
        Filesystem fake = faked.get(name);
        if (fake != null) return fake;

        requireKnown(name);
        return resolved.computeIfAbsent(name, n -> factories.get(n).apply(this));
    }

    private void requireKnown(String name) {
        if (factories.containsKey(name)) return;
        if (faked.containsKey(name)) return;

        throw new IllegalStateException("""
                没有名为 "%s" 的磁盘。%s
                  已注册:%s
                  在 config/filesystems.yml 的 filesystems.disks 下登记它。"""
                .formatted(name,
                           Arr.closest(name, factories.keySet().stream().toList())
                              .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                           factories.keySet()));
    }

    // ================= 测试 =================

    /**
     * 把一个磁盘换成内存实现(docs/14 §4.3 的 {@code Storage.fake("s3")})。
     *
     * <p>**即便那个磁盘没注册也能 fake** —— 一个测试想测
     * 「上传到 s3」的代码时,测试环境里通常根本没配 s3。
     * 要求先注册会让那个测试必须带一套 AWS 配置。
     *
     * @return 那个内存磁盘,用于断言
     */
    public MemoryFilesystem fake(String name) {
        var memory = new MemoryFilesystem();
        faked.put(name, memory);
        return memory;
    }

    /** 把默认磁盘换成内存实现 */
    public MemoryFilesystem fake() {
        return fake(defaultDisk == null ? "local" : defaultDisk);
    }

    public void stopFaking() {
        faked.clear();
    }

    public boolean isFaking(String name) {
        return faked.containsKey(name);
    }

    /**
     * 取 fake 的那个磁盘。
     *
     * <p>**没 fake 时报错**而不是返回真实磁盘 —— 后者会让
     * {@code Storage.fake("s3"); ... disk.assertExists(...)} 这样的代码
     * 去断言一个真实的 S3,而那个断言要么失败得莫名其妙、
     * 要么(更糟)在 CI 上真的去动生产桶。
     */
    public MemoryFilesystem fakedDisk(String name) {
        Filesystem disk = faked.get(name);
        if (disk instanceof MemoryFilesystem memory) return memory;

        throw new IllegalStateException("""
                磁盘 "%s" 没有被 fake,没有记录可查。
                  在被测代码**之前**调 Storage.fake("%s")。

                  这里报错而不是返回真实磁盘 —— 后者会让断言去动真实存储,
                  而在 CI 上那可能是生产桶。"""
                .formatted(name, name));
    }

    /** 未实现的驱动 —— 报错而不是退回 local */
    public static Filesystem unsupportedDriver(String driver, String diskName) {
        throw new IllegalStateException("""
                磁盘 "%s" 配了驱动 "%s",而 v0.4 还没实现它。

                  已实现:local、public(= local + url)、memory。
                  未实现:s3、sftp、oss、cos。

                  s3 没做而不是半做的理由:它不只是「换一个读写实现」——
                  预签名 URL、按对象的 ACL、分段上传、最终一致性都是
                  S3 特有的语义。一个只实现了 get/put 的 s3 驱动会让
                  temporaryUrl 与 setVisibility 变成抛异常的空壳,
                  那比没有这个驱动更糟 —— 用户会以为它能用。

                  这里报错而不是退回 local:退回的后果是「我以为文件在
                  S3 上」,而它们在容器的本地盘里,容器一重启就没了。"""
                .formatted(diskName, driver));
    }
}
