package app;

import java.nio.file.Path;
import java.util.Map;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;

public class V3Probe {
    public static void main(String[] args) {
        var resolver = new DirectoryCodeResolver(Path.of("src/main/jte"));
        var engine = TemplateEngine.create(resolver, Path.of("target/jte-classes"),
                                           ContentType.Html, V3Probe.class.getClassLoader());
        var out = new StringOutput();
        long t0 = System.nanoTime();
        engine.render("posts/index.jte", Map.of("posts", Post.sample()), out);
        long t1 = System.nanoTime();
        System.out.println(out);
        System.out.printf("%n--- 首次渲染(含编译)耗时: %.0f ms ---%n", (t1-t0)/1e6);

        var out2 = new StringOutput();
        long t2 = System.nanoTime();
        engine.render("posts/index.jte", Map.of("posts", Post.sample()), out2);
        long t3 = System.nanoTime();
        System.out.printf("--- 二次渲染耗时: %.3f ms ---%n", (t3-t2)/1e6);
    }
}
