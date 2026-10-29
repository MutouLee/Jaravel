package io.jaravel.migration.fixtures.m2026_10_29_130000;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

/** 故意失败:引用不存在的表 */
public class Failing implements Migration {

    @Override
    public void up() {
        Schema.table("no_such_table", t -> t.string("x"));
    }
}
