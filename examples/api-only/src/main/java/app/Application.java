package app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

import net.dobeen.jaravel.Jaravel;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        Jaravel.run(Application.class, args);
    }
}
