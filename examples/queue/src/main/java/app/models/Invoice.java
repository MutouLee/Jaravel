package app.models;

import java.time.Instant;

import io.jaravel.eloquent.annotations.Fillable;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

/**
 * 发票。任务执行的**结果**落在这里。
 *
 * <p>示例里刻意有这张表:断言「任务跑了」不该靠看日志。
 * 日志断言在队列里特别不可靠 —— 一个任务被重试两次会打两条日志,
 * 而那与「两个任务各跑一次」在日志里长得一模一样。
 */
@Model(table = "invoices")
// @Fillable 列的是**数据库列名**而不是属性名 —— 它守的是
// 「哪些列允许从请求批量赋值」,而那道防线的对象是列
@Fillable({"order_no", "amount", "status"})
public class Invoice extends InvoiceBase {

    @Id public Long id;
    public String orderNo;
    public Integer amount;
    public String status;

    /** 这张发票被处理了几次 —— 用来断言重试真的发生了 */
    public Integer attempts;

    public Instant createdAt;
    public Instant updatedAt;
}
