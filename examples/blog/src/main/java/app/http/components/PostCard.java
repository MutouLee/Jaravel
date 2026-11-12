package app.http.components;

import app.models.Post;
import io.jaravel.view.Component;

/**
 * 类组件(docs/04 §1.7):列表页的一张文章卡片。
 *
 * <p>模板 {@code components/post-card.jblade.html} 的 {@code @params} 指向它,
 * 于是列表页只写 {@code <x-post-card :post="post" />} —— 卡片的结构与逻辑
 * 都在这个类与它的模板里,而不是散在列表页中间。
 *
 * <p>构造函数参数 {@code post} 就是标签的属性名。
 */
public class PostCard extends Component {

    public final Post post;

    public PostCard(Post post) {
        this.post = post;
    }

    @Override
    public String view() {
        return "components.post-card";
    }
}
