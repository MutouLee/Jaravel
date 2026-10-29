package io.jaravel.view;

import java.util.List;

/**
 * 测试用模型。
 *
 * <p><b>必须是 public 顶层类</b>:jte 生成的模板类位于
 * {@code gg.jte.generated.ondemand.*} 包,看不见 package-private 的类型。
 * 这也反映了真实约束 —— 用户的模型本来就是 public 的。
 *
 * <p>标量列用 public 字段,即 ADR-010 采纳的方案 E'。
 */
public class TestPost {

    public Long id;
    public String title;
    public String body;
    public boolean featured;

    public TestPost(Long id, String title, String body, boolean featured) {
        this.id = id;
        this.title = title;
        this.body = body;
        this.featured = featured;
    }

    public String excerpt() {
        return body != null && body.length() > 5 ? body.substring(0, 5) + "…" : body;
    }

    public static List<TestPost> sample() {
        return List.of(new TestPost(1L, "第一篇", "正文甲", true),
                       new TestPost(2L, "第二篇", "正文乙", false),
                       new TestPost(3L, "<script>xss</script>", "正文丙", false));
    }
}
