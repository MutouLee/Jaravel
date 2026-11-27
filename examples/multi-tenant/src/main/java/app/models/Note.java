package app.models;

import java.time.Instant;

import net.dobeen.jaravel.eloquent.annotations.Fillable;
import net.dobeen.jaravel.eloquent.annotations.GlobalScope;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;
import net.dobeen.jaravel.eloquent.query.Query;

import app.tenancy.TenantContext;

/**
 * 共享库 + tenant_id 的隔离方式。
 *
 * <p>@GlobalScope 让「没带 tenant 条件」的查询根本不存在;绕过它需要显式
 * {@code withoutGlobalScope("tenant")} —— 那是审计里能 grep 到的动作。
 */
@Model
@Fillable({"tenant_id", "body"})
public class Note extends NoteBase {

    @Id public Long id;
    public Long tenantId;
    public String body;
    public Instant createdAt;
    public Instant updatedAt;

    @GlobalScope
    public static Query<Note> tenant(Query<Note> q) {
        return TenantContext.current()
                .map(t -> q.where(Note_.tenantId, t.id))
                .orElse(q);
    }
}
