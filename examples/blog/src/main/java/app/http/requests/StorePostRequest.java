package app.http.requests;

import java.util.List;
import java.util.Map;

import io.jaravel.support.Str;
import io.jaravel.validation.FormRequest;

public class StorePostRequest extends FormRequest {

    @Override
    public Map<String, String> rules() {
        return Map.of(
                "title",     "required|string|max:200",
                "slug",      "required|string|unique:posts,slug",
                "body",      "required|string|min:10",
                "author_id", "required|integer|exists:authors,id",
                "published", "nullable|boolean",
                "tags",      "nullable|array|max:5",
                "tags.*",    "integer|exists:tags,id");
    }

    @Override
    public Map<String, String> messages() {
        return Map.of("slug.unique", "该 slug 已被占用,换一个");
    }

    @Override
    public Map<String, String> attributes() {
        return Map.of("title", "标题", "body", "正文", "author_id", "作者");
    }

    @Override
    protected void prepareForValidation() {
        String slug = Str.slug(input("title", ""));
        merge("slug", slug.isEmpty() ? "post-" + input("title", "").hashCode() : slug);
    }

    public Map<String, Object> attributes_() {
        return validated().only("title", "slug", "body", "author_id", "published");
    }

    public List<Long> tagIds() {
        return validated().longs("tags");
    }
}
