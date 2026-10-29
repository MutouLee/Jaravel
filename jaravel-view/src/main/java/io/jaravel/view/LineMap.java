package io.jaravel.view;

import java.util.ArrayList;
import java.util.List;

/**
 * jte 行号 → jblade 行号的映射。
 *
 * <p><b>为什么需要:</b>jblade 的编译链是 {@code .jblade.html → .jte → .java → .class}。
 * jte 已经能把 Java 错误映射回 {@code .jte} 行号(V3 实测确认),
 * 剩下 {@code .jte → .jblade.html} 这一跳由本类负责。
 *
 * <p>转写器**尽量保持 1:1 行对齐**(大多数 Blade 指令可在同一行转换完),
 * 不得已时记录显式映射。这让 {@code docs/04} §2.4 列为「头号质量要求」的
 * 错误定位得以成立。
 */
public final class LineMap {

    /** 下标 = jte 行号 - 1,值 = jblade 行号(1 起);0 表示生成的辅助行,无对应 */
    private final int[] mapping;
    private final String sourceName;

    private LineMap(int[] mapping, String sourceName) {
        this.mapping = mapping;
        this.sourceName = sourceName;
    }

    public static Builder builder(String sourceName) {
        return new Builder(sourceName);
    }

    /** jte 行号 → jblade 行号。超出范围或无对应时返回 0。 */
    public int toSourceLine(int jteLine) {
        if (jteLine < 1 || jteLine > mapping.length) return 0;
        int src = mapping[jteLine - 1];
        if (src > 0) return src;

        // 生成的辅助行:回退到最近的有映射的上一行,给出「大致位置」
        for (int i = jteLine - 2; i >= 0; i--) {
            if (mapping[i] > 0) return mapping[i];
        }
        return 0;
    }

    public String sourceName() { return sourceName; }

    public int jteLineCount() { return mapping.length; }

    /** 是否严格 1:1(诊断与测试用) */
    public boolean isIdentity() {
        for (int i = 0; i < mapping.length; i++) {
            if (mapping[i] != i + 1) return false;
        }
        return true;
    }

    public static final class Builder {
        private final List<Integer> lines = new ArrayList<>();
        private final String sourceName;

        private Builder(String sourceName) {
            this.sourceName = sourceName;
        }

        /** 记录一条 jte 行,对应 jblade 的第 sourceLine 行 */
        public Builder emit(int sourceLine) {
            lines.add(sourceLine);
            return this;
        }

        /** 记录一条生成的辅助行(无 jblade 对应) */
        public Builder emitGenerated() {
            lines.add(0);
            return this;
        }

        public LineMap build() {
            int[] arr = new int[lines.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = lines.get(i);
            return new LineMap(arr, sourceName);
        }
    }
}
