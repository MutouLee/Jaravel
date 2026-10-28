package app;
import java.nio.file.Path; import java.util.Map;
import gg.jte.*; import gg.jte.output.StringOutput; import gg.jte.resolve.DirectoryCodeResolver;
public class V3Frag {
    public static void main(String[] a) {
        var e = TemplateEngine.create(new DirectoryCodeResolver(Path.of("src/main/jte")),
                Path.of("target/jte-classes"), ContentType.Html, V3Frag.class.getClassLoader());
        try { var o = new StringOutput(); e.render("fragtest/page.jte", Map.of(), o); System.out.println("渲染成功:\n"+o); }
        catch (Exception ex) { System.out.println("✗ 失败: " + ex.getMessage()); }
    }
}
