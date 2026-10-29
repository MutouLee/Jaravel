package app.http.controllers;

import org.springframework.stereotype.Component;

import app.http.requests.StoreCommentRequest;
import app.models.Post;
import io.jaravel.http.Redirect;
import io.jaravel.http.Response;

@Component
public class CommentController {

    /** POST /posts/{id}/comments */
    public Response store(Long id, StoreCommentRequest request) {
        Post post = Post.findOrFail(id);
        post.comments().create(request.attributes_());
        return Redirect.to("/posts/" + post.slug).with("status", "评论已提交,待审核");
    }
}
