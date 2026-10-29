package io.jaravel.migration.fixtures.m2026_10_29_110000;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

public class AddSlug implements Migration {

    @Override
    public void up() {
        Schema.table("posts", t -> t.string("slug").nullable());
    }

    @Override
    public void down() {
        Schema.table("posts", t -> t.dropColumn("slug"));
    }
}
