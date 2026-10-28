package app;
import java.util.List;
public record Post(long id, String title, String body, boolean featured) {
    public static List<Post> sample() {
        return List.of(new Post(1,"第一篇","正文 A",true),
                       new Post(2,"第二篇","正文 B",false),
                       new Post(3,"<script>xss</script>","正文 C",false));
    }
}
