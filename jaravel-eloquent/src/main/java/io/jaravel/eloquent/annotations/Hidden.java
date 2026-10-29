package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 序列化时排除该字段。
 *
 * <p>password / token 这类字段必须标上 —— docs/08 §7.2 的安全清单里
 * 「@Hidden 的 password 绝不出现在任何 JSON 序列化中」有专门的测试。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
public @interface Hidden {
}
