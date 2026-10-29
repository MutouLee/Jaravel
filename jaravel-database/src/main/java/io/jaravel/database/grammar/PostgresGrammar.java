package io.jaravel.database.grammar;

/** PostgreSQL 方言。标识符用双引号,原生支持 ILIKE。 */
public class PostgresGrammar extends Grammar {

    @Override public String name() { return "postgres"; }
    @Override protected char openQuote()  { return '"'; }
    @Override protected char closeQuote() { return '"'; }

    /** 唯一原生支持 ILIKE 的方言 */
    @Override protected boolean supportsIlike() { return true; }

    /**
     * PostgreSQL 的 JSON 路径:
     * {@code meta->tags}  → {@code "meta"->'tags'}(返回 json)
     * {@code meta->>tags} → {@code "meta"->>'tags'}(返回 text)
     */
    @Override
    protected String wrapJsonPath(String id) {
        boolean text = id.contains("->>");
        String[] parts = id.split("->>?", -1);
        var sb = new StringBuilder(quote(parts[0].trim()));
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i].trim().replace("'", "");
            boolean last = i == parts.length - 1;
            sb.append(last && text ? "->>" : "->");
            sb.append(seg.matches("[0-9]+") ? seg : "'" + seg + "'");
        }
        return sb.toString();
    }
}
