package database.seeders;

import io.jaravel.migration.Factory;
import io.jaravel.migration.Seeder;

import app.models.Post;
import app.models.Post_;

public class DatabaseSeeder implements Seeder {

    @Override
    public void run() {
        Factory.of(Post_.META, (f, p) -> {
            p.title = f.sentence();
            p.body = f.paragraph();
            p.published = true;
        }).count(5).seed(42).create();
    }
}
