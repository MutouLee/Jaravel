package database.factories;

import app.models.Comment;
import io.jaravel.support.Faker;

/** Comment 的工厂(docs/14 §3)。外键由 {@code Post.factory().hasComments(3)} 填。 */
public class CommentFactory extends CommentFactoryBase {

    @Override
    protected void definition(Faker f, Comment comment) {
        comment.authorName = f.word() + "读者";
        comment.body = f.sentence();
        comment.approved = true;
    }

    public CommentFactory pending() {
        return state(c -> c.approved = false);
    }
}
