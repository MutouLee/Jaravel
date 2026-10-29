package io.jaravel.eloquent.it.models;

import io.jaravel.eloquent.annotations.BelongsTo;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

/** HasOne 的对侧 */
@Model(timestamps = false)
@BelongsTo(name = "user", target = User.class)
public class Profile extends ProfileBase {
    @Id public Long id;
    public Long userId;
    public String bio;
}
