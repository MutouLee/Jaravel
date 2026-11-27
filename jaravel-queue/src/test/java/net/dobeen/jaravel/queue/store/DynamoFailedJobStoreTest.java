package net.dobeen.jaravel.queue.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.dobeen.jaravel.queue.FailedJobStore;
import net.dobeen.jaravel.queue.JobSerializer;
import net.dobeen.jaravel.queue.QueuedJob;

/**
 * 失败任务的 DynamoDB 存储(docs/10 §1.7)。
 *
 * <p>给了 {@code -Djaravel.test.dynamodb.endpoint} 或环境变量
 * {@code JARAVEL_TEST_DYNAMODB_ENDPOINT} 才跑。本地:
 *
 * <pre>
 * docker run -d --name jaravel-dynamodb -p 8000:8000 amazon/dynamodb-local
 * </pre>
 *
 * <p>每次运行一张唯一命名的表 —— 上一次的残留不会影响这一次,
 * 而共用一张表的测试在并行跑时会互相删对方的记录。
 */
class DynamoFailedJobStoreTest {

    private static final JobSerializer SERIALIZER = new JobSerializer();
    private static DynamoFailedJobStore store;

    @BeforeAll
    static void connect() {
        String endpoint = firstNonBlank(
                System.getProperty("jaravel.test.dynamodb.endpoint"),
                System.getenv("JARAVEL_TEST_DYNAMODB_ENDPOINT"));
        Assumptions.assumeTrue(endpoint != null,
                "设置 -Djaravel.test.dynamodb.endpoint 才跑 DynamoDB 失败任务测试");

        String table = "jaravel_failed_test_"
                + UUID.randomUUID().toString().substring(0, 8);
        store = DynamoFailedJobStore.connect(endpoint, "us-east-1",
                "test", "test", table, SERIALIZER);
    }

    @BeforeEach
    void clear() {
        store.flush();
    }

    @AfterAll
    static void tearDown() {
        if (store != null) {
            store.flush();
            store.close();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static QueuedJob job(String uuid) {
        return QueuedJob.forTest(uuid, "app.jobs.TestJob", Map.of("x", 1), "default", 3, 3);
    }

    @Test
    @DisplayName("★ 句柄就是任务的 uuid —— KV 上不需要再造一个自增号")
    void handleIsTheUuid() {
        String id = store.record(job("u1"), "sqs", new IllegalStateException("boom"));

        assertThat(id)
                .as("""
                        这是 id 从 long 改成 String 的全部理由:uuid 本来就在记录上、
                        天生唯一,为了迁就一个 Java 的 long 去给 KV 造序列,方向是反的。""")
                .isEqualTo("u1");

        var found = store.find(id).orElseThrow();
        assertThat(found.id()).isEqualTo("u1");
        assertThat(found.uuid()).isEqualTo("u1");
        assertThat(found.connection()).isEqualTo("sqs");
        assertThat(found.queue()).isEqualTo("default");
        assertThat(found.displayName()).isEqualTo("test");   // forTest 的 displayName
        assertThat(found.payload()).contains("u1");
        assertThat(found.exception()).contains("boom").contains("IllegalStateException");
        assertThat(found.failedAt()).isNotNull();
        assertThat(found.reason()).contains("boom");
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 同一个 uuid 再失败:覆盖,不追加")
    void sameUuidOverwrites() {
        String first = store.record(job("dup"), "sqs", new IllegalStateException("first"));
        String second = store.record(job("dup"), "rabbitmq", new IllegalStateException("second"));

        assertThat(second).isEqualTo(first);
        assertThat(store.count())
                .as("追加会让同一个任务在 queue:failed 里出现十几条,而 retry 不知道重试哪条")
                .isEqualTo(1);
        assertThat(store.find(first).orElseThrow().exception()).contains("second");
        assertThat(store.find(first).orElseThrow().connection()).isEqualTo("rabbitmq");
    }

    @Test
    @DisplayName("★ all 按时间倒序 —— Scan 给的是分区顺序,不是时间顺序")
    void allIsNewestFirst() {
        store.record(job("a"), "sqs", new IllegalStateException("1"));
        sleep(5);
        store.record(job("b"), "sqs", new IllegalStateException("2"));
        sleep(5);
        store.record(job("c"), "sqs", new IllegalStateException("3"));

        assertThat(store.all()).extracting(FailedJobStore.FailedJob::uuid)
                .containsExactly("c", "b", "a");
    }

    @Test
    @DisplayName("forget 真的删掉;删第二次返回 false")
    void forget() {
        String id = store.record(job("f1"), "sqs", new IllegalStateException("x"));

        assertThat(store.forget(id)).isTrue();
        assertThat(store.forget(id))
                .as("""
                        DeleteItem 删一个不存在的 key 也返回成功 —— 不要 ALL_OLD 的话
                        「两个人同时在清」会显示成两次都删成功了""")
                .isFalse();
        assertThat(store.count()).isZero();
        assertThat(store.find(id)).isEmpty();
    }

    @Test
    @DisplayName("找不存在的 / 空 id:返回空,不报错")
    void findMissing() {
        assertThat(store.find("nope")).isEmpty();
        assertThat(store.find("")).isEmpty();
        assertThat(store.find(null)).isEmpty();
        assertThat(store.forget("nope")).isFalse();
    }

    @Test
    @DisplayName("flush 清空,返回删掉的条数")
    void flush() {
        store.record(job("x1"), "sqs", new IllegalStateException("x"));
        store.record(job("x2"), "sqs", new IllegalStateException("x"));

        assertThat(store.flush()).isEqualTo(2);
        assertThat(store.count()).isZero();
        assertThat(store.all()).isEmpty();
    }

    @Test
    @DisplayName("prune 只删超过时长的")
    void prune() {
        store.record(job("old"), "sqs", new IllegalStateException("x"));
        sleep(30);

        assertThat(store.prune(Duration.ofMillis(10))).isEqualTo(1);
        assertThat(store.count()).isZero();

        store.record(job("fresh"), "sqs", new IllegalStateException("x"));
        assertThat(store.prune(Duration.ofHours(1)))
                .as("刚失败的不该被清掉 —— 那是 queue:retry 还要用的东西")
                .isZero();
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 超过一批(25 条)也要删干净 —— BatchWriteItem 的硬限制")
    void deletesMoreThanOneBatch() {
        for (int i = 0; i < 30; i++) {
            store.record(job("batch-" + i), "sqs", new IllegalStateException("x"));
        }
        assertThat(store.count()).isEqualTo(30);

        assertThat(store.flush())
                .as("不分批的实现会在第 26 条上报 ValidationException")
                .isEqualTo(30);
        assertThat(store.count()).isZero();
    }

    @Test
    @DisplayName("display_name 为空也存得下 —— DynamoDB 不收空字符串属性")
    void emptyAttributesAreStored() {
        var withoutName = new QueuedJob(
                "blank", "app.jobs.TestJob", Map.of(), "default", 1,
                java.time.Instant.now(), null, 3, null, null, 0,
                "", 0, null, List.of(), null, Map.of());

        String id = store.record(withoutName, "sqs", new IllegalStateException("x"));

        assertThat(store.find(id)).isPresent();
        assertThat(store.find(id).orElseThrow().displayName()).isEmpty();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
