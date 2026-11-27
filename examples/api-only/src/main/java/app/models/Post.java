package app.models;

import java.time.Instant;

import net.dobeen.jaravel.eloquent.annotations.Fillable;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;

@Model
@Fillable({"title", "body", "published"})
public class Post extends PostBase {

    @Id public Long id;
    public String title;
    public String body;
    public boolean published;
    public Instant createdAt;
    public Instant updatedAt;
}
