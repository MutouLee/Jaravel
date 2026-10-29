package io.jaravel.devtools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 递归文件监听,带**去抖**。
 *
 * <pre>{@code
 * var watcher = new FileWatcher(List.of(Path.of("src"), Path.of("src/main/resources")),
 *                               changed -> coordinator.onChange(changed));
 * watcher.start();
 * }</pre>
 *
 * <h2>去抖是必需的,不是优化</h2>
 * <p>一次「保存」在文件系统层面往往是多个事件:编辑器先写临时文件、
 * 再重命名、再改 mtime;IDE 的增量编译会在几十毫秒内产出一串 .class。
 * 不去抖的话一次保存会触发 3~10 次重载 —— 而重载是秒级操作,
 * 结果是应用在那几秒里反复重启,用户的刷新永远落在重启中间。
 *
 * <p>做法:收到事件后等 {@link #quietPeriod} 没有新事件才上报。
 *
 * <h2>为什么要自己递归注册</h2>
 * <p>JDK 的 {@code WatchService} **不支持递归**(macOS/Linux 上没有
 * 原生递归监听)。必须逐目录注册,并在新目录出现时补注册 ——
 * 不补的话「新建一个包再在里面建类」不会被监听到,
 * 而那是写新功能时最常见的动作。
 */
public final class FileWatcher implements AutoCloseable {

    private final List<Path> roots;
    private final Consumer<Set<Path>> onChange;
    private final Duration quietPeriod;

    private final Map<WatchKey, Path> watched = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private WatchService service;
    private Thread thread;

    public FileWatcher(List<Path> roots, Consumer<Set<Path>> onChange) {
        this(roots, onChange, Duration.ofMillis(150));
    }

    /**
     * @param quietPeriod 静默期。150ms 是实测折中:短于 100ms 在 IDE
     *                    增量编译下仍会分批触发,长于 300ms 用户能感觉到延迟
     */
    public FileWatcher(List<Path> roots, Consumer<Set<Path>> onChange,
                       Duration quietPeriod) {
        this.roots = List.copyOf(roots);
        this.onChange = onChange;
        this.quietPeriod = quietPeriod;
    }

    // ================= 生命周期 =================

    public void start() {
        if (!running.compareAndSet(false, true)) return;

        try {
            service = roots.get(0).getFileSystem().newWatchService();
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建 WatchService", e);
        }

        for (Path root : roots) {
            if (Files.isDirectory(root)) registerRecursively(root);
        }

        // 虚拟线程:它绝大部分时间阻塞在 poll 上,占一个平台线程很浪费。
        // 命名是为了让 jstack 的输出可读 —— 「这个线程在干什么」
        // 不该需要去翻代码
        thread = Thread.ofVirtual().name("jaravel-file-watcher").start(this::loop);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;

        try {
            if (service != null) service.close();
        } catch (IOException ignored) {
            // 关闭时的异常没有处理价值
        }
        if (thread != null) thread.interrupt();
    }

    public boolean isRunning() {
        return running.get();
    }

    public int watchedDirectories() {
        return watched.size();
    }

    // ================= 监听 =================

    private void registerRecursively(Path directory) {
        try {
            Files.walkFileTree(directory, new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isSkippedDirectory(dir)) return FileVisitResult.SKIP_SUBTREE;
                    register(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 权限不足、符号链接成环 —— 跳过而不是整体失败
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("注册监听失败:" + directory, e);
        }
    }

    private void register(Path directory) {
        try {
            WatchKey key = directory.register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watched.put(key, directory);
        } catch (IOException ignored) {
            // 目录在注册前被删了:正常竞态
        }
    }

    /**
     * 不监听的目录。
     *
     * <p>{@code .git} 下的变更量极大(每次 git 操作都有一堆),
     * {@code node_modules} 动辄十万个文件 —— 注册它们会让
     * WatchService 的句柄数爆掉(Linux 的 inotify 有上限,
     * 超了之后监听**静默失效**,症状是热重载时好时坏)。
     */
    private static boolean isSkippedDirectory(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return name.equals(".git") || name.equals(".idea") || name.equals(".vscode")
            || name.equals("node_modules") || name.equals("logs");
    }

    private void loop() {
        var batch = new LinkedHashSet<Path>();

        while (running.get()) {
            try {
                // 第一个事件:无限等
                WatchKey key = batch.isEmpty()
                        ? service.take()
                        : service.poll(quietPeriod.toMillis(), TimeUnit.MILLISECONDS);

                if (key == null) {
                    // 静默期内没有新事件 → 这一批结束,上报
                    flush(batch);
                    continue;
                }

                drain(key, batch);
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.nio.file.ClosedWatchServiceException e) {
                return;
            } catch (RuntimeException e) {
                // 监听线程**绝不能死**:死了之后热重载静默失效,
                // 而用户只会发现「改代码没反应了」,想不到去看日志
                System.err.println("[jaravel] 文件监听出错(已继续):" + e);
            }
        }
    }

    private void drain(WatchKey key, Set<Path> batch) {
        Path directory = watched.get(key);
        if (directory == null) return;

        for (var event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                // 事件队列溢出:丢了哪些不知道了。把整个根目录报上去,
                // 让分类器按最保守的方式处理 —— 漏掉变更比多重启一次糟
                batch.add(directory);
                continue;
            }

            Path changed = directory.resolve((Path) event.context());
            batch.add(changed);

            // 新建的目录要补注册,否则「新建包再在里面建类」监听不到
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                    && Files.isDirectory(changed)) {
                registerRecursively(changed);
            }
        }
    }

    private void flush(Set<Path> batch) {
        if (batch.isEmpty()) return;

        var snapshot = new LinkedHashSet<>(batch);
        batch.clear();
        try {
            onChange.accept(snapshot);
        } catch (RuntimeException e) {
            System.err.println("[jaravel] 处理文件变更时出错:" + e);
        }
    }

    /** 测试用:同步跑一遍分类与回调,不依赖真实文件事件 */
    public void simulateChange(Path... paths) {
        onChange.accept(new LinkedHashSet<>(new ArrayList<>(List.of(paths))));
    }
}
