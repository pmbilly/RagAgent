# W5δ provider 终端执行体：**传输层 spike**（2026-09-25）

> 决策简报（HANDOFF §0.-34 表）里的阻塞点原文：**"需真 provider/沙箱；且要先定 zerodep stdin 无半关闭
> 对双向流的影响"**，建议"先做**传输层 spike 评估**（真实 cube/e2b 会话量化 EOF 不可表达的后果），
> 有结论再谈 ~1.3k 行执行体"。
>
> **本文结论：该阻塞点不成立**——协议里既没有双向流，也没有"stdin 半关闭"这个概念；
> 零依赖 Java（`java.net.http.HttpClient`）就是够用的实现面。离线部分已用**本地 envd 桩**实证（本批落地），
> 真机只剩第四节那份"只能在真实 provider 上量"的清单。

## 一、结论

| 问题 | 结论 | 证据 |
|---|---|---|
| 喂键击要不要"半关闭的 stdin 通道"？ | **不要**。每次喂入是一条**一元 POST**（`/process.Process/SendInput`，裸 JSON 体） | Cube SDK `pty.go:432`；Go 侧还要 `ptyInputCoalescer` 聚合突发，正因为每次都是**独立往返** |
| 输出要不要"全双工"（同一连接边写边读）？ | **不要**。建/重附 PTY 是**服务端流**：请求体**一次性发完**，再读响应体的帧序列 | `pty.go:368`（`encodeConnectEnvelope` 作请求体）+ `PtyHandle.readLoop` 从响应体读帧（`pty.go:600-620`） |
| E2B 侧是不是另一套？ | 实现不同、**协议同一套**：connect-go 生成的 `Start/Connect/SendInput/Resize` | go-e2b `pty.go:115,158,186,194`；两者都是 envd 的 `/process.Process/*` |
| `HttpClient` 够不够？ | **够**：`BodyHandlers.ofInputStream()` 读流 + 一元请求并发 | 本批 `EnvdConnectTransportTest` 第 1 条（本地桩，0.019s 通过） |

**"EOF 不可表达"的后果量化 = 0。** 原担忧的场景是"客户端必须关闭 stdin 半连接，服务端才知道输入结束"；
本协议里输入天然离散（每次一个完整请求），服务端**从不依赖请求流的 EOF**。

## 二、协议规格（照 SDK 逐条，可直接实现）

| 项 | 值 | 出处 |
|---|---|---|
| 路径 | `POST /process.Process/{Start,Connect,SendInput,Resize,Kill}` | `envd.go:90`、`pty.go:107,122,142,368,432` |
| 帧 | 5 字节头 = 1 flag + **大端 uint32** 长度 + payload；`0x01`=压缩、`0x02`=end-stream；上限 **64MB** | `connect.go:17-20,35-62` |
| 流式头 | `Content-Type: application/connect+json`、`Connect-Protocol-Version: 1` | `connect.go:18` |
| 一元头 | `Content-Type: application/json`、`Connect-Protocol-Version: 1` | `pty.go:434-436` |
| 流超时 | `Connect-Timeout-Ms` 是**服务端**上限；Cube 传 **24h**（SDK 默认 60s 会静默掐掉空闲终端）；客户端**不设** HTTP 级超时 | `envd.go:setConnectTimeout`、`cube_terminal.go:cubeTerminalTimeout` |
| 认证 | `Authorization: Basic base64(user + ":")`（空 user=root）+ `X-Access-Token` + traffic-token 头 | `envd.go:basicAuthUser,newEnvdRequest` |
| end-stream 错误 | `{"error":{code,message}}` → 文本 `code: message`（message 空回落 `Connect stream error`） | `connect.go:64-88` |
| 压缩帧 | **拒绝**（不尝试解压） | `pty.go:605-607` |
| 一元错误 | 状态 ≥400 → 读取体（≤64KB）→ `<method> failed: <message>` | `pty.go:unary` |

## 三、已落地（本批 spike 产物）

- `sandbox/runtime/terminal/EnvdConnectTransport`（~300 行）：帧编解码（含 64MB 上限与截断检测）、
  一元/流式调用（裸体 vs 帧化体、两个 Content-Type、`Connect-Protocol-Version`、`Connect-Timeout-Ms`）、
  `StreamingCall`（`next()`/`endError()`/`close()`）、错误映射到仓库既有的 `SandboxException`。
- `EnvdConnectTransportTest`（**本地 envd 桩**，4 条）：
  1. **★流开着时仍能发一元输入**——流处理器阻塞等 `SendInput` 到达才写第二帧，客户端在流未结束时完成一元 POST；
     并逐项断言请求形状（帧头 flag=0/长度自洽、`application/connect+json`、`Connect-Timeout-Ms=86400000`、
     `X-Access-Token`、`Basic cm9vdDo=`；一元体是**裸 JSON** + `application/json`）；
  2. 压缩帧 → 拒绝（文案照 SDK）；
  3. end-stream 错误 → `endError()` 出 `resource_exhausted: pty gone`；
  4. 一元 404 → `SandboxException("Resize failed: no such process")`。
- 桩的一个副产品也值得记：`HttpServer` 默认执行器是**单线程**，第一版测试因此失败（流处理器把一元请求堵在门外）——
  这恰好说明"流与一元调用真交织"这件事是被真实验证过的，不是空想。

## 四、真机量化清单（只能在真实 cube/e2b 上量；含"若不符则…"）

| # | 要量什么 | 怎么量 | 若不符 |
|---|---|---|---|
| 1 | **E2B 的 envd 是否接受 JSON 编解码**（其 SDK 用 connect-go 的 binary protobuf 客户端；Cube 的 envd 明确吃 `application/connect+json`） | 对该端点发一个 JSON 体的一元调用（如 `Resize` 到不存在 PID），看是否 200/合理错误 | 若只吃 protobuf：Java 需自带最小 protobuf 编解码（`ProcessEvent`/`PtyInput` 等三五个消息；仍零依赖，工作量 +~200 行） |
| 2 | 数据面 URL 形态与**必需头集合**（网关前缀？`X-Access-Token` vs traffic token？） | 用 SDK 直连成功的那套头逐条替换（先全带、再逐条去掉） | 把必需头写进 provider 配置面 |
| 3 | `Connect-Timeout-Ms` 的真实上限与超时后的**流结束形状**（end-stream 错误？直接断连？） | 传 1s 建 PTY，等 2s 看收到什么 | 若"直接断连无帧"：Java 侧要按读超时兜底并归类 TIMEOUT |
| 4 | 长空闲终端的存活（24h 是否被接受；网关有无 idle timeout） | 建 PTY 空置 5–10 分钟再发键击 | 需要前端自动重连 + `attachPid` 重附（中性层已有该旋钮） |
| 5 | `Start` 的**事件序列**与 PID 出现位置（`readPtyStartPID` 依赖首个含 PID 的事件） | 录一次真实 Start 的完整帧序列 | 照实录调整"何时拿到 PID"的状态机 |
| 6 | 重附语义（`Connect(pid)` 对已退出 shell 的行为） | 杀掉 shell 后再 `Connect` | 决定 `attachPid` 失败回落的判据（Go：失败回落 Create） |
| 7 | 沙箱 TTL 刷新与终端的交互（Go 每 `ttl/3` 刷 `SetTimeout`） | 观察长会话中沙箱是否被回收 | 保持刷新；不支持则落"终端保活=不做"的备案 |

## 五、执行体骨架落地（2026-09-25，W5γ5.9）

spike 之后同一批把**不依赖真机的那半**做完了（控制面留接缝）：

| 件 | 内容 | 对照 Go |
|---|---|---|
| `EnvdPtyProtocol` | 请求体构造 + 事件解析 + 退出码解析 + 方法名常量 | `pty.go` 的结构体 JSON 标签（含 **Resize→`Update`、Kill→`SendSignal`、信号值 `SIGNAL_SIGKILL`** 三个易错点）；`exitCodeFromStatus`（`exit status N` / `signal N`→128+N / `exited`→0） |
| `EnvdTerminalSession` | 输出队列（容量 256 背压）、收尾恰好一次（end.error → 无 end 事件 → exited 的顺序判定）、幂等 Close（断开不杀）、`PtyInputCoalescer` 喂入、TTL 刷新（立即 + `ttl/3` 钳位，8s 超时） | `cube_terminal.go:126-183` + `pty.go:250-320`（readLoop/Wait/recordEnd）+ `terminal.go:244-283`（startTerminalTTLRefresh） |
| `EnvdTerminalManager` | 解析端点 →（`attachPid>0` 先试 `Connect`，**失败回落 Create**）→ 读 `start` 事件拿 PID → 起会话 | `openCubePty`/`openE2BPty` + `readPtyStartPID` |
| `TerminalTypes.TerminalSessionState` | 新增可选能力：会话是否终结（**Go channel 关闭**的 Java 等价物） | Go 的 `for range Output()` |
| `TerminalBridge.adapt(...)` | 中性会话 → 桥窄接口；`pumpOutput` 补 null 收尾 | `sandbox_terminal_bridge.go` 的装配 |

**测试**（本地 envd 桩，6 条）：生命周期（Start→PID→data→end→exited，含退出码 7）、
输入线上形状（`SendInput` + base64 + PID）、改窗口（**`Update`** + `{pty:{size:{rows,cols}}}`）、
建流请求形状（`/bin/bash -i -l` + `TERM/LANG/LC_ALL` 默认 + `cwd=/workspace` + 80x24）、
"流结束但无 end 事件"→ 错误事件、end-stream 错误 → 原文、`status` 文本兜底（signal 15→143）、
重附回落、桥适配（含终结后 `next()==null`）。合计 **15 条**（含 spike 的 4 条）全绿，门 B4 80s。

**仍未做的部分（诚实边界）**：`EndpointResolver` 的真实实现（cube/e2b 控制面路由 + token 头 + TTL 钩子）
——需真机校准（第四节清单 1–2）；`SandboxTerminalController:300` 的装配点已留（注释指向本批装配件），
待 resolver 可用后接通。**执行体本体不再是"未翻译"，只剩"未接线"。**

## 六、成本与建议（按 W5γ5.9 落地后的口径更新）

- **传输层** ✅ 已给出（`EnvdConnectTransport` + 4 条桩测试）。
- **执行体本体** ✅ 已给出（`EnvdPtyProtocol` + `EnvdTerminalSession` + `EnvdTerminalManager` + 桥适配，
  6 条桩测试）；**不再是"~1.3k 行未翻译"，而是"只剩未接线"**——剩余为 `EndpointResolver` 的真实实现
  （cube/e2b 控制面路由 + token 头 + TTL 钩子）与 `SandboxTerminalController` 的装配接通。
- **真机清单 1–7**（第四节）是唯一还需外部条件的部分：**一次带凭据的会话**（半小时级）即可收口；
  若第 1 项不符（E2B 只吃 protobuf），追加"最小 protobuf 编解码"子项（+~200 行，仍零依赖）。
- **建议**：拿凭据跑清单 1–4 → 写 resolver → 接通控制器装配点 → 本族收口。

---

## 七、真机前的离线收口（2026-09-25，W5γ5.17）：清单↔桩覆盖 + 控制面规格

### 7.1 清单 1–7：哪些**已被桩证明**、哪些真机才需量

| # | 桩已覆盖（测试名在 §三/§五） | 真机仍要量的 |
|---|---|---|
| 1 | 本仓传输层说的是 `application/connect+json`（Cube envd 已明确吃 JSON）；一元体裸 JSON + 帧化流已被桩逐项断言 | **E2B 的 envd 是否也吃 JSON**（其 SDK 用 binary protobuf 客户端）——不符则 +~200 行最小 protobuf（仍零依赖） |
| 2 | **URL 形态已定**：数据面一律 `49983-{id}.{domain}`（Go `sandbox.go:67-68` `CubeEnvdPort=49983`）；**头集合已定**：`X-Access-Token` + `Authorization: Basic base64("<user>:")`（仅当请求无 Authorization 时加，`envd_compat_transport.go:60-97`） | 网关前缀/逐条必需性（"先全带，再逐条去掉"）；Cube 经 proxy 时 Host 头即 `49983-{id}.{cube.app}`（`cube_mock_test.go:330`） |
| 3 | **超时后的流结束形状**已被桩覆盖两态：end-stream 错误（→`endError()`）与"流结束但无 end 事件"（→错误事件） | `Connect-Timeout-Ms` 的**真实上限**；超时若"直接断连无帧"⇒ 读超时兜底并归类 TIMEOUT |
| 4 | 中性层已有重连旋钮（`attachPid`） | 24h 是否被接受、网关 idle timeout；长空闲后键击是否仍通 |
| 5 | Start→PID→data→end→exited 的事件序列与退出码解析已被桩钉住（含 `exit status N`/`signal N`→128+N） | 真机 Start 的完整帧序列（确认 PID 出现在首个含 PID 的事件，与桩一致） |
| 6 | "重附失败回落 Create"已覆盖 | 对**已退出 shell** 的 `Connect(pid)` 行为（决定回落判据） |
| 7 | TTL 刷新已实现：立即 + `ttl/3` 钳位、8s 超时（`cube_terminal.go:80` `sb.SetTimeout(ttl)`；`e2b_terminal.go:92` `SetTimeoutWithContext`） | 长会话中沙箱是否真被保活（不支持则落"终端保活=不做"备案） |

⇒ **真机会话要量的其实只剩：①E2B 编解码、②网关头集合、③超时上限、④长空闲、⑥退出后重附**；
事件序（⑤）与超时形状（③的后半）已由桩证明。

### 7.2 控制面规格（写 resolver 前必须知道的，全部带 Go 出处）

| 事实 | 出处 | 对 Java 的含义 |
|---|---|---|
| **traffic token 只在 create 响应里发一次**，connect/resume 都不重复："both persisting it and attaching it are WeKnora's job" | `e2b_remote_client.go:147-155`；Cube 同款注释 `cube_remote_client.go:125-135`（"only at create time and never repeats it on connect or resume, so the lifecycle has to persist it"） | ~~Java 的沙箱绑定记录必须持久化该 token~~ ✅ **已有**：`SessionSandboxBinding.trafficAccessToken`（`:64`）+ `SessionSandboxBindingStore.replaceTrafficTokenIfMatch`（`:261`）+ `SandboxSessionClient.inboundTokenOf(handle)`（`:241`）——**Java 侧不缺这一环**（初稿误记为"尚无字段" ✗，已纠正） |
| 句柄能力面 `RemoteInboundTokenCarrier.TrafficAccessToken()` | `terminal.go:162-168`（`handleTrafficAccessToken`） | Java provider client 需暴露等价能力；**Docker 类后端故意不实现**（无入站凭据，"拿不到"≠"丢了"，`remote_fake_test.go:27-35`）⇒ resolver 需有 tokenless 分支 |
| 数据面地址 = `49983-{id}.{domain}`；Cube 经 SDK proxy（`sb.GetHost(49983)`） | `sandbox.go:59-68`、`cube_remote_client.go:1178,1201`、`gateway_transport.go:12` | 已可写：E2B 直拼 `https://49983-{id}.{sandboxDomain}`；Cube 走 `cubeProxyUrl` + Host 头 |
| TTL 刷新 = provider 自己的 `SetTimeout`（cube）/`SetTimeoutWithContext`（e2b） | `cube_terminal.go:80`、`e2b_terminal.go:92` | 对应 Java 的 `Endpoint.ttlRefresher` 回调，**不是**数据面调用 |

### 7.3 真机会话执行手册（半小时级，拿凭据后照此跑）

1. **造一个沙箱**（cube 或 e2b 任一侧即可先量通用项）：记录 create 响应里的 `TrafficAccessToken`；
2. 数据面探针（清单 1/2）：对 `https://49983-{id}.{domain}` 发**一元** `Resize` 到不存在 PID，
   用 `application/json` 裸体；全带 `X-Access-Token` + `Basic`，再逐条去掉看变化；
3. 超时（清单 3）：`Connect-Timeout-Ms: 1000` 建 PTY，等 2s 看收到 end-stream 错误还是断连；
4. 长空闲（清单 4）：建 PTY 空置 5–10 分钟再发键击；
5. 重附（清单 6）：杀 shell 后 `Connect(pid)`；
6. 把结果回填本文件 §四 的"若不符"列，然后写 resolver（Java 侧前置项见 §7.4）+ 接通
   `SandboxTerminalController:301` 装配点，本族收口。

### 7.4 Java 侧写 resolver 的**两个真实前置项**（都不是 token 那条）

摸查结论（2026-09-25，W5γ5.17）：

1. ✅ **已做（W5γ5.17）** —— ~~接缝签名与绑定存储不对口~~：`EnvdTerminalManager.EndpointResolver.resolve(String sandboxId)` 只拿得到
   sandboxId，而 Java 绑定存储的键是 `SessionSandboxBindingStore.SessionSandboxKey(tenantId, sessionId)`
   （`get(key)` `:226`），**没有 by-sandboxId 查询** ✗。Go 的对应流是
   `openCubePty(ctx, handle RemoteSandboxHandle, opts)` —— **传的是句柄**（自带 id/provider/token）。
   ⇒ **已按此改**：中性层新增不透明引用 `TerminalTypes.RemoteTerminalRef{provider, sandboxId, trafficAccessToken}`
   （照 Go `RemoteSandboxHandle`，token 随引用走 = `RemoteInboundTokenCarrier` 等价面；无凭据后端返回空串），
   `RemoteTerminalManager.openTerminal(RemoteTerminalRef, opts)` 与 `EndpointResolver.resolve(RemoteTerminalRef)`
   与 Go `OpenTerminal(ctx, handle, opts)` 同形 ⇒ resolver 不再需要反查绑定存储 ✓。
   回归：终端包 **16 条**（4 传输 + 7 会话含新增"resolver 收到引用本体" + 5 类型），门 B4 81s ✓。
2. **TTL 刷新缺能力**：Java `SandboxSessionClient` 没有 `SetTimeout`/`SetTimeoutWithContext` 等价方法
   （接口面只有 create/connect/get/list/delete/exec/文件；全 runtime 目录 grep 无 setTimeout ✗），
   而 Go 的刷新正是调它（`cube_terminal.go:80`、`e2b_terminal.go:92`）⇒ 目前 `Endpoint.ttlRefresher`
   只能留 null（管理器容忍 null ✓），"终端保活"记成**待接线**（清单 7 真机跑完再定）。

**其余输入齐备**：域/代理（`EffectiveConfig.cubeSandboxDomain/cubeProxyUrl`、`SandboxIdentity`）、token（§7.2 表）、
数据面地址形态（`49983-{id}.{domain}`）、头集合（`X-Access-Token` + `Basic user:`）、传输层
（`EnvdConnectTransport`）与执行体（`EnvdTerminalSession/Manager`）均已在位。
⇒ 估：resolver 本体 ~150 行 + 桩测 ~100 行（含接缝签名调整）；接通装配点另算小改。
