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
   §9 里的规则都是复发的来源，写代码前逐条对照，别等到 review。

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

## 9. 当前确认过的细节

- **Go 全局错误形态（两种并存，按 handler 实际写法区分）**：
  1. AppError 走全局 ErrorHandler：`{"success":false,"error":{"code":N,"message":"...","details":...}}`（details 恒输出，null 时为 `"details":null`）
  2. auth 中间件直接写：`{"error":"Unauthorized: missing authentication"}`（纯字符串，401）
- **⚠️ JSON 键序规则（2026-09-17 实测修正）**：Go `gin.H`/`map` 响应的键按 **encoding/json 字母序**输出；
  struct 响应按**字段声明序**输出。Java 侧：map 形态（错误信封、TENANT_REQUIRED、middleware 直写）必须按字母序构造
  （`{"error":{"code","details","message"},"success":false}`、`{"code":"TENANT_REQUIRED","error":"Workspace required"}`）；
  DTO record 按声明序 = Go struct 字段序。骨架期"插入序"假设被 golden 证伪，已修。
- **Go 时间序列化**：`time.Time` RFC3339Nano，**服务器本地时区偏移**（dev 实测 `2026-09-17T15:44:16.950624+08:00`）。
  Java：OffsetDateTime 经 JacksonConfig 统一转 JVM 默认时区再输出（ISO_OFFSET_DATE_TIME 的纳秒尾部零裁剪与 RFC3339Nano 字节一致）。
- **Go 非指针字段零值**：string 列 NULL → `""`（`"avatar":""` 恒输出）、uint64 列 NULL → `0`；
  指针+omitempty 字段（tenant 各 *Config）→ 省略。Java User getter 归一化 + TenantResponse.from 零值归一化 + NON_NULL 注解对齐。
- **soft delete 不用 @TableLogic**：GORM `DeletedAt` 改为显式 `.isNull(deleted_at)` 查询条件（datetime 逻辑删除值在 MP 各版本行为敏感，显式条件语义确定）。
- **401 三态**（auth.go）：无凭据→`missing authentication`；Bearer 携带但校验失败→`invalid or expired token`；X-API-Key 但服务未配置→`API key service is not configured`
- **已知差异（阶段 1+2，随后续模块消除）**：
  1. X-API-Key 通道：Go dev 已装配 apiKeyService → `Unauthorized: invalid API key`；Java 阶段 1 未实现 → `API key service is not configured`（golden api-key-401.json 已录，后续对齐）
  2. 登录 400 的 details：空 body 复刻 `EOF`；JSON 语法错误用 Jackson 原生消息（Go 为 encoding/json 消息，逐字节不同）——前端只读 message，golden 掩码 details
  3. AuthFilter 放行的未翻译端点（如带有效 JWT 的 GET /api/v1/auth/me）Java 404 vs Go 200——端点随模块翻译补齐
  4. JWT secret < 32 字节：Go 不报错，jjwt 解析抛 WeakKeyException（dev 走随机 32B 无影响）
  5. **POST /models/:id/debug**：需要 models/* 运行时客户端（真实上游调用），随阶段 7 agent 引擎翻译；本阶段该路由 404
  6. **非 remote 源的 CreateModel**：Go 会起 ollama 拉取协程轮询状态到 active/download_failed；Java 阶段 2 无 OllamaService → 保持 downloading 不轮转（ollama 集成随 initialization 模块）
  7. **SSRF 白名单**：Java 仅 ENV 路径（SSRF_WHITELIST + _EXTRA，对照 Go 启动兜底）；DB 运行时调谐（SystemSettingService 推送）随系统设置模块——`SsrfGuard.reloadWhitelist(raw)` 接口已就位（对照 Go SetSSRFWhitelistFromRaw）
  8. RBAC 拒绝审计落库（AuditService.LogDenied）未翻译，仅记日志；RequireSystemAdmin / RequireOwnershipOrRole 随对应模块
- **阶段 2 新确认的契约细节**：
  - gin.H 信封在模型模块大量出现：{"data":...,"success":true}（data<success 恰为字母序）；
    DeleteModel 是 {"message":"Model deleted","success":true}——message<success，别写反
  - credentials/fields 等 map 响应 key 字母序：LinkedHashMap 按序插入（Map.of 顺序未定义，会漂）
  - UpdateModel：type/source/description **无条件覆盖**（空串清空，golden 锁定）；api_key/app_secret 快照保留；
    响应用内存旧对象（时间戳不刷新，golden 锁定）
  - PG jsonb 写入：setObject(OTHER) 让服务端按列类型强转（setString 会被 PG 拒绝）→ PgJsonTypeHandler
  - ModelParameters 加密在 TypeHandler 层（对照 Go Value()/Scan() driver 钩子），读路径宽容解密失败置空
  - ModelResponse.parameters 的 omitempty int：0 → 省略（context_window/max_output_tokens/max_concurrency）
  - CheckStatus 的 needs_reinit=true 分支在 Go 中不可达（Scan 阶段已把解密失败的 secret 置空）——以代码行为为准
  - 测试共享 H2 在 JVM 内跨类复用：DDL 必须收敛到 TestSchema，CREATE IF NOT EXISTS 不补列
  - 中文 golden 比较必须按原始字节（content().bytes）——MockMvc 默认 ISO-8859-1 解码会出 mojibake
- **TENANT_REQUIRED**：有效 JWT 但无可用空间时 409（仅 tenant-optional 路由放行 tenantless）
- **Flyway baseline**：历史库（Go 98 迁移已生效）必须 `baseline-version: 97`——骨架期漏配导致 V2-V7 被重复执行（IF NOT EXISTS 侥幸成功）、V8 数据迁移失败。
  dev 库 flyway_schema_history 留有脏数据（baseline@1 + V2-V7 记录），需一次性清理后正常启动：
  `UPDATE flyway_schema_history SET version='97' WHERE installed_rank=1; DELETE FROM flyway_schema_history WHERE installed_rank > 1;`
  （清理前 Java 连 dev 库启动需 `--spring.flyway.enabled=false`；全新库从 V0 全量执行不受影响）
- **阶段 3 新确认的契约细节**：
  - KB 响应**双序列化**：单条/列表走 buildKBResponse（TreeMap 全字母序，含嵌套）；move-targets 走原始
    struct 声明序（含嵌套——capabilities 按 vector,keyword,wiki,graph,faq；chunking/vlm/asr/storage_config
    按 Go 字段声明序）→ buildListItem/build vs buildRaw 两套，勿混
  - 列表接口才回填 creator_name（Go enrichKBCreatorNames 仅 list）；get/update/pin 响应**不含**该键
  - KB 列表/移动目标排序：created_at DESC（repository L88 Order 子句）
  - manual create 的 metadata 键序：响应是**内存对象**（Go struct 声明序 content,format,status,version,updated_at）；
    经 PG jsonb 落库后键序被规范化为**（长度,字节序）排序**（doc-update golden 的 format,status,content 即 jsonb 序）——
    Java 在 PgJsonTypeHandler.parse 对 JsonNode 目标做同等规范化（H2 的 VARCHAR 不会自动做）
  - Go 非指针零值在 DB 层同样是 "":name/type/embedding_model_id/summary_model_id/file_path/file_hash/folder_path
    缺省都是 ""（GORM 写零值非 NULL）→ Java 实体字段默认值 ""，包装类型计数器用原始类型（PG 列 NOT NULL）
  - knowledge.source ≠ type：file 上传 source=""（零值），url 记 url，manual 记 manual
  - 404 消息大小写：KB 不存在是 "knowledge base not found"（全小写 k，kb_access.go），勿写成 "Knowledge base..."
  - 文档 update 联动：description 显式提交时 summary_status = 非空?"completed":"none"（即使只改 title 带 description 字段也触发）
  - folders 树只排除 parse_status='deleting'（draft 计入）；manual draft 的 "不计数" 是录制顺序假象，勿照搬
  - rerank_model_id **不在** knowledge_bases 表（迁移 000001 已删，移到 session 级配置）——实体勿加该列
  - chunking_config：chunk_size/chunk_overlap/separators 恒输出（separators 用 @JsonInclude(ALWAYS) 覆盖类级
    NON_NULL，null→JSON null）；其余字段 NON_DEFAULT（0/空/false 省略，对照 Go omitempty）
  - 领域对象上的便捷方法（isZero/isMultimodalEnabled/isAborted）必须 @JsonIgnore——否则会写进 jsonb，
    回读触发 UnrecognizedPropertyException（MyBatisSystemException: null，根因埋在 Caused by 链深处）
- **阶段 3 已知差异（后续阶段补）**：
  1. asynq → 进程内虚拟线程队列（KnowledgeProcessWorker）：单实例语义一致，多实例无 Redis 队列协调
  2. 富化扇出/多模态（VLM 图片描述）/图谱/问题生成未实现：expectedSubtasks==0 快路径，状态机 pending→processing→completed/failed
  3. 共享 KB 跨租户访问（kb_shares）、span 追踪、vector_store_id 绑定校验未实现
  4. resource:// key 内部编码与 Go 不同（opaque，不外泄）；本地存储落盘 {LOCAL_STORAGE_BASE_DIR}/{tenantId}/{knowledgeId}/{fileName}
  5. 未实现路由（阶段 4+）：clear-contents/copy/duplicate/preview/download/cancel/reparse/move/batch/tags/FAQ 导入/wiki_config 相关
  6. 删除文档为同步软删（Go 异步任务语义，响应契约一致：data.task_id + "Delete task submitted"）
- **阶段 4.0（LLM 客户端）新确认的细节与简化**：
  - **SDK/裸 HTTP 双路径合并**：Go 的 `useRawHTTP = thinkingUseRaw || adapter.ForceRawHTTP() || endpoint != "" || promptCacheForceRaw`
    是因为 go-openai SDK 的 struct 带不了 `enable_thinking` 等非标准字段。Java 统一用 Jackson
    `ObjectNode` 构造请求体 → 双路径合并为一条，`ForceRawHTTP()` 无对应物（ProviderAdapter 已删该方法）。
    **必须保留的**是各 adapter 的净效果：ShapeRequest 直接改 ObjectNode、Endpoint 覆写、Auth 分叉。
  - ThinkingStrategy 同理：Go 的 `Apply` 返回 `(customBody, useRawHTTP)`，Java 退化为纯注入
    `apply(ObjectNode, opts, isStream)`；"何时注入"的语义（nil 语义 / alwaysSend / disableOnNonStream）逐条保留。
  - **并发装饰器**：Go 的 `out` 是**无缓冲 channel**（生产者阻塞直到消费者接收），Java 必须用
    `SynchronousQueue` —— 用有界 `LinkedBlockingQueue` 会让 `offer` 在消费者不读时仍成功，
    放弃检测永不触发、信号量泄漏（阶段 4.0 实测踩过）。
  - 放弃检测差异：Go 靠 `select { case out<-resp; case <-ctx.Done(): }` 即时释放；
    Java 无 ctx，改用 `offer(timeout)` 超时判定（默认 120s，构造器可配）。语义等价但**释放是延迟的**，
    调用方若会主动放弃长流，应考虑显式机制。
  - **补全预算**：一个内部预算（`ChatOptions.completionBudget()`，MaxCompletionTokens 优先），
    出站**恰好一个** token 字段（`CompletionBudget.wireField(provider, model)`）——
    同时携带两者会被火山 Ark 之类网关直接拒绝（WeKnora#3014）。
  - provider 路由：`ProviderRegistry.detectProvider(baseUrl)` 的 26 分支**顺序有语义**，逐条照抄。
- **阶段 4.0 已知差异 / 未实现**：
  1. `langfuse_wrapper.go`：Java 侧追踪未实现 → 不做包装器（等价于 Go 未启用路径，零成本）
  2. `llm_debug.go` / `llm_debug_wrapper.go`：同上，省略（等价于 LLMDebugEnabled 关闭）
  3. `stream_raw_dump.go`：SSE 原始包落盘调试功能，默认关闭，未翻
  4. `sandbox_file_progress.go`（293 行）：沙箱工具的行数进度事件，依赖阶段 7 的 sandbox → 随阶段 7
     （RemoteApiChat 里已留接线点 TODO）
  5. `limiter` 的 **Redis 分布式限流器**（ZSET+Lua+心跳）未翻：多实例部署下并发上限不做跨进程协调，
     与 asynq→进程内队列同类取舍
  6. `ImageResolver` 的 `LocalImageResolver` 全局钩子：应用层存储模块装配点待补（当前走
     LOCAL_STORAGE_BASE_DIR 兜底，与 Go 测试环境行为一致）
  7. `logUsage` 缺 ctx 里的 purpose / 前缀指纹（日志行比 Go 略短，不影响行为）
- **阶段 4.0 发现的 Go 侧不一致（Java 已照抄并用测试钉住，改动前须知会偏离 Go）**：
  1. `transport.go` 注释写 1800s/600s，**代码实际 300s/600s** → Java 取 300/600
  2. Ollama `completionTokens`：非流式 `EvalCount - promptTokens`，流式直接 `EvalCount`——同一语义两种算法
  3. **七牛云默认 URL `api.qnaigc.com` 不含 "qiniu" 子串**，而 DetectProvider 只认 `qiniuapi.com`/`qiniu`
     → 目录默认地址喂回去会 detect 成 generic，QINIU 分支永远命中不了自家默认 URL（有 golden 测试钉住）
  4. Ollama 工具 schema：Go 反序列化进强类型 struct，**静默丢弃** `oneOf`/`additionalProperties` 等未建模关键字；
     Java 选择原样透传 JsonNode（信息只多不少，且 Go 行为会让 schema 非法）
  5. 终态 answer 的 `finish_reason`：Go 的裸 HTTP 路径不带、SDK 路径带；Java 单路径统一带
  6. WeKnoraCloud 签名函数在 Java 侧重复实现（`llm.chat.ProviderAdapters` 内一份、
     `model.service.WeKnoraCloudService.sign` 一份）——建议后续提升可见性收敛为一处
  7. Azure URL 拼接：Go 走 go-openai `fullURL`（`<base>/openai/deployments/<modelId>/chat/completions?api-version=`），
     Java 照公式复刻，api-version 取 `extra_config.api_version`，缺省 `2023-05-15`
- **阶段 4.1（MCP）新确认的细节**：
  - MCP 协议层**全自研**（无第三方 SDK）：JSON-RPC 2.0 over HTTP/SSE，两种传输
    （sse / http-streamable）。官方 MCP Java SDK 虽已 1.0.0 GA，但核心不含 OAuth，
    而 Go 侧的 OAuth 恰是 mark3labs/mcp-go 现成的——无论如何要自研，故不引入依赖。
  - **stdio 传输保留类型但硬禁用**（命令注入）：结构体、`stdio_config` 列、DTO 字段都在，
    传输层双重拒绝，与 Go 一致。
  - **密钥剥离是编译期不变式**：`dto.McpServiceResponse` / `McpAuthConfigResponse` 结构体上
    就没有 api_key/token 字段；"是否已配置"经 `credentials.api_key.configured` 布尔暴露。
    非秘密的结构配置（`auth_type`/`api_key_header`）**会**回显，密钥值不会（golden 钉住）。
  - `MCPServiceResponse.usage_instructions` 在字段序**首位**（Go struct 声明序）。
  - **MCPTool 的 `inputSchema` / MCPResource 的 `mimeType` 是驼峰**——MCP 协议规范字段名，
    别按项目惯例改成 snake_case。
  - `MCPToolApproval` 实体**直接作为** `GET /{id}/tool-approvals` 响应体，其 JSON 键名必须
    逐字对齐 Go tag（`@JsonProperty` 只影响 Jackson，MyBatis 列名仍走 underscore 映射）。
  - **工具策略的"缺行 = enabled"语义**：Go 用 map Create 绕开 GORM 省略零值，Java 仓储
    同样显式写 false，不能依赖实体默认值。
  - RBAC：`/mcp-services` 全部端点走 Admin（写）/Viewer（读）；**OAuth 的 authorize-url/status/token
    都是 Viewer+**；`/api/v1/mcp-oauth/callback` 是**公开路由**（一次性 state 自证），不注册规则。
  - 认证流程的跨请求 hack（`SetExpectedState`）与"attempt 仅在 code 交换后完成"是刻意的，
    防止"已存在的 token 满足新开的授权弹窗"。
- **阶段 4.1 已知差异 / 未接线**：
  1. **create 可写入 `id` 与 `is_builtin`**（Go 直接绑实体，Java 照抄）：客户端能钉死主键、
     或建出跨租户可见的 builtin 行。属 Go 侧既有问题，建议后续单独收紧。
  2. **create 时 `enabled` 恒为 true**：GORM `default:true` 会替换非指针 bool 的零值并回写内存，
     Go 的 POST 实际无法创建 disabled 服务；Java 显式复刻。
  3. `ErrMCPMetadataStorage`（503）在 Java 侧不可达：Go 靠接口类型断言失败触发，Java 是静态装配。
  4. **OAuth state 的 Redis 存储默认关闭**（`weknora.mcp-oauth.redis-state-store=false`）：
     Go 靠 `*redis.Client` 是否为 nil 判断，Java 的 StringRedisTemplate 只要引入依赖就存在。
     **多副本生产部署必须打开**，否则回调落到别的副本会找不到 state。
  5. `usage_instructions` 生成的 WeKnoraCloud 凭据回落缺失（TenantService 尚无该读取口）。
  6. LLM 客户端的 `langfuse` 追踪未实现 → MCP/LLM 的观测数据不落 Langfuse。
- **阶段 5（stream 流管理器）新确认的细节**：
  - **这一块是「跨语言共用的 Redis 存储契约」**，不是内部类型：Go 与 Java 两个实现
    读写同一批键。JSON 是契约本身，逐字节对齐不是洁癖——
    `ClearLiveRun` 的 CAS 是在原始 JSON 上做**子串匹配**、`UpdateSteerEventData` 的 CAS 是
    拿**读到的原文**比对槽位，两边写出的字节不同，跨语言的这两条 CAS 就会一路重试到放弃。
  - **Jackson 与 Go `encoding/json` 有两处字节差**（实测确认）：
    1. `encoding/json` 默认把 `<` `>` `&` 转义成 \u003c / \u003e / \u0026（**小写**十六进制），
       Jackson 默认**原样输出**；
    2. 其余控制字符 Go 写**小写** `\u00xx`，Jackson 写大写。
    已用 `com.ragagent.stream.GoJsonEscapes` 复刻。
    **踩坑**：Jackson 的 `CharacterEscapes` 是**整表替换**而非叠加——返回数组里没标 escape
    的字符会**原样输出**，所以 `\b \t \n \f \r \" \\` 这些"两边本来就一致"的短转义
    **也必须显式声明**（实现时漏掉，`\n`/`\t`/`\b` 直接漏成了原文，被 `StreamJsonTest` 抓到）。
  - **map 按键字母序输出**（`ORDER_MAP_ENTRIES_BY_KEYS`）：Go 的 `json.Marshal` 对 map 恒排序。
  - **`REDIS_PREFIX` 原样使用**，不做去尾冒号处理——dev `.env` 是 `REDIS_PREFIX=stream:`，
    拼出来的键是 `stream::sess:msg`（**双冒号**）。这是 Go 的原样行为，别"顺手修好"。
  - **`STREAM_MANAGER_TYPE` 是精确匹配** `"redis"`，其余一切值（含大小写不符、空）都是内存后端
    ——Go 的 `switch` 就是精确匹配，别改成 `equalsIgnoreCase`。
  - **Go 的工厂会先 Ping**，连不上就返回 error、服务起不来。Java 侧在 `StreamManagerConfig`
    里同样 fail-fast（实测：Redis 端口写错 → context 取消刷新，报 `failed to connect to Redis`）。
  - **损坏的 live-run 标记必须抛错**，不能折叠成"没有 live run"：调用方把空值当 `new_run`，
    于是 `/steer` 会在仍在生成的轮次之上叠起第二个 AgentQA。
  - **`GetEvents` 的 nextOffset 用 Redis 原始条数**：解码失败被跳过的事件也计入，
    否则每次轮询都会把同一条坏数据重拉一遍。
  - **TTL 续期点有四处**：`AppendEvent`、`GetEvents`（**含空读**）、`AppendSteerEvents`、`GetLiveRun`。
    空读续期是刻意的——模型思考期间 SSE 轮询循环走的正是这条路径，长轮次不能看起来"空闲"。
  - **测试起真 `redis-server`**（等价 Go 测试的 miniredis；`EmbeddedRedis` 会顺 PATH 找，
    找不到就整类 skip，也可用 `REDIS_TEST_ADDR` 指向已有实例）。这块的语义一半在 Lua 脚本与真实
    TTL 里，本项目其他 Redis 依赖点那种 `Fake*` 替身在这里替不掉。
    TTL 用例断言"续期前后 TTL 变大"而非"大于某个绝对值"——TTL 按毫秒存、按秒向下取整读，
    刚写 5s 也可能读到 4，断言太紧会假红（已踩过一次）。
- **阶段 5 已知差异 / 未接线**：
  1. `RedisStreamManager` 复用 Spring 的 `StringRedisTemplate`（Go 是 `NewRedisStreamManager` 自建
     client）。连接参数走 `spring.data.redis.*`，已接 `REDIS_HOST/PORT/PASSWORD/USERNAME/DB`
     （对照 Go 工厂读的 `REDIS_ADDR/USERNAME/PASSWORD/DB`）——`REDIS_ADDR` 那个 `host:port` 单串形式未直接消费。
  2. Redis TLS：`REDIS_USE_TLS` 已接（落到 Boot 的 `spring.data.redis.ssl.enabled`）；
     **`REDIS_TLS_SERVER_NAME` / `REDIS_TLS_INSECURE_SKIP_VERIFY` 无对应物**（dev / .env 均未启用 TLS）。
  3. `StreamEvent.type` 未赋值时 Java 序列化为 `null`，Go 的零值是 `""`（真实产出方恒会赋值，未复刻零值）。
  4. U+2028 / U+2029：Go 在 escapeHTML 下会转义，Java 未复刻（Jackson 的转义表只管 7-bit，
     复刻得付出的代价是把中文也一起转义，反而更远）。
  5. `RedisStreamManager` 没有 Go 的 `Close()`——连接由 Spring 管理，不归它管。
  6. **stream 目前只是装配好的 bean，还没有 HTTP 端点消费它**：session / message 模块未翻译，
     所以这一阶段还做不了 SSE 的 A/B。e2e 目前只验证到「redis 模式下服务能起、连不上时按 Go 的方式起不来」。
  7. e2e 脚本默认不导出 `STREAM_MANAGER_TYPE`（host-run 下 Go/Java 都走内存后端）；
     要用 redis 后端做联调需显式带上 `STREAM_MANAGER_TYPE=redis REDIS_PREFIX=stream:`。
- **阶段 5（session/message）新确认的细节与坑**：
  - **GORM 的 `Updates(结构体)` 会跳过零值字段**（session/message 仓储都有这条路径）。
    string `""` / 数值 `0` / bool `false` / 指针 nil / 切片 nil 一律不写进 SET——
    所以**"把 content 改成空串"在这种调用下不生效**。这是 Go 的既有语义，照抄别修。
    Java 侧要逐字段判零后再 `set`。判据：string != ""、数值 != 0、bool != false、对象/切片 != null。
    另：全部字段为零时 GORM 会生成 `UPDATE t SET` 这种非法语句而报错，Java 侧直接跳过。
  - **⚠️ MyBatis-Plus 的 wrapper `.set()` 不套用实体上的 `@TableField(typeHandler=...)`**。
    `LambdaUpdateWrapper.set(Message::getImages, list)` 会退化成 **Java 序列化**，
    落库时报 `Data conversion error converting "CAST(X'aced0005...)"`（`0xACED` 是
    Java 序列化魔数，看到这个字节串就是本坑）。
    **修法**：用 3 参形式 `set("images", value, "typeHandler=com.x.YTypeHandler")`
    （需要字符串列名，所以这类更新得用 `UpdateWrapper` 而不是 `LambdaUpdateWrapper`），
    或者干脆写一个带 `#{..., typeHandler=...}` 的显式 `@Update`。
    实体自带的 insert/updateById 路径不受影响，只有 wrapper 有这个问题。
  - **`List<T>` 型 jsonb 列读回来是 `List<LinkedHashMap>`**：泛型擦除后
    `JacksonTypeHandler` 只拿得到 `List.class`，元素类型丢失，一取元素就 `ClassCastException`。
    项目里 wiki/apikey/mcp 各自手写了 List 处理器就是这个原因；
    session 模块用了个基类收口照抄这个结论：
    `AbstractJsonListTypeHandler<T>` + 每个元素类型一个三行的子类
    （`MessageImageListTypeHandler` 等）。
    **注意写路径与 wiki 相反**：Go 的 `MessageImages.Value()` 把 nil 切片写成 `[]`
    （不是 SQL NULL），所以这里空列表也写 `[]`，别套用 wiki 那套"空列表写 NULL"。
  - **自定义 `@Select` 的结果映射不会自动套实体的 `@TableField(typeHandler=...)`**——
    要在方法上写 `@Results({@Result(column=..., property=..., typeHandler=...)})`
    （实体之外的投影行同理）。`@Result` 是**方法级**注解，写在类的字段上不生效。
  - `Message.execution_context` 与 `MessageAttachment.url` 都是 `json:"-"`，但**仍要落库**；
    `MessageAttachment.URL` 的 tag 同时管响应**和** `Value()` 的 JSON（所以它落库也不带），
    而 `Message.execution_context` 的 `-` 只管响应（它的 `Value()` 是独立方法）。
    两个 `json:"-"` 的含义不一样，逐个看 Value() 而不是一律加 `@JsonIgnore`。
  - `Message` 里三处跨模块类型（`References`→检索、`AgentSteps`→agent 引擎、
    `execution_context` 的 `QuestionSuggestionConfig`/`TagScope`）先按**不透明**类型
    （`List<Object>` / `Map<String,Object>`）透传，与 `StreamResponse.knowledgeReferences`
    的既有做法一致；对应模块翻译时再收紧类型。
  - `MessageRepository.GetMessageByRequestID` 查不到时返回 `nil, nil`（**不是**错误），
    与同文件其它读方法不同——别统一成抛异常。
- **阶段 5.2（SSE 契约层）新确认的细节与坑**：
  - **`types.JSON` 的名字陷阱**（本轮实测）：Go 的 `type JSON json.RawMessage` 是**具名类型**，
    它**带** `MarshalJSON`（`internal/types/json.go`）→ 序列化时**内联**成真正的 JSON 对象。
    ⚠️ **但只抄 `type JSON json.RawMessage` 而漏掉方法，会退化成 base64 字符串**
    （`"chunk_metadata":"eyJxdWVzdGlvbnMiOlsicSJdfQ=="`）——写对照试验程序时踩过，
    因为具名类型不继承底层类型的方法。Java 侧 `SearchResult.chunkMetadata` 用 `JsonNode`。
    另：它带 `omitempty`，判据是 `len(bytes)==0`，所以**空**才省略，字面量 `{}` 会输出。
  - **Go 的 `float64` 不用 `fmt`，有专用编码器**：`'f'` 最短表示，但 `abs != 0 && (abs < 1e-6 || abs >= 1e21)`
    时切 `'e'`（指数带 `+`、不补零）。与 Jackson 默认的 `Double.toString` 有两处**系统性**差异：
    整数值多 `.0`（`1` vs `1.0`）、指数写法不同（`1e+21` vs `1.0E21`）。
    → `com.ragagent.common.web.GoDoubleSerializer` 复刻，28 条 **Go 实录**语料钉住。
  - **⚠️ Java 的 `Double.toString` 在次正规数上不是最短表示**（实测 JDK 21）：
    最小次正规数它给 `4.9E-324`（2 位），真正能唯一往返的是 `5e-324`（1 位），Go 输出的正是后者。
    故 `GoDoubleSerializer` 在 Java 结果上再做一轮「有效位数递减」（`BigDecimal.round` + 回读校验），
    这**只会朝 Go 移动**。**任何直接 `Double.toString` 当 Go 输出的做法都会在这里分叉。**
  - **map 的键序要递归，而且嵌套 map 手工排不掉**：Go 的 `json.Marshal` 对 map 恒排序，
    项目此前的做法是「手工按字母序 `put`」——只对**一层**有效。
    `StreamResponse.data` 里的 `arguments` 是**模型返回的 JSON 参数**，键序由模型决定，
    外层再怎么排也管不到 → `GoMapSerializer`（挂在字段上，递归到 map 与数组）。
    键序按 **UTF-8 字节**比（Go 的字符串序），不是 `String.compareTo`——只在「BMP U+E000–U+FFFF
    与增补平面混排」时不同，JSON 键基本都是 ASCII。
  - **⚠️ 挂自定义序列化器会让 `@JsonInclude(NON_EMPTY)` 失效**：`JsonSerializer.isEmpty` 的
    默认实现**只看 `value == null`**，不再走 `MapSerializer.isEmpty` 判"空容器"→
    Go 的 `omitempty`（len==0 省略）会退化成"空 map 也输出 `{}`"。
    **修法是覆写 `isEmpty`**。本轮被 `StreamResponseBuilderTest.omitsEmptyDataMap` 抓到。
  - **`buildStreamResponse` 的两个字段恒不被设置**：`tool_calls` / `finish_reason` 在这条路径上
    从不赋值（照抄 Go，别"顺手补全"）。
  - **`references` 事件的三态**：`data["references"]` 缺席/`null` → 不设；活路径是
    `[]*SearchResult`（直接透传）；**从 Redis 回放**是 `[]interface{}`（元素退化成 map）→
    逐个 `searchResultFromMap` 重建；其它类型（如字符串）→ 一条分支都不命中。
    注意「元素不是 map」时 Go 是**跳过**，重建出**空但非 nil** 的 slice → omitempty 让它整个键消失。
  - **`searchResultFromMap` 只恢复部分字段**（照抄，别补全）：`match_type` 留在 `0`、
    `sub_chunk_id` 留在 `null`、`chunk_metadata`/`matched_content`/`knowledge_custom_metadata`
    不恢复；**未知键被丢弃**（但**只在重建出来的 `knowledge_references` 里**——
    `data.references` 是原样的 map，未知键照旧回显）。
    验证方法：同一个引用在两处的**键序本来就不同**（一处是 struct 声明序，一处是 map 字母序）。
  - **`sendCompletionEvent` 是刻意的空实现**（Go 的注释说明：再补一个 `done:true` 的空 answer
    会让前端状态机混乱，完成以 `complete` 事件为准）。Java 侧保留同名空方法，
    让调用序列与 Go 逐行对应，而不是删掉调用点。
  - **`setSSEHeaders` 是覆盖语义**（对应 Go 的 `c.Header`），用 `setHeader` 不是 `addHeader`。
  - **时机**：SSE 头必须在写任何正文**之前**设置——参数校验失败要能退回普通 400 JSON。
    这也是步 4 里 `resolveStreamRewriter` 必须前置的原因。
  - **遗留待办（步 3）**：`Message.knowledge_references` 仍是 `List<Object>` 透传。
    本步只把 `StreamResponse.knowledgeReferences` 收紧成 `List<SearchResult>`
    （聊天模块不产出该字段，改动零影响）；Message 那边涉及 jsonb 回读，等 `GetMessage` 一起做。
- **阶段 5.2 步 2（storageurl 包）新确认的细节与坑**：
  - **⚠️ Java 的 `$` 不是 Go 的 `$`**（本轮最值钱的一条）：Go 的 RE2 在没有 `(?m)` 时
    `$` 只匹配**文本末尾**；Java 的 `$` **还匹配末尾换行符之前**。任何"尾部锚定"的正则
    （`stream.go` 的两个都不完整引用检测）照抄 `$` 就会分叉：
    `"text local://1/a.png\n"` 这种普通分片会被 Java 判成"尾部有不完整引用"而整段扣住，
    Go 却照常发出。**改写为 `\z`**。（`\b` 则无需改：两边默认都是 ASCII 词边界。）
  - **`\s` 两边也不同（保留差异）**：Go 的 RE2 `\s` 是 `[\t\n\f\r ]`（**不含** `\x0B`），
    Java 默认 **含** `\x0B`。于是 `"text local://1/a.png\v"` 在 Go 里是一个完整 token、
    在 Java 里于 `\v` 处截断。URL 里出现垂直制表符不可能，权衡后**保留差异并写进差分测试**，
    而不是把正则改成显式字符类（那会让它离 Go 的写法更远、更易在维护时写错）。
  - **`maxHeldBytes` / `maxIncompleteImageBytes` 是「字节」不是「字符」**：
    Go 的 `string` 按 UTF-8 计长，Java 的 `String` 按 UTF-16。直接当字符数用，
    中文尾巴的扣留上限会放宽 3 倍。故 `StreamRewriter` 自备 `utf8Length` /
    `charIndexAtByteOffset`（后者等价于 Go 的 `runeStart`：把字节偏移**向下取整到字符边界**，
    增补平面字符会落到它的高位代理上——正好是该 rune 的起点）。
  - **⚠️ `copyValue` 的 `changed` 必须按值判，不能按引用判**：Go 的
    `return out, out != typed` 对 `string` 是**值**比较；而 Java 的 `rewrite()` 每次都
    `toString()` 出一个新对象。用 `converted != item` 判，每个没改动的字符串都算"改过"，
    于是 `copyData` 永远返回副本——Go 那条"元数据没变就原样返回（免得每个 SSE 事件都复制一遍）"
    的优化直接失效。修法：照抄 Go 的 `(值, changed)` 二元返回，字符串比 `equals`，
    容器用**子层递归出的 changed**（不做深比较）。
  - **`WithForcedHandleMode` → `StorageUrlContext`（ThreadLocal）**：Go 把标记塞进 ctx 随链透传，
    Java 沿用与 `TenantContext` / `APIKeyScopeContext` 一致的 ThreadLocal。
    **区别在于生命周期**：ctx 值随作用域自动失效，ThreadLocal 不会——
    设置方必须在请求结束的 finally 里 `clear()`，漏清会污染同线程的下一个请求。
  - **两种模式错误用异常继承表达**（对照 Go 的哨兵错误 + `errors.Is`）：
    `ResourceModeException` → **400**、`PublicModeForbiddenException extends ResourceModeException`
    → **403**。调用方 **catch 子类在前**。这与 §9"403 有两种形态"里
    "守卫形态 vs handler 内 AppError 信封"是同一个区分点。
  - **`NewRequestRewriter` → `Rewriter.forRequest(mode, tenant, defaultSvc, storageResolver)`**：
    `ModeHandle` 得到的是**禁用**的 Rewriter（不解析任何东西、不写 access-grant 行）。
  - **⚠️ 已知差异：provider 级文件服务未翻译**。Go 的 `BuildFileServiceForProvider` 第一步是
    `filesvc.NewFileServiceFromStorageConfig`（local/minio/s3/cos/tos/oss/obs/ks3 的 SDK 客户端，
    20+ 文件），Java 无对应物。故 `FileService` / `StorageBackendResolver` 做成
    **只有方法的端口、暂无生产实现**，调用方传 `null`。
    **可见行为与 Go 未配 `APP_EXTERNAL_URL` 的部署逐字节一致**（引用解析不出 http(s)
    → 原样保留成 handle），只有日志措辞不同。真正的公网 URL 生成要等存储后端模块。
  - **未接线（步 3 一起做）**：`RewriteMessages` / `RewriteMessagesResponse` / `rewriteAgentSteps`
    未翻译——它们要**有类型的** `AgentSteps`（Java 侧目前是 `List<Object>` 透传），
    且服务的是消息历史端点而非 SSE。`CopyReferences` / `CopyData`（SSE 要用的两个）已落地。
  - `Mode.defaultMode()` 读 `System.getenv`，**Java 侧没测**（进程内改不了 env，项目至今
    没有环境注入的测试缝）。`ParseMode` 已全覆盖，而 `defaultMode` 拿到非空值后就是调它。
- **阶段 5.2 步 3 新确认的细节与坑**：
  - **⚠️ Jackson 对 `null` 值根本不调用 `@JsonSerialize(using=…)`**（本轮第二值钱的一条）：
    序列化一个 null 字段时走的是 `nullSerializer` 分支，自定义序列化器**一次都不会被调到**。
    Go 的 `time.Time` 是**值类型**、没有"缺省"这回事（零值输出
    `"0001-01-01T00:00:00Z"`），而 Java 字段是可空的 `OffsetDateTime`——
    想靠"null → 输出 year-1 字面量"是**行不通的**。
    **修法是让字段默认值本身就持有 Go 零值时间**（`GoTimeSerializer.GO_ZERO_DATE_TIME`），
    反序列化侧也把 null/缺失回落到零值时间，往返才幂等。
    字段访问器因此是**值语义**：判"有没有时间"要用
    `GoTimeSerializer.isGoZero(t)`（对应 Go 的 `t.IsZero()`），不是 `!= null`。
  - **⚠️ jsonb 读路径的 mapper 没有 `JavaTimeModule`**：`AbstractJsonListTypeHandler` 用的是裸
    `new ObjectMapper()`。**这就是本项目此前没人往 jsonb 类型里放时间字段的原因**——
    序列化侧有 `JacksonConfig` 兜着，读回侧没有，一放就 `InvalidDefinitionException`。
    给 `AgentStep.timestamp` 挂了**成对**的 `@JsonSerialize` + `@JsonDeserialize` 之后两个方向自足，
    不依赖任何全局 mapper 配置。**后续任何 jsonb 类型要加时间字段，照这个模子来。**
  - **Go 里有两个同名 `ToolCall`，JSON 完全不同**：
    `chat.ToolCall`（OpenAI 协议形状 `id`/`type`/`function`）→ `llm.domain.ToolCall`；
    `types.ToolCall`（agent 领域形状 `name`/`args`/`result`/`reflection`/`duration`）→
    `agent.domain.ToolCall`。Java 保留同名、靠包区分（Go 也是这样），**别互相顶替**。
  - **`result` 是指针但 `json:"result"` 没有 omitempty** → nil 要输出 `"result":null`；
    `tool_calls` 同理。这两个最容易被"顺手加个 omomitempty"改错，语料已钉住。
  - **`provider_metadata` 是 `map[string]json.RawMessage`，值原样内联**（不是字符串）——
    Java 用 `Map<String, JsonNode>`，别写成 `Map<String, String>`。
  - **`List<Object>` 透传 jsonb 是实打实的契约偏差**：读回来的元素是 `LinkedHashMap`，
    键序变成 PG 的 jsonb 规范化序（长度,字节序），不是 Go 的 struct 声明序。
    `knowledge_references` 与 `agent_steps` 本轮一并收紧，模子是既有的
    `AbstractJsonListTypeHandler<T>` + 三行子类。
  - **`Rewriter.rewriteMessages` 的覆盖范围照抄 Go**：只改
    `content` / `images[].url` / `images[].caption` / `knowledge_references` /
    `agent_steps[].thought` / `agent_steps[].reasoning_content` /
    `tool_calls[].reflection` / `tool_calls[].result.output`。
    **`result.data` 不动**——别看着像 Markdown 就顺手改。
  - **`cloneMessages` 是"经 JSON 往返的深拷贝"**，与 Go 一致会**丢掉 `json:"-"` 的字段**
    （`rendered_content` / `execution_context`）。它们本来就不出响应所以不可见，
    但这是刻意行为，别"顺手修好"。它用的 mapper 需要 `JavaTimeModule`
    （否则 `created_at` 直接抛），与 HTTP 那个**不是同一个**、也刻意不是。
  - **读路径有两条，刻意分开**：`getSession`（读，带 Admin+ 回退，管理员能读渠道会话）
    vs `getOwnedSession`（写/变更，严格 owner 范围）。合成一条会让"能读"悄悄变成"能改"。
  - **Admin 回退只覆盖渠道托管行**：`loadSessionForRead` 第二跳拿到的会话若
    `requiresAdminConsoleRead` 为 false（例如别人的普通 Web 会话），仍然要 404。
    第二跳也找不到时返回的是**第一跳**的错误——不泄漏"租户里确实有这一行"。
  - **`types.MustTenantIDFromContext` 的 Java 侧不得降级**：Go 直接 panic，
    Java 抛 `IllegalStateException`。悄悄当成"无租户查询"会跨租户泄漏。
  - **`sessionUserIDForLookup` 的共享 agent 分支未接线**（属阶段 7）：
    `SessionLookupScope` 已就位但无人 `mark()`，于是查询**始终带 user 范围**。
    差异方向偏保守（Go 放行的 Java 可能 404），不是漏洞。
- **阶段 5.2 步 4（continue-stream）新确认的细节与坑**：
  - **⚠️ SSE 帧不是 Spring 的格式**：{@code c.SSEvent("message", resp)} 走 gin-contrib/sse，
    对结构体/指针走 `json.NewEncoder` 那一支，线上字节是
    **`event:message\ndata:<json>\n\n`**——冒号后**没有空格**，`data:` 里的 JSON 尾随一个 `\n`，
    再补一个 `\n`。Spring 的 `SseEmitter` 自己拼帧且格式不同，**不能用**。
    已按 Go 实录在 `SseFrameWriterTest` 里逐字节钉住。
  - **⚠️ 帧里的 JSON 用 Go 的转义**：gin 用 `json.NewEncoder`，默认开 HTML 转义——
    `< > &` 写成 `\u003c` / `\u003e` / `\u0026`（小写十六进制）。
    **这个差异不是 SSE 特有的、也不是本步引入的**：Spring 的 mapper 从来就不这么转义，
    全应用如此，只是 golden 契约文件里 `< > &` 的出现次数一直是 **0**，从没被测到。
    聊天正文（散文）是第一个踩到的，KB 名称/描述、模型 description 同样会踩。
    **已在 `config.JacksonConfig` 全局装上 `GoJsonEscapes`**（转义表挂在 **JsonFactory** 上，
    要用 `postConfigurer` 拿建好的 mapper；且 `CharacterEscapes` 是**整表替换**，
    `\b \t \n \f \r \" \\` 这些短转义必须显式声明）。
    改动后全量 1659 测试**零翻转**——印证了"从没被测到"这个判断。
    `GoJsonEscapes` 已从 `com.ragagent.stream` 移到 `com.ragagent.common.web`
    （它现在是全应用的基础设施，不再只服务 Redis 那条路）。
    `SseFrameWriter` / `StreamEventEmitter` 因此改成 `@Component`、注入**应用统一**的 mapper——
    不再给 SSE 单开一个，那正是"同一段文本在两条响应路径上给出不同字节"的来源。
  - **⚠️ Content-Type 会被 SSE 渲染器无条件覆盖**：`sse.Event.Render` 里的
    `WriteContentType` 把 Content-Type 直接赋成 `text/event-stream;charset=utf-8`，
    所以 `setSSEHeaders` 设的 `text/event-stream` **不是**线上的最终值
    （`Cache-Control` 是"没有才设"，`no-cache` 保持不变）。
  - **扣留键是 `类型 \x00 事件 id`**：同一流的增量分片共用一个 event id 才会被**重组**
    （A/B 实测：id 不同则各自独立扣留，尾巴在终止事件前被 `FlushAll` 冲成独立事件，
    带 `data:{"event_id": <原事件 id>}`）；id 相同则后一片到达时补全并整段发出。
    两条路径都已 A/B 对齐。
  - **四种失败文案各不相同，别统一**：`invalid session id`（400，路由上不可达）/
    `Missing message ID`（400）/ `session not found`（404，会话可见性也是这个）/
    **`record not found`**（404，`gorm.ErrRecordNotFound.Error()` 的原文，
    **不是** "message not found"）/ `No stream events found`（404 手写信封）/
    非法 `resource_urls`（400，文案与 Go 的 `ParseMode` 逐字一致）。
  - **手写信封的键序**：`c.JSON(404, gin.H{...})` 里 gin.H 是 **map → 键按字母序**，
    所以线上是 `{"error":"…","success":false}`，不是源码里的书写顺序。
  - **`resource_urls` 必须在写任何 SSE 头之前解析**——否则非法取值就没法落成普通 400 JSON 了。
  - **客户端断开检测与 Go 有差异（已记录，未复刻）**：Go 用 `c.Request.Context().Done()`
    立刻返回；Java 阻塞式 Servlet 拿不到等价通知，改用**写失败**（`IOException`）检测。
    后果是**延迟**而非错误：流中途断线要等下一次有事件可写才发现（活跃生成下亚秒级），
    只有"流完全停滞"会多挂一会儿。
  - **响应头层面容器固有差异（非本步引入，暂不处理）**：Tomcat 的 status line 是
    `HTTP/1.1 200 `（无 reason phrase）、Spring 的 CORS 过滤器多三个 `Vary`、
    `X-Request-ID` 的大小写与 gin 的 `X-Request-Id` 不同。HTTP 头名大小写不敏感，
    正文不受影响。
- **剩余工作的依赖结构（2026-09-18 实测重排，推翻原阶段 6/7/8 顺序）**：
  - **做法**：量路由（Go 注册 412 条 vs Java 已注册 77 条）+ 按 router 文件分布 + 逐模块量前置/
    解锁关系。**结论与原计划差得很远**，两条实测推翻了原前提：
    1. **`agent/approval` 早已翻译**（阶段 4.1，25 个文件）。按**包名**做闭包判断会把
       `mcp_service.go`(11) + `mcp_oauth.go`(6) 误判成"被 agent 阻塞"——它们其实早就可做。
    2. **`session.go` 里的 `chat_pipeline` 只是个没被用到的字段**：全仓只有两处真正调
       `eventManager`，都在 `session_knowledge_qa.go`（L728/L905）。会话 CRUD / 附件 / 产物 /
       追问建议那 ~25 条路由**不**被 agent 引擎阻塞。
    教训：**判依赖要看调用点，不要看包名闭包。** Go 的包粒度会让"只为了一个类型"
    的 import 传染出一大片假依赖。
  - **agent 不是一块巨石，是三件平行的事**：`chat_pipeline` 与 `agent` 之间只有 1 处引用
    （`chat_pipeline/data_analysis.go` 引 `agent/tools`）——是 RAG pipeline 与 ReAct agent
    两条**平行**执行路径。真正的关键路径是
    `sandbox → agent 核心 → agent/tools(20k，咽喉) → {im, skill, chat_pipeline}`。
  - **五个真叶子**（零未翻译前置，可独立开工）：`datasource`(10.8k)、`sandbox`(14.7k)、
    `browserskill`(2.5k)、`infrastructure`(11.7k)、`memory`(4.8k)。
    其中 **sandbox 是最紧的前置**——`agent/skills` 硬依赖它，且它另解锁系统管理端与 skill 模块。
  - **`internal/agent/tools`(20k) 是全局咽喉**：被 `im`、`chat_pipeline`、`skill` 三处 import，
    并经 `shared_agent_access.go` 拖住 `knowledge.go`(27) + `knowledgebase.go`(12) +
    `organization.go`(35)。它不做完，`routes_agent.go`(88) 整块动不了。
  - **`agent/tools/*` 内部还有一层可分**：wiki_*(~2.9k) / sandbox_*(~1.3k) / mcp_*(~2.2k) /
    data_analysis(~1.2k) 都可以在各自依赖就绪后再补，不必一次做完。
  - **顺带确认**：`internal/sandbox` / `datasource` / `browserskill` / `infrastructure/web_search`
    对 agent 是**干净的**，不会反向阻塞它们自己的 handler。
- **波 0（memory）新确认的细节与坑**：
  - **⚠️ 自定义 `@Select` 的结果映射不会套实体的 `@TableField(typeHandler=…)`**（本轮新踩，
    与阶段 5「自定义 `@Select` 的结果映射」是同一条，但这次的具体表现值得记）：
    `MemorySubjectMapper.selectByScope/selectByScopeForUpdate` 与 `MemoryTopicStatMapper`
    的 4 条查询**必须写方法级 `@Results`**，否则 `extraction_state` / `pending_sessions` /
    `aliases` 读回来**恒为 null**——库里明明有值、读出来是 null，H2 与 PG 都会中。
    **凡是自定义 `@Select` 且返回实体、实体上挂了 typeHandler 的，逐个检查 `@Results`。**
  - **测试不要靠墙钟造"时间已经过去"**（本轮抓到一次真实的不稳测试）：
    「第一次 enqueue 用 90s 窗口、第二次用 1ms 窗口，断言该投递了」——
    这要求两次调用间隔 ≥1ms。单跑绿、**全量跑红**（JIT/GC 压力下两次落在同一毫秒）。
    正确写法：**直接把 DB 里的时间戳推到窗口之外**（`UPDATE … SET extract_scheduled_at = ?`），
    完全不依赖计时。Go 侧没有这个用例，不存在可照抄的写法。
  - **GORM 的 CREATE 零值→DDL 默认值替换**要显式复刻：`importance 0→3`、`origin ""→extracted`、
    `status ""→active`、`enabled false→true`。不补的话会落进 DDL 默认值——**H2 与 PG 都一样**，
    但服务层拿到的内存对象与 Go 不同（Go 的钩子会回写内存）。
  - **三处「Go 源码没写、GORM 偷偷补 `updated_at`」**：`TouchUsed`、`DeleteItem` 的提议作废、
    `FinishExtraction` 的 `Update`。GORM 的 `Update` 会自动写 `updated_at`，照抄时容易漏。
  - **`MemoryExtractionSession` 是复合主键**（`(tenant_id, subject_id, session_id)`）：
    MyBatis-Plus 不支持复合主键的 `@TableId`，只标了 `sessionId`，**所有读写都走显式 SQL**。
    后续 service 若想用 `selectById` 会踩坑。
  - **`MemorySubject.pendingSessions` 的"新建 null / 读回 []"**：新建对象字段是 null
    （对齐 Go 零值的 JSON `null`），落库时由 `ensureSubject`/`saveExtractionState` 归一成 `[]`，
    读回来是非 null 空列表。两种形态并存，与 Go 一致。
  - **`MemoryConfig` 的业务方法刻意不带 `get`/`is` 前缀**（`vectorRecallEnabled()` 而不是
    `isVectorRecallEnabled()`）：本类型是响应体 + 落库 jsonb 的形状，叫 `isXxx` 会让 Jackson
    多吐一个 `vector_recall_enabled` 键（§7.5 第 2 条那个复发率最高的坑）。
  - **`Character.UnicodeScript.HAN` 表达 Go 的 `unicode.Is(unicode.Han, r)`**；
    且 Java 的 `Character.isWhitespace` **不含** U+00A0 而 Go 的 `unicode.IsSpace` **含**，
    故 `isGoSpace` 显式补上——与 §9 那条 `\s` 差异同族。
  - **未覆盖**：`searchItemsByVector` 的 SQL 排名路径（需要真 PG + pgvector）**无自动化测试**，
    只有手跑验证 → 回归保护为零，建议排一个 e2e 步骤。
- **memory HTTP 层（波 0 第 4 步）新确认的细节与坑**：
  - **⚠️ 同一个 service 方法，两条响应路径的 nil 语义不同**（本轮最值得记的一条）：
    `GET /memory/items` 的空仓库是 `"data":[]`，而 `GET /memory/export` 的空仓库是
    `"data":null`。原因在 Go 侧：`ListItems` 的仓储走 GORM 的 `Find(&items)`，
    **GORM 会把 nil slice 初始化成非 nil 空切片**（`[]`）；
    而 `Export` 是 `var items []*types.MemoryItem` + `append`，
    空仓库时**一次 append 都没发生** → 仍是 nil → `null`。
    **Java 侧要把 `Export` 的列表声明成可空的 `List<MemoryItem> items = null`**，
    只在真的有行时才 `new ArrayList<>()`——写成 `new ArrayList<>()` 就再也拿不回 `null` 了。
    这与 Wiki 那条"`ListIssues` 归一为 `[]`"是**相反方向**的取舍：这里对齐了 `null`，
    因为它就在导出文件的第一个字节上。
  - **`gin.H` 的键序在这些端点上"反直觉"**：`Clear` 的线上字节是
    `{"removed":N,"success":true}`——`removed` 在 `success` **之前**，
    与源码里的书写顺序（`"success", "removed"`）相反。Export 同理
    （`data, success, total, truncated`）。一律按**字母序** `put` 进 `LinkedHashMap`。
  - **`Export` 的两个头**：`Content-Disposition: attachment; filename="weknora-memories.json"`
    + **`Content-Type` 仍是普通 JSON**（Go 是 `c.Header(...)` + `c.JSON`，
    不是 `application/octet-stream`）。Java 侧 Java 这条端点显式带 `charset=utf-8`，
    是全局 JSON 端点里最接近 Go 的一条（其余 Java 端点是裸 `application/json`）。
    ⚠️ **容器层仍差一个空格**：Go 写 `application/json; charset=utf-8`，
    Tomcat 的 `setContentType` 会规范成 `application/json;charset=utf-8`
    （MockMvc 里保留原样、真容器上去掉 OWS）。RFC 7231 下 OWS 可选、语义等价，
    与既有的 status line / `Vary` / `X-Request-ID` 大小写同属容器固有差异。
  - **`memoryListPaging` 是容错的**：`limit` 非法、≤0 或 >200 一律归 50，
    `offset` 为负归 0——实测 `?limit=abc&offset=-5` 返回 **200 空页**而不是 400。
    但 `status` 白名单校验**先于**分页解析：`?status=bogus` 一律 400（与 limit 无关）。
  - **`EmptyContent` / `PreviouslyForgotten` 落 500 而不是 400**：两个异常刻意
    **不在** `fail()` 的 switch 里。实测 `POST /memory/items {"content":"   "}` →
    `500 {"error":{"code":1007,"details":"memory: empty content","message":"Failed to create memory"}}`
    ——`message` 是 **handler 传进来的那句**（`Failed to create memory`），
    `details` 才是 `err.Error()`。别把这两者搞反，也别"顺手"把它改成 400。
  - **校验错误的 code 是 1010 不是 1000**：`NewValidationError` → 1010（`Invalid request data`），
    `NewBadRequestError` → 1000（如 `enabled is required` / `unsupported status` /
    `memory is disabled`）。同一个 handler 里两者并存。
  - **`createItem` / `promoteTopic` / `consolidateNow` 在"没有主体"时抛 `Disabled`（400）
    而不是 `NoScope`（401）**——service 侧走的是 `enabledScope()` 的**布尔**判定，
    NoScope 在那里被吞成了"不许用记忆"。照抄，别在 handler 里"修正"成 401。
  - **NoScope（401 `no principal in request`）在这 16 条路由上实际不可达**：
    整组都要求 Viewer 角色，能进来的请求一定有主体。分支保留（对照 Go 的 `fail()`），
    但没有实测 golden——报告里要说明这一点。
  - **405 的情况**：Go 里 `DELETE /memory/items` 与 `DELETE /memory/items/:id` 是
    两条独立路由；Java 侧同理，别让 `/**` 的 Ant 规则把两者合并。
  - **`RbacInterceptor` 的 Ant 模式够不到两段路径**：`/api/v1/memory/items/*` 匹配
    `/items/{id}` 但**不**匹配 `/items/{id}/confirm`。所以 confirm/reject/promote
    这些两段路径要按 `/**` 登记（与 Wiki 段的既有写法一致）。
    **顺序仍是"静态段在前"**——`match()` 取首个命中，而 Ant 的 `/a/**` 连 `/a` 自己都匹配。
  - **API-Key 策略是纯 `fullAccess()`，不带任何能力**（对照
    `apiKeyGroup(r.Group("/memory", g.Viewer()), apiKeyFullAccess())`）。
    与 `/sessions/continue-stream` 的 `chat(fullAccess())` 是**有意**的差别：
    记忆属于**一个人**（`subject_id = principal.StorageID()`），
    而 scoped 集成 Key 代表的是一个系统。实测：带 `chat` 的 scoped Key → 403
    `{"error":"Forbidden: API key scope does not allow this operation"}`，
    full-access Key → 200，两侧与 Go 逐字节一致。
  - **A/B 结果（Go :8080 vs Java :8082，同一 dev PG）**：16 个端点 + 各种边界共
    **36 组请求，35 组逐字节 MATCH**。唯一 DIFF 是**非法 JSON 请求体**的
    `details` 文案（Go 的 `encoding/json` vs Jackson；`code`/`message` 一致），
    已归入 §9 阶段 1 差异 #2 那一族。空 body 的 `details:"EOF"` 是逐字节一致的。
  - **复现 A/B 的注意点**：`scripts/go-server-up.sh` 必须从 **WeKnora 目录**调用
    （viper 在 cwd 下找 `config/config.yaml`，否则 `Config File "config" Not Found`）；
    且 host-run 需要 `DB_DRIVER`/`DB_USER`/`DB_PASSWORD`/`DB_NAME`/`RETRIEVE_DRIVER`
    等**非地址类**的 env（`dev-env.sh` 只覆盖地址，所以要先 `set -a; . ./.env; set +a`
    再把 `DB_HOST/DB_PORT/REDIS_ADDR/DOCREADER_ADDR` 覆盖回 localhost）。
    不这么做会在启动时 panic `unsupported database driver:` 或找不到配置文件。
  - **契约测试要自己清 memory 的 7 张表**：`TestSchema.resetData` 不含它们
    （该文件不归 memory 模块改），照 `MemoryRepositoryTest` 的写法显式 `DELETE`。
- **波 0（datasource）新确认的细节与坑**：
  - **map 里数字的浮点格式也要对齐**：`GoMapSerializer` 只排键序、值原样交给 Jackson，
    而 `Resource.metadata` / `connector_cursor` / `settings` 里必然有数字（分页偏移、条目上限），
    Go 的 `float64` 会写 `1` 而 Jackson 写 `1.0`。故新增
    `datasource.domain.DataSourceMapSerializer`：在按键排序之上再把 map 值的 `Double`
    走 `GoDoubleSerializer`。**没有动共享的 `GoMapSerializer`**——那会让所有用它的地方
    一起改变行为，而这条规则只在"map 值可能是浮点"的字段上才需要。
    **后续模块若也有"数字进 map 且要出响应"的字段，照这个模子做模块内的子类。**
  - **jsonb 列不一定需要 `FieldStrategy.ALWAYS`**：`data_sources` 三个 jsonb 列在迁移里
    **没有 DEFAULT**，MP 省略 null 列恰好落 SQL NULL，与 Go 的 `JSON.Value()` 一致。
    与 wiki 的 `page_metadata`（有 `DEFAULT '{}'`，必须 ALWAYS）**相反**——
    **逐个查迁移里有没有 DEFAULT，别一刀切。**
  - **GORM 的"三步舞"可以退化成一步**（要能证明净效果）：`sync_deletions` 写入 Go 走
    `Create`(GORM 把 false 替成 true) → `UpdateColumn`(写回原值，SkipHooks 不刷 updated_at)
    → 回写内存；三步的净效果就是"落库与内存都等于调用方原值"，Java 直接插原值。
    **退化必须写出证明，并用用例钉住**（本轮钉了"false 真的落库"）——
    这类"看起来多余实则有净效果"的 Go 代码，退化前要先把它连起来算一遍。
- **波 0（datasource 连接器层）新确认的坑——四条都会复发**：
  - **⚠️ 日志断言必须显式 `setLevel` 再还原**：`logging.level.com.ragagent: WARN` 的级别过滤
    发生在 **appender 之前**，所以任何断言 INFO/DEBUG 日志内容的测试，**单跑该包绿、一旦与
    任何 `@SpringBootTest` 同批跑就红**（拿到空串）。这是"单跑绿全量红"的**新变种**，
    与之前那个墙钟不稳是两回事。修法：测试里 `logger.setLevel(INFO)` + `@AfterAll` 还原。
  - **JDK 的 `com.sun.net.httpserver.HttpServer` 必须 `setExecutor(...)`**，否则它的默认
    分派器是单线程且会挂死（测试表现为超时）。做 stub server 时容易漏。
  - **`HttpExchange.getRequestBody()` 只能读一次**：读第二次得空串且**不报错**——
    需要重放的场景要先把字节缓存下来。
  - **Javadoc 里不能出现字面的"星号+斜杠"**，会提前结束注释块（本轮踩了两次）。
    写正则或路径时用 `&#42;` 之类转义，或改写措辞。
  - 另外：`SsrfGuard` 的白名单是**进程级静态**，连接器测试必须 `reloadWhitelist(...)` 放行
    loopback、并在 `@AfterAll` 还原（§9 里"多 Spring 上下文互相覆盖"那条的延伸）。
  - `TaskScheduler.shutdown()` 在 Spring 6.1.14 **不存在**（6.2 才有）。
- **波 0（datasource service + HTTP 层）新确认的细节与坑**：
  - **⚠️ Java 的 `Long != Long` 是引用比较（本轮最值钱的一条）**：租户 id 这类
    **包装类型**之间写 `a != b`，判的是引用；`10002` 超出 `Long` 缓存区间（-128..127），
    装箱出来的两个实例恒不相等。症状是**每一个请求都 404**（`DataSourceCredentialsController`
    实测：凭据子资源全组 404）。**修法**：`Objects.equals(...)`，或让比较的一侧是
    **原始类型**（方法签名写 `long tenantId` 就会自动拆箱，`DataSourceController`
    的守卫正是因此没中招）。Go 的 `uint64 != uint64` 是值比较，翻过来时最容易漏。
  - **⚠️ GORM 的 struct `Updates` 会把 `updated_at` **写回内存对象**（实测）**：
    `stmt.SetColumn("updated_at", curTime)` 直接改 `stmt.Dest`（就是调用方那个
    `*types.DataSource`）。所以 PUT 的响应里 **`updated_at` 是本次更新时间、
    而 `created_at` 仍是 Go 零值 `0001-01-01T00:00:00Z`**（后者是 AutoCreateTime，
    零值被跳过、不回写）。Java 的 wrapper 不回写实体 → 必须由仓储显式
    `ds.setUpdatedAt(同一个值)`，否则 PUT 响应会退化成两个零值时间
    （golden `ds-update.json` 钉住）。
  - **`json.RawMessage` + `omitempty` 的判据是 `len(bytes)==0`**：`config` /
    `last_sync_cursor` / `last_sync_result` / `error_message` / `latest_sync_log` 在
    DTO 上**省略整个键**，而同一个字段在**实体**上是恒输出的——两种形态并存，
    别把 DTO 的 omitempty 抄到实体上（反之亦然）。
  - **`DataSource.ParseConfig()` 会返回 `(nil, nil)`**（`len(d.Config)==0` 短路）。
    service 的 `validateDataSourceConfig` 把它**原样递给连接器**（各连接器自己拒绝，
    实测文案是 RSS 的 `"invalid configuration: config is nil"`）。把这些 null
    提前折叠成泛泛的 `ErrInvalidConfig` 会改掉**暴露出来的那句话**。
  - **`ResolveResourceAncestors` 的短路在 service、不在 handler**：空 `resource_ids`
    在 service 里直接返回空切片，但 handler **仍然先跑** `getOwnedDataSource`
    ——所以"未知 id + 空列表"回 404，"存在的 id + 空列表"回 200。别把短路提到 handler 前面。
  - **两个 handler 文件的错误形态不同**：`datasource.go` 全是
    `c.JSON(status, gin.H{"error": msg})`（**纯字符串**），
    `datasource_credentials.go` 全是 `c.Error(errors.NewXxxError(...))`
    （**AppError 信封**）。而且复制的两份归属判定**有意不同**：租户缺失时一个回 401
    `unauthorized`、另一个回 400 `Workspace ID cannot be empty`；跨租户的知识库
    一个回 403 `access denied`、另一个折叠成 404（消息不同）。照抄，别统一。
  - **凭据子资源的"字段缺失"回的是 go-playground/validator 的原文**
    （`Key: 'dataSourceCredentialsPutRequest.Credentials' Error:Field validation for
    'Credentials' failed on the 'required' tag`）——它是稳定的线上字符串，已逐字复刻。
    空 map 是**另一条** 400（`credentials map must be non-empty; …`）。
  - **RSS 的 `feed_urls` 是非密钥配置**：`HasConfiguredCredentials("rss")` 只看
    `auth_headers`。所以 PUT 一个只有 feed_urls 的 credentials 之后
    `configured` 仍是 **false**（实测，golden 钉住）。
  - **`/datasource/types` 在 Go 侧本来就不可逐字节复现**：先遍历 map（随机序）再做
    **稳定**插入排序，同优先级的条目顺序每次调用都不同（实测相邻两次调用里
    `feishu_drive`/`lark_drive` 就换了位）。**按 type 建索引比**，别按下标比。
  - **`limit` 严格、`offset` 容错**：`/datasource/{id}/logs` 的 `limit` 只要给了就必须
    落在 1..100（含 `abc` → 400 `limit must be between 1 and 100`），而 `offset`
    解析失败或为负一律归 0（200）——与 memory 那套"非法 limit 归 50"**相反**。
  - **A/B 的日志比对要"等终态"**：`POST /{id}/sync` 之后 Go 的任务要经 Redis 派发给
    asynq worker（实测 ~1-2s 跑完），Java 是进程内虚拟线程队列——发完立刻比 `/logs`
    就是在比竞态（会看到 `finished_at` 一边 null 一边有值）。脚本里等
    `finished_at` 非空后再比，顺带把**真实同步的终态计数**也比了（实测两侧都是
    `status=success total=2 created=2 failed=0`）。
  - **`SsrfGuard` 白名单是进程级静态**：A/B 与契约测试里的 RSS 都要真的抓 loopback
    上的 stub feed，所以 **Go 侧启动必须带 `SSRF_WHITELIST=127.0.0.1,::1,localhost`**
    （不是 `_EXTRA`），Java 侧同名 env；`../scripts/ab-datasource.sh` 与
    `record-datasource-golden.sh` 都固化了这件事，且 golden 的 `feed_urls`
    与 stub 端口（18099）是**响应的一部分**，改端口要重新录。
  - **契约测试里把 `DataSourceSyncTaskQueue` 换成 mock**：真队列会在后台虚拟线程里
    跑完整同步（抓 feed → 往 H2 写 knowledge 行），与断言无关又互相干扰。
    队列是"传输"不是契约面（与 memory 把 task queue 留在真实服务外的取舍一致）。
- **波 0（datasource service + HTTP 层）已知差异 / 未接线**：
  1. **asynq → 进程内队列**（既有取舍的延续）：拿不到 `asynq.GetRetryCount` →
     `streamStartCursor` 的 `attempt` 恒为 0（等价于"手动全量同步的第一次尝试"，
     重试的全量同步会从头再抓而不是续跑）；拿不到 `asynq.GetTaskID` → 同步审计的
     details 少 `task_id` 一个键（`trigger`/`processing_status` 照常）。
     `ManualSync` 的 TaskID 由 Java 侧生成 UUID（Go 是 asynq 生成的随机串）。
  2. **知识库写入是"最小闭环"**：`MapperKnowledgeBridge` 只覆盖
     "落一行可被后续同步找回的 knowledge + 交给进程内处理队列"。Go 的
     `CreateKnowledgeFromFile` 另有：文件名安全校验、按 KB/连接器解析多模态与问题生成
     配置、标签关系、按 KB 选存储引擎、asynq 载荷形态、入队失败的补偿与审计。
     **净效果**：同步进知识库的内容本身弱于 Go（解析/分块仍走阶段 3 的 worker）。
  3. **自动标签无生产实现**：`knowledge_tag` 模块未翻译 → `NoAutoTagProvider` 恒回 null，
     等价于 Go 的 `autoTag == nil` 分支（同步照常、条目没有自动标签）。
  4. **langfuse 追踪未接线**：`InjectTracing` 是 no-op（§9 阶段 4.0 差异 1）。
  5. **进程内队列拿不到跨副本去重**：调度器的两层去重里，第 2 层（确定性 TaskID）
     只在单 JVM 内有效——多副本会各自触发一次（与 memory/wiki 同族取舍）。
- **JSON 编码器的系统性差分排查（本轮的专项）**：
  - **做法**（可复用）：读 Go `encoding/json` 的 encoder 源码定出**类别**（转义分支、
    浮点编码器、整数、容器），为每类构造语料，用独立 Go 程序录出真值，再拿**容器里那个
    mapper**（`@SpringBootTest` 注入，不是自己 new 的）对表。
    成果是 `GoJsonEncodingContractTest`（27 条）——它把下面这张表变成可自动检查的。
  - **起因**：HTML 转义那一类之所以被发现，纯粹是因为聊天正文碰巧踩到了。
    其余类别**没有任何 golden 覆盖**，是同一类潜伏风险：某个响应恰好在某个字段里出现
    某种字符或数值就会分叉，而且没人会预料到。
  - **逐类结论**：

    | 类别 | 状态 |
    |---|---|
    | `< > &` 的 HTML 转义 | ✅ 已**全局**对齐（`JacksonConfig`） |
    | 控制字符小写十六进制、`\b \t \n \f \r \" \\` | ✅ 对齐（`GoJsonEscapes`，注意是整表替换） |
    | U+2028 / U+2029 | ⚠️ **已知差异，刻意保留**——详见下条 |
    | `int64` / `uint64` | ✅ 对齐（uint64 上限超出 Java `long`，用 `BigInteger`） |
    | `null` / `[]` / `{}` 形状 | ✅ mapper 层不额外加工，取舍是逐字段的事 |
    | **double 的值格式** | ⚠️ **逐字段**，见下 |
    | **map 键序** | ⚠️ **逐字段**，见下 |

  - **⚠️ U+2028 / U+2029 是刻意保留的差异**：Go **会**把它们转义（`"a\u2028b"`，
    为的是 JSON 能直接当 JavaScript 求值），而 Jackson 的 `CharacterEscapes`
    **够不到非 ASCII**——生成器对 `> 0x7F` 的字符走另一条路径，压根不查转义表。
    要复刻只能给 `String` 注册全局自定义序列化器，或开 `ESCAPE_NON_ASCII`（那会把中文
    也一起转义，反而更远）。这两个字符出现在正文里的概率极低、且对解析 JSON 的客户端不可见，
    权衡后保留，并**在测试里显式钉住当前行为**，免得将来有人以为它对齐了。
  - **⚠️ double 的值格式只能逐字段**：Go 走专用编码器（整数值不补 `.0`），
    Jackson 走 `Double.toString`。**刻意不做全局注册**——`serializerByType(Double.class,…)`
    会连 `ChatOptions` 这种**发给 LLM provider 的请求体**一起改掉（`"temperature":1`
    取代 `1.0`），收益为零、风险实在。
    **新增含 double 的响应类型时，字段上必须挂 `GoDoubleSerializer`。**
  - **⚠️ map 键序只能逐字段**（Go 对 map 恒按字节序排，Jackson 不排）。
    本轮排查发现：**golden 契约文件里所有 map 字段都只有 1 个键**
    （`headers`/`credentials`/`env_vars` 全是单键）——多键 map 的键序**从未被验证过**。
    已给响应侧的 5 个字段补上 `GoMapSerializer`：
    `McpService.{headers,envVars}`、`McpServiceResponse.{headers,envVars,credentials}`、
    `WikiStats.pagesByType`、`llm.domain.ToolCall.providerMetadata`。
    **新增响应类型时，Map 字段上必须挂 `GoMapSerializer`**（它对已排好序的 map 是幂等的）。
    注：jsonb 列另有一套（PG 的 `(长度,字节序)` 规范化），别混——那套走
    `PgJsonTypeHandler.parse` 的 JsonNode 规范化，见 §9「KB 响应双序列化」。
- **跨阶段通用坑（阶段 4 新增）**：
  - **领域对象的 isXxx() 便捷方法必须 @JsonIgnore**——已在阶段 3 记录，阶段 4 又踩一次
    （`McpAuthConfig.isOAuth()` 导致整个 auth_config 列落库后读不回）。这是**复发率最高的坑**，
    新增任何「对照 Go 方法」的便捷访问器时先想它。
  - **同族的第二种形态：Java 字段名以 `is` 开头**（阶段 5 新增）。Go 的字段是 `IsPinned` /
    json tag `is_pinned`，直译成 `private boolean isPinned` 会**多吐一个键**：
    Jackson 给字段的隐式属性名是 `isPinned`、给 `isPinned()` 这个 getter 的是 `pinned`，两者
    对不上 → 各生成一个属性 → JSON 里同时出现 `is_pinned` 和 `pinned`。
    **修法：字段改名去掉 `is` 前缀**（`private boolean pinned` + `@TableField("is_pinned")`），
    getter 仍是 `isPinned()`。这样字段与 getter 的隐式名都是 `pinned`、合并成一个属性，
    再被 `@JsonProperty("is_pinned")` 改名。**顺带解决** MyBatis-Plus 的 lambda：
    `Session::isPinned` 按 PropertyNamer 推成 `pinned`，正好对上字段名（否则运行期抛
    `can not find lambda cache for this property`）。
    既有实体（如 `McpToolApproval.requireApproval`）没这问题，因为**字段名不带 `is` 前缀**——
    Java 字段名一律跟随 Go 去掉 `Is` 前缀的那部分。
  - **⚠️ `JsonContractRoundTripTest` 抓不到上面这条**（本轮实测）：多出来的 `pinned` 键在
    反序列化时被 `setPinned` 接住，往返仍然幂等，测试是绿的。**而且**当时写键序断言的正则
    是 `"([a-z_]+)"`，驼峰键名直接被过滤掉。
    所以：**带 `is` 前缀布尔字段的响应体，必须额外钉一条「键序 + 键数」断言**，正则要写成
    驼峰感知的 `"([A-Za-z_][A-Za-z0-9_]*)":`。范式见
    `server/src/test/java/com/ragagent/session/SessionJsonContractTest.java`。
  - **jsonb 回读的 ObjectMapper 要容忍未知属性**：Go 的 `json.Unmarshal` 默认**忽略**未知字段，
    Jackson 默认**失败**。TypeHandler 里用的裸 ObjectMapper 必须配
    `FAIL_ON_UNKNOWN_PROPERTIES=false`，否则历史行/新增字段会让整行读不出来。
  - **写脚本时注意 zsh 特殊变量**：`$GID` 在 zsh 里是只读的组 ID，赋值 UUID 会炸
    （bad math expression）——e2e 脚本用 `SVC_ID` 之类的名字。
  - **`source .env` 会覆盖 host-run 需要的地址**：WeKnora 的 .env 是容器内地址
    （DB_HOST=postgres / REDIS_ADDR=redis:6379 / DOCREADER_ADDR=docreader:50051），
    host-run 必须逐项覆盖为 localhost + 映射端口（15432/16379/50051）。只想要某个 key
    （如 SYSTEM_AES_KEY）时用 `grep -m1 '^KEY=' .env | cut -d= -f2-` 单独取，别整份 source。
  - **跨语言加密互操作的 e2e 前提是两侧 SYSTEM_AES_KEY 相同**：不同 key 下读回会静默置空
    （宽容解密→configured:false），这是预期的密码学行为不是 bug。验证互操作必须用同一个 key。
- **工具链与调试坑（跨阶段复用，阶段 3 实测）**：
  1. JUnit XML 的 failure `message` 属性会截断长 diff（~4KB），且 `content().bytes` 失败时 expected/actual
     以十进制字节数组呈现——直接按 byte 解码或找首个差异位，别信肉眼截断的片段
  2. `<testcase>` 与 `<failure>` 的归属正则会跨用例误配（贪婪匹配）——定位失败一律以堆栈里的
     `KnowledgeContractTest.java:行号` 为准
  3. MyBatis 层异常报 `MyBatisSystemException: null`（NPE 被吞 message）——根因必在日志
     `Caused by:` 链深处（如 jsonb 回读 UnrecognizedPropertyException），别在业务代码里瞎找
  4. `./gradlew test --tests X` 通过后跑 `./gradlew test` 可能全 up-to-date——先看
     `build/test-results/test/*.xml` 的 tests/failures 计数和时间戳再下结论
  5. 契约断言顺序：先 status 再 body——body 断言失败时若 status 也错，优先修 status（500 时 body 无意义）
  6. H2 与 PG 行为差：NOT NULL 约束、jsonb 键序、DDL 默认值都会在 H2 绿、PG 炸——e2e 必须连真 PG 过一遍
     （阶段 3 的 source/folder_path/channel 零值、rerank 列都是 H2 测不出来的）
  7. golden 录制顺序会影响响应内容（如 folders 计数、列表顺序）——测试必须**严格复刻录制序**，
     反推语义前先怀疑顺序
- DB：schema 与 98 个迁移一字不改；端口：后端 8080（前端 dev 代理默认值）；dev 库 localhost:15432
- 测试：契约/单测用 H2 内存库（server/src/test/resources/application.yml），不依赖外部 postgres；golden 文件在 server/src/test/resources/contracts/；动态字段（token/refresh_token/时间戳）两侧同掩码后比对
- **波 1 G1（session CRUD+pin）新确认的细节与坑——前四条都会复发**：
  1. **AppError 二次包装**：controller 的 `catch (RuntimeException e) → BizException.internal(e.getMessage())`
     会把 **BizException 自身**也再包一层——`BizException.getMessage()` 是
     `"error code: N, error message: …"` 前缀形态，再包一次就变成
     `"error code: 1007, error message: error code: 1002, …"`。必须先 `instanceof BizException` 直通。
  2. **渠道来源筛选的拒绝是 500 不是 403**：Go service 返回 `NewForbiddenError`（AppError），
     handler 对非 NotFound 错误一律 `NewInternalServerError(err.Error())`——而 AppError.Error()
     的形态是 `"error code: 1002, error message: listing channel sessions requires tenant admin or owner role"`。
     golden 实测前按直觉写成 403，A/B 抓回。
  3. **gin form 绑定 `omitempty,min=1` 的语义**：`page=0` 显式传入同样被 omitempty 跳过
     （200 且服务层归一化成 1），只有**负数**才触发 min tag；非整数是 strconv 原文
     `strconv.ParseInt: parsing "abc": invalid syntax`；超界是 go-playground validator 原文
     `Key: 'Pagination.PageSize' Error:Field validation for 'PageSize' failed on the 'max' tag`。
  4. **`queryPaged` 的 is_pinned 映射缺陷（golden 抓到的真 bug）**：自定义 `@Select` 的
     自动映射按 `is_pinned → "isPinned"` 找属性，而实体属性名是 `pinned`（字段名刻意去 is 前缀，
     见 Session.pinned 注释）→ 列表置顶态**恒为 false**。§5 第 7 条（自定义 @Select 不套实体注解）
     的同族：**@Results 里凡是"实体属性名 ≠ 列名驼峰"的列都要显式映射**。
  5. **Go JSON 解析错误文案仿真**：session 的 400 把解析器原文放在 `message`（golden 锁字节），
     Jackson 措辞不同 → `common.web.GoJsonBindError` 仿真顶层形态（EOF / 字面量扫描 /
     looking for beginning of value）。**已知差异**：body 以 `{ [ " 数字 -` 开头但深层结构坏掉时
     回落 Jackson 消息——前端正常请求不触发，录到此类 golden 再补。
  6. **请求体 `null` 字面量**：Go 的 `ShouldBindJSON` 遇 `null` 零值绑定**不报错**——
     Java 侧 `readValue` 返回 null，要按空对象处理，别 NPE。
  7. **dev PG 的列表 golden 带遗留数据**：录制时测试租户里有历史会话，`session-list*.json`
     有 4 个条目。契约测试用 SQL 精确播种（含 updated_at 相对顺序）复现录制状态；
     A/B 脚本对变更类用例要**两侧各用各的 id**（Go 先打会消费状态，Java 复打同一 id 必 DIFF）。
  8. **删除路径的清理三件套**：知识清理在 Go 是 goroutine 异步 + cleanup-scope 授权，
     Java 暂为同步尽力而为（HTTP 不可见）；webSearchState 清理与 destroyBoundSandbox
     以 TODO 占位（随波 2/波 3 收口）。`SanitizeForLog` 不只是日志卫生——批量删除把它
     的输出**当真实入参**（`"  "` 两个空格保留 → 判不可见），Java 侧 `LogSanitizer.sanitize` 逐字对照。
- **波 1 G2（消息面）新确认的细节与坑——前三条都会复发**：
  1. **search 的 match_type 全是 "hybrid" 是 partner 补对造成的**：关键词只命中
     Q/A 一侧时，`fetchPartnerMessages` 补的另一条 matchType 是**空串**，
     Go 的合并分支 `g.MatchType != item.MatchType`（**不排除空串**）→ "keyword" != "" →
     升 "hybrid"。Java 首版多写了 `!item.matchType().isEmpty()` 守卫，golden 抓回。
     **凡是照抄 Go 的 merge/compare 分支，一个条件都不能多加**。
  2. **keyword 模式不是透传**：Go 走 `convertKeywordResults` 赋线性分值 (n-i)/n
     （单结果 = 1，不是 1.0——GoDoubleSerializer 逐字段）；hybrid 模式走 RRF
     （k=60，分值 1/(60+rank)）。两条路径的分值公式完全不同。
  3. **两个 404 文案刻意不同**：会话不可见是 `session not found`（ErrSessionNotFound），
     消息不存在是 **gorm 的原文** `record not found`（handler 直接透传 err.Error()）。
     Java 的 `MessageNotFoundException` 消息不是契约，controller 里用常量 `RECORD_NOT_FOUND`。
  4. **消息没有 HTTP 创建端点**（聊天管线产生，波 4/5）——golden 录制与 A/B 造数
     都要 psql 直插 dev PG（messages 表大半列有默认值，显式给
     id/request_id/session_id/role/content/created_at 即可）。H2 测试同款播种。
  5. **搜索的向量路径依赖 ChatHistoryConfig**（未配置时 Go 跳过向量搜索）——
     Java 的 retrieval/HybridSearch 未翻译，恒走"未配置"分支，未配置租户两侧一致。
     已知差异只在"租户配置了聊天历史 KB"时出现（TODO 随 retrieval 收口）。
  6. **A/B 的删除用例两侧各删各的孪生行**（同内容不同 id）——Go 先删会消费状态，
     Java 复删同 id 必 404，那组 MATCH 是**假绿**（空转）。清空（clear）是幂等的，
     两侧先后打同一会话即可。
- **波 1 G3（追问建议）新确认的细节与坑——前三条都会复发**：
  1. **writeError 的子串分派要逐字照抄**：gorm.ErrRecordNotFound → "suggestions not found"
     （**消息不存在也落这**，不是 "message not found"）；会话 404 是独立分支
     （"session not found"）；业务 400 靠 `strings.Contains` 匹配
     "completed assistant" / "invalid suggestion event" / "requires question_id" /
     "does not belong" / "not allowed"——Java 侧的 IllegalArgumentException 文案必须
     含这些子串；其余 500 用**固定文案** "message suggestion operation failed"（不透传）。
  2. **Ensure 的请求体只在 ContentLength > 0 时解析**：空 body 合法
     （regenerate=false）；畸形 JSON → 400 固定文案 "invalid request body"
     （不是解析器原文——与 sessions/messages 的 GoJsonBindError 路径不同）。
  3. **ready 集合的复用是幂等契约**：ensure 对已有 ready/suppressed 集合（不 regenerate）
     直接返回既有结果（AcquireGeneration acquired=false），两侧先后打同一资源响应一致——
     A/B 不需要孪生数据。
  4. **GetFollowUps 的缓存键是五元组**：(tenant, assistant_message_id, placement,
     config_hash, locale)。config_hash 缺省 "no-agent-config"、locale 缺省
     DefaultLanguage（zh-CN）——直插的 ready 集合必须用这两个缺省值才能被命中。
  5. **LLM 生成步的降级**：generateWithModel 依赖 ModelService 运行时工厂（阶段 7）、
     generateFromKnowledge 依赖 customAgentService（波 2/4）。降级走 generateErr 路径
     → failed/generation_error（HTTP 形态与生成失败一致）；未配置 follow-ups 的
     默认路径（suppress "disabled"）两侧逐字节一致。
  6. **metric 字段的 omitempty 形态**：MessageSuggestionSet 的
     suppression_reason/error_code/model_id（空串省略）、prompt/completion_tokens/
     latency_ms（0 省略）、generated_at（nil 省略）、agent_id（**无** omitempty，恒输出
     空串）——逐一对照，agent_id 漏了空串输出就会分叉。
- **波 1 G6（产物/title/stop）新确认的细节与坑——前三条都会复发**：
  1. **`Long != Long` 又咬了一口**（陷阱 §5 第 6 条复发）：stop 里
     `session.getTenantId() != tenantId`（Long vs Long）引用比较恒不等 →
     正常请求被误判 403 "Access denied"。**凡租户/ID 比较，一律 `.longValue()` /
     `equals` / 先拆箱**——这个坑第 2 次出现了，写比较表达式前先看两边类型。
  2. **jsonb List 处理器必须挂 JavaTimeModule**：MessageArtifact 带
     OffsetDateTime 字段（mod_time/created_at），AbstractJsonListTypeHandler 的
     静态 MAPPER 缺 JSR310 模块 → artifacts 列**整列**反序列化失败（读消息就炸）。
     这类缺陷只有"真的写带时间字段的 jsonb"才暴露——新增带时间的 jsonb 元素类型时，
     先写一条往返测试。
  3. **validator 的 required 对切片是"非 nil 即通过"**：`{"messages":[]}` 通过
     binding 走到了模型查找（golden 实测 500 "no KnowledgeQA model..."），
     只有字段**缺失（null）**才 400。别按直觉把空列表也拒了。
  4. **stop 的错误形态是纯字符串信封** `{"error":"..."}`（c.JSON 直写，不是
     AppError 信封），且 GetMessage 在 GetOwnedSession **之前**——会话不可见也落
     404 "Message not found"。成功形态 {"message","success"}（字母序）。
  5. **stop 事件的 type 是字符串强转**：`types.ResponseType(event.EventStop)`
     = "stop"，不在 Go 的 ResponseType 常量表里但真实落存储——Java 的
     ResponseType 枚举补了 STOP("stop")（跨语言键空间契约）。
  6. **artifact 下载的 access 层未翻译**：确定性分支（index 非法/越界/路径缺失/
     404 文案）逐字对照后，恒落 404 "artifact not accessible"（与 Go catalog
     查不到资源同一出口）。Go 的 fileService==nil → 500 分支生产装配不可达。
  7. **generate_title 的已知差异**：LLM 调用依赖运行时模型工厂（阶段 7）——
     确定性路径（已有标题 / 无 user 消息 / 无 KnowledgeQA 模型）逐字对照；
     走到 LLM 那步 Java 以 500 收场（与 Go 运行时失败同形态）。
- **波 1 G4（steer）新确认的细节与坑——前三条都会复发**：
  1. **binding required 先于业务 trim**：Go 的 `query binding:"required"` 在
     ShouldBindJSON 里就拒掉空串/缺失（validator 原文），handler 里那句
     "query must not be empty" 只有**纯空白**才可达。校验顺序写反 golden 立刻分叉。
  2. **validator 的 required 对 string 是"非零值"**：空串失败；对 slice 是"非 nil"
     （与 G6 的 messages:[] 行为同源）。两类字段别想当然。
  3. **live run 是 agent 引擎的产物**：HTTP 层造不出"有活轮"状态——steer 的
     排队/注入路径在引擎接线（波 4/5）前无法 golden/A/B，用"直种 streamManager 的
     单测"钉住响应形态（StreamManager.setLiveRun + appendSteerEvents 可直接调）。
  4. **gin.H 响应的字母序在 steer 里随处可见**：
     already_injected/queued/gone/deleted 各形态的键序都是字母序
     （removed < status < steer_id < success；assistant_message_id < delivery < ...）。
  5. **StreamBatch 是 record**：访问器是 `events()` 不是 `getEvents()`——
     既有代码 5.2 就写对了，新代码凭直觉写错会编译错（本轮）。
  6. **503（ServiceUnavailable）是 steer 的可重试语义**：live run / 前序投递
     查询失败 → 503 "Failed to look up running turn"（客户端 toast 后重试，
     而不是开第二轮）。BizException 需补 serviceUnavailable 工厂。
- **波 1 G5（临时文档 attachments）新确认的细节与坑——前四条都会复发**：
  1. **`@TableField(typeHandler=…)` 在 MP 生成的 insert/update SQL 里生效的前提是
     `@TableName(autoResultMap = true)`**——而且 **`LambdaUpdateWrapper.set(col, val)`
     根本不带 typeHandler**，必须用三参重载 `set(col, val, "typeHandler=<FQCN>")`。
     两处都漏就是 A/B 实测的 "column \"chunks\" is of type jsonb but expression is of
     type character varying"。value 属性也要显式给（照 SyncLog 模式）。
  2. **时间列的"双形态"**：naive 列（timestamp without time zone）由 Go 写入的值
     （expires_at/started_at/ready_at，time.Now() 本地墙钟）读回渲染 **Z 形态**，
     但**创建响应里的 expires_at 是内存值**渲染 **+08:00 形态**；created_at/updated_at
     是 DB 默认（PG 服务器 UTC）恒 **Z**。Java 用 OffsetDateTime +
     `GoNaiveOffsetDateTimeTypeHandler`（写=原 offset 墙钟、读=UTC 解释）对齐；
     created_at/updated_at 无 RETURNING 回填，service 显式赋 now(UTC)。
  3. **Go nil slice 的 jsonb 链路**：text 路径 parse 返回 nil images → json.Marshal →
     4 字节 `"null"` 字面量写进 jsonb（**不是 SQL NULL**）→ 读回 MarshalJSON →
     响应里 `"image_refs":null`。Java 字符串字段存 `"null"` + `@JsonRawValue`
     输出（H2 列 NOT NULL，写 SQL NULL 直接炸）；`readJsonArray` 要把 `"null"`
     当空数组。**IdType.ASSIGN_UUID 是 32 位无连字符 hex**——Go 是带连字符
     UUID，必须 IdType.INPUT + service 显式 `UUID.randomUUID()`。
  4. **ext 在 service 层带点**：`filepath.Ext` 只 Lower 不去点——`file_type` 是
     `".txt"`、报错 `"unsupported file type: .exe"`；传给 docreader 才去点。
     纯文本管线的终态（metadata `{"parser":"plain_text"}`、token_count/chunk_count、
     chunks jsonb 形态）与 Go **逐字节一致**——chunker auto/1600/160 + ApproxTokenCount
     的对齐被顺手验证。
  5. **存储布局按 Go 逐字对齐**（`{base}/{tenant}/exports/chat_attachment_{uuid12}{ext}`
     → `local://` 引用）：resource_ref 虽是 json:"-"，但 A/B 跨服务互读依赖两侧同布局。
     baseDir 必须 `toAbsolutePath().normalize()`——相对路径（./build/test-files）下
     `startsWith` 会误判路径穿越。
  6. **上传容器的非 multipart / 空 size 语义**：非 multipart 请求 Go 的 FormFile
     固定报 `request Content-Type isn't multipart/form-data`（400），multipart 但缺
     file part 是 `http: no such file`；**空 size 不在 controller 拒**（FormFile 收
     0 字节，由 service 报 "file size must be between 1 byte and 50MB"）。
     MaxBytesReader 超限 → `http: request body too large`（multipart 上限放宽到
     60MB 让 service 的 50MB 校验先触发）。
  7. **已知差异（记录在 service 类注释）**：任务队列用进程内单线程 executor
     （asynq 随波 4）；扩展白名单静态表（ListEngines 未翻译）；agent_id 门控
     （shared-agent config/Audio/VLM）随波 5/7；preview 响应头用 servlet
     `setHeader` 原样写（Spring/Tomcat 的 Content-Type 规范化会去掉 charset= 前空格，
     A/B 脚本已归一化该容器噪音 + Vary + reason phrase）。

- **波 2 chunk（编辑面）新确认的细节与坑——前四条都会复发**：
  1. **同一个 service 异常在三个 handler 的 HTTP 形态各不相同**（本轮最重要的发现）：
     `UpdateDocumentChunk` 的业务失败（空内容/加图/非 text/超 200000 字节）在 Go 是
     `fmt.Errorf` → update handler 包 **500** 信封 code=1007 且 message=原文；
     revert handler 对非 AppError 包 **400**；questions 三端点把**一切**错误包成
     **400** `NewBadRequestError(err.Error())`（含 AppError 的双前缀原文）。golden 实测：
     `PUT` 空内容 → 500 `"chunk content cannot be empty"`。**不能给 ChunkService 写一个
     统一的异常映射**——每个端点单独 catch。
  2. **分页钳位是三段 if，不是 clamp**（golden 抓回的真 bug）：Go L117-125 是
     `page<1→1`（**无上限**，page=5 合法）、`size<1→10`（**缺省语义**，size=2 是合法值
     不会被抬高）、`size>100→100`。写成 `clamp(size, 10, 100)` 会让 page_size=2 静默
     变 10、clamp(page,1,1) 让所有页码变 1——列表"看起来对"但翻页坏了。
  3. **revert 未知 revision → 400 "record not found"**（gorm 原文透传，非 404）；
     `revision` 缺失 → validator 原文 `Key: 'RevertChunkRequest.Revision' ... 'required' tag`
     （required 挂在 ***int** 上，`null`/缺失都触发）；`question:"   "` **过** binding
     （required 对 string 是"非零值"），由 service 落 `"question cannot be empty"`——
     与 G4 的"required 先于业务 trim"是**相反**的顺序，按端点实录。
  4. **delete-question 的模型链在 metadata 变更之前**：无 embedding 模型的 KB 上
     `DELETE /chunks/by-id/:id/questions` 三连发全部落 400
     `"failed to get embedding model: model ID cannot be empty"`——问题**从未**被真正
     删除，metadata 不变。service 内的顺序（writableChunk → 找问题 → kb → engine → model）
     必须逐字照抄，不能把"找不到问题"的 400 提前到模型校验之后。
  5. **gin.H 字母序的两个新形态**：update/revert 成功响应是
     `data < description < success < summary_status`；list 是
     `data < page < page_size < success < total`。knowledge 重载失败时
     description/summary_status 两个键**整体缺席**（只剩 data+success）。
  6. **Chunk 的响应化注解**：`source_content`/`context_header` 是 `json:"-"`；
     三个 json 列（relation_chunks/indirect_relation_chunks/metadata）对照
     types.JSON.MarshalJSON——空输出 `null`；**7 个非指针 string 列的 getter 归一化
     NULL→""**（tag_id/parent_chunk_id/pre_chunk_id/next_chunk_id/content_hash/
     last_editor_id/image_info，对照 GORM 扫描 NULL 进非指针 string 的零值语义——
     H2 列可空，不归一化会输出 `null`）。`is_enabled` 字段名带 is 前缀但
     getter `isIsEnabled()` 隐式属性名与字段一致 → 合并成一个属性，安全。
  7. **ChunkAccessGuard 的分层与放行语义**（对照 RequireOwnershipOrRole +
     RequireKBAccess 的中间件链）：ownership 守卫里资源在调用者空间**不存在 → 放行**
     （ErrResourceNotFound 透传，交给后续守卫/handler 出 404）；KB 访问层
     knowledge 缺失 → 404 `"Knowledge not found"`（大写 K）、chunk 缺失 → 404
     `"Chunk not found"`、KB 缺失 → 404 `"knowledge base not found"`（小写 k）、
     跨租户 → 403 信封 `"Permission denied to access this knowledge base"`、
     非创建者写 → 403 **纯字符串**。by-id 的 ownership 查找显式重校验租户
     （GetChunkByIDOnly 无空间过滤）。判定顺序 golden 依赖，不能重排。
  8. **契约测试的固定 id 必须是纯十六进制**：掩码正则认 `[0-9a-f-]`，`kkk…` 开头的
     种子 id 不会被掩码 → 与 golden 的真 uuid 对不上。A/B 的 seq_id 来自 PG 序列、
     两侧必然不同 → A/B 里掩码 `"seq_id":`，契约测试里播种精确复刻 golden 值。
  9. **A/B 残留清理要含 chunk_revisions**：只清 chunks/knowledges 的话，上一轮的
     revision 快照会让下一次 update 撞 `idx_chunk_revisions_chunk_revision` 唯一索引
     ——两侧同型 500 但 JDBC 错误包装文案不同 → DIFF。幂等种子 =
     `DELETE chunk_revisions → chunks → knowledges`（外键序）。
  10. **旧 Java server 进程占 8082**（本轮实际踩到）：上次会话的 bootRun 还在跑时，
     新启动端口冲突直接 BUILD FAILED，但 `wait_for_port` 打到**旧进程**照样报 ready
     ——表现为"新路由 404"。`java-server-up.sh` 前先 `kill` 旧进程（`lsof -i :8082`）。
  - 已知差异（记录在 ChunkService 类注释）：syncChunkIndex 只对齐"策略关 → 早退"分支
    （测试数据全走这条）；KB 需要 embedding 时，模型行缺失与 Go 同形报错，模型存在则
    Java 恒 failed（检索引擎未接线，随波 3/4）；Regenerate 的 LLM 生成步降级
    （summary model 存在时报 "summary model is not available in this deployment"，随阶段 7）；
    delete-question 的向量删除 WARN+no-op（模型行校验保留）。
- **波 2 knowledge 文档操作面（15+1 条路由）新确认的细节与坑——前四条都会复发**：
  1. **⚠️ EnsureDefaults 会把"全关索引"重置成默认值**（本轮最值钱的发现）：
     Go 每次经 `knowledgeBaseService.GetKnowledgeBaseByID` 读 KB 都跑
     `kb.EnsureDefaults()`，其中 `IndexingStrategy.IsZero()`（vector/keyword/wiki/graph
     **全 false**）会被替换成 `DefaultIndexingStrategy()`（vector+keyword=true）——
     所以"用 SQL 关掉全部索引"对 service 层的 NeedsEmbedding 判定**无效**。
     实测：UpdateImageInfo 链尾的 updateChunkVector 恒走
     `GetEmbeddingModel("")` → 500 `"model ID cannot be empty"`（golden
     kg-image-update/again/mismatch 钉住）。Java 侧照抄：读 KB 后
     `isZero() → defaultStrategy()` 再判 NeedsEmbedding。同理"关 summary model"只影响
     regenerate-summary 的判定（那里读的是 SummaryModelID，不受 EnsureDefaults 影响）。
  2. **ValidateInput 的 XSS 正则要求闭合标签**：`"<script>x"` **不**命中
     `<script[^>]*>.*?</script>`，会通过校验并归一化成文件夹 `<scriptx`
     （golden kg-move-badpath=200、kg-rename-badto=200 moved_count=2 钉住）。
     common.security.InputSanitizer 的 16 条正则逐条照抄，别"补全"安全性。
  3. **同一个"跨租户"有两种 403 文案，取决于守卫在哪一层**：路由挂了
     KBAccessFromKnowledgeIDParam 的（stages/spans/download/preview/reparse/cancel/
     manual/image/regenerate）在中间件层拒绝 → `"Permission denied to access this
     knowledge base"`；body 路由（/knowledge/tags 无 kb_id，从首条 knowledge 推导 KB）
     在 handler 的 resolveKnowledgeAndValidateKBAccess 里拒绝 →
     `"Permission denied to access this knowledge"`（不带 base）。判定顺序：
     tags 路径的 handler 链**不做** requireKbAccess 的 KB 查询（Go 直接用
     knowledge 行上的 tenantID 判），Java 侧单独走 resolveKnowledgeHandlerLevel。
  4. **clear-contents 的两次连续调用都是 "task submitted"+相同计数**：Go 异步
     worker 没跑完时第二次 list 仍看到行。Java 用 parse_status='deleting' 标记 +
     计数复刻该窗口（golden kg-clear-again 钉住）；list 侧只排除 parse_status=
     'deleting' 的过滤同时作用于 folders 计数（ListKnowledgeFolderCounts）与
     clear 的行清单——别在 clear 的 list 里额外加 status 过滤。
  5. **批处理路由的行校验文案三处刻意不同**：batch-delete 的 count 不符 →
     `"One or more knowledge entries not found"`；batch-reparse 的 →
     `"some knowledge entries were not found"`；move（requireKnowledgeInKB）→
     `"One or more..."`。且 batch-delete/batch-reparse 逐行先 `RejectMovingKnowledge`
     （409）再查跨 KB；move 则由 service 层 loadKnowledgeWriteBatch 兜
     （404 "knowledge not found" 小写 / 409 / 403 `"knowledge outside target KB"`）。
  6. **tags 的授权 grant 只覆盖一个 KB**：kb_id 路径 = 显式 kb_id；无 kb_id =
     首条 knowledge 的 KB。loadKnowledgeWriteBatch 逐 KB 校验
     requireKBWrite，落在授权 KB 之外 → 403 `"无权修改该知识库"`（service 层，
     早于 authorizedKBID 的 scope 校验；golden kg-tags-cross-kb 钉住）。
     而 tags-unknown-knowledge 在同一批加载里先出 404 `"knowledge not found"`
     （小写 k）。两个文案的先后顺序 golden 依赖，不能重排。
  7. **gin.H 的字母序有两处新形态**：spans 响应 data 键序
     `attempt < current_attempt < current_stage < knowledge_id < last_error <
     latest_attempt < parse_status < trace`，last_error 内
     `code < error_code < error_message < finished_at < message < name < stage`；
     SpanTreeNode 按 struct 声明序（children 恒最后，空缺席）。合成树的
     created_at/updated_at 是响应时刻（掩码）。
  8. **文件下载/预览的头是逐字节契约**：下载固定 `Content-Type: application/octet-stream`
     + `Content-Description/-Transfer-Encoding/Expires`；预览按
     SafeContentTypeByFilename（.md → `text/markdown; charset=utf-8` inline）。
     Content-Disposition 走 mime.FormatMediaType：token 安全（ASCII 且非 tspecials）
     → `filename=kg-doc.txt`；否则 `filename*=utf-8''%E6...`（小写 utf-8、大写十六进制）。
     Seeker（磁盘文件）→ `Accept-Ranges: bytes`；manual（内存 reader）→
     `Accept-Ranges: none` + 显式 Content-Length。manual 文件名 =
     sanitizeManualDownloadFilename(title)（换行删除、斜杠转 `-`、引号转 `'`、补 .md）。
  9. **GET /knowledge/batch 的绑定顺序**：uint64 form 字段的 strconv 映射错误
     （details=`strconv.ParseUint: parsing "abc": invalid syntax`，message 仍是
     `"Invalid request parameters"`）先于 validator 的 IDs required；`?ids=`（空值）
     通过 required 进服务层 → `data:[]`。agent 共享路径未翻译恒 403
     `"no permission for this shared agent"`（与 Go 的 agents==nil/not-found 同文案）。
  10. **manual 更新的响应 metadata 是内存对象**（content,format,status,version,updated_at
      声明序），version=旧值+1，updated_at 是 RFC3339 秒级 UTC；经落库再读回（如
      reparse 响应）才变成 jsonb 规范化键序（format,status,content,...）——两种形态
      在同一轮 golden 里并存，别统一。
  - 已知差异（记录在 KnowledgeService 类注释）：① batch-delete/clear-contents 为同步
    尽力而为（batch-delete=软删、clear=parse_status='deleting' 标记；HTTP 契约一致，
    真正的向量/文件/wiki 回收缺位）；② reparse 的 process_config 覆盖不落地（仅支持
    null/缺省，覆盖校验随 worker 收口）；③ regenerate-summary/向量更新在模型存在时
    报 "…not available in this deployment"（运行时模型工厂随阶段 7）；④ spRepo 未
    翻译 → spans 恒走 spanRepo==nil 分支（rows 空 + latest_attempt=0 + ?attempt=N
    透传），buildSpanTree/knowledgeSpansLastError 全量翻译非降级；⑤ shared-agent /
    org-share 两条授予路径未翻译（恒 403/仅同租户），与 wiki/chunk 同源。
  - 测试基建：TestSchema 增 knowledge_tags/knowledge_tag_relations（迁移 000001 §10 +
    000063；seq_id 播种显式给值）；错误 message 里内嵌的 UUID（"…does not belong to
    knowledge base X"）要用**裸 UUID 掩码**（不带键名上下文），本测试类 mask() 已带。
    录制脚本 scripts/record-knowledge-golden.sh 的 KG 行 file_name/file_hash 逐行
    固定（文档 body 断言 file_size/hash 是字面量不是掩码）；KG1 的文件落在
    LOCAL_STORAGE_BASE_DIR/kgdocs/（local://kgdocs/kg-doc.txt，两侧同布局）。

- **波 2 knowledge 搜索与移动/复制（8 条路由，knowledge 域收官）新确认的细节与坑——前四条都会复发**：
  1. **同一个"binding 失败"，move 和 copy 的 HTTP 形态刻意不同**（本轮最重要的发现）：
     move 是 `NewBadRequestError("Invalid request parameters: " + err.Error())` →
     **message 带前缀、details=null**，且 validator 把**全部**失败字段按 struct 序用 `\n`
     连接成一条 message（`{}` 空 body → 四行 Key: ... required tag；部分合法 → 只列失败字段）；
     copy 是 `NewBadRequestError("Invalid request parameters").WithDetails(err.Error())` →
     **message 固定、validator 原文在 details**。hybrid-search 也是 details 形态
     （EOF / GoJsonBindError 原文）。三处别统一。
  2. **跨租户 KB 的 403/404 取决于守卫在哪一层**：move 的 handler 直接
     `GetKnowledgeBaseByID`（租户无关）再自己判租户 → 403 "No permission to access
     source/target knowledge base"；copy/duplicate 走 `resolveHandlerKBAccessFor` /
     路由 KBAccessRead → cross-tenant source 是 **403 "Permission denied to access this
     knowledge base"**（access.ResolveKB 的 ErrForbidden，不是注释说的 NotFound——golden
     实测推翻了源码注释）；duplicate 的 404 "Source knowledge base not found"（大写 S）
     被路由中间件的 404 小写 "knowledge base not found" 挡成**不可达死代码**。
  3. **进度终态的 `created_at` 恒为 0**：handler 准入时 SetNX 的 pending 进度带
     created_at=now，但 asynq worker 每步都 `&types.KBCloneProgress{...}` 新对象
     **不带 created_at** → SET 覆写后读回来的就是 0。Java 侧照抄（新 record 构造传 0），
     别"顺手保留"。move 终态 message 是逐条推进的 "Moved X/N knowledge items"
     （完成块不改 message），clone 终态是 "Knowledge base clone completed successfully"
     （覆盖逐条 "Processed X/N clone operations"）。
  4. ** EnsureDefaults 会传染进 duplicate/copy-create**：duplicate 的响应
     `indexing_strategy`/`capabilities` 是**读路径 EnsureDefaults 之后**的形态
     （DB 全关 → 响应 vector+keyword=true）；clone-create 的 Go worker 建 KB 行**不复制**
     源的 indexing_strategy 字段，EnsureDefaults 补成 vector+keyword。Java 用
     JSON 往返（CLONE_MAPPER，**必须挂 JavaTimeModule**——KnowledgeBase 带
     OffsetDateTime，裸 mapper 直接 500）+ `KnowledgeBaseService.ensureDefaults`。
  5. **`vector_store_engine_type` 键的有无 = 部署状态**：Go 的 buildKBResponse 只在
     `storeView.EngineType != ""` 时写键；envDefaultStoreView 的 EngineType 取
     `envStores[0]`——阶段 3 的 kb-get golden（09-17 录）有 "postgres"、本轮 duplicate
     golden（09-19 录）没有：当前 Go dev 的 envStores 为空。Java 侧
     `KnowledgeBaseResponseBuilder.build(kb, driver, includeEngineType)` 重载分开两条路，
     **别把两份 golden 互相"修"成一个样子**。
  6. **dev PG 的存储后端回填**：Go API 建 KB 时 applyAndValidateStorageBackend 会把
     租户的 System LOCAL（legacy alias、source=env）写进 storage_backend_id +
     provider=local——duplicate 响应里这两个字段依赖它。契约测试要**种子一个
     storage_backends 行 + KB 行带 storage_backend_id/storage_provider_config**，
     否则 duplicate 的 storage_backend_id 输出 null、provider 输出 ""。
  7. **搜索是租户级全库扫描**：SearchKnowledge 的 scope = 本租户全部 document KB
     （repo JOIN 再按 type=document 过滤）；`recent=true` 的 total=租户全量行数——
     **dev PG 有历史残留，不过滤的 recent golden 不是契约稳定值**（本轮只录
     file_types=url 收敛后的 recent，脚本里留了注释占位）。keyword 搜索用租户内唯一
     关键词（ksdoc）收敛命中集合；种子行 created_at 必须显式且互不相同（Go 按
     created_at DESC，并列顺序不稳定）；file_types 别名（xlsx↔xls/docx↔doc/
     jpg↔jpeg↔png、url/html→type='url'）与 `% _ \` 的 LIKE 转义要逐字照抄。
  8. **task id 是跨请求契约**：`<type>_<tenant>_<millis>_<8hex>[_<biz12>]`，进度路由按
     嵌入租户段隔离（解析失败 400 "invalid task ID"、跨租户 404 "task not found"、
     同租户查无 404 专属文案 "Knowledge move task not found" / "KB clone task not found"）。
     Java 照 ParseTaskID 的"定位 (tenant,ts) 对"算法实现（type 段可含下划线）。
  9. **hybrid-search 的确定性降级**：retriever 未接线（波 4）→ 前置确定性分支
     （KBAccessRead 守卫、query_text 必填、resource_urls 解析、multi-KB scope 授权
     ——空集/越权/主库缺席都是 404 小写）逐字翻译后，检索执行落 Go 的"零结果"出口
     `{"data":null,"success":true}`（空库+空 embedding 的 dev KB 在 Go 也是这个形态，
     golden 钉住）。**有绑定且命中数据时 Go 能出结果**——已知差异记在 Javadoc。
  10. **GET 带-body 的兼容路由**：`GET /knowledge-bases/{id}/hybrid-search` 与 POST 同
      handler（#1727），JSON body 缺失同样 400 EOF——别按"GET 无 body"写绑定。
  - 已知差异（记录在 KnowledgeService/KnowledgeTaskProgressStore 类注释）：
    ① hybrid-search 的检索执行随波 4（含 multi-KB embedding 一致性校验）；② move/clone
    只做到行级（knowledge+chunk 行），向量索引/文件对象/wiki/FAQ tag 映射不复制；
    ③ 进度存储是进程内 map（24h 读路径 TTL 对照 Redis SET EX；单实例语义一致，多副本
    无跨进程可见性）；④ asynq 的 retry/preflight-failed 中间态不翻译（进度直接落终态）；
    ⑤ search 的 org-shared 补捞与 agent_id 分支未翻译（scope 恒本租户文档库；agent_id
    恒 403 "no permission for this shared agent"，与 batch 路由同款）。
- **波 2 FAQ 补充（真 PG A/B 抓回的跨列缺陷）**：
  - **PG 列 DEFAULT 演进会让旧 Java 实体整行读不出来**：`knowledge_bases.chunking_config`
    的 PG 默认值（迁移后新增）含 `split_markers`/`keep_separator`，Go 的
    `json.Unmarshal` 静默丢弃未知键，Java 的 `KbChunkingConfig` 裸抛
    UnrecognizedPropertyException → **该 KB 的一切读写 500**。修法：全部 Kb*Config
    jsonb 类挂 `@JsonIgnoreProperties(ignoreUnknown = true)`（对照 Go 的容忍语义）。
    **凡"裸 SQL/新迁移写的行"都可能有 Java 实体不认识的键**——契约测试用 API 建的行
    永远踩不到，只有 A/B 的裸种子行暴露（H2 绿 PG 炸的又一变种）。
  - **裸种子 KB 行的 NULL jsonb 列**：`KnowledgeBase.getIndexingStrategy()` 对 null
    兜底 defaultStrategy()（照 Go Scan 的 NULL→零值）——这类兜底要逐列检查，别只兜一个。
  - FAQ 的其余实录坑（无细节 500 形态、先落库后 500、mode 缺失即 400、
    "分页参数不合法"单独文案、dry_run 进度富化、faq-upsert-running 为 A/B 预期 DIFF）
    见 FaqService/FaqController 类注释与 §8 台账行。
- **波 2 基础设施配置三组补充（真 PG A/B 抓回的三个真缺陷）**：
  1. **自定义 jsonb TypeHandler 写库必须 `setObject(i, json, Types.OTHER)`**——三个新
     handler（WebSearchParams/ConnectionConfig/IndexConfig）都写成 `setString`，H2 全绿、
     PG 直接 "column ... is of type jsonb but expression is of type character varying"
     （§9 阶段 2 的结论在自定义 handler 上复发：**setString 不行，与 handler 声明无关**）。
  2. **GORM TableName() 覆写是陷阱**：`types.StoredResource.TableName()` = **"resources"**
     ——agent 按结构体名造了 `stored_resources` 影子表并在 TestSchema 建出来自圆其说，
     H2 绿、真 PG 500 "relation does not exist"。**翻译守卫查询前先查 TableName()**。
  3. **create 响应的 AutoCreateTime 回写**：GORM Create 会把 now 回写内存对象，
     controller 直接序列化实体——Java 的 `repo.create(entity, now)` 把 now 当独立参数
     就丢了回写 → 响应恒 year-1。**insert 后显式 setCreatedAt/setUpdatedAt(now)**
     （datasource 波 §9 的 PUT updated_at 回写是同族教训）。
  4. wsp 的 PUT 把 created_at 清零（Go `Select("*").Updates` 写零值 year-1，之后所有
     GET 恒 year-1）是**字面契约**，Java 写 SQL NULL 读 null→GO_ZERO 字面量跨语言等价；
     vs 的 PUT 只 Select("name") created_at 保持——同文件族内两组行为刻意不同，照抄。
  - 其余实录坑（同一 404 两种形态、test 端点的 AppError 双前缀差异、Go map 迭代随机、
    PreserveIfRedacted、env stores 部署状态、viewer 用例误带 owner 头的录制坑）
    见各 Controller/Service 类注释与 §8 台账行。
- **波 2 成员/邀请/api-principal 补充（真 PG A/B 抓回的 clearStaleHomeTenant 落库链）**：
  - **clearStaleHomeTenant 的落库有两个专属坑**：① `updateById(user)` 会把
    `tenant_id=0` 写进 UPDATE → FK `fk_users_tenant` 直接炸（Go 是
    `Omit("tenant_id").Save` + `UpdateColumn("tenant_id", nil)`——显式 NULL，不是 0）；
    ② `users.preferences` 是 jsonb 列，wrapper 两参 `.set(col, obj)` 缺 typeHandler 直接
    MyBatisSystemException（§9 三参规则）。**净修法：单条 wrapper 只写
    `tenant_id=NULL`**；preferences 存量偏差无害（pref==home 与 home 走同一解析路径，
    已记录为已知偏差）。
  - **B 的两种 token 形态是契约场景**：曾入成员→登录→成员行被删的 JWT（带租户上下文）
    访问 /me/* 落 403 "not a member of the target workspace"；从未是成员的重登 JWT
    （tenantless）访问 /me/* 走 tenant-optional 200。契约测试必须复刻对应的登录时序，
    不能用"从未是成员"的 token 去比 403 场景。
  - 其余实录坑（@PathVariable 名字必须与模板一致、MP 分页 count 不能带 orderBy、
    gin.H 内层 map 字母序、邀请 seq_id/invite_url/JWT 的掩码策略）见
    TenantMemberContractTest 与各 Controller 注释。
- **波 2 系统管理端补充（真 PG A/B 抓回）**：
  - **阶段 3 的 UserKbPin 列映射是错的**：真表列名是 `kb_id`/`pinned_at`，实体按驼峰
    映射成 `knowledge_base_id`/`created_at`——fillPin 在真 PG 直接 500。**H2 的 DDL 是
    照实体写的，永远发现不了**；pin 路由此前从未过真 PG A/B 所以潜伏至今。
    **凡"表 DDL 以迁移为准"的纪律同样适用于 TestSchema**——照实体写 DDL 等于自欺。
  - **Go 常量文案要取到源头文件**（anydoc 不可用原因分三段拼接在
    `anydoc/backend_stub.go`，Java 只抄了第一段）；运行环境相关的 long 常量
    （ affected 租户数 / key 数字 id / tenant_id）在 A/B 里按部署掩码。
  - evaluation 执行步是部署能力（Go dev 真跑 LLM 流水线带真实指标），执行态三文件
    （ev-post/ev-get/ev-get-viewer）A/B 归部署态跳过，确定性校验分支照常比对。
  - RequireSystemAdmin 的拒绝文案是 "Forbidden: system administrator required"
    （RbacInterceptor 既有实现写错，golden 纠正——audit-log 路由的既有文案随之修正）。
- **波 2 扫尾批 1（auth 注册族）补充**：
  - **MyBatis 走 getter 取属性**——`User.getTenantId()` 的「null→0 归一化」会把
    tenantless 注册写成 `tenant_id=0`，真 PG 违反 `fk_users_tenant`（H2 无 FK 永远不暴露）。
    凡「Go Omit → SQL NULL」语义的**写入**路径都不能依赖实体 getter：
    `UserMapper.insertTenantless` 显式省略 tenant_id 列（register-by-invite /
    OIDC provisioning 共用；update 侧参照波 2 第六批的 `tenant_id=NULL` wrapper 写法）。
    **这是 §5.6 之后的又一代复发坑：getter 归一化只服务于读/序列化，写库要绕开它。**
  - **`tenants.context_config` 的零值对象契约**（实测当前 Go 二进制）：Go 注册路径经
    GORM Create 对 nil `*ContextConfig` 调 `Value()` → `json.Marshal(nil)` → 落库的是
    **jsonb `'null'` 字面量**（非 SQL NULL）；读回 `Scan([]byte("null"))` 落在已分配的
    零值 struct 上 → 响应**恒输出零值对象**
    `{"max_tokens":0,"compression_strategy":"","recent_message_count":0,"summarize_threshold":0}`
    （struct 声明序）。Java 侧：createTenant 在 null 时写 `MAPPER.nullNode()`（与 Go
    落库字节一致），TenantService 读路径 `normalizeContextConfig` 把 null/NullNode/任意
    存储序对象统一归一成 4 键 struct 序（jsonb 存储序 ≠ Go 输出序，实测 PG 10002 行）。
    **login-success.json（9/17 录）因此键缺失已过时，按当前二进制重录**——golden
    的时效以「构建中的 Go 二进制」为准，旧 golden 与新二进制冲突时重录并记台账。
  - **匿名 struct 的 binding 错误无 Key 前缀**：change-password 的请求体是 handler 内联
    匿名 struct，validator 错误为 `Key: 'OldPassword' ...`（无 `RegisterRequest.` 式前缀）；
    invitations/lookup 的 message 是 "token is required"（非 "Invalid registration
    parameters"）。逐条以 golden 为准，勿凭一致性想象。
  - **updateMyPreferences 的 max=4000 校验在 binding 层**（go-playground 按 rune 计），
    4001 字符的响应 details 是 `Key: 'updateMyPreferencesRequest.BrowserSearchInstructions'
    Error:...'max' tag`——service 层的 4000 复查只是兜底，响应形态由 binding 决定。
  - **register-by-invite 的 updated_at 二次刷新**：user 建号（UTC now）→ 回填 tenant_id
    再 Save（GORM 自动刷 updated_at）→ 响应里 created_at ≠ updated_at 且时区形态可不同
    （A/B 掩码覆盖；断言勿假设两值相等）。
  - 注册成功响应的 `user` 是**完整 User 实体**（含 `"deleted_at":null`），而
    /auth/validate 与 /auth/me 的 user 是 **UserInfo 投影（无 deleted_at）**——同一用户
    两种形状，UserInfo.from 静态工厂收口。

- **波 2 扫尾批 2（auth OIDC）补充**：
  - **gin Redirect 的 302 是有 body 的**：`<a href="<Location>">Found</a>.\n\n`，
    Content-Type `text/html; charset=utf-8`，且 body 里 href 的 Location 经
    **Go html.EscapeString** 转义（`&`→`&amp;` 等 5 字符）而 **Location 头保持原样**
    （实测含 `&` 的 oidc_error 场景两形态并存）。Spring 的 302 ResponseEntity 默认无
    body——`redirectFound` 手写 status/Location/Content-Type/body 四件套；golden 对
    302 端点用**合成信封 JSON**（键字母序 body/location/set_cookie/status）落盘，
    契约测试从 MockMvc 结果组同一信封比对（约定写在 record-oidc-golden.sh 头注释）。
  - **MockHttpServletResponse 会解析并重序列化 Set-Cookie**：`Max-Age=0` 被补
    `Expires=Thu, 01 Jan 1970 00:00:00 GMT`（MockCookie 行为）；真容器（Tomcat）
    原样透传（A/B 逐字节证实 Go 的清算头是 `weknora_oidc_nonce=; Path=/; Max-Age=0;
    HttpOnly`）。MockMvc 契约测试对这条头做窄化还原，真字节由 A/B 钉住。
  - **OIDC state 是 HMAC 签名的自包含令牌**（oidc_state.go）：
    `b64url_nopad(json).b64url_nopad(hmac-sha256)`，json 字段序 nonce,redirect_uri,iat，
    密钥=env JWT_SECRET（空则随机 32B，sync.Once）。**跨语言互验已实测**：python
    锻造的 state 被 Go 接受（录制脚本）、Java OidcStateCodec 自签自验（契约测试）。
    verify 的 6 条失败（段数/b64/HMAC/redirect_uri/iat/新鲜度 ±10min/-1min）在 handler
    层全部坍缩成 `invalid_state` 302——**不区分原因、无 Set-Cookie**；只有 verify+nonce
    cookie 双过才发清算头（`Max-Age=0`），再分派 missing_code / login_failed。
  - **urlQueryEscape 是定制 replacer 不是标准 percent-encoding**（auth.go L494-505）：
    只转义 `% # & + = ?` 与空格共 7 个，其余原样（`/`、`:`、多字节 UTF-8 都不动）。
    而授权 URL 构建用的是 `url.Values.Encode()`（**Go QueryEscape 语义**：alnum 与
    `-_.~` 原样、空格 `+`、其余 %XX 大写；**键按字母序** client_id,redirect_uri,
    response_type,scope,state）——两个 escaper 职责不同，勿混用（Java URLEncoder 会把
    `~` 编成 %7E，不可用，OidcService.goQueryEscape 手写）。
  - **OIDC 配置链只有 env+缺省两层**（Java 侧）：Go 另有 config.yaml `oidc_auth` 段，
    Java 仓无该加载器；env 名与 Go 完全同名（OIDC_AUTH_* + OIDC_USER_INFO_MAPPING_*），
    缺省 ProviderDisplayName="OIDC"、Scopes=[openid,profile,email]、mapping name/email。
    dev 两侧 config.yaml 均无此段，行为等价；若将来接 config.yaml 需补 OidcConfig。
  - **nonce cookie 名 `weknora_oidc_nonce`**：下发（url/start 成功分支，dev 不可达）
    Max-Age=600、HttpOnly、SameSite=Lax、secure=TLS 或 X-Forwarded-Proto=https；
    Set-Cookie 字节序对照 Go Cookie.String()（Path; Max-Age; Secure; HttpOnly; SameSite）。
    /oidc/start 的回调地址由请求自身 Host 头推导（oidcCallbackURL），外部平台深链用。

- **波 2 扫尾批 3（跨空间租户目录 + KV 配置分发器）补充**：
  - **PathTenantMatch 必须按 BEST_MATCHING_PATTERN 门控**：旧实现按「路径以
    /api/v1/tenants/ 开头」裸匹配，会把 /tenants/all、/tenants/search、/tenants/kv/*
    全当跨租户越权 403 掉（all/search/kv 的 {id} 段根本不是租户 id）。改为仅当
    匹配模板以 `/api/v1/tenants/{` 开头才查目标租户——literal 段路由（all/search/kv）
    由各自守卫管。这是本批唯一的存量行为修正（旧路由无这些模板，回归由全量套件钉住）。
  - **跨空间守卫在 RbacInterceptor 不走 EnableRBAC 判定**：flag off → 403
    「Cross-workspace access is disabled」；非超管 → 403「Insufficient permissions
    for cross-workspace operation」（均 code 1002，c.Error 信封，不审计）。
    POST /tenants 不登记 RBAC 规则（任何登录用户可自助，部署级开关在 handler 内
    三层解析：DB > env > config 底座）。
  - **创建族三种错误形态并存**：binding 失败 400 code 1010 details 是
    go-playground 原文（`Key: 'createTenantRequest.Name' Error:...'required' tag`，
    rune 计长，min=1/max=128、description max=512；空 body → details "EOF"）；
    空名/空格名穿过 binding 在 service 抛 → 500 code 1007 details
    "workspace name cannot be empty"；配额预检 cap>0 且 owned≥cap → 429 code 1006；
    self-service 关停 → 403 code 2005。超管全字段路径 ShouldBindJSON(&types.Tenant)
    绑定整个实体（status 请求值被 service 恒写 "active"，id 恒由 DB 生成）。
  - **TOCTOU 复检与回滚顺序**：ensureOwner（DuplicateKeyException 重读胜出行）→
    提交后再数一遍 owner 数，超帽回滚成员+租户；tenantless 回填失败同样回滚。
    auto_create_api_key 失败只 warn 不拖垮创建（对照 Go `_ =` 尽力语义）。
  - **autokey 响应是 map 深排序**：tenantWithAPIKey 把实体序列化成 map 再加
    api_key——Go map[string]any 序列化**各层键都字母序**（api_key 排最前），
    与实体 @JsonPropertyOrder 的声明序完全不同。Java 用 springMapper.valueToTree
    （保 +08:00 时间串）再递归 TreeMap 深排序复刻。
  - **KV GET 默认形态六 key 各异**（golden 钉死）：web-search 是 `data:null`
    （指针列 SQL NULL → nil）；parser/storage/chat/retrieval 是**零值对象**
    （GORM Scan 对 SQL NULL 留给已分配 struct；jsonb 'null' 字面量也 Scan 成零值
    对象——parseConfig 对 NullNode 返回 newInstance 复刻）；memory 多一层
    Normalize 默认值（write_mode=explicit_only/max_items=200/delay=90/interval=300/
    interest=3/vector_recall+retrieval_conditioning=null）。
  - **PreserveIfRedacted 语义 = 空串或 "***" 都保留旧值**（ws 的 api_key/proxy_url、
    parser 的 mineru_api_key 等）；**S3 例外只认 "***"**（空串是真清字段）。
    响应侧 api_key 恒不输出（write-only），proxy_url/secret_access_key 等掩成 "***"。
  - **KV PUT 校验顺序坑**：storage 的 provider 归一+白名单、retrieval 的五段范围、
    memory 的七段校验都在**租户上下文检查之前**（对照 Go handler 行序）——无租户
    时这些 400 先于 "Workspace is empty"。PUT 成功信封键字母序
    `{"data":...,"message":...,"success":true}`；message 文案 ws/retrieval/memory/chat
    英文、parser/storage 中文（"解析引擎配置已更新"/"存储引擎配置已更新"）。
  - **chat-history enable 自动建隐藏 KB**：enabled+有模型+无存量 KB → 建
    __chat_history__（is_temporary）；模型未变再 PUT 沿用存量 knowledge_base_id。
    embedding_model_id 不做存在性校验（Go 同）。KnowledgeBaseService 对无后端租户
    容忍（backend null 直接返回，不落 backend 关联）。
  - **parser 无成功路径 golden**：三条录制全是 SSRF 1010（example.com 在本机
    fake-ip DNS 下解析到 198.18.0.0/15 受限段；127.0.0.1 字面量字节稳定）。
    A/B 与契约测试对 "resolves to restricted IP <ip>" 的 IP 段掩码。若将来
    dev 环境 DNS 变化，可补成功路径 golden。
  - **prompt-templates 推迟**：GET 是 Go 独有（vendor config/prompt_templates/*.yaml
    + Language 中间件，47KB payload），Java 落 default → 400 unsupported key；
    PUT 两侧本来都 400（Go 分发器无此 key）。A/B 列 EXPECTED DIFF（ab-ct.sh 的
    EXPECTED_DIFFS 清单）。
  - **search 的宽松解析**：tenant_id 非数字→0 忽略；page<1→1；page_size<1→20、
    >100→100；keyword+tenant_id 是 OR 关系；恒 created_at DESC。list/search 响应
    恒按 viewer 形态裁剪（调用方自家角色不解锁别家秘密）：省略四个秘密字段，
    且 context_config 因 GORM jsonb 'null' Scan 语义输出**零值对象而非 null**。
  - **设置族回归**：tenant.{max_owned_per_user,self_service_creation_enabled,
    auto_create_api_key} 三键注册进 SystemSettingRegistry（int/bool/bool），
    布尔 PUT 必须发 JSON bool（{"value":true}，发字符串 "true" 400
    "expected bool, got string"——录制脚本第一轮的坑）。
  - **双构造器 record 必须 @ConstructorBinding**：TenantProperties 加第 4 组件后
    保留了三参兼容构造，@ConfigurationPropertiesScan 找不到绑定构造器就退化成
    无参实例化 → 启动 NoSuchMethodException。钉在 canonical 构造器上解决。
- **波 2 终扫批（favorites + chunker 预览）补充——波 2 到此全部收官**：
  - **GORM Find 空结果 → `"data":[]` 非 null**：Go `var list []*T; Find(&list)`
    零命中时 list 是**空非 nil 切片**，序列化成 `[]`。别按"Go nil slice→null"
    的通用规则去猜——golden fav-list-empty-kb.json 钉死。同批另一处 nil 语义
    相反：`Diagnostics.Rejected` 未发生拒绝时保持 nil → `"rejected":null`
    （append 过才变数组）；`Chunks` 用 make 初始化 → 恒 `[]`。**同一个 handler
    里三种形态并存，逐字段对 Go 源码**。
  - **空 strategy = legacy，不是 auto**：`resolveChainWithProfile` 的 switch 里
    `case StrategyLegacy, "":` 同档；只有 `auto` 和**未知值**（default 分支）才走
    画像选链。此时 diag.Profile 为 null，由 **handler** 调 ProfileDocument 物化
    （避免二次切分）——preview 响应里的 profile 恒非 null 但来源分两种。
  - **preview 的错误体是裸 gin.H**：`{"error":"<字符串>","success":false}`，
    不走 AppError 信封（fav 的 400 才是信封+details）。413 三键字母序
    error<limit<success。binding 文案 `"invalid request body: "+err.Error()`
    拼在 error 字符串里，复用 GoJsonBindError。深层结构类型错误
    （chunk_size:"five"）Go/Jackson 措辞差异大，**刻意不录**（已知差异）。
  - **profile 的两个编码陷阱**（§9.2 在本批的实例）：double 三字段
    （avg_line_len/std_line_len/code_ratio）挂 GoDoubleSerializer——golden 里
    `"avg_line_len":91`（Go 整值 float64 无 .0）；`md_heading_counts` 是
    `map[int]int`：键**数字升序**输出（Jackson 用按键排序的 LinkedHashMap）、
    空表恒 `{}`（profiler 恒 make）。
  - **favorites 幽灵删除也是 200** `{"success":true}`（repo 返回 0 行，Handler
    不分支）；类型白名单/空 id 的 400 文案逐字对照 Go sentinel error；FirstOrCreate
    = 先 SELECT 四键再 INSERT（复合主键 → MyBatis-Plus 无 @TableId，**纯 SQL mapper**）。
  - **收藏表无外键**：resource_id 任意字符串即合法（不校验资源存在），录制/测试
    用固定假 id 不依赖真实 KB/agent；RBAC Viewer 三条 + chunker/preview 一条；
    API key 侧 favorites **不登记**（默认拒绝），preview 登记
    `retrieve(ingest(fullAccess()))`（单条路由双能力组合的又一例）。
  - **MockMvc 编码陷阱复发**（陷阱清单第 12 条的兄弟）：`getContentAsString()`
    在响应缺 charset 时按 ISO-8859-1 解码——全角破折号（"text is empty — paste…"）
    和中文 content 全部变 mojibake。**契约测试的 raw() 一律
    `new String(getContentAsByteArray(), UTF_8)`**。
  - **测试堆 2g→3g**：3298 条 + 契约文件过千后，2g 再次随机 OOM（仍是
    「Gradle Test Executor N failed to execute tests」，OOM 点在 Spring 资源扫描
    的 substring 里，极易误判业务 bug——见 server/build.gradle.kts 注释史：
    512MB→1g→2g→3g，随测试量继续上调）。
  - 本批是**零缺陷批**：A/B 首轮 ALL MATCH，没有抓回任何 H2 绿/PG 红问题——
    小模块+纯 SQL+既有基础设施（GoJsonBindError/GoTimeSerializer/GoDoubleSerializer）
    全复用时的预期形态。
- **波 3 sandbox 子批 1（配置 CRUD 面）补充**：
  - **依赖真相（本批立项依据）**：sandbox 包 14.7k 行里，配置 CRUD 的 HTTP 面完全不依赖
    provider 可用性——Create 纯校验+落库；Update 的旧凭据盘点失败 → WARN 后**继续保存**
    （Go 注释原文：堵保存会把管理员困在正要修的 key 上）；Delete 盘点失败 →
    `!force` 409 `sandbox_inventory_unverifiable` / `force` 继续删。错误文案全是
    固定串，dev 无 provider 两侧同形。**provider 执行面（templates/skills/exec）才需要
    真客户端**——接缝 `SandboxClientFactory`/`ConfigSandboxClient#list` 本批以
    Unwired 占位，子批 2 换真实现。
  - **URL 守卫先于必填校验**（Sanitize 链实测顺序）：endpoint 的
    ValidateOutboundURLWithPolicy 在 ResolveEffectiveConfig（必填）**之前**——
    golden sbx-cube-incomplete 落在 "address 127.0.0.1 is private" 而非 missing-fields。
    录 golden 前先实测分支顺序，别按源码阅读顺序想当然。
  - **三种 unsupported-type 文案并存**：named 配置保存路径
    "named sandbox configs only support cube, e2b and docker backends"；
    templates/query 路径 "sandbox template catalog only supports …"；ParseSandboxType
    底层还有 `sandbox: unsupported sandbox type "xxx"`（带引号值）。同语义不同层不同
    文案，golden 各钉各的。
  - **特殊拒绝体是"信封形但字符串 code"**：409/423 的
    `{"error":{"code":"sandbox_inventory_unverifiable",…}}` code 是**字符串**且
    **无 details 键**——与 AppError 信封的数字 code+恒输出 details 两处都不同，
    controller 里用裸 LinkedHashMap 直写，不走 GlobalExceptionHandler。
  - **Inventory 的 provider 失败形态是 200 不是 500**：
    `{"data":{"sandbox_count":0,"unverifiable":true},"success":true}`（Go 的
    SandboxInventory 把不可核实编码进响应体而非错误）。
  - **config 列的字段级 AES**（与模型凭证 whole-payload 加密不同）：Go 在 Value()
    钩子里对 Cube/E2B 的 APIKey、EnvVars 值、Network secret **逐字段**加密后混入
    jsonb；Scan 侧 DecryptStoredSecretLenient 失败→置空（不抛）。Java 在
    TypeHandler 里做同样的事；enc:v1: 前缀与阶段 2 互操作同族。
  - **api_key/env_vars 的响应掩码与存储加密无关**：SandboxConfigForResponse 恒把
    非空密文掩成 "***"（未配置留空串），MergeSandboxConfigForUpdate 用
    PreserveIfRedacted（与 ct 批 tenantconfig 同族）把 "***" 回填成存量值。
    契约测试/契约断言只依赖掩码形态，H2 无 AES key 也能全链路（服务层的
    「拒绝明文落密钥」检查用固定 key 替身放行，真加密路径由 A/B 真 server 覆盖）。
  - **docker 后端开关是三层解析**（DB system_settings > env WEKNORA_SANDBOX_DOCKER_ENABLED
    > false），Java 侧 SandboxBackendPolicy.setDockerBackendEnabled 是 system_settings
    的推送口，本批只有 env+false 两层（dev 等价）；**将来接 SystemSettingRegistry 时
    记得把 sandbox.docker_enabled 推进来**。
  - **时区形态在同一 Go 进程内都不稳定**：sbx-create 响应的 created_at 是
    `…Z`（GORM 内存对象）、sbx-list 读回是 `…+08:00`（PG 驱动回读）——**同一行
    同一轮录制两种形态**。时间戳掩码从「跨轮稳定」升级为「必需」，任何新 golden
    都别赌 Go 侧时区形态。
  - **墙钟脆弱测试第三变种**（§5 陷阱 9 续）：RedisStreamManagerTest 的 TTL 续期
    用例（5s TTL + sleep(2s) + 秒级精度前后对比）在全量慢跑下两种假红——键过期
    （读数 -2）与 renew 前后同秒（after==before）。修法：毫秒精度（PTTL）读数 +
    管理器 TTL 拉到 2min，把续期可见性与机器速度解耦。
  - **全量时长 6.7min→14.5min**：sandbox 批 +2~3 个 @SpringBootTest 上下文变体后
    （每变体整份上下文驻留堆），3g 堆开始 OOM（GC 死亡螺旋特征：全量耗时翻倍），
    提到 4g 恢复稳定。治本方向是收敛上下文变体数量（TestConfiguration/不同
    @MockBean 组合各算一个变体），堆只买时间。
- **波 3 sandbox 子批 2（sandbox-check + templates provider 面）补充**：
  - **sandboxCheckReason 是 dev A/B 的字节稳定锚**：provider 传输错误按
    RemoteError.Kind 归一成固定中文文案（拒连→"服务不可用：端点拒绝连接"、
    超时→"请求超时：…"、认证→"认证失败：…"、404→"资源不存在：请检查模板 ID"、
    INVALID_REQUEST→"参数无效："+msg、docker 的 Unavailable 走
    dockerUnavailableCheckReason 从消息里抠 host）。原始拨号措辞永不进响应体。
  - **分类器契约**（remote_errors.go）：httpErrorKind（400/422→invalid_request、
    401/403→authentication、404→not_found（**Create 特判 invalid_request**）、
    408/504→timeout、409→conflict、410→terminal、429/507→capacity、5xx→
    unavailable、其它→internal）+ 传输层（net.Error 非超时→unavailable、
    超时→timeout；Java 的 ConnectException/UnknownHostException/SSLException 全落
    unavailable 分支）。
  - **sandbox-check 的三个形态并存**：400 老式 `{"code":1,"msg":…}`（系统组遗留，
    非 AppError 信封）、200 结构化探测结果、checks[].ok 三态（null=跳过）。
    **capabilities 是 gin.H：键字母序**（pause_resume<reconnect<volumes）——
    struct 序会假红。三方具名后端的探测面能力同表（volumes=false，
    pause_resume/reconnect=true）。
  - **latency_ms 的粒度差**：Go 拒连 0ms → omitempty 整键省略；Java HttpClient
    同场景 1ms+ → 键出现。掩码把 `,"latency_ms":N` 整片段从两侧移除，别只掩数字。
  - **plain-500 分支**：Go 全局 ErrorHandler 对非 AppError 是
    `{"error":{"code":1007,"message":"Internal server error"},"success":false}`
    （**无 details 键**），与 AppError 信封的 "details":null 刻意不同。处理方式
    沿用 FAQ 批先例：**controller-local @ExceptionHandler**（列 RemoteError/
    IllegalStateException/DataAccessException，刻意不列 BizException——它必须
    继续走全局信封），common 全局文件零改动。
  - **agent 中断接力的边界**（本批实测）：agent 撞用量上限时可能**还没有写任何
    文件**（全部预算耗在按纪律读文档+Go 源码上）——接力前先 `git status` 盘点，
    别假设有半成品。主会话接力时优先做"自包含+验收闭环"的切片，把大面留给
    配额恢复后的下一个 agent。
  - **fmt 的 %q**：Go `"sandbox: provider %q cannot be probed"` 的 %q 输出双引号
    包裹——Java 手拼 `"\"" + type + "\""` 时别漏。
- **波 3 sandbox 子批 3（skills 子资源）补充**：
  - **任务书会写错，golden+Go 源才是准绳**：任务书断言 is_set 读
    tenant_user_env_vars（按主体存储），实际 Go 读 tenant_skills.envs 列内 value
    非空——agent 按 Go 源纠正并回报。派发任务书的"事实段"只是路标，落地以源码+实录为准。
  - **类级 @JsonInclude(NON_DEFAULT) 会吞布尔 false**：skillResponse 的
    enabled:false、skillEnvResponse 的 is_set:false 是合法输出（无 omitempty），
    类级注解会把它们整个吞掉——**布尔/数字字段逐字段挂 NON_DEFAULT，宁可啰嗦**。
  - **202 异步受理是 provider 无关契约**：upload 合法 zip → 202
    {"data":{"skill_id":…}}，install 管线在后台走（首个 provider 调用失败置
    failed）。golden/A-B 只对受理响应字节；**异步终态（installing→failed）不进
    golden**——两侧状态机语义一致即可，A/B 掩码面不含行状态。
  - **SSE 单帧 + JSON 前置 404**：install-events 的 resolveSkill 404 在 SSE 头
    写出前以 JSON 返回（Go 注释原文：refusal 在任何 SSE 头之前仍可渲染为 JSON）；
    ready 技能单帧 {"percent":100,"stage":"done","status":"ready","done":true}。
  - **envs jsonb 列逐字段 AES**（value 字段 json:"-" 恒不出响应）：
    nil→NULL、空→[]、解不开置空——TypeHandler 三态与配置列同族。
  - **Redis 键名契约**：进度键 weknora-skill-install:<tenant>:<config>:<skill>
    （TTL 30min）与 Go 逐字一致——跨语言互操作的键空间。
- **波 3 sandbox 子批 4（/skills 家族 + /me/env-vars）补充**：
  - **catalog 列表是三段合并投影**：catalog 行 → 孤儿 catalog_id 技能行 → 无
    catalog_id 同名行并入 installations[]——Go catalogProjectionFromSkill 把
    tenant_skills 行也当目录条目输出，别只查 catalog 表。
  - **bundle_sha256 的 A/B 掩码**：python zipfile.writestr 用当前 localtime 做
    zip 内嵌 mtime → 每次构建 sha 不同；契约测试与 ab 脚本都要掩
    `"bundle_sha256":"<sha64>"`。要根治可在录制脚本用固定 date_time 的 ZipInfo。
  - **DELETE 也吃 JSON body**：/me/env-vars 的 DELETE 与 PUT 同构（body 绑定），
    query 传参得 400 "EOF"——录制脚本里用 `printf … > file` + `-d @file` 避免
    shell 转义地狱（本轮在 delete-skill 上踩了两轮）。
  - **mev 投影 source 三态** unset/workspace/user：dev 只出现 unset/user
    （workspace 来源随 system_settings 管理面）；updated_at 仅 user 态输出。
  - **install 受理映射**：202 {"data":{"installs":{configId:installId}}}，
    per-config 吞错、全败重抛首个；config 不存在 → **404**（1003）非 403。
  - **删除钉住语义**：catalog delete 在存在引用行时 409 code 1005（install 的
    后台失败不删行——与子批 3 的 failSkill 语义衔接）。
- **波 3 协作面（organizations + shares）补充**：
  - **【跨横切真缺陷，待专项】emoji 转义**：jackson-core 2.17.2 + GoJsonEscapes
    （自定义 CharacterEscapes）对**补充字符**（emoji，UTF-32 > 0xFFFF）按 UTF-16
    代理对分段转义成 \uD83E\uDD16；Go/encoding/json 输出 raw UTF-8。任何含 emoji
    的响应都会字节 DIFF。修法候选：升级 jackson-core（新版 escape 实现合并代理对）
    或 JacksonConfig 层对补充字符直通。修复时必须用 Go 实录（含 emoji 的响应）
    钉字节，并回归全部既有 golden。本批以 ASCII avatar 种子规避。
  - **org 模块的响应投影是"Go struct 序 + gin.H 字母序"混合作业**：外层 gin.H
    字母序、内层 struct 声明序、CustomAgent 的 jsonb 列还要**重排回 struct 序**
    （jsonb 读出是 PG 规范化键序的 LinkedHashMap，直接序列化会字节 DIFF）。
  - **Go 文案错配也是契约**：RequestRoleUpgrade 的 handler 文案与 service 文案
    不匹配 → 实际真录出 500（"tenant is not a member..." 等）——不要"修好"成
    自洽文案，逐字复刻（§8 如果遇到不确定的：实测优先）。
  - **共享 KB 读面走 Preload raw**（无 EnsureDefaults）：indexing NULL → 三 false
    结构、storage_backend_id omitempty 缺键——与 KB 常规读路径刻意不同。
  - **守卫映射**：OwnedKBOrAdmin/OwnedAgentOrAdmin 在 Go 无角色门（下限 VIEWER，
    判定在 handler 内、API-Key 主体短路路由 guard）——Java 侧 rbac 规则登记
    VIEWER 下限 + 控制器内所有权判定，shares 组 API key 仅 fullAccess。
- **波 3 agents 批补充（含 emoji 修复的完整因果链）**：
  - **emoji 缺陷根因（升级不可解）**：协作批上报后，本批定位为 Jackson
    `UTF8JsonGenerator._outputMultiByteChar` 对补充字符（>0xFFFF）硬编码按
    UTF-16 代理对写 `\uD83E\uDD16` 形态——2.17/2.18/2.19/2.20 四版实测一致，
    升级依赖救不了；`CharacterEscapes` 只能"加"转义不能"免"转义，是死代码。
  - **修法：改道 Writer**。新增 `common.web.GoWriterJsonFactory`：HTTP ObjectMapper
    的 jsonGenerator 工厂换成 WriterBasedJsonGenerator（HttpServletResponse 的
    Writer 按 UTF-8 编码，代理对经 Writer 输出 raw UTF-8 字节，与 Go 一致）。
    `& < > &`、控制字符、中文的转义两路径本就相同，GoJsonEscapes 的
    CharacterEscapes 继续挂（Writer 路径下 escape 代码仍生效）。**修复被
    golden 直接钉住**（内建 agent avatar 📚/📊），org/knowledge/session/wiki
    全部逐字节套件回归全绿——任何字节形态改动都会被既有 golden 抓住。
  - **教训**：上报"跨横切缺陷"时若给出根因定位与修法候选，下一个批次就能
    顺手修掉而不是无限期推迟——缺陷闭环时间 = 上报质量 × 有人接。
  - **initialization 的 Go 既有行为照抄未修**：POST initialize 建的 model 行
    **tenant_id=0**（GET config 按租户回读找不到 → llm/embedding 键缺席——
    疑似 Go 的 bug，但 golden 钉的是行为不是意图）；404 是守卫层
    "knowledge base not found"（handler 文案不可达，陷阱 §5.5 复发）。
  - **vendor yaml 的启动装载**：builtin_agents/prompt_templates/type_presets
    三个 yaml 自 Go config vendor 进 resources，YAML 未知键按 Go 强类型
    Unmarshal 语义丢弃（Jackson FAIL_ON_UNKNOWN_PROPERTIES=false 等价）。
- **波 3 browserskill 批补充**：
  - **引擎级路由的中间件链**：local-browser 三条注册在 Auth 之前（router.go 引擎级），
    Java 侧 AuthFilter 的 NO_AUTH_API 放行清单承载同一语义（X-API-Key 同样被忽略）；
    /me/browser 三条是 v1 标准链但**无角色守卫**（RbacInterceptor pattern 不含 /me/**）。
  - **真实服务器状态跨请求持久 → 契约测试必须单方法**：pair/authorize/revoke 是
    有状态收敛链，拆 @Test 会被 @BeforeEach 的 resetData 清状态造成假红——
    首轮实测教训；用注释固化原因再合并流程。
  - **录制部署形态进脚本头注释**：BROWSERSKILL_BINARY=/usr/bin/false +
    CLUSTER_SECRET + EXTENSION_PATH 是 golden 的前提（禁用态在更早分支 503，
    任务书预期的"无票据失败族"需要 Enabled=true 才可达——由 manager 级单测钉）。
  - **download golden 的字节锚在 dev PG 外部文件**（/tmp/weknora-bs-ext/*.zip，
    Last-Modified 头）：删除该文件需重录 download golden。
  - **既有容器噪音备案**（非本批引入）：Content-Type 的 OWS 被 Tomcat 规范化、
    Vary×3、status line 无 reason phrase（ab 脚本归一化，g5 批先例）、OPTIONS
    预检无 Origin 时 Java 200 vs Go 404。
  - **堆曲线**：4g 撑了协作/agents 两批，browserskill 批后 4g 在 MyBatis XML
    解析处 OOM → 5g。治本仍是收敛上下文变体。
- **波 4.1 事件契约包补充**：
  - **EventBus 的 Go quirk 照抄**：Global 单例 Set→Get→被 once 覆盖的次序怪癖
    （第二次 Set 实际不生效）被实录钉住；同步 panic 原样冒出调用方（Go 不 recover）、
    异步每 handler 一虚拟线程且 Exception 静默丢（Go `_ =`）——两模式失败语义刻意不同。
  - **emit 的 ID 是值语义**：emit 在 shallowCopy 上补 UUID，调用方的 Event 不被写回；
    metadata map 跨拷贝共享（WithTiming 的 duration_ms 调用方可见）。
  - **Jackson 两个新钉住的坑**：①`is` 前缀 boolean（isStream/isFallback）必须同时在
    getter 标 @JsonProperty，否则 Jackson 把字段与 getter 拆成两个属性；②primitive
    double 字段绕过模块注册的 Double 序列化器——Go 浮点形态的字段用包装 Double。
  - **跨虚拟线程传值**：TenantContextSnapshot 显式捕获/恢复（§3 约定的正式实现），
    异步 emit 路径有专门测试。
  - **24 emit 点表**已固化在 com.ragagent.event 的 package-info（think 5/observe 4/
    approval gate 4/finalize 3/act 3/engine 2/tools 2/steer 1）——4.6 引擎批的 §6 纪律基线。
