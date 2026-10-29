package io.jaravel.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Laravel {@code Collection} 的等价物:**即时求值**的集合包装。
 *
 * <p>为什么不直接用 {@link Stream}:Stream 一次性消费、每步都要 {@code collect(...)} 收尾、
 * 没有到集合的简洁 groupBy、Optional 嵌套繁琐。{@code Coll} 的语义更接近 Laravel 的
 * Collection,心智负担更低。大数据量场景用 {@link #stream()} 逃生。
 *
 * <p>不可变:所有变换返回新实例。
 */
public final class Coll<T> implements Iterable<T> {

    private static final Coll<?> EMPTY = new Coll<>(List.of());

    private final List<T> items;

    private Coll(List<T> items) {
        this.items = items;
    }

    // ---------- 构造 ----------

    @SafeVarargs
    public static <T> Coll<T> of(T... items) {
        return new Coll<>(List.of(items));
    }

    public static <T> Coll<T> from(Iterable<T> items) {
        if (items instanceof Coll<T> c) return c;
        List<T> list = new ArrayList<>();
        items.forEach(list::add);
        return new Coll<>(List.copyOf(list));
    }

    public static <T> Coll<T> from(Collection<T> items) {
        return new Coll<>(List.copyOf(items));
    }

    public static <T> Coll<T> wrap(List<T> items) {
        return new Coll<>(items);
    }

    @SuppressWarnings("unchecked")
    public static <T> Coll<T> empty() {
        return (Coll<T>) EMPTY;
    }

    // ---------- 变换 ----------

    public <R> Coll<R> map(Function<? super T, ? extends R> f) {
        List<R> out = new ArrayList<>(items.size());
        for (T t : items) out.add(f.apply(t));
        return new Coll<>(out);
    }

    /** 带索引的 map */
    public <R> Coll<R> mapIndexed(java.util.function.BiFunction<Integer, ? super T, ? extends R> f) {
        List<R> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) out.add(f.apply(i, items.get(i)));
        return new Coll<>(out);
    }

    public <R> Coll<R> flatMap(Function<? super T, ? extends Iterable<R>> f) {
        List<R> out = new ArrayList<>();
        for (T t : items) f.apply(t).forEach(out::add);
        return new Coll<>(out);
    }

    public Coll<T> filter(Predicate<? super T> p) {
        List<T> out = new ArrayList<>();
        for (T t : items) if (p.test(t)) out.add(t);
        return new Coll<>(out);
    }

    /** filter 的反义,避免写 {@code filter(x -> !p.test(x))} */
    public Coll<T> reject(Predicate<? super T> p) {
        return filter(p.negate());
    }

    public Coll<T> take(int n) {
        if (n >= items.size()) return this;
        return new Coll<>(List.copyOf(items.subList(0, Math.max(0, n))));
    }

    public Coll<T> skip(int n) {
        if (n <= 0) return this;
        if (n >= items.size()) return empty();
        return new Coll<>(List.copyOf(items.subList(n, items.size())));
    }

    public Coll<T> reverse() {
        List<T> out = new ArrayList<>(items);
        java.util.Collections.reverse(out);
        return new Coll<>(out);
    }

    public Coll<T> sortBy(Function<? super T, ? extends Comparable> key) {
        List<T> out = new ArrayList<>(items);
        out.sort(Comparator.comparing(key));
        return new Coll<>(out);
    }

    public Coll<T> sortByDesc(Function<? super T, ? extends Comparable> key) {
        return sortBy(key).reverse();
    }

    public Coll<T> sorted(Comparator<? super T> cmp) {
        List<T> out = new ArrayList<>(items);
        out.sort(cmp);
        return new Coll<>(out);
    }

    public Coll<T> unique() {
        return unique(t -> t);
    }

    public Coll<T> unique(Function<? super T, ?> key) {
        Map<Object, T> seen = new LinkedHashMap<>();
        for (T t : items) seen.putIfAbsent(key.apply(t), t);
        return new Coll<>(List.copyOf(seen.values()));
    }

    public Coll<Coll<T>> chunk(int size) {
        if (size <= 0) throw new IllegalArgumentException("chunk size 必须 > 0,收到 " + size);
        List<Coll<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            out.add(new Coll<>(List.copyOf(items.subList(i, Math.min(items.size(), i + size)))));
        }
        return new Coll<>(out);
    }

    // ---------- 分组与提取 ----------

    public <K> Map<K, Coll<T>> groupBy(Function<? super T, K> key) {
        Map<K, List<T>> tmp = new LinkedHashMap<>();
        for (T t : items) tmp.computeIfAbsent(key.apply(t), k -> new ArrayList<>()).add(t);
        Map<K, Coll<T>> out = new LinkedHashMap<>();
        tmp.forEach((k, v) -> out.put(k, new Coll<>(v)));
        return out;
    }

    public <K> Map<K, T> keyBy(Function<? super T, K> key) {
        Map<K, T> out = new LinkedHashMap<>();
        for (T t : items) out.put(key.apply(t), t);
        return out;
    }

    public <R> Coll<R> pluck(Function<? super T, R> f) {
        return map(f);
    }

    // ---------- 取值 ----------

    public Optional<T> first() {
        return items.isEmpty() ? Optional.empty() : Optional.ofNullable(items.get(0));
    }

    public Optional<T> firstWhere(Predicate<? super T> p) {
        for (T t : items) if (p.test(t)) return Optional.ofNullable(t);
        return Optional.empty();
    }

    public T firstOrFail() {
        return first().orElseThrow(() -> new java.util.NoSuchElementException("集合为空"));
    }

    public Optional<T> last() {
        return items.isEmpty() ? Optional.empty() : Optional.ofNullable(items.get(items.size() - 1));
    }

    public T get(int index) {
        return items.get(index);
    }

    // ---------- 聚合 ----------

    public boolean isEmpty()      { return items.isEmpty(); }
    public boolean isNotEmpty()   { return !items.isEmpty(); }
    public int size()             { return items.size(); }

    public boolean contains(T t)                 { return items.contains(t); }
    public boolean any(Predicate<? super T> p)   { for (T t : items) if (p.test(t)) return true; return false; }
    public boolean all(Predicate<? super T> p)   { for (T t : items) if (!p.test(t)) return false; return true; }
    public boolean none(Predicate<? super T> p)  { return !any(p); }
    public long count(Predicate<? super T> p)    { return items.stream().filter(p).count(); }

    public BigDecimal sum(Function<? super T, BigDecimal> f) {
        BigDecimal acc = BigDecimal.ZERO;
        for (T t : items) {
            BigDecimal v = f.apply(t);
            if (v != null) acc = acc.add(v);
        }
        return acc;
    }

    public long sumLong(Function<? super T, Long> f) {
        long acc = 0;
        for (T t : items) {
            Long v = f.apply(t);
            if (v != null) acc += v;
        }
        return acc;
    }

    public <R extends Comparable<R>> Optional<R> max(Function<? super T, R> f) {
        return items.stream().map(f).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder());
    }

    public <R extends Comparable<R>> Optional<R> min(Function<? super T, R> f) {
        return items.stream().map(f).filter(java.util.Objects::nonNull).min(Comparator.naturalOrder());
    }

    public String join(String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    public String join(String sep, Function<? super T, String> f) {
        return map(f).join(sep);
    }

    // ---------- 链式副作用 ----------

    /** 遍历但返回自身,便于继续链式 */
    public Coll<T> each(Consumer<? super T> c) {
        items.forEach(c);
        return this;
    }

    public Coll<T> eachIndexed(java.util.function.BiConsumer<Integer, ? super T> c) {
        for (int i = 0; i < items.size(); i++) c.accept(i, items.get(i));
        return this;
    }

    /** 对整个集合做副作用(调试、日志),返回自身 */
    public Coll<T> tap(Consumer<Coll<T>> c) {
        c.accept(this);
        return this;
    }

    /** 把整个集合交给一个函数,返回其结果 */
    public <R> R pipe(Function<Coll<T>, R> f) {
        return f.apply(this);
    }

    /** 条件式变换,避免打断链式 */
    public Coll<T> when(boolean condition, Function<Coll<T>, Coll<T>> f) {
        return condition ? f.apply(this) : this;
    }

    public Coll<T> unless(boolean condition, Function<Coll<T>, Coll<T>> f) {
        return when(!condition, f);
    }

    // ---------- 逃生口与互操作 ----------

    public List<T> toList()       { return List.copyOf(items); }
    public List<T> toMutableList() { return new ArrayList<>(items); }
    public Stream<T> stream()     { return items.stream(); }

    @Override public Iterator<T> iterator() { return items.iterator(); }

    public static <T> Coll<T> fromStream(Stream<T> s) {
        return new Coll<>(s.toList());
    }

    public static <T> Coll<T> fromIterator(Iterator<T> it) {
        List<T> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return new Coll<>(out);
    }

    public static <T> Coll<T> fromSpliterator(java.util.Spliterator<T> sp) {
        return fromStream(StreamSupport.stream(sp, false));
    }

    // ---------- Object ----------

    @Override public boolean equals(Object o) {
        return o instanceof Coll<?> c && items.equals(c.items);
    }

    @Override public int hashCode() { return items.hashCode(); }

    @Override public String toString() {
        return "Coll" + items;
    }
}
