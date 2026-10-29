package io.jaravel.authit.models;

import io.jaravel.auth.AuthModel;
import io.jaravel.eloquent.annotations.Hidden;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

@Model
@AuthModel(password = "password", rememberToken = "remember_token")
public class User extends UserBase {

    @Id public Long id;
    public String email;
    @Hidden public String password;
    @Hidden public String rememberToken;
    public String status;
    public java.time.Instant createdAt;
    public java.time.Instant updatedAt;
}
