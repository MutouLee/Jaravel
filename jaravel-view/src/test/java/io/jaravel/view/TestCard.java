package io.jaravel.view;

/** 类组件的测试夹具:两个构造参数,模板里通过 {@code self} 访问 */
public class TestCard extends Component {

    public final String title;
    public final int count;

    public TestCard(String title, int count) {
        this.title = title;
        this.count = count;
    }

    public String upperTitle() {
        return title == null ? "" : title.toUpperCase();
    }

    @Override
    public String view() {
        return "components.test-card";
    }
}
