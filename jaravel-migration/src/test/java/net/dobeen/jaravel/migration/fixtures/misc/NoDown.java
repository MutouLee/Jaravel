package net.dobeen.jaravel.migration.fixtures.misc;

import net.dobeen.jaravel.migration.Migration;
import net.dobeen.jaravel.migration.schema.Schema;

/** 没实现 down() —— 回滚时应报错 */
public class NoDown implements Migration {

    @Override
    public void up() {
        Schema.create("no_down", t -> t.id());
    }
}
