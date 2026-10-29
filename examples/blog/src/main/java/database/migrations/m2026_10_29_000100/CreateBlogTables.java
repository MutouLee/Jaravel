package database.migrations.m2026_10_29_000100;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

public class CreateBlogTables implements Migration {

    @Override
    public void up() {
        Schema.create("authors", t -> {
            t.id();
            t.string("name", 80);
            t.string("email").unique();
            t.string("password").nullable();
            t.timestamps();
        });

        Schema.create("posts", t -> {
            t.id();
            t.string("title", 200);
            t.string("slug").unique();
            t.text("body");
            t.foreignId("author_id").constrained("authors").cascadeOnDelete();
            t.boolean_("published").default_(false);
            t.integer("views").default_(0);
            t.timestamps();
            t.softDeletes();
            t.index("published");
        });

        Schema.create("comments", t -> {
            t.id();
            t.foreignId("post_id").constrained("posts").cascadeOnDelete();
            t.string("author_name", 80);
            t.text("body");
            t.boolean_("approved").default_(false);
            t.timestamps();
        });
    }

    @Override
    public void down() {
        Schema.dropIfExists("comments");
        Schema.dropIfExists("posts");
        Schema.dropIfExists("authors");
    }
}
