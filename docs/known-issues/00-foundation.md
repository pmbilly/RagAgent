# 已确认细节与坑 · 基础契约（阶段 0–4.0）+ 跨阶段通用坑

> 本文件是 `docs/translation-conventions.md` §9 的一个分片（按批次拆分，**内容未改动**）。
> 代码注释与任务书里的「约定 §9「XXX」」按条目标题在本目录内检索即可。
> 回链：[`translation-conventions.md`](../translation-conventions.md) §9 索引 ｜
> 同目录兄弟文件：00 基础 / 01 阶段 4.1–5.2 / 02 波 0–1 / 03 波 2 / 04 波 3 / 05 波 4 / 06 W5。


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
     ✅ **已解决（2026-09-23 走查收口批，347eb18）**：SandboxFileProgress 全文翻译并接通
     openai_stream.go L561-593 的两处 emit（sandbox 模块波 3 已落地，备案理由过期）
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

- **走查抓回（2026-09-22，SSRF 误拦公网模型域名，已修复）**：添加 dashscope embedding
  模型测试连接报 `hostname dashscope.aliyuncs.com resolves to restricted IP 8.152.159.24:
  restricted range 0.0.0.0/8`——8.x 明明是公网。根因：`IpClass` 的 0.0.0.0/8 上界误写
  `0x0fffffff`（实为 0.0.0.0/4，把 1.x–15.x 整段公网误判；8.8.8.8 都过不了），正确上界
  `0x00ffffff`。既有测试/golden 没覆盖 1–15 开头的 IP 所以全绿漏网。
  - **同场对照 Go Classify 谓词顺序又抓回一个文案 DIFF**：Java 把
    `isAnyLocalAddress() || isLoopbackAddress()` 合并报 LOOPBACK，Go 是
    IsUnspecified 独立分支报 "unspecified address"——0.0.0.0 双端文案不同。
    已拆开对照。链路本地多播 224.0.0.0/24 双端都先于 generic multicast，一致。
  - 修复 = 上界改正 + UNSPECIFIED/LOOPBACK 谓词拆开；新增 IpClassTest 逐段钉
    Go restrictedIPv4Ranges 边界（含 dashscope 实案 8.152.159.24）。
  - 验证：remote/check 打 dashscope 真端点 → SSRF 放行、拿到真 401（假 key），
    认证/网络链路通畅。注意 TUN 代理 fake-IP（198.18.0.0/15）被拦仍是**设计行为**
    （Go 同表），与本缺陷无关。
