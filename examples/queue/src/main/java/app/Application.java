package app;

import io.jaravel.Jaravel;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        Jaravel.run(Application.class, args);
    }
}
