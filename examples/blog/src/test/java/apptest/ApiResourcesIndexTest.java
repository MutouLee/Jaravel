package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import app.http.resources.AuthorResource;
import app.http.resources.PostResource;
import io.jaravel.api.openapi.ResourceProperty;
import io.jaravel.api.openapi.ResourceSchemaIndex;

/**
 * 端到端:示例项目接上 {@code jaravel-api-processor} 之后,
 * 资源形状真的能从编译产物读回来(docs/12 §5)。
 *
 * <p>为什么这条测试要放在示例里:处理器的接线
 * ({@code annotationProcessorPaths})是 Maven 配置,不是 Java 代码 ——
 * 单元测试里手写一份索引永远测不到「用户的 pom 里漏了那一行」。
 * 而那正是最容易出、又最安静的一种错:所有类都在,只是没有索引。
 */
class ApiResourcesIndexTest {

    @Test
    @DisplayName("★ 示例的资源真的被 APT 索引了 —— 接线漏了时会静默缺失")
    void resourcesAreIndexedByTheProcessor() {
        var index = ResourceSchemaIndex.load();

        assertThat(index.failures()).isEmpty();

        var post = index.schemaOf(PostResource.class).orElseThrow();
        assertThat(post.properties()).extracting(ResourceProperty::name)
                .containsExactly("id", "title", "slug", "views",
                        "published_at", "author", "comment_count");

        assertThat(post.properties())
                .filteredOn(property -> property.name().equals("published_at"))
                .singleElement()
                .satisfies(property -> {
                    assertThat(property.type()).isEqualTo("string");
                    assertThat(property.optional()).isTrue();
                });

        assertThat(post.properties())
                .filteredOn(property -> property.name().equals("author"))
                .singleElement()
                .satisfies(property -> {
                    assertThat(property.type()).isEqualTo("object");
                    assertThat(property.ref())
                            .as("嵌套资源记下类名,生成器会把它解析成 $ref")
                            .isEqualTo("app.http.resources.AuthorResource");
                    assertThat(property.optional()).isTrue();
                });

        assertThat(index.schemaOf(AuthorResource.class)).isPresent();

        assertThat(post.toOpenApiSchema().get("required"))
                .as("put 的字段是无条件的;putWhen/putWhenLoaded 的字段不进 required")
                .isEqualTo(List.of("id", "title", "slug", "views"));
    }
}
