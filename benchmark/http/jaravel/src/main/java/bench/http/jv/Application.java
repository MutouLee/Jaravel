package bench.http.jv;

import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.jaravel.Jaravel;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        Jaravel.run(Application.class, args);
    }
}
