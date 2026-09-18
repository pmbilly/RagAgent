# 交接文档（新会话接手用）

> 最后更新：2026-09-18 · 最新提交 `b475907` · 1605 测试全绿

## 0. 一句话背景

把 WeKnora 后端从 Go（Gin/GORM）**全面翻译**成 Java（Spring Boot 3 + JDK 21 + MyBatis-Plus）。
前端**零改动**，因此验收标准是「响应与 Go 实录**逐字节一致**（golden 契约测试）」，
而不是"代码看起来对"。

- Java 仓：`/Users/billy/ragagent-java`（可写）
- Go 仓：`/Users/billy/WeKnora`（**只读**对照，`~/.claude/CLAUDE.md` 要求改动后跑 `graphify update .`）
- 计划文件：`/Users/billy/.claude/plans/flickering-wishing-cat.md`

## 1. 开工前必读（按顺序）

1. **`docs/translation-conventions.md`** —— 本项目最重要的资产。
   - §3 GORM 隐式行为清单
   - §7.5 **派 agent 的标准约束**（十条，每条都对应踩过的坑）
   - §8 翻译日志（每完成一个模块**必须**追加一行）
   - §9 已确认的契约细节 + 已知差异 + **工具链坑**（含最高频的几类错误）
2. `docs/HANDOFF.md`（本文）—— 进度与下一步
3. 需要时再查源码：`server/src/main/java/com/ragagent/`

## 2. 进度总览

| 阶段 | 模块 | 状态 | 提交 |
|---|---|---|---|
| 0 | 骨架（路由/错误体系/分页/TenantContext） | ✅ | — |
| 1 | auth / 租户 | ✅ | `d0e85fd` |
| 2 | 模型配置（含 SSRF / AES 凭证加密） | ✅ | `6f90b51` |
| 3 | 知识库（KB CRUD + 文档上传→解析→chunk） | ✅ | `5048dd3` |
| 4.0 | **LLM 调用客户端**（`models/chat` + `provider` + `limiter`） | ✅ | `11a8ae9` |
| 4.1 | MCP 服务管理（自研协议客户端 + OAuth 全链） | ✅ | `c645502` |
| 4.2 | Wiki（21 端点 + 完整生成管线） | ✅ | `a0efb66` |
| — | 机制建设（往返测试 + 脚本 + agent 约束模板） | ✅ | `fc13321` |
| — | API Key 体系回补（含数据面收口） | ✅ | `4d38d75` |
| — | audit 审计回补（含埋点接线） | ✅ | `23d0859` |
| 5.0 | **`stream/` 流管理器**（SSE 的前置） | ✅ | `4393168` |
| 5.1 | **会话/消息 domain + 仓储**（含追问建议） | ✅ | `a13e4df` |
| **5.2** | **会话 / SSE 端点**（`continue-stream` 起） | 🔶 拆解中 · **步 1-2/4 完成** | — |
| 6 | embed 渠道 | ⏳ | — |
| 7 | **agent 引擎 + chat_pipeline + modelcontext**（39k，最大一块） | ⏳ | — |
| 8 | 联调 | ⏳ | — |

**后移/后置**（用户已决策）：
- **IM**（15.6k）：主链路硬依赖阶段 5+7，整体后移到阶段 5 之后
- **计划外模块**（datasource 10.8k / memory 7.9k / 管理端 10k / skill 6k / infrastructure 11.7k）：
  阶段 7 之后统一做
- **sandbox + browserskill**（17.2k）：原计划即后置到第二期

## 3. 下一步：阶段 5（会话 / SSE）

### 3.1 拆解（含依赖判断）

**已落地**：
- `stream/` 流管理器（1032 行）→ `com.ragagent.stream`（§3.4）
- 会话/消息/追问建议的 domain + 仓储 → `com.ragagent.session.{domain,mapper}`（§3.5）
- **仓储层对 `continue-stream` 已经够用**，三条 JOIN 检索查询（搜索端点用，不在该路径上）留到做搜索时补

**可独立做（不依赖 agent 引擎）** —— 建议接着做这批：

| 文件 | 行数 | 内容 |
|---|---|---|
| `application/service/session.go` | 993 | 会话 CRUD、标题生成 |
| `application/service/message.go` | 978 | 消息 CRUD |
| `application/service/message_suggestion.go` | 962 | 追问建议 |
| `application/service/session_qa_helpers.go` | 345 | QA 辅助 |
| `application/service/session_attachment_staging.go` | 295 | 附件暂存 |
| `handler/session/*` + `handler/message*.go` | ~12k（含测试） | HTTP 层 |

**强依赖阶段 7（agent 引擎）** —— 建议后移或先打桩：

| 文件 | 行数 | 依赖 |
|---|---|---|
| `application/service/session_knowledge_qa.go` | 1290 | `internal/agent/tools` + `chat_pipeline` |
| `application/service/session_agent_qa.go` | 647 | agent 引擎 |
| `application/service/session_sandbox_pin.go` | 214 | sandbox（已后置） |

### 3.2 已就位的可复用组件

- **`com.ragagent.llm`**：`LlmChatClient` / `LlmChatClients.create(config, ollamaService, governor)`，
  返回 `ChatResponse` 与 `BlockingQueue<StreamResponse>`；`ResponseType` 枚举已含全部 22 个值
  （`ANSWER`/`THINKING`/`TOOL_CALL`/`SESSION_TITLE`/`STEER` …），阶段 5 直接产出即可
- **`StreamResponse` / `TokenUsage`**（`llm.domain`）：SSE 事件体与用量契约已定义
- `com.ragagent.auth` 的 `TenantContext`、`com.ragagent.apikey` 的 `APIKeyScopeContext`
- `com.ragagent.mcp` 的 `Gate`（审批门，含 `OAuthPendingRequest`）
- 测试基座：`TestSchema`（H2 共享 DDL）、`JsonRoundTrip`（契约往返体检）

### 3.3 开工建议

1. ~~先读 `stream/` 三个文件——**SSE 管理器是 agent 引擎的前置**，优先落地~~ ✅ 已完成
2. 摸底 agent 只读，产出一份与 `docs/translation-conventions.md` 风格一致的报告
3. 按 §7.5 约束派 agent（**单 agent 串行优先**，见下方"并发踩坑"）
4. 验收走 §4 的完整流程

### 3.4 阶段 5.0（stream 管理器）交接要点

- 已交付 `com.ragagent.stream`：`StreamManager` 接口 + `MemoryStreamManager` / `RedisStreamManager`
  两个实现，`StreamManagerConfig` 按 `STREAM_MANAGER_TYPE` 选型（精确匹配 `"redis"`，
  其余走内存；选 redis 时**启动即 Ping**，连不上就起不来——与 Go 一致，已实测）。
- **它是跨语言共用的 Redis 存储契约**，JSON 逐字节对齐 Go（HTML 转义 / 小写十六进制 /
  map 排序）——细节与踩坑见 §9「阶段 5（stream 流管理器）新确认的细节」，**动它之前先读那一段**。
- 测试：35 条（`com.ragagent.stream.*`，其中 15 条跑在**真 redis-server** 上；
  本机 PATH 上没有 `redis-server` 时整类 skip）。
- **还没接进任何 HTTP 端点**——消费方是 `handler/session/stream.go`，见 §3.5。

### 3.5 阶段 5.1 交接要点（会话/消息 domain + 仓储）

已交付 `com.ragagent.session.{domain,mapper}`：

- `Session` / `SessionListItem` / `SessionListQuery` + `SessionRepository`（含 `QueryPaged` 的
  六种来源桶、方言分叉 `ILIKE`/`NULLS LAST`）
- `Message` + 六个 jsonb 子类型 + `MessageRepository`
- `MessageSuggestionSet` / `MessageSuggestionEvent` + `MessageSuggestionRepository`
  （`AcquireGeneration` 的四条分支：唯一键抢占 → 复用 ready/suppressed → 让位未过期租约 → 抢过期租约）
- 五个 `List<T>` 型 jsonb 列的处理器（`AbstractJsonListTypeHandler` 基类 + 三行子类）

**尚未包含**（不在 `continue-stream` 路径上，做搜索端点时一并补）：
`SearchMessagesByKeyword` / `GetMessagesByKnowledgeIDs` / `GetMessagesByRequestIDs`
（都要 JOIN sessions + `MessageWithSession`）与 `ListMessagesBySessionAfterCursor`
（依赖 memory 模块的 `MessageMessageCursor`），以及搜索响应类型
（`MessageSearchGroupItem` / `MessageSearchResult`）。

**动这块之前先读 §9「阶段 5（session/message）新确认的细节与坑」**——尤其
`Updates(结构体)` 的零值跳过、wrapper `.set()` 不套 typeHandler、`List<T>` 泛型擦除这三条。

### 3.6 下一轮：`continue-stream` 端点（**已侦察，比预想的大**）

上一轮把 `handler/session/stream.go` 的依赖面查清了：它**不是"两个 service 调用"**，
还要下面三层。建议按这个顺序做，前三步各自可独立验证，第 4 步才需要它们合起来。

| 步 | 内容 | 规模 | 为什么这个顺序 |
|---|---|---|---|
| 1 | ✅ **已完成** — SSE 契约层：`setSSEHeaders` / **`buildStreamResponse`** / `sendCompletionEvent` / `searchResultFromMap`（Go `helpers.go` L182-249）+ `types.SearchResult` | ~100 行 | 见下方「步 1 交付说明」 |
| 2 | ✅ **已完成** — **`storageurl` 包**（`mode` / `storageurl` / `stream` / `resolver` / `request` 的重写部分） | 实为 846 行（原估 203 行只算了 `stream.go`） | 见下方「步 2 交付说明」 |
| 3 | **service 最小读路径**：`GetSession` / `GetOwnedSession` / `GetMessage`（含 `loadSessionForRead` 的可见性判定） | 仓储已就绪 | 依赖已全部到位 |
| 4 | **`ContinueStream` 控制器**（Go `stream.go` L29-204） | ~200 行 | 到这一步才有第一次真正的 SSE A/B |

**步 1 交付说明（已完成）**：

| 文件 | 内容 |
|---|---|
| `common/web/GoDoubleSerializer` | `float64` 按 Go 专用编码器输出（整数值不补 `.0`、指数写法、次正规数最短表示） |
| `common/web/GoMapSerializer` | map 键序**递归**对齐 Go（含 `data.arguments` 这类模型返回的嵌套 map） |
| `retrieval/domain/SearchResult` | Go `types.SearchResult`；两个 `json:"-"` 内部字段走 `@JsonIgnore` |
| `session/sse/SseContract` | 四个 SSE 头（覆盖语义）+ 空实现的 `sendCompletionEvent` |
| `session/sse/StreamResponseBuilder` | `buildStreamResponse` + `searchResultFromMap`；**类注释里就是 §6 要求的 emit 表** |

测试 41 条（`GoDoubleSerializerTest` 28 + `StreamResponseBuilderTest` 12 + 往返 1），
**期望值全部是 Go 实录**：把 helpers.go 的三个函数连同 `types.SearchResult`/`types.JSON`
原样抄进一个独立 Go 程序跑 `json.Marshal`，输出抄进断言。
这条「抄源码 + 真 Go 运行时序列化」的做法在本轮抓到两个只看代码看不出的坑
（`types.JSON` 漏 `MarshalJSON` 会退化成 base64；Java `Double.toString` 在次正规数上更长），
**后续凡涉及"字节级对齐"的模块建议沿用**。

**步 2 交付说明（已完成）**：

⚠️ **原估偏小**：步 2 写的「`StreamRewriter`，203 行」只是 `stream.go` 一个文件。
`StreamRewriter` 依赖 `Rewriter`，后者依赖 `Resolver`/`Mode`，一路拖出整个 `storageurl` 包
（846 行）；而 `resolver.go` 又要一套**完全不存在的**多 provider 文件服务层
（`internal/application/service/file/*`，20+ 文件 + 各家云 SDK）。
**处理方式是收窄成端口**，见下。

| 文件 | 内容 |
|---|---|
| `storageurl/Mode` + `StorageUrlContext` + 两个异常 | 模式解析/合并（查询值 vs 部署默认 vs 强制 handle）、KB 受限 Key 的 403 |
| `storageurl/Rewriter` | `Pattern` 替换 + memo + `IsHTTPURL` + `forRequest` + `CopyReferences`/`CopyData` |
| `storageurl/StreamRewriter` | 扣留缓冲：`findIncompleteRef` / `findIncompleteMarkdownImage` / `holdbackCutoff` / `push` / `flushAll` |
| `storageurl/FileServiceResolver` + `FileService` / `StorageBackendResolver` 端口 | provider 解析与缓存；**端口暂无生产实现** |

测试 49 条。除照搬 Go 的表驱动用例外，还加了一份**差分语料**（把 Go 的两个正则与三个函数
抄进独立程序打印结果，期望值抄回断言）——它抓到并钉住了 `\v` 那条已知差异。

**未接线（步 3 一起做）**：`RewriteMessages` / `RewriteMessagesResponse` / `rewriteAgentSteps`
——它们要**有类型的** `AgentSteps`（Java 侧目前是 `List<Object>` 透传），且服务的是消息历史端点
而非 SSE。SSE 要用的 `CopyReferences` / `CopyData` 已落地。

**第 4 步的几个要点（读 Go 时注意）**：

- `resolveStreamRewriter` 必须在**写任何 SSE header 之前**解析——非法 `resource_urls`
  要能落成普通 400 JSON，而不是"已经开始流了才发现参数错"。
- 轮询是 **100ms ticker**；客户端断开靠 `c.Request.Context().Done()` →
  Java 侧是 `SseEmitter.onCompletion/onTimeout`（§6 第 3、4 条）。
- 三种 not-found 的**文案与状态码各不相同**，别统一：
  会话不存在 → 404 + `err.Error()`；消息不存在（`gorm.ErrRecordNotFound`）→ 404 + `err.Error()`；
  `message == nil` → **404 + `{"success":false,"error":"Incomplete message not found"}`**（手写信封）；
  流里没有事件 → **404 + `{"success":false,"error":"No stream events found"}`**。
- 已经 `complete` 的流要**先回放全部事件、再补一个完成事件**然后返回，不进轮询循环。

## 4. 标准验收流程（每个模块）

```bash
# 0) 环境
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"   # 或直接用 scripts/ 里的脚本（已自动探测）
cd /Users/billy/ragagent-java

# 1) 起 Go server 录 golden（同时起 Java 做 A/B 对比）
scripts/go-server-up.sh          # 内含 env 覆盖与 SSRF 白名单配置
scripts/java-server-up.sh

# 2) 录 golden（务必用 curl -o，不要用 echo >，zsh 会解释转义）
TOKEN=$(scripts/token.sh 8080)
curl -s -o server/src/test/resources/contracts/xxx.json \
  -X POST http://localhost:8080/api/v1/... -H "Authorization: Bearer $TOKEN" ...

# 3) 写契约测试（掩码 UUID/时间戳后逐字节比对；中文用 content().bytes）
# 4) 全量测试
./gradlew test

# 5) e2e：Java 连真 PG 跑通，并与 Go 做 A/B（掩码后应 MATCH）
# 6) 更新 docs/translation-conventions.md 的 §8（日志行）+ §9（新细节/差异）
# 7) 提交（结尾带 Co-Authored-By: Claude <noreply@anthropic.com>）
```

## 5. 陷阱清单（按复发率排序）

> 完整版在 `docs/translation-conventions.md` §9。这里是最高频的几条。

1. **领域对象的 `isXxx()` 派生方法必须 `@JsonIgnore`** —— 曾在阶段 3、4.1 各复发一次
   （`Knowledge.isAborted`、`McpAuthConfig.isOAuth`），后者导致整个 jsonb 列读不回来。
   **防线**：`JsonContractRoundTripTest` 里加一条 `assertRoundTrips(...)`，自动拦。
2. **JSON 键名逐字段对照 Go 的 json tag** —— 蛇形忘了写 `@JsonProperty` 就接不住前端请求、
   也读不出 Go 写的行。MCP/Wiki 里还有协议规定的驼峰（`inputSchema` / `mimeType`）。
3. **Go 零值语义** —— string 默认 `""`、计数器用原始类型（否则插 NULL）、
   `omitempty` 的 0/空/false 要省略、无 `omitempty` 的恒输出（含 `null`）。
4. **带 `DEFAULT` 的 jsonb 列**：MyBatis-Plus 对 null 字段**省略该列** → 落到 DB 默认值，
   而 Go 显式写 NULL。需要 `insertStrategy = FieldStrategy.ALWAYS`（阶段 4.2 踩过）。
5. **中间件分层会改变错误文案** —— 写契约测试前先确认拒绝发生在哪一层，
   Go 的 handler 里常有**不可达的死代码**（阶段 4 的 audit 回补踩过）。
6. **测试禁止依赖真实网络** —— 本机 DNS 可能把 `api.openai.com` 解析到 Teredo 段而被 SSRF 拒绝。
7. **保存 curl 输出用 `-o`**，别用 `echo "$X" > f`（zsh 的 echo 会解释 `\n` 转义，golden 会坏）。
8. **`MyBatisSystemException: null`** 的根因在 `Caused by:` 链深处，别在业务代码里瞎找。
9. **H2 绿、PG 炸** —— NOT NULL 约束、jsonb 键序、DDL 默认值只在真 PG 上暴露。e2e 必须连真 PG。

## 6. 协作方式（这轮验证有效的）

- **主会话做**：共享契约（domain 类型）、跨模块装配（`WebConfig` 路由/过滤器）、
  golden 录制与 A/B 对比、真实缺陷的排查与修复、文档与提交
- **agent 做**：单模块的机械翻译 + 对等测试（任务书必须带 §7.5 的十条约束）
- **并发踩坑（重要）**：多个 agent 同时跑 `./gradlew test`（**全量**）会争抢 build 目录，
  导致 OOM 与"假失败"重跑——阶段 4 因此浪费了至少两轮。
  **现在的做法**：任务书里明确「只跑 `--tests "com.ragagent.<你的包>.*"`」，且**尽量串行**。
- 派 agent 时**先说清单一：**「先读 `docs/translation-conventions.md` 的 §3/§9/§7.5」

## 7. 关键文件索引

| 用途 | 路径 |
|---|---|
| 翻译约定（必读） | `docs/translation-conventions.md` |
| 交接文档（本文） | `docs/HANDOFF.md` |
| 契约 golden | `server/src/test/resources/contracts/` |
| H2 共享 DDL | `server/src/test/java/com/ragagent/TestSchema.java` |
| JSON 往返体检 | `server/src/test/java/com/ragagent/common/JsonContractRoundTripTest.java` |
| e2e 脚本 | `scripts/{dev-env,go-server-up,java-server-up,token}.sh` |
| 路由与过滤器装配 | `server/src/main/java/com/ragagent/config/WebConfig.java` |

## 8. 如果遇到不确定的

- **架构/范围决策**：问用户（这轮几次范围调整都是用户定的：IM 后移、计划外模块后置、机制优先）
- **Go 行为不确定**：**实测**——起 Go server 打一发，不要猜。这轮发现的真实缺陷
  （403 两种形态、201 状态码、jsonb NULL 语义、PathTenantMatch 缺失）**全部**是实测出来的
