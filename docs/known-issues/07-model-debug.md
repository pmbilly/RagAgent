# 阶段 7（models/{id}/debug 模型调试端点）细节与坑

> 2026-09-22 收官。端点：`POST /api/v1/models/{id}/debug`（前端「模型测试」按钮）。
> 实现：`model/controller/ModelDebugController.java` + `model/service/ModelRuntimeFactory.java`
> + `model/dto/ModelDebug{Chat,Asr}Response.java` + `retrieval/vlm/VlmHttpTransport.java`
> + `common/web/GoFloat{,Array}Serializer.java`。
> 验收：24 场景 golden（Go 实录，`server/src/test/resources/contracts/md-*.json`）
> + `ModelDebugContractTest` 24 用例全绿 + 双端 stub A/B 两轮 24/24 逐字节 MATCH
> （掩码仅 elapsed_ms）+ HTTP 状态码 24/24 一致。

## 新确认的细节与坑

- **并发包装器曾截断 done 之后的终态事件（真缺陷，已修）**：
  `ConcurrencyChatClient.chatStream` 原实现在转发首个 `done=true` 元素后立即 return，
  而 Go `concurrency_wrapper.go` 是 `for resp := range ch`——channel 关闭前**全量转发**，
  包括 RemoteAPIChat 在 `[DONE]`/EOF 时补发的「带 usage 的终态 answer」。Java 队列无
  close 语义，修复 = done 之后进 `forwardTail`：短窗口（2s）poll 模拟 close，尾巴事件用
  短超时（500ms）offer 转发——仍在读的消费者（模型调试等 range 语义）照单全收；
  已在首个 done 收束的消费者（SSE/agent 管线）最多占 500ms 信号量即释放，不会触发
  120s 放弃阈值。**教训：range-over-channel 的翻译终点不是首个 done，是 channel close。**
- **流终态事件的 finish_reason 分路径携带（Go 的真实分叉，已复刻）**：
  Go 的 SDK 路径终态事件带 `state.lastFinishReason`，裸 HTTP 路径不带
  （remote_api.go processRawHTTPStream 的收尾 send）。`useRawHTTP =
  thinking.Apply 注入了字段 || adapter.ForceRawHTTP() || endpoint != "" ||
  prompt cache 重写了 body`。Java 侧传输层虽单路径，但为保住这一可观察差异：
  `ThinkingStrategy.apply` 恢复 boolean 返回（= 是否注入，对照 Go useRawHTTP 返回值），
  `RemoteApiChat.Outbound` 新增 `rawPath` 标记，`terminalResponse` 仅在
  `!rawPath` 时挂 finish_reason。golden 实测：`md-chat-ok`（SDK 等价路径）终态带
  finish_reason，`md-chat-options`/`md-chat-thinking`（thinking 注入 → 裸 HTTP 等价）
  不带——不修就是 2 条 DIFF。
- **embedding/rerank 与 chat/vlm/asr 的状态闸门不对称（照抄 Go）**：
  Go DebugModel 里 embedding/rerank 走 `GetModelByID`（downloading 状态 →
  "model is currently downloading"，HTTP 500），chat/vlm/asr 走 `repo.GetByID`
  直取（无闸门）。ModelRuntimeFactory 按同一不对称实现，勿「顺手统一」。
- **options 解析要复刻 Go UnmarshalTypeError 文案**：`{"max_tokens":"abc"}` →
  "json: cannot unmarshal string into Go struct field ModelDebugOptions.max_tokens
  of type int"（number 带原始字面量）。Java 用 JsonNode 手工解析拼文案，不要用
  Jackson 绑定的默认报错。
- **request preview 的键序**：gin.H 是 map——encoding/json 按键名排序输出 →
  preview/observations 用 TreeMap；options 子 map 是 struct → 字段声明序
  （system_prompt/temperature/top_p/max_tokens/thinking）用 LinkedHashMap 保序；
  double 值包 `RawValue(GoDoubleSerializer.format(v))`。
- **input 64KB 上限按 UTF-8 字节**（Go `len(s)` 是字节数），不是 codePoint。
- **file 尺寸上限**走 `LocalStorageService.maxFileSizeBytes()/maxFileSizeMb()`
  （env MAX_FILE_SIZE_MB 缺省 50），文案照 Go。
- **录制坑**：curl `-F "field=<<...>>"` 会把 `<` 当文件读（exit 26）——文本字段一律
  `--form-string`，文件内容字段用 `-F "field=<path"`。
- **契约测试的 in-JVM stub**：`com.sun.net.httpserver.HttpServer`（必须 setExecutor，
  否则默认串行 executor 会卡流式）；SSE 流式用 `sendResponseHeaders(200, 0)`（chunked）。
  stub 只需逻辑等价（Java 侧是 Jackson 解析），不必逐字节复刻 python stub 的 SSE 帧。

## 已知差异 / 未接线（备案）

- **ollama / weknoracloud 界面的 VLM**：Java 侧给诚实的 XDEP 错误文案（Go 会真调
  本地/云端 VLM）——provider-XDEP 族新成员，真实部署时补齐。
- **preview 的 custom_header_names**：Go 从 map 迭代收集（顺序随机），Java 排序输出
  （确定性）；golden 未覆盖带头模型，暂不影响对拍。
- **VLM 的 customHeaders 未透传**（Java VlmClient.VlmConfig 无该字段）。
- **ASR segments 的 start/end**：Go float64 marshal 语义（`0.0` → `0`），Java 侧
  GoDoubleSerializer 已对齐；embedding 向量是 float32 语义，用 GoFloatSerializer
  （Float.toString 最短往返），两者别混用。

## 环境相关的既有失败（与本阶段无关，验收时须知）

- **B2 `DataSourceJsonTest`**：要求环境里**没有** `SYSTEM_AES_KEY`（有 key 时凭据
  加密落库，断言落空）。`scripts/acceptance.sh` 只设 PATH 不设 key——**手动跑批不要
  source `scripts/dev-env.sh`**（它会从 .env 带出 SYSTEM_AES_KEY）。
- **B4 `TenantSkillPythonVerifierTest.skillPythonVerifierCaseTable`**：在本机确定性
  失败（2026-09-22 实测，干净树上同样失败——pip/venv 环境行为变化，与代码改动无关）。
  待单独排查 verifier 的 pip 调用口径。
