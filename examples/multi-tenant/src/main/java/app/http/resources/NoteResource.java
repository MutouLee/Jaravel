package app.http.resources;

import java.util.Map;

import app.models.Note;
import io.jaravel.api.JsonResource;
import io.jaravel.http.Request;

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
