package io.jaravel.auth.provider;

import java.util.Map;
import java.util.Optional;

import io.jaravel.auth.AuthModel;
import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.RememberToken;
import io.jaravel.auth.hash.HashManager;
import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.meta.Col;
import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.eloquent.meta.ModelRegistry;
import io.jaravel.eloquent.query.Query;
import io.jaravel.support.Arr;

/**
 * 从 Eloquent 模型取用户。
 *
 * <p>模型上标 {@code @AuthModel} 即可,不需要实现 {@link Authenticatable} ——
 * 这里把模型包装成 {@link ModelUser}。理由见 {@link AuthModel} 的注释。
 */
public class EloquentUserProvider<M extends Model<M>> extends AbstractUserProvider {

    private final String name;
    private final ModelMeta<M> meta;
    private final AuthModel config;

    private final Col<M, ?> identifierColumn;
    private final Col<M, String> passwordColumn;       // 可为 null
    private final Col<M, String> rememberColumn;       // 可为 null

    public EloquentUserProvider(String name, Class<M> modelType, HashManager hasher) {
        super(hasher);
        this.name = name;
        this.meta = ModelRegistry.of(modelType);
        this.config = resolveConfig(modelType);

        this.identifierColumn = config.identifier().isBlank()
                ? meta.key()
                : column(modelType, config.identifier(), "identifier");
        this.passwordColumn = config.password().isBlank()
                ? null
                : stringColumn(modelType, config.password(), "password");
        this.rememberColumn = config.rememberToken().isBlank()
                ? null
                : optionalStringColumn(modelType, config.rememberToken());
    }

    private static AuthModel resolveConfig(Class<?> modelType) {
        AuthModel annotation = modelType.getAnnotation(AuthModel.class);
        if (annotation != null) return annotation;

        throw new IllegalStateException("""
                模型 %s 没有 @AuthModel 注解,不能用于认证。
                  加上它并声明列名:
                    @Model
                    @AuthModel(password = "password", rememberToken = "remember_token")
                    public class %s extends %sBase { ... }

                  不用密码登录(纯 Token / OAuth)时写 @AuthModel(password = "")。
                  不支持 remember me 时写 rememberToken = ""。"""
                .formatted(modelType.getSimpleName(), modelType.getSimpleName(),
                           modelType.getSimpleName()));
    }

    @Override public String name() { return name; }

    public ModelMeta<M> meta() {
        return meta;
    }

    // ================= 查询 =================

    @Override
    public Optional<Authenticatable> retrieveById(Object id) {
        return Query.of(meta).find(coerceId(id)).map(this::wrap);
    }

    @Override
    public Optional<Authenticatable> retrieveByCredentials(Map<String, Object> credentials) {
        var conditions = queryableCredentials(credentials);

        Query<M> query = Query.of(meta);
        conditions.forEach((column, value) -> query.whereNamed(resolveColumn(column), value));
        return query.first().map(this::wrap);
    }

    @Override
    public void updateRememberToken(Authenticatable user, String token) {
        if (rememberColumn == null) {
            throw new UnsupportedOperationException("""
                    模型 %s 的 @AuthModel 没有声明 rememberToken 列,不支持 remember me。
                      要支持:给表加 remember_token varchar(100) null,
                      并在 @AuthModel 里写 rememberToken = "remember_token"。"""
                    .formatted(meta.type().getSimpleName()));
        }
        // 存摘要,不存明文 —— 见 UserProvider.updateRememberToken 的契约
        M model = unwrapModel(user);
        rememberColumn.set(model, RememberToken.hash(token));
        model.save();
    }

    /**
     * 登录成功后,若哈希参数已升级则静默重算。
     *
     * <p>只有此刻手里有明文密码。不做的话,提高 cost 之后老用户的哈希
     * 永远不会升级 —— 等于那次安全加固只对新注册用户生效。
     */
    @Override
    public void rehashPasswordIfRequired(Authenticatable user, Map<String, Object> credentials) {
        if (passwordColumn == null) return;

        String plaintext = passwordFrom(credentials);
        if (plaintext == null || plaintext.isEmpty()) return;

        String current = user.getAuthPassword().orElse(null);
        if (current == null || !hasher.needsRehash(current)) return;

        M model = unwrapModel(user);
        passwordColumn.set(model, hasher.make(plaintext));
        model.save();
    }

    // ================= 列解析 =================

    /**
     * 把凭据里的键名解析成列名。
     *
     * <p>同时接受 Java 字段名与数据库列名({@code countryId} 与 {@code country_id}),
     * 因为调用方可能从表单(snake_case)或代码(camelCase)两处来。
     * 解析不了时报错并列出所有列 —— 静默忽略一个条件会让
     * {@code attempt(email, password, status)} 变成不校验 status,
     * 那是个安全问题。
     */
    private String resolveColumn(String key) {
        if (meta.column(key).isPresent()) return key;

        String snake = io.jaravel.support.Str.snake(key);
        if (meta.column(snake).isPresent()) return snake;

        throw new IllegalArgumentException("""
                凭据里的 "%s" 在模型 %s 上没有对应的列。%s
                  该模型的列:%s
                  注意:静默忽略一个凭据字段会让它**不参与校验** ——
                  比如 attempt(email, password, status="active") 里的 status
                  被忽略后,停用的账号也能登录。所以这里报错。"""
                .formatted(key, meta.type().getSimpleName(),
                           Arr.closest(snake, meta.columnNames())
                              .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                           meta.columnNames()));
    }

    @SuppressWarnings("unchecked")
    private Col<M, ?> column(Class<M> modelType, String name, String role) {
        return meta.column(name).orElseThrow(() -> new IllegalStateException("""
                @AuthModel 的 %s 列 "%s" 在模型 %s 上不存在。%s
                  该模型的列:%s"""
                .formatted(role, name, modelType.getSimpleName(),
                           Arr.closest(name, meta.columnNames())
                              .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                           meta.columnNames())));
    }

    @SuppressWarnings("unchecked")
    private Col<M, String> stringColumn(Class<M> modelType, String name, String role) {
        return (Col<M, String>) column(modelType, name, role);
    }

    @SuppressWarnings("unchecked")
    private Col<M, String> optionalStringColumn(Class<M> modelType, String name) {
        // rememberToken 列不存在时**不报错**:多数应用不需要 remember me,
        // 而默认值是 "remember_token" —— 为一个没人用的功能让启动失败不合理
        return (Col<M, String>) meta.column(name).orElse(null);
    }

    /** 路径参数与 JSON 里的 id 类型五花八门,统一交给模型主键的类型 */
    private Object coerceId(Object id) {
        if (id == null) return null;
        Class<?> target = meta.key().type();
        if (target.isInstance(id)) return id;
        try {
            return io.jaravel.support.Cast.to(id, target);
        } catch (RuntimeException e) {
            return id;     // 转不了就原样传,让数据库去判
        }
    }

    // ================= 包装 =================

    /**
     * 包装任意对象。
     *
     * <p>类型不符时报错而不是返回空 —— 多 provider 的应用里
     * {@code Auth.login(admin)} 走到 users provider 是个配置错误,
     * 静默失败会表现为「登录了但 Auth.check() 是 false」。
     */
    @Override
    public Authenticatable wrap(Object model) {
        if (model instanceof Authenticatable ready) return ready;

        if (!meta.type().isInstance(model)) {
            throw new IllegalArgumentException("""
                    provider "%s" 管的是 %s,不能包装 %s。
                      多 guard 的应用里这通常是用错了 provider:
                      检查 Auth.guard("...") 或 config/auth.yml 里的 provider 指向。"""
                    .formatted(name(), meta.type().getSimpleName(),
                               model == null ? "null" : model.getClass().getSimpleName()));
        }
        return wrap(meta.type().cast(model));
    }

    private Authenticatable wrap(M model) {
        return new ModelUser<>(model, meta, identifierColumn, passwordColumn, rememberColumn);
    }

    @SuppressWarnings("unchecked")
    private M unwrapModel(Authenticatable user) {
        Object raw = user.unwrap();
        if (meta.type().isInstance(raw)) return (M) raw;

        throw new IllegalArgumentException("""
                这个 Authenticatable 不是 %s —— 实际是 %s。
                  多 guard 应用里容易把 admin guard 的用户传给 users 提供器。
                  检查调用处用的是哪个 guard。"""
                .formatted(meta.type().getSimpleName(),
                           raw == null ? "null" : raw.getClass().getSimpleName()));
    }

    /** 把 Eloquent 模型包装成 Authenticatable */
    record ModelUser<M extends Model<M>>(
            M model,
            ModelMeta<M> meta,
            Col<M, ?> identifierColumn,
            Col<M, String> passwordColumn,
            Col<M, String> rememberColumn
    ) implements Authenticatable {

        @Override public Object getAuthIdentifier() {
            return identifierColumn.get(model);
        }

        @Override public String getAuthIdentifierName() {
            return identifierColumn.name();
        }

        @Override public Optional<String> getAuthPassword() {
            return passwordColumn == null
                    ? Optional.empty()
                    : Optional.ofNullable(passwordColumn.get(model));
        }

        @Override public String getAuthPasswordName() {
            return passwordColumn == null ? "" : passwordColumn.name();
        }

        @Override public Optional<String> getRememberToken() {
            return rememberColumn == null
                    ? Optional.empty()
                    : Optional.ofNullable(rememberColumn.get(model));
        }

        @Override public void setRememberToken(String token) {
            if (rememberColumn == null) {
                throw new UnsupportedOperationException(
                        meta.type().getSimpleName() + " 没有 remember_token 列");
            }
            rememberColumn.set(model, token);
        }

        @Override public String getRememberTokenName() {
            return rememberColumn == null ? "" : rememberColumn.name();
        }

        @Override public Object unwrap() {
            return model;
        }

        @Override public String toString() {
            return meta.type().getSimpleName() + "#" + getAuthIdentifier();
        }
    }
}
