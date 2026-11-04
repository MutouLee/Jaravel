package io.jaravel.spring.commands;

import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.Option;
import io.jaravel.core.Application;

/**
 * 需要应用上下文的命令的基类(docs/13 §1 的「在线命令」)。
 *
 * <h2>为什么这些命令在 starter 里,而不是 jaravel-console</h2>
 * <p>docs/13 §6 把它们画在 {@code jaravel-console/commands/}。实现时放不进去,
 * 有两个具体原因:
 *
 * <ol>
 *   <li><b>迁移与 seeder 的发现本来就是 Spring 的</b>:
 *       {@code MigrationRunner} 用 {@code ClassPathScanningCandidateComponentProvider}
 *       扫 {@code database.migrations} 包(ADR-009 的约定布局)。
 *       那段代码只能在 starter 里。</li>
 *   <li><b>jaravel-console 要保持羽量级</b>:{@code jaravel-cli} 依赖它,
 *       而 CLI 的存在理由就是「离线命令不启动容器」。让 console 依赖
 *       migration / database / routing,CLI 的 classpath 就会把整个数据层
 *       拖进来 —— 那三秒就回来了,native 镜像也打不出来。
 *       这条由 archunit 阻断式守着。</li>
 * </ol>
 *
 * <p>所以命令住在适配层,通过**构造器**拿到它要的东西 ——
 * 与 jaravel-mail 的 {@code ViewRenderer}、调度器的 {@code CommandRunner}
 * 是同一个做法。
 */
abstract class RuntimeCommand extends ConsoleCommand {

    @Option(names = "--force", description = "生产环境下跳过确认")
    protected boolean force;

    /**
     * 生产环境的护栏。
     *
     * <h2>★ 为什么不可交互时是「拒绝」而不是「放行」</h2>
     * <p>这些命令会改库结构或删数据。它们跑在三种地方:
     * 开发者的终端(可交互)、本地命令通道(不可交互)、
     * {@code Artisan.call}(不可交互)。
     *
     * <p>{@code confirm(..., false)} 在不可交互时返回默认值 {@code false} ——
     * 于是**没有终端的情况下一律拒绝**,要做就显式写 {@code --force}。
     * 反过来(默认放行)意味着一个部署脚本里的 {@code migrate} 会在
     * 生产库上静默执行,而那正是 Laravel 当年加这个护栏的原因。
     *
     * @return true 表示可以继续
     */
    protected boolean confirmInProduction(String what) {
        if (force || !Application.isProduction()) return true;

        warn("当前是 **生产环境**(app.env=production),而这条命令会" + what + "。");
        if (confirm("确定要继续吗?", false)) return true;

        error("已取消。确实要在生产环境执行的话,加 --force。");
        return false;
    }
}
