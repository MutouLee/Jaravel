package io.jaravel.api.apt;

import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

import io.jaravel.api.openapi.ResourceProperty;
import io.jaravel.api.openapi.ResourceSchema;

/**
 * 为每个 {@code JsonResource} 生成响应 schema 索引(docs/12 §5)。
 *
 * <p>产出的 {@code META-INF/jaravel/api-resources.idx} 由
 * {@link io.jaravel.api.openapi.ResourceSchemaIndex} 在运行时读取,
 * 于是 {@code openapi:generate} 能填上「响应 schema」那一行。
 *
 * <h2>★ 为什么用 source API 而不是 javax.lang.model</h2>
 * <p>{@code toArray} 的字段名写在**方法体**里,而 {@code javax.lang.model}
 * 只给签名、不给方法体。{@code com.sun.source.*} 是 JDK 的公开 API
 * (在同一份源码里定位 {@code .put("id", ...)} 这一串调用),
 * 不需要 {@code --add-exports} 那些 javac 内部包。
 *
 * <h2>提取失败不是编译错误</h2>
 * <p>一个资源用了 {@code merge(动态 map)} 或分支 return,只发 **warning**
 * 并写进索引的 {@code X} 记录。编译不该因为「文档生成不了」而失败,
 * 但 {@code openapi:generate} 必须把这件事说出来 —— 静默少一个 schema
 * 会让前端以为那个接口没有响应体。
 */
@SupportedAnnotationTypes("*")
public class ResourceSchemaProcessor extends AbstractProcessor {

    private static final String JSON_RESOURCE = "io.jaravel.api.JsonResource";

    static final String RESOURCE = "META-INF/jaravel/api-resources.idx";

    /** 排序后写:同一份源码两次编译产出的索引逐字节一致 */
    private final Map<String, ResourceSchema> schemas = new TreeMap<>();
    private final Map<String, String> failures = new TreeMap<>();
    private final Set<String> analyzed = new HashSet<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        TypeElement jsonResource = processingEnv.getElementUtils()
                .getTypeElement(JSON_RESOURCE);

        if (jsonResource != null) {
            scan(round.getRootElements(), jsonResource);
        }

        if (round.processingOver()) {
            writeIndex();
        }
        return false;
    }

    private void scan(Iterable<? extends Element> elements, TypeElement base) {
        for (Element element : elements) {
            if (!(element instanceof TypeElement type)) continue;

            if (type.getKind() == ElementKind.CLASS
                    && !type.getModifiers().contains(Modifier.ABSTRACT)
                    && isResource(type, base)) {
                analyze(type);
            }

            // 嵌套类
            scan(type.getEnclosedElements(), base);
        }
    }

    private boolean isResource(TypeElement type, TypeElement base) {
        try {
            // ★ 必须比**擦除后**的 JsonResource:base.asType() 是
            // JsonResource<M>(M 是类型变量),而 JsonResource<Post> 不是
            // 任意 M 的子类型 —— 直接比会全部返回 false。
            return processingEnv.getTypeUtils().isSubtype(
                    type.asType(), processingEnv.getTypeUtils().erasure(base.asType()));
        } catch (RuntimeException e) {
            // 类型本身有编译错误时 isSubtype 可能抛 —— 那轮的错误由 javac 报
            return false;
        }
    }

    private void analyze(TypeElement type) {
        String binaryName = processingEnv.getElementUtils().getBinaryName(type).toString();
        if (!analyzed.add(binaryName)) return;

        var result = new ResourceAnalyzer(processingEnv).analyze(type);

        if (result instanceof ResourceAnalyzer.Success success) {
            schemas.put(binaryName, new ResourceSchema(binaryName, success.properties()));
            return;
        }

        String reason = ((ResourceAnalyzer.Failure) result).reason();
        failures.put(binaryName, reason);
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                binaryName + " 的响应 schema 无法静态提取:" + reason
                        + "\n  它不会出现在 openapi:generate 的文档里。"
                        + "把 toArray 改成单一的 map(request).put(...).build() 链,"
                        + "或者接受这个接口没有响应 schema。",
                type);
    }

    private void writeIndex() {
        if (schemas.isEmpty() && failures.isEmpty()) return;

        try (Writer writer = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE)
                .openWriter()) {

            writer.write("# jaravel api resources v1\n");

            for (ResourceSchema schema : schemas.values()) {
                writer.write("R\t" + escape(schema.className()) + "\n");
                for (ResourceProperty property : schema.properties()) {
                    writer.write("F\t" + escape(property.name())
                            + "\t" + escape(property.type() == null ? "" : property.type())
                            + "\t" + (property.optional() ? "1" : "0") + "\n");
                }
            }

            for (var failure : failures.entrySet()) {
                writer.write("X\t" + escape(failure.getKey())
                        + "\t" + escape(failure.getValue()) + "\n");
            }

        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "写不了 " + RESOURCE + ":" + e.getMessage()
                            + "\n  响应 schema 会缺失;清一次构建产物再编译试试。");
        }
    }

    /**
     * 与 {@code ResourceSchemaIndex.unescape} 必须一致。
     *
     * <p>字段名可以包含制表符/换行,不转义的话一份索引会从中间断成两行 ——
     * 而症状是「后面的字段不见了」。
     */
    static String escape(String value) {
        var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
