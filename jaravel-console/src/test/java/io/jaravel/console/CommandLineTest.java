package io.jaravel.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 一条命令在「一个字符串」与「一串参数」之间的来回(docs/13 §1)。
 *
 * <p>这件事只有在**冷启动**那条路上才需要:参数要塞进
 * {@code -Djaravel.command=…},而系统属性只能是一个字符串。
 */
class CommandLineTest {

    @Test
    @DisplayName("★ 最难缠的参数也要能原样回来 —— 错位的症状是「选项莫名其妙没生效」")
    void roundTrip() {
        var nasty = List.of(
                "db:seed",
                "--class=BlogSeeder",
                "--path=db/有 空 格/x",      // 空格
                "--title=他说\"行\"",          // 引号
                "--regex=\\d+",               // 反斜杠
                "",                            // 空参数
                "--中文选项=值");

        String line = CommandLine.join(nasty);

        assertThat(CommandLine.split(line))
                .as("拼接与拆分的约定只有一份,就是为了这里能相等:%s", line)
                .isEqualTo(nasty);
    }

    @Test
    @DisplayName("人手敲的那种也要认 —— 今天这个属性就是手写的")
    void humanWritten() {
        assertThat(CommandLine.split("migrate --force"))
                .containsExactly("migrate", "--force");
        assertThat(CommandLine.split("   migrate    --step=2   "))
                .as("多余的空白吃掉")
                .containsExactly("migrate", "--step=2");
        assertThat(CommandLine.split("db:seed --class=\"Blog Seeder\""))
                .as("引号里的空格不是分隔符")
                .containsExactly("db:seed", "--class=Blog Seeder");
    }

    @Test
    @DisplayName("只给**需要**的参数加引号 —— 加满了没法读")
    void quotesOnlyWhenNeeded() {
        assertThat(CommandLine.join(List.of("migrate", "--force")))
                .isEqualTo("migrate --force");
    }

    @Test
    @DisplayName("引号没闭合时按「到行尾」处理 —— 不报错、不丢字符")
    void unclosedQuote() {
        assertThat(CommandLine.split("db:seed --class=\"Blog"))
                .as("""
                        这个字符串常常是人手敲的。报错的话要在
                        「应用启动完才发现命令解析失败」那个位置报,
                        而按到行尾处理至少把用户的字符都带到了命令那边,
                        由命令自己说「没有 Blog 这个 seeder」。""")
                .containsExactly("db:seed", "--class=Blog");
    }

    @Test
    @DisplayName("空的 / null 不炸 —— 属性可能根本没设")
    void empty() {
        assertThat(CommandLine.split(null)).isEmpty();
        assertThat(CommandLine.split("   ")).isEmpty();
        assertThat(CommandLine.join(List.of())).isEmpty();
    }

    @Test
    @DisplayName("属性名只定义一次 —— CLI 拼它、应用拆它")
    void propertyName() {
        assertThat(CommandLine.COMMAND_PROPERTY)
                .as("""
                        两边各写一个字面量的话,改名字时只改一边 ——
                        而症状是「起了一个 JVM,它什么也没干就退出了」:
                        条件 bean 没装上,没有任何报错。""")
                .isEqualTo("jaravel.command");
    }
}
