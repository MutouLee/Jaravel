package io.jaravel.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.support.Faker;

/**
 * 模型工厂 —— 测试与种子数据的造数据工具。
 *
 * <pre>{@code
 * static Factory<Post> posts() {
 *     return Factory.of(Post_.META, (f, p) -> {
 *         p.title = f.sentence();
 *         p.body  = f.paragraph();
 *         p.views = f.number(0, 1000);
 *     });
 * }
 *
 * posts().count(10).create();                  // 建 10 篇并入库
 * posts().state(p -> p.published = true).create();
 * posts().make();                              // 只造对象不入库
 * }</pre>
 *
 * <h2>为什么用 lambda 而不是注解或 YAML</h2>
 * <p>造数据的本质是「给字段赋值」,而给字段赋值在 Java 里已经有最直接的
 * 表达方式。换成注解或外部文件,就要重新发明一套表达「随机范围」「依赖另一个字段」
 * 的语法,而且全部失去编译期检查。
 *
 * <h2>随机性是可复现的</h2>
 * <p>{@link #seed(long)} 固定种子。测试里「偶尔失败」最大的来源就是随机数据,
 * 而失败时无法复现等于无法修 —— 所以种子必须可控,且失败信息里要能看到它。
 */
public final class Factory<M extends Model<M>> {

    private final ModelMeta<M> meta;
    private final BiConsumer<Faker, M> definition;
    private final List<Consumer<M>> states = new ArrayList<>();
    private final List<BiConsumer<M, Integer>> afterCreating = new ArrayList<>();

    private int count = 1;
    private Long seed;

    private Factory(ModelMeta<M> meta, BiConsumer<Faker, M> definition) {
        this.meta = meta;
        this.definition = definition;
    }

    public static <M extends Model<M>> Factory<M> of(
            ModelMeta<M> meta, BiConsumer<Faker, M> definition) {
        return new Factory<>(meta, definition);
    }

    /** 不需要随机数据时的简写 */
    public static <M extends Model<M>> Factory<M> of(ModelMeta<M> meta, Consumer<M> definition) {
        return new Factory<>(meta, (faker, model) -> definition.accept(model));
    }

    // ================= 配置 =================

    public Factory<M> count(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("count 不能是负数:" + n);
        }
        this.count = n;
        return this;
    }

    /** 在基础定义之上覆盖部分字段 */
    public Factory<M> state(Consumer<M> override) {
        states.add(override);
        return this;
    }

    /** 入库之后执行,拿得到自增主键 —— 用于建关联数据 */
    public Factory<M> afterCreating(BiConsumer<M, Integer> action) {
        afterCreating.add(action);
        return this;
    }

    /** 固定随机种子,让失败可复现 */
    public Factory<M> seed(long value) {
        this.seed = value;
        return this;
    }

    // ================= 产出 =================

    /** 只造对象,不入库 */
    public List<M> make() {
        var faker = newFaker();
        var out = new ArrayList<M>(count);
        for (int i = 0; i < count; i++) {
            out.add(build(faker, i));
        }
        return out;
    }

    public M makeOne() {
        return requireSingle(make(), "makeOne");
    }

    /** 造对象并入库 */
    public List<M> create() {
        var faker = newFaker();
        var out = new ArrayList<M>(count);
        for (int i = 0; i < count; i++) {
            M model = build(faker, i);
            model.save();
            for (var action : afterCreating) action.accept(model, i);
            out.add(model);
        }
        return out;
    }

    public M createOne() {
        return requireSingle(create(), "createOne");
    }

    private M requireSingle(List<M> models, String method) {
        if (models.size() == 1) return models.get(0);
        throw new IllegalStateException(
                method + "() 期望恰好一个模型,但 count = " + count + "。"
              + "\n  要多个请用 " + method.replace("One", "") + "()。");
    }

    private M build(Faker faker, int index) {
        M model = meta.instantiate();
        definition.accept(faker, model);
        for (var state : states) state.accept(model);
        return model;
    }

    private Faker newFaker() {
        return new Faker(seed == null ? new Random() : new Random(seed));
    }
}
