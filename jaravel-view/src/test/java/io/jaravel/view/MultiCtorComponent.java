package io.jaravel.view;

/** 有多个 public 构造函数 —— 转写器无法决定用哪一个,应当报错 */
public class MultiCtorComponent extends Component {

    public MultiCtorComponent() {
    }

    public MultiCtorComponent(String value) {
    }

    @Override
    public String view() {
        return "components.multi";
    }
}
