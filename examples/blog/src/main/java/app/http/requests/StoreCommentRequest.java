package app.http.requests;

import java.util.Map;

import io.jaravel.validation.FormRequest;

public class StoreCommentRequest extends FormRequest {

    @Override
    public Map<String, String> rules() {
        return Map.of(
                "author_name", "required|string|max:80",
                "body",        "required|string|min:2|max:1000");
    }

    public Map<String, Object> attributes_() {
        return validated().only("author_name", "body");
    }
}
