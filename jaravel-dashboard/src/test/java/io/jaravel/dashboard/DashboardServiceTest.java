package io.jaravel.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.queue.Batch;
import io.jaravel.queue.BatchRepository;
import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueueManager;
import io.jaravel.queue.QueueStore;
import io.jaravel.queue.QueuedJob;
import io.jaravel.queue.ShouldQueue;

/**
 * 面板的数据聚合(docs/10 §1.8)。
 *
 * <p>这一层的职责是「把三个数据源拼成一份快照」,而其中任何一个
 * 数据源都可能坏掉 —— 队列连不上、failed_jobs 表没建、批次存储不可用。
 * 那些必须变成 {@code warnings} 里的一行,而不是整页 500。
 */
class DashboardServiceTest {

    static class TestJob implements ShouldQueue {
        public void handle() {
        }
    }

    @Test
    @DisplayName("队列积压 × 连接,失败数按连接+队列分组")
    void overviewAggregatesQueuesAndFailures() {
        var queues = new QueueManager(new JobSerializer())
                .register("db", new FakeQueue("db", Map.of("default", 5L, "mail", 2L)))
                .register("redis", new FakeQueue("redis", Map.of("default", 7L, "mail", 0L)))
                .defaultConnection("db");

        var failed = new FakeFailedJobs()
                .add(failed("1", "db", "default", "发发票"))
                .add(failed("2", "db", "default", "发发票"))
                .add(failed("3", "redis", "mail", "发邮件"));

        var batches = new FakeBatches().add(batch("b1", "月度报表", 10, 3, false));

        var service = new DashboardService(queues, failed, batches,
                List.of("default", "mail"),
                () -> new DashboardService.WorkerStat("db", List.of("default"), 10,
                        100, 90, 5, 3, 2));

        var overview = service.overview();

        assertThat(overview.queues()).hasSize(4);
        assertThat(overview.queues())
                .filteredOn(q -> q.connection().equals("db") && q.queue().equals("default"))
                .singleElement().satisfies(q -> {
                    assertThat(q.pending()).isEqualTo(5);
                    assertThat(q.failed()).isEqualTo(2);
                    assertThat(q.error()).isNull();
                });
        assertThat(overview.queues())
                .filteredOn(q -> q.connection().equals("redis") && q.queue().equals("mail"))
                .singleElement().satisfies(q -> assertThat(q.failed()).isEqualTo(1));

        assertThat(overview.failedTotal()).isEqualTo(3);
        assertThat(overview.failed()).extracting(DashboardService.FailedJobView::reason)
                .as("reason() 取的是最深的 Caused by,不是 worker 包的那层")
                .allMatch(r -> r.contains("根因"));
        assertThat(overview.batches()).hasSize(1);
        assertThat(overview.worker().processed()).isEqualTo(100);
        assertThat(overview.warnings()).isEmpty();
    }

    @Test
    @DisplayName("★ 某个数据源坏了 → warnings 里一行,其余照常")
    void brokenSourcesBecomeWarnings() {
        var queues = new QueueManager(new JobSerializer())
                .register("db", new FakeQueue("db", Map.of()) {
                    @Override public long size(String queue) {
                        throw new IllegalStateException("数据库连不上");
                    }
                })
                .defaultConnection("db");

        var service = new DashboardService(queues, new ThrowingFailedJobs(),
                new ThrowingBatches(), List.of("default"), null);

        var overview = service.overview();

        assertThat(overview.queues()).singleElement().satisfies(q -> {
            assertThat(q.pending()).isEqualTo(-1);
            assertThat(q.error()).contains("数据库连不上");
        });
        assertThat(overview.failed()).isEmpty();
        assertThat(overview.batches()).isEmpty();
        assertThat(overview.warnings()).anyMatch(w -> w.contains("失败任务"))
                .anyMatch(w -> w.contains("批次"));
    }

    @Test
    @DisplayName("★ retry 放回原连接并删除失败记录")
    void retryPushesToTheOriginalConnection() {
        var serializer = new JobSerializer();
        QueuedJob original = QueuedJob.forTest("u1", TestJob.class, Map.of(), "mail", 0, 3);

        var db = new FakeQueue("db", Map.of());
        var redis = new FakeQueue("redis", Map.of());
        var queues = new QueueManager(serializer)
                .register("db", db).register("redis", redis).defaultConnection("db");

        var failed = new FakeFailedJobs().add(new FailedJobStore.FailedJob(
                "7", "u1", "redis", "mail", "TestJob",
                serializer.encode(original), "boom", Instant.now()));

        var service = new DashboardService(queues, failed, null,
                List.of("mail"), null);

        assertThat(service.retry("7")).isTrue();

        assertThat(redis.pushed).extracting(QueuedJob::uuid).containsExactly("u1");
        assertThat(db.pushed).isEmpty();
        assertThat(failed.find("7")).isEmpty();
        assertThat(service.retry("999")).isFalse();
    }

    @Test
    @DisplayName("forget / flush")
    void forgetAndFlush() {
        var service = new DashboardService(
                new QueueManager(new JobSerializer())
                        .register("db", new FakeQueue("db", Map.of())).defaultConnection("db"),
                new FakeFailedJobs()
                        .add(failed("1", "db", "default", "a"))
                        .add(failed("2", "db", "default", "b")),
                null, List.of("default"), null);

        assertThat(service.forget("1")).isTrue();
        assertThat(service.flushFailed()).isEqualTo(1);
    }

    // ================= 夹具 =================

    private static FailedJobStore.FailedJob failed(String id, String connection,
                                                   String queue, String name) {
        return new FailedJobStore.FailedJob(id, "u" + id, connection, queue, name,
                "{}", """
                io.jaravel.queue.QueueWorker$JobFailedException: 已尝试 1 次
                    at io.jaravel.queue.QueueWorker.process(QueueWorker.java:1)
                Caused by: java.lang.IllegalStateException: 根因""", Instant.now());
    }

    private static Batch batch(String id, String name, int total, int pending, boolean failed) {
        return new Batch(id, name, total, pending, failed ? 1 : 0, List.of(),
                false, Instant.now(), null, null);
    }

    static class FakeQueue implements QueueStore {
        final List<QueuedJob> pushed = new ArrayList<>();
        private final String name;
        private final Map<String, Long> sizes;

        FakeQueue(String name, Map<String, Long> sizes) {
            this.name = name;
            this.sizes = sizes;
        }

        @Override public String name() { return name; }
        @Override public String push(QueuedJob job) { pushed.add(job); return job.uuid(); }
        @Override public Optional<QueuedJob> pop(String queue, Duration retryAfter) {
            return Optional.empty();
        }
        @Override public void delete(QueuedJob job) { }
        @Override public void release(QueuedJob job, Duration delay) { }
        @Override public long size(String queue) {
            Long size = sizes.get(queue);
            if (size == null) throw new IllegalStateException("未知队列 " + queue);
            return size;
        }
        @Override public void clear(String queue) { }
    }

    static class FakeFailedJobs implements FailedJobStore {
        final List<FailedJob> all = new ArrayList<>();
        private long nextId = 100;

        FakeFailedJobs add(FailedJob job) {
            all.add(job);
            return this;
        }

        @Override public String record(QueuedJob job, String connection, Throwable cause) {
            String id = String.valueOf(nextId++);
            all.add(new FailedJob(id, job.uuid(), connection, job.queue(),
                    job.displayName(), "{}", String.valueOf(cause), Instant.now()));
            return id;
        }
        @Override public Optional<FailedJob> find(String id) {
            return all.stream().filter(j -> j.id().equals(id)).findFirst();
        }
        @Override public List<FailedJob> all() { return List.copyOf(all); }
        @Override public boolean forget(String id) { return all.removeIf(j -> j.id().equals(id)); }
        @Override public int flush() { int n = all.size(); all.clear(); return n; }
        @Override public int prune(Duration olderThan) { return 0; }
        @Override public long count() { return all.size(); }
    }

    static class ThrowingFailedJobs implements FailedJobStore {
        @Override public String record(QueuedJob job, String connection, Throwable cause) { return "0"; }
        @Override public Optional<FailedJob> find(String id) { return Optional.empty(); }
        @Override public List<FailedJob> all() { throw new IllegalStateException("failed_jobs 表不存在"); }
        @Override public boolean forget(String id) { return false; }
        @Override public int flush() { return 0; }
        @Override public int prune(Duration olderThan) { return 0; }
        @Override public long count() { return 0; }
    }

    static class FakeBatches implements BatchRepository {
        final List<Batch> batches = new ArrayList<>();
        FakeBatches add(Batch batch) { batches.add(batch); return this; }

        @Override public Batch create(String id, String name, int totalJobs, boolean allowsFailures,
                                      QueuedJob thenCallback, QueuedJob catchCallback,
                                      QueuedJob finallyCallback) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<Batch> find(String id) { return batches.stream().filter(b -> b.id().equals(id)).findFirst(); }
        @Override public Batch recordSuccess(String id) { throw new UnsupportedOperationException(); }
        @Override public Batch recordFailure(String id, String jobUuid) { throw new UnsupportedOperationException(); }
        @Override public void cancel(String id) { }
        @Override public boolean markFinished(String id) { return false; }
        @Override public Optional<QueuedJob> callback(String id, Kind kind) { return Optional.empty(); }
        @Override public int prune(Duration olderThan) { return 0; }
        @Override public List<Batch> recent(int limit) { return batches.stream().limit(limit).toList(); }
    }

    static class ThrowingBatches implements BatchRepository {
        @Override public Batch create(String id, String name, int totalJobs, boolean allowsFailures,
                                      QueuedJob thenCallback, QueuedJob catchCallback,
                                      QueuedJob finallyCallback) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<Batch> find(String id) { return Optional.empty(); }
        @Override public Batch recordSuccess(String id) { throw new UnsupportedOperationException(); }
        @Override public Batch recordFailure(String id, String jobUuid) { throw new UnsupportedOperationException(); }
        @Override public void cancel(String id) { }
        @Override public boolean markFinished(String id) { return false; }
        @Override public Optional<QueuedJob> callback(String id, Kind kind) { return Optional.empty(); }
        @Override public int prune(Duration olderThan) { return 0; }
        @Override public List<Batch> recent(int limit) {
            throw new IllegalStateException("job_batches 表不存在");
        }
    }
}
