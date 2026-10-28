import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import javax.tools.StandardLocation;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

/**
 * V4 工具:判定一次代码变更能否被 JVM 标准 HotSwap 处理。
 *
 * JVM 的 Instrumentation.redefineClasses 只允许改方法体 ——
 * 不允许增删重命名字段/方法、不允许改方法签名、不允许改修饰符与继承关系。
 *
 * 因此本工具提取每个 Java 文件的「结构骨架」(类声明 + 字段签名 + 方法签名),
 * 比较变更前后的骨架:
 *   骨架相同  → METHOD_BODY_ONLY  → 标准 HotSwap 可处理,0 重启
 *   骨架不同  → STRUCTURAL        → 需要 L3 类加载器重启
 *
 * 关键:**只解析不编译**,所以能直接分析任意 git 版本,无需解决项目依赖。
 *
 * 用法(由 analyze.sh 驱动):
 *   java ChangeClassifier <旧文件> <新文件>
 *   输出:METHOD_BODY_ONLY | STRUCTURAL | PARSE_ERROR  + 差异说明
 */
public class ChangeClassifier {

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--selftest")) { selfTest(); return; }
        if (args.length != 2) {
            System.err.println("用法: java ChangeClassifier <旧文件> <新文件>");
            System.exit(2);
        }
        String oldSrc = java.nio.file.Files.readString(java.nio.file.Path.of(args[0]), StandardCharsets.UTF_8);
        String newSrc = java.nio.file.Files.readString(java.nio.file.Path.of(args[1]), StandardCharsets.UTF_8);
        System.out.println(classify(oldSrc, newSrc));
    }

    // ---------------- 分类 ----------------

    public record Verdict(String kind, List<String> diffs) {
        @Override public String toString() {
            return kind + (diffs.isEmpty() ? "" : "\t" + String.join(" | ", diffs));
        }
    }

    public static Verdict classify(String oldSrc, String newSrc) {
        Map<String, String> a, b;
        try {
            a = skeleton(oldSrc);
            b = skeleton(newSrc);
        } catch (Exception e) {
            return new Verdict("PARSE_ERROR", List.of(e.getClass().getSimpleName()));
        }

        List<String> diffs = new ArrayList<>();
        for (String k : b.keySet()) if (!a.containsKey(k)) diffs.add("新增 " + k);
        for (String k : a.keySet()) if (!b.containsKey(k)) diffs.add("删除 " + k);
        for (String k : a.keySet()) {
            if (b.containsKey(k) && !Objects.equals(a.get(k), b.get(k))) diffs.add("改变 " + k);
        }

        if (diffs.isEmpty()) return new Verdict("METHOD_BODY_ONLY", List.of());

        // 细分结构性变更:**纯新增** vs **删除/改签名**。
        // 标准 HotSwap 两者都不支持;
        // 但 JetBrains Runtime / DCEVM 的增强 HotSwap **支持纯新增**
        // (加字段、加方法),只有删除与签名/继承变更才真正需要重启。
        boolean onlyAdditions = diffs.stream().allMatch(d -> d.startsWith("新增"));
        String kind = onlyAdditions ? "STRUCTURAL_ADD_ONLY" : "STRUCTURAL_HARD";
        return new Verdict(kind, diffs.size() > 4 ? diffs.subList(0, 4) : diffs);
    }

    // ---------------- 骨架提取 ----------------

    /**
     * 返回 「成员标识 → 签名」 的映射。
     * 不含方法体,不含注解(注解在 class 文件里是元数据,
     * 实践中 redefineClasses 可容忍其变化),不含 import。
     */
    static Map<String, String> skeleton(String source) throws IOException {
        CompilationUnitTree unit = parse(source);
        Map<String, String> out = new LinkedHashMap<>();

        new TreeScanner<Void, String>() {
            @Override public Void visitClass(ClassTree node, String outer) {
                String name = (outer == null ? "" : outer + ".") + node.getSimpleName();
                out.put("class " + name, String.join(" ",
                        node.getModifiers().getFlags().stream().map(Enum::name).sorted().toList())
                        + " extends:" + str(node.getExtendsClause())
                        + " implements:" + node.getImplementsClause().stream()
                              .map(Object::toString).sorted().collect(Collectors.joining(","))
                        + " typeParams:" + node.getTypeParameters());
                return super.visitClass(node, name);
            }

            @Override public Void visitMethod(MethodTree node, String owner) {
                String params = node.getParameters().stream()
                        .map(v -> v.getType().toString())      // 只看类型,参数改名不影响字节码兼容
                        .collect(Collectors.joining(","));
                String key = "method " + owner + "#" + node.getName() + "(" + params + ")";
                out.put(key, String.join(" ",
                        node.getModifiers().getFlags().stream().map(Enum::name).sorted().toList())
                        + " returns:" + str(node.getReturnType())
                        + " throws:" + node.getThrows().stream()
                              .map(Object::toString).sorted().collect(Collectors.joining(",")));
                return null;   // 不下钻方法体 —— 方法体正是我们要忽略的
            }

            @Override public Void visitVariable(VariableTree node, String owner) {
                if (owner == null) return null;              // 跳过方法内的局部变量
                out.put("field " + owner + "#" + node.getName(),
                        String.join(" ", node.getModifiers().getFlags().stream()
                                .map(Enum::name).sorted().toList())
                        + " type:" + str(node.getType()));
                return null;
            }

            private String str(Tree t) { return t == null ? "-" : t.toString(); }
        }.scan(unit, null);

        return out;
    }

    static CompilationUnitTree parse(String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var fm = compiler.getStandardFileManager(d -> {}, null, StandardCharsets.UTF_8);
        JavaFileObject file = new SimpleJavaFileObject(
                URI.create("string:///X.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        // 诊断全部丢弃:只需要语法树,语义错误无所谓
        JavacTask task = (JavacTask) compiler.getTask(
                null, fm, d -> {}, List.of("-proc:none"), null, List.of(file));
        var it = task.parse().iterator();
        if (!it.hasNext()) throw new IOException("解析结果为空");
        return it.next();
    }

    // ---------------- 自测 ----------------

    static void selfTest() {
        record Case(String name, String expect, String a, String b) {}
        List<Case> cases = List.of(
            new Case("只改方法体", "METHOD_BODY_ONLY",
                "class A { int f; int m(int x){ return x+1; } }",
                "class A { int f; int m(int x){ return x+2; } }"),
            new Case("方法体内加语句与局部变量", "METHOD_BODY_ONLY",
                "class A { void m(){ } }",
                "class A { void m(){ int t=1; System.out.println(t); } }"),
            new Case("改参数名(不影响字节码)", "METHOD_BODY_ONLY",
                "class A { int m(int x){ return x; } }",
                "class A { int m(int y){ return y; } }"),
            new Case("新增方法(增强 HotSwap 可处理)", "STRUCTURAL_ADD_ONLY",
                "class A { void m(){} }",
                "class A { void m(){} void n(){} }"),
            new Case("删除方法", "STRUCTURAL_HARD",
                "class A { void m(){} void n(){} }",
                "class A { void m(){} }"),
            new Case("新增字段(增强 HotSwap 可处理)", "STRUCTURAL_ADD_ONLY",
                "class A { int f; }",
                "class A { int f; String g; }"),
            new Case("改方法返回类型", "STRUCTURAL_HARD",
                "class A { int m(){return 0;} }",
                "class A { long m(){return 0;} }"),
            new Case("改方法参数类型", "STRUCTURAL_HARD",
                "class A { void m(int x){} }",
                "class A { void m(long x){} }"),
            new Case("改修饰符", "STRUCTURAL_HARD",
                "class A { void m(){} }",
                "class A { public void m(){} }"),
            new Case("改继承关系", "STRUCTURAL_HARD",
                "class A { }",
                "class A extends B { }"),
            new Case("方法重载(新增)", "STRUCTURAL_ADD_ONLY",
                "class A { void m(int x){} }",
                "class A { void m(int x){} void m(String s){} }")
        );

        int pass = 0, fail = 0;
        System.out.println("ChangeClassifier 自测");
        System.out.println("=".repeat(70));
        for (Case c : cases) {
            Verdict v = classify(c.a, c.b);
            boolean ok = v.kind().equals(c.expect);
            if (ok) pass++; else fail++;
            System.out.printf("  %s %-32s 期望 %-20s 实际 %s%n",
                    ok ? "✓" : "✗", c.name, c.expect, v.kind());
            if (!ok || !v.diffs().isEmpty()) System.out.println("        差异: " + v.diffs());
        }
        System.out.println("=".repeat(70));
        System.out.printf(" %d 通过,%d 失败%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
