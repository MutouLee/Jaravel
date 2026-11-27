package net.dobeen.jaravel.migration.fixtures.m2026_10_29_100000;

import net.dobeen.jaravel.migration.Migration;
import net.dobeen.jaravel.migration.schema.Schema;

public class CreatePosts implements Migration {

    @Override
    public void up() {
        Schema.create("posts", t -> {
            t.id();
            t.string("title", 200);
            t.text("body").nullable();
            t.timestamps();
        });
    }

    @Override
    public void down() {
        Schema.dropIfExists("posts");
    }
}
