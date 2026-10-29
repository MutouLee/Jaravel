package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 批量赋值黑名单:{@code fill(request.all())} 时过滤掉这些列。
 *
 * <p>防权限提升攻击 —— 不加保护时,用户只要在表单里多提一个
 * {@code role=admin} 就能提权。APT 会在编译期校验这里写的列名存在。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Guarded {
    String[] value();
}
