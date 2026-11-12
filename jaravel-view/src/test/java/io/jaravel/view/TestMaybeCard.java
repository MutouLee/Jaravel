package io.jaravel.view;

/** 声明了 shouldRender 的类组件夹具 */
public class TestMaybeCard extends Component {

    public final String title;
    public final boolean visible;

    public TestMaybeCard(String title, boolean visible) {
        this.title = title;
        this.visible = visible;
    }

    @Override
    public boolean shouldRender() {
        return visible;
    }

    @Override
    public String view() {
        return "components.test-maybe";
    }
}
