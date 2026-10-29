package app.http.requests;

import java.util.Map;

import io.jaravel.validation.FormRequest;

public class RegisterRequest extends FormRequest {

    @Override
    public Map<String, String> rules() {
        return Map.of(
                "name", "required|string|max:80",
                "email", "required|email|unique:users,email",
                // confirmed 要求同时有 password_confirmation 字段且相等。
                // min:8 是下限而不是建议 —— 更长的要求交给业务,
                // 但框架不该让「12345」通过
                "password", "required|string|min:8|confirmed");
    }

    @Override
    public Map<String, String> attributes() {
        return Map.of("name", "姓名", "email", "邮箱", "password", "密码");
    }

    @Override
    public Map<String, String> messages() {
        return Map.of("email.unique", "该邮箱已注册,直接登录或重置密码");
    }

    public String name()     { return validated().string("name"); }
    public String email()    { return validated().string("email"); }
    public String password() { return validated().string("password"); }
}
