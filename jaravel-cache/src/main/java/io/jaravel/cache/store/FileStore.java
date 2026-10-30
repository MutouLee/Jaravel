package io.jaravel.cache.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import io.jaravel.cache.CacheStore;

/**
 * 文件系统驱动。本地开发用:重启不丢、无需额外服务。
 *
 * <h2>文件格式</h2>
 * <p>第一行是过期时间戳(毫秒,{@code 0} 表示永久),其余是 JSON。
 * 把过期时间放在文件里而不是文件名里,是为了让「续期」不必重命名文件
 * (重命名在 Windows 上会被杀毒软件/索引器偶发锁住)。
 *
 * <h2>目录分片</h2>
 * <p>键先 SHA-256,取前 4 个十六进制字符做两级目录。单目录放几万个文件时
 * 在多数文件系统上会明显变慢,而缓存键的数量天然不可控。
 */
public class FileStore implements CacheStore {

    private final Path root;

    public FileStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "无法创建缓存目录 " + root.toAbsolutePath()
                  + "(file 驱动需要可写目录;检查权限或换 cache.stores.file.path)", e);
        }
    }

    @Override public String name() { return "file"; }

    /**
     * 多进程间可见 —— 只要共享同一个目录。
     *
     * <p>但**不保证跨机器**:同一台机器的多个进程可以,容器化部署里
     * 各容器的文件系统是隔离的。所以它算「共享」,而是否真共享取决于部署。
     */
    @Override public boolean isShared() { return true; }

    @Override
    public synchronized Optional<Object> get(String key) {
        Path file = pathFor(key);
        if (!Files.isRegularFile(file)) return Optional.empty();

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int newline = content.indexOf('\n');
            if (newline < 0) {
                // 文件被截断(写入中途崩溃)。删掉当没有,而不是抛异常 ——
                // 缓存是可重建的,为一个坏文件让请求失败不值得。
                Files.deleteIfExists(file);
                return Optional.empty();
            }

            long expiresAt = Long.parseLong(content.substring(0, newline).trim());
            if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            String json = content.substring(newline + 1);
            return Optional.ofNullable(JsonBridge.read(json));
        } catch (IOException | NumberFormatException e) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // 删不掉就留着,下次再试
            }
            return Optional.empty();
        }
    }

    @Override
    public synchronized void put(String key, Object value, Duration ttl) {
        Path file = pathFor(key);
        long expiresAt = ttl == null ? 0 : System.currentTimeMillis() + ttl.toMillis();
        String content = expiresAt + "\n" + JsonBridge.write(value);

        try {
            Files.createDirectories(file.getParent());
            // 先写临时文件再原子移动:直接覆盖时若中途崩溃,
            // 留下的是半个文件,而半个 JSON 读出来是异常而非「缓存未命中」
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                           java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入缓存文件失败:" + file, e);
        }
    }

    /**
     * 原子 put-if-absent。
     *
     * <p>{@code synchronized} 只能保证**本进程内**的互斥。跨进程靠
     * {@code CREATE_NEW} —— 文件已存在时 {@code Files.newOutputStream}
     * 会抛 {@code FileAlreadyExistsException},这个检查在多数文件系统上是原子的。
     *
     * <p>这条保证不如 Redis 的 {@code SET NX} 硬。file 驱动的锁适合
     * 「同机多进程防重复」,不适合跨机器。
     */
    @Override
    /**
     * ⚠ **多进程下这个 add 不是严格原子的。**
     *
     * <p>{@code synchronized} 只管同一个 JVM。跨进程时窗口在这里:
     * {@link #get} 发现条目过期会把文件删掉,而那个 delete 是无条件的。
     * 于是两个进程可能:A 读到过期 → B 读到过期 → A 删 → A 建(持有)
     * → **B 删掉 A 刚建的** → B 建(也持有)。
     *
     * <p>{@code DatabaseStore} 没有这个问题:它的插入靠主键冲突裁决,
     * 而清理过期行带了 {@code expires_at} 条件
     * (见那边的 {@code deleteIfExpired})。文件系统给不出等价的
     * 「条件删除」原语,所以这个窗口关不掉。
     *
     * <p>**实际影响有限**:{@code file} 已经在
     * {@code SchedulerConfigCheck.NON_SHARED_DRIVERS} 里,所以
     * {@code onOneServer} + file + 多实例的组合在**启动时就被拒绝**。
     * 剩下的暴露面是「把 file 驱动放在共享卷上直接用
     * {@code Cache.lock(...)}」—— 要跨实例互斥请用 database 驱动。
     *
     * <p>这条写在这里而不是悄悄修:一个「看起来修好了」的版本
     * 会让人以为 file 驱动能做分布式锁。
     */
    public synchronized boolean add(String key, Object value, Duration ttl) {
        // 已存在但已过期的,视为不存在
        if (get(key).isPresent()) return false;

        Path file = pathFor(key);
        long expiresAt = ttl == null ? 0 : System.currentTimeMillis() + ttl.toMillis();
        String content = expiresAt + "\n" + JsonBridge.write(value);

        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("写入缓存文件失败:" + file, e);
        }
    }

    @Override
    public synchronized void forget(String key) {
        try {
            Files.deleteIfExists(pathFor(key));
        } catch (IOException e) {
            throw new UncheckedIOException("删除缓存文件失败:" + pathFor(key), e);
        }
    }

    @Override
    public synchronized void flush() {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .filter(p -> !p.equals(root))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 并发访问下可能已被别人删掉,不是错误
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("清空缓存目录失败:" + root, e);
        }
    }

    @Override
    public synchronized long increment(String key, long delta) {
        long base = get(key).map(v -> {
            if (v instanceof Number n) return n.longValue();
            try {
                return Long.parseLong(String.valueOf(v));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "缓存键 \"" + key + "\" 里存的不是数字,不能 increment", e);
            }
        }).orElse(0L);

        long updated = base + delta;
        // 保留原过期时间:重新读一次文件头比把 TTL 搞丢安全
        Duration remaining = remainingTtl(key);
        put(key, updated, remaining);
        return updated;
    }

    private Duration remainingTtl(String key) {
        Path file = pathFor(key);
        if (!Files.isRegularFile(file)) return null;
        try {
            String firstLine = Files.readString(file, StandardCharsets.UTF_8).lines()
                    .findFirst().orElse("0");
            long expiresAt = Long.parseLong(firstLine.trim());
            if (expiresAt <= 0) return null;
            long remaining = expiresAt - System.currentTimeMillis();
            return remaining > 0 ? Duration.ofMillis(remaining) : Duration.ofMillis(1);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    // ================= 路径 =================

    Path pathFor(String key) {
        String hash = sha256(key);
        return root.resolve(hash.substring(0, 2))
                   .resolve(hash.substring(2, 4))
                   .resolve(hash);
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 没有 SHA-256,这不该发生", e);
        }
    }

    /** 把序列化隔到一个地方,便于 database 驱动复用同一套格式 */
    static final class JsonBridge {
        private JsonBridge() {}

        static String write(Object value) {
            try {
                return Mapper.get().writeValueAsString(new Envelope(
                        value == null ? null : value.getClass().getName(), value));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("""
                        无法把值序列化进缓存:%s
                          file / database 驱动需要把值写成 JSON。
                          不可序列化的对象(持有连接、线程、Lambda 的)请换
                          caffeine / array 驱动,或只缓存它的数据部分。"""
                        .formatted(e.getOriginalMessage()), e);
            }
        }

        static Object read(String json) {
            try {
                Envelope envelope = Mapper.get().readValue(json, Envelope.class);
                if (envelope.type() == null || envelope.value() == null) return envelope.value();
                try {
                    Class<?> type = Class.forName(envelope.type());
                    return Mapper.get().convertValue(envelope.value(), type);
                } catch (ClassNotFoundException e) {
                    // 类被改名或删掉了。返回原始结构,让 Repository 层的
                    // coerce 去试目标类型 —— 比直接失败宽容
                    return envelope.value();
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return null;     // 坏数据 = 未命中
            }
        }

        /** 带类型信息的信封 —— 否则 {@code Long} 读回来会变成 {@code Integer} */
        record Envelope(String type, Object value) {}

        static final class Mapper {
            private static final com.fasterxml.jackson.databind.ObjectMapper M = create();

            static com.fasterxml.jackson.databind.ObjectMapper get() { return M; }

            private static com.fasterxml.jackson.databind.ObjectMapper create() {
                var m = new com.fasterxml.jackson.databind.ObjectMapper();
                m.findAndRegisterModules();
                m.configure(com.fasterxml.jackson.databind.DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES, false);
                return m;
            }
        }
    }
}
