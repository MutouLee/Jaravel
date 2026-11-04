package io.jaravel.spring.commands;

import java.util.List;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleException;
import io.jaravel.console.Option;
import io.jaravel.migration.Seeder;

/**
 * {@code jaravel db:seed [--class=PostSeeder]}(docs/13 §2.3)。
 *
 * <p>不给 {@code --class} 时跑约定包({@code database.seeders})下的全部 ——
 * Laravel 默认只跑 {@code DatabaseSeeder} 一个,由它 {@code call} 出其余的。
 * 这里两种都支持:有 {@code DatabaseSeeder} 就只跑它(尊重那个约定),
 * 没有就全跑(否则一个只写了几个 seeder、没写入口的项目会「什么都没发生」)。
 */
@Command(name = "db:seed", description = "执行种子数据")
public final class DbSeedCommand extends RuntimeCommand {

    /** Laravel 的入口 seeder 名 —— 有它就只跑它 */
    private static final String ENTRY_POINT = "DatabaseSeeder";

    @Option(names = "--class", description = "只跑这一个 Seeder(简单类名)")
    String only;

    private final Supplier<List<Seeder>> seeders;

    public DbSeedCommand(Supplier<List<Seeder>> seeders) {
        this.seeders = seeders;
    }

    @Override
    public int handle() {
        if (!confirmInProduction("向数据库写入种子数据")) return 1;

        List<Seeder> found = seeders.get();
        if (found.isEmpty()) {
            comment("没有找到 Seeder(约定位置:database.seeders 包下)。");
            return 0;
        }

        List<Seeder> toRun = select(found);
        var runner = new Seeder.Runner();
        for (Seeder seeder : toRun) {
            // 一个一个地报,而不是最后报个总数:一个慢 seeder
            // 在「总数」的那种输出里与卡住无法区分
            line("执行 " + seeder.getClass().getSimpleName() + " …");
            runner.run(seeder).forEach(name -> {
                if (!name.equals(seeder.getClass().getSimpleName())) {
                    line("  ↳ " + name);
                }
            });
        }
        success("种子数据完成。");
        return 0;
    }

    private List<Seeder> select(List<Seeder> found) {
        if (only != null && !only.isBlank()) {
            String wanted = only.trim();
            List<Seeder> matched = found.stream()
                    .filter(s -> s.getClass().getSimpleName().equals(wanted))
                    .toList();
            if (matched.isEmpty()) {
                throw new ConsoleException("""
                        没有叫 "%s" 的 Seeder。

                          找到的:%s""".formatted(wanted, found.stream()
                                .map(s -> s.getClass().getSimpleName()).sorted().toList()));
            }
            return matched;
        }

        List<Seeder> entry = found.stream()
                .filter(s -> s.getClass().getSimpleName().equals(ENTRY_POINT))
                .toList();
        return entry.isEmpty() ? found : entry;
    }
}
