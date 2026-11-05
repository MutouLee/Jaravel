package io.jaravel.spring;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import io.jaravel.http.Request;
import io.jaravel.http.RequestScope;
import io.jaravel.support.Paginated;

/**
 * 把分页与 API 资源接到 HTTP 上(docs/12 §1 / §2)。
 *
 * <p>两件事:
 * <ul>
 *   <li>**resolver** —— 让 {@code User.query().paginate(15)} 读到 {@code ?page=N},
 *       并让 {@code links} 里是真的 URL;</li>
 *   <li>**序列化** —— 控制器直接返回 {@code Paginated} 时,输出 §2.2 那个形状。</li>
 * </ul>
 *
 * <h2>★ 为什么这两件事在 starter 而不在 Paginated 自己身上</h2>
 * <p>{@code Paginated} 住在 {@code jaravel-support} —— 那个模块**零依赖**
 * (CLI 的卖点就是 classpath 小)。让它认识 {@code Request} 或者 Jackson
 * 都要往 support 里拖依赖,而它同时被查询层、HTTP 层、视图层用。
 *
 * <p>所以 {@code Paginated} 只管数据与形状,「当前请求是什么」由这里装进去。
 * 这与 Laravel 的 {@code Paginator::currentPageResolver} 是同一个结构。
 */
@AutoConfiguration
public class JaravelPaginationAutoConfiguration {

    /**
     * 装 / 卸三个 resolver。
     *
     * <p>⚠ 它们是**静态**的全局状态,所以必须在容器关闭时卸掉 ——
     * 否则一次测试里装上的 resolver 会活到下一个上下文,
     * 而那时它引用的请求作用域已经没了。症状是
     * 「另一个测试里的分页莫名其妙带上了上一个测试的路径」。
     */
    @Bean
    public PaginationResolvers jaravelPaginationResolvers() {
        return new PaginationResolvers();
    }

    public static class PaginationResolvers {

        @PostConstruct
        void install() {
            Paginated.pageResolver(() -> currentRequest()
                    .flatMap(request -> request.query("page"))
                    .map(Integer::parseInt)
                    .orElse(1));

            // 基准路径用**不带查询串**的 URL:查询参数由 queryResolver 单独给,
            // 否则 page 会在链接里出现两次(一次是旧的,一次是新的)
            Paginated.pathResolver(() -> currentRequest().map(Request::url).orElse(null));

            Paginated.queryResolver(() -> currentRequest()
                    .map(PaginationResolvers::queryWithoutPage)
                    .orElse(Map.of()));
        }

        @PreDestroy
        void uninstall() {
            Paginated.reset();
        }

        /**
         * 不在请求里时是 empty —— 队列任务、命令、测试都会走到这里。
         *
         * <p>那时 {@code paginate(15)} 当成第 1 页、links 为 null,
         * 而不是报错:一个在 worker 里分批处理数据的调用方不该因为
         * 「没有 HTTP 请求」而失败。
         */
        private static java.util.Optional<Request> currentRequest() {
            return RequestScope.isActive() ? RequestScope.tryRequest() : java.util.Optional.empty();
        }

        /**
         * 除 {@code page} 之外的查询参数。
         *
         * <p>★ 必须排掉 {@code page} 自己:留着它的话
         * {@code /posts?page=2} 的「下一页」会变成
         * {@code /posts?page=2&page=3} —— 而哪一个生效取决于服务端实现,
         * 也就是说翻页会**有时**不动。
         *
         * <p>其它参数要留着:丢了它们,用户一翻页筛选条件就没了
         * (而那是分页组件最常见的 bug)。
         */
        static Map<String, String> queryWithoutPage(Request request) {
            var out = new LinkedHashMap<String, String>();
            String raw = request.queryString();
            if (raw == null || raw.isBlank()) return out;

            for (String pair : raw.split("&")) {
                if (pair.isBlank()) continue;
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                key = decode(key);
                if (key.equals("page")) continue;
                out.put(key, decode(value));
            }
            return out;
        }

        private static String decode(String raw) {
            return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * 让 Jackson 认识 {@code Paginated}。
     *
     * <h2>★ 不加这个会怎样</h2>
     * <p>Jackson 按 bean 规则序列化:{@code Paginated} 没有 getter
     * (它的访问器是 {@code items()} / {@code total()},而 {@code total()}
     * 还返回 {@code Optional})。结果要么是
     * {@code {"empty":false}} 这种莫名的东西,要么直接抛
     * 「No serializer found」—— 两者都离「我返回了一页数据」很远。
     *
     * <p>而加注解不行:那要让 {@code jaravel-support} 依赖 Jackson。
     * 一个序列化器放在这里,两边都干净。
     */
    @Bean
    public SimpleModule jaravelPaginationJacksonModule() {
        var module = new SimpleModule("jaravel-pagination");
        module.addSerializer(Paginated.class, new PaginatedSerializer());
        module.addSerializer(io.jaravel.api.JsonResource.class, new ResourceSerializer());
        module.addSerializer(io.jaravel.api.ResourceCollection.class,
                new ResourceCollectionSerializer());
        return module;
    }

    /** 序列化时「当前请求」从哪来 —— 不在请求里时是 null(命令、队列、测试) */
    private static io.jaravel.http.Request requestOrNull() {
        return RequestScope.isActive() ? RequestScope.tryRequest().orElse(null) : null;
    }

    /**
     * 资源 → JSON(docs/12 §1.3)。
     *
     * <h2>★ {@code data} 包裹只在**根位置**加</h2>
     * <p>一个资源既可能是整个响应体,也可能是分页 {@code data} 数组里的一个元素,
     * 或者另一个资源的嵌套字段。只有第一种该包裹 —— 否则会出现
     * {@code {"data":[{"data":{…}},…]}} 这种双层。
     *
     * <p>判据是 Jackson 自己的输出上下文:{@code getOutputContext().inRoot()}。
     * Laravel 的做法是让分页器主动拆包,而那要求**每一种容器**都记得去拆;
     * 问「我现在在根上吗」只需要问一次,而且对将来任何容器都成立。
     */
    public static final class ResourceSerializer
            extends JsonSerializer<io.jaravel.api.JsonResource> {

        @Override
        @SuppressWarnings("unchecked")
        public void serialize(io.jaravel.api.JsonResource value, JsonGenerator generator,
                              SerializerProvider serializers) throws IOException {

            var request = requestOrNull();
            boolean root = generator.getOutputContext().inRoot();
            generator.writeObject(root ? value.toRootBody(request) : value.toArray(request));
        }
    }

    /** 资源集合 → JSON。根位置可能是一个**裸数组**(关掉包裹时) */
    public static final class ResourceCollectionSerializer
            extends JsonSerializer<io.jaravel.api.ResourceCollection> {

        @Override
        @SuppressWarnings("unchecked")
        public void serialize(io.jaravel.api.ResourceCollection value, JsonGenerator generator,
                              SerializerProvider serializers) throws IOException {

            var request = requestOrNull();
            boolean root = generator.getOutputContext().inRoot();
            generator.writeObject(root ? value.toRootBody(request) : value.toArray(request));
        }
    }

    /**
     * {@code Paginated} → docs/12 §2.2 的那个形状。
     *
     * <h2>★ 信封里的 null 必须**显式写出来**</h2>
     * <p>第一版是 {@code writeObject(value.toMap())},而第一次拿真项目跑
     * 就看见 {@code links.next} **整个键消失了** —— 不是 null,是没有。
     * 原因:应用的 {@code ObjectMapper} 配了「不输出 null 字段」
     * ({@code spring.jackson.default-property-inclusion},API 响应的常见设置)。
     *
     * <p>于是最后一页的响应里没有 {@code next}、第一页里没有 {@code prev}。
     * 前端大多数分页组件靠 {@code links.next === null} 判断「能不能翻」——
     * 键不存在时拿到的是 {@code undefined},在 JS 里恰好也是假值,
     * 所以**多数情况下还能用**:这正是最糟的那种 bug,它不报错、
     * 只在某些组件上表现为按钮状态不对。
     *
     * <p>而 docs/12 §2.2 承诺的是「与 Laravel 完全一致,前端分页组件可直接复用」。
     * 一个会随无关配置变形的信封兑现不了那句话。
     *
     * <p>所以**信封**(links / meta)逐字段显式写,null 就写 null;
     * 而 {@code data} 里的元素仍然走应用自己的 mapper —— 那是用户的数据,
     * 用户的 inclusion 策略在那里是对的。
     * (同一条理由在 {@code route:list --json} 那里出现过一次。)
     */
    public static final class PaginatedSerializer extends JsonSerializer<Paginated> {

        private static final java.util.List<String> LINK_KEYS =
                java.util.List.of("first", "last", "prev", "next");

        @Override
        @SuppressWarnings("unchecked")
        public void serialize(Paginated value, JsonGenerator generator,
                              SerializerProvider serializers) throws IOException {

            Map<String, Object> shape = value.toMap();
            generator.writeStartObject();

            // data:用户的数据,照应用自己的策略序列化
            generator.writeFieldName("data");
            generator.writeObject(shape.get("data"));

            writeEnvelope(generator, "links", (Map<String, Object>) shape.get("links"), LINK_KEYS);
            writeEnvelope(generator, "meta", (Map<String, Object>) shape.get("meta"), null);

            generator.writeEndObject();
        }

        /**
         * @param required 必须出现的键(即便值是 null);null 表示「只写 map 里有的」
         */
        private void writeEnvelope(JsonGenerator generator, String name,
                                   Map<String, Object> values,
                                   java.util.List<String> required) throws IOException {
            generator.writeFieldName(name);
            generator.writeStartObject();

            var written = new java.util.LinkedHashSet<String>();
            for (var entry : values.entrySet()) {
                generator.writeFieldName(entry.getKey());
                generator.writeObject(entry.getValue());
                written.add(entry.getKey());
            }
            // meta 里「没查总数」时本来就不该有 total/last_page,所以只补 links 的
            if (required != null) {
                for (String key : required) {
                    if (written.contains(key)) continue;
                    generator.writeNullField(key);
                }
            }
            generator.writeEndObject();
        }
    }
}
