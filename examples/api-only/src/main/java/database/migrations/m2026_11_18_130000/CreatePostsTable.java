package database.migrations.m2026_11_18_130000;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

public class CreatePostsTable implements Migration {

    @Override
    public void up() {
        Schema.create("posts", t -> {
            t.id();
            t.string("title", 200);
            t.text("body");
            t.boolean_("published").default_(false);
            t.timestamps();
        });
    }

    @Override
    public void down() {
        Schema.dropIfExists("posts");
    }
}
