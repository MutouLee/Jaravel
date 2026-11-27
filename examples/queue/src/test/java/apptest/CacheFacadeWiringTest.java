package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.dobeen.jaravel.cache.Cache;
import net.dobeen.jaravel.cache.CacheManager;
import net.dobeen.jaravel.cache.Repository;
import net.dobeen.jaravel.testing.FeatureTest;

/**
 * {@code Cache} 门面指向**当前上下文**的 CacheManager。
 *
 * <h2>★ 为什么在示例里而不是在 jaravel-cache 里</h2>
 * <p>{@code jaravel-cache} 的 {@code CacheFacadeTest} 测的是转发本身
 * (自己 {@code use(...)} 一个 manager)。而「启动时真的被绑上了」
 * 跨两个模块:starter 的 {@code jaravelCacheManager} 里那行
 * {@code Cache.use(manager)},以及测试基类的 {@code StaticBindings}
 * 重绑 —— 只有在一个真实上下文里才测得到。
 *
 * <p>漏掉其中任何一边的症状都是「文档照抄下来的 {@code Cache.get(...)}
 * 一跑就说缓存尚未装配」,或者更坏:在多上下文的测试 JVM 里读到
 * **另一个**上下文的缓存。
 */
class CacheFacadeWiringTest extends FeatureTest {

    @Autowired
    CacheManager caches;

    @Autowired
    Repository repository;

    @Test
    @DisplayName("★ 门面与注入的 CacheManager 是同一个")
    void facadeIsBoundToThisContext() {
        assertThat(Cache.manager()).isSameAs(caches);
        assertThat(Cache.store()).isSameAs(repository);
    }

    @Test
    @DisplayName("门面写进去的,注入的 Repository 读得到")
    void facadeWritesAreVisibleToTheBean() {
        Cache.forget("facade:probe");
        Cache.put("facade:probe", "ada", Duration.ofMinutes(1));

        assertThat(repository.get("facade:probe", String.class)).contains("ada");
        assertThat(Cache.has("facade:probe")).isTrue();

        Cache.forget("facade:probe");
        assertThat(repository.missing("facade:probe")).isTrue();
    }
}
