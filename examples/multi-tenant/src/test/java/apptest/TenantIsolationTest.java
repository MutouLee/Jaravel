package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import app.models.Note;
import app.models.Tenant;
import io.jaravel.testing.FeatureTest;

class TenantIsolationTest extends FeatureTest {

    private Tenant tenant(String slug) {
        return Tenant.create(Map.of("name", slug + " 团队", "slug", slug));
    }

    @Test
    @DisplayName("两个租户的笔记互相不可见 —— @GlobalScope + TenantContext")
    void notesAreIsolated() {
        Tenant alpha = tenant("alpha");
        Tenant beta = tenant("beta");
        Note.create(Map.of("tenant_id", alpha.id, "body", "alpha note"));
        Note.create(Map.of("tenant_id", beta.id, "body", "beta note"));

        withHeader("X-Tenant", "alpha").getJson("/api/notes")
                .assertOk()
                .assertJsonPath("data.0.body", "alpha note")
                .assertJsonCount(1, "data");

        withHeader("X-Tenant", "beta").getJson("/api/notes")
                .assertOk()
                .assertJsonPath("data.0.body", "beta note")
                .assertJsonCount(1, "data");
    }

    @Test
    @DisplayName("创建自动带上当前租户")
    void createUsesCurrentTenant() {
        tenant("alpha");

        withHeader("X-Tenant", "alpha")
                .postJson("/api/notes", Map.of("body", "新笔记"))
                .assertStatus(201)
                .assertJsonPath("data.body", "新笔记");

        withHeader("X-Tenant", "alpha").getJson("/api/notes")
                .assertOk()
                .assertJsonPath("data.0.body", "新笔记");
    }

    @Test
    @DisplayName("缺租户头 400;未知租户 404")
    void missingTenantIsRejected() {
        tenant("alpha");
        getJson("/api/notes").assertStatus(400);
        withHeader("X-Tenant", "nope").getJson("/api/notes").assertNotFound();
    }

    @Test
    @DisplayName("租户列表不需要租户头")
    void tenantListIsPublic() {
        tenant("alpha");
        getJson("/api/tenants").assertOk()
                .assertJsonPath("data.0.slug", "alpha");
    }
}
