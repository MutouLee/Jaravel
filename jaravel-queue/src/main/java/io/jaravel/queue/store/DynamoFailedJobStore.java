package io.jaravel.queue.store;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueuedJob;
import io.jaravel.support.Clock;

/**
 * DynamoDB 的失败任务归档(docs/10 §1.7,{@code queue.failed.driver: dynamodb})。
 *
 * <h2>它为哪个场景存在</h2>
 * <p>**AWS 上没有关系库的部署**:Lambda / Fargate + SQS,没有 RDS 也没有
 * ElastiCache。那种应用在此之前只能退到 {@code log} 驱动 ——
 * 失败任务只剩日志里一行,{@code queue:retry} 用不了,
 * 也就是说任务丢了之后**没有补救手段**。受众窄,但窄在「不做就没退路」上。
 *
 * <h2>★ 主键是任务的 uuid,不是一个自增号</h2>
 * <p>于是 {@code find} / {@code forget} 是单条 {@code GetItem} /
 * {@code DeleteItem},不需要在 KV 上额外维护一个序列
 * (那要么多一次往返、要么多一个会漂的计数器 item)。
 * {@link FailedJobStore} 的 id 因此是 {@code String} —— 这个存储返回 uuid,
 * {@code queue:retry 9f8c…} 照常能用(database / redis 返回自增编号的字符串,
 * {@code queue:retry 3} 也照常能用)。
 *
 * <p>**同一个 uuid 再失败就覆盖**(PutItem 的天然语义),与另两个存储一致 ——
 * 追加会让同一个任务在 {@code queue:failed} 里出现十几条,
 * 而 {@code queue:retry} 不知道该重试哪一条。
 *
 * <h2>语义差异(必须知道)</h2>
 * <ul>
 *   <li>{@code all} / {@code count} / {@code prune} / {@code flush} 都是
 *       **Scan**。DynamoDB 没有「按时间排序扫一张表」的索引型读法,
 *       而失败记录本来是低频、只增、量小的 —— 一张堆了几十万条失败记录的表
 *       说明有个任务一直在炸,那时该做的是修任务、而不是优化这个扫描;</li>
 *   <li>{@code count} 用 {@code Select: COUNT} 的 Scan,**不是**表的
 *       {@code ItemCount} —— 后者由 AWS 每六小时更新一次,
 *       于是「删完了但面板还显示 37 条」;</li>
 *   <li>排序在**内存里**按 {@code failed_at} 倒序。Scan 的返回顺序是
 *       分区顺序,不是时间顺序;</li>
 *   <li><b>不做 TTL</b>:自动过期要额外的 IAM 权限,而且删除是**静默**的 ——
 *       「上周的失败记录自己不见了」比「表大了点」难查得多。
 *       清理走 {@code queue:prune-failed}({@link #prune}),那是显式的。</li>
 * </ul>
 *
 * <h2>表会自动建</h2>
 * <p>第一次用到时 {@code CreateTable}(按请求计费),与 {@code SqsQueue}
 * 自动建队列同一个取舍:让「跑起来」不需要先去控制台点一遍。
 * 已经存在就直接用,不去改它的配置。
 */
public class DynamoFailedJobStore implements FailedJobStore, AutoCloseable {

    public static final String DEFAULT_TABLE = "jaravel_failed_jobs";

    /** BatchWriteItem 一次最多 25 条 —— AWS 的硬限制 */
    private static final int BATCH_LIMIT = 25;

    private final DynamoDbClient client;
    private final boolean ownsClient;
    private final String table;
    private final JobSerializer serializer;

    private volatile boolean tableReady;

    public DynamoFailedJobStore(DynamoDbClient client, String table, JobSerializer serializer) {
        this(client, false, table, serializer);
    }

    private DynamoFailedJobStore(DynamoDbClient client, boolean ownsClient,
                                 String table, JobSerializer serializer) {
        this.client = client;
        this.ownsClient = ownsClient;
        this.table = table == null || table.isBlank() ? DEFAULT_TABLE : table;
        this.serializer = serializer;
    }

    /**
     * 建一个自己管理的客户端。
     *
     * @param endpoint 空 = 走 AWS 默认端点;本地用
     *                 {@code http://127.0.0.1:8000}(amazon/dynamodb-local)
     */
    public static DynamoFailedJobStore connect(String endpoint, String region,
                                               String accessKey, String secretKey,
                                               String table, JobSerializer serializer) {
        var builder = DynamoDbClient.builder()
                .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                .httpClientBuilder(UrlConnectionHttpClient.builder());

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (accessKey != null && !accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey == null ? "" : secretKey)));
        }
        return new DynamoFailedJobStore(builder.build(), true, table, serializer);
    }

    // ================= 记录 =================

    @Override
    public String record(QueuedJob job, String connection, Throwable cause) {
        ensureTable();

        var item = new HashMap<String, AttributeValue>();
        item.put("uuid", string(job.uuid()));
        item.put("connection", string(connection));
        item.put("queue", string(job.queue()));
        item.put("display_name", string(job.displayName()));
        item.put("payload", string(serializer.encode(job)));
        item.put("exception", string(DatabaseFailedJobStore.stackTraceOf(cause)));
        item.put("failed_at", number(Clock.millis()));

        // 同 uuid 覆盖 —— PutItem 的天然语义,正好是我们要的那个
        client.putItem(PutItemRequest.builder().tableName(table).item(item).build());
        return job.uuid();
    }

    // ================= 查询 =================

    @Override
    public Optional<FailedJob> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        ensureTable();

        var response = client.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(Map.of("uuid", string(id)))
                .consistentRead(true)        // 刚 record 完就 find 要能看到
                .build());

        return response.hasItem() && !response.item().isEmpty()
                ? Optional.of(toFailedJob(response.item()))
                : Optional.empty();
    }

    @Override
    public List<FailedJob> all() {
        ensureTable();

        var out = new ArrayList<FailedJob>();
        for (Map<String, AttributeValue> item : scanAll()) {
            out.add(toFailedJob(item));
        }
        // Scan 给的是分区顺序;按时间倒序是调用方(queue:failed / 面板)要的
        out.sort(Comparator.comparing(FailedJob::failedAt).reversed());
        return List.copyOf(out);
    }

    @Override
    public long count() {
        ensureTable();

        long total = 0;
        Map<String, AttributeValue> start = null;
        do {
            var request = ScanRequest.builder()
                    .tableName(table)
                    .select(Select.COUNT);          // 不是表的 ItemCount,见类注释
            if (start != null) request.exclusiveStartKey(start);

            ScanResponse response = client.scan(request.build());
            total += response.count();
            start = nextKey(response);
        } while (start != null);
        return total;
    }

    // ================= 删除 =================

    @Override
    public boolean forget(String id) {
        if (id == null || id.isBlank()) return false;
        ensureTable();

        var response = client.deleteItem(DeleteItemRequest.builder()
                .tableName(table)
                .key(Map.of("uuid", string(id)))
                // ★ 要 ALL_OLD 才能回答「真的删掉了吗」。
                // 不要的话 DeleteItem 删一个不存在的 key 也返回成功,
                // 于是「两个人同时在清」会显示成两次都删成功了
                .returnValues(ReturnValue.ALL_OLD)
                .build());

        return response.hasAttributes() && !response.attributes().isEmpty();
    }

    @Override
    public int flush() {
        ensureTable();
        return deleteAll(scanAll());
    }

    @Override
    public int prune(Duration olderThan) {
        ensureTable();
        long cutoff = Clock.now().minus(olderThan).toEpochMilli();

        var doomed = new ArrayList<Map<String, AttributeValue>>();
        for (Map<String, AttributeValue> item : scanAll()) {
            if (millis(item.get("failed_at")) < cutoff) doomed.add(item);
        }
        return deleteAll(doomed);
    }

    private int deleteAll(List<Map<String, AttributeValue>> items) {
        int removed = 0;
        for (int from = 0; from < items.size(); from += BATCH_LIMIT) {
            var slice = items.subList(from, Math.min(items.size(), from + BATCH_LIMIT));

            List<WriteRequest> writes = slice.stream()
                    .map(item -> WriteRequest.builder()
                            .deleteRequest(d -> d.key(Map.of("uuid", item.get("uuid"))))
                            .build())
                    .toList();

            var response = client.batchWriteItem(b -> b.requestItems(Map.of(table, writes)));

            // ★ 未处理的要重发。BatchWriteItem **部分成功是正常返回**,
            // 不是异常 —— 不看 unprocessedItems 的实现会在限流时静默少删几条,
            // 而返回的数字说「都删了」
            Map<String, List<WriteRequest>> pending = response.unprocessedItems();
            int unprocessed = pending.getOrDefault(table, List.of()).size();
            removed += slice.size() - unprocessed;

            for (int attempt = 0; attempt < 3 && unprocessed > 0; attempt++) {
                final Map<String, List<WriteRequest>> resend = pending;
                var again = client.batchWriteItem(b -> b.requestItems(resend));

                int left = again.unprocessedItems().getOrDefault(table, List.of()).size();
                removed += unprocessed - left;
                unprocessed = left;
                pending = again.unprocessedItems();
            }
        }
        return removed;
    }

    // ================= 内部 =================

    private List<Map<String, AttributeValue>> scanAll() {
        var out = new ArrayList<Map<String, AttributeValue>>();
        Map<String, AttributeValue> start = null;
        do {
            var request = ScanRequest.builder().tableName(table);
            if (start != null) request.exclusiveStartKey(start);

            ScanResponse response = client.scan(request.build());
            out.addAll(response.items());
            start = nextKey(response);
        } while (start != null);
        return out;
    }

    /**
     * 分页游标。
     *
     * <p>⚠ 必须有:Scan 一次最多返回 1MB,不翻页的实现在表小的时候
     * 一切正常、在表大的时候**静默只处理前一页** —— 而那正是
     * 「失败记录堆起来了」的时候,也就是最需要它正确的时候。
     */
    private static Map<String, AttributeValue> nextKey(ScanResponse response) {
        return response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                ? response.lastEvaluatedKey()
                : null;
    }

    private FailedJob toFailedJob(Map<String, AttributeValue> item) {
        return new FailedJob(
                text(item.get("uuid")),              // 句柄就是 uuid,见类注释
                text(item.get("uuid")),
                text(item.get("connection")),
                text(item.get("queue")),
                text(item.get("display_name")),
                text(item.get("payload")),
                text(item.get("exception")),
                Instant.ofEpochMilli(millis(item.get("failed_at"))));
    }

    private static AttributeValue string(String value) {
        // DynamoDB 不收空字符串的属性值(会报 ValidationException),
        // 而 display_name 之类是可能为空的 —— 用一个空格太脏,所以存 NULL
        return value == null || value.isEmpty()
                ? AttributeValue.builder().nul(true).build()
                : AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }

    private static String text(AttributeValue value) {
        return value == null || Boolean.TRUE.equals(value.nul()) ? "" : value.s();
    }

    private static long millis(AttributeValue value) {
        if (value == null || value.n() == null) return 0;
        try {
            return Long.parseLong(value.n());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 表不在就建。
     *
     * <p>与 {@code SqsQueue} 自动建队列同一个取舍:让「跑起来」不需要
     * 先去控制台点一遍。已经存在就直接用,**不去改它的配置** ——
     * 一个框架不该悄悄改动用户的容量模式。
     */
    private void ensureTable() {
        if (tableReady) return;

        synchronized (this) {
            if (tableReady) return;

            try {
                client.describeTable(DescribeTableRequest.builder().tableName(table).build());
                tableReady = true;
                return;
            } catch (ResourceNotFoundException missing) {
                create();
            }
            tableReady = true;
        }
    }

    private void create() {
        try {
            client.createTable(CreateTableRequest.builder()
                    .tableName(table)
                    .keySchema(KeySchemaElement.builder()
                            .attributeName("uuid").keyType(KeyType.HASH).build())
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("uuid")
                            .attributeType(ScalarAttributeType.S).build())
                    // 按请求计费:失败记录是低频写,预置容量在这里只会浪费钱
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
        } catch (ResourceInUseException raced) {
            return;        // 另一个实例刚好也在建 —— 它赢了,没问题
        }

        client.waiter().waitUntilTableExists(
                DescribeTableRequest.builder().tableName(table).build());
    }

    @Override
    public void close() {
        if (ownsClient) client.close();
    }
}
