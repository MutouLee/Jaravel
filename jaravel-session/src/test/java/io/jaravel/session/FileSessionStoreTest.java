package io.jaravel.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.session.store.FileSessionStore;

/** 落盘会话:开发期热重启后登录状态不丢(docs/15 §3.5) */
class FileSessionStoreTest {

    @TempDir
    Path tempDir;

    private FileSessionStore store() {
        return new FileSessionStore(tempDir.resolve("sessions"));
    }

    // ================= 基本读写 =================

    @Nested
    @DisplayName("读写")
    class ReadWrite {

        @Test
        @DisplayName("写入后读回")
        void roundTrip() {
            var store = store();
            store.write("abc123", Map.of("user_id", 42L, "name", "张三"),
                        Duration.ofHours(1));

            var read = store.read("abc123").orElseThrow();
            assertThat(read).containsEntry("user_id", 42L)
                            .containsEntry("name", "张三");
        }

        @Test
        @DisplayName("不存在的 id 返回空")
        void missingIsEmpty() {
            assertThat(store().read("nope")).isEmpty();
        }

        @Test
        @DisplayName("★ 新实例能读到旧实例写的 —— 这就是「活过重启」")
        void survivesNewInstance() {
            store().write("abc123", Map.of("user_id", 42L), Duration.ofHours(1));

            // 模拟热重启:全新的 store 实例,同一个目录
            assertThat(store().read("abc123").orElseThrow())
                    .as("每改一行代码就要重新登录一次,这个摩擦足以让人放弃热重载")
                    .containsEntry("user_id", 42L);
        }

        @Test
        @DisplayName("覆盖写")
        void overwrite() {
            var store = store();
            store.write("abc", Map.of("v", 1L), Duration.ofHours(1));
            store.write("abc", Map.of("v", 2L), Duration.ofHours(1));

            assertThat(store.read("abc").orElseThrow()).containsEntry("v", 2L);
        }

        @Test
        @DisplayName("destroy 删掉文件")
        void destroy() {
            var store = store();
            store.write("abc", Map.of("v", 1L), Duration.ofHours(1));
            store.destroy("abc");

            assertThat(store.read("abc")).isEmpty();
            assertThat(store.directory().resolve("abc.session.json")).doesNotExist();
        }

        @Test
        @DisplayName("过期后读不到,且文件被清掉")
        void expires() throws Exception {
            var store = store();
            store.write("abc", Map.of("v", 1L), Duration.ofMillis(1));
            Thread.sleep(20);

            assertThat(store.read("abc")).isEmpty();
            assertThat(store.directory().resolve("abc.session.json")).doesNotExist();
        }

        @Test
        @DisplayName("lifetime 为 null 表示永不过期")
        void nullLifetimeNeverExpires() {
            var store = store();
            store.write("abc", Map.of("v", 1L), null);

            assertThat(store.read("abc")).isPresent();
        }
    }

    // ================= 数据类型 =================

    @Nested
    @DisplayName("数据类型")
    class DataTypes {

        @Test
        @DisplayName("字符串/数字/布尔/列表/嵌套 Map 都能往返")
        void supportedTypes() {
            var store = store();
            store.write("abc", Map.of(
                    "s", "值",
                    "i", 7L,
                    "b", true,
                    "list", List.of("a", "b"),
                    "nested", Map.of("k", "v")), Duration.ofHours(1));

            var read = store.read("abc").orElseThrow();
            assertThat(read.get("s")).isEqualTo("值");
            assertThat(read.get("i")).isEqualTo(7L);
            assertThat(read.get("b")).isEqualTo(true);
            assertThat(read.get("list")).isEqualTo(List.of("a", "b"));
            assertThat(read.get("nested")).isEqualTo(Map.of("k", "v"));
        }

        @Test
        @DisplayName("★ 需要转义的字符串不破坏格式")
        void escaping() {
            var store = store();
            String nasty = "引号\" 反斜杠\\ 换行\n 制表\t 中文 🙂";
            store.write("abc", Map.of("k", nasty), Duration.ofHours(1));

            assertThat(store.read("abc").orElseThrow().get("k")).isEqualTo(nasty);
        }

        @Test
        @DisplayName("★ 放不进去的类型直接报错,不静默丢弃")
        void unsupportedTypeFailsLoudly() {
            var store = store();

            // 静默丢弃的症状是「某个会话键在开发环境时有时无」
            assertThatThrownBy(() -> store.write("abc",
                    Map.of("obj", new Object()), Duration.ofHours(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("session.driver: cache");
        }

        @Test
        @DisplayName("空会话也能往返")
        void emptyData() {
            var store = store();
            store.write("abc", Map.of(), Duration.ofHours(1));

            assertThat(store.read("abc")).contains(Map.of());
        }
    }

    // ================= 健壮性 =================

    @Nested
    @DisplayName("健壮性")
    class Robustness {

        @Test
        @DisplayName("★ 路径穿越的 id 直接拒绝")
        void rejectsPathTraversal() {
            var store = store();

            // 会话 id 来自 cookie,是不可信输入
            for (String evil : new String[] {
                    "../../etc/passwd", "a/b", "a\\b", "..", "a:b", "a\u0000b"}) {
                assertThatThrownBy(() -> store.read(evil))
                        .as("id = %s", evil)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("空 id 与超长 id 也拒绝")
        void rejectsEmptyAndOverlong() {
            var store = store();
            assertThatThrownBy(() -> store.read(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.read("a".repeat(200)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("★ 损坏的文件当作「没有这个会话」,而不是 500")
        void corruptFileDegrades() throws Exception {
            var store = store();
            store.write("abc", Map.of("v", 1L), Duration.ofHours(1));

            // 模拟写入途中进程被杀 / 磁盘满
            Files.writeString(store.directory().resolve("abc.session.json"),
                    "{\"expires_at\":0,\"dat", StandardCharsets.UTF_8);

            // 抛异常会让用户卡在一个自己无法恢复的 500 上,
            // 而「重新登录一次」是可接受的降级
            assertThat(store.read("abc")).isEmpty();
            assertThat(store.directory().resolve("abc.session.json")).doesNotExist();
        }

        @Test
        @DisplayName("目录不存在时自动创建")
        void createsDirectory() {
            var deep = tempDir.resolve("a/b/c/sessions");
            new FileSessionStore(deep);

            assertThat(deep).isDirectory();
        }

        @Test
        @DisplayName("★ 并发写同一个会话不会读到截断内容")
        void concurrentWritesAreAtomic() throws Exception {
            var store = store();
            store.write("abc", Map.of("v", 0L), Duration.ofHours(1));

            var failures = new java.util.concurrent.atomic.AtomicInteger();
            var writerFailures = new java.util.concurrent.atomic.AtomicInteger();
            var writer = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    try {
                        store.write("abc", Map.of("v", (long) i, "pad", "x".repeat(500)),
                                    Duration.ofHours(1));
                    } catch (RuntimeException e) {
                        writerFailures.incrementAndGet();
                    }
                }
            });
            var reader = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    // 读到空(文件正在被替换的瞬间)是可以接受的;
                    // 读到**截断的内容**不行 —— 那会被当作损坏并删掉会话,
                    // 症状是用户随机掉登录
                    store.read("abc").ifPresent(data -> {
                        if (!data.containsKey("v")) failures.incrementAndGet();
                    });
                }
            });

            writer.start();
            reader.start();
            writer.join();
            reader.join();

            assertThat(failures.get()).isZero();
            assertThat(writerFailures.get())
                    .as("并发写同一个会话不该以 500 收场(v0.9 安全/健壮性审计)")
                    .isZero();
        }

        @Test
        @DisplayName("临时文件不会被当成会话")
        void tempFilesAreNotSessions() throws Exception {
            var store = store();
            store.write("abc", Map.of("v", 1L), Duration.ofHours(1));

            try (var files = Files.list(store.directory())) {
                assertThat(files.map(p -> p.getFileName().toString()).toList())
                        .containsExactly("abc.session.json");
            }
        }
    }

    // ================= 清理 =================

    @Nested
    @DisplayName("清理")
    class Purge {

        @Test
        @DisplayName("purgeExpired 删过期的,留有效的")
        void purgesOnlyExpired() throws Exception {
            var store = store();
            store.write("old", Map.of("v", 1L), Duration.ofMillis(1));
            store.write("fresh", Map.of("v", 2L), Duration.ofHours(1));
            Thread.sleep(20);

            assertThat(store.purgeExpired(Duration.ofHours(1))).isEqualTo(1);
            assertThat(store.read("fresh")).isPresent();
        }

        @Test
        @DisplayName("损坏的文件也被清掉")
        void purgesCorrupt() throws Exception {
            var store = store();
            Files.writeString(store.directory().resolve("bad.session.json"), "garbage");

            assertThat(store.purgeExpired(Duration.ofHours(1))).isEqualTo(1);
        }
    }

    // ================= 与会话层集成 =================

    @Nested
    @DisplayName("与 SessionData 集成")
    class Integration {

        @Test
        @DisplayName("★ CSRF 令牌与 flash 都能活过重启")
        void tokenAndFlashSurvive() {
            var store = store();

            var session = new SessionData(SessionData.generateId());
            session.put("user_id", 42L);
            session.flash("status", "已保存");
            String token = session.token();
            session.ageFlash();
            store.write(session.id(), session.raw(), Duration.ofHours(1));

            // 热重启:新 store 实例读回
            var restored = new SessionData(session.id());
            restored.replaceAll(store().read(session.id()).orElseThrow());

            assertThat(restored.get("user_id", Long.class)).contains(42L);
            assertThat(restored.token())
                    .as("令牌变了的话用户的下一个 POST 会得到 419")
                    .isEqualTo(token);
            assertThat(restored.get("status")).contains("已保存");
        }

        @Test
        @DisplayName("声明自己不跨进程共享 —— 多实例部署时能被提醒")
        void notShared() {
            assertThat(store().isShared())
                    .as("同机多实例能共享,但多机不能 —— 而生产的多实例几乎总是跨机器")
                    .isFalse();
        }
    }
}
