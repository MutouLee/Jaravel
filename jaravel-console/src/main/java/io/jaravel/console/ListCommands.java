package io.jaravel.console;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jaravel.support.Str;

/**
 * {@code jaravel list} —— 所有命令,按冒号前的那一段分组。
 *
 * <h2>为什么分组</h2>
 * <p>artisan 的命令有上百条,Jaravel 的清单(docs/13 §2)也在那个量级。
 * 一个按字母排的平铺列表里,{@code make:model} 与 {@code migrate}
 * 挨在一起而 {@code make:controller} 在十行之外 —— 而人找的是
 * 「make 下面有什么」。
 */
@Command(name = "list", description = "列出所有命令", offline = true)
public final class ListCommands extends ConsoleCommand {

    @Override
    public int handle() {
        var grouped = new LinkedHashMap<String, List<String>>();
        for (String name : commands().names().stream().sorted().toList()) {
            String group = name.contains(":") ? Str.before(name, ":") : "";
            grouped.computeIfAbsent(group, g -> new ArrayList<>()).add(name);
        }

        // 不分组的(list / help / migrate)排在最前面 —— 它们是入口
        List<String> ungrouped = grouped.remove("");
        if (ungrouped != null) {
            newLine();
            ungrouped.forEach(this::describe);
        }

        grouped.forEach((group, names) -> {
            newLine();
            comment(group);
            names.forEach(this::describe);
        });
        newLine();
        line("某条命令的详细用法:help <命令>");

        if (withoutApplication) {
            newLine();
            warn("这张清单**不全**:只有不需要应用的命令。");
            line("  migrate / db:* / queue:* / schedule:* / tinker,以及");
            line("  **应用自己定义的命令**,都要问应用才知道。");
            line("  出路:把应用跑起来(它会开一个命令通道),");
            line("  再敲一次 list —— 那时拿到的是完整的一张。");
        }
        return 0;
    }

    /**
     * 这张表是不是「只有离线命令」的那张。
     *
     * <h2>★ 为什么要由调用方告诉它,而不是自己判断</h2>
     * <p>自己判断的办法是遍历注册表、看有没有非 offline 的命令 ——
     * 而那要**把每条命令都实例化一遍**({@code isOffline} 要读实例上的注解)。
     * 为了打一行脚注去构造所有命令(在应用里还会触发构造器注入)
     * 不值当,而且有副作用。
     *
     * <p>{@code jaravel-cli} 在建注册表时直接说明这件事 ——
     * 它是唯一知道答案的那个:它这个进程里根本没有应用。
     */
    public void withoutApplication() {
        this.withoutApplication = true;
    }

    private boolean withoutApplication;

    private void describe(String name) {
        twoColumnDetail(name, commands().descriptionOf(name));
    }
}
