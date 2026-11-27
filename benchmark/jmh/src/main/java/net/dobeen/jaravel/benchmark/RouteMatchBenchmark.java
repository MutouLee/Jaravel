package net.dobeen.jaravel.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import net.dobeen.jaravel.routing.Route;
import net.dobeen.jaravel.routing.RouteCollection;

/**
 * 路由匹配:静态命中、动态命中、以及大路由表上的未命中(线性扫描的最坏情况)。
 *
 * <p>用 {@link Route#forTesting} 构造,避免把 lambda 反推的耗时混进匹配里 ——
 * 那个成本只发生一次,不在请求路径上。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RouteMatchBenchmark {

    private RouteCollection staticTable;
    private RouteCollection dynamicTable;
    private RouteCollection largeDynamicTable;

    @Setup
    public void setup() {
        staticTable = RouteCollection.of(routes(50, i -> "/static/" + i));
        dynamicTable = RouteCollection.of(routes(50, i -> "/api/v" + (i % 5) + "/items/" + i + "/{id}"));
        largeDynamicTable = RouteCollection.of(routes(300, i -> "/api/g" + (i % 10) + "/r" + i + "/{id}/edit"));
    }

    private static List<Route> routes(int count, java.util.function.IntFunction<String> uri) {
        var out = new ArrayList<Route>(count);
        for (int i = 0; i < count; i++) {
            out.add(Route.forTesting("GET", uri.apply(i), Controller.class, "index", List.of()));
        }
        return out;
    }

    @Benchmark
    public Object staticHit() {
        return staticTable.match("GET", "/static/25");
    }

    @Benchmark
    public Object dynamicHit() {
        return dynamicTable.match("GET", "/api/v0/items/25/42");
    }

    @Benchmark
    public Object largeTableMiss() {
        return largeDynamicTable.match("GET", "/nope");
    }

    public static final class Controller {
        public void index() {
        }
    }
}
