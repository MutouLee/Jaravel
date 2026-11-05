package io.jaravel.events.apt;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

/**
 * {@code @Listener} 处理器。
 *
 * <h2>★ 为什么这个测试是补上来的</h2>
 * <p>接上覆盖率之后,这个模块显示 **0%** —— 629 条指令,**一条测试都没有**。
 *
 * <p>它此前「有覆盖」是一种错觉:{@code jaravel-events-tests} 里那些测试
 * 确实在用生成出来的绑定,但那是**编译期**跑的处理器 ——
 * 跑在另一个模块的 javac 里,不在任何测试 agent 之下。
 * 于是它既不产生覆盖率数据,也就看不出自己没被直接测过。
 * 而没被直接测的恰恰是它最该测的那一面:**报错**。
 *
 * <p>生成代码的框架,报错信息就是它的 UI(与 {@code ProcessorErrorTest}
 * 开头那段同一个理由):用户写错方法名,得到的要么是
 * 「没有 handle 方法 —— 它不会收到任何事件」,要么是运行时
 * 「监听器不触发」而日志里什么都没有。后者等于没有报错。
 */
class ListenerProcessorTest {

    private static final String PRELUDE = """
            package t;
            import io.jaravel.events.Listener;
            """;

    private static final JavaFileObject EVENT = JavaFileObjects.forSourceString("t.OrderPaid",
            "package t;\npublic record OrderPaid(long orderId) {}\n");

    // ================= 报错 =================

    @Nested
    @DisplayName("报错(这个模块的 UI)")
    class Errors {

        @Test
        @DisplayName("★ 一个 handle 都没有 → 报错,而不是生成一个什么都不做的监听器")
        void noHandleMethod() {
            Compilation c = compile(listener("""
                    @Listener
                    public class Silent {
                        public void onOrderPaid(OrderPaid e) { }
                    }
                    """, "t.Silent"));

            assertThat(c).failed();
            assertThat(c).hadErrorContaining("没有 handle 方法");
            // 要说清为什么不放过它
            assertThat(c).hadErrorContaining("方法名写错了");
        }

        @Test
        @DisplayName("handle 有两个参数 → 指出监听器的依赖从构造器注入")
        void handleWithTwoParameters() {
            Compilation c = compile(listener("""
                    @Listener
                    public class Wrong {
                        public void handle(OrderPaid e, String extra) { }
                    }
                    """, "t.Wrong"));

            assertThat(c).failed();
            assertThat(c).hadErrorContaining("必须恰好有一个参数");
            // 与 Job 的 handle 不同那一点要讲清楚 —— 否则用户会以为是笔误
            assertThat(c).hadErrorContaining("构造器");
        }

        @Test
        @DisplayName("handle 不是 public → 生成的绑定在另一个包里,调不到")
        void handleIsNotPublic() {
            Compilation c = compile(listener("""
                    @Listener
                    public class Hidden {
                        void handle(OrderPaid e) { }
                    }
                    """, "t.Hidden"));

            assertThat(c).failed();
            assertThat(c).hadErrorContaining("必须是 public");
        }

        @Test
        @DisplayName("handle 是 static → 监听器是容器建的实例")
        void handleIsStatic() {
            Compilation c = compile(listener("""
                    @Listener
                    public class Statics {
                        public static void handle(OrderPaid e) { }
                    }
                    """, "t.Statics"));

            assertThat(c).failed();
            assertThat(c).hadErrorContaining("不能是 static");
        }

        @Test
        @DisplayName("★ 返回类型不是 void/boolean → 那个值会被静默丢弃")
        void handleReturnsSomethingElse() {
            Compilation c = compile(listener("""
                    @Listener
                    public class Returns {
                        public String handle(OrderPaid e) { return "x"; }
                    }
                    """, "t.Returns"));

            assertThat(c).failed();
            assertThat(c).hadErrorContaining("只能是 void 或 boolean");
            // 一个被静默丢弃的返回值会让人以为它有作用 ——
            // 比如以为 return null 能中止传播。
            assertThat(c).hadErrorContaining("静默丢弃");
        }

        @Test
        @DisplayName("handle 的参数是基本类型 → 事件必须是类/record")
        void handleTakesAPrimitive() {
            Compilation c = compile(listener("""
                    @Listener
                    public class Primitive {
                        public void handle(long orderId) { }
                    }
                    """, "t.Primitive"));

            assertThat(c).failed();
            assertThat(c).hadErrorContaining("必须是一个类/record 类型");
        }

        @Test
        @DisplayName("抽象类 → 容器建不出来")
        void abstractListener() {
            Compilation c = compile(listener("""
                    @Listener
                    public abstract class Abstract {
                        public void handle(OrderPaid e) { }
                    }
                    """, "t.Abstract"));

            assertThat(c).failed();
            assertThat(c).hadErrorContaining("不能标在抽象类上");
        }

        @Test
        @DisplayName("非静态内部类 → 加 static 或挪到顶层")
        void innerClassListener() {
            Compilation c = compile(listener("""
                    public class Outer {
                        @Listener
                        public class Inner {
                            public void handle(OrderPaid e) { }
                        }
                    }
                    """, "t.Outer"));

            assertThat(c).failed();
            assertThat(c).hadErrorContaining("非静态内部类");
        }

        @Test
        @DisplayName("★ 同一个事件两个 handle → 编译不过(而拦住它的是 javac,不是处理器)")
        void duplicateEventType() {
            Compilation c = compile(listener("""
                    @Listener
                    public class Twice {
                        public void handle(OrderPaid e) { }
                        public void handle(OrderPaid again) { }
                    }
                    """, "t.Twice"));

            assertThat(c).failed();
        }

        /**
         * ★ 处理器里那个「两个 handle 都接收同一个事件」的检查**走不到**。
         *
         * <p>写这条测试时才发现:Java 的重载规则本身就不允许两个参数擦除后
         * 相同的方法 —— {@code handle(Box<String>)} 与 {@code handle(Box<Integer>)}
         * 会被 javac 判成 name clash,根本到不了注解处理这一步。
         * 而不同的声明类型不可能有相同的擦除全名。
         *
         * <p>所以那段检查是**防御性的死分支**。不删它(它不花成本,而且
         * 将来若支持继承来的 handle 就会变成活的),但把这件事记在这里 ——
         * 否则下一个看覆盖率的人会去为它编一个测试,
         * 然后像我一样发现编不出来。
         */
        @Test
        @DisplayName("擦除后同签名的两个 handle 是 javac 先拦的 —— 处理器那段检查是死分支")
        void duplicateAfterErasureIsRejectedByJavacFirst() {
            Compilation c = compile(
                    JavaFileObjects.forSourceString("t.Box",
                            "package t;\npublic record Box<T>(T value) {}\n"),
                    JavaFileObjects.forSourceString("t.Erased", PRELUDE + """
                            @Listener
                            public class Erased {
                                public void handle(Box<String> a) { }
                                public void handle(Box<Integer> b) { }
                            }
                            """));

            assertThat(c).failed();

            // 报的是 javac 的 name clash,不是处理器那句「两个 handle 都接收」。
            //
            // ⚠ 不断言 javac 那条消息的文字:它**跟着 locale 变**
            // (本机跑出来是中文的「名称冲突…具有相同擦除」)。
            // 断言自己的消息**没出现**才是这条测试真正的主张,
            // 而我们自己的消息是固定的。
            String messages = c.diagnostics().stream()
                    .map(d -> d.getMessage(java.util.Locale.ROOT))
                    .reduce("", String::concat);
            org.assertj.core.api.Assertions.assertThat(messages)
                    .doesNotContain("两个 handle 都接收");
        }
    }

    // ================= 生成 =================

    @Nested
    @DisplayName("生成的绑定")
    class Generation {

        @Test
        @DisplayName("★ void 的 handle 生成「调用完 return true」—— 忘了 return 就会跳过后续监听器")
        void voidHandleNeverAborts() {
            Compilation c = compile(listener("""
                    @Listener
                    public class Notify {
                        public void handle(OrderPaid e) { }
                    }
                    """, "t.Notify"));

            assertThat(c).succeeded();
            assertThat(c).generatedSourceFile("t.Notify$$OrderPaid")
                    .contentsAsUtf8String()
                    .contains("return true;");
        }

        @Test
        @DisplayName("boolean 的 handle 直接把返回值交出去(false = 中止传播)")
        void booleanHandleCanAbort() {
            Compilation c = compile(listener("""
                    @Listener
                    public class Gate {
                        public boolean handle(OrderPaid e) { return false; }
                    }
                    """, "t.Gate"));

            assertThat(c).succeeded();
            assertThat(c).generatedSourceFile("t.Gate$$OrderPaid")
                    .contentsAsUtf8String()
                    .contains("return ((t.Gate) listener).handle((t.OrderPaid) event);");
        }

        @Test
        @DisplayName("★ 装箱的 Boolean 为 null 时当 true —— 把 null 当 false 会静默中止整条链")
        void boxedNullMeansContinue() {
            Compilation c = compile(listener("""
                    @Listener
                    public class Boxed {
                        public Boolean handle(OrderPaid e) { return null; }
                    }
                    """, "t.Boxed"));

            assertThat(c).succeeded();
            assertThat(c).generatedSourceFile("t.Boxed$$OrderPaid")
                    .contentsAsUtf8String()
                    .contains("result == null || result");
        }

        @Test
        @DisplayName("@Listener(priority=…) 进到生成的 priority() 里")
        void priorityIsCarriedOver() {
            Compilation c = compile(listener("""
                    @Listener(priority = 50)
                    public class Early {
                        public void handle(OrderPaid e) { }
                    }
                    """, "t.Early"));

            assertThat(c).succeeded();
            assertThat(c).generatedSourceFile("t.Early$$OrderPaid")
                    .contentsAsUtf8String()
                    .contains("return 50;");
        }

        @Test
        @DisplayName("一个监听器听两个事件 → 两个绑定,而不是一个带 switch 的类")
        void oneBindingPerHandle() {
            Compilation c = compile(
                    JavaFileObjects.forSourceString("t.OrderRefunded",
                            "package t;\npublic record OrderRefunded(long orderId) {}\n"),
                    JavaFileObjects.forSourceString("t.Audit", PRELUDE + """
                            @Listener
                            public class Audit {
                                public void handle(OrderPaid e) { }
                                public void handle(OrderRefunded e) { }
                            }
                            """));

            assertThat(c).succeeded();
            assertThat(c).generatedSourceFile("t.Audit$$OrderPaid").contentsAsUtf8String()
                    .contains("t.OrderPaid.class");
                            // 按事件类型一个绑定,总线的 map 就能直接是
                // 「事件类型 → 绑定列表」—— 分发时不需要任何类型判断。
                assertThat(c)
                    .generatedSourceFile("t.Audit$$OrderRefunded").contentsAsUtf8String()
                    .contains("t.OrderRefunded.class");
        }

        @Test
        @DisplayName("★ 嵌套监听器的包名要从 Elements 取 —— 按点切会生成一个与外层类同名的包")
        void nestedListenerPackageName() {
            Compilation c = compile(JavaFileObjects.forSourceString("t.Host", PRELUDE + """
                    public class Host {
                        @Listener
                        public static class Inner {
                            public void handle(OrderPaid e) { }
                        }
                    }
                    """));

                            // 嵌套类的限定名是 t.Host.Inner,按最后一个点切会把
                // t.Host 当成包名 —— javac 报「程序包与带有相同名称的类冲突」。
                // 这个 bug 只在监听器是嵌套类时出现,而测试里的监听器
                // 恰恰几乎总是嵌套类。
                assertThat(c)
                    .succeeded();
        }

        @Test
        @DisplayName("索引文件列出全部绑定 —— 启动时靠它避免扫包")
        void indexListsEveryBinding() {
            Compilation c = compile(listener("""
                    @Listener
                    public class Indexed {
                        public void handle(OrderPaid e) { }
                    }
                    """, "t.Indexed"));

            assertThat(c).succeeded();
            assertThat(c)
                    .generatedFile(javax.tools.StandardLocation.CLASS_OUTPUT,
                            "META-INF/jaravel/listeners.idx")
                    .contentsAsUtf8String()
                    .contains("t.Indexed$$OrderPaid");
        }

        @Test
        @DisplayName("没有 @Listener 时不生成索引 —— 一个空索引文件会让人以为扫到了零个")
        void noIndexWithoutListeners() {
            Compilation c = javac().withProcessors(new ListenerProcessor())
                    .compile(JavaFileObjects.forSourceString("t.Plain",
                            "package t;\npublic class Plain {}\n"));

            assertThat(c).succeeded();
        }
    }

    // ================= 工具 =================

    private static Compilation compile(JavaFileObject... sources) {
        var all = new JavaFileObject[sources.length + 1];
        all[0] = EVENT;
        System.arraycopy(sources, 0, all, 1, sources.length);
        return javac().withProcessors(new ListenerProcessor()).compile(all);
    }

    private static JavaFileObject listener(String body, String name) {
        return JavaFileObjects.forSourceString(name, PRELUDE + body);
    }
}
