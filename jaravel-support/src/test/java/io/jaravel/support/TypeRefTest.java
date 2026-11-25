package io.jaravel.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TypeRef} —— 泛型参数捕获。
 *
 * <p>它的全部价值在于「擦除之后还能知道元素类型」,所以测的就是这件事,
 * 外加一条:**用错写法时报错说得清**。拿不到类型参数的 TypeRef 如果静默
 * 退化成 Object,症状会出现在很远的地方(缓存读出来是一堆 Map)。
 */
class TypeRefTest {

    @Test
    @DisplayName("匿名子类捕获到完整的泛型类型")
    void capturesTheGenericType() {
        var ref = new TypeRef<List<String>>() {};

        assertThat(ref.type().getTypeName()).isEqualTo("java.util.List<java.lang.String>");
        assertThat(ref.toString()).isEqualTo("TypeRef<java.util.List<java.lang.String>>");
    }

    @Test
    @DisplayName("嵌套泛型也能捕获")
    void capturesNestedGenerics() {
        var ref = new TypeRef<Map<String, List<Integer>>>() {};

        assertThat(ref.type().getTypeName())
                .isEqualTo("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>");
    }

    @Test
    @DisplayName("具名子类也算 —— 同一个类型反复用时值得起个名字")
    void namedSubclassWorks() {
        assertThat(new StringList().type()).isEqualTo(new TypeRef<List<String>>() {}.type());
    }

    static class StringList extends TypeRef<List<String>> {}

    @Test
    @DisplayName("相同类型的两个 TypeRef 相等 —— 可以当 map 的键")
    void equalityIsByType() {
        assertThat(new TypeRef<List<String>>() {})
                .isEqualTo(new TypeRef<List<String>>() {})
                .isNotEqualTo(new TypeRef<List<Integer>>() {});
        assertThat(new TypeRef<List<String>>() {}.hashCode())
                .isEqualTo(new TypeRef<List<String>>() {}.hashCode());
    }

    @Test
    @DisplayName("★ 裸类型报错,并给出正确的写法")
    @SuppressWarnings("rawtypes")
    void rawUsageFailsLoudly() {
        assertThatThrownBy(() -> new TypeRef() {})
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("new TypeRef<List<Post>>() {}");
    }

    @Test
    @DisplayName("★ 隔了一层的子类拿不到类型 —— 签名里是父类的名字")
    void subclassOfASubclassFailsLoudly() {
        assertThatThrownBy(Deeper::new)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能往下继承一层");
    }

    static final class Deeper extends StringList {}
}
