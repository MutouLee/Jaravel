package io.jaravel.view;

/** 类组件的测试夹具:布尔简写与 kebab 属性名 */
public class TestButton extends Component {

    public final String userId;
    public final boolean disabled;

    public TestButton(String userId, boolean disabled) {
        this.userId = userId;
        this.disabled = disabled;
    }

    @Override
    public String view() {
        return "components.test-button";
    }
}
