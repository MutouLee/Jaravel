@import java.util.List
@import app.Post
@param List<Post> posts

@template.layout(
    title = "文章列表",
    styles = @`<link rel="stylesheet" href="/css/posts.css">`,
    scripts = @`<script src="/js/posts.js"></script>`,
    content = @`
        <h1>文章列表</h1>

        @template.components.alert(
            type = "error",
            heading = "注意",
            slot = @`这是默认 slot 的内容`,
            footer = @`<a href="/help">帮助</a>`)

        @if(posts.isEmpty())
            <p>暂无内容</p>
        @else
            @for(var entry : posts)
                @template.components.postCard(post = entry, highlighted = entry.featured())
            @endfor
        @endif

        @for(int i = 0; i < posts.size(); i++)
            <li>索引 ${i} / 共 ${posts.size()} : ${posts.get(i).title()}</li>
        @endfor
    `)
