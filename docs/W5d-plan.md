# W5d 作战计划（真缺口收尾批）

> ⚠️ **2026-09-21 下半场状态更新：W5d 已部分开工（另一 agent），本计划需按实况读。**
> 终端 2 条 + local-browser 2 条已写出代码但**未提交、未登记、未验收，且当前 Spring 上下文起不来**；
> embed QA 3 条**未开工**。**接手先看 `docs/HANDOFF.md` §0.0**（含根因、未登记清单、续做顺序），
> 本文件继续有效的是：§4 的协议契约与验收方案、§5 的开工纪律、§6 的后续波次。
>
> 拟于 2026-09-21 · 基线 `2c71495`（W5c 收官 · golden 1,649）· 立此文档的依据是**本会话的独立复核**，
> 不是 HANDOFF 的转述。开工前请连同 `docs/HANDOFF.md` §0 与 `docs/translation-conventions.md`
> §3/§6/§7.5/§8 一起读（§9 的正文在 `docs/known-issues/`，按批次分片）。

## 1. 复核结论（本会话实测，可直接采纳）

| 检查项 | 结论 |
|---|---|
| 仓库状态 | `main` @ `2c71495`，工作区干净（仅一个未跟踪的 `.zcodeignore`），与 HANDOFF 一致 |
| golden 数 | `server/src/test/resources/contracts/` **1,649** 个；w5a 56 / w5b 45 / w5c 85，**w5d-* 为 0**（未开工） |
| 路由缺口 | 新增 `scripts/route-recon.py` 程序化对账：Go 389 / Java 444（verb+path，参数名归一后 交集 380）→ **真缺口候选 8 条**，与 HANDOFF 声称的「7 条 + models/{id}/debug」**完全吻合**，无新增缺口 |
| WebSocket 基建 | **Java 侧完全没有**——`server/build.gradle.kts` 无 `spring-boot-starter-websocket`，全仓无 `WebSocketHandler` / `ServerEndpoint` / 手工 `Upgrade` 握手。这是 W5d 唯一的**新基建** |
| sandbox 终端传输 | Java 侧无 `SessionTerminalManager` 等价物，`sandbox/runtime/` 只有 health/templates/create/list/delete 形态的远端客户端；terminal 的空闲断连配置**已翻译**（`EffectiveConfig.effectiveTerminalIdleDisconnect`，W5b/wave3 落地） |
| browserskill | Java 已有 `BrowserSkillManager`（含 `Sec-WebSocket-Protocol` 校验、`Enabled/Account/Pair/Revoke/...`），但 `relayHandshake/pump` 明确留了「波 4 接缝」并抛 `IllegalStateException` |

## 2. W5d 范围（8 条候选的定性）

| # | 路由 | Go 出处 | 定性 |
|---|---|---|---|
| 1 | `POST /api/v1/sessions/:session_id/sandbox/terminal-ticket` | `routes_chat.go:67` | **真缺口**，Auth 之后（Viewer 组内） |
| 2 | `GET /api/v1/sessions/:id/sandbox/terminal?ticket=` | `routes_chat.go:149` | **真缺口**，**在全局 Auth 之前**注册，自鉴权（ticket + `AttachAuthenticatedUser`） |
| 3 | `GET /api/v1/sessions/:id/local-browser` | `routes_chat.go:68` | **真缺口**，同 handler、`GET`=GetStatus |
| 4 | `POST /api/v1/sessions/:session_id/local-browser` | `routes_chat.go:69` | **真缺口**，同 handler、`POST`=action 分派 |
| 5 | `POST /api/v1/embed/:channel_id/knowledge-chat/:session_id` | `routes_agent.go:239` | **真缺口**，公开面 QA 委托 |
| 6 | `POST /api/v1/embed/:channel_id/agent-chat/:session_id` | `routes_agent.go:240` | **真缺口**，同上 |
| 7 | `GET /api/v1/embed/:channel_id/files` | `routes_agent.go:255` | **真缺口**（重定向脚本把它归到「疑似」，人工确认 Java 侧确实没有） |
| 8 | `POST /api/v1/models/:id/debug` | `routes_infra.go:30` | 已备案推迟（阶段 7，Java 已 404 占位）保留 |

> 噪声项（非缺口，勿做）：`GET /swagger/*`（Go 的 swagger UI，非业务 API）。

## 3. 批次切分（建议）

W5d 名义上一批，实际是**三块异质工作**。终端那一块带新基建，按 conventions §6「主会话做共享契约与跨模块装配」
的原则**不能整体派 agent**。建议：

| 子批 | 范围 | 谁做 | 规模 | 依赖 |
|---|---|---|---|---|
| **W5d-1** | 终端 WS 面（路由 1、2 + 服务面 + ticket） | **主会话主导**基建，service/bridge 可派 agent | Go ≈1,350 行（ws 426 + bridge 340 + service 262 + ticket 92 + cube/e2b terminal 391 + session_manager） | 无（新基建自足） |
| **W5d-2** | local-browser 2 条（路由 3、4） | agent（小批） | Go 158 行 | 无 |
| **W5d-3** | embed 公开 QA 委托 3 条（路由 5、6、7） | agent（小批） | Go ≈200 行（handler 分派 + 复用） | 复用 4.6d `KnowledgeQaController` 与 W5c `FileProxyService` |

**顺序**：W5d-2 + W5d-3 可以**合派一个 agent 一批做掉**（5 条路由、规模小、都走 HTTP 面），
主会话同时开 W5d-1 的基建；两者不重叠文件，可并行。W5d-1 完成后再派 agent 补 service/bridge 的机械翻译。

## 4. 各子批实施要点与验收

### W5d-1 终端 WS 面（最高风险，先做基建）

**Java 侧要新建的四件东西**

1. **WS 基建**：建议加 `spring-boot-starter-websocket` 依赖 + `WebSocketConfigurer` 注册
   `/api/v1/sessions/{id}/sandbox/terminal`；该端点必须在 `AuthFilter` / RBAC 的**让路名单**里
   （Go 是在全局 Auth **之前**注册的；参考 W5c 给 `/r/` 前缀让路的成例——AuthFilter 通道 1.7）。
   ⚠️ 半成品走的是**另一条路**：`TerminalWebSocketServer` 手写 RFC 6455 + 101 后继续用 Servlet
   裸流（未加依赖）。Servlet 规范不支持这种用法，**Tomcat 上能否工作必须先实测**（见 HANDOFF §0.0）。
2. **ticket**：对照 `internal/application/service/sandbox_terminal_ticket.go`(92 行) ——
   同一 JWT secret、`type=sandbox_terminal`、claims `{user_id, tenant_id, session_id, token_id}`、
   TTL **2 分钟**（`DefaultSandboxTerminalTicketTTL`），且**必须被 `ValidateToken` 拒绝**
   （它不是 access token）。跨语言互操作要实测（波 3 browserskill 有同款先例：Go 铸造的凭据能过 Java 校验）。
3. **`SessionTerminalManager` 接缝**：对照 `internal/sandbox/session_manager.go` + `capabilities.go` +
   `sandbox.go` 的 `ErrTerminalUnsupported`（无 terminal 能力的 provider 要返回这个错误而不是 panic）。
4. **PTY 传输**：`cube_terminal.go`(183) / `e2b_terminal.go`(208) —— `OpenTerminal` /
   `Write` / `Resize` / `Close` / `Output() <-chan RemoteTerminalEvent` / `PID`。
   **先确认 Java 的远端客户端支持哪些 backend**（`SandboxTypes`/`SandboxBackendPolicy`），
   不支持的按 XDEP 标注而不是硬造。

**协议契约（逐条钉，全部来自 `sandbox_terminal_ws.go` 头部注释）**

- 帧：`binary` = 原始 PTY 字节（双向）；`text` = JSON 控制帧（resize/ping/ready/error/exited 双向）
- 心跳 30s（`terminalPingInterval`）、读超时 **120s**（刻意 4× ping 间隔，背景标签页会节流）、
  写超时 10s、单帧输入上限 **4096B**、几何上限 **500**、**每会话最多 5 个并发 PTY**
- 空闲断连取租户配置 `effectiveTerminalIdleDisconnect`（Java 已有该 helper，直接接）
- 升级后**约每分钟**复检绑定 `auth_tokens.id` 行（登出/吊销即拆桥）；bridge 的五个 goroutine
  （output/input/heartbeat/idle/auth）里**任一个**观察到终止就由 `teardownOnce` 驱动统一清理
- 失败文案（用户可见错误，要逐字节）：`invalid or expired ticket`(401) /
  `ticket is not valid for this session`(403) / `failed to validate ticket`(500) / `failed to issue ticket`(500)
- **虚拟线程下 `TenantContext` 必须显式拷贝**（conventions §5 + HANDOFF §2.3 风险③）

**验收**
- golden 前缀 `w5d-term-*`；ticket 端点可纯 HTTP 录（401/403/404/200 形态 + claims 载荷可断言部分）
- WS 面**不能**逐字节 A/B 全链路（无真 PTY）→ **双端同打 stub**：桩掉 sandbox 远端，
  按帧比对 ready/error/resize/exited 控制帧；真 PTY 路径标 **XDEP**
- 跨语言互操作单独一条：Go 铸 ticket → Java 握手通过（反向亦然）
- 服务面 + bridge 的纯逻辑件（几何钳制、空闲计时、帧上限、并发计数）走**Go 实录单测**（§9.1 方法）

### W5d-2 local-browser 2 条

对照 `internal/handler/session/browserskill.go` L20-83（158 行全文很短，建议整文件翻译）：

- 同一个 handler 挂两个路由（`GET /:id/local-browser`、`POST /:session_id/local-browser`），
  **Go 靠 `c.Param("session_id")` 取不到时回退 `c.Param("id")`** —— Java 侧两条 mapping
  要各自处理（Spring 下建议同一方法配双 pattern 或用 `@PathVariable(required=false)` 双参，参考
  `MessageSuggestionController` 的 `@GetMapping({"...{session_id}...","...{id}..."})` 成例）
- `GET`：`GetOwnedSession` 失败 → **404** `{"error":"session not found"}`；
  `GetStatus` 失败 → 503 `err.Error()`；成功 → `{"success":true,"data":status}`；`Cache-Control: no-store`
- `POST`：`!Enabled()` → **503** `local browser is unavailable`；`MaxBytesReader` **4096**；
  解析失败 → **400** `invalid browser action`；`action` 分派：
  `preview`（走 `c.Data` 手拼 `{"success":true,"data":<frame>}`，**不是** gin.H）、
  `focus`、`select|start|resume|pause|stop`→`Control`、其余 → 400；
  业务错误 → **409** `err.Error()`；成功 → 再取 `GetStatus` 返回
- 鉴权：两条都在 `sessions` 组内（Viewer + API key chat 能力）→ **`WebConfig` 的 addRule 与
  拦截器 `addPathPatterns` 必须同批核对**（W5a 踩过：只 addRule 不加 pattern = 空转）

**验收**：golden `w5d-lb-*`（404/503/400/409/200 五形态 + 各 action），A/B 两轮；
真设备（扩展）路径 XDEP。

### W5d-3 embed 公开 QA 委托 3 条

- `knowledge-chat` / `agent-chat`：复用 4.6d 的 QA 路径（`KnowledgeQaController`），
  **EmbedAuth 注入的是 channel 的租户 + embedVisitorId**，不是登录用户——visitor 身份要显式传递
- `files`：复用 W5c 的 `FileProxyService` / `newFileServeHandler` 等价物；EmbedAuth 已注入租户，
  handler 侧仍要校验「请求路径属于该租户」（Go 注释明确写了这一点）
- `WebConfig`：`/api/v1/embed/**` **刻意不登记** RBAC 规则（它不走 AuthFilter/RBAC）——
  别顺手加规则，`APIKeyRoutePolicies` 同理（已有注释备案）

**验收**：golden `w5d-emb-*` + `ab-w5d.sh` 双端同指 **stub LLM**（复用 `scripts/stub-llm-server.py`
与 `ab-emb.sh` 的通道-会话准备逻辑）；visitor 越权、跨租户路径要各一条负例。

## 5. 开工前必做的动作（照 §3.3 纪律）

```bash
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
cd /Users/billy/ragagent-java && git status
lsof -ti :8082 | xargs kill -9 2>/dev/null   # 旧 Java server 占端口会让新路由表现为 404
python3 scripts/route-recon.py               # 起点基线：真缺口候选应为 8
ls server/src/test/resources/contracts/ | grep '^w5d'   # 应为空，防前缀冲突
```

派 agent 的任务书模板沿用 HANDOFF §0 的「重派模板（W5d）」，并**补上**本计划的新事实：
① 终端批拆成「主会话基建 + agent 机械翻译」两段；
② `WebConfig.addRule` 与拦截器 pattern 同批核对；
③ 小包批测试，**勿与 agent/chatpipeline 同批**（Mockito attach 假红，4.6d 复发确认）。

## 6. W5d 之后（按依赖排序，供后续会话选向）

1. **波 5**：`im` 执行体（`internal/application/service/im/service.go` 3,453 行）+ `tenant_skill_*` 收口 +
   `shared_agent_access → tools` + 共享 agent QA 解析（`GetSharedAgentForTenant`，4.6d 备案）
2. **检索引擎批**：`HybridSearch` 执行面（向量/关键词检索实质执行；4.6d adapter 留了空/1003 两形态，
   纯聊天路径不受影响）
3. **执行体批**：`ArtifactCollector` / `rewriteArtifactReferences` / `VLM Predict` 的生产装配
   （dev 部署两侧同形 no-op，只有真部署才需要）
4. **专项 / Owner 决策**：`mcp×storage` 的 `SsrfGuard` 互踩（既有问题，W5a 发现）、
   ⑱ MCP `initialize` 契约对齐、`SkillEnvironment` 位置、波 3 `SkillFrontmatter` 的 snakeyaml 宽容类型、
   `TenantService` 占位是否变真、`ConversationProperties` 多环境接线
5. `models/{id}/debug`（阶段 7，规则已登记、控制器 404 占位）

## 7. 工具

- `scripts/route-recon.py`（本会话新增）：Go↔Java 路由对账。**局限性写在文件 docstring 里**——
  Go 侧会漏解析少量非常规 group（实测 389 vs Java 444），所以「真缺口候选」可信、
  「只在 Java」那一段只是噪声。每批收尾跑一次，确认真缺口数只减不增。
- 既有：`scripts/record-*.sh` / `scripts/ab-*.sh`（参数化 `XXX_TARGET_PORT/XXX_OUT_DIR`）、
  `scripts/stub-llm-server.py` / `stub-ollama-server.py`、`scripts/dev-env.sh` / `go-server-up.sh` /
  `java-server-up.sh` / `token.sh`
