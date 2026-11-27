package net.dobeen.jaravel.authit.models;

import net.dobeen.jaravel.auth.AuthModel;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;

/** 无密码主体(纯 Token / OAuth)—— 测 @AuthModel(password = "") */
@Model(timestamps = false)
@AuthModel(password = "", rememberToken = "")
public class TokenOnly extends TokenOnlyBase {

    @Id public Long id;
    public String name;
}
