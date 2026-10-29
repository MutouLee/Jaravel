package app.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

/**
 * 记下批次回调跑过什么。
 *
 * <p>存在的理由:断言「回调只跑了一次」需要一个计数,而日志做不到 ——
 * 回调跑两遍与「一次回调打了两条日志」在日志里分不开。
 * 而「回调只跑一次」正是批处理里最重要的一条保证
 * (一个「给所有人发汇总邮件」的回调跑两遍是用户可见的事故)。
 */
@Component
public class BatchLedger {

    private final List<String> entries = new CopyOnWriteArrayList<>();

    public void record(String entry) {
        entries.add(entry);
    }

    public List<String> entries() {
        return List.copyOf(entries);
    }

    public long countStartingWith(String prefix) {
        return entries.stream().filter(e -> e.startsWith(prefix)).count();
    }

    public void reset() {
        entries.clear();
    }
}
