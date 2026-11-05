package app.http.resources;

import java.util.Map;

import app.models.Author;
import io.jaravel.api.JsonResource;
import io.jaravel.http.Request;

/** 作者 —— 嵌在文章里,所以不带 data 包裹(包裹只在最外层加一次) */
public class AuthorResource extends JsonResource<Author> {

    public AuthorResource(Author author) {
        super(author);
    }

    @Override
    public Map<String, Object> toArray(Request request) {
        return map(request).put("id", model.id).put("name", model.name).build();
    }
}
