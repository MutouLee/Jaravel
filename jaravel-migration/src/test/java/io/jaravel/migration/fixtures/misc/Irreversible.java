package io.jaravel.migration.fixtures.misc;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

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
