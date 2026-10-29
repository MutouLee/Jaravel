package database.seeders;

import java.util.List;

import app.models.Author;
import app.models.Author_;
import app.models.Comment;
import app.models.Comment_;
import app.models.Post;
import app.models.Post_;
import app.models.Tag;
import app.models.Tag_;
import io.jaravel.migration.Factory;
import io.jaravel.migration.Seeder;

public class BlogSeeder implements Seeder {

    @Override
    public void run() {
        var authors = Factory.of(Author_.META, (f, a) -> {
            a.name = f.word() + "作者";
            a.email = f.email();
        }).count(3).create();

        var tags = Factory.of(Tag_.META, (f, t) -> {
            t.name = f.word();
            t.slug = f.slug();
        }).count(5).create();

        Factory.of(Post_.META, (f, p) -> {
            p.title = f.sentence();
            p.slug = f.slug();
            p.body = f.paragraph();
            p.authorId = f.pick(authors).id;
            p.published = f.chance(0.8);
            p.views = f.number(0, 500);
        }).count(10).seed(42).afterCreating((post, i) -> {
            post.tags().attach(List.of(tags.get(i % tags.size()).id));
            Factory.of(Comment_.META, (f, c) -> {
                c.postId = post.id;
                c.authorName = f.word() + "读者";
                c.body = f.sentence();
                c.approved = f.chance(0.7);
            }).count(3).create();
        }).create();
    }
}
