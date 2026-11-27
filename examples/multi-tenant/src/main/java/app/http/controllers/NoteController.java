package app.http.controllers;

import java.util.Map;

import org.springframework.stereotype.Component;

import app.http.requests.StoreNoteRequest;
import app.http.resources.NoteResource;
import app.models.Note;
import app.tenancy.TenantContext;
import net.dobeen.jaravel.http.Response;

@Component
public class NoteController {

    public Object index() {
        return NoteResource.collection(
                Note.query().orderByKeyDesc().get().toList(),
                NoteResource::new);
    }

    public Response store(StoreNoteRequest request) {
        var tenant = TenantContext.current().orElseThrow();
        Note note = Note.create(Map.of("tenant_id", tenant.id, "body", request.body()));
        return Response.json(new NoteResource(note)).status(201);
    }
}
