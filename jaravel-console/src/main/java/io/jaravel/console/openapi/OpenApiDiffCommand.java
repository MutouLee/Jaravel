package io.jaravel.console.openapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jaravel.console.Argument;
import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.ConsoleException;
import io.jaravel.console.Option;

/**
 * {@code jaravel openapi:diff}(docs/12 §5、docs/13 §1)。
 *
 * <pre>
 * jaravel openapi:diff public/openapi.json /tmp/openapi.json
 * jaravel openapi:diff base.json current.json --json
 * jaravel openapi:diff base.json current.json --fail-on=any
 * </pre>
 *
 * <h2>★ 为什么是离线命令,以及为什么它收**两个文件**</h2>
 * <p>当前文档要由 {@code openapi:generate} 从路由表生成,而路由表在应用进程里。
 * 这个命令如果自己去生成,就变成在线命令了 —— 而一条「比两份已经生成好的
 * JSON」的命令根本不需要容器。CI 里的用法正好是两段:
 *
 * <pre>
 * jaravel openapi:generate --stdout &gt; /tmp/openapi.json
 * jaravel openapi:diff public/openapi.json /tmp/openapi.json
 * </pre>
 *
 * <p>基线是**提交在仓库里的那一份**。这样 review 时看到的 diff 就是
 * 这次发布对 API 契约的实际影响,而不是一份没人读的 JSON 文本差异。
 *
 * <h2>退出码</h2>
 * <ul>
 *   <li>0 —— 没有达到 {@code --fail-on} 的门槛;</li>
 *   <li>1 —— 达到门槛(默认:有破坏性变更),或参数/文件有问题。</li>
 * </ul>
 * {@code --fail-on=any} 让「任何变化」都失败(适合要求严格 review 的仓库);
 * {@code --fail-on=none} 只报告不拦截。
 *
 * <h2>判定规则</h2>
 * <p>见 {@link OpenApiDiff} 的类注释。一句话:
 * <b>按旧文档生成的客户端,照着旧文档发请求、读响应,还能不能工作。</b>
 */
@Command(name = "openapi:diff", description = "对比两份 OpenAPI 文档,检测破坏性变更", offline = true)
public final class OpenApiDiffCommand extends ConsoleCommand {

    @Argument(description = "基线文档(通常是提交在仓库里的那一份)")
    String base;

    @Argument(description = "当前文档(新生成的)")
    String current;

    @Option(names = "--json", description = "只把结果输出成 JSON(给 CI 用)")
    boolean json;

    @Option(names = "--fail-on",
            description = "什么时候返回 1:breaking(默认)/ any / none",
            defaultValue = "breaking")
    String failOn;

    /** 只用于读写 JSON。不复用应用的 ObjectMapper —— 理由与 openapi:generate 相同 */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public int handle() {
        FailOn gate = FailOn.parse(failOn);

        JsonNode baseDocument = read(base, "基线");
        JsonNode currentDocument = read(current, "当前");

        List<OpenApiDiff.Change> changes = OpenApiDiff.compare(baseDocument, currentDocument);

        List<OpenApiDiff.Change> breaking = filter(changes, OpenApiDiff.Severity.BREAKING);
        List<OpenApiDiff.Change> nonBreaking = filter(changes, OpenApiDiff.Severity.NON_BREAKING);
        List<OpenApiDiff.Change> added = filter(changes, OpenApiDiff.Severity.ADDED);

        if (json) {
            // ★ 只有这一份 JSON 进 stdout —— 与 route:list --json、
            // openapi:generate --stdout 同一条规矩:多一行就是 jq: parse error
            line(encode(breaking, nonBreaking, added));
        } else {
            render(base, current, breaking, nonBreaking, added, gate);
        }

        return gate.fails(changes, breaking) ? 1 : 0;
    }

    // ================= 输出 =================

    private void render(String baseFile, String currentFile,
                        List<OpenApiDiff.Change> breaking,
                        List<OpenApiDiff.Change> nonBreaking,
                        List<OpenApiDiff.Change> added,
                        FailOn gate) {
        line("基线  " + baseFile);
        line("当前  " + currentFile);
        newLine();

        if (breaking.isEmpty() && nonBreaking.isEmpty() && added.isEmpty()) {
            success("两份文档没有差异。");
            return;
        }

        section(breaking, "破坏性变更", OpenApiDiff.Severity.BREAKING);
        section(added, "新增", OpenApiDiff.Severity.ADDED);
        section(nonBreaking, "非破坏性变更", OpenApiDiff.Severity.NON_BREAKING);

        if (!breaking.isEmpty()) {
            failure(breaking.size() + " 项破坏性变更 —— 按旧文档生成的客户端可能出错。");
            if (gate == FailOn.NONE) {
                warn("--fail-on=none:这次不因此返回非零。");
            }
        } else {
            success("没有破坏性变更。");
            if (gate == FailOn.ANY) {
                warn("--fail-on=any:有变化,仍然返回 1。");
            }
        }
    }

    private void section(List<OpenApiDiff.Change> changes, String title,
                         OpenApiDiff.Severity severity) {
        if (changes.isEmpty()) return;

        switch (severity) {
            case BREAKING -> failure(title + " " + changes.size());
            case ADDED -> success(title + " " + changes.size());
            case NON_BREAKING -> comment(title + " " + changes.size());
        }
        for (OpenApiDiff.Change change : changes) {
            line("  " + change.location());
            line("    " + change.message());
        }
        newLine();
    }

    private String encode(List<OpenApiDiff.Change> breaking,
                          List<OpenApiDiff.Change> nonBreaking,
                          List<OpenApiDiff.Change> added) {
        var document = new LinkedHashMap<String, Object>();
        document.put("base", base);
        document.put("current", current);
        document.put("breaking", entries(breaking));
        document.put("nonBreaking", entries(nonBreaking));
        document.put("added", entries(added));

        var summary = new LinkedHashMap<String, Object>();
        summary.put("breaking", breaking.size());
        summary.put("nonBreaking", nonBreaking.size());
        summary.put("added", added.size());
        document.put("summary", summary);

        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new ConsoleException("diff 结果序列化失败:" + e.getOriginalMessage(), e);
        }
    }

    private List<Map<String, Object>> entries(List<OpenApiDiff.Change> changes) {
        var out = new ArrayList<Map<String, Object>>(changes.size());
        for (OpenApiDiff.Change change : changes) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("location", change.location());
            entry.put("message", change.message());
            out.add(entry);
        }
        return out;
    }

    private List<OpenApiDiff.Change> filter(List<OpenApiDiff.Change> changes,
                                            OpenApiDiff.Severity severity) {
        return changes.stream().filter(change -> change.severity() == severity).toList();
    }

    // ================= 读文件 =================

    private JsonNode read(String file, String role) {
        if (file == null || file.isBlank()) {
            throw new ConsoleException("缺少" + role + "文档的路径。\n"
                    + "  用法:jaravel openapi:diff <基线.json> <当前.json>");
        }

        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            throw new ConsoleException("""
                    读不了%s文档 %s:文件不存在。

                      基线通常是仓库里提交的那一份:
                        jaravel openapi:generate --output=public/openapi.json
                      当前文档是新生成的:
                        jaravel openapi:generate --stdout > /tmp/openapi.json
                        jaravel openapi:diff public/openapi.json /tmp/openapi.json"""
                    .formatted(role, file));
        }

        JsonNode document;
        try {
            document = JSON.readTree(path.toFile());
        } catch (IOException e) {
            String detail = e instanceof JsonProcessingException processing
                    ? processing.getOriginalMessage()
                    : e.getMessage();
            throw new ConsoleException(
                    "读不了%s文档 %s(不是合法的 JSON):%s".formatted(role, file, detail), e);
        }

        if (document == null || !document.isObject()) {
            throw new ConsoleException(role + "文档 " + file + " 的顶层不是 JSON 对象。");
        }
        if (!document.path("paths").isObject()) {
            throw new ConsoleException("""
                    %s文档 %s 里没有 paths 对象 —— 这不是一份 OpenAPI 文档。
                      生成一份:jaravel openapi:generate""".formatted(role, file));
        }
        return document;
    }

    // ================= --fail-on =================

    private enum FailOn {
        BREAKING,
        ANY,
        NONE;

        static FailOn parse(String raw) {
            if (raw == null || raw.isBlank()) return BREAKING;

            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "breaking" -> BREAKING;
                case "any" -> ANY;
                case "none" -> NONE;
                default -> throw new ConsoleException(
                        "--fail-on 只认 breaking / any / none,收到:" + raw);
            };
        }

        boolean fails(List<OpenApiDiff.Change> all, List<OpenApiDiff.Change> breaking) {
            return switch (this) {
                case BREAKING -> !breaking.isEmpty();
                case ANY -> !all.isEmpty();
                case NONE -> false;
            };
        }
    }
}
