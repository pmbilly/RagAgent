# E2E 两观察项的复跑结果（2026-09-25，W5γ5.10）

> 来源：HANDOFF §0.-42 三 的"E2E 两观察项：复现清单"（**不做无现象的猜测式改动**）。
> 探针：`scripts/ab-e2e-observations.sh`（可复跑）+ `scripts/stub-llm-server.py` 的
> `early-error` / `early-close` 场景与 `STUB_RECORD_DIR` 录制开关（本批新增）。
> 产物留在 `/tmp/e2e-obs/`（SSE 帧序列 + `summary.txt`）。

## 一、观察项 1「早错 SSE 不收流」——**确认为真，且两侧同形；但要按"错在哪一阶段"分两类**

| 错误阶段 | 复现方式 | Go (:8080) | Java (:8082) | 结论 |
|---|---|---|---|---|
| **调用前**（模型侧前置校验失败） | ①模型 base_url 未过 SSRF 白名单；②agent 绑的 chat 模型不存在；③smart 模式缺 rerank 模型 | `agent_query(done)` + `error(done)` **两帧后不收流**（curl 15–20s 到点仍在等，rc=28） | 同左（逐帧同） | **两侧一致**——"不收流"是**继承行为**，非 Java 回归 |
| **流内早期**（上游 200 后立刻出错） | stub `early-error`（首帧 error）/ `early-close`（零帧关流） | 3 帧后**立刻收流**（0.15s，rc=0） | 同左（0.15s，rc=0） | **两侧一致**，行为=给错误帧后正常收流 |

- 基线对照：`<<SCENARIO:chat>>` 双端 4 帧 / 0.14–0.17s 收流，逐帧同形 ✓。
- **可操作结论**：客户端（前端）不能指望服务端在"调用前失败"时关流——必须**按 `done:true` 或自身超时收流**；
  这一点两侧一致，属产品级口径（若将来要对齐，应作为**两侧同步**的行为变更，而非本仓单侧修复）。

## 二、观察项 2「`list_sandbox_files` 注册时机」——**代码层已对清；真机确认卡在 dev 前置**

**代码层（两侧逐条对应，证据 file:line）**：工具的注册是**每用户回合一次、在首帧之前**（Go
`agent_service.go:180-233` ↔ Java `SessionAgentQaService.java:237,620-641`），此后迭代内只刷新 MCP 工具
（`engine.go:527-532` ↔ `AgentEngine.java:662-666`），**从不中途补注册沙箱族**。

缺 `list_sandbox_files` 的唯一"设计性"原因（两侧相同）：**`shell_exec` 在场时该工具不注册**
（Go `agent_service.go:435-437` ↔ Java `SessionSandboxExecutionService.java:256-258`，
注释即 "listing is a no-shell fallback"）。其余门（install 模式 / 沙箱可解析 / 会话文件面能力）也一一对应；
出站 tools 顺序两侧都是**工具名升序**（Go `registry.go:97,116-136` ↔ Java `ToolRegistry.java:117,132`）。

**真机确认（2026-09-25 完成，W5γ5.11）**：绕开两个前置坑的做法——**新建一个"无 KB + smart 模式 +
stub rerank"的 agent**（`kb_selection_mode:none` 避免 2200；`rerank_model_id` 指 stub 的 `/rerank` 避免
"rerank model is not configured"），再按清单直问。

实测（双端各一 session，同一会话连问两次）：
- **首帧出站请求就带 `tools` 段**：本环境为 1 个工具 `search_conversations`（`shell_exec`/
  `list_sandbox_files` 均不在——因无沙箱/会话文件能力，命中 G2/G3 门，**非时序问题**）；
- **双端 tools 名集逐一相同**（Go 与 Java 各两问、每问两条 LLM 调用，全部 `tools=1: search_conversations`）；
- 帧序列双端**逐帧同形**且**正常收流**：`agent_query → answer → answer → answer(done) → complete`（5 帧，rc=0）。

⇒ 观察项 2 的可疑点（"首帧没有、调过别的沙箱工具才有"）在真机层面也不成立：**工具集在轮首即定型、双端一致**；
缺失的那件是**能力门**决定的（本环境无沙箱），不是注册晚。

## 三、顺带抓到的一处**真差异**（Java 侧，待修）

同一错误路径的**终止错误帧 `content`** 两侧不一致：

| 端 | 非终止 error 帧 | 终止 error 帧（done=true） |
|---|---|---|
| Go | `error code: 2200, error message: vector store bound …` | **同左（带前缀）** |
| Java | 同左（带前缀 ✓） | **裸消息**：`vector store bound …`（**丢前缀** ✗） |

Go 的 `AppError.Error()` 就是 `"error code: %d, error message: %s"`（`internal/errors/errors.go:65`），
终止帧 `Content: data.Error`（`internal/handler/session/agent_stream_handler.go:611`）；
Java 侧的终止帧 content 取自流响应内容（`chatpipeline/PluginChatCompletionStream.java:155,162`），
上游把它填成裸 `getMessage()` → **AppError→SSE 的文案在"终止帧"这一路上丢了前缀**。
（本仓别处已实现同格式助手：`wiki/controller/WikiPageController.java:1265-1268`，说明格式是已知契约。）
**处置**：✅ **已修并线上复验（W5γ5.11 + W5γ5.12）**。这条差异的根因**不止一处，且第一处判断是错的**——
按"先取证再改"补做线上 A/B 后才找到真凶：

1. **（W5γ5.11，防御性，非本次观测的发源）** `agent/tools/ToolRegistry.java` 的"工具抛异常"兜底把文案
   **写死成 `"tool returned no result"`**、`agent/AgentEngine.java` 工具失败处取 `getMessage()` —— 都与 Go 的
   `err.Error()` 语义不符（Go 是 `return nil, err`，AppError 文本自带前缀）。已统一为
   `common/error/BizException.wireText(Throwable)`（沿 cause 链取最近 BizException 的**已带前缀** message，
   否则退到最深非空 message）；`AgentEngineException` 补 `(message, cause)` 保留 cause。回归：
   `ToolRegistryRecordingTest` 新增"工具抛异常 → 文案照 Go 的 `err.Error()`"。
2. **（W5γ5.12，真凶）** 真正发出终止帧的是 `session/controller/KnowledgeQaController` 的 catch：
   它**有意**把文案剥成 `appError().message()`，理由是"Go 的 qa.go 发的是内层错误（不含包装前缀）"。
   线上 A/B 直接推翻该前提——**两种 stage（`knowledge_qa_execution` 与 `agent_execution`）Go 都带前缀**
   （Go 的"内层错误"就是 AppError 本身，其 `Error()` 含前缀）。该剥离已删除，两模式统一走 `wireText`
   （旧方法 `errorEventText` 一并删除，理由挪到调用点注释）。
3. **线上复验**：`:8082` 重启到当前构建后，同一条 2200 错误路径（`/agent-chat`）
   **逐帧比对 4/4 完全一致**：`agent_query` → `tool_call(knowledge_search)` →
   `error(done=false, 带前缀)` → `error(done=true, 带前缀)`。
4. **教训**：本仓那行注释（"Go 发内层错误不带前缀"）读起来完全合理，却是**没取证的对照结论**——
   而它恰恰写在"故意偏离 Go"的代码旁边（同类坑：§0.-43 jieba 的 `LoadDict("")` 读起来像"加载词典"，
   实际是空词典）。**注释里的对照结论同样要取证**。

> 复验顺带发现的**行为**差异（不只是文案）见第七节。

## 四、运维发现（对后续 E2E / A-B 很关键）

- **SSRF 白名单有 DB 侧设置**：`ssrf.whitelist`（`PUT /api/v1/system/admin/settings/ssrf.whitelist`，
  管理员账号 `walkadmin@weknora.test`）。
- ⚠️ **热加载是"按进程"的**：经 Java 写入 → Java 立即生效、**Go 不生效**（仍按启动时的值拦 loopback）；
  **两端都要各 PUT 一次**（或起服时带 `SSRF_WHITELIST_EXTRA=127.0.0.1`）。
- 本次已把 `127.0.0.1` 追加进白名单（保留原值 `["198.18.0.0/15"]`）；**收尾时已用两端 API 各写一次回退**
  （现值复核 `["198.18.0.0/15"]` ✓）。
- 登录响应里 token 在**顶层** `token`（不在 `data` 里）；夹具管理员口令与 `TEST_PASSWORD` 相同。

## 五、复跑步骤（三步）

```bash
# 1) stub（带新场景；如需看 tools 段再加 STUB_RECORD_DIR）
STUB_RECORD_DIR=/tmp/w5obs-rec python3 scripts/stub-llm-server.py 8181 &

# 2) 白名单：两端各 PUT 一次（缺一个就会看到 loopback 被拦）
#    见第四节；起服时带 SSRF_WHITELIST_EXTRA=127.0.0.1 亦可

# 3) 探针（观察项 1 全跑；观察项 2 需前置齐备）
STUB_RECORD_DIR=/tmp/w5obs-rec bash scripts/ab-e2e-observations.sh
```

## 六、收尾（W5γ5.11）——夹具与设置已清理

| 项 | 处置 |
|---|---|
| `ssrf.whitelist` | 回退为 `["198.18.0.0/15"]`（两端 API 各写一次，双进程热加载 ✓；已复核） |
| 测试模型行 `stub-early-w5obs` / `stub-rerank-w5obs` | 已删（删除需先解引用：rerank 行被 agent 引用时 400，删 agent 后 200） |
| 测试 agent `w5obs-stub-agent` / `w5obs-smart` / `w5obs-tools` | 已删（list 复核只剩原 6 个） |
| 测试会话（39 个，标题 `w5obs-*`/`obs-*`/`e2e-obs`） | 已删（残留 0） |
| 临时 stub 8182 | 已关；**8181 保持运行**（跑的是本批更新版脚本：两个早错场景 + 录制开关，默认行为不变） |
| Java 实例 :8082 | ✅ **已重启三次**（首次换构建 + 两次部署修复；日志 `/tmp/ragagent-java-server-w5g511.log`、`-w5g512.log`）——线上复验通过（逐帧 4/4）；**未动**并发会话的那对 `19080/19082` |

## 七、⚠️ 新发现（W5γ5.12→γ5.13，**待修**）：KB 检索失败时 Java 中止、Go 继续

### 7.1 现象（复现过两次，但**非确定性**）

用同一条 2200 错误做双端 A/B 时，`/knowledge-chat`（KB = `shr-kb-alpha`，无向量库/无 embedding 模型）出现：

| 端 | `-w5g511`（18:13）与 `-w5g512`（18:29）两次观测 | 之后 6 次重复（同一实例配置） |
|---|---|---|
| **Go** | `tool_call/tool_result(knowledge_search)` → **`answer`（降级作答）→ `complete`** ✓ | 6/6 降级作答 ✓ |
| **Java** | `tool_call(knowledge_search)` → **`error`×2（2200，回合中止）** ✗ | **6/6 降级作答** ✓（不再复现 ✗） |

两侧日志在失败时刻都完整：`stage=Search action=kb_search_failed error="error code: 2200…"` →
`stage=Pipeline action=stage_failed description="Failed to search knowledge base" error_type="search_failed"`；
而成功时刻两侧都只到 `stage=Search action=output result_count=0` / `stage_fallback reason="search_nothing"`。
⇒ **触发器是状态相关的，尚未钉住**（同一 DB、同一 KB、同一请求；两次失败的 Java 构建各不相同、之后又都不复现）。

### 7.2 已钉准的规则（Go 的错误分级，**与 Java 实为等价**——初始假设被证伪 ✗）

`internal/application/service/chat_pipeline/search.go:127-162`：
- `kbSearchErr != nil && len(allResults) == 0` → `pipelineError("Search","kb_search_failed")` + **`return ErrSearch.WithError(kbSearchErr)`（硬错）**；
- `kbSearchErr != nil`（有结果）→ `pipelineWarn("Search","kb_search_partial_failure")` + **继续**；
- 0 结果 → `return ErrSearchNothing`。

`search_parallel.go:115-130`：并行任务里 **`ErrSearchNothing` 被吞成 nil**（视作成功），硬错才上抛；
`session_knowledge_qa.go:773-791`：`ErrSearchNothing` → `stage_fallback` + `handleFallbackResponse`（strategy=`model` 且 `FallbackPrompt` 空时退化为固定文案）→ **返回 nil** ✓；
硬错 → `stage_failed` + `return err.Err` ✗ → `qa.go:1290-1310`：记日志 + `Emit(EventError)`（**不再中止**）。

**Java 侧同构**（`PluginSearch:128-133/380-405`、`PluginSearchParallel:96-155`、`SessionKnowledgeQaService:383-393`、`KnowledgeQaController:799-816`）
⇒ **分级规则两侧一致**，真正待钉的是"**为什么那两次 Java 的 KB 检索会抛 2200 而 Go 不会**"（同一 KB、同一时刻）。

### 7.3 结案改判（W5γ5.17，用第一次失败实例的日志回挖）

第一次失败的实例日志仍在（`/tmp/ragagent-java-server-w5g511.log`），回挖出两条结论：

- **18:13 那次（AGENT 流）不是分歧** ✅：`search_targets` 含 **11 个租户 KB**（agent `kb_selection_mode=all`），
  其中就有失效绑定的 `ks-golden-store` ⇒ `combined_kb_search_error 2200` ⇒ `firstErr` ⇒ 硬错。
  **这一段与 Go 完全同构**（Go 同范围也硬错 ✓，见 §7.4 的双端表）⇒ 该次属"对齐行为"，
  **顺带解开了 7.6 前那个"日志与代码对不上"的谜**（`firstErr` 来自 `PluginSearch:386` 的合并检索 catch ✓）。
- **18:14 那次（NORMAL 单 KB `shr-kb-alpha`）仍无法解释，但已可排除多种原因** ✗：
  - 该窗口**没有** `combined_kb_search_error`（0 次，非过滤假象）⇒ `:386` 未走；`group_plan` 显示
    `model_key=""` ⇒ `:320` 未走；`individual_targets=0` ⇒ `:403` 未走 ⇒ **按当前源码，这条日志产不出来** ✗
    （`firstErr` 只有这三个写入点，`kb_search_failed` 只有 `PluginSearch:132` 一个发射点，均已逐一核对）；
  - KB 类型是 `document`（非 faq）、`vector_store_id` 为 NULL、`indexing_strategy` 为空 ⇒ 也不是
    FAQ 后处理/向量开关那些路径；
  - 同请求后来 **6/6 两端一致**（都降级作答 ✓），且此后 W5γ5.15/16 又把"KB 读权限/API-key 作用域"
    这一整类原因修掉 ✓。
  ⇒ 判定：**疑为当时构建/瞬态产物，非确定性**；不按"无现象的猜测"改代码 ✗。若再现，走下面配方。

### 7.3 下一步（诊断配方，已备好未留痕）

1. 在 `PluginSearch` 的三个 catch（`:112` / `:386` / `:402`）临时加 `e.printStackTrace()`，把实例挂上**常驻探针**等它复发，栈会直接给出抛出点；
2. 同时抓失败时刻的 `storage/engine 解析状态`（`vector_stores` 行、租户有效引擎、`ResolveEmbeddingModelKeys` 的告警序列）；
3. 钉住后再按"Go 的哪条分支"对齐实现 + 补回归（**预估：小批**，但**前置是复现**；不建议先改代码 ✗）。

### 7.4 DB 线索查证结果（W5γ5.13 续）——找到了 2200 的来处，但**未复现**该分歧

查 dev PG（`WeKnora@localhost:15432`；两端共用）现状：

| 事实 | 值 |
|---|---|
| `vector_stores` 表 | **0 行**（空的） |
| 租户 10002 `retriever_engines` | `{"engines": []}`（**无有效引擎**） |
| 37 个 KB 里唯一带绑定的 | **`ks-golden-store`（`811b7781-249c-4e58-b7d4-83e0b4442c8b`）→ `b1c2d3d4-…-0001`（行已不存在 ✗）**，且 `indexing_strategy.vector_enabled=true`、无 embedding 模型 |
| 其余 36 个 KB | `vector_store_id = NULL`（如 `shr-kb-alpha`、全部 `ks-golden-*`/`ab-chunk-*`/`faq-*`） |

**用它做了确定性验证**（双端 `/knowledge-chat`，每次新会话）：

| 请求的 KB 范围 | Go | Java |
|---|---|---|
| `[ks-golden-store]`（单一） | `error`×2（2200） | `error`×2（2200） |
| `[shr-kb-alpha, ks-golden-store]`（混挂） | `error`×2（2200） | `error`×2（2200） |

⇒ **"范围里含失效绑定的 KB" → 两端都硬错（2200）——这是对齐行为** ✓，不是分歧 ✗；
且**这一路径可作回归夹具**（store 行被删而 KB 仍引用 = 真实会发生的运维场景）。
**但它解释了 2200 的来处、没能解释**"那两次只有 Java 的错误" ✗——当时 Java 的检索范围（日志 `search_targets=1`）与现在完全一致，
现状下同请求 6/6 两端一致 ⇒ **引发分歧的那份状态已经不在**（触发器消失）。

**新的候选解释（未验证，留给复现时先查）**：`ks-golden-store` 正是**曾经的默认/首个 KB**——若失败时刻
Java 的某段作用域解析（agent 的 `kb_selection_mode=all`、或 `/knowledge-chat` 的 KB 兜底）把**全部租户 KB**
纳入了范围，就会吃进这个失效绑定 ✗（Go 同场景亦会 ✗，故仅当两侧作用域解析不同步时才表现为"只 Java 错"）。
复现时的第一优先观察点：**失败那一发的 `search_targets` 里到底有几个 KB / 是哪些**。

### 7.5 已补回归：分级契约（W5γ5.14）

`chatpipeline/SearchGradingTest`（2 例，非实录型——契约断言，桩注入 2200）：

| 用例 | 钉住的契约（对照 Go `search.go:127-133` / `search_parallel.go:115-130,168-176`） |
|---|---|
| `staleStoreBindingIsHardErrorNotDegrade` | **chunk 检索抛错 + 0 命中 ⇒ 硬错上抛**（`search_failed`，**非** `search_nothing`）——单插件与并行阶段都是；原始错误带 `error code: 2200` 前缀（SSE 终止帧文案来源）；**不推进 `next`** |
| `noErrorNoResultsDegradesToSearchNothing` | **无错 + 0 命中 ⇒ `SEARCH_NOTHING`**（降级分支）；任务级 `SEARCH_NOTHING` 被并行插件**吞成"无错"**，不得被当成硬错上抛 |

覆盖缺口说明：`RetrieveEngineFactoriesTest` / `HybridSearchServiceStoreGroupTest` 已覆盖**工厂层**的
2200/2201 映射（`crossTenantStoreMapsTo2200`、`unregisteredStoreMapsTo2201`、`notOwned` 等），
缺的正是**搜索插件的分级行为**（本批补上）；`vector_only_fail` 那个实录只覆盖"embed 失败"的硬错，
不覆盖"0 命中 + 硬错"的组合（正是失效绑定场景）。
门：`--changed` → **B1a PASS（15s）**。

### 7.6 分歧根因找到并修复（W5γ5.15）：`buildSearchTargets` 缺 KB 读权限过滤

**静态对读**（本项正题）发现一条**确定性**分歧（`SessionKnowledgeQaService.buildSearchTargets`
的 `resolveKbTenant` vs Go `resolveKBTenant` + `access.KBPermissions.Check`，context.go:79-94）：

| 情形 | Go | 本仓（修前） |
|---|---|---|
| KB 行缺失（unknown id） | 租户回落 caller、**保留** | 同 ✓ |
| KB 行存在且**自有租户** | 保留（`caller==owner && Viewer`） | 保留 ✓ |
| KB 行存在但**调用方无权读** | `Check` 不过 ⇒ 记 0 ⇒ **`continue` 丢弃该 KB** | **不过滤、照搜** ✗ |
| 组织共享 ≥ viewer | `p.shares.Check` ⇒ 保留 | `checkTenantKBPermission(...).permits("viewer")` ✓（修后接入） |

**根因**：旧注释假称"跨租户访问由上层可见性拒绝"——又一处**未取证的对照结论**（同 §7 的教训家族）。
**判决实验（确定性）**：以 tenant 10002 用户拿**外租户 KB**（`shr-kb-gamma`，tenant 10004）检索：

| 端 | `search_targets` | 结果 |
|---|---|---|
| Go（修前=修后） | **0**（KB 被丢弃） | `search_nothing` → **降级作答** ✓ |
| 本仓（修前） | **1**（真去搜它） | **2200 硬错、回合中止** ✗ |
| 本仓（修后） | 0 | 降级作答 ✓ **与 Go 一致** |

**修法**：`resolveKbTenant` 增可读性判定（`callerCanReadKb` → 静态骨架 `kbReadableByCaller` + 共享判定
`checkTenantKBPermission(...).permits("viewer")`）；不可读 ⇒ 记 0 ⇒ 调用方 `continue` 丢弃。
**② W5γ5.16 补齐**（初版只做"同租户 + 组织共享"，并把另两条误标成"只会放宽"——其中
`AuthorizeTenantAPIKeyKnowledgeBases` 实为**拒绝**路径 ✗）：
- **API-key 作用域已接线**：`TenantAPIKeyScope.authorizeKnowledgeBases` 等价物（Java 早已实现，
  该文件自述"等对应模块接线"）⇒ KB 受限的 Key 指向白名单外 ⇒ 该 KB 被丢弃（Go `Check` 第二步）；
- **比较基准改用"检索作用域租户"**（`buildSearchTargets` 的 `tenantID`；Go 文档：session 租户或
  **共享 agent 的生效租户**）而非 ctx 当前租户——否则共享 agent 会把 **agent 自己的 KB** 误丢 ✗
  （对应 Go 的 `SharedAgentGrantContextKey`）；
- **唯一仍未移植**：`KBGrantsContextKey`（精确授予，由 KB 传输/导入流注入 ctx：
  `access/kb_transfer.go:101`、`knowledgebase.go:61-70`）——不经 QA 检索路径，备案。
回归：`SessionKnowledgeQaKbScopeTest` **6 例**（新增"共享 agent：作用域租户读 agent 自己的 KB ⇒ 可读"）；
线上三例 A/B 复验（外租户/自有/自有失效绑定）全一致 ✓。

**验证**：① 回归 `SessionKnowledgeQaKbScopeTest`（5 例：
自有可读 / 外租户无共享不可读 / 外租户共享可读 / 缺 caller|owner 不可读 / 共享服务缺失按不可读）全绿；
② **线上三例 A/B**（:8082 重启到最终构建）：外租户 KB → 两端 `answer→complete` 一致 ✓；
自有 KB → 一致 ✓；自有**失效绑定** KB（`ks-golden-store`）→ 两端 `error`×2（2200）一致 ✓（未被本修影响）。

**附：本轮门红的两条环境坑**（都与改动无关，已各自定位）：
- `SystemContractTest.parserEnginesOfflineShape` 假红 = **我的 shell 泄漏了 `DOCREADER_ADDR`**
  （`dev-env.sh` 导出 → 门继承 → 端点变成 `connected:true`）⇒ **跑门前别在同 shell source dev-env.sh**；
- `TenantSkillPythonVerifierTest.skillPythonVerifierCaseTable` = **既有环境相关失败**（stash 到 HEAD 复跑同样失败：
  "a pyproject.toml dependency the venv does not carry"），与本批无关。

### 7.7 两处"门红"治本（W5γ5.16，dev/测试环境）

| 现象 | 根因（已定位） | 治本 |
|---|---|---|
| `SystemContractTest.parserEnginesOfflineShape` 假红（断言 `connected:false`，实得 `true`） | 端点读 `System.getenv("DOCREADER_ADDR")`（`SystemController:750`）；调用者 shell 若 `source scripts/dev-env.sh`（导出 `localhost:50051`），**测试 JVM 继承** ⇒ 真连上 docker docreader | `server/build.gradle.kts` 的 `tasks.withType<Test>` 加 `environment("DOCREADER_ADDR", "")` ⇒ **任何入口跑测试都沙箱化** ✓（空串与未设等价） |
| `TenantSkillPythonVerifierTest.skillPythonVerifierCaseTable` 假红（"缺依赖的 skill 必须判坏"却 exit 0） | 登录 shell 的 PATH 里 `/usr/bin` 先于 Homebrew ⇒ `python3` 解析到 **macOS 自带 3.9（无 `tomllib`）** ⇒ 产品脚本 `load_pyproject` 静默 `return None` ⇒ pyproject 依赖检查**整段跳过** ⇒ exit 0。**Go 侧脚本逐字节相同**（`diff -q` 一致）⇒ 上游共有脆弱点，本仓不改产品脚本 | 测试侧**解析一个具备 `tomllib` 的解释器**（依次 `python3`/`3.13`/`3.12`/`3.11`），都不可用才整组跳过 ⇒ 依赖用例恢复真判 ✓ |

**备案（上游共有）**：`tenant_skill_verify.py` 缺 `tomllib` 时静默跳过 pyproject 检查 ⇒ 沙箱运行时须保证
python ≥3.11，否则该检查不生效（两端同款 ✗，改需两侧同步提案）。

**验收**：两处红在"泄漏环境"下复跑通过 ✓；**全量五批全绿 233s（ACCEPTANCE PASS）** ✓。
（中途 B1a 出过一次 `WebToolsRecordingTest` 单例 flake ✗：单跑 18/18 过、批内再跑也过，属批内共享态时序，与改动无关。）

## 八、用户报障修复（W5γ5.18，2026-09-25）：导入文档后"推荐问题"不显示

**症状**（用户报）：文档导入后新建提问，看不到生成的推荐问题（追问建议）。

**诊断链**（每步都有证据）：

1. `GET /api/v1/agents/{id}/suggested-questions` **存在**且两侧都 `200` ✓ —— 但**无 KB 范围**时 Go 返回真实推荐问题、
   Java 返回 `{"questions":[]}` ✗（双端实测）。
2. **带显式 `knowledge_base_ids`** 时**两端都正常** ✓（同一组 FAQ 问题，仅桶序不同——非契约 ✓）
   ⇒ 差异在**"all"分支的 KB 能力过滤**，不在池查询/接口 ✓。
3. 同一 KB 行（`faq-golden-kb`，DB `indexing_strategy` 四标志全 false）**两端读出的能力不同**：
   Go `capabilities={vector:true,keyword:true,faq:true}` ✓ / Java `{vector:false,keyword:false,faq:true}` ✗
   ⇒ quick-answer 的能力过滤（`{VECTOR,KEYWORD}` 任一即可）把 Java 这边的 KB 全滤掉 ✗ ⇒ 空数组 ✓。
4. **根因**：Go 的读路径逐 KB 调 `KnowledgeBase.EnsureDefaults()`（`types/knowledgebase.go:727-770`），
   其中 **零值策略（四标志全 false）⇒ 回填 `DefaultIndexingStrategy()`（vector+keyword=true）** ✓；
   Java 的等价实现 `KnowledgeBaseService.ensureDefaults`（`:172`）**早已存在且正确** ✓
   （get 路径 `getAllTenantById:304` 一直在调 ✓），但 **`listKnowledgeBases`（主列表）漏调** ✗
   —— 而"all"分支正是走主列表（`CustomAgentService` → `kbService.listKnowledgeBases(null)`）✗。

**修法**：主列表 `listKnowledgeBases` 逐 KB 调用 `ensureDefaults` ✓（照 Go `knowledgebase.go:343/356/367`）。

**踩到的坑（值得记）**：初版把**共享 agent 列表**（`listKnowledgeBasesByTenantId`）也补上了 ✗ ⇒
`W5sSharedAgentContractTest.kbListAgentBranch` **立刻红** ✗ —— 该 **w5s 实录**（Go 付费录制的响应）
钉死"共享 agent 列表对零值策略**原样返回**（capabilities 全假）" ✗ ⇒ Go 在这个分支**不归一** ✓。
故最终**只改主列表**，并在代码里原位写明两分支的差别 ✓。**教训：同一函数名（EnsureDefaults）在两个调用点可以有不同效果，实证优先于推断。**

**验证**：
- 新测 `KnowledgeBaseEnsureDefaultsTest` **4 条**（零值⇒默认 / 非零原样 / extract_config⇒graph 同步 / 类型与专属配置默认）✓；
- **线上 A/B**：`builtin-quick-answer` 与 `builtin-smart-reasoning` 两个内置 agent，无 KB 范围时 Go/Java **条数与集合一致** ✓
  （顺序不同 = 桶序，非契约 ✓）；
- 门：`--changed` → **B3 PASS 71s** ✓。

**影响面（不止推荐问题）**：凡走**主列表**的能力过滤都受影响 ✓（KB 选择器/quick-answer 的目标过滤等）；
get/检索路径（`getAllTenantById`、`QaWiring` 的 by-ids）**本已正确** ✓，无需改动。

### 8.2 第二半：**导入后自动生成**已补（W5γ5.19）

用户报障的另一半是"**文档导入后**推荐问题生成"——上一节修的是"取不到"（读路径 ✓），
这一节补的是"**根本没有生成**"：本仓此前只有手动路径（`POST /chunks/by-id/{id}/questions/regenerate`），
自动生成在 `KnowledgeService:300-302` 备案为"未翻" ✗ ⇒ 刚导入的 KB 推荐问题恒为空 ✓。

**Go 的原文**（逐条照抄）：
- 触发：`willSpawnQuestion = willSpawnSummary && kb.NeedsEmbeddingModel() && qg.Enabled`（`knowledge_post_process.go:209-212`）；
- 选块：只取 `ChunkTypeText` 且 `chunkHasExtractableText`，按 `StartAt` 排序（`:225-233`）；批大小 **20**（`:596`）；
- 扇出：`enqueueQuestionGenerationTasks`（`:604-690`）——每批一个任务，载荷**只带 chunk id + 边界邻块 id**（worker 运行时读新内容），
  `MaxRetry=3 / Timeout=30min`，入队失败的槽位由 shortfall 释放；
- worker：`processQuestionGenerationForChunks`（`knowledge_process.go:1812-2110`）——supersede / 取消短路 → 取 KB（聊天模型取 **`kb.SummaryModelID`**）→
  逐块生成（**revision 变化则跳过**）→ 写 metadata + 重建该分块索引 → 终态递减 `pending_subtasks_count`。

**本仓实现**（新增 5 件 + 改 2 件）：
| 件 | 对照 |
|---|---|
| `QuestionBatchPlanner`（纯函数：选块/分批/边界 id） | Go 的 `questionChunks` 收集 + 分批 + `prev/next` 取法 |
| `QuestionBatchPayload`（只带 id + 追踪载体五键） | `types.QuestionGenerationPayload` |
| `QuestionGenerationTaskQueue` + `InProcessQuestionGenerationTaskQueue` | asynq `QueueQuestion`/`TypeQuestionGeneration`（重试公式与图队列同款） |
| `QuestionGenerationService`（批 worker） | `processQuestionGenerationForChunks` |
| `ChunkService.generateAndStoreQuestionsForWorker`（与手动路径**共用**落库：metadata + `updateChunkVector`） | worker 的逐块段；revision 变化**跳过**（手动路径仍是 409） |
| `KnowledgeProcessWorker` 扇出 + 计入 `pendingSubtasks`（`+ questionBatchCount`）、入队失败释放槽位 | `willSpawnQuestion` 判定 + `enqueueQuestionGenerationTasks` |

**验证**：`QuestionBatchPlannerTest` **5 例**（选块顺序/剔除非文本与空内容/20 分批与边界 id/批数/载荷 JSON 往返）✓；
编译 ✓ + **Spring 启动即构造成功**（应用能起 ⇒ 新服务与队列 bean 装配可用 ✓）；门 `--changed` → **B3 PASS 67s** ✓。

**✅ 端到端已跑通（W5γ5.19 续），且抓出两个"单测/门全绿、真机才暴露"的 bug**：

| bug | 现象 | 根因与修法 |
|---|---|---|
| ① **worker 无租户上下文**（致命） | `InProcessQuestionGenerationTaskQueue` 报 `IllegalStateException: model not found`（同一模型在 HTTP 请求里正常） | 进程内 worker 线程没有请求上下文，而 `ModelRuntimeFactory` 的可见性判定读 `TenantContext`（空 ⇒ tid=0 ⇒ 查不到模型行）。**Go 在 worker 开头就 `ctx = context.WithValue(ctx, types.TenantIDContextKey, payload.TenantID)`** ⇒ Java 侧漏了这步。修法：`handle()` 按 `WikiBatchSupport` 同款纪律绑定/恢复租户 |
| ② **槽位递减时机不对** | 失败且还会重试时就释放了 finalizing 槽 ⇒ 父知识可能在问题落库前完成 | Go：`willDrain = retErr == nil \|\| final`。修法：队列按 `attempt > MAX_RETRY` 传 `terminal`，service 仅在 `succeeded \|\| terminal` 时递减 |

**真机证据（Java 腿）**：
```
KnowledgeProcessWorker I [KnowledgePostProcess] Knowledge <id> entered finalizing (1 subtask(s) pending)
QuestionGenerationService I Question generation (batch): knowledge=<id> batch=0 chunks_in_batch=1 processed=1 generated=3
chunks.metadata.generated_questions → 3 条（"WeKnora 支持哪些检索方式？" 等）
```
（`wiki/graph` 都关 ⇒ `finalizing (1 subtask)` 那 1 个槽就是**问题批** ⇒ 扇出记账生效 ✓。）

**E2E 配方（约 10 分钟，踩过的坑都在这）**：
1. 起**本地 LLM stub**（一个 HTTP 服务同时提供 `/v1/chat/completions`（返回 `1. …\n2. …\n3. …`，
   解析器是"逐行 trim → 裁前缀 → >5 字节才收"）与 `/v1/embeddings`（固定向量，维度与模型行一致））；
2. 建 chat + Embedding 两个模型行：**必须同租户**（从别的租户的行复制 ⇒ `getByIdVisible` 找不到 ⇒ `model not found`）、
   **id 用 UUID**、`api_key` 明文亦可；**插入后必须重启 Java**（模型行有启动期缓存）；
3. SSRF 白名单加 `127.0.0.1`（否则 `baseURL SSRF check failed: hostname 127.0.0.1 is restricted`）；
4. 目标 KB：`question_generation_config={"enabled":true,"question_count":3}` + 指向上面两个模型行 +
   `indexing_strategy` 至少开一个（`vector||keyword` —— 问题生成的触发条件之一）；
5. 建知识用 **`/knowledge/manual`**（`docreader` **不支持 txt**：`docreader parse error: Unsupported file type: txt`）；
   上传**同名文件会被去重**成同一行（换名再试）；
6. 查 `chunks.metadata.generated_questions` 与 `/agents/{id}/suggested-questions`。

**Go 腿 A/B 未完成**：Go 侧用同一 stub 模型在嵌入阶段被远端 `401` 挡住（Go 自己的模型缓存/嵌入路径问题），
按"不追非本批范围"停手。

（dev 夹具**已全部复原**：探针 KB/知识/分块已删、`BQ Alpha/Beta` 配置回原值、自建模型行已删、
SSRF 白名单两端回退为 `["198.18.0.0/15"]`、本地 stub 已停 ✓；`:8082` 跑当前构建 ✓。）
