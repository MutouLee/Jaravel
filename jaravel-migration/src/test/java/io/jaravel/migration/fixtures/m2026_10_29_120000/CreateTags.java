package io.jaravel.migration.fixtures.m2026_10_29_120000;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

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
