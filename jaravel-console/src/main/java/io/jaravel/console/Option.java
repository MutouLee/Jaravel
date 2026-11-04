package io.jaravel.console;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一个选项(docs/13 §3):{@code -f} / {@code --force} / {@code --chunk=500}。
 *
 * <pre>{@code
 * @Option(names = {"-m", "--migration"}, description = "同时生成迁移")
 * boolean migration;
 *
 * @Option(names = "--table", description = "要修改的表名")
 * String table;
 * }</pre>
 *
 * <p>{@code boolean} 字段是开关(出现即 true);其他类型要带值。
 * 这个区分由字段类型决定而不是另加一个 {@code flag = true} 属性 ——
 * 多一个可以写错的地方,而类型已经说清楚了。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Option {

    String[] names();

    String description() default "";

    /** 未给出时的值。空串表示「不设」(boolean 则为 false) */
    String defaultValue() default "";
}
