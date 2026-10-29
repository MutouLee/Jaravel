package io.jaravel.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.meta.ModelMeta;

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

    /**
     * 最小够用的假数据生成器。
     *
     * <p>不引入 Java Faker:它有 10MB 的 locale 数据和一串传递依赖,而工厂
     * 真正需要的只是「不重复的字符串」和「范围内的数字」。想要更真实的数据,
     * 用户在 definition 里换成自己的 Faker 即可 —— 那是个 lambda,没有限制。
     */
    public static final class Faker {

        private final Random random;
        private int sequence;

        Faker(Random random) {
            this.random = random;
        }

        private static final String[] WORDS = {
            "数据", "服务", "模型", "查询", "视图", "路由", "缓存", "队列",
            "事件", "任务", "配置", "校验", "迁移", "索引", "连接", "会话"
        };

        public Random random() { return random; }

        /** 自增序号 —— 需要唯一值时比随机可靠 */
        public int sequence() { return ++sequence; }

        public String word() {
            return WORDS[random.nextInt(WORDS.length)];
        }

        public String words(int n) {
            var sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(' ');
                sb.append(word());
            }
            return sb.toString();
        }

        public String sentence() {
            return words(3 + random.nextInt(4)) + "。";
        }

        public String paragraph() {
            var sb = new StringBuilder();
            int sentences = 2 + random.nextInt(3);
            for (int i = 0; i < sentences; i++) sb.append(sentence());
            return sb.toString();
        }

        /** 带序号的唯一字符串,避免唯一索引冲突 */
        public String unique(String prefix) {
            return prefix + "-" + sequence();
        }

        public String email() {
            return "user" + sequence() + "@example.com";
        }

        public String slug() {
            return "slug-" + sequence();
        }

        public String url() {
            return "https://example.com/" + slug();
        }

        public String ipv4() {
            return random.nextInt(256) + "." + random.nextInt(256) + "."
                 + random.nextInt(256) + "." + random.nextInt(256);
        }

        public String uuid() {
            // 用受控的 Random 而非 UUID.randomUUID(),否则种子固定也不可复现
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            var sb = new StringBuilder(36);
            for (int i = 0; i < 16; i++) {
                if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        }

        public int number(int minInclusive, int maxInclusive) {
            if (maxInclusive < minInclusive) {
                throw new IllegalArgumentException(
                        "number(" + minInclusive + ", " + maxInclusive + "):上界小于下界");
            }
            return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
        }

        public long longNumber(long minInclusive, long maxInclusive) {
            return minInclusive + (long) (random.nextDouble() * (maxInclusive - minInclusive + 1));
        }

        public java.math.BigDecimal decimal(double min, double max, int scale) {
            double value = min + random.nextDouble() * (max - min);
            return java.math.BigDecimal.valueOf(value)
                    .setScale(scale, java.math.RoundingMode.HALF_UP);
        }

        public boolean bool() {
            return random.nextBoolean();
        }

        /** 按概率返回 true —— {@code chance(0.3)} 约三成为真 */
        public boolean chance(double probability) {
            return random.nextDouble() < probability;
        }

        public <T> T pick(List<T> candidates) {
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("pick() 的候选列表为空");
            }
            return candidates.get(random.nextInt(candidates.size()));
        }

        @SafeVarargs
        public final <T> T pick(T... candidates) {
            return pick(List.of(candidates));
        }

        public <E extends Enum<E>> E pick(Class<E> enumType) {
            return pick(List.of(enumType.getEnumConstants()));
        }

        /** 过去 n 天内的随机时刻 */
        public java.time.Instant pastInstant(int withinDays) {
            long seconds = longNumber(0, (long) withinDays * 86_400);
            return java.time.Instant.now().minusSeconds(seconds);
        }

        public java.time.Instant futureInstant(int withinDays) {
            long seconds = longNumber(0, (long) withinDays * 86_400);
            return java.time.Instant.now().plusSeconds(seconds);
        }

        public java.time.LocalDate pastDate(int withinDays) {
            return java.time.LocalDate.now().minusDays(number(0, withinDays));
        }
    }
}
