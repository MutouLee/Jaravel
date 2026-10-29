package io.jaravel.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.core.config.ConfigLoader;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.config.DotEnv;
import io.jaravel.core.config.MapConfigRepository;
import io.jaravel.core.container.Container;
import io.jaravel.core.container.SimpleContainer;
import io.jaravel.core.context.ContextHolder;
import io.jaravel.core.context.ThreadLocalContextHolder;
import io.jaravel.core.facade.Facade;
import io.jaravel.core.pipeline.Pipeline;

class CoreTest {

    @AfterEach void cleanup() {
        Application.reset();
        Facade.clearSwaps();
    }

    // ================= 容器 =================

    interface Greeter { String greet(); }

    @Nested
    @DisplayName("Container")
    class ContainerTest {

        @Test void bindAndMake() {
            var c = new SimpleContainer();
            c.bind(Greeter.class, x -> () -> "hi");
            assertThat(c.make(Greeter.class).greet()).isEqualTo("hi");
        }

        @Test
        @DisplayName("singleton 只构造一次,bind 每次新建")
        void singletonVsFactory() {
            var c = new SimpleContainer();
            var singletonCalls = new AtomicInteger();
            var factoryCalls = new AtomicInteger();

            c.singleton(Greeter.class, x -> { singletonCalls.incrementAndGet(); return () -> "s"; });
            c.bind(Runnable.class, x -> { factoryCalls.incrementAndGet(); return () -> {}; });

            c.make(Greeter.class); c.make(Greeter.class); c.make(Greeter.class);
            c.make(Runnable.class); c.make(Runnable.class);

            assertThat(singletonCalls.get()).isEqualTo(1);
            assertThat(factoryCalls.get()).isEqualTo(2);
        }

        @Test void namedBindings() {
            var c = new SimpleContainer();
            c.bind(Greeter.class, "en", x -> () -> "hello");
            c.bind(Greeter.class, "zh", x -> () -> "你好");
            assertThat(c.make(Greeter.class, "en").greet()).isEqualTo("hello");
            assertThat(c.make(Greeter.class, "zh").greet()).isEqualTo("你好");
        }

        @Test
        @DisplayName("override 优先于一切,用于测试替换")
        void overrideWins() {
            var c = new SimpleContainer();
            c.singleton(Greeter.class, x -> () -> "real");
            assertThat(c.make(Greeter.class).greet()).isEqualTo("real");

            c.override(Greeter.class, () -> "fake");
            assertThat(c.make(Greeter.class).greet()).isEqualTo("fake");

            c.clearOverrides();
            assertThat(c.make(Greeter.class).greet()).isEqualTo("real");
        }

        @Test
        @DisplayName("解析失败的报错要列出已注册类型并给相似建议")
        void unresolvedErrorIsActionable() {
            var c = new SimpleContainer();
            c.bind(Greeter.class, x -> () -> "hi");

            assertThatThrownBy(() -> c.make(Comparable.class))
                    .isInstanceOf(Container.BindingResolutionException.class)
                    .hasMessageContaining("Comparable")
                    .hasMessageContaining("已注册的类型")
                    .hasMessageContaining("Greeter")
                    .hasMessageContaining("修复");
        }

        @Test void tryMakeDoesNotThrow() {
            var c = new SimpleContainer();
            assertThat(c.tryMake(Greeter.class)).isEmpty();
            c.bind(Greeter.class, x -> () -> "hi");
            assertThat(c.tryMake(Greeter.class)).isPresent();
        }
    }

    // ================= 门面 =================

    static final class Greet extends Facade {
        static String say() { return resolve(Greeter.class).greet(); }
    }

    @Nested
    @DisplayName("Facade")
    class FacadeTest {

        @Test void resolvesThroughApplication() {
            var c = new SimpleContainer();
            c.bind(Greeter.class, x -> () -> "from container");
            Application.setContainer(c);
            assertThat(Greet.say()).isEqualTo("from container");
        }

        @Test
        @DisplayName("swap 让门面在测试中可替换(回应『静态不可测试』)")
        void swapMakesFacadesTestable() {
            var c = new SimpleContainer();
            c.bind(Greeter.class, x -> () -> "real");
            Application.setContainer(c);

            Facade.swap(Greeter.class, () -> "faked");
            assertThat(Greet.say()).isEqualTo("faked");
            assertThat(Facade.isSwapped(Greeter.class)).isTrue();

            Facade.clearSwaps();
            assertThat(Greet.say()).isEqualTo("real");
        }

        @Test
        @DisplayName("未初始化时报错指明如何在测试中初始化")
        void uninitializedErrorIsHelpful() {
            assertThatThrownBy(Greet::say)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Application.setContainer");
        }
    }

    // ================= Pipeline =================

    @Nested
    @DisplayName("Pipeline")
    class PipelineTest {

        @Test
        @DisplayName("前置逻辑按注册顺序、后置逻辑逆序 —— 洋葱模型")
        void onionOrdering() {
            List<String> log = new ArrayList<>();
            String result = Pipeline.<String, String>of("x")
                    .through(List.of(
                        (p, next) -> { log.add("A-in"); String r = next.apply(p); log.add("A-out"); return r; },
                        (p, next) -> { log.add("B-in"); String r = next.apply(p); log.add("B-out"); return r; }
                    ))
                    .then(p -> { log.add("handler"); return p + "!"; });

            assertThat(result).isEqualTo("x!");
            assertThat(log).containsExactly("A-in", "B-in", "handler", "B-out", "A-out");
        }

        @Test
        @DisplayName("不调用 next 即短路,后续 pipe 与 handler 都不执行")
        void shortCircuit() {
            List<String> log = new ArrayList<>();
            String result = Pipeline.<String, String>of("x")
                    .through(List.of(
                        (p, next) -> { log.add("A"); return "short"; },
                        (p, next) -> { log.add("B"); return next.apply(p); }
                    ))
                    .then(p -> { log.add("handler"); return p; });

            assertThat(result).isEqualTo("short");
            assertThat(log).containsExactly("A");
        }

        @Test
        @DisplayName("pipe 可以改变传递的值")
        void transformsPassable() {
            String result = Pipeline.<String, String>of("a")
                    .through(List.of(
                        (p, next) -> next.apply(p + "b"),
                        (p, next) -> next.apply(p + "c")
                    ))
                    .then(p -> p);
            assertThat(result).isEqualTo("abc");
        }

        @Test void emptyPipelineCallsHandlerDirectly() {
            assertThat(Pipeline.<String, String>of("x").then(p -> p + "!")).isEqualTo("x!");
        }

        @Test
        @DisplayName("20 个中间件不会栈溢出")
        void deepPipelineIsFine() {
            var pipes = new ArrayList<io.jaravel.core.pipeline.Pipe<Integer, Integer>>();
            for (int i = 0; i < 20; i++) pipes.add((p, next) -> next.apply(p + 1));
            assertThat(Pipeline.<Integer, Integer>of(0).through(pipes).then(p -> p)).isEqualTo(20);
        }
    }

    // ================= 上下文作用域 =================

    @Nested
    @DisplayName("ContextHolder(ADR-007 的地基)")
    class ContextTest {

        @Test void boundInsideScopeOnly() throws Exception {
            ContextHolder<String> h = new ThreadLocalContextHolder<>("test");
            assertThat(h.isBound()).isFalse();

            String r = h.runIn("v", () -> {
                assertThat(h.current()).contains("v");
                return "done";
            });

            assertThat(r).isEqualTo("done");
            assertThat(h.isBound()).isFalse();   // 退出即解绑
        }

        @Test
        @DisplayName("抛异常后仍然解绑 —— 这是不串号的关键")
        void unbindsOnException() {
            ContextHolder<String> h = new ThreadLocalContextHolder<>("test");
            assertThatThrownBy(() -> h.runIn("v", () -> { throw new RuntimeException("boom"); }))
                    .hasMessage("boom");
            assertThat(h.isBound()).isFalse();
        }

        @Test
        @DisplayName("嵌套绑定:内层退出后恢复外层值")
        void nestedBinding() throws Exception {
            ContextHolder<String> h = new ThreadLocalContextHolder<>("test");
            h.runIn("outer", () -> {
                assertThat(h.current()).contains("outer");
                h.runIn("inner", () -> {
                    assertThat(h.current()).contains("inner");
                    return null;
                });
                assertThat(h.current()).contains("outer");   // 已恢复
                return null;
            });
        }

        @Test
        @DisplayName("线程池复用下不串号 —— 这正是不能裸用 ThreadLocal 的原因")
        void noLeakAcrossPooledThreads() throws Exception {
            ContextHolder<String> h = new ThreadLocalContextHolder<>("test");
            // 单线程池:强制复用同一个线程,最容易暴露残留
            try (var pool = Executors.newSingleThreadExecutor()) {
                var latch = new CountDownLatch(1);
                pool.submit(() -> {
                    try { h.runIn("task-1", () -> null); } catch (Exception ignored) {}
                    latch.countDown();
                });
                latch.await(2, TimeUnit.SECONDS);

                var leaked = pool.submit(() -> h.current().orElse("CLEAN")).get();
                assertThat(leaked).isEqualTo("CLEAN");
            }
        }

        @Test
        @DisplayName("虚拟线程下每请求一个作用域,互不干扰")
        void isolatedAcrossVirtualThreads() throws Exception {
            ContextHolder<String> h = new ThreadLocalContextHolder<>("test");
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<String>>();
                for (int i = 0; i < 100; i++) {
                    final String v = "req-" + i;
                    futures.add(pool.submit(() -> h.runIn(v, () -> h.current().orElseThrow())));
                }
                for (int i = 0; i < 100; i++) {
                    assertThat(futures.get(i).get()).isEqualTo("req-" + i);
                }
            }
        }
    }

    // ================= 配置 =================

    @Nested
    @DisplayName("Config")
    class ConfigTest {

        @Test void dotPathAccess() {
            var cfg = new MapConfigRepository(ConfigLoader.parse("""
                app:
                  name: Jaravel
                  debug: true
                database:
                  connections:
                    mysql:
                      port: 3306
                      charset: utf8mb4
                """, "test.yml"));

            assertThat(cfg.string("app.name")).isEqualTo("Jaravel");
            assertThat(cfg.bool("app.debug")).isTrue();
            assertThat(cfg.integer("database.connections.mysql.port")).isEqualTo(3306);
            assertThat(cfg.has("app.name")).isTrue();
            assertThat(cfg.has("app.nope")).isFalse();
            assertThat(cfg.string("app.nope", "fallback")).isEqualTo("fallback");
        }

        @Test
        @DisplayName("${VAR:default} 占位符:有环境变量用它,否则用默认值")
        void placeholderResolution() {
            Map<String, String> env = Map.of("DB_PORT", "5432");
            var cfg = new MapConfigRepository(ConfigLoader.parse("""
                db:
                  port: ${DB_PORT:3306}
                  host: ${DB_HOST:127.0.0.1}
                  name: ${DB_NAME}
                """, "t.yml"), env::get);

            assertThat(cfg.integer("db.port")).isEqualTo(5432);      // 环境变量生效
            assertThat(cfg.string("db.host")).isEqualTo("127.0.0.1"); // 默认值
            // 未设置且无默认值 → 保留原样,便于 doctor 检测,而不是静默变空串
            assertThat(cfg.string("db.name")).isEqualTo("${DB_NAME}");
        }

        @Test
        @DisplayName("默认值里含 : 与 / 不被截断(URL 场景)")
        void placeholderDefaultWithColons() {
            var cfg = new MapConfigRepository(
                    ConfigLoader.parse("app:\n  url: ${APP_URL:http://localhost:8000}\n", "t.yml"),
                    k -> null);
            assertThat(cfg.string("app.url")).isEqualTo("http://localhost:8000");
        }

        @Test void durationShorthand() {
            var cfg = new MapConfigRepository(
                    ConfigLoader.parse("session:\n  lifetime: 120m\n", "t.yml"));
            assertThat(cfg.duration("session.lifetime", null)).isEqualTo(Duration.ofMinutes(120));
        }

        @Test void stringListAcceptsBothForms() {
            var cfg = new MapConfigRepository(ConfigLoader.parse("""
                a:
                  list:
                    - x
                    - y
                  csv: p, q
                """, "t.yml"));
            assertThat(cfg.stringList("a.list")).containsExactly("x", "y");
            assertThat(cfg.stringList("a.csv")).containsExactly("p", "q");
            assertThat(cfg.stringList("a.missing")).isEmpty();
        }

        @Test
        @DisplayName("类型转换失败的报错必须指出是哪个配置键")
        void castErrorNamesTheKey() {
            var cfg = new MapConfigRepository(
                    ConfigLoader.parse("db:\n  port: not-a-number\n", "t.yml"));
            assertThatThrownBy(() -> cfg.integer("db.port"))
                    .isInstanceOf(ConfigRepository.MissingConfigException.class)
                    .hasMessageContaining("db.port")
                    .hasMessageContaining("not-a-number");
        }

        @Test
        @DisplayName("all() 对敏感键脱敏")
        void sensitiveValuesAreMasked() {
            var cfg = new MapConfigRepository(ConfigLoader.parse("""
                db:
                  username: root
                  password: s3cr3t
                app:
                  key: abc123
                """, "t.yml"));
            var all = cfg.all();
            assertThat(all).containsEntry("db.username", "root");
            assertThat(all.get("db.password")).isEqualTo("********");
            assertThat(all.get("app.key")).isEqualTo("********");
        }

        @Test
        @DisplayName("YAML 语法错误带文件名与行号")
        void yamlErrorHasLocation() {
            assertThatThrownBy(() -> ConfigLoader.parse("a:\n  b: [unclosed\n", "broken.yml"))
                    .isInstanceOf(ConfigRepository.MissingConfigException.class)
                    .hasMessageContaining("broken.yml");
        }

        @Test
        @DisplayName("多文件合并:同名嵌套层递归合并而非整体覆盖")
        void directoryMergeIsDeep(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("a-database.yml"),
                    "database:\n  default: mysql\n  connections:\n    mysql:\n      host: localhost\n");
            Files.writeString(dir.resolve("b-database.yml"),
                    "database:\n  connections:\n    mysql:\n      port: 3306\n");

            var cfg = new MapConfigRepository(ConfigLoader.fromDirectory(dir));
            assertThat(cfg.string("database.default")).isEqualTo("mysql");
            assertThat(cfg.string("database.connections.mysql.host")).isEqualTo("localhost");
            assertThat(cfg.integer("database.connections.mysql.port")).isEqualTo(3306);
        }
    }

    // ================= .env =================

    @Nested
    @DisplayName("DotEnv")
    class DotEnvTest {

        @Test void basicSyntax() {
            var env = DotEnv.parse("""
                # 注释行
                APP_NAME=Jaravel
                APP_DEBUG=true
                QUOTED="hello world"
                SINGLE='raw $value'
                export EXPORTED=yes
                EMPTY=
                INLINE=value # 行内注释
                """);

            assertThat(env).containsEntry("APP_NAME", "Jaravel")
                           .containsEntry("APP_DEBUG", "true")
                           .containsEntry("QUOTED", "hello world")
                           .containsEntry("SINGLE", "raw $value")
                           .containsEntry("EXPORTED", "yes")
                           .containsEntry("EMPTY", "")
                           .containsEntry("INLINE", "value");
            assertThat(env).doesNotContainKey("# 注释行");
        }

        @Test
        @DisplayName(".env.local 覆盖 .env,不反过来")
        void precedence(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve(".env"), "A=base\nB=base\n");
            Files.writeString(dir.resolve(".env.local"), "A=local\n");

            var env = DotEnv.load(dir, null);
            assertThat(env).containsEntry("A", "local")   // local 优先
                           .containsEntry("B", "base");   // local 没定义则用 .env
        }

        @Test
        @DisplayName("profile 文件优先级介于 local 与 base 之间")
        void profilePrecedence(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve(".env"), "A=base\nB=base\nC=base\n");
            Files.writeString(dir.resolve(".env.testing"), "A=testing\nB=testing\n");
            Files.writeString(dir.resolve(".env.local"), "A=local\n");

            var env = DotEnv.load(dir, "testing");
            assertThat(env).containsEntry("A", "local")
                           .containsEntry("B", "testing")
                           .containsEntry("C", "base");
        }

        @Test void variableExpansionWithinEnv(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve(".env"),
                    "HOST=db.example.com\nPORT=3306\nDSN=mysql://${HOST}:${PORT}/app\n");
            assertThat(DotEnv.load(dir, null))
                    .containsEntry("DSN", "mysql://db.example.com:3306/app");
        }

        @Test
        @DisplayName("循环引用不会死循环")
        void circularReferenceTerminates(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve(".env"), "A=${B}\nB=${A}\n");
            var env = DotEnv.load(dir, null);   // 不应挂住
            assertThat(env).containsKeys("A", "B");
        }

        @Test void missingFilesAreFine(@TempDir Path dir) {
            assertThat(DotEnv.load(dir, "nope")).isEmpty();
        }
    }
}
