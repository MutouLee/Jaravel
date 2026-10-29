package io.jaravel.authit.models;

import io.jaravel.auth.AuthModel;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

/** 无密码主体(纯 Token / OAuth)—— 测 @AuthModel(password = "") */
@Model(timestamps = false)
@AuthModel(password = "", rememberToken = "")
public class TokenOnly extends TokenOnlyBase {

    @Id public Long id;
    public String name;
}
