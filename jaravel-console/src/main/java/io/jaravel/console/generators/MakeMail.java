package io.jaravel.console.generators;

import io.jaravel.console.Command;

/** {@code jaravel make:mail OrderShipped} —— Mailable(docs/11 §1.1) */
@Command(name = "make:mail", description = "新建一个 Mailable", offline = true)
public final class MakeMail extends SimpleGenerator {

    @Override protected String targetPackage() { return "app.mail"; }

    @Override protected String label() { return "邮件"; }
}
