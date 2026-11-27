package net.dobeen.jaravel.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 信封本身的边界(docs/10 §1.6)。
 */
class QueuedJobTest {

    @Test
    @DisplayName("★ 任务字段可以有 null —— 不能因为一个可空参数就入队失败")
    void nullFieldsSurvive() {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("orderId", 1);
        data.put("note", null);

        Instant now = Instant.now();
        var job = new QueuedJob("u1", "app.jobs.TestJob", data, "default", 0,
                now, now, 1, null, null, 1, "TestJob", 0, null,
                List.of(), null, Map.of());

        assertThat(job.data()).containsKey("note");
        assertThat(job.data().get("note")).isNull();
    }

    @Test
    @DisplayName("null 字段经过 JSON 往返仍然是 null,不是被丢掉")
    void nullSurvivesJsonRoundTrip() {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("note", null);

        Instant now = Instant.now();
        var job = new QueuedJob("u2", "app.jobs.TestJob", data, "default", 0,
                now, now, 1, null, null, 1, "TestJob", 0, null,
                List.of(), null, Map.of());

        var serializer = new JobSerializer();
        var decoded = serializer.decode(serializer.encode(job));

        assertThat(decoded.data()).containsKey("note");
        assertThat(decoded.data().get("note")).isNull();
    }
}
