package io.jaravel.eloquent.factories;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.support.Faker;

/**
 * 生成式模型工厂的运行时底座(docs/14 §3)。
 *
 * <p>用户不直接继承它 —— 继承的是 APT 生成的 {@code PostFactoryBase},
 * 那里有按列的类型化 setter({@code .title("X")})、按关联的构建器
 * ({@code .hasComments(3)})与自类型的链式方法。本类只负责**共同的机制**:
 * 计数、状态叠加、序列、种子、创建后钩子。
 *
 * <pre>{@code
 * public class PostFactory extends PostFactoryBase {
 *     @Override protected void definition(Faker f, Post p) {
 *         p.title = f.sentence();
 *     }
 *     public PostFactory draft() { return state(p -> p.status = "draft"); }
 * }
 * }</pre>
 *
 * <h2>与 {@code Factory} 的分工</h2>
 * <p>{@code Factory} 是**不需要 APT** 的那条路:一个 lambda 定义 + 链式调用,
 * 适合 seeder 里就地造几条数据。本类是「每个模型一个工厂类」那条路,
 * 换来的是类型化 setter 与关联构建器 —— 它们只能生成,手写就是样板。
 *
 * <p>两者共用 {@link Faker},所以定义里的写法完全一样。
 *
 * <h2>为什么公开面上没有 make() / create()</h2>
 * <p>它们由生成的基类定义,返回的是 {@code Post} / {@code Coll<Post>} 这种
 * **具体类型**。如果本类也声明一个 {@code List<M> create()},那两个方法
 * 擦除后同名而返回类型不兼容 —— 生成的代码直接编译不过。
 * 于是这里只给 {@link #persistAll()} / {@link #buildAll()},由生成的基类包装。
 */
public abstract class ModelFactory<M extends Model<M>> {

    private final List<Consumer<M>> states = new ArrayList<>();
    private final List<Consumer<M>> sequence = new ArrayList<>();
    private final List<BiConsumer<M, Integer>> afterCreatingHooks = new ArrayList<>();

    private int count = 1;
    private Long seed;

    // ================= 子类(生成的基类)要实现的 =================

    /** 这个工厂造哪个模型 —— 生成的基类返回 {@code Post_.META} */
    protected abstract ModelMeta<M> modelMeta();

    /**
     * 字段默认值。**用户写的就是这一个方法。**
     *
     * <p>留空也能用:所有列保持 Java 默认值。那样 NOT NULL 的列会在
     * 入库时被数据库拒绝 —— 报错来自数据库、指名道姓说哪一列,
     * 比框架替它猜一个默认值好。
     */
    protected void definition(Faker faker, M model) {
    }

    /**
     * 入库之后的钩子 —— 覆盖它来建关联数据。
     *
     * <p>这时主键已经有了(自增列在 {@code save()} 之后回填)。
     */
    protected void afterCreating(M model) {
    }

    // ================= 机制(生成的基类以自类型包装) =================

    protected final void withCount(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("count 不能是负数:" + n);
        }
        this.count = n;
    }

    protected final void withState(Consumer<M> override) {
        states.add(override);
    }

    /**
     * 轮转状态:第 i 个模型用第 {@code i % n} 个。
     *
     * <p>用于「造 3 条,一条草稿两条已发布」这类分布。按顺序而不是随机:
     * 随机分布的测试会偶发失败,而偶发失败的测试会被人加 {@code @Disabled}。
     */
    protected final void withSequence(List<Consumer<M>> steps) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("sequence() 至少要一个步骤");
        }
        sequence.clear();
        sequence.addAll(steps);
    }

    protected final void withSeed(long value) {
        this.seed = value;
    }

    protected final void withAfterCreating(BiConsumer<M, Integer> action) {
        afterCreatingHooks.add(action);
    }

    protected final int currentCount() {
        return count;
    }

    // ================= 产出 =================

    /** 只造对象,不入库 */
    protected final List<M> buildAll() {
        var faker = newFaker();
        var out = new ArrayList<M>(count);
        for (int i = 0; i < count; i++) {
            out.add(build(faker, i));
        }
        return out;
    }

    /** 造对象并入库,然后跑创建后钩子 */
    protected final List<M> persistAll() {
        var faker = newFaker();
        var out = new ArrayList<M>(count);
        for (int i = 0; i < count; i++) {
            M model = build(faker, i);
            model.save();
            afterCreating(model);
            for (var hook : afterCreatingHooks) hook.accept(model, i);
            out.add(model);
        }
        return out;
    }

    /**
     * 恰好一个 —— {@code createOne()} / {@code makeOne()} 用。
     *
     * <p>count 不是 1 时报错而不是「取第一个」:后者会让
     * {@code count(10).createOne()} 静默只返回一条,而另外九条已经入库了。
     */
    protected final M requireSingle(List<M> models, String method) {
        if (models.size() == 1) return models.get(0);
        throw new IllegalStateException(
                method + "() 期望恰好一个模型,但 count = " + count + "。"
              + "\n  要多个请用 " + method.replace("One", "") + "()。");
    }

    private M build(Faker faker, int index) {
        M model = modelMeta().instantiate();
        definition(faker, model);
        for (var state : states) state.accept(model);
        if (!sequence.isEmpty()) sequence.get(index % sequence.size()).accept(model);
        return model;
    }

    private Faker newFaker() {
        return new Faker(seed == null ? new Random() : new Random(seed));
    }
}
