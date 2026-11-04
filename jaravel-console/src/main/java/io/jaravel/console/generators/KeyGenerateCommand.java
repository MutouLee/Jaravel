package io.jaravel.console.generators;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleException;
import io.jaravel.console.Option;

/**
 * {@code jaravel key:generate}(docs/13 §2.5)。
 *
 * <p>生成 APP_KEY 并写进 {@code .env}。会话加密与敏感列加密都用它。
 *
 * <h2>★ 为什么它必须是**离线**命令</h2>
 * <p>没有 APP_KEY 的应用**起不来**(会话与 crypt 在装配时就要它)。
 * 一条「要先把应用跑起来」的命令在这里是死循环 ——
 * 所以它不能走命令通道,必须在 CLI 这个进程里跑完。
 *
 * <p>代价:密钥的生成在这里重写了一遍(32 字节 SecureRandom + base64),
 * 而权威定义在 {@code io.jaravel.core.crypt.Encrypter} ——
 * 让 jaravel-console 依赖 jaravel-core 只为了三行代码不值得。
 * **那份重复由一条跨模块测试钉住**:starter 的测试会拿这里生成的 key
 * 去喂 {@code Encrypter.fromBase64},格式一旦漂掉它就红。
 *
 * <h2>⚠ 换掉已有的 key 会让所有已加密的数据解不开</h2>
 * <p>会话(用户全部掉线)、加密列(永久读不出来)。所以已有非空 APP_KEY 时
 * **拒绝覆盖**,除非 {@code --force}。
 */
@Command(name = "key:generate", description = "生成 APP_KEY 并写进 .env", offline = true)
public final class KeyGenerateCommand extends GeneratorCommand {

    /** 与 Encrypter.KEY_BYTES 一致:AES-256 */
    private static final int KEY_BYTES = 32;

    private static final String ENV_FILE = ".env";
    private static final String KEY_NAME = "APP_KEY";

    @Option(names = "--show", description = "只打印,不写文件")
    boolean show;

    @Override
    public int handle() {
        String key = generate();

        if (show) {
            line(key);
            return 0;
        }

        Path env = projectRoot().resolve(ENV_FILE);
        if (!Files.isRegularFile(env)) {
            throw new ConsoleException("""
                    找不到 %s。

                      key:generate 把密钥写进 .env,所以那个文件要先存在。
                      只想看一个新密钥:key:generate --show""".formatted(relative(env)));
        }

        List<String> lines = read(env);
        int existing = indexOfKey(lines);

        if (existing >= 0 && hasValue(lines.get(existing)) && !force) {
            throw new ConsoleException("""
                    %s 里已经有 APP_KEY 了。

                      ⚠ 换掉它会让**所有已加密的数据解不开**:
                        · 会话 —— 所有用户掉线;
                        · 加密列 —— 永久读不出来(没有旧密钥就没有退路)。

                      确实要换:加 --force。
                      只想看一个新密钥:key:generate --show""".formatted(relative(env)));
        }

        if (existing >= 0) {
            lines.set(existing, KEY_NAME + "=" + key);
        } else {
            lines.add(KEY_NAME + "=" + key);
        }
        write(env, lines);

        success("APP_KEY 已写入 " + relative(env));
        if (force) {
            warn("旧密钥已被替换 —— 用它加密过的会话与数据现在解不开了。");
        }
        return 0;
    }

    /**
     * 32 字节随机 + base64,带 {@code base64:} 前缀。
     *
     * <p>前缀是 Encrypter 认的形式之一(它也收裸 base64)。带上前缀是因为
     * 它让 .env 里那一行**自解释** —— 看到的人知道那不是一段被截断的明文。
     */
    public static String generate() {
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return "base64:" + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 找到 {@code APP_KEY=} 那一行。
     *
     * <p>**跳过注释行**:一个 `.env.example` 抄过来的文件里常常有
     * {@code # APP_KEY=} —— 改那一行等于没写。
     */
    private int indexOfKey(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).stripLeading();
            if (line.startsWith("#")) continue;
            if (line.startsWith(KEY_NAME + "=")) return i;
        }
        return -1;
    }

    private boolean hasValue(String line) {
        int equals = line.indexOf('=');
        return equals >= 0 && !line.substring(equals + 1).isBlank();
    }

    private List<String> read(Path env) {
        try {
            return new java.util.ArrayList<>(Files.readAllLines(env, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConsoleException("读不了 " + relative(env) + ":" + e.getMessage(), e);
        }
    }

    private void write(Path env, List<String> lines) {
        try {
            Files.writeString(env, String.join(System.lineSeparator(), lines)
                            + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConsoleException("写不了 " + relative(env) + ":" + e.getMessage(), e);
        }
    }
}
