package io.jaravel.eloquent.it.models;

import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;
import io.jaravel.eloquent.annotations.MorphTo;

/** 多态:一张图可以挂在 Post 或 User 上 */
@Model(timestamps = false)
@MorphTo(name = "owner", types = {Post.class, User.class}, morphName = "imageable")
public class Image extends ImageBase {
    @Id public Long id;
    public String url;
    public Long imageableId;
    public String imageableType;
}
