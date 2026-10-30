package io.jaravel.spring;

import java.nio.file.Path;
import java.util.Set;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.crypt.UrlSigner;
import io.jaravel.storage.Filesystem;
import io.jaravel.storage.FilesystemManager;
import io.jaravel.storage.Storage;
import io.jaravel.storage.disk.LocalFilesystem;
import io.jaravel.storage.disk.MemoryFilesystem;

/**
 * 存储的自动装配(docs/11 §3.2 的 config/filesystems.yml)。
 *
 * <pre>{@code
 * filesystems:
 *   default: ${FILESYSTEM_DISK:local}
 *   disks:
 *     local:
 *       driver: local
 *       root: storage/app
 *       visibility: private
 *     public:
 *       driver: local
 *       root: storage/app/public
 *       url: ${APP_URL}/storage
 *       visibility: public
 * }</pre>
 *
 * <h2>没配时给一套默认的</h2>
 * <p>{@code local}(root = {@code storage/app})与
 * {@code memory} —— 「不写 config/filesystems.yml 也能跑」。
 *
 * <p>{@code public} 不给默认:它要一个 {@code url},
 * 而那个值猜不出来(它取决于部署时的域名与反向代理配置)。
 * 猜一个的后果是「图片的 URL 指向一个不存在的地址」。
 */
@AutoConfiguration(after = JaravelAutoConfiguration.class)
public class JaravelStorageAutoConfiguration {

    /**
     * 临时 URL 指向的那个路由。
     *
     * <p>local 驱动的临时 URL 是「一个应用自己的、带签名的路由」——
     * 见 {@code LocalFilesystem.temporaryUrl} 的注释。
     */
    static final String TEMPORARY_URL_PATH = "/storage/temporary";

    @Bean
    @ConditionalOnMissingBean
    public FilesystemManager jaravelFilesystemManager(
            ConfigRepository config, UrlSigner signer,
            JaravelAutoConfiguration.JaravelStaticWiring wiring) {

        var manager = new FilesystemManager();

        var disks = config.section("filesystems.disks");
        if (disks.isEmpty()) {
            manager.register("local", m -> new LocalFilesystem(Path.of("storage/app")));
            manager.register("memory", m -> new MemoryFilesystem());
        } else {
            disks.keySet().forEach(name ->
                    registerDisk(manager, name, config, signer));

            // memory 没在配置里出现时也注册它 —— 它是测试的退路,
            // 而 Storage.fake(...) 虽然不需要它注册,
            // 但「把 FILESYSTEM_DISK 改成 memory 看看」是一个常用的排查手段
            if (!manager.registeredDisks().contains("memory")) {
                manager.register("memory", m -> new MemoryFilesystem());
            }
        }

        String defaultDisk = config.string("filesystems.default", "local");
        if (!manager.registeredDisks().contains(defaultDisk)) {
            throw new IllegalStateException("""
                    filesystems.default 是 "%s",但没有这个磁盘。
                      已注册:%s
                      在 config/filesystems.yml 的 filesystems.disks 下登记它。"""
                    .formatted(defaultDisk, manager.registeredDisks()));
        }
        manager.defaultDisk(defaultDisk);

        Storage.use(manager);
        return manager;
    }

    private void registerDisk(FilesystemManager manager, String name,
                              ConfigRepository config, UrlSigner signer) {
        String path = "filesystems.disks." + name;
        String driver = config.string(path + ".driver", "local");

        switch (driver) {
            // local 与 public 是同一个驱动。
            // 区别是 public 多一个 url 并打开可执行类型检查 ——
            // 做成两个类会让它们 99% 的代码重复
            case "local", "public" -> manager.register(name, m -> {
                String url = config.string(path + ".url", null);
                String visibility = config.string(path + ".visibility",
                        driver.equals("public") ? "public" : "private");

                // 「对外可访问」由**配了 url** 或 driver=public 决定。
                //
                // 不只看 driver:一个 driver=local 但配了
                // url: ${APP_URL}/storage 的磁盘同样是对外可访问的,
                // 而它同样需要那个可执行类型检查
                boolean servedPublicly = driver.equals("public")
                        || (url != null && !url.isBlank());

                return new LocalFilesystem(
                        Path.of(config.string(path + ".root", "storage/app")),
                        url,
                        servedPublicly,
                        Set.copyOf(config.stringList(path + ".allow-extensions")),
                        visibility,
                        signer,
                        config.string("app.url", "") + TEMPORARY_URL_PATH);
            });

            case "memory" -> manager.register(name, m -> new MemoryFilesystem(
                    config.string(path + ".visibility", "private")));

            // 懒注册:只有真正用到时才报错 —— 否则一个「配了但没用」的
            // s3 磁盘会让启动失败,而那个应用可能根本不走 s3
            default -> manager.register(name,
                    m -> FilesystemManager.unsupportedDriver(driver, name));
        }
    }

    /**
     * 临时 URL 的那个路由。
     *
     * <p>⚠ **它是一道安全边界**,所以做的事按顺序是:
     * <ol>
     *   <li>校验签名 —— 不过就 403。不校验的话这个路由等于
     *       一个任意文件读取漏洞;</li>
     *   <li>规范化路径 —— {@code PathGuard} 会拒掉 {@code ..};</li>
     *   <li>从**指定的磁盘**读 —— 而不是默认磁盘。</li>
     * </ol>
     *
     * <p>签名里**包含了 path 查询参数**(它是被签的 URL 的一部分),
     * 所以改路径签名就不对了 —— 那是这道边界成立的关键。
     *
     * <p>{@code Content-Disposition: inline} 而不是 attachment:
     * 临时 URL 的典型用途是「在页面里显示一张私有图片」。
     * 要下载的话用 {@code Storage.download(...)}(那一侧
     * 需要 HTTP 层的响应构造,与上传一样还没接 —— 见下)。
     */
    @Bean
    public StorageTemporaryUrlRoute jaravelStorageTemporaryUrlRoute(
            FilesystemManager disks, UrlSigner signer, ConfigRepository config) {
        return new StorageTemporaryUrlRoute(disks, signer, config);
    }

    /**
     * 临时 URL 的处理器。
     *
     * <p>⚠ **它还没有被注册成一个路由。**
     *
     * <p>注册它需要在 {@code RouteDefinition} 那一层加一条框架自带的路由,
     * 而当前的路由注册是「应用提供 {@code RouteDefinition} bean」——
     * 框架自己没有一个加路由的入口。
     *
     * <p>那是一处真实的缺口(框架级路由:存储的临时 URL、
     * 将来的 health check、metrics 端点都需要它),
     * 但它属于路由层而不是存储层。
     *
     * <p>所以这个类现在是一个**可直接调用的处理器** ——
     * 应用可以自己在 routes 里挂上它:
     *
     * <pre>{@code
     * r.get("/storage/temporary", StorageTemporaryUrlRoute::handle);
     * }</pre>
     *
     * <p>写成这样而不是等路由层:临时 URL 的**签名校验**是这一节的
     * 安全核心,它现在就能被完整地测,而「先上线功能、
     * 安全检查后补」是这类事故的标准剧本。
     */
    public static class StorageTemporaryUrlRoute {

        private final FilesystemManager disks;
        private final UrlSigner signer;
        private final ConfigRepository config;

        StorageTemporaryUrlRoute(FilesystemManager disks, UrlSigner signer,
                                 ConfigRepository config) {
            this.disks = disks;
            this.signer = signer;
            this.config = config;
        }

        /**
         * 处理一次临时 URL 的访问。
         *
         * @param fullUrl   完整的请求 URL(含查询串)—— 签名校验要它
         * @param path      要读的存储路径
         * @param diskName  哪个磁盘。null = 默认
         * @return 文件内容
         * @throws SecurityException 签名不对 / 过期
         */
        public byte[] handle(String fullUrl, String path, String diskName) {
            // ★ 第一件事:校验签名。
            //
            // 不校验的话这个路由等于一个任意文件读取漏洞 ——
            // 任何人都能用 ?path=../../etc/passwd 来试
            // (虽然 PathGuard 会拦下那个具体的尝试,
            //  但一个未签名的路由仍然能读到磁盘上的**所有**文件)
            if (!signer.isValid(fullUrl)) {
                throw new SecurityException("""
                        临时 URL 的签名无效或已过期。
                          签名覆盖了整个 URL(含 path 查询参数),
                          所以改路径会让签名失效 —— 那是这道边界成立的关键。""");
            }

            Filesystem disk = diskName == null ? disks.disk() : disks.disk(diskName);

            return disk.get(path).orElseThrow(() ->
                    new Filesystem.FileNotFoundException(
                            "临时 URL 指向的文件不存在:" + path));
        }

        /** 给这个路由用的 MIME 类型 */
        public String mimeTypeOf(String path, String diskName) {
            Filesystem disk = diskName == null ? disks.disk() : disks.disk(diskName);
            return disk.mimeType(path);
        }

        public String routePath() {
            return TEMPORARY_URL_PATH;
        }
    }
}
