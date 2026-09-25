# 已确认细节与坑 · W5 收尾批（W5a / W5b / W5c + 后续 W5d 追加于此）

> 本文件是原 `docs/translation-conventions.md` §9 的一个分片（按批次拆分，**内容未改动**）。
> 2026-09-25 起 conventions 已拆分：规范正文并入 `docs/HANDOFF.md` §7，日志/细则见 `docs/translation-log.md`。
> 代码注释与任务书里的「约定 §9「XXX」」按条目标题在本目录内检索即可。
> 回链：[`translation-log.md`](../translation-log.md) §9 索引 ｜
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
  - **FAQ 导入执行面接线（同日续，`importUnavailable` 占位清除）**：对照
    executeFAQImport 的导入循环（knowledge_faq_import.go L1499-1671）实现
    `executeImportBatches`——按 faqImportBatchSize(50) 分批 → 逐条 sanitize /
    resolveTagID / 建 chunk（不设 flags，对照 Go）→ CreateChunks →
    indexFAQChunks(adjustStorage=true) → status=2（updateChunks 批量）→ 收集
    success_entries（index/seq_id/tag_id/tag_name/标准问；**seq_id=0 与 Go 一致**：
    Go 的 CreateChunks 在 PG 上不预分配 SeqID（注释：DB 序列，避免并发冲突），
    GORM 也不回读）→ 进度落库（"正在处理第 X/Y 条"）→ finalizeImport
    （completed + 结果落库 + replace 清未引用标签）。已知差异：Go 的 defer recover
    会回滚本任务已建 chunks 与索引行，Java 直落 failed 终态（与 processImport 既有
    取舍同款）。配套补 `withSuccessEntries` helper。
  - **导入真实验证（走查租户临时 FAQ KB，验证后已清理）**：POST /faq/entries
    （3 条，append/dry_run=false）→ 200 + task_id；轮询进度 → **completed /
    progress 100 / processed 3 / success 3 / failed 0**（"导入完成 / 上传 3 条 /
    成功 3 条"）+ success_entries 3 条（index/tag_id/tag_name/标准问逐字正确）；
    DB：3 chunks status=2 + 3 向量行（dimension 1024、tag_id/source_id 齐、
    content=combined 形态）；列表接口回读（seq_id 真值）；删除条目 + 删除 KB
    无残留。knowledge 185 + agent/chatpipeline 405 绿。
  - **updateImageInfo 向量重建接线（同日续，占位清除）**：`updateChunkVector` 占位
    （"embedding model is not available in this deployment"）替换为真实调用
    `ChunkVectorIndexer.updateChunkVector(kbId, updateChunks + addChunks)`（对照 Go
    L3099 `append(updateChunk, addChunk...)` 形态；空 model ID → 1007
    "model ID cannot be empty"，golden kg-image-update/again/mismatch 复绿）。
  - **踩坑③（读层差异，二次修正上一批的去钩子过修）**：Go 的
    `NeedsEmbeddingModel` 判定**按调用点分两层语义**——
    **服务层**（`kbService.GetKnowledgeBaseByID`：repo 读后 `EnsureDefaults()`，
    `IsZero()`（4 字段全 false）→ Default（vector+keyword 开））；
    **repo 层**（`kbRepository.GetKnowledgeBaseByID`：仅 GORM Scan，NULL 列 →
    Default、显式全 false JSON → **保持 false**）。
    `updateChunkVector`/regenerate 走服务层（`knowledge_process.go` L2861/L2302）；
    `chunkService.syncChunkIndex` 走 repo 层（`chunk.go` L670）。证据：kg-image
    golden 的 KB fixture 是**显式全 false 策略**，Go 实录仍是 1007（走进了向量
    分支）→ 服务层钩子确实存在；而 chunk 编辑系列的 golden 期望不走进
    （上一批 13 测试红的实证）→ repo 层无钩子。修正：`ChunkVectorIndexer` 拆
    `needsEmbeddingServiceLayer`/`needsEmbeddingRepoLayer`，
    `KnowledgeService.kbNeedsEmbedding`（regenerate 路径）恢复服务层钩子；
    updateImageInfo 路径不再有独立判定（统一走 indexer）。
    验证：knowledge 185 全绿（kg-image 系列 + chunk 编辑系列同时满足）；
    全量回归遇成片 Mockito「Could not self-attach」为 HANDOFF 已钉的内存压力
    抖动（非业务）；真实验证见下条。
  - **WebSearchProvider test 端点接线（同日续，SEARCH_DEGRADED 占位清除）**：
    `doTestSearch`（`POST /web-search-providers/test` 与 `/{id}/test` 共用）的固定
    降级文案替换为真实执行——对照 Go `handler/web_search_provider.go` L412-431 全量：
    `registry.createProvider(providerType, params)`（失败 → "failed to create provider: "
    + 原文）→ `provider.search("test", 1, false)`（失败 → 原文透传）→ 空结果 →
    `EmptyTestResults.emptyTestResultsError` 文案；三支出口均 200 纯字符串
    （TestFailure）。真实出站受本部署 SSRF 白名单约束（与 Go guard 同款）。
    controller 注入 `WebSearchProviderRegistry`（域类/接口同名用全限定名区分）。
    验证：websearch 39 全绿（原 38 条 golden 契约 + 新增 1 条执行面回归——
    `section7_testRealExecution` 以 registry.register 注入内存 stub 覆盖
    「有结果 success / 空结果文案 / search 抛错原文」三出口与调用参数契约
    （"test"/1/false）；Go 侧该路径无实录，故为内联断言非 golden）；
    真实环境实测（Java vs Go 双端对拍）：`{"provider":"duckduckgo"}` 均 200
    `{"error":"duckduckgo HTML search failed: ... HTTP connect timed out / net/http ...",`success":false}`
    ——真实出站已发生（本环境 DDG 不通，错误文案详情因 HTTP 客户端不同，属 provider
    翻译层的既有差异）；`nosuch` 类型 → 双端同文案 "not registered"。
  - **EvaluationService 执行步（Owner 决策：暂缓，2026-09-23）**：范围与依赖清单
    已侦查完，暂不投入——理由：①前端**无** `/v1/evaluation` 调用入口（settings
    仅 API-key 权限位名 `run_evaluations`）；②`CreateKnowledgeFromPassageSync`
    亦**无 HTTP 路由**（仅评估内部调用）；③需新增依赖 **jieba-analysis** 中文分词
    （Go metric 包 `splitIntoWords` 用 `types.Jieba.Cut`——BLEU/ROUGE 分词），
    零新依赖纪律下需单独评估；④metrics 数值因分词器实现差异**不承诺**与 Go
    逐字节一致（HANDOFF 已约定 `ev-get.json` 属部署能力差异，A/B 按部署各自断言）。
    **已完成部分**：dataset 服务（parquet → 内嵌 JSON + Iterate/PrintStats，
    提交 272928b + 2 条单测）。**剩余清单**（按依赖序）：metric 算法包
    （Go ~600 行非测试：precision/recall/ndcg/mrr/map/bleu/rouge/rouge_score/
    common；Go 单测 precision/recall/mrr/map 可照搬断言）→ metric_hook.go
    （194 行：MetricList.Append/Avg + HookMetric 的 record*/内容匹配回 pid）→
    `CreateKnowledgeFromPassageSync` + `processDocumentFromPassage`
    （Go knowledge_create.go ~300 行：段落安全校验 → knowledge 行（type=passage、
    enable_status=disabled）→ 同步分块+索引 → audit）→ `EvalDataset` +
    `getPassageList`（147 行：并发 worker = GOMAXPROCS-1、逐 QA 跑
    KnowledgeQAByEvent（Java 已有）、进度回写、defer 清理 knowledge+KB）→
    controller 接线（后台线程从「标记 failed」换真实执行）。
  - **会话标题生成接线（走查第十七处，2026-09-23）**：现象 = 发消息后标题恒为
    "新会话"。根因两处阶段占位：①`SessionService.generateTitle` 的 LLM 步抛
    `"title model runtime is not available yet (untranslated)"`；②
    `KnowledgeQaController` 的 GenerateTitleAsync 触发点只打日志（原备案
    "两侧均无 session_title 事件" 的论断有误——Go 在 dev 有 KnowledgeQA 模型时
    会真实生成并 emit 事件，前端据此更新标题）。修复：`generateTitle` 全量接线
    （GetChatModel → system=GenerateSessionTitlePrompt（language 占位渲染）+
    user=消息内容 → Chat（temperature 0.3 / thinking=false）→
    `sanitizeGeneratedTitle`（剥 `"<think>\n\n</think>"` 前缀 + Go TrimSpace +
    100 码点截断）→ `sessionRepository.update`）；新增 `generateTitleAsync`
    （捕获租户/请求 ID → 虚拟线程 → 生成 → emit `session_title` 事件；
    AgentStreamBridge 已订阅同事件转发 SSE）。**EventBus 非 Spring bean**
    （请求级实例）→ 对照 Go 签名按参数传入（上下文启动期 `NoSuchBeanDefinition` 实测踩坑）。
    验证：session 270 全绿（g6-title-* golden 的确定性分支全保持）；真实环境——
    ①同步端点 `POST /sessions/{id}/generate_title` → 200
    `{"data":"计算机操作系统有哪些","success":true}`（0.9s 真实 LLM）+ 落库；
    ②二次调用 0.01s 返回已有标题（幂等不重生成）；③异步路径：
    `knowledge-chat` 发消息 → SSE 流内 `session_title` 事件 ×1 + 标题落库；
    测试会话已清理。
  - **重建知识后摘要恒空（走查第十八处，2026-09-23）**：现象 = 「重建知识」
    （reparse）后 description 被清空且永不恢复（首次导入同样不生成摘要）。
    根因 = Java 缺 Go 的「处理完成 → post-process 摘要 fan-out」链路：①worker
    完成时未落 `summary_status=none`（对照 finalizeIndexedKnowledgeState
    L215-242）；②无 post-process fan-out（对照 knowledge_post_process.go L208
    `willSpawnSummary = textChunks>0` → L562 入队摘要任务；任务体 =
    ProcessSummaryGeneration）。修复：`failOrComplete` 完成时（KB 配了 summary
    model）落 none + 有文本块则调 `requestPostProcessSummaryGeneration`
    （cancelled/deleting 跳过 → 无 summary model 落 failed 不抛 → pending 落库 +
    复用刷新的重试/吞错 worker，对齐 MaxRetry(3)）。
    **条件化取舍（记录在案）**：仅当 KB 配了 summary model 时推进——Java 的进程内
    worker 在契约测试里会真实处理，而 Go 录制环境的 asynq worker 不在录制进程内；
    无条件推进会用异步副作用污染 HTTP 快照断言（kg-manual-draft 等 3 例实测红）。
    无 summary model 的 KB 因此不做 none/failed 中间态（Go 真实运行会推 failed），
    可观测差异 = 摘要状态列的中间值。验证：knowledge 185 全绿；真实环境
    （走查 KB + 文档 reparse）：`pending→completed` + `summary:
    completed→processing→completed` + **摘要重新生成**（30s 内）。
  - **span 写入侧全量接线（走查第十九处，2026-09-23）**：现象 = 文档导入/重建后
    无「查看 Trace」按钮（前端判定 `spans` 返回 `trace.span_id` 或
    `current_attempt>0`）——Java 只有 spans **读**侧（无行时合成占位 trace，
    span_id 空），缺 Go 写入侧。Owner 决策**全量翻译**，四阶段落地：
    ①**Phase 1**（55a224f）：`KnowledgeProcessingSpan` 模型（kind/status/stage
    常量集）+ `KnowledgeSpanRepository` 8 方法（upsert **动态列**语义 / attempt
    分配 / `listByAttempt` id ASC / BFS 级联取消 / 三种 cancel）——PG 用
    `ON CONFLICT (knowledge_id,attempt,span_id) DO UPDATE SET <动态列>`
    （`uq_kpspan_attempt_span` 在 000055 迁移），H2 退化为先查后写；TestSchema
    补表（jsonb→CLOB）；单测 8 组。
    ②**Phase 2**（d61c02a）：读侧接真实表（attempt 选择 / `buildSpanTree(rows)`
    真实建树 + 缺失 stage 合成 / `last_error` span 失败行优先 /
    `spanNodeFromRow` 键序与 omitempty 语义）。
    ③**Phase 3**：`SpanTracker` 全文（openAttempt / beginStage（同名重入复用
    span_id）/ beginSubSpan（fitSpanName + supersede）/ end-fail-skip /
    lookupStage-ByName / finalizeAttempt（幂等 + duration 重算）/ abortAttempt
    （平扫非终态）/ `stagesDependingOn` 传递闭包 / MAIN 阶段失败收口 root /
    heartbeat 仅 root-stage）。
    ④**Phase 4**：worker 埋点（openAttempt + docreader（仅文件路径 + 失败
    failSpan）/ chunking（chunks_written+total_text_chars）/ embedding（或 skip）/
    multimodal skip / postprocess + finalize；`cancelKnowledgeParse` →
    AbortAttempt）。
    **已知差异**：reparse 的 attempt 分配推迟到 worker 启动（Go 在 reparse 入口
    分配并随 payload 传递，UI 立即看到新 attempt）；span 的 input/output 取关键
    子集（无 golden 覆盖该细节）。**验证**：knowledge 193 + 回归 868 全绿（停
    daemon 后跑）；真实 reparse：spans 表 6 行（root 5732ms + docreader 268ms +
    chunking 176ms + embedding 5073ms + multimodal **skipped** + postprocess 27ms）
    → `/spans` 返回 `trace.span_id` 非空 + `current_attempt=1` → 前端
    `hasTrace=true`（按钮显示）。
  - **同族彻底清空**：至此占位扫描清单的 4 项真缺口全部落地（FaqService 索引族 /
    updateImageInfo / WebSearchProvider test / 评估 dataset 前置）+ 走查抓回的
    会话标题生成、post-process 摘要 fan-out；仅 EvaluationService 执行步按 Owner
    决策暂缓、span 写入侧待决策。

- **走查抓回（2026-09-23，agent 模式分派谓词抄错——「智能推理」agent 恒走 RAG 快答）**：
  现象 = 所有真实 agent（前端/内置/IM 一律写 `agent_mode=smart-reasoning`）经
  `POST /agent-chat` 都被判进 RAG 快答管线：无 thinking/todo/工具事件、无多步推理、
  steer/compaction 失效；`local_browser_enabled` 因同谓词被误拒 400。
  - **根因一（谓词抄错）**：`SessionKnowledgeQaService.isAgentMode` 写成
    `"agent".equals(...)`——Go 侧无此取值（`types/custom_agent.go` L553-556：
    `Config.AgentMode == AgentModeSmartReasoning`；Java IM 侧 `ImService:912`、
    前端 `Input-field.vue:2054`、`AgentConfigJson:65` 都写对）。5 处消费点
    （QA 分派 / 本地浏览器门控 / agentEnabled 持久化 / 两处 prompt 模板选择）同源受害。
  - **根因二（内建虚拟 agent 运行时空配置）**：无 DB 行的内建 agent 走
    `CustomAgentService.virtualAgent` 合成行，但合成时**不落 config 字符串**；
    `KnowledgeQaController.resolveAgent` 只取 `result.row()` 再
    `parse(row.getConfig())` → 得 `{}`（agent_mode/allowed_tools/kb_selection_mode
    全丢，SandboxTerminalController 同款受害）。Go 的 `GetAgentByID` 在无 DB 行时
    返回带完整 Config 的物化 agent。有 DB 行的租户（10000/10009/10122）不触发，
    故 dev 主租户长期带伤而不可见。
  - **修复**：谓词改 `"smart-reasoning"`（注释钉 Go 行号）；`virtualAgent` 合成行补
    `setConfig(built.get("config").toString())`（响应层不受影响——`AgentResponses`
    用 `result.config()`）。
  - **验证**：新增 `SessionKnowledgeQaServiceAgentModeTest`（smart-reasoning/
    quick-answer/旧误值/空 四取值域）+ `AgentContractTest.virtualBuiltinAgentCarriesConfigOnRow`
    （虚拟行 config 非空）；session 339 / agentm 7 / agent 362 / org 2 / knowledge 193
    全绿；**双端活进程 2×2 对拍**（同一会话同请求体）：`builtin-smart-reasoning`
    → Go/Java 均 `stage=agent_execution`（修复前 Java 为 `knowledge_qa_execution`）；
    `agent_mode="agent"`（Go 视为非 agent）→ 双端均 RAG 流（knowledge_search 进度 +
    fallback answer）——分派完全互换验证通过。**无 golden 覆盖 `agent_execution`**
    是漏网主因（4.6d A/B 场景全是不可解析 agent / agent_enabled=false）。
  - **遗留（紧接下一步）**：agent 引擎的**检索工具族仍未注册**
    （`SessionAgentQaService.registerTools` 只构造 thinking/todo_write，
    knowledge_search 等落 `default → "Unknown tool"`；工具实现（4.5b）与检索执行面
    均已就位，`hasVectorKb` 硬网不会删掉有向量库的用例）——分派修好后 agent 真实跑
    引擎但无 KB 工具，须随批补注册。

- **agent 检索工具族接线（2026-09-23，切片 1——KB 五件；双端 stub A/B 抓回四处保真差）**：
  背景 = `registerTools` 对 knowledge_search / grep_chunks / list_knowledge_chunks /
  query_knowledge_graph / get_document_info 只落 `default → "Unknown tool"`（工具类 4.5b、
  检索执行面 3cb4b2e 早已就位）。
  - **接线**：新增 `session.service.AgentToolBackends`（9 个窄 seam → HybridSearchService /
    KnowledgeService / ChunkRepository / Reranker / ImageInfoEnricher / JdbcTemplate），
    `registerTools` 经 `createKbTool` 注册；grep_chunks 按 Go `searchChunks` 整段移植
    （OR scope / 正则 / LIMIT 500 / enabled 计数回填；方言 PG `~*` / 通用 REGEXP /
    H2 `REGEXP_LIKE(...,'i')`）；修 `KnowledgeSearchTool` 的 seam 缺口——
    `KnowledgeSearchBackend.hybridSearch` 补 kbID 参数（Go 两参：`HybridSearch(ctx,kbID,params)`；
    定向分支此前丢 target KB id）。
  - **A/B 方法**：`stub-llm-server.py` 增 `STUB_DUMP_DIR`（默认关闭）落 LLM 请求体；
    双端同指 stub（`SSRF_WHITELIST_EXTRA=127.0.0.1` 重启）→ 同一 agent（smart-reasoning +
    stub 模型 + stub rerank）跑 `<<SCENARIO:chat>>` → 对拍请求体。
  - **A/B 抓回四处（均已修）**：① 五件工具 schema 与 Go `GenerateSchema` 输出不一致——
    query_knowledge_graph 缺 `additionalProperties:false` 与可空数组 `["null","array"]`、
    grep_chunks 描述里 `\brag\b` 少一层反斜杠、三件 schema **键序**非字母序；② `RemoteApiChat`
    的 tools 信封键序（Go 字母序：`function` < `type`；`description` < `name` <
    `parameters`）；③ 出站请求体未做 Go HTML 转义（`< > &` → 小写反斜杠 u 形式；改经
    `GoJsonEscapes` 的 JsonMapper 序列化，与 embedding/websearch 的既有 GoJson 模式一致）；
    ④ `SessionAgentQaService.chatModel` 的 governor/ollama 传 null——并发闸门装配
    （95a49c4）后 **agent 路径任何 LLM 调用必 NPE**（被分派 bug 掩盖，修分派后暴露）；
    同族还有 `SkillInstallPipelineImpl:1123`（install 管线，未修，随批补）。
  - **残留（记录待专项，均非本切片主题）**：外层请求体键序（Go 字母序 vs Java 插入序）、
    messages 差异（system prompt 缺 Go 的 KB 使用段约 860 字符 + user 消息缺
    `<runtime_context scope="this_turn">` 块）、`temperature`（Java 发 0.7 / Go 不发）。
  - **验证**：tools 段 9685 字节前缀逐字节一致（五件工具结构与顺序一致）；
    agent 362 / session 346 / knowledge 193 / llm.chat 154 全绿；新增
    `AgentToolBackendsKbToolTest`（构造钉）+ `AgentToolBackendsDbTest`（H2：scope/正则/
    计数/分页/标签）。恢复后端时注意 dev-env：本仓 `.env` 存在时 `WEKNORA_ROOT` 未导出，
    `go-server-up.sh`（set -u）会炸——需显式 `WEKNORA_ROOT=/Users/billy/WeKnora` 前缀。

- **agent 工具接线·切片 2a（2026-09-23，会话/记忆/DB 三件 + 记忆闸门回收）**：
  在切片 1 的 `AgentToolBackends` 上补三件：
  - `search_conversations`：对照 NewSearchConversationsTool——owner 在引擎装配期从调用方
    身份捕获（`SessionOwnerIds.currentSessionOwnerId()`）显式传入；`MessageService` 新增
    owner 显式重载（对照 Go `MessageSearchParams.OwnerID`，不再依赖线程上下文）；
    映射 `MessageSearchGroupItem → ExchangeView`（createdAt→LocalDate，零值 0001-01-01）。
  - `search_memory`：**闸门回收**——Go L956-962 是「先摘再按 `MemoryAvailable`
    挂回」，Java 此前只摘不挂（记忆开着也永远没有该工具）；已按 Go 补
    `memoryService.memoryAvailable()` 条件挂回 + go 原文日志。
  - `database_query`：`SqlQueryExecutor` 走 JdbcTemplate 行扫描（列名有序 + 值类型约定：
    整型→Long、byte[]→UTF-8 String、numeric→BigDecimal、uuid/时间→toString）；
    tenant 供应商取 `TenantContext.currentTenantId()`（SQL 注入由工具侧 `validateAndSecureSQL`
    完成，adapter 只执行）。
  - **A/B 抓回**：`database_query` 与 `search_conversations` 的 schema 字面量与
    Go 输出不一致（缺 `additionalProperties`、键序/字段序非字母序）——均已按 Go 实录修正；
    `search_memory` 的 schema 是 Go **手写字面量**（非 GenerateSchema），Java 逐字保留，
    待记忆开启的部署再用 A/B 复核。
  - **验证**：A/B 双端同注册 **7 件**（KB 五件 + conversations + database_query，
    `search_memory` 因双方记忆闸门同判为关而都不注册）→ **tools 段 14285 字节逐字节一致**；
    agent 362 / session 349 全绿；新增映射钉（conversations/memory）+ H2 行扫描用例。
  - **遗留**：`data_schema`（需 ScopeAuthorizer 装配）+ wiki 10 件 + web_search/web_fetch
    （Java 无工具类，属新翻）= 切片 2b；外层键序/messages/temperature 三项残留照旧。

- **agent 工具接线·切片 2b（2026-09-23，data_schema 落地）**：`data_schema` 接进
  `AgentToolBackends.createTool`——KnowledgeLookup/ChunkLister 走
  `KnowledgeService.getKnowledgeByIdOnly`（拿 tenant）+ `ChunkRepository.listPagedChunksByKnowledgeId`
  （text 语义同 Go：page{1,100}、chunkTypes=[table_summary,table_column]、enabled）；
  `searchTargets != null` 时挂 `SearchAuth.authorizeKnowledgeInSearchTargets` 授权器
  （对照 Go 的 scopeEnforced + WithSearchTargets）。schema 字面量 A/B 抓回键序差（已按实录修）。
  **验证**：A/B 双端同注册 8 件 → tools 段 14675 字节逐字节一致；agent 362 / session 349 全绿；
  接线钉新增 `data_schema`。**仍待切片 2c**：wiki 10 件（`WikiSupport.WikiPages` 适配器
  14 方法 + scopes/routes + hasWikiKb 门控，验收需 wiki KB 夹具）与 `web_search`/`web_fetch`
  （Java 缺件，属新翻）。

- **agent 工具接线·切片 2c（2026-09-23，wiki 十件落地；A/B 抓回「Go 出站体每层键序」根因）**：
  在 `AgentToolBackends` 上补 `createWikiTool`（对照 `agent_service.go` L1093-1116 十个构造点：
  `wiki_read_page`/`wiki_search` 收 `wikiScopes`+`routes`+tagsFetcher，其余八件收扁平
  `wikiKBIDs`——Go 的这处不对称是**有意的**：`NewWikiScopesFromKBIDs` 会丢掉 doc/tag 窄化）
  与 `wikiPages()` 适配器（`WikiSupport.WikiPages` 14 方法 → `wiki.service.WikiPageService`）。
  - **接缝契约翻译三处**：① Go 的 `repository.ErrWikiPageNotFound` 在 Java 侧是
    `WikiPageNotFoundException`（repository 抛，不是返回 nil），而接缝约定「null = 页不存在」
    ——`getPageBySlug` 单独吞掉它，其余异常照抛（否则 `resolveUniqueWikiPage` 会把
    「本 KB 没有这页」当致命错误，多 KB 扫描直接断）；② Go 的
    `types.WithWikiEditSource(ctx, WikiEditSourceAgent)` → `WikiEditContext.callWith/runWith`
    （service 落库时读 ThreadLocal）；③ Go 的 `time.Time` 进 `json.MarshalIndent` 是
    RFC3339Nano 文本，`IssueView` 以字符串承载 → `goTimeText`：**按 UTC 渲染**（GORM 读
    timestamptz 得 UTC location）**且尾零连小数点一起裁**（`ISO_OFFSET_DATE_TIME` 会补齐到
    3/6/9 位，`.100` vs Go 的 `.1`）。
  - **另一处必翻译**：Go 建页只走字段字面量（`wiki_write_page.go:199`、`wiki_rename_page.go:94`），
    `ID` 恒为空串 → `CreatePage` 生成新 UUID；Java 的 rename 走 `PageView.copy()` 会带上**旧页
    ID**，接缝不清掉就跟尚未删除的旧页撞主键。已在 `wikiPages().createPage` 里 `setId(null)`。
  - **门控补齐**：`registerTools` 此前只 `hasWikiKb = !wikiKbIds.isEmpty()`，没做 Go L886-895 的
    「dedup → `NewWikiScopesFromSearchTargets` → **用 scope 结果重建 wikiKBIDs**」；现在
    `hasWikiKb` 按窄化后的清单判定（畸形空 target 不成整库授权 → 该 KB 也不挂 wiki 工具），
    并按 Go L870 一个引擎一个 `WikiRouteResolver` 实例共享给十件。
  - **A/B 抓回的根因（本批最大一处，且解释了前三个切片逐个修的键序）**：Go 的整个出站 chat
    请求体是经 **map** 序列化的，`encoding/json` 对 map key 一律按字节序输出，所以
    **每一层对象**都是字母序——实录证据：顶层 `max_completion_tokens/messages/model/
    parallel_tool_calls/prompt_cache_key/stream/stream_options/tools`、messages 元素
    `content/role`、工具 schema `properties/required/type`（Java 全是插入序）。
    十件 wiki schema 的**内容**与 Go 逐字段相等，只差键序 → 说明此前「手改字面量键序」的
    路子治标不治本。改在 `RemoteApiChat.goSorted()`（`Outbound.bodyBytes()` 序列化前递归重排，
    比较器用 UTF-8 字节 = Go 序）一处收口：**同时清掉备案残留「外层请求体键序」**，
    messages 元素的 `role/content` 也随之外对齐。
  - **验证**：新增 `scripts/ab-tools-wiki.sh`（双端 stub：同 agent、各自建会话跑 agent-chat，
    从 `STUB_DUMP_DIR` 落盘里挑首个带 tools 的请求体对拍）——修前报十件全差、修后
    **同注册 11 件 + tools 段 10692 字节逐字节一致**，顶层键序与 Go 完全一致
    （只剩 `temperature` 一项）；回归 wiki 554 / agent 362 / session 361 / llm.chat 155 全绿，
    新增 `AgentToolBackendsWikiTest`（H2 真 service）+ `RemoteApiChatTest.outboundKeysAreSortedLikeGoMap`。
    夹具已全退（夹具 KB 的 `wiki_enabled`、temp agent `cd03be45-…-wiki`、rerank stub 行
    `ab0c0000-…-ab01`、六个临时会话）。
  - **残留（新增备案）**：① `wiki_read_issue` 结果里 `suspected_knowledge_ids` 空值——Go 的
    nil `StringArray` 经 jsonb `null` 往返仍是 nil → 渲染 `null`，Java 的 `IssueView` setter
    把 null 归成空表 → 渲染 `[]`（工具**结果**字节差异，Go 迁移 000037 该列无 DEFAULT，
    `wiki_flag_issue` 不带该参数时落的是 jsonb 'null'）；② agent 建的 wiki 页 `tenant_id=0`
    （Go `wiki_write_page.go` 的 struct 字面量不带 TenantID，只 rename 带；Java 逐字对齐——
    wiki 读路径按 `knowledge_base_id+slug` 过滤、不看 tenant，所以两端同样无感，但
    `WikiPageMapper` 唯一按 tenant 过滤的那条查询会漏掉这些行）。照旧残留：messages 内容、
    `temperature`、`SkillInstallPipelineImpl:1123`。**下一步 = web_search/web_fetch**（Java 缺件，新翻）。

- **存储写字节面 + ArtifactCollector 生产装配（2026-09-24，§0.-2 剩余 1 收口）**：
  Go `file/local.go SaveBytes/DeleteFile` + `file/resource_catalog.go`（装饰器全文）+
  `resource.go Register/Bind/MarkDeleted` 全量翻译：
  - **写面分层**：`LocalFileContentService` 补 SaveBytes（SafeFileName →
    baseDir/{tenant}/exports/ → `<base>_<纳秒><ext>` 唯一名 → `local://` 引用；
    temp 对 local 无效——Go 注释原文）/ DeleteFile；新接口
    `WritableFileContentService extends FileContentService`（Go FileService 写半边）；
    `StorageFileResolver` 的 DecoratedFileService 升级实现它：SaveBytes = 物理落盘 →
    SHA-256 → `catalog.register`（失败尽力回删）→ resource:// 手柄；DeleteFile =
    物理删 + `catalog.markDeleted` 软删。`globalFileService` 返回类型随之放宽。
  - **ResourceCatalogService** 补 register（location_hash 命中复用手柄 / handle 4 次
    撞重试 / provider scheme 白名单）/ bind（owner 校验 + relation 缺省 attachment）/
    markDeleted；`ResourceRepository` 补 createResource（id/时间戳代码侧生成——H2 无
    列默认）/ createBinding（**H2 无 ON CONFLICT 语法** → 按"插入失败即已绑定"吞
    unique/duplicate/primary key 冲突）/ markDeleted。
  - **ArtifactCollectorWiring**（session.service @Component）：对照 container.go
    Provide(NewArtifactCollectorFromSandboxManager) + initFileService。结构差异备案：
    Go collector 是**进程单例**（process-wide manager 断言成 source，会话绑定在
    manager 内部）；Java bound manager 按回合解析 → collector 按回合构造，
    **沙箱解析惰性到首次列文件**（回合开始绝不提前 provisioning——首版写成立即
    解析，走查自查改掉）。
  - **drain 点接线**（agent_stream_handler.go L726-757）：AgentStreamBridge 完成段、
    clarify 之前——CollectWithNotify（notify = artifacts_pending 事件补齐，事件 ID
    `artifacts-pending-<epochMilli>`）→ setArtifacts + rewriteArtifactReferences →
    ReferencedHistory → clarifyArtifactVersions(previous)。collector 未装配（旧构造
    器）/source 解析失败时行为与 Go nil/降级分支同形。
  - **AgentWebPages 生产接线**：Store = 装饰服务（SaveBytes/GetFile 的 resource://
    链），Binding = catalog::bind——§0.-8 残留 ① 收口。
  - **坑**：resolvePath 返回的是 **provider 作用域路径**（`local://…`），不是文件系统
    路径——归一必须走装饰服务/normalizePathForBase，直接 `Path.of` 会拿到
    `local:/…`（单斜杠）NoSuchFile。location hash 的输入也是 local:// 原形态，
    重复注册复用手柄时别先剥 scheme。
  - **验证**：StorageWriteFaceContractTest 6 + AgentWebPagesStoreTest 4（写读回环 /
    同路径复用手柄 / 软删 / 绑定行 / 消息缺失与租户不符 fail-closed / 8MB 上限文案）；
    storage 55 / session 366 全绿。真实 Docker 的 drain 全链 E2E 随 §0.-2 剩余 2
    （真实 LLM install E2E）顺带验证。

- **agent 工具接线·切片 2d（2026-09-23，web_search/web_fetch 落地——registerTools「Unknown tool」清零）**：
  Go `web_search.go`/`web_fetch.go` 全文新翻（Java 无工具类，属新翻而非接线）：
  `WebFetchTool`（批执行 1..8、两段式 offset-0 先行再续读、pageFlight 单飞合流、
  LRU 快照缓存 8 页、snapshot_expired/等待超时文案、输出预算分摊、双重编码 items
  解包、canonicalFetchURL/normalizeGitHubURL、WithPageSource 接缝）+
  `WebSearchTool`（schema/描述按 Go GenerateSchema 实录逐字节钉、`%d` 注入
  maxResults（≤0→10、clamp 20）、count/country/freshness 校验文案、租户配置装配期
  捕获 + tenantID 执行期读、结果过滤 canonical 去重、content=true 前 3 页并行抓取
  5000 chars/15s 共享预算、applySearchPageFetch 的 Go map 恒写键语义）。
  - **接线**：`registerTools` switch 两件（agent_service.go L1071-1082）+
    `AgentToolBackends.createWebTool`（webSearchBackend 桥
    `WebSearchService.search(tenantId,...)`；`loadTenantWebSearchConfig` 经
    TenantService 落 `EffectiveWebSearchConfig` 打底拷贝，provider/apiKey/blacklist/
    proxyUrl 全量带）。
  - **registerWebPageFiles 补齐**（agent_web_pages.go L108-159）：web_search 共享
    会话 web_fetch（WithPageReader，注意 Go 是**另一次** GetTool(web_search)，不是
    对 fetch 自身 instanceof）；read_file 缺席时补注册 `NewReadFileTool(nil)` +
    WithWebPages——**A/B 抓回 Go 在 web 启用时经此注册 read_file（web:// 读取范围
    进描述）**，Java 缺这段时 tools 段恒差一件。存储写字节面
    （FileService.SaveBytes/ResourceCatalog.Bind 生产实现）未翻译 →
    `AgentWebPages`（session.service，同时实现 ReadFileTool/WebFetchTool 两个同形
    WebPageSource 接口）的 Store/Binding 接缝缺省 null = Go save-failure 分支
    （storageError 文案逐字对照），页仍进本轮缓存；与 ArtifactCollector 生产装配
    共栈，随存储写字节面批（HANDOFF §0.-2 剩余 1）落地。
  - **A/B 抓回并修复四处**：
    ① `SequentialThinkingTool` 描述**两处行尾双空格**（Go sequentialthinking.go
    L52/L58 的 `  ` 行）被 Java 文本块剥掉——`\s\s` 转义修复；坑：`\s` 行要放在
    **公共缩进**上，放浅一级会多算 2 空格（首差实测 4 vs 2）。4.6b 以来的潜伏差异，
    2c 的 wiki A/B 无 thinking 工具未覆盖。
    ② registerWebPageFiles 的 Go `GetTool` err 检查翻成 Java `getTool` 后会抛
    `ToolNotFoundException` 且未捕获 → web 开启的 agent QA 恒败（Java 必须
    try/catch 复刻 err 分支）。
    ③ `completeStore` 漏了 Go `delete(t.inflight, rawURL)` 的等价摘除——被 LRU
    逐出的页读到过期 flight 的旧快照、不重新下载（单测
    `fetchCachesPerRunAndEvictsLruBeyond8` 抓回）。
    ④ WebFetchTool schema 字面量内层 `additionalProperties:false` 后多写一个
    `}`——根对象提前闭合，Jackson readTree 对 trailing data **静默**，根只剩
    type/properties 两键。教训：schema 字面量测试要同时断言「readTree 成功 + 根
    键数」，只断言两份字符串相等会让**同样写错的两份**互相印证
    （`schemasMatchGoRecording` 首轮抓回）。
  - **线格式分路径发现（备案，未改）**：Go 出站请求体分 SDK 结构体序 vs 裸 HTTP
    map 序（`buildOutbound` 的 useRawHTTP）：model 参数带 `"provider":"openai"`
    → prompt-cache sendKey 策略 → `applyPromptCacheToJSONBody` 把 body 改写成
    map（**字母序 + prompt_cache_key**）；provider 键缺失（unknown）→ SDK 结构体
    序（type-first、chat_template_kwargs/enable_thinking 尾随；deepseek 名字走
    chat_template_kwargs 亦结构体序）。Java `goSorted` 恒 map 序，只覆盖 map 路径
    ——4.6d 的「Java 单路径合并」在该路径族上不成立，随 LLM 批收口。A/B 夹具模型
    必须带 `"provider":"openai"` 才能两端同形（ab-tools-web.sh 注释备案）。
  - **验证**：新增 `scripts/ab-tools-web.sh`（租户 10009 夹具：建 web 临时 agent
    「allowed_tools 显式四件 + web_search_max_results=7 + thinking:true」、种子
    provider=openai 模型行指 stub、跑完全删）——**双端两轮 tools 段 18825 字节
    逐字节一致**（5 件：read_file/thinking/todo_write/web_fetch/web_search；描述
    「Returns up to 7 results」钉住 %d 注入）。新增 `WebToolsRecordingTest` 18 用例
    （schema/描述字节钉、批执行/续读/缓存逐出/去重/预算不足/双编解包/空内容/
    snapshot_expired、search 校验文案/整形/去重/content 抓取页键语义/RFC3339）。
    回归 agent 380 / session 362 全绿。夹具全退（temp agent、种子模型行、12 个
    测试会话）。
  - **残留**：① AgentWebPages 生产 Store/Binding（存储写字节面）；② Go SDK 结构体
    路径键序 Java 未模拟（见上分路径备案）；③ `PluginSearch.effectiveWebSearchConfig`
    漏拷 apiKey（Go EffectiveWebSearchConfig 全量拷贝——chatpipeline 既有面，非本
    切片，备案）；④ 照旧：messages 内容、`temperature`、`SkillInstallPipelineImpl:1123`。

- **agent 出站体 messages/temperature 保真 + 全 body A/B 升级（2026-09-24，切片 2 系列残留收口）**：
  2a~2c 反复备案的「messages 内容差 + temperature 差」本轮复现并收口：
  - **复现升级**：`ab-tools-web.sh` 从「只比 tools 段」升级为**全请求体对拍**
    （标题轮 + tools 轮，sort_keys + 掩 UUID/时间戳）。复现用夹具必须**带 KB +
    rerank + temperature**——无 KB 的 web 夹具在 9-23 走查批之后已经全匹配，
    2c 时代的残留只在带 KB 配置上才出现。
  - **差异一：RAG base 模板缺失（862 字符）**。Go 的 BuildSystemPromptSections
    按 KB 有无选模板：有 KB → `GetProgressiveRAGSystemPrompt`（vendored
    agent_system_prompt.yaml 的 rag 模式），无 KB → pure。Java 引擎的
    `setAppConfig` 塞的是**空 TemplatesConfig** → rag 模板渲染为空 → system
    首段变成 steering_guidance。修复：`loadAgentSystemPromptTemplates()` 装载
    vendored yaml（纯模板路径无回归）。
  - **差异二：kbInfos 占位**。Go resolveKBAndDocInfos → getKnowledgeBaseInfos
    加载真实 KB 行（名称/类型/描述/docCount/最近文档 top10/capabilities）；Java
    此前全占位（name=别名 b1、description 空、capabilities 空）→ runtime_context
    的 knowledge_base 块缺 name/description/capabilities。修复：翻译
    knowledgeBaseScopesForPrompt（KnowledgeBases 优先，否则 SearchTargets 全集 +
    租户映射）+ getKnowledgeBaseInfos（IsTemporary 跳过；FAQ 库 ListFAQEntries、
    否则 ListPagedKnowledgeByKnowledgeBaseID(completed)；单库失败回落 ID-only）+
    kbRetrievalCapabilities（wiki/chunks）+ getSelectedDocumentInfos（@ 提及文档）。
  - **差异三：temperature**。Go config 无 temperature → 零值 0 → openai-go
    omitempty **整键省略**；Java 的 `asDouble(0.7)` 缺省多发一键。修复：缺省改 0
    （RemoteApiChat 对 temperature==0 本就不出键）。内建 agent 的 0.7 来自
    agent_type_presets.yaml **显式配置**（两端 yaml 同源），不靠 handler 缺省。
  - **验证**：带 KB 夹具（ab-kb + rerank stub + temperature 0.7 + deepseek 模型行）
    双端全 body **两轮逐字节一致**（tools 轮 30813 字节 + 标题轮 449 字节）；无 KB
    web 夹具同样两轮 MATCH。回归 agent 380 / session 366 / chatpipeline 43 全绿。
    夹具全退（ab-kbagent、种子模型行、ab-kb、测试会话）。
  - **顺带修复**：`PluginSearch.effectiveWebSearchConfig` 漏拷 apiKey（Go 全量
    拷贝；deprecated provider 回落路径消费）——c1a0528 单独提交。
  - **仍开放**：Go SDK 结构体序 vs map 序的分路径（provider 键缺失时顶层键序不同，
    LLM 批收口）；AgentWebPages 真实 Docker drain E2E（随 install E2E 批）。

- **LLM 分路径键序收口（2026-09-24，§0.-8/§0.-10「SDK vs map 序」备案关闭）**：
  RemoteApiChat 出站序列化按 Go buildOutbound 的**两条真实路径**分流：
  - **map 改写路径**（prompt-cache sendKey/sendCacheControl 策略命中 + sessionId 在位
    → Go `applyPromptCacheToJSONBody` 把 body 转 map）：每层对象按键字节序
    （goSorted，2c 的既有行为，保留）；
  - **SDK 结构体直出路径**（provider 未配置/未知 → 空缓存策略；以及 thinking 包装
    结构体路径）：openai-go v1.41.2 **结构体声明序**（structSorted 新增）。字段序表
    对照 `chat.go` L263-328（顶层）/ChatCompletionMessage/Tool/FunctionDefinition/
    StreamOptions/ResponseFormat/ToolCall/FunctionCall 录入；未知键（Qwen 包装的
    enable_thinking 等）按插入序**尾随**（Go 包装结构体把扩展字段声明在嵌入基座
    之后）；工具 parameters 子树**原样保留**（Go 是 json.Marshaler，jsonschema 库
    结构体序=Java 字面量录入序）；metadata/logit_bias 等 Go map 字段理论上应字母
    序，单键场景与插入序一致，从简未改（备案）。
  - **Outbound** 增加 cacheRewritten 标记，bodyBytes 按 `cacheRewritten ? goSorted :
    structSorted` 分流；rawPath（finish_reason 分野，§0.0 第七处）不受影响。
  - **验证**：结构体路径夹具（md-chat-think，无 provider 键 → 未知 provider → 空缓存
    策略 + enableThinking 包装）双端全 body 两轮 0 差异，Go 顶层实测
    `model/messages/max_tokens/stream/tools/stream_options/parallel_tool_calls/
    enable_thinking`（包装字段尾随）、tools 元素 type 先行——Java 逐字节跟随；
    map 路径夹具（provider=openai）同样两轮 MATCH（30813+449 字节）——**两条路径
    均闭合**。RemoteApiChatTest 键序测试重写为双路径断言（structPath 跟随结构体序 +
    parameters 保留录入序；mapPath 保持字母序）；llm.chat 156 / agent 380 /
    chatpipeline 43 全绿。
  - **运维注意**：本轮 Go server 两次在后台被优雅关闭（setsid 缺失的进程组信号），
    macOS 无 setsid——长跑用 `nohup ... &` 后在同一次调用内完成验证，或挂 launchd。

- **真实 Docker 产物排水 E2E + 引用扫描真缺陷修复（2026-09-24，§0.-2 剩余 2 的验证缺口关闭）**：
  - **新 IT**：`session.service/ArtifactDrainDockerIT`（门控
    `WEKNORA_SANDBOX_DOCKER_IT=true`，@SpringBootTest + OrbStack）——真容器
    `/workspace/output` 文件 → 生产绑定形态（docker + 容器 id，templateId=镜像 tag）
    的 SessionBoundManager → SessionBoundArtifactSource → ArtifactCollector →
    装饰存储（resource:// 手柄 + 资源注册 + artifact 绑定行）→ 磁盘回环；去重
    （全部已知 = **空切片**非 nil，Go `make(0,0)` 语义）与引用历史回带。跑法：
    `DOCKER_HOST=unix:///$HOME/.orbstack/run/docker.sock WEKNORA_SANDBOX_DOCKER_IT=true
    WEKNORA_SANDBOX_DOCKER_ENABLED=true ./gradlew :server:test --tests
    "com.ragagent.session.service.ArtifactDrainDockerIT"`。
  - **IT 抓回一个真生产缺陷**：`ArtifactCollector.ResourceReferences` 的引用扫描
    正则是 `[A-Za-z0-9]{22}`，而 Go（types/resource.go L164）是
    `[A-Za-z0-9_-]{22}`——handle 是 base64url，**约一半含 -/_**，旧实现会让答案
    文本引用这类产物时 ReferencedHistory 静默失效；且缺 Go L181-185 的边界检查
    （更长连串拒绝截断前缀）。已修（字符集 + 边界 + 去重），补纯单测
    `resourceReferenceScanHandlesBase64UrlAlphabetAndBoundary`（22/23/21 字符、
    基本square字符集、去重、空文本）。
  - **坑**：①22 字符手写字面量极易数错（24 个 a 写成 22）——测试用 `"a".repeat(22)`
    生成；②装饰存储的 `resolvePath().physicalPath()` 是 `local://…` provider 作用域
    路径，测试读盘要 `baseDir.resolve(strip前缀)`，直接 `Path.of` 得到 `local:/…`；
    ③IT 的 `@EnabledIfEnvironmentVariable` 门控在改写类时**必须保留**——丢失会让
    无 Docker 的普通批次直接 initializationError。
  - **全量分批回归（本日基线）**：B1a agent 380 + chatpipeline 43 / B1b
    common+event+apikey+audit+auth 458 / B2 knowledge+wiki+model+mcp+modelcontext
    1059 / B3 memory+datasource+stream+im+org+embedding+vectorstore+rerank 1378 /
    B4 sandbox+browserskill+embed+system+websearch+webfetch+searchutil+tracing+
    favorite+evaluation+storageurl+root 440 / session 369 / storage 55 / llm.chat
    156——**共 ~4,100 用例全绿，0 失败**。

- **API 文档站点（2026-09-24）**：`scripts/generate-api-docs.py` 解析控制器注解
  （复用 route-recon 的 parse_java，经 importlib 避免正则漂移）+ WebConfig 的
  RBAC 规则 + APIKeyRoutePolicies 策略（含策略变量展开），输出
  `docs/api/{index.html,api-docs.json}`——**452 条路由 / 45 个域分组**。
  index.html 自包含（数据内嵌，双击 file:// 即开，无外部依赖）：按域导航、
  正则搜索、方法色标、RBAC 门槛与 API-Key 策略标签、处理器源码定位。
  路由变更后重跑 `python3 scripts/generate-api-docs.py` 重新生成。
  注意：解析含 route-recon 已备案的启发式局限（类前缀漏解析的少量路由会归到
  other/尾部形态）；domain 标签表在脚本头部的 DOMAIN_LABELS 维护。

- **系统架构交互图（2026-09-24，Archify）**：安装 Agent Skill `archify`
  （github.com/tt-a1i/archify，MIT，`~/.zcode/skills/archify`）后按其流程生成
  `docs/architecture.html`（自包含交互 HTML：3 个引导视图 / 双区域边界 / 明暗主题
  / PNG·SVG 导出），候选源在 `docs/architecture.json`，改架构后重跑：
  `node ~/.zcode/skills/archify/bin/archify.mjs deliver architecture
  docs/architecture.json docs/architecture.html --quality showcase`。
  生成过程按 SKILL 的 showcase 验收走——首轮 7 条布局诊断（标签互撞/端点方向
  不实），按逐条 suggestedFixes 修到 0 错误 + 9/9 artifact checks。

- **文档门户细化（2026-09-25）**：①`api/` 与 `architecture.*` 归位 `docs/site/`；
  ②侧栏独立滚动（sticky + overflow-y）；③API 文档新增**入参/响应提取**：
  生成器解析控制器方法签名（@PathVariable/@RequestParam/@RequestBody）+ 方法体
  `readValue(rawBody, X.class)` 与 bind/parse helper 二级追踪 + 全仓类型字段索引
  （POJO 字段与 record 组件，@JsonProperty wire 名优先）→ 每条路由输出路径参数/
  Query 参数/请求体绑定与字段表/响应声明类型与字段表（447/452 有入节数据；129/154
  的 body 是 raw JSON 手工绑定，如实标注「控制器内解析」）；api/index.html 渲染
  入参/响应小节；④site 功能模块扩写（22 个模块的功能清单/代表端点/技术要点）。
  坑：解析器正则 `(\-/api…)` 笔误（`\-` 是字面减号）导致类前缀全部失配、routes
  键丢失——用 `importlib` 直跑 `file_routes` 单文件定位，别靠整仓输出猜。

- **外部向量店 driver 的 Go 侧缺陷三则（2026-09-25 W5γ4.1~γ4.3，ES v7/v8）**：按"逐字照抄"落地时
  复刻了三处 Go 缺陷，用户指示**发现的问题必须修复**（不得把 bug 复制进 Java）——已改"有意偏离 + 备案"
  （HANDOFF §0.-18）：① v7 `CopyIndices` 的 `saveCopiedIndices` 里 `embeddingMap` 是新建空 map
  （收集的向量被丢弃）→ 修：向量随 `CopiedHit(indexInfo, embedding)` 回来、按目标 SourceID 为键；
  ② v7 `processHit` 恒传 `MatchTypeKeywords`（向量结果也标 1）→ 修：按实际检索类型给；
  ③ v8 `CopyIndices` 键用目标 chunkID 而 `ToDBVectorEmbedding` 按 SourceID 查表（生成问题取不到、
  同 chunk 互相覆盖）→ 修：键改目标 SourceID。**坑**：查表语义由 `structs.go` 定死为"按 SourceID"，
  修**键**比改查表小且两版一致；上游若修，反向同步时别把这三处再抄回来。

- **外部向量店 driver 的 Go 侧缺陷·第 4 例（2026-09-25 W5γ4.4）**：`KeywordsVectorHybridRetrieveEngineService`
  的 `EstimateStorageSize` 里占位向量以 `ChunkID` 为键，而 `ToDBVectorEmbedding` 按 `SourceID`
  查表 → 生成问题（`<chunk>-<qid>`）估不到向量字节（低估）。修：占位向量按 SourceID 为键
  （与 §0.-18 ①②③ 同法：**键与查表语义必须一致**）。**坑**：这类"键/查表错配"是 Go 侧的高频形态，
  翻译时看到 `embeddingMap[...] = ...` 就要回头核对查表用的是 `SourceID` 还是 `ChunkID`。

- **接线批第 2 步（2026-09-25 W5γ4.5，注册表 + 复合引擎 + 工厂）——七条备案与坑**：

  1. **singleflight 的时序必须与 Go 一致**：先 `flights.remove(key, mine)` 再 `publish(...)`。
     反过来的话，后到的调用方会读到一个"已算出结果但仍挂在表里"的航班——白等一次（Go 的
     `doCall` 也是先删 key 再向 chans 交付）。`shared` 标志要在 `publish` **之前**采样
     （`waiters>0`），publish 之后 `dups` 不再变。
  2. **移植 Go 的测试钩子要连调用点一起移**：`onFlightJoin` / `flightObserver` 是两个纯测试口
     （生产恒 nil），Go 在 `doChanJoin` 里调 `onFlightJoin()`。首版只把**字段**搬进了 Java 却没在
     挂载点调用 → 「16 个调用方都应挂上航班」的 latch 超时失败。**钩子的语义就是"在哪一刻触发"**，
     只搬字段等于没搬。修法：把钩子当 `doChan` 的参数传进去，leader 在**开跑构建之前**、等待者在
     `waiters++` 之后各触发一次。
  3. **构建超时的等价物**：Go 是 `context.WithTimeout(..., EngineBuildTimeout=10s)` 让工厂自己
     感知 deadline。Java 无 ctx → 在虚拟线程上跑构建、`CompletableFuture.get(10s)`，超时
     `interrupt` 并走"建失败 → 冷却 → 可重试哨兵"。语义等价（两条路都归到 UNAVAILABLE + 冷却），
     但**不要在测试里把超时设到与断言竞争的量级**（本仓留了包内构造器注入 buildTimeout/cooldown）。
  4. **Java 的 "panic" 是 `Error`，不是 `RuntimeException`**：Go 的 `recover()` 捕一切，且 panic
     路径**绕过** `markBuildFailed`（不设冷却）。Java 侧 `RuntimeException` 已被"工厂返回 error"
     那条分支吃掉并进冷却，因此外层 `catch (Throwable)` 的判定是：**`RetrieveEngineException`
     原样重抛，其余（含 `Error`）折成 UNAVAILABLE 且不进冷却** —— 与 Go 的两条路径一一对应。
  5. **薄口撤并**：KV 服务原来自造了一个三方法薄口 `Embedder`（`embed`/`batchEmbed`/`dimensions`），
     与全仓 `com.ragagent.embedding.Embedder`（`embed`/`batchEmbed`/`getModelName`/`getDimensions`/
     `getModelID`）**同名不同物**。端口一落地就必须二选一：选**统一口**（照 Go——端口收的就是同一个
     `embedding.Embedder`）。收益是第 3 步的适配器可直接接 `ModelRuntimeFactory.getEmbeddingModel`；
     代价是测试桩补两个 getter，且桩里 `batchEmbed` **不能再 `throws Exception`**（接口未声明）→
     失败字段类型要改成 `RuntimeException`。
  6. **确定性优先于 Go 的随机序（做 A/B 时注意）**：`maps.Values`（engineInfos 的顺序）、
     并发收集（结果顺序）、`common.Deduplicate`（map 收集后 `maps.Values`）三处 Go 都是**不确定序**；
     本仓一律保序（引擎序 / 入参序 / 首次出现序）。**多引擎共同支持同一检索类型时 Go 选谁是不确定的**——
     这种场景不要当作逐字节锚点。
  7. **`EffectiveEngines` 抽取的边界**：先确认 `EngineParams`/`effectiveEngines` **只在
     HybridSearchService 内部被引用**（无测试、无外部调用）才动手；抽取后 HybridSearchService
     只删不增、行为不变，把"会动 golden 锁定读路径"的风险全部留给第 4 步（引擎路由）。

## W5γ4.6：接线批第 3/4 步 + normalizer + 走查评审批（2026-09-25）

1. **Java 源文件里的 NUL 字节字面量**（KnowledgeBaseService 列表过滤哨兵 `"\0skip"`）：
   `file` 判成 `data`、grep 当二进制静默跳过、Edit 工具直接拒绝写入——"文件读得到改不了"
   先查控制字符（`LC_ALL=C grep -anP '[\x00-\x08]'`）。修复顺手把哨兵写法改为循环内
   `continue`，不再污染源文件。
2. **Long 引用比较复发**（@mention 收敛，`agentRow.getTenantId() != session.getTenantId()`）：
   两侧都是装箱 Long，租户 10002 超出缓存区间恒不等——own agent 被误判为共享 agent、
   mention 范围被错误收敛。约定 §5 第 6 条的第 N 次复发：**跨方法传递的租户 id 一律
   `Objects.equals`**，包括"看起来同源"的两个实体字段。
3. **嵌入 provider 的调用间非确定性是检索 A/B 的噪声下限**：dashscope 对同一查询文本
   两次调用返回末位有差异的 float32（~1e-6），导致 vector 命中分数、乃至距离跨过
   `distance <= 1-threshold` 边界的**条数**都可能在两次请求间抖动。判据：同侧连跑两次
   亦漂移、跨端分数集合在两次运行间重合——这时差异是 provider 噪声而非翻译缺陷
   （检索链路的确定性 A/B 要么 stub 嵌入、要么只对齐"公共前缀 + 分数集合"）。
4. **bootRun 被 kill 后 gradle 守护进程会记着那个失败的 task**：下一次
   `java-server-up.sh` 的 bootRun 秒退（日志里 `BUILD FAILED in 1h 31m 32s` 是**被杀的
   旧任务**的时长，不是新任务的）——readiness 探针打到垂死的旧进程造成"起来了"的假象。
   处置：重启前 `./gradlew --stop`（或确认守护进程空闲），起完以 login 200 为准再验。
5. **工厂哨兵 → HTTP 的映射**（storegroup 的 classifyFactoryError）：FORBIDDEN→2200、
   NOT_FOUND/UNAVAILABLE→2201、取消→2201（"绑定没问题，重试可能成功"）、其余原样
   （handler 折 500 原文）；KB create 的 validateVectorStoreBinding 另有"畸形 UUID 快拒
   2200"防枚举预言。store UUID 只进结构化日志（LogSanitizer）。
6. **GORM `default:true` 的 Create 省略语义**（pg CopyIndices 照抄）：目标行 IsEnabled
   零值 false 被 default tag 省略 → DB 默认 true 生效；Java 侧等价实现 = INSERT 显式
   省略该列。save/batchSave 的显式 is_enabled 路径不受影响（false 在生产不可达，备案）。

## W5γ4.7：写链改道 + 启动恢复（2026-09-25）

1. **绑定/未绑定的切分纪律**：写链改道用"绑定店走引擎、未绑定保持 pg 直连"的网关
   （`KnowledgeVectorWrites`）——golden 锁定的错误形态全在未绑定路径，改道对现有
   契约零扰动；新增绑定行为全部有 Go 原文对应。不要把"未绑定也走 postgres 引擎"
   一起翻译（驱动未配置时语义分叉，且无观测收益）。
2. **KB clone 的向量复制按 Go 是"每个知识一次 CopyIndices"**（含 chunk 新旧 id 映射），
   不是整 KB 一把梭——映射在建 chunk 行时顺手积累（cloneKnowledgeRow 内），
   `Map.of(src.getId(), dstKnowledgeId)` 的 knowledge 映射别和 chunk 映射弄混
   （CopyIndices 的 kbMap 在 chunkMap 之前）。
3. **move 的 reuse_vectors 有两道前置校验**（同店 + 同嵌入模型），校验失败是
   "逐条失败"进进度任务 failures（Go 语义），不是整体 400；跨店文案原文
   "reuse_vectors move across different vector stores is not supported (...); use reparse mode"。
4. **resetPendingTasks 的 wiki 独槽排除**（NOT-EXISTS 子查询）是分布式/Lite 分野之外
   的第三重闸：finalizing 且 pending_subtasks_count=1 且唯一 op 是持久化 wiki ingest
   的行**不复位**——wiki op 独立落库，启动后消费面重建触发器能自然收尾；复位它
   等于把可自愈的行误判为死。
5. **分布式模式（REDIS_ADDR 已配置）刻意不复位知识/摘要行**：asynq 队列持久化 +
   另一副本可能正在执行同一知识，启动钩子无法区分孤儿与未开 span 的积压——
   Go 把这个判定交给 HousekeepingService（span 活动 + 真队列双检查）。同步日志
   两种模式都清，分布式只多加 30 分钟陈旧窗。
6. **JdbcTemplate 的 IN 占位符参数序**：`UPDATE ... SET a=?,b=? WHERE id IN (?,?,?)`
   的绑定数组必须是 [SET 参数..., IN 参数...]——先 SET 后 WHERE（本次实踩：
   ids 放前面导致 Parameter #4 not set）。

## W5γ4.8：检索批 follow-up 清零（知识管家清扫 + move reparse 收尾，2026-09-25）

1. **javadoc 里写 cron 表达式会提前闭合注释**：`{@code 0 */5 * * * *}` 里的 `*/` 就是注释
   结束符——`javac` 从那一行起把类体当顶层内容，报出来的是几十条"class, interface, enum,
   or record expected / illegal character: '\uff1a'"（中文标点被当成非法字符只是**下游症状**）。
   教训：**注释里不要出现 `*/`**（改写为文字描述，或拆成 `*` + `/`）；编译报"中文标点是非法字符"
   时先回头看上文哪个注释提前结束了，别去动中文。
2. **H2 的 `CURRENT_TIMESTAMP - 180 MINUTE` 会炸**：隐式 `INTERVAL MINUTE` 的默认精度不够
   （`Value too long for column "INTERVAL MINUTE": "INTERVAL '180' MINUTE (18)"`）。
   两位数（`- 60 MINUTE`）侥幸能跑，三位数就红。统一改 `DATEADD('MINUTE', -N, CURRENT_TIMESTAMP)`
   ——凡是要播种"很久以前"的测试数据，一律用它。
3. **异步搬移的测试等待条件别拿"状态 ≠ pending"当锚**：reparse 搬移的**前置状态可能是
   completed**，`parse_status != 'pending'` 在跑之前的首轮轮询就成立 → 断言在搬移提交前执行，
   拿到的是源 KB（本次实踩：kb_id 断言随机红）。正确锚是**被改写的那一列本身**（`kb_id == 目标`），
   它与 `parse_status=pending` 是同一条 UPDATE 落库的。另：断言只钉 **worker 不会改写的列**
   （kb_id/embedding_model_id/description/storage_size），入队后的异步解析会把
   parse_status/error_message 改掉。
4. **Go 的清理副本 `cleanup.StorageSize = 0` 是防双扣**：`moveKnowledgeReparse` 先把副本的
   storage_size 置 0 再调 `cleanupKnowledgeResources`——所以清理里那个
   `if knowledge.StorageSize > 0` 分支永不进，存储扣减只走随后的
   `UpdateKnowledgeForTransfer` 的 `storage_used += after - before`。翻译时若"顺手"在清理里
   扣一次，租户用量会**双扣**（还会被负数钳位掩盖成 0）。Java 侧同法：清理不碰用量，
   扣减在行改写处（`tenantStorage.adjustStorageUsed`）。
5. **两处失败安全的相反方向是刻意的**（Go 注释写得很细，别"统一"掉）：
   - span 心跳查询失败 → 当作**全都卡死**（多回收）；
   - `task_pending_ops` 持久闸探测失败 → **推迟本轮全部候选**（少回收）。
   理由：前者最坏是多杀一个已经停摆的行；后者最坏是把活文档误杀成 failed，用户无法恢复，
   而多等一个周期可以。瞬时队列 inspector 探测失败则与前者同向（按仍卡死）。
6. **Go 的 `TaskInspector` 在本仓无实现**：Lite 无 asynq（`SystemAdminController` 的
   noopTaskInspector 即此），故 `HousekeepingService` 的 inspector 缝生产传 `null`——
   照 Go 的 nil-safe 设计，**只关闭瞬时队列这一项检查**，`task_pending_ops` 持久闸独立生效。
   测试用假 inspector 覆盖"队列仍有活""探测失败"两分支（与 Go 的 fakeTaskInspector 同法）。
7. **reuse_vectors 路径长期漏了两件 Go 做的事**（本次顺手补上，避免"照抄不齐"的二次返工）：
   Go 的搬移分支在 `UpdateKnowledgeForTransfer` 里同时写 `parse_status=completed` +
   `error_message=''`，并在搬移前清 `knowledge_tag_relations`（标签是 KB 作用域的）。Java 此前
   只改 kb_id + updated_at，搬走后的文档仍挂着源 KB 的标签、行状态停在原值。
   **排查手法**：把 Go 的 `moveKnowledgeReuseVectors` 尾部逐行对照 Java 的搬行方法，差异一目了然。

## W5γ4.9：OpenSearch k-NN 驱动（2026-09-25）

1. **Go 注释与代码分叉（ensureReady transient 分支）**：Go 的注释声称"Fall through —
   caller still sees this attempt's err"，但代码在 transient 失败时**不写** initErr，
   函数尾部读 initErr 返回 nil——当次调用者拿 nil 后继续操作，以后续操作的
   INDEX_NOT_FOUND 显形。Java 以**代码**为准（不持久化、当次不报错）；别照注释修。
2. **逐维惰性建索引**（与 ES 的"构造即自举"完全不同）：OpenSearch 在构造期只探针
   （版本 + 每节点 k-NN 插件），索引在首个带该 dim 的 Save/Retrieve 时创建
   （`<base>_<dim>` 别名 → `<base>_<dim>_v1` 实体）。测试桩的 HEAD 缺省状态码
   必须给 404，否则别名探针恒"存在"、惰性路径测不到。
3. **min_score 直通**：OpenSearch k-NN 的 COSINESIMIL.scoreTranslation 已把分数映射到
   (1+cos)/2 ∈ [0,1]，调用方 Threshold **原样**传 min_score（不做 1-threshold 之类的
   距离换算——与 pgvector 驱动相反）。
4. **CopyIndices 的向量键是目标 SourceID**（BatchSave 按 SourceID 查 embedding），
   与 ES 驱动的 chunk id 约定**相反**——两个驱动别抄混。同页混合维度会以
   DIMENSION_MISMATCH 拒绝（Go 同）。
5. **翻译手误两例（测试抓回）**：move 的 filter 元素缺 `{"term":…}` 包装、第二个 term
   的键装进了第一个 map——逐请求断言 wire 形状的 stub 测试是抓这类手误的唯一防线
   （H2/单测编译都发现不了）。
6. **env-path 的 SSRF 差异**：OpenSearch 的客户端构造（NewOpenSearchClient）内置
   无条件 SSRF 校验——env-path 也过；ES 的 env-path 用裸 SDK 客户端不过。同是
   env-store 注册，两个驱动的安全面不同，接线时别对齐。

## W5γ4.10：Doris 检索引擎（2026-09-25）

1. **SQL 面用"执行口"缝，不直连 JDBC**：Doris 方言（`ARRAY<FLOAT>` 字面量、`MATCH_ANY`、
   `SHOW INDEX` 的列序随小版本漂移、`inner_product_approximate`）没有本地等价库可跑，
   故抽 `DorisSqlExecutor`（execute/query/scalar + Row 视图），生产实现是 Hikari 池 + MySQL
   协议，测试实现是记账假执行器——SQL 文本、参数序、扫描分支因此都能逐句钉住。
   这是本仓第一次把"店"的 SQL 面做成可测缝；Qdrant/Milvus 等后续店可复用同一手法。
2. **DDL 里的双制表符是 Go 原文形状，不是笔误**：`schema.go` 的模板里 `PROPERTIES(\n\t%s\n)`
   自带一个制表符，`properties` 串自己又带一个 → 输出 `\t\t"replication_num"=...`；
   `"metric_type"` 行也是两个制表符。翻译时若"顺手对齐缩进"，DDL 字节就与 Go 分叉
   （用 `sed -n '146,178p' schema.go | cat -et` 可复核）。
3. **`Publish Timeout` 是成功状态**：Doris Stream Load 的 `Status` 只有
   `Success` 与 `Publish Timeout` 两种算成功（后者=数据已写入但发布事务超时）；
   其余一律失败，报文形态 `status=%s msg=%s err_url=%s` 照 Go。
4. **Stream Load 的凭据转发比 Go 的通用客户端更严**：FE 会 307 到 BE，Basic 凭据要跟着走；
   Go 的包装是"同主机或白名单目标才转发"，跨主机非白名单直接拒
   （`stream load redirect blocked: target host %q is not trusted...`）。Java 侧用
   `followRedirects(NEVER)` + 手写循环只跟随 307/308（Go 会跟随 301/302/303 并改写 GET——
   该差异对 Stream Load 无观测面）。另：`Expect: 100-continue` 是 JDK HttpClient 的禁设头，
   Doris 不依赖它（仅提前拒收优化）。
5. **两条写路径由兼容模式决定**（别只翻一条）：
   - `legacy`（UNIQUE KEY + MoW）：批量更新走 Stream Load 的 `partial_columns=true` +
     `merge_type=APPEND`，body 是 `[{"id":…,"is_enabled":…}]`（Go `json.Marshal` 的 map
     键字母序 → Java 显式 `TreeMap`）；
   - `inner_product_duplicate`（DUPLICATE KEY）：**没有** partial update 可言，改为
     "读整行 → 变异 → delete+insert 重写"，也因此 `ValidateKnowledgeIndexMove` 对
     reuse_vectors 搬移直接拒（`reuse_vectors move is not supported by Doris ANN tables;
     use reparse mode`）——失败的 insert 会丢掉唯一向量副本。
6. **空 embedding 的判定键是 SourceID**：`additionalParams["embedding"]` 是
   `Map<String, float[]>`（KV 服务按 SourceID 装），查不到即"空向量跳过"（WARN）；
   单测里若把 embedding 挂到别的键上，行会被静默跳过（本次两处用例就是这么写错的）。
7. **兼容模式解析是三步序且只算一次**（含错误也缓存，照 `sync.Once`）：
   显式配置 → 既有表 DDL 探测（`SHOW CREATE TABLE` 判 `duplicate key(`/`unique key(`，
   混用直接拒）→ 只有 `auto` 且无既有表才跑函数探针
   （`SELECT inner_product_approximate([1.0],[1.0])` / `cosine_distance_approximate`，
   都能报错才拒收）。因此**任何**写路径都会先打一次 `information_schema` 列表查询——
   桩里必须给这条查询留位置（否则会撞 "no query stub"）。
8. **env-path 不做探针**：Go 的 `container.go` 注册路径只 `sql.Open`（惰性）+ 注册，
   不建表不探测；Java 侧 Hikari 用 `initializationFailTimeout=-1` 保住"池创建不拨号"，
   所以 `RETRIEVE_DRIVER=doris` 在无 Doris 的机器上也能完成注册（首个请求才失败——
   与 Go 同形，也让注册路径的单测不依赖真库）。
9. **验收脚本的历史遗漏（本批补上）**：`scripts/acceptance.sh` 的五批划分里从未包含
   `com.ragagent.retrieval.*` 与 `com.ragagent.config.*`——"五批全量"其实一直没覆盖
   检索引擎域（ES/OpenSearch 批次都是单独跑 `--tests`）。本批把两域补进 B4；
   今后声称"全量"前先核对脚本覆盖的包清单与 `server/src/test/java/com/ragagent/` 是否一一对应。

## W5γ4.11：Qdrant 驱动（REST 自持，2026-09-25）

1. **协议决策的落地样本（gRPC → REST 映射表）**：Go 用 qdrant/go-client（gRPC），本仓自持
   REST——`CollectionExists`→`GET /collections/{n}`、`CreateCollection`→`PUT /collections/{n}`、
   `CreateFieldIndex`→`PUT /collections/{n}/index`、`Upsert`→`PUT …/points`、
   `Delete`(filter)→`POST …/points/delete`、`Query`→`POST …/points/search`、
   `Scroll`→`POST …/points/scroll`、`SetPayload`→`POST …/points/payload`、
   `ListCollections`→`GET /collections`、`HealthCheck`→`GET /`。
   语义等价点：过滤 DSL（match.value / match.any / match.text / must_not）、score_threshold、
   with_payload / with_vector、scroll 的 offset=上页最后一个点 ID。**不可等价点**：gRPC 专有字段
   （ShardKeySelector 等）不适用；`wait` 是 REST 查询参数——照 Go 只在 Move 的 SetPayload 上带
   `wait=true`，Upsert/Delete/批量 SetPayload 都不带（默认异步），别"顺手"补齐。
2. **集合前缀规则是纯字符串前缀，不是维度校验**：Go 的判定是
   `len(name) > len(base) && name[:len(base)] == base`——`weknora_embeddingsX` 这种名字
   <b>也会</b>被批量更新扇出命中。所以跨集合批量更新的测试要挑一个真正不以前缀开头的集合
   （如 `other_base`）来验"跳过"，别拿"看起来像维度尾巴"的名字。
3. **nil 与空数组在两家店的存储估算里语义相反**（照抄时别互抄）：
   Qdrant 判 `Embedding != nil`（空数组<b>也</b>计 HNSW 256 字节，M=16）；
   Doris 判 `len > 0`（空数组不计 HNSW 512 字节，M=32）。两处都是 Go 原文，翻译时照各自实现。
4. **payload 字符串过 CleanInvalidUtf8**：Go 的 `newQdrantValueMap` 对含 NUL/非法 UTF-8 的
   字符串做清理；Java 侧复用既有的 `com.ragagent.common.CleanInvalidUtf8`（同一语义：
   非法编码单元/NUL 直接删除，不替换 U+FFFD）。
5. **分词降级要补一手"二次切分"**：Go 的 `tokenizeQuery` 直接吃 gojieba `CutForSearch` 的
   词元（拉丁文本按词切、空白自成词元后被 trim+长度过滤丢弃）；本仓 `SearchTextUtil` 的降级
   分词器把非 Han 连段整块返回（"Hello hello" 是一个词元）——驱动侧须再按空白切分一次，
   净效果才与 Go 一致。真实分词器接入后该二次切分对其无副作用（jieba 词元本就不含空白）。
6. **点 ID 恒新 UUID**：Qdrant 不承载业务主键（Go 的 Save/BatchSave/CopyIndices 都 `uuid.New()`）；
   行身份在 payload 的 source_id/chunk_id 上，别把 IndexInfo.ID 写进点 ID。
7. **test-connection 升级消除一条已知差异**：此前 Java 对 qdrant 只做 TCP 拨号、版本恒空；
   现在走 REST `GET /` 返回 version（等价 gRPC HealthCheck）——`VectorStoreConfigService`
   的"无 gRPC 客户端"备案随之作废。

## W5γ4.12：install 真实 LLM E2E（2026-09-25，抓回并修复四处驱动缺陷）

**E2E 链路（本机 dev 栈，OrbStack + 真 docker + 真 LLM）**：登录 10009 租户（真实
deepseek-flash / qwen3.7-text-embedding）→ 建 docker 沙箱配置（host 留空自动探测）→
上传 `sha-digest` 技能包（SKILL.md + scripts/digest.py）到 catalog → install（installer
agent 9 轮真实 LLM 驱动：读技能、探测运行环境、写 `.weknora/install-report.json`）→
verify 门通过 → 快照镜像 commit（`weknora-skill/weknora-sk-…-g1-…`）→ 指针切换 →
`tenant_skills.status=ready` → 对话执行（技能自动注入：读 `skill://sha-digest/SKILL.md`、
`shell_exec` 跑技能脚本、产出 `/workspace/output/e2e-report.txt`）→ ArtifactCollector
排水 → `resource://…` artifact + 消息挂载。**sha256 与宿主计算逐字节一致**
（`622cfb83…409c`），install-events SSE 回放 `{"percent":100,"stage":"done","status":"ready"}`。

### 四处缺陷（全部本批修复 + 回归测试）

1. **`DockerHostSupport.contextHostFromMeta` 读错 JSON 形状**（真驾驶路径全断）：
   Go `dockerContextHost` 的 meta.json 是 `{"Name":"orbstack","Endpoints":{"docker":{"Host":"unix://…"}}}`
   ——`Name` 在**顶层**、`Endpoints` 是**对象**；Java 读的是 `Metadata.Name` 且把
   `Endpoints` 当**数组**遍历 → 恒空 → 回落 `/var/run/docker.sock`（macOS 上 OrbStack/Colima
   的 socket 都在 $HOME 下，正是该函数存在的意义）。**修法**：照 Go 重写解析，抽
   `contextHostFromMeta(String, wantName)` 纯函数 + `DockerHostSupportTest` 钉真实形状与
   "别把数组分支加回来"。修复后同一份配置（host 留空）自动探到
   `unix:///Users/billy/.orbstack/run/docker.sock`。
2. **`AgentEngine.execute` 的 `llmContext` null → 入口 NPE**：Go 的 `len(nil slice)=0`、
   `range nil` 空转；Java 直接在日志行 `llmContext.size()` NPE。安装器的 installer run
   正是传 null → 第一次安装即 `installer agent failed: Cannot invoke "java.util.List.size()"
   because "llmContext" is null`。**修法**：入口按 Go 语义归一（`null → List.of()`），
   新增 `AgentEngineNullContextTest`。
3. **安装器 chat 客户端漏传 governor/ollama**：`SkillInstallPipelineImpl.chatModel` 用
   `LlmChatClients.create(config, null, null)` 构造 → `ConcurrencyChatClient` 首调
   `governor.gateNamedN(...)` NPE。Go 的 governor 是**进程级全局**，安装器与交互路径共用
   同一闸门；这与 2026-09-23 agent 路径的同类漏传是**同一个坑的第二次出现**。
   **修法**：管线构造器注入 `ConcurrencyGovernor` + `ObjectProvider<OllamaService>` 并透传
   （照 `SessionAgentQaService.chatModel`）；同时把 `ConcurrencyChatClient`/
   `ConcurrencyEmbedder` 的 gate 改成 **fail-open**（`governor == null → Release.NOOP`），
   对齐 Go `GateNamedN` 的 `l == nil` 分支——这样将来任何漏传都只丢节流、不再炸。
4. **无活沙箱时列举返回 null 的调用方 NPE**：Go 的 `ListSessionFiles` 契约明说
   "Returns nil (no error) when the session has no live sandbox so callers can treat
   'no sandbox' and 'empty output' uniformly"，Go 调用方 `for range nil` 天然安全；Java 三个
   调用点（`SessionAttachmentStagingService.BoundSessionInputStore`、`SessionBoundArtifactSource`、
   `SessionSandboxExecutionService.BoundFileStore`）直接 for-each → 首轮 staging 就
   `Cannot invoke "java.util.List.iterator()" because … is null`。**修法**：调用方 null → 空集
   （`SessionBoundManager` 保持返回 null——Go 契约，`SessionBoundManagerTest` 已钉住）。

### 教训（跨批复发率最高的一类）

- **"Go 的 nil slice / 空返回值"在 Java 侧没有对等物**：本批四处缺陷有两处（②④）就是这一类
  ——凡是 Go 侧"返回 nil 让调用方当空集用"或"传 nil 让被调方当空集用"的位置，Java 翻译时
  必须在**某一侧**显式归一。判断位置的原则：**Go 注释/契约把 nil 当合法输入或输出的地方，
  Java 侧就在该契约点归一**（如 `ListSessionFiles` 的契约在返回方，则返回方保留 null、
  调用方归一；`AgentEngine` 的契约在入参，则入口归一）。
- **注入面漏传是第二类**：governor 这类"进程级全局"在 Java 变成显式注入后，**每一处新建
  客户端都必须透传**。本批第二次踩（第一次 2026-09-23 agent 路径）。除了补传，还应给
  装饰器加 fail-open 兜底——Go 的 `l == nil` 分支就是这个兜底。
- **E2E 的价值就在于此**：这四处全都**单测覆盖不到**（单测用假管理器/假列表，不会传 null；
  Mockito 桩默认返回空列表而不是 null）。真实链路第一次跑就抓了四处，且都在第一分钟内。

### E2E 操作要点（复跑用）

1. docker 探测依赖 docker CLI 的 context（`~/.docker/config.json` 的 `currentContext` +
   `contexts/meta/*/meta.json`）；env `DOCKER_HOST` 优先级最高。
2. 安装器模型 = `builtin-skill-installer` 记录（`custom_agents`，租户可写）的
   `config.model_id` → 回退工作区默认 KnowledgeQA → 再回退第一个 active KnowledgeQA。
   dev 库 10009 的 KnowledgeQA 行里混着 stub 模型（`md-chat` 等指向 127.0.0.1:8181），
   要确保真模型须**钉住该记录**（本次写 `{"model_id": "<deepseek-flash id>"}`）。
3. 会话侧 agent config 三件套：`model_id` / `sandbox_config_id` / `skills_selection_mode: "all"`
   + `allowed_tools` 里放沙箱工具（`shell_exec`、`read_file`、`write_sandbox_file`、
   `edit_sandbox_file`、`list_sandbox_files`）。**若 allowed_tools 含 KB 工具
   （knowledge_search 等）则 agent 必须配 `rerank_model_id`**，否则首轮即
   `rerank model is not configured: please set rerank_model_id on the agent`。
4. 产物必须落在 `/workspace/output`（`SESSION_OUTPUT_ROOT`）才会被 ArtifactCollector
   排水；写在 `/workspace` 其它位置只对 agent 可见（LLM 自己也说得出这一点）。
5. 观察面：`tenant_skills.status/installed_snapshot_id`、`tenant_skill_snapshots`、
   `docker images`（快照镜像）、`tenant_sandbox_configs.config.skill_image`（指针）、
   `GET /sandbox-configs/{id}/skills/{sid}/install-events`（终态回放）、
   `GET /sessions/{id}/artifacts`。

## W5γ4.13：Weaviate 驱动（REST 自持，2026-09-25）

### 协议决策（本批的核心判断题）

Go 用 weaviate-go-client v5。读客户端源码后确认：**GraphQL 检索/列举本就是 REST**
（`GetBuilder` 持 `connection rest`）、**批量删除本就是 REST**（`DELETE /v1/batch/objects`）、
**批量创建在无 gRPC 客户端时也回落 REST**（`ObjectsBatcher.runREST`，body 为
`{"fields":["ALL"],"objects":[…]}`）——唯一纯 gRPC 的是 v5 的默认批量创建路径。
故本仓统一走 REST 自持（零新依赖），端点与报文形状逐条对照客户端源码 + 真实
Weaviate 1.28.4（compose 里钉的版本）实测。GraphQL 查询串按客户端 `Build()` 的
**Go 实录**逐字节复刻（`WeaviateGqlTest` 里钉着四条实录串）。

### 真服务端实测抓到的四件事（都写进了代码或测试）

1. **`tokenization: "gse"` 需要服务端开关**：Weaviate 1.28.4 默认关闭该内建中文分词器，
   建类直接 422 `the GSE tokenizer is not enabled; set 'ENABLE_TOKENIZER_GSE' to 'true'`。
   Go 驱动同样受影响（部署侧须给 Weaviate 容器加 `ENABLE_TOKENIZER_GSE=true`；
   Go 仓的 compose 里没有这一项——**部署缺陷，值得回填**）。本仓按 Go 原样保留 `gse`，
   并在 IT 的 javadoc 里写好起容器的完整命令。
2. **`after` + `where` 组合被服务端拒绝**：`cursor api: invalid 'after' parameter: where
   cannot be set with after and limit parameters`。Go 的 `CopyIndices` 正是这么查的
   → **Go 的 Weaviate 拷贝恒失败**（`move.go` 的注释也承认 after+where 不可用，但 copy
   没改）。且命名向量类下 `_additional{vector}` 恒返回空数组（须用
   `_additional{vectors{embedding}}`）——即便跳过第一条，拷贝到的也是空向量。
   **本仓修正**：`where + limit + offset` 分页 + `vectors{embedding}`（实测可用）。
3. **无 merge 的 PUT 会清属性与向量**：Go 的 `BatchUpdateChunkEnabledStatus`/
   `BatchUpdateChunkTagID` 用 `Updater`（不带 merge）→ `PUT /v1/objects/{cls}/{id}`，
   实测**只发一个字段时其余属性被清空、向量也丢**（真数据丢失缺陷）。
   **本仓修正**：改用 `PATCH`（merge）——只该变目标字段，且失败语义仍照 Go（逐对象失败只记日志）。
4. **BM25 的 `_additional.score` 是字符串**：服务端返回 `{"score":"0.48952064"}`；Go 的
   `.(float64)` 断言恒失败 → Go 的关键词结果分数**恒 0.0**（其 `keywords → 1.0` 分支是死代码）。
   用 Go 客户端对真服务端录了一次实锤（`score="…" type=string isFloat64=false`）。
   **本仓修正**：存在 score 值即 1.0（与 Qdrant/Doris 的关键词分数一致），字符串数字也解析进
   certainty 作容错。

### 复跑（真服务端 IT，env 门控）

```bash
docker run -d --name WeKnora-weaviate-local -p 9035:8080 -p 50052:50051 \
  -e DEFAULT_VECTORIZER_MODULE=none -e ENABLE_MODULES=none \
  -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true -e ENABLE_TOKENIZER_GSE=true \
  semitechnologies/weaviate:1.28.4
WEKNORA_WEAVIATE_IT=true ./gradlew :server:test --tests "*WeaviateDriverLocalIT*"
```

IT 覆盖：建类 → 批量写 → 向量检索（certainty）→ 关键词检索（gse 中文）→ **merge 更新后
对象仍可检索**（修正 ③ 的回归锚点）→ 拷贝（修正 ② 的回归锚点）→ move → 删除。

### 其它

- 类名默认 `Weknora_embeddings`（Go 原文拼写"Weknora"，非 WeKnora）——改名会与既有部署不匹配。
- Go 的 `weaviate.tokenizeQuery` 是死代码（本包无调用点；词表相关的分词在 Qdrant 用），未翻译。
- Go 批量更新里的 `if err != nil`（用的是上一个调用的陈旧 err）死分支未复刻。
- env 侧 `WEAVIATE_GRPC_ADDRESS` 保留在配置面（照 Go 的 env 解析），REST 实现不用它。

## W5γ4.14：Milvus 驱动（REST v2 自持，2026-09-25）

### 协议决策：REST v2 全覆盖（实测 milvusdb/milvus:v2.6.11）

Go 用 milvus-sdk-go v2（gRPC）。起真例逐端点验过 **REST v2** 后确认可零新依赖自持：
建集合（`schema` + `functions`(BM25) + `indexParams` 内联）、`collections/load`、`collections/list`、
`entities/upsert`、`entities/query`（`outputFields:["*"]` **含向量**——"查整行→改字段→回写"
的更新路径靠它）、`entities/search`（向量检索与 **BM25 文本检索**）、`entities/delete`
（过滤表达式）全部可用。

### REST 与 SDK 的差异（实现时必须知道）

1. **稀疏列 dataType 是 `SparseFloatVector`**（SDK 常量叫 `FieldTypeSparseVector`；
   写 `SparseVector` 会被服务端拒：`data type SparseVector is invalid(case sensitive)`）。
2. **REST 没有模板参数**：Go 用 `filter: "field in {ids}"` + `WithTemplateParam`；REST 的
   `filterParams` 被静默忽略（实测报 `the value of expression template variable name {ids}
   is not found`）→ 本仓把值**内联**（照 `filter.go` 的 `formatValue`/`escapeDoubleQuotes`：
   字符串加双引号只转义 `"`、布尔 true/false、数值原样）。算子的括号/结合形状照 Go。
3. **库名走请求体**的 `dbName`（`DbName` 头实测无效）。
4. **`shardsNum` 被 REST create 忽略**（describe 恒 1）——照传保留配置面（Go 的
   `WithShardNum` 走 gRPC 字段，是真生效的；差异已备案）。
5. **load 是同步调用**（SDK 是异步 task + `Await`）；错误文案合并为
   `failed to load collection: …`。

### 语义要点（照 Go，别"顺手统一"）

- **行主键恒新 UUID**；所谓"更新"是 **查询整行 → 改字段 → Upsert 回写**（three-step），
  向量随行回写（依赖 query 输出含 `embedding`）。
- **enabled 批量更新失败会聚合冒泡**（`errors.Join` 语义——"停用必须让索引不可搜"）；
  **tag 批量更新失败只 WARN 继续**。两条语义不同。
- **move 的 drain 语义是"失败换重试"，不是 bug**：`move.go` 注释明说"重复 ID = 后端尚未把
  已确认的更新可见 → 报错让调用方重试，而不是推进检查点静默漏搬"，重复 ID →
  `invalid or repeated move index`。IT 因此按**调用方姿态重试**（Bounded 一致性下有可见性窗口）。
- **默认 Bounded 一致性**：写后读有延迟（实测 upsert 后立即 search 为空、数十毫秒后可见）。
  驱动照 Go 不显式设置一致性；集成测试用轮询等待。

### 中文关键词的真相（易踩，Go 侧同款）

`schema.content` 的 `enable_analyzer=true` 用的是 Milvus **标准分析器**——**按 CJK 连段切词**
（"中文检索 hello" 的 token 是 `中文检索` 与 `hello`），**不是 jieba 分词**。因此查询词必须与
文本里的分段一致（探针实录：`"中文"` 命中 `"中文 检索 hello"`、不命中 `"中文检索 hello"`；
`"hello"` 恒命中）。Go 的 schema 同样如此——中文召回的粒度取决于文档里是否有空格/标点。

### 复跑（真服务端 IT，env 门控）

```bash
docker run -d --name WeKnora-milvus-local --security-opt seccomp=unconfined \
  -p 19530:19530 -p 9091:9091 \
  -e ETCD_USE_EMBED=true -e ETCD_DATA_DIR=/var/lib/milvus/etcd \
  -e COMMON_STORAGETYPE=local -e DEPLOY_MODE=STANDALONE \
  milvusdb/milvus:v2.6.11 milvus run standalone
WEKNORA_MILVUS_IT=true ./gradlew :server:test --tests "*MilvusDriverLocalIT*"
```

IT 覆盖：建集合（BM25 函数 + 稀疏列 + 索引）→ 批量写 → 向量检索 → BM25 中文检索 →
tag/enabled 的整行回写（向量必须仍在）→ 拷贝（offset 分页）→ move（带调用方重试）→ 删除。

## W5γ4.15：腾讯 VectorDB 驱动（HTTP 自持 + 客户端 BM25，2026-09-25）

### 协议决策：自持 SDK 的 HTTP 面（Go 走 gRPC）

Go 的 `tcvectordb.RpcClient` 是**混合**的：`/database/*` 走 HTTP，但**集合与文档操作全部走
gRPC（olama protobuf）**。本仓不引 protobuf，改走同一服务端的 **HTTP 面**（SDK 的
`tcvectordb.Client` 就是它）：

- 鉴权：`Authorization: Bearer account=<username>&api_key=<key>`（**明文，不是 TC3 签名**）；
- 头：`Content-Type: application/json` + `Sdk-Version: v1.8.4`；
- 路径静态、库名/集合名在**请求体**：`/database/list`（GET！）/`create`、
  `/collection/create|describe|list`、`/document/upsert|search|fullTextSearch|query|delete|update`；
- 信封：HTTP 非 2xx → `response code is %d, %s`；`code != 0` → `code: %d, message: %s`；
- 地址：**https 被 SDK 拒**（"not supporting https://"）、空 username/key 被拒
  （"username or key is empty"）——本仓同款。

### BM25 是客户端算的（本批最重的一块）

稀疏向量 `sparse_vector` 由 **客户端 BM25** 生成（写库与查询各编一次）：

| 件 | 说明 |
|---|---|
| 哈希 | **murmur3 32 位**（x86_32，种子 0）→ 无符号 int64；实测对照：`"hello"→613153351`、`"world"→4220927227`、`"中文"→3676729751`、`""→0` |
| 文档权重 | `tf/(K1*(1-B+B*(len/avgDocLen))+tf)`（B=0.75、K1=1.2） |
| 查询权重 | `idf=ln((docCount+1)/(df+0.5))` 再按 Σidf 归一（df 缺失按 0 → 拿到大 idf） |
| 语料统计 | COS 的 `bm25_zh_default.json`：**85 MB / 389 万词条**（doc_count=382835、avg_doc_len=245.61638），**运行时下载**并缓存到 `/tmp/tencent/vectordatabase/data/`（照 Go 的 DefaultStorageDir） |
| 停用词 | COS 的 `default_stopwords.txt`（1.1 KB / 252 行），同样下载缓存 |
| 分词 | Go 内嵌 gse/jieba（HMM 开、cutAll 关、forSearch 关） |

**对照验证**（Go 侧跑 SDK 打印基准值，Java 逐值比对，见 `TencentVectorDbBm25Test`）：
`"中文检索测试 hello"` → ids `[1872693679, 4269123640, 613153351]`、DOC 三项均 `0.7627807`、
QUERY `{0.44923997, 0.10152008, 0.44923997}`；`"第二条 中文 hello world"` → DOC 均 `0.7606547`、
QUERY `{0.38338965, 0.16701655, 0.066204146, 0.38338965}`——**哈希与 BM25 数学与 Go 完全一致**。

**唯一的差异是分词**：本仓复用既有分词接缝（`SearchTextUtil.segmenter()`，默认是
"按空白 + CJK 二字滑窗"的近似实现）。因此 **Java 写出的稀疏向量与 Go/jieba 写出的不逐词一致**
——同一集合的写与读必须同一实现；从 Go 迁移过来的存量数据需**重导入**才能被 Java 的
关键词检索命中（向量检索不受影响）。接缝可替换（接入真实 jieba 后即与 Go 同源）。

另：Java 侧把 85 MB 参数表**流式解析进排序长整型数组**（约 47 MB；Go 是
`map[string]float64`，数百 MB 堆）——查找走二分，结果等价。

### 其余语义（照 Go，别"顺手统一"）

1. **过滤语法与 Milvus 不同**：腾讯是 `key in ("a","b")`（双引号 + 圆括号，照 SDK 的
   `In`）；`is_enabled`/`source_type` 是 **uint64**，所以基础过滤写 `is_enabled=1`（不是 `true`）。
2. **集合命名的开关**：`indexCfg.CollectionName` 非空 → **单集合**（无 `_<dim>` 后缀），
   且前缀匹配变成精确相等；为空/无配置 → 带维度后缀 + 前缀匹配（默认口径）。
3. **批量更新走 Update API**（`/document/update` + `query.filter`），不是"查整行→回写"；
   **任一匹配集合失败即抛错**（与 Milvus 的"聚合冒泡"、Weaviate 的"忽略"都不同）。
4. **关键词检索**：跨匹配集合 `fullTextSearch`；**单个集合失败只 WARN 跳过**，但**全部匹配
   集合都失败**时抛错，文案提示"集合缺 sparse 索引、需重导入"；结果按 score 降序（稳定）
   截 `limit`；`limit` 与向量检索一样，TopK≤0 → 10。
5. **拷贝的第 3 态 SourceID 是 sha256**：`"<targetChunkID>-<sha256(targetChunkID NUL
   sourceChunkID NUL originalSourceID) 前 16 hex>"`（Milvus 是"新 UUID"、Doris 是"新 UUID"——
   各店不同，别照抄）；目标文档 **id = 改写后的 SourceID**（不是新 UUID）；kb 映射缺失时
   保留原 knowledgeId（不跳过）；isEnabled 沿用源值。
6. **move 只有一发 Update**（kb 改 + tag 清空），没有 Milvus 的 drain/seen 守卫。
7. 内容入库前过 `cleanInvalidUTF8`（丢 NUL 与非法序列）。
8. 存储估算：`content 字节 + 4×dim + content 字节×2 + (四个 id 字节 + 256)`——content 计两次
   （照 Go 原文，非笔误）。

### 验证与"没有真服务端 IT"的说明

腾讯 VectorDB 是云服务（无本地版）→ 本批**没有真服务端 IT**（与 ES/OpenSearch/Milvus 批不同）：
wire 形状用 stub HTTP 逐请求钉死（auth/path/body/信封），BM25 用 Go SDK 的实跑基准逐值对照。
真机联调留待有云实例凭据时（配置面已就绪：`TENCENT_VECTORDB_ADDR/USERNAME/API_KEY/DATABASE`）。

## W5γ4.16：SQLite 驱动（独立文件 + FTS5 照用 + 平面 cosine，2026-09-25）——**九家店全部落地**

### 介质决策（本批的判断题）

Go 的 SQLite 引擎**不是**独立存储：`createSQLiteEngine(_ types.VectorStore, db *gorm.DB)`
忽略 store 配置、直接用**产品库**（单二进制模式下的 SQLite），并建三张表
（`lite_embeddings` 元数据 / `lite_embeddings_fts` FTS5 contentless / `vec_embeddings_<dim>`
是 sqlite-vec 的 `vec0` 虚拟表）。

本仓产品库是 **PostgreSQL**（H2 仅测试），没有"挂产品库的 SQLite"这种形态。判断与落法：

1. **驱动**：`org.xerial:sqlite-jdbc:3.46.1.3`——平台 native 随 Maven 构件分发，
   **仓内零二进制**（替代 Go 的 CGO 静态链接）。实测打包版 SQLite 3.46.1
   **支持 FTS5 / contentless_delete / bm25()** → 关键词面与 Go 基本同构。
2. **介质**：一颗**独立 SQLite 文件**（`SQLITE_PATH`，缺省
   `./data/weknora-retrieval.sqlite`；另支持系统属性 `weknora.sqlite.path` 供测试/运维，
   与 store 的 `connection_config.addr`）。引擎名与对外语义不变，介质就近成文件。
3. **vec0 → 普通表 + Java 标量函数**：`vec_embeddings_<dim>(rowid INTEGER PRIMARY KEY,
   embedding BLOB)`（小端 float32，与 `sqlite_vec.SerializeFloat32` 同格式）+
   **注册 `vec_distance_cosine(blob, blob)` Java 函数**做平面扫描；排序/取 k/过滤顺序
   与 Go 逐句一致（cosine 的 KNN 结果完全相同，仅复杂度从 ANN 变 O(n)）。

### 照抄别改的语义点

1. **写入是 `INSERT OR IGNORE`**（GORM `OnConflict DoNothing`）：靠
   `(source_id, source_type)` 唯一索引去重——**重复 source 的二次写被静默忽略**
   （内容/向量都不更新！与其它店的"覆盖写"完全相反）。
2. **关键词面**：`tokenizeCJKBigram` 把连续汉字切**重叠二元组**（单字保留、非 CJK 整词、
   空白/标点/符号分隔），入 FTS5 的 content；查询同样切二元组后 `"a" OR "b"` 连接；
   分数 `bm25(...) * -1000000.0` 变正数。**contentless FTS5 不存原文**——直接
   `SELECT content FROM ..._fts` 恒 NULL（Go 同款，别拿它做断言；用二元 MATCH 断）。
3. **向量是"先取 k 近邻、再按过滤收窄"**（照 Go 的 `MATCH ? AND k = ?` 外层
   `rowid IN (过滤子查询)`）：因此**结果可能少于 TopK**（最近的两条若不过滤条件，topK=2
   会返回空）。阈值在取回后于内存里 `score < threshold → 跳过`。
4. **过滤只有三个 IN**（kb/knowledge/tag）——**没有排除项**（`excludeKnowledgeIds` /
   `excludeChunkIds` 被此店忽略，照 Go 原文）。
5. **检索分派是特例**：`RetrieverType == ""` 时**关键词与向量两条都跑**并合并返回；
   **未知类型不报错**（返回空），与其它店的 `invalid retriever type` 不同。
6. 结果 `id` 是 **rowid 的十进制串**；`EstimateStorageSize = len(content)+200`（字节）；
   `move` 只有一条 UPDATE（FTS/向量靠 rowid 关联、不动）。
7. **WAL 快照坑（实测踩到）**：写事务里若用**另一条连接**建向量表，SQLite（WAL）返回
   `no such table`——写路径必须用**同一条连接**建表（本仓 `ensureVecTable(conn, dim)`）。

### 验证

`SqliteRetrieveRepositoryTest` 12 条（真实 SQLite 文件：建表/FTS/重开、去重语义、二元
关键词与英文整词、分派特例、cosine 排名与分数、**k-then-filter 语义**、阈值、三种删除、
批量更新、拷贝（含 FTS/向量复制）、move、估算）+ `SqliteCjkBigramTest` 4 条（切分/查询/
小端序列化/cosine/UTF-8 清理）**全绿**；`EngineFactoryTest`/`RetrievalEngineWiringConfigTest`
补齐（sqlite 不再是 XDEP）；五批验收 PASS。

## W5γ4.17：备案小账批（2026-09-25）

| 项 | 结论 |
|---|---|
| **VLM ollama 界面** | ✅ **落地**（照 Go `vlm/ollama.go` 74 行）：单条 user 消息（prompt + 图片原始字节 → JSON base64）、`stream=false`、`options.temperature=0.1`、取 `message.content`；`ModelDebugController.debugVlm` 放行 ollama（Go 侧对 ollama 基址不做 SSRF 校验——基址来自 `OLLAMA_BASE_URL`）。**weknoracloud 仍是 XDEP**（云 API，需凭据；`VlmClientTest` 无关，golden 只覆盖 OpenAI 面） |
| **Milvus `shardsNum`** | ✅ **钉测试**：`indexCfg.shardsNum>0` 才带该键（服务端忽略是已备案差异，配置面照传） |
| **Weaviate `ENABLE_TOKENIZER_GSE`** | 📋 **跨仓提案（未改 Go 仓）**：Go 仓 `docker-compose.yml` 的 weaviate 服务缺 `-e ENABLE_TOKENIZER_GSE=true`，而 Go 驱动 schema 用了 `tokenization:"gse"` → 1.28.4 默认关时**建类 422**（Go 侧同样受影响）。建议由 Go 仓持有者补该 env（本仓 IT 的启动命令已含它，见 W5γ4.13 段） |
| **E2E 两个观察项**（早错 SSE 不收流 / `list_sandbox_files` 注册时机） | 📋 **待复跑时定位**：现有文档只留了名词、没有现象与复现步骤——先补复现（按 W5γ4.12 的"E2E 操作要点"五步起栈），再按现象定修法。**不做无现象的猜测式改动** |
| **腾讯分词接缝 / jieba 真实分词** | 📋 维持接缝（接上真实 jieba 即与 Go 存量稀疏向量互通；属独立工作，非小账） |

## W5γ4.19：provider-XDEP 族收口（2026-09-25）——weknoracloud VLM 落地 + 三项决策简报

### 一、weknoracloud VLM ✅ 落地（照 Go `vlm/weknoracloud.go` 188 行）

| 件 | 说明 |
|---|---|
| `VlmClient.predictWeKnoraCloud`（新） | `POST {baseURL}/api/v1/chat/completions`：multipart 内容（text + 各图 `data:<mime>;base64,…`）、`max_tokens=5000`、`temperature=0.1`（**用常量，不读 extra 覆盖**——照 Go 的 `float64(defaultTemp)`）、`stream=false`；模型名可被 `extra.remote_model_name` 覆盖（`effectiveModelName`）；取 `choices[0].message.content` |
| 鉴权 | 复用既有 `embedding.WeknoraCloudSign`（与 chat/embedding/rerank **同一份**签名实现；本批不新增第二份）——六个头 `X-APPID/X-API-Key/X-Request-ID/X-Timestamp/X-Nonce/X-Signature`（md5 over sorted rfc3986 `k=v` & …，body 先取 md5，空体按 `{}`） |
| 传输 | `VlmClient.Transport` 新增 `postWithHeaders`（**缺省抛**，保持函数式接口——既有 lambda stub 不受影响）；`VlmHttpTransport` 实现之：**不带 Authorization**、非 200 抛 `HttpStatusException(status, body)` → 调用方按 Go 文案报 `weknoracloud VLM: status %d: %s` |
| 凭证 | `VlmConfig` 补 `appId/appSecret` 字段（`configFromModel(m, appId, appSecret)` 本就收这两个参数、此前**被丢弃**）；新增 `WeKnoraCloudService.resolveCredentials()`（照 Go `resolveWeKnoraCloudCredentials`：租户缺失 → null；未配/解密失败 → 空串对）；`ModelDebugController` 注入该服务并在校验前解析 |
| 错误族（照 Go 原文） | `WeKnoraCloud VLM: AppID is required` / `AppSecret is required`（**在基址校验之前**，照 `NewWeKnoraCloudVLM` 顺序）；`weknoracloud VLM: do request: …`；`weknoracloud VLM: status %d: %s`；`WeKnoraCloud VLM: no choices in response` |

**验证**：`VlmWeKnoraCloudTest` 5 条——① 请求形状 + 六头齐备 + **签名用抓到的头独立重算一致** + 取 content；② `remote_model_name` 覆盖与空图丢弃；③ 凭证缺失文案；④ 非 200 / 无 choices 文案；⑤ `predict` 分派走 `postWithHeaders`（不落 `post`）。测试用**真实 `VlmHttpTransport`**（临时换放行 loopback 的 `SsrfGuard`，照 `ConnectorHttpTest` 做法，收尾恢复）——顺带覆盖传输层的状态码映射。**至此 VLM 三个界面（openai/ollama/weknoracloud）全部落地**。

### 二、三项决策简报（**均需 Owner 输入，故未动工**）

| 项 | 现状（已核） | 阻塞点 | 我的建议 |
|---|---|---|---|
| **W5δ provider 终端执行体** | Java 侧**中性层已全**（`RemoteTerminalOptions` 五旋钮、`SessionTerminalService` / `TerminalBridge` 接缝、`RemoteError` 分类器）；缺的是 cube/e2b/docker 的**远程 PTY 执行体**（Go ~1.3k 行）+ 生产接线（`openOnResolved`/`provisionAndOpen` + 泵组） | ① 需**真实 provider/沙箱**（凭据 + 可达端点）才能联调；② 需先定"zerodep stdin 无半关闭对双向流的影响"这个传输层判断题 | 先做**传输层可行性评估**（写一个 spike：以 zerodep 的 stdin 语义模拟半关闭，跑一个真实 cube/e2b 会话，量化"EOF 不可表达"的后果），评估有结论再谈执行体 |
| ~~**⑱ MCP initialize 契约对齐**~~ | ✅ **已落地（2026-09-25 W5γ4.21）**：差异从两侧代码 + golden 实录
（`GoRecording45C` 的 `mcp_stub/req_00`）直接定位——协议版本 `2024-11-05`→`2025-11-25`、params 键序、
补 `ValidProtocolVersions` 校验；**无需 Owner 提供实录** | 详见本分片 W5γ4.21 段 |
| **存储三条备案** | 出自 `known-issues/08-storage-a3.md` 的「已知差异」：① 云对象**整对象入堆**（Go 流式 `io.ReadCloser`）→ 要动 `FileTransport` 补第三种形态；② **OSS 大文件未走分片 Uploader**（Go >10MB 用 10MB/片 + 3 并发）；③ **local 有两支实现**（`knowledge.LocalStorageService` 与 `fileserve.LocalFileContentService`）收敛 | ①③ **会动既有 golden 锁定面**（`resource://` 契约）→ 属"改读路径"类，按项目纪律需先批准再动；② 需真 OSS 凭据联调 | 建议**分开处理**：② 最独立（只加分流阈值 + 分片，OSS 上传面自有测试）→ 可先做；①③ 建议排到"读路径黄金面"专门批（附 A/B 方案）再动 |

**验收**：`VlmWeKnoraCloudTest` 5 条全绿 + 模型域/初始化域回归绿 + **五批验收 PASS**。

---

## W5γ4.21：MCP initialize 契约对齐（2026-09-25，⑱ 决策项落地）

### 差异（实测三处，均已修）

Go 侧 initialize 由 mcp-go v0.52.0 发出，golden 实录（`GoRecording45C` 的 `mcp_stub/req_00`，agent.tools 包内）原文：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","clientInfo":{"name":"WeKnora","version":"1.0.0"},"capabilities":{}}}
```

| # | 差异（修复前） | 根因 | 修法 |
|---|---|---|---|
| ① | `protocolVersion` 为 `2024-11-05` | `McpProtocol.PROTOCOL_VERSION` 是**依赖派生值**：Go 传 `mcp.LATEST_PROTOCOL_VERSION`（mcp-go v0.52.0，`mcp/types.go:139` = `2025-11-25`），Java 常量停在更早 SDK 时代的字面量 | 常量改 `2025-11-25`，注释钉"照哪个 SDK 版本、Go 升 SDK 时要跟着钉" |
| ② | params 键序 `protocolVersion→capabilities→clientInfo` | Java 手写 `LinkedHashMap` 的插入序 ≠ mcp-go params **匿名结构字段序**（`client/client.go:206-213`：protocolVersion, clientInfo, capabilities） | 按键序构造 |
| ③ | 无应答版本校验（空版本也放过） | mcp-go 在应答处校验 `ValidProtocolVersions`，不在表内即报 `UnsupportedProtocolVersionError`（`client/client.go:232-234` + `mcp/errors.go:57-63`） | 加 `McpProtocol.VALID_PROTOCOL_VERSIONS`（四值照 `mcp/types.go:142-147`）+ `isSupportedProtocolVersion()`；校验在**设版本头 / 发 `notifications/initialized` 之前**（照 SDK 顺序），失败时 `initialized` 不置位 |

**错误文案对齐**（两层拼装，逐字节）：内层 `unsupported protocol version: "xxx"`（照 SDK 的 `fmt.Sprintf("unsupported protocol version: %q", v)`），外层 Go 仓储 `fmt.Errorf("failed to initialize: %w")` → 最终 `failed to initialize: unsupported protocol version: "xxx"`。该错误在 Go 侧是 **SDK 自带错误、不对应任何哨兵** → Java 异常 `code == null`（别塞 `NOT_CONNECTED` 之类的码）。

### 有意保留的次要差异（非契约面）

- **capabilities 的解析**：Go 仓储侧 `internal/mcp/types.go` 的 `InitializeResult.Capabilities` **从不被填充**（`client/client.go:436-445` 只拷 ProtocolVersion/Instructions/ServerInfo），Java 解析了 `tools/resources/prompts` 三键。Java 侧**无消费者**（grep 确认），解析是宽进的（缺键 → null）——保留为"信息更全"；**若将来要做按 capability 开关的行为，一律以 Go 的"恒空"为准**。
- `OAuthHttp` 的 `MCP-Protocol-Version: 2025-03-26` **不是缺陷**：mcp-go 的 `client/transport/oauth.go:537/732` 就是这个硬编码值（OAuth 流程专用），与 initialize 协商出的版本无关。

### 回归守卫（新增/加强）

- `McpClientProtocolTest`：① 握手用例新增**出站报文整串逐字节断言**（含键序）；② 新增"应答版本不在白名单"用例——断言文案、`code==null`、**不发 initialized 通知**、后续 `listTools` → `ErrNotConnected`。
- `McpStubABTest`：initialize 从"按各自基线断言"**升格为整串逐字节比对**（`javaInit.toString() == goInitBody.toString()`；两端数值 id 都从 1 起步）——旧文档里记的 ⑱ 差异已消除。
- `McpServerStub`：新增 `requestBodies` 录制（钉键序用）。

### 复跑

```bash
./scripts/acceptance.sh --changed     # 受影响批：B1a（agent.tools 的 A/B）+ B3（mcp.protocol）
./gradlew :server:test --tests "com.ragagent.agent.tools.McpStubABTest"   # 单跑 A/B
```

### 教训（可复用）

**依赖派生的常量必须钉"来源 + 版本"**：`McpProtocol.PROTOCOL_VERSION` 照的是 Go 依赖里的常量（而非常量本身），Go 升级 mcp-go 时会**静默漂移**——协议版本、SDK 版本头、默认参数这一类常量都该在注释里写清"照哪个 SDK 版本"，并把它们纳入"Go 侧依赖升级 → 对账"的检查项。
