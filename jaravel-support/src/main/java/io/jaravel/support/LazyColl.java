package io.jaravel.support;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * 惰性集合(docs/02 §9 的待决问题、docs/05 §2.5)。
 *
 * <p>{@link Coll} 是**即时**的:{@code get()} 把全部行读进内存。数据量真的很大时
 * ({@code DB.table("events").lazy()}、{@code User.query().lazy()}),
 * 需要的是「用一条取一条」——这个类提供那条路。
 *
 * <pre>{@code
 * try (var lazy = User.query().lazy(500)) {
 *     lazy.filter(u -> u.active)
 *         .map(u -> u.email)
 *         .take(10_000)
 *         .forEach(mailer::enqueue);
 * }
 * }</pre>
 *
 * <h2>★ 数据源是 Supplier,每次 iterator() 重新开始</h2>
 * <p>{@code LazyColl} 存的是「怎么造一个迭代器」,不是迭代器本身。于是它可以
 * 被重复遍历,而且 {@code map} / {@code filter} / {@code take} 都保持惰性 ——
 * 在调用 {@code iterator()} 之前一条数据都不会取。
 *
 * <p>⚠ 资源型的源(游标、文件行)每次遍历都是一次新的打开。用完请
 * {@code close()},或者用 {@link #toList()} / {@link #forEach} / {@code try-with-resources} ——
 * 这些路径会在结束时关闭。真正的 JDBC 游标推迟到后续版本;这一版数据库侧
 * 的 {@code lazy()} 是按主键分块取,内存上限是块大小,不是单条游标。
 *
 * <p>不是线程安全的 —— 与 Laravel 的 LazyCollection 同一个约定。
 */
public final class LazyColl<T> implements Iterable<T>, AutoCloseable {

    private final Supplier<? extends Iterator<T>> source;
    private final CloseState closeState;

    private LazyColl(Supplier<? extends Iterator<T>> source, CloseState closeState) {
        this.source = source;
        this.closeState = closeState;
    }

    // ================= 工厂 =================

    /** 每次遍历都调用 {@code supplier} 造一个新的迭代器 */
    public static <T> LazyColl<T> from(Supplier<? extends Iterator<T>> supplier) {
        if (supplier == null) throw new IllegalArgumentException("LazyColl.from(null)");
        return new LazyColl<>(supplier, new CloseState());
    }

    /** 可重复遍历的即时集合;转换本身不取数据 */
    public static <T> LazyColl<T> of(Iterable<T> iterable) {
        if (iterable == null) throw new IllegalArgumentException("LazyColl.of(null)");
        return new LazyColl<>(iterable::iterator, new CloseState());
    }

    @SafeVarargs
    public static <T> LazyColl<T> of(T... values) {
        return of(List.of(values));
    }

    public static <T> LazyColl<T> empty() {
        return of(List.of());
    }

    // ================= 遍历 =================

    @Override
    public Iterator<T> iterator() {
        return source.get();
    }

    public <R> LazyColl<R> map(Function<? super T, ? extends R> mapper) {
        return new LazyColl<>(() -> new Iterator<R>() {
            private final Iterator<T> delegate = LazyColl.this.iterator();

            @Override public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override public R next() {
                return mapper.apply(delegate.next());
            }
        }, closeState);
    }

    public LazyColl<T> filter(Predicate<? super T> predicate) {
        return new LazyColl<>(() -> new Iterator<T>() {
            private final Iterator<T> delegate = LazyColl.this.iterator();
            private T next;
            private boolean ready;

            @Override public boolean hasNext() {
                while (!ready && delegate.hasNext()) {
                    T candidate = delegate.next();
                    if (predicate.test(candidate)) {
                        next = candidate;
                        ready = true;
                    }
                }
                return ready;
            }

            @Override public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                T out = next;
                next = null;
                ready = false;
                return out;
            }
        }, closeState);
    }

    public LazyColl<T> take(long max) {
        return new LazyColl<>(() -> new Iterator<T>() {
            private final Iterator<T> delegate = LazyColl.this.iterator();
            private long remaining = Math.max(0, max);

            @Override public boolean hasNext() {
                return remaining > 0 && delegate.hasNext();
            }

            @Override public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                remaining--;
                return delegate.next();
            }
        }, closeState);
    }

    public <R> LazyColl<R> flatMap(
            Function<? super T, ? extends Iterable<? extends R>> mapper) {

        return new LazyColl<>(() -> new Iterator<R>() {
            private final Iterator<T> outer = LazyColl.this.iterator();
            private Iterator<? extends R> inner = List.<R>of().iterator();

            @Override public boolean hasNext() {
                while (!inner.hasNext() && outer.hasNext()) {
                    Iterable<? extends R> mapped = mapper.apply(outer.next());
                    inner = mapped == null ? List.<R>of().iterator() : mapped.iterator();
                }
                return inner.hasNext();
            }

            @Override public R next() {
                if (!hasNext()) throw new NoSuchElementException();
                return inner.next();
            }
        }, closeState);
    }

    // ================= 消费 =================

    public List<T> toList() {
        try {
            var out = new ArrayList<T>();
            iterator().forEachRemaining(out::add);
            // 不 List.copyOf:它拒绝 null,而映射出来的值可以是 null ——
            // 一个 null 字段不该让整次遍历炸在这里
            return java.util.Collections.unmodifiableList(out);
        } finally {
            close();
        }
    }

    public long count() {
        try {
            long count = 0;
            Iterator<T> iterator = iterator();
            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }
            return count;
        } finally {
            close();
        }
    }

    public void forEach(Consumer<? super T> action) {
        try {
            iterator().forEachRemaining(action);
        } finally {
            close();
        }
    }

    /**
     * 转成 {@link Stream}。
     *
     * <p>⚠ Stream 的终止操作**不会**自动关闭它 —— 用 try-with-resources:
     * <pre>{@code
     * try (var s = lazy.stream()) { s.limit(10).toList(); }
     * }</pre>
     */
    public Stream<T> stream() {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false)
                .onClose(this::close);
    }

    // ================= 关闭 =================

    /** 注册一个结束回调;派生集合共享同一个关闭状态 */
    public LazyColl<T> onClose(Runnable action) {
        if (action != null) closeState.add(action);
        return this;
    }

    @Override
    public void close() {
        closeState.close();
    }

    /**
     * 共享的关闭状态。
     *
     * <p>派生集合({@code map} / {@code filter})与原集合共享它,于是
     * 「关掉 map 出来的那个」也会关掉源;而 {@link AtomicBoolean} 保证
     * 回调只跑一次 —— 同时关掉源和派生集合不会二次关闭游标。
     */
    private static final class CloseState {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final List<Runnable> actions =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        void add(Runnable action) {
            actions.add(action);
            if (closed.get()) action.run();
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            for (Runnable action : actions) action.run();
        }
    }
}
