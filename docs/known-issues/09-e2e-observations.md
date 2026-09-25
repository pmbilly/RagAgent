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
**处置**：✅ **已修（W5γ5.11）**。根因两处——① `agent/AgentEngine.java` 工具执行失败处
`r.setError(execError.getMessage())`（`~1948`，丢前缀）；② `agent/tools/ToolRegistry.java` 的
"工具抛异常"兜底把文案**写死成 `"tool returned no result"`**（比丢前缀更严重）。
修法：新增 `common/error/BizException.wireText(Throwable)`（沿 cause 链取最近 `BizException` 的
**已带前缀** message，否则退到最深非空 message——即 Go 的 `err.Error()` 语义），两处改为调它；
`AgentEngineException` 补 `(message, cause)` 重载保留 cause。
回归：`ToolRegistryRecordingTest` 新增"工具抛异常 → 文案照 Go 的 err.Error()"（含包装层穿透与裸异常），
门 B1a+B1b 全绿（49s）。⚠️ 线上复验需重启 Java 实例（当前 :8082 仍是旧构建，本批**未重启**——见下节）。

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
| Java 实例 :8082 | **未重启**（跑的是旧构建）——故"终止错误帧前缀"的**线上复验待一次重启**；单测与门已覆盖 |
