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

- **走查抓回（2026-09-22，手动验收「新会话问答→追问建议」场景，已修复）**：
  NORMAL 模式 QA 完成后 `POST sessions/{sid}/messages/{mid}/suggestions` 恒 400
  「follow-up suggestions require a completed assistant message」。根因是**同一处
  TenantContext ThreadLocal 丢失的两个切面**（Go 的 ctx 随 goroutine 自然携带，
  Java 跨虚拟线程必须 capture/replay——纪律 #1 的漏网分支）：
  ①NORMAL 模式的 `EVENT_AGENT_FINAL_ANSWER` 完成监听在桥接虚拟线程上触发，
  监听器内才读 `TenantContext.currentTenantId()`（必为 null）→
  `completeAssistantMessage` 的 `updateMessage` 抛
  `IllegalStateException: types.TenantIDContextKey not set in context`，WARN 吞掉 →
  **`is_completed` 永远落不了库**（AGENT 路径 L778 有 runWithTenant 包裹所以没事，
  stop 处理器 L976 也有——唯独 NORMAL 快答路径漏了）；
  ②`completeAssistantMessage` 里 spawn 的追问建议生成线程没像
  indexMessageToKb 那样包 runWithTenant → 同一个 IllegalStateException。
  修复 = 监听器注册时捕获 `reqCtx.session.getTenantId()`（对照 stop 处理器写法）
  + 建议线程包 runWithTenant。e2e 复核：修复后同流程 is_completed=t、
  suggestions 200（suppressed/disabled），与 Go 端逐字节同形态。
  - **副产物教训（测试环境抖动）**：排查中三批回归（session+chatpipeline+agent）
    反复出现 234 条「Could not self-attach to current VM」（Mockito/ByteBuddy
    外部进程 attach 失败）——一度与树状态呈假相关（stash 交叉实验 7 轮误导），
    实为**多个 Gradle 守护进程 + bootRun + vite 并存耗尽 16GB 内存**，helper JVM
    fork 失败。`./gradlew --stop` 清掉旧守护进程后稳定全绿。与
    build.gradle.kts 里 512MB→5g 的 OOM 史同族：**测试基建报错先查内存压力，
    再怀疑代码**；交叉实验要控制守护进程这个变量。

- **走查抓回（2026-09-22，QA 错误事件文案泄漏 + 代理环境污染，已修复）**：
  用户代理抖动期间 QA 失败，前端错误事件内容是
  `com.ragagent.common.error.BizException: error code: 1007, error message: send request: ConnectException`
  ——Java 异常类名 + BizException 前缀全漏给用户。Go 对照（qa.go L1290 附近）：
  管道返回的是 `PluginError.Err` **内层错误**（`return err.Err`，不带包装），
  事件文案 = `send request: Post "...": proxyconnect tcp: dial tcp ...: connection refused`。
  修复 = KnowledgeQaController.executeQA 的 catch 里新增 errorEventText()：
  沿 cause 链找 BizException 取 appError().message()，否则 getMessage() 兜底类名。
  A/B 取证法（值得复用）：**双端各指一个死代理**（Go: HTTPS_PROXY=127.0.0.1:9999
  环境变量，Go ProxyFromEnvironment 会读；Java: JAVA_TOOL_OPTIONS 注
  -Dhttps.proxyHost/Port）制造同款失败，抓 SSE error 事件对比——Go
  `send request: proxyconnect tcp: ...`，Java 修复后 `send request: ConnectException`
  （结构对齐，内层网络文案随平台差异属既有约定，同疑点③ EOF 兜底）。
  - **环境根因（不改代码）**：Java 进程的 http(s).proxyHost=127.0.0.1:7897
    来自 **Gradle 守护进程把启动 shell 的代理环境变量固化成 JVM 系统属性**，
    bootRun fork 继承——Clash 一切换/端口一空，Java 全部出站 LLM 调用
    ConnectException，而 Go（无代理环境变量，直连）正常。表象极像翻译 bug。
    处置 = `./gradlew --stop` + 干净 shell 重启（注意 --stop 会杀掉 bootRun，
    见 HANDOFF §0.0 测试纪律）。**教训：QA 链路「Go 通 Java 不通」先 jcmd
    VM.system_properties 查 proxyHost，再怀疑代码。**

- **走查抓回（2026-09-22，检索恒空三连根因，已修复）**：知识库明明有内容，
  工具检索恒「未找到匹配的内容」。三层根因叠加，逐层剥离：
  1. **部署/env 差（非代码）**：cmd/server 不读 .env（godotenv 只在
     cmd/desktop），RETRIEVE_DRIVER 必须真实导出；本仓 .env 存在会整文件抢占
     WeKnora/.env，而它不含此键 → 双端默认检索引擎皆空 → Go/Java 同打
     "No retrievable indexing pipelines"。修复 = dev-env.sh 导出
     RETRIEVE_DRIVER + env_value() 改按 key 回落 WeKnora/.env。
  2. **虚拟线程丢 TenantContext（翻译缺陷）**：PluginSearch 三处
     newVirtualThreadPerTaskExecutor（KB/web 并发、model 分组、查询扩展）上
     TenantContext（ThreadLocal）为空 → getQueryEmbedding → getModelByID 里
     tenantId()=0 → ModelNotFoundException（无 message，日志 error=<nil>）
     → 整组静默降级关键词-only → hit_count=0。Go 的 ctx 值随 goroutine 捕获
     流转，无此问题。修复 = 提交线程 TenantContextSnapshot.capture() +
     工作线程 replay/finally clear（约定 §5，与 cb1a003 同款）；同法修
     PipelineCommon.runParallel/parallelMap——agent 路径 SearchParallel 的
     任务线程也靠它拿到租户（否则 capture 到的还是空）。
  3. **SearchKnowledge 漏 setTenantId（翻译缺陷）**：Go 的 Merge 插件从 ctx
     取租户；Java 走 ChatManage。knowledge-search 路径没塞 → Merge 阶段
     parent_resolve/faq_enrich/expand 全 skip（missing_tenant）→ 响应
     content 少了前后文扩块。
  4. **mergeOrderedContent 裸拼（翻译缺陷）**：Go 用 searchutil.JoinChunkContent
     （后缀/前缀重叠折叠），Java 用了本地 joinChunk 裸 "\n\n" 拼接 → 扩块后
     内容与 Go 逐字节对不上（邻居块尾部与 base 前缀重复一段）。修复 = 改调
     ChunkSearchUtil.joinChunkContent，删除本地 joinChunk。
  - **验证**：POST /api/v1/knowledge-search（kb=d25d3cfd，「什么是在线工程师？」）
    双端 A/B：均 8 条，**前 6 条逐字节一致**（含 850 字扩块 content）；agent
    会话 @知识库 工具检索 Java「检索到 8 条」/ Go「检索到 7 条」同形态。
  - **残留已知差（文档化降级，非新 bug）**：尾部第 7/8 条集合不同
    （Java 65b911e5 vs Go 6ad80dd3）——MMR 的 jaccard 依赖中文分词，Java 的
    jieba 接缝是二字滑窗近似（SearchTextUtil 类注释声明的「唯一实质降级」），
    mmr avg_redundancy 0.0536 vs Go 0.0853，选择因此分叉。RRF 融合分、
    rerank 模型分双端 15 位小数一致。

- **走查抓回（2026-09-22，regenerate-summary 500——「阶段 7 占位」补全，已修复）**：
  知识库文档点「生成摘要」恒 500。根因：Java 端 regenerate-summary 在「无 summary
  model 的确定性 400」之外是占位——模型已配置时无条件抛
  `"summary model is not available in this deployment"`。route-recon 只对账路由，
  **抓不到「路由在、执行体占位」**；同类占位还有 ChunkService 三处（见文末待办）。
  本批全量翻译 Go knowledge_process.go L2291-2443 + knowledge_summary_refresh.go：
  - **RegenerateKnowledgeSummary**：textChunks 筛选（text+enabled）→ 空集
    description="" + failed 落库 + insufficient 400 → metadataVersion 捕获 +
    processing 落库 → getChatModel（ModelRuntimeFactory；code"model not found" →
    404 "Model not found"，对照 errors.As 解出内层 AppError 透传）→ getSummary →
    新鲜度校验（sourceChanged 先于 completed 落库）→ summary chunk 复用/新建
    （**必须按 chunk_type=summary 另查**——Go L2404-2414 的修复点：text-only 列表
    永远查不到已有 summary chunk，漏了会每次追加新块）+ updateChunkVector。
  - **getSummary**：sortChunksForSummary（有编辑按 chunk_index、否则 StartAt）+
    编辑拼接/MergeTextChunks + 图片富化（正文 <200 runes 走 caption+OCR，否则
    caption-only）+ sampleLongContent（头60%/中20%/尾20% 码点采样，
    “[...content omitted...]” 衔接）+ minTextContentRunes=10 充分性闸门 +
    CustomMetadataText 前缀 + LLM（temperature 0.3 硬编码、thinking=false、
    max_tokens 2048 缺省、{{language}} 渲染）+ 空白输出 → errEmptySummaryOutput。
  - **failGeneration**：insufficient → description=""+failed；willRetry（asynq 重试
    额度）→ pending（保留既有 description）；终态 → 新鲜度校验（源已变 → 丢弃）→
    首 chunk 兜底（500 码点 trim）+ failed。
  - **RequestKnowledgeSummaryRefresh**：summary 未启用静默返回；无 model →
    markFailed（单列）+ 400；成功 → pending 落库 + 虚拟线程刷新（显式拷
    TenantContext，§5），重试语义对齐 asynq MaxRetry(3)（stale/insufficient 静默丢弃）。
  - **controller**：两分支成功 → 200 {"success","data"}；refresh 分支 reload
    （对照 handler L1788-1791 的 pending 回流）。无 summary model 的 5 条
    kg-regen-* golden 保绿（400/404/403 形态不变）。
  - 新积木：ChunkRepository 补 listChunksByKnowledgeID（text-only ASC，Go L149）/
    listChunksByKnowledgeIDAndTypes / listChunksByParentIDs；ConversationProperties
    补 getGenerateSummaryPrompt；CollectImageInfoByChunkIDs 两级聚合下沉
    searchutil.ImageInfoEnricher（chatpipeline.ImageInfoCollector 改委托，消除两份
    实现漂移）；Chunk.embeddingContent()；KnowledgeIndexContent.build（新文件）。
- **同场处理管道三个索引契约点对齐（对照 Go processDocument L335-351/L563-585）**：
  ① **索引文本形态**：嵌入文本与 embeddings.content 改为
  `buildKnowledgeIndexContent(title + "\n" + EmbeddingContent)`（此前 Java 写裸
  content、嵌入无 title 前缀——ParadeDB BM25 的评分对象与 Go 不一致）；
  ② **重处理预清理**：先删旧 chunks 行（无条件）+ 删该 knowledge 全部向量行
  （仅向量化启用且模型可用时），此前只删 chunks 行、embeddings 旧行残留；
  ③ **失败清理**：处理链失败时删本次 chunks + 向量行；模型解析失败发生在预清理
  **之前**（既有数据不动，照抄 Go 顺序）。
- **同场修正 VectorStoreService 的 source_id 形态偏差（预存缺陷）**：Java 原写
  `"chunk:"+id`，Go 全部构造点是 `chunk.ID`（无前缀）+ 问题行
  `GeneratedQuestionSourceID`（chunkID-qID；超 64 字节折叠 chunkID-q+sha256 前
  12 字节 hex）。双端共库时旧形态会让同一 chunk 出两份向量行（ON CONFLICT 键不同）。
  改：saveIndexRows（Go 形态 + ON CONFLICT DO NOTHING 对照 BatchSave）+ 新增
  deleteByChunkId/deleteByKnowledgeId（对照 DeleteByChunkIDList/ByKnowledgeIDList，
  按 chunk_id 删**不区分前缀**——dev 库遗留的 "chunk:" 历史行由重新解析/编辑路径
  自然清理，无需数据迁移）。TestSchema 的 embeddings 补 is_enabled 列（迁移 000002
  有该列，此前 H2 缺列）。
- **走查阻断回归（服务重启后暴露）：knowledge_bases.creator_id 为 NULL 的行 →
  KB 列表 NPE 500**。dev 库 A/B 种子行（BQ Alpha/Beta/Temp）creator_id 为 NULL；
  `KnowledgeBase.getCreatorId()` 无归一化（字段 null 直出，setter 的归一化挡不住
  MyBatis 的字段回填），Go 非指针 string 恒 ""。getter 归一化 → 同族未防御点
  （ChunkAccessGuard/KnowledgeBaseController/WikiPageController 的 ownership
  `isEmpty()` 判定）一并解除。**A/B 实测**：Go :8080 同库列表该字段为 ""
  （非 null），Java 修复后一致。**教训：走查期间的提交（如 c5a34f1）若服务未重启，
  回归潜伏到下次重启才暴露——提交后应及时重启验证。**
- **验证（真实环境）**：走查文档（租户 10122「GAC客服」KB，49 text chunks，
  summary model 已配）——首发 200 + 8.9s + DB completed + description 落库 +
  summary chunk（index=max+1、parent=首 text chunk、is_enabled=t）+ embeddings 行
  （source_id 无前缀、content 带 title 前缀、dimension 1024）；第二发（refresh）
  200 + 80ms + 响应 summary_status=pending（reload 回流）+ 异步完成后 completed
  且 summary chunk 仍 1 个（复用不追加）；全站冒烟 200。新增
  SummaryPipelineLogicTest 12 用例；knowledge/chatpipeline 两包 215 绿 + 复跑 172 绿。
- **同族待办（本批未动，下一批）**：ChunkService 三处同款阶段占位——
  `syncChunkIndex`（「reindex engine unavailable」占位；Go chunk.go L669-719 就是
  单 chunk 版 updateChunkVector：NeedsEmbedding→GetEmbeddingModel→
  DeleteByChunkIDList→disabled 只删不插→chunk 行+问题行 BatchIndex）、
  `enqueueSummaryRefresh`（WARN no-op；应接本批 requestKnowledgeSummaryRefresh 的
  pending/入队语义）、`regenerateChunkQuestions` 的 LLM 生成步（同款
  "summary model is not available in this deployment" 占位）。接线会改变
  ChunkServiceTest 已钉断言（「策略开+模型在 → failed」），需随批更新 + A/B 验证。
  另：KnowledgeService 的 updateChunkVector 与新积木可顺手抽公共
  ChunkVectorIndexer 供 ChunkService 复用。

- **同批续（2026-09-22，ChunkService 四处阶段占位接线，已修复）**：上条「同族待办」
  当日一并落地——
  - **新积木 `ChunkVectorIndexer`**（@Service）：`updateChunkVector`（多 chunk，摘要
    链路）与 `syncChunkIndex`（单 chunk，chunk 编辑链路）共用同一执行体（删旧 →
    disabled 只删不插 → chunk 行 + 问题行 BatchIndex）。问题行 source_id 统一到既有
    `ChunkSearchUtil.generatedQuestionSourceId`（删除了本批一度重复的实现）。
  - `ChunkService.syncChunkIndex`（原「reindex engine unavailable」占位）→ 委托
    indexer；失败文案阶梯照旧（kb 缺失/模型 id 空/模型缺失 → IllegalStateException →
    上层标 index_status=failed）。
  - `ChunkService.enqueueSummaryRefresh`（原 WARN no-op）→ 委托
    `KnowledgeService.requestKnowledgeSummaryRefresh`（pending 落库 + 虚拟线程刷新）。
  - `ChunkService.regenerateChunkQuestions` 的 LLM 生成步（原「summary model is not
    available」占位）→ 全量：prompt 渲染（question_count/content/context/doc_name/
    language）+ 业务指引包裹 + chat（temp 0.7 / max 512 / thinking=false）+ 行解析
    （剥前缀符号、>5 字节、count 上限；抽 `parseGeneratedQuestions` 纯逻辑）+ latest
    revision 冲突 409 + 向量原子替换。
  - `ChunkService.deleteGeneratedQuestion` 的向量删除（原 WARN no-op）→
    `VectorStoreService.deleteBySourceId`（新增，对照 DeleteBySourceIDList；删失败同样
    只警告继续）。
  - 配套：`PromptInstructions` 上提 `com.ragagent.common.prompt`（wiki 的历史类改一行
    委托，消除两份措辞漂移——wiki 类注释当初的建议落地）；ConversationProperties 补
    `getGenerateQuestionsPrompt`（含 extractEntities/extractRelationships 预留 getter）。
  - **踩坑（值得记）**：把 `updateImageInfo` 的「策略 isZero→Default」钩子误套进
    `NeedsEmbeddingModel` 判定 → 显式全 false 的策略被翻成 Default（vector+keyword
    全开）→ 策略关的 KB 走进真实出站 → 13 个既有测试红（ChunkServiceTest 全链 +
    ChunkContractTest 契约）。Go 的 IndexingStrategy 是**值类型 struct**：零值即 false，
    `NeedsEmbeddingModel` **没有** EnsureDefaults 钩子（那是服务读路径的独立语义）。
    修正 = indexer 与 KnowledgeService.kbNeedsEmbedding 双处去钩子（注释钉住教训）。
  - **验证（真实环境闭环，租户 10122 文档）**：① chunk 编辑（index_status 置 failed
    走重试路径）→ 200 + ready（0.99s 真实出站）+ 向量行重建（source_id=chunkID 无前缀、
    content 带 title 前缀、1024 维）；② 生成问题 → 200 + 真实 LLM 出 3 条问题（1.8s）+
    问题行向量 `chunkID-q<sha24hex>` 折叠形态（62 字节）；③ 删除问题 → 200 ×3 + 向量行
    4→1 + metadata 复原 `{}`。回归 ~2502 测试绿，仅 2 条 SSRF/DNS 环境性失败
    （TenantCatalogContractTest / SandboxSkillsMeContractTest 的 fake-IP 漂移族，
    与代码无关）。**未单独验证**：chunk 编辑 bodyChanged 触发的 enqueueSummaryRefresh
    真实入队（逻辑已由摘要链路端到端覆盖，调用点 catch/warn 与 Go 同款）。

- **占位扫描结论 + FaqService 索引执行面接线（2026-09-22 走查批续）**：全仓按
  「not available in this deployment / engine unavailable / lands with wave」文案扫描，
  真缺口清单 = FaqService 索引族（本段修复）/ updateImageInfo 向量重建（小）/
  WebSearchProvider test（小-中）/ EvaluationService 执行步（大，依赖 dataset 服务）；
  其余（sandbox/skill 文件工具、TenantSkillSource、OidcService enabled 分支）为
  provider-XDEP 设计内降级或明确 deferral。
  - **新积木**：`FaqIndexRows`（buildFAQIndexInfoList：combined 单行 / separate
    标准问 + 相似问 `chunkID-<i>` 行，buildFAQIndexContent 逐字对照）；
    `TenantStorageService`（AdjustStorageUsed：SQL 增量 + 负数钳 0）；
    `VectorStoreService` 扩展（IndexRow 补 tagId（**所有行**写 tag_id 列，此前落
    NULL 与 Go 的 "" 有别）+ batchUpdateChunkEnabledStatus / batchUpdateChunkTagId
    （对照 pgRepository 两个 Batch 方法）+ estimateStorageSize（对照
    calculateIndexStorageSize））。
  - **FaqService 接线**：创建（indexFAQChunks adjustStorage=true，失败回滚 chunk +
    "failed to index chunk: %w"）/ 更新两处（adjustStorage=false，失败原样返回）/
    删除（deleteFAQChunkVectors：删行 + 配额回退 + 钳 0）/ 字段批量（enabled/tag 同步
    → 失败**阻断**（对照 knowledge_faq.go L838-852 的 return err），此前 WARN+no-op）。
  - **踩坑①（真实缺陷，两处通用）**：`chunks` 表的 NOT NULL string 列
    （source_content / last_editor_id / context_header）在「Go Save 全列写零值」的
    语义下，Java 的 updateChunk（updateAllFieldsExceptSeqId 全列 UPDATE）对
    **新建 chunk 的内存对象**写 NULL → 违反 NOT NULL（FAQ 创建后 status 更新的实案
    500：`null value in column "source_content"`）。修复 = ChunkRepository.updateChunk
    对三列做 Go 零值归一（source_content/context_header 判 null 置 ""；
    getLastEditorId 已归一——经 setter 回写字段）。同族教训：**「Go 非指针 string」
    的列在写库前都要过零值归一清单**。
  - **踩坑②（纪律）**：接线后直接在旧进程上验证 → 撞到旧 classes（日志里是已删除的
    方法名栈帧），**编译/测试后必须重启 bootRun 才能验证**（同 §0.0 测试纪律编）。
  - **验证（真实环境，走查租户临时 FAQ KB + 现有 Embedding 模型，验证后已清理）**：
    ① 创建条目 → 200（1.0s 真实 embedding）+ chunk status=2 + 三 NOT NULL 列 "" +
    embeddings 行（source_id=chunkID、tag_id=未分类标签、dimension=1024、
    content=标准问/相似问/答案 combined 形态）；② 字段批量 is_enabled=false →
    chunks 与 embeddings 同步 f；③ 删除条目 → 200 + 向量行清理 + chunk 软删 +
    **storage_used 12958→6479（配额回退精确）**；④ 删除临时 KB 无残留。
  - **待办（同族，下一步）**：FAQ **导入**执行面（`importUnavailable` 仍占位，
    knowledge_faq_import.go L1499-1620 的分批循环：build → CreateChunks →
    indexFAQChunks(adjustStorage=true) → status=2 → 进度/失败收集；indexFAQChunks
    本段已就绪，导入只剩循环与进度接线）；updateImageInfo 向量重建；
    WebSearchProvider test；EvaluationService。
