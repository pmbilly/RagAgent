# 已确认细节与坑 · W5 收尾批（W5a / W5b / W5c + 后续 W5d 追加于此）

> 本文件是 `docs/translation-conventions.md` §9 的一个分片（按批次拆分，**内容未改动**）。
> 代码注释与任务书里的「约定 §9「XXX」」按条目标题在本目录内检索即可。
> 回链：[`translation-conventions.md`](../translation-conventions.md) §9 索引 ｜
> 同目录兄弟文件：00 基础 / 01 阶段 4.1–5.2 / 02 波 0–1 / 03 波 2 / 04 波 3 / 05 波 4 / 06 W5。


- **W5a 补充（漂移修复 + 13 条小散路由）**：
  - **RBAC 漂移的两类形态**（对账产物，修的都是"规则存在但空转"或"规则缺席"）：
    ① **拦截器 pattern 缺前缀**——`registry.addInterceptor(rbac).addPathPatterns(...)`
    清单缺 `chunks/**`、`messages/**`、`faq/**`、`knowledge-chat/**`、`agent-chat/**`、
    `knowledge-search` 六个前缀，此前的 addRule 全部空转；② **规则缺席**——sessions
    组（Go 在组级挂 g.Viewer()，组内每条都吃 Viewer 下限）、messages 组 4 条、chunks
    写族 7 条（OwnedChunkKBOrAdmin=creator OR Admin，无角色下限 → 拦截器只设 VIEWER、
    ownership 归 ChunkAccessGuard——FAQ/Wiki 写路由的既有落地同款）。漂移修复的验证
    直击场景：viewer PUT /tenants/10002 → OWNER 规则 403（w5a-tenant-put-nonowner），
    A/B 与 Go 逐字节 MATCH。
  - **tag.SeqID 会回填**（纠正 FAQ 批的旧注释）：GORM 对 `autoIncrement` 列在 PG 走
    RETURNING 回填内存对象——CreateTag 响应 `seq_id` 是真值。Java：PG 插入
    （NEXTVAL）后按 id 回读；H2 维持 max+1。
  - **refresh 轮换的吊销检查是"同值行"敏感的**：JWT iat 秒级——同一秒内 refresh 轮换
    出的新 refresh_token 与旧值**逐字节相同**，auth_tokens 出现两行同值记录，
    Go 的 GetTokenByValue（First 无 ORDER BY）命中哪行取决于堆序 → "revoked" 检查
    掷硬币。录制/测试必须 sleep 2 再刷新（保证轮换值不同，旧值必 401）。
  - **具名 struct 的 validator 键带前缀**：`createTagRequest.Name`（tag create）——
    "匿名 struct 无前缀"的规则只适用于 handler 内联匿名 struct（refresh 的
    `RefreshToken`、switch 的 `TenantID`）。别一刀切。
  - **page=0 是合法输入**：Pagination 的 `omitempty,min=1` 对查询绑定把零值视为空
    → 跳过校验 → GetPage 归一成 1（200）——不是 400。page=abc 才是 400
    （details=strconv 原文）。
  - **PathTenantMatch 的两只新死代码**：GET /tenants/:id 的 handler "Invalid workspace
    ID"（400）与 DELETE 缺租户的路径——URL :id ≠ 活动租户时中间件先行 403，
    两者都不可达。self-serve 建租户后活动租户仍是 home → 对新租户的 DELETE 恒 403，
    这是**录制钉住的既有行为**（要真删得切 X-Tenant-ID）。
  - **im 回调（engine 级路由）**：AuthFilter 对 `/api/v1/im/callback/` 前缀整体让路
    （对照 Go 注册在 Auth 之前的语义）。Go dev 上 enabled 渠道的回调恒 503
    "channel not available"——mattermost webhook 适配器工厂在空 credentials 下建适配
    失败——Java 无适配器（随波 5）落同形 503 → **MATCH 非 XDEP**；disabled → 503
    "channel is disabled"、缺行 → 404 "channel not found"，三条确定性分支全 MATCH。
  - **DeleteTenant 的级联软删**：Go repo 在事务里先软删 tenant_members 再软删 tenant
    （GORM DeletedAt）——Java 原实现 deleteById 是硬删（且不删成员），已对齐为
    显式 `deleted_at` 写入；createTenant 的回滚路径共用此方法（Go 三处 rollback 也走
    service.DeleteTenant）。
  - **switch-tenant 的绑定顺序**：json.Unmarshal（语法/类型错，uint64 的文案带
    匿名 struct 的 ".tenant_id" 路径）先于 validator（required）；成功路径先落
    last_active 偏好再签发令牌（写失败中止切换），旧 refresh 尽力吊销。
  - **测试基建（复发提醒）**：`mcp.*` 与 `storage.*` 同批跑会互踩——MCP 的 SSRF 用例
    改动进程级 SsrfGuard 白名单且未还原，StorageBackendContractTest 的 SSRF 拒绝分支
    随之假红（clean HEAD 复现，与本批无关，待专项收敛）。
- **收尾批 W5a 补充**：
  - **RBAC 漂移（对账产物，2026-09-21）**：WebConfig 的 addRule 与拦截器
    addPathPatterns 是两段配置——规则登记了 pattern 不覆盖=空转。六前缀
    （chunks/messages/faq/knowledge-chat/agent-chat/knowledge-search）修复后以
    "viewer PUT /tenants → 403 与 Go 逐字节 MATCH" 钉住。**教训：新增路由时
    addRule 与 addPathPatterns 必须同批核对**。
  - **GORM RETURNING 回填**：tag.SeqID 创建后是 DB 回填的真值，别信"恒 0"旧注释。
  - **具名 struct validator 错误键**：gin binding 对具名请求 struct 的字段路径带
    结构体名前缀（createTagRequest.xxx），匿名 struct 无前缀——绑定文案契约要逐
    个录。
  - **deleteTenant 纠偏**：旧 Java 实现是硬删，Go 是事务内级联软删（成员+租户）。
  - **mcp×storage 测试互踩（既有，留专项）**：MCP SSRF 用例改进程级 SsrfGuard
    白名单后未还原，泄漏影响同 JVM 后续测试；clean HEAD 复现确认非 W5a 引入。

- **W5b 补充（initialization 系统级 14 条）**：
  - **gin validator 键的另一面**：W5a 钉了"具名 struct 带 `createTagRequest.` 前缀、
    匿名 struct 无前缀"；W5b 再钉一刀——键里的字段名是 **Go 字段名**而非 json tag
    （字段 `Models []string json:"models"` → `Key: 'Models' Error:...`，不是 'models'）。
    validator 从不看 json tag。
  - **同一 handler 两种时区路径**：OllamaModelInfo.modified_at 来自 JSON 反序列化
    （UTC 字面量）→ time.Time marshal **保留原 location** 输出 `Z`；下载任务的
    startTime 来自 time.Now() → 输出服务器本地偏移 `+08:00`。Java 侧对应
    goTimeAsIs（不改时区）与 goTime（atZoneSameInstant 本地化）两个 helper，别混。
  - **OllamaService 单例 bean（W5b 首次落地）**：Go 是 container.Provide 单例，
    Java 此前只有 ObjectProvider 空注入。isAvailable 是**跨请求共享状态**——
    CheckOllamaModels 的"已可用则跳过 StartService"分支依赖单例；每请求新建会把
    可用实例的 per-model 失败（200 map 全 false）变成 500 "Ollama服务不可用"。
    bean 落地后 QaWiring/memory 的 getIfAvailable() 与 Go 同形。
  - **downloadTasks 是进程内存**（Go 包级 map）：不落 DB、重启即空——Java 用
    ConcurrentHashMap 单例承载，**不引入新表**；A/B 的 tasks 比对要重启双端
    lockstep（各自历史决定数量），且 Go map 迭代序随机 → 掩码后按元素排序再比。
  - **ASR seam 的取舍**：asr 包只有一个 provider（OpenAI 兼容 transcription），
    接缝缺省实现做真实出站（multipart POST {base}/audio/transcriptions + 300s
    超时），错误文案按 go-openai error.go **逐字节仿真**（APIError 与 RequestError
    两族 + Go encoding/json 顶层错误文案仿真）。教训：**JDK HttpClient 会剥掉
    header 值尾随空格**——"Bearer " 到达 stub 变 "Bearer"，stub 探针判空 key 时
    两侧要按 strip 后比对。
  - **multimodal/test 名不副实**：Go 的 TestMultimodalFunction 校验了 VLM/存储表单，
    实际执行只调 DocReader（VLM 参数不参与调用）——Java 复用 knowledge 的
    DocReaderClient 即可，无需 VLM provider；A/B 双端同打 dev docreader，成功
    响应只有 caption:""/ocr:""/processing_time（掩 ms）+ success。
  - **A/B 抓回的真缺陷**：multimodal data 节点按 gin.H 字母序输出（caption < ocr <
    processing_time < success），首版按处理顺序插入（processing_time 在前）——
    golden 没盖住（mm 成功路径需要 docreader，契约测试 JVM 没有），A/B 抓回。
    **教训：响应含 docreader/外部依赖的成功路径至少要有一条 A/B。**
  - **契约测试的 in-JVM stub 选位**：OllamaService bean 的缺省基址是
    localhost:11434 → 测试 stub 直接占 11434（环境无 ollama 时恒空闲），避免
    "测试改 env"的不可能问题；OpenAI 兼容 upstream 用随机端口 + SSRF 白名单
    reload/restore（§5 #8）；SSRF 拒绝场景用 10.0.0.1:9（直连 IP 不在白名单，
    与白名单状态无关的确定性 400，不碰 DNS）。
  - **record 脚本的进程态前提**：downloadTasks 在 Go 侧是进程内存 → 录制前必须
    重启 Go server（否则上一轮的任务残留进 tasks 空表 golden——实测踩过一次）。
  - **已备案差异**：ollama/上游传输层错误内文（Go `dial tcp ...` vs JDK 文案）
    掩码比对；GoJsonBindError 的深结构 JSON 错误回落 Jackson 文案；pull 的 12h
    ctx 超时未翻（Java 虚拟线程无等价 cancel，只有取消语义缺位无行为差异）；
    50MB 上传上限族（MaxBytesReader 前置）未录——Spring multipart 60MB 全局上限
    承接，超限文案不同（golden 不可达）。

- **W5c 补充（文件代理面 8 条路由）**：
  - **注册位置决定鉴权分层（files.go 的核心契约）**：/files 在 Auth **之后**（需登录）、
    /r/:token 在 Auth **之前**（零鉴权，令牌自证）、presigned 在 Auth 之后但 Auth 的
    noAuthAPI 白名单放行 GET/HEAD（IM 平台 HEAD 预检）、presigned-preview 在引擎根
    （APIKeyGate 不跑 → 显式 DenyAPIKeyPrincipal + RequireRole(Admin)，而 RequireRole
    对 Key 短路所以 Deny 必须在先）。Java 侧分层：AuthFilter 通道 1.7（/r/ 前缀让路）、
    APIKeyGateInterceptor `.excludePathPatterns("/api/v1/files/**")`、
    AllowFileServeAPIKeyInterceptor/DenyAPIKeyPrincipalInterceptor（既有预留类接线）
    + RbacInterceptor ADMIN 规则 + 控制器内 Deny 兜底删除。
  - **HEAD 的 404 是 gin NoRoute 形态**：Go 只给 /r/* 与 presigned 注册 HEAD；
    其余 GET 路由的 HEAD 落 gin NoRoute（404 + `text/plain` 无 charset +
    "404 page not found" 无换行 + Content-Length:18）。Spring 的 `head()` 请求会把
    **GET 处理器的 method 参数改写成 GET**（拦截器/handler 里 `getMethod()` 不可靠），
    必须显式 `@RequestMapping(method=HEAD)` 映射才能钉住。curl 侧 `-X HEAD` 会等
    永不到来的 body → 录制脚本 HEAD 一律 `-I`。
  - **presigned 的 Content-Disposition 是裸值**：Go 调 streamStoredFile **不带
    filename**（varargs 空）→ filetransport.Serve 内部对空 filename 直接写
    "inline"/"attachment"（无 filename= 段），Content-Type 却从路径派生。
  - **presigned-preview 的 url 是 BackendScoped 包装**：dev 租户有 System LOCAL
    legacy alias 行 → ResolveBackend 命中 → BackendScopedFileService.GetFileURL 把
    未改写的路径重新包成 `storage://<backendID>/<原路径>` → `rewritten` 恒 true、
    provider 被覆写成 backend 的 local（"minio://bucket/x.png" 也一样）。
    "URL unchanged" 的 hint 分支在 dev 不可达（golden 只钉 200 形态）。
  - **If-None-Match 携带但服务端无 ETag → 照常 200**：go1.26 的
    checkIfNoneMatch 对"携带但不匹配"返回 condTrue（即 If-None-Match 未命中）→
    不 304。304 只在 etagWeakMatch(请求 etag, 服务端 ETag) 命中时发生，本服务
    恒无 ETag → 永不 304。第一版翻译照想象写了 304，被 golden 抓回。
  - **mime.FormatMediaType 按 UTF-8 字节迭代**：Go `value[index]` 是字节索引，
    CJK 文件名逐字节百分号化（数 → %E6%95%B0）；Java 按 char 迭代会把 BMP 字符
    的 16 位值直接切 hex。另：`'`/`%` 是 token 字符（不引号），`=` 是 tspecial
    （引号）；needsEncoding 对 \t 豁免（encodedword.go）。
  - **filepath.Rel/Rel 的"根性不同"分支**：`Rel("/data/files", "10002/exports/a.png")`
    是 error（Go files.go GetFileURL 据此原样返回输入 → rewritten=true）。
    go1.26 实录钉住：`Rel("/a/b","/a/x/y/../cgi-bin") = "../x/cgi-bin"`。
  - **go-server-up 的 SYSTEM_AES_KEY**：WeKnora/.env **有** 32 字节的
    SYSTEM_AES_KEY（presign 签名在 dev 是激活态）；不要用自造 key 覆盖——两侧
    必须同 key（dev-env 统一取 .env）。录制/重放的 expires+sig 只在请求里、
    不进 golden，所以 golden 与时间无关。
  - **容器 Content-Type 空格规范化（备案，同波 1 G5）**：Tomcat 把
    `text/plain; charset=utf-8` 写上线成 `text/plain;charset=utf-8`（去空格）；
    ab-w5c.sh 的 norm_hdr 两侧同形归一（MockMvc 不归一，契约测试无此问题）。
  - **MockMvc 的 query 参数不做百分号解码**：`get("/files?file_path=local%3A%2F…")`
    到 handler 里还是编码值——契约测试直接放解码后的值（Go c.Query 拿到的也是
    解码值，两侧 handler 输入一致）。
  - **资源注册表（迁移 000069）**：TestSchema 的 resources 从最小投影扩到全投影 +
    补 resource_bindings/resource_access_grants 两表。/r/ 的授权完全在行上
    （token_hash=SHA-256(token)，派生令牌=HMAC("resource_grant:v1:<id>:<窗口起点>")
    前 16 字节 base64url，窗口=TTL/2、锚点是 **Go 零值时间（公元 1 年）** 距纪元
    62135596800 秒——跨语言派生同一 token 的前提；无 key 时回落随机令牌）。
    IsReferencedByKnowledgeBase 只认"绑定指向存活文档"（knowledges×knowledge_bases
    双 JOIN），未注册的 exports 文件在 KB 代理路由下是 403（不是 404）。
  - **已知差异（备案）**：①云 provider 的 SDK 客户端层未翻译——完备云配置的
    解析在 Java 落 400（Go 会造出客户端并可能 200），dev 恒 local 不可达（XDEP）；
    ②APP_EXTERNAL_URL 在位的 GetFileURL 预签名/派生令牌分支已按 Go 移植但 dev
    不可达；③消息代理的跨租户 shared-agent/org-shared 授予路径随波 5（owner≠caller
    恒 403，方向偏保守）；④If-Match/If-Range 的 412/200 语义按 Go 移植但 A/B 未录
    （curl 默认不带）；多段 Range 的 multipart 边界随机无字节锚。


- **W5d（沙箱终端 WS + 会话侧 local-browser + embed QA 委托收口）**：
  - **⚠️ Tomcat 上「101 后继续写 Servlet 裸流」不成立（本批最大技术风险，实测结论）**：
    `setStatus(101)+flushBuffer` 后，101 状态行与 `Sec-WebSocket-Accept` 能正常到达
    客户端，但 Tomcat 按 HTTP 语义视 **1xx 响应无实体**，之后对 response 裸流的
    `write+flush` **调用成功、字节却被静默吞掉**（探针日志证实 flush OK、客户端
    10 秒收不到 close 帧）——容器层面**不**等价 gorilla 的 Hijack。正路是
    **Servlet 3.1 升级**：`request.upgrade(TerminalWebSocketUpgradeHandler.class)`，
    帧走 `WebConnection` 裸流。升级判定段（gorilla 的检查序 + returnError 形态）
    与 101 头仍由控制器写（不 flush，容器在 service 返回时提交）。
  - **HttpUpgradeHandler 的数据交接 = ThreadLocal（arm → init）**：容器只按类名
    实例化 handler（无 Spring 注入）；Tomcat 在 service 返回后**同一线程**回调
    `init(WebConnection)`，但**过滤器链已退出**（TenantContext 已清）——续跑所需
    的租户等上下文必须由控制器在 arm 前**显式捕获进闭包**（HANDOFF §2.3 纪律 3
    「显式拷 TenantContext」的 WS 变体）。并发计数（sessionTerminalLimiter 上限 5）
    的 release 也必须挪进续跑闭包的 finally——控制器返回时连接才刚开始。
  - **`AuthFilter` 不是 bean，谁都不能注入它**（W5d 半成品踩塌整个上下文的根因）：
    它由 WebConfig `new` 进 FilterRegistrationBean。修法是 HANDOFF 属主决策的
    方案②——把 AttachAuthenticatedUser 能力链（authenticateJWTUser/空间解析/
    角色装配）抽成 `@Component WsAuthSupport`，AuthFilter 通道 2 与
    SandboxTerminalController 共用一份装配逻辑。派 agent 注入 bean 时，先核对
    注入目标是不是真是 bean（§2.3 纪律 4 的最新反例）。
  - **golden/A/B 抓回的真契约**：
    ① **`plainStatus` 的空体 404**——只 `setStatus(404)` 时 Tomcat 的
    ErrorReportValve 在响应未提交且状态 ≥400 时补默认错误体（`"404 Not Found"`，
    Spring Boot showReport=false 形态），Go 的 `c.Status(404)` 是空体。修法：
    `setContentLength(0)+flushBuffer()` 提交空响应后阀门跳过。**W5c 的
    missing-file 场景文件名无扩展名、ab 循环只比 .json/.bin/.hdr，同一偏差潜伏
    未曝**——无扩展名场景文件是比对盲区，新批次的 ab 循环要显式枚举。
    ② **WS 升级失败族的 `Sec-Websocket-Version: 13` 头**（小写 s——gorilla
    returnError 的原样键名）+ `X-Content-Type-Options: nosniff` + 纯文本
    `Bad Request\n`（12 字节含尾换行）逐字节钉住。
  - **框架层差异（备案，不入字节契约）**：①**CORS 头族**——Go cors 中间件
    恒写 `Access-Control-Allow-Origin: *`（配 credentials:true，规范上不允许但
    浏览器容忍），Spring CORS 禁止 allowCredentials+'*' 组合而**回显 Origin**；
    Expose-Headers 的逗号后空格也是框架渲染差异。浏览器语义等价，ab-w5d.sh 的
    norm_hdr 排除 `access-control-` 整族。②Tomcat 的 `;charset=` 去空格（W5c 已备案）。
  - **ticket JWT 的形状差（ opaque，不掩不行）**：Go 铸的票 header 含 `"typ":"JWT"`、
    claims 按字母序（exp/iat/session_id/tenant_id/token_id/type/user_id）；Java
    （jjwt）header 只有 alg、claims 按声明序。票对客户端不透明可交换（同 secret
    HMAC-SHA256 双端互验已过 A/B），但 golden 比对必须掩 JWT 值。
  - **embed QA 委托层**：`patchEmbedChatPayload` 的 `"null"` 字面量视同空体
    （Go `json.Unmarshal("null",&map)` 得 nil map 无错误）；数组/标量是 unmarshal
    类型错误 → 400 "invalid json"。委托后确定性错误的锚是 validator 文案
    （`Key: 'CreateKnowledgeQARequest.Query' ...`，Go 字段名——W5b 已备案同款）。
    **带 query 的 embed chat 不进 golden**（进完整 QA 管线挂流；SSE 字节契约
    4.6d 已钉，本批只验委托层）。
  - **登记面**（W5a 漂移族教训的执行）：sessions 组 RBAC 补 terminal-ticket +
    local-browser×2 三条 VIEWER 规则；APIKeyRoutePolicies 补同三条 chat 能力；
    APIKeyGateInterceptor `excludePathPatterns` 补 WS 升级路由（Go 注册于 Auth
    与 /api/v1 组之前，组级 gate 对它不跑）。WS 路由**不**登记 RBAC 规则
    （票据自鉴权，Go 无角色门）。

- **W5α1（共享 agent 读面收口：KB list / knowledge batch / knowledge search 的 agent_id 分支）**：
  - **`Long != Long` 装箱比较是真缺陷高发位**：`getSharedAgentForTenant` 的
    `agent.getTenantId() != share.getSourceTenantId()`（两个 Long，10005 超缓存区间 →
    引用不等 → 恒 true → 恒抛"agent not found"）。契约测试第一断言 403 抓回；
    修法 `.longValue()` 比较。**教训**：跨实体 id 相等判定一律先查两边声明类型，
    装箱类型用 `longValue()`/`Objects.equals`。
  - **`buildKBResponse` 走 `json.Marshal(实体)→map`，omitempty 在实体侧生效**：
    `storage_backend_id,omitempty` 空值时键**整体缺席**（Java build() 此前恒输出
    `"storage_backend_id":null`）。Go map 合并路径的 omitempty 判定要回到实体
    json tag，不能只看 map 组装代码。旧 golden（kb-list.json 等）该键恒有值，
    偏差潜伏到 w5s 的空值场景才曝。
  - **GORM 对 NULL 列跳过 `sql.Scanner.Scan`**（留零值 struct）——
    `IndexingStrategy.Scan` 注释里的 "NULL → DefaultIndexingStrategy()" 分支实际
    到不了。`IsZero→Default` 只发生在 **service 读路径的 EnsureDefaults 调用点**
    （KB list/get），chunk 等路径不做此默认：ChunkContractTest 种子显式存全 false
    strategy 关索引，getter 若自作主张 IsZero→Default 会把索引重新打开
    （16 个回归红灯："model ID cannot be empty" / index_status ready→failed）。
    **实体 getter 不许内嵌 EnsureDefaults 语义**——只保留 w5s 实录钉住的
    carve-out：faq 且 faq_config NULL 时 EnsureDefaults 提前 return，策略保持零值。
  - **FAQ 的 EnsureDefaults 段**：type=faq 且 FAQConfig==nil → 物化
    `{"index_mode":"question_answer","question_index_mode":"combined"}` 并**提前
    return**（策略默认段被跳过）；type!=faq → FAQConfig 清空（JSON null）。
    capabilities = IndexingStrategy 四位 + `type=="faq"`。
  - **`custom_metadata` 种子显式给 `'{}'`**（PG 列 NOT NULL + 默认值，H2 TestSchema
    是 nullable VARCHAR——KnowledgeSearchMoveContractTest 既有先例同款）。
  - **403 文案三兄弟各归各位**："no permission for this shared agent"（agent 解析，
    含 share 无/agent 无/显式 source 不符三种来源都折成它）/"Permission denied to
    access this knowledge base"（ResolveKB 三段授予全灭）/"Knowledge base not
    accessible through this agent"（授予后 scope 校验失败）。
  - **共享 KB 列表项三态 store view**：无 vector_stores 绑定 → envDefaultStoreView
    （本部署 engine_type 键缺席）；有绑定且跨租户 → SharedStoreDisplay（删
    vector_store_id/name、source="shared"）；own-tenant 绑定 → 批量解析。
    `creator_name` 带 omitempty——共享分支不回填 → 键整体缺席（勿用 buildListItem）。
  - **调试方法**：Spring Boot 测试的 `System.err` 落在
    `build/test-results/test/TEST-*.xml` 的 system-err 节点，不在 Gradle 控制台；
    断言 fail-fast——"8 请求只 1 个探针"是首断言即抛的正常形态，不是请求丢了。
  - 验收：21 条 w5s-* golden（Go 实录，record-w5s-golden.sh 幂等种子）+
    W5sSharedAgentContractTest 3 方法 22 断言 + 真 PG A/B 两轮 21/21 ALL MATCH
    （ab-w5s.sh，掩码仅时间戳）。knowledge/org 回归 174/174。

- **W5α2（QA resolveAgent 共享分支：knowledge-chat/agent-chat 的共享 agent 解析）**：
  - **Go resolveAgent 的错误是"吞掉"不是外抛**：GetSharedAgentForTenant 失败 →
    customAgent=nil 静默；source==0 才回落 own agent（"被拒的共享选择子不许静默跑
    同 id 本地内建"）；source!=0 且 nil → 外层 404 "Shared agent not found"。
    与 α1 读面（失败即 403）形态完全不同，别混。
  - **执行租户切换 = 换 TenantContext 快照，身份不动**：Go WithExecutionTenant
    先 WithCaller 捕获身份再换 TenantIDContextKey——授权面永远看调用方，仓库/模型
    解析看执行租户。Java 对应物：TenantContextSnapshot.withTenantId（record 派生），
    在异步段 replay 前替换。判别锚：模型行只在源租户 10005——不切换必
    "model not found"，A/B 正路径 SSE 双端 MATCH 证明切换生效。
    Go 同款守卫：租户不存在（GetTenantByID miss）则不切换。
  - **agentTenantID 取 effectiveTenantID 而非请求 source 参数**（Go L493-495：
    为 0 才回落 agent.TenantID）——请求里的 agent_source_tenant_id 只是选择子，
    不是数据。
  - **检索租户不依赖快照**：Java resolveRetrievalTenantId 已取 agentRow.tenantId
    （共享行=源租户），与 Go 的执行租户殊途同归；access.WithSharedAgent 的
    KB grant 授权收窄机制 Java 侧无对应物，随检索面专项收口（已备案控制器 doc）。
  - **字段级 JSON 类型错误的 Go 措辞仿真**：Go `json: cannot unmarshal string
    into Go struct field CreateKnowledgeQARequest.agent_source_tenant_id of type
    uint64`——GoJsonBindError 新增 fieldTypeError/valueKind + 登记表
    （Struct.field → Go 类型；uint64/int64 不能从 Java 类型推断，逐条 golden 登记），
    parseOrBindError 从 JsonMappingException 的 path 首段取字段名。
  - 验收：3 条 w5q-* golden（pre-SSE 错误面）+ W5qSharedAgentQaContractTest +
    真 PG A/B 两轮 5/5 ALL MATCH（ab-w5q.sh：2 SSE 正路径掩码对拍 + 3 负面逐字节，
    双侧各一条会话避免历史互染）；session/common/event 回归 467 绿。

- **W5α3（FileAccessResolver 跨租户双授予：shared-agent 授予 + org-shared KB 证据链）**：
  - **授予顺序照 Go files.go L154-230 逐行**：owner = resource 租户（无 resource
    行回落 message.agent_tenant_id）→ owner==0 → 403 → role=="user" 且跨租户 →
    403 → resource!=null 且 agentTenantId!=0 且 ≠owner → 先试证据链**失败即 403**
    （不落 shared-agent）→ owner!=caller 且未授权 → agentTenantId==0 时才再试
    证据链 → shared-agent 授予。授权失败一律 FileAccessException.forbidden()
    （Go 错误全折 403 的 fail-closed），消息加载失败 notFound()。
  - **证据链四要件缺一不可**（resourceAccessibleViaSharedKB，自有 agent + 他方
    KB 的 #3022 场景）：持久化检索证据含规范 resource:// handle
    （knowledge_references 的 content/matched_content/image_info，或 agent_steps
    递归）+ 证据 KB 属于资源租户 + kb_shares org 共享 ≥viewer + 存活
    resource_bindings（**文本里出现 handle 不算所有权证据**）。任何查找失败
    fail-closed。
  - **ToolCall 证据的 kb 上下文不共享**：collectKBEvidenceFromValue 对 ToolCall
    先 Output 后 Data——Output 命中的 handle **不能**归因到兄弟 Data map 的
    knowledge_base_id（上下文只沿 map 下行继承 knowledge_base_id /
    knowledge_base / knowledge_id）。Go 实录钉住：handle 在 Output + kb 在 Data
    → 403；handle 放进 Data 内部字符串（与 knowledge_base_id 同 map）→ 200。
    首录 evidence-steps 403 是种子设计错，不是翻译错。
  - **API-Key 主体的会话可见性**：owner = `api_tenant_key:<tenant>:<keyID>`
    （SessionOwnerIDFromContext(PrincipalAPITenant)），读 web 用户会话恒 404
    （owner 精确不匹配）；runtimeMayBypassAdminConsoleRead 只放行 owner 相等的
    key-owned 会话。授予循环里的 apiKeyAllowsKb 因此**必须用 key 自有会话才
    触达**（受限 key 白名单外 → false；full-access / web 用户恒放行；Go 是
    `AuthorizeTenantAPIKeyKnowledgeBases(...) == nil` 判定，Java try/catch 包
    authorizeKnowledgeBases）。
  - **GetMessageFileBindings 在 catalog 层做 reference→resource.ID 解析**
    （Go files.go 传原始 reference）：resolvePath → getByTenantLocation 兜底；
    资源不存在/租户不符 → 空 origins（**不是错误**）；messageArtifact 段认
    owner_type='message' + relation='artifact' + owner_id=消息 id。
  - **撤销 share 即撤销历史消息文件访问**：授权每次请求重查当前共享关系
    （agent_shares/kb_shares/绑定全部现查，无缓存）。
  - 验收：13 场景 18 个 w5f-* golden（Go 实录，record-w5f-golden.sh 幂等种子：
    租户/用户 ON CONFLICT ensure + w5f 专属 id 自清）+
    W5fCrossTenantFileContractTest 3 方法 + 真 PG A/B 两轮 18/18 ALL MATCH
    （ab-w5f.sh，无掩码）；storage/session/org/knowledge 回归 477 绿。

- **W5β（tenant_skill verify 族 + progress 收口）**：
  - **范围裁定**：verify 门=install 的最后一关，但**它不是 HTTP 面**——Go 里
    verifySkill 只被 install 管线的 installer-agent 循环调用
    （install.go installDependenciesAndVerify L769-820：round→agent→verify→
    repairable 才再来一轮，上限 skillInstallVerifyRounds=2）。Java 的 install
    管线体（播种/agent/快照/指针切换/transcript/steer/reaper）仍是 provider-XDEP
    接缝（dev 无 cube/e2b/docker，bootMaintenanceSandbox 恒失败），所以本批 =
    把"能翻且能钉死"的校验门全部落地 + 接缝原语就位，**管线消费点随 provider
    执行体批**。progress 三件套（publish/Last/Subscribe）波 3 已翻
    （SkillProgressStore，subscribe 的恒 null 通道=Go redis==nil 分支，逐字对齐），
    本批复核无缺口。repository 491 行复核：现役方法全在；快照台账四方法 +
    ListStaleInstalling 按 mapper 注释随管线批（消费点在 Snapshot/reaper）。
  - **形状差异（诚实声明）**：Go 的 verify 族挂 *TenantSkillService、从
    sandbox.Manager 取能力（installExecutor 能力断言 + SessionFileReader 类型
    断言）；Java 会话 Manager 未翻，执行面/读面以 SandboxInstallCommandExecutor +
    TenantSkillVerifier.SessionFileReader（窄能力接口，capabilities.go L108-111
    对应物）两个 seam 显式传入，断言失败收敛为 null 检查（文案逐字保留：
    "sandbox backend does not support install-mode shell" / "sandbox backend
    cannot read the install report"）。
  - **字节契约**：四个命令构造（tree/python/node/shell）+ forEach + runtime 命令
    全部钉 Go 实录（2026-09-21 `go test -overlay` 探针加
    internal/application/service/w5k_probe_test.go，不落盘 Go 仓）——fixture 在
    `contracts/w5k-probe-commands.tsv`（18 键原始字节）。**python 命令里的
    base64 全文一并钉住**：两侧各自 base64 后相等 = Java 资源
    `resources/sandbox/tenant_skill_verify.py` 与 Go go:embed 文件逐字节相同的
    实测证明（另有 shasum 256=f7d896a2…，cp+cmp 双保险）。
  - **行为验收三层**（27 项全绿）：①命令构造字节（17 测试）；②runtime 门行为
    ——照 Go runtimeProbeManager 用本机 `/bin/bash --noprofile --norc -c` 真执行
    校验命令：六形态报告阶梯（{}×3→"Write a valid…"、分号/绝对路径命令名→
    "bare executable names"、空 blocker→"non-empty explanations"）、skill 本地
    bin 的 PATH 解析（先缺→gate 含命令名，写入 .weknora/bin 再跑→nil，引号目录名
    练 ShellQuote）、外部 blocker 恒不可修（Repairable=false）、SKILL.md-only
    bundle 的完整错误串与 Go 实录逐字节（含 reader 的 "file does not exist"）；
    ③python 校验器行为——Go verify_python_test 全表镜像：10 用例 + 副作用
    （parse-only 不执行模块体）+ 非零权限 000 文件 + import 形态九连（**import
    永远不是裁决**）+ office 工具包布局，python3 stdin 真跑；缺 python3 跳过、
    `packaging` 在否决定 false marker 是 note 还是静默（Go 同款运行时分支）。
  - **语义细节（容易翻错的）**：①verificationNotes **不去重**（去重是 python
    校验器自己 add_note 的事，server 侧保序保重）；②verificationProblems 同样
    保重；③describeExecFailure 的字段序 exit→killed→error→stderr、各段
    TrimSpace；④nodeDependencyNames 读不了的 package.json → 空名单不报错
    （"是安装器 agent 要报告的问题"）、devDependencies 排除、名单排序；
    ⑤sortedScriptPaths 的 Go map 乱序被末尾 sort 消化——Java 侧 map 序无所谓，
    结果一致；⑥skillAuxiliaryScript 的目录段匹配在**大小写折叠后**进行
    （TESTS/x.py 命中），文件名 stem 规则 = conftest/setup/test_*/`*_test`；
    ⑦runtime 门 JSON 语义：commands/blockers 任一为 null 或缺失 → 无效报告
    （与解析失败同一文案），类型不符（`{"commands":{}}`）同败；命令名正则
    `^[A-Za-z0-9_][A-Za-z0-9_.+-]*$` + ≤128；blocker 空白行拒绝；entries>100
    拒绝；blockers 非空 → **Repairable=false** 的 gate（唯一不可修形态）；
    commands 空数组 → 直接 nil（不 exec）。
  - **execInstall 语义**：退出码非零 → (result, "command failed (" +
    describeExecFailure + ")") 双通道返回（verifySkillTree 需要读 result 的
    ExitCode/Stderr 再决定覆盖错误）；transport 失败 → (nil, msg)。Java 侧
    record InstallExec(result, failure) 对齐。ShellExecOptions：execVerify 用
    WorkDir=/workspace + 10min 超时 + WEKNORA_SKILL_DIR/WEKNORA_SKILL_OUTPUT_DIR
    双 env（常量复用 SkillEnvResolver）；execInstall 用 AsRoot+AllowSkillsRoot
    （旗标随会话 Manager 批生效）。
  - **SkillCommandPath 归一**：唯一实现提到 SandboxPaths.skillCommandPath
    （public），agent.skills.Manager 改委托——普通 skill 执行与安装校验共用
    （Go skill_paths.go L211 注释原文），防两处漂移。
  - **SkillInstallRuntimeInstructions 刻意未翻**：只被安装 prompt 构造消费
    （buildInstallPrompt/buildRepairPrompt，install.go L995/L1831），属 installer
    agent 管线；随管线翻，避免无消费者的大段常量先漂移。
  - 验收：27 新测试全绿（字节契约 17 + runtime 行为 4 + python 行为 6 类）；
    sandbox/agent.tools/agent.skills 定向回归绿；全量按 B1 纪律五批
    （B1a agent+chatpipeline / B1b common+event+apikey+audit+auth / B2 六包 /
    B3 八包 / B4 十四包）**全绿零失败**。A/B：**N/A（XDEP 备案）**——verify 门
    无 HTTP 面，dev 双端 install 管线都止于 bootMaintenanceSandbox（provider
    不可达）， golden 对账 route-recon 交集 387、真缺口候选 2 不变。

- **总验收冒烟 A/B 抓回三个真缺陷（2026-09-22，dev PG 双端实录，已修复）**：
  - **SessionListItem 的 NULL 字符串列**：dev PG 有 description 为 NULL 的会话行，
    GORM 扫描 → Go 零值 `""`（恒输出键），Java 侧 `SessionListItem` 的
    title/description 无零值归一 → 出 `null`。修复 = 字段默认 `""` + setter 归一
    （§9「Go 零值语义」的列表投影版——**列表 DTO 也要过零值清单**，不仅实体）。
  - **storage-backends 的时间戳时区**：Go 的 StorageBackendResponse 直接 marshal
    struct，GORM/lib-pq 扫描 timestamptz 得到的 time.Time 带 **UTC location** →
    输出 `Z`；Java 的 GoTimeSerializer 统一转 JVM 本地时区 → `+08:00`。此前的
    golden/A/B 都掩码时间戳所以漏网。修复 = `GoTimeSerializer.Utc` 变体（保护
    targetZone()）挂在该 DTO 两字段。**教训：掩码 A/B 对时间戳是盲的——收尾验收
    必须跑一轮无掩码逐字节抽样**；其余"直接 marshal struct"的响应类型同病，
    逐个排查（agents 列表 Go 走自定义格式化所以本就 MATCH）。
  - **im-channels 空列表 `null` vs `[]`**：Go 的 ListChannelsByAgent/
    ListChannelsByTenant 用 GORM Find/Scan 进 nil 切片 → 零行 marshals 为
    `{"data":null}`；Java 侧恒 `new ArrayList` → `[]`。修复 = 空列表出 null。
    golden 只录了有行场景所以没钉住——**空列表形态要显式录 golden**。
  - 验收锚：sessions/agents/knowledge_bases/im-channels/storage-backends/
    web-search-providers/vector-stores/sandbox-configs/tenants-all 九族 GET +
    sessions pin 写路径（pin 响应 + 列表回流 + 还原）全部无掩码逐字节 MATCH。

- **走查抓回（2026-09-22，手动验收「添加模型→测试连接」场景，已修复）**：
  `POST /initialization/remote/check` 组装失败文案时对 `BizException` 直接
  `getMessage()` → 带出 `error code: 1007, error message: ` 前缀（Go 的
  error.Error() 只有原文）。修复 = 拆包取 `appError().message()`
  （InitializationController.remoteCheck）。**BizException 前缀坑又一处复发**——
  凡「catch 后把异常文案回显/拼进响应」的位置都要过这一遍。
  - 同场景叠加的环境教训：用户机器 TUN 代理（Clash 类）开启时
    api.deepseek.com 被系统解析成 fake-IP 198.18.0.4（RFC 2544 网段），
    **双端 SSRF 都拦**（Go ipclass.go 也把 198.18.0.0/15 列 Reserved，拦截行为
    双端一致，是设计行为非缺陷）；而 Java 的 HTTP 发送路径在代理半开状态下拿到
    无消息的 IOException → `send request: null` 不可诊断。修复 =
    RemoteApiChat.sendRequest / AnthropicChat.send 的 IOException 文案在
    `getMessage()==null` 时兜底异常类名（对照 Go `fmt.Errorf("send request: %w")`
    会打出错误名如 "EOF"）。重启服务（清掉腐化连接池/DNS 缓存）后同请求立即
    打通。A/B 复核：DeepSeek 401 分支与 127.0.0.1 SSRF 拒绝分支双端逐字节一致。
