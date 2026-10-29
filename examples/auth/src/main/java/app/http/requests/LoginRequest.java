package app.http.requests;

import java.util.Map;

import io.jaravel.validation.FormRequest;

public class LoginRequest extends FormRequest {

    @Override
    public Map<String, String> rules() {
        return Map.of(
                "email", "required|email",
                // 登录时**不**校验密码长度/格式:那会让「密码规则变严之前
                // 注册的老用户」登不进来。规则只管新密码的写入口
                "password", "required|string",
                "remember", "nullable|boolean");
    }

    @Override
    public Map<String, String> attributes() {
        return Map.of("email", "邮箱", "password", "密码");
    }

    public String email()     { return validated().string("email"); }
    public String password()  { return validated().string("password"); }
    public boolean remember() { return validated().bool("remember", false); }

    public Map<String, Object> credentials() {
        return Map.of("email", email(), "password", password());
    }
}
