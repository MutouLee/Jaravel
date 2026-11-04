package app.console;

import java.time.Duration;
import java.time.Instant;

import app.models.Invoice;
import app.models.Invoice_;
import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.Option;
import io.jaravel.database.query.QueryState.Op;
import io.jaravel.core.Application;
import org.springframework.stereotype.Component;

/**
 * 清掉已归档的旧发票。
 *
 * <pre>
 * jaravel invoice:prune --days=90
 * </pre>
 *
 * <p>★ 它会删数据,所以生产环境下**不可交互时一律拒绝**:
 * {@code confirm(..., false)} 在没有终端时返回默认值 false ——
 * 于是一个部署脚本里的 invoice:prune 不会静默删生产数据。
 * 要删就显式 {@code --force}。
 */
@Component
@Command(name = "invoice:prune", description = "删掉 N 天前已归档的发票")
public class PruneInvoicesCommand extends ConsoleCommand {

    @Option(names = "--days", description = "保留最近多少天(默认 30)")
    int days = 30;

    @Option(names = "--force", description = "跳过确认")
    boolean force;

    @Override
    public int handle() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));

        long doomed = Invoice.query()
                .where(Invoice_.status, "archived")
                .where(Invoice_.createdAt, Op.LT, cutoff)
                .count();

        if (doomed == 0) {
            info(days + " 天前没有已归档的发票。");
            return 0;
        }
        if (!force) {
            warn(doomed + " 张发票会被**删除**,不可恢复。");
            if (Application.isProduction()) {
                error("这是生产环境 —— 要删请显式加 --force。");
                return 1;
            }
            if (!confirm("继续?", false)) {
                error("已取消。");
                return 1;
            }
        }

        int deleted = Invoice.query()
                .where(Invoice_.status, "archived")
                .where(Invoice_.createdAt, Op.LT, cutoff)
                .delete();

        success("已删除 " + deleted + " 张。");
        return 0;
    }
}
