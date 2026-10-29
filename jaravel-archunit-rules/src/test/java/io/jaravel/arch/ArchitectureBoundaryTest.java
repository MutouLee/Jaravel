package io.jaravel.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ★ 架构边界的**阻断式**强制(ADR-001)。
 *
 * <p>「骑在 Spring Boot 上,但核心零 Spring 依赖」这条承诺,如果没有机械化的
 * 强制手段,会在第二个迭代里就被悄悄破坏 —— 某个模块为了图方便 import 一个
 * Spring 工具类,然后就再也回不去了。
 *
 * <p>因此这些规则是 CI 的**阻断项**,不是警告。一旦失效,
 * 「将来可出 jaravel-quarkus-starter」「Spring 升级的冲击收窄到一个模块」
 * 这两个收益就同时作废。
 */
class ArchitectureBoundaryTest {

    /** 被约束的模块:除 starter 外的全部 */
    private static final String[] FRAMEWORK_PACKAGES = {
            "io.jaravel.support..",
            "io.jaravel.core..",
            "io.jaravel.http..",
            "io.jaravel.routing..",
            "io.jaravel.view..",
            "io.jaravel.database..",
            "io.jaravel.eloquent..",
            "io.jaravel.migration..",
            "io.jaravel.validation..",
            "io.jaravel.cache..",
            "io.jaravel.session..",
            "io.jaravel.auth..",
    };

    /**
     * 唯一允许接触 Spring 的地方。
     *
     * <p>{@code io.jaravel.spring..} 覆盖两个模块:starter 与
     * jaravel-security-bridge。后者刻意依赖 Spring Security 全栈
     * (docs/08 §8.3 的企业桥接),所以它的包名就落在适配层里 ——
     * 这样它受的约束与 starter 完全一致,不需要为它单开一条例外。
     *
     * <p>{@code io.jaravel.testing..} 是第三个:它用 Spring 的
     * {@code MockHttpServletRequest} 驱动真实管道(docs/14 §1.3)。
     * 这是**刻意**的 —— 测试基类的全部价值就在于「走的是真实管道」,
     * 而真实管道的入口是 Servlet。为它自研一套 mock 等于重新实现
     * 一遍 Servlet API,且会让测试与生产走两条不同的路径,
     * 那正是这个模块要消除的东西。
     *
     * <p>代价是 jaravel-testing 只能用于 Spring Boot 运行时。将来出
     * jaravel-quarkus-starter 的话,需要一个对应的 testing 模块 ——
     * 而 FeatureTest 的断言部分({@code TestResponse})不碰 Servlet,
     * 可以直接复用。
     */
    private static final String[] ADAPTER_PACKAGES = {
            "io.jaravel.spring..",
            "io.jaravel.testing..",
            "io.jaravel.facades..",
            "io.jaravel",            // Jaravel.run 入口
    };

    private static JavaClasses jaravelClasses;

    @BeforeAll
    static void importClasses() {
        jaravelClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.jaravel");
    }

    @Test
    @DisplayName("★ ADR-001:核心模块不得依赖 Spring")
    void coreModulesMustNotDependOnSpring() {
        // 规则要守的是「不绑定 Spring 这个**运行时**」,而不是
        // 「禁止一切 org.springframework 坐标」。所以禁的是构成运行时的那几块:
        // 容器、Boot、Web、AOP、事务、数据访问。
        //
        // 唯一的例外是 org.springframework.security.crypto.. ——
        // 它是一个**独立的 crypto jar**,不含 Filter 链、不含 ApplicationContext,
        // 用它只为复用经过审计的 bcrypt / argon2 实现(docs/08 §4 的
        // 「不自己写密码学代码」)。而且 jaravel-auth 对它是 optional:
        // 不用 bcrypt 的项目完全不会拉到它,默认算法是 JDK 的 PBKDF2。
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(FRAMEWORK_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.boot..",
                        "org.springframework.context..",
                        "org.springframework.beans..",
                        "org.springframework.web..",
                        "org.springframework.aop..",
                        "org.springframework.core..",
                        "org.springframework.transaction..",
                        "org.springframework.jdbc..",
                        "org.springframework.data..",
                        "org.springframework.security.config..",
                        "org.springframework.security.web..")
                .because("ADR-001:Spring 运行时只能出现在 jaravel-spring-boot-starter。"
                       + "核心依赖 Spring 会让版本漂移波及全框架,"
                       + "并断掉将来换到 Quarkus / 纯 Netty 的可能。"
                       + "spring-security-crypto 不在禁列 —— 它是独立 crypto 库,"
                       + "不含 Filter 链与容器,且是 optional 依赖");

        rule.check(jaravelClasses);
    }

    @Test
    @DisplayName("★ ADR-001:核心模块不得依赖 Servlet API")
    void coreModulesMustNotDependOnServletApi() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(FRAMEWORK_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.servlet..", "javax.servlet..")
                .because("ADR-001:Request/Response 必须与 Servlet 解耦,"
                       + "适配由 starter 的 ServletRequestAdapter 完成");

        rule.check(jaravelClasses);
    }

    @Test
    @DisplayName("接触 Spring 的类只能位于 starter 的适配包内")
    void onlyAdapterTouchesSpring() {
        // 等价表述:适配包**之外**的任何类都不得依赖 Spring。
        // 这样写而不用 classes().that().dependOnClassesThat(...) ——
        // 后者在 ArchUnit 的 DSL 里不存在(that() 返回的 ClassesThat 没有该方法)。
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages(ADAPTER_PACKAGES)
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("把 Spring 的接触面收窄到一个模块,是 ADR-001 的全部价值所在");

        rule.check(jaravelClasses);
    }

    @Test
    @DisplayName("模块依赖方向严格单向向下,不得有环")
    void moduleDependenciesAreAcyclic() {
        // support ← core ← {http, view, database} ← routing ← starter
        ArchRule supportDependsOnNothing = noClasses()
                .that().resideInAPackage("io.jaravel.support..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.jaravel.core..", "io.jaravel.http..", "io.jaravel.routing..",
                        "io.jaravel.view..", "io.jaravel.database..", "io.jaravel.eloquent..",
                        "io.jaravel.spring..")
                .because("support 是最底层,依赖任何上层模块都会形成环");

        ArchRule coreDependsOnlyOnSupport = noClasses()
                .that().resideInAPackage("io.jaravel.core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.jaravel.http..", "io.jaravel.routing..",
                        "io.jaravel.view..", "io.jaravel.database..", "io.jaravel.eloquent..",
                        "io.jaravel.spring..")
                .because("core 只能依赖 support");

        ArchRule httpDoesNotDependOnRouting = noClasses()
                .that().resideInAPackage("io.jaravel.http..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.jaravel.routing..", "io.jaravel.view..",
                        "io.jaravel.database..", "io.jaravel.spring..")
                .because("http 在 routing 之下:routing 依赖 http,不能反过来");

        ArchRule databaseIsIndependentOfWeb = noClasses()
                .that().resideInAPackage("io.jaravel.database..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.jaravel.http..", "io.jaravel.routing..",
                        "io.jaravel.view..", "io.jaravel.eloquent..", "io.jaravel.spring..")
                .because("数据层不该知道 HTTP 的存在 —— 这让它在 CLI / 队列场景同样可用;"
                       + "也不该知道 Eloquent —— 查询构造器要能脱离 ORM 单独使用");

        // v0.3 新增:缓存是最底层的横切能力,不该知道任何上层
        ArchRule cacheIsIndependent = noClasses()
                .that().resideInAPackage("io.jaravel.cache..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.jaravel.http..", "io.jaravel.routing..", "io.jaravel.view..",
                        "io.jaravel.eloquent..", "io.jaravel.session..",
                        "io.jaravel.auth..", "io.jaravel.spring..")
                .because("缓存要能被会话、认证、限流共用,因此它不能依赖其中任何一个");

        // 会话可以依赖 http(它要读写 cookie)与 cache(存储后端),
        // 但不该知道认证 —— 反向依赖是 auth → session
        ArchRule sessionDoesNotDependOnAuth = noClasses()
                .that().resideInAPackage("io.jaravel.session..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.jaravel.auth..", "io.jaravel.routing..",
                        "io.jaravel.view..", "io.jaravel.spring..")
                .because("会话是认证的基础设施,不是反过来。"
                       + "让 session 知道 auth 会形成环,也让「只要会话不要认证」不可能");

        // 认证在最上层,但仍然不碰 Spring 运行时与路由注册
        ArchRule authDoesNotDependOnRouting = noClasses()
                .that().resideInAPackage("io.jaravel.auth..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.jaravel.routing..", "io.jaravel.view..", "io.jaravel.spring..")
                .because("认证中间件只与 Request/Response 打交道。"
                       + "依赖 routing 会让「在非 HTTP 上下文里用 Auth」变得不可能,"
                       + "而队列任务需要它");

        // v0.2 新增:迁移不该知道 web,也不该依赖 ORM
        // (只用 Schema + 迁移、不用 Eloquent 是正当用法,pom 里是 optional)
        ArchRule migrationIsIndependentOfWeb = noClasses()
                .that().resideInAPackage("io.jaravel.migration..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.jaravel.http..", "io.jaravel.routing..",
                        "io.jaravel.view..", "io.jaravel.spring..")
                .because("迁移在命令行和测试里跑,拉进 Web 栈就没法单独使用");

        // 验证器可以依赖 http(FormRequest 需要 Request/Response),
        // 但不该知道路由与模板
        ArchRule validationIsIndependentOfRouting = noClasses()
                .that().resideInAPackage("io.jaravel.validation..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.jaravel.routing..", "io.jaravel.view..", "io.jaravel.spring..")
                .because("校验是纯数据判断,接管路由或渲染会让它无法在队列任务里复用");

        // v0.2 新增:Eloquent 的位置是「database 之上、web 之下」
        ArchRule eloquentIsIndependentOfWeb = noClasses()
                .that().resideInAPackage("io.jaravel.eloquent..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.jaravel.http..", "io.jaravel.routing..",
                        "io.jaravel.view..", "io.jaravel.spring..")
                .because("ORM 不该知道 HTTP / 路由 / 模板的存在。"
                       + "这条规则守的是一个具体承诺:同一套模型能在 Web 请求、"
                       + "队列任务、命令行脚本里原样使用,不必为了跑一个脚本而拉起整个 Web 栈");

        ArchRule viewIsIndependentOfWeb = noClasses()
                .that().resideInAPackage("io.jaravel.view..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.jaravel.routing..", "io.jaravel.database..", "io.jaravel.spring..")
                .because("视图引擎不该知道路由与数据库 —— 它只负责『模板 + 数据 → 字符串』");

        supportDependsOnNothing.check(jaravelClasses);
        coreDependsOnlyOnSupport.check(jaravelClasses);
        httpDoesNotDependOnRouting.check(jaravelClasses);
        databaseIsIndependentOfWeb.check(jaravelClasses);
        eloquentIsIndependentOfWeb.check(jaravelClasses);
        migrationIsIndependentOfWeb.check(jaravelClasses);
        validationIsIndependentOfRouting.check(jaravelClasses);
        cacheIsIndependent.check(jaravelClasses);
        sessionDoesNotDependOnAuth.check(jaravelClasses);
        authDoesNotDependOnRouting.check(jaravelClasses);
        viewIsIndependentOfWeb.check(jaravelClasses);
    }

    @Test
    @DisplayName("门面必须零状态:不得有实例字段")
    void facadesAreStateless() {
        // allowEmptyShould(true) 是必要的:门面**完全没有字段**才是期望状态,
        // 而 ArchUnit 默认把「规则未检查到任何对象」判为失败。
        // 这里的语义是「若存在字段,则必须是 static」—— 零字段同样满足。
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields()
                .that().areDeclaredInClassesThat().resideInAPackage("io.jaravel.facades..")
                .should().beStatic()
                .because("门面持有状态就会出现并发问题;状态必须全部在容器里")
                .allowEmptyShould(true);

        rule.check(jaravelClasses);
    }

    @Test
    @DisplayName("门面只提供静态方法:不得有 public 构造函数")
    void facadesCannotBeInstantiated() {
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors()
                .that().areDeclaredInClassesThat().resideInAPackage("io.jaravel.facades..")
                .should().notBePublic()
                .because("门面是静态入口,实例化它没有意义,会误导使用者")
                .allowEmptyShould(true);

        rule.check(jaravelClasses);
    }

    @Test
    @DisplayName("核心模块不得直连具体日志实现(只允许 SLF4J 抽象)")
    void noDirectLoggingImplementation() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(FRAMEWORK_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.apache.logging..", "ch.qos.logback..", "org.apache.log4j..")
                .because("绑死日志实现会让用户无法替换;只依赖 SLF4J 抽象");

        rule.check(jaravelClasses);
    }
}
