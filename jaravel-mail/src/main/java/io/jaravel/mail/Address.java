package io.jaravel.mail;

/**
 * 一个邮件地址,可带显示名。
 *
 * <pre>{@code
 * Address.of("a@b.com")
 * Address.of("a@b.com", "张三")
 * }</pre>
 *
 * <h2>校验到什么程度</h2>
 * <p>只做**最小的结构检查**:非空、恰好一个 {@code @}、两侧都非空、
 * 不含空白与换行。
 *
 * <p>不做 RFC 5322 的完整校验。那个规范允许的东西远超直觉
 * (带引号的本地部分、注释、IP 字面量域名),而一个「严格」的
 * 正则几乎必然会拒掉某些合法地址 —— 而拒掉一个真实用户的邮箱
 * 比放过一个无效地址糟得多:后者由邮件服务器拒信告知,
 * 前者是一个「我的邮箱不能用」的工单。
 *
 * <h2>为什么必须拒掉换行</h2>
 * <p>这是唯一一条**安全**检查,不是格式洁癖。
 *
 * <p>SMTP 的头部以 CRLF 分隔。一个地址里带 {@code \r\n} 的话,
 * 后面的内容会被接收方当成**新的头部** —— 于是
 * {@code "a@b.com\r\nBcc: attacker@evil.com"} 变成一个真实的
 * 密送头。这是邮件头注入,一类有名的漏洞。
 *
 * <p>所以这个检查在构造时做,而不是留给 transport ——
 * 留给 transport 的话每个驱动都得记得做一遍,而漏掉的那个
 * 就是漏洞所在。
 *
 * @param email 地址
 * @param name  显示名,可为 null
 */
public record Address(String email, String name) {

    public Address {
        email = validate(email);
        name = name == null || name.isBlank() ? null : sanitizeName(name);
    }

    public static Address of(String email) {
        return new Address(email, null);
    }

    public static Address of(String email, String name) {
        return new Address(email, name);
    }

    private static String validate(String email) {
        if (email == null || email.isBlank()) {
            throw new InvalidAddressException("邮件地址不能为空。");
        }
        String trimmed = email.trim();

        if (containsLineBreak(trimmed)) {
            throw new InvalidAddressException("""
                    邮件地址里不能有换行:%s
                      SMTP 的头部以 CRLF 分隔,地址里的换行会让后面的内容
                      被当成**新的头部** —— 那是邮件头注入
                      (比如 "a@b.com\\r\\nBcc: attacker@evil.com")。
                      这个检查在构造地址时做,不留给驱动 ——
                      留给驱动的话漏掉的那一个就是漏洞所在。"""
                    .formatted(describe(trimmed)));
        }
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidAddressException(
                    "邮件地址里不能有空白字符:\"%s\"".formatted(trimmed));
        }

        int at = trimmed.indexOf('@');
        if (at <= 0 || at != trimmed.lastIndexOf('@') || at == trimmed.length() - 1) {
            throw new InvalidAddressException("""
                    "%s" 不像一个邮件地址 —— 需要恰好一个 @,且两侧都非空。
                      这里只做最小的结构检查,不做 RFC 5322 的完整校验:
                      那个规范允许的东西远超直觉,而一个「严格」的正则
                      几乎必然拒掉某些合法地址 —— 而拒掉一个真实用户的
                      邮箱,比放过一个无效地址糟得多。"""
                    .formatted(trimmed));
        }
        return trimmed;
    }

    /**
     * 显示名里的换行同样危险 —— 它也会进头部。
     *
     * <p>但这里**清理**而不是报错:显示名常常来自用户资料
     * (昵称、公司名),而一个带换行的昵称不该让「发不出邮件」。
     * 地址不同:地址是程序给的,带换行一定是 bug 或攻击。
     */
    private static String sanitizeName(String name) {
        return name.replaceAll("[\\r\\n]", " ").trim();
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    /** 报错时把换行显形,否则消息里看不出问题在哪 */
    private static String describe(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }

    public boolean hasName() {
        return name != null;
    }

    /** RFC 形式:{@code 张三 <a@b.com>} 或 {@code a@b.com} */
    public String format() {
        return hasName() ? "%s <%s>".formatted(name, email) : email;
    }

    @Override
    public String toString() {
        return format();
    }

    /** 地址非法。**不可重试** —— 地址不改,再试也一样 */
    public static class InvalidAddressException extends IllegalArgumentException {
        public InvalidAddressException(String message) {
            super(message);
        }
    }
}
