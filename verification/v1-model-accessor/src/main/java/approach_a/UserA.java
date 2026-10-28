package approach_a;

import java.time.Instant;

/**
 * 方案 A(AutoValue 风格):用户写抽象访问器对,APT 生成含字段的具体子类。
 *
 * ★ 以下全部由用户手写 —— parity 计数对象。
 *
 * 这是「标准 APT 能力范围内」唯一可行的基类方案:
 * 字段必须声明在生成的子类里(生成的父类访问不到子类字段),
 * 所以用户只能声明「形状」,由 APT 实现。
 *
 * 优点:标准 APT、无 javac 内部 API、无字节码织入、类型安全。
 * 缺点:① 每字段 2 行声明,仍有样板;
 *       ② 用户类必须 abstract → `new UserA()` 不可用,只能 `UserA.create()`;
 *       ③ 用户看到的类与实际运行的类不是同一个(UserA vs UserA_Impl),
 *          调试时栈帧和 toString 会暴露 _Impl,有认知成本。
 */
public abstract class UserA extends UserABase {

    public abstract Long id();
    public abstract void id(Long v);

    public abstract String name();
    public abstract void name(String v);

    public abstract String email();
    public abstract void email(String v);

    public abstract String status();
    public abstract void status(String v);

    public abstract Instant createdAt();
    public abstract void createdAt(Instant v);

    /** 由于类是 abstract,构造只能走工厂 */
    public static UserA create() { return new UserA_Impl(); }
}
