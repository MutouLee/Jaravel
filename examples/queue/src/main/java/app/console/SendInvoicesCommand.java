package app.console;

import java.util.List;

import app.jobs.SendInvoice;
import app.models.Invoice;
import app.models.Invoice_;
import io.jaravel.console.Argument;
import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.Option;
import io.jaravel.queue.Queue;
import org.springframework.stereotype.Component;

/**
 * 把待发的发票投进队列。
 *
 * <pre>
 * jaravel invoice:send                  全部待发
 * jaravel invoice:send 3 --dry-run      只看某个客户的,不真投
 * </pre>
 *
 * <p>只要 {@code @Component} —— 框架每跑一次新造一个实例,
 * 所以不用操心「上一条命令留下的选项」。
 */
@Component
@Command(name = "invoice:send", description = "把待发的发票投进队列")
public class SendInvoicesCommand extends ConsoleCommand {

    @Argument(description = "只处理这个客户的发票", required = false)
    Long customerId;

    @Option(names = {"-n", "--dry-run"}, description = "只列出,不真的投递")
    boolean dryRun;

    @Option(names = "--limit", description = "最多处理几张")
    int limit = 50;

    @Override
    public int handle() {
        var query = Invoice.query().where(Invoice_.status, "pending");
        if (customerId != null) {
            query = query.where(Invoice_.customerId, customerId);
        }
        List<Invoice> pending = query.limit(limit).get().toList();

        if (pending.isEmpty()) {
            info("没有待发的发票。");
            return 0;
        }

        table(List.of("ID", "单号", "金额", "客户"), pending, invoice -> List.of(
                invoice.id,
                invoice.orderNo,
                invoice.amount + " 元",
                invoice.customerId == null ? "—" : invoice.customerId));

        if (dryRun) {
            newLine();
            comment("--dry-run:什么都没投。");
            return 0;
        }
        if (!confirm("把这 " + pending.size() + " 张投进队列?", true)) {
            error("已取消。");
            return 1;
        }

        withProgressBar(pending, invoice -> Queue.dispatch(new SendInvoice(invoice.id)));
        newLine();
        success("已投递 " + pending.size() + " 张。");
        return 0;
    }
}
