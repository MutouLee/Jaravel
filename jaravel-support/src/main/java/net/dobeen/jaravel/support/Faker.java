package net.dobeen.jaravel.support;

import java.util.List;
import java.util.Random;

/**
 * 最小够用的假数据生成器 —— 工厂与种子数据的随机值来源。
 *
 * <p>不引入 Java Faker:它有 10MB 的 locale 数据和一串传递依赖,而造数据
 * 真正需要的只是「不重复的字符串」和「范围内的数字」。想要更真实的数据,
 * 在工厂的 definition 里换成自己的 Faker 即可 —— 那是一个 lambda,没有限制。
 *
 * <h2>★ 序号是每个实例一份</h2>
 * <p>{@link #sequence()} / {@link #unique} / {@link #email} / {@link #slug}
 * 的计数器挂在**这个 Faker 实例**上,而工厂每次产出新建一个 Faker ——
 * 因为固定种子必须可复现,而可复现就不能有跨实例的共享状态。
 *
 * <p>代价要知道:两次 {@code Post.factory()} 的 slug 都从 {@code slug-1} 开始,
 * 于是 UNIQUE 列的默认值不能只靠它(examples/blog 的 {@code PostFactory}
 * 里有这件事的写法)。
 *
 * <h2>随机性是可复现的</h2>
 * <p>种子由工厂给(见 {@code Factory.seed} / {@code ModelFactory.seed})。
 * 测试里「偶尔失败」最大的来源就是随机数据,而失败时无法复现等于无法修。
 */
public final class Faker {

    private final Random random;
    private int sequence;

    public Faker(Random random) {
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
        return Clock.now().minusSeconds(seconds);
    }

    public java.time.Instant futureInstant(int withinDays) {
        long seconds = longNumber(0, (long) withinDays * 86_400);
        return Clock.now().plusSeconds(seconds);
    }

    public java.time.LocalDate pastDate(int withinDays) {
        return Clock.localDate().minusDays(number(0, withinDays));
    }
}
