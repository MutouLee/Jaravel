package app;
import java.nio.file.*; import java.util.Map;
import gg.jte.*; import gg.jte.output.StringOutput; import gg.jte.resolve.DirectoryCodeResolver;
public class V3Hot {
    public static void main(String[] a) throws Exception {
        var e = TemplateEngine.create(new DirectoryCodeResolver(Path.of("src/main/jte")),
                Path.of("target/jte-classes"), ContentType.Html, V3Hot.class.getClassLoader());
        Path f = Path.of("src/main/jte/hot.jte");

        var o1 = new StringOutput(); e.render("hot.jte", Map.of(), o1);
        System.out.println("第一次渲染 : " + o1.toString().trim());

        Files.writeString(f, "<p>版本二 —— 已修改</p>\n");
        long t0 = System.nanoTime();
        var o2 = new StringOutput(); e.render("hot.jte", Map.of(), o2);
        long t1 = System.nanoTime();
        System.out.println("改文件后渲染: " + o2.toString().trim());
        System.out.printf("重编译耗时 : %.0f ms%n", (t1-t0)/1e6);
        System.out.println(o2.toString().contains("版本二") ? "✓ 热重载开箱生效" : "✗ 未生效,需手动 invalidate");

        Files.writeString(f, "<p>版本一</p>\n");   // 还原
    }
}
