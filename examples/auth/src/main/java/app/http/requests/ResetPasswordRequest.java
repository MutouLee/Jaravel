package app.http.requests;

import java.util.Map;

import io.jaravel.validation.FormRequest;

public class ResetPasswordRequest extends FormRequest {

    @Override
    public Map<String, String> rules() {
        return Map.of(
                "email", "required|email",
                "token", "required|string",
                "password", "required|string|min:8|confirmed");
    }

    public Map<String, Object> credentials() {
        return Map.of(
                "email", validated().string("email"),
                "token", validated().string("token"),
                "password", validated().string("password"));
    }
}
