import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简 HTTP 压测器,单文件运行:
 *
 * <pre>
 * java benchmark/http/LoadGen.java http://localhost:18000/ping 64 10
 * </pre>
 *
 * <p>用 JDK 的 HttpClient + 固定线程池,避免 Docker Desktop 的网络开销
 * 把 wrk 的读数搅成 16k / 5k / 1.4k 那种不可用的噪声。两个应用用**同一个**
 * 生成器交替跑,只比较相对值。
 */
public class LoadGen {

    public static void main(String[] args) throws Exception {
        String url = args[0];
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        var client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        var request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        var hits = new AtomicLong();
        var errors = new AtomicLong();
        long end = System.nanoTime() + seconds * 1_000_000_000L;

        var pool = Executors.newFixedThreadPool(workers);
        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                while (System.nanoTime() < end) {
                    try {
                        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        if (response.statusCode() == 200) hits.incrementAndGet();
                        else errors.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(seconds + 15L, TimeUnit.SECONDS);

        System.out.printf("workers=%d hits=%d errors=%d requests/sec=%.1f%n",
                workers, hits.get(), errors.get(), hits.get() / (double) seconds);
    }
}
