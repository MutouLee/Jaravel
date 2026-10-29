package io.jaravel.eloquent.cast;

import java.util.ArrayList;
import java.util.List;

import io.jaravel.eloquent.meta.Cast;

/**
 * {@code List<String>} ↔ 逗号分隔文本。
 *
 * <p>比 JSON 轻,适合标签这类简单字符串列表。
 *
 * <p>元素里含逗号或反斜杠时会转义。手写解析而不用正则:
 * 「带转义的分隔符」用正则表达容易出错(负向后顾在连续转义时失效),
 * 而逐字符扫描一眼就能看出对不对。
 */
public class AsList implements Cast<List<String>, String> {

    private static final char SEP = ',';
    private static final char ESC = '\\';

    @Override
    public List<String> fromDatabase(String raw) {
        if (raw == null || raw.isEmpty()) return List.of();

        var out = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ESC && i + 1 < raw.length()) {
                current.append(raw.charAt(++i));      // 转义的下一个字符原样取
            } else if (c == SEP) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return List.copyOf(out);
    }

    @Override
    public String toDatabase(List<String> value) {
        if (value == null || value.isEmpty()) return null;

        var sb = new StringBuilder();
        for (int i = 0; i < value.size(); i++) {
            if (i > 0) sb.append(SEP);
            String item = value.get(i);
            if (item == null) continue;
            for (int j = 0; j < item.length(); j++) {
                char c = item.charAt(j);
                if (c == SEP || c == ESC) sb.append(ESC);
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
