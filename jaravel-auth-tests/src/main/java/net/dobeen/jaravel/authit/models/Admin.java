package net.dobeen.jaravel.authit.models;

import net.dobeen.jaravel.auth.AuthModel;
import net.dobeen.jaravel.eloquent.annotations.Hidden;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;

/** 第二个 guard 用的模型;没有 remember_token 列 —— 刻意测「不支持 remember me」 */
@Model(timestamps = false)
@AuthModel(password = "password", rememberToken = "")
public class Admin extends AdminBase {

    @Id public Long id;
    public String email;
    @Hidden public String password;
}
