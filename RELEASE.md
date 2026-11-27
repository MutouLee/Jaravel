# 发布流程

> 这份文档描述 **v1.0 起**的正式发布。当前仓库版本仍是
> `1.0.0`;下一次发布时按下面的步骤改版本号。

## 0. 版本策略

- 语义化版本(SemVer):`MAJOR.MINOR.PATCH`;
- 框架模块同版本发布,由 `jaravel-bom` 统一管理;
- 快照版本用 `-SNAPSHOT`,正式版本不带后缀;
- CLI 与框架同版本;native 二进制作为 GitHub Release 资产发布。

## 1. 准备

- JDK 21、Maven 3.9+
- GPG 密钥(发布到 Maven Central 的每个 artifact 都要签名)
- Maven Central Portal 账号与 `net.dobeen.jaravel` 命名空间
  (groupId 以 `net.dobeen` 开头,Central 的验证方式是在 `dobeen.net`
  加一条 DNS TXT 记录)
- `~/.m2/settings.xml` 里配置 `central` server:

```xml
<servers>
  <server>
    <id>central</id>
    <username>${env.CENTRAL_USERNAME}</username>
    <password>${env.CENTRAL_PASSWORD}</password>
  </server>
</servers>
```

- 环境变量 `MAVEN_GPG_PASSPHRASE`。

## 2. 改版本

```bash
mvn versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false -DprocessAllModules=true
```

`examples/*/pom.xml` 里的 `<jaravel.version>` 是独立属性,需要同步:

```bash
# 确认没有残留的旧版本
grep -rn "0.1.0-SNAPSHOT" --include=pom.xml .
```

同步更新 `docs-site` 中引用的版本(如果固定了版本号)。
`jaravel-cli` 的 `<version>` 由父 POM 管理;`jaravel new` 从 CLI 的
manifest 读取版本号写入生成项目。

## 3. 门禁

```bash
mvn clean test
mvn -DskipTests install                             # 让 jaravel-maven-plugin:X 进本地仓库
mvn -Psecurity -DskipTests validate
mvn -Prelease -DskipTests -Dgpg.skip=true package   # source/javadoc 能构建
python benchmark/laravel-parity/parity.py --max 1.3

cd docs-site
pip install -r requirements.txt
mkdocs build --strict
```

要求:

- 全量测试 0 失败;
- parity 最差 ≤ 1.30;
- ArchUnit 边界规则通过(包含在 `mvn test`);
- CI 全绿(`.github/workflows/ci.yml`:test / parity / docs / dependency-convergence);
- docs 的「待决问题」已在 [KNOWN-ISSUES.md](KNOWN-ISSUES.md) 中登记或关闭。

## 4. 构建 native CLI

见 [docs/20-native-and-performance.md](docs/20-native-and-performance.md) §3.1。
产物 `jaravel-cli/target/jaravel`(Linux x86_64)。

Windows/macOS 的 native 产物尚未纳入发布流程;发布页只提供
Linux 二进制 + 各平台可运行 jar 的启动方式。

## 5. 发布到 Maven Central

```bash
mvn -Prelease deploy
```

`release` profile 会:

- attach sources / javadocs;
- 用 GPG 签名;
- 通过 Central Publishing Plugin 上传,`autoPublish=false` ——
  到 <https://central.sonatype.com/> 人工检查后再点 Publish。

> 示例模块设置了 `maven.deploy.skip=true`,不会进入 Central。
> 第一次发布前确认 `net.dobeen.jaravel` 命名空间已经验证
> (即 `dobeen.net` 的 DNS TXT 记录已经生效)。

## 6. 打标签与 GitHub Release

```bash
git tag -a v1.0.0 -m "Jaravel 1.0.0"
git push origin v1.0.0
```

GitHub Release 附件:

- `jaravel-cli/target/jaravel`(Linux x86_64 native);
- `CHANGELOG.md` 的对应段落;
- 可选的 `jaravel-cli` + 依赖的 zip(给没有 native 的用户)。

## 7. 发布后

1. 用发布版本跑一次 [docs-site/docs/quickstart.md](docs-site/docs/quickstart.md)
   的 30 分钟检查表;
2. 更新 README 的状态与版本;
3. 需要在下一轮修的已知问题进 [KNOWN-ISSUES.md](KNOWN-ISSUES.md);
4. 安全响应流程见 [SECURITY.md](SECURITY.md)。

## 8. 回滚

- Maven Central 的版本不可撤回,只能发布下一个补丁;
- 若发现高危问题,按 SECURITY.md 的 SLA 发布补丁,并在 Release Notes
  明确影响范围与缓解方式。
