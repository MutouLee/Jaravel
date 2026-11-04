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
        return 0;
    }

    private void describe(String name) {
        twoColumnDetail(name, commands().descriptionOf(name));
    }
}
