package io.jaravel.devtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★ L2 方法体热替换 —— docs/17 在 v0.3 复盘里挪进 v0.4 的那一项。
 *
 * <h2>这组测试真的替换字节码</h2>
 * <p>它在测试期**现场编译**一个类的两个版本,把第二版的字节
 * 换进正在运行的 JVM,然后调用那个方法看返回值变没变。
 *
 * <p>不 mock {@code Instrumentation}:要验证的恰好是
 * 「{@code redefineClasses} 在这个 JVM 上真的生效」,而一个 mock
 * 只能证明我们调用了它。L2 的全部风险都在那次真实调用上
 * (JVM 支不支持、agent 挂不挂得上、字节对不对)。
 *
 * <p>为此 {@code jaravel-devtools} 的 surefire 配了
 * {@code -Djdk.attach.allowAttachSelf=true} —— 见那段注释。
 */
class HotSwapperTest {

    private static final HotSwapper SWAPPER = new HotSwapper();

    @BeforeAll
    static void requireL2() {
        assertThat(SWAPPER.available())
                .as("""
                        L2 在这个 JVM 上不可用,原因:
                        %s

                          这组测试是 v0.4 对「改方法体 → 0 重启」的唯一验证。
                          让它跳过等于没有验证,所以这里直接失败。
                          pom 里已经配了 -Djdk.attach.allowAttachSelf=true。""",
                        SWAPPER.unavailableReason())
                .isTrue();
    }

    // ================= 路径 → 类名 =================

    @Nested
    @DisplayName("从 .class 路径推类名")
    class ClassNames {

        @Test
        @DisplayName("Maven 的 target/classes")
        void mavenLayout() {
            assertThat(HotSwapper.classNameOf(
                    Path.of("/app/target/classes/com/x/Foo.class")))
                    .contains("com.x.Foo");
            assertThat(HotSwapper.classNameOf(
                    Path.of("/app/target/test-classes/com/x/FooTest.class")))
                    .contains("com.x.FooTest");
        }

        @Test
        @DisplayName("Windows 的反斜杠")
        void windowsSeparators() {
            assertThat(HotSwapper.classNameOf(
                    Path.of("E:\\app\\target\\classes\\com\\x\\Foo.class")))
                    .contains("com.x.Foo");
        }

        @Test
        @DisplayName("Gradle 的 build/classes/java/main")
        void gradleLayout() {
            assertThat(HotSwapper.classNameOf(
                    Path.of("/app/build/classes/java/main/com/x/Foo.class")))
                    .as("Gradle 在 classes/ 后面多了 java/main 两段")
                    .contains("com.x.Foo");
        }

        @Test
        @DisplayName("嵌套类")
        void nestedClass() {
            assertThat(HotSwapper.classNameOf(
                    Path.of("/app/target/classes/com/x/Foo$Bar.class")))
                    .contains("com.x.Foo$Bar");
        }

        @Test
        @DisplayName("★ 推不出来时返回 empty —— 不猜")
        void refusesToGuess() {
            assertThat(HotSwapper.classNameOf(Path.of("/tmp/Foo.class")))
                    .as("按文件名去匹配已加载的类会在同名类(不同包)上"
                            + "换错对象,那个后果比降级到 L3 严重得多")
                    .isEmpty();
            assertThat(HotSwapper.classNameOf(Path.of("/app/target/classes/x.txt")))
                    .isEmpty();
        }
    }

    // ================= 真的热替换 =================

    @Nested
    @DisplayName("★ 真的把方法体换掉")
    class RealRedefinition {

        @TempDir
        Path workspace;

        /**
         * 编译一个类到 {@code <workspace>/classes/}。
         *
         * <p>目录名必须是 {@code classes} —— {@link HotSwapper#classNameOf}
         * 以它为锚点。这也顺便说明了那个约定不是随意的。
         */
        private Path compile(String className, String source) throws Exception {
            Path sourceRoot = Files.createDirectories(workspace.resolve("src"));
            Path classesRoot = Files.createDirectories(workspace.resolve("classes"));

            Path file = sourceRoot.resolve(className + ".java");
            Files.writeString(file, source, StandardCharsets.UTF_8);

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertThat(compiler)
                    .as("需要 JDK 才能跑这组测试(JRE 上没有编译器)")
                    .isNotNull();

            int exit = compiler.run(null, null, null,
                    "-d", classesRoot.toString(), file.toString());
            assertThat(exit).as("测试里的源码编译失败了").isZero();

            return classesRoot.resolve(className + ".class");
        }

        private static final String V1 = """
                public class Swappable {
                    public static String version() { return "v1"; }
                }
                """;

        private static final String V2 = """
                public class Swappable {
                    public static String version() { return "v2"; }
                }
                """;

        /** 只改方法体 —— 这是 L2 唯一支持的变更 */
        @Test
        @DisplayName("★ 改方法体:换完之后调用返回新值")
        void methodBodySwap() throws Exception {
            Path v1 = compile("Swappable", V1);

            // 用一个独立的加载器加载 v1,并**真的调一次**
            var loader = new java.net.URLClassLoader(
                    new java.net.URL[] {v1.getParent().toUri().toURL()},
                    getClass().getClassLoader());
            Class<?> type = loader.loadClass("Swappable");

            assertThat(type.getMethod("version").invoke(null)).isEqualTo("v1");

            // 编译 v2 盖掉同一个 .class 文件
            compile("Swappable", V2);

            var result = SWAPPER.redefine(List.of(v1));

            assertThat(result.ok())
                    .as("替换应该成功,失败原因:%s", result.failures())
                    .isTrue();
            assertThat(result.swapped()).containsExactly("Swappable");

            assertThat(type.getMethod("version").invoke(null))
                    .as("""
                            ★ 这是 L2 的全部意义:**同一个 Class 对象**,
                              方法体已经是新的了,没有重启、没有换加载器。""")
                    .isEqualTo("v2");
        }

        /**
         * ★ 加一个方法 —— 标准 JVM **拒绝**。
         *
         * <p>这一条比上面那条更重要:L2 最常见的结局是失败,
         * 而失败必须被**如实报告**,否则调用方不会降级到 L3,
         * 于是「文件已经改了、运行时还是旧的」。
         */
        @Test
        @DisplayName("★ 增加方法:被拒绝,且运行时保持旧行为")
        void addingAMethodIsRejected() throws Exception {
            Path v1 = compile("Growing", """
                    public class Growing {
                        public static String version() { return "v1"; }
                    }
                    """);

            var loader = new java.net.URLClassLoader(
                    new java.net.URL[] {v1.getParent().toUri().toURL()},
                    getClass().getClassLoader());
            Class<?> type = loader.loadClass("Growing");
            assertThat(type.getMethod("version").invoke(null)).isEqualTo("v1");

            compile("Growing", """
                    public class Growing {
                        public static String version() { return "v2"; }
                        public static String extra()   { return "new"; }
                    }
                    """);

            var result = SWAPPER.redefine(List.of(v1));

            assertThat(result.ok())
                    .as("增删方法是标准 HotSwap 的硬限制 —— 必须报失败")
                    .isFalse();
            assertThat(result.failures().get("Growing"))
                    .as("消息要说清这不是 bug,而是 JVM 的限制")
                    .contains("方法体");

            assertThat(type.getMethod("version").invoke(null))
                    .as("失败之后运行时保持旧行为 —— 调用方据此降级到 L3")
                    .isEqualTo("v1");
        }

        @Test
        @DisplayName("★ 加一个字段:同样被拒绝")
        void addingAFieldIsRejected() throws Exception {
            Path v1 = compile("Fielded", """
                    public class Fielded {
                        public static String version() { return "v1"; }
                    }
                    """);

            new java.net.URLClassLoader(
                    new java.net.URL[] {v1.getParent().toUri().toURL()},
                    getClass().getClassLoader()).loadClass("Fielded");

            compile("Fielded", """
                    public class Fielded {
                        public static int count = 0;
                        public static String version() { return "v1"; }
                    }
                    """);

            assertThat(SWAPPER.redefine(List.of(v1)).ok())
                    .as("给模型加一个字段是开发期最常见的改动之一 —— "
                            + "它走不了 L2,这就是 L2 覆盖率低于 docs/15 §1 "
                            + "估计的原因")
                    .isFalse();
        }

        @Test
        @DisplayName("没被加载过的类算「跳过」,不算失败")
        void notYetLoadedIsSkipped() throws Exception {
            Path v1 = compile("NeverLoaded", """
                    public class NeverLoaded {
                        public static String version() { return "v1"; }
                    }
                    """);

            var result = SWAPPER.redefine(List.of(v1));

            assertThat(result.ok())
                    .as("没加载过的类下次加载就是新的 —— 不需要替换,"
                            + "更不该因此降级到 L3")
                    .isTrue();
            assertThat(result.skipped()).containsExactly("NeverLoaded");
            assertThat(result.swapped()).isEmpty();
        }

        @Test
        @DisplayName("★ 带着 APT 注解的类不走 L2")
        void aptAnnotatedClassesEscalate() throws Exception {
            // 自己定义一个叫 Model 的注解 —— HotSwapper 按**简单名**
            // 比对，因为 jaravel-devtools 引用不到 eloquent 里的那个。
            // 这也顺便证明了那个取舍的代价：一个恰好同名的
            // 自定义注解会被误伤 —— 而误伤的后果只是慢两秒
            Path v1 = compile("Annotated", """
                    import java.lang.annotation.*;

                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.TYPE)
                    @interface Model {}

                    @Model
                    public class Annotated {
                        public static String version() { return "v1"; }
                    }
                    """);

            var loader = new java.net.URLClassLoader(
                    new java.net.URL[] {v1.getParent().toUri().toURL()},
                    getClass().getClassLoader());
            Class<?> type = loader.loadClass("Annotated");
            assertThat(type.getMethod("version").invoke(null)).isEqualTo("v1");

            // 只改方法体 —— 单看 JVM 的限制这次替换是合法的
            compile("Annotated", """
                    import java.lang.annotation.*;

                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.TYPE)
                    @interface Model {}

                    @Model
                    public class Annotated {
                        public static String version() { return "v2"; }
                    }
                    """);

            var result = SWAPPER.redefine(List.of(v1));

            assertThat(result.ok())
                    .as("""
                            带 @Model 的类必须降级到 L3。
                              HotSwap 不重跑 APT，于是 XxxBase / Xxx_
                              这些生成物会与新代码脱节 ——
                              而那种不一致的报错会指向生成的代码。""")
                    .isFalse();
            assertThat(result.failures().get("Annotated")).contains("@Model");

            assertThat(type.getMethod("version").invoke(null))
                    .as("没有被换 —— 等 L3 重启")
                    .isEqualTo("v1");
        }

        @Test
        @DisplayName("非 .class 的文件被忽略")
        void nonClassFilesIgnored() {
            var result = SWAPPER.redefine(List.of(
                    Path.of("/app/src/main/resources/views/home.jblade")));

            assertThat(result.ok()).isTrue();
            assertThat(result.swapped()).isEmpty();
            assertThat(result.skipped()).isEmpty();
        }

        @Test
        @DisplayName("推不出类名的 .class 记为失败 —— 调用方会降级")
        void unresolvableClassNameFails() {
            var result = SWAPPER.redefine(List.of(Path.of("/tmp/Orphan.class")));

            assertThat(result.ok()).isFalse();
            assertThat(result.describe()).contains("失败");
        }
    }
}
