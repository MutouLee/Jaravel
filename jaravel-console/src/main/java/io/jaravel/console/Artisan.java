package io.jaravel.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 程序化调用命令的静态门面(docs/13 §3.3)。
 *
 * <pre>{@code
 * Artisan.call("migrate", Map.of("--force", true));
 * Artisan.call("inventory:sync", "acme");
 * String output = Artisan.output();
 * }</pre>
 *
 * <h2>★ output() 是上一次调用的输出,而且是 ThreadLocal 的</h2>
 * <p>Laravel 的 {@code Artisan::output()} 也是「上一次」的语义。
 * 这里额外按线程隔离:两个并发请求各自 {@code Artisan.call(...)} 时,
 * 共享一个缓冲区会让它们读到对方的输出 ——
 * 而那种污染在测试里基本不可能复现(要恰好并发)。
 */
public final class Artisan {

    private static volatile CommandRegistry registry;
    private static final ThreadLocal<String> LAST_OUTPUT = new ThreadLocal<>();

    private Artisan() {
    }

    public static void use(CommandRegistry configured) {
        registry = configured;
    }

    public static CommandRegistry registry() {
        CommandRegistry current = registry;
        if (current == null) {
            throw new ConsoleException("""
                    控制台尚未装配。
                      正常情况下 jaravel-spring-boot-starter 会在启动时完成;
                      单元测试里调
                        Artisan.use(new CommandRegistry().register(MakeModel.class));""");
        }
        return current;
    }

    /** 测试结束后清掉 —— 静态状态不清会漏到下一个测试 */
    public static void reset() {
        registry = null;
        LAST_OUTPUT.remove();
    }

    // ================= 调用 =================

    public static int call(String command, String... args) {
        return call(command, List.of(args));
    }

    public static int call(String command, List<String> args) {
        var io = new ConsoleIo.Recording();
        try {
            return registry().run(command, args, io);
        } finally {
            // ★ finally:命令**抛异常时也要**把已有输出留下。
            //
            // 不这么做的话,一个跑到一半炸掉的 migrate 会让 output() 返回上一次
            // 调用的内容 —— 而调用方正要用它写日志。「第几个迁移炸的」
            // 恰恰在那段输出里,异常本身只说了原因。
            //
            // 错误也进 output():调用方拿它去记日志时,最该看到的就是报错。
            LAST_OUTPUT.set(io.output() + io.errors());
        }
    }

    /**
     * {@code Map} 形式的参数(docs/13 §3.3 的写法)。
     *
     * <p>{@code Map.of("--force", true)} → {@code --force};
     * {@code Map.of("supplier", "acme")} → 位置参数 {@code acme}。
     * 键以 {@code -} 开头的是选项,否则是位置参数 —— 与命令行一致。
     *
     * <p>⚠ {@code Map} 没有顺序,所以**多于一个位置参数时顺序不确定**。
     * 那种情况用 {@link #call(String, List)} ——
     * 这里报错而不是随机排一个顺序。
     */
    public static int call(String command, Map<String, ?> arguments) {
        var options = new ArrayList<String>();
        var positionals = new ArrayList<String>();

        arguments.forEach((key, value) -> {
            if (key.startsWith("-")) {
                if (Boolean.TRUE.equals(value)) options.add(key);
                else if (!Boolean.FALSE.equals(value)) options.add(key + "=" + value);
            } else {
                positionals.add(String.valueOf(value));
            }
        });

        if (positionals.size() > 1) {
            throw new ConsoleException("""
                    Artisan.call(name, Map) 只能带一个位置参数 —— Map 没有顺序,
                    多于一个的话传进命令里的次序是不确定的。
                      改用 Artisan.call("%s", List.of(…))。""".formatted(command));
        }

        var args = new ArrayList<String>(positionals);
        args.addAll(options);
        return call(command, args);
    }

    /** 上一次 {@link #call} 的输出(含 stderr) */
    public static String output() {
        String output = LAST_OUTPUT.get();
        return output == null ? "" : output;
    }
}
