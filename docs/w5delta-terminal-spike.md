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

## 五、成本与建议

- **传输层**：本批已给出（`EnvdConnectTransport` + 4 条桩测试）；真机清单 1–7 只需**一次带凭据的会话**即可收口。
- **执行体本体**（仍未做，估 **~1.3k 行**不变，但性质已明确）：会话生命周期（PID/attach/TTL 刷新/幂等 Close、
  "流结束但无 exit 事件"的兜底）、PTY 事件语义（数据/退出/错误三态）、provider 控制面接线
  （Cube/E2B 的建沙箱 + token 头 + `OutboundUrlGuard`）、`TerminalBridge` 的 WS 桥接（中性层已就位）。
- **建议**：先按清单 1–4 做一次真机 spike（半小时级），再开执行体批；若第 1 项不符（E2B 只吃 protobuf），
  执行体批追加"最小 protobuf 编解码"子项。
