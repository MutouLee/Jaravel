package io.jaravel.console.generators;

import io.jaravel.console.Command;

/** {@code jaravel make:notification InvoicePaid} —— 通知(docs/11 §2) */
@Command(name = "make:notification", description = "新建一个通知", offline = true)
public final class MakeNotification extends SimpleGenerator {

    @Override protected String targetPackage() { return "app.notifications"; }

    @Override protected String label() { return "通知"; }
}
