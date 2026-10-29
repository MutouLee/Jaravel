package io.jaravel.support;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 字符串工具。Laravel {@code Str} 的等价物。
 *
 * <p>既提供静态方法({@code Str.snake("HelloWorld")}),也提供链式包装
 * ({@code Str.of("hello world").title().limit(10)})。
 */
public final class Str {

    private Str() {}

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{Alnum}]+");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /**
     * -o 结尾却加 -es 的词。其余 -o 结尾一律加 -s。
     * 这条规则在英语里是不规则的:hero→heroes 但 photo→photos,两者 o 前都是辅音,
     * 无法用规则区分,只能用词表(Rails / Laravel 同样如此)。
     */
    private static final java.util.Set<String> O_TO_OES = java.util.Set.of(
            "hero", "potato", "tomato", "echo", "volcano", "torpedo", "veto",
            "mosquito", "buffalo", "domino", "embargo", "halo", "mango",
            "motto", "tornado", "zero", "cargo");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    // ---------- 命名风格转换 ----------

    /** HelloWorld / helloWorld / hello-world → hello_world */
    public static String snake(String s) {
        if (isBlank(s)) return s == null ? "" : s;
        String withBoundaries = CAMEL_BOUNDARY.matcher(s).replaceAll("_");
        return withBoundaries.replace('-', '_').replace(' ', '_')
                .replaceAll("_+", "_")
                .toLowerCase(Locale.ROOT);
    }

    /** hello_world → helloWorld */
    public static String camel(String s) {
        String studly = studly(s);
        if (studly.isEmpty()) return studly;
        return Character.toLowerCase(studly.charAt(0)) + studly.substring(1);
    }

    /** hello_world → HelloWorld */
    public static String studly(String s) {
        if (isBlank(s)) return s == null ? "" : s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean upper = true;
        for (char c : s.toCharArray()) {
            if (c == '_' || c == '-' || c == ' ') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    /** HelloWorld → hello-world */
    public static String kebab(String s) {
        return snake(s).replace('_', '-');
    }

    /** hello_world → Hello World */
    public static String title(String s) {
        if (isBlank(s)) return s == null ? "" : s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean upper = true;
        for (char c : s.replace('_', ' ').replace('-', ' ').toCharArray()) {
            if (c == ' ') { upper = true; sb.append(c); continue; }
            sb.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = false;
        }
        return sb.toString();
    }

    public static String ucfirst(String s) {
        if (isBlank(s)) return s == null ? "" : s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String lcfirst(String s) {
        if (isBlank(s)) return s == null ? "" : s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** URL 友好的 slug。处理变音符号与非字母数字字符。 */
    public static String slug(String s) {
        return slug(s, "-");
    }

    public static String slug(String s, String separator) {
        if (s == null) return "";
        String normalized = Normalizer.normalize(s, Normalizer.Form.NFD);
        String ascii = DIACRITICS.matcher(normalized).replaceAll("");
        String replaced = NON_ALNUM.matcher(ascii).replaceAll(separator);
        // 去掉首尾分隔符
        String sep = Pattern.quote(separator);
        return replaced.replaceAll("^" + sep + "+|" + sep + "+$", "").toLowerCase(Locale.ROOT);
    }

    // ---------- 单复数(英文,覆盖常见规则) ----------

    public static String plural(String word) {
        if (isBlank(word)) return word == null ? "" : word;
        String lower = word.toLowerCase(Locale.ROOT);

        // 不规则
        String irregular = switch (lower) {
            case "person" -> "people";   case "man"   -> "men";
            case "child"  -> "children"; case "woman" -> "women";
            case "tooth"  -> "teeth";    case "foot"  -> "feet";
            case "mouse"  -> "mice";     case "goose" -> "geese";
            default -> null;
        };
        if (irregular != null) return matchCase(word, irregular);

        // 不变
        if (lower.equals("sheep") || lower.equals("series") || lower.equals("species")
                || lower.equals("deer") || lower.equals("fish") || lower.equals("data")) {
            return word;
        }

        if (lower.endsWith("y") && word.length() > 1 && !isVowel(lower.charAt(lower.length() - 2))) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z")
                || lower.endsWith("ch") || lower.endsWith("sh")) {
            return word + "es";
        }
        if (lower.endsWith("f")) return word.substring(0, word.length() - 1) + "ves";
        if (lower.endsWith("fe")) return word.substring(0, word.length() - 2) + "ves";
        // -o 结尾是不规则的:hero→heroes 但 photo→photos,两者 o 前都是辅音。
        // 没有可用的规则,只能用词表(Rails / Laravel 同样如此)。
        if (O_TO_OES.contains(lower)) return word + "es";
        return word + "s";
    }

    public static String singular(String word) {
        if (isBlank(word)) return word == null ? "" : word;
        String lower = word.toLowerCase(Locale.ROOT);

        String irregular = switch (lower) {
            case "people"   -> "person"; case "men"   -> "man";
            case "children" -> "child";  case "women" -> "woman";
            case "teeth"    -> "tooth";  case "feet"  -> "foot";
            case "mice"     -> "mouse";  case "geese" -> "goose";
            default -> null;
        };
        if (irregular != null) return matchCase(word, irregular);

        if (lower.endsWith("ies") && word.length() > 3) return word.substring(0, word.length() - 3) + "y";
        if (lower.endsWith("ves")) return word.substring(0, word.length() - 3) + "f";
        if (lower.endsWith("ches") || lower.endsWith("shes") || lower.endsWith("xes") || lower.endsWith("zes")) {
            return word.substring(0, word.length() - 2);
        }
        if (lower.endsWith("ses")) return word.substring(0, word.length() - 2);
        if (lower.endsWith("s") && !lower.endsWith("ss")) return word.substring(0, word.length() - 1);
        return word;
    }

    private static boolean isVowel(char c) {
        return "aeiou".indexOf(Character.toLowerCase(c)) >= 0;
    }

    private static String matchCase(String original, String replacement) {
        if (!original.isEmpty() && Character.isUpperCase(original.charAt(0))) {
            return ucfirst(replacement);
        }
        return replacement;
    }

    // ---------- 判断与裁剪 ----------

    public static boolean isBlank(String s)    { return s == null || s.isBlank(); }
    public static boolean isNotBlank(String s) { return !isBlank(s); }

    public static boolean startsWith(String s, String... prefixes) {
        if (s == null) return false;
        for (String p : prefixes) if (s.startsWith(p)) return true;
        return false;
    }

    public static boolean endsWith(String s, String... suffixes) {
        if (s == null) return false;
        for (String p : suffixes) if (s.endsWith(p)) return true;
        return false;
    }

    public static boolean contains(String s, String... needles) {
        if (s == null) return false;
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    /** 通配匹配,{@code *} 匹配任意字符。用于路径/中间件排除列表。 */
    public static boolean is(String pattern, String value) {
        if (pattern == null || value == null) return false;
        if (pattern.equals(value)) return true;
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            if (c == '*') regex.append(".*");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        regex.append('$');
        return value.matches(regex.toString());
    }

    public static String limit(String s, int max) {
        return limit(s, max, "...");
    }

    public static String limit(String s, int max, String end) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + end;
    }

    /** 按词截断,不会把单词切断 */
    public static String words(String s, int count, String end) {
        if (s == null) return "";
        String[] parts = s.trim().split("\\s+");
        if (parts.length <= count) return s;
        return String.join(" ", java.util.Arrays.copyOf(parts, count)) + end;
    }

    public static String before(String s, String search) {
        if (s == null) return "";
        int i = s.indexOf(search);
        return i < 0 ? s : s.substring(0, i);
    }

    public static String after(String s, String search) {
        if (s == null) return "";
        int i = s.indexOf(search);
        return i < 0 ? s : s.substring(i + search.length());
    }

    public static String beforeLast(String s, String search) {
        if (s == null) return "";
        int i = s.lastIndexOf(search);
        return i < 0 ? s : s.substring(0, i);
    }

    public static String afterLast(String s, String search) {
        if (s == null) return "";
        int i = s.lastIndexOf(search);
        return i < 0 ? s : s.substring(i + search.length());
    }

    /** 掩码:13800138000 → 138****8000 */
    public static String mask(String s, char mask, int keepStart, int keepEnd) {
        if (s == null) return "";
        if (s.length() <= keepStart + keepEnd) return s;
        return s.substring(0, keepStart)
                + String.valueOf(mask).repeat(s.length() - keepStart - keepEnd)
                + s.substring(s.length() - keepEnd);
    }

    public static String padLeft(String s, int length, char pad) {
        if (s == null) s = "";
        if (s.length() >= length) return s;
        return String.valueOf(pad).repeat(length - s.length()) + s;
    }

    public static String padRight(String s, int length, char pad) {
        if (s == null) s = "";
        if (s.length() >= length) return s;
        return s + String.valueOf(pad).repeat(length - s.length());
    }

    /** 确保以 prefix 开头(不重复添加) */
    public static String start(String s, String prefix) {
        if (s == null) return prefix;
        return s.startsWith(prefix) ? s : prefix + s;
    }

    /** 确保以 suffix 结尾(不重复添加) */
    public static String finish(String s, String suffix) {
        if (s == null) return suffix;
        return s.endsWith(suffix) ? s : s + suffix;
    }

    // ---------- 生成 ----------

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String random(int length) {
        final String pool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        var rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(pool.charAt(rnd.nextInt(pool.length())));
        return sb.toString();
    }

    // ---------- 链式包装 ----------

    public static Fluent of(String value) {
        return new Fluent(value == null ? "" : value);
    }

    /** 链式包装器。{@code Str.of("hello world").title().limit(8).toString()} */
    public static final class Fluent {
        private final String value;

        private Fluent(String value) { this.value = value; }

        public Fluent snake()               { return new Fluent(Str.snake(value)); }
        public Fluent camel()               { return new Fluent(Str.camel(value)); }
        public Fluent studly()              { return new Fluent(Str.studly(value)); }
        public Fluent kebab()               { return new Fluent(Str.kebab(value)); }
        public Fluent title()               { return new Fluent(Str.title(value)); }
        public Fluent slug()                { return new Fluent(Str.slug(value)); }
        public Fluent ucfirst()             { return new Fluent(Str.ucfirst(value)); }
        public Fluent lcfirst()             { return new Fluent(Str.lcfirst(value)); }
        public Fluent plural()              { return new Fluent(Str.plural(value)); }
        public Fluent singular()            { return new Fluent(Str.singular(value)); }
        public Fluent lower()               { return new Fluent(value.toLowerCase(Locale.ROOT)); }
        public Fluent upper()               { return new Fluent(value.toUpperCase(Locale.ROOT)); }
        public Fluent trim()                { return new Fluent(value.trim()); }
        public Fluent limit(int max)        { return new Fluent(Str.limit(value, max)); }
        public Fluent limit(int m, String e){ return new Fluent(Str.limit(value, m, e)); }
        public Fluent before(String s)      { return new Fluent(Str.before(value, s)); }
        public Fluent after(String s)       { return new Fluent(Str.after(value, s)); }
        public Fluent replace(String a, String b) { return new Fluent(value.replace(a, b)); }
        public Fluent start(String p)        { return new Fluent(Str.start(value, p)); }
        public Fluent finish(String s)       { return new Fluent(Str.finish(value, s)); }
        public Fluent append(String s)       { return new Fluent(value + s); }
        public Fluent prepend(String s)      { return new Fluent(s + value); }
        public Fluent mask(char m, int a, int b) { return new Fluent(Str.mask(value, m, a, b)); }

        public boolean isBlank()             { return Str.isBlank(value); }
        public boolean isNotBlank()          { return Str.isNotBlank(value); }
        public int length()                  { return value.length(); }
        public boolean is(String pattern)    { return Str.is(pattern, value); }

        /** 条件式变换,避免打断链式 */
        public Fluent when(boolean cond, java.util.function.UnaryOperator<Fluent> f) {
            return cond ? f.apply(this) : this;
        }

        public String get()                  { return value; }
        @Override public String toString()   { return value; }
    }
}
