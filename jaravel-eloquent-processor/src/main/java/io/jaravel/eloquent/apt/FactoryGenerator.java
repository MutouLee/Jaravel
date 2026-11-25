package io.jaravel.eloquent.apt;

import java.util.List;
import java.util.Set;

/**
 * 生成 {@code PostFactoryBase} —— 用户的工厂继承它(docs/14 §3)。
 *
 * <p>用户在工厂里只写两样东西:字段默认值({@code definition})与
 * 具名状态({@code draft()} / {@code published()})。其余的
 * **都是样板,所以都生成**:
 *
 * <ul>
 *   <li>按列的类型化 setter —— {@code .title("X")} 而不是
 *       {@code Map.of("title", "X")}(docs/14 §10 登记了三个版本的
 *       「Map 缺少类型安全」,这里关掉它);</li>
 *   <li>自类型的链式方法 —— {@code count} / {@code state} / {@code sequence}
 *       / {@code seed} 返回 {@code PostFactory},于是用户的 {@code draft()}
 *       接在它们后面仍然编译得过;</li>
 *   <li>产出方法 —— {@code make} / {@code makeOne} / {@code create} /
 *       {@code createOne},返回 {@code Post} / {@code Coll<Post>} 这种
 *       具体类型而不是 {@code List<Model>}。</li>
 * </ul>
 *
 * <h2>自类型是怎么来的</h2>
 * <p>没有用 {@code F extends ModelFactory<M, F>} 这种自引用泛型 ——
 * 那会让用户的类声明变成 {@code PostFactory extends ModelFactory<Post,
 * PostFactory>},而它的全部作用只是让链式方法返回正确的类型。
 *
 * <p>生成的基类**知道**具体工厂叫什么(来自 {@code @UseFactory}),
 * 所以直接把返回类型写死成 {@code PostFactory} 就行。代价是一次向下转型,
 * 换来的是用户侧零泛型噪音。
 */
final class FactoryGenerator {

    private FactoryGenerator() {}

    /**
     * 不生成 setter 的字段名。
     *
     * <p>这些名字已经被基类的机制方法占了。生成一个同名 setter 会得到
     * **重载歧义**:一个 {@code count} 列会让 {@code .count(3)} 既可能是
     * 「造 3 条」也可能是「把 count 列设成 3」—— javac 会按参数类型挑一个,
     * 挑中哪个取决于列的类型,那是最糟糕的一种静默行为。
     */
    private static final Set<String> RESERVED = Set.of(
            "count", "state", "sequence", "seed", "make", "makeOne", "create", "createOne",
            "afterCreating", "definition", "modelMeta", "self", "toString", "hashCode", "equals",
            "getClass", "notify", "notifyAll", "wait", "clone", "finalize");

    /** @return 被跳过的 setter 字段名(调用方据此报 warning) */
    static List<String> reservedColumns(ModelInfo m) {
        return m.columns().stream()
                .map(ModelInfo.ColumnInfo::fieldName)
                .filter(RESERVED::contains)
                .toList();
    }

    static String generate(ModelInfo m) {
        String model = m.simpleName();
        String factory = m.factorySimpleName();
        String base = factory + "Base";
        var c = new Code();

        c.line("// 由 Jaravel 生成,请勿修改。源:%s(@UseFactory)", m.qualifiedName());
        if (!m.factoryPackage().isEmpty()) c.line("package %s;", m.factoryPackage()).blank();

        c.line("import java.util.List;");
        c.line("import java.util.function.BiConsumer;");
        c.line("import java.util.function.Consumer;");
        c.blank();
        c.line("import io.jaravel.eloquent.meta.ModelMeta;");
        c.line("import io.jaravel.eloquent.factories.ModelFactory;");
        c.line("import io.jaravel.support.Coll;");
        c.blank();

        c.doc("%s 的工厂基类。".formatted(model),
              "",
              "<p>不要手改本文件 —— 每次编译都会重新生成。",
              "字段默认值写在 {@code " + factory + ".definition(...)} 里,",
              "具名状态写成 {@code " + factory + "} 自己的方法:",
              "<pre>{@code",
              "public " + factory + " draft() {",
              "    return state(p -> p.status = \"draft\");",
              "}",
              "}</pre>");
        c.open("public abstract class %s extends ModelFactory<%s> {".formatted(base, m.qualifiedName()));
        c.blank();

        c.line("@Override");
        c.open("protected final ModelMeta<%s> modelMeta() {".formatted(m.qualifiedName()));
        c.line("return %s.META;", qualified(m, m.metamodelClass()));
        c.close();
        c.blank();

        emitChaining(c, m, factory);
        emitOutputs(c, m);
        emitColumnSetters(c, m, factory);
        emitRelationBuilders(c, m, factory);
        emitSelf(c, m, factory);

        c.close();
        return c.toString();
    }

    // ================= 链式配置 =================

    private static void emitChaining(Code c, ModelInfo m, String factory) {
        String model = m.qualifiedName();

        c.line("// ================= 链式配置 =================");
        c.blank();

        c.doc("造几条。{@code create()} 之后拿到的是 {@code Coll<" + m.simpleName() + ">}。");
        c.open("public %s count(int n) {".formatted(factory));
        c.line("withCount(n);");
        c.line("return self();");
        c.close();
        c.blank();

        c.doc("在默认值之上覆盖字段 —— 具名状态({@code draft()})就用它实现。");
        c.open("public %s state(Consumer<%s> override) {".formatted(factory, model));
        c.line("withState(override);");
        c.line("return self();");
        c.close();
        c.blank();

        c.doc("轮转状态:第 i 条用第 {@code i % n} 个步骤。",
              "按顺序而不是随机 —— 随机分布的测试会偶发失败。");
        c.line("@SafeVarargs");
        c.open("public final %s sequence(Consumer<%s>... steps) {".formatted(factory, model));
        c.line("withSequence(List.of(steps));");
        c.line("return self();");
        c.close();
        c.blank();

        c.doc("固定随机种子,让失败可复现。");
        c.open("public %s seed(long value) {".formatted(factory));
        c.line("withSeed(value);");
        c.line("return self();");
        c.close();
        c.blank();

        c.doc("入库之后执行(拿得到自增主键)。第二个参数是序号。",
              "整个工厂固定的钩子覆盖 {@code afterCreating(" + m.simpleName() + ")} 更合适。");
        c.open("public %s afterCreating(BiConsumer<%s, Integer> action) {".formatted(factory, model));
        c.line("withAfterCreating(action);");
        c.line("return self();");
        c.close();
        c.blank();
    }

    // ================= 产出 =================

    private static void emitOutputs(Code c, ModelInfo m) {
        String model = m.qualifiedName();

        c.line("// ================= 产出 =================");
        c.blank();

        c.doc("只造对象,不入库。");
        c.open("public Coll<%s> make() {".formatted(model));
        c.line("return Coll.wrap(buildAll());");
        c.close();
        c.blank();

        c.open("public %s makeOne() {".formatted(model));
        c.line("return requireSingle(buildAll(), \"makeOne\");");
        c.close();
        c.blank();

        c.doc("造对象并入库,然后跑创建后钩子。");
        c.open("public Coll<%s> create() {".formatted(model));
        c.line("return Coll.wrap(persistAll());");
        c.close();
        c.blank();

        c.open("public %s createOne() {".formatted(model));
        c.line("return requireSingle(persistAll(), \"createOne\");");
        c.close();
        c.blank();
    }

    // ================= 类型化 setter =================

    private static void emitColumnSetters(Code c, ModelInfo m, String factory) {
        var columns = m.columns().stream()
                .filter(col -> !RESERVED.contains(col.fieldName()))
                .toList();
        if (columns.isEmpty()) return;

        c.line("// ================= 按列的类型化 setter =================");
        c.doc("{@code .title(\"X\")} 而不是 {@code Map.of(\"title\", \"X\")} ——",
              "列名写错、类型写错都在编译期暴露。");
        c.blank();

        for (var col : columns) {
            c.open("public %s %s(%s value) {".formatted(factory, col.fieldName(), col.typeName()));
            c.line("return state(m -> m.%s = value);", col.fieldName());
            c.close();
            c.blank();
        }
    }

    // ================= 关联构建器 =================

    /**
     * {@code forAuthor(author)} / {@code hasPosts(3)} / {@code hasTags(2)}。
     *
     * <p>三类关联各有一种写法:
     * <ul>
     *   <li>{@code @BelongsTo} —— 外键在**本模型**上,直接写字段;</li>
     *   <li>{@code @HasMany} / {@code @HasOne} —— 外键在**目标模型**上,
     *       父对象入库后用它的主键填;</li>
     *   <li>{@code @BelongsToMany} —— 外键在**中间表**上,走关联访问器
     *       ({@code owner.tags().attach(...)})。它比前两类多一个参数:
     *       中间表上的额外字段。</li>
     * </ul>
     *
     * <p>through 与多态那几种仍然不生成:{@code @HasManyThrough} 的「中间那层」
     * 要先存在才能接上,而那是一条**三方**关系 —— 一个
     * {@code hasXxx(int)} 的签名表达不了「中间那层从哪来」。
     * 多态同理(目标类型由一列决定,造数据时得先说清造哪一种)。
     * 它们的写法留给 {@code afterCreating}(docs/14 §3.2)。
     */
    private static void emitRelationBuilders(Code c, ModelInfo m, String factory) {
        // 可生成的判据按关联类型不同:前三种要知道外键落在哪个字段上,
        // 而中间表那种根本没有「模型上的外键」—— 用 factoryReady() 一刀切
        // 会把它过滤掉(第一版就是这样,症状是 hasTags 根本没生成)
        var relations = m.relations().stream()
                .filter(r -> switch (r.kind()) {
                    case BELONGS_TO, HAS_MANY, HAS_ONE -> r.factoryReady();
                    case BELONGS_TO_MANY -> r.pivotReady();
                    default -> false;
                })
                .toList();
        if (relations.isEmpty()) return;

        c.line("// ================= 关联 =================");
        c.blank();

        for (var r : relations) {
            switch (r.kind()) {
                case BELONGS_TO -> emitBelongsTo(c, m, factory, r);
                case BELONGS_TO_MANY -> emitBelongsToMany(c, m, factory, r);
                default -> emitHasMany(c, factory, r);
            }
        }
    }

    /**
     * {@code hasTags(2)} / {@code hasTags(2, f -> f.featured())} /
     * {@code hasTags(2, f -> {}, Map.of("primary", true))} /
     * {@code withTags(tag1, tag2)}。
     *
     * <p>中间表的外键不在任何模型的字段上,所以这里走关联访问器:
     * 先造目标对象,再 {@code owner.tags().attach(ids, pivot)}。
     *
     * <p>⚠ 需要关联访问器存在 —— 即模型没关掉 {@code generateHelpers}。
     * 关掉的模型跳过这个构建器(而不是生成一段编译不过的代码)。
     */
    private static void emitBelongsToMany(Code c, ModelInfo m, String factory,
                                          ModelInfo.RelationInfo r) {
        if (!m.generateHelpers()) return;           // 没有 owner.tags() 可调
        if (r.targetFactoryClass() == null) return; // 没有目标工厂,造不出来

        String method = "has" + io.jaravel.support.Str.studly(r.name());
        String with = "with" + io.jaravel.support.Str.studly(r.name());
        String childFactory = r.targetFactoryClass();

        c.doc("附带 n 个 %s,并挂上中间表 {@code %s}。".formatted(
                        r.targetSimpleName(), r.pivotTable()),
              "",
              "<p>与 {@code hasXxx} 的差别只在最后一步:多对多没有「子对象的外键」,",
              "挂接走的是关联访问器 {@code owner." + r.name() + "().attach(...)}。",
              "",
              "<p>只对 {@code create()} 有效 —— {@code make()} 不入库,",
              "中间表里填不进两边的主键。");
        c.open("public %s %s(int n) {".formatted(factory, method));
        c.line("return %s(n, child -> { }, java.util.Map.of());", method);
        c.close();
        c.blank();

        c.open("public %s %s(int n, Consumer<%s> tweak) {".formatted(factory, method, childFactory));
        c.line("return %s(n, tweak, java.util.Map.of());", method);
        c.close();
        c.blank();

        c.doc("同上,外加中间表上的额外字段({@code withPivot} 声明过的那些)。");
        c.open("public %s %s(int n, Consumer<%s> tweak, java.util.Map<String, ?> pivot) {"
                .formatted(factory, method, childFactory));
        c.open("return afterCreating((owner, index) -> {");
        c.line("var children = new %s();", childFactory);
        c.line("children.count(n);");
        c.line("tweak.accept(children);");
        c.line("var created = children.create();");
        c.line("owner.%s().attach(created.toList().stream()", r.name());
        c.line("        .map(related -> (Object) related.%s).toList(), pivot);", r.targetKeyField());
        c.close("});");
        c.close();
        c.blank();

        c.doc("挂**已有**的 %s —— 造数据时常有「几篇文章共用同一批标签」。".formatted(
                r.targetSimpleName()));
        c.open("public %s %s(%s... existing) {".formatted(factory, with, r.targetType()));
        c.line("return %s(java.util.List.of(existing), java.util.Map.of());", with);
        c.close();
        c.blank();

        // Collection 的重载:List<Tag> 传不进 Tag... ——
        // 而「把已有的一批挂上去」最常见的来源就是另一个工厂的 create()
        c.open("public %s %s(java.util.Collection<%s> existing) {".formatted(
                factory, with, r.targetType()));
        c.line("return %s(existing, java.util.Map.of());", with);
        c.close();
        c.blank();

        c.open("public %s %s(java.util.Collection<%s> existing, java.util.Map<String, ?> pivot) {"
                .formatted(factory, with, r.targetType()));
        c.open("return afterCreating((owner, index) -> {");
        c.line("owner.%s().attach(existing.stream()", r.name());
        c.line("        .map(related -> (Object) related.%s).toList(), pivot);", r.targetKeyField());
        c.close("});");
        c.close();
        c.blank();
    }

    private static void emitBelongsTo(Code c, ModelInfo m, String factory,
                                      ModelInfo.RelationInfo r) {
        String method = "for" + io.jaravel.support.Str.studly(r.name());

        c.doc("归属 %s —— 把外键 {@code %s} 指向这一条。".formatted(
                r.targetSimpleName(), r.foreignKey()));
        c.open("public %s %s(%s related) {".formatted(factory, method, r.targetType()));
        c.line("return state(m -> m.%s = related.%s);", r.foreignKeyField(), r.targetKeyField());
        c.close();
        c.blank();

        if (r.targetFactoryClass() == null) return;

        c.doc("同上,但父对象**立刻用工厂建一条**。",
              "",
              "<p>★ 建的是**一条**,count 里所有模型共用它 —— 不是每条一个父对象。",
              "这是刻意的:{@code count(3).%s(%s.factory())} 想表达的是".formatted(
                      method, r.targetSimpleName()),
              "「同一个作者的三篇」。要「各自一个父对象」就在 state 里自己建。");
        c.open("public %s %s(%s related) {".formatted(factory, method, r.targetFactoryClass()));
        c.line("return %s(related.createOne());", method);
        c.close();
        c.blank();
    }

    private static void emitHasMany(Code c, String factory, ModelInfo.RelationInfo r) {
        if (r.targetFactoryClass() == null) return;        // 没有目标工厂,建不出子对象

        String method = "has" + io.jaravel.support.Str.studly(r.name());
        String childFactory = r.targetFactoryClass();
        boolean single = !r.isMany();

        c.doc("附带 %s %s —— 在父对象**入库之后**建,外键 {@code %s} 已经填好。".formatted(
                        single ? "一条" : "n 条", r.targetSimpleName(), r.foreignKey()),
              "",
              "<p>所以它只对 {@code create()} 有效:{@code make()} 不入库,",
              "父对象没有主键,子对象的外键也就无处可填。");
        c.open("public %s %s(%s) {".formatted(factory, method, single ? "" : "int n"));
        c.line("return %s(%schild -> { });", method, single ? "" : "n, ");
        c.close();
        c.blank();

        c.doc("同上,外加一次对子工厂的调整:{@code %s(%sf -> f.draft())}。".formatted(
                method, single ? "" : "3, "));
        c.open("public %s %s(%sConsumer<%s> tweak) {".formatted(
                factory, method, single ? "" : "int n, ", childFactory));
        c.open("return afterCreating((owner, index) -> {");
        c.line("var children = new %s();", childFactory);
        c.line("children%s.state(child -> child.%s = owner.%s);",
                single ? "" : ".count(n)", r.foreignKeyField(), r.targetKeyField());
        c.line("tweak.accept(children);");
        c.line("children.create();");
        c.close("});");
        c.close();
        c.blank();
    }

    // ================= 自类型 =================

    private static void emitSelf(Code c, ModelInfo m, String factory) {
        c.doc("向下转型成具体工厂 —— 见类注释「自类型是怎么来的」。",
              "",
              "<p>再派生一层({@code class Xxx extends " + factory + "})会在这里失败。",
              "那条消息必须说清原因:ClassCastException 只会指着一次",
              "用户没写过的转型。");
        c.open("private %s self() {".formatted(factory));
        c.line("if (this instanceof %s typed) return typed;", factory);
        c.line("throw new IllegalStateException(");
        c.line("        \"%s 的链式方法返回 %s,但当前实例是 \" + getClass().getName()",
                factory, factory);
        c.line("      + \"。\\n  @UseFactory 只认一个工厂类;要再分一层请改用具名状态方法\"");
        c.line("      + \"\\n  (public %s draft() { return state(...); })。\");", factory);
        c.close();
    }

    /** 生成的类与模型同包,这里拼全限定名 */
    private static String qualified(ModelInfo m, String simpleName) {
        return m.packageName().isEmpty() ? simpleName : m.packageName() + "." + simpleName;
    }
}
