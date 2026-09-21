# 波 5 作战计划（im 执行体 + 共享 agent 收口 + tenant_skill 收口 + provider 终端执行体）

> 拟于 2026-09-21 · 基线 `bea7e98`（W5d 收官 · golden 1,676 · route-recon 真缺口候选 2）
> 立此文档的依据是**本会话的独立侦察**（Go 仓只读对照 + Java 仓现状核对），不是 HANDOFF 转述。
> 开工前连同 `docs/HANDOFF.md` §0.0/§3、`docs/translation-conventions.md` §3/§6/§8 与
> `docs/known-issues/05-wave-4.md`（4.6d 移交缺口）、`06-wave-5.md`（W5d 坑）一起读。

## 1. 侦察结论（本会话实测）

| 检查项 | 结论 |
|---|---|
| 仓库状态 | `main` @ `bea7e98`，工作区干净；route-recon：Go 389 / Java 451 / 交集 387，**真缺口 2**（swagger 非目标 + models/{id}/debug 留阶段 7）——波 5 范围**不在路由缺口里**，全是「路由已在、执行体缺失/打桩」 |
| im 执行体 | Go `internal/im/` 主包非测试 **6,038 行**（service.go 3,453 + qaqueue 380 + think 264 + tool_display 585 + types 250 + adapter 208 + command 族 ~290 + credentials/supervisor/stream_section/mode ~110）；**渠道适配器 9 个目录另计 ~10,656 行**（wecom 1,871 / feishu 1,830 / yunzhijia 1,267 / dingtalk 1,087 / wechat 981 / qqbot 683 / mattermost 672 / telegram 672 / slack 543） |
| Java im 现状 | `com.ragagent.im` 仅 5 文件 1,079 行（W5a：channel CRUD + callback 骨架 + wechat/qrcode 桩）；callback 对 enabled 渠道恒 503 "channel not available"（桩，golden 钉住）；`im_channel_sessions` 表（迁移 000021）**无实体无 mapper** |
| im 对 Redis | Go 侧 **redis 可 nil**（nil → 进程内回落：dedup/限流/WS leader/pub-sub 全有本地分支）；Java 已有 Memory/Redis 双形态先例（StreamManager、EmbedTokenStore、WikiFinalizeLock）→ 照先例先落进程内存形态 |
| 共享 agent | Go `agent_share.go` 550 行 + `handler/shared_agent_access.go` 82 行 + `application/access/` 三处授权路径。Java `AgentShareService` 已有 CRUD/列表面（~460 行）但**缺 `getSharedAgentForTenant`**（Go L455-505 + 三个哨兵错误）；5 处调用点全部打桩：KnowledgeQaController.resolveAgent 共享分支恒 404、KnowledgeController ×2 恒 403 "no permission for this shared agent"、KnowledgeBaseController org-share 不可见、FileAccessResolver 跨租户恒 403 |
| tenant_skill | Go `tenant_skill_verify.go` 491 + `tenant_skill_progress.go` 71 + repository 491。Java `TenantSkillService` 已有 1,300+ 行（install/reinstall/stop/remove/catalog/env-vars 全在）但 **verify 族整体缺失**（grep 零命中）；progress 的 publish（Go 走 redis pub/sub）待核 |
| provider 终端 | Go `sandbox/` 终端族 ≈1,300 行（terminal 283 + session_lifecycle 终端段 + cube_terminal 183 + e2b_terminal 208 + pty_input_coalesce 96；remote_errors 283 **已翻**为 `RemoteError`/`RemoteErrorKind`）。Java sandbox 包已有 12,448 行客户端切片（RemoteProviderClient/CubeDns 等）；W5d 留的接缝：`SessionTerminalService.openOnResolved`/`provisionAndOpen` + `TerminalBridge` 泵组生产接线 |
| wechat/qrcode ×2 | **已翻**（W5a，ImChannelController L277-301，真实 iLink 出站标 XDEP）——不在波 5 |

## 2. 批次切分与顺序

| 子批 | 范围 | Go 规模 | 可验收性 |
|---|---|---|---|
| **W5α 共享 agent 收口** | getSharedAgentForTenant + 哨兵族 + resolveSharedAgentForRequest 等效体 + 5 处桩替换（QA resolveAgent / KB list agent_id / knowledge batch-restore / document search / FileAccessResolver + access 三包授权路径） | ≈700 行新翻 + 桩替换 | **最好**：纯 PG 种子（agent_shares/org_members/tenant_disabled_shared_agents）→ golden + A/B 全覆盖，无外部依赖 |
| **W5β tenant_skill verify + progress** | verifySkill/verifySkillTree/execVerify/命令构造族 + publishProgress + repository 缺口核对 | ≈560 行 | 命令构造是纯函数可单测；真实 exec 走 sandbox（dev 可达性开工即查，不可达则标 XDEP 骨架断言） |
| **W5γ im 执行体**（内部三小批） | γ1 地基：types/adapter 接口族/qaqueue/think/tool_display/command 族；γ2 service.go 核心（生命周期/HandleMessage 管线/runQA/会话解析/CRUD 补齐）；γ3 渠道适配器 9 个 | 6,038 + 10,656 行 | γ1 纯函数族（think/tool_display/命令解析）可单测 + golden；γ2 的 QA 管线双端同指 stub-llm 可 A/B；γ3 真实平台不可达——验签失败族/错误形态可 A/B，成功路径标 XDEP |
| **W5δ provider 终端执行体** | terminal/session_lifecycle 终端段/cube_terminal/e2b_terminal/pty_input_coalesce → 接 W5d 接缝（openOnResolved/provisionAndOpen/TerminalBridge 泵组） | ≈1,300 行 | dev 无 cube/e2b 远端——错误族（RemoteError 映射）可测；成功泵组标 XDEP（延续 W5d 备案） |

**顺序理由**：α 最小且无外部依赖，先拿下建立节奏并解锁 5 处桩；β 独立小批；γ 是主菜放中间
（γ1→γ2→γ3 严格依赖序）；δ 收尾（只依赖 W5d 已留接缝，与 γ 无文件重叠）。

**不在波 5**（HANDOFF §3 独立批次，勿混入）：HybridSearch 执行面（检索引擎批）、
ArtifactCollector/rewriteArtifactReferences/VLM Predict（执行体批）、models/{id}/debug（阶段 7）、
⑱ initialize 契约对齐（Owner 决策）。

## 3. 执行纪律（沿用 W5a~W5d 验证有效的）

- **主会话直接执行**（本会话不派 agent）：批次小步快走，每子批一提交。
- golden 前缀：`w5a-*`…`w5d-*` 已占用，本波用 `w5s-*`（shared）/ `w5k-*`（skill）/ `w5i-*`（im）/
  `w5t-*`（terminal）；录制前 `ls contracts/` 确认前缀为空。
- im 的 QA 管线 A/B 复用 stub-llm 双端同指模式（ab-w5b.sh 先例）；渠道回调 A/B 只覆盖确定性失败族。
- Redis 一律先落进程内存形态（Go redis==nil 分支逐行对照），不接真 Redis。
- 测试分批：agent/chatpipeline 单独一批，Spring 包按 B1b~B4（Mockito attach 假红教训）。
- 每子批：契约测试 + 受影响包绿 + 真 PG A/B 两轮 ALL MATCH（或逐项标注 XDEP/EXPECTED-DIFF 理由）→
  台账 conventions §8 + known-issues/06-wave-5.md → 提交。
