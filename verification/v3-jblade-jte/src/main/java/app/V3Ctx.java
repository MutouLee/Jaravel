package app;
import java.nio.file.Path; import java.util.Map;
import gg.jte.*; import gg.jte.output.StringOutput; import gg.jte.resolve.DirectoryCodeResolver;
public class V3Ctx {
    public static void main(String[] a) {
        var e = TemplateEngine.create(new DirectoryCodeResolver(Path.of("src/main/jte")),
                Path.of("target/jte-classes"), ContentType.Html, V3Ctx.class.getClassLoader());
        try {
            var o = new StringOutput();
            e.render("ctxtest/page.jte", Map.of("ctx", new JbContext()), o);
            System.out.println(o);
        } catch (Exception ex) {
            System.out.println("✗ " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            Throwable c = ex.getCause();
            while (c != null) { System.out.println("  caused by: " + c.getClass().getName() + ": " + c.getMessage()); c = c.getCause(); }
        }
    }
}
