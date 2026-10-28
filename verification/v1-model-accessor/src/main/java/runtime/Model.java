package runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Active Record 基类(验证用的最小实现)。
 *
 * 核心验证点:**快照式脏检查**。
 * 加载模型时拍一份原值快照;save() 时与当前值比对,只 UPDATE 变化的列。
 *
 * 这让「方案 E(public 字段直接赋值)」成为可能 —— 字段写入无法被拦截,
 * 但快照比对不需要拦截。代价是每个已加载模型多持有一个 Object[] 快照。
 */
public abstract class Model<M extends Model<M>> {

    /** 原值快照。null 表示该实例是新建的(未从数据库加载)。 */
    private Object[] original;

    /** setter 位图脏跟踪(方案 A/C 用)。方案 E 不使用,靠快照。 */
    private long dirtyBits;

    /** 本实例是否已存在于数据库 */
    private boolean exists;

    /** 由 APT 生成:返回本模型的全部列(含 getter/setter 方法引用) */
    protected abstract List<Col<M, ?>> columns();

    /** 由 APT 生成:表名 */
    protected abstract String table();

    @SuppressWarnings("unchecked")
    private M self() { return (M) this; }

    // ---------- 生命周期 ----------

    /** 模拟「从数据库加载完成」:拍快照 */
    public M hydrated() {
        this.original = snapshot();
        this.exists = true;
        this.dirtyBits = 0;
        return self();
    }

    public boolean exists() { return exists; }

    private Object[] snapshot() {
        List<Col<M, ?>> cols = columns();
        Object[] snap = new Object[cols.size()];
        for (int i = 0; i < cols.size(); i++) snap[i] = cols.get(i).get(self());
        return snap;
    }

    // ---------- 脏检查 ----------

    /** 位图方式(方案 A/C:setter 中调用 markDirty) */
    protected void markDirty(int index) { dirtyBits |= (1L << index); }

    /**
     * 返回需要写回的列 → 新值。
     * 合并两种来源:setter 位图 + 快照比对。
     * 这让方案 A/C/E 可以共用同一个 save() 实现。
     */
    public Map<String, Object> changes() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Col<M, ?>> cols = columns();
        for (int i = 0; i < cols.size(); i++) {
            Col<M, ?> col = cols.get(i);
            Object now = col.get(self());
            boolean changed;
            if (original == null) {
                changed = now != null;                       // 新建:非 null 即写入
            } else {
                changed = (dirtyBits & (1L << i)) != 0       // setter 标记过
                       || !Objects.equals(original[i], now); // 或快照比对有差异
            }
            if (changed) out.put(col.name(), now);
        }
        return out;
    }

    public boolean isDirty() { return !changes().isEmpty(); }

    public Object getOriginal(Col<M, ?> col) {
        return original == null ? null : original[col.index()];
    }

    // ---------- 关联 ----------

    /**
     * 关联句柄的惰性创建 + 按名缓存。
     *
     * 状态存在基类的 map 里,**不存在用户模型的字段中**。原因(已验证):
     *   1. public 字段无法拦截访问,做不到懒加载;
     *   2. 生成的父类构造器初始化子类字段,会被用户的显式初始化器静默覆盖。
     *
     * 代价:每个模型实例多一个 HashMap(仅在实际访问关联时才创建)。
     */
    private Map<String, Object> relations;

    @SuppressWarnings("unchecked")
    protected <T> Rel<T> rel(String name, Class<T> target) {
        if (relations == null) relations = new LinkedHashMap<>();
        return (Rel<T>) relations.computeIfAbsent(name, n -> new Rel<>(n, target));
    }

    /** 预加载器回填入口 */
    @SuppressWarnings("unchecked")
    public <T> void fillRelation(String name, Class<T> target, List<T> items) {
        rel(name, target).setLoaded(items);
    }

    // ---------- 持久化(验证用:只生成 SQL,不真连库) ----------

    public String save() {
        Map<String, Object> changes = changes();
        String sql;
        if (!exists) {
            sql = buildInsert(changes);
        } else if (changes.isEmpty()) {
            sql = "-- 无变化,跳过 UPDATE";
        } else {
            sql = buildUpdate(changes);
        }
        // 保存成功后重新拍快照
        this.original = snapshot();
        this.dirtyBits = 0;
        this.exists = true;
        return sql;
    }

    private String buildInsert(Map<String, Object> changes) {
        List<String> cols = new ArrayList<>(changes.keySet());
        String names = String.join(", ", cols.stream().map(c -> "`" + c + "`").toList());
        String marks = String.join(", ", cols.stream().map(c -> "?").toList());
        return "insert into `" + table() + "` (" + names + ") values (" + marks + ")"
             + "  -- bindings: " + changes.values();
    }

    private String buildUpdate(Map<String, Object> changes) {
        String sets = String.join(", ",
                changes.keySet().stream().map(c -> "`" + c + "` = ?").toList());
        return "update `" + table() + "` set " + sets + " where `id` = ?"
             + "  -- bindings: " + changes.values();
    }
}
