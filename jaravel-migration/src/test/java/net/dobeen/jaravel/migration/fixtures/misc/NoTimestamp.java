package net.dobeen.jaravel.migration.fixtures.misc;

import net.dobeen.jaravel.migration.Migration;
import net.dobeen.jaravel.migration.schema.Schema;

/** 包名里没有时间戳 —— 应触发顺序不确定的警告 */
public class NoTimestamp implements Migration {

    @Override
    public void up() {
        Schema.create("no_timestamp", t -> t.id());
    }

    @Override
    public void down() {
        Schema.dropIfExists("no_timestamp");
    }
}
