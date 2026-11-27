package app.http.resources;

import java.util.Map;

import app.models.Note;
import net.dobeen.jaravel.api.JsonResource;
import net.dobeen.jaravel.http.Request;

public class NoteResource extends JsonResource<Note> {

    public NoteResource(Note note) {
        super(note);
    }

    @Override
    public Map<String, Object> toArray(Request request) {
        return map(request)
                .put("id", model.id)
                .put("tenant_id", model.tenantId)
                .put("body", model.body)
                .put("created_at", model.createdAt)
                .build();
    }
}
