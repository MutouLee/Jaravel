package io.jaravel.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 中间件链的解析:分组展开、别名、参数化、去重、优先级、排除 */
class MiddlewareStackTest {

    /** 记录执行顺序的中间件基类 */
    abstract static class Recording implements Middleware {
        static final List<String> TRACE = new ArrayList<>();

        @Override
        public Response handle(Request request, Function<Request, Response> next) {
            TRACE.add(getClass().getSimpleName());
            return next.apply(request);
        }
    }

    static class TrimStrings extends Recording {}
    static class HandleCors extends Recording {}
    static class StartSession extends Recording {}
    static class VerifyCsrf extends Recording {}
    static class Authenticate extends Recording {}

    /** 带参数的:限流 */
    static class Throttle implements Middleware.Parameterized {
        int maxAttempts = 60;
        int windowMinutes = 1;

        @Override public void configure(String... params) {
            if (params.length > 0) maxAttempts = Integer.parseInt(params[0]);
            if (params.length > 1) windowMinutes = Integer.parseInt(params[1]);
        }

        @Override public Response handle(Request request, Function<Request, Response> next) {
            return next.apply(request);
        }
    }

    /** 没实现 Parameterized 却被传了参数 */
    static class NotParameterized extends Recording {}

    private static MiddlewareStack stackOf(Kernel kernel) {
        return new MiddlewareStack(kernel, new MiddlewareStack.Factory() {
            @Override
            @SuppressWarnings("unchecked")
            public <T extends Middleware> T create(Class<T> type) {
                try {
                    var ctor = type.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    return (T) ctor.newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    private static List<String> names(List<Middleware> chain) {
        return chain.stream().map(m -> m.getClass().getSimpleName()).toList();
    }

    // ================= 基础 =================

    @Test
    @DisplayName("全局中间件按声明顺序,且无需在路由上声明")
    void globalInDeclaredOrder() {
        var kernel = new Kernel() {
            @Override protected List<Class<? extends Middleware>> global() {
                return List.of(TrimStrings.class, HandleCors.class);
            }
        };

        assertThat(names(stackOf(kernel).resolve(List.of(), List.of())))
                .containsExactly("TrimStrings", "HandleCors");
    }

    @Test
    @DisplayName("分组名展开为整组")
    void groupExpands() {
        var kernel = new Kernel() {
            @Override protected Map<String, List<Class<? extends Middleware>>> groups() {
                return Map.of("web", List.of(StartSession.class, VerifyCsrf.class));
            }
        };

        assertThat(names(stackOf(kernel).resolve(List.of("web"), List.of())))
                .containsExactly("StartSession", "VerifyCsrf");
    }

    @Test
    @DisplayName("全局 + 分组 + 别名 混合,全局在前")
    void globalThenDeclared() {
        var kernel = kernelWithEverything();

        assertThat(names(stackOf(kernel).resolve(List.of("web", "auth"), List.of())))
                .containsExactly("TrimStrings", "StartSession", "VerifyCsrf", "Authenticate");
    }

    // ================= 参数化 =================

    @Test
    @DisplayName("alias:参数 透传给 configure()")
    void parameterizedConfigure() {
        var kernel = kernelWithEverything();
        var chain = stackOf(kernel).resolve(List.of("throttle:10,5"), List.of());

        var throttle = (Throttle) chain.get(chain.size() - 1);
        assertThat(throttle.maxAttempts).isEqualTo(10);
        assertThat(throttle.windowMinutes).isEqualTo(5);
    }

    @Test
    @DisplayName("不同路由的参数互不串台 —— 共享实例会让限额被后来者覆盖")
    void parameterizedInstancesAreNotShared() {
        var stack = stackOf(kernelWithEverything());

        var tight = stack.resolve(List.of("throttle:10,1"), List.of());
        var loose = stack.resolve(List.of("throttle:1000,60"), List.of());

        var a = (Throttle) tight.get(tight.size() - 1);
        var b = (Throttle) loose.get(loose.size() - 1);

        assertThat(a).isNotSameAs(b);
        // 若复用单例,a.maxAttempts 会被 b 的 1000 覆盖
        assertThat(a.maxAttempts).isEqualTo(10);
        assertThat(b.maxAttempts).isEqualTo(1000);
    }

    @Test
    @DisplayName("同一中间件带不同参数时不去重 —— 叠加限流是合法诉求")
    void differentParamsAreNotDeduped() {
        var chain = stackOf(kernelWithEverything())
                .resolve(List.of("throttle:10,1", "throttle:1000,60"), List.of());

        assertThat(names(chain)).filteredOn(n -> n.equals("Throttle")).hasSize(2);
    }

    @Test
    @DisplayName("给没实现 Parameterized 的中间件传参数时,报错里直接给出要写的代码")
    void parametersOnNonParameterized() {
        var kernel = new Kernel() {
            @Override protected Map<String, Class<? extends Middleware>> aliases() {
                return Map.of("plain", NotParameterized.class);
            }
        };

        assertThatThrownBy(() -> stackOf(kernel).resolve(List.of("plain:1"), List.of()))
                .hasMessageContaining("没有实现 Middleware.Parameterized")
                .hasMessageContaining("configure(String... params)");
    }

    @Test
    @DisplayName("分组不能带参数")
    void groupCannotTakeParameters() {
        assertThatThrownBy(() ->
                stackOf(kernelWithEverything()).resolve(List.of("web:1"), List.of()))
                .hasMessageContaining("分组 \"web\" 不能带参数");
    }

    // ================= 去重 =================

    @Test
    @DisplayName("同一中间件出现多次只跑一次 —— 会话被重置、限流翻倍都是真实后果")
    void duplicatesRemoved() {
        var kernel = new Kernel() {
            @Override protected Map<String, List<Class<? extends Middleware>>> groups() {
                return Map.of("web", List.of(StartSession.class, Authenticate.class));
            }
            @Override protected Map<String, Class<? extends Middleware>> aliases() {
                return Map.of("auth", Authenticate.class);
            }
        };

        // web 组里已含 auth,路由又写了一次
        assertThat(names(stackOf(kernel).resolve(List.of("web", "auth"), List.of())))
                .containsExactly("StartSession", "Authenticate");
    }

    @Test
    @DisplayName("无参中间件共享实例")
    void statelessMiddlewareIsShared() {
        var stack = stackOf(kernelWithEverything());

        var first = stack.resolve(List.of("auth"), List.of());
        var second = stack.resolve(List.of("auth"), List.of());

        assertThat(first.get(first.size() - 1)).isSameAs(second.get(second.size() - 1));
    }

    // ================= 优先级 =================

    @Test
    @DisplayName("优先级覆盖声明顺序 —— auth 必须在 StartSession 之后")
    void priorityOverridesDeclarationOrder() {
        var kernel = new Kernel() {
            @Override protected Map<String, Class<? extends Middleware>> aliases() {
                return Map.of("auth", Authenticate.class, "session", StartSession.class);
            }
            @Override protected List<Class<? extends Middleware>> priority() {
                return List.of(StartSession.class, Authenticate.class);
            }
        };

        // 故意把 auth 写在前面
        assertThat(names(stackOf(kernel).resolve(List.of("auth", "session"), List.of())))
                .containsExactly("StartSession", "Authenticate");
    }

    @Test
    @DisplayName("优先级只约束彼此,不会把中间件提到全局之前")
    void priorityDoesNotJumpOverGlobal() {
        var kernel = new Kernel() {
            @Override protected List<Class<? extends Middleware>> global() {
                return List.of(TrimStrings.class);
            }
            @Override protected Map<String, Class<? extends Middleware>> aliases() {
                return Map.of("auth", Authenticate.class, "session", StartSession.class);
            }
            @Override protected List<Class<? extends Middleware>> priority() {
                return List.of(StartSession.class, Authenticate.class);
            }
        };

        assertThat(names(stackOf(kernel).resolve(List.of("auth", "session"), List.of())))
                .containsExactly("TrimStrings", "StartSession", "Authenticate");
    }

    @Test
    @DisplayName("不在优先级列表里的保持声明顺序")
    void unprioritizedKeepDeclarationOrder() {
        var kernel = new Kernel() {
            @Override protected Map<String, Class<? extends Middleware>> aliases() {
                return Map.of("cors", HandleCors.class, "trim", TrimStrings.class,
                              "auth", Authenticate.class);
            }
            @Override protected List<Class<? extends Middleware>> priority() {
                return List.of(Authenticate.class);
            }
        };

        assertThat(names(stackOf(kernel).resolve(List.of("cors", "auth", "trim"), List.of())))
                .containsExactly("HandleCors", "Authenticate", "TrimStrings");
    }

    // ================= 排除 =================

    @Test
    @DisplayName("withoutMiddleware 按别名排除")
    void excludeByAlias() {
        assertThat(names(stackOf(kernelWithEverything())
                .resolve(List.of("web", "auth"), List.of("auth"))))
                .doesNotContain("Authenticate");
    }

    @Test
    @DisplayName("withoutMiddleware 按类名排除 —— 组里的成员往往没有别名")
    void excludeByClassName() {
        // 典型诉求:这条路由属于 web 组,但不要 CSRF 校验(webhook 回调)
        assertThat(names(stackOf(kernelWithEverything())
                .resolve(List.of("web"), List.of("VerifyCsrf"))))
                .contains("StartSession")
                .doesNotContain("VerifyCsrf");
    }

    @Test
    @DisplayName("排除全局中间件也生效")
    void excludeGlobal() {
        assertThat(names(stackOf(kernelWithEverything())
                .resolve(List.of(), List.of("TrimStrings"))))
                .doesNotContain("TrimStrings");
    }

    @Test
    @DisplayName("排除名字写错时报错,而不是静默无效")
    void unknownExclusionFailsLoud() {
        assertThatThrownBy(() -> stackOf(kernelWithEverything())
                .resolve(List.of("web"), List.of("VerifyCsrfToken")))
                .hasMessageContaining("没有匹配到任何中间件")
                .hasMessageContaining("静默忽略最危险");
    }

    // ================= 未知名字 =================

    @Test
    @DisplayName("未知中间件名给出「是否想写」与已注册清单")
    void unknownNameIsActionable() {
        assertThatThrownBy(() -> stackOf(kernelWithEverything())
                .resolve(List.of("ath"), List.of()))
                .hasMessageContaining("未知的中间件 \"ath\"")
                .hasMessageContaining("是否想写 \"auth\"")
                .hasMessageContaining("已注册的别名");
    }

    @Test
    @DisplayName("空内核:什么都不声明也能跑")
    void emptyKernel() {
        assertThat(Kernel.empty().globalMiddleware()).isEmpty();
        assertThat(stackOf(Kernel.empty()).resolve(List.of(), List.of())).isEmpty();
    }

    // ================= 夹具 =================

    private static Kernel kernelWithEverything() {
        return new Kernel() {
            @Override protected List<Class<? extends Middleware>> global() {
                return List.of(TrimStrings.class);
            }
            @Override protected Map<String, List<Class<? extends Middleware>>> groups() {
                return Map.of("web", List.of(StartSession.class, VerifyCsrf.class));
            }
            @Override protected Map<String, Class<? extends Middleware>> aliases() {
                return Map.of("auth", Authenticate.class, "throttle", Throttle.class);
            }
        };
    }
}
