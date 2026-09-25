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
