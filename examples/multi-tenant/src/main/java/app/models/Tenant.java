package app.models;

import java.time.Instant;

import io.jaravel.eloquent.annotations.Fillable;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

@Model
@Fillable({"name", "slug"})
public class Tenant extends TenantBase {

    @Id public Long id;
    public String name;
    public String slug;
    public Instant createdAt;
    public Instant updatedAt;
}
