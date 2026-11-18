package app.http.requests;

import java.util.Map;

import io.jaravel.validation.FormRequest;

public class StorePostRequest extends FormRequest {

    @Override
    public Map<String, String> rules() {
        return Map.of(
                "title", "required|string|max:200",
                "body", "required|string|min:10",
                "published", "nullable|boolean");
    }

    @Override
    public Map<String, String> attributes() {
        return Map.of("title", "标题", "body", "正文");
    }

    public Map<String, Object> attributes_() {
        return validated().only("title", "body", "published");
    }
}
