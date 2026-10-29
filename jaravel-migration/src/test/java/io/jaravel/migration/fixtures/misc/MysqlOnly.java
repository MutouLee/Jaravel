package io.jaravel.migration.fixtures.misc;

import java.util.List;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

/** 只在 MySQL 上跑 */
public class MysqlOnly implements Migration {

    @Override
    public void up() {
        Schema.create("mysql_only", t -> t.id());
    }

    @Override
    public void down() {
        Schema.dropIfExists("mysql_only");
    }

    @Override
    public List<String> onlyOnDialects() {
        return List.of("mysql");
    }
}
