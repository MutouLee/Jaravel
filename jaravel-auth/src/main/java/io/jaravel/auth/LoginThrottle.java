package io.jaravel.auth;

import java.time.Duration;
import java.util.Map;

import io.jaravel.cache.RateLimiter;
import io.jaravel.http.HttpException;

/**
 * 登录失败节流。
 *
 * <pre>{@code
 * throttle.guardAttempt(email, request.ip());        // 超限则抛 429
 * if (Auth.attempt(credentials)) {
 *     throttle.clear(email, request.ip());           // 成功后清零
 * } else {
 *     throttle.recordFailure(email, request.ip());
 * }
 * }</pre>
 *
 * <h2>按 email + IP 的组合计数</h2>
 * <p>这是 docs/08 §6.6 指定的方式,理由是两种攻击形态要分别挡住:
 * <ul>
 *   <li><b>撞库</b>(一个账号试很多密码)—— 按 email 计数挡住;</li>
 *   <li><b>喷洒</b>(很多账号各试一个常见密码)—— 按 IP 计数挡住。</li>
 * </ul>
 * <p>只按其中一个都有明显的绕过方式。
 *
 * <h2>只记失败,不记成功</h2>
 * <p>成功登录立刻清零。否则一个正常用户在一分钟内登录几次
 * (多设备、会话过期)就会被锁 —— 而节流本该只针对攻击行为。
 *
 * <h2>这个机制本身的副作用:拒绝服务</h2>
 * <p>攻击者知道某人的邮箱后,可以故意输错密码把对方锁住。所以:
 * <ul>
 *   <li>锁定是**临时**的(默认 1 分钟窗口),不是「锁定账号等管理员解锁」;</li>
 *   <li>按 email 的阈值比按 IP 的宽松 —— 让针对单个账号的锁定更难触发。</li>
 * </ul>
 * <p>这个取舍没有完美答案,但「永久锁定」肯定是错的。
 */
public class LoginThrottle {

    private final RateLimiter limiter;
    private final int maxPerAccount;
    private final int maxPerIp;
    private final Duration window;

    public LoginThrottle(RateLimiter limiter) {
        this(limiter, 5, 20, Duration.ofMinutes(1));
    }

    /**
     * @param maxPerAccount 单个账号在窗口内允许的失败次数(默认 5)
     * @param maxPerIp      单个 IP 在窗口内允许的失败次数(默认 20)——
     *                      比账号阈值高,因为一个 IP 后面可能有很多正常用户(NAT)
     */
    public LoginThrottle(RateLimiter limiter, int maxPerAccount, int maxPerIp,
                         Duration window) {
        this.limiter = limiter;
        this.maxPerAccount = maxPerAccount;
        this.maxPerIp = maxPerIp;
        this.window = window;
    }

    /**
     * 尝试登录前检查。
     *
     * @throws HttpException 429,带 Retry-After
     */
    public void guardAttempt(String identifier, String ip) {
        if (limiter.tooManyAttempts(accountKey(identifier), maxPerAccount)) {
            throw lockout(accountKey(identifier), "该账号");
        }
        if (limiter.tooManyAttempts(ipKey(ip), maxPerIp)) {
            throw lockout(ipKey(ip), "该 IP");
        }
    }

    public boolean isLocked(String identifier, String ip) {
        return limiter.tooManyAttempts(accountKey(identifier), maxPerAccount)
            || limiter.tooManyAttempts(ipKey(ip), maxPerIp);
    }

    /** 登录失败时调用 */
    public void recordFailure(String identifier, String ip) {
        limiter.hit(accountKey(identifier), window);
        limiter.hit(ipKey(ip), window);
    }

    /**
     * 登录成功时调用 —— 清零。
     *
     * <p>**同时清 IP 计数**:一个正常用户在共享 IP 上成功登录,
     * 说明这个 IP 不是在被用来喷洒。不清的话,办公室里第 21 个人
     * 会因为前面 20 次正常的输错而登不进来。
     */
    public void clear(String identifier, String ip) {
        limiter.clear(accountKey(identifier));
        limiter.clear(ipKey(ip));
    }

    public long remainingAttempts(String identifier) {
        return limiter.remaining(accountKey(identifier), maxPerAccount);
    }

    public long lockoutSeconds(String identifier, String ip) {
        return Math.max(
                limiter.availableInSeconds(accountKey(identifier)),
                limiter.availableInSeconds(ipKey(ip)));
    }

    private HttpException lockout(String key, String subject) {
        long retryAfter = limiter.availableInSeconds(key);
        return new HttpException(429,
                "登录失败次数过多(" + subject + "),请在 " + retryAfter + " 秒后重试。",
                Map.of("Retry-After", String.valueOf(retryAfter)));
    }

    /**
     * 账号维度的键。
     *
     * <p>**小写化**:{@code A@x.com} 与 {@code a@x.com} 在多数系统里是同一个
     * 账号,不统一的话攻击者只要改大小写就能重置计数器。
     */
    private static String accountKey(String identifier) {
        return "login:account:" + (identifier == null ? "" : identifier.toLowerCase());
    }

    private static String ipKey(String ip) {
        return "login:ip:" + (ip == null ? "unknown" : ip);
    }

    public int maxPerAccount() {
        return maxPerAccount;
    }

    public int maxPerIp() {
        return maxPerIp;
    }

    public Duration window() {
        return window;
    }
}
