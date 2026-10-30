package io.jaravel.scheduler;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * 一次任务执行的结果,传给 {@code onSuccess} / {@code onFailure} 钩子。
 *
 * @param task      哪个任务
 * @param startedAt 开始时刻
 * @param duration  耗时
 * @param output    外部进程的输出({@code s.exec(...)});其它形态为空串
 * @param exitCode  外部进程的退出码;其它形态为 0
 * @param failure   失败原因。成功时为 null
 */
public record TaskOutcome(
        ScheduledTask task,
        ZonedDateTime startedAt,
        Duration duration,
        String output,
        int exitCode,
        Throwable failure
) {

    public boolean succeeded() {
        return failure == null;
    }

    public boolean failed() {
        return failure != null;
    }

    /**
     * 适合放进告警的一行摘要。
     *
     * <p>**带上耗时**:一个「跑了 4 小时才失败」与「0.1 秒就失败」
     * 的告警意味着完全不同的问题,而只带异常消息的告警分不清。
     */
    public String summary() {
        if (succeeded()) {
            return "%s 成功,耗时 %d ms".formatted(task.label(), duration.toMillis());
        }
        return "%s 失败,耗时 %d ms:%s".formatted(
                task.label(), duration.toMillis(),
                failure.getMessage() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage());
    }

    static TaskOutcome success(ScheduledTask task, ZonedDateTime startedAt,
                               Duration duration, String output) {
        return new TaskOutcome(task, startedAt, duration, output, 0, null);
    }

    static TaskOutcome failure(ScheduledTask task, ZonedDateTime startedAt,
                               Duration duration, String output, int exitCode,
                               Throwable cause) {
        return new TaskOutcome(task, startedAt, duration, output, exitCode, cause);
    }
}
