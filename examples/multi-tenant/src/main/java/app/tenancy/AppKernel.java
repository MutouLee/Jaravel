package app.tenancy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.jaravel.http.Middleware;
import io.jaravel.spring.DefaultKernel;

/** 在默认 web/api 组与别名之上加一个 {@code tenant} 别名。 */
@Component
public class AppKernel extends DefaultKernel {

    @Override
    protected Map<String, Class<? extends Middleware>> aliases() {
        var aliases = new LinkedHashMap<>(super.aliases());
        aliases.put("tenant", ResolveTenant.class);
        return aliases;
    }
}
