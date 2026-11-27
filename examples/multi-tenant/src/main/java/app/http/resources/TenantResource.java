package app.http.resources;

import java.util.Map;

import app.models.Tenant;
import net.dobeen.jaravel.api.JsonResource;
import net.dobeen.jaravel.http.Request;

public class TenantResource extends JsonResource<Tenant> {

    public TenantResource(Tenant tenant) {
        super(tenant);
    }

    @Override
    public Map<String, Object> toArray(Request request) {
        return map(request)
                .put("id", model.id)
                .put("name", model.name)
                .put("slug", model.slug)
                .build();
    }
}
