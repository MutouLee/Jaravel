package io.jaravel.database.grammar;

/** MySQL / MariaDB 方言。标识符用反引号。 */
public class MySqlGrammar extends Grammar {

    @Override public String name() { return "mysql"; }
    @Override protected char openQuote()  { return '`'; }
    @Override protected char closeQuote() { return '`'; }

    /**
     * MySQL 的 JSON 路径:{@code meta->tags} → {@code json_unquote(json_extract(`meta`, '$.tags'))}
     */
    @Override
    protected String wrapJsonPath(String id) {
        boolean unquote = id.contains("->>");
        String[] parts = id.split("->>?", -1);
        String column = quote(parts[0].trim());
        var path = new StringBuilder("$");
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i].trim().replace("'", "");
            path.append(seg.matches("[0-9]+") ? "[" + seg + "]" : "." + seg);
        }
        String extract = "json_extract(" + column + ", '" + path + "')";
        return unquote ? "json_unquote(" + extract + ")" : extract;
    }
}
