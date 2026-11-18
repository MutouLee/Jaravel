package app.http.controllers;

import org.springframework.stereotype.Component;

import app.http.resources.TenantResource;
import app.models.Tenant;

@Component
public class TenantController {

    public Object index() {
        return TenantResource.collection(Tenant.all().toList(), TenantResource::new);
    }
}
