package net.dobeen.jaravel.eloquent.it.models;

import net.dobeen.jaravel.eloquent.annotations.BelongsTo;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;

/** HasOne 的对侧 */
@Model(timestamps = false)
@BelongsTo(name = "user", target = User.class)
public class Profile extends ProfileBase {
    @Id public Long id;
    public Long userId;
    public String bio;
}
