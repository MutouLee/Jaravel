# 端到端 HTTP 吞吐对照(v0.9 验收)

同一个 `GET /ping` → `{"ok":true,"app":...}`:

| 目录 | 实现 |
|---|---|
| `bare/` | 裸 Spring MVC(`@RestController`) |
| `jaravel/` | Jaravel 路由 + 控制器 + `Response.json` |

两个都是独立 Maven 工程,不在根 reactor 里。

## 运行

```bash
# 先安装框架
cd ../.. && mvn -DskipTests install

# 构建两个应用
cd benchmark/http/bare && mvn -q package
cd ../jaravel && mvn -q package

# 分别启动(两个终端)
java -jar bare/target/bare-mvc-0.1.0-SNAPSHOT.jar --server.port=18000
java -jar jaravel/target/jaravel-http-bench-0.1.0-SNAPSHOT.jar
```

用 wrk(Docker,避免宿主机装工具):

```bash
docker run --rm williamyeh/wrk \
  -t4 -c100 -d15s http://host.docker.internal:18000/ping

docker run --rm williamyeh/wrk \
  -t4 -c100 -d15s http://host.docker.internal:18001/ping
```

交替各跑 3 次取中位;不要同时跑两个应用,也不要在并行构建时跑。
结果与机器/容器网络有关,只做**相对**对照,不要外推成绝对容量。
