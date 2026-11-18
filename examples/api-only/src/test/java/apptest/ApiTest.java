package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import app.models.Post;
import io.jaravel.testing.FeatureTest;

class ApiTest extends FeatureTest {

    @Test
    @DisplayName("列表返回 data 数组")
    void index() {
        Post.create(Map.of("title", "第一篇", "body", "这是第一篇的正文", "published", true));

        var response = getJson("/api/posts");

        response.assertOk()
                .assertJsonPath("data.0.title", "第一篇")
                .assertJsonStructure("data.0", List.of("id", "title", "body"));
    }

    @Test
    @DisplayName("创建成功返回 201 与资源")
    void store() {
        var response = postJson("/api/posts", Map.of(
                "title", "新文章", "body", "这是一篇足够长的新文章正文内容", "published", true));

        response.assertStatus(201)
                .assertJsonPath("data.title", "新文章");
        assertThat(Post.query().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("校验失败返回 422 JSON")
    void validation() {
        postJson("/api/posts", Map.of("title", "", "body", "短"))
                .assertStatus(422)
                .assertJsonPath("errors.title.0", "标题 不能为空。");
    }

    @Test
    @DisplayName("不存在返回 404")
    void notFound() {
        getJson("/api/posts/999").assertNotFound();
    }
}
