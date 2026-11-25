package io.jaravel.eloquent.apt;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

/**
 * {@code @UseFactory} 的生成与报错(docs/14 §3)。
 *
 * <p>与 {@link ProcessorErrorTest} 同一个判断:生成代码的框架,错误信息
 * 就是它的 UI。工厂这条链上用户能写错的地方有四个(类不存在、没继承生成的
 * 基类、abstract、没有无参构造),每一个都必须落在**模型的那一行**上,
 * 而不是落在 {@code target/generated-sources} 里。
 *
 * <p>生成的内容本身由 {@code examples/blog} 的 {@code FactoryFeatureTest}
 * 在真库上验收 —— 这里只核对「生成了」与「长什么样」。
 */
class FactoryProcessorTest {

    private static final String PKG = "package t;\n"
            + "import io.jaravel.eloquent.annotations.*;\n";

    // ================= 生成 =================

    @Test
    @DisplayName("工厂基类生成出来:类型化 setter + 自类型链式方法 + 产出方法")
    void generatesFactoryBase() {
        Compilation c = compile(
                source("t.Post", PKG + """
                        @Model
                        @UseFactory(PostFactory.class)
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                            public int views;
                        }
                        """),
                source("t.PostFactory", """
                        package t;
                        public class PostFactory extends PostFactoryBase {
                            @Override protected void definition(
                                    io.jaravel.support.Faker f, Post p) {
                                p.title = f.sentence();
                            }
                            public PostFactory popular() { return state(p -> p.views = 9999); }
                        }
                        """));

        assertThat(c).succeeded();
        assertThat(c).generatedSourceFile("t.PostFactoryBase")
                .contentsAsUtf8String()
                .contains("public PostFactory title(java.lang.String value)");
        assertThat(c).generatedSourceFile("t.PostFactoryBase")
                .contentsAsUtf8String()
                .contains("public PostFactory count(int n)");
        assertThat(c).generatedSourceFile("t.PostFactoryBase")
                .contentsAsUtf8String()
                .contains("public t.Post createOne()");
    }

    @Test
    @DisplayName("模型上生成 Post.factory(),返回的是用户那个工厂类")
    void generatesStaticEntryOnTheModel() {
        Compilation c = compile(model(), factory());

        assertThat(c).generatedSourceFile("t.PostBase")
                .contentsAsUtf8String()
                .contains("public static t.PostFactory factory()");
    }

    @Test
    @DisplayName("没标 @UseFactory 就什么都不生成 —— 工厂是可选的")
    void withoutTheAnnotationNothingIsGenerated() {
        Compilation c = compile(
                source("t.Post", PKG + """
                        @Model
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                        }
                        """));

        assertThat(c).succeeded();
        assertThat(c).generatedSourceFile("t.PostBase")
                .contentsAsUtf8String()
                .doesNotContain("factory()");
    }

    @Test
    @DisplayName("★ 名字与机制方法冲突的列不生成 setter,但会 warning 说出来")
    void reservedColumnNamesAreReportedNotSilentlyDropped() {
        Compilation c = compile(
                source("t.Post", PKG + """
                        @Model
                        @UseFactory(PostFactory.class)
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                            public int count;
                        }
                        """),
                factory());

        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("count");
        assertThat(c).generatedSourceFile("t.PostFactoryBase")
                .contentsAsUtf8String()
                .doesNotContain("PostFactory count(int value)");
    }

    // ================= 关联构建器 =================

    @Test
    @DisplayName("@BelongsTo → forAuthor(author) 与 forAuthor(AuthorFactory)")
    void generatesBelongsToBuilders() {
        Compilation c = compileRelations();

        assertThat(c).generatedSourceFile("t.PostFactoryBase")
                .contentsAsUtf8String()
                .contains("public PostFactory forAuthor(t.Author related)");
        assertThat(c).generatedSourceFile("t.PostFactoryBase")
                .contentsAsUtf8String()
                .contains("public PostFactory forAuthor(t.AuthorFactory related)");
    }

    @Test
    @DisplayName("@HasMany → hasPosts(n) 挂在父侧,外键用的是子模型的字段")
    void generatesHasManyBuilders() {
        Compilation c = compileRelations();

        String generated = contents(c, "t.AuthorFactoryBase");
        assertThat(c).succeeded();
        org.assertj.core.api.Assertions.assertThat(generated)
                .contains("public AuthorFactory hasPosts(int n)")
                .contains("child.authorId = owner.id");
    }

    @Test
    @DisplayName("目标模型没有工厂时,只生成 forAuthor(author) 那一个重载")
    void withoutATargetFactoryOnlyTheModelOverloadExists() {
        Compilation c = compile(
                source("t.Author", PKG + """
                        @Model
                        public class Author extends AuthorBase {
                            @Id public Long id;
                            public String name;
                        }
                        """),
                source("t.Post", PKG + """
                        @Model
                        @UseFactory(PostFactory.class)
                        @BelongsTo(name = "author", target = Author.class)
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                            public Long authorId;
                        }
                        """),
                factory());

        assertThat(c).succeeded();
        String generated = contents(c, "t.PostFactoryBase");
        org.assertj.core.api.Assertions.assertThat(generated)
                .contains("forAuthor(t.Author related)")
                .doesNotContain("FactoryBase forAuthor(t.AuthorFactory");
    }

    @Test
    @DisplayName("★ @BelongsToMany → hasTags / withTags,挂接走关联访问器")
    void generatesBelongsToManyBuilders() {
        Compilation c = compile(
                source("t.Tag", PKG + """
                        @Model
                        @UseFactory(TagFactory.class)
                        public class Tag extends TagBase {
                            @Id public Long id;
                            public String name;
                        }
                        """),
                source("t.TagFactory", """
                        package t;
                        public class TagFactory extends TagFactoryBase {
                        }
                        """),
                source("t.Post", PKG + """
                        @Model
                        @UseFactory(PostFactory.class)
                        @BelongsToMany(name = "tags", target = Tag.class, pivot = "post_tag")
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                        }
                        """),
                factory());

        assertThat(c).succeeded();
        String generated = contents(c, "t.PostFactoryBase");
        org.assertj.core.api.Assertions.assertThat(generated)
                .as("""
                        多对多没有「模型上的外键」—— 判据与前三种不同
                        (第一版用 factoryReady() 一刀切,结果 hasTags 根本没生成)。""")
                .contains("public PostFactory hasTags(int n)")
                .contains("owner.tags().attach(")
                .contains("java.util.Map<String, ?> pivot")
                .contains("public PostFactory withTags(t.Tag... existing)")
                .contains("public PostFactory withTags(java.util.Collection<t.Tag> existing)");
    }

    @Test
    @DisplayName("关掉 generateHelpers 的模型不生成中间表构建器 —— 没有访问器可调")
    void belongsToManyNeedsRelationAccessors() {
        Compilation c = compile(
                source("t.Tag", PKG + """
                        @Model
                        @UseFactory(TagFactory.class)
                        public class Tag extends TagBase {
                            @Id public Long id;
                            public String name;
                        }
                        """),
                source("t.TagFactory", """
                        package t;
                        public class TagFactory extends TagFactoryBase {
                        }
                        """),
                source("t.Post", PKG + """
                        @Model(generateHelpers = false)
                        @UseFactory(PostFactory.class)
                        @BelongsToMany(name = "tags", target = Tag.class, pivot = "post_tag")
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                        }
                        """),
                factory());

        assertThat(c).succeeded();
        org.assertj.core.api.Assertions.assertThat(contents(c, "t.PostFactoryBase"))
                .as("生成一段引用不存在的 owner.tags() 的代码,比不生成糟得多")
                .doesNotContain("hasTags");
    }

    // ================= 报错 =================

    @Test
    @DisplayName("工厂没继承生成的基类:说清要继承哪个,并给写法")
    void factoryMustExtendTheGeneratedBase() {
        Compilation c = compile(
                model(),
                source("t.PostFactory", """
                        package t;
                        public class PostFactory {
                        }
                        """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("必须 extends PostFactoryBase");
    }

    @Test
    @DisplayName("工厂是 abstract:factory() 要 new 它")
    void factoryMustNotBeAbstract() {
        Compilation c = compile(
                model(),
                source("t.PostFactory", """
                        package t;
                        public abstract class PostFactory extends PostFactoryBase {
                        }
                        """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("不能是 abstract");
    }

    @Test
    @DisplayName("工厂只有带参构造:指出链式方法才是传参的地方")
    void factoryNeedsANoArgConstructor() {
        Compilation c = compile(
                model(),
                source("t.PostFactory", """
                        package t;
                        public class PostFactory extends PostFactoryBase {
                            public PostFactory(String unused) { }
                        }
                        """));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("没有无参构造");
    }

    @Test
    @DisplayName("★ 工厂不在这次编译里(典型:写在 src/test/java):说清为什么要在 main")
    void factoryMustBeInTheSameCompilation() {
        Compilation c = compile(
                source("t.Post", PKG + """
                        @Model
                        @UseFactory(java.util.ArrayList.class)
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                        }
                        """));

        assertThat(c).failed();
        // ArrayList 在 classpath 上、但不继承生成的基类 —— 走的是同一条校验
        assertThat(c).hadErrorContaining("ArrayListBase");
    }

    // ================= 工具 =================

    private static Compilation compileRelations() {
        return compile(
                source("t.Author", PKG + """
                        @Model
                        @UseFactory(AuthorFactory.class)
                        @HasMany(name = "posts", target = Post.class, foreignKey = "author_id")
                        public class Author extends AuthorBase {
                            @Id public Long id;
                            public String name;
                        }
                        """),
                source("t.AuthorFactory", """
                        package t;
                        public class AuthorFactory extends AuthorFactoryBase {
                        }
                        """),
                source("t.Post", PKG + """
                        @Model
                        @UseFactory(PostFactory.class)
                        @BelongsTo(name = "author", target = Author.class)
                        public class Post extends PostBase {
                            @Id public Long id;
                            public String title;
                            public Long authorId;
                        }
                        """),
                factory());
    }

    private static JavaFileObject model() {
        return source("t.Post", PKG + """
                @Model
                @UseFactory(PostFactory.class)
                public class Post extends PostBase {
                    @Id public Long id;
                    public String title;
                }
                """);
    }

    private static JavaFileObject factory() {
        return source("t.PostFactory", """
                package t;
                public class PostFactory extends PostFactoryBase {
                }
                """);
    }

    private static String contents(Compilation c, String name) {
        var file = c.generatedSourceFile(name)
                .orElseThrow(() -> new AssertionError("没有生成 " + name));
        try {
            return file.getCharContent(true).toString().replace("\r\n", "\n");
        } catch (java.io.IOException e) {
            throw new AssertionError("读不到生成的 " + name, e);
        }
    }

    private static Compilation compile(JavaFileObject... sources) {
        return javac().withProcessors(new ModelProcessor()).compile(sources);
    }

    private static JavaFileObject source(String name, String content) {
        return JavaFileObjects.forSourceString(name, content);
    }
}
