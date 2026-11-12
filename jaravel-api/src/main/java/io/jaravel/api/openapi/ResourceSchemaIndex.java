package io.jaravel.api.openapi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 读注解处理器生成的 {@code META-INF/jaravel/api-resources.idx}(docs/12 §5)。
 *
 * <pre>{@code
 * var index = ResourceSchemaIndex.load();
 * index.schemaOf(PostResource.class);   // 有就返回
 * index.failureOf(ComplexResource.class); // 提取失败的原因
 * }</pre>
 *
 * <h2>格式:一行一条,制表符分隔</h2>
 * <pre>
 * # 注释
 * R\tapp.http.resources.PostResource      ← 一份资源
 * F\tid\tinteger\t0                       ← 字段:名字 / type(可空)/ 是否可选
 * X\tapp.http.resources.ComplexResource\ttoArray 有 3 个 return
 * </pre>
 *
 * <p>不用 JSON:这一层不需要再引一个解析器,而字段名里的制表符/换行
 * 用反斜杠转义就够了(见 {@code escape})。
 *
 * <h2>★ 用 getResources 而不是 getResource</h2>
 * <p>与 {@code listeners.idx} 同一条理由:多模块应用里每个 jar 各有一份
 * 索引,只读第一份会让**只有一个模块的资源**进入文档,而那个症状在
 * 单模块开发时完全看不出来。
 *
 * <h2>索引不存在不是错误</h2>
 * <p>处理器没接进 {@code annotationProcessorPaths} 时,没有任何索引文件。
 * 这时返回空索引,由调用方在文档生成时明确报告「这个资源没有 schema」——
 * 静默返回空 map 会让用户以为资源本来就没有字段。
 */
public final class ResourceSchemaIndex {

    static final String RESOURCE = "META-INF/jaravel/api-resources.idx";

    private ResourceSchemaIndex() {
    }

    public static Index load() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return load(context != null ? context : ResourceSchemaIndex.class.getClassLoader());
    }

    public static Index load(ClassLoader loader) {
        var schemas = new LinkedHashMap<String, ResourceSchema>();
        var failures = new LinkedHashMap<String, String>();

        try {
            Enumeration<URL> resources = loader.getResources(RESOURCE);
            while (resources.hasMoreElements()) {
                readInto(resources.nextElement(), schemas, failures);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "读取 " + RESOURCE + " 失败:" + e.getMessage(), e);
        }

        return new Index(schemas, failures);
    }

    private static void readInto(URL url, Map<String, ResourceSchema> schemas,
                                 Map<String, String> failures) throws IOException {
        String current = null;
        List<ResourceProperty> properties = new ArrayList<>();

        try (var reader = new BufferedReader(
                new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;

                String[] parts = line.split("\t", -1);
                switch (parts[0]) {
                    case "R" -> {
                        flush(current, properties, schemas);
                        current = parts.length > 1 ? unescape(parts[1]) : null;
                        properties = new ArrayList<>();
                    }
                    case "F" -> {
                        if (current == null || parts.length < 4) continue;
                        String type = unescape(parts[2]);
                        properties.add(new ResourceProperty(
                                unescape(parts[1]),
                                type.isBlank() ? null : type,
                                "1".equals(parts[3])));
                    }
                    case "X" -> {
                        if (parts.length < 3) continue;
                        failures.putIfAbsent(unescape(parts[1]), unescape(parts[2]));
                    }
                    default -> { }
                }
            }
        }
        flush(current, properties, schemas);
    }

    private static void flush(String className, List<ResourceProperty> properties,
                              Map<String, ResourceSchema> schemas) {
        if (className == null) return;
        schemas.putIfAbsent(className, new ResourceSchema(className, properties));
    }

    /**
     * 一次加载的结果。
     *
     * @param schemas  资源类的二进制名 → schema
     * @param failures 资源类的二进制名 → 提取失败的原因
     */
    public record Index(Map<String, ResourceSchema> schemas,
                        Map<String, String> failures) {

        public Index {
            schemas = Collections.unmodifiableMap(new LinkedHashMap<>(schemas));
            failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
        }

        /** 这个资源类的 schema —— 没有就是 empty */
        public Optional<ResourceSchema> schemaOf(Class<?> resource) {
            return Optional.ofNullable(schemas.get(resource.getName()));
        }

        /** 这个资源类提取失败的原因 —— 没有失败记录就是 empty */
        public Optional<String> failureOf(Class<?> resource) {
            return Optional.ofNullable(failures.get(resource.getName()));
        }

        public boolean isEmpty() {
            return schemas.isEmpty() && failures.isEmpty();
        }
    }

    // ================= 转义 =================

    /**
     * 与处理器里的那半必须一致。
     *
     * <p>字段名可以包含制表符与换行(一个 JSON key 不限于标识符),
     * 不转义的话一份资源的索引会从中间断成两行 ——
     * 而那种错误会表现为「后面的字段不见了」,很难与处理器的问题联系起来。
     */
    static String unescape(String value) {
        var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            out.append(switch (next) {
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case '\\' -> '\\';
                default -> {
                    // 不认识的转义原样保留 —— 丢掉反斜杠会静默改掉字段名
                    out.append('\\');
                    yield next;
                }
            });
        }
        return out.toString();
    }
}
