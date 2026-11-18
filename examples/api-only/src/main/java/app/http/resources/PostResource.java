package app.http.resources;

import java.util.Map;

import app.models.Post;
import io.jaravel.api.JsonResource;
import io.jaravel.http.Request;

public class PostResource extends JsonResource<Post> {

    public PostResource(Post post) {
        super(post);
    }

    @Override
    public Map<String, Object> toArray(Request request) {
        return map(request)
                .put("id", model.id)
                .put("title", model.title)
                .put("body", model.body)
                .put("published", model.published)
                .put("created_at", model.createdAt)
                .build();
    }
}
