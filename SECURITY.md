# 安全策略

## 支持范围

| 版本 | 状态 |
|---|---|
| `1.x` | 支持中(补丁版本) |
| `0.9.x` | 仅安全修复,直到 1.0 发布后 3 个月 |

## 报告漏洞

**不要公开开 issue。** 用 GitHub 的私密安全公告:

<https://github.com/MutouLee/Jaravel/security/advisories/new>

报告里请包含:

- 受影响版本;
- 复现步骤或最小项目;
- 影响(认证绕过 / XSS / 路径穿越 / DoS …);
- 你建议的修复方向(可选)。

## 响应 SLA

| 阶段 | 目标 |
|---|---|
| 确认收到 | 72 小时内 |
| 初步评估 | 7 天内 |
| 高危修复 | 30 天内(补丁版本) |
| 中低危 | 下一个计划版本 |

修复发布后会在 GitHub Security Advisory 与 CHANGELOG 中致谢(除非你要求匿名)。

## 范围

**在范围内**:本仓库第一方代码(`io.jaravel.*`)、starter 自动配置、
APT 生成的代码、官方示例中的框架用法。

**不在范围内**:JDK、Spring Boot、数据库/容器本身、用户应用代码、
第三方依赖的已知漏洞(请直接报告给上游;我们会在依赖升级中跟进)。

## 安全默认值

框架的安全默认值与检查表见 [SECURITY-AUDIT.md](SECURITY-AUDIT.md)。
其中包括:密码哈希、会话固定防护、CSRF 常数时间比较、密码重置 token、
remember token、Cookie 标志、上传安全、CORS 默认关闭、客户端 IP 不采信
`X-Forwarded-For` 等。
