package io.jaravel.storage.disk;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.jaravel.storage.Filesystem;
import io.jaravel.storage.PathGuard;

/**
 * 内存驱动 —— 测试用(docs/11 §3.2)。
 *
 * <p>{@code Storage.fake("s3")} 换上的就是它。
 *
 * <h2>它**也过路径安全检查**</h2>
 * <p>这一点值得强调:一个不做检查的内存驱动会让「路径穿越的测试」
 * 在内存驱动上通过、在 local 驱动上失败 —— 于是那个安全检查的
 * 测试实际上什么都没测。
 *
 * <p>所以 {@link PathGuard#normalize} 在这里一样调用。
 * 唯一不做的是 {@code requireWithin} —— 内存里没有 root 可逃。
 *
 * <h2>没有目录的概念,但要装得像有</h2>
 * <p>内存里只有一个「路径 → 字节」的 map。而 {@code files("avatars")}
 * / {@code directories(...)} 要能工作,所以目录是**从路径推断**出来的。
 *
 * <p>一个后果:{@code makeDirectory} 之后那个目录**不会出现在**
 * {@code directories(...)} 里 —— 因为没有文件在它下面。
 * 这与 local 驱动不同,而它与**对象存储**一致(S3 也没有真正的目录)。
 *
 * <p>这个差异写在这里:一个依赖「空目录存在」的测试会在内存驱动上
 * 通过而在 S3 上失败,反之亦然。
 */
public class MemoryFilesystem implements Filesystem {

    private record Entry(byte[] content, Instant modifiedAt, String visibility) {
    }

    private final Map<String, Entry> files = new ConcurrentHashMap<>();
    private final String defaultVisibility;

    public MemoryFilesystem() {
        this("private");
    }

    public MemoryFilesystem(String defaultVisibility) {
        this.defaultVisibility = defaultVisibility;
    }

    @Override
    public String name() {
        return "memory";
    }

    private String key(String path) {
        // 内存驱动也过规范化 —— 见类注释
        return PathGuard.normalize(path);
    }

    // ================= 读 =================

    @Override
    public Optional<byte[]> get(String path) {
        var entry = files.get(key(path));

        // 返回副本 —— 调用方改掉返回的数组不该影响「存储里的内容」。
        // 真实驱动做不到这件事(它们每次都从磁盘读),
        // 而一个会被意外共享的数组会让测试出现诡异的串台
        return Optional.ofNullable(entry).map(e -> e.content().clone());
    }

    @Override
    public Optional<InputStream> readStream(String path) {
        return get(path).map(ByteArrayInputStream::new);
    }

    @Override
    public boolean exists(String path) {
        return files.containsKey(key(path));
    }

    @Override
    public long size(String path) {
        var entry = files.get(key(path));
        if (entry == null) throw new FileNotFoundException("内存里没有 " + path);
        return entry.content().length;
    }

    @Override
    public Instant lastModified(String path) {
        var entry = files.get(key(path));
        if (entry == null) throw new FileNotFoundException("内存里没有 " + path);
        return entry.modifiedAt();
    }

    @Override
    public String mimeType(String path) {
        return MimeTypes.forPath(path);
    }

    // ================= 写 =================

    @Override
    public void put(String path, byte[] contents) {
        files.put(key(path), new Entry(contents.clone(), Instant.now(),
                defaultVisibility));
    }

    @Override
    public void writeStream(String path, InputStream contents) {
        try {
            put(path, contents.readAllBytes());
        } catch (java.io.IOException e) {
            throw new StorageException("读不完流 " + path + ":" + e.getMessage(), e);
        }
    }

    @Override
    public void append(String path, String contents) {
        String existing = getString(path).orElse("");
        putString(path, existing + contents);
    }

    @Override
    public void prepend(String path, String contents) {
        String existing = getString(path).orElse("");
        putString(path, contents + existing);
    }

    // ================= 删 / 移动 / 复制 =================

    @Override
    public void delete(String path) {
        files.remove(key(path));
    }

    @Override
    public void copy(String from, String to) {
        var entry = files.get(key(from));
        if (entry == null) throw new FileNotFoundException("内存里没有 " + from);

        files.put(key(to), new Entry(entry.content().clone(), Instant.now(),
                entry.visibility()));
    }

    @Override
    public void move(String from, String to) {
        copy(from, to);
        delete(from);
    }

    // ================= 目录 =================

    @Override
    public List<String> files(String directory) {
        String prefix = prefixOf(directory);

        var out = new ArrayList<String>();
        for (String path : files.keySet()) {
            if (!path.startsWith(prefix)) continue;

            String rest = path.substring(prefix.length());
            // 直接子文件:剩下的部分里没有 /
            if (!rest.isEmpty() && !rest.contains("/")) out.add(path);
        }
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    @Override
    public List<String> allFiles(String directory) {
        String prefix = prefixOf(directory);

        return files.keySet().stream()
                .filter(path -> path.startsWith(prefix))
                .sorted()
                .toList();
    }

    @Override
    public List<String> directories(String directory) {
        String prefix = prefixOf(directory);

        var out = new java.util.LinkedHashSet<String>();
        for (String path : files.keySet()) {
            if (!path.startsWith(prefix)) continue;

            String rest = path.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash > 0) out.add(prefix + rest.substring(0, slash));
        }

        var sorted = new ArrayList<>(out);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    private String prefixOf(String directory) {
        if (directory == null || directory.isBlank() || directory.equals("/")) return "";
        return PathGuard.normalize(directory) + "/";
    }

    /**
     * 内存里没有真正的目录 —— 这是一个空操作。
     *
     * <p>**不报错**:一个为 local 驱动写的「先建目录再写文件」的代码
     * 应该在内存驱动上也能跑,而报错会让那段代码必须按驱动分支。
     *
     * <p>后果见类注释:建出来的目录不会出现在 {@code directories(...)} 里。
     */
    @Override
    public void makeDirectory(String directory) {
        // 空操作
    }

    @Override
    public void deleteDirectory(String directory) {
        String prefix = prefixOf(directory);
        files.keySet().removeIf(path -> path.startsWith(prefix));
    }

    // ================= URL =================

    /**
     * 一个固定的假前缀。
     *
     * <p>测试里断言「URL 里有这个路径」是有意义的,而抛异常会让
     * 一个用到 {@code url()} 的控制器没法在 fake 下测。
     */
    @Override
    public String url(String path) {
        return "memory://" + key(path);
    }

    /**
     * 临时 URL —— **未签名的占位符**。
     *
     * <p>⚠ 这是一处真实的 fake-vs-real 差异,必须说清:
     *
     * <p>local 驱动的临时 URL 是**签过名**的
     * (见 {@code LocalFilesystem.temporaryUrl}),而这里返回的是一个
     * {@code memory://} 的假 URL —— 它过不了签名校验。
     *
     * <p>后果:一段「生成临时 URL,然后校验它」的代码在 fake 下
     * 会失败。写那种测试时要用**真实磁盘**。
     *
     * <p>这个坑是写示例的端到端测试时当场踩到的:
     * 测试先 {@code Storage.fake("local")} 再生成临时 URL,
     * 然后那个 URL 过不了校验 —— 而失败消息是「签名无效或已过期」,
     * 与「用了 fake 磁盘」之间没有线索。
     *
     * <p>不让内存驱动也签名的理由:它没有签名器,而给它一个意味着
     * 内存驱动要依赖 {@code UrlSigner} 与一个 base URL ——
     * 那两样在一个「纯内存、零配置」的测试驱动里都不该有。
     */
    @Override
    public String temporaryUrl(String path, Duration ttl) {
        return "memory://" + key(path) + "?expires=" + ttl.toSeconds();
    }

    @Override
    public String temporaryUploadUrl(String path, Duration ttl) {
        return "memory://upload/" + key(path) + "?expires=" + ttl.toSeconds();
    }

    // ================= 可见性 =================

    @Override
    public String getVisibility(String path) {
        var entry = files.get(key(path));
        return entry == null ? defaultVisibility : entry.visibility();
    }

    /**
     * 内存驱动**支持**按文件设可见性,而 local 驱动不支持。
     *
     * <p>这个差异是刻意的:内存驱动模拟的是**对象存储**的语义
     * (S3 的每个对象有自己的 ACL),而 {@code Storage.fake()} 的
     * 典型用法是「测一段本该跑在 S3 上的代码」。
     *
     * <p>⚠ 代价:一段在 fake 下通过的 {@code setVisibility} 代码
     * 在 local 驱动上会抛。那是一个真实的不一致,而两头都不改更糟 ——
     * 让内存驱动也抛的话,S3 的可见性逻辑就没法测了。
     */
    @Override
    public void setVisibility(String path, String visibility) {
        var entry = files.get(key(path));
        if (entry == null) throw new FileNotFoundException("内存里没有 " + path);

        files.put(key(path), new Entry(entry.content(), entry.modifiedAt(), visibility));
    }

    // ================= 测试辅助 =================

    /** 所有路径 —— 断言用 */
    public List<String> allPaths() {
        var out = new ArrayList<>(files.keySet());
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    public int count() {
        return files.size();
    }

    public void clear() {
        files.clear();
    }
}
