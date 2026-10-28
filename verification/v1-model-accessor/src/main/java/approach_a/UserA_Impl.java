package approach_a;

import java.time.Instant;

/**
 * ⚙ 由 APT 生成 —— 不计入用户代码行数。
 *
 * 字段必须在这里(生成的父类访问不到子类字段,所以只能反过来:
 * 用户声明抽象形状,APT 生成含字段的具体子类)。
 */
final class UserA_Impl extends UserA {

    private Long id;
    private String name;
    private String email;
    private String status;
    private Instant createdAt;

    @Override public Long id()                  { return id; }
    @Override public void id(Long v)            { markDirty(0); this.id = v; }

    @Override public String name()              { return name; }
    @Override public void name(String v)        { markDirty(1); this.name = v; }

    @Override public String email()             { return email; }
    @Override public void email(String v)       { markDirty(2); this.email = v; }

    @Override public String status()            { return status; }
    @Override public void status(String v)      { markDirty(3); this.status = v; }

    @Override public Instant createdAt()        { return createdAt; }
    @Override public void createdAt(Instant v)  { markDirty(4); this.createdAt = v; }

    @Override public String toString() {
        return "UserA{id=" + id + ", name=" + name + ", email=" + email + "}";
    }
}
