package database.migrations.m2026_11_18_131000;

import net.dobeen.jaravel.migration.Migration;
import net.dobeen.jaravel.migration.schema.Schema;

public class CreateTenancyTables implements Migration {

    @Override
    public void up() {
        Schema.create("tenants", t -> {
            t.id();
            t.string("name", 80);
            t.string("slug").unique();
            t.timestamps();
        });

        Schema.create("notes", t -> {
            t.id();
            t.foreignId("tenant_id").constrained("tenants").cascadeOnDelete();
            t.text("body");
            t.timestamps();
            t.index("tenant_id");
        });
    }

    @Override
    public void down() {
        Schema.dropIfExists("notes");
        Schema.dropIfExists("tenants");
    }
}
