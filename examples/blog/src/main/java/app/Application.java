package app;

import io.jaravel.Jaravel;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Blog 应用入口。
 *
 * <p>用户看不到 Spring —— {@code Jaravel.run} 内部才是 {@code SpringApplication.run}。
 *
 * <p>入口类必须在一个包里(不能在默认包),否则 Spring 的组件扫描会遍历整个
 * classpath。Jaravel 自动扫描 app / routes / database 三个约定包。
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        Jaravel.run(Application.class, args);
    }
}
