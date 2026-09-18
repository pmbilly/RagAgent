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
| 会话/消息最小读路径（阶段 5.2 步 3 下） | internal/application/service/{session,message}.go 的读方法 + loadSessionForRead | com.ragagent.session.service.{SessionService,MessageService,SessionLookupScope} | ✅ | 14 测试（授权判定）。`Session.requiresAdminConsoleRead` 阶段 5.1 已落地，本步只补 service 层的两条读路径与 Admin 回退 |
| storageurl（阶段 5.2 步 2） | internal/storageurl/{mode,storageurl,stream,resolver,request}.go | com.ragagent.storageurl.{Mode,StorageUrlContext,ResourceModeException,PublicModeForbiddenException,Resolver,Rewriter,StreamRewriter,FileServiceResolver,FileService,StorageBackendResolver} | ✅ | 49 测试。**这是第一处跨 5 个 handler 的共享契约**（message/knowledgebase/session/embed/im 都 import 它）。扣留缓冲 + 模式解析全部按 Go 对等移植；差分语料见 §9。已知差异：provider 级文件服务未翻译 |
| SSE 契约层（阶段 5.2 步 1） | internal/handler/session/helpers.go L182-249（setSSEHeaders / buildStreamResponse / sendCompletionEvent / searchResultFromMap）；internal/types/search.go 的 SearchResult；internal/types/json.go 的 JSON | com.ragagent.session.sse.{SseContract,StreamResponseBuilder} + com.ragagent.retrieval.domain.SearchResult + com.ragagent.common.web.{GoDoubleSerializer,GoMapSerializer} | ✅ | 41 个新测试（28 浮点语料 + 12 SSE 逐字节 + 1 往返）；**期望值全部是 Go 实录**（把 helpers.go 的三个函数原样抄进独立 Go 程序跑出来的 `json.Marshal`）。emit 表见 `StreamResponseBuilder` 类注释。关键坑见 §9 |

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
