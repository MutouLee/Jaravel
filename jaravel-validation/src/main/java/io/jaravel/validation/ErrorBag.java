package io.jaravel.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 校验错误的集合。按字段分组,保持插入顺序。
 *
 * <p>顺序不是细节:表单上的错误要按字段在页面上出现的顺序显示,
 * 用 HashMap 会让错误位置每次刷新都变。
 */
public final class ErrorBag {

    private final Map<String, List<String>> messages = new LinkedHashMap<>();

    public void add(String field, String message) {
        messages.computeIfAbsent(field, f -> new ArrayList<>()).add(message);
    }

    public boolean has(String field) {
        return messages.containsKey(field) && !messages.get(field).isEmpty();
    }

    /** 任一以该前缀开头的字段有错 —— {@code hasAny("items")} 覆盖 {@code items.2.qty} */
    public boolean hasAny(String prefix) {
        return messages.keySet().stream()
                .anyMatch(f -> f.equals(prefix) || f.startsWith(prefix + "."));
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public boolean isNotEmpty() {
        return !messages.isEmpty();
    }

    /** 第一条错误;没有则返回 null —— 模板里 {@code @error} 用它 */
    public String first(String field) {
        var list = messages.get(field);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    public Optional<String> firstOptional(String field) {
        return Optional.ofNullable(first(field));
    }

    /** 全部字段里的第一条 */
    public String first() {
        return messages.values().stream()
                .flatMap(List::stream)
                .findFirst()
                .orElse(null);
    }

    public List<String> get(String field) {
        return List.copyOf(messages.getOrDefault(field, List.of()));
    }

    public Map<String, List<String>> messages() {
        var copy = new LinkedHashMap<String, List<String>>();
        messages.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return copy;
    }

    /** 扁平化的全部消息 */
    public List<String> all() {
        return messages.values().stream().flatMap(List::stream).toList();
    }

    public List<String> fields() {
        return List.copyOf(messages.keySet());
    }

    public int count() {
        return messages.values().stream().mapToInt(List::size).sum();
    }

    public void merge(ErrorBag other) {
        other.messages.forEach((field, list) -> list.forEach(m -> add(field, m)));
    }

    /**
     * 与 Laravel 完全一致的 JSON 结构。
     *
     * <p>一致不是为了好看 —— 前端的错误处理代码(把 errors 映射到表单字段)
     * 可以直接从 Laravel 项目搬过来,这是迁移成本里实打实的一块。
     */
    public Map<String, Object> toJson(String message) {
        return Map.of("message", message, "errors", messages());
    }

    @Override public String toString() {
        if (messages.isEmpty()) return "ErrorBag(无错误)";
        var sb = new StringBuilder("ErrorBag(" + count() + " 条):");
        messages.forEach((field, list) ->
                list.forEach(m -> sb.append("\n  ").append(field).append(": ").append(m)));
        return sb.toString();
    }
}
