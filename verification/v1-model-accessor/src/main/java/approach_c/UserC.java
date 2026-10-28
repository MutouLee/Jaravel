package approach_c;

import java.time.Instant;

/**
 * 方案 C:用户手写全部 getter/setter。
 *
 * ★ 以下全部由用户手写 —— 这是 parity 统计的计数对象。
 * (元模型与 columns()/table() 由 APT 生成,见 UserCBase / UserC_,不计入用户代码)
 *
 * 优点:零魔法、零访问器生成、IDE 完美支持、任何人一眼看懂。
 * 缺点:信息密度崩塌。5 个字段 = 10 个访问器 = 约 20 行纯样板;
 *       且 setter 里必须记得写 markDirty(index),索引硬编码,易错。
 */
public class UserC extends UserCBase {

    private Long id;
    private String name;
    private String email;
    private String status;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long v) { markDirty(0); this.id = v; }

    public String getName() { return name; }
    public void setName(String v) { markDirty(1); this.name = v; }

    public String getEmail() { return email; }
    public void setEmail(String v) { markDirty(2); this.email = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { markDirty(3); this.status = v; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { markDirty(4); this.createdAt = v; }
}
