package io.jaravel.database.grammar;

import java.util.List;

import io.jaravel.database.query.QueryState;

/** SQLite 方言。标识符用双引号。测试与小项目的默认选择。 */
public class SqliteGrammar extends Grammar {

    @Override public String name() { return "sqlite"; }

    /** SQLite 没有时间类型 —— 见 {@link Grammar#bindsTemporalAsString()} */
    @Override
    public boolean bindsTemporalAsString() {
        return true;
    }

    @Override protected char openQuote()  { return '"'; }
    @Override protected char closeQuote() { return '"'; }

    /** SQLite 没有 TRUNCATE */
    @Override
    public String compileTruncate(QueryState q) {
        return "delete from " + wrap(q.table);
    }

    /** SQLite 的 json_extract 行为与 MySQL 接近 */
    @Override
    protected String wrapJsonPath(String id) {
        String[] parts = id.split("->>?", -1);
        var path = new StringBuilder("$");
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i].trim().replace("'", "");
            path.append(seg.matches("[0-9]+") ? "[" + seg + "]" : "." + seg);
        }
        return "json_extract(" + quote(parts[0].trim()) + ", '" + path + "')";
    }
}
