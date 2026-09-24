# 已确认细节与坑 · 阶段 4.1 / 5 / 5.2（MCP、流管理器、会话消息、SSE）

> 本文件是原 `docs/translation-conventions.md` §9 的一个分片（按批次拆分，**内容未改动**）。
> 2026-09-25 起 conventions 已拆分：规范正文并入 `docs/HANDOFF.md` §7，日志/细则见 `docs/translation-log.md`。
> 代码注释与任务书里的「约定 §9「XXX」」按条目标题在本目录内检索即可。
> 回链：[`translation-log.md`](../translation-log.md) §9 索引 ｜
> 同目录兄弟文件：00 基础 / 01 阶段 4.1–5.2 / 02 波 0–1 / 03 波 2 / 04 波 3 / 05 波 4 / 06 W5。


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
