package io.jaravel.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LazyColl}(docs/05 §2.5)。
 *
 * <p>重点不是「集合有哪些方法」,而是**什么时候才真的取数据** ——
 * 一个名字叫 lazy 却先把整张表读进内存的实现,比没有这个类更糟。
 */
class LazyCollTest {

    @Test
    @DisplayName("★ 在 iterator() 之前一条数据都不取")
    void isActuallyLazy() {
        var opened = new AtomicInteger();

        LazyColl<Integer> lazy = LazyColl.from(() -> {
            opened.incrementAndGet();
            return List.of(1, 2, 3).iterator();
        });

        assertThat(opened.get()).isZero();

        var mapped = lazy.map(i -> i * 10).filter(i -> i > 10);
        assertThat(opened.get()).as("派生集合也不该触发数据源").isZero();

        assertThat(mapped.toList()).containsExactly(20, 30);
        assertThat(opened.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("map / filter / take 保持惰性且顺序正确")
    void mapFilterTake() {
        var result = LazyColl.of(IntStream.rangeClosed(1, 10).boxed().toList())
                .filter(i -> i % 2 == 0)
                .map(i -> i * 10)
                .take(3)
                .toList();

        assertThat(result).containsExactly(20, 40, 60);
    }

    @Test
    @DisplayName("take 能在无限源上停下")
    void takeOnInfiniteSource() {
        var result = LazyColl.from(() -> IntStream.iterate(1, i -> i + 1).iterator())
                .take(5)
                .toList();

        assertThat(result).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("flatMap 把每项摊开")
    void flatMap() {
        var result = LazyColl.of(List.of(1, 2, 3))
                .flatMap(i -> List.of("a" + i, "b" + i))
                .toList();

        assertThat(result).containsExactly("a1", "b1", "a2", "b2", "a3", "b3");
    }

    @Test
    @DisplayName("每次遍历都从 Supplier 重新开始")
    void canBeIteratedAgain() {
        var opened = new AtomicInteger();
        var lazy = LazyColl.from(() -> {
            opened.incrementAndGet();
            return List.of(1, 2).iterator();
        });

        assertThat(lazy.toList()).containsExactly(1, 2);
        assertThat(lazy.toList()).containsExactly(1, 2);
        assertThat(opened.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("map 出来的 null 不会让 toList 炸掉")
    void nullsSurvive() {
        var result = LazyColl.of(List.of("a", "b"))
                .map(text -> text.equals("b") ? null : text)
                .toList();

        assertThat(result).containsExactly("a", null);
    }

    @Test
    @DisplayName("count 与 forEach 都会消费并关闭")
    void countAndForEach() {
        var closed = new AtomicInteger();
        var lazy = LazyColl.of(List.of(1, 2, 3)).onClose(closed::incrementAndGet);

        assertThat(lazy.count()).isEqualTo(3);
        assertThat(closed.get()).isEqualTo(1);

        var seen = new java.util.ArrayList<Integer>();
        lazy.forEach(seen::add);
        assertThat(seen).containsExactly(1, 2, 3);
        assertThat(closed.get()).as("关闭只跑一次").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 派生集合与原集合共享关闭状态,不会二次关闭")
    void closeStateIsShared() {
        var closed = new AtomicInteger();
        var lazy = LazyColl.of(List.of(1, 2)).onClose(closed::incrementAndGet);
        var mapped = lazy.map(i -> i);

        mapped.close();
        lazy.close();

        assertThat(closed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("stream 用 try-with-resources 关闭")
    void streamCloses() {
        var closed = new AtomicInteger();
        var lazy = LazyColl.of(List.of(1, 2, 3)).onClose(closed::incrementAndGet);

        try (var stream = lazy.stream()) {
            assertThat(stream.map(i -> i * 2).toList()).containsExactly(2, 4, 6);
        }

        assertThat(closed.get()).isEqualTo(1);
    }
}
