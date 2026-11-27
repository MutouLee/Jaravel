package net.dobeen.jaravel.core.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link Translator} / {@link Lang} / {@link LangLoader}(docs/02 §2.3) */
class TranslatorTest {

    @AfterEach
    void tearDown() {
        Lang.reset();
    }

    private Translator translator() {
        return new Translator("zh_CN", "en")
                .put("zh_CN", Map.of(
                        "posts.created", "文章已发布",
                        "posts.greeting", "你好 :name,欢迎回来",
                        "posts.count", "没有文章|一篇文章|:count 篇文章",
                        "posts.ranged", "{0} 空的|[1,19] :count 篇|[20,*] 很多篇"))
                .put("en", Map.of(
                        "posts.created", "Post published",
                        "posts.only_in_en", "English only"));
    }

    // ================= 查询 =================

    @Nested
    @DisplayName("取消息")
    class Lookup {

        @Test
        @DisplayName("按当前 locale 取,取不到回退到 fallback")
        void fallsBackToFallbackLocale() {
            var t = translator();

            assertThat(t.get("posts.created")).isEqualTo("文章已发布");
            assertThat(t.get("posts.only_in_en"))
                    .as("zh_CN 没有这条 —— 回退到 en,而不是显示键")
                    .isEqualTo("English only");

            t.setLocale("en");
            assertThat(t.get("posts.created")).isEqualTo("Post published");
        }

        @Test
        @DisplayName("★ 找不到键时返回键本身 —— 不抛异常、不返回空串")
        void missingKeyReturnsTheKey() {
            var t = translator();

            assertThat(t.get("nope.missing"))
                    .as("漏翻是翻译工作的正常中间状态:抛异常会打挂页面,"
                      + "空串会让漏翻静默消失")
                    .isEqualTo("nope.missing");
            assertThat(t.has("nope.missing")).isFalse();
            assertThat(t.has("posts.created")).isTrue();
        }

        @Test
        @DisplayName("getIn 指定 locale —— 按收件人语言发邮件用")
        void getInSpecificLocale() {
            var t = translator();

            assertThat(t.getIn("en", "posts.created", Map.of())).isEqualTo("Post published");
            assertThat(t.locale())
                    .as("getIn 不改当前 locale")
                    .isEqualTo("zh_CN");
        }
    }

    // ================= 占位符 =================

    @Nested
    @DisplayName("占位符")
    class Replacements {

        @Test
        @DisplayName(":name 被替换")
        void replacesPlaceholders() {
            assertThat(translator().get("posts.greeting", Map.of("name", "张三")))
                    .isEqualTo("你好 张三,欢迎回来");
        }

        @Test
        @DisplayName("★ 替换值里的 :other 不会被二次替换")
        void replacementValuesAreNotRescanned() {
            var t = new Translator("zh_CN", "en")
                    .put("zh_CN", Map.of("x", "A=:a B=:b"));

            assertThat(t.get("x", Map.of("a", ":b", "b", "真值")))
                    .as("一次扫描:用户填的内容里带冒号不该变成注入点")
                    .isEqualTo("A=:b B=真值");
        }

        @Test
        @DisplayName("没传的占位符原样留着 —— 比留一个空洞容易发现")
        void missingReplacementStays() {
            assertThat(translator().get("posts.greeting", Map.of()))
                    .isEqualTo("你好 :name,欢迎回来");
        }
    }

    // ================= 复数 =================

    @Nested
    @DisplayName("复数")
    class Choice {

        @Test
        @DisplayName("三段按 0 / 1 / 其余")
        void threeSegments() {
            var t = translator();

            assertThat(t.choice("posts.count", 0)).isEqualTo("没有文章");
            assertThat(t.choice("posts.count", 1)).isEqualTo("一篇文章");
            assertThat(t.choice("posts.count", 7)).isEqualTo("7 篇文章");
        }

        @Test
        @DisplayName("两段按 1 / 其余")
        void twoSegments() {
            var t = new Translator("zh_CN", "en")
                    .put("zh_CN", Map.of("x", "一个|:count 个"));

            assertThat(t.choice("x", 1)).isEqualTo("一个");
            assertThat(t.choice("x", 0)).isEqualTo("0 个");
            assertThat(t.choice("x", 9)).isEqualTo("9 个");
        }

        @Test
        @DisplayName("★ 显式区间 {0} / [1,19] / [20,*]")
        void explicitRanges() {
            var t = translator();

            assertThat(t.choice("posts.ranged", 0)).isEqualTo("空的");
            assertThat(t.choice("posts.ranged", 5)).isEqualTo("5 篇");
            assertThat(t.choice("posts.ranged", 19)).isEqualTo("19 篇");
            assertThat(t.choice("posts.ranged", 20)).isEqualTo("很多篇");
            assertThat(t.choice("posts.ranged", 9999)).isEqualTo("很多篇");
        }

        @Test
        @DisplayName("choice 的额外占位符与 :count 共存")
        void choiceWithExtraReplacements() {
            var t = new Translator("zh_CN", "en")
                    .put("zh_CN", Map.of("x", ":name 有 :count 条"));

            assertThat(t.choice("x", 3, Map.of("name", "张三")))
                    .isEqualTo("张三 有 3 条");
        }

        @Test
        @DisplayName("找不到键时 choice 也返回键本身")
        void missingKey() {
            assertThat(translator().choice("nope", 3)).isEqualTo("nope");
        }
    }

    // ================= 门面 =================

    @Nested
    @DisplayName("Lang 门面")
    class Facade {

        @Test
        @DisplayName("★ 没装配时返回键本身,而不是报错")
        void unconfiguredReturnsTheKey() {
            assertThat(Lang.isConfigured()).isFalse();
            assertThat(Lang.get("posts.created"))
                    .as("单语言应用(绝大多数)不该因为没有消息目录而 500")
                    .isEqualTo("posts.created");
            assertThat(Lang.choice("posts.count", 2)).isEqualTo("posts.count");
            assertThat(Lang.has("posts.created")).isFalse();
        }

        @Test
        @DisplayName("withLocale 只在那一段里切,之后还原")
        void withLocaleRestores() {
            Lang.use(translator());

            Lang.withLocale("en", () ->
                    assertThat(Lang.get("posts.created")).isEqualTo("Post published"));

            assertThat(Lang.locale()).isEqualTo("zh_CN");
            assertThat(Lang.get("posts.created")).isEqualTo("文章已发布");
        }

        @Test
        @DisplayName("withLocale 抛异常也还原")
        void withLocaleRestoresOnThrow() {
            Lang.use(translator());

            try {
                Lang.withLocale("en", () -> {
                    throw new IllegalStateException("boom");
                });
            } catch (IllegalStateException expected) {
                // 预期
            }

            assertThat(Lang.locale()).isEqualTo("zh_CN");
        }
    }

    // ================= 加载 =================

    @Nested
    @DisplayName("从磁盘加载")
    class Loading {

        @Test
        @DisplayName("★ 第一段是文件名,嵌套用点号")
        void keysAreGroupPrefixed(@TempDir Path tmp) throws Exception {
            Path zh = Files.createDirectories(tmp.resolve("zh_CN"));
            Files.writeString(zh.resolve("posts.yml"), """
                    created: 文章已发布
                    form:
                      title: 标题
                      body: 正文
                    """, StandardCharsets.UTF_8);
            Files.writeString(zh.resolve("auth.yml"), """
                    failed: 邮箱或密码不正确
                    """, StandardCharsets.UTF_8);

            var catalogs = LangLoader.fromDirectory(tmp);

            assertThat(catalogs).containsKey("zh_CN");
            assertThat(catalogs.get("zh_CN"))
                    .containsEntry("posts.created", "文章已发布")
                    .containsEntry("posts.form.title", "标题")
                    .containsEntry("auth.failed", "邮箱或密码不正确");
        }

        @Test
        @DisplayName("列表按下标寻址")
        void listsAreIndexed(@TempDir Path tmp) throws Exception {
            Path zh = Files.createDirectories(tmp.resolve("zh_CN"));
            Files.writeString(zh.resolve("ui.yml"), """
                    choices:
                      - 第一项
                      - 第二项
                    """, StandardCharsets.UTF_8);

            assertThat(LangLoader.fromDirectory(tmp).get("zh_CN"))
                    .containsEntry("ui.choices.0", "第一项")
                    .containsEntry("ui.choices.1", "第二项");
        }

        @Test
        @DisplayName("_ 开头的文件不是消息(它是 classpath 的分组索引)")
        void underscoreFilesAreSkipped(@TempDir Path tmp) throws Exception {
            Path zh = Files.createDirectories(tmp.resolve("zh_CN"));
            Files.writeString(zh.resolve("_groups.yml"), "groups: [posts]\n",
                    StandardCharsets.UTF_8);
            Files.writeString(zh.resolve("posts.yml"), "created: x\n",
                    StandardCharsets.UTF_8);

            assertThat(LangLoader.fromDirectory(tmp).get("zh_CN"))
                    .containsOnlyKeys("posts.created");
        }

        @Test
        @DisplayName("目录不存在时返回空,不报错")
        void missingDirectoryIsEmpty(@TempDir Path tmp) {
            assertThat(LangLoader.fromDirectory(tmp.resolve("nope"))).isEmpty();
        }

        @Test
        @DisplayName("put 是合并:包提供的消息与应用自己的能共存")
        void putMerges() {
            var t = new Translator("zh_CN", "en")
                    .put("zh_CN", Map.of("a", "包的", "b", "包的 b"))
                    .put("zh_CN", Map.of("a", "应用的"));

            assertThat(t.get("a")).isEqualTo("应用的");
            assertThat(t.get("b")).isEqualTo("包的 b");
            assertThat(t.size("zh_CN")).isEqualTo(2);
        }
    }
}
