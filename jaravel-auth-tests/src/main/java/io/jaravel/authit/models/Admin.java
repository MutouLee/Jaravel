package io.jaravel.authit.models;

import io.jaravel.auth.AuthModel;
import io.jaravel.eloquent.annotations.Hidden;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

/** 第二个 guard 用的模型;没有 remember_token 列 —— 刻意测「不支持 remember me」 */
@Model(timestamps = false)
@AuthModel(password = "password", rememberToken = "")
public class Admin extends AdminBase {

    @Id public Long id;
    public String email;
    @Hidden public String password;
}
