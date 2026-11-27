package net.dobeen.jaravel.cache.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Redis 的序列化格式。
 *
 * <p>这不只是「编码/解码」的细节:计数器必须写成**纯数字**,否则
 * Redis 的原子 {@code INCRBY} 无法工作,限流器与标签版本号就会退化成
 * 读-改-写(并可能丢更新)。其余类型用与 file / database 相同的 JSON
 * 信封,跨驱动读回的语义才一致。
 */
class RedisStoreTest {

    @Test
    @DisplayName("★ 整数写纯数字 —— INCRBY 只认这个")
    void integersAreStoredPlain() {
        assertThat(RedisStore.encode(1L)).isEqualTo("1");
        assertThat(RedisStore.encode(42)).isEqualTo("42");
        assertThat(RedisStore.decode("7")).isEqualTo(7L);
    }

    @Test
    @DisplayName("非数字仍用 JSON 信封,与 file / database 一致")
    void nonNumbersUseJsonEnvelope() {
        String encoded = RedisStore.encode("hello");

        assertThat(encoded).contains("java.lang.String").contains("hello");
        assertThat(RedisStore.decode(encoded)).isEqualTo("hello");
    }

    @Test
    @DisplayName("小数按 Double 读回,不误判成整数")
    void decimalsReadAsDouble() {
        assertThat(RedisStore.decode("1.5")).isEqualTo(1.5d);
    }

    @Test
    @DisplayName("null 仍然是空,不当成 0")
    void nullStaysNull() {
        assertThat(RedisStore.decode(null)).isNull();
        assertThat(RedisStore.decode(RedisStore.encode(null))).isNull();
    }
}
