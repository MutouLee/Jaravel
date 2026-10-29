package io.jaravel.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RoutingTest {

    // ---------- 被测控制器 ----------

    public static class UserController {
        public Object index()                      { return "all users"; }
        public Object show(Long id)                { return "user " + id; }
        public Object store(String body)           { return "created " + body; }
        public Object update(Long id, String body) { return "updated " + id; }
        public Object destroy(Long id)             { return "deleted " + id; }
    }

    /** 完整资源控制器 */
    public static class PostController {
        public Object index()                      { return null; }
        public Object create()                     { return null; }
        public Object store(String b)              { return null; }
        public Object show(Long post)              { return null; }
        public Object edit(Long post)              { return null; }
        public Object update(Long post, String b)  { return null; }
        public Object destroy(Long post)           { return null; }
    }

    /** 只有 2 个方法,resource() 应报错 */
    public static class HalfController {
        public Object index()         { return null; }
        public Object show(Long id)   { return null; }
    }

    private Router router() { return new Router(); }

    // ================= 方法引用内省 =================

    @Nested
    @DisplayName("方法引用内省(序列化 lambda)")
    class Introspection {

        @Test
        @DisplayName("从方法引用提取控制器类与方法名")
        void extractsControllerAndAction() {
            var r = router();
            r.get("/users", UserController::index);
            r.get("/users/{id}", UserController::show);

            var routes = r.routes();
            assertThat(routes).hasSize(2);
            assertThat(routes.get(0).controller()).isEqualTo(UserController.class);
            assertThat(routes.get(0).action()).isEqualTo("index");
            assertThat(routes.get(1).action()).isEqualTo("show");
        }

        @Test
        @DisplayName("javac 按 arity 选对重载:index→H0、show→H1、update→H2")
        void arityOverloadResolution() {
            var r = router();
            r.get("/a", UserController::index);                  // H0
            r.get("/b/{id}", UserController::show);              // H1
            r.put("/c/{id}", UserController::update);            // H2

            assertThat(r.routes()).extracting(Route::arity).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("传 lambda 而非方法引用时,报错要说清该怎么做")
        void lambdaIsRejectedWithGuidance() {
            var r = router();
            assertThatThrownBy(() -> r.get("/x", (Handler.H0<UserController>) c -> "inline"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能是 lambda 表达式")
                    .hasMessageContaining("方法引用")
                    .hasMessageContaining("r.view(");
        }
    }

    // ================= 匹配 =================

    @Nested
    @DisplayName("路由匹配")
    class Matching {

        @Test void staticRouteExactMatch() {
            var r = router();
            r.get("/users", UserController::index);
            var coll = r.compile();

            assertThat(coll.match("GET", "/users")).isPresent();
            assertThat(coll.match("GET", "/users/")).isPresent();      // 尾斜杠归一化
            assertThat(coll.match("POST", "/users")).isEmpty();        // 方法不符
            assertThat(coll.match("GET", "/user")).isEmpty();
        }

        @Test void pathParameterExtraction() {
            var r = router();
            r.get("/users/{id}", UserController::show);
            var m = r.compile().match("GET", "/users/42").orElseThrow();

            assertThat(m.params()).containsEntry("id", "42");
            assertThat(m.route().paramNames()).containsExactly("id");
        }

        @Test void multipleParameters() {
            var r = router();
            r.get("/users/{userId}/posts/{postId}", UserController::update);
            var m = r.compile().match("GET", "/users/7/posts/99").orElseThrow();
            assertThat(m.params()).containsEntry("userId", "7").containsEntry("postId", "99");
        }

        @Test
        @DisplayName("参数不跨路径段:{id} 不应匹配 42/extra")
        void parameterDoesNotSpanSegments() {
            var r = router();
            r.get("/users/{id}", UserController::show);
            assertThat(r.compile().match("GET", "/users/42/extra")).isEmpty();
        }

        @Test
        @DisplayName("通配参数 {path*} 可跨段")
        void wildcardSpansSegments() {
            var r = router();
            r.get("/files/{path*}", UserController::show);
            var m = r.compile().match("GET", "/files/a/b/c.txt").orElseThrow();
            assertThat(m.params()).containsEntry("path", "a/b/c.txt");
        }

        @Test
        @DisplayName("可选参数:带与不带都应匹配")
        void optionalParameter() {
            var r = router();
            r.get("/users/{id?}", UserController::show);
            var coll = r.compile();

            assertThat(coll.match("GET", "/users/42").orElseThrow().params())
                    .containsEntry("id", "42");
            assertThat(coll.match("GET", "/users")).isPresent();       // 不带参数也要命中
        }

        @Test
        @DisplayName("正则约束生效,不符则不匹配")
        void constraints() {
            var r = router();
            r.get("/users/{id}", UserController::show).whereNumber("id");
            var coll = r.compile();

            assertThat(coll.match("GET", "/users/42")).isPresent();
            assertThat(coll.match("GET", "/users/abc")).isEmpty();
        }

        @Test
        @DisplayName("URI 里没有的参数名调用 where() 应立刻报错")
        void whereOnUnknownParamFails() {
            var r = router();
            var route = r.get("/users", UserController::index);
            assertThatThrownBy(() -> route.where("id", "\\d+"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("没有参数 {id}");
        }

        @Test
        @DisplayName("路径存在但方法不符 → 可查出允许的方法,用于 405 的 Allow 头")
        void allowedMethodsFor405() {
            var r = router();
            r.get("/users", UserController::index);
            r.post("/users", UserController::store);
            assertThat(r.compile().allowedMethods("/users")).containsExactly("GET", "POST");
        }

        @Test
        @DisplayName("静态路由优先于动态路由 —— /posts/create 不应被 /posts/{id} 吃掉")
        void staticWinsOverDynamic() {
            var r = router();
            r.get("/posts/{id}", UserController::show);
            r.get("/posts/create", UserController::index);

            var m = r.compile().match("GET", "/posts/create").orElseThrow();
            assertThat(m.route().action()).isEqualTo("index");
        }
    }

    // ================= 调用 =================

    @Nested
    @DisplayName("Invoker 调用")
    class Invocation {

        @Test void callsControllerMethod() {
            var r = router();
            r.get("/users/{id}", UserController::show);
            var route = r.routes().get(0);

            Object result = route.invoker().invoke(new UserController(), new Object[]{42L});
            assertThat(result).isEqualTo("user 42");
        }

        @Test void zeroArityCall() {
            var r = router();
            r.get("/users", UserController::index);
            assertThat(r.routes().get(0).invoker().invoke(new UserController(), new Object[0]))
                    .isEqualTo("all users");
        }

        @Test void twoArityCall() {
            var r = router();
            r.put("/users/{id}", UserController::update);
            assertThat(r.routes().get(0).invoker()
                    .invoke(new UserController(), new Object[]{7L, "data"}))
                    .isEqualTo("updated 7");
        }
    }

    // ================= 分组 =================

    @Nested
    @DisplayName("分组")
    class Groups {

        @Test void prefixMiddlewareAndNamePrefix() {
            var r = router();
            r.group(g -> g.prefix("/admin").middleware("auth", "can:admin").name("admin."), g -> {
                g.get("/dashboard", UserController::index).name("dashboard");
            });

            var route = r.routes().get(0);
            assertThat(route.uri()).isEqualTo("/admin/dashboard");
            assertThat(route.middlewareNames()).containsExactly("auth", "can:admin");
            assertThat(route.routeName()).contains("admin.dashboard");
        }

        @Test
        @DisplayName("分组退出后恢复上下文,后续路由不继承前缀")
        void contextIsRestoredAfterGroup() {
            var r = router();
            r.group(g -> g.prefix("/admin"), g -> g.get("/a", UserController::index));
            r.get("/b", UserController::index);

            assertThat(r.routes()).extracting(Route::uri).containsExactly("/admin/a", "/b");
        }

        @Test
        @DisplayName("嵌套分组:前缀与中间件逐层累加")
        void nestedGroups() {
            var r = router();
            r.group(g -> g.prefix("/api").middleware("throttle").name("api."), g1 -> {
                g1.group(g -> g.prefix("/v1").middleware("auth").name("v1."), g2 -> {
                    g2.get("/users", UserController::index).name("users");
                });
            });

            var route = r.routes().get(0);
            assertThat(route.uri()).isEqualTo("/api/v1/users");
            assertThat(route.middlewareNames()).containsExactly("throttle", "auth");
            assertThat(route.routeName()).contains("api.v1.users");
        }

        @Test
        @DisplayName("分组体内抛异常也要恢复上下文")
        void contextRestoredOnException() {
            var r = router();
            assertThatThrownBy(() -> r.group(g -> g.prefix("/x"), g -> {
                throw new RuntimeException("boom");
            })).hasMessage("boom");

            r.get("/clean", UserController::index);
            assertThat(r.routes().get(0).uri()).isEqualTo("/clean");
        }
    }

    // ================= 资源路由 =================

    @Nested
    @DisplayName("资源路由")
    class Resources {

        @Test
        @DisplayName("resource() 展开为 7 条 + 1 条 PATCH 别名")
        void fullResourceExpansion() {
            var r = router();
            r.resource("/posts", PostController.class);

            var routes = r.routes();
            assertThat(routes).hasSize(8);   // 7 条 + update 的 PATCH

            assertThat(routes).extracting(x -> x.method() + " " + x.uri())
                    .contains("GET /posts", "GET /posts/create", "POST /posts",
                              "GET /posts/{post}", "GET /posts/{post}/edit",
                              "PUT /posts/{post}", "PATCH /posts/{post}",
                              "DELETE /posts/{post}");
        }

        @Test
        @DisplayName("路径参数名用资源名的单数:/posts → {post}")
        void parameterNameIsSingular() {
            var r = router();
            r.resource("/categories", PostController.class);
            assertThat(r.routes()).extracting(Route::uri)
                    .contains("/categories/{category}");
        }

        @Test void routeNamesFollowConvention() {
            var r = router();
            r.resource("/posts", PostController.class);
            assertThat(r.routes().stream().map(x -> x.routeName().orElse("")).toList())
                    .contains("posts.index", "posts.create", "posts.store",
                              "posts.show", "posts.edit", "posts.update", "posts.destroy");
        }

        @Test void onlyAndExcept() {
            var r1 = router();
            r1.resource("/posts", PostController.class).only("index", "show");
            assertThat(r1.routes()).hasSize(2);

            var r2 = router();
            r2.resource("/posts", PostController.class).except("destroy");
            assertThat(r2.routes()).extracting(Route::action).doesNotContain("destroy");
        }

        @Test
        @DisplayName("apiResource 去掉 create / edit")
        void apiResourceDropsFormRoutes() {
            var r = router();
            r.apiResource("/posts", PostController.class);
            assertThat(r.routes()).extracting(Route::action)
                    .doesNotContain("create", "edit")
                    .contains("index", "store", "show", "update", "destroy");
        }

        @Test
        @DisplayName("★ 控制器缺方法时启动即报错,列出缺哪些并给出 except() 现成写法")
        void missingActionsFailFastWithActionableMessage() {
            var r = router();
            r.resource("/half", HalfController.class);

            assertThatThrownBy(r::routes)
                    .isInstanceOf(ResourceRegistration.MissingResourceActionException.class)
                    .hasMessageContaining("控制器上不存在")
                    .hasMessageContaining("create")
                    .hasMessageContaining("store")
                    .hasMessageContaining("destroy")
                    .hasMessageContaining(".except(")
                    .hasMessageContaining("控制器已有的 public 方法");
        }

        @Test
        @DisplayName("缺的方法被 except 排除后应正常注册")
        void exceptingMissingActionsWorks() {
            var r = router();
            r.resource("/half", HalfController.class)
             .except("create", "store", "edit", "update", "destroy");
            assertThat(r.routes()).extracting(Route::action).containsExactly("index", "show");
        }

        @Test
        @DisplayName("only/except 写了不存在的 action 名应报错并给相似建议")
        void unknownActionNameIsRejected() {
            var r = router();
            r.resource("/posts", PostController.class).except("delete");   // 正确的是 destroy
            assertThatThrownBy(r::routes)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不是资源路由的 action")
                    .hasMessageContaining("destroy");
        }

        @Test void resourceInsideGroupInheritsPrefix() {
            var r = router();
            r.group(g -> g.prefix("/api/v1").middleware("auth"), g -> {
                g.apiResource("/posts", PostController.class);
            });
            assertThat(r.routes()).allSatisfy(x -> {
                assertThat(x.uri()).startsWith("/api/v1/posts");
                assertThat(x.middlewareNames()).contains("auth");
            });
        }

        @Test void resourceInvokerCallsRealMethod() {
            var r = router();
            r.resource("/posts", PostController.class).only("show");
            var route = r.routes().get(0);
            // PostController#show 返回 null,这里只验证能调通不抛异常
            assertThat(route.invoker().invoke(new PostController(), new Object[]{1L})).isNull();
        }
    }

    // ================= 路由表 =================

    @Nested
    @DisplayName("RouteCollection")
    class Collection {

        @Test
        @DisplayName("重复的 method+uri 在编译期报错(V2 验证的校验项之一)")
        void duplicateRouteIsRejected() {
            var r = router();
            r.get("/users", UserController::index);
            r.get("/users", UserController::index);

            assertThatThrownBy(r::compile)
                    .isInstanceOf(RouteCollection.DuplicateRouteException.class)
                    .hasMessageContaining("路由重复定义")
                    .hasMessageContaining("GET /users");
        }

        @Test
        @DisplayName("重复的路由名也要报错")
        void duplicateRouteNameIsRejected() {
            var r = router();
            r.get("/a", UserController::index).name("same");
            r.get("/b", UserController::index).name("same");

            assertThatThrownBy(r::compile)
                    .isInstanceOf(RouteCollection.DuplicateRouteException.class)
                    .hasMessageContaining("路由名重复")
                    .hasMessageContaining("same");
        }

        @Test void lookupByName() {
            var r = router();
            r.get("/users/{id}", UserController::show).name("users.show");
            assertThat(r.compile().byName("users.show")).isPresent();
            assertThat(r.compile().byName("nope")).isEmpty();
        }

        @Test
        @DisplayName("render() 产出 route:list 的内容")
        void renderForRouteList() {
            var r = router();
            r.get("/users", UserController::index).name("users.index");
            String out = r.compile().render();
            assertThat(out).contains("METHOD", "URI", "ACTION", "NAME")
                           .contains("GET", "/users", "UserController#index", "users.index");
        }

        @Test void sizeCountsBoth() {
            var r = router();
            r.get("/a", UserController::index);
            r.get("/b/{id}", UserController::show);
            assertThat(r.compile().size()).isEqualTo(2);
        }
    }
}
