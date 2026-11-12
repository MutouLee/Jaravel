package io.jaravel.view;

/**
 * 类组件(docs/04 §1.7)。
 *
 * <pre>{@code
 * public class PostCard extends Component {
 *     public final Post post;
 *     public final boolean highlighted;
 *
 *     public PostCard(Post post, boolean highlighted) {
 *         this.post = post;
 *         this.highlighted = highlighted;
 *     }
 *
 *     public String excerpt() { return Str.of(post.body).limit(120).toString(); }
 *
 *     @Override public String view() { return "components.post-card"; }
 *     @Override public boolean shouldRender() { return post.isPublished(); }
 * }
 * }</pre>
 *
 * <p>模板 {@code components/post-card.jblade.html} 的第一行声明
 * {@code @params(PostCard self)},于是模板里用 {@code self.post}、{@code self.excerpt()}
 * —— **普通 Java,编译期类型检查**。
 *
 * <h2>调用方的标签怎么变成它</h2>
 * <p>{@code <x-post-card :post="post" highlighted />} 由转写器根据组件模板的
 * {@code @params} 读到类名与构造函数参数,生成:
 *
 * <pre>
 * &#64;template.components.post-card(ctx = ctx, self = new PostCard(post, true))
 * </pre>
 *
 * <p>属性名与构造参数按名字配对(kebab 与 camel 两种写法都认);
 * 缺属性、多属性都是**转写期错误**并指回模板行 —— 属性类型不对则由 javac 报。
 *
 * <h2>这一版的限制</h2>
 * <ul>
 *   <li>只能自闭合({@code <x-post-card … />});带 body 或具名 slot 的类组件
 *       需要一个把 {@code Content} 传进构造器的约定,还没做;</li>
 *   <li>{@link #shouldRender()} 还没有在标签调用里强制执行 —— 模板标签是
 *       同步编译的,先构造再判断会构造两次。手工渲染时可以用;</li>
 *   <li>{@link #view()} 不参与标签解析:标签 {@code x-post-card} 永远对应
 *       {@code components/post-card.jblade.html}(与匿名组件同一个约定)。</li>
 * </ul>
 */
public abstract class Component {

    /** 手工渲染时用的视图名;标签调用按约定路径解析,不读这个方法 */
    public abstract String view();

    /** 要不要渲染。手工渲染时调用;标签调用尚未强制执行(见类注释) */
    public boolean shouldRender() {
        return true;
    }
}
