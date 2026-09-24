# Go → Java 翻译约定（AI 翻译会话必读）

> 本文件是每一个翻译任务的上下文入口。开始任何翻译前，先读本文件全文。
> 源仓库：`/Users/billy/WeKnora`（Go，对照参考，只读）。目标仓库：`/Users/billy/ragagent-java`。
> 铁律：**前端零改动**——所有 HTTP 响应 JSON 逐字段一致，错误格式一致，状态码一致。

## 1. 技术栈映射

| Go | Java | 备注 |
|---|---|---|
| Gin handler | `@RestController` | 路由路径逐字符相同 |
| `gin.Context` | 方法参数按需组合：`HttpServletRequest`/`@RequestBody`/`@PathVariable` 等 | 不要注入裸 `HttpServletResponse` 除非必要（SSE 除外） |
| GORM | MyBatis-Plus | Mapper 接口 + `@TableName` 实体；见 §3 |
| `context.Context` | `TenantContext`（ThreadLocal，见 §5）+ 显式传参 | 不要把 Context 当参数层层传 |
| goroutine | 虚拟线程：`Thread.ofVirtual().start(...)` / `Executors.newVirtualThreadPerTaskExecutor()` | 已在 application.yml 开启 `spring.threads.virtual.enabled` |
| channel | `BlockingQueue` / `CompletableFuture` | |
| `error` 返回值 | 抛 `BizException`（见 §4） | Go 的 `if err != nil` 日志后返回 → 抛异常，由全局 handler 记日志 |
| `time.Time` | `java.time.Instant`（DB 存 `OffsetDateTime`） | JSON 序列化格式必须与 Go 的 RFC3339 一致（`yyyy-MM-dd'T'HH:mm:ss'Z'`） |
| `json:"xxx,omitempty"` | `@JsonInclude(NON_NULL)` | 字段级：`@JsonInclude(NON_NULL)` 放字段上 |
| `int64`/`float64` | `Long`/`Double` | JSON 数字精度对齐 |
| option 配置结构体 | `@ConfigurationProperties` record/class | |
| `sync.Mutex`/RWMutex | `ReentrantLock`/`ReentrantReadWriteLock` 或 `ConcurrentHashMap` | |
| `defer` | try-finally 或 try-with-resources | |

## 2. 包结构约定

- Go `internal/handler/x.go` → `com.ragagent.x.XController`
- Go `internal/application/service/x.go` → `com.ragagent.x.XService`
- Go `internal/application/repository/x.go` → `com.ragagent.x.mapper.XMapper`（MyBatis-Plus 接口）
- Go `internal/types/x.go` 中的领域类型 → `com.ragagent.x.domain.X`（实体）+ `com.ragagent.x.dto.XxxRequest/XxxResponse`（传输对象）
- Go `internal/middleware/` → `com.ragagent.common.filter.*`（Servlet Filter 链，顺序 = Go 中间件顺序）

## 3. GORM → MyBatis-Plus 规则（翻译 model 前必做清单）

翻译每个 Go model 前，先扫描并**显式列出**以下隐式行为，翻成 MyBatis-Plus 等效配置：

1. **钩子**：`BeforeCreate`/`AfterFind`/`BeforeUpdate` 等 → MyBatis-Plus `@TableField(fill = ...)` + `MetaObjectHandler`，或在 service 层显式赋值。**列出每个钩子等效为哪段 Java 代码。**
2. **关联预加载**：`Preload(...)` → 是额外查询就在 service 层 join 查询，标清 N+1 风险。
3. **软删除**：`gorm.DeletedAt` / `gorm:"softDelete"` → `@TableLogic` 注解。
4. **默认排序**：`gorm:"default:..."` 和代码里隐式的 `Order(...)` → 显式出现在查询构造中。
5. **唯一索引/外键**：struct tag 里的 `uniqueIndex`/`index` → 在 `@TableField` 注释或迁移 SQL 核对（**迁移不改**，索引以迁移为准）。
6. **自动时间戳**：`CreatedAt/UpdatedAt` 自动写 → `MetaObjectHandler`。

翻译一个 model 不出这张清单 = 任务未完成。

## 4. 错误与响应格式（逐字段锁定）

Go 的成功响应统一为：

```json
{"data": ..., "success": true}
```

Go 的错误响应有两种形态，**按源文件实际使用的翻译**（看 handler 里写的是 `c.JSON(status, gin.H{"error": ...})` 还是统一 error handler）：
- `{"error": "消息"}` + 对应 HTTP 状态码（handler 直接写的）
- 统一 `{"success": false, "message": "...", "code": ...}`（若走全局 error handler——翻译 router/middleware 时确认实际形态，记录到本文件 §8）

Java 实现：`com.ragagent.common.R<T>`（`{data, success}`）+ `@RestControllerAdvice` 全局异常处理器按 Go 实际形态输出。状态码必须一致（400/401/403/404/409/500）。

## 5. TenantContext（对照 Go context.Context）

Go 用 `context.Context` 传递 tenant/principal/visitor。Java：

- `com.ragagent.common.context.TenantContext`：ThreadLocal 持有 `tenantId`、`principalType`、`principalId`、`embedVisitorId`
- 由 Filter 链（对应 Go middleware 链）填充：auth → rbac → principal 解析
- service 层需要租户隔离的每个查询必须带 `TenantContext.currentTenantId()`，等效 GORM 的 `Where("tenant_id = ?")`
- **虚拟线程下 ThreadLocal 安全**（虚拟线程也是线程），但跨虚拟线程传递（异步任务）必须显式传递值，禁止共享 ThreadLocal

## 6. SSE 流式翻译纪律（agent/chat 模块最严格）

翻译任何涉及 `stream_emit` / SSE 的代码时：

1. **先把 Go 侧所有 emit 点列成表**：文件、行号、事件类型、payload 字段。
2. Java 侧逐一对照：每个 emit 点编号对应，事件顺序一致，`data:` 的 JSON 字段名和结构一致。
3. `SseEmitter` 用完必须 `complete()`；异常路径必须 `completeWithError()`。
4. 客户端断开检测：Go 的 `c.Stream` 断流 → `SseEmitter.onCompletion/onTimeout` 注册清理。
5. 心跳/keepalive 间隔与 Go 一致。

## 7. 测试翻译规则

- Go 表测试（table-driven）→ JUnit 5 `@ParameterizedTest`
- `testify/assert` → AssertJ
- Go mock（gomock/mockery）→ Mockito
- httptest → `@WebMvcTest` + MockMvc
- ** golden 契约测试**：`server/src/test/resources/contracts/` 下按端点存 Go 版实际响应，Java 集成测试逐字段比对
- 翻译完成的定义：Go 测试语义对应的 Java 测试全部通过 + golden 通过

- **阶段 4.2（Wiki）新确认的细节**：
  - **Wiki 的响应形态与知识库/MCP 都不同**，三处都要按实录写：
    1. 页面 CRUD 返回**裸实体**（无 `success`/`data` 信封）
    2. 列表是自定义分页 `{"pages":[...],"total":N,"page":N,"page_size":N,"total_pages":N}`
    3. 错误是**纯字符串** `{"error":"Wiki page not found"}`（不是 AppError 信封）
  - **403 有两种形态**（本轮新分离）：路由守卫/所有权判定 → `{"error":"Forbidden: <msg>"}`
    （纯字符串，无信封）；handler 内 AppError → 完整信封。Go 的所有权守卫
    （`OwnedWikiKBOrAdmin`）属前者，Java 侧因拦截器拿不到资源，只能在控制器内判定，
    故新增 `GuardForbiddenException` + 全局处理器分支来产出守卫格式。**阶段 3 的
    `KnowledgeBaseController` 有同样偏差，已一并修正。**
  - **创建页面/文件夹返回 201**（不是 200）；不存在的 KB 返回 **404 + AppError 信封**
    （不是 403）——这两个都要实测确认，别照直觉写。
  - **jsonb 字符串数组列的 NULL 语义**（`aliases`/`source_refs`/`chunk_refs`/`in_links`/
    `out_links`/`category_path`）：Go 的 nil slice 写 **SQL NULL**，响应输出 `null`。
    Java 侧落地三件事才对齐：
    1. `WikiStringListTypeHandler` 对空列表**写 SQL NULL**（不是 `[]`）；
       代价是查询侧要用 `COALESCE(in_links, '[]'::jsonb)`，不能依赖 `= '[]'`
    2. `EmptyListAsNullSerializer` 把空列表序列化回 `null`（读路径宽容返回空列表）
    3. decode 必须返回**可变** `ArrayList`——业务代码会就地 `add`，`List.of()` 会抛
       `UnsupportedOperationException`（本轮踩过）
  - **`category_path` 是唯一带 omitempty 的数组字段**（空时省略键，其余恒输出 `null`）——
    逐个核对 Go 的 json tag，别一刀切。
  - **`page_metadata` 列有 `DEFAULT '{}'`，但 Go 显式写 NULL 覆盖它**。
    MyBatis-Plus 默认对 null 字段**省略该列**，会落到 DB 默认值 `{}` →
    必须 `insertStrategy = FieldStrategy.ALWAYS`。**其他带 DEFAULT 的 jsonb 列同理**。
  - `wiki` 路由前缀是 `/knowledgebase`（**无连字符**），与知识库的 `/knowledge-bases` 不同。
- **阶段 4.2 已知差异 / 未接线**：
  1. **slug 锁 / inflight 限流 / 身份认领 / finalize 锁默认全为进程内实现**（= Go 的 Lite 模式）。
     多副本部署必须换 Redis 实现并 `@Primary` 注册；否则表现为"同一标题建出两个页面"。
  2. `spans` 追踪与 langfuse 未实现（no-op 门面，调用点形状与 Go 逐行对应）。
  3. `WikiCrossLinker`/`WikiChunkCleaner`/`WikiPendingOpsCounter`/`WikiActiveFlag` 是可选接线端口，
     缺 bean 时按 Go 的 nil 分支降级。
  4. `GetGraph` 的 `FamiliarKnowledgeIDs` 恒 null（memory 模块未翻译）。
  5. 空列表 vs null：`ListIssues`/`SearchPages` 等 Go 的 nil slice 输出 `null`，
     Java 归一为 `[]`（前端已有守卫）。**与上面 jsonb 数组列的处置不同**——
     那些对齐了 null，这几个没有；若要完全逐字节对齐需在控制器手工组 JSON。
  6. `asynq` 计数（`retry=n/m`）与 `errgroup` 首错取消未实现（Go 实际也恒 `return nil`）；
     `ClaimBatch` 用等价的条件 UPDATE 替代 `FOR UPDATE SKIP LOCKED`（H2 不支持），
     极端并发下整组放弃的几行会停到 90 分钟 stale 阈值后回收。
- **本轮新增的通用坑**：
  - **zsh 的 `echo` 会解释反斜杠转义**：`echo "$JSON" > f.json` 会把 Go 正确输出的
    `\n` 转成真换行，导致 golden 文件非法。**保存 curl 输出一律用 `curl -o`**。
  - **zsh 的 `GID`/`UID` 是只读特殊变量**，赋 UUID 会报 "bad math expression"——
    e2e 脚本用 `SVC_ID` 之类名字，或直接用 bash（已收敛到 `scripts/`）。
  - **`scripts/` 里的脚本已固化 JDK 路径探测**：换 shell 后 `./gradlew` 会报
    "Unable to locate a Java Runtime"（Homebrew openjdk 不在默认 PATH）。

- **API Key 体系（横切回补）关键点**：
  - **两套独立授权叠加**：角色维度（`RbacInterceptor`）与能力维度（`APIKeyGateInterceptor`）
    是独立的，Go 里能力判定先于角色判定，且 **`RequireRole` 对 API-Key 主体直接放行**
    （否则 full-access Key 会被角色下限拦住）——Java 侧在 `RbacInterceptor` 加了
    `APIKeyScopeContext.present()` 短路。
  - **门禁层 ≠ 数据面**：门禁只校验"这个路由需要什么能力"，**数据面**还要校验
    "这个 KB 是否在 Key 的白名单内"（`authorizeKnowledgeBases`）。只做前者 = scoped Key
    能访问任意 KB。Java 侧收口点选在 `KnowledgeService.requireKb` / `getKnowledge`
    （所有文档端点都经过它们，一处覆盖全部；Go 是分散在 handler 里逐个调的）。
  - **管理端点刻意不登记 API-Key 策略** → Key 主体 default-deny（Key 不能给自己扩权）。
  - **jsonb 三态**（`null` = full-access / `[]` = scoped 空 / 有值）：不能用 wiki 那套
    "空列表写 SQL NULL" 的 handler——会把三态压成两态。故单独有 `APIKeyRawJsonbTypeHandler`。
  - **`SsrfGuard` 的白名单改为进程级静态**（对照 Go 的包级变量）。原先每实例一份，
    而出站工具类持静态引用，多 Spring 上下文下会互相覆盖——表现为契约测试随机 400
    （真实踩过）。
- **API Key 回补的剩余差异**：
  1. `resolveAPIPrincipal`（API 主体模式 / HMAC 令牌 / `X-External-User-ID`）未翻译 → 等价于
     Go 的回落分支；与 `api-principal-config` 端点配套，建议单独排期。
  2. `userService.GetUserByTenantID` 未翻译 → 租户 Key 一律走 Go 的合成用户兜底 `system-<tenantId>`。
  3. `APIKeyRoutePolicies` 目前只覆盖**已翻译**路由；未翻译模块（agents/sessions/chunks/eval/
     sandbox/系统管理/tenants/**）的策略待各自模块落地时补登记。

- **audit 回补 + 守卫补齐的关键点**：
  - **`RbacInterceptor` 新增 `sysAdminOnly` 语义**：`orSystemAdmin`（放行条件："角色达标**或**系统管理员"）
    与"**仅限**系统管理员"是两回事，用前者表达后者会把租户 Owner 也放进来。
    `/api/v1/system/**` 这类平台端点必须用 `addSystemAdminRule`。
  - **`PathTenantMatch` 已实现**（自动对所有 `/api/v1/tenants/{id}/**` 生效）：URL 里的租户必须
    等于活动租户。没有它时，租户 A 的 Owner 换个 URL 里的 id 就能读租户 B 的审计/密钥列表。
    错误形态逐条对照 Go：空 → 400、非正整数 → 400、上下文无租户 → 401（fail closed）、不匹配 → 403。
  - **中间件分层会改变错误文案**（本轮修正了两条测试）：Go 的 `tenantByID` 组挂着
    `PathTenantMatch`，它在 handler **之前**拒绝，所以 handler 里的 `"Invalid workspace ID"`(code 1000)
    是**不可达死代码**——线上真实返回是中间件的 `"workspace id must be a positive integer"`(code 1010)。
    同理，跨租户操作 API Key 返回 **403**（中间件）而非 404（service 层的租户边界）。
    **写契约测试时先确认拒绝发生在哪一层**，别照 handler 源码的文案写期望。
  - **审计埋点已接线**：`WikiActivityAudit` 的 6 处（manual_create/edit/delete/revert/auto-fix）
    与 `RbacInterceptor` 的拒绝审计都真正落库了。前者此前因缺 bean 退化成 debug 日志，
    后者是 §9 阶段 1 记录的差异 #8。
- **审计模块的已知差异**：
  1. `request_path` 记法是 Spring 的 `{id}`（Go 是 gin 的 `:id`）——同一条路由，字符串差一个符号。
     取模板用 `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`，拿不到时回落 RBAC 规则的 Ant 模式，
     **绝不**回落原始 URI（否则遍历 UUID 会审计去重窗口失效）。
  2. KB 活动的第三个判定用守卫形态（Go handler 内那条同义 AppError 检查不可达，未复刻）。
  3. Wiki 埋点的 details 缺 3 个键（`task_id`/`trigger`/`processing_status`），任务上下文未移植。
  4. `AuditLog.id` 在未落库的内存对象上序列化为 `null`（Go 是 `0`）——读路径恒有值。

## 7.5 派发翻译 agent 的标准约束（每次必带）

每个翻译 agent 的任务书里**必须**包含以下段落。它们对应的是已经踩过的坑，
省掉任何一条都会以某种形式复发。

```
【项目强制约束——以下每条都对应踩过的坑】

1. 先读 docs/translation-conventions.md 的 §3（GORM 隐式行为清单）与 §9（已确认细节与坑）。
   ⚠️ §9 的**正文**已拆到 `docs/known-issues/`（§9 只剩索引）——按你的模块选分片读：
   动 model/service 前优先 `00-foundation.md`，动 chat/SSE 优先 `01-mcp-stream-session.md`，
   收尾批看 `06-wave-5.md`。里面的规则都是复发的来源，写代码前逐条对照，别等到 review。

2. 领域对象上**任何** isXxx() / getXxx() 形式的派生访问器，先判断它在 Go 里是**方法**还是**字段**：
   - 方法 → 必须 @JsonIgnore（否则被 Jackson 当属性写进 jsonb，回读抛
     UnrecognizedPropertyException，整列不可用）
   - 这个坑在阶段 3、4.1 各复发一次，是**复发率最高**的错误。

3. 新增/修改的、会落 jsonb 或直接作响应体的类型，必须在
   `server/src/test/java/com/ragagent/common/JsonContractRoundTripTest.java` 里加一条
   `assertRoundTrips(...)`（工具见 `com.ragagent.common.JsonRoundTrip`，它用严格映射器
   自动抓「漏 @JsonIgnore」与「键名漏蛇形」）。

4. JSON 键名**逐字段对照 Go 的 json tag**：本项目 JSON 是契约。Go 的 tag 是蛇形就写
   @JsonProperty("snake_case")，不要按 Java 字段名输出。MCP/Wiki 里还有协议规定的
   驼峰（如 inputSchema / mimeType），照抄别改。

5. Go 非指针零值语义：string 字段默认 ""、计数器用原始类型（避免插 NULL）、
   omitempty 的 0/空/false 要省略（@JsonInclude(NON_DEFAULT)），无 omitempty 的恒输出。

6. jsonb 回读路径的 ObjectMapper 必须容忍未知属性
   （Go 的 json.Unmarshal 默认忽略，Jackson 默认失败），否则历史行读不出来。

7. 测试**禁止依赖真实网络**：
   - 不写真实公网域名（本机 DNS 可能把 api.openai.com 解析到 Teredo 段而被 SSRF 拒绝）
   - 需要出站校验时注入白名单（`SsrfGuard.reloadWhitelist(...)` /
     `LlmTransport.setSsrfGuard(...)`）或用 stub server

8. 测试命令**只跑你负责的包**，不要跑全量：
   `./gradlew test --tests "com.ragagent.<你的包>.*"`
   多个 agent 同时跑全量会争抢 build 目录（OOM / test-results 被并发写坏 → 假失败）。

9. 只改你负责的目录。`config/WebConfig.java` 的路由注册、`TestSchema.java` 的表结构
   （除非任务明确要求新增表）由主会话统一处理，避免并发写冲突。

10. 报告里必须给出：翻了的文件、**暴露给后续模块的关键签名**、测试数量与结果、
    与 Go 的已知差异、需要主会话决策的点。
```

**为什么要降并行度**：并行 agent 争抢 gradle/build 目录造成的 OOM 与"假失败"重跑，
在阶段 4 浪费了至少两轮。宁可串行，也别让两个 agent 同时跑全量测试。

## 8. 翻译日志（每完成一个模块更新）

| 模块 | Go 源 | Java 目标 | 状态 | 备注（踩坑/GORM 清单/SSE emit 表位置） |
|---|---|---|---|---|
| 骨架 | internal/router/、internal/errors/、internal/middleware/{error_handler,auth}.go | com.ragagent.common.{error,context,filter,web} + config.WebConfig | ✅ | 错误格式两种形态已确认（见 §9）；401 三态已锁定；Flyway 对 Go 数据 baseline 验证通过 |
| auth/租户（阶段 1） | internal/middleware/auth.go、auth_context.go、access.go；internal/application/service/user.go（Login/ValidateToken/generateTokensForTenant/resolveLoginTenantID 链）；internal/handler/auth.go(Login)、dto/{auth,tenant}.go；types/{user,tenant,tenant_member,principal}.go | com.ragagent.auth.{domain,mapper,dto,service,filter,controller} + config.{TenantProperties,JacksonConfig} | ✅ | 10 条新 golden 全过（H2 种子+掩码比对）；e2e 连 dev DB 验证通过。关键坑见 §9 |
| 模型配置（阶段 2） | internal/handler/model*.go、weknoracloud.go；internal/application/service/{model,weknoracloud}.go；internal/types/model.go、builtin_models_config.go；internal/models/provider/*；internal/utils/{security.go(SSRF),crypto.go}；internal/middleware/rbac.go（RequireRole 子集）；internal/application/repository/{model,model_usage}.go | com.ragagent.model.{domain,mapper,dto,service,controller} + com.ragagent.common.{crypto,security,web.RbacInterceptor/PgJsonTypeHandler} | ✅ | 9 条新 golden 全过；providers 响应与 Go 实录字节一致；AES-GCM 落库密文/回读解密 e2e 验证。关键坑见 §9 |
| 知识库（阶段 3） | internal/handler/{knowledgebase,knowledge}.go；internal/application/service/{knowledgebase,knowledge,knowledge_create,knowledge_process}.go；internal/application/repository/{knowledgebase,knowledge}.go；internal/types/{knowledgebase,knowledge,knowledge_folder}.go；internal/infrastructure/docparser/(gRPC 客户端)；internal/chunker/*；internal/utils/{storage,security}(SSRF) | com.ragagent.knowledge.{domain,mapper,dto,service,controller,chunker}（KnowledgeProcessWorker=进程内虚拟线程队列，对照 asynq；DocReaderClient gRPC；EmbedderClient；VectorStoreService） | ✅ | 20 条 golden 全过（KB CRUD+文档 CRUD，含 409 duplicate 特殊信封）；e2e 连 dev PG：上传→docreader 解析→chunk 落库→无 embedding 模型按契约 failed。关键坑见 §9 |
| LLM 调用客户端（阶段 4.0） | internal/models/chat/*（26 文件）；internal/models/provider/*（30 文件）；internal/models/limiter/*；internal/models/utils/ollama/ | com.ragagent.llm.{domain,chat,provider,limiter,ollama}（LlmChatClient 接口；RemoteApiChat/AnthropicChat/OllamaChat；ProviderAdapter 13 实现）+ LlmChatClients 工厂 | ✅ | 368 测试全绿（本模块 ~330）。Java 侧把 Go 的「SDK 路径 vs 裸 HTTP 路径」合并为 ObjectNode 单路径。关键简化与已知差异见 §9 |
| MCP 服务管理（阶段 4.1） | internal/mcp/*（自研协议客户端：client/manager/oauth_*+SSRF）；internal/types/mcp*.go；internal/application/{repository,service}/mcp*.go；internal/handler/mcp_*.go + dto/mcp.go；internal/agent/approval/*（提前翻译以解耦） | com.ragagent.mcp.{domain,protocol,oauth,mapper,service,dto,controller} + com.ragagent.agent.approval | ✅ | 22 端点全落地；17 条 golden（CRUD/审批/凭据/SSRF 拒绝/403/404）掩码比对通过。e2e 在真 PG 验证：密钥加密落库（enc:v1:）+ **跨语言双向互操作**（同 key 下 Go 写 Java 读、Java 写 Go 读均成功）。关键坑见 §9 |
| Wiki（阶段 4.2） | internal/handler/wiki_page.go；internal/application/service/{wiki_page,wiki_lint,wiki_slug_handles,wiki_linkify,wiki_ingest*}.go；internal/application/repository/wiki_page.go；internal/types/{wiki_page,interfaces/wiki_page}.go；internal/agent/prompts_wiki.go | com.ragagent.wiki.{domain,mapper,service,prompt,controller} | ✅ | 21 端点全落地；13 条 golden（CRUD/文件夹/聚合读/权限，掩码比对）+ **真 PG 上 5 个读端点 A/B 全部 MATCH**，且 Java 写的行 Go 读回一致。关键坑见 §9 |
| API Key 体系（横切回补） | internal/types/tenant_api_key.go；internal/middleware/api_key_gate.go；internal/application/{repository,service}/tenant_api_key.go；internal/handler/tenant.go 的 API Key 段 | com.ragagent.apikey.{domain,mapper,service,filter,controller} | ✅ | 25 条能力 + scope + 路由策略表；门禁拦截器接入 WebConfig（order -1，先于角色维度）；AuthFilter 通道 3 换真实鉴权；**数据面 KB 白名单已收口**（requireKb / getKnowledge）。120 新测试 |
| audit 审计（横切回补） | internal/types/audit_log.go；internal/application/{service,repository}/audit_log*.go；internal/handler/audit_log.go | com.ragagent.audit.{domain,mapper,service,controller} | ✅ | 62 个 AuditAction；3 端点；**接上了既有埋点**：WikiActivityAudit 的 6 处 + RbacInterceptor 的拒绝审计（§9 阶段 1 差异 #8 的正式收口）。golden A/B 实测（空页 `[]` 非 null、1010 文案、request_path 存路由模板） |
| stream 流管理器（阶段 5 起步） | internal/stream/{factory,memory_manager,redis_manager}.go；internal/types/interfaces/stream_manager.go | com.ragagent.stream.{StreamEvent,StreamBatch,LiveRun,StreamManager,MemoryStreamManager,RedisStreamManager,StreamJson,GoJsonEscapes,StreamManagerConfig} + config.StreamProperties | ✅ | 35 测试（**起真 redis-server** 跑 3 个 Lua 脚本/CAS/TTL）+ 契约往返 3 条。它不落 jsonb 也不作响应体，却是 Go 与 Java **共用同一批 Redis 键**的契约，故按字节对齐（HTML 转义/小写十六进制/map 排序）；跨语言互操作已实测。关键点见 §9 |
| agent_steps 类型收紧（阶段 5.2 步 3 上） | internal/types/{agent,message}.go；internal/storageurl/request.go 的 RewriteMessages* | com.ragagent.agent.domain.{AgentStep,ToolCall,ToolCallTarget,ToolResult} + session.domain.{AgentStepListTypeHandler,SearchResultListTypeHandler} + common.web.{GoTimeSerializer,GoTimeDeserializer} + storageurl.Rewriter.rewriteMessages* | ✅ | 30 测试（12 逐字节 + 18 往返/重写）。**修掉了消息响应体上一个既有契约偏差**：jsonb 透传时元素退化成 LinkedHashMap，键序变成 PG 规范化序。关键坑见 §9 |
| continue-stream 端点（阶段 5.2 步 4） | internal/handler/session/stream.go L29-204 + resource_urls.go L56-174 | com.ragagent.session.{controller.SessionStreamController,sse.SseFrameWriter,sse.StreamEventEmitter} + WebConfig 路由 + APIKeyRoutePolicies | ✅ | **第一次真正的 SSE A/B：四条路径逐字节 MATCH**（见 §9）。帧骨架、HTML 转义、扣留重组全部对齐 |
| datasource 连接器层（波 0） | internal/datasource/**（框架 + 8 个连接器子包） | com.ragagent.datasource.{,connector.*} | ✅ | 补 737 测试（829 总数），全打 127.0.0.1 stub。**RSS 的 gofeed/go-readability/html-to-markdown 无 Java 等价物 → 显式接缝降级**（唯一实质降级）。⚠️ 套件 2855 条后测试堆 1g 不够，已提到 2g |
| datasource 类型+仓储（波 0） | internal/types/datasource.go；internal/application/repository/datasource_repo.go | com.ragagent.datasource.{domain,mapper} | ✅ | 92 测试（42 JSON 逐字节 Go 实录 / 50 H2 仓储）。TestSchema 加 data_sources/sync_logs（迁移 000029 唯一来源） |
| memory 实体+仓储（波 0 第 2 步） | internal/types/{memory,memory_extraction}.go 的实体；internal/application/repository/memory{,_extraction,_lifecycle,_vector}.go | com.ragagent.memory.{domain,mapper} + MemoryContext | ✅ | 187 测试（其中 52+23+17 是 H2 仓储、其余是实体/纯函数）。PG 专属路径（ON CONFLICT / FOR UPDATE / halfvec）在 dev PG 上手跑验过。关键坑见 §9 |
| 会话/消息最小读路径（阶段 5.2 步 3 下） | internal/application/service/{session,message}.go 的读方法 + loadSessionForRead | com.ragagent.session.service.{SessionService,MessageService,SessionLookupScope} | ✅ | 14 测试（授权判定）。`Session.requiresAdminConsoleRead` 阶段 5.1 已落地，本步只补 service 层的两条读路径与 Admin 回退 |
| storageurl（阶段 5.2 步 2） | internal/storageurl/{mode,storageurl,stream,resolver,request}.go | com.ragagent.storageurl.{Mode,StorageUrlContext,ResourceModeException,PublicModeForbiddenException,Resolver,Rewriter,StreamRewriter,FileServiceResolver,FileService,StorageBackendResolver} | ✅ | 49 测试。**这是第一处跨 5 个 handler 的共享契约**（message/knowledgebase/session/embed/im 都 import 它）。扣留缓冲 + 模式解析全部按 Go 对等移植；差分语料见 §9。已知差异：provider 级文件服务未翻译 |
| SSE 契约层（阶段 5.2 步 1） | internal/handler/session/helpers.go L182-249（setSSEHeaders / buildStreamResponse / sendCompletionEvent / searchResultFromMap）；internal/types/search.go 的 SearchResult；internal/types/json.go 的 JSON | com.ragagent.session.sse.{SseContract,StreamResponseBuilder} + com.ragagent.retrieval.domain.SearchResult + com.ragagent.common.web.{GoDoubleSerializer,GoMapSerializer} | ✅ | 41 个新测试（28 浮点语料 + 12 SSE 逐字节 + 1 往返）；**期望值全部是 Go 实录**（把 helpers.go 的三个函数原样抄进独立 Go 程序跑出来的 `json.Marshal`）。emit 表见 `StreamResponseBuilder` 类注释。关键坑见 §9 |
| memory HTTP 层（波 0 第 4 步，**memory 模块收官**） | internal/handler/memory.go（465 行）；internal/router/routes_memory.go（16 条路由） | com.ragagent.memory.controller.MemoryController + config.WebConfig 路由 + apikey.filter.APIKeyRoutePolicies | ✅ | 16 端点全落地；34 条新测试（**22 个 golden 全部是 Go 实录**）+ **真 PG 上 36 组 A/B（35 MATCH / 1 已知差异）**（唯一 DIFF 是非法 JSON 的 details 文案，已知差异）。关键坑见 §9 |
| datasource service+HTTP 层（波 0，**datasource 模块收官**） | internal/application/service/datasource_service.go（1488）；internal/handler/{datasource,datasource_credentials}.go；internal/router/routes_infra.go L292-333（17 条路由）；internal/container 的 initConnectorRegistry/startDataSourceScheduler | com.ragagent.datasource.{service,controller,dto} + config.WebConfig + apikey.filter.APIKeyRoutePolicies | ✅ | 17 端点全落地；88 条新测试（50 service + 38 契约，**golden 全是 Go 实录**）+ **真 PG 上 39 组 A/B 全 MATCH**（含**双向跨语言互读**与**一次真实 RSS 同步的终态计数**）。关键坑见 §9 |
| session CRUD+pin（波 1 G1） | internal/application/service/session.go 的写方法（L188-668）；internal/handler/session/handler.go L123-582；internal/router/routes_chat.go L53-83 | com.ragagent.session.service.SessionService 写路径扩展 + controller.SessionController（8 条端点）+ apikey.filter.APIKeyRoutePolicies + common.web.GoJsonBindError | ✅ | 8 端点全落地；34 条新契约测试（**golden 全是 Go 实录**）+ **真 PG 上 34 组 A/B 全 MATCH**（2988 测试全量绿）。**golden 实测纠正了三处预实现**：渠道 source 拒绝是 500 双前缀（非 403）、page=0 被 omitempty 跳过、`queryPaged` 的 is_pinned 映射缺陷。关键坑见 §9「波 1 G1」 |
| 消息面（波 1 G2） | internal/handler/message.go（347 行）；internal/application/service/message.go 的读/删/搜/统计方法；internal/application/repository/message.go 的两条检索查询；routes_chat.go L16-33 + L59 | com.ragagent.session.{service.MessageService 扩展,controller.MessageController,domain.MessageWithSession/MessageSearchGroupItem/MessageSearchResult/ChatHistoryKbStats} + SessionController 补 ClearSessionMessages + APIKeyRoutePolicies | ✅ | 5 端点全落地（4 条 /messages + 清空）；23 条新契约测试（golden 全是 Go 实录）+ **真 PG 上 25 组 A/B 全 MATCH**（3011 全量绿）。**golden 抓到的关键契约**：search 的 match_type 全是 "hybrid"（partner 补对的空 matchType 与 "keyword" 合并所致）。关键坑见 §9「波 1 G2」 |
| 追问建议（波 1 G3） | internal/handler/message_suggestion.go（156 行）；internal/application/service/message_suggestion.go 的 Ensure/Get/RecordEvent/suppress 与包级辅助 | com.ragagent.session.{service.MessageSuggestionService,controller.MessageSuggestionController} + APIKeyRoutePolicies | ✅ | 3 端点全落地；17 条新契约测试 + **真 PG 上 18 组 A/B 全 MATCH**（3028 全量绿）。**已知差异**：LLM 生成步降级为 failed/generation_error（运行时模型工厂随阶段 7、知识推荐随波 2/4）；未配置 follow-ups 的默认路径逐字节一致。关键坑见 §9「波 1 G3」 |
| 产物+title+stop（波 1 G6） | internal/handler/session/{artifact_download.go,title.go,stream.go 的 StopSession}；service/session.go 的 GenerateTitle | SessionController 追加 5 条端点 + llm.domain.ResponseType 补 STOP + MessageService.getSessionArtifacts + SessionService.generateTitle | ✅ | 5 端点全落地；22 条新契约测试 + **真 PG 上 22 组 A/B 全 MATCH**（3050 全量绿）。**golden 抓回两个真缺陷**：stop 的 Long 引用比较（陷阱 §5.6 复发）、AbstractJsonListTypeHandler 缺 JSR310 模块（artifacts 列整列不可读）。关键坑见 §9「波 1 G6」 |
| steer（波 1 G4） | internal/handler/session/steer.go 的 4 个 HTTP 端点（L461-810）+ 包级辅助（parseSteerDelivery/selectSteerBacklog/pendingSteerQueueItems/steerEvent） | com.ragagent.session.controller.SteerController + APIKeyRoutePolicies + BizException 补 serviceUnavailable | ✅ | 4 端点 HTTP 面全落地；16 条新测试（11 golden + 5 条直种 streamManager 的排队路径单测）+ **真 PG 上 12 组 A/B 全 MATCH**（3066 全量绿）。**范围说明**：live run 只能由 agent 引擎设置——排队/注入路径的引擎侧（PollSteer/follow-up 交接）随波 4/5，HTTP 面已对齐。关键坑见 §9「波 1 G4」 |
| 临时文档 attachments（波 1 G5，**波 1 收官**） | internal/handler/session/temporary_document.go（174 行）；service/temporary_document.go 的 Create/Get/List/Delete/OpenFile/Process（L133-348 + parse L350-449 的纯文本/docreader 路径）；repository/temporary_document.go；filetransport/response.go；file/local.go 的 SaveBytes/GetFile/DeleteFile | com.ragagent.session.{service.TemporaryDocumentService,service.AttachmentFileStore,controller.TemporaryDocumentController,mapper.TemporaryDocument*,domain.TemporaryDocument} + common.web.{GoNaiveTimeSerializer,GoNaiveOffsetDateTimeTypeHandler,ContentTypeByFilename} + TestSchema.temporary_documents | ✅ | 5 端点全落地；11 条新契约测试（golden 全是 Go 实录）+ **真 PG 上 11 组 A/B 全 MATCH**（3077 全量绿）。**顺手验证了 chunker(auto/1600/160)+ApproxTokenCount 与 Go 逐字节一致**。关键坑见 §9「波 1 G5」 |
| chunk 编辑面（波 2 第一批） | internal/handler/chunk.go（472 行，10 端点）；internal/application/service/{chunk,chunk_write}.go + knowledge_write.go 的 loadKnowledgeWrite + access/knowledge_state.go + searchutil/{imageinfo*,chunkmerge}；internal/application/repository/chunk.go 的 14 个方法；internal/middleware/{rbac.go 的 RequireOwnershipOrRole,kb_access.go 的 KBIDFrom*Param}；types/{chunk,faq} 的 GeneratedQuestion* | com.ragagent.knowledge.{domain.ChunkRevision/domain.DocumentChunkMetadata/domain.GeneratedQuestion,mapper.ChunkRepository/mapper.ChunkRevisionMapper,mapper.ChunkAccessGuard,service.ChunkService,service.ChunkSearchUtil,controller.ChunkController} + common.CleanInvalidUtf8 + WebConfig 读规则 + APIKeyRoutePolicies chunks 段 + TestSchema.chunk_revisions | ✅ | 10 端点全落地；81 条新测试（18 仓储 + 28 service + 35 契约，golden 全是 Go 实录）+ **真 PG 上 46 场景 A/B 全 MATCH**（3161 全量绿）。**golden 抓回 clamp 误写**（page 钳成恒 1、size 小值被抬高）。已知差异：syncChunkIndex 引擎未接线恒 failed、Regenerate 的 LLM 步降级（随波 3/4、阶段 7）。关键坑见 §9「波 2 chunk」 |
| FAQ（波 2 第四批） | internal/handler/faq.go（607 行，12 端点）；internal/application/service/faq*.go；internal/application/repository/chunk.go 的 FAQ 方法子集；internal/types/faq.go 的 Sanitize/Normalize/ContentHash；internal/textconv/*；internal/handler/dto（导入导出形态） | com.ragagent.knowledge.{controller.FaqController,service.FaqService（含 FaqImportTaskStore 进程内进度/running 锁）,domain.FaqChunkMetadata,dto.FaqDtos,mapper.KnowledgeTagRepository 扩展} + knowledge/textconv.TextConv（OpenCC 词典 vendor）+ ChunkMapper 的 FAQ SQL（PG 原版/H2 等价双路径） | ✅ | 12+1 端点全落地（progress 路由为可观察性补充）；98 golden + 13 契约测试；**golden 抓回**：FAQ 的 500 是"无细节"形态、先落库后 500、mode 缺失即 400、"分页参数不合法"单独文案。已知差异：asynq→进程内（无 retry，faq-upsert-running 为 A/B 预期差异）、向量索引随波 4。关键坑见 §9「波 2 FAQ 补充」 |
| 基础设施配置三组（波 2 第五批） | internal/handler/{web_search_provider,web_search_provider_credentials,vectorstore,storagebackend,web_search}.go；internal/application/service/{web_search_provider,vectorstore,vectorstore_healthcheck,storagebackend}.go；internal/types/{web_search_provider,...}；router/routes_infra.go L206-283 | com.ragagent.websearch.{domain,mapper,service,controller,dto}（13 provider 元数据）+ com.ragagent.vectorstore.{...}（env stores 10 driver 全量）+ com.ragagent.storage.{...}（复用 knowledge.domain.StorageBackend）+ WebConfig/APIKeyRoutePolicies/TestSchema.{web_search_providers,vector_stores} | ✅ | 30 端点全落地；110 golden + 13 契约测试 + A/B 全 MATCH。**A/B 抓回三缺陷**：①自定义 jsonb TypeHandler 写库必须 setObject(Types.OTHER)；②types.StoredResource.TableName()="resources" 被误译成 stored_resources 影子表；③create 响应缺 AutoCreateTime 回写（恒 year-1）。已知差异：test 端点的真实外网执行步降级、云存储连通为 TCP 拨号近似。关键坑见 §9「波 2 基础设施配置三组补充」 |
| 成员/邀请/api-principal（波 2 第六批） | internal/handler/tenant_member.go（442 行）+ tenant_invitation.go（703）+ tenant_invite_link.go（111）+ tenant.go 的 api-principal 段（L869-1130）；internal/application/service/tenant_member*.go | com.ragagent.auth.{controller.TenantMember/TenantInvitation/TenantAPIPrincipalController,service.TenantMember/TenantInvitation/TenantService 扩展,domain.APIPrincipalConfig} + WebConfig/APIKeyRoutePolicies + TestSchema.tenant_invitations | ✅ | 17 端点全落地；91 golden + 23 契约测试 + A/B 全 MATCH。**真缺陷：clearStaleHomeTenant 必须写 SQL NULL**（updateById 写 0 炸 FK fk_users_tenant；preferences jsonb 两参 set 炸 MyBatisSystemException）。契约场景：B 的"曾入成员→登录→成员行被删"token 走 403、"从未是成员"token 走 tenantless 200。关键坑见 §9「波 2 成员/邀请/api-principal 补充」 |
| 系统管理端+评估（波 2 收官批） | internal/handler/system.go（2393 行）+ deployment_capabilities.go + evaluation.go（131 行）；internal/application/service/{system_setting 相关,vectorstore 健康检查,storagebackend 复用}；router/routes_auth_tenant.go L246-335 + routes_infra.go L81-89 | com.ragagent.system.{domain.SystemSetting,mapper,service.{SystemSettingService/Registry,SystemAdminUserService,SystemInfoService,ParserEngineRegistry,DeploymentCapabilitiesHolder},controller.{System,SystemAdmin}Controller}（14 文件）+ com.ragagent.evaluation（4 文件，执行步降级）+ common.error.PlainErrorException + RbacInterceptor sysAdminOnly 文案纠正 + TestSchema.system_settings | ✅ | 22+2 端点全落地（sandbox-check 留波 3 占位 404）；53 golden + 26 契约测试 + A/B 三轮稳定全 MATCH（capabilities/db_version/evaluation 执行态按部署 XDEP）。**golden 纠正**：RequireSystemAdmin 文案、ParserEngineInfo 无 json tag（键为驼峰）；**真缺陷**：UserKbPin 列映射（真表 kb_id/pinned_at）、anydoc 文案缺尾段、createTenant 漏 business 零值。关键坑见 §9「波 2 系统管理端补充」 |
| knowledge 文档操作面（波 2 第二批） | internal/handler/knowledge.go 的 15 端点（L608-2791）+ knowledgebase.go 的 ClearKnowledgeBaseContents；internal/application/service/{knowledge,knowledge_process,knowledge_create,knowledge_summary_refresh}.go 对应方法 + knowledge_write.go 批量校验；internal/types/{knowledge_folder,tag,knowledge_span}.go；internal/application/repository/{knowledge_tag,tag}.go；internal/filetransport/response.go；internal/application/service/file/local.go 的 GetFile | com.ragagent.knowledge.{controller.KnowledgeController 扩展,service.KnowledgeService 扩展,service.KnowledgeAccessGuard,dto.SpanTree,domain.KnowledgeTag,mapper.KnowledgeTagMapper} + common.security.InputSanitizer + LocalStorageService.readChecked/baseDir + WebConfig 规则 + APIKeyRoutePolicies + TestSchema.{knowledge_tags,knowledge_tag_relations} | ✅ | 15+1 端点全落地；14 条契约测试（**121 个 kg-* golden 全是 Go 实录**，含下载/预览的响应头与字节）+ 既有 knowledge/chunk/common 套件 234 测试全绿。**golden 抓回四个真契约**：EnsureDefaults 把"全关索引"重置成 vector+keyword（image 链恒 500 "model ID cannot be empty"）、ValidateInput 放行 `<script>x`（无闭合标签）、tags 无 kb_id 的跨租户 403 文案不带 "base"、clear-contents 两次连续调用都返回 task submitted。已知差异：批量删除/清空/重解析为同步尽力而为（HTTP 契约一致）、summary/vector 的模型运行时随阶段 7。关键坑见 §9「波 2 knowledge 文档操作面」 |
| knowledge 搜索与移动/复制（波 2 第三批，**knowledge 域收官**） | internal/handler/knowledge.go 的 SearchKnowledge/MoveKnowledge/GetKnowledgeMoveProgress（L2149-2543）+ knowledgebase.go 的 HybridSearch/CopyKnowledgeBase/DuplicateKnowledgeBase/GetKBCloneProgress（L318-1112）；internal/application/service/{knowledge.go 的 Search*,knowledge_clone_move.go,knowledge_transfer.go,knowledgebase.go 的 Duplicate/Copy,knowledgebase_search.go+storegroup 的 HybridSearch 前置段}；internal/application/access/kb_transfer.go；internal/utils/taskid.go；internal/handler/{task_progress_auth.go,list_pagination.go 的 parseOffsetPagination} | com.ragagent.knowledge.{controller.{KnowledgeController 扩展,KnowledgeBaseController 扩展},service.{KnowledgeService 扩展（search/move/clone/duplicate/task-id/兼容性）,KnowledgeTaskProgressStore},dto.KnowledgeTaskDtos,KnowledgeBaseResponseBuilder 的 includeEngineType 重载} + WebConfig 规则 + APIKeyRoutePolicies | ✅ | 8 端点全落地；**76 个 ks-* golden 全是 Go 实录** + 7 条契约测试（异步用例轮询到 completed 再比对终态）+ 套件 242 测试全绿。**golden 抓回的真契约**：move 的 binding 校验把全部失败字段按 struct 序 join("\n") 进 message、copy 的 binding 错误在 **details**（与 move 的 message 前缀形态刻意不同）、跨租户 source 在 copy 是 403 "Permission denied..."（ResolveKB）而 move 是 handler 的 "No permission to access source..."、duplicate 的 404 是路由中间件的小写 "knowledge base not found"（handler 的 "Source..." 不可达）、worker 覆写进度**不带 created_at**（终态 created_at:0）、duplicate 无 vector_store_engine_type 键（envStores 空）而阶段 3 的 kb-get golden 有（部署状态漂移）。已知差异：hybrid-search 的检索执行随波 4（当前恒 "data":null）、move/clone 只做到行级、进度存储为进程内 map。关键坑见 §9「波 2 knowledge 搜索与移动/复制」 |
| auth 注册族（波 2 扫尾批 1） | internal/handler/auth.go 的 Register/AutoSetup/GetAuthConfig/ValidateToken/GetCurrentUser/UpdateMyPreferences/ChangePassword（L85-1024 的剩余段）+ auth_register_by_invite.go（全文）；internal/application/service/{user.go 的 Register/ChangePassword/UpdateUserPreferences,tenant.go 的 CreateTenant/createDefaultStorageBackend,tenant_invitation.go 的 token 路径,password_policy.go}；internal/types/{user.go 的 RegisterRequest/UserInfo/RegisterResponse/UserPreferences,tenant.go 的 BeforeCreate} | com.ragagent.auth.{controller.AuthController 重写（+9 端点）,service.{PasswordPolicy（新）,UserService 扩展,TenantService.createDefaultStorageBackend},dto.{RegisterRequest/RegisterResponse/UserInfo/UpdatePreferencesRequest/InvitationLookup*/RegisterByInviteRequest/ChangePasswordRequest},mapper.UserMapper.insertTenantless} + config.TenantProperties 补 selfServiceCreationEnabled + system.service.SystemAdminUserService 委托 PasswordPolicy + TestSchema（复用） | ✅ | 9 端点全落地；**46 个 reg-* golden 全是 Go 实录** + 8 条契约测试（场景顺序严格复刻录制脚本）+ **真 PG 上 46 组 A/B 两轮稳定全 MATCH**。**A/B 抓回真缺陷**：tenantless 注册的 user insert 走 getter 把 null 归一成 0 → 真 PG 违反 fk_users_tenant（H2 无 FK 不暴露）→ UserMapper.insertTenantless 省略该列（对照 GORM Omit）。**golden 纠正**：UserPreferences 三字段蛇形 tag（browser_search_instructions/last_active_tenant_id/oidc_only_login）、context_config 零值对象恒输出（见 §9「波 2 扫尾批 1」）。login-success.json golden 随当前 Go 二进制重录（9/17 旧版无 context_config 键）。关键坑见 §9「波 2 扫尾批 1」 |
| auth OIDC（波 2 扫尾批 2） | internal/handler/auth.go 的 GetOIDCAuthorizationURL/OIDCStart/GetOIDCConfig/OIDCRedirectCallback（L311-505，含 setOIDCNonceCookie/oidcCallbackURL/decodeOIDCState/urlQueryEscape）；internal/utils/oidc_state.go（全文）；internal/application/service/user.go 的 GetOIDCAuthorizationURL(L435)/LoginWithOIDC 门控段(L484-500)/getOIDCConfig(L1518)/populateOIDCEndpoints(L1542)/validateOIDCEndpoints(L1488)；internal/config/config.go 的 OIDCAuthConfig+env 覆盖+缺省段（L305-323/L690-748）；internal/types/user.go 的 OIDC*Response（L149-181） | com.ragagent.auth.{service.{OidcConfig,OidcStateCodec,OidcService}（新）,controller.AuthController（+4 端点+302/cookie/escaper 辅助）,dto.{OidcConfigResponse,OidcAuthUrlResponse}} | ✅ | 4 端点全落地（未配置=disabled 分支全覆盖）；**13 个 oidc-* golden 全是 Go 实录**（302 端点用合成信封 JSON：body/location/set_cookie/status）+ 5 条契约测试 + 往返 4 条 + **真 PG 上 13 组 A/B 两轮全 MATCH**（字节比对，无掩码项）。**翻译边界**：enabled 后的 discovery 抓取与 code 交换/userinfo/provisioning 整体推迟（dev 两侧恒 disabled 不可达），抛自造 OidcException；config.yaml 的 oidc_auth 段无 Java 加载器，仅实现 env+缺省两层（dev 等价）。关键坑见 §9「波 2 扫尾批 2」 |
| 跨空间租户目录 + KV 配置（波 2 扫尾批 3） | internal/handler/tenant.go 的 ListAllTenants(L1192)/SearchTenants(L1218)/CreateTenant(L226-513)/GetTenantKV+UpdateTenantKV(L1304-1395)/六个 KV 子 handler(L1398-1901)/validateParserEngineOutboundURLs(L1904)；internal/application/service/tenant.go 的 CreateTenant/createDefaultStorageBackend/SearchTenants；router/routes_auth_tenant.go L53-81；internal/types/tenant.go 的 WebSearchConfig/ParserEngineConfig/StorageEngineConfig/ChatHistoryConfig/RetrievalConfig + PreserveIfRedacted 族 | com.ragagent.auth.{domain.tenantconfig（5 类型+TenantConfigRedaction，新）,controller.TenantCatalogController（新，5 端点）,domain.Tenant 注解,service.{TenantService+listAll/search/validateStorageBucketUniqueness,TenantMemberService+ensureOwner}} + config.{TenantProperties 第 4 组件 maxOwnedPerUser,WebConfig +crossTenant 规则×2+kv 角色下限} + common.web.RbacInterceptor（crossTenant 分支 + PathTenantMatch 门控修正）+ apikey.APIKeyRoutePolicies +5 条 + system.SystemSettingRegistry +3 键 | ✅ | 5 端点全落地；**65 个 ct-* golden 全是 Go 实录**（63 flag-on + 2 flag-off，含 settings 切换链）+ 11 条契约测试（含 flag-off 小类）+ 往返 5 条 + **真 PG A/B 62 MATCH + 1 EXPECTED-DIFF**（prompt-templates GET 为 Go 独有 vendor yaml，Java 推迟 → 400，ab-ct.sh 清单列明）。**golden 抓回**：Tenant.StorageUsed 零值 0（Java 实体 Long 默认 null）；**录制脚本坑**：布尔设置 PUT 必须 JSON bool。掩码族：uuid/时间戳/数字 id/api_key 明文/SSRF 解析 IP。关键坑见 §9「波 2 扫尾批 3」 |
| 用户收藏 + chunker 预览（波 2 终扫批，**波 2 全部收官**） | internal/handler/{user_resource_favorite.go,chunker_debug.go}；internal/application/{repository,service}/user_resource_favorite.go；internal/types/user_resource_favorite.go + interfaces 同名；internal/router/{routes_agent.go RegisterUserFavoriteRoutes（3 条，**HANDOFF 旧写 4 条是笔误**）,routes_knowledge.go RegisterChunkerDebugRoutes（1 条）} | com.ragagent.favorite.{domain.UserResourceFavorite,mapper.UserResourceFavoriteMapper（复合主键→纯 SQL）,service.UserResourceFavoriteService,controller.UserFavoriteController} + knowledge.controller.ChunkerDebugController + knowledge.chunker 诊断层（TierRejection/Diagnostics/SplitResult/splitWithDiagnostics/splitParentChildWithDiagnostics + ParentChildSplit 内部重构） + WebConfig（rbac×4 + 拦截器路径 +2） + APIKeyRoutePolicies（+1） + TestSchema.user_resource_favorites | ✅ | 3+1 端点全落地；**32 条新 golden 全是 Go 实录**（fav-* ×20 + cprev-* ×12，cprev 全确定零掩码）+ 7 条契约测试 + **真 PG A/B 32 场景两轮 ALL MATCH（首轮即全对，唯一掩码项 created_at；3298 全量绿）**。**golden 钉死**：GORM Find 空结果 `"data":[]` 非 null、空 strategy=legacy 非 auto、rejected nil→null、preview 错误体是裸 gin.H 非信封。关键坑见 §9「波 2 终扫批」 |
| sandbox 配置 CRUD 面（波 3 子批 1，**波 3 开工**） | internal/handler/sandbox_config.go（8 端点）；internal/application/service/tenant_sandbox_config.go 的 CRUD 子集（Sanitize/Create/List/Get/Update/Delete/workspace-policy/inventory 前置）；internal/application/repository/tenant_sandbox_config.go（全文）；internal/types/{tenant.go L613-955 的 TenantSandboxConfig 族,tenant_sandbox_config_entity.go,config_redaction.go L271-398,sandbox_network_policy.go}；internal/sandbox/{sandbox.go,docker_enabled.go,config_required.go,url_guard.go,tenant_config.go 的类型与校验层} | com.ragagent.sandbox.{domain（TenantSandboxConfig 家族+SandboxNetworkPolicy+SandboxConfigRedaction+TenantSandboxConfigEntity+TypeHandler 字段级 AES）,runtime（SandboxTypes/BackendPolicy/CubeDns/OutboundUrlGuard/SandboxConfigRequirements/EffectiveConfigResolver/SandboxIdentity/ConfigSandboxClient 接缝；第五包名）,mapper（配置+tenant_skills 只读投影）,service（TenantSandboxConfigService+SandboxClientFactory 接缝+Skills 只读店+错误类型族）,controller.SandboxConfigController} + WebConfig rbac×8 + APIKeyRoutePolicies fullAccess×8 + TestSchema.{tenant_sandbox_configs,tenant_skills} | ✅ | 8 端点全落地；**27 条 sbx-* golden 全是 Go 实录**（uuid/ts 掩码；api_key/env_vars 两侧投影恒 "***"）+ 5 条契约测试 + 86 条域/服务/冒烟测试 + **真 PG A/B 27 场景两轮 ALL MATCH（首轮即全对；3358 全量绿）**。**golden 钉死**：URL 守卫先于必填校验（cube-incomplete 落私网拒绝分支）、三种 unsupported-type 文案（named-configs/template-catalog 变体）、409 固定文案 inventory_unverifiable、Inventory 恒 200 {sandbox_count,unverifiable}、docker 恒禁用（env 未设）。关键坑见 §9「波 3 sandbox 子批 1」 |
| sandbox-check + templates provider 面（波 3 子批 2，主会话接力） | internal/handler/sandbox_check.go（567 行全文）；internal/application/service/tenant_sandbox_config.go 的 QueryTemplates provider 段（L619-691）+ 纯函数族（L693-922）；internal/sandbox/{remote_errors.go 全文,tenant_resolver.go 的 NewRemoteClientForCheck,cube/e2b/docke r_remote_client.go 的 Health/Capabilities 切片,template_catalog.go 判定助手} | com.ragagent.sandbox.{runtime.{RemoteErrorKind,RemoteError（httpErrorKind+传输分类）,RemoteTemplate（含 isStandardTemplate/normalizeImageRepository 判定）,RemoteProviderClient（薄 HTTP+路由常量）,RemoteConfigSandboxClient（Health/ListTemplates/Create/list/delete）},service.{RemoteSandboxClientFactory（替换 Unwired）,TemplateCatalogSupport 纯函数族,queryTemplates provider 段},controller.{SandboxCheckController（老式 code/msg + 200 结构化 + sandboxCheckReason 固定文案）,SandboxConfigController 补 plain-500 handler}} + TestSchema.tenant_skill_snapshots/tenant_user_env_vars | ✅ | 1+1 端点全落地（sandbox-check 404 占位转正）；**8 条 schk-*/tpl-* golden 全是 Go 实录**（latency_ms 掩码）+ 3 条契约测试 + **真 PG A/B 8 场景两轮 ALL MATCH（3361 全量绿）**。**golden 钉死**：sandboxCheckReason 固定中文分类（拒连→"服务不可用：端点拒绝连接"）、caps 是 gin.H 字母序 {pause_resume,reconnect,volumes}、latency 0ms 被 omitempty 整键省略、templates 500 是 plain 1007 无 details 键（FAQ 批同款 controller-local handler）。skills 12 条随子批 3。关键坑见 §9「波 3 sandbox 子批 2」 |
| sandbox skills 子资源（波 3 子批 3，**sandbox 模块 HTTP 面收官**） | internal/handler/sandbox_skill.go（937 行全文，12 端点）；internal/application/service/tenant_skill_{admin,progress,transcript,env_declare,bundle,install,remove,stop,steer,files}.go 的入口+首个 provider 调用失败传播；internal/types/{tenant_skill.go,tenant_env_vars.go 的 SkillEnvVars} | com.ragagent.sandbox.{controller.SandboxSkillController（13 路由+SSE 帧直写+plain-500）,service.{TenantSkillService,SkillBundleParser（zip 全文移植）,SkillFrontmatter,SkillProgressStore（Redis 键与 Go 同名）,SkillBundleStore 接缝},mapper.TenantSkillMapper（envs 列逐字段 AES TypeHandler）,domain.{TenantSkillEntity,SkillEnvVars,SkillStatus,TenantSkillCatalogEntity}} + TestSchema.tenant_skill_catalog | ✅ | 12 端点全落地；**18 条 sbk-* golden 全是 Go 实录**（upload 合法 zip=202 异步受理；SSE 单帧 {"percent":100,"stage":"done","status":"ready","done":true}）+ 契约测试 + 89 sandbox 测试绿 + **真 PG A/B 18 场景两轮 ALL MATCH（3390 全量绿）**。**golden 钉死**：is_set 读 envs 列 value 非空（agent 纠正任务书错判）、非 zip → 400 "zip: not a valid zip file"（Go 措辞照抄）、transcript 无日志 → 404 "this install's event log is no longer available"、类级 NON_DEFAULT 会吞 enabled:false/is_set:false（注解逐字段）。管线接缝随波 4。关键坑见 §9「波 3 sandbox 子批 3」 |
| skills 家族 + /me/env-vars（波 3 子批 4，**skill 模块用户面收官**） | internal/handler/{skill_handler.go,skill_catalog.go,me_env_var.go}；internal/application/service/{tenant_skill_catalog.go（606 行）,user_env.go（475 行）} | com.ragagent.sandbox.{controller.{SkillController,MeEnvVarController},service.{TenantSkillService 扩展（listCatalog 三段合并/register/install/deleteCatalog/usable）,UserEnvService,SkillSource 切片},domain.TenantUserEnvVar} + WebConfig rbac×7 + /me/env-vars 无角色门 + APIKeyRoutePolicies（catalogWrite fullAccess；GET /skills、GET catalog、/me/env-vars 默认拒绝） | ✅ | 12 端点全落地；**24 条 slk-*/mev-* golden 全是 Go 实录**（首轮全绿）+ 契约测试 + 250 包内回归 + **真 PG A/B 24 场景两轮 ALL MATCH（3392 全量绿）**。**golden 钉死**：catalog register 201 内层 gin.H 字母序 {description,id,name,version}（version 空串也输出）、install=202 installs 映射（config 缺失=404 1003）、删除被安装钉住=409 code 1005、DELETE /me/env-vars 也吃 JSON body（query→"EOF"）、未声明名→400 "skill <name> does not declare <VAR>"、source 走 SSRF 固定文案。source 的 registry/git 抓取与 CaptureSkillEnv 随波 4。关键坑见 §9「波 3 sandbox 子批 4」 |
| organizations + shares 协作面（波 3 协作批） | internal/handler/organization.go（2097 行全文）+ shared_agent_access.go；internal/router/routes_agent.go L95-232；internal/application/service/{organization*.go,share*.go} | com.ragagent.org.{domain（Organization/OrganizationTenantMember/OrganizationJoinRequest/KbShare/AgentShare/AgentRow 只读投影）,mapper（5 BaseMapper+AgentRowMapper+OrgSqlMapper+TenantDisabledSharedAgentMapper 复合主键纯 SQL）,service.{OrganizationService,KbShareService,AgentShareService,OrgServiceException},dto.OrgResponses（CustomAgent jsonb→struct 序重排 marshal）} + WebConfig（35 条 rbac+4 组 pattern+DeploymentCapabilitiesHolder organizations→true）+ APIKeyRoutePolicies（orgs/shared-*=manageSpaces、shares=fullAccess）+ TestSchema（6 张 org/share 新表+custom_agents 扩列） | ✅ | 35 端点全落地；**117 条 org-*/shr-* golden 全是 Go 实录** + OrganizationContractTest（全流程复刻+401 家族）+ system/apikey/favorite/knowledge 四包回归 + **真 PG A/B 两轮 116 场景 ALL MATCH（3394 全量绿）**。**golden 纠正**：CreateOrganizationRequest 无 require_approval（DefaultMemberLimit=200）、ListKBShares 不回填 shared_by_username（ListOrgShares 反之 organization_name 恒空）、agent share POST permission 恒 viewer 且 AllowedTools 空补默认含 knowledge_search（无 rerank_model_id 即 400）、RequestRoleUpgrade 两处 Go 文案错配真录 500、共享 KB 读面走 Preload raw 无 EnsureDefaults（indexing NULL→全 false、storage_backend_id 缺键）。**上报跨横切缺陷待专项**：emoji 补充字符被 GoJsonEscapes 转义成代理对 \uD83D 形态（Go 输出 raw UTF-8）——本批 ASCII 种子规避，任何含 emoji 响应会字节 DIFF。关键坑见 §9「波 3 协作面」 |
| agents CRUD + initialization（波 3 agents 批） | internal/handler/custom_agent.go（715 行全文）+ initialization*.go；internal/application/service/custom_agent*.go；internal/types 的 CustomAgent/CustomAgentConfig；config 的 builtin_agents.yaml/agent_type_presets.yaml/prompt_templates vendor | com.ragagent.agentm.{controller.{AgentController,InitializationController},service.{CustomAgentService,BuiltinAgentRegistry（vendor yaml 启动装载+i18n+prompt 解析）,AgentConfigJson（树级 EnsureDefaults/Validate）,AgentPlaceholders,AgentTypePresets},domain.CustomAgentEntity,mapper.{CustomAgentMapper,AgentQuestionMapper,JsonbRawStringTypeHandler},dto.{AgentResponses,InitResponses}} + common.web.GoWriterJsonFactory（**emoji 修复**） + WebConfig（rbac 12 条+holders agents→true）+ APIKeyRoutePolicies（12 条） | ✅ | 11 端点全落地；**60 条 ag-*/init-* golden 全是 Go 实录** + AgentContractTest + 回归 24 包全绿 + **真 PG A/B 两轮 60 场景 ALL MATCH（3396 全量绿）**。**emoji 跨横切缺陷修复落地**：根因=UTF8JsonGenerator._outputMultiByteChar 对补充字符硬编码代理对转义（2.17-2.20 四版一致，升级不可解）→ GoWriterJsonFactory 把 HTTP mapper 改道 WriterBasedJsonGenerator（Writer 编码=raw UTF-8，与 Go 逐字节一致），内建 avatar 📚/📊 golden 直接钉住，org/knowledge/session/wiki 全部逐字节套件回归绿。**golden 钉死的 Go 既有行为**：POST initialize 建 model 行 tenant_id=0（回读找不到→llm/embedding 键缺席）、initialization 404 是守卫层 "knowledge base not found"、内建 PUT 无行时创建行且 config 取请求原文。已知差异：DeleteAgent 的 im 清理 no-op（im 未翻）、suggested-questions 的 wiki fallback、kb_selection_mode=all 派生表——均随波 4/5。关键坑见 §9「波 3 agents 批」 |
| browserskill（波 3 尾巴批） | internal/browserskill/ 全部（4,689 行：Manager/store/http 语义/authorization/cluster/daemon/errors/focus/human）+ router.go 的 5 条路由（含引擎级 3 条）+ session.Handler 的 BrowserSkill* handler | com.ragagent.browserskill.{domain（Scope=SHA-256 前 16 字节 hex 跨语言键空间/DeviceRecord/PairingRecord/TaskInterruption/BrowserStatus/AccountStatus/RpcError）,service.{BrowserSkillManager 全文移植,BrowserSkillStore 14 方法,BrowserSkillHttp（http.Error/json.Encoder 尾随换行/MaxBytesReader 语义）},controller×2,GinJson 字母序信封,BrowserSkillWiring} + AuthFilter NO_AUTH_API（引擎级 3 条在 Auth 之前的语义）+ TestSchema 3 表（迁移 000093） | ✅ | 5 端点全落地；**44 个 bs-* golden 全是 Go 实录**（含 download 的字节+headers 双锚；录制部署形态 BROWSERSKILL_BINARY=/usr/bin/false 固化在脚本头）+ BrowserSkillContractTest（单方法按录制序——真实服务器状态跨请求持久，拆 @Test 会假红）+ **真 PG A/B 两轮 45 项 ALL MATCH 零 DIFF**（3399 全量绿）。**跨语言互操作实测**：Go authorize 兑换的设备行（SHA-256 哈希）Java WS 握手认证通过。执行循环接缝（RPC 执行段/WS 双向转发/preview/idle 释放）随波 4；sessions/:id/local-browser 两条随波 4。关键坑见 §9「波 3 browserskill 批」 |
| 事件契约包（波 4.1，**波 4 开工**） | internal/event/ 全部（event.go 267+event_data.go 317+adapter 59+middleware 94+global 57）+ agent/const.go 的 generateEventID | com.ragagent.event 新包 41 文件：基建 14（EventType 39 常量/Event/EventBus 同步+异步/EventMiddleware 链/GlobalEventBus/EventIds/EventJson（GoJsonEscapes+map 字母序+Go 浮点+GoTime）/TenantContextSnapshot 跨虚拟线程显式传值/package-info 含 **24 emit 点表**）+ payload 27 类 | ✅ | **101 单测全绿**（Go 实录：27 payload × 零值/全量/omitempty ≈ 75 字节断言 + EventBus 行为 12 条：同步顺序/断链/panic 原样冒出/ID 值语义 shallowCopy/异步虚拟线程隔离+租户传递/EmitAndWait barrier/中间件链序/Global Set-先-Get-后 once quirk）+ **全量 3500 绿**。**踩坑新知**：`is` 前缀 boolean 字段必须 getter 上同时标 @JsonProperty（否则拆出多余属性）；primitive double 绕过模块注册的 Double 序列化器（threshold 用包装 Double 才输出 Go 的 0）——均被实录钉住。零路由零 TestSchema。关键坑见 §9「波 4.1 事件契约」 |
| embed/im 清单面（波 4.3） | internal/handler/embed_channel*.go 管理段+公开面 + im channel CRUD handler；internal/application/service/embed_channel.go（426）+ im channels service（CRUD 段） | com.ragagent.embed 新包 12 文件（EmbedTokens em_/ems_ 令牌+HMAC 会话签名/EmbedRateLimiter 本地滑动窗口=Go Lite 回退/EmbedAuthFilter/EmbedChannelService/EmbedTokenStore+Redis 变体/GoStyleErrorReportValve 容器级错误页对齐）+ com.ragagent.im 新包 4 文件（bot_identity 全平台计算/duplicate 检查/BeforeCreate·Save 钩子） + AuthFilter（/api/v1/embed/ 前缀让路）+ McpOAuthController 空 body 文案 EOF 对齐 + WebConfig 17 条 + TestSchema 2 表 | ✅ | 12 端点全落地（管理面 9 + im 清单 8 计 + 公开面非 QA 部分；QA 委托面随 4.6）；**85 条 emb-*/imc-* golden 全是 Go 实录** + EmbedContractTest/ImContractTest + 回归 10 包 + **真 PG A/B 两轮 85/85 ALL MATCH 零 DIFF（3502 全量绿）**。**golden 钉死**：create 对 default:true 零值 bool 走 DB 默认（请求 false 落库仍 true）、update 缺 allowed_origins 键=nil 整列覆写（响应 null 且清空 allowlist）、未知/跨租户 agent 落 500 "operation failed"、gin.H 字母序与三套 struct 序并存、im CRUD 信封**无 success 键且 create 是 200 非 201**、duplicate bot 409 文案带 %q 渠道名。**横切备案**：GoStyleErrorReportValve（容器级错误页对齐 Go 纯文本，只在响应未被应用代码写过时接管）。关键坑见 §9「波 4.3 embed/im」 |
| 模型客户端+检索地基（波 4.4） | internal/models/embedding/（3,843 含测试）+ models/rerank/（5,725）+ internal/searchutil（2,139，部分波 2 已翻走桥接）+ infrastructure/web_fetch/（~900）+ application/service/web_search.go 执行面（792） | com.ragagent.embedding 新包 21 文件（Embedder 洋葱装饰：Factory→Http SSRF 传输+4 次指数退避→BatchEmbedder 子批短路→ConcurrencyEmbedder 过闸；10 provider 含 WeknoraCloudSign 全项目第二份 Sign）+ rerank 新包 14 文件（8 provider：LKEAP=TC3-HMAC-SHA256 裸 HTTP+切批、Volcengine=V4 HMAC 并发 4——SDK 无 Java 等价的规范复刻；NVIDIA logit sigmoid）+ searchutil 新包 8 文件（SearchChunkMerge/ImageInfoEnricher/KeywordScoreNormalizer 等；ChunkSearchUtil 桥接复用）+ webfetch 4 文件（双工厂 60s/2MB+15s/100KB、错误分类 17 码、BrowserRenderer 接缝=chromedp 降级恒失败）+ websearch/provider 20 文件+WebSearchService 执行面 + retrieval/domain 3 类型 | ✅ | **122 新测试+受影响 9 包 701 全绿**（embedding 22/rerank 23/searchutil 33/webfetch 16/websearch 38）；**31 份 Go wire 实录**（/tmp 录制器）+ **30 个请求体 stub 逐字节 A/B**（embedding 11+rerank 8+web_search 11）。**stub A/B 抓回两个真契约**：Volcengine rerank 顶层键序是 datas→rerank_model→rerank_instruction（非字母序）；Go `%02s` 对字符串也补零（Baidu 日期）。已知降级：jieba 分词接缝（默认二字滑窗近似，可注入恢复）、chromedp/readability 走 Go 自身回退分支、IP pinning 用每跳 SSRF+DNS 校验近似。关键坑见 §9「波 4.4 模型客户端+检索地基」 |
| agent 纯逻辑件（波 4.2） | internal/agent/{token/estimator.go,compaction/ 8 文件,prompts.go,prompts_browser.go,grounding_prompt.go,const.go,tool_images.go,context_debug.go} + types/prompt_instructions.go/placeholder.go/agent.go 预算族 | com.ragagent.agent 新包 24 文件 ~3.2k 行：TokenEstimator（jtokkit cl100k_base=tiktoken-go，**token 数逐字节一致**）、compaction 8 件（Compactor/CutPoint/ConversationSerializer/FileOps/Preparation/Overflow/Settings）、AgentPrompts/GroundingPrompt/AgentPromptPlaceholders/AgentPromptTemplates、AgentConsts/AgentBudgets/ContextDiagnostics/ToolImages | ✅ | **133 测试全绿**（新 79）；**486 条 Go 实录**（13 场景组：token 36 语料含 CJK/emoji、serialize/truncate 七态/renderToolArgs 16 态含 float64 语义、overflow 35 条、Compact 端到端 4 场景、isTransientError 20 表、三条全量系统提示词逐字节）。**实录钉住的真契约**：ovf17 "CONTEXT_WINDOW_EXCEEDED" 不匹配 generic 模式（Go 既有行为逐字保留）；Go json.Marshal float64 大整数→1.23e+29 形态（手写递归编码器，Jackson DoubleNode 走不到 DoubleSerializer）。**新依赖 jtokkit 1.1.0**（纯 Java 零传递，逐字节验收要求真 BPE——jieba 式降级会破坏压缩切点语义）。已知差异：摘要 60s 超时归调用方、context_debug 引擎段随 4.6、llm domain 三字段 null 守卫（消费侧）。关键坑见 §9「波 4.2 纯逻辑件」 |

| tools 基建+确定性工具（波 4.5a） | internal/agent/tools/ 的 registry+基建 21 文件（registry/definitions/capabilities/param_cast/param_validate/json_repair/truncate/normalize_id/output_budget/output_links/file_mutation_queue/strip_think/think_stream/exec_context/execution_policy/tool/data_schema/mcp_schema）+ todo_write/sequentialthinking/faq_snippet | com.ragagent.agent.tools 新包 33 文件 ~4.4k 行：ToolRegistry（first-wins/排序字节稳定/deferred/outputLimitProvider 接缝）+ ToolDefinitions（退役工具替代文案）+ ToolCapabilities（KB 能力门控）+ ParamCaster/ParamValidator/JsonRepair/ToolOutput（truncate）/NormalizeToolCallId/OutputBudgets/OutputLinks/FileMutationQueue/ThinkBlocks/ThinkStreamSplitter/GoJsonCodec/GoPath/GoJsonEscapes 复用 + TodoWriteTool/SequentialThinkingTool/FaqSnippet/DataSchemaTool + AgentTool/BaseTool/ToolRequest/ToolExecContext/ToolCancellation/MessageSanitizer/ShellEnvExtractor/ShellCommandOutput（emit 只接线不触发，执行面 4.5c） | ✅ | **31 个测试类全绿**（15 个实录回放类 + FileMutationQueue 行为机）；**208 条 Go 实录**（/tmp/toolrec 探针同包调用未导出函数 → rec.jsonl → GoRecording45A.java 生成常量，覆盖 json_repair 31/cast 29/validate 15+3/truncate 15/normid 9/strip_think 11/think_stream 13/todo 6/seqthink 11/registry 17/caps 12/faq 10/fmq 4/budget 13/codec 9）。**实录抓回三个真缺陷**：① ParamValidator 对 `{}` args 提前返回跳过 required 检查（Go 只在 len(args)==0 短路，`{}` 照样报 missing）；② TodoWriteTool 缺省 steps 编出 `[]`/`"[]"`（Go 的 nil 切片是 `null`/`"null"`）；③ JsonRepair `List<Character>`/ParamCaster import/FaqSnippet import/ShellCommandOutput 受检异常 4+2 编译错误。**已知通道差异（备案）**：Go 的 (result,err) 双通道折叠——Java 工具用 success=false+error 表达失败，"success=true 同时 err" 形态不可表达；bad-json 的 args 在 Java 上游已解析（seqthink/todo 的 bad_json case 不录）。执行面（sandbox/shell/skill/web/knowledge/wiki/MCP 工具）与 EventBus emit 接线随 4.5b/4.5c |

| 知识检索+wiki 工具族（波 4.5b） | internal/agent/tools/ 的 knowledge 九件（knowledge_search/grep_chunks/list_chunks/query_graph/get_doc_info/search_conversations/search_memory/database_query/data_analysis+sql_guard/search_auth）+ wiki 十件（wiki_search/read_page/write_page/replace_text/rename_page/delete_page/read_source_doc + wiki_issue 四件 flag/read/update） | com.ragagent.agent.tools 新文件 24 个：KnowledgeSearchTool/GrepChunksTool/ListKnowledgeChunksTool/QueryKnowledgeGraphTool/GetDocumentInfoTool/SearchConversationsTool/SearchMemoryTool/DatabaseQueryTool/DataAnalysisTool + SqlGuard（1590 行，手写替代 pg_query）/SearchAuth（455）/SearchTarget/DocChunkSupport/WikiSupport + 10 个 Wiki*Tool。执行接线（SqlQueryExecutor/KnowledgeLoader/Materializer/DuckDB 引擎选型）的生产装配随 4.6 | ✅ | **9 个新测试类全绿**（259 tests / 0 failures，PG 依赖类真实执行：DatabaseQuery 15/DataAnalysis 12/KnowledgeSearch 15/WikiTools 8/GrepChunks 10）；**249 条 Go 实录 21 组**（/tmp/toolrec45b 探针，GoRecording45B.java 249 常量）。**已知差异（备案，实录已锁稳定段）**：⑮ DuckDB 1.5.2 vs JDBC 1.1.3 错误文案不同（missing-column 语料只锁稳定段）；⑯ DuckDB 1.1.3 无 read_xlsx（buildExcelCreateTableSQL 逐字锁定，执行侧 ST_Read shim）；⑰ knowledge_search dedup 二轮顺序 Go map 随机 vs Java LinkedHashMap 确定（rerank_preserve_top 语料钉采样）。**决策点**：手写 SqlGuard 替代 pg_query、跳过 Deparse、DatabaseQueryTool 注入 LongSupplier tenantId、storage 后端解析留 KnowledgeFileMaterializer seam、未加 JsonContractRoundTripTest。零既有文件被改。关键坑见 §9「波 4.5b 补充」 |
| 执行面+MCP 工具族（波 4.5c） | internal/agent/tools/ 的 sandbox 文件面五件（read_file 180/sandbox_ls 320/sandbox_write 403/sandbox_edit 423/sandbox_diff 158）+ shell_exec（1027）+ skill 三件（skill_file 416/skill_resources 90/skill_runtime_guard 89）+ 行为完整所需超清单两件（workspace_reader 442/python_syntax 158）+ read_web_page 77 + MCP 四件（mcp_tool 755/mcp_catalog 974/mcp_exposure 254/mcp_oauth 253） | com.ragagent.agent.tools 新文件 35 个 ~10k 行：ReadFileTool（skill 资源/workspace/web:// 游标三分支）+WorkspaceFileReader/ListSandboxFilesTool/WriteSandboxFileTool/EditSandboxFileTool+SandboxEdits（**字节偏移语义**）/SandboxDiffs（接 4.5a 预留 sandboxOutputSnapshot）+ ShellExecTool（session+install 双变体、黑名单、保头保尾截断、stdin base64 管道、skill env 解析/捕获；**4.5a 的 ShellCommandOutput emit 首次真正触发**）+ WriteSkillFileTool/EditSkillFileTool/SkillFiles/SkillResources/SkillRuntimeGuard/PythonSyntax + McpToolWrapper/McpCatalog/McpDiscoverTool（三模式）/McpCallTool/McpRegisteredTool（实现 4.5a 预留 McpCatalogGuardedTool，鉴权先于 schema 校验）/McpExposure/McpOAuthSupport/ApprovalBridge + SandboxPaths/RemoteDirEntry/RemoteStatEntry/SkillEnvironment（SkillEnvResolver 按 Go env_resolver.go 原样补在本包）+ 接口族 SandboxFileSource/Sink/Editor、SandboxCommandExecutor/SandboxInstallCommandExecutor、SkillFileStore。**ToolRegistry 扩展 +303 行纯新增**（唯一授权既有文件：prepareMcpTools(Direct)/refreshMcpTools/rememberMcpHistory/mcpCatalog/mcpCallTarget/hasMcpServer） | ✅ | **68 新测试全绿，agent 包 327 条零回归**（小包批 B1a~B4 合计 3901 全绿）；**265 条 Go 实录 14 组**（/tmp/toolrec45c 探针 a/b/c + 内嵌 stub MCP server；GoRecording45C.java 265 常量）；**MCP stub A/B 双端同打同款 stub server**：tools/list、tools/call、notifications/initialized 请求体逐字节一致（id 掩码），工具结果 output 逐字节一致（initialize 差异备案⑱）。实录抓回 7 处真语义缺陷（edit 字节偏移/UTF-8 分页预算/U+FFFD 逐字节/GoDuration "1ms"/16-hex 后缀/content_items 字节计数/ErrTimeout 补写）。**已知差异（备案）**：⑱ initialize 请求体 Go SDK=2025-11-25 vs Java 4.1=2024-11-05（4.1 既有契约，握手语义一致）；McpTool InputSchema JsonNode 重序列化 vs Go RawMessage 原文（pretty-JSON 服务器的 ref 哈希不一致）；Go json.Unmarshal 错误文案不可达只录不比。**决策点**：SkillEnvironment 位置、McpTool raw-schema 通道（动 4.1）、执行期身份重校验、投影类型对接方式。关键坑见 §9「波 4.5c 补充」 |
| modelcontext + skills 库 + langfuse seam（波 4.6a，4.6 首批） | internal/modelcontext/ 15 非测试文件 ~3.3k（citations/handles/handle_table/registry/sources/mcp/mcp_sources/resources/stream/tool_policy/model_output）+ internal/agent/skills/ 9 非测试文件 ~3.3k（skill/skill_frontmatter/source/loader/tenant_source/env_resolver/manager/shell_staging/shell_environment）+ internal/tracing/langfuse 的引擎三调用点 | com.ragagent.modelcontext 新包 10 文件 ~3.0k（Registry：ProtocolPrompt/EncodeMessages/DecodeToolCalls/DecodeOutputText/StreamDecoder/Register*/ModelToolResult*/CompactKnownText + SourceRegistry/CitationStreamExpander/GoHtml（EscapeString 五字符）+ ResourceRegistry（res://0001 起/孤儿过滤）+ ToolPolicy（37 策略表+RawJson 保原始字节扫描器）+ ModelOutput（8 display_type）+ GoJsonValues（float64 归一/Go 严格单值解析））+ com.ragagent.agent.skills 新包 8 文件 ~2.0k（Skill/SkillFrontmatter 两步修复管线/Loader（ReadDir 排序+Walk 深度序）/TenantSkillSource（**手写 zip 中央目录解析器含 zip64**+LRU）/SkillEnvResolver/Manager（SessionFileStore/SandboxGateway 窄 seam + **asSkillEnvironment() 适配器接 4.5c SkillEnvironment**））+ com.ragagent.tracing.langfuse 3 文件（LangfuseManager 接口+Span+no-op 单例；OTLP 导出降级备案） | ✅ | **48 新测试全绿（四包 375 条）**；**193 条 Go 实录**（modelcontext 92 + skills 101，含 100_001 条目/20_001 文件真 zip 边界、LRU 逐出计数）；**零既有文件改动**（SkillEnvironment 桥接走适配器，tools 包一字未动；波 3 sandbox.service 对账同源同值零改动）。**实录抓回**：Go json 严格单值（FAIL_ON_TRAILING_TOKENS——default 分支 Go 只走 labeled refs）、RE2 `$`→`\z`、`\s` 的 `\x0B` 差异、float64 数字归一、map[string]RawMessage 保 1.0 字面量重组。**主会话修掉 4.2 潜伏墙钟 flake**（AgentPromptsTest，见 §9「波 4.6a 补充」）。**决策点**：波 3 SkillFrontmatter snakeyaml 宽容类型是否对齐、GoRawJson 深层键序边界。关键坑见 §9「波 4.6a 补充」 |
| agent 引擎核心（波 4.6b） | internal/agent/ 根包 engine.go(934)/observe.go(918)/think.go(626)/act.go(619)/finalize.go(188)/steer.go(93)/context_debug.go 引擎段(169) + types 的 AgentState/AgentConfig 消费面 + prompts 的四个 info 类型 | com.ragagent.agent 根包新文件 4 个：AgentEngine（七文件方法收进一类，分段注释=Go 文件名；Execute/executeLoop/runReActIteration/streamLLMToEventBus/streamThinkingToEventBus/callLLMWithRetry/executeToolCalls(+parallel 写屏障)/runToolCall/manageContextWindow/runCompaction/analyzeResponse/streamFinalAnswerToEventBus/emitCompletionEvent/drainSteerMessages/logContextPrediction/logContextDrift）+ AgentConfig（运行时消费面，与 agentm.AgentConfigJson 配置树刻意分离）+ AgentEngineException（Go error 通道，message=fmt.Errorf 原文）+ SteerSink（引擎半边接口，4.6d 实现）+ domain.AgentState（json 契约：pending_steer_messages 是 json:"-"） | ✅ | **20 个实录回放测试全绿（com.ragagent.agent.* 362 条零回归）**；**74 条 Go 实录 20 组**（/tmp/toolrec46b 同包探针复用 engine_test.go mockChat + steer_test.go fakeSteerSink：自然停/空内容重试/卡死检测/max-iterations 合成/content_filter/并行工具写屏障/length 拒执行/steer 注入+循环结束续跑/render_user_turn 句柄压缩/trim/compaction 触发/estimate/流错误/优雅降级）。**实录抓回两个真缺陷**：① MessageSanitizer 合并就地改共享对象（Go 切片值语义→多轮下用户消息被反复追加），② 引擎两处用字段 sessionId 而非 Execute 入参。**语义备案**：complete 事件 usage 键恒输出（Go typed-nil interface）+ agent_steps 恒输出（interface{} 持切片，与切片类型字段的 omitempty 相反）——RawValue 过 NON_EMPTY。关键坑见 §9「波 4.6b 补充」 |
| chat_pipeline 检索管线（波 4.6c） | internal/application/service/chat_pipeline/ 全部 26 非测试文件（9,873 行） | com.ragagent.chatpipeline 新包 43 主文件 ~8.7k 行：ChatManage 三段状态包 + EventManager（注册序闭包链）+ PipelineBuilder（5 组预设管线）+ PluginError（9 预定义错误**同一实例**保 Go 指针比较语义）+ 17 个 Plugin* 插件类（多文件收进一类，分段注释=Go 文件名）+ PipelinePorts（全部窄 seam：Model/KnowledgeBase/Knowledge/Chunk/Memory/Message 等 Service 子集，**签名已与既有 memory.service 等对齐**）+ PipelineCommon（RunParallel/ParallelMap=虚拟线程）/SearchSupport/ReferencesSupport/PipelineProgress（进度窗口时钟可注入）/GoJsonMarshal（MarshalIndent 字节形态）/QueryTokenizer（jieba 接缝） | ✅ | **42 新测试全绿（四包合跑 437 条）**；**263 条 Go 实录 40 组**（/tmp/toolrec46c 同包探针复用 Go 既有测试 fakes；两次运行 diff 为空）覆盖 query 理解 19 形态/expansion 17（jieba 真分词）/merge 五件全分支/rerank 48/progress 事件序列/stream 路由等。**实录抓回三个真缺陷**：① expandShortContextWithNeighbors 前后文是**替换**非拼接（首版文本翻倍）；② Go `len()` 三处是**字节语义**（按 char 翻译会漏变体）；③ 引号字符类只有直引号+「」『』（hexdump 验证无弯引号）。**零既有文件改动**。已知差异：jieba 降级 seam、entity 错误文案掩差异段、search_parallel 合并序恒 chunk→entity（Go 并发序不可控备案）。seam 装配清单（PipelinePorts 11 接口）随 4.6d |
| chat HTTP 面+装配（波 4.6d，波 4 收官批） | internal/handler/session/qa.go(1,768) + agent_stream_handler.go(896) + application/service/{session_agent_qa 647, session_knowledge_qa 1,290, agent_service.go 装配 1,485} | com.ragagent.session 新文件 10 个：KnowledgeQaController（三入口全文）/AgentStreamBridge（17 事件订阅 + final_answer 分片重组 + superseded preamble 剔除）/SessionKnowledgeQaService（管线调用方+进度窗口+ErrSearchNothing 兜底）/SessionAgentQaService（AgentQA+LoadAgentHistory+registerTools）/QaSupport/QaWiring（**PipelinePorts 11 seam 生产 adapter** + 17 插件注册序）/SteerSinkBridge/SteerRunCoordinator/QaAgentConfig + dto + config.ConversationProperties（11 vendor 模板装载）+ agent.tools.{ToolResultPersist,DataAnalysisSessionBridge} + chatpipeline.DataAnalysisSessionFactoryAdapter | ✅ | **A/B 15 场景 × 2 轮全 MATCH 零 DIFF**（scripts/ab-qa46d.sh，双端同指 stub LLM scripts/stub-llm-server.py，真 PG）；golden qa46d-* ×15 + KnowledgeQaContractTest 7 条绿；**小包批合计 3,985 全绿**（session 257/agent+chatpipeline 404/apikey+auth 175/common+event+audit 270/B2 955/B3 1066/B4 1115）。**实录/联调抓回**：parseQARequest 漏拷 query、虚拟线程 TenantContext 丢失（capture/replay 修复）、gin binding 文案必须 @RequestBody String 手工绑定（否则 400→500）。**已知缺口（备案）**：共享 agent QA 解析（波 5）、HybridSearch 执行面（检索引擎批）、Artifact/VLM 执行体（dev 两侧同形 no-op）、models/{id}/debug+local-browser+terminal-ws（收尾扫描）。**批次教训**：agent/chatpipeline 不能与 apikey/auth 等 @SpringBootTest 包同批（Mockito attach 假红复发，分批即绿） |
| 收尾批 W5a：RBAC 漂移修复+13 散条 | routes_auth_tenant.go（logout/refresh/switch-tenant + tenants CRUD 4）+ routes_knowledge.go 标签 4 + routes_agent.go IM 回调 2 + WebConfig 拦截器/规则对账修复 | AuthController +3（UserService logout/refreshToken/switchTenant，JwtService.parseSignedAllowExpired）+ TenantCatalogController +4（deleteTenant 纠偏为级联软删）+ KnowledgeTag 全套（Service/Controller/DTO/mapper/repository）+ ImCallbackController（ensureChannelForCallback）+ WebConfig 拦截器六前缀补齐（chunks/messages/faq/knowledge-chat/agent-chat/knowledge-search——规则早已存在但拦截器不覆盖空转）+ sessions 23/messages 4/chunks 写族 7 规则补登记 | ✅ | **56 条 w5a-* golden + 真 PG A/B 三轮 56/56 ALL MATCH**（scripts/ab-w5a.sh）；golden 抓回两处预实现错误：①Go tag.SeqID 经 GORM RETURNING 回填真值（旧注释"恒 0"是错的）②具名 struct validator 键带 createTagRequest. 前缀。**漂移修复验证**：viewer PUT /tenants → OWNER 规则 403 与 Go 逐字节 MATCH。**既有问题备案**：mcp×storage 批互踩（MCP SSRF 用例泄漏进程级 SsrfGuard 白名单，clean HEAD 复现确认非本批引入，留专项）。**已知差异**：tag 异步回收降级 no-op WARN、IM 回调 enabled 渠道 dev 也 503（mattermost 工厂失败，非 XDEP） |

| W5a 收尾批（WebConfig RBAC 漂移修复 + 13 条小散路由） | internal/router/rbac.go 全文对照 + routes_chat.go RegisterSessionRoutes/RegisterMessageRoutes + routes_knowledge.go RegisterChunkRoutes 写族 + handler/{auth,tenant,tag}.go 的 Logout/RefreshToken/SwitchTenant/ListTenants/GetTenant/UpdateTenant/DeleteTenant + service/{user.go L1127-1470, tag.go 全文} + internal/im EnsureChannelAdapter 确定性前缀 + routes_agent.go RegisterIMRoutes | com.ragagent.config.WebConfig（漂移：拦截器 pattern +sessions/messages/chunks/faq/knowledge-chat/agent-chat/knowledge-search 六前缀补齐，规则 +sessions 23 条/messages 4 条/chunks 写 7 条）+ auth.{controller.AuthController +3 端点,service.{UserService logout/refreshToken/switchTenant,JwtService.parseSignedAllowExpired}} + auth.{controller.TenantCatalogController +4 端点,service.TenantService.deleteTenant 软删级联} + knowledge.{controller.KnowledgeTagController,service.KnowledgeTagService,dto.KnowledgeTagDtos,mapper.KnowledgeTagMapper/Repository 全套 CRUD,ChunkRepository.deleteChunksByTagId} + im.{controller.ImCallbackController,service.ImChannelService.ensureChannelForCallback} + auth.filter.AuthFilter 回调让路 + apikey.filter.APIKeyRoutePolicies +8 | ✅ | **56 条 w5a-* golden 全是 Go 实录** + W5aSundryRoutesContractTest（56 比对一法）+ **真 PG A/B 三轮 56/56 ALL MATCH 零 DIFF**（ab-w5a.sh，漂移修复的 put-nonowner=403 场景直击 OWNER 规则）+ 受影响 9 包回归绿。**golden 抓回三个真契约**：①Go 的 tag.SeqID 经 GORM RETURNING **回填真值**（旧注释"恒 0"是错的）→ PG 插入后按 id 回读；②tag create 的 validator 键**带** struct 前缀（`createTagRequest.Name`，匿名 struct 才无前缀）；③page=0 过 binding（omitempty 视零值为空）→ 归一 page=1。**实录钉住**：refresh 轮换的吊销检查在"同秒 JWT 逐字节相同"时会因 auth_tokens 出现同值行而变成堆序掷硬币——录制脚本 sleep 2 保证确定性；GET /tenants/:id 的 handler "Invalid workspace ID" 与 DELETE 缺行 500 均被 PathTenantMatch 拦成死代码；im 回调 enabled 渠道在 Go dev 因 mattermost 适配器工厂失败恒 503 "channel not available"（Java 无适配器同形 → MATCH 非 XDEP）。**决策点**：tenant DELETE 用自助建租户+PathTenantMatch 403 的组合钉住（真实删除在 dev 不可达），级联软删（成员+租户）以 repo 层对齐。已知差异：tag force/content_only 的 asynq 异步回收（knowledge 文件删除/向量索引）降级 no-op WARN；org-share 授予路径未翻译（同源收紧）。关键坑见 §9「W5a 补充」 |

| 收尾批 W5b：initialization 系统级 14 条（模型初始化向导收官） | internal/handler/initialization.go 的系统级 14 端点（CheckOllamaStatus/ListOllamaModels/CheckOllamaModels/DownloadOllamaModel/GetDownloadProgress/ListDownloadTasks + CheckRemoteModel/TestEmbeddingModel/CheckRerankModel/CheckASRModel/TestMultimodalFunction + ExtractTextRelations/FabriTag/FabriText，L923-2606）+ internal/models/utils/ollama 的 Pull 进度回调 + internal/models/asr（唯一 provider OpenAIASR，381 行）+ config.yaml extract 段（extract_graph/fabri_text 模板）+ internal/assets/asr_test.wav | com.ragagent.agentm.{service.{OllamaDownloadTaskStore（进程内 map=Go 包级 downloadTasks，无新表）,AsrTranscriber（seam+OpenAI 兼容缺省实现，含 go-openai error.go 字节级仿真）,AsrTestAudio,ExtractPrompts（vendor agentm/extract_config.yaml 与 Go config.yaml L49-107 逐字节同源）,AgentmWiring（**OllamaService 单例 bean 首次落地**，对照 container.Provide）}} + InitializationController +14 端点 + llm.ollama.OllamaService.pullWithProgress（management 缺口）+ WebConfig rbac×14 + APIKeyRoutePolicies manageModels×14 + 测试侧 W5bStubServers（in-JVM ollama:11434 + OpenAI 兼容 upstream） | ✅ | **45 条 w5b-* golden 全是 Go 实录**（DOWN/UP 双态 + upstream stub 场景）+ W5bInitializationContractTest（4 方法顺序敏感）+ **真 PG A/B 两轮 40 场景×2=80 项 ALL MATCH 零 DIFF**（ab-w5b.sh，双端同指 stub-llm 8181/stub-ollama 8182 + 同一 dev docreader）。**A/B 抓回一个真缺陷**：multimodal 成功/失败 data 节点必须按 gin.H 字母序**插入**（ObjectNode 保插入序，caption<ocr<processing_time<success）。**golden 钉死的契约**：①gin validator 键用 Go 字段名非 json tag（匿名 struct 是 'Models'/'ModelName'，具名才带 struct 前缀）；②OllamaModelInfo.modified_at 保留 JSON 反序列化的 UTC（time.Time marshal 语义），而下载任务的 startTime 是本地时区——同 handler 内两种时区路径并存；③asr 的 500 纯文本 body 走 go-openai RequestError 形态（`invalid character 'b' looking for beginning of value, body: boom`）且 available=**true**（端点可达分支）。**seam 降级备案**：ASR=薄复刻唯一 provider（真实出站）；VLM 无 provider 调用（multimodal/test 实际打 DocReader）；download 的 12h ctx 超时未翻（虚拟线程无等价 cancel）。已知差异：ollama 传输层错误内文（Go dial tcp vs JDK）掩码比对、GoJsonBindError 深结构回落。关键坑见 §9「W5b 补充」 |
| 收尾批 W5c：文件代理面 8 条路由（internal/router/files.go 收官） | internal/router/files.go（693 行全文：newFileServeHandler/serveFilesWithResources/serveResourceGrants/serveKBScopedFiles/serveMessageScopedFiles/servePresignedFiles/servePresignedPreview + parseStorageTarget/resolveCatalogResource/streamStoredFile/fileAccessError/serveAuthorizedFile）+ internal/application/access/files.go（全文）+ internal/application/service/{resource.go 的解析/授权子集,resource_references.go,storagebackend.go 的 ResolveFileService/ResolveBackend}+ internal/application/service/file/{factory.go 的完备性检查,resolve_tenant.go,local.go 的 GetFile/GetFileURL,backend_scoped.go 的 GetFile/GetFileURL}+ internal/filetransport/response.go + internal/middleware/api_key_gate.go 的 AllowFileServeAPIKey/DenyAPIKeyPrincipal + utils/presign.go 全文 + utils/file_reference.go + types/{resource.go 的解析族,file_reference.go} | com.ragagent.storage.fileserve 新包 8 文件（StoragePaths/FileContentService/LocalFileContentService/BackendScopedFileService/StorageFileResolver/ResourceCatalogService 复用 mapper/FileTransport/FileAccessResolver/FileProxyService）+ storage.{domain.StoredResource,mapper.ResourceRepository,controller.FileProxyController}+ knowledge.controller.KbFileProxyController + session.controller.MessageFileProxyController + config.WebConfig（rbac×3 + 拦截器 pattern + APIKeyGate exclude /api/v1/files/** + 两个既有 API-Key 拦截器接线）+ AuthFilter /r/ 前缀让路 + APIKeyRoutePolicies ×2 + TestSchema resources 全投影 + resource_bindings/resource_access_grants（迁移 000069） | ✅ | **85 个 w5c-* golden 全是 Go 实录**（6 类种子文件字节 + resources/grants/KB/绑定/session/messages 直种双端共享）+ W5cFileProxyContractTest 9 方法（二进制 body+headers 双锚）+ fileserve 纯函数 24 单测（FormatMediaType 24 语料/parseRange/Rel 全是 go1.26 实录）+ **真 PG A/B 两轮 75/75 ALL MATCH 零 DIFF**（ab-w5c.sh，双端同指 dev PG + 同一落盘目录 + .env 同一把 SYSTEM_AES_KEY）。**golden/A/B 抓回的真契约**：①presigned 的 Content-Disposition 是**裸 inline/attachment**（Go 调 streamStoredFile 不带 filename）；②presigned-preview 的 url 是 BackendScoped 的 `storage://<backendID>/` 包装（rewritten 恒 true、provider 被 backend 覆写）；③KB 受限 Key 对 KB 代理路由的门禁拒绝文案与 gate 相同；④If-None-Match 携带但无服务端 ETag → 照常 200（不是 304）。关键坑见 §9「W5c 补充」 |

| 收尾批 W5d：沙箱终端 WS + 会话侧 local-browser + embed QA 委托收口（路由对账真缺口 7→1） | internal/handler/session/sandbox_terminal_ws.go（426 全文）+ internal/application/service/sandbox_terminal_service.go（263 全文）+ internal/middleware/ws_auth.go（全文）+ internal/handler/session/browserskill.go 的 BrowserSkillConnection（L20-81）+ internal/handler/embed_channel.go 的 delegateEmbedChat/patchEmbedChatPayload（L460-466/620-648/712-750）+ routes_agent.go L239-240/255 的 embed QA/文件注册 | session.controller.SandboxTerminalController + session.service.{SandboxTerminalTicketService,SandboxTerminalAuthService,SessionTerminalService,TerminalBridge,TerminalWebSocketServer,TerminalWebSocketUpgradeHandler}（**Servlet 3.1 upgrade 路线**）+ auth.filter.WsAuthSupport（AttachAuthenticatedUser 能力链抽 bean，AuthFilter 委托）+ browserskill.controller.BrowserSkillSessionController + embed.controller.EmbedChannelController +3 端点（knowledge-chat/agent-chat/files 委托）+ WebConfig（rbac×3 + APIKeyGate exclude WS 路由）+ APIKeyRoutePolicies ×3 + JwtService.generateSandboxTerminalTicket + SessionMapper sandbox_config_id pin 读写 + auth/filter/AuthFilter 通道 1.8（WS GET 让路） | ✅ | **24 个 w5d-* golden 全是 Go 实录**（租户 10008 种子，record-w5d-golden.sh）+ W5dTerminalEmbedContractTest 5 方法（ticket 掩 JWT 形状比对 + 自铸自解析回环 + WS 400 族逐字节 + embed 委托/文件全族）+ **真 PG A/B 两轮 26/26 ALL MATCH 零 DIFF**（ab-w5d.sh：22 场景体 + 2 组头部 + 文件二进制 + **ws-handshake-live 双端实测**：真 ticket 打原始握手，101+Sec-WebSocket-Accept+close(1008,SANDBOX_NOT_BOUND) 逐字节一致）。**本批实测翻案**：半成品的手写 RFC 6455「101 后写 Servlet 裸流」在 Tomcat 上不成立（1xx 无实体、写入被静默吞），改 Servlet 3.1 `request.upgrade` + WebConnection 裸流。**golden/A/B 抓回的真契约**：①plainStatus 空体 404 被 Tomcat ErrorReportValve 补默认体（W5c 潜伏偏差一并修复）；②WS 400 族的 Sec-Websocket-Version/nosniff/text/plain 12 字节体。框架层备案：CORS 头族（Go '*' vs Spring 回显）、ticket JWT 形状差（掩码）。provider 终端执行体（cube/e2b/docker 远程 PTY）标 XDEP 随波 5。关键坑见 §9「W5d」 |
| 阶段 7：models/{id}/debug 模型调试端点（最后一条真缺口收官） | internal/handler/model.go 的 DebugModel(L347-534) + parseModelDebugOptions/modelDebugRequestPreview/writeModelDebugResult/consumeModelDebugChatStream/redactedDebugConfig(L220-345) + internal/models/{chat,embedding,rerank,vlm,asr} 的运行时工厂取用路径 | model.controller.ModelDebugController + model.service.ModelRuntimeFactory（五类运行时工厂：embedding/rerank 走状态闸门、chat/vlm/asr 直取——不对称照抄 Go）+ model.dto.ModelDebug{Chat,Asr}Response + retrieval.vlm.VlmHttpTransport + common.web.GoFloat{,Array}Serializer + rerank.RankResult Jackson 注解 + agentm.AsrTranscriber.goOpenAiError 转 public + apikey.filter.APIKeyRoutePolicies +1 | ✅ | **24 个 md-* golden 全是 Go 实录**（租户 10009 种子，scripts/record-modeldebug-golden.sh）+ ModelDebugContractTest 24 用例全绿（in-JVM HttpServer stub 复刻 stub-llm-server.py）+ **双端 stub A/B 两轮 24/24 逐字节 MATCH 零 DIFF + HTTP 状态码 24/24 一致**（掩码仅 elapsed_ms）。**A/B 抓回两个共享栈真缺陷**：①ConcurrencyChatClient 在首个 done 后截断流（Go 是 range-until-close 全量转发）→ forwardTail 尾巴转发；②流终态事件的 finish_reason 在 Go 分路径携带（SDK 带/裸 HTTP 不带）→ ThinkingStrategy.apply 恢复 boolean 返回 + Outbound.rawPath 标记复刻分野。回归：B1a/llm/model 批次绿（SSE/agent 消费面不受影响——它们在首个 done 即收束）。**备案偏差**：ollama/weknoracloud 界面 VLM 为诚实 XDEP 文案（provider-XDEP 族新成员）；preview custom_header_names 排序输出（Go map 随机序）；VLM customHeaders 未透传。关键坑见 §9「阶段 7（models/{id}/debug）」 |

| A3 存储 provider 层：八个 provider（local + s3/minio/obs/ks3 + oss/cos/tos）+ 工厂 + storageurl 两组窄口接线 + 知识/skill/FAQ 三处消费者改道（2026-09-24，五笔提交） | `internal/application/service/file/*`（20+ 文件全文）、`internal/storageurl/{resolver,rewriter}.go`、`internal/application/service/storagebackend.go`、`internal/types/{storagebackend,tenantconfig}*.go` | `com.ragagent.storage.provider/*`（FileService 接口 / LocalFileService / S3CompatibleFileService / OssFileService / CosFileService / TosFileService / FileServiceFactory / StorageObjects）、`com.ragagent.storage.fileserve/*`（ProviderFileContentService / FileserveStorageBackendResolver / StorageUrlWiringConfig）、`com.ragagent.knowledge.service.TenantFileStorage`、`com.ragagent.sandbox.service.TenantSkillBundleStore` | ✅ | 依赖：aws-sdk s3 2.31.68 / aliyun-sdk-oss 3.18.1 / cos_api 5.6.227 / ve-tos-java-sdk 2.9.19。坑：TOS 版本与 V2 输入类名、COS 拷贝四参参数序（javap 核对）、OSS 异常无状态码、`@JsonUnwrapped` 不支持 record Creator、`local://` 归本地、`SafeFileName` 取 basename。已知差异：云对象整对象入堆、OSS 未走分片 Uploader、local 两支实现。明细见 §9 索引的 `08-storage-a3.md` |

## 9. 已确认细节与坑（索引）

> **本节的正文已按批次拆分到 [`docs/known-issues/`](known-issues/)（内容未改动，只挪位置）。**
> 动任何模块前先读对应分片；代码注释与任务书里的「约定 §9「XXX」」按下面的标题检索。
> 维护纪律不变：每完成一批，把新条目追加到对应的分片文件（W5d/W5α…W5δ 及阶段 7 之后收口批 → `06-wave-5.md`，
> models/{id}/debug → `07-model-debug.md`，存储 provider 层（A3）→ `08-storage-a3.md`）。

| 批次 / 条目 | 分片文件 |
|---|---|
| Go 全局错误形态（两种并存，按 handler 实际写法区分） | [`00-foundation.md`](known-issues/00-foundation.md) |
| ⚠️ JSON 键序规则（2026-09-17 实测修正） | [`00-foundation.md`](known-issues/00-foundation.md) |
| Go 时间序列化 | [`00-foundation.md`](known-issues/00-foundation.md) |
| Go 非指针字段零值 | [`00-foundation.md`](known-issues/00-foundation.md) |
| soft delete 不用 @TableLogic | [`00-foundation.md`](known-issues/00-foundation.md) |
| 401 三态 | [`00-foundation.md`](known-issues/00-foundation.md) |
| 已知差异（阶段 1+2，随后续模块消除） | [`00-foundation.md`](known-issues/00-foundation.md) |
| 阶段 2 新确认的契约细节 | [`00-foundation.md`](known-issues/00-foundation.md) |
| TENANT_REQUIRED | [`00-foundation.md`](known-issues/00-foundation.md) |
| Flyway baseline | [`00-foundation.md`](known-issues/00-foundation.md) |
| 阶段 3 新确认的契约细节 | [`00-foundation.md`](known-issues/00-foundation.md) |
| 阶段 3 已知差异（后续阶段补） | [`00-foundation.md`](known-issues/00-foundation.md) |
| 阶段 4.0（LLM 客户端）新确认的细节与简化 | [`00-foundation.md`](known-issues/00-foundation.md) |
| 阶段 4.0 已知差异 / 未实现 | [`00-foundation.md`](known-issues/00-foundation.md) |
| 阶段 4.0 发现的 Go 侧不一致（Java 已照抄并用测试钉住，改动前须知会偏… | [`00-foundation.md`](known-issues/00-foundation.md) |
| JSON 编码器的系统性差分排查（本轮的专项） | [`00-foundation.md`](known-issues/00-foundation.md) |
| 跨阶段通用坑（阶段 4 新增） | [`00-foundation.md`](known-issues/00-foundation.md) |
| 工具链与调试坑（跨阶段复用，阶段 3 实测） | [`00-foundation.md`](known-issues/00-foundation.md) |
| 阶段 4.1（MCP）新确认的细节 | [`01-mcp-stream-session.md`](known-issues/01-mcp-stream-session.md) |
| 阶段 4.1 已知差异 / 未接线 | [`01-mcp-stream-session.md`](known-issues/01-mcp-stream-session.md) |
| 阶段 5（stream 流管理器）新确认的细节 | [`01-mcp-stream-session.md`](known-issues/01-mcp-stream-session.md) |
| 阶段 5 已知差异 / 未接线 | [`01-mcp-stream-session.md`](known-issues/01-mcp-stream-session.md) |
| 阶段 5（session/message）新确认的细节与坑 | [`01-mcp-stream-session.md`](known-issues/01-mcp-stream-session.md) |
| 阶段 5.2（SSE 契约层）新确认的细节与坑 | [`01-mcp-stream-session.md`](known-issues/01-mcp-stream-session.md) |
| 阶段 5.2 步 2（storageurl 包）新确认的细节与坑 | [`01-mcp-stream-session.md`](known-issues/01-mcp-stream-session.md) |
| 阶段 5.2 步 3 新确认的细节与坑 | [`01-mcp-stream-session.md`](known-issues/01-mcp-stream-session.md) |
| 阶段 5.2 步 4（continue-stream）新确认的细节与坑 | [`01-mcp-stream-session.md`](known-issues/01-mcp-stream-session.md) |
| 剩余工作的依赖结构（2026-09-18 实测重排，推翻原阶段 6/7/8 顺序） | [`01-mcp-stream-session.md`](known-issues/01-mcp-stream-session.md) |
| 波 0（memory）新确认的细节与坑 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| memory HTTP 层（波 0 第 4 步）新确认的细节与坑 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| 波 0（datasource）新确认的细节与坑 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| 波 0（datasource 连接器层）新确认的坑——四条都会复发 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| 波 0（datasource service + HTTP 层）新确认的细节与坑 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| 波 0（datasource service + HTTP 层）已知差异 / 未接线 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| 波 1 G1（session CRUD+pin）新确认的细节与坑——前四条都会复发 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| 波 1 G2（消息面）新确认的细节与坑——前三条都会复发 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| 波 1 G3（追问建议）新确认的细节与坑——前三条都会复发 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| 波 1 G6（产物/title/stop）新确认的细节与坑——前三条都会复发 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| 波 1 G4（steer）新确认的细节与坑——前三条都会复发 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| 波 1 G5（临时文档 attachments）新确认的细节与坑——前四条都会复发 | [`02-wave-0-1.md`](known-issues/02-wave-0-1.md) |
| 波 2 chunk（编辑面）新确认的细节与坑——前四条都会复发 | [`03-wave-2.md`](known-issues/03-wave-2.md) |
| 波 2 knowledge 文档操作面（15+1 条路由）新确认的细节与坑——前四… | [`03-wave-2.md`](known-issues/03-wave-2.md) |
| 波 2 knowledge 搜索与移动/复制（8 条路由，knowledge 域收… | [`03-wave-2.md`](known-issues/03-wave-2.md) |
| 波 2 FAQ 补充（真 PG A/B 抓回的跨列缺陷） | [`03-wave-2.md`](known-issues/03-wave-2.md) |
| 波 2 基础设施配置三组补充（真 PG A/B 抓回的三个真缺陷） | [`03-wave-2.md`](known-issues/03-wave-2.md) |
| 波 2 成员/邀请/api-principal 补充（真 PG A/B 抓回的 c… | [`03-wave-2.md`](known-issues/03-wave-2.md) |
| 波 2 系统管理端补充（真 PG A/B 抓回） | [`03-wave-2.md`](known-issues/03-wave-2.md) |
| 波 2 扫尾批 1（auth 注册族）补充 | [`03-wave-2.md`](known-issues/03-wave-2.md) |
| 波 2 扫尾批 2（auth OIDC）补充 | [`03-wave-2.md`](known-issues/03-wave-2.md) |
| 波 2 扫尾批 3（跨空间租户目录 + KV 配置分发器）补充 | [`03-wave-2.md`](known-issues/03-wave-2.md) |
| 波 2 终扫批（favorites + chunker 预览）补充——波 2 到此… | [`03-wave-2.md`](known-issues/03-wave-2.md) |
| 波 3 sandbox 子批 1（配置 CRUD 面）补充 | [`04-wave-3.md`](known-issues/04-wave-3.md) |
| 波 3 sandbox 子批 2（sandbox-check + template… | [`04-wave-3.md`](known-issues/04-wave-3.md) |
| 波 3 sandbox 子批 3（skills 子资源）补充 | [`04-wave-3.md`](known-issues/04-wave-3.md) |
| 波 3 sandbox 子批 4（/skills 家族 + /me/env-var… | [`04-wave-3.md`](known-issues/04-wave-3.md) |
| 波 3 协作面（organizations + shares）补充 | [`04-wave-3.md`](known-issues/04-wave-3.md) |
| 波 3 agents 批补充（含 emoji 修复的完整因果链） | [`04-wave-3.md`](known-issues/04-wave-3.md) |
| 波 3 browserskill 批补充 | [`04-wave-3.md`](known-issues/04-wave-3.md) |
| 波 4.1 事件契约包补充 | [`05-wave-4.md`](known-issues/05-wave-4.md) |
| 波 4.3 embed/im 清单面补充 | [`05-wave-4.md`](known-issues/05-wave-4.md) |
| 波 4.4 模型客户端+检索地基补充 | [`05-wave-4.md`](known-issues/05-wave-4.md) |
| 波 4.2 纯逻辑件补充 | [`05-wave-4.md`](known-issues/05-wave-4.md) |
| 波 4.5a tools 基建补充 | [`05-wave-4.md`](known-issues/05-wave-4.md) |
| 波 4.5b 补充 | [`05-wave-4.md`](known-issues/05-wave-4.md) |
| 波 4.5c 补充 | [`05-wave-4.md`](known-issues/05-wave-4.md) |
| 波 4.6a 补充 | [`05-wave-4.md`](known-issues/05-wave-4.md) |
| 波 4.6b 补充 | [`05-wave-4.md`](known-issues/05-wave-4.md) |
| 波 4.6c 补充 | [`05-wave-4.md`](known-issues/05-wave-4.md) |
| 波 4.6d 补充 | [`05-wave-4.md`](known-issues/05-wave-4.md) |
| W5a 补充（漂移修复 + 13 条小散路由） | [`06-wave-5.md`](known-issues/06-wave-5.md) |
| 收尾批 W5a 补充 | [`06-wave-5.md`](known-issues/06-wave-5.md) |
| W5b 补充（initialization 系统级 14 条） | [`06-wave-5.md`](known-issues/06-wave-5.md) |
| W5c 补充（文件代理面 8 条路由） | [`06-wave-5.md`](known-issues/06-wave-5.md) |
| W5d（沙箱终端 WS + local-browser + embed QA 委托） | [`06-wave-5.md`](known-issues/06-wave-5.md) |
| W5α1（共享 agent 读面收口：KB list/batch/search agent_id 分支） | [`06-wave-5.md`](known-issues/06-wave-5.md) |
| W5α2（QA resolveAgent 共享分支 + 执行租户切换） | [`06-wave-5.md`](known-issues/06-wave-5.md) |
| W5α3（FileAccessResolver 跨租户双授予：shared-agent 授予 + org-shared KB 证据链） | [`06-wave-5.md`](known-issues/06-wave-5.md) |
| W5β（tenant_skill verify 族 + progress 收口：校验门字节契约 + 行为三层验收） | [`06-wave-5.md`](known-issues/06-wave-5.md) |
| W5γ/W5δ/检索引擎/ArtifactCollector+VLM 等收官批（总验收抓回的三缺陷） | [`06-wave-5.md`](known-issues/06-wave-5.md) |
| 阶段 7（models/{id}/debug）新确认的细节与坑——包装器截断与终态 finish_reason 分路径都会复发 | [`07-model-debug.md`](known-issues/07-model-debug.md) |
| 阶段 7 已知差异 / 未接线 + 环境相关既有失败（B2 SYSTEM_AES_KEY、B4 pip verifier） | [`07-model-debug.md`](known-issues/07-model-debug.md) |
| 占位收口批（2026-09-23，十处「备案理由过期」缺口全修 + SsrfGuard 快照纪律 + DataSource 白名单类顺序修复 + 单发全量自附风暴定性） | [`HANDOFF.md`](HANDOFF.md) §0.-1 与 git log 9eec8a4..7744fab |
| 存储 provider 层（A3，2026-09-24：八 provider + 工厂 + 窄口接线 + 三处消费者改道；含 TOS/COS/OSS 的 SDK 坑与 SafeFileName 语义纠正） | [`08-storage-a3.md`](known-issues/08-storage-a3.md) |
| 同日批次回填（2026-09-24：追踪 C-1/C-4/C-5、图库面、评估执行体、共享 agent 两笔、标签回收、一致性批） | [`HANDOFF.md`](HANDOFF.md) §0.-14（明细在各自提交信息） |
