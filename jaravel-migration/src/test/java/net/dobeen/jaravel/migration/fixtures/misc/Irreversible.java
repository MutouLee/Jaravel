package net.dobeen.jaravel.migration.fixtures.misc;

import net.dobeen.jaravel.migration.Migration;
import net.dobeen.jaravel.migration.schema.Schema;

/** 明确声明不可回滚 */
public class Irreversible implements Migration {

    @Override
    public void up() {
        Schema.create("irreversible", t -> t.id());
    }

    @Override
    public boolean irreversible() {
        return true;
    }
}
