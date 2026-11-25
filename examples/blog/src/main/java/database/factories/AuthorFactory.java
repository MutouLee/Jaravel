package database.factories;

import app.models.Author;
import io.jaravel.support.Faker;

/**
 * Author 的工厂(docs/14 §3)。
 *
 * <p>用户只写两样:**字段默认值**与**具名状态**。
 * {@code count} / {@code state} / {@code sequence} / {@code seed} /
 * {@code make} / {@code create} / 类型化 setter({@code .name("张三")})/
 * 关联构建器({@code .hasPosts(3)})都在生成的 {@link AuthorFactoryBase} 里。
 */
public class AuthorFactory extends AuthorFactoryBase {

    /** {@code authors.email} 上有 UNIQUE 索引 —— 理由见 {@link PostFactory} 里那段注释 */
    private static final java.util.concurrent.atomic.AtomicLong EMAILS =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    protected void definition(Faker f, Author author) {
        author.name = f.word() + "作者";
        author.email = "author" + EMAILS.incrementAndGet() + "@example.com";
    }

    /**
     * 具名状态 —— 这才是工厂类存在的理由。
     *
     * <p>默认值里**不**放密码:这个示例没有登录,一个看起来像凭据的
     * 默认值会被人照抄进真项目。要密码的测试自己说出来。
     */
    public AuthorFactory withPassword(String hashed) {
        return state(a -> a.password = hashed);
    }
}
