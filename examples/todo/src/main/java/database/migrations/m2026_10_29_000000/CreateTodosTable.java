package database.migrations.m2026_10_29_000000;

import net.dobeen.jaravel.migration.Migration;
import net.dobeen.jaravel.migration.schema.Schema;

public class CreateTodosTable implements Migration {

    @Override
    public void up() {
        Schema.create("todos", t -> {
            t.id();
            t.string("title", 100);
            t.boolean_("done").default_(false);
            t.timestamps();
        });
    }

    @Override
    public void down() {
        Schema.dropIfExists("todos");
    }
}
