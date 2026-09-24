# ragagent-java

WeKnora（[Tencent/WeKnora](https://github.com/Tencent/WeKnora)）后端的 **Java 全量翻译版**：把 Go（Gin/GORM）后端逐文件翻译为 **Spring Boot 3.3 / JDK 21 / MyBatis-Plus**，前端 **零改动** 复用。团队技术栈统一项目。

**验收标准不是"看起来对"，而是字节级一致**：同一请求打到 Go 与 Java 两侧，响应体（含 JSON 键序、HTML 转义、SSE 帧格式、错误文案、时区渲染）必须**逐字节相同**。为此建立了 1,748 个 golden 契约测试、75 个双端 A/B / 录制脚本，以及一套已验证的翻译方法论（见 [`docs/translation-conventions.md`](docs/translation-conventions.md)）。

---

## 目录

- [能力地图](#能力地图)
- [技术栈](#技术栈)
- [架构：契约驱动的翻译](#架构契约驱动的翻译)
- [快速开始](#快速开始)
- [配置](#配置)
- [测试与验收体系](#测试与验收体系)
- [项目结构](#项目结构)
- [脚本索引](#脚本索引)
- [文档索引](#文档索引)
- [翻译状态与已知边界](#翻译状态与已知边界)
- [高频坑速查](#高频坑速查)

---

## 能力地图

后端 37 个领域包（`server/src/main/java/com/ragagent/`），与 Go 仓包一一对应：

| 域 | 包 | 内容 |
|---|---|---|
| 基础 | `common` `config` `event` | Go 等价 JSON 序列化器（map 键序/HTML 转义/double/时间）、租户上下文、错误信封、事件总线 |
| 认证与租户 | `auth` `apikey` `audit` | 登录/JWT/OIDC（未配置=disabled 契约）、API Key 体系（scope+数据面白名单）、审计埋点、跨租户租户目录 |
| 模型 | `model` `llm` `rerank` `embedding` `tracing` | 模型配置（AES 凭据加密/SSRF 校验）、LLM 客户端（OpenAI 兼容/Anthropic/Ollama，thinking 策略、并发闸门、prompt-cache）、`models/{id}/debug` |
| 知识库 | `knowledge` `wiki` `faq` `searchutil` `retrieval` `vectorstore` | KB CRUD、文档解析管线（span 时间线）、chunk 编辑/修订、FAQ 双优先级检索、wiki（21 端点+生成管线）、混合检索（pgvector HNSW + ParadeDB BM25 + RRF） |
| 会话 | `session` `stream` `storage` `storageurl` `webfetch` `websearch` | 会话/消息/SSE 契约层、steer/追问建议/产物、continue-stream、文件代理（Range/预签名/`/r/*` 能力 URL）、agent 网络工具（web_search/web_fetch + 全页快照存储） |
| Agent | `agent` `agentm` `chatpipeline` `mcp` `modelcontext` | AgentEngine（thinking 流、工具循环、输出预算分摊）、30+ 内置工具（知识检索族/wiki 十件/web 两件/沙箱文件族/MCP）、RAG 快答管线、技能安装管线（installer agent + 镜像快照）、MCP 协议 + OAuth |
| 沙箱 | `sandbox` `browserskill` | docker-java（zerodep 传输）容器执行面、会话绑定/租约、shell/文件工具、浏览器技能 |
| 协作与集成 | `org` `im` `embed` `datasource` `favorite` `memory` `system` `evaluation` | 组织/共享（KB/agent/文件跨租户授予链）、IM 九渠道回调管线（含 AES 验签）、嵌入问答、数据源连接器、长期记忆、系统管理端、评估数据面 |
| 基础设施 | `docreader/` proto | docreader gRPC 契约（解析服务用官方镜像，目录名镜像 Go 仓） |

数据库迁移 196 个（`migrations/versioned/`，与 Go 仓 schema 一字不改），PostgreSQL 依赖 **ParadeDB**（BM25）与 pgvector（halfvec HNSW）。

## 技术栈

| 层 | 选型 |
|---|---|
| 运行时 | JDK 21（虚拟线程：管线并发、工具并行抓取、租户上下文显式拷贝） |
| 框架 | Spring Boot 3.3.5（Web/Validation/AOP/Redis）、MyBatis-Plus 3.5.7 + 手写 JdbcClient |
| 存储 | PostgreSQL 17（ParadeDB v0.22.2：pgvector + BM25）、Redis 7.0（stream/绑定存储/限流） |
| 文档解析 | docreader gRPC（`wechatopenai/weknora-docreader` 官方镜像，proto 已本仓化） |
| 沙箱 | docker-java 3.7.1（**zerodep 传输**——httpclient5 的 exec hijack 不回传输出帧，踩坑实录见 known-issues） |
| 数据分析 | DuckDB JDBC（`data_analysis` 工具） |
| 其他 | Flyway、gRPC/protobuf、jjwt、jtokkit、snakeyaml（vendored 提示词模板） |
| 前端 | Vue 3.5 + Vite 7（从 Go 仓原样复制，不修改） |
| 测试 | JUnit 5 + AssertJ + MockMvc + H2（内存 DDL 镜像 `TestSchema`）+ stub LLM/stub Ollama |

## 架构：契约驱动的翻译

```
                ┌────────────── 同一 dev PostgreSQL / Redis ──────────────┐
   请求 ──────► │  Go :8080（参考实现，只读对照）    Java :8082（翻译目标） │
                └────────────────────────┬────────────────────────────────┘
                                         │ 双端响应掩码后逐字节比对（A/B 脚本族）
   golden ◄── 录制（record-*.sh）────────┘
   契约测试（1,748 个 contracts/*.json + 4,100+ 测试用例）
```

三条铁律：

1. **录 Go 实录，不靠读码推断**——拿不准的行为把 Go 类型/函数抄进独立程序跑出真值（正则 `$` 语义、UUIDv5 向量、`%q` 动词、空切片 marshal 形态……全部是实录抓出来的）。
2. **字节即契约**——JSON 键序（map 字节序 / 结构体声明序双路径）、HTML 转义、`omitempty`、RFC3339 纳秒裁尾、SSE 帧格式，全部按 Go 实测逐字节对齐。
3. **agent 报绿后主会话独立全量复核**——H2 绿不等于 PG 绿，单跑绿不等于全量绿（Mockito self-attach、上下文变体、墙钟脆弱都是抓过的）。

## 快速开始

前置：JDK 21（`/opt/homebrew/opt/openjdk@21` 或等效）、Docker、Node 20+。

```bash
# 1) 基础设施：postgres(15432) + redis(16379) + docreader(50051)
docker compose up -d

# 2) 密钥/连接配置（.env 已 gitignore；dev-env.sh 按 key 读取）
#    必需键见 scripts/dev-env.sh 头注释（JWT_SECRET / SYSTEM_AES_KEY / DB_*)……

# 3) 后端（默认 :8082）
./gradlew bootRun
#    或带就绪等待：scripts/java-server-up.sh

# 4) 前端（:5173；代理缺省指向 :8080，测试 Java 请显式指向 8082）
cd frontend && npm ci
VITE_DEV_PROXY_TARGET=http://localhost:8082 npm run dev
```

登录：`java-phase1@weknora.test` / `Passw0rd!`（租户 10002 owner，含种子数据）。
平台管理员：`walkadmin@weknora.test` / `Passw0rd!`（`is_system_admin`，可跨租户 + 系统管理端）。

> 命令行取 token：`scripts/token.sh 8082`。

## 配置

环境变量（经 `scripts/dev-env.sh` 统一装配，host-run 必须覆盖容器内地址）：

| 变量 | 缺省 | 说明 |
|---|---|---|
| `SERVER_PORT` | 8082 | Java 服务端口 |
| `DB_HOST` / `DB_PORT` | localhost / 15432 | ParadeDB（pgvector + BM25） |
| `REDIS_HOST` / `REDIS_PORT` | localhost / 16379 | stream / 绑定存储 / 限流 |
| `DOCREADER_ADDR` | localhost:50051 | 文档解析 gRPC |
| `LOCAL_STORAGE_BASE_DIR` | /data/files | 本地存储根 |
| `JWT_SECRET` / `SYSTEM_AES_KEY` | — | 双侧必须一致（跨语言密文互操作） |
| `SSRF_WHITELIST_EXTRA` | — | 额外 SSRF 白名单（逗号分隔，支持 `*.example.com`） |
| `RETRIEVE_DRIVER` | — | 检索引擎选择（需真实导出，不从 .env 静默读） |
| `VITE_DEV_PROXY_TARGET` | http://localhost:8080 | 前端代理目标（测 Java 用 8082） |

运行时可调（DB 层即开即用，无需重启）：`system_settings`（沙箱 docker 开关、SSRF 白名单、并发上限 `model.max_concurrency` 等，经 `SystemSettingService` 推送）。

## 测试与验收体系

```bash
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"   # 换 shell 必设，否则找不到 JRT

# 定向（某领域包）
./gradlew :server:test --tests "com.ragagent.session.*"

# 全量必须分批跑（同 JVM 全量会触发 Mockito self-attach 风暴，见 known-issues/00）
./gradlew :server:test --tests "com.ragagent.agent.*" --tests "com.ragagent.chatpipeline.*"
./gradlew :server:test --tests "com.ragagent.common.*" --tests "com.ragagent.auth.*"   # …按 B1~B4 分批

# 真实 Docker 集成（默认跳过；OrbStack 注意 DOCKER_HOST）
DOCKER_HOST=unix:///$HOME/.orbstack/run/docker.sock \
WEKNORA_SANDBOX_DOCKER_IT=true WEKNORA_SANDBOX_DOCKER_ENABLED=true \
./gradlew :server:test --tests "com.ragagent.sandbox.runtime.DockerSandboxIntegrationTest" \
                         --tests "com.ragagent.session.service.ArtifactDrainDockerIT"
```

三个层次：

1. **golden 契约测试**（`server/src/test/resources/contracts/`，1,748 个实录）：MockMvc 掩码比对（UUID/时间戳等动态字段掩掉后逐字节）。
2. **双端 stub A/B**（`scripts/ab-*.sh`）：双端同指 `scripts/stub-llm-server.py`，走真实 HTTP/SSE 全链路；agent 出站 LLM 请求体已可**全 body 对拍**（tools/messages/键序双路径）。
3. **真实集成**：dev PG（非 H2）暴露 jsonb/NOT NULL/时区差异；Docker IT 验证沙箱执行面与产物排水全链。

## 项目结构

```
├── server/                     Spring Boot 后端
│   └── src/main/java/com/ragagent/    37 个领域包（见能力地图）
│   └── src/test/java/com/ragagent/    361 个测试类 / 4,118 个用例
│       └── resources/contracts/       1,748 个 golden 实录
├── frontend/                   Vue 3 + Vite（Go 仓原样复制，零改动）
├── migrations/versioned/       196 个 SQL 迁移（schema 一字不改）
├── docreader/                  docreader gRPC proto（目录名镜像 Go 仓）
├── docker-compose.yml          dev 基础设施（postgres/redis/docreader）
├── scripts/                    75 个脚本：环境装配 / 起服 / golden 录制 / A/B 对拍
├── docs/
│   ├── api/                         API 文档（index.html 双击可看；由脚本生成）
│   ├── translation-conventions.md   翻译约定（§3 GORM 语义 / §7.5 约束 / §8 日志 / §9 索引）
│   ├── known-issues/                按批次分片的契约细节与坑（00 ~ 07）
│   ├── HANDOFF.md                   交接文档（进度基线，新会话必读 §0.x）
│   ├── translation-progress.md      模块进度
│   └── W5-plan.md / W5d-plan.md     批次作战计划（存档）
└── gradle/                     wrapper
```

## 脚本索引

| 类别 | 脚本 | 用途 |
|---|---|---|
| 环境 | `scripts/dev-env.sh` | 被其他脚本 source：JDK 路径、端口、.env 按 key 回落 |
| 起服 | `scripts/java-server-up.sh` / `go-server-up.sh` | 后台起服 + 等就绪（Go 用于 A/B 对照） |
| 取证 | `scripts/token.sh <port>` | 登录取 JWT |
| 录制 | `scripts/record-*-golden.sh` | 从 Go 侧录 golden（ag/chunk/kg/w5a/w5b/w5c/w5f/model-debug…） |
| 对拍 | `scripts/ab-*.sh` | 双端掩码后逐字节比对（session/chunk/kb/w5*/qa46d/tools-web…） |
| 对账 | `scripts/route-recon.py` | Go↔Java 路由缺口程序化对账（当前真缺口仅 `/swagger/{}`，非翻译目标） |
| API 文档 | `scripts/generate-api-docs.py` | 解析控制器注解 + RBAC/API-Key 策略，生成 `docs/api/`（452 条路由；路由变更后重跑即可） |
| stub | `scripts/stub-llm-server.py [port]` | LLM/Ollama stub（`STUB_DUMP_DIR` 落盘请求体供 A/B） |

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/HANDOFF.md`](docs/HANDOFF.md) | **新会话从这里开始**：进度基线（§0.x 倒序批次志）、验收流程、协作方式 |
| [`docs/translation-conventions.md`](docs/translation-conventions.md) | 翻译约定正文（GORM 隐式行为、错误/响应格式、十条强制约束、翻译日志） |
| [`docs/known-issues/`](docs/known-issues/) | 按批次分片的契约细节与踩坑（00-foundation ~ 07-model-debug） |
| [`docs/translation-progress.md`](docs/translation-progress.md) | 模块进度表 |

## 翻译状态与已知边界

**已完成**：HTTP 面全量（route-recon 对账，唯一留白 `/swagger/{}` 为 Go 工具路由、非翻译目标）；执行面含混合检索、技能安装管线（installer agent + 镜像快照 + 产物排水）、agent 网络工具、沙箱执行、IM 回调管线、`models/{id}/debug`。

**明确边界**（都有备案，非缺陷）：

- **provider-XDEP 族**（dev 双侧都到不了真实后端，接缝与测试已备）：IM 九渠道的平台客户端传输、cube/e2b 终端 PTY、真实 LLM install E2E
- **Owner 暂缓**：EvaluationService 执行步（前端无入口；恢复条件=出现评估调用需求）
- **备案降级**：langfuse 追踪（no-op 等价于 Go 未启用）、Redis 分布式限流器（单实例 LocalLimiter）、RSS readability 抽取、chromedp 浏览器渲染（走 Go 自身的 browser-unavailable 分支）、OIDC enabled 后的网络步

## 高频坑速查

<details>
<summary>展开（历史实锤，完整版见 docs/known-issues/）</summary>

- **"Go 通 Java 不通"**：先 `jcmd` 查 Gradle 守护进程把启动 shell 的代理变量固化成 `proxyHost`，再怀疑代码。
- **跑测试后服务全站 500 + NoClassDefFoundError**：`./gradlew test` 重写了 bootRun 正在用的 `build/classes`——**重启服务即解**，不用查代码。
- **`--stop` 守护进程会把 bootRun 一起杀掉**：停完必须重启 Java。
- **旧进程占端口**：起服前 `lsof -ti :8082 | xargs kill`，否则就绪探测打到旧代码、新路由 404。
- **后台服务被"优雅关闭"**：nohup 后台进程受进程组信号影响（macOS 无 setsid），长跑验证尽量在同一次调用内完成。
- **`Long != Long` 引用比较**：租户 id 10002 超出 Long 缓存区，一律 `equals`。
- **H2 绿 ≠ PG 绿**：jsonb 键序、NOT NULL、DDL 默认值只在真 PG 暴露——验收必须连 dev PG。
- **领域对象 `isXxx()` 派生方法必须 `@JsonIgnore`**：漏了会多吐键，jsonb 回读炸整列。

</details>

---

*源自 Tencent/WeKnora（Apache-2.0）。本地参考仓只读对照，不参与构建与运行。*
