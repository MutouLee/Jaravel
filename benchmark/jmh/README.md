# JMH 基准(v0.9)

独立模块,**不进主构建**。用于量:

| 基准 | 量什么 |
|---|---|
| `RouteMatchBenchmark` | 静态命中 / 动态命中 / 300 条动态表上的未命中 |
| `EscapeBenchmark` | jte OWASP 上下文转义的纯字符串成本 |
| `QueryBuildBenchmark` | 查询构造器拼 SQL + 绑定值(不执行) |
| `TemplateRenderBenchmark` | 同一模板:运行时编译缓存 vs 构建期预编译 |

## 运行

```bash
# 先安装框架模块(jmh 模块直接依赖本地仓库里的 snapshot)
mvn -DskipTests install

cd benchmark/jmh
mvn package
java -jar target/jmh-benchmarks.jar -f 1 -wi 5 -i 10 -r 1s
```

只跑某一类:

```bash
java -jar target/jmh-benchmarks.jar 'RouteMatchBenchmark' -f 1 -wi 5 -i 10
```

`-f 1` 单 fork 以便和旧结果对比;**不要**在并行构建同一台机器上跑,
噪声会让 5% 的门禁失去意义。
