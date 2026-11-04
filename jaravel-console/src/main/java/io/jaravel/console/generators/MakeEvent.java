package io.jaravel.console.generators;

import io.jaravel.console.Command;

/**
 * {@code jaravel make:event OrderPaid} —— 事件(docs/10 §2)。
 *
 * <p>生成的是一个 {@code record}:事件是**不可变的事实**,
 * 一个带 setter 的事件意味着监听器能改掉后面监听器看到的东西,
 * 而那种耦合在事件总线上无从追踪。
 */
@Command(name = "make:event", description = "新建一个事件")
public final class MakeEvent extends SimpleGenerator {

    @Override protected String targetPackage() { return "app.events"; }

    @Override protected String label() { return "事件"; }
}
