package app.console;

import java.util.List;
import java.util.Map;

import app.models.Invoice;
import app.models.Invoice_;
import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.Option;
import org.springframework.stereotype.Component;

/**
 * 按状态汇总发票。
 *
 * <pre>
 * jaravel invoice:report
 * jaravel invoice:report --json      给脚本用
 * </pre>
 *
 * <p>{@code --json} 不是摆设:一条检视命令迟早会被 CI 或监控脚本调用,
 * 而让它们去解析对齐过的表格是在制造脆弱的管道。
 */
@Component
@Command(name = "invoice:report", description = "按状态汇总发票")
public class InvoiceReportCommand extends ConsoleCommand {

    private static final List<String> STATUSES =
            List.of("pending", "sent", "ready", "archived", "stale");

    @Option(names = "--json", description = "输出 JSON")
    boolean json;

    @Override
    public int handle() {
        var counts = new java.util.LinkedHashMap<String, Long>();
        for (String status : STATUSES) {
            counts.put(status, Invoice.query().where(Invoice_.status, status).count());
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();

        if (json) {
            // 手拼 JSON:这个示例里的数据形状固定,引不进一个序列化库
            // 只为三个字段(而 ObjectMapper 在 CLI 的 classpath 上也不保证有)
            var out = new StringBuilder("{\"total\":").append(total).append(",\"byStatus\":{");
            boolean first = true;
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
            }
            line(out.append("}}").toString());
            return 0;
        }

        comment("发票(共 " + total + " 张)");
        counts.forEach((status, count) -> twoColumnDetail(status, count + " 张"));

        if (counts.get("pending") > 0) {
            newLine();
            line("有 " + counts.get("pending") + " 张待发 —— invoice:send 可以投出去。");
        }
        return 0;
    }
}
