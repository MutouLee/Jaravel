package database.seeders;

import java.util.Map;

import app.models.Note;
import app.models.Tenant;
import io.jaravel.migration.Seeder;

public class DatabaseSeeder implements Seeder {

    @Override
    public void run() {
        Tenant alpha = Tenant.create(Map.of("name", "Alpha 团队", "slug", "alpha"));
        Tenant beta = Tenant.create(Map.of("name", "Beta 团队", "slug", "beta"));

        Note.create(Map.of("tenant_id", alpha.id, "body", "Alpha 的第一条笔记"));
        Note.create(Map.of("tenant_id", beta.id, "body", "Beta 的第一条笔记"));
    }
}
