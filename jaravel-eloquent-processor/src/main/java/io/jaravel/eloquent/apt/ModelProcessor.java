package io.jaravel.eloquent.apt;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/**
 * {@code @Model} 的注解处理器。
 *
 * <p>为每个模型生成三个文件:
 * <ul>
 *   <li>{@code Post_}     —— 列常量 + 关联路径 + {@code ModelMeta}</li>
 *   <li>{@code PostBase}  —— 用户模型的父类,带静态查询入口和关联访问器</li>
 *   <li>{@code PostQuery} —— 协变重写后的类型化查询</li>
 * </ul>
 *
 * <p>外加一份 {@code META-INF/jaravel/models.idx},供「列出所有模型」
 * (迁移检查、{@code jaravel model:list})使用,避免运行时扫包。
 *
 * <h2>生成父类这件事</h2>
 * <p>处理期用户的 {@code Post extends PostBase} 里 {@code PostBase} 尚不存在,
 * {@code getSuperclass()} 返回 {@code TypeKind.ERROR}。javac 容忍这一点
 * (已由 spike 验证:最终编译与运行均正常),但约束了分析器只能读本类
 * 自己声明的成员 —— 见 {@link ModelAnalyzer} 的类注释。
 */
@SupportedAnnotationTypes("io.jaravel.eloquent.annotations.Model")
@SupportedOptions({ModelProcessor.OPT_VERBOSE, ModelProcessor.OPT_SKIP_INDEX})
public class ModelProcessor extends AbstractProcessor {

    static final String OPT_VERBOSE = "jaravel.verbose";
    static final String OPT_SKIP_INDEX = "jaravel.skipModelIndex";

    private ModelAnalyzer analyzer;
    private final Set<String> generated = new LinkedHashSet<>();
    private boolean verbose;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.analyzer = new ModelAnalyzer(env.getElementUtils(), env.getTypeUtils(),
                                          env.getMessager());
        this.verbose = "true".equals(env.getOptions().get(OPT_VERBOSE));
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            if (!analyzer.hasErrors()) writeIndex();
            return false;
        }

        TypeElement modelAnn = processingEnv.getElementUtils()
                .getTypeElement("io.jaravel.eloquent.annotations.Model");
        if (modelAnn == null) return false;

        for (Element element : round.getElementsAnnotatedWith(modelAnn)) {
            if (!(element instanceof TypeElement type)) continue;

            if (!rejectNested(type)) continue;

            ModelInfo info;
            try {
                info = analyzer.analyze(type);
            } catch (RuntimeException e) {
                // 处理器崩溃会被 javac 报成「An annotation processor threw an exception」,
                // 对用户毫无信息量。转成定位到具体模型的错误。
                error(type, """
                        Jaravel 处理模型 %s 时内部出错:%s
                          这是 Jaravel 的 bug,请带上这个模型的定义提 issue:
                          https://github.com/MutouLee/Jaravel/issues
                          临时绕过:把这个模型改回手写 Repository + QueryBuilder。"""
                        .formatted(type.getSimpleName(), describe(e)));
                continue;
            }
            if (info == null) continue;              // 校验失败,错误已报

            emit(info);
        }
        return false;    // 不声明独占,让别的处理器也能看到 @Model
    }

    private void emit(ModelInfo info) {
        write(info, info.metamodelClass(), MetamodelGenerator.generate(info));
        write(info, info.baseClass(), BaseGenerator.generate(info));
        write(info, info.queryClass(), QueryGenerator.generate(info));
        generated.add(info.qualifiedName());

        if (verbose) {
            note(info.element(), "Jaravel 为 %s 生成了 %s / %s / %s(%d 列,%d 关联)".formatted(
                    info.simpleName(), info.metamodelClass(), info.baseClass(), info.queryClass(),
                    info.columns().size(), info.relations().size()));
        }
    }

    private void write(ModelInfo info, String simpleName, String source) {
        String qualified = info.packageName().isEmpty()
                ? simpleName : info.packageName() + "." + simpleName;
        try (Writer w = processingEnv.getFiler()
                .createSourceFile(qualified, info.element()).openWriter()) {
            w.write(source);
        } catch (javax.annotation.processing.FilerException e) {
            // 同一轮重复生成 —— 多个处理器配置或增量编译时会出现,不是错误
            if (verbose) note(info.element(), "跳过已生成的 " + qualified + ":" + e.getMessage());
        } catch (IOException e) {
            error(info.element(), "无法写出生成文件 " + qualified + ":" + e.getMessage()
                      + "\n  检查 target/generated-sources 的写权限。");
        }
    }

    /**
     * 模型清单。
     *
     * <p>用途:迁移的「模型 vs 表」一致性检查、{@code model:list} 命令、
     * 以及启动时一次性注册(省掉 {@code Class.forName} 的回落路径)。
     * 写成资源而非扫包,是因为扫包在 fat jar + 虚拟线程下既慢又不可靠。
     */
    private void writeIndex() {
        if (generated.isEmpty()) return;
        if ("true".equals(processingEnv.getOptions().get(OPT_SKIP_INDEX))) return;

        try {
            var file = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", "META-INF/jaravel/models.idx");
            try (Writer w = file.openWriter()) {
                w.write("# Jaravel 模型清单(由 APT 生成)\n");
                for (String name : generated) w.write(name + "\n");
            }
        } catch (javax.annotation.processing.FilerException e) {
            // 增量编译里本轮没有新模型时会重复创建,忽略
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "无法写出 META-INF/jaravel/models.idx:" + e.getMessage()
                  + "(不影响运行,只是 model:list 之类的命令会回落到扫包)");
        }
    }

    private static String describe(RuntimeException e) {
        var top = e.getStackTrace().length > 0 ? e.getStackTrace()[0].toString() : "?";
        return e.getClass().getSimpleName() + ": " + e.getMessage() + " @ " + top;
    }

    /**
     * 嵌套的 {@code @Model} 直接报错。
     *
     * <p>生成的基类按**简单名**引用模型({@code class WidgetBase { Widget self() }}),
     * 而嵌套类的简单名在生成文件所在的包里解析不到 —— 于是生成的是
     * **无法编译的代码**,javac 的报错指向 {@code target/generated-sources}
     * 里的某个文件,与真正的原因(模型是嵌套类)之间没有任何线索。
     *
     * <p>这是写模型事件的测试时当场撞上的:把测试模型写成嵌套类,
     * 得到六条指向生成文件的「找不到符号」。
     *
     * <p>不去支持嵌套模型而是报错:模型在真实应用里一律是顶层类
     * (一个模型对应一张表,它不属于任何别的类),
     * 为一个没人用的写法改掉整套生成逻辑不值得。
     * 但**报错必须说清**,而原先连错误都不是它自己报的。
     *
     * @return 可以继续处理吗
     */
    private boolean rejectNested(TypeElement type) {
        if (!type.getNestingKind().isNested()) return true;

        error(type, """
                @Model 不能标在嵌套类上(%s)。
                  生成的基类按简单名引用模型,而嵌套类的简单名在生成文件
                  所在的包里解析不到 —— 结果是生成一份无法编译的代码,
                  而那个报错会指向 target/generated-sources 里的文件。
                  把这个模型挪到一个顶层类(一个模型对应一张表,
                  它不属于任何别的类)。"""
                .formatted(type.getQualifiedName()));
        return false;
    }

    private void error(Element where, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, where);
    }

    private void note(Element where, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, message, where);
    }
}
