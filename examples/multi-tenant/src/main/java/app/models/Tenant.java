package app.models;

import java.time.Instant;

import net.dobeen.jaravel.eloquent.annotations.Fillable;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;

@Model
@Fillable({"name", "slug"})
public class Tenant extends TenantBase {

    @Id public Long id;
    public String name;
    public String slug;
    public Instant createdAt;
    public Instant updatedAt;
}
