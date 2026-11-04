package io.jaravel.console.generators;

import io.jaravel.console.Command;
import io.jaravel.console.Option;
import io.jaravel.support.Str;

/**
 * {@code jaravel make:listener NotifyWarehouse --event=OrderPaid}(docs/10 §2.2)。
 *
 * <h2>没给 --event 时生成 {@code handle(Object)}</h2>
 * <p>不是偷懒:不给的话唯一的选择是往 stub 里填一个**不存在的事件类名**
 * (`handle(ExampleEvent e)`),而那生成出来的是一个编译不过的文件。
 * 生成的代码必须能编译 —— 否则用户第一件事是修框架给他的报错。
 *
 * <p>所以分两个 stub(与「每个变体一个 stub 文件」的做法一致)。
 */
@Command(name = "make:listener", description = "新建一个事件监听器")
public final class MakeListener extends SimpleGenerator {

    @Option(names = "--event", description = "要监听的事件类(app.events 下)")
    String event;

    @Override protected String targetPackage() { return "app.listeners"; }

    @Override protected String label() { return "监听器"; }

    @Override
    protected String stubName() {
        return hasEvent() ? "listener.event" : "listener";
    }

    @Override
    protected void customise(Stub stub, String className) {
        if (hasEvent()) stub.with("event", Str.studly(event.trim()));
    }

    private boolean hasEvent() {
        return event != null && !event.isBlank();
    }
}
