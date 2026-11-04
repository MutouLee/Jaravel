package io.jaravel.console.generators;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import io.jaravel.console.Argument;
import io.jaravel.console.Command;
import io.jaravel.console.ConsoleException;
import io.jaravel.console.Option;
import io.jaravel.support.Str;

/**
 * {@code jaravel make:migration create_posts_table}(docs/13 §2.2)。
 *
 * <pre>
 * make:migration create_posts_table          → 建表(表名从名字里推出来)
 * make:migration add_slug_to_posts --table=posts  → 改表
 * </pre>
 *
 * <h2>★ 时间戳在**包名**上,不在类名上</h2>
 * <p>Laravel 的迁移是文件名带时间戳({@code 2026_10_29_000100_create_posts_table.php})。
 * Java 不行:类名不能以数字开头,而且同名类不能共存 ——
 * 两次 {@code add_slug_to_posts} 会撞车。
 *
 * <p>所以照 examples/blog 已经在用的那个形态:**每个迁移一个带时间戳的包**
 * ({@code database.migrations.m2026_10_29_000100.CreatePostsTable})。
 * 执行顺序由包名的字典序决定,与 Laravel 的文件名排序等价。
 */
@Command(name = "make:migration", description = "新建一个数据库迁移")
public final class MakeMigration extends GeneratorCommand {

    @Argument(description = "迁移名,如 create_posts_table")
    String name;

    @Option(names = "--table", description = "要修改的表(改表迁移)")
    String table;

    @Option(names = "--create", description = "要新建的表(建表迁移)")
    String create;

    /** 测试要一个确定的时间戳;生产用当前时间 */
    private LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());

    public void at(LocalDateTime timestamp) {
        this.now = timestamp;
    }

    @Override
    public int handle() {
        String raw = name.trim();
        if (raw.isEmpty()) throw new ConsoleException("迁移名不能为空");

        Guess guess = guess(raw);
        String className = Str.studly(raw);
        String packageName = MIGRATIONS_PACKAGE + "."
                + now.format(DateTimeFormatter.ofPattern("'m'yyyy_MM_dd_HHmmss"));

        String file = write(fileFor(packageName, className),
                stub(guess.creating() ? "migration.create" : "migration.update")
                        .with("pkg", packageName)
                        .with("class", className)
                        .with("table", guess.table())
                        .render());

        success("迁移:" + file);
        if (guess.table().isEmpty()) {
            warn("从名字里猜不出表名 —— stub 里的表名留空了,记得填。"
                    + "\n  下次可以直接给:--table=posts 或 --create=posts");
        }
        return 0;
    }

    static final String MIGRATIONS_PACKAGE = "database.migrations";

    private record Guess(boolean creating, String table) {}

    /**
     * 从迁移名里认出「建表还是改表、哪张表」。
     *
     * <p>{@code create_posts_table} → 建 {@code posts};
     * {@code add_slug_to_posts} → 改 {@code posts}。
     * 显式的 {@code --table} / {@code --create} 优先。
     *
     * <p>⚠ 猜不出来时**不报错**:名字是用户自己起的,不该为了迁就
     * 一个命名约定而拒绝生成文件。此时表名留空并提示一句 ——
     * 因为没填表名的 stub 一眼就能看出要改,而一个被拒绝的
     * {@code make:migration} 只会让人去搜命名规则。
     */
    private Guess guess(String raw) {
        if (create != null && !create.isBlank())  return new Guess(true, create.trim());
        if (table != null && !table.isBlank())    return new Guess(false, table.trim());

        String snake = Str.snake(raw);
        var createMatcher = java.util.regex.Pattern
                .compile("^create_(\\w+?)_table$|^create_(\\w+)$").matcher(snake);
        if (createMatcher.matches()) {
            String found = createMatcher.group(1) != null ? createMatcher.group(1) : createMatcher.group(2);
            return new Guess(true, found);
        }

        var updateMatcher = java.util.regex.Pattern
                .compile("^\\w+?_(?:to|from|in)_(\\w+?)(?:_table)?$").matcher(snake);
        if (updateMatcher.matches()) {
            return new Guess(false, updateMatcher.group(1));
        }
        return new Guess(false, "");
    }
}
