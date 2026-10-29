package io.jaravel.eloquent.meta;

/**
 * 列的类型转换:模型里的类型 ↔ 数据库里的类型。
 *
 * <pre>{@code
 * public class Money implements Cast<BigDecimal, Long> {
 *     public BigDecimal fromDatabase(Long cents) { return BigDecimal.valueOf(cents, 2); }
 *     public Long toDatabase(BigDecimal yuan)    { return yuan.movePointRight(2).longValueExact(); }
 * }
 * // 用法:@Cast(Money.class) public BigDecimal price;
 * }</pre>
 *
 * <p>实现类必须有无参构造(APT 会校验)—— 转换器是无状态的纯函数。
 *
 * @param <M> 模型里的类型
 * @param <D> 数据库里的类型
 */
public interface Cast<M, D> {

    M fromDatabase(D raw);

    D toDatabase(M value);

    /** 标注在字段上以启用转换 */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)
    @interface Use {
        Class<? extends Cast<?, ?>> value();

        /** 传给转换器构造函数的参数(目前仅支持字符串) */
        String[] args() default {};
    }
}
