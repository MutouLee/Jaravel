package io.jaravel.console.generators;

import io.jaravel.console.Command;

/** {@code jaravel make:job SendInvoice} —— 队列任务(docs/10 §1.1) */
@Command(name = "make:job", description = "新建一个队列任务", offline = true)
public final class MakeJob extends SimpleGenerator {

    @Override protected String targetPackage() { return "app.jobs"; }

    @Override protected String label() { return "任务"; }
}
