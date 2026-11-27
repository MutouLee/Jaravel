package net.dobeen.jaravel.view;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.dobeen.jaravel.core.Application;
import net.dobeen.jaravel.core.config.ConfigRepository;

/**
 * {@code @vite([...])} 的落点(docs/04 §1.9、ADR-020)。
 *
 * <pre>{@code
 * <head>
 *   @vite(['resources/css/app.css', 'resources/js/app.js'])
 * </head>
 * }</pre>
 *
 * <h2>两个模式</h2>
 * <ul>
 *   <li><b>build</b>(默认):读 manifest,吐带哈希文件名的
 *       {@code <link>} / {@code <script type="module">},并为 chunk 的
 *       依赖吐 {@code modulepreload};</li>
 *   <li><b>dev</b>:配了 {@code vite.dev-server}(通常来自 {@code .env} 的
 *       {@code VITE_DEV_SERVER})时,改为指向 dev server 并先加载
 *       {@code @vite/client}。</li>
 * </ul>
 *
 * <h2>★ 为什么 dev 模式是配置,而不是「存在 public/hot 就算」</h2>
 * <p>Laravel 用 {@code public/hot} 文件做开关,因为 PHP 每个请求都去看文件系统。
 * Java 这边 dev 期的类路径是 {@code target/classes} —— 写进
 * {@code src/main/resources} 的文件要重新编译才可见,
 * 所以「存在某个文件」在这里根本不是一个可靠的开关。
 *
 * <p>也**不做自动探测**:每次渲染去 TCP 探一下 5173 会给每个页面加一次连接
 * 尝试,而探测失败时的回落(读 manifest)恰好是最难发现的错 ——
 * 「我改了 CSS 但页面没变」会被当成浏览器缓存。
 *
 * <h2>路径约定(ADR-020)</h2>
 * <pre>
 * resources/{css,js}/            前端源码(项目根,与 Laravel 逐字相同)
 * src/main/resources/public/build/   vite 产物(进 jar)
 * /build/...                      浏览器看到的 URL
 * </pre>
 * 产物放在 {@code classpath:/public/} 下是因为 Spring Boot 默认就把它当静态根 ——
 * 于是 URL 恰好是 Laravel 的 {@code /build/...},且零配置,也**进得了 jar**。
 *
 * <h2>manifest 读一次就缓存</h2>
 * <p>类路径资源没有「改了就重新读」这回事(dev 期读的是 {@code target/classes}
 * 里的副本)。所以 build 模式的产物变更需要重启 —— 而真正的开发回路是
 * dev server,那条路上压根不读 manifest。
 */
public final class Vite {

    private Vite() {}

    /** 产物目录名:类路径上是 {@code /public/<它>/},URL 上是 {@code /<它>/} */
    public static final String DEFAULT_BUILD_DIRECTORY = "build";

    /** 类路径上的静态根 —— Spring Boot 的默认值之一 */
    public static final String DEFAULT_RESOURCE_ROOT = "public";

    private static volatile Map<String, Object> manifest;
    private static volatile String manifestSource;

    // ================= 指令的落点 =================

    /**
     * {@code @vite(['resources/css/app.css', 'resources/js/app.js'])}。
     *
     * @return 整段 HTML(调用方走 {@code $unsafe},见 {@code JbladeTranspiler})
     */
    public static String tags(String... entries) {
        if (entries == null || entries.length == 0) {
            throw new IllegalArgumentException("""
                    @vite 需要至少一个入口,如
                      @vite(['resources/css/app.css', 'resources/js/app.js'])""");
        }

        String devServer = devServer();
        return devServer.isBlank()
                ? buildTags(List.of(entries))
                : devTags(devServer, List.of(entries));
    }

    /**
     * {@code @viteReactRefresh} —— React 的 HMR 前置脚本。
     *
     * <p>只在 dev 模式有输出;build 模式是空串(那时 refresh runtime 不存在,
     * 吐出来就是一个 404 加一个控制台报错)。
     */
    public static String reactRefresh() {
        String devServer = devServer();
        if (devServer.isBlank()) return "";

        return """
                <script type="module">
                import RefreshRuntime from '%s/@react-refresh'
                RefreshRuntime.injectIntoGlobalHook(window)
                window.$RefreshReg$ = () => {}
                window.$RefreshSig$ = () => (type) => type
                window.__vite_plugin_react_preamble_installed__ = true
                </script>
                """.formatted(devServer);
    }

    // ================= dev 模式 =================

    private static String devTags(String devServer, List<String> entries) {
        var html = new StringBuilder();
        html.append(script(devServer + "/@vite/client"));

        for (String entry : entries) {
            // dev server 直接按**源码路径**提供文件,没有哈希、没有 manifest
            html.append(isStylesheet(entry)
                    ? stylesheet(devServer + "/" + trimSlashes(entry))
                    : script(devServer + "/" + trimSlashes(entry)));
        }
        return html.toString();
    }

    // ================= build 模式 =================

    private static String buildTags(List<String> entries) {
        Map<String, Object> loaded = manifest();

        // 顺序:样式 → modulepreload → module 脚本。
        //
        // 样式在前,是为了别让浏览器先执行脚本再回头重排(FOUC);
        // preload 在脚本前,是为了让依赖的 chunk 与入口并行下载 ——
        // 不然浏览器要等入口解析完才知道还要下别的。
        var stylesheets = new LinkedHashSet<String>();
        var preloads = new LinkedHashSet<String>();
        var scripts = new ArrayList<String>();

        for (String entry : entries) {
            Map<String, Object> chunk = chunk(loaded, entry);
            String file = string(chunk, "file");

            if (isStylesheet(file)) {
                stylesheets.add(url(file));
                continue;
            }

            scripts.add(url(file));
            stylesheets.addAll(cssOf(chunk).stream().map(Vite::url).toList());
            collectPreloads(loaded, chunk, preloads);
        }

        var html = new StringBuilder();
        stylesheets.forEach(href -> html.append(stylesheet(href)));
        preloads.forEach(href -> html.append(modulePreload(href)));
        scripts.forEach(src -> html.append(script(src)));
        return html.toString();
    }

    /**
     * 入口依赖的 chunk,递归。
     *
     * <p>递归而不是只看一层:`imports` 里的 chunk 自己还有 imports,
     * 漏掉那些的结果是「首屏多几次串行往返」—— 不报错,只是慢,
     * 所以没人会发现。
     */
    private static void collectPreloads(Map<String, Object> loaded,
                                        Map<String, Object> chunk,
                                        LinkedHashSet<String> preloads) {
        for (String imported : stringList(chunk, "imports")) {
            Map<String, Object> dependency = asMap(loaded.get(imported));
            if (dependency == null) continue;

            String file = string(dependency, "file");
            if (file == null || !preloads.add(url(file))) continue;   // 已经收过:别绕圈

            cssOf(dependency).forEach(css -> preloads.add(url(css)));
            collectPreloads(loaded, dependency, preloads);
        }
    }

    private static Map<String, Object> chunk(Map<String, Object> loaded, String entry) {
        String key = trimSlashes(entry);
        Map<String, Object> chunk = asMap(loaded.get(key));
        if (chunk != null && chunk.get("file") != null) return chunk;

        throw new IllegalStateException("""
                manifest 里没有入口 "%s"。
                  manifest:%s
                  里面有:%s

                  两种原因:
                    1. vite.config.js 的 input 里没有这个文件 —— 入口要显式列出;
                    2. 模板里的路径与 input 里的写法不一致(manifest 的 key
                       就是 input 里那个字符串,逐字相同才查得到)。"""
                .formatted(entry, manifestSource, loaded.keySet()));
    }

    // ================= manifest =================

    private static Map<String, Object> manifest() {
        Map<String, Object> cached = manifest;
        if (cached != null) return cached;

        synchronized (Vite.class) {
            if (manifest != null) return manifest;

            String root = resourceRoot();
            String build = buildDirectory();

            // Vite 5 起 manifest 在 .vite/ 子目录里;4 及 laravel-vite-plugin
            // 放在产物根。两个都认 —— 版本差异不该变成一条「没有 manifest」的报错
            for (String candidate : List.of(
                    "/%s/%s/.vite/manifest.json".formatted(root, build),
                    "/%s/%s/manifest.json".formatted(root, build))) {

                String json = readResource(candidate);
                if (json == null) continue;

                manifestSource = "classpath:" + candidate;
                manifest = parse(json, candidate);
                return manifest;
            }

            throw new IllegalStateException("""
                    找不到 vite 的 manifest(找过 classpath:/%1$s/%2$s/.vite/manifest.json
                      与 classpath:/%1$s/%2$s/manifest.json)。

                      要么构建一次:
                        npm run build
                      要么用 dev server(改一行就刷新):
                        npm run dev
                        并在 .env 里写 VITE_DEV_SERVER=http://localhost:5173

                    ⚠ 这里**报错而不是吐空标签**:没有标签的页面长得像「CSS 坏了」,
                      而真正的原因是没构建过 —— 那两件事离得太远。"""
                    .formatted(resourceRoot(), buildDirectory()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json, String where) {
        // ★ 用 snakeyaml 读 JSON:**YAML 是 JSON 的超集**,而 jaravel-view
        // 已经(经 jaravel-core)有 snakeyaml 了。为一个机器生成的 manifest
        // 再引一个 JSON 库,或者手写一个 JSON 解析器,都不划算。
        Object loaded = new org.yaml.snakeyaml.Yaml().load(json);
        if (loaded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException(
                "manifest 不是一个对象:" + where + "(读到 "
                + (loaded == null ? "空" : loaded.getClass().getSimpleName()) + ")");
    }

    private static String readResource(String path) {
        try (InputStream in = Vite.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不了 classpath:" + path + ":" + e.getMessage(), e);
        }
    }

    /** 测试用:manifest 缓存一次就不再读(见类注释) */
    public static void clearCache() {
        synchronized (Vite.class) {
            manifest = null;
            manifestSource = null;
        }
    }

    // ================= 配置 =================

    /**
     * dev server 的地址。空 = build 模式。
     *
     * <p>容器还没起来时(单元测试里直接渲染模板)按 build 模式处理 ——
     * 那时没有配置可读,而「假装在 dev」会让测试去连一个不存在的端口。
     */
    private static String devServer() {
        return config().map(c -> c.string("vite.dev-server", ""))
                .orElse("")
                .trim()
                .replaceAll("/+$", "");          // 末尾斜杠:拼出来会是 //@vite/client
    }

    private static String buildDirectory() {
        return trimSlashes(config()
                .map(c -> c.string("vite.build-directory", DEFAULT_BUILD_DIRECTORY))
                .orElse(DEFAULT_BUILD_DIRECTORY));
    }

    private static String resourceRoot() {
        return trimSlashes(config()
                .map(c -> c.string("vite.resource-root", DEFAULT_RESOURCE_ROOT))
                .orElse(DEFAULT_RESOURCE_ROOT));
    }

    private static Optional<ConfigRepository> config() {
        return Application.tryMake(ConfigRepository.class);
    }

    // ================= 标签 =================

    private static String url(String file) {
        return "/" + buildDirectory() + "/" + trimSlashes(file);
    }

    private static String stylesheet(String href) {
        return "<link rel=\"stylesheet\" href=\"" + escape(href) + "\">\n";
    }

    private static String modulePreload(String href) {
        return isStylesheet(href)
                ? stylesheet(href)
                : "<link rel=\"modulepreload\" href=\"" + escape(href) + "\">\n";
    }

    private static String script(String src) {
        return "<script type=\"module\" src=\"" + escape(src) + "\"></script>\n";
    }

    private static boolean isStylesheet(String file) {
        return file != null && (file.endsWith(".css") || file.endsWith(".scss")
                || file.endsWith(".sass") || file.endsWith(".less")
                || file.endsWith(".styl") || file.endsWith(".stylus"));
    }

    /**
     * 属性值转义。
     *
     * <p>文件名来自 manifest(机器生成,正常不含引号),但「正常不含」
     * 不该出现在拼 HTML 的代码里 —— 与 {@code Jb.csrfField()} 同一个判断。
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                    .replace("\"", "&quot;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
    }

    private static String trimSlashes(String value) {
        return value == null ? "" : value.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    // ================= manifest 取值 =================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String string(Map<String, Object> chunk, String key) {
        Object value = chunk.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> cssOf(Map<String, Object> chunk) {
        return stringList(chunk, "css");
    }

    private static List<String> stringList(Map<String, Object> chunk, String key) {
        if (!(chunk.get(key) instanceof List<?> list)) return List.of();

        var out = new ArrayList<String>(list.size());
        for (Object item : list) {
            if (item != null) out.add(String.valueOf(item));
        }
        return out;
    }
}
