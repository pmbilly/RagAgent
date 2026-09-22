# 已确认细节与坑 · 波 4（事件契约 / tools / 引擎 / chat 三兄弟）

> 本文件是 `docs/translation-conventions.md` §9 的一个分片（按批次拆分，**内容未改动**）。
> 代码注释与任务书里的「约定 §9「XXX」」按条目标题在本目录内检索即可。
> 回链：[`translation-conventions.md`](../translation-conventions.md) §9 索引 ｜
> 同目录兄弟文件：00 基础 / 01 阶段 4.1–5.2 / 02 波 0–1 / 03 波 2 / 04 波 3 / 05 波 4 / 06 W5。


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
- **波 4.3 embed/im 清单面补充**：
  - **GoStyleErrorReportValve（横切备案）**：Go 对畸形 HTTP 头行回纯文本
    `400 Bad Request`，Tomcat 默认渲染 HTML 错误页——自定义 ErrorReportValve
    经 host.setErrorReportValveClass 替换默认阀，**只在响应体未被任何应用代码
    写过时接管**（应用 JSON 错误契约不受影响，401 JSON 已验证原样）。影响全服
    容器级错误页格式（对齐 Go）。
  - **GORM 零值 bool 的 DB 默认语义**：create 对 default:true 的列（enabled/
    show_suggested）请求 false **落库仍 true**（GORM 省略零值列 → DB 默认）——
    与 §3 清单一致但方向反直觉，golden 已钉。
  - **update 缺键 = json.Marshal(nil)="null" 整列覆写**：allowed_origins 缺失时
    Go 把字面量 "null" 写进 jsonb 列（响应 null、allowlist 清空）——不是
    "保留原值"也不是 "写 []"。
  - **im CRUD 信封形态**：无 success 键、create 是 200 非 201、duplicate bot
    409 文案带 %q 渠道名——与 embed 管理面（201、信封）刻意不同族。
  - **EmbedAuth 中间件**：publish token（em_/ems_ 前缀）+ HMAC 会话签名 +
    本地滑动窗口限流（=Go Lite 回退；Redis ZSET 路径未翻，429 分支未录）。
- **波 4.4 模型客户端+检索地基补充**：
  - **stub A/B 的"请求体逐字节"打法**：把 Go 客户端类型逐字抄进 /tmp 程序打本地
    stub server 录请求体（31 份 wire 实录），Java 测试对同一 stub 比对——无路由面
    支撑库的等价 A/B。抓回的真契约：**Volcengine rerank 顶层键序是非字母序的
    datas→rerank_model→rerank_instruction**（map 字母序规则的 provider 级例外）；
    **Go fmt 的 %02s 对字符串也补零**（宽度对字符串同样生效）。
  - **SDK 无 Java 等价 → 规范裸 HTTP 复刻**：LKEAP（腾讯 TC3-HMAC-SHA256）与
    Volcengine（V4 签名）按云厂商签名规范直接实现 + 切批语义（60 条/2000 字符、
    50 条并发 4）照抄；Authorization 具体值不做跨语言比对（Go 测试也只验含 AK）。
  - **降级接缝三件**（与波 0 RSS 同族）：jieba 分词（Segmenter 接缝默认二字滑窗
    近似，可注入恢复）、chromedp 渲染（BrowserRenderer 接缝恒失败=Go 的
    browser-unavailable 分支）、readability/html-to-markdown（走 Go 自身回退分支）。
  - **IP pinning 取舍**：JDK 不能换 dialer——用每跳 SSRF+DNS 校验近似
    （LlmTransport 同款，阶段 4.0 已备案）。
  - **GoJson 包内副本收敛**（待办）：embedding/rerank/websearch.provider 三份
    GoJson + 两份 Sign 暂为包内副本（避免跨包耦合），收敛为 common 级是
    无行为变更的重构，留清理批次。
  - **wsp-test 路由维持 SEARCH_DEGRADED**：执行面已就绪但接线需重录 golden
    （现有 golden 录的是 Go 真实外网执行文案）——留决策。
- **波 4.2 纯逻辑件补充**：
  - **token 估算选型**：jtokkit cl100k_base `encodeOrdinary` = tiktoken-go 的
    Encode——36 语料（中/英/日韩/代码/emoji/URL）token 数**逐字节一致**。这类
    "分词/切点"语义不能接缝降级：压缩切点/阈值全部建立在 token 数上。
  - **Go json.Marshal 的 float64 泛型语义**：`interface{}` 持有大整数时输出
    `1.2345678901234568e+29`、`1e21→1e+21`——Jackson 的 DoubleNode 走不到注册的
    DoubleSerializer，需手写递归编码器（renderToolArgs 16 态实录钉住）。
  - **Go 既有怪癖逐字保留**：ovf17 的 "CONTEXT_WINDOW_EXCEEDED" 不匹配 generic
    模式（`context[_ ]length[_ ]exceeded`）——不"修好"。
  - **llm domain 缺口备案**：ChatMessage 的 name/toolCallId/reasoningContent
    默认 null（Go 零值 ""）——消费侧已 null-guard；是否补 `= ""` 初始化留
    llm domain Owner 决策（FunctionDef.parameters 是 JsonNode 非 RawMessage，
    字节级对齐等 4.5 注册表统一构造 schema 时核对）。
  - **全量跑的瞬时失败处置**：被中断的录制轮次会在 dev PG 留残留状态
    （bs 的 device/pairing 行）→ 下一轮全量个别用例假红（单跑即绿）。
    处置：重跑一轮确认瞬时，再决定是否需要测试侧自愈。
- **波 4.5a tools 基建补充**：
  - **录制方法**：/tmp/toolrec 复制 Go 仓 internal/，加同包探针 zz_recorder_test.go
    直接调未导出函数（mockTool/outcomeTool 复用 Go 测试文件的定义），输出
    rec.jsonl → 生成 GoRecording45A.java（"禁止手改"惯例，同 GoRecording.java）。
  - **ValidateParams 的短路条件**：Go 只认 `len(args)==0`（空 RawMessage）；
    `{}` 照常走 required 检查并报 "required parameter ... is missing"——
    Java 侧写 `args.isEmpty()` 提前返回是**真缺陷**（registry 的 call_mcp_tool 等
    hint 拼接依赖 `{}` 触发校验），实录 hint_mcp case 抓回。
  - **Go nil 切片 vs Java 空 List**：todo_write 缺省 steps → Go null/"null"，
    Java 空 ArrayList → []/"[]"。data map 里的嵌套结构不能无脑
    GoJsonCodec 全树排序——Go 只排 map 键、struct 按声明序；测试侧用
    键序无关 canonical 比较 + steps_json 字符串/输出文本的字节断言分担契约。
  - **(result, err) 双通道折叠备案**：Java registry 单返回——工具以
    success=false+error 表达失败；Go 的 "result.Success=true 且 err≠nil 时
    强制置 false" 语义在 Java 不可表达（也不需要：工具内部已归一）。
  - **环境性假红（非代码问题）**：TenantCatalogContractTest.kvParserMatchesGo 与
    SandboxSkillsMeContractTest.recordedScenario 依赖本机 fake-ip DNS
    （198.18.0.0/15，代理/VPN 的 wildcard 解析）——当前 nslookup NXDOMAIN 时
    SSRF 文案变为 "DNS resolution failed"，与 4110ad4 干净基线上复现一致。
    恢复 fake-ip DNS（开代理）即绿；或后续把这两个 golden 的 SSRF 文案按
    部署态标 XDEP。
- **波 4.5b 补充**：
  - **录制方法**：同 4.5a（/tmp/toolrec45b 复制 Go 仓 internal/，同包探针
    zz_rec45b_*.go ×11 → rec.jsonl → GoRecording45B.java，"禁止手改"）。
  - **已知差异（实录已锁稳定段，不再验证）**：
    - ⑮ DuckDB 1.5.2（Go）vs JDBC 1.1.3（Java，波 4.2 引入，build.gradle.kts:39
      既有）错误文案不同——missing-column 语料只锁稳定段；
    - ⑯ DuckDB 1.1.3 无 read_xlsx——buildExcelCreateTableSQL 逐字锁定，
      执行侧靠 ST_Read shim 重试；
    - ⑰ knowledge_search dedup 二轮顺序：Go map 随机 vs Java LinkedHashMap
      确定——rerank_preserve_top 语料钉采样。
  - **决策点（照单收录）**：手写 SqlGuard（1590 行）替代 pg_query、跳过
    Deparse、DatabaseQueryTool 注入 LongSupplier tenantId、storage 后端解析留
    KnowledgeFileMaterializer seam（4.5c）、未加 JsonContractRoundTripTest
    （agent 已给理由）。
  - **⚠️ 测试基建新坑（2026-09-20 实测，与 4.5b 代码无关）**：大组合批跑
    （agent+common+event+apikey+audit+auth 六包 704 条，或 agent+apikey）
    稳定复现 Mockito inline MockMaker 初始化失败
    （"Could not self-attach to current VM using external process"，
    ByteBuddy attach 外部进程失败）——该 JVM 内首个 @SpringBootTest 上下文
    /mock() 初始化失败后，**全部** Spring/Mockito 测试假红（208 条）；而
    com.ragagent.agent.* 全包零 Mockito（纯实录回放），单跑恒绿。同样组合
    分小包（agent 单独 / 其余五包一起）全绿。处置：**全量回归按 4.5a 同款
    小包批次跑**（B1a=agent、B1b=common/event/apikey/audit/auth、
    B2=browserskill/datasource/embed/embedding/evaluation/favorite、
    B3=im/knowledge/llm/mcp/memory/model/org、
    B4=rerank/sandbox/searchutil/session/storage/storageurl/stream/system/
    vectorstore/webfetch/websearch/wiki/agentm）；批次内若冒同款 attach 假红，
    单包重跑确认后重试该批即可。如频繁复发，候选修法（留给 Owner 决策，
    未动手）：test JVM 加 `-Djdk.attach.allowAttachSelf=true` 或
    `net.bytebuddy.agent.attacher.dump` 诊断，勿改 forkEvery 先定位。
- **波 4.5c 补充**：
  - **录制方法**：同 4.5a/b（/tmp/toolrec45c 复制 Go internal/，同包探针
    zz_rec45c_a/b/c_test.go + 探针内嵌 stub MCP server → rec.jsonl →
    GoRecording45C.java，"禁止手改"；重生成命令在文件头注释。超长常量用
    StringBuilder 运行期拼接——javac 对字面量 + 常量折叠会撞 CONSTANT_Utf8 64KB 上限）。
  - **MCP stub A/B 双端结论**：tools/list、tools/call、notifications/initialized 的
    method+params 逐字节一致（JSON-RPC id 掩码），工具结果 output 逐字节一致。
    **initialize 请求体（⑱）不逐字节**：Go 走 mark3labs SDK（protocolVersion
    2025-11-25、键序 protocolVersion→clientInfo→capabilities），Java 4.1 客户端
    （2024-11-05、protocolVersion→capabilities→clientInfo）——握手语义一致，
    A/B 按各自基线断言；要逐字节就得动 4.1 McpProtocol（决策点，未动手）。
  - **mcpToolRef 的 schema 原字节**：Go InputSchema 是 json.RawMessage（服务器发什么
    哈希什么）；Java 4.1 McpTool 存 JsonNode（紧凑重序列化）。服务器发 compact schema
    时两端 ref 一致；pretty-JSON 服务器会不一致。修法=给 McpTool 加 raw-schema
    字符串通道（动 4.1 文件，留 Owner 决策）。
  - **实录抓回的字节语义族**（Go→Java 最易翻错的四处）：① edit 工具的
    index/overlap 判定是**字节偏移**非字符偏移（中文语料 [3,9] vs 字符 [1,3]）；
    ② sandbox 文件分页 maxBytes 按 UTF-8 **字节**预算；③ shell 流截断切 rune 时
    坏解码逐**字节**输出 U+FFFD（不是每 rune 一个）；④ Go duration 的 "1ms"
    不写 "1.0ms"。
  - **ctx 身份的显式化**：Go catalog.authorize 从 ctx 取 (tenant, principal,
    oauthPrincipal)；Java 在 McpCatalog 构造期捕获，执行路径 authorizeExecution()
    （tenant==0 恒失败）。多引擎共用 registry 时的执行期重校验留 4.6。
  - **装配边界确认**：真 sandbox Manager（SandboxFileSource/Sink/Editor、
    SandboxCommandExecutor/SandboxInstallCommandExecutor、SkillFileStore、
    SkillEnvironment 的实现）与 4.5b 的 SqlQueryExecutor/AnalysisDuckDb/
    GrepChunkSearch/KnowledgeFileMaterializer 的**生产**接线全部随 4.6
    agent_service 装配（4.5c 报告有完整签名清单）；4.5b 行的「执行接线留 4.5c」
    实指装配期，本波确认归 4.6。
- **波 4.6a 补充**：
  - **⚠️ 墙钟 flake 教训（4.2 潜伏、4.6a 复核抓回、已修）**：Go 的
    {{current_week}}/{{yesterday}} 由 types.RenderPromptPlaceholders 用
    `time.Now()` 兜底（placeholder.go L215-218），{{current_time}} 走
    renderPromptPlaceholdersWithStatus 的显式参数——录制常量
    STR_PHS_AUTOFILL（"auto 2026-09-20 Sunday 2026-09-19"）只在录制日可复现，
    09-21 凌晨复核翻红。修法=测试按当日现算期望值（公式等价性由录制日全量绿
    背书），录制常量保留作形状文档，GoRecording.java 不动。**教训：录 Go 实录
    时若输出含 time.Now() 派生段，必须当日把断言写成「静态骨架 + 当日现算
    动态段」，别等翻红**。
  - **Go json 严格单值**：Jackson 默认容忍尾随 token，Go json.Unmarshal 拒绝
    ——GoJsonValues 全局 FAIL_ON_TRAILING_TOKENS。default 分支（整串非 JSON）
    Go 只走 labeled refs，结构化注册是 Java 初版误分支（实录抓回）。
  - **RE2 vs Java 正则再+2**：`$` 锚一律 `\z`；`\s` 用 `[\t\n\f\r ]` 显式类
    （Java 多 `\x0B`）。
  - **zip 中央目录**：TenantSkillSource 手写解析器（对称 Go zip.NewReader，
    含 zip64 EOCD），条目体惰性解压；flag-bit-3 流式条目不支持（探针与生产
    zip.Writer 产物都不用，备案）。
  - **波 3 对账结论**：SkillBundleParser 四常量（20_000/100_000/32MiB/512MiB）
    与 SkillFrontmatter 同源同值零改动；波 3 snakeyaml 宽容嵌套类型 vs 新包
    显式类型错误留决策（未动波 3 文件）。
  - **langfuse seam**：恒 no-op 单例；4.6b 引擎三调用点（StartSpan/
    finishAgentSpan/finishToolSpan）签名照 Go，接真 OTLP 时只换实现不动调用点。

- **波 4.6b 补充**：
  - **实录方法（引擎版）**：/tmp/toolrec46b 复制 Go internal/ + go.mod/go.sum，同包探针
    zz_rec46b_test.go 复用 engine_test.go 的 mockChat、steer_test.go 的 fakeSteerSink/
    summarizerChat（同包 \_test.go 可直接引用），脚本化 chunk 驱动真引擎。**chunk 三件套
    形态**（首轮实录抓的错：只给 Data 不给 ToolCalls → 工具从未执行）：UI pending/progress
    事件由 chunk.Data 驱动，真正执行的调用是 provider 末块的 chunk.ToolCalls（不带 Data，
    避免 pending 去重再发一次 progress）。重生成命令在 GoRecording46B.java 头注释。
  - **掩码纪律（引擎实录特有）**：duration/duration_ms/total_duration_ms 必须**连键带值
    删除**（含前导逗号），不能掩成 0——Go 侧 stub 工具 0ms 被 omitempty 掉键、Java 侧
    真实耗时非 0 键在，留键掩 0 恒不一致；两侧同删后逐字节可比。时间戳→"TS"、
    `<current_time>` 日期→DATE（含 `<` 转义形态；Java 测试当日现算后同款掩码——
    4.6a 墙钟教训的标准应用）。**事件 id 掩 uuid 前缀保后缀**（-tool-call-pending 等
    形态仍是断言的一部分）。
  - **⚠️ Go interface{} + omitempty 的 typed-nil/空切片语义（实录钉死，Java 复刻）**：
    ① complete 事件的 `usage` 键**恒输出**——turnUsage 返回 nil \*TokenUsage 装进
    interface{} 是 typed-nil，interface 非 nil → 不省略，输出 `"usage":null`；Java 用
    NullNode 过 NON_EMPTY。② `agent_steps` 同理**恒输出**（空切片→`[]`）；对比：
    声明为**切片类型**的字段（knowledge_refs []interface{}）len 0 才省略。event 包不可改，
    空列表用 `RawValue("[]")` 过 NON_EMPTY（@JsonValue 包装器不行——JsonValueSerializer
    把空判定委托给 List 序列化器照样省略）。**4.6d 消费 complete 事件时 usage 按
    `instanceof TokenUsage` 判别（NullNode=无用量）**。
  - **流终止约定（Java 侧定义，对齐 4.0 生产者）**：Go 引擎 `for chunk := range stream`
    以 channel 关闭收尾，done 只是数据；Java BlockingQueue 无关闭——引擎在
    **`done=true` 且非 THINKING** 的元素处收束（4.0 生产者的终态元素恒为
    ANSWER/ERROR+done；THINKING+done 是生产者中途补的 thinking-done 标记，其后仍有
    分片，照 Go 继续消费）。首轮实录抓回：见 done 就 break 会把思考通道后面的答案
    全部吞掉。
  - **实录抓回的真缺陷（4.5a 潜伏）**：MessageSanitizer 合并连续同角色消息时**就地
    setContent 改共享对象**——Go 的 `result[last].Content += ...` 写在切片的**结构体副本**
    上、调用方列表不动；Java 列表持引用，多轮场景下同一条用户消息被逐轮反复追加
    （引擎实录 chat-shape 抓回：第二轮 user content 里出现两份 runtime_context）。
    修法=合并与孤儿 tool-result 改写都**落成新对象**（shallowCopy）。**教训：Go 切片
    持值语义翻译成 Java 引用列表时，一切就地写都要过一遍"写的是谁的副本"**。
  - **sessionId 参数 vs 字段**：Go 引擎有 sessionId 字段（构造时给，emitContextCompacted
    用它）但 Execute 的 sessionID 参数才是各 emit 点的正文——closeAnswerStream/
    analyzeResponse 若误用字段，SSE 帧的 session_id 会变成装配值而非请求值（实录抓回）。
    另外 **complete 事件的 AgentCompleteData.SessionID Go 恒不赋值（=""）**——照抄。
  - **cancel seam**：Go 的 ctx 取消检查（轮首/LLM 调用后/流停顿）收敛为
    `setCancellationSource(Supplier<String>)`（null=存活，非 null=ctx.Err().Error() 原文）；
    4.6d 的 stop 链路接线。取消时抢救 final answer 后抛 AgentEngineException(cancelErr, state)，
    state 挂异常上（Go 的 `return state, ctx.Err()` 对应物）。
  - **并行工具调用**：errgroup(8)+CanRunConcurrently 白名单→虚拟线程+Semaphore(8)，
    写屏障=顺序落段。租户/主体在引擎线程 TenantContextSnapshot.capture()、虚拟线程
    replay()+finally clear（纪律 #1 的标准实现）。runToolCall 的 principal 显式传参。
  - **compaction 引擎接线**：无窗口（MaxContextTokens≤0）时 Compactor.create 返回 null=
    Go 的 nil 压缩器，全部 settings 访问走 activeCompactionSettings()（nil receiver→零值
    settings 的对应物）；compactionExhaustedAt 挂消息数、freed < tokens_before/20 即耗尽。
  - **已知差异（备案）**：① Go json.Unmarshal 错误文案（runToolCall unrepairable 形态
    的 "invalid character ..."）与 Jackson 不同——实录只锁静态骨架（前缀+两段固定提示），
    同 4.5c 备案；② formatToolHint 遍历 args 取第一个 string 值——Go map 迭代随机、
    Java 取插入序首个，单参数工具等价（测试用单参数）；③ PromptPrefixFingerprint/
    WithLLMCallMetadata（provider 缓存观测 ctx 标注）未翻——Java LlmChatClient 无 ctx
    形参，无消费点；④ LLM 瞬态重试的 Thread.sleep(1s/2s) 保留；⑤ 工具执行超时由
    ToolExecContext.execTimeoutMillis 传给工具面自执行（Go 是 ctx.WithTimeout 包裹），
    engine 不再包一层。
  - **决策点（留 4.6c/4.6d/Owner）**：① SteerSink 实现方（session 侧 PollSteer/
    PersistSteerMessage back half）随 4.6d 装配，接口已定（mentionedItems 为不透明
    Object 透传，实现方用 session.domain.MentionedItem 还原）；② Registry 加了一个
    公开重载 registerContextChunk(6 参)（ChunkReference 是包内类型，引擎拿不到构造面）
    ——4.6a 文件的唯一改动，纯新增无行为变更；③ tools/MessageSanitizer 两处
    copy-on-merge 修复（见上，真缺陷非偏好）；④ domain/ToolResult.setError null 归一
    （Go 零值 "" 语义，消费侧 isEmpty 直用）；⑤ persist.go 的 SanitizeToolDataForPersist
    本波以私有静态落在引擎（唯一消费点 emitToolOutcome），SSE 回放/DB 存储的其余
    persist 函数随 4.6d 落 tools 包时再收敛；⑥ AgentConfig 本波只收引擎消费字段
    （其余 Go 字段随 4.6d agent_service 装配按需补），未进 JsonContractRoundTripTest
    （引擎内部类型，不落 jsonb/响应体；JSON 形状由实录回放钉住）。
- **波 4.6c 补充**：
  - **实录方法**：/tmp/toolrec46c 复制 Go internal/ + go.mod/go.sum，同包探针
    zz_rec46c_support_test.go + zz_rec46c_test.go 复用 Go 既有 *_test.go 的 fakes
    （8 个测试文件里有 stub service/LLM），驱动管线纯函数与各插件 OnEvent →
    rec46c.jsonl → GoRecording46C.java（禁手改，重生成命令在头注释）。
    掩码两侧同款（uuid/事件 id 8-hex 前缀/duration_ms 连键删/DATE/WEEKDAY/PORT）。
  - **实录抓回的真缺陷（三件）**：① merge_expand 的 prevContent 是
    `JoinChunkContent(prev, prevContent)` **替换**语义，写成 append 会文本翻倍
    （链式邻居展开组抓回）；② expansion/extract 的 `len()` 是 **UTF-8 字节语义**
    ×3（len(seg)>5、len(s)<3、len>2）——「知识库」3 字符=9 字节入选，按 char
    翻译漏变体；③ 引号字符类经 hexdump 验证只有直引号+「」『』，无弯引号
    U+2018/2019。
  - **PluginError 同一实例语义**：Go 的 9 个预定义错误是包级单例，
    `stageErr == ErrSearchNothing` 是指针比较——Java 保留同一实例 + 引用比较，
    管线的"错误类型分流"依赖它，别重构成值相等。
  - **search_parallel 合并序备案**：Go 侧 map 迭代/并发合并序不可控，
    Java 恒 chunk→entity（LinkedHashMap 保出现序）——实录锁稳定段；
    `EntityKBIDs/EntityKnowledge` 用 LinkedHashMap。
  - **jieba 接缝（4.4 既有）**：expansion 组按实录注入固定分词表
    （QueryTokenizer.setSegmenter），未命中回落二字滑窗。
  - **web_fetch 实录差异**：Go 探针的本地 stub 被 web_fetch 的 SSRF 守卫拒连，
    实录锁的是"抓取失败→内容不变"的管线行为；Java 用 127.0.0.1:9 复现同语义，
    不依赖进程级 SsrfGuard 状态。
  - **4.6d 装配清单（PipelinePorts 11 seam）**：ModelService/KnowledgeBaseService/
    KnowledgeService/ChunkRepository（含 ListChunksByParentIDs）/KnowledgeRepository/
    KnowledgeBaseRepository/MessageService（updateMessageImages/
    updateMessageRenderedContent 需补方法或 adapter 直写 mapper）/MemoryService
    （可直接委托 memory.service.MemoryService）/WebSearch/RetrieveGraphRepository/
    DataAnalysisSessionFactory（实现须放 agent.tools 包内触达包私有
    loadFromKnowledge）；TenantService/SessionService/WebSearchStateService/
    WebSearchProviderRepository 是占位（Go 侧只判 nil 或存而不读）。
    进度窗口：PipelineProgress.begin/endRetrievalProgress、
    shouldCloseRetrievalProgress、lastConsolidatedRetrievalStage。
- **波 4.6d 补充**：
  - **stub LLM 全链路 A/B 方法**：scripts/stub-llm-server.py（脚本化 OpenAI 兼容 stub，
    场景=chat/long/echo）+ dev PG seed 模型行（base_url 指 stub 端口）→ 双端同打同一
    stub 进程，SSE 帧序掩码（uuid/事件 id 前缀/时间戳/耗时数字）后逐字节；A/B 脚本
    scripts/ab-qa46d.sh（XXX_TARGET_PORT/XXX_OUT_DIR 参数化惯例）。
  - **gin binding 文案契约（本批新钉）**：binding:required 校验错走
    `Key: '...' Error:Field validation...` 形态、JSON 解析错走 Go encoding/json 文案
    ——Java 必须 @RequestBody String 手工绑定再走 GoJsonBindError，Spring 直接绑
    对象会把 400 变 500 且文案不同。
  - **虚拟线程 TenantContext 再+1**：executeQA 异步段（modelService 按租户可见性取
    模型）首跑取不到模型——capture/replay 修复；引擎/管线/HTTP 异步段都要过一遍。
  - **kse-unknown-kb 的 500 信封**：1007 包 "error code: 1003, error message:
    knowledge base not found"（Go resolveKBTenant caller-租户回落 + 检索插件 1003
    传播）——双层错误码包携形态，别按普通 404 复刻。
  - **批次教训（复现+解法确认）**：agent/chatpipeline 与 apikey/auth 同批 →
    Mockito attach 假红 110 条；分批（agent+chatpipeline 一批、apikey+auth 一批）
    即全绿。**4.6 起 B1 批拆为：agent/chatpipeline 一批；其余 Spring 包按 B1b~B4**。
  - **已知缺口（移交收尾/波 5）**：共享 agent QA 解析 GetSharedAgentForTenant
    （resolveAgent 只走 own-agent，shared 恒 404）；HybridSearch 执行面（向量/关键词
    检索引擎——adapter 留空/1003 两形态，纯聊天不受影响）；ArtifactCollector/
    rewriteArtifactReferences/VLM Predict 执行体（dev 两侧同形 no-op 分支）；
    models/{id}/debug、sessions/:id/local-browser ×2、sandbox_terminal_ws+bridge、
    embed 公开 QA 委托面。

- **走查抓回（2026-09-22，手动验收新会话场景）**：PluginSearch 合并整库检索路径对
  `hybridSearch` 的 null 返回（无可用检索管道，对照 Go nil 切片）裸 `addAll` →
  NPE（"Cannot invoke Collection.toArray() because c is null"），QA 以
  PipelinePortException 收场。修复 = null 显式跳过（append(dst, nil...) 的 no-op
  语义），回归用例 `SearchRecordingTest.searchByTargetsNullHybridResult`（stub 新增
  hybridNull 集合模拟 null 返回）。**教训复发确认**：Go nil 切片语义清单要覆盖
  「服务返回 nil → 消费方 addAll/遍历」全链路，不只生产端。
