package io.jaravel.http.middleware;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import io.jaravel.core.lang.Lang;
import io.jaravel.http.Middleware;
import io.jaravel.http.Request;
import io.jaravel.http.Response;

/**
 * 按请求选语言(docs/02 §2.3 的 i18n 底座之上)。
 *
 * <pre>{@code
 * r.get("/", HomeController::index).middleware("web", "locale");
 *
 * // 或整组:
 * public class HttpKernel extends DefaultKernel {
 *     @Override protected Map<String, List<Class<? extends Middleware>>> groups() {
 *         var groups = new LinkedHashMap<>(super.middlewareGroups());
 *         groups.put("web", List.of(StartSession.class, VerifyCsrfToken.class,
 *                                   SubstituteBindings.class, SetLocale.class));
 *         return groups;
 *     }
 * }
 * }</pre>
 *
 * <h2>选语言的顺序</h2>
 * <ol>
 *   <li>查询参数(默认 {@code ?lang=en})—— 让「切换语言」能做成一个链接;</li>
 *   <li>会话里存的选择(默认键 {@code locale})—— 用户选过就记住,
 *       由应用在切换语言的那条路由里写入;</li>
 *   <li>{@code Accept-Language} 头,按 q 值排序后取第一个**支持**的;</li>
 *   <li>都不中:不动,用配置里的默认语言。</li>
 * </ol>
 *
 * <h2>★ 只认已装载的语言</h2>
 * <p>浏览器会发一串 {@code Accept-Language: de,fr;q=0.8,en;q=0.5},而应用只
 * 翻译了 zh_CN 与 en。如果照单全收地 {@code overrideLocale("de")},结果是**整站
 * 回退到 fallback**:每一条消息都查不到 de 的目录,于是 `Lang.get` 全部走
 * fallbackLocale —— 看起来像「语言设置没生效」,而原因是设成了一个不存在的语言。
 *
 * <p>所以默认只在 {@code Lang.translator().loadedLocales()} 里挑。应用要
 * 自己控制候选集就用 {@link #supporting(List)}。
 *
 * <h2>为什么不默认挂在 web 组里</h2>
 * <p>它会改变一个现有应用的可见行为:一个只写了中文页面、但用户浏览器
 * 发 {@code Accept-Language: en} 的站点,挂上它之后所有**有 en 翻译的**
 * 文案会变成英文,而没翻的还是中文 —— 半英半中的页面。那是不是想要的,
 * 只有应用知道。所以它是一个别名({@code "locale"}),显式挂。
 */
public class SetLocale implements Middleware {

    private static final String DEFAULT_QUERY_KEY = "lang";
    private static final String DEFAULT_SESSION_KEY = "locale";

    private String queryKey = DEFAULT_QUERY_KEY;
    private String sessionKey = DEFAULT_SESSION_KEY;
    private List<String> supported = List.of();

    /**
     * 限定候选语言。空 = 用 {@code Lang} 里已装载的那些(见类注释)。
     *
     * <p>顺序有意义:{@code Accept-Language} 里同 q 值时按这里的顺序优先。
     */
    public SetLocale supporting(List<String> locales) {
        this.supported = locales == null ? List.of() : List.copyOf(locales);
        return this;
    }

    /** 改查询参数名。传 null / 空 = 不看查询参数。 */
    public SetLocale queryKey(String key) {
        this.queryKey = key;
        return this;
    }

    /** 改会话键名。传 null / 空 = 不看会话。 */
    public SetLocale sessionKey(String key) {
        this.sessionKey = key;
        return this;
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        resolve(request).ifPresent(Lang::overrideLocale);
        return next.apply(request);
    }

    /**
     * 这个请求该用哪个语言。
     *
     * <p>包可见(而不是私有)是为了能直接测这段协商逻辑 ——
     * 它是本类唯一有分支的部分,而通过 HTTP 去测 q 值排序很绕。
     */
    Optional<String> resolve(Request request) {
        var candidates = candidates();
        if (candidates.isEmpty()) return Optional.empty();

        if (queryKey != null && !queryKey.isBlank()) {
            var fromQuery = match(request.query(queryKey).orElse(null), candidates);
            if (fromQuery.isPresent()) return fromQuery;
        }

        if (sessionKey != null && !sessionKey.isBlank()) {
            var fromSession = match(sessionLocale(), candidates);
            if (fromSession.isPresent()) return fromSession;
        }

        for (String wanted : acceptedLanguages(request.header("Accept-Language").orElse(null))) {
            var matched = match(wanted, candidates);
            if (matched.isPresent()) return matched;
        }
        return Optional.empty();
    }

    /**
     * 「用户选过的语言」从哪读 —— 默认没有来源。
     *
     * <h2>为什么是一个注入进来的函数</h2>
     * <p>这个来源天然是会话,而 {@code jaravel-http} **不依赖**
     * {@code jaravel-session}(会话是可选的,HTTP 不是;那条边界由 ArchUnit
     * 钉着)。所以这里只认一个 {@code 键 → 值} 的查询函数,由 starter 接上
     * 会话 —— 与 {@code jaravel-view} 的 {@code ViewRequestState.Source}
     * 是同一个做法。
     *
     * <p>反射去找 {@code io.jaravel.session.Session} 也能work,但那样
     * 「这个类依赖什么」就不在签名里了,而且拼错类名只会表现为
     * 「记住语言的功能静默失效」。
     */
    private Function<String, Optional<String>> selectionSource = key -> Optional.empty();

    public SetLocale readingSelectionFrom(Function<String, Optional<String>> source) {
        this.selectionSource = source == null ? key -> Optional.empty() : source;
        return this;
    }

    private String sessionLocale() {
        try {
            return selectionSource.apply(sessionKey).orElse(null);
        } catch (RuntimeException noSession) {
            // 这条路由没挂 StartSession —— 当作「没选过」,而不是让整个请求失败
            return null;
        }
    }

    private List<String> candidates() {
        if (!supported.isEmpty()) return supported;
        if (!Lang.isConfigured()) return List.of();

        // 已装载的语言就是「我们真的能翻」的那些 —— 见类注释
        return List.copyOf(Lang.translator().loadedLocales());
    }

    /**
     * 把一个想要的语言匹配到候选集。
     *
     * <p>两轮:先精确(大小写、{@code -}/{@code _} 都归一),再按语言段
     * ({@code zh-TW} → {@code zh_CN} 当 {@code zh} 只有一个候选时)。
     * 第二轮是必需的:浏览器发的是 {@code zh-CN} / {@code zh-Hans-CN},
     * 而目录名是 {@code zh_CN} —— 只做精确匹配时中文用户一个都匹配不上。
     */
    private Optional<String> match(String wanted, List<String> candidates) {
        if (wanted == null || wanted.isBlank()) return Optional.empty();
        String normalized = normalize(wanted);

        for (String candidate : candidates) {
            if (normalize(candidate).equals(normalized)) return Optional.of(candidate);
        }

        String language = normalized.split("_")[0];
        for (String candidate : candidates) {
            if (normalize(candidate).split("_")[0].equals(language)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static String normalize(String locale) {
        return locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 解析 {@code Accept-Language},按 q 值从高到低。
     *
     * <p>{@code zh-CN,zh;q=0.9,en;q=0.8} → {@code [zh-CN, zh, en]}。
     * 缺 q 值算 1.0(规范如此);q=0 表示「明确不要」,直接丢掉 ——
     * 不丢的话 {@code en;q=0} 会被当成候选,而那与用户的意思相反。
     */
    static List<String> acceptedLanguages(String header) {
        if (header == null || header.isBlank()) return List.of();

        record Weighted(String tag, double q, int order) {}
        var parsed = new ArrayList<Weighted>();
        int index = 0;

        for (String part : header.split(",")) {
            String[] pieces = part.trim().split(";");
            String tag = pieces[0].trim();
            if (tag.isEmpty() || tag.equals("*")) continue;

            double q = 1.0;
            for (int i = 1; i < pieces.length; i++) {
                String piece = pieces[i].trim();
                if (!piece.startsWith("q=")) continue;
                try {
                    q = Double.parseDouble(piece.substring(2).trim());
                } catch (NumberFormatException malformed) {
                    q = 1.0;      // 坏掉的 q 当没写 —— 为一个头里的格式错误拒绝请求不值得
                }
            }
            if (q > 0) parsed.add(new Weighted(tag, q, index++));
        }

        parsed.sort(java.util.Comparator.comparingDouble(Weighted::q).reversed()
                .thenComparingInt(Weighted::order));

        var out = new LinkedHashSet<String>();
        for (Weighted w : parsed) out.add(w.tag());
        return List.copyOf(out);
    }
}
