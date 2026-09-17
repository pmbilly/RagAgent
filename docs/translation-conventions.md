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

## 8. 翻译日志（每完成一个模块更新）

| 模块 | Go 源 | Java 目标 | 状态 | 备注（踩坑/GORM 清单/SSE emit 表位置） |
|---|---|---|---|---|
| 骨架 | internal/router/、internal/errors/、internal/middleware/{error_handler,auth}.go | com.ragagent.common.{error,context,filter,web} + config.WebConfig | ✅ | 错误格式两种形态已确认（见 §9）；401 三态已锁定；Flyway 对 Go 数据 baseline 验证通过 |
| auth/租户（阶段 1） | internal/middleware/auth.go、auth_context.go、access.go；internal/application/service/user.go（Login/ValidateToken/generateTokensForTenant/resolveLoginTenantID 链）；internal/handler/auth.go(Login)、dto/{auth,tenant}.go；types/{user,tenant,tenant_member,principal}.go | com.ragagent.auth.{domain,mapper,dto,service,filter,controller} + config.{TenantProperties,JacksonConfig} | ✅ | 10 条新 golden 全过（H2 种子+掩码比对）；e2e 连 dev DB 验证通过。关键坑见 §9 |
| 模型配置（阶段 2） | internal/handler/model*.go、weknoracloud.go；internal/application/service/{model,weknoracloud}.go；internal/types/model.go、builtin_models_config.go；internal/models/provider/*；internal/utils/{security.go(SSRF),crypto.go}；internal/middleware/rbac.go（RequireRole 子集）；internal/application/repository/{model,model_usage}.go | com.ragagent.model.{domain,mapper,dto,service,controller} + com.ragagent.common.{crypto,security,web.RbacInterceptor/PgJsonTypeHandler} | ✅ | 9 条新 golden 全过；providers 响应与 Go 实录字节一致；AES-GCM 落库密文/回读解密 e2e 验证。关键坑见 §9 |
| 知识库（阶段 3） | internal/handler/{knowledgebase,knowledge}.go；internal/application/service/{knowledgebase,knowledge,knowledge_create,knowledge_process}.go；internal/application/repository/{knowledgebase,knowledge}.go；internal/types/{knowledgebase,knowledge,knowledge_folder}.go；internal/infrastructure/docparser/(gRPC 客户端)；internal/chunker/*；internal/utils/{storage,security}(SSRF) | com.ragagent.knowledge.{domain,mapper,dto,service,controller,chunker}（KnowledgeProcessWorker=进程内虚拟线程队列，对照 asynq；DocReaderClient gRPC；EmbedderClient；VectorStoreService） | ✅ | 20 条 golden 全过（KB CRUD+文档 CRUD，含 409 duplicate 特殊信封）；e2e 连 dev PG：上传→docreader 解析→chunk 落库→无 embedding 模型按契约 failed。关键坑见 §9 |
| LLM 调用客户端（阶段 4.0） | internal/models/chat/*（26 文件）；internal/models/provider/*（30 文件）；internal/models/limiter/*；internal/models/utils/ollama/ | com.ragagent.llm.{domain,chat,provider,limiter,ollama}（LlmChatClient 接口；RemoteApiChat/AnthropicChat/OllamaChat；ProviderAdapter 13 实现）+ LlmChatClients 工厂 | ✅ | 368 测试全绿（本模块 ~330）。Java 侧把 Go 的「SDK 路径 vs 裸 HTTP 路径」合并为 ObjectNode 单路径。关键简化与已知差异见 §9 |
| MCP 服务管理（阶段 4.1） | internal/mcp/*（自研协议客户端：client/manager/oauth_*+SSRF）；internal/types/mcp*.go；internal/application/{repository,service}/mcp*.go；internal/handler/mcp_*.go + dto/mcp.go；internal/agent/approval/*（提前翻译以解耦） | com.ragagent.mcp.{domain,protocol,oauth,mapper,service,dto,controller} + com.ragagent.agent.approval | ✅ | 22 端点全落地；17 条 golden（CRUD/审批/凭据/SSRF 拒绝/403/404）掩码比对通过。e2e 在真 PG 验证：密钥加密落库（enc:v1:）+ **跨语言双向互操作**（同 key 下 Go 写 Java 读、Java 写 Go 读均成功）。关键坑见 §9 |

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
- **跨阶段通用坑（阶段 4 新增）**：
  - **领域对象的 isXxx() 便捷方法必须 @JsonIgnore**——已在阶段 3 记录，阶段 4 又踩一次
    （`McpAuthConfig.isOAuth()` 导致整个 auth_config 列落库后读不回）。这是**复发率最高的坑**，
    新增任何「对照 Go 方法」的便捷访问器时先想它。
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
