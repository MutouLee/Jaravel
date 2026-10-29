package io.jaravel.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 热重载:变更分类、请求挂起/重放、文件监听去抖(docs/15) */
class HotReloadTest {

    // ================= 变更分类 =================

    @Nested
    @DisplayName("变更分类")
    class Classification {

        private final ChangeClassifier classifier = new ChangeClassifier();

        private ReloadStrategy of(String path) {
            return classifier.classifyOne(Path.of(path));
        }

        @Test
        @DisplayName("模板/配置/静态资源 → L1")
        void resources() {
            assertThat(of("src/main/resources/views/posts/index.jblade.html"))
                    .isEqualTo(ReloadStrategy.RESOURCE);
            assertThat(of("src/main/resources/config/app.yml"))
                    .isEqualTo(ReloadStrategy.RESOURCE);
            assertThat(of("src/main/resources/static/app.css"))
                    .isEqualTo(ReloadStrategy.RESOURCE);
            assertThat(of("src/main/resources/lang/zh.properties"))
                    .isEqualTo(ReloadStrategy.RESOURCE);
        }

        @Test
        @DisplayName(".java → L3")
        void javaSources() {
            assertThat(of("src/main/java/app/http/controllers/PostController.java"))
                    .isEqualTo(ReloadStrategy.RESTART_LOADER);
        }

        @Test
        @DisplayName("★ pom.xml → L4,没有更便宜的办法")
        void pomRequiresJvmRestart() {
            // classpath 本身变了
            assertThat(of("pom.xml")).isEqualTo(ReloadStrategy.RESTART_JVM);
            assertThat(of("examples/blog/pom.xml")).isEqualTo(ReloadStrategy.RESTART_JVM);
        }

        @Test
        @DisplayName("★ .env → L4,因为它在 Spring 启动之前就被读走了")
        void envRequiresJvmRestart() {
            // 把它当资源处理会让用户改完没反应,然后去怀疑 .env 的语法
            assertThat(of(".env")).isEqualTo(ReloadStrategy.RESTART_JVM);
            assertThat(of("/project/.env")).isEqualTo(ReloadStrategy.RESTART_JVM);
            assertThat(of("/project/.env.local")).isEqualTo(ReloadStrategy.RESTART_JVM);
        }

        @Test
        @DisplayName("★ .class 在 L2 可用时走 HotSwap,否则降级")
        void classFilesDependOnHotswap() {
            assertThat(new ChangeClassifier(false)
                    .classifyOne(Path.of("target/classes/app/X.class")))
                    .as("L2 不可用时降到 L3 —— 核心体验不押在非标准 JVM 上")
                    .isEqualTo(ReloadStrategy.RESTART_LOADER);

            assertThat(new ChangeClassifier(true)
                    .classifyOne(Path.of("target/classes/app/X.class")))
                    .isEqualTo(ReloadStrategy.HOTSWAP);
        }

        @Test
        @DisplayName("★ 编辑器临时文件与 .git 一律忽略")
        void ignoresNoise() {
            for (String noise : new String[] {
                    "src/main/java/app/X.java~",
                    "src/main/java/app/.#X.java",
                    "src/main/java/app/X.java.swp",
                    ".git/index",
                    ".idea/workspace.xml",
                    "node_modules/foo/index.js",
                    "storage/logs/jaravel.log",
                    "storage/framework/sessions/abc.session.json",
                    ".DS_Store"}) {
                assertThat(classifier.classifyOne(Path.of(noise)))
                        .as("%s 不该触发任何重载", noise)
                        .isEqualTo(ReloadStrategy.IGNORE);
            }
        }

        @Test
        @DisplayName("★ 会话文件被忽略 —— 否则每个请求都触发一次重载")
        void sessionFilesIgnored() {
            // FileSessionStore 写在 storage/framework/sessions 下,
            // 而那个目录每个请求都在变。不忽略的话应用会永不停歇地重载
            assertThat(of("storage/framework/sessions/abc.session.json"))
                    .isEqualTo(ReloadStrategy.IGNORE);
        }

        @Test
        @DisplayName("★ 一批变更取成本最高的那个")
        void escalatesToMostExpensive() {
            // 只做资源替换会让新类不生效,而那个不一致比多等 2 秒糟得多
            assertThat(classifier.classify(List.of(
                    Path.of("src/main/resources/views/a.jblade.html"),
                    Path.of("src/main/java/app/New.java"))))
                    .isEqualTo(ReloadStrategy.RESTART_LOADER);

            assertThat(classifier.classify(List.of(
                    Path.of("src/main/java/app/New.java"),
                    Path.of("pom.xml"))))
                    .isEqualTo(ReloadStrategy.RESTART_JVM);
        }

        @Test
        @DisplayName("全是噪声的一批 → IGNORE")
        void allNoiseIsIgnored() {
            assertThat(classifier.classify(List.of(
                    Path.of(".git/HEAD"), Path.of("src/X.java~"))))
                    .isEqualTo(ReloadStrategy.IGNORE);
        }

        @Test
        @DisplayName("Windows 的反斜杠路径同样生效")
        void windowsPaths() {
            assertThat(classifier.classifyOne(
                    Path.of("src\\main\\resources\\views\\a.jblade.html")))
                    .isEqualTo(ReloadStrategy.RESOURCE);
        }

        @Test
        @DisplayName("★ 含 APT 注解的源文件即使只改方法体也要重启")
        void aptAnnotationsForceRestart() {
            // HotSwap 不会重跑注解处理,生成的父类/元模型会与源码脱节
            assertThat(ChangeClassifier.triggersAnnotationProcessing("""
                    @Model
                    public class Post extends PostBase { }"""))
                    .isTrue();
            assertThat(ChangeClassifier.triggersAnnotationProcessing("""
                    public class PlainService {
                        void doWork() { }
                    }"""))
                    .isFalse();
        }
    }

    // ================= 请求闸门 =================

    @Nested
    @DisplayName("请求闸门:挂起与重放")
    class Gate {

        @Test
        @DisplayName("没有待处理变更时直接放行")
        void passesThroughWhenIdle() {
            var gate = new RestartGate();
            var reloads = new AtomicInteger();

            assertThat(gate.enter(s -> reloads.incrementAndGet())).isTrue();
            assertThat(reloads.get()).isZero();
        }

        @Test
        @DisplayName("★ 有待处理变更 → 下一个请求触发重载再放行")
        void nextRequestTriggersReload() {
            var gate = new RestartGate();
            gate.recordChange(ReloadStrategy.RESTART_LOADER);

            var applied = new AtomicReference<ReloadStrategy>();
            assertThat(gate.enter(applied::set)).isTrue();

            // 用户的感知是「刷新一下,改动就生效了」—— 与 PHP 一致
            assertThat(applied.get()).isEqualTo(ReloadStrategy.RESTART_LOADER);
            assertThat(gate.hasPendingChanges()).isFalse();
        }

        @Test
        @DisplayName("★ 连续保存 10 次只重启 1 次")
        void coalescesManyChanges() {
            var gate = new RestartGate();
            for (int i = 0; i < 10; i++) gate.recordChange(ReloadStrategy.RESTART_LOADER);

            var reloads = new AtomicInteger();
            gate.enter(s -> reloads.incrementAndGet());

            // 文件一存就重启看起来更积极,实际很糟:
            // 连续编辑 10 个文件会触发 10 次重启,而用户一次都没刷新
            assertThat(reloads.get()).isEqualTo(1);
            assertThat(gate.totalRestarts()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 多次登记取成本最高的")
        void escalatesPending() {
            var gate = new RestartGate();
            gate.recordChange(ReloadStrategy.RESOURCE);
            gate.recordChange(ReloadStrategy.RESTART_LOADER);
            gate.recordChange(ReloadStrategy.RESOURCE);

            assertThat(gate.pendingStrategy()).isEqualTo(ReloadStrategy.RESTART_LOADER);
        }

        @Test
        @DisplayName("★ 重启期间的请求挂起等待,而不是收 503")
        void parksConcurrentRequests() throws Exception {
            var gate = new RestartGate(Duration.ofSeconds(5));
            gate.recordChange(ReloadStrategy.RESTART_LOADER);

            var reloadStarted = new CountDownLatch(1);
            var releaseReload = new CountDownLatch(1);
            var secondPassed = new CountDownLatch(1);

            var first = Thread.ofVirtual().start(() -> gate.enter(s -> {
                reloadStarted.countDown();
                try {
                    releaseReload.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));

            assertThat(reloadStarted.await(2, TimeUnit.SECONDS)).isTrue();

            var second = Thread.ofVirtual().start(() -> {
                if (gate.enter(s -> {})) secondPassed.countDown();
            });

            // 第二个请求此刻应该在等,而不是被拒
            Thread.sleep(80);
            assertThat(secondPassed.getCount())
                    .as("浏览器刷新的体验该是「慢了 2 秒」而不是「报错了」")
                    .isEqualTo(1);
            assertThat(gate.parkedRequests()).isEqualTo(1);
            assertThat(gate.isRestarting()).isTrue();

            releaseReload.countDown();
            first.join();
            second.join();

            assertThat(secondPassed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.totalParked()).isEqualTo(1);
            assertThat(gate.isRestarting()).isFalse();
        }

        @Test
        @DisplayName("★ 挂起有上限 —— 重启卡住时不让浏览器永远转圈")
        void parkingTimesOut() throws Exception {
            var gate = new RestartGate(Duration.ofMillis(120));
            gate.recordChange(ReloadStrategy.RESTART_LOADER);

            var reloadStarted = new CountDownLatch(1);
            var releaseReload = new CountDownLatch(1);

            var first = Thread.ofVirtual().start(() -> gate.enter(s -> {
                reloadStarted.countDown();
                try {
                    releaseReload.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(reloadStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // 无限等待意味着重启一旦卡住,连错误页都显示不出来
            assertThat(gate.enter(s -> {})).isFalse();
            assertThat(gate.timeouts()).isEqualTo(1);
            assertThat(gate.timeoutMessage())
                    .contains("编译失败")
                    .contains("--no-watch");

            releaseReload.countDown();
            first.join();
        }

        @Test
        @DisplayName("★ 重载失败时把变更放回去 —— 修好再刷新一次就会重试")
        void failedReloadKeepsPending() {
            var gate = new RestartGate();
            gate.recordChange(ReloadStrategy.RESTART_LOADER);

            assertThatThrownBy(() -> gate.enter(s -> {
                throw new IllegalStateException("编译失败");
            })).isInstanceOf(IllegalStateException.class);

            // 清掉它等于「改错了之后再改对也不生效」
            assertThat(gate.hasPendingChanges()).isTrue();
            assertThat(gate.pendingStrategy()).isEqualTo(ReloadStrategy.RESTART_LOADER);
            assertThat(gate.isRestarting()).isFalse();
        }

        @Test
        @DisplayName("IGNORE 不算待处理变更")
        void ignoreIsNotPending() {
            var gate = new RestartGate();
            gate.recordChange(ReloadStrategy.IGNORE);

            assertThat(gate.hasPendingChanges()).isFalse();
        }

        @Test
        @DisplayName("restartNow 不等请求")
        void restartNowIsImmediate() {
            var gate = new RestartGate();
            var applied = new AtomicReference<ReloadStrategy>();

            gate.restartNow(applied::set);

            assertThat(applied.get()).isEqualTo(ReloadStrategy.RESTART_LOADER);
            assertThat(gate.totalRestarts()).isEqualTo(1);
        }
    }

    // ================= 协调器 =================

    @Nested
    @DisplayName("协调器")
    class Coordination {

        @Test
        @DisplayName("★ 资源变更立即生效,不等请求")
        void resourceAppliesImmediately() {
            var gate = new RestartGate();
            var invalidations = new AtomicInteger();
            var coordinator = new ReloadCoordinator(gate)
                    .onResource(invalidations::incrementAndGet);

            coordinator.handle(Set.of(Path.of("src/main/resources/views/a.jblade.html")));

            // 毫秒级操作,没有「攒着等请求」的必要
            assertThat(invalidations.get()).isEqualTo(1);
            assertThat(gate.hasPendingChanges()).isFalse();
        }

        @Test
        @DisplayName("★ 源码变更登记为待处理,不立即重启")
        void javaChangeIsDeferred() {
            var gate = new RestartGate();
            var restarts = new AtomicInteger();
            var coordinator = new ReloadCoordinator(gate)
                    .onRestartLoader(restarts::incrementAndGet);

            coordinator.handle(Set.of(Path.of("src/main/java/app/X.java")));

            assertThat(restarts.get()).as("还没有请求进来").isZero();
            assertThat(gate.pendingStrategy()).isEqualTo(ReloadStrategy.RESTART_LOADER);

            // 下一个请求把它执行掉
            assertThat(coordinator.enterRequest()).isTrue();
            assertThat(restarts.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ L2 未配置时降级到 L3,而不是什么都不做")
        void hotswapFallsBackToRestart() {
            var gate = new RestartGate();
            var restarts = new AtomicInteger();
            var coordinator = new ReloadCoordinator(gate, new ChangeClassifier(true))
                    .onRestartLoader(restarts::incrementAndGet);

            coordinator.handle(Set.of(Path.of("target/classes/app/X.class")));
            coordinator.enterRequest();

            // L3 的 < 2s 是兜底保证(docs/15 §3.4)
            assertThat(restarts.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("配置了 L2 就用 L2")
        void usesHotswapWhenAvailable() {
            var gate = new RestartGate();
            var hotswaps = new AtomicInteger();
            var restarts = new AtomicInteger();
            var coordinator = new ReloadCoordinator(gate, new ChangeClassifier(true))
                    .onHotswap(hotswaps::incrementAndGet)
                    .onRestartLoader(restarts::incrementAndGet);

            coordinator.handle(Set.of(Path.of("target/classes/app/X.class")));
            coordinator.enterRequest();

            assertThat(hotswaps.get()).isEqualTo(1);
            assertThat(restarts.get()).isZero();
        }

        @Test
        @DisplayName("★ pom.xml 变更默认只提示,不自作主张退出进程")
        void jvmRestartOnlyWarnsByDefault() {
            var gate = new RestartGate();
            var coordinator = new ReloadCoordinator(gate);

            // 在 IDE 里跑的时候突然退出进程会非常突然
            coordinator.handle(Set.of(Path.of("pom.xml")));

            assertThat(gate.hasPendingChanges()).isFalse();
            assertThat(coordinator.lastStrategy()).isEqualTo(ReloadStrategy.RESTART_JVM);
        }

        @Test
        @DisplayName("配置了 onRestartJvm 就调它")
        void jvmRestartHookIsCalled() {
            var called = new AtomicInteger();
            new ReloadCoordinator(new RestartGate())
                    .onRestartJvm(called::incrementAndGet)
                    .handle(Set.of(Path.of("pom.xml")));

            assertThat(called.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("监听器能看到分类与执行")
        void listenersObserve() {
            var classified = new AtomicReference<ReloadStrategy>();
            var applied = new AtomicReference<ReloadStrategy>();

            new ReloadCoordinator(new RestartGate())
                    .addListener(new ReloadCoordinator.Listener() {
                        @Override
                        public void onClassified(Set<Path> changed, ReloadStrategy s) {
                            classified.set(s);
                        }

                        @Override
                        public void onApplied(ReloadStrategy s) {
                            applied.set(s);
                        }
                    })
                    .handle(Set.of(Path.of("src/main/resources/views/a.jblade.html")));

            assertThat(classified.get()).isEqualTo(ReloadStrategy.RESOURCE);
            assertThat(applied.get()).isEqualTo(ReloadStrategy.RESOURCE);
        }

        @Test
        @DisplayName("噪声变更什么都不做")
        void noiseDoesNothing() {
            var gate = new RestartGate();
            var touched = new AtomicInteger();
            var coordinator = new ReloadCoordinator(gate)
                    .onResource(touched::incrementAndGet)
                    .onRestartLoader(touched::incrementAndGet);

            coordinator.handle(Set.of(Path.of(".git/index"), Path.of("a.java~")));

            assertThat(touched.get()).isZero();
            assertThat(gate.hasPendingChanges()).isFalse();
        }
    }

    // ================= 文件监听 =================

    @Nested
    @DisplayName("文件监听")
    class Watching {

        @TempDir
        Path root;

        @Test
        @DisplayName("★ 一次保存只上报一批(去抖)")
        void debouncesBurstsOfEvents() throws Exception {
            var batches = new java.util.concurrent.CopyOnWriteArrayList<Set<Path>>();
            var latch = new CountDownLatch(1);

            try (var watcher = new FileWatcher(List.of(root), changed -> {
                batches.add(changed);
                latch.countDown();
            }, Duration.ofMillis(120))) {
                watcher.start();
                Thread.sleep(200);      // 等监听就绪

                // 模拟编辑器的原子保存:写临时文件 → 重命名 → 改 mtime
                Files.writeString(root.resolve("a.tmp"), "x");
                Files.move(root.resolve("a.tmp"), root.resolve("a.jblade.html"));
                Files.writeString(root.resolve("a.jblade.html"), "y");

                assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(300);      // 看会不会又来一批
            }

            // 不去抖的话一次保存触发 3~10 次重载,而重载是秒级操作
            assertThat(batches).hasSize(1);
            assertThat(batches.get(0))
                    .anyMatch(p -> p.getFileName().toString().equals("a.jblade.html"));
        }

        @Test
        @DisplayName("★ 新建的子目录会被补注册")
        void registersNewDirectories() throws Exception {
            var seen = new java.util.concurrent.CopyOnWriteArrayList<Path>();
            var newFile = new CountDownLatch(1);

            try (var watcher = new FileWatcher(List.of(root), changed -> {
                changed.forEach(seen::add);
                if (changed.stream().anyMatch(
                        p -> p.getFileName().toString().equals("New.java"))) {
                    newFile.countDown();
                }
            }, Duration.ofMillis(100))) {
                watcher.start();
                Thread.sleep(200);

                // 「新建一个包再在里面建类」是写新功能时最常见的动作
                Path pkg = root.resolve("app/services");
                Files.createDirectories(pkg);
                Thread.sleep(300);      // 等补注册
                Files.writeString(pkg.resolve("New.java"), "class New {}");

                assertThat(newFile.await(3, TimeUnit.SECONDS))
                        .as("不补注册的话新包里的文件永远监听不到")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("不监听 .git 与 node_modules")
        void skipsNoisyDirectories() throws Exception {
            Files.createDirectories(root.resolve(".git/objects"));
            Files.createDirectories(root.resolve("node_modules/pkg"));
            Files.createDirectories(root.resolve("app"));

            try (var watcher = new FileWatcher(List.of(root), changed -> {})) {
                watcher.start();
                Thread.sleep(200);

                // root + app = 2。注册 .git / node_modules 会让
                // inotify 的句柄数爆掉,而超限后监听**静默失效**
                assertThat(watcher.watchedDirectories()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("close 后不再上报")
        void stopsAfterClose() throws Exception {
            var count = new AtomicInteger();
            var watcher = new FileWatcher(List.of(root),
                    changed -> count.incrementAndGet(), Duration.ofMillis(80));
            watcher.start();
            Thread.sleep(150);
            watcher.close();

            assertThat(watcher.isRunning()).isFalse();

            Files.writeString(root.resolve("a.jblade.html"), "x");
            Thread.sleep(300);
            assertThat(count.get()).isZero();
        }

        @Test
        @DisplayName("不存在的根目录不导致启动失败")
        void missingRootIsTolerated() {
            try (var watcher = new FileWatcher(
                    List.of(root, root.resolve("does-not-exist")), changed -> {})) {
                watcher.start();
                assertThat(watcher.isRunning()).isTrue();
            }
        }

        @Test
        @DisplayName("监听线程出错不会死掉")
        void listenerExceptionDoesNotKillWatcher() throws Exception {
            var calls = new AtomicInteger();

            try (var watcher = new FileWatcher(List.of(root), changed -> {
                calls.incrementAndGet();
                throw new IllegalStateException("回调里炸了");
            }, Duration.ofMillis(80))) {
                watcher.start();
                Thread.sleep(150);

                Files.writeString(root.resolve("a.jblade.html"), "x");
                Thread.sleep(300);
                Files.writeString(root.resolve("b.jblade.html"), "y");
                Thread.sleep(300);

                // 监听线程死了之后热重载静默失效,而用户只会发现
                // 「改代码没反应了」,想不到去看日志
                assertThat(calls.get()).isGreaterThanOrEqualTo(2);
                assertThat(watcher.isRunning()).isTrue();
            }
        }
    }
}
