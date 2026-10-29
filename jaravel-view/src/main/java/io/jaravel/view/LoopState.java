package io.jaravel.view;

import java.util.Collection;

/**
 * {@code @foreach} 内的 {@code loop} 变量。
 *
 * <p>jte 的 {@code @for} 只是 Java for 循环,没有循环元信息。转写器为每个
 * {@code @foreach} 生成一个 {@code LoopState} 局部变量,并把模板里的
 * {@code loop.index} 重写为 {@code __loopN.index()}。
 *
 * <p>**非反射**:转写器在编译期就把 {@code loop.xxx} 换成了真实方法调用。
 *
 * <p>嵌套循环通过 {@link #parent} 串成链,支持 {@code loop.parent.index}。
 */
public final class LoopState {

    private final int count;
    private final LoopState parent;
    private int index = -1;

    private LoopState(int count, LoopState parent) {
        this.count = count;
        this.parent = parent;
    }

    /** 已知大小的集合 */
    public static LoopState of(Collection<?> items, LoopState parent) {
        return new LoopState(items == null ? 0 : items.size(), parent);
    }

    public static LoopState of(Collection<?> items) {
        return of(items, null);
    }

    public static LoopState of(Object[] items, LoopState parent) {
        return new LoopState(items == null ? 0 : items.length, parent);
    }

    public static LoopState of(Object[] items) {
        return of(items, null);
    }

    /** 大小未知的 Iterable(如游标)。{@code count}/{@code last}/{@code remaining} 不可用。 */
    public static LoopState unsized(LoopState parent) {
        return new LoopState(-1, parent);
    }

    /**
     * 转写器生成的入口:直接给长度,不物化集合。
     * 长度由 {@link Jb#sizeOf} 求得;拿不到(纯 Iterable)时传 -1。
     */
    public static LoopState ofSize(int size, LoopState parent) {
        return new LoopState(size, parent);
    }

    /** 由生成代码在每轮循环体开头调用 */
    public void advance() {
        index++;
    }

    /** 从 0 开始 */
    public int index() { return index; }

    /** 从 1 开始 */
    public int iteration() { return index + 1; }

    public boolean first() { return index == 0; }

    public boolean last() {
        requireSized("loop.last");
        return index == count - 1;
    }

    public int count() {
        requireSized("loop.count");
        return count;
    }

    public int remaining() {
        requireSized("loop.remaining");
        return count - index - 1;
    }

    public boolean even() { return iteration() % 2 == 0; }
    public boolean odd()  { return iteration() % 2 == 1; }

    /** 嵌套深度,最外层为 1 */
    public int depth() {
        int d = 1;
        for (LoopState p = parent; p != null; p = p.parent) d++;
        return d;
    }

    public LoopState parent() {
        if (parent == null) {
            throw new IllegalStateException(
                    "loop.parent 在最外层循环中不可用(当前 loop.depth() == 1)。"
                  + "\n  修复:先用 @if (loop.depth > 1) 判断。");
        }
        return parent;
    }

    private void requireSized(String what) {
        if (count < 0) {
            throw new IllegalStateException(
                    what + " 在大小未知的序列上不可用(遍历的是 Iterable 而非 Collection)。"
                  + "\n  修复:先 .toList() 收集,或改用 loop.index / loop.first。");
        }
    }

    @Override public String toString() {
        return "loop(index=" + index + ", count=" + (count < 0 ? "?" : count) + ", depth=" + depth() + ")";
    }
}
