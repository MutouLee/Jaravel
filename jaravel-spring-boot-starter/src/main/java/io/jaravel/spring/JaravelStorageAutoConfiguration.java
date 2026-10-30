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
     * 要下载的话应用自己用
     * {@code Response.download(content, "报表.pdf")} ——
     * 没有 {@code Storage.download(...)},因为那会让存储模块依赖
     * HTTP 模块(与 {@code UploadedFile} 没有 {@code store(...)}
     * 是同一条约束的两面)。
     */
    @Bean
    public StorageTemporaryUrlRoute jaravelStorageTemporaryUrlRoute(
            FilesystemManager disks, UrlSigner signer, ConfigRepository config) {
        return new StorageTemporaryUrlRoute(disks, signer, config);
    }

    /**
     * 框架自带的那条路由。
     *
     * <p>它是一个普通的 {@code RouteDefinition} bean —— 路由层本来就把
     * 所有 {@code RouteDefinition} bean 收集起来按 {@code order()} 注册,
     * 所以框架不需要一个特殊的入口。
     *
     * <p>注册顺序与「应用可以接管它」见 {@link FrameworkRoutes} 的说明。
     */
    @Bean
    public FrameworkRoutes jaravelStorageRoutes() {
        return r -> r.get(TEMPORARY_URL_PATH, StorageTemporaryUrlRoute::serve)
                .name("storage.temporary");
    }

    /**
     * 临时 URL 的处理器。
     *
     * <p>它既是路由的目标({@link #serve}),也留了一个
     * 不经过 HTTP 的入口({@link #handle})——
     * 后者让签名校验能被单元测试直接测。
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

        /**
         * 路由入口:{@code GET /storage/temporary?path=...&expires=...&signature=...}
         *
         * <h2>★ 签名校验用的是「配置里的前缀 + 请求的原始查询串」,
         * 不是请求的完整 URL</h2>
         *
         * <p>这是一个刻意的决定,理由与代价都要说清。
         *
         * <p>签名覆盖的 base 是 {@code app.url + /storage/temporary} ——
         * 签 URL 的时候只有配置,没有请求。而**请求到达时的 host 常常
         * 不等于 app.url**:
         * <ul>
         *   <li>测试跑在随机端口上;</li>
         *   <li>反向代理改写了 Host;</li>
         *   <li>同一个应用挂在多个域名下。</li>
         * </ul>
         *
         * <p>按请求的完整 URL 校验的话,以上每一种情况都会得到
         * 「签名无效」—— 而那个消息与真正的原因(host 对不上)
         * 之间没有任何线索。这正是本项目反复遇到的那类缺陷。
         *
         * <p>**代价**:一个为 a.example.com 签的链接在 b.example.com 上
         * 也有效。那不是一个有意义的削弱 —— 签名密钥是**应用级**的
         * (`app.key`),两个域名跑的是同一个应用、同一个密钥、
         * 同一个磁盘。真正的边界是「path 被签进了签名」,而那一条没变。
         *
         * <p>⚠ 原始查询串必须**整个**参与校验(见
         * {@code Request.queryString()} 的说明)—— 只按已知参数重建的话,
         * 追加一个 {@code &tampered=1} 会被静默丢弃,签名照样通过。
         */
        public Object serve(io.jaravel.http.Request request) {
            String query = request.queryString();
            String url = config.string("app.url", "") + TEMPORARY_URL_PATH
                    + (query.isEmpty() ? "" : "?" + query);

            String path = request.query("path").orElseThrow(() ->
                    new io.jaravel.http.HttpException(400, """
                            临时 URL 缺少 path 参数。
                              正确的链接由 Storage.temporaryUrl(...) 生成。"""));
            String diskName = request.query("disk").orElse(null);

            byte[] content;
            try {
                content = handle(url, path, diskName);
            } catch (SecurityException e) {
                // 403 而不是 404:签名不对是一个**授权**失败。
                //
                // 不用 404 掩盖它:那会让一个「链接过期了」的用户
                // 以为文件被删了,而这是临时 URL 最常见的正常情况
                throw new io.jaravel.http.HttpException(403, e.getMessage());
            }

            // inline 而不是 attachment:临时 URL 的典型用途是
            // 「在页面里显示一张私有图片」。要下载的话应用自己用
            // Response.download(content, "报表.pdf")
            return io.jaravel.http.Response.inline(content,
                    fileNameOf(path), mimeTypeOf(path, diskName))
                    // 私有文件不该被任何中间层缓存住 ——
                    // 链接是限时的,而一个被 CDN 缓存的响应
                    // 会在链接过期之后继续可用
                    .header("Cache-Control", "private, no-store")
                    // 即便 Content-Type 说它是图片,也不让浏览器
                    // 去嗅探 —— 这是存储型 XSS 的最后一道闸
                    .header("X-Content-Type-Options", "nosniff");
        }

        private static String fileNameOf(String path) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
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
