package database.migrations.m2026_10_29_000200;

import net.dobeen.jaravel.migration.Migration;
import net.dobeen.jaravel.migration.schema.Schema;

public class CreateTagTables implements Migration {

    @Override
    public void up() {
        Schema.create("tags", t -> {
            t.id();
            t.string("name", 40);
            t.string("slug").unique();
        });

        Schema.create("post_tag", t -> {
            t.foreignId("post_id").constrained("posts").cascadeOnDelete();
            t.foreignId("tag_id").constrained("tags").cascadeOnDelete();
            t.primary(java.util.List.of("post_id", "tag_id"));
        });
    }

    @Override
    public void down() {
        Schema.dropIfExists("post_tag");
        Schema.dropIfExists("tags");
    }
}
