package io.jaravel.migration.fixtures.misc;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

/** 没实现 down() —— 回滚时应报错 */
public class NoDown implements Migration {

    @Override
    public void up() {
        Schema.create("no_down", t -> t.id());
    }
}
