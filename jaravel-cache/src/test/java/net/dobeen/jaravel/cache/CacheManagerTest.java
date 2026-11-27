package net.dobeen.jaravel.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.dobeen.jaravel.cache.store.ArrayStore;
import net.dobeen.jaravel.cache.store.TieredStore;

/**
 * 驱动的惰性解析与组合(docs/09 §1.4)。
 *
 * <p>{@code storeRaw} 是给 tiered 这类组合驱动用的:它必须拿到
 * **同一个** L1 / L2 实例,否则「tiered 的 L1」与「直接用的 caffeine」
 * 是两份缓存,失效时只清掉其中一份。
 */
class CacheManagerTest {

    @Test
    @DisplayName("storeRaw 与 Repository 共用同一个 store 实例")
    void rawStoreIsShared() {
        var manager = new CacheManager()
                .register("array", new ArrayStore())
                .defaultStore("array");

        CacheStore raw = manager.storeRaw("array");

        assertThat(manager.storeRaw("array")).isSameAs(raw);
        assertThat(manager.store("array").store()).isSameAs(raw);
        assertThat(manager.store("array").store()).isSameAs(manager.storeRaw("array"));
    }

    @Test
    @DisplayName("reset 同时丢弃 store 与 Repository")
    void resetDropsBoth() {
        var manager = new CacheManager().register("array", m -> new ArrayStore());
        CacheStore before = manager.storeRaw("array");

        manager.reset();

        assertThat(manager.storeRaw("array")).isNotSameAs(before);
    }

    @Test
    @DisplayName("tiered 工厂拿到的 L1/L2 就是直接取用的那两个实例")
    void tieredReusesTheSameStores() {
        var manager = new CacheManager()
                .register("l1", m -> new ArrayStore())
                .register("l2", m -> new ArrayStore())
                .register("tiered", m -> new TieredStore(
                        m.storeRaw("l1"), m.storeRaw("l2"), Duration.ofSeconds(10)));

        manager.store("tiered").put("k", "v", Duration.ofMinutes(1));

        assertThat(manager.store("l1").get("k", String.class)).contains("v");
        assertThat(manager.store("l2").get("k", String.class)).contains("v");
    }
}
