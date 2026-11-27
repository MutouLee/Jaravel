package net.dobeen.jaravel.migration.fixtures.m2026_10_29_120000;

import net.dobeen.jaravel.migration.Migration;
import net.dobeen.jaravel.migration.schema.Schema;

public class CreateTags implements Migration {

    @Override
    public void up() {
        Schema.create("tags", t -> {
            t.id();
            t.string("name").unique();
        });
    }

    @Override
    public void down() {
        Schema.dropIfExists("tags");
    }
}
