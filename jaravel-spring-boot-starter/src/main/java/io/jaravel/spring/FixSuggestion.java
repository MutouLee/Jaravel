package io.jaravel.spring;

import java.util.List;
import java.util.Optional;

/**
 * 修复建议知识库 v1(docs/15 §4.2)。
 *
 * <p>docs/15 的原话:「**这个知识库是 Jaravel 最容易被低估、
 * 但对新手留存影响最大的部分**」。它的作用不是解释异常是什么,
 * 而是给出**下一步该敲什么**。
 *
 * <h2>为什么按异常的简单名匹配,而不是 instanceof</h2>
 * <p>这些异常分散在 eloquent / validation / session / auth / cache
 * 等十来个模块里,而 starter 对其中几个是 optional 依赖 ——
 * 用 {@code instanceof} 会在那些模块不在 classpath 上时
 * 触发 NoClassDefFoundError,而**错误页自己崩掉**是最糟的失败模式:
 * 用户连原始异常都看不到了。
 *
 * <p>按名字匹配是字符串比较,永远不会失败。代价是同名类会误匹配 ——
 * 这些名字足够特异(没人会把自己的类叫 LazyLoadingViolationException),
 * 而误匹配的后果只是多一条不相关的建议。
 *
 * <h2>每条建议都要可执行</h2>
 * <p>「检查你的配置」不算建议。{@link #code} 必须是能直接粘进去的东西,
 * 或者是一条能直接跑的命令。写不出可执行的建议,就不要加这一条 ——
 * 一屏模糊的建议会让用户学会忽略这个区域。
 *
 * @param title       一句话说清问题的性质
 * @param explanation 为什么会这样。可以为空。
 * @param code        可直接使用的修复代码或命令。可以为空。
 * @param docs        文档位置,如 {@code docs/06-eloquent.md §3.4}
 */
public record FixSuggestion(String title, String explanation, String code, String docs) {

    public static FixSuggestion of(String title, String explanation, String code) {
        return new FixSuggestion(title, explanation, code, null);
    }

    public static FixSuggestion of(String title, String explanation, String code, String docs) {
        return new FixSuggestion(title, explanation, code, docs);
    }

    public boolean hasCode() {
        return code != null && !code.isBlank();
    }

    // ================= 知识库 =================

    /**
     * 查这个异常的建议。
     *
     * <p>沿着 cause 链找 —— 真正的原因往往被包了两三层
     * ({@code QueryFailedException} 包 {@code SQLException},
     * 外面再包一层 {@code UncheckedIOException})。只看最外层
     * 会漏掉绝大多数可建议的情况。
     */
    public static Optional<FixSuggestion> forThrowable(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            var found = lookup(current);
            if (found.isPresent()) return found;

            // 自引用的 cause 链(少见但存在)会让循环永不结束
            if (current.getCause() == current) break;
        }
        return Optional.empty();
    }

    private static Optional<FixSuggestion> lookup(Throwable t) {
        return forName(t.getClass().getSimpleName(),
                       t.getMessage() == null ? "" : t.getMessage());
    }

    /**
     * 按异常的**简单名**查建议。
     *
     * <p>公开这个入口是为了让知识库可以被整表测试 —— 否则测试必须为
     * 二十个异常类型各造一个真实实例,而其中一半的构造函数需要
     * 数据库连接或完整的模型元数据。那样的测试会比知识库本身还脆。
     *
     * @param message 异常消息。有几条建议会据它分流
     *                (比如迁移的「校验和不匹配」与其它失败)
     */
    public static Optional<FixSuggestion> forName(String name, String message) {
        String text = message == null ? "" : message;

        return Optional.ofNullable(switch (name) {

            case "LazyLoadingViolationException" -> of(
                    "访问了没有预加载的关联",
                    """
                    开发模式下未预加载的关联访问会直接报错,而不是静默发一条额外查询。
                    这是 N+1 的第二道防线:在列表页里,静默懒加载会变成 N 条查询,
                    而那在本地只是「有点慢」,上线后是数据库告警。""",
                    "User.query().with(User_.posts).get()   // 在查询上声明要用到的关联",
                    "docs/06-eloquent.md §4");

            case "ModelNotFoundException" -> of(
                    "按主键找不到记录",
                    """
                    findOrFail / firstOrFail 找不到就抛异常,框架会把它转成 404。
                    如果「找不到」在业务上是正常分支,改用返回 Optional 的那组方法。""",
                    """
                    Post.find(id).orElse(null)              // 可能不存在
                    Post.find(id).orElseThrow(...)          // 自定义异常
                    r.get("/posts/{id}", ...).whereNumber("id")   // 路由层挡掉非法 id""",
                    "docs/06-eloquent.md §2");

            case "MassAssignmentException" -> of(
                    "批量赋值被拦住了",
                    """
                    fill / create 只接受 @Fillable 里声明过的字段。
                    这道防线挡的是「请求体里多塞一个 is_admin=true」——
                    所以**不要**为了让它通过而把字段加进 @Fillable,
                    先确认这个字段真的允许由用户指定。""",
                    """
                    // 允许用户填:加进白名单
                    @Fillable({"title", "body"})

                    // 不允许:显式赋值
                    post.authorId = Auth.userOrFail(User.class).id;""",
                    "docs/06-eloquent.md §5");

            case "ValidationException" -> of(
                    "请求校验未通过",
                    """
                    表单请求会 302 回跳并把错误与旧输入闪存(模板里用 @error / @old 读);
                    JSON 请求拿 422 与结构化的错误体。两者的区别只看 Accept 头。""",
                    null,
                    "docs/07-validation.md");

            case "OutsideRequestException" -> of(
                    "在请求作用域之外用了请求级的门面",
                    """
                    Auth.user() / Session / Request 都依赖「当前请求」。
                    队列任务、定时任务、CLI 里没有请求 —— 这里抛异常而不是返回 null,
                    是为了消除「队列里 auth()->user() 莫名为空」这个 Laravel 的经典坑。""",
                    """
                    // 把需要的东西显式传进去
                    dispatch(new SendInvoice(user.id));     // 传 id,不传当前用户""",
                    "docs/02-core-container.md §3");

            case "NoSessionException", "OutsideSessionException" -> of(
                    "需要会话但当前没有",
                    """
                    几乎总是中间件没挂或顺序错了:会话型 guard 与 CSRF 都需要
                    StartSession 先跑。顺序由 Kernel.priority() 保证,
                    但路由上得先有 web 组。""",
                    """
                    r.get("/profile", ...).middleware("web", "auth");
                    // api 组刻意不含 StartSession —— 无状态接口不该下发会话 cookie""",
                    "docs/09-cache-session.md §4");

            case "TokenMismatchException" -> of(
                    "CSRF 令牌不匹配",
                    """
                    419 而不是 403 —— 前端据此区分「页面放太久了」(刷新即可)
                    与「没权限」(重试无用)。最常见的原因是表单里漏了令牌,
                    或登录/登出之后令牌轮换了而页面没重新渲染。""",
                    """
                    <form method="post">@csrf ...</form>      <!-- 模板里 -->
                    headers: { 'X-CSRF-TOKEN': token }        // SPA 里
                    // Webhook 之类的入口应加入排除列表:
                    //   new VerifyCsrfToken(List.of("/webhooks/*"))""",
                    "docs/09-cache-session.md §5");

            case "BindingResolutionException" -> of(
                    "容器里没有这个类型",
                    """
                    具体类会先尝试用构造注入现场创建 —— 走到这个异常说明它是
                    接口/抽象类,或者它的某个构造参数自己解析不出来。""",
                    """
                    @Component                      // 实现类上
                    public class SmtpMailer implements Mailer { ... }

                    // 或在配置类里
                    @Bean Mailer mailer() { return new SmtpMailer(...); }""",
                    "docs/02-core-container.md §2");

            case "QueryFailedException" -> of(
                    "SQL 执行失败",
                    """
                    下面的「本次请求的 SQL」面板里有完整语句与绑定参数。
                    如果提示列或表不存在,通常是迁移没跑。""",
                    """
                    # 开发期按需跑迁移
                    jaravel.migrate.on-boot: true     # config/jaravel.yml""",
                    "docs/05-database.md");

            case "UnknownModelException" -> of(
                    "模型没有注册到元数据表里",
                    """
                    @Model 的注解处理会生成 X_ 元模型并在类加载时注册。
                    拿不到说明注解处理没跑 —— 新增模型后需要重新编译
                    (这类变更走 L3 类加载器重启,HotSwap 处理不了)。""",
                    """
                    mvn compile     # 确认 target/generated-sources 下有 X_.java""",
                    "docs/06-eloquent.md §1");

            case "MissingConfigException" -> of(
                    "缺少必需的配置项",
                    """
                    config/*.yml 在开发期从文件系统读、打包后从 classpath 读。
                    新增配置文件时要同步 JaravelAutoConfiguration.CONFIG_FILES ——
                    漏了的症状是「本地好的,打包后不生效」。""",
                    null,
                    "docs/02-core-container.md §4");

            case "DecryptionFailedException" -> of(
                    "解密失败",
                    """
                    几乎总是 APP_KEY 变了:换掉它会让所有已发出的加密 cookie
                    与签名链接立刻失效。也可能是数据在两个不同 key 的环境间搬过。""",
                    "APP_KEY=base64:...        # 确认 .env 与部署环境里是同一个",
                    "docs/09-cache-session.md §6");

            case "InvalidSignatureException" -> of(
                    "签名 URL 无效或已过期",
                    """
                    expires 也在签名覆盖范围内,所以改它无法续期。
                    邮件客户端给 URL 加跟踪参数也会让签名失配 ——
                    参数本身是按名字排序后参与签名的,新增参数会破坏它。""",
                    null,
                    "docs/08-auth.md §6");

            case "SessionTooLargeException" -> of(
                    "会话超出 cookie 的大小上限",
                    """
                    cookie 驱动把整个会话放在 cookie 里,上限 4KB。
                    往会话里放集合或实体对象很容易超。""",
                    """
                    session:
                      driver: cache         # 换成服务端存储""",
                    "docs/09-cache-session.md §4");

            case "LockTimeoutException" -> of(
                    "没拿到缓存锁",
                    """
                    另一个进程持有同一个锁。检查是否有未释放的锁
                    (owner 校验会拒绝别人的 release),或把超时调长。""",
                    "Cache.lock(\"key\", Duration.ofSeconds(30)).get(() -> { ... });",
                    "docs/09-cache-session.md §3");

            case "JbladeException" -> of(
                    "模板编译或渲染失败",
                    """
                    上面的「模板」区给出了文件与行号。
                    模板是资源,改完**不需要重启** —— 存盘后直接刷新即可。""",
                    null,
                    "docs/04-view-jblade.md §2.4");

            case "DuplicateRouteException" -> of(
                    "重复的路由",
                    """
                    同一个 method + URI 注册了两次。注册期就报错而不是让后者
                    静默覆盖前者 —— 覆盖的症状是「我明明写了这个路由,
                    它却走到另一个控制器」。""",
                    null,
                    "docs/03-routing-http.md §2");

            case "UnconditionalWriteException" -> of(
                    "不带 where 的 update / delete",
                    """
                    框架拒绝执行会命中全表的写操作。要真的全表更新就显式说出来 ——
                    这个异常挡住的是「忘了加 where」,而那个错误不可逆。""",
                    """
                    Post.query().whereNotNull(Post_.id).update(...)   // 显式条件
                    Post.truncate();                                  // 真要清空""",
                    "docs/05-database.md §3");

            case "MigrationException" -> of(
                    "迁移执行失败",
                    text.contains("checksum") ? """
                        校验和不匹配:这个迁移已经跑过,但它的内容变了。
                        改一个跑过的迁移在团队里会让其他人的库与你的不一致 ——
                        所以应该新增一个迁移,而不是改旧的。""" : """
                        看上面的 SQL 面板定位失败的语句。""",
                    null,
                    "docs/05-database.md §5");

            default -> null;
        });
    }

    /** 知识库里覆盖了哪些异常 —— 给测试与文档用 */
    public static List<String> covered() {
        return List.of(
                "LazyLoadingViolationException", "ModelNotFoundException",
                "MassAssignmentException", "ValidationException",
                "OutsideRequestException", "NoSessionException",
                "OutsideSessionException", "TokenMismatchException",
                "BindingResolutionException", "QueryFailedException",
                "UnknownModelException", "MissingConfigException",
                "DecryptionFailedException", "InvalidSignatureException",
                "SessionTooLargeException", "LockTimeoutException",
                "JbladeException", "DuplicateRouteException",
                "UnconditionalWriteException", "MigrationException");
    }
}
