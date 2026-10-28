package app;
import java.nio.file.Path; import java.util.Map;
import gg.jte.*; import gg.jte.output.StringOutput; import gg.jte.resolve.DirectoryCodeResolver;
public class V3Err {
    public static void main(String[] a) {
        var e = TemplateEngine.create(new DirectoryCodeResolver(Path.of("src/main/jte")),
                Path.of("target/jte-classes"), ContentType.Html, V3Err.class.getClassLoader());
        try {
            var o = new StringOutput();
            e.render("errtest/typo.jte", Map.of("post", new Post(1,"t","b",false)), o);
        } catch (Exception ex) {
            System.out.println("异常类型: " + ex.getClass().getName());
            System.out.println("消息:");
            System.out.println(ex.getMessage());
        }
    }
}
