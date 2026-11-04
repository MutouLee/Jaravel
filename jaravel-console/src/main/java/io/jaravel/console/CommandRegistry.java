package io.jaravel.console;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.jaravel.support.Str;

/**
 * 命令注册表 —— docs/13 §6 里的 {@code ConsoleKernel} 的那一半。
 *
 * <h2>为什么存的是工厂而不是实例</h2>
 * <p>命令对象**带状态**:解析出来的参数就写在它的字段上。
 * 存实例的话第二次调用会看到第一次留下的 {@code --force},
 * 而那是一个「只在连着跑两条命令时出现」的错。
 * 所以每次运行都新建一个,参数绑定到一个干净的对象上。
 *
 * <p>工厂同时是依赖注入的接缝:{@code register("inventory:sync",
 * () -> new SyncInventory(container.get(SupplierClient.class)))}。
 * 这样这个模块不需要知道容器的存在(它只依赖 support)。
 */
public final class CommandRegistry {

    private final Map<String, Supplier<? extends ConsoleCommand>> factories = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final List<java.util.function.Consumer<ConsoleCommand>> configurers = new ArrayList<>();

    /**
     * 每个新建出来的命令都过一遍这些钩子。
     *
     * <h2>★ 为什么需要它:子命令</h2>
     * <p>{@code make:model Post -m} 会 {@code call("make:migration", …)},
     * 而那是从注册表**新建**的一个实例 —— 它拿不到父命令身上的东西。
     * 最要紧的是项目根({@code inProject}):不传下去的话,
     * 父命令往临时目录写模型,子命令往当前工作目录写迁移,
     * 而那在测试里表现为「迁移没生成」、在 {@code jaravel new} 里
     * 表现为「文件生成到了错误的项目」。
     *
     * <p>做成通用钩子而不是在 {@code call} 里拷字段:
     * 将来的依赖注入(从容器取构造参数)走的也是这个接缝。
     */
    public CommandRegistry configure(java.util.function.Consumer<ConsoleCommand> configurer) {
        configurers.add(configurer);
        return this;
    }

    /** 用无参构造的命令 */
    public CommandRegistry register(Class<? extends ConsoleCommand> type) {
        return register(() -> {
            try {
                var constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new ConsoleException(type.getSimpleName()
                        + " 没有可用的无参构造。带依赖的命令用 register(name, factory)"
                        + " 把依赖传进去。", e);
            }
        });
    }

    /**
     * 带依赖的命令。
     *
     * <p>⚠ **必须给类型**,不能只给工厂。名字与说明在 {@code @Command} 注解上,
     * 只有工厂的话就得先建一个实例才能读到它们 —— 而那会在**注册的时候**
     * 解析这条命令的全部依赖。
     *
     * <p>后果很具体:注册表在应用启动的很早就建好(门面要它),
     * 而一条 {@code route:list} 的依赖是编译后的路由表,它要等所有路由定义完。
     * 「注册时就建实例」会把注册表拖到那之后,而那条链上还有控制器 bean;
     * 更糟的是一个**没有数据库的应用**会在注册 {@code migrate} 时就炸,
     * 而那条命令它根本不会用。
     *
     * <p>给了类型就只读注解,实例留到真要跑的时候再建。
     */
    public CommandRegistry register(Class<? extends ConsoleCommand> type,
                                    Supplier<? extends ConsoleCommand> factory) {
        Command annotation = type.getAnnotation(Command.class);
        if (annotation == null) {
            throw new ConsoleException(type.getSimpleName()
                    + " 没有 @Command 注解 —— 命令要有名字才能被调用");
        }
        factories.put(annotation.name(), factory);
        descriptions.put(annotation.name(), annotation.description());
        return this;
    }

    /**
     * 只给工厂 —— 会**立刻建一个实例**来读它的名字。
     *
     * <p>所以它只适合没有依赖(或者依赖已经在手上)的命令。
     * 依赖要从容器取的,用 {@link #register(Class, Supplier)}。
     */
    public CommandRegistry register(Supplier<? extends ConsoleCommand> factory) {
        ConsoleCommand probe = factory.get();
        factories.put(probe.name(), factory);
        descriptions.put(probe.name(), probe.description());
        return this;
    }

    public boolean has(String name) {
        return factories.containsKey(name);
    }

    /**
     * 新建一个实例但**不运行** —— {@code help} 要读它的 @Argument/@Option。
     *
     * <p>不存在时返回 null:调用方(help)要自己给出「没有这条命令」的
     * 提示,而那条提示带着「是否想写」的建议,与这里的 null 不是一回事。
     */
    public ConsoleCommand create(String name) {
        var factory = factories.get(name);
        if (factory == null) return null;
        ConsoleCommand command = factory.get();
        configurers.forEach(configurer -> configurer.accept(command));
        return command;
    }

    /** 这条命令需要应用上下文吗(docs/13 §1 的离线/在线分流) */
    public boolean isOffline(String name) {
        ConsoleCommand command = create(name);
        if (command == null) return false;
        Command annotation = command.getClass().getAnnotation(Command.class);
        return annotation != null && annotation.offline();
    }

    public List<String> names() {
        return List.copyOf(factories.keySet());
    }

    public String descriptionOf(String name) {
        return descriptions.getOrDefault(name, "");
    }

    /**
     * 跑一条命令。返回退出码。
     *
     * <p>{@link ConsoleException} 被就地处理成「打印消息 + 返回 1」——
     * 用错命令不该得到一屏与原因无关的栈帧(见那个异常的注释)。
     * 其他异常原样抛出:那是真的失败了,调用方(CLI 或测试)
     * 要能看到完整的栈。
     */
    public int run(String name, List<String> args, ConsoleIo io) {
        Supplier<? extends ConsoleCommand> factory = factories.get(name);
        if (factory == null) {
            io.writeError(unknownCommand(name) + "\n");
            return 1;
        }

        ConsoleCommand command = factory.get();
        command.bind(io, this);
        configurers.forEach(configurer -> configurer.accept(command));
        try {
            ArgumentBinder.applyDefaults(command);
            ArgumentBinder.bind(command, args);
            return command.handle();
        } catch (ConsoleException e) {
            io.writeError(e.getMessage() + "\n");
            return 1;
        }
    }

    /**
     * 「没有这条命令」要给出最接近的那个。
     *
     * <p>{@code make:modle} 这种手误在 CLI 上太常见,而一句
     * 「Command not found」留给用户的下一步是去翻命令清单 ——
     * 而正确答案就在一个字母之外。
     */
    private String unknownCommand(String name) {
        var message = new StringBuilder("没有命令 \"" + name + "\"");
        suggestion(name).ifPresent(closest ->
                message.append("\n  是否想写 \"").append(closest).append("\" ?"));

        String group = Str.before(name, ":");
        var sameGroup = new ArrayList<String>();
        if (!group.isEmpty() && !group.equals(name)) {
            factories.keySet().stream().filter(n -> n.startsWith(group + ":")).forEach(sameGroup::add);
        }
        if (!sameGroup.isEmpty()) {
            message.append("\n  ").append(group).append(": 下有:")
                   .append(String.join(" ", sameGroup));
        }
        return message.toString();
    }

    /**
     * 这个名字最像注册表里的哪一条 —— 没有足够像的就是 empty。
     *
     * <h2>★ 为什么是 public:{@code jaravel-cli} 要用它来**分流**</h2>
     * <p>CLI 遇到一个不认识的名字时有两种可能:用户敲错了,
     * 或者那是**应用自己定义的**命令(CLI 这个进程里没有应用,
     * 所以它的注册表里当然没有)。两者的处理截然不同:前者该立刻给建议,
     * 后者要去问应用 —— 走通道,或者冷启动一个 JVM(要几秒)。
     *
     * <p>有近似匹配就按「敲错了」处理,因为那条路**便宜且几乎总是对的**:
     * {@code make:modle} 不值得为它启动一个应用 JVM。
     *
     * @return 阈值内最近的那条命令名
     */
    public java.util.Optional<String> suggestion(String name) {
        String closest = null;
        int best = Integer.MAX_VALUE;
        for (String candidate : factories.keySet()) {
            int distance = distance(name, candidate);
            if (distance < best) {
                best = distance;
                closest = candidate;
            }
        }
        // 阈值:名字长度的三分之一。再松就会把 make:model 推给 db:seed
        boolean close = closest != null && best <= Math.max(1, name.length() / 3);
        return close ? java.util.Optional.of(closest) : java.util.Optional.empty();
    }

    /** 编辑距离。命令名很短,O(n·m) 的朴素实现足够 */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                                      previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
