package app.http.requests;

import java.util.Map;

import net.dobeen.jaravel.validation.FormRequest;

public class StoreNoteRequest extends FormRequest {

    @Override
    public Map<String, String> rules() {
        return Map.of("body", "required|string|max:2000");
    }

    public String body() {
        return validated().string("body");
    }
}
