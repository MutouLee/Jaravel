package io.jaravel.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.cache.store.ArrayStore;

/**
 * {@code Cache.fake()} 与 {@link CacheFake}(docs/14 §4)。
 *
 * <p>重点是它比「换成 array 驱动」多出来的那件事:**命中记录**。
 * 「这段代码加了缓存」唯一值得测的断言是「第二次没有再去算」,
 * 而从返回值上看不出来 —— 两次调用的返回值本来就该一样。
 */
class CacheFakeTest {

    @AfterEach
    void tearDown() {
        Cache.reset();
    }

    private CacheFake fakeOn(CacheManager manager) {
        Cache.use(manager);
        return Cache.fake();
    }

    @Test
    @DisplayName("★ 命中/未命中被记下来 —— 「第二次没再算」才测得出来")
    void recordsHitsAndMisses() {
        var fake = fakeOn(CacheManager.inMemory());
        var computed = new AtomicInteger();

        java.util.function.Supplier<String> load = () -> {
            computed.incrementAndGet();
            return "value";
        };

        Cache.remember("dashboard:7", Duration.ofMinutes(5), load);
        fake.assertMissed("dashboard:7");
        fake.assertPut("dashboard:7");

        fake.forgetOperations();
        Cache.remember("dashboard:7", Duration.ofMinutes(5), load);

        fake.assertHit("dashboard:7");
        assertThat(computed.get())
                .as("第二次必须走缓存 —— 这是加缓存的全部意义")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("fake 是空的 —— 不继承原 store 里的数据")
    void startsEmpty() {
        var real = new ArrayStore();
        var manager = new CacheManager().register("array", real).defaultStore("array");
        Cache.use(manager);
        Cache.put("kept", "v", Duration.ofMinutes(1));

        var fake = Cache.fake();

        assertThat(Cache.get("kept")).isEmpty();
        fake.assertMissing("kept");
        assertThat(real.get("kept"))
                .as("原 store 里的值不该被动过")
                .isPresent();
    }

    @Test
    @DisplayName("stopFaking 还原之前的默认 store")
    void stopFakingRestoresDefault() {
        var manager = new CacheManager()
                .register("array", new ArrayStore())
                .register("other", new ArrayStore())
                .defaultStore("other");
        Cache.use(manager);

        Cache.fake();
        assertThat(manager.defaultStoreName()).isEqualTo(CacheFake.STORE_NAME);

        Cache.stopFaking();

        assertThat(manager.defaultStoreName()).isEqualTo("other");
        assertThat(Cache.isFaking()).isFalse();
    }

    @Test
    @DisplayName("前缀被剥掉 —— 断言写的是业务里那个键")
    void stripsThePrefix() {
        var manager = new CacheManager()
                .register("array", new ArrayStore())
                .defaultStore("array")
                .prefix("myapp");
        Cache.use(manager);
        var fake = Cache.fake();

        Cache.put("user:1", "ada", Duration.ofMinutes(1));

        fake.assertPut("user:1");
        fake.assertHas("user:1");
        assertThat(fake.keysPut()).containsExactly("user:1");
    }

    @Test
    @DisplayName("forget / flush / increment / many 都进记录")
    void recordsTheOtherOperations() {
        var fake = fakeOn(CacheManager.inMemory());

        Cache.put("a", 1, Duration.ofMinutes(1));
        Cache.increment("counter");
        Cache.many(List.of("a", "missing"), Integer.class);
        Cache.forget("a");

        fake.assertForgotten("a");
        assertThat(fake.hits()).isEqualTo(1);        // many 里的 a
        assertThat(fake.misses()).isEqualTo(1);      // many 里的 missing
        assertThat(fake.operations()).anyMatch(o -> o.kind() == CacheFake.Kind.INCREMENT);
    }

    @Test
    @DisplayName("★ 失败消息带上实际记录了什么")
    void failuresListWhatHappened() {
        var fake = fakeOn(CacheManager.inMemory());
        Cache.put("user:1", "ada", Duration.ofMinutes(1));

        assertThatThrownBy(() -> fake.assertHit("user:1"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("没有命中过")
                .hasMessageContaining("PUT(user:1)");

        assertThatThrownBy(fake::assertNothingPut)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("[user:1]");

        assertThatThrownBy(() -> fake.assertHas("nope"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("要断言前者用 assertPut");
    }

    @Test
    @DisplayName("不在 fake 模式时取记录器会报错")
    void recorderWithoutFake() {
        Cache.use(CacheManager.inMemory());

        assertThatThrownBy(Cache::recorder)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不在 fake 模式");
    }
}
