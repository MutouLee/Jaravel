package io.jaravel.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.cache.store.ArrayStore;

/**
 * {@link Cache} 门面(docs/09 §1.1、docs/02 §2.3)。
 *
 * <p>门面只是转发,所以这里不重测 {@link Repository} 的语义 ——
 * 那些在 {@link CacheStoreContractTest}。这里钉三件门面特有的事:
 * 转发到的是**默认 store**、{@code store(name)} 能换 store、
 * 没装配时的报错说得出下一步。
 */
class CacheFacadeTest {

    @AfterEach
    void tearDown() {
        Cache.reset();
    }

    @Test
    @DisplayName("转发到默认 store")
    void forwardsToDefaultStore() {
        Cache.use(CacheManager.inMemory());

        Cache.put("user:1", "ada", Duration.ofMinutes(5));

        assertThat(Cache.get("user:1", String.class)).contains("ada");
        assertThat(Cache.has("user:1")).isTrue();
        assertThat(Cache.remember("user:2", Duration.ofMinutes(5), () -> "grace"))
                .isEqualTo("grace");
        assertThat(Cache.get("user:2", String.class)).contains("grace");

        Cache.forget("user:1");
        assertThat(Cache.missing("user:1")).isTrue();
    }

    @Test
    @DisplayName("store(name) 换到另一个 store,两边互不可见")
    void namedStoreIsIsolated() {
        Cache.use(new CacheManager()
                .register("a", new ArrayStore())
                .register("b", new ArrayStore())
                .defaultStore("a"));

        Cache.put("k", 1, Duration.ofMinutes(1));

        assertThat(Cache.store("a").get("k", Integer.class)).contains(1);
        assertThat(Cache.store("b").get("k", Integer.class)).isEmpty();
    }

    @Test
    @DisplayName("没装配时报错指出下一步")
    void unconfiguredErrorSaysWhatToDo() {
        assertThatThrownBy(() -> Cache.get("k"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缓存尚未装配")
                .hasMessageContaining("Cache.use(CacheManager.inMemory())");
    }
}
