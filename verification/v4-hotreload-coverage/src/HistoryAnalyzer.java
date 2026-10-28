import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * V4:扫描真实项目的 git 历史,统计「只改方法体」的变更占比。
 *
 * 这个比例决定 L2 热重载(JVM 标准 HotSwap,0 重启)能覆盖多少日常开发。
 * docs/15 §9 原本是估计值 60%,需要实测。
 *
 * 重要口径说明:
 *   本工具以 **commit** 为单位统计,而真实开发的重载单位是 **一次保存**。
 *   一个最终新增了方法的 commit,过程中可能经历多次纯方法体的保存-刷新循环。
 *   因此本统计是 L2 覆盖率的 **下界**(保守估计)。
 *
 * 用法: java HistoryAnalyzer <repo路径> [commit数量] [路径前缀过滤]
 */
public class HistoryAnalyzer {

    static String repo;
    static boolean excludeChores = false;

    /** 版本升级 / 依赖提升 / 发版类提交的消息特征 */
    static final java.util.regex.Pattern CHORE = java.util.regex.Pattern.compile(
            "(?i)\\b(bump|upgrade|update .*(dependenc|version|image)|"
          + "next development version|prepare release|release [0-9]|"
          + "dependabot|renovate)\\b");

    public static void main(String[] args) throws Exception {
        repo = args.length > 0 ? args[0] : ".";
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        String filter = "";
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--exclude-chores")) excludeChores = true;
            else filter = args[i];
        }

        List<String> commits = List.of(git("log", "--format=%H", "-n", String.valueOf(limit))
                .strip().split("\n"));

        Map<String, Integer> byCommit = new LinkedHashMap<>();
        Map<String, Integer> byFile = new LinkedHashMap<>();
        Map<String, Integer> structuralReasons = new TreeMap<>();
        int analyzed = 0, skippedRoot = 0;
        List<String> bodyOnlyExamples = new ArrayList<>();

        int skippedChore = 0;
        for (String sha : commits) {
            if (sha.isBlank()) continue;

            // 排除版本升级/依赖提升类提交 —— 它们会让 L4 占比虚高,
            // 而开发者的内循环(改代码-刷新)里不包含这类操作。
            if (excludeChores) {
                String subject = showQuiet2("log", "-1", "--format=%s", sha);
                if (subject != null && CHORE.matcher(subject).find()) { skippedChore++; continue; }
            }

            String status;
            try {
                status = git("diff-tree", "--no-commit-id", "--name-status", "-r", "-M", sha);
            } catch (Exception e) { skippedRoot++; continue; }

            List<String[]> javaChanges = new ArrayList<>();
            boolean touchesBuild = false, touchesReloadable = false, touchesNonRuntime = false;
            for (String line : status.strip().split("\n")) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;
                String st = parts[0].substring(0, 1);
                String path = parts[parts.length - 1];
                if (path.endsWith(".java")) {
                    if (filter.isEmpty() || path.startsWith(filter)) {
                        javaChanges.add(new String[]{st, path});
                    }
                    continue;
                }
                switch (classifyResource(path)) {
                    case "BUILD"       -> touchesBuild = true;
                    case "RELOADABLE"  -> touchesReloadable = true;
                    default            -> touchesNonRuntime = true;
                }
            }

            if (javaChanges.isEmpty()) {
                // 关键修正:pom.xml / build.gradle 变更需要 L4 全量重启(~10s),
                // 不能与模板/配置的 L1 热替换(0 重启)混为一谈。
                if (touchesBuild)            bump(byCommit, "L4_BUILD_FILE");
                else if (touchesReloadable)  bump(byCommit, "L1_RESOURCE");
                else if (touchesNonRuntime)  bump(byCommit, "NON_RUNTIME");
                else                         bump(byCommit, "NO_CHANGE");
                continue;
            }
            if (touchesBuild) {              // Java + 构建文件同时改 → 仍需全量重启
                analyzed++;
                for (String[] ch : javaChanges) bump(byFile, "(随构建文件重启)");
                bump(byCommit, "L4_BUILD_FILE");
                continue;
            }

            analyzed++;
            boolean allBodyOnly = true;
            for (String[] ch : javaChanges) {
                String st = ch[0], path = ch[1];
                String verdict;
                if (st.equals("A")) { verdict = "FILE_ADDED"; }
                else if (st.equals("D")) { verdict = "FILE_DELETED"; }
                else {
                    String before = showQuiet(sha + "^:" + path);
                    String after  = showQuiet(sha + ":" + path);
                    if (before == null || after == null) { verdict = "UNRESOLVED"; }
                    else {
                        var v = ChangeClassifier.classify(before, after);
                        verdict = v.kind();
                        if (v.kind().startsWith("STRUCTURAL")) {
                            for (String d : v.diffs()) {
                                String reason = d.split(" ")[0] + " " + d.split(" ")[1].split("#")[0]
                                        .replaceAll("[A-Z]\\w*", "X");
                                bump(structuralReasons, normalizeReason(d));
                            }
                        } else if (v.kind().equals("METHOD_BODY_ONLY") && bodyOnlyExamples.size() < 5) {
                            bodyOnlyExamples.add(sha.substring(0, 8) + "  " + path);
                        }
                    }
                }
                bump(byFile, verdict);
                if (!verdict.equals("METHOD_BODY_ONLY")) allBodyOnly = false;
            }
            bump(byCommit, allBodyOnly ? "METHOD_BODY_ONLY" : "STRUCTURAL");
        }

        // ---------------- 报告 ----------------
        String title = "V4 · L2 热重载覆盖率实测";
        System.out.println("=".repeat(76));
        System.out.println(" " + title);
        System.out.println(" 仓库: " + repo + (filter.isEmpty() ? "" : "   路径过滤: " + filter));
        System.out.println(" 分析 commit 数: " + commits.size() + "(含 Java 变更的: " + analyzed
                + (excludeChores ? ",已排除版本升级类: " + skippedChore : "") + ")");
        System.out.println("=".repeat(76));

        report("按文件统计(每个被修改的 .java 文件一条)", byFile);
        report("按 commit 统计(commit 内全部 Java 变更均为方法体才算 METHOD_BODY_ONLY)", byCommit);

        int bodyOnly  = byFile.getOrDefault("METHOD_BODY_ONLY", 0);
        int addOnly   = byFile.getOrDefault("STRUCTURAL_ADD_ONLY", 0);
        int hard      = byFile.getOrDefault("STRUCTURAL_HARD", 0);
        int structural = addOnly + hard;
        int modified = bodyOnly + structural;
        if (modified > 0) {
            System.out.printf("%n★ 在「被修改的已有 .java 文件」中,只改方法体的占比: %.1f%%  (%d/%d)%n",
                    100.0 * bodyOnly / modified, bodyOnly, modified);
            System.out.println("  —— 这是 L2 标准 HotSwap(任何 JDK,0 重启)能覆盖的部分");
            System.out.printf("★ 再加上「纯新增」(加方法/加字段),增强 HotSwap(JBR/DCEVM)可覆盖: %.1f%%  (%d/%d)%n",
                    100.0 * (bodyOnly + addOnly) / modified, bodyOnly + addOnly, modified);
            System.out.printf("  真正必须重启的(删除/改签名/改继承): %.1f%%  (%d/%d)%n",
                    100.0 * hard / modified, hard, modified);
        }

        // 按重载层级汇总(排除与运行时无关的 commit)
        int l1 = byCommit.getOrDefault("L1_RESOURCE", 0);
        int l2 = byCommit.getOrDefault("METHOD_BODY_ONLY", 0);
        int l3 = byCommit.getOrDefault("STRUCTURAL", 0);
        int l4 = byCommit.getOrDefault("L4_BUILD_FILE", 0);
        int runtime = l1 + l2 + l3 + l4;
        if (runtime > 0) {
            System.out.println();
            System.out.println("按重载层级汇总(仅计影响运行时的 commit,共 " + runtime + " 个):");
            System.out.printf("   L1 资源热替换   0 重启   %5d   %5.1f%%  %s%n", l1, 100.0*l1/runtime, bar(100.0*l1/runtime));
            System.out.printf("   L2 HotSwap      0 重启   %5d   %5.1f%%  %s%n", l2, 100.0*l2/runtime, bar(100.0*l2/runtime));
            System.out.printf("   L3 类加载器重启 <2s      %5d   %5.1f%%  %s%n", l3, 100.0*l3/runtime, bar(100.0*l3/runtime));
            System.out.printf("   L4 全量 JVM重启 ~10s     %5d   %5.1f%%  %s%n", l4, 100.0*l4/runtime, bar(100.0*l4/runtime));
            System.out.printf("%n   ⇒ 无需重启的变更占比: %.1f%%   需要重启的: %.1f%%%n",
                    100.0*(l1+l2)/runtime, 100.0*(l3+l4)/runtime);
        }

        if (!structuralReasons.isEmpty()) {
            System.out.println("\n结构性变更的原因分布(前 10):");
            structuralReasons.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue()).limit(10)
                    .forEach(e -> System.out.printf("   %-34s %d%n", e.getKey(), e.getValue()));
        }

        if (!bodyOnlyExamples.isEmpty()) {
            System.out.println("\n纯方法体变更示例:");
            bodyOnlyExamples.forEach(s -> System.out.println("   " + s));
        }
        if (skippedRoot > 0) System.out.println("\n(跳过 " + skippedRoot + " 个无父提交的 commit)");
    }

    /**
     * 非 Java 文件的重载代价分类。
     *   BUILD      —— 依赖/构建变更,需 L4 全量 JVM 重启(~10s)
     *   RELOADABLE —— 模板/配置/静态资源,L1 内存热替换(0 重启)
     *   NON_RUNTIME—— 文档、CI、Dockerfile 等,与运行时无关,无需重载
     */
    static String classifyResource(String path) {
        String f = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
        if (f.equals("pom.xml") || f.endsWith(".gradle") || f.endsWith(".gradle.kts")
                || f.equals("mvnw") || f.equals("mvnw.cmd") || f.equals("gradlew")
                || f.endsWith(".jar") || f.equals("gradle.properties")
                || f.equals("settings.gradle") || f.equals("libs.versions.toml")) return "BUILD";

        if (f.endsWith(".properties") || f.endsWith(".yml") || f.endsWith(".yaml")
                || f.endsWith(".html") || f.endsWith(".jte") || f.endsWith(".jblade.html")
                || f.endsWith(".ftl") || f.endsWith(".mustache") || f.endsWith(".jsp")
                || f.endsWith(".css") || f.endsWith(".js") || f.endsWith(".scss")
                || f.endsWith(".svg") || f.endsWith(".png") || f.endsWith(".jpg")
                || f.endsWith(".woff") || f.endsWith(".woff2") || f.endsWith(".ico")) return "RELOADABLE";

        return "NON_RUNTIME";   // md / Dockerfile / .gitignore / CI / sql / jmx ...
    }

    static String normalizeReason(String diff) {
        // "新增 method A#n()" → "新增 method"
        String[] p = diff.split(" ");
        return p.length >= 2 ? p[0] + " " + p[1] : diff;
    }

    static void report(String title, Map<String, Integer> m) {
        int total = m.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("\n" + title + ":");
        m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e ->
                System.out.printf("   %-18s %5d   %5.1f%%  %s%n", e.getKey(), e.getValue(),
                        100.0 * e.getValue() / total, bar(100.0 * e.getValue() / total)));
        System.out.printf("   %-18s %5d%n", "合计", total);
    }

    static String bar(double pct) { return "█".repeat((int) Math.round(pct / 3)); }

    static void bump(Map<String, Integer> m, String k) { m.merge(k, 1, Integer::sum); }

    // ---------------- git ----------------

    static String git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo));
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        var p = pb.start();
        String out = read(p.getInputStream());
        if (p.waitFor() != 0) throw new Exception("git 失败: " + String.join(" ", args));
        return out;
    }

    static String showQuiet(String ref) {
        try { return git("show", ref); } catch (Exception e) { return null; }
    }

    static String showQuiet2(String... args) {
        try { return git(args).strip(); } catch (Exception e) { return null; }
    }

    static String read(InputStream in) throws Exception {
        var bos = new ByteArrayOutputStream();
        in.transferTo(bos);
        return bos.toString(StandardCharsets.UTF_8);
    }
}
