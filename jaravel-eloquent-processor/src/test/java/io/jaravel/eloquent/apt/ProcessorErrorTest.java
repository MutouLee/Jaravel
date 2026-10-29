package io.jaravel.eloquent.apt;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

/**
 * 报错信息的测试 —— docs/06 §6 把这组列为**本模块第一优先级**,早于功能实现。
 *
 * <p>理由是:生成代码的框架,错误信息就是它的 UI。用户写错一个列名,
 * 得到的要么是「列 rolee 不存在,是否想写 role?」,要么是生成文件第 47 行的
 * 「找不到符号」—— 后者等于没有报错。
 *
 * <p><b>关于 docs/06 §6 的第一条错误</b>:原文设想的是
 * {@code @HasMany(Post.class) Rel<Comment> comments;} 这种「注解目标与字段泛型不符」。
 * ADR-010 选了方案 E'(关联改成**类级注解 + 生成访问器**)之后,
 * 关联已经没有字段了,返回类型完全由 {@code target()} 推导 —— 这类错误
 * 被**设计掉了**,不是漏测。同一类「注解声明的类型 ≠ 字段类型」的风险
 * 转移到了 {@code @Cast.Use} 上,由 {@link #castTypeMismatch()} 覆盖。
 */
class ProcessorErrorTest {

    private static final String PKG = "package t;\n"
            + "import io.jaravel.eloquent.annotations.*;\n"
            + "import io.jaravel.eloquent.query.Query;\n";

    // ================= E1:关联目标不是模型 =================

    @Test
    @DisplayName("关联目标没有 @Model:指出目标、解释为什么、给修复")
    void relationTargetIsNotAModel() {
        Compilation c = compile(
                source("t.Plain", "package t;\npublic class Plain {}\n"),
                source("t.Post", PKG + """
                        @Model
                        @HasMany(name = "items", target = Plain.class)
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                        }
                        """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("Plain 没有 @Model 注解");
        assertThat(c).hadErrorContaining("Rel<Plain>");
        assertThat(c).hadErrorContaining("修复");
    }

    // ================= E2:推断的外键不存在 =================

    @Test
    @DisplayName("@BelongsTo 推断的外键不存在:列出已有列 + 「是否想用」+ 两种修复")
    void inferredForeignKeyMissing() {
        Compilation c = compile(
                source("t.User", PKG + """
                        @Model(timestamps = false)
                        public class User extends UserBase {
                            @Id public Long id;
                            public String name;
                        }
                        """),
                source("t.Post", PKG + """
                        @Model(timestamps = false)
                        @BelongsTo(name = "author", target = User.class)
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                            public Long authorId;
                        }
                        """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("外键 `user_id` 在模型 Post 上不存在");
        // 必须给出猜测:authorId → author_id 与 user_id 距离最近
        assertThat(c).hadErrorContaining("author_id");
        assertThat(c).hadErrorContaining("已声明的列");
        // 两条修复路径都要说:改注解,或改字段
        assertThat(c).hadErrorContaining("foreignKey");
    }

    // ================= E3:@Guarded 列名拼错 =================

    @Test
    @DisplayName("@Guarded 列名拼错:「是否想写 role ?」")
    void guardedColumnTypo() {
        Compilation c = compile(source("t.User", PKG + """
                @Model(timestamps = false)
                @Guarded({"id", "rolee"})
                public class User extends UserBase {
                    @Id public Long id;
                    public String name;
                    public String role;
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("`rolee` 不存在");
        assertThat(c).hadErrorContaining("是否想写 `role`");
    }

    @Test
    @DisplayName("@Fillable 同样校验")
    void fillableColumnTypo() {
        Compilation c = compile(source("t.User", PKG + """
                @Model(timestamps = false)
                @Fillable({"nmae"})
                public class User extends UserBase {
                    @Id public Long id;
                    public String name;
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Fillable");
        assertThat(c).hadErrorContaining("是否想写 `name`");
    }

    // ================= E4:跨模型用错列(javac 原生) =================

    @Test
    @DisplayName("把别的模型的列传进本模型的查询:Col 的两个类型参数让 javac 自己拦住")
    void crossModelColumnIsRejectedByJavac() {
        Compilation c = compile(
                source("t.User", PKG + """
                        @Model(timestamps = false)
                        public class User extends UserBase {
                            @Id public Long id;
                            public String email;
                        }
                        """),
                source("t.Post", PKG + """
                        @Model(timestamps = false)
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                        }
                        """),
                source("t.Usage", """
                        package t;
                        public class Usage {
                            public static void bad() {
                                // Col<User,String> 传进 Query<Post>
                                Post.query().where(User_.email, "x");
                            }
                        }
                        """));

        assertThat(c).failed();
        // 这条是 javac 的天然报错,只需要 Col<M,V> 的泛型设计正确。
        // 断言它**确实**发生,否则 docs/06 §6 承诺的保护就是空话。
        assertThat(c).hadErrorContaining("Col");
    }

    // ================= E5:@Cast.Use 类型不符 =================

    @Test
    @DisplayName("@Cast.Use 的模型侧类型与字段类型不符")
    void castTypeMismatch() {
        Compilation c = compile(source("t.Post", PKG + """
                @Model(timestamps = false)
                public class Post extends PostBase {
                    @Id public Long id;
                    // AsList 是 Cast<List<String>, String>,字段却是 String
                    @io.jaravel.eloquent.meta.Cast.Use(io.jaravel.eloquent.cast.AsList.class)
                    public String tags;
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("AsList");
        assertThat(c).hadErrorContaining("字段 tags 的类型是");
    }

    // ================= 形态类错误 =================

    @Test
    @DisplayName("没继承生成的基类:说清基类是什么、从哪来")
    void mustExtendGeneratedBase() {
        Compilation c = compile(source("t.Post", PKG + """
                @Model(timestamps = false)
                public class Post {
                    @Id public Long id;
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("必须继承生成的基类 PostBase");
        assertThat(c).hadErrorContaining("generated-sources");
    }

    @Test
    @DisplayName("没有主键:列出已有列并给出两种修复")
    void noPrimaryKey() {
        Compilation c = compile(source("t.Post", PKG + """
                @Model(timestamps = false)
                public class Post extends PostBase {
                    public String title;
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("没有主键");
        assertThat(c).hadErrorContaining("@Id public Long id");
    }

    @Test
    @DisplayName("复合主键:说明为什么不支持,并给出 Laravel 同样的替代做法")
    void compositeKeyRejected() {
        Compilation c = compile(source("t.Pivot", PKG + """
                @Model(timestamps = false)
                public class Pivot extends PivotBase {
                    @Id public Long postId;
                    @Id public Long tagId;
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("只支持单列主键");
        assertThat(c).hadErrorContaining("唯一索引");
    }

    @Test
    @DisplayName("私有字段:解释 public 字段换来的是什么")
    void privateFieldRejected() {
        Compilation c = compile(source("t.Post", PKG + """
                @Model(timestamps = false)
                public class Post extends PostBase {
                    @Id public Long id;
                    private String secret;
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("不是 public");
        assertThat(c).hadErrorContaining("@Transient");
    }

    @Test
    @DisplayName("没有无参构造:指出是带参构造顶掉了默认构造")
    void missingNoArgConstructor() {
        Compilation c = compile(source("t.Post", PKG + """
                @Model(timestamps = false)
                public class Post extends PostBase {
                    @Id public Long id;
                    public String title;
                    public Post(String title) { this.title = title; }
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("无参构造函数");
        assertThat(c).hadErrorContaining("public Post() {}");
    }

    @Test
    @DisplayName("abstract 模型:解释实例化不了")
    void abstractModelRejected() {
        Compilation c = compile(source("t.Post", PKG + """
                @Model(timestamps = false)
                public abstract class Post extends PostBase {
                    @Id public Long id;
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("abstract");
    }

    @Test
    @DisplayName("@SoftDeletes 缺列:直接给出要加的字段声明")
    void softDeletesMissingColumn() {
        Compilation c = compile(source("t.Post", PKG + """
                @Model(timestamps = false)
                @SoftDeletes
                public class Post extends PostBase {
                    @Id public Long id;
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@SoftDeletes 需要列 `deleted_at`");
        assertThat(c).hadErrorContaining("public java.time.Instant deletedAt;");
    }

    @Test
    @DisplayName("@Scope 不是 static:给出正确签名")
    void scopeMustBeStatic() {
        Compilation c = compile(source("t.Post", PKG + """
                @Model(timestamps = false)
                public class Post extends PostBase {
                    @Id public Long id;
                    @Scope
                    public Query<Post> bad(Query<Post> q) { return q; }
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("必须是 static");
        assertThat(c).hadErrorContaining("public static Query<Post> bad(Query<Post> q)");
    }

    @Test
    @DisplayName("@Scope 第一个参数不是 Query:打印当前签名")
    void scopeFirstParamMustBeQuery() {
        Compilation c = compile(source("t.Post", PKG + """
                @Model(timestamps = false)
                public class Post extends PostBase {
                    @Id public Long id;
                    @Scope
                    public static Query<Post> bad(String wrong) { return Post.query(); }
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("第一个参数必须是 Query<Post>");
        assertThat(c).hadErrorContaining("当前签名");
    }

    @Test
    @DisplayName("@GlobalScope 带额外参数:指出全局作用域没地方传参")
    void globalScopeCannotTakeParameters() {
        Compilation c = compile(source("t.Post", PKG + """
                @Model(timestamps = false)
                public class Post extends PostBase {
                    @Id public Long id;
                    @GlobalScope("x")
                    public static Query<Post> bad(Query<Post> q, int n) { return q; }
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("不能有额外参数");
        assertThat(c).hadErrorContaining("@Scope");
    }

    @Test
    @DisplayName("关联名与字段撞名:两者都会生成同名方法")
    void relationNameCollidesWithField() {
        Compilation c = compile(
                source("t.Comment", PKG + """
                        @Model(timestamps = false)
                        public class Comment extends CommentBase {
                            @Id public Long id;
                            public Long postId;
                        }
                        """),
                source("t.Post", PKG + """
                        @Model(timestamps = false)
                        @HasMany(name = "comments", target = Comment.class)
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String comments;
                        }
                        """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("与同名字段冲突");
    }

    @Test
    @DisplayName("关联名用了保留方法名")
    void relationNameIsReserved() {
        Compilation c = compile(
                source("t.Comment", PKG + """
                        @Model(timestamps = false)
                        public class Comment extends CommentBase {
                            @Id public Long id;
                        }
                        """),
                source("t.Post", PKG + """
                        @Model(timestamps = false)
                        @HasMany(name = "query", target = Comment.class)
                        public class Post extends PostBase {
                            @Id public Long id;
                        }
                        """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("保留方法名");
    }

    @Test
    @DisplayName("两个同名关联")
    void duplicateRelationName() {
        Compilation c = compile(
                source("t.Comment", PKG + """
                        @Model(timestamps = false)
                        public class Comment extends CommentBase {
                            @Id public Long id;
                            public Long postId;
                        }
                        """),
                source("t.Post", PKG + """
                        @Model(timestamps = false)
                        @HasMany(name = "items", target = Comment.class)
                        @HasOne(name = "items", target = Comment.class)
                        public class Post extends PostBase {
                            @Id public Long id;
                        }
                        """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("两个名为 \"items\" 的关联");
    }

    @Test
    @DisplayName("@MorphTo 缺 *_id / *_type 列")
    void morphToMissingColumns() {
        Compilation c = compile(source("t.Image", PKG + """
                @Model(timestamps = false)
                @MorphTo(name = "owner", types = {Image.class}, morphName = "imageable")
                public class Image extends ImageBase {
                    @Id public Long id;
                    public String url;
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("`imageable_id`");
    }

    @Test
    @DisplayName("@MorphTo 的 types 为空:说明运行时解析不了")
    void morphToNeedsTypes() {
        Compilation c = compile(source("t.Image", PKG + """
                @Model(timestamps = false)
                @MorphTo(name = "owner", types = {}, morphName = "imageable")
                public class Image extends ImageBase {
                    @Id public Long id;
                    public Long imageableId;
                    public String imageableType;
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("types 为空");
    }

    @Test
    @DisplayName("没有任何持久化字段")
    void noColumns() {
        Compilation c = compile(source("t.Post", PKG + """
                @Model(timestamps = false)
                public class Post extends PostBase {
                }
                """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("没有任何持久化字段");
        assertThat(c).hadErrorContaining("@Transient");
    }

    // ================= 成功路径 =================

    @Test
    @DisplayName("正常模型:三个文件都生成,且生成的代码本身能编译")
    void happyPathGeneratesThreeFiles() {
        Compilation c = compile(
                source("t.User", PKG + """
                        @Model
                        @HasMany(name = "posts", target = Post.class)
                        public class User extends UserBase {
                            @Id public Long id;
                            public String name;
                            @Hidden public String password;
                            public java.time.Instant createdAt;
                            public java.time.Instant updatedAt;
                        }
                        """),
                source("t.Post", PKG + """
                        @Model
                        @BelongsTo(name = "author", target = User.class, foreignKey = "user_id")
                        @Guarded({"id"})
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                            public Long userId;
                            public boolean published;
                            public java.time.Instant createdAt;
                            public java.time.Instant updatedAt;
                        }
                        """));

        assertThat(c).succeeded();
        assertThat(c).generatedSourceFile("t.Post_");
        assertThat(c).generatedSourceFile("t.PostBase");
        assertThat(c).generatedSourceFile("t.PostQuery");
        assertThat(c).generatedSourceFile("t.User_");
    }

    @Test
    @DisplayName("生成的 Query 让作用域能接在 where 之后 —— 协变重写真的生效")
    void covariantOverridesKeepConcreteType() {
        Compilation c = compile(
                source("t.Post", PKG + """
                        @Model(timestamps = false)
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                            public int views;
                            @Scope
                            public static Query<Post> popular(Query<Post> q) { return q; }
                        }
                        """),
                source("t.Usage", """
                        package t;
                        public class Usage {
                            public static void chain() {
                                // where 之后还能接作用域 —— 若 where 返回 Query<Post> 就编译不过
                                Post.query().whereTitle("x").popular().orderByViewsDesc().limit(1).get();
                            }
                        }
                        """));

        assertThat(c).succeededWithoutWarnings();
    }

    // ================= 工具 =================

    private static Compilation compile(JavaFileObject... sources) {
        return javac().withProcessors(new ModelProcessor()).compile(sources);
    }

    private static JavaFileObject source(String name, String content) {
        return JavaFileObjects.forSourceString(name, content);
    }
}
