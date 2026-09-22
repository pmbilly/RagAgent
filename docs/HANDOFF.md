# 交接文档（新会话接手用）

## 0.0 阶段 7 收官（2026-09-22，models/{id}/debug 落地——路由对账真缺口清零）

**做了什么**：翻译最后一条功能性真缺口 `POST /api/v1/models/{id}/debug`
（前端「模型测试」按钮），对照 internal/handler/model.go DebugModel 全文：
multipart 入参（input 64KB 按 UTF-8 字节）、options 手工解析复刻 Go
UnmarshalTypeError 文案、五类模型运行时工厂（ModelRuntimeFactory：embedding/rerank
走状态闸门、chat/vlm/asr 直取——不对称照抄 Go）、request preview（gin.H 键序
TreeMap + options struct 声明序 LinkedHashMap）、chat 流式消费（done 后短超时
排空，模拟 channel close）、ASR/VLM/embedding/rerank 四族响应整形。
**验收**：24 场景 golden 全是 Go 实录（租户 10009，`md-*.json`）+
ModelDebugContractTest 24 用例全绿 + **双端 stub A/B 两轮 24/24 逐字节 MATCH +
HTTP 状态码 24/24 一致**（掩码仅 elapsed_ms）+ 全量五批回归（B4 一条
TenantSkillPythonVerifierTest 为本机 pip 环境性失败，干净树同样挂，与本阶段无关——
见 known-issues/07）。
**A/B 抓回并修复共享 LLM 栈两个真缺陷**（详情 known-issues/07-model-debug.md）：
①`ConcurrencyChatClient` 在首个 done 后截断流——Go 包装器是 range-until-close
全量转发（含带 usage 的终态事件），修复为 done 后 forwardTail 短窗口转发；
②流终态事件的 finish_reason 在 Go 分路径携带（SDK 路径带 / 裸 HTTP 路径不带）——
`ThinkingStrategy.apply` 恢复 boolean 返回（= 是否注入字段，对照 Go useRawHTTP），
`Outbound.rawPath` 标记复刻分野。SSE/agent 消费面在首个 done 即收束，回归不受影响。

**整体状态更新**：route-recon 交集 387，**真缺口只剩 `/swagger/{}`（Go 工具路由，
明确非翻译目标）**。前端「模型测试」按钮现在可用。provider-XDEP 族不变（γ3 九渠道 /
W5δ 终端 / VLM ollama+weknoracloud 界面 / tenant_skill install 管线体等，见 §0.1 清单）。

> 最后更新：2026-09-22 · **基线：阶段 7 收官（models/{id}/debug 全量 + 共享栈两缺陷修复，见 git log 顶部）· golden 1,719+24**
> 端点覆盖（2026-09-22 程序化对账 `scripts/route-recon.py`：交集 387）：
> **真缺口候选 1 条** = `/swagger/{}`（Go 工具路由，非翻译目标）

## 0.1 总验收完成（2026-09-22，存档——双端起服 + 无掩码 A/B 抽样 + 全量分批绿，最新实况见 §0.0）

**收尾补录（同日第三批，γ3 深化）**：feishu/wecom AES 加密验签族（crypt 族全量：
AES-256-CBC + PKCS#7 + SHA1 四元组签名，密文/签名 fixture 录自独立 Go 程序
`contracts/w5g3b-im-crypt.tsv`，5 测试绿）；slack 确定性核心（URL 挑战回显、
app_mention/message 分支 bot+subType 过滤、thread_ts 双 ID 语义、<@U…> 提及
剥离、mattermost payload 双态解析，6 测试绿）；**ArtifactReferenceRewriter**
（artifact_reference.go 270 行全文——代码段保护、括号配对扫描、sandbox: 前缀/
百分号解码/path.Base 归一、重名保首、handle 优先，20 条 Go overlay 实录逐字节
MATCH，且实录抓回移植下标 bug 一枚）。

**收尾补录（同日第四批，执行体确定性面收官）**：三批再进——
①**ArtifactCollector 排水本体**（ac06c71/0c01fc9，artifact_collector.go 全文）：
Collect/CollectWithNotify 全流程（降级链全 null、(path,mtime) 已知集去重、
accept 过滤、二次尺寸守卫、UUID 命名上传、resource 绑定尽力而为、
ReferencedHistory 引用重绑），7 测试绿。**教训**：构造依赖无 bean 的类不能标
@Service（0c01fc9，否则拖垮全部 @SpringBootTest 上下文）。
②**VLM Predict 客户端**（662222ec，vlm/remote_api.go+vlm.go 确定性面）：
ConfigFromModel（local→ollama 缺省）、multipart 请求体（data-URI/detail=auto/
max_tokens=5000/temp 0.1）、reasoning/GPT5 整形（max_completion_tokens 平移+
采样清零）、no choices 与 length 截断错误族、MIME 嗅探，5 测试绿（stub transport）。
③**tenant_skill reaper 状态机 + 快照台账**（82b16d7）：ReapStuckRuns 逐行
（installing 的 serving||!known → 治愈 ready、known&&!serving → failed；
removing 的保守三分支）+ 快照台账四方法 + ListStaleInstalling，H2 全绿。
**最终验收（二轮）**：全量五批再全绿（B2 首轮 browserskill 假红单包重跑即绿——
既有批次内假红族）；route-recon 终核交集 387、真缺口 2 不变；双端活进程重新
登录后 agents 列表逐字节 MATCH。

**收尾补录（同日第二批）**：三类剩余缺口又推进四批并全部收官——
①**检索引擎批**（3cb4b2e）：HybridSearch 执行面全量翻译（pgvector halfvec HNSW
向量检索 + ParadeDB BM25 关键词 + RRF 融合 + FAQ 迭代/负例 + 富化装配 +
GetEffectiveEngines×RETRIEVE_DRIVER 闸门），QaWiring seam 与 hybrid-search HTTP
端点接入真实执行。**dev PG 双端 A/B 逐字节 MATCH**：无 RETRIEVE_DRIVER 场景双端
data:null 一致；RETRIEVE_DRIVER=postgres 场景向量-only 与 RRF 混合两条路径的
id/score(浮点字节)/content/排序/富化字段全同。
②**W5δ provider 终端**（ea68238）：中性层全量（RemoteTerminalOptions 五旋钮、
事件/会话/能力接口、idle 15m 钳位、TTL 刷新钳位、PtyInputCoalescer 突发聚合），
provider SDK 传输标 XDEP（dev 双侧同落 INTERNAL）。
③**γ3 验签核心**（55686ac）：slack/dingtalk 签名向量录自独立 Go 程序
（contracts/w5g3-im-adapter-signatures.tsv），telegram 常时比较/mattermost token
语义钉住；各平台出站发送与 feishu/wecom AES 验签族属后续。
④**最终验收**：全量五批再次全绿（含 im.runtime/retrieval/sandbox.runtime.terminal
新增测试），route-recon 终核交集 387、真缺口 2 不变。

**做了什么（本段收尾）**：W5γ1（im 地基：types/adapter 接口/命令族/think/
tool_display/qaqueue/supervisor/ChannelSession，98 键 Go 实录钉字节契约）+
W5γ2（ImService 全量：HandleMessage 管线/executeQARequest 三态输出/handleMessageStream
冲刷+holdback/runQA 事件收集/会话解析双模式/命令副作用；回调控制器全接线；
租户上下文按纪律显式传递）相继收官后，执行总验收：
①**全量分批五批绿**（B1a agent+chatpipeline / B1b common+event+apikey+audit+auth /
B2 六包 / B3 八包 / B4 十四包，含新增 im.runtime）；②双端起服（Go :8080 +
Java :8082 同连 dev PG）；③**无掩码逐字节 A/B 抽样**：sessions/agents/
im-channels/storage-backends/web-search-providers/vector-stores/sandbox-configs/
tenants-all 九族 GET + sessions pin 写路径（pin 响应/列表回流/还原）全 MATCH；
④route-recon 终核：交集 387、真缺口候选 2 不变。
**验收抓回并修复三个真缺陷**（详情见 known-issues/06-wave-5.md「总验收冒烟」）：
①SessionListItem 的 NULL 字符串列（Go 零值 "" vs Java null）——列表 DTO 也要过
零值清单；②storage-backends 时间戳时区（Go 原生 marshal 出 UTC 的 Z vs Java
本地 +08:00）——`GoTimeSerializer.Utc` 变体；③im-channels 空列表 `null` vs `[]`
（GORM nil 切片）。

**整体改造状态的诚实清单（接手必读）**：
- **HTTP 面已全部翻译**：route-recon 交集 387，Java 无缺口；唯一留白
  models/{id}/debug 属阶段 7（控制器 404 占位 + 规则登记，Go 侧同路径也只服务
  调试用途）。
- **执行体仍留四处 provider-XDEP 缺口**（dev 环境双侧都到不了真实后端，接缝与
  测试已备，真实部署时补齐）：
  1. **γ3 九渠道适配器**（Go ~10.7k 行）：wecom/feishu/yunzhijia/dingtalk/wechat/
     qqbot/mattermost/telegram/slack 的平台客户端。γ2 的 AdapterFactory 注册面
     已就位（`imService.registerAdapterFactory`），回调控制器/管线/命令族全部
     可用；平台签名验签与载荷解析是各适配器的可单测核心。
  2. **W5δ provider 终端执行体**（Go ~1.3k 行）：cube/e2b/docker 远程 PTY →
     W5d 已留 SessionTerminalService/TerminalBridge 接缝；RemoteError 分类器已翻。
  3. **检索引擎批 HybridSearch 执行面**：QaWiring 的 hybridSearch/getQueryEmbedding
     返回空（两侧无 embedding 模型部署行为一致的备案形态）；embedding 客户端
     4.4 已翻，缺 pgvector 检索 + RRF 融合 + 模型解析接线。
  4. **执行体批 ArtifactCollector/VLM Predict**：dev 两侧同形 no-op，真部署才需要。
- tenant_skill install 管线体（播种/agent/快照/指针切换/transcript/steer/reaper）
  同属 provider-XDEP（沙箱后端不可达）；verify 门（W5β）与全部状态机/仓库面已翻。

**下一步**：按上述 1→4 顺序补执行体（每处都是独立批次，验收口径=签名/解析/错误族
单测 + 双端 stub 对拍；成功路径标 XDEP）→ models/{id}/debug（阶段 7，Owner 决策）。

> 最后更新：2026-09-22 · **基线：终验收收官（ArtifactCollector + VLM Predict + reaper/快照台账 + γ3 确定性面全量，见 git log 顶部）· golden 1,719+**
> 端点覆盖（2026-09-22 程序化对账 `scripts/route-recon.py`：交集 387）：
> **真缺口候选 2 条** = `/swagger/{}`（Go 工具路由，非翻译目标）+ `models/{id}/debug`（留阶段 7，规则已登记、控制器 404 占位）

## 0.2 W5α3 已收官（2026-09-21，存档——波 5 第三子批：FileAccessResolver 跨租户双授予）

**做了什么**：消息文件代理的跨租户授权收口成 Go access/files.go
AuthorizeMessageFile（L154-230 逐行）+ message_files.go 全文——
①shared-agent 授予（`AgentShareService.getSharedAgentForTenant` +
`ResourceCatalogService.getMessageFileBindings`（catalog 层负责
reference→resource.ID 解析）+ `SharedAgentKBScope.allows` + `apiKeyAllowsKb`
（try/catch 对照 Go `== nil` 判定），消息 artifact 绑定独立放行）；
②org-shared KB 证据链（`collectSharedKBEvidenceIDs`：knowledge_references +
agent_steps 的 `collectKBEvidenceFromValue` 递归 + kb_shares viewer +
存活 resource_bindings，全程 fail-closed）。`FileAccessResolver` 构造器新增
4 依赖（AgentShare/KbShare/Knowledge/KnowledgeBaseService）。
验收：**13 场景 18 个 w5f-* golden（Go 实录，record-w5f-golden.sh 幂等种子）+
W5fCrossTenantFileContractTest 3 方法 + 真 PG A/B 两轮 18/18 ALL MATCH**
（ab-w5f.sh，无掩码）；storage/session/org/knowledge 回归 477 绿。
**golden 抓回两个真契约**：①ToolCall.Result.Output 命中的 handle **不能**
归因到兄弟 Data 的 knowledge_base_id（Go 先 Output 后 Data、kb 上下文只沿
map 下行继承）——首录 evidence-steps 403 是种子设计错而非翻译错，证据串
须与 knowledge_base_id 同 map；②API-Key 主体读 web 用户会话恒 404
（owner = `api_tenant_key:<tenant>:<keyID>` 精确匹配，
runtimeMayBypassAdminConsoleRead 仅放行 key-owned 会话）——授予循环的
apiKeyAllowsKb 段必须用 key 自有会话才触达。台账 conventions §8「W5α3」，
坑 §9「W5α3」（known-issues/06-wave-5.md）。

**下一步**：W5β（tenant_skill verify + progress，Go 侧位置见 route-recon
登记）→ W5γ（im 执行体：γ1 地基 / γ2 service / γ3 九渠道适配器，波 5 最大
块）→ W5δ（provider 终端执行体，W5d 已标 XDEP）。顺序见 `docs/W5-plan.md`。

## 0.3 W5α2 已收官（2026-09-21，存档——波 5 第二子批：QA resolveAgent 共享分支）

**做了什么**：knowledge-chat/agent-chat 的 resolveAgent 共享分支全量落地——
共享优先（err 吞掉）+ source==0 才回落 own + source!=0 未命中 404
"Shared agent not found"；`TenantContextSnapshot.withTenantId`（对照 Go
WithExecutionTenant：换执行租户不换身份）在 QA 异步段 replay 前切换；
agentTenantID 取 effectiveTenantID（=共享 agent 实际归属租户，非请求 source 参数）；
sharedAgentReadOnly 接入 rc→QaRequest（下游 SessionAgentQaService 既有消费点激活）。
GoJsonBindError 新增字段级类型错误仿真（登记表 Struct.field→Go 类型，
"json: cannot unmarshal string into Go struct field ... of type uint64"）。
验收：**3 条 w5q-* golden + W5qSharedAgentQaContractTest + 真 PG A/B 两轮
5/5 ALL MATCH**（ab-w5q.sh：2 SSE 正路径掩码对拍——模型行只在源租户 10005
是执行租户切换的判别锚——+ 3 负面逐字节）；session/common/event 回归 467 绿。
备案：ApplyBuiltinAgentLocalization 随 agentm 装配层统一补齐；
access.WithSharedAgent 的 KB grant 收窄随检索面专项收口（检索租户已取
agentRow.tenantId，殊途同归）。台账 conventions §8「W5α2」，坑 §9「W5α2」。

**下一步**：W5α3（FileAccessResolver 跨租户恒 403 桩 → Go access/files.go
AuthorizeMessageFile 双授予路径：resourceAccessibleViaSharedKB 证据收集
（collectKBEvidenceFromValue 递归）+ GetSharedAgentForTenant +
GetMessageFileBindings）→ W5β/γ/δ 按 `docs/W5-plan.md`。

## 0.4 W5α1 已收官（2026-09-21，存档——波 5 首子批：共享 agent 读面收口）

**作战计划**：`docs/W5-plan.md`（波 5 顺序 W5α 共享 agent 收口 → W5β tenant_skill →
W5γ im 执行体 → W5δ provider 终端执行体；W5α 内部 α1 读面 / α2 QA / α3
FileAccessResolver）。本批 = α1。

**做了什么**：KB list / knowledge batch / knowledge search 三读端点的 `agent_id`
分支全量落地——org 侧基元（`SharedAgentKBScope` scope 快照、`AgentShareSources.parse`
逐字对齐 Go strconv 文案、`AgentShareService.getSharedAgentForTenant` 双路径哨兵、
`KbShareService.checkTenantKBPermission`）+ knowledge 侧
`SharedAgentAccessResolver`（401/400/403/500 四态错误映射 +
filterKnowledgeBasesForSharedAgent / filterKnowledgeByAgentScope）+ 三控制器
agent 分支接线（scope 空短路、effectiveTenant 切换、ResolveKB 三段授予
own→org-share→agentScope、search 的 all 模式列源租户 KB 只留 document 型）。
验收：**21 条 w5s-* golden（Go 实录，record-w5s-golden.sh 幂等种子）+
W5sSharedAgentContractTest 3 方法 + 真 PG A/B 两轮 21/21 ALL MATCH 零 DIFF**
（ab-w5s.sh，掩码仅时间戳）；knowledge/org 回归 174/174。
**golden 抓回四个真契约**：①`Long != Long` 装箱比较恒不等（getSharedAgentForTenant
恒 403）；②`storage_backend_id` omitempty 空值键缺席（buildKBResponse 走实体
json.Marshal→map）；③GORM 对 NULL 列跳过 Scan——indexing_strategy NULL → 零值，
IsZero→Default 只在 EnsureDefaults 调用点（faq+faq_config NULL 提前 return 保持
零值）；④FAQ faq_config 物化 question_answer/combined。台账 conventions §8「W5α1」，
坑 §9「W5α1」（known-issues/06-wave-5.md）。

**下一步**：W5α2（QA resolveAgent 共享分支：Go qa.go L548-600；Java
`KnowledgeQaController.resolveAgent` 只有 own 分支；effectiveTenantID/
sharedAgentReadOnly 下游接线 + ApplyBuiltinAgentLocalization 装配层补齐——
CustomAgentService.applyLocalization 私有，用 BuiltinAgentRegistry 原语）→
W5α3（FileAccessResolver 跨租户恒 403 桩 → Go access/files.go 双授予路径）。

## 0.5 W5d 已收官（2026-09-21，存档——沙箱终端 WS + local-browser + embed QA 委托）

> 半成品的实况记录（接手可跳过）已被本节替换；当时的分析底稿在 git 历史里。

**做了什么**：①修 context（`AuthFilter` 非 bean 被注入控制器 → 按方案②抽
`WsAuthSupport` @Component，AuthFilter 通道 2 同委托）；②**实测翻案**——半成品
「101 后写 Servlet 裸流」在 Tomcat 上不成立（1xx 无实体、写入被静默吞，探针实证），
改 **Servlet 3.1 `request.upgrade` + WebConnection 裸流**
（`TerminalWebSocketUpgradeHandler`，ThreadLocal arm→init 交接，租户显式捕获）；
③补登记（RBAC 三条 VIEWER + APIKeyRoutePolicies 三条 chat + APIKeyGate exclude
WS 路由）；④补 embed QA 三端点（knowledge-chat/agent-chat 委托 +
patchEmbedChatPayload、files 委托 FileProxyService）；⑤验收：**24 个 w5d-* golden
（Go 实录）+ W5dTerminalEmbedContractTest 5 方法 + 真 PG A/B 两轮 26/26 ALL MATCH
零 DIFF**（ab-w5d.sh，含 ws-handshake-live 双端实测握手逐字节一致）。
**golden 抓回真缺陷**：plainStatus 空体 404 被 Tomcat ErrorReportValve 补默认体
（W5c 潜伏偏差一并修复）。台账 conventions §8「W5d」，坑 §9「W5d」
（known-issues/06-wave-5.md）。provider 终端执行体（远程 PTY）标 XDEP 随波 5。

**复核基线（本批分批全量）**：W5d 受影响六包 479 绿（session/embed/storage/
browserskill/apikey/auth）+ agent/chatpipeline 批 + 其余包分批全绿（Gradle
Test Executor 300 秒窗口限制下按 B1 纪律分批；agent/chatpipeline 单独一批）。

## 0.6 W5β 已收官（2026-09-21，存档——tenant_skill verify 族 + progress 收口）

台账见 conventions §9「W5β」与 known-issues/06-wave-5.md；W5α1~α3/W5d/W5a~W5c
的存档小节随历史提交保留在 git 历史与本文件下方。

## 0.7 接手状态（2026-09-21，收尾批 W5a/W5b/W5c 已收官——存档，最新实况见 §0.0）

**W5c 收尾批（2026-09-21，文件代理面收官）**：Go `internal/router/files.go`(693) 全文
翻译 → `com.ragagent.storage.fileserve` 新包 8 文件（FileProxyService/FileAccessResolver/
FileTransport（**http.ServeContent 移植**：Range 206/416、If-* 预判、FormatMediaType
RFC 2231）/StoragePaths/LocalFileContentService/BackendScopedFileService/
StorageFileResolver/ResourceCatalogService）+ 4 控制器（/files、/r/*、presigned
GET+HEAD、presigned-preview、KB-scoped、message-scoped）。验收：85 条 w5c-* golden +
真 PG A/B 两轮 **75/75 ALL MATCH 零 DIFF**（ab-w5c.sh：27 JSON 逐字节 + 21 二进制
body + 6 HEAD 形态）。golden 抓回六个真契约（presigned 裸 inline/attachment、
presigned-preview 的 storage:// 包装、If-None-Match 无 ETag 照常 200、绑定缺失 403
先于 404、FormatMediaType UTF-8 字节百分号化、HEAD 404 是 gin NoRoute 形态）。
**TestSchema 新增 resource_bindings/resource_access_grants 两表**（迁移 000069 硬依赖，
主会话复核接受——"TestSchema 以迁移为准"先例）。台账见 conventions §8「W5c」§9「W5c 补充」。

**W5b 收尾批（2026-09-21，initialization 模型初始化向导收官）**：补齐
initialization 系统级 14 条（ollama 管理 6 + 模型连通性测试 5 + 抽取 3）。
OllamaService 单例 bean 首次落地（对照 container.Provide）；下载任务=进程内存
（无新表）；ASR=薄复刻唯一 provider 的 seam（真实出站，go-openai 错误文案字节级
仿真）；multimodal 实际打 DocReader（VLM 参数不参与调用）。
验收：45 条 w5b-* golden + W5bInitializationContractTest + 真 PG A/B 两轮
80/80 ALL MATCH（ab-w5b.sh，双端同指 stub-llm/stub-ollama + 同一 dev docreader）。
台账见 conventions §8「W5b」，坑见 §9「W5b 补充」（validator 键用 Go 字段名、
同 handler 两种时区路径、A/B 抓回 mm data 节点字母序缺陷）。

**W5a 收尾批（2026-09-21）**：A 部分 = WebConfig RBAC 漂移修复（拦截器 pattern 补
chunks/messages/faq/knowledge-chat/agent-chat/knowledge-search 六前缀 + sessions 23 条/
messages 4 条/chunks 写族 7 条规则——此前多为空转/缺席）；B 部分 = 13 条小散路由
（auth logout/refresh/switch-tenant + tenants CRUD 4 + KB 标签 4 + IM 回调 2）。
验收：56 条 w5a-* golden + W5aSundryRoutesContractTest + 真 PG A/B 三轮 56/56 ALL MATCH
（ab-w5a.sh）。台账见 conventions §8「W5a 收尾批」，坑见 §9「W5a 补充」
（tag.SeqID 回填、refresh 同秒 JWT 掷硬币、PathTenantMatch 死代码、mcp×storage
测试互踩为新发现）。

**波 4 已全部收官（4.6 四连批）**：4.6a f7a2b98（modelcontext+skills+langfuse seam，
193 实录）→ 4.6b f345658（AgentEngine 引擎核心，74 实录 + MessageSanitizer 真缺陷
修复）→ 4.6c 755bff4（chatpipeline 43 文件，263 实录 + 三个 len/拼接真缺陷修复）→
4.6d 29b41b9（chat 三入口 HTTP 面 + AgentStreamBridge SSE 桥 + PipelinePorts 11 seam
装配 + SteerSink/follow-up + stub LLM 全链路 A/B 15 场景 × 2 轮全 MATCH 零 DIFF）。

**复核基线（主会话独立复跑）**：W5c 受影响六包 692 绿（storage/storageurl/knowledge/
session/apikey/auth）+ W5b 批 303 绿（agentm/llm）+ W5a 批 226 绿（auth/knowledge/im）
+ session 257 绿；波 4 收官时小包批合计 3,985 绿。golden 1,649。

**⚠️ 批次教训（4.6d 复发确认）**：agent/chatpipeline 与 apikey/auth 等 @SpringBootTest
包同批 → Mockito attach 假红（110 条）；**B1 批拆两批跑**（agent/chatpipeline 一批、
Spring 包按 B1b~B4），分批即全绿。其余处置同 conventions §9「波 4.5b 补充」。

**剩余缺口清单（整体改造收尾，按批派）**：
1. ~~W5d~~ ✅ **已收官（2026-09-21，见 §0.0）**：终端 2 + local-browser 2 + embed QA 3
   全部落地并验收（24 golden + A/B 两轮 26/26 零 DIFF）。
   （models/{id}/debug 仍留阶段 7：规则已登记、控制器 404 占位）
2. **波 5**：im 执行体（im service.go 3,453）+ tenant_skill_* 收口 +
   shared_agent_access→tools + 共享 agent QA 解析（GetSharedAgentForTenant，
   4.6d 备案）+ 波 4.6d 其余移交缺口 + **provider 终端执行体**（W5d 的 XDEP 接缝：
   cube/e2b/docker 远程 PTY，SessionTerminalService 的 openOnResolved/provisionAndOpen
   与 TerminalBridge 泵组的生产接线）
3. **检索引擎批**：HybridSearch 执行面（向量/关键词检索实质执行——4.6d adapter 留
   空/1003 两形态，纯聊天路径不受影响）
4. **执行体批**：ArtifactCollector/rewriteArtifactReferences/VLM Predict 的生产装配
   （dev 部署两侧同形 no-op，真部署才需要）
5. **专项/Owner 决策遗留**：mcp×storage SsrfGuard 互踩（既有问题，W5a 发现）；
   ⑱ MCP initialize 契约对齐、SkillEnvironment 位置、波 3 SkillFrontmatter snakeyaml
   宽容类型、TenantService 占位是否变真、ConversationProperties 多环境接线

## 0. 一句话背景

把 WeKnora 后端从 Go（Gin/GORM）**全面翻译**成 Java（Spring Boot 3 + JDK 21 + MyBatis-Plus）。
前端**零改动**，因此验收标准是「响应与 Go 实录**逐字节一致**（golden 契约测试）」，
而不是"代码看起来对"。

> **原仓下线声明（2026-09-22）**：Java 仓运行/构建已不依赖 Go 仓——
> ①基础设施全部由本仓 `docker-compose.yml` 自起（postgres/redis/docreader 三容器，
> docreader 用官方镜像 `wechatopenai/weknora-docreader:latest`，proto 契约已本仓化）；
> ②密钥/连接值已迁至本仓 `.env`（gitignored；dev-env.sh 优先读本仓，WeKnora 路径
> 仅为历史开发机兼容回落）；③server/frontend/gradle 源码零 Go 仓路径引用。
> 仅存的 WeKnora 引用在**验证工具脚本**（go-server-up.sh/ab-*/record-*，69 个脚本
> 共享 dev-env.sh）——用途是对拍 Go 行为，等价性已建立后随原仓下线自然退役，
> golden 契约测试（1,719+ 已入库）不受影响。
- Java 仓：`/Users/billy/ragagent-java`（可写）
- Go 仓：`/Users/billy/WeKnora`（**只读**对照，别改任何源文件；`scripts/go-server-up.sh` 会往
  `bin/` 写构建产物，那是对的，但跑完记得 `rm -rf bin` 让 Go 仓保持干净）
- 计划文件：`/Users/billy/.claude/plans/flickering-wishing-cat.md`

## 1. 开工前必读（按顺序，不要跳）

1. **`docs/translation-conventions.md`** —— 本项目最重要的资产。
   - §3 GORM 隐式行为清单
   - §4 错误与响应格式
   - §7.5 **派 agent 的十条强制约束**（每条都对应踩过的坑）
   - §8 翻译日志（每完成一个模块**必须**追加一行）
   - §9 已确认的契约细节 + 已知差异 + 工具链坑 —— **动任何模块前逐条对照**。
     ⚠️ **§9 的正文已于 2026-09-21 按批次拆到 `docs/known-issues/`**（内容未改动）：
     `00-foundation`（基础契约+跨阶段坑）/ `01-mcp-stream-session` / `02-wave-0-1` /
     `03-wave-2` / `04-wave-3` / `05-wave-4` / `06-wave-5`（W5d 及以后追加于此）。
     conventions 的 §9 现在是**全量索引表**（条目 → 文件），历史注释里的
     「约定 §9「XXX」」按标题在 `docs/known-issues/` 里检索即可。
2. `docs/HANDOFF.md`（本文）—— 进度、波次、下一步、协作方式
3. 需要时再查源码：`server/src/main/java/com/ragagent/`

## 2. 进度总览

### 2.1 已完成的模块

| 阶段 | 模块 | 状态 | 关键验证 |
|---|---|---|---|
| 0 | 骨架（路由/错误体系/分页/TenantContext） | ✅ | — |
| 1 | auth / 租户 | ✅ | 10 条 golden + e2e |
| 2 | 模型配置（SSRF / AES 凭证加密） | ✅ | 9 条 golden + 加密互操作 e2e |
| 3 | 知识库（KB CRUD + 文档→解析→chunk） | ✅ | 20 条 golden + e2e 连真 PG |
| 4.0 | LLM 调用客户端 | ✅ | 368 测试 |
| 4.1 | MCP 服务管理（自研协议 + OAuth 全链） | ✅ | 17 条 golden + 跨语言互操作 |
| 4.2 | Wiki（21 端点 + 生成管线） | ✅ | 13 条 golden + 5 端点 A/B MATCH |
| — | API Key 体系回补 | ✅ | 25 能力 + 数据面白名单收口 |
| — | audit 审计回补 | ✅ | 埋点接线 |
| 5.0 | `stream/` 流管理器 | ✅ | 35 测试（含真 redis） |
| 5.1 | 会话/消息 domain + 仓储 | ✅ | — |
| 5.2 | **`continue-stream` 端点 + SSE 契约层 + storageurl** | ✅ | **四条路径 A/B 逐字节 MATCH**（见 §9.3） |
| — | JSON 编码器全局对齐 + 系统性差分排查 | ✅ | 见 §9.2 |
| **波 0** | **`memory`（16 条路由）** | ✅ | 22 golden + **真 PG A/B 36 组 35 MATCH** |
| **波 0** | **`datasource`（17 条路由）** | ✅ | 39 golden + **真 PG A/B 39 组全 MATCH** |
| **波 1 G1** | **session CRUD + pin（8 条路由）** | ✅ | 34 golden + **真 PG A/B 34 组全 MATCH**（golden 实测纠正 3 处预实现，见 conventions §9「波 1 G1」） |
| **波 1 G2** | **消息面（load/search/stats/delete + 清空，5 条路由）** | ✅ | 23 golden + **真 PG A/B 25 组全 MATCH**（search 的 match_type=hybrid 之谜、matchType 空串合并等，见 conventions §9「波 1 G2」） |
| **波 1 G3** | **追问建议（ensure/get/events，3 条路由）** | ✅ | 17 golden + **真 PG A/B 18 组全 MATCH**（writeError 子串分派、LLM 生成步降级等，见 conventions §9「波 1 G3」） |
| **波 1 G6** | **产物 3 条 + generate_title + stop（5 条路由）** | ✅ | 22 golden + **真 PG A/B 22 组全 MATCH**（golden 抓回 stop 的 Long 引用比较、jsonb 处理器缺 JSR310 两个真缺陷，见 conventions §9「波 1 G6」） |
| **波 1 G4** | **steer（排队/列表/删除/提升，4 条路由）** | ✅ | 11 golden + 直种 streamManager 的排队路径单测 5 条 + **真 PG A/B 12 组全 MATCH**（引擎侧 PollSteer/follow-up 随波 4/5，见 conventions §9「波 1 G4」） |
| **波 1 G5** | **临时文档 attachments（5 条路由，波 1 收官）** | ✅ | 11 golden + **真 PG A/B 11 组全 MATCH**；纯文本解析管线（chunker+token）与 Go 逐字节一致；agent 门控/VLM/asynq 按已知差异收口（见 conventions §9「波 1 G5」） |
| **波 2 chunk** | **chunk 编辑面（10 条路由，波 2 开工）** | ✅ | 46 golden + **真 PG A/B 46 场景全 MATCH**（3161 全量绿）；ChunkAccessGuard（ownership+KB 访问分层）、修订历史/乐观锁、生成问题；clamp 误写被 golden 抓回（见 conventions §9「波 2 chunk」） |
| **波 2 knowledge 域** | **文档操作 16 条 + 搜索/移动/复制 8 条（24 条）** | ✅ | 197 golden + 真 PG A/B 189 项全 MATCH（3183 绿）；spans 合成时间线全量翻译、EnsureDefaults 钩子、file_path 零值归一（见 §9「波 2 knowledge」两节） |
| **波 2 FAQ** | **FAQ 12+1 条** | ✅ | 98 golden + A/B 全 MATCH（1 项预期差异=asynq 重试窗口）；textconv 繁简 vendor；**真缺陷：Kb*Config 七类补 @JsonIgnoreProperties（PG 列 DEFAULT 演进出 split_markers）**（见 §9「波 2 FAQ 补充」） |
| **波 2 基础设施配置** | **web-search-providers/vector-stores/storage-backends 30 条** | ✅ | 110 golden + A/B 全 MATCH（3215 绿）；**A/B 抓回三缺陷：jsonb TypeHandler setObject(Types.OTHER)、StoredResource.TableName()=resources、create 缺 AutoCreateTime 回写**（见 §9「波 2 基础设施配置三组补充」） |
| **波 2 成员/邀请** | **members/invitations/api-principal 17 条** | ✅ | 91 golden + A/B 全 MATCH（3238 绿）；**真缺陷：clearStaleHomeTenant 必须写 SQL NULL（写 0 炸 FK）**；B 的两种 token 形态是契约场景（见 §9「波 2 成员/邀请/api-principal 补充」） |
| **波 2 系统管理端** | **/system 7 条 + /system/admin 15 条 + evaluation 2 条** | ✅ | 53 golden + A/B 三轮稳定全 MATCH（3266 绿）；RequireSystemAdmin 文案纠正、UserKbPin 列映射真缺陷（kb_id/pinned_at）、sandbox-check 留波 3 占位（见 §9「波 2 系统管理端补充」） |
| **波 2 终扫批** | **用户收藏 3 条 + chunker 预览 1 条（波 2 全部收官）** | ✅ | 32 golden + 真 PG A/B 32 场景两轮 ALL MATCH（3298 绿，首轮即全对零缺陷）；GORM Find 空结果 `[]` 非 null、空 strategy=legacy 非 auto、preview 裸错误体、测试堆 2g→3g（见 conventions §9「波 2 终扫批」） |
| **波 3 sandbox 子批 1** | **/sandbox-configs 配置 CRUD 8 条** | ✅ | 27 golden + A/B 27 场景两轮 ALL MATCH（3358 绿，首轮即全对）；URL 守卫先于必填、Inventory 失败=200 固定形态、config 列字段级 AES、SandboxClientFactory 接缝占位（见 conventions §9「波 3 sandbox 子批 1」） |
| **波 3 sandbox 子批 2** | **/system/sandbox-check 转正 + templates/query provider 面** | ✅ | 8 golden + A/B 8 场景两轮 ALL MATCH（3361 绿，首轮即全对）；sandboxCheckReason 固定中文分类是字节稳定锚、plain-500 无 details 键（controller-local handler，FAQ 同款）、RemoteError 分类器落地（见 conventions §9「波 3 sandbox 子批 2」） |
| **波 3 sandbox 子批 3** | **/sandbox-configs/:id/skills* 12 条（sandbox HTTP 面收官）** | ✅ | 18 golden + A/B 18 场景两轮 ALL MATCH（3390 绿）；upload=202 异步受理、SSE 单帧、envs 列逐字段 AES、类级 NON_DEFAULT 吞 false 的坑（见 conventions §9「波 3 sandbox 子批 3」） |
| **波 3 sandbox 子批 4** | **/skills 家族 7 条 + /me/env-vars 5 条（skill 模块用户面收官）** | ✅ | 24 golden + A/B 24 场景两轮 ALL MATCH（3392 绿）；catalog 三段合并投影、install=202 installs 映射、删除钉住 409 1005、DELETE 吃 JSON body、bundle_sha256 掩码（见 conventions §9「波 3 sandbox 子批 4」） |
| **波 3 协作批** | **organizations 25 条 + KB/agent shares 7 条 + shared-* 3 条（协作面收官）** | ✅ | 117 golden（org-*/shr-*）+ A/B 两轮 116 场景 ALL MATCH（3394 绿）；com.ragagent.org 新包 20 文件；golden 纠正六处预实现（require_approval 不存在/shares 回填不对称/permission 恒 viewer/Go 文案错配真录 500/共享 KB raw 读无 EnsureDefaults）；**上报 emoji 转义跨横切缺陷待专项**（见 conventions §9「波 3 协作面」） |
| **波 3 agents 批** | **agents CRUD 8 条 + initialization 3 条** | ✅ | 60 golden（ag-*/init-*）+ A/B 两轮 60 场景 ALL MATCH（3396 绿）；**emoji 修复落地**（GoWriterJsonFactory 改道 Writer，root cause=Jackson UTF8 生成器硬编码，升级不可解；内建 avatar golden 钉住+全逐字节套件回归）；vendor yaml 装载；initialization 的 tenant_id=0 等既有行为照抄（见 conventions §9「波 3 agents 批」） |
| **波 3 browserskill 批** | **/me/browser 3 条 + local-browser 3 条（引擎级鉴权）** | ✅ | 44 golden（含 download 字节+headers）+ A/B 两轮 45 项 ALL MATCH 零 DIFF（3399 绿，测试堆 4g→5g）；跨语言互操作实测（Go authorize 兑换的设备行 Java WS 握手通过）；执行循环随波 4（见 conventions §9「波 3 browserskill 批」） |

### 2.2 波次路线（**2026-09-18 实测重排，已废弃原「阶段 6/7/8」**）

| 波 | 内容 | 规模 | 状态 |
|---|---|---|---|
| 0 | `memory`(7.9k) · `datasource`(14k) | 33 条路由 | ✅ **完成** |
| **1** | **会话/消息面剩余**（CRUD/附件/产物/追问建议/消息历史/steer） | 27 条 | ✅ **完成**（真 PG A/B 全 MATCH） |
| 2 | 其余未被 agent 阻塞的端点群（chunk/knowledge/faq/infra-config/members+invitations+api-principal/system/admin/evaluation + 扫尾 auth/OIDC/跨租户/favorites/chunker-预览） | ~140 条 | ✅ **全部收官（A/B 全 MATCH）** |
| 3 | **关键路径前置**：`sandbox` → `infrastructure` → `browserskill` → `modelcontext` | ~32k | ✅ **波 3 完成（sandbox/skill/协作/agents/browserskill，~92 条）**——emoji 专项已在 agents 批修复。剩余：sessions/:id/local-browser 2 条（随波 4 tools）、models/{id}/debug（阶段 7） |
| 4 | **agent 核心 + tools + chat_pipeline + 前置缺口** | ~40k | ✅ **波 4 全部收官**：4.1（mcp 17 golden）/4.2（纯逻辑件 486 实录）/4.3（embed/im 85 golden）/4.4（模型客户端+检索地基 122 测试+30 stub A/B）/4.5a（tools 基建 208 实录）/4.5b（知识检索+wiki 249 实录）/4.5c（执行面+MCP 265 实录+stub A/B）/4.6a（modelcontext+skills 193 实录）/4.6b（AgentEngine 74 实录）/4.6c（chatpipeline 263 实录）/4.6d（chat 三入口+装配+A/B 15 场景全 MATCH）——台账 conventions §8，批次坑 §9 各小节 |
| 5 | **im 执行体 + skill 收口 + shared-agent 收口** | — | ⏳ **进行中**：W5d/W5α1~α3/W5β ✅；剩 W5γ（im 执行体 γ1 地基/γ2 service/γ3 九渠道）+ W5δ（provider 终端执行体）；install 管线体等 provider-XDEP 族随 δ 同批（见 §0.0 与 W5-plan.md） |
| 4 | **agent 核心** + `agent/tools`（实测待翻 ~27k 非测试行）+ chat_pipeline 6.8k + 前置缺口 6.5k | ~40k | ⏳ 作战计划见 §2.3 |
| 5 | `chat_pipeline` · `im` · skill · shared-agent 收口 | ~30k | ⏳ |

**为什么这么排（实测结论，别再照搬旧计划）**：

- **335 条待做路由里约 60% 现在就能做，不用等 agent 引擎。** 两条实测推翻了原前提：
  ① `agent/approval` **阶段 4.1 就翻译完了**——按**包名**做闭包判断会把 `mcp_service.go`(11)
  + `mcp_oauth.go`(6) 这 17 条误判成"被 agent 阻塞"；
  ② `session.go` 里的 `chat_pipeline` **只是个没被用到的字段**（全仓只有两处真正调
  `eventManager`，都在 `session_knowledge_qa.go`），所以 ~25 条会话路由**不**被阻塞。
  **教训：判依赖要看调用点，不要看包名闭包。**
- **agent 不是一块巨石，是三件平行的事**：`chat_pipeline` 与 `agent` 之间只有 1 处引用。
  真正的关键路径是 `sandbox → agent 核心 → agent/tools → {im, skill, chat_pipeline}`。
- **`embed`(28 条) 已从"阶段 6"移到波 4 之后**——它同样堵在 `agent/tools` 上。
- 五个真叶子（零未翻译前置）：`datasource` ✅、`memory` ✅、`sandbox`、`browserskill`、`infrastructure`。
  **`sandbox` 是最紧的前置**（`agent/skills` 硬依赖它，另解锁系统管理端与 skill）。


### 2.3 波 4 作战计划（✅ 已收官，只留档与仍有效的纪律）

波 4（4.1→4.6d）已全部收官。**逐批的源文件 / 落地文件 / 验收数字见 conventions §8 的六行台账**
（细节在 `known-issues/05-wave-4.md`），§2.1 / §2.2 有汇总。当时勘察的实测规模
（agent+tools+chat_pipeline ≈ 待翻 34k、前置缺口 6.5k）与逐子批切法/验收口径**不再维护，
别当成待办**。

**跨批仍有效的四条纪律**（波 4 踩出来的，W5d 及以后照用）：

1. **SSE 时序是最高危区**：final_answer 的 event-id 分片重组 + superseded preamble 剔除。
   已由 4.6d 的 stub LLM A/B 钉住，线格式细节在 `known-issues/01-mcp-stream-session.md`
   —— **动 SSE / bridge / 事件订阅之前先读那两节**。
2. **LLM 确定性**：凡涉及 chat 链路的 A/B **必须双端同指 stub LLM**
   （`scripts/stub-llm-server.py`），真实链路只做骨架断言。W5d 的 embed QA 委托同样适用。
3. **虚拟线程必须显式拷 TenantContext**（Go 用 ctx 传租户，ThreadLocal 不跨虚拟线程），
   `Long` 比较一律 `equals`/拆箱——并发工具回调处会复发。**W5d 的 WS 升级后正是这套线程模型。**
4. **装配走接口 seam 注入 stub，不加 `@SpringBootTest` 上下文变体**（§5 补注）；
   新增/注入 bean 时连带核对注入点是否真是 bean —— W5d 半成品就是把非 bean 的 `AuthFilter`
   注进控制器、把整个上下文搞挂的（见 §0.0），这是本纪律的最新反例。


## 3. 下一步（总验收已完成，剩余缺口清单见 §0.0）

**新会话开场动作**（按序）：
1. `git status`（确认在 `/Users/billy/ragagent-java`）+ 读 §0.0。
2. 真缺口用 `python3 scripts/route-recon.py` 复核（起点应为「真缺口候选 2」=
   `/swagger/{}` 非翻译目标 + `models/{id}/debug` 留阶段 7）。
3. 之后：波 5 剩余（im 执行体 3,453 行 = W5γ1/γ2/γ3 + W5δ provider 终端执行体；
   tenant_skill verify/progress 已由 W5β 收官，install 管线体属 provider-XDEP 族）、
   检索引擎批（HybridSearch 执行面）、执行体批（ArtifactCollector/VLM Predict 生产装配）、
   ⑱ initialize 契约对齐（Owner 决策）

### 3.0 波 2 扫尾清单（✅ 全部完成，留档备查）

最终对账（Go/Java 路由程序化对账 + 逐批核对）确认波 2 命名模块全部落地后，
剩下的"非命名模块但同样不被 agent 阻塞"的散条，已全部完成：

| 组 | 路由 | 说明 |
|---|---|---|
| ~~auth 注册族（~9）~~ | ✅ **已完成（2026-09-19）**：9 端点全落地，46 reg-* golden + 8 契约测试 + 真 PG A/B 46 组两轮 ALL MATCH。台账见 conventions §8「auth 注册族（波 2 扫尾批 1）」，坑见 §9 同名小节 | logout/refresh 阶段 1 已翻 |
| ~~OIDC（4）~~ | ✅ **已完成（2026-09-19）**：4 端点全落地（路由实为 4 条，config/url/start/callback），13 oidc-* golden（302 用合成信封约定）+ 5 契约测试 + 真 PG A/B 两轮 ALL MATCH。只翻了未配置=disabled 确定性分支；enabled 后的 discovery/code 交换/provisioning 整体推迟（§9「波 2 扫尾批 2」deferral）。将来做 provisioning 时 tenantless 建号必须用 UserMapper.insertTenantless（§9「波 2 扫尾批 1」的 FK 坑） | logout/refresh 阶段 1 已翻 |
| ~~跨租户租户管理（4~5）~~ | ✅ **已完成（2026-09-19）**：5 端点全落地（GET /tenants/all、/tenants/search、POST /tenants、GET/PUT /tenants/kv/{key}——注意 KV 是 /kv/{key} 不是 /{id}/kv/{key}，目标租户走 X-Tenant-ID 头）。65 ct-* golden（63 flag-on + 2 flag-off）+ 11 契约测试 + 真 PG A/B 62 MATCH + 1 EXPECTED-DIFF（prompt-templates GET 推迟，Go 独有 vendor yaml）。台账见 conventions §8「跨空间租户目录 + KV 配置（波 2 扫尾批 3）」，坑见 §9 同名小节 | logout/refresh 阶段 1 已翻 |
| ~~用户收藏（标 4，实为 3）~~ | ✅ **已完成（2026-09-19）**：GET/POST /user/favorites + DELETE /user/favorites/{type}/{id}（routes_agent.go 实际只注册 3 条，HANDOFF 旧写 4 条是笔误已纠正）。20 fav-* golden + 5 契约测试 + A/B 两轮 ALL MATCH。表无外键、纯 SQL mapper、幽灵删除 200。台账见 conventions §8「用户收藏 + chunker 预览（波 2 终扫批）」 | — |
| ~~chunker 预览（1）~~ | ✅ **已完成（2026-09-19）**：POST /chunker/preview，12 cprev-* golden（响应全确定零掩码）+ 2 契约测试 + A/B 两轮 ALL MATCH。chunker 补诊断层（SplitWithDiagnostics/splitParentChildWithDiagnostics），核心切分零改动 | — |

**明确推迟（有依赖，别现在做）**：/me/browser + /local-browser（波 3 browserskill）、
/me/env-vars/{skill,sandbox}（波 3/5）、/wechat/qrcode ×2（波 5 im）、
knowledge-chat/agent-chat/knowledge-search（波 4）、models/{id}/debug（阶段 7）、
/system/sandbox-check（波 3，Java 已 404 占位）。

### 3.1 波 3 起点（HANDOFF §2.2 波表已排）

**sandbox 是最紧的前置**（agent/skills 硬依赖，另解锁系统管理端 sandbox-check 与
skill 模块）。顺序：`sandbox → infrastructure → browserskill → modelcontext`。
波 3 的 A/B 直接复用本会话沉淀的脚本族（ab-chunk/ab-knowledge/ab-faq/ab-infra-config/
ab-members/ab-system）与录制脚本参数化模式（XXX_TARGET_PORT/XXX_OUT_DIR）。

### 3.2 已经就位的组件（直接用，别重造）

- **共享守卫**：`ChunkAccessGuard`（ownership+KB 访问分层，403 纯字符串 vs 信封按层分布）
  / `KnowledgeAccessGuard`（含 envelope 形态）/ `requireOwnedKb` 族——知识库域路由照此分层
- **chunk 模块起就位的响应契约**：`Chunk`/`ChunkRevision`/`DocumentChunkMetadata`/
  `GeneratedQuestion`/`KbChunkingConfig`（@JsonIgnoreProperties 全家桶）
- **基础设施**：`PlainErrorException(status,msg)`（任意 handler 直写纯字符串错误的通用出口）、
  `KnowledgeService.generateTaskId`（任务 id 契约）、`KnowledgeTaskProgressStore`
  （进程内进度存储）、`SystemSettingService`（运行时调谐统一入口）、
  `WebSearchProviderService.constructProvider`、`VectorStoreConfigService.testConnection`
- **A/B 脚本族**（全部真 PG、掩码后逐字节）：ab-chunk / ab-knowledge / ab-faq /
  ab-infra-config / ab-members / ab-system / ab-reg；录制脚本统一支持 XXX_TARGET_PORT/XXX_OUT_DIR
  参数化重放（新模块照此模式写）
- 既有：session/message/memory/datasource/wiki/mcp/model/audit/apikey/stream/storageurl 各域
  （见 §2.1 与 conventions §8）
- **引擎/agent 侧地基（波 4 之前的沉淀，W5d 与波 5 直接用）**：llm 流式客户端（阶段 4.0）、
  `StreamManager`+steer 队列（阶段 5.0）、`session.sse` 契约层 + `continue-stream`（5.2）、
  `agent.approval`、`sandbox` 客户端切片、`SkillFrontmatter`/`SkillBundleParser`/`TenantSkillService`、
  `agentm.BuiltinAgentRegistry`、`BrowserSkillManager`（含 `Sec-WebSocket-Protocol` 校验）
- **文档/工具（收尾期新增）**：`docs/known-issues/`（conventions §9 正文分片）、
  `docs/W5d-plan.md`、`scripts/route-recon.py`（路由缺口对账）

### 3.3 执行纪律（本会话验证有效）

- 派 agent 前先 `lsof -ti :8082` 杀旧 Java server（**旧进程占端口会让 wait_for_port 打到
  旧代码，新路由表现为 404**——本会话 chunk 与 infra 两批都踩过）
- agent 任务书必带：`docs/known-issues/` 的对应分片（原 conventions §9，按批次分片）+
  conventions §7.5 十条约束、golden 前缀防冲突（先 ls contracts/）、
  幂等清理含 chunk_revisions 等衍生表、固定种子 id 纯十六进制
- agent 报"全绿"后主会话必须独立跑全量 + 真 PG A/B——本会话各批里 A/B 抓回了
  **10 个 H2 绿/PG 红或实现缺陷**（clamp 误写、setString→setObject、TableName 影子表、
  AutoCreateTime 回写、UserKbPin 列映射、clearStaleHomeTenant FK、business 零值、
  anydoc 文案、Kb*Config 容忍性、**扫尾批 1 的 tenantless 注册 getter 归一化写 0 炸 FK**）
- agent 可能撞用量上限中断（本会话 members 批中断一次）：中断后主会话直接接力修
  （编译错误→测试失败逐个排），比重新派 agent 快

## 4. 标准验收流程（每个模块）

```bash
# 0) 环境（换了 shell 一定要先设 JDK，否则 ./gradlew 报 "Unable to locate a Java Runtime"）
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
cd /Users/billy/ragagent-java

# 1) 起 Go server 录 golden（同时起 Java 做 A/B 对比）
scripts/go-server-up.sh            # 注意：必须从 WeKnora 目录调！见 §5 第 11 条
scripts/java-server-up.sh          # Java 默认 :8082，Go :8080
# 要共享 Redis 键空间时两个都要带：STREAM_MANAGER_TYPE=redis REDIS_PREFIX=stream:

# 2) 录 golden（务必用 curl -o，不要用 echo >，zsh 会解释转义）
TOKEN=$(scripts/token.sh 8080)
curl -s -o server/src/test/resources/contracts/xxx.json \
  -X POST http://localhost:8080/api/v1/... -H "Authorization: Bearer $TOKEN" ...

# 3) 写契约测试（掩码 UUID/时间戳后逐字节比对；中文用 content().bytes）
# 4) 定向测试 → 最后必须全量
./gradlew :server:test --tests "com.ragagent.<你的包>.*"
./gradlew test                     # ⚠️ 必须跑一次；约 15 分钟（测试堆 4g；全量时长随
                                   #    @SpringBootTest 上下文变体数增长，见 §5 补充）

# 5) e2e / A/B：Java 连真 PG 跑通，并与 Go 逐字节对比
# 6) 更新 docs/translation-conventions.md 的 §8（日志行）+ §9（新细节/差异）
# 7) 提交（结尾带 Co-Authored-By: Claude <noreply@anthropic.com>）
```

**环境**：dev PG `localhost:15432`（密码 `postgres123!@#`，库 `WeKnora`）、
Redis `localhost:16379`（密码 `redis123!@#`）、docreader `localhost:50051`；
测试账号 `java-phase1*`（租户 10002）；**dev DB 含真实数据，只动测试租户**。

## 5. 陷阱清单（按复发率排序）

> 完整版在 `docs/known-issues/`（conventions §9 的正文已按批次拆到那里，§9 只留索引）。
> 下面是最高频的几条。

1. **领域对象的 `isXxx()` 派生方法必须 `@JsonIgnore`** —— 复发率最高，阶段 3、4.1、波 0 各踩过。
   漏了会把多余的键写进 jsonb，回读抛 `UnrecognizedPropertyException` 让**整列不可用**。
   **防线**：`JsonContractRoundTripTest` 里加 `assertRoundTrips(...)`。
   ⚠️ **不要给字段取名 `isXxx`**（`private boolean isPinned` 会多吐一个键）——字段名去掉 `is` 前缀。
2. **JSON 键名逐字段对照 Go 的 json tag** —— 蛇形漏 `@JsonProperty` 就接不住前端请求。
   **map 响应字段必须挂 `GoMapSerializer`（或模块内子类）、double 字段必须挂 `GoDoubleSerializer`**
   —— §9 有专门说明，这两类**全局解不了**，新增响应类型时逐个检查。
3. **Go 零值语义** —— string 默认 `""`、计数器用原始类型、`omitempty` 的 0/空/false 要省略、
   无 `omitempty` 的恒输出（含 `null`）。**三态 `*bool` 必须是可空 `Boolean`**，压成 `boolean`
   等于替用户做决定。
4. **带 `DEFAULT` 的 jsonb 列**：MyBatis-Plus 对 null 字段**省略该列** → 落到 DB 默认值，
   而 Go 显式写 NULL。需要 `FieldStrategy.ALWAYS`（wiki 踩过）。
   ⚠️ **反过来也成立**：列**没有** DEFAULT 时不要加 ALWAYS（datasource 的三个 jsonb 列就是）。
   **逐个查迁移里有没有 DEFAULT，别一刀切。**
5. **中间件分层会改变错误文案** —— 写契约测试前先确认拒绝发生在哪一层，
   Go 的 handler 里常有**不可达的死代码**。
6. **`Long != Long` 是引用比较** —— 租户 id 10002 超出 `Long` 缓存区间（-128..127），
   用 `!=` 比会让整组子资源 404，**只在真请求下暴露**（波 0 踩到）。比 `Long` 一律用 `equals` 或先拆箱。
7. **自定义 `@Select` 的结果映射不套实体的 `@TableField(typeHandler=…)`** ——
   要写**方法级** `@Results`，否则 jsonb 列静默读成 null（"库里有值、读出来是 null"）。
8. **测试禁止依赖真实网络** —— 用 stub server（`com.sun.net.httpserver.HttpServer` 就够，
   记得 `setExecutor(...)` 否则挂死）；SSRF 白名单要在 `@AfterAll` 还原。
9. **不要写靠墙钟造时间的测试** —— 「1ms 窗口断言已过期」这类单跑绿、全量红（JIT/GC 下
   两次调用落在同一毫秒）。要造"时间已过去"就直接改 DB 里的时间戳。
10. **日志断言必须显式 `setLevel` 再还原** —— 级别过滤发生在 appender **之前**，
    断言 INFO/DEBUG 的测试单跑绿、与 `@SpringBootTest` 同批跑就红。这是"单跑绿全量红"的另一变种。
11. **起 Go server 的两个坑**（都实际踩过）：必须**从 WeKnora 目录**调用（viper 找
    `config/config.yaml`），且 `DB_DRIVER/DB_USER/DB_PASSWORD/DB_NAME/REDIS_ADDR/REDIS_PASSWORD`
    要显式导出（否则 panic `unsupported database driver:` 或 `连接Redis失败`）。
12. **保存 curl 输出用 `-o`**，别用 `echo "$X" > f`（zsh 的 echo 会解释 `\n`，golden 会坏）。
13. **`MyBatisSystemException: null`** 的根因在 `Caused by:` 链深处，别在业务代码里瞎找。
14. **H2 绿、PG 炸** —— NOT NULL 约束、jsonb 键序、DDL 默认值只在真 PG 上暴露。e2e 必须连真 PG。
15. **全量时长/堆的退化信号**（波 3 实测）：全量从 6.7min 涨到 13-15min 且伴随
    「Gradle Test Executor N failed」OOM → 先怀疑 **GC 死亡螺旋**（@SpringBootTest
    上下文变体又变多了），提堆（现 4g）只是买时间，治本是收敛变体数。
    墙钟脆弱测试的第三变种（TTL 续期断言）见 conventions §9「波 3 sandbox 子批 1」。

## 6. 协作方式（已验证有效）

- **主会话做**：共享契约（domain 类型）、跨模块装配（`WebConfig` 路由/过滤器、
  `APIKeyRoutePolicies`）、**`TestSchema` 的 DDL**、golden 录制 / A-B 对比、
  真实缺陷的排查与修复、文档与提交
- **agent 做**：单模块的机械翻译 + 对等测试。任务书必须带 §7.5 的十条约束
- **⚠️ 最重要的一条：agent 报"全绿"之后，主会话必须自己跑一次全量 `./gradlew test` 复核。**
  本轮就靠这个抓到 agent 自己写的一个墙钟不稳测试（单跑绿、全量红）。
  **只跑自己的包会漏掉这类问题。**
- **并发**：多个 agent 同时跑**全量** `./gradlew test` 会争抢 build 目录（OOM / 假失败）。
  任务书里要明确「只跑 `--tests "com.ragagent.<你的包>.*"`」，且**尽量串行**。
- **如果一个 agent 需要跨模块改动**（比如要动 `session` 包、`TestSchema`），
  在它跑的期间**不要派别的 agent**，并在任务书里显式授权那几个文件。
- 派 agent 时**第一句**永远是：「先读 `docs/translation-conventions.md` 的 §3/§7.5/§8/§9」
- **agent 撞用量上限中断（波 2 members 批实测）**：主会话直接接力修——先编译（该批
  遗留了测试变量遮蔽与 @PathVariable 模板名不一致），再逐个排契约测试失败（每修一轮
  重跑单包）。比重新派 agent 快，且上下文无损。接力时以 agent 留下的 golden 为准绳
  （预实现与 golden 冲突时以 golden 为准）。
- **A/B 掩码是逐步长出来的**：每批的 ab 脚本首轮跑完，把 DIFF 里的动态字段逐个加掩码
  （uuid/ts/epoch/task/seq/invite_url/JWT/generated_password/affected…），直到 ALL MATCH
  且连跑两轮稳定。掩码不是"放过差异"——**契约测试同时钉住 Java 自身确定性值**。
- **部署态文件**（capabilities/db_version/evaluation 执行态/搜索引擎列表连接态）在 A/B 里
  标 XDEP 跳过、按部署各自断言，契约测试负责 Java 侧形状。

## 7. 关键文件索引

| 用途 | 路径 |
|---|---|
| 翻译约定（必读，§1–§8 正文） | `docs/translation-conventions.md` |
| **已知细节与坑（原 §9 正文，按批次分片）** | `docs/known-issues/{00-foundation,01-mcp-stream-session,02-wave-0-1,03-wave-2,04-wave-3,05-wave-4,06-wave-5}.md` |
| 交接文档（本文） | `docs/HANDOFF.md` |
| W5d 作战计划（2026-09-21 立） | `docs/W5d-plan.md` |
| 契约 golden | `server/src/test/resources/contracts/`（1,649 个） |
| H2 共享 DDL | `server/src/test/java/com/ragagent/TestSchema.java` |
| JSON 往返体检 | `server/src/test/java/com/ragagent/common/JsonContractRoundTripTest.java` |
| e2e 脚本 | `scripts/{dev-env,go-server-up,java-server-up,token}.sh` |
| golden 录制 / A-B 范例 | `scripts/{record-datasource-golden,ab-datasource}.sh` |
| **路由对账（Go↔Java 缺口）** | `scripts/route-recon.py`（收尾期每批收尾跑一次） |
| 路由与过滤器装配 | `server/src/main/java/com/ragagent/config/WebConfig.java` |
| Go 的响应格式锚点 | `com.ragagent.common.web.{GoJsonEscapes,GoMapSerializer,GoDoubleSerializer,GoTimeSerializer}` |

## 8. 如果遇到不确定的

- **架构 / 范围 / 顺序决策**：问用户（这轮几次调整都是用户定的）
- **Go 行为不确定**：**实测**——起 Go server 打一发，**不要猜**。
  这轮发现的真实缺陷（403 两种形态、201 状态码、jsonb NULL 语义、`gorm` 的 `updated_at` 回写内存、
  `Long != Long`）**全部**是实测出来的
- **怀疑 Go 有 bug 时**：先实测再下结论。本轮有一次怀疑 GORM 的 AND/OR 优先级问题，
  用 DryRun 打印实际 SQL 后发现**是我错了**（GORM 会自己包括号），差点"修好"成偏离 Go。

## 9. 方法论与流程（不随批次增长；坑与细节一律进 known-issues）

> 本节**只放方法与流程**。具体坑、契约细节、已知差异的正文都在
> `docs/known-issues/`（conventions §9 的正文，按批次分片，索引见 conventions §9）。
> 曾在此处的「JSON 编码器类差异」「SSE 线格式」两份摘要已删（与分片重复且更旧）。
> ⚠️ **§9.1 / §9.2 / §9.3 这三个编号是稳定锚点**——源码注释里有 6+ 处在引用
> （如 `event.EventJson`「§9.2 的污染警告」、`event.AgentFinalAnswerData`「§9.3 扣留键」），
> 只可作为**指针**保留，别删、别重编号。

### 9.1 最值钱的方法：**录 Go 实录**

不要靠读源码推断 Go 的行为。把 Go 的类型/函数**原样抄进一个独立 Go 程序**，
跑出真值，再把输出抄进 Java 断言。这条方法抓到过：

- `types.JSON` 漏抄 `MarshalJSON` 会退化成 base64（差点按错的行为写 Java）
- Java 的 `Double.toString` 在次正规数上比 Go 长（`4.9E-324` vs `5e-324`）
- Java 的 `$` **不等于** Go 的 `$`（Java 还匹配末尾换行符之前）——照抄会让流卡住
- gin 的 SSE 帧是 `event:message\ndata:…\n\n`（冒号后**没有空格**）+ Go 的 HTML 转义
- `scoreItems` 的分母口径、`selectResidentInterests` 会把 nil 条目也塞进 selected

**Go 程序要放到 `/tmp` 或复制一份 Go 仓到 `/tmp`**（原仓只读）。
需要调未导出函数时，用 `go test -overlay` 挂探针或复制整仓加同包测试文件。

### 9.2 JSON 编码器类差异 → 见 `known-issues/00-foundation.md`

正文（逐类结论表：HTML 转义 / `GoJsonEscapes` / U+2028 / int64 / double / map 键序，
以及「double 与 map 键序**只能逐字段**，全局注册会污染发给 LLM 的请求体」的取舍）
在 **`docs/known-issues/00-foundation.md`「JSON 编码器的系统性差分排查」**。
配套体检：`GoJsonEncodingContractTest` + `JsonContractRoundTripTest`。

### 9.3 SSE 线格式 → 见 `known-issues/01-mcp-stream-session.md`

状态锚点：**四条路径（错误 ×3 / handle 回放 / public 扣留冲发 / public 跨分片重组）逐字节 MATCH**。
帧格式、Content-Type 被覆盖、扣留键、断开检测的实现细节在
**`docs/known-issues/01-mcp-stream-session.md`「阶段 5.2（SSE 契约层）」与「步 4（continue-stream）」**。

### 9.4 一个模块的典型节奏（≈4 步，`memory` 与 `datasource` 都是这么走的）

1. **契约类型**（domain：实体 + 5~10 个响应/配置类型）—— 主会话做
2. **实体 + 仓储**（Mapper + Repository）—— 可派 agent，但 **`TestSchema` 由主会话加**
3. **service 层** —— 派 agent
4. **HTTP 层 + 路由**（Controller + `WebConfig` + `APIKeyRoutePolicies`）—— 派 agent

每步一个提交；每步都要求 agent 附**一次全量 `./gradlew test`** 的结果；
主会话再独立复核一次。一个模块大约 5 个提交 / 2000-6000 行 Java / 100-900 条测试。
