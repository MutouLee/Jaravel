package io.jaravel.events;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 读 APT 生成的 {@code META-INF/jaravel/listeners.idx} 并实例化绑定。
 *
 * <pre>{@code
 * var bindings = ListenerIndex.load(getClass().getClassLoader());
 * bus.register(bindings);
 * }</pre>
 *
 * <h2>为什么要有索引文件,而不是让 Spring 扫 @Listener bean</h2>
 * <p>Spring 能扫出监听器**实例**,但扫不出「哪个 handle 对应哪个事件」——
 * 那需要反射读方法签名,而那正是 APT 要省掉的。
 *
 * <p>索引文件让启动时只做一件事:读一个文本文件,按名字 newInstance
 * 若干个无状态的绑定对象。没有包扫描,没有方法反射。
 *
 * <h2>多模块时会有多份索引</h2>
 * <p>{@code getResources}(复数)而不是 {@code getResource} —— 一个
 * 多模块应用里每个 jar 各有一份自己的索引。只读第一份的后果是
 * **只有一个模块的监听器生效**,而那个症状在单模块开发时完全看不出来,
 * 等拆模块时才出现。
 */
public final class ListenerIndex {

    private static final String RESOURCE = "META-INF/jaravel/listeners.idx";

    private ListenerIndex() {
    }

    /** 读所有 classpath 上的索引并实例化绑定 */
    public static List<ListenerBinding> load(ClassLoader loader) {
        var bindings = new ArrayList<ListenerBinding>();

        for (String className : names(loader)) {
            bindings.add(instantiate(className, loader));
        }
        return bindings;
    }

    /** 索引里登记的绑定类名。去重 —— 同一个 jar 在 classpath 上出现两次是常见的 */
    public static List<String> names(ClassLoader loader) {
        var names = new LinkedHashSet<String>();

        try {
            Enumeration<URL> resources = loader.getResources(RESOURCE);
            while (resources.hasMoreElements()) {
                readInto(resources.nextElement(), names);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "读取 " + RESOURCE + " 失败:" + e.getMessage(), e);
        }
        return List.copyOf(names);
    }

    private static void readInto(URL url, LinkedHashSet<String> names) throws IOException {
        try (var reader = new BufferedReader(
                new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                names.add(trimmed);
            }
        }
    }

    private static ListenerBinding instantiate(String className, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, true, loader);
            return (ListenerBinding) type.getDeclaredConstructor().newInstance();

        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("""
                    索引里登记了绑定类 %s,但它不在 classpath 上。
                      这通常意味着索引文件是旧的 —— 清一次构建产物
                      (mvn clean)再编译。"""
                    .formatted(className), e);

        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("""
                    无法实例化监听器绑定 %s:%s
                      绑定类由 APT 生成,它应当有一个无参构造器。
                      手工改过生成的代码吗?"""
                    .formatted(className, e.getMessage()), e);
        }
    }
}
