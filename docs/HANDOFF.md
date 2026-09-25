# 交接文档（新会话接手用）

## 0.-40 ①b：知识下载/预览面流式化（2026-09-25——W5γ5.4）

**做了什么**（对照 Go：`handler/knowledge.go:1477/1533` → `filetransport.Serve`；改前本仓是"读满 byte[] 再 `ResponseEntity<byte[]>`"）：

| 件 | 说明 |
|---|---|
| `KnowledgeService.openKnowledgeFile`（替换 `getKnowledgeFile`） | 返回 `KnowledgeFileStream(filename, opened, manual)`——**不读内容**。manual 知识在内存（照 Go 的 `NopCloser(bytes.Reader)`：**非 seekable** → `none` + 显式 CL）；真实文件走存储层打开 |
| `TenantFileStorage.open(tenantId, filePath)` | 本地引用 → 可 seek；provider 引用 → 复用 {@code ProviderFileContentService}（**同一套能力分流**，不重复实现）；错误折叠成与 `readChecked` 同一个 `Failed to retrieve file` 信封（kg-404/traversal golden 锁的就是它） |
| `LocalStorageService.openChecked` | 本地流式打开（`Files.size` 先取长度 → 缺失即信封 404，与 Go 的 `os.Open` 同口径；目录照 Go 允许"打开成功、读时才炸"） |
| `KnowledgeController` 两端点 | 改为 `void` + `HttpServletRequest/Response` + `FileTransport.serve`；`Content-Description`/`Content-Transfer-Encoding`/`Expires` 照旧在 serve 前设；disposition 仍用既有 `contentDisposition(...)` 助手（golden 逐字不动）|

**顺带获得的能力**：知识下载/预览**支持 Range**（改前没有——`serveFile` 只输出两种无 Range 形态）。

**验证**：`KnowledgeOperationsContractTest` **15/15 全绿**——`kg-download*` / `kg-preview*`（含 manual 的 `none`+CL 与真文件的 `bytes`+CL）**golden 零重录**；新增**断言型**用例 `downloadSupportsRange`：`Range: bytes=0-5` → 206 + `Accept-Ranges: bytes` + `Content-Range: bytes 0-5/34` + 6 字节体（非 golden：Go 侧 kg-* 实录未录 Range，升级成 golden 可照 `record-w5c-golden.sh` 补录）；受影响批 **B3 全绿（58s）**。

**未做（留档）**：知识下载的**云引用**端到端 A/B（`file_path = minio://…` 的知识行）——它复用 `/files` 已 A/B 验证过的同一 `ProviderFileContentService`，但要造 rows；做法：`psql` 把某知识行 `file_path` 临时改为 `minio://weknora-ab/10002/exports/stream-small.bin`（先备份原值）+ 双端起 minio env + `curl /api/v1/knowledge/<id>/download` 对拍 + 还原。

**存储 ①③ 至此全部收官**：①a（流式化）/①a2（凭据解密缺陷）/①a3（minio seekable）/①b（知识面）＋ ③B（守卫去重）。

---


## 0.-39 ①a3：minio seekable 补适配（2026-09-25——W5γ5.3，A/B 全 PASS）

**做了什么**（让 Java 与 Go 一样，对 **minio** 走 `http.ServeContent`：`Accept-Ranges: bytes` + Range/206）：

| 件 | 说明 |
|---|---|
| `SeekableSource` / `SeekableFileService`（provider 包新增） | 前者=可随机读字节源（`size()` + `open(offset)`，对照 Go 的 `io.ReadSeeker`）；后者=provider 能力接口（`seekableReads()` 谓词 + `openSeekable()`） |
| `FileTransport` 泛化 | `OpenedFile.seekable` 由 `Path` 泛化为 `SeekableSource`（本地 = `PathSeekableSource`，`open(offset)` 用 `skipNBytes` 定位）；`serveContent`/multipart/`readAllBytes` 全部走源——**本地行为不变**（w5c/w5f/kg 的 Range/206/416 golden 未重录） |
| `S3CompatibleFileService` | 实现 seekable 读：`size` = `HeadObject`、`open(offset)` = 带 `Range: bytes=<off>-` 的 `GetObject`（**不缓冲整对象**）；`seekableReads()` 门控为 **`minio://` 独有** |
| `ProviderFileContentService` | 按能力分流：seekable provider → `ofSeekableSource(source, source.size())`（HEAD 失败即 IOException → 404）；否则流式 |

**为什么只有 minio**（别"顺手统一"）：minio-go 的 `*minio.Object` 实现 `io.ReadSeeker`（用 Range 请求实现 Seek）→ Go 走 ServeContent；aws-sdk 族（s3/cos/tos/oss/obs/ks3）的 body 是 `io.ReadCloser` → Go 走流式 + `none`（本仓同）。这是 **SDK 类型差异**。

**验证**：
- `ProviderWiringTest` 第 6 例：seekable 形态下 `Range: bytes=2-5` → 206 + `Content-Range: bytes 2-5/10` + 切片体；且断言**不走** `getFile`（不缓冲）；
- 受影响批 **B4 全绿（81s）**——本地路径的 Range/206/416 golden 全绿 ⇒ 泛化未改本地行为；
- **真 A/B 全 PASS**（`scripts/ab-storage-stream.sh`，MinIO 真云面）：

| 场景 | Go | Java | 头 diff | 体 |
|---|---|---|---|---|
| 全量 GET | 200 / `bytes` / CL 4096 | 200 / `bytes` / CL 4096 | **空** | 一致（4096B） |
| `Range: bytes=0-99` | 206 / `bytes` / `Content-Range: bytes 0-99/4096` / CL 100 | 同左 | **空** | 一致（100B） |

**角落差异（记档）**：对象缺失时 Go 会在 ServeContent 的 `Seek` 处失败 → **500**；本仓在 `HeadObject` 阶段抛 IOException → **404**。同属"非 200"，影响可忽略（本地路径的 404 语义仍由 golden 锁定）。

---


## 0.-38 ①a2：云读凭据解密缺陷（W5γ5.2）+ minio seekable 语义决策（2026-09-25）

**缺陷（真 A/B 抓回的第一个真 bug；影响面不止 minio）**：`/files` 走**实例行**（`storage_backends`）解析 provider 时，
`StorageFileResolver.toStorageEngineConfig` 把 jsonb 里的私钥**原样当明文**回挂——而存储层的私钥是**存储密文**
（`enc:v1:…`，见 `StorageBackendService.serializeConfig`/`configOf`）。于是云 provider 的读全部 403，实录：

```
WARN FileProxyService: [Router] /files get file failed: tenant_id=10002 provider=minio
  path="minio://weknora-ab/10002/exports/stream-small.bin"
  err=java.io.IOException: failed to get file from S3: The Access Key Id you provided does not exist in
  our records. (Service: S3, Status Code: 403, ...)
```

同 env 下 Go 200（Go 的实例行读路径本身是解密读）→ 定位为**翻译缺口**而非环境问题。
（实例行来源可查：`storage_backends` 里 `source=env` 的行由 env 物化写入、凭据**加密落库** ✓。）

**修**：`toStorageEngineConfig` 就地解密两族命名（`access_key_id`/`secret_access_key`、`secret_id`/`secret_key`），
语义照存储层（`decryptStoredSecret`：带 `enc:v1:` 前缀才解密、无前缀原样）；加 `toStorageEngineConfig(b, crypto)`
重载供测试注入。**回归**：`ProviderWiringTest` 第 5 例（密文必解 + 明文原样），受影响批 **B4 全绿（78s）**。

**A/B 复验（修后）**：双端同 `minio://weknora-ab/10002/exports/stream-small.bin`——

| 侧 | 状态 | Accept-Ranges | Content-Length | 体 |
|---|---|---|---|---|
| Go | 200 | `bytes` | 4096 | 4096 B |
| Java | 200 | `none` | 4096 | **逐字节一致** |

即：**404 缺陷已消；头只剩一项差异 = seekable 语义**。

**minio seekable 语义（A/B 第二个发现）——决策：按"补适配"做，排 ①a3**：
- 事实：Go 对 MinIO 走 `http.ServeContent`（`Accept-Ranges: bytes` + Range/206），因为 minio-go 的
  `*minio.Object` 实现 `io.ReadSeeker`（用 Range 请求实现 Seek）；aws-sdk 族（s3/cos/tos/oss）的 body 是
  `io.ReadCloser` → Go 走非 seekable 流式（`none`，与本仓一致）。
- 影响：Java 目前对 minio 对象只给 `none`（无 Range）→ **大文件预览/视频拖动不可用**（功能性差异，非纯报文）。
- 方案（①a3）：① `FileTransport` 抽 `SeekableSource`（`size()` + `open(offset)`），本地盘用 Path 实现
  （**ServeContent 端口行为不变，由 w5c/w5f/kg 的 Range/206/416 golden 锁验证**）；② provider 侧加
  `SeekableProvider` 缝（`headObject` 取 size + `getObject(range)` 取段），由 `S3CompatibleFileService` 在 minio
  形态实现；③ `ProviderFileContentService` 对 seekable provider 走 seekable 形态；④ 判据：A/B 双端头逐行一致
  （含 206/Content-Range）+ 体一致。
- 备选（不推荐）：按差异备案——则 minio 面永远无 Range。

**顺带**：A/B 脚本 `scripts/ab-storage-stream.sh` 的默认 key 改带租户前缀（不带 → 双端同为 403
`forbidden: file path not accessible`），本地临时文件与上传目标分离。

---

## 0.-37 存储 ①a 读路径流式化 + ③ local 双实现去重（2026-09-25——W5γ5.1）

**做了什么**（`docs/storage-a3-plan.md` 的执行；**golden 零重录**，验证见下）：

| 件 | 说明 |
|---|---|
| **①a 流式化** | `FileTransport.OpenedFile` 补**第三形态** `ofStream(InputStream, size)`（+ `readAllBytes()` 三形态通吃）；`serve` 非 seekable 支路分流（流 → `transferTo`，bytes → 原样）；`closeReader` 从 no-op 变**真关流**（照 Go 的 `defer reader.Close()`）；`ProviderFileContentService.getFile` 改 `ofStream`——**打开仍即时**（错误即刻暴露 → 404 语义不变），只把"读体"交给 HTTP 层直转（不再整对象入堆） |
| **①a 消费点** | 三处 `opened.bytes()`（`ChatLocalImageResolverWiring`/`ArtifactCollectorWiring`/`SessionAttachmentStagingService`）改 `readAllBytes()`（三形态通吃；Go 侧同样是读全量） |
| **③B 去重** | 新 `StoragePathGuard`（`stripKnownScheme`/`cleanPath`/`safePathUnderBase`，照 Go 的 `filepath.Clean` + `SafePathUnderBase`）；`LocalFileContentService` 与 `knowledge.LocalStorageService` 各自**委托**，保留引用形态/布局/错误通道（IOException→404 vs BizException 信封）——**不合并两支**（Go 侧无对应物） |

**验证**：`StoragePathGuardTest` 4（守卫矩阵 + **两支等价性**：同输入拒绝集合一致）+ `ProviderWiringTest` 4（**流式响应形态**：`Accept-Ranges: none`、`Content-Length` 只认 `Options.size`、写完关流、HEAD 也关流）+ `FileTransportTest` 10 全绿；**受影响批 B3+B4 全绿（133s，`--changed` 门）→ 128 个存储 golden（w5c/w5f/kg/att）零重录** ✓（与方案预测一致）。

**真 A/B（新脚本 `scripts/ab-storage-stream.sh`，MinIO 真云面）：跑通但未 PASS——抓回两条发现**

1. **Go 对 MinIO 走的是 ServeContent（`Accept-Ranges: bytes` + Range）**：minio-go 的 `*minio.Object` 实现 `io.ReadSeeker`（用 Range 请求实现 Seek）——即"云对象 = 非 seekable"这个假设**只对 aws-sdk 族成立**（s3/cos/tos/oss 的 body 是 `io.ReadCloser`）。Java 的流形态给 `none` → minio 面两侧头不同（**既有差异的精确化**：Java 改前也是 `none`，①a 未回归）。待决：补 seekable 适配（流 + Range 重发）或按差异备案。
2. **Java 侧同一 minio 路径 404（Go 200）**：租户校验已过（先 403 → 带租户前缀后 404），provider 读取失败 → 疑为 Java 的 minio 路径解析/env 回退面。归 ①a2。
   （环境事实：本机 9000 是 **rustfs**、18080-18082 是 **rocketmq** → A/B 用 9100/19080/19082；MinIO 走 `brew install minio minio-mc`——docker 镜像站对该镜像 403、dl.min.io 的 darwin 构建已 410。）

**顺带修**：`scripts/go-server-up.sh` 在"本仓有 `.env`"时 `WEKNORA_ROOT` 未设 → `set -u` 下 unbound（`--with-ab` 会因此起不来）；已加兜底。

**下一步**：**①a2**（minio 面：seekable 语义决策 + Java minio 404 排查）→ **①b**（知识下载面流式化，有动 `kg-*` 头形态的风险）。

---

## 0.-36 MCP initialize 契约对齐（2026-09-25——W5γ4.21，⑱ 决策项落地）

**做了什么**（§0.-34 三项决策简报之 ⑱；"差异点无文档描述"其实**不需要 Owner 实录**——两侧代码 + golden 实录就能定位）：

| # | 差异（修复前） | 修法 |
|---|---|---|
| ① | `protocolVersion` = `2024-11-05`（Go 是 mcp-go v0.52.0 的 `LATEST_PROTOCOL_VERSION` = `2025-11-25`——**依赖派生值**，旧值是更早 SDK 时代的字面量） | `McpProtocol.PROTOCOL_VERSION` → `2025-11-25`，注释钉住"照哪个 SDK 版本" |
| ② | params 键序 `protocolVersion→capabilities→clientInfo`（mcp-go 是 `protocolVersion→clientInfo→capabilities`，client.go:206-213） | 按键序构造 `LinkedHashMap` |
| ③ | 缺 `ValidProtocolVersions` 白名单校验（应答版本随意，空版本也放过） | 加白名单 + `isSupportedProtocolVersion()`；校验在**设版本头/发 initialized 通知之前**（照 SDK 顺序），失败不置 initialized；文案 `failed to initialize: unsupported protocol version: "xxx"`，异常 `code==null`（Go 侧无哨兵） |

**有意保留的次要差异**（记档）：Java 解析了 `capabilities`（tools/resources/prompts 三键，宽进），而 Go 仓储侧 `InitializeResult.Capabilities` **从不被填充**（client.go:436-445 只拷三件）——Java 无消费者，保留"信息更全"；`OAuthHttp` 的 `MCP-Protocol-Version: 2025-03-26` 是 mcp-go oauth.go:537/732 的硬编码，**非缺陷**。

**验证**：`McpClientProtocolTest` 握手用例新增**出站报文整串逐字节断言** + 新增白名单拒绝用例（文案/`code==null`/不发通知/后续 `ErrNotConnected`）；`McpStubABTest` 的 initialize **升格为整串逐字节比对**（⑱ 差异消除，单跑复确认）；`McpServerStub` 加请求体录制。**用新的 `--changed` 门跑受影响批 B1a + B3：全绿（69s）**。

**教训（可复用）**：协议版本/SDK 版本头/默认参数这类**依赖派生常量**，注释必须钉"照哪个依赖版本"，并在 Go 侧依赖升级时纳入对账（否则静默漂移）。

**下一步**：决策简报只剩 **W5δ provider 终端执行体**（需真实 provider + zerodin stdin 半关闭的 spike）与**存储 ①③**（读路径黄金面专项批）；备案小账剩 Weaviate gse 跨仓提案、E2E 两观察项、jieba 真实分词。

---

## 0.-35 OSS 大文件分片上传（2026-09-25——W5γ4.20，存储三条备案之②）

**做了什么**（照 Go `file/oss.go` 的 `SaveFile`）：`>10MB` 走**分片上传**——`initiateMultipartUpload`（带 ContentType 元数据）→ `uploadPart` ×N（**10MB/片、3 并发**、单遍读流、按 partNumber 保序）→ `completeMultipartUpload`（有序 ETag）；任一步失败 **best-effort `abortMultipartUpload`** 后抛错（照 Go SDK Uploader 收尾）。小文件仍走单次 `putObject`。**错误前缀照 Go 的两个分支**：分片 `failed to upload file to OSS (multipart): …`、单次 `failed to upload file to OSS: …`。

- 常量照 Go 原文：`MULTIPART_THRESHOLD = 10*1024*1024`、`PART_SIZE = 10*1024*1024`、`PARALLEL_NUM = 3`（片大小/阈值可由包内构造器注入，供测试用小值断言片序）。
- Java SDK v1 的 `uploadFile` 只收**本地路径**，故走**低层分片 API**（initiate/uploadPart/complete/abort）自持——与 Go 的 Uploader 行为对齐（并发度、片大小、abort 收尾）。

**验证**：`OssMultipartUploadTest` 5 条（Mockito 桩 `OSS`，不触网）：Go 常量钉住 / 小文件单次 putObject（不走分片）/ 大文件三片（partNumber 1..3、片大小 64/64/22、ETag 有序、`maxInFlight ≤ 3`、不 abort）/ uploadPart 失败 → abort + `(multipart)` 前缀 / initiate 失败 → 同前缀且不 abort。存储域回归 + **五批验收 PASS**。

**顺带记录（环境假红）**：本批首次全量跑时 `B1a` 的 `WebToolsRecordingTest.searchWithContentFetchesLeadingPagesViaSharedFetchTool` 偶发失败（断言抓 3 条得 2 条，`a/c` 缺 `b`）——**与本批改动无关**：单跑 3 次连绿、全量重跑绿；属负载敏感型偶发（与既有"并发跑测试撞端口/偶发假红"同族），跑批前尽量降低本机负载。

---

## 0.-34 provider-XDEP 族收口：weknoracloud VLM 落地 + 三项决策简报（2026-09-25——W5γ4.19）

**落地**（照 Go `vlm/weknoracloud.go` 188 行）：`VlmClient.predictWeKnoraCloud`——`POST /api/v1/chat/completions`（multipart：text + 各图 data URI、`max_tokens=5000`、`temperature=0.1` 用常量、`stream=false`；`extra.remote_model_name` 覆盖模型名；取 `choices[0].message.content`）。

- **鉴权**复用既有 `embedding.WeknoraCloudSign`（chat/embedding/rerank 同一份），六头签名照 Go；
- `VlmClient.Transport` 新增 `postWithHeaders`（**缺省抛**以保函数式接口）；`VlmHttpTransport` 实现（不带 Authorization；非 200 抛 `HttpStatusException` → 文案 `weknoracloud VLM: status %d: %s`）；
- `VlmConfig` 补 `appId/appSecret`（此前 `configFromModel` 收了这两个参数却**丢弃**）；新增 `WeKnoraCloudService.resolveCredentials()`（照 Go `resolveWeKnoraCloudCredentials`）；`ModelDebugController` 注入并解析（**凭证检查在基址校验之前**，照 `NewWeKnoraCloudVLM` 顺序）。
- **至此 VLM 三个界面（openai / ollama / weknoracloud）全部落地**，provider-XDEP 族只剩终端与执行体。

**验证**：`VlmWeKnoraCloudTest` 5 条（形状 + **签名独立重算** + 覆盖/空图 + 凭证文案 + 非 200/无 choices + 分派）全绿——测试用**真实 `VlmHttpTransport`**（临时换放行 loopback 的 guard，照 `ConnectorHttpTest`）；模型域回归绿；五批验收 PASS。

**三项决策简报（均需 Owner 输入，详见 `known-issues/06` 的 W5γ4.19 段）**：

| 项 | 阻塞点 | 建议 |
|---|---|---|
| W5δ provider 终端执行体 | 需真 provider/沙箱；且要先定 zerodep stdin 无半关闭对双向流的影响 | 先做**传输层 spike 评估**（真实 cube/e2b 会话量化 EOF 不可表达的后果），有结论再谈 ~1.3k 行执行体 |
| ~~⑱ MCP initialize 契约对齐~~ | ✅ **已落地（W5γ4.21，§0.-36）**：差异从两侧代码 + `GoRecording45C` 实录直接定位并修正 | — |
| 存储三条备案（`known-issues/08`） | ② OSS 分片 ✅ W5γ4.20；①③ **方案稿已出**（[`docs/storage-a3-plan.md`](storage-a3-plan.md)，2026-09-25 两侧逐处对账，含证据与 golden 影响判断） | **①a** provider/HTTP 面流式化（**报文字节不变 → golden 零重录**，可真 A/B：MinIO 双端对拍 + `-Xmx` 内存实证）**＋ ③B** 内部去重（**不合并两支**——Go 侧无对应物，两支职责不重叠）；**①b** 知识下载面另评。**待批准** |

---

## 0.-33 检索批收官报告（2026-09-25——W5γ4.18，**范围全部收官**）

**交付**：[`docs/retrieval-batch-closure.md`](retrieval-batch-closure.md)——检索批的一次性收敛报告，含：

1. **一页结论 + 提交链**（`59a361e` W5γ4.1 → `233707a` W5γ4.16/17，共 16 个提交）；
2. **九店对照总表**：Go 规模（非测试 12,299 行）/ Go 协议 → 本仓自持口径 / 索引与写入模型 /
   关键词面 / 验证方式（stub vs 真服务端 IT）/ 真服务端状态；
3. **逐店差异备案**（42 条，跨店最容易踩的都在这：Milvus 的 CJK 连段切分、Weaviate 的 PUT 清属性、
   Doris 的双制表符、腾讯的 sha256 第 3 态、SQLite 的 INSERT OR IGNORE 与 k-then-filter…）；
4. **有意偏离**：W5γ4.3 修的三处 Go 侧向量缺陷（不复制 bug）；
5. **接线批**（W5γ4.4~γ4.8）与验收面（五批 + B4 已含 retrieval/config 域）；
6. **待真机联调清单**（9 行：各店前置/操作，含"腾讯存量需重导入""SQLite 迁移需脚本"
   "Weaviate 容器加 `ENABLE_TOKENIZER_GSE=true`"）；
7. **复现命令合集**（验收脚本 + Weaviate/Milvus IT 起容器 + SQLite 定向测试）；
8. **遗留**（小账、provider-XDEP 族、postgres 读路径 golden 约束）。

**范围澄清（避免今后误计）**：Go `retriever/` 下其实是 **10 个目录**——第 10 个 **neo4j 不是向量店**，
实现的是 `interfaces.RetrieveGraphRepository`（Labels/Label/AddGraph/DelGraph/SearchNode，236 行），
**已在更早的图管线批落地**（Java `retrieval/graph/Neo4jGraphRepository implements
PipelinePorts.RetrieveGraphRepository`，方法面一一对应，`QaWiring` 装配）——故本批九店即全范围。

---

## 0.-32 备案小账批（2026-09-25——W5γ4.17）

| 项 | 结论 |
|---|---|
| **VLM ollama 界面** | ✅ **落地**（照 Go `vlm/ollama.go`）：`VlmClient.predictOllama`（单条 user 消息 + 图片原始字节 → JSON base64、`stream=false`、`options.temperature=0.1`、取 `message.content`；错误族 `Ollama VLM request: …`）+ `ModelDebugController.debugVlm` 放行 ollama（Go 侧对 ollama 基址不做 SSRF 校验）+ `VlmOllamaTest` 4 条（形状/空图丢弃/服务不可用/分派不走传输层）。**weknoracloud 仍是 XDEP**（云 API，需凭据） |
| **Milvus `shardsNum`** | ✅ 钉测试：`indexCfg.shardsNum>0` 才带键（服务端忽略为已备案差异） |
| **Weaviate `ENABLE_TOKENIZER_GSE`** | 📋 **跨仓提案（未改 Go 仓）**：Go 仓 compose 的 weaviate 缺该 env，而其 schema 用 `tokenization:"gse"` → 1.28.4 默认关时建类 422（Go 侧同样受影响）——建议由 Go 仓持有者补 |
| **E2E 两个观察项**（早错 SSE 不收流 / `list_sandbox_files` 注册时机） | 📋 **待复跑时定位**：文档只有名词、无现象与复现步骤；先按 W5γ4.12 的"E2E 操作要点"复跑抓现象，再做修（**不做猜测式改动**） |
| **腾讯分词接缝 / jieba** | 📋 维持接缝（接真实 jieba 即与 Go 存量稀疏向量互通；独立工作） |

**验证**：`VlmOllamaTest` 4 + Milvus 新增 1 全绿；五批验收 PASS。

---

## 0.-31 SQLite 驱动落地（2026-09-25——W5γ4.16）**★ 九家店全部落地**

**做了什么**（照 Go `repository/retriever/sqlite/` 全包 ~680 行非测试；**介质决策**：Go 的 `createSQLiteEngine(_ types.VectorStore, db *gorm.DB)` **忽略 store 配置、用产品库**（单二进制模式的 SQLite）并建 `lite_embeddings` + `lite_embeddings_fts`(FTS5 contentless) + `vec_embeddings_<dim>`(vec0)；本仓产品库是 PostgreSQL → 改**独立 SQLite 文件**）：

| 件 | 说明 |
|---|---|
| `SqliteRetrieveRepository`（新） | JDBC + **`org.xerial:sqlite-jdbc:3.46.1.3`**（平台 native 随 Maven 分发，仓内零二进制；实测打包版 **FTS5/contentless_delete/bm25 全可用**→关键词面与 Go 同构）。建表照 Go（含"老 FTS 表非 contentless → 重建 + 二元回填"迁移 + 既有维度补建向量表）；写入 **`INSERT OR IGNORE`**（(source_id, source_type) 唯一索引去重——**重复 source 二次写被静默忽略**，与其它店的覆盖写相反）；关键词 `tokenizeCJKBigram`（重叠二元组）+ FTS5 MATCH + `bm25()×-1000000`；向量**平面扫描**（`vec0` → 普通表 `(rowid, embedding BLOB)` + 注册 Java 标量函数 `vec_distance_cosine`，cosine 的 KNN 结果与 vec0 完全相同）；**先取 k 近邻再按过滤收窄**（结果可能少于 TopK——Go 语义）、阈值取回后衰减；三种删除、批量更新、拷贝（含 FTS/向量复制）、move 一条 UPDATE、估算 `len(content)+200` |
| `SqliteCjkBigram`（新） | 照 Go 的 `tokenizeCJKBigram` / `sanitizeFTS5Query`（`"a" OR "b"`）+ 小端 float32 序列化（= `sqlite_vec.SerializeFloat32`） |
| 装配 | **EngineFactory** sqlite 分支（store 的 `connection_config.addr` 当文件路径；免 SSRF 照 Go）+ **RetrievalEngineWiringConfig.envSqlite**（`SQLITE_PATH` 缺省 `./data/weknora-retrieval.sqlite`；另支持系统属性 `weknora.sqlite.path` 供测试/运维） |

**照抄别改的语义点**：① `INSERT OR IGNORE` 去重（内容/向量都不更新）；② 过滤**只有三个 IN**（kb/knowledge/tag，**无排除项**，照 Go）；③ 检索分派特例：**空类型两条都跑并合并**、**未知类型不报错返回空**（其它店是 `invalid retriever type`）；④ contentless FTS5 **不存原文**（`SELECT content` 恒 NULL，别拿它断言）；⑤ 结果 `id` 是 rowid 十进制串。**实测坑**：WAL 下"另一条连接建向量表"在已开事务快照里不可见（`no such table`）→ 写路径必须**同连接建表**（`ensureVecTable(conn, dim)`）。

**验证**：`SqliteRetrieveRepositoryTest` 12（**真实 SQLite 文件**全链）+ `SqliteCjkBigramTest` 4 全绿；工厂/接线测试补齐（sqlite 出 XDEP 名单）；五批验收 PASS。

**下一步**：**九家店（ES v7/v8、OpenSearch、Doris、Qdrant、Weaviate、Milvus、腾讯 VectorDB、SQLite）全部落地**——检索批至此**收官**。后续为：§0.-32 的备案小账（weknoracloud VLM、E2E 两观察项、jieba 接缝）、provider-XDEP 族（W5δ PTY、initialize 契约）、以及建议出一份**检索批收官报告**（九家口径总表 + 差异汇总 + 待真机联调清单）。

---

## 0.-30 腾讯 VectorDB 驱动落地（2026-09-25——W5γ4.15，HTTP 自持 + 客户端 BM25）

**做了什么**（照 Go `repository/retriever/tencentvectordb/` 全包 ~870 行非测试；**协议决策**：Go 的 `tcvectordb.RpcClient` 集合/文档操作走 **gRPC（olama）**、仅 database 走 HTTP；本仓自持 SDK 的 **HTTP 面**并**不引 protobuf**）：

| 件 | 说明 |
|---|---|
| `TencentVectorDbRestClient`（新） | `Authorization: Bearer account=<user>&api_key=<key>`（明文，非 TC3）+ `Sdk-Version: v1.8.4`；静态路径 + 库名/集合名在请求体：`/database/list`(GET)/`create`、`/collection/create|describe|list`、`/document/upsert|search|fullTextSearch|query|delete|update`；信封非 0 → `code: N, message: …`；**https 与空凭据照 SDK 拒绝**；构造期 SSRF |
| `TencentVectorDbBm25`（新） | **客户端 BM25**：murmur3 32 位哈希 + 文档 tf 归一 + 查询 idf 归一（B=0.75/K1=1.2）；语料统计从 COS 下 **85 MB** 的 `bm25_zh_default.json`（389 万词条，doc_count=382835、avg_doc_len=245.61638）并缓存 `/tmp/tencent/vectordatabase/data/`；停用词 1.1 KB 同源；**流式解析进排序长整型数组**（~47 MB，Go 是数百 MB 的 map，查找走二分）；分词走仓库既有接缝 |
| `TencentVectorDbRetrieveRepository`（新） | 集合命名开关（`collectionName` 非空 → 单集合无维度后缀 + 精确匹配）；建集合索引表照 Go（vector HNSW/COSINE/M16/efC200 + sparse_vector inverted/IP + 9 标量 primaryKey/filter）；Upsert（buildIndex=true，稀疏向量随文档写）；删除 `field in ("…")`；**enabled/tag 批量更新走 Update API**（任一集合失败即抛）；向量检索（ef=100、threshold→radius、TopK≤0→10）；关键词检索 = BM25 查询向量 + `fullTextSearch`（单集合失败跳过、**全失败报错**并提示重导入、score 降序截断）；拷贝 offset 500 分页 + **第 3 态 SourceID = sha256 前 16 hex** + 目标 id 改写；move 一发 Update；存储估算（content 计两次，照 Go） |
| 装配 | **EngineFactory** 腾讯分支（照 createTencentVectorDBEngine：addr/username/apiKey 必填）；**envTencentVectorDb**（TENCENT_VECTORDB_ADDR/USERNAME/API_KEY 三者缺一即跳过 + DATABASE 缺省 weknora）；**testTencentVectorDB** 从 TCP 拨号升级为 ListDatabase 探针（两段错误文案照 Go） |

**BM25 对照验证（本批关键证据）**：在 Go 仓用 SDK v1.8.4 实跑取基准（基准程序已删除，Go 仓干净），Java 逐值比对——murmur3（`"hello"→613153351`、`"world"→4220927227`、`"中文"→3676729751`、`""→0`）与文档/查询权重（`"中文检索测试 hello"` → DOC 均 `0.7627807`；QUERY `{0.44923997, 0.10152008, 0.44923997}`；`"第二条 中文 hello world"` → DOC 均 `0.7606547`）**完全一致**。

**差异备案**：① **分词接缝**——Java 默认分词是仓库既有近似（非 jieba），故稀疏向量与 Go 存量数据**不互通**（同集合需同一实现；Go 迁移来的数据需重导入；接缝可替换为真实 jieba）；② Go 走 gRPC/olama，本仓走 HTTP 面（同服务端、语义等价）；③ **无真服务端 IT**（腾讯 VectorDB 是云服务，无本地版）——wire 形状用 stub 钉死、BM25 用 Go 基准逐值对照，真机联调待云凭据。

**验证**：`TencentVectorDbBm25Test` 4 + 仓储/客户端 stub 16 + 工厂/接线 2 **全绿**；五批验收 PASS；bootRun 重启冒烟 200。

**下一步**：检索批只剩 **SQLite**（~680 行，CGO + sqlite-vec 扩展，native 多平台分发需决策，优先级最低）；其余为备案小账（Milvus shardsNum/模板参数、Weaviate gse 开关回填、腾讯分词接缝、VLM 界面、jieba 等）。

---

## 0.-29 Milvus 驱动落地（2026-09-25——W5γ4.14，REST v2 自持）

**做了什么**（照 Go `repository/retriever/milvus/` 全包 ~1,560 行非测试；**协议决策**：起真例逐端点验过 REST v2 全覆盖（含 **BM25 文本检索**）→ 零新依赖自持，不走 SDK）：

| 件 | 说明 |
|---|---|
| `MilvusRestClient`（新） | REST v2 传输（{@code /v2/vectordb/…}）：`collections/{has,create,load,list}` + `entities/{upsert,query,search,delete}`；信封 `{code,data,message}`（code≠0 → 异常）；认证 `Authorization: Bearer <user>:<password>`；`dbName` 走请求体；构造期 SSRF |
| `MilvusRetrieveRepository`（新） | 惰性建集合（schema：VarChar PK + FloatVector + content(enable_analyzer/match) + `SparseFloatVector` 稀疏列 + **BM25 函数** `text_bm25_emb` + 五个 VarChar/Int64/Bool；`indexParams`：HNSW(metric=MILVUS_METRIC_TYPE 缺省 IP, M16, efC128) + content_sparse AUTOINDEX(BM25) + 五个标量 AUTOINDEX）、每次 ensure 都 load；行主键恒新 UUID；"更新"= 查询整行→改字段→Upsert 回写（向量随行回写）；删除 `field in [...]`（照 SDK WithStringIDs）；向量检索 threshold → 范围搜索 **radius**；关键词检索 = BM25 全文（文本进 `data`、`annsField=content_sparse`），单集合失败跳过、score 恒 1.0；CopyIndices 的 offset 分页 + 三态 SourceID（isEnabled 沿用源值）；move 的 drain 循环 + seen 守卫 |
| `MilvusFilter`（新） | 照 filter.go：算子表（eq→`==`/in/not in/between/and/or）、左结合全括号形状、`formatValue`/`escapeDoubleQuotes`；**值内联**（REST 无模板参数——备案） |
| 装配 | **EngineFactory** milvus 分支（照 buildMilvusClientConfig：addr 缺省 `localhost:19530`、username/password/database 非空才设）；**RetrievalEngineWiringConfig.envMilvus**（MILVUS_ADDRESS/USERNAME/PASSWORD/DB_NAME，照 Go）；**VectorStoreConfigService.testMilvus** 从 TCP 拨号升级为 REST 探针（版本仍照 Go 恒空） |

**真服务端（milvusdb/milvus:v2.6.11）实测抓到的差异与语义（全部落文档）**：① 稀疏列拼写是 `SparseFloatVector`（SDK 叫 SparseVector）；② **REST 无模板参数**（`filterParams` 被忽略）→ 值内联，算子/括号照 Go；③ `dbName` 走请求体（头无效）；④ `shardsNum` 被 REST create 忽略（照传保留配置面）；⑤ load 同步（SDK 异步）；⑥ **enabled 更新失败聚合冒泡**（errors.Join）vs **tag 更新失败只 WARN**——两条别统一；⑦ **move 的"失败换重试"是设计**（`move.go` 注释：重复 ID = 更新未可见，报错让调用方重试，避免静默漏搬）；⑧ **中文关键词按 CJK 连段切分**（标准分析器，非 jieba；Go 同款）——查询词形要与文本分段一致；⑨ 默认 Bounded 一致性：写后读有窗口，IT 用轮询等待。

**验证**：`MilvusFilterTest` 9 + `MilvusRetrieveRepositoryTest` 15 + **`MilvusDriverLocalIT` 1（真实 Milvus 2.6.11：建集合→写→向量查→BM25 中文查→tag/enabled 整行回写→拷贝→move→删除）** 全绿；五批验收 PASS。IT 起容器命令见 `known-issues/06` 的 W5γ4.14 段（本机现留有 `WeKnora-milvus-local` 容器在 19530）。

**下一步**：检索批只剩 **腾讯 VectorDB**（~870 行：HTTP API 3.0 + TC3 签名自持）与 **SQLite**（native 扩展分发决策）；以及备案项小账（Milvus 的 `shardsNum`/模板参数差异、Weaviate 的 gse 开关回填、E2E 抓回的两个观察项）。

---

## 0.-28 Weaviate 驱动落地（2026-09-25——W5γ4.13，REST 自持 + 真服务端实测）

**做了什么**（照 Go `repository/retriever/weaviate/` 全包 ~1,170 行非测试；**协议决策**：读 v5 客户端源码确认 GraphQL 检索/列举与批量删除本就是 REST、批量创建也有 REST 回落路径 → 统一 REST 自持，零新依赖）：

| 件 | 说明 |
|---|---|
| `WeaviateGql`（新） | GraphQL 串构造，**逐字节对照客户端 `Build()` 的 Go 实录**（四条实录串钉在 `WeaviateGqlTest`）：`{Get {Cls (where:…, nearVector:{certainty: x vector: […]}, limit: N) {fields}}}`；where 内部单空格连接、operands 逗号无空格、字符串 Go `%q` 引号、Contains* 恒数组、bm25 空 query 省略 query 段 |
| `WeaviateRestClient`（新） | 传输层：`GET /v1/schema[/{cls}]`、`POST /v1/schema`、`POST /v1/objects`（id 在 body，照 Creator）、`POST /v1/batch/objects`（`fields:["ALL"]`，照 v5 的 REST 回落）、`DELETE /v1/batch/objects`（照 BatchDeleter）、`PATCH /v1/objects/{cls}/{id}`（merge，期望 204）、`POST /v1/graphql`、ready/meta 探针；SSRF 构造期校验（Go 是 SSRF HTTP 客户端逐请求） |
| `WeaviateRetrieveRepository`（新） | 惰性建类（命名向量 `embedding` + hnsw/cosine/efConstruction 128/maxConnections 32/ef 64 + vectorizer none + content gse + 四个可过滤 text + is_enabled bool + 可选 replicationConfig/shardingConfig）；对象 ID = chunkID；三种删除走 ContainsAny 批量删除；向量检索解析 certainty、关键词检索 BM25（单集合失败**直接返回错误**，照 Go——与 Qdrant 相反）；CopyIndices 的 offset 分页与命名向量回搬；move 的 seen-set 循环 + merge；存储估算 HNSW M=32（nil 判定同 Go） |
| 装配 | **EngineFactory** weaviate 分支（host 缺省 `weaviate:8080`、scheme 缺省 http、api_key 直取）；**RetrievalEngineWiringConfig.envWeaviate**（WEAVIATE_HOST/GRPC_ADDRESS/SCHEME/AUTH_ENABLED+API_KEY，照 Go）；testWeaviate 探针既有实现已是 ready+meta（无需升级） |

**真服务端实测抓回四处（全部落在文档与修正里）**：① `tokenization:"gse"` 需服务端开关 `ENABLE_TOKENIZER_GSE=true`（1.28.4 默认关；Go 驱动同样受影响——部署侧缺陷，Go 仓 compose 缺这一项）；② **`after`+`where` 被服务端拒绝**（Go 的 CopyIndices 恒失败）+ 命名向量类下 `_additional{vector}` 恒空 → 本仓改 `where+limit+offset` + `vectors{embedding}`；③ **无 merge 的 PUT 会清掉未提供属性与向量**（Go 的两处批量更新是数据丢失缺陷）→ 本仓改 PATCH merge；④ **BM25 的 score 是字符串**（Go 的 float64 断言恒失败 → 关键词分数恒 0.0，用 Go 客户端对真服务端实录实锤）→ 本仓按代码意图修正为 1.0。

**验证**：`WeaviateGqlTest` 8 + `WeaviateRetrieveRepositoryTest` 16 + **`WeaviateDriverLocalIT` 1（真实 Weaviate 1.28.4 全链）** 全绿；五批验收 PASS。IT 起容器命令与复跑方式见 `known-issues/06` 的 W5γ4.13 段（本机现留有 `WeKnora-weaviate-local` 容器在 9035）。

**下一步**：gRPC/SDK 族只剩 Milvus（~1,560 行）与腾讯（~870 行）；SQLite（native 分发决策）；以及备案项小账（早错 SSE 不收流、`list_sandbox_files` 注册时机两个观察）。

---

## 0.-27 install 真实 LLM E2E 通过（2026-09-25——W5γ4.12，抓回并修复四处驱动缺陷）

**做了什么**：把批 D2 的安装管线与技能执行链路在**真 docker + 真 LLM** 上跑通（dev 栈 OrbStack，租户 10009 的真实 deepseek-flash/qwen），**全链验证**：

1. **docker 沙箱配置**（host 留空自动探测）→ 2. **技能上传**（`sha-digest`，SKILL.md + scripts/digest.py）→ 3. **安装**（installer agent 9 轮真实 LLM：读技能/探测环境/写 `.weknora/install-report.json` → verify 门 → 快照镜像 `weknora-skill/weknora-sk-…-g1-…` commit → 指针切换 → `status=ready`，`skill_image.generation=1`）→ 4. **对话执行**（技能自动注入：`skill://sha-digest/SKILL.md` → `shell_exec` 跑技能脚本 → 真实 sha256 与宿主逐字节一致 → `write_sandbox_file` 落 `/workspace/output/e2e-report.txt`）→ 5. **产物排水**（ArtifactCollector → `resource://…` artifact + 消息挂载，`GET /sessions/{id}/artifacts` 可见）。install-events SSE 终态回放 `{"percent":100,"stage":"done","status":"ready"}`。

**抓回并修复四处驱动缺陷**（全部是单测盲区：假对象不传 null、不通真 docker；详见 `known-issues/06` 的 W5γ4.12 段）：

| # | 缺陷 | 修法 |
|---|---|---|
| ① | `DockerHostSupport` 读错 docker context 的 meta.json 形状（Go：顶层 `Name` + `Endpoints` **对象**；Java 读 `Metadata.Name` + `Endpoints` **数组**）→ 自动探测恒空、回落 macOS 上失效的 `/var/run/docker.sock` | 照 Go 重写 + 抽纯函数 + `DockerHostSupportTest` |
| ② | `AgentEngine.execute` 的 `llmContext` 传 null → 入口日志 NPE（Go：`len(nil slice)=0`）→ 安装器第一轮即失败 | 入口按 Go 语义归一（null → 空表）+ `AgentEngineNullContextTest` |
| ③ | 安装器 chat 客户端 `LlmChatClients.create(config, null, null)` → `ConcurrencyChatClient` 解引用 null governor（Go 是进程级全局，安装器与交互路径共用闸门；与 2026-09-23 agent 路径同类漏传**第二次**） | 管线注入并透传 governor/ollama（照 `SessionAgentQaService.chatModel`）+ 两个包装器改 **fail-open**（照 Go `GateNamedN` 的 `l == nil → noop`） |
| ④ | 无活沙箱时 `ListSessionFiles` 返回 null，三处调用方直接 for-each → 首轮 staging NPE（Go 契约：nil 让调用方当空集） | 三处调用方 null → 空集（返回方保持 null，Go 契约 + 既有测试钉住） |

**教训（跨批复发率最高）**：**"Go 的 nil slice"在 Java 没有对等物**——本批 4 处有 2 处属此类；判断归一点的原则是"Go 注释把 nil 当合法入参就在入参归一、当合法返回就在返回契约处让调用方归一"。第二类是**注入面漏传**（governor 这类进程级全局在 Java 变成显式注入后每处新建客户端都要透传）——除补传外，装饰器要有 fail-open 兜底。

**验收**：五批全量 PASS（新增 2 个回归测试：`DockerHostSupportTest`、`AgentEngineNullContextTest`）；真实链路端到端如上。dev 库留存的 E2E 夹具（沙箱配置 `e2e-docker`、agent `e2e-sandbox-agent`、技能 `sha-digest`、快照镜像、10009 的 `builtin-skill-installer` 记录=钉住真实模型）——可留作下次复跑，复跑五要点见 known-issues/06。

---

## 0.-26 Qdrant 驱动落地（2026-09-25——W5γ4.11，gRPC 族首支·REST 自持）

**做了什么**（照 Go `repository/retriever/qdrant/` 全包 ~1,070 行非测试；**协议决策落地**：Go 走 qdrant/go-client 的 gRPC，本仓照 ES/OpenSearch 先例自持 HTTP/JSON）：

| 件 | 说明 |
|---|---|
| `QdrantRetrieveRepository`（新，engine/qdrant） | 实现 `RetrieveEngineRepository` + `KnowledgeIndexMover`。**按维度分集合** `<base>_<dim>`（base=ResolveCollectionName(indexCfg, QDRANT_COLLECTION, "weknora_embeddings")）；**惰性建集合**：GET 探测（404=不存在）→ `PUT /collections/{n}`（size + distance=Cosine + shard/replication 仅在 >0 时带）→ payload 索引六件（keyword：chunk/knowledge/kb/source；bool：is_enabled；text：content + multilingual + lowercase），索引失败只 WARN，结果按维度缓存；**点 ID 恒新 UUID**（Qdrant 不承载业务主键）；payload 字符串过 `CleanInvalidUtf8`（NUL/非法编码单元丢弃） |
| 写入/删除/检索 | **BatchSave** 按维度分组 + 100 分片 + 空向量 WARN 跳过（全空 → "No valid points to save after filtering"）；**Save** 空向量拒收 `empty embedding vector for chunk ID: %s`；**三种删除** 走 `POST …/points/delete`（match.any 过滤，不带 wait——照 Go）；**向量检索** `POST …/points/search`（filter + limit=TopK + score_threshold + with_payload；集合不存在 → 空结果；失败包 `<collection>: <err>`）；**关键词检索** 跨集合 `POST …/points/scroll`（无 token 时 must 塞原 query，有 token 时 should 逐 token 的 `match.text`；跨集合合并截 TopK、score 恒 1.0、单集合失败只 WARN） |
| 批量更新 / move / copy | **批量更新**（enabled 按 true/false 分组、tag 按 tagID 分组）× 集合前缀扇出 → `POST …/points/payload?wait=true`；**move** 同端点（kb_id 改写 + tag 清空 + filter）；**CopyIndices** 每页 64 的 scroll（with_payload + with_vector）→ chunk/knowledge 映射缺失跳过 → 三态 SourceID 改写 → 新 UUID + 向量回搬 → 批量 upsert；`EstimateStorageSize` 照 Go（payload 不含 tag_id；HNSW M=16；**nil 判定**——非 null 空数组也计 256 字节，与 Doris 的 `len>0` 相反） |
| `QdrantRestClient`（新） | 传输层：result 信封解析（`{"result":…}`）、非 2xx → `QdrantHttpException`（status + 报文原文）、`api-key` 头、构造期 SSRF 校验（照 ES/OpenSearch 姿态；Go 是 gRPC dialer 逐连接）、`buildBaseUrl(host, port 缺省 6334, useTls)` |
| 分词 | `tokenizeQuery` 复用 `SearchTextUtil.segmenter()`（jieba 缝；本仓默认降级为二字滑窗）→ trim/小写/单字符丢弃/去重保序，并**补一手按空白二次切分**对齐 gojieba 的拉丁分词净效果（`SearchTextUtil` 因此新增 `segmenter()` 读取口） |
| 装配三处 | **EngineFactory** qdrant 分支（host/port 缺省 6334/api_key/use_tls）；**RetrievalEngineWiringConfig.envQdrant**（QDRANT_HOST 缺省 localhost / QDRANT_PORT 缺省 6334（Atoi 失败保缺省）/ QDRANT_API_KEY / QDRANT_USE_TLS 非 "false"/"0" 即开）；**VectorStoreConfigService.testQdrant** 从 TCP 拨号升级为 REST 健康探针（`GET /` 取 version）——**消掉"Java 无 gRPC 客户端 → 版本恒空"这条旧备案** |

**与 Go 的差异（备案）**：① 传输 gRPC→REST（端点映射与不可等价点见 known-issues/06 第 1 条）；② `wait` 只在 move 的 SetPayload 上带（照 Go 的 `wait := true`），Upsert/Delete/批量 SetPayload 均不带；③ 分词降级（jieba 缝 + 二次空白切分为本仓补丁，净效果对齐）；④ TopK≤0 时 Go 的截断会 panic，本仓 clamp 0；⑤ 无真实 Qdrant 实例验证（stub 面全绿，同 ES/OpenSearch 口径）。

**验证**：`QdrantRetrieveRepositoryTest` 14 条（stub HTTP 逐请求断言：建集合与 6 个索引的形状、集合缓存、Save 空向量/新 UUID、BatchSave 101→2 片 + NUL 清理、三种删除的 match any、向量检索体与响应映射、集合不存在短路、关键词 should(text) + 前缀过滤 + TopK 截断 + 单集合失败容忍、批量更新两态 + 非前缀跳过、move、CopyIndices 向量回搬与三态、存储估算 nil/空数组、分词、payload 清理、探针 version/401）**全绿**；**五批验收 PASS**；bootRun 重启冒烟 200。

**下一步**：gRPC/SDK 族还剩 Weaviate（REST/GraphQL + batch 走 gRPC，需决定 batch 路径）、Milvus（REST v2 覆盖度需探）、腾讯（HTTP API 3.0 + TC3 签名）；SQLite 的 native 分发决策；install 真实 LLM E2E（原计划 1→2→3 的 3）。

---

## 0.-25 Doris 检索引擎落地（2026-09-25——W5γ4.10，SQL 族收官）

**做了什么**（照 Go `repository/retriever/doris/` 全包 7 非测试文件 ~2,040 行，MySQL 协议主链路 + Stream Load HTTP 自持——Go 用 go-sql-driver/mysql + FE HTTP 8030）：

| 件 | 说明 |
|---|---|
| `DorisRetrieveRepository`（新，engine/doris） | 实现 `RetrieveEngineRepository` + `KnowledgeIndexMover`。**表结构按维度分表** `<base>_<dim>`（base=ResolveCollectionName(indexCfg, DORIS_TABLE_PREFIX, "weknora_embeddings")）；**兼容模式**（compat.go 全文）：显式配置 → 既有表 DDL 探测（`SHOW CREATE TABLE` 判 `duplicate key(`/`unique key(`；混用拒收；与显式配置不符按 Go 原文拒）→ 只有 auto 且无既有表才跑函数探针（`inner_product_approximate`/`cosine_distance_approximate` 各试 `SELECT [1.0],[1.0]`，都失败才报错）；结果**含错误只解析一次**（sync.Once 语义 → 双检锁）。legacy=UNIQUE KEY+cosine+MoW，内积副本=DUPLICATE KEY+单位化内积+delete/insert 重写 |
| 写入/删除/检索 | **BatchSave**：按维度分组（TreeMap 升序，Go map 无序→确定性优先）→ 空向量跳过（WARN）/非有限值拒收（`invalid embedding for chunk %s: doris: embedding[i] is not finite: …`）→ id 兜底（info.ID → SourceID → UUID）→ 逐维 `ensureTable`（information_schema 判存 + 惰性建表 + 后台虚拟线程轮询 `SHOW INDEX` 的 idx_emb FINISHED/NORMAL、30s 上限、未就绪只 WARN）→ legacy 直接 INSERT / 内积副本 DELETE+INSERT。**embedding 列一律字面量内联**（`[0.6,0.8]`，按 Go `FormatFloat('g',-1,32)` 形态）；三种 Delete 走 `IN (?,…)`；**向量检索**：查询向量单位化（非 legacy）→ `inner_product_approximate`（legacy 用 `1 - cosine_distance_approximate`）→ `HAVING score >= ?` + `ORDER BY score DESC LIMIT`（TopK 内联，照 Go 不 clamp）；**关键词检索**：跨维表 `content MATCH_ANY ?`、score 恒 1.0、单表失败只 WARN 跳过 |
| `CopyIndices` / 批量更新 / move | **拷贝**：64 行分页扫源表 → chunk/knowledge 映射缺失跳过（WARN）→ 三态 SourceID 改写（普通/生成型问题/新 UUID）→ 新 UUID 主键 + 向量回填写回；**批量更新**：内积副本=读整行→变异→delete+insert（含 embedding 原样回写），legacy=Stream Load partial update（只读 `id,chunk_id` 定位）；**move**：内积副本直接拒（`reuse_vectors move is not supported by Doris ANN tables; use reparse mode`）、legacy 走 `UPDATE … SET knowledge_base_id=?, tag_id=''`；`EstimateStorageSize`=payload UTF-8 字节+`dim*4`+HNSW 512+24 |
| `DorisSqlExecutor` + `JdbcDorisSqlExecutor`（新） | **SQL 面做成可测缝**（本仓首例）：execute/query/scalar + Row 视图；生产实现 = Hikari 池（max 20 / idle 5 / lifetime 1h，照 Go 三参数）+ MySQL 协议 JDBC（`initializationFailTimeout=-1` 保 Go 的惰性建连；`characterEncoding=UTF-8&sslMode=DISABLED&allowPublicKeyRetrieval=true`；`parseTime/loc` 无等价设置——不读时间列） |
| `DorisStreamLoadClient`（新） | 照 streamload.go：`PUT <feHTTP>/api/<db>/<table>/_stream_load`，头 `Authorization/Content-Type/format/strip_outer_array/partial_columns/columns/merge_type=APPEND`，体=JSON 数组（Go map 字母序 → 显式 TreeMap）；**1 MiB 自动拆批**；`Status ∈ {Success, Publish Timeout}` 视为成功；307/308 手写跟随（`followRedirects(NEVER)` + 凭据只发同主机或 SSRF 白名单目标，跨主机拒转）；每次请求过 SSRF 校验 |
| 装配三处 | **EngineFactory** 新增 doris 分支（照 createDorisEngine：addr 必填 `doris connection requires addr (host:port)`、database 必填、http_port 缺省 8030、httpBase=addr 的 host+该端口）；**RetrievalEngineWiringConfig.envDoris**（DORIS_ADDR 缺省 `doris-fe:9030`/DORIS_DATABASE 缺省 `weknora`/DORIS_USERNAME 缺省 `root`/DORIS_PASSWORD/DORIS_HTTP_PORT 缺省 8030）；**VectorStoreConfigService.testDoris** 从 TCP 拨号升级为驱动探针（MySQL 协议连接 + `SELECT @@version` 剥 `Doris-` 前缀，版本查询失败只 WARN 返回 ""）；新依赖 `com.mysql:mysql-connector-j`（BOM 管版本） |

**与 Go 的差异（备案）**：① **SSRF 姿态**：Go 注册全局 MySQL dialer 在每次连接建立时校验；Java 在构造期校验一次（同 ES/OpenSearch 驱动的 Java 侧姿态）；② JDK HttpClient 把 `Expect: 100-continue` 列为禁设头（Go 会发；Doris 不依赖，仅提前拒收优化）；③ 重定向只跟随 307/308（Go 会跟随 301/302/303 并改写 GET——对 Stream Load 无观测面）；④ 浮点字面量按 Go `'g'` 形态输出（`1`/`0.0001`/`1e+07`），数值与 Java 最短往返表示一致；⑤ `keywordsRetrieve` 的 `all[:TopK]` 在 TopK≤0 时 Go 会 panic（负下标），本仓 clamp 到 0（防御性偏离，正数 TopK 语义不变）；⑥ 兼容模式探测/建表的 SQL 文本与 Go 逐字对齐（含 DDL 的双制表符形状，见 known-issues/06）。

**验证**：`DorisPureFunctionsTest` 12 条（兼容模式/字面量/解析/校验/单位化/SourceID/DDL 形状/估算/拆批）+ `DorisRetrieveRepositoryTest` 31 条（假执行器钉 SQL 与参数序：分组/替换语义/判存缓存/探测三步序/既有表拒收/三种删除/向量与关键词检索/拷贝三态/批量更新两模式/move/估算/驱动可用性）+ `DorisStreamLoadTest` 14 条（stub FE：wire 头与体/成功与失败状态/非 2xx/拆批/同主机 307 跟随/跨主机拒转/无行短路）**全绿**；**五批验收 PASS**——并且把历史遗漏的 `com.ragagent.retrieval.*`/`com.ragagent.config.*` 两域补进 B4（此前"五批全量"不含检索引擎域，见 known-issues/06 第 9 条）；bootRun 重启冒烟 200（system/info + vector-stores + sandbox-configs）。**未做**：真 Doris 端到端（无本地 Doris 实例，legacy 的 partial update 需实机验证）——与 ES/OpenSearch 批同口径（stub 面全绿、实机留待部署）。

**下一步**：Doris 落地后**SQL 族收官**（检索批剩 gRPC/SDK 族 Weaviate/Qdrant/Milvus/腾讯——需协议决策；SQLite——native 扩展分发决策）。原计划的 1→2→3 里已完成 1（Doris）；2（Qdrant）与 3（install 真实 LLM E2E）待续。

---

## 0.-24 OpenSearch k-NN 驱动落地（2026-09-25——W5γ4.9，HTTP 族收官）

**做了什么**（照 Go `repository/retriever/opensearch/` 全包 15 文件 ~2380 行，HTTP/JSON 自持——Go 用 opensearch-go v4 SDK，wire 形状逐段对照）：

| 件 | 说明 |
|---|---|
| `opensearch.OpenSearchRetrieveRepository`（新） | 实现 `RetrieveEngineRepository` + `KnowledgeIndexMover`。**生命周期**（repository.go）：构造期探针（版本分段拒：非 opensearch/1.x/2.0~2.3；2.4~2.10 WARN 收；2.11+/3.x 收 + 每节点 k-NN 插件检查，缺节点列表照 Go `%v` 形态）**不建索引**——逐维惰性建（ensureReady：dim ∈ (0,16000]，永久错误持久化/瞬时错误重置重试——**照 Go 代码**：瞬时分支连当次调用也不报错，注释与代码的分叉见 known-issues）；索引命名 base=ResolveIndexName(OPENSEARCH_INDEX,"weknora") + DB-store 折叠 storeID 前 12 hex（env-store 前缀 id 折叠为 ""、≥16 规则）+ sanitizeIndexName 正则；别名 `<base>_<dim>`→`<alias>_v1`，keyword 专用索引 `<base>_keywords`（mutex+flag 可重试）；already-exists → 结构指纹比对（漂移 → CONFIG_INVALID "manual reindex required"）；aliasPut 失败尽力删孤儿 |
| 检索/写入/删除/迁移/批量更新 | **query.go**：knn 查询（embedding.vector/k/filter 内嵌 bool.must + min_score 直通——COSINESIMIL.scoreTranslation 已映射 [0,1]）、BM25 match + 类型化过滤（无 JSON 注入面）、is_enabled=true 隐含子句、TopK ≤0→WARN+10 / cap 10000；**crud.go**：Save 幂等（_id=chunk_id）、缺 embedding 路由 keywords 索引、BatchSave 的 10MB 预估/1000 文档上限 + 混合维度拒 + 逐项错误检视（≤5 条 "[op id] type"，reason 只进 DEBUG）、三种删除走 _delete_by_query terms+refresh、cap 1000；**copy.go**：批 500 分页扫源（from/size 受 max_result_window 界，Go 同缺）+ 三态 SourceID 改写 + **向量按目标 SourceID 回填**（不同于 ES 的 chunk id 约定）+ 逐页 BatchSave；**move.go**：跨维 `<base>_*` update_by_query 改写 knowledge_base_id 清 tag_id（painless + params 绑定防注入），完整性校验（timed_out/version_conflicts/total==updated）；**bulk_update.go**：enabled 按值分组（false 先 true 后）/tag 字典序、组内 id 排序（确定性）；**stubs.go**：EstimateStorageSize 保守下界 n*(1024+4*768+128)（真实现读 _stats 未落地，Go 同——删除守卫 fail-closed） |
| `opensearch.OpenSearchDriverException`（新） | 九哨兵分类（INDEX_NOT_FOUND/DIMENSION_MISMATCH/AUTH/TRANSPORT/VERSION_UNSUPPORTED/CONFIG_INVALID/BATCH_TOO_LARGE/CIRCUIT_BREAKER/FEATURE_NOT_ENABLED）+ isTransient（TRANSPORT/CIRCUIT_BREAKER）+ isNotFound/isAlreadyExists（404 / 400+resource_already_exists_exception）；集群 reason 不进异常 message（只进 DEBUG，脱敏纪律照 wrapTransport） |
| 装配两处拆 XDEP/WARN | **EngineFactory.createFromStore** 新增带 audit sink 的重载（照 Go createOpenSearchEngine 的 WithAuditSink；其它引擎忽略——Go 同），opensearch 分支真落地（env-store id 折叠 + 驱动构造）；**RetrievalEngineWiringConfig** env-path：OPENSEARCH_ADDR/USERNAME/PASSWORD/OPENSEARCH_INSECURE_SKIP_VERIFY（equalFold "true"）分段报错（client/repo/Register 互不掩盖），**与 ES env-path 不同：OpenSearch 客户端构造无条件过 SSRF**（Go 的 NewOpenSearchClient 内置）→ 传 guard；DB-store 工厂 lambda 换成带 sink 的 createFromStore |
| `OpenSearchAuditSinkAdapter`（新，config） | 照 container/audit_sink.go：`AuditAction.OPENSEARCH_INDEX_CREATED/REINDEX_EXECUTED`（常量已预置）、target_type="opensearch_index"、details 只装 alias/dim/src_dst/docs、**无租户上下文 WARN+跳过**（注册期自跳过，Go 同） |
| test-connection 升级 | `VectorStoreConfigService.testOpenSearch` 从"根端点 200"升级为驱动的完整探针（照 vectorstore_healthcheck.go testOpenSearchConnection→TestConnection：版本 + 每节点插件），失败折叠成原有通用文案 |

**与 Go 的差异（备案）**：①SDK→自持 HTTP：Go 的 TLS 加固（min 1.2、前向保密套件、池 32/90s）由 Java HttpClient 缺省 + insecureSkipVerify 的 trust-all SSLContext 承担（Go 的 InsecureSkipVerify 含主机名校验跳过，Java 侧 trust-all 不跳主机名校验——自签集群若 CN 不匹配需 JVM 系统属性，备案）；Go 的 ResponseHeaderTimeout=30s 与 SSRFValidatingRoundTripper（逐请求重校验）无 Java 等价（构造期一次校验，ES 驱动同姿态）；②map 序列化一律字母序（TreeMap = Go json.Marshal 对 map 的语义）；③分组遍历序排序（Go map 随机）；④审计 sink 适配器在无租户上下文时同样自跳过。

**验证**：stub HTTP 13 条全绿（探针拒/收、惰性建索引 mapping 形状 + 别名动作 + 短路不 emit、save/batchSave wire 形状 + 上限 + 混合维度、knn/keyword 查询体 + min_score 直通 + 隐含子句、删除/移动/批量更新体 + 完整性校验、copy 三态改写 + 向量回填、估算 + 纯函数）。**测试抓回驱动两处真缺陷并已修**：move 的 filter 元素缺 `{"term":…}` 包装、`knowledge_id` 误装进第一个 term map（go 侧无此问题——纯翻译手误）；另照 Go 修正 move/updateByQueryScript 的顶层 `params` 键（Go 只在 script 内）。

**下一步**：HTTP 族（ES v7/v8 + OpenSearch）至此收官。检索批剩 gRPC 族（Weaviate/Qdrant/Milvus/腾讯——需协议决策）、SQLite（C 绑定）/Doris（MySQL 协议）；之外为 provider-XDEP 族与 Owner 决策遗留。

## 0.-23 检索批 follow-up 清零：知识管家清扫 + move 的 reparse 模式（2026-09-25——W5γ4.8）

**做了什么**（§0.-21 立项 follow-up 的 ② 与 ③ 的剩余项）：

| 件 | 说明 |
|---|---|
| `HousekeepingService`（新，照 `service/knowledge_housekeeping.go` 399 行） | **清扫 A**：`parse_status ∈ {pending, processing, finalizing}` 且行 `updated_at` 早于阈值 → 判死。**两级判定**：先按 `knowledge_processing_spans` 的最新心跳过滤（有新鲜 span 的行保留——长阶段期间父行会"冻结"），span 查询失败按"全都没有心跳"**失败安全**（宁可多回收）；**第二道闸** `task_pending_ops` 持久闸（wiki ingest 按知识 id 去重）命中即保留、探测失败则**推迟本轮全部候选**（分不清积压与孤儿时多等一个周期），瞬时队列 inspector 为 `null`（Lite 无 asynq）时只关这一项、其探测失败按仍卡死；通过的行批量改 `parse_status=failed` + `error_message="task stuck in processing > <threshold>, recovered by housekeeping"` + `pending_subtasks_count=0`。**清扫 B**：`summary_status=processing` 且 `updated_at` 早于 1 小时 → failed。**周期与开关**：5 分钟（守护虚拟线程 + sleep，同 `TemporaryDocumentService` 约定）；`WEKNORA_HOUSEKEEPING_ENABLED` 缺省开启（只有 0/false/off/no 才关）；`WEKNORA_DOCUMENT_PROCESS_TIMEOUT`（Go duration 解析，缺省 2h）→ `staleThreshold = max(1h, timeout) + 10min`；`@PreDestroy` 停循环 |
| move 的 **reparse 模式收尾**（照 `moveKnowledgeReparse` L1382-1510 + `enqueueMovedKnowledge`） | `KnowledgeService.moveKnowledgeReparse`：①源侧资源清理（`cleanupKnowledgeResourcesForReparse`：向量行——绑定店走引擎口/未绑定走 pg 适配器、chunks 行软删、源图谱命名空间；失败上抛 `failed to clean up source: ...`）；②清标签关联（KB 作用域）；③行改写到目标 KB 的待解析态（`knowledge_base_id`、`embedding_model_id`=目标 KB、`parse_status=pending`、`error_message=''`、`enable_status=disabled`、`description=''`、`processed_at=NULL`、`storage_size=0`）+ 按 delta 扣减租户 `storage_used`（新增 `TenantStorageService` 注入）；④`worker.enqueue` 重新解析（目标 KB 的 chunker/嵌入模型/多模态/问题生成随之生效） |
| 顺手修复：reuse_vectors 路径的两处保真缺口 | Go 的搬移分支同时做「清 `knowledge_tag_relations`」与「行落 `parse_status=completed` + `error_message=''`」（`UpdateKnowledgeForTransfer` 的写列），Java 此前两处都漏——搬走后的文档还挂着源 KB 的标签、且行停在原状态。已补齐（两条 move 分支共用清标签） |

**四处理错注释回收**：`KnowledgeProcessWorker:621`、`ChunkExtractService:297`、`SpanTracker:38,161`、`StartupTaskRecovery:38` 都把兜底责任外推给"housekeeping sweep"——本轮起该组件真实存在，注释不再悬空。

**与 Go 的差异（备案）**：① Go 用 robfig/cron，本仓用守护虚拟线程（同 10 分钟 ticker 约定），启动后先睡一个周期（= Go 的"下个整 5 分边界首次触发"）；② span 心跳过滤由"取 `MAX(updated_at)` 再客户端解析字符串"改为**存在性判定**（Go 的字符串解析是为绕开 SQLite 聚合不做类型转换；`EXISTS(updated_at > cutoff)` 语义等价），失败方向一致；③ Go 的 `TaskInspector`（asynq）在本仓无实现（Lite，见 `SystemAdminController` 的 noopTaskInspector 对照）——保留同名注入缝，生产传 `null`，**瞬时队列这道检查因此休眠**（持久闸独立生效）；④ reparse 收尾无 transfer-state 的 `reparse_pending/done` 阶段与 `acknowledgeMovedReparse`（Java 无该状态机）、无图片资源回收（`deleteExtractedImages`，随资源目录面）、无 wiki 侧清理与触发（`cleanupMovedSourceWiki`/`EnqueueWikiIngest`，随 wiki 消费面）、入队无 asynq TaskID 去重（幂等由解析本身承担）。

**验证**：`HousekeepingServiceTest` 15 条（清扫 A/B、心跳新鲜/过期、瞬时队列保留与探测失败、持久 wiki op 命中（inspector=null 下独立生效）、阈值下限/缓冲、Go duration 解析与回落、开关缺省开启与显式关停）+ `KnowledgeMoveReparseTest` 2 条（行改写到目标 KB 待解析态 + 源侧向量/chunks/标签关联清空 + 租户用量 delta 扣减 + 进度终态；`storage_size=0` 时不动用量）全绿；knowledge 定向批绿；全量五批验收 **B1a/B1b/B2/B3 全绿，B4 仅 `SystemContractTest.parserEnginesOfflineShape` 一条环境相关既有失败**（该用例断言"测试机禁网络"，但本机 dev 栈常驻——OrbStack 的 docreader gRPC 占 50051、Go 8080、Java 8082——探测到真注册表；**`git stash -u` 后干净树单跑同样失败**，与本批无关；已记入 `known-issues/07-model-debug.md` 的环境失败清单）。

**follow-up 剩余**：§0.-21 立项的三项**全部清零**。检索批只剩**新店族**（OpenSearch 独立一支；gRPC 族 Weaviate/Qdrant/Milvus/腾讯需协议决策；SQLite/Doris）。

## 0.-22 写链改道 + 启动恢复（2026-09-25——W5γ4.7）

**做了什么**（§0.-21 立项 follow-up 的 1、3 两项 + 2 的一部分）：

| 件 | 说明 |
|---|---|
| `KnowledgeVectorWrites`（新，网关） | 绑定 store 的 KB → 复合引擎（`createForKb`）；未绑定 → null（调用方保持 pg 直连）。**风险最小切分**：golden 锁定的错误形态全在未绑定路径，行为逐字节不变；Go 对未绑定也走 postgres 引擎，净效果同一段 SQL（驱动未配置时 Go no-op、本仓仍直删——该部署检索同样 no-op，差异无观测面，备案） |
| `ChunkVectorIndexer` 写链改道 | `indexAndStore` 的绑定分支走引擎：`getEmbeddingModel` → `engine.deleteByChunkIdList` → `engine.batchIndex`（IndexInfo 逐字段照 Go syncChunkIndex/updateChunkVector：KnowledgeType=kb.Type、问题行 GeneratedQuestionSourceID、TagID 零值）。嵌入/分批/退避由 KV 引擎服务承担（40/10 + 5 次退避，不走 BATCH_EMBED_SIZE 直连分批——与 Go 引擎路径一致） |
| 知识删除改道 | `KnowledgeProcessWorker` 两处预清理/失败清理抽 `deleteKnowledgeVectors`：绑定 → 引擎 `deleteByKnowledgeIDList`（照 knowledge_delete.go L499-514），未绑定保持直删 + 模型缺失跳过（与 Go "Skipping vector store cleanup" 同形） |
| FAQ 写链改道 | `FaqService` 三触点（批量 enabled/tag 同步、`indexFAQChunks`、`deleteFAQChunkVectors`）：绑定 → 引擎 BatchUpdate*/DeleteByChunkIDList/BatchIndex；配额估算保留在本侧（部署级记账）。separate 模式相似问削减无独立触点（indexFAQChunks 全删重插，净效果等价，照原注释备案） |
| **move 向量搬运**（照 moveKnowledgeReuseVectors L1288-1370） | mode 贯通到 worker；reuse_vectors 模式搬行后 `MoveKnowledgeIndices` 原地改写 embeddings——同店校验（源/目标 vector_store_id 一致，两边 NULL = 共享 env-store）+ 同嵌入模型校验（"uses a different embedding model"）后，绑定店走引擎口（ES 有自己的 move 语义）、未绑定直接走 pg 适配器（move.go 的 UPDATE + tag_id 清空）。reparse 模式的资源清理 + 重新解析入队**仍未翻译**（行为 = 只搬行，向量残留待目标重解析清理——HANDOFF follow-up 剩余项） |
| **KB clone 向量复制**（照 CloneChunk L390-421） | `cloneKnowledgeRow` 建 chunk 新旧 id 映射后 `copyKnowledgeVectors`：目标 KB 配了嵌入模型才复制；`CopyIndices` 经**源** KB 的店（绑定→引擎，未绑定→pg 适配器的分页 + 三态 SourceID 改写 + ON CONFLICT DO NOTHING）。Go 的 rollbackIndices 闭包（失败回删）属 clone worker 回滚机制，本仓无对应面——失败标 failed（备案）。**修复的真实功能缺口**：此前克隆/KB clone 只拷行不拷向量，克隆出的知识不可检索 |
| `StartupTaskRecovery`（新，照 reset_pending_tasks.go 全文） | 启动时复位卡死的处理态任务：①知识解析（仅 Lite=REDIS_ADDR 未配置）pending/processing/finalizing/deleting → failed + "Task interrupted due to application restart" + 子任务计数清零，**wiki 独槽的 finalizing 行排除**（NOT-EXISTS task_pending_ops 子查询照抄——持久化 wiki op 启动后能重建触发器收尾）；复位后按行取消孤儿 span（latestAttempt + cancelAllOpenSpans，SERVER_RESTART）；②摘要（仅 Lite）pending/processing → failed；③同步日志（两模式）running → failed + "Sync interrupted..." + finished_at，分布式加 30 分钟陈旧窗（asynq 队列里可能有未开 span 的积压，照 Go 不敢判死）。**分布式模式刻意不复位知识/摘要**（Go 注释原文：另一副本可能在执行同一知识）——HousekeepingService 的职责。已知差异：摘要状态字面量未提常量（Knowledge 域类型无 SUMMARY_* 常量，"failed"/"pending"/"processing" 直写） |

**验证**：全量五批验收 PASS；新增 `KnowledgeVectorRoutingTest`（move 搬行改写 embeddings + tag 清空；clone 复制向量行三态 SourceID 改写）与 `StartupTaskRecoveryTest` 4 条（Lite 复位/wiki 独槽排除/分布式跳过/同步日志两模式 + 陈旧窗）全绿；knowledge/config/datasource 定向批绿。

**follow-up 剩余**：②HousekeepingService（Go `knowledge_housekeeping.go` 399 行 + container.go:1737 周期清扫——需 span 活动 + 真队列双检查，分布式语义重，专批）；reparse 模式的 move 收尾（清理 + 重新解析入队）。

## 0.-21 接线批第 3/4 步 + normalizer + 走查评审批（2026-09-25——W5γ4.6）

**做了什么**（§3.-2 的两步 + §0.-20 遗留的 normalizer + 一轮全面评审的修复）：

| 件 | 说明 |
|---|---|
| `PgVectorEngineRepository`（新） | postgres 引擎仓库的引擎口适配器（照 Go `repository/retriever/postgres/` 的 `RetrieveEngineRepository` 实现面 + move.go 16 行）：读路径委托既有 `PgVectorRetrieveRepository`（golden 锁定的 SQL 逐字件）、写路径委托 `VectorStoreService`（双方言件）——行为与直连路径逐字节一致，不复制 SQL；检索分派按 `RetrieverType`（未知类型报 `invalid retriever type`）；`moveKnowledgeIndices` 改写 knowledge_base_id 并清 tag_id |
| `EngineAwareNormalizer` + `ScoreNormalizer`（新，照 normalizer.go 全文） | 只归一化向量分；Milvus 带符号余弦 `(s+1)/2` 再 clamp01；其余已落地引擎 clamp 透传；BM25 原样；NaN→0 保严格弱序 |
| `RetrievalEngineWiringConfig`（新，照 initRetrieveEngineRegistry） | `EngineRegistry` bean（挂 storeRepo + `StoreEngineFactory.withGuard(ssrfGuard)`）；env-store 注册：RETRIEVE_DRIVER 逐段精确匹配（不 trim，照 Go）——postgres → 适配器、elasticsearch_v7/v8 → env 现场建驱动（guard=null，照 Go env-path 无 SSRF）、其余驱动诚实 WARN 跳过；注册失败只记日志不炸启动；`TenantStoreOwnership` bean |
| `ChunkService.deleteGeneratedQuestion`（接线，Go chunk.go L832-855 照序） | 引擎创建（`RetrieveEngineFactories.createForKb`：无绑定回落租户有效引擎，失败 → `failed to create retrieve engine: %w` 包 400，**分支已可达**）→ 嵌入模型（`ModelRuntimeFactory.getEmbeddingModel`，文案逐字）→ `engine.deleteBySourceIdList`（失败只警告继续）。未配 RETRIEVE_DRIVER 时引擎列表为空、复合引擎空壳删除 no-op（与 Go 同形——golden `chunk-q-delete*` 双环境皆成立） |
| `HybridSearchService`（改造，照 storegroup/fanout 两文件） | **拆掉 2201 硬编码**：KB 按 (vectorStoreId, kb.tenantId) 分组 → 逐组 `createForKb` 解析复合引擎 → `buildRetrievalParams`（FAQ/文档分流是逐 KB 属性）→ 单组快速路径直接 Retrieve、多组虚拟线程扇出（上限 4、组超时 `MULTI_STORE_RETRIEVE_TIMEOUT_SEC` 缺省 30s，all-or-nothing → 2201）→ 跨引擎类型过 normalizer；`classifyFactoryError` 哨兵→2200/2201 的 BizException（HTTP 400，UUID 只进日志）；`validateSameEmbeddingModel` 落地（Go 语义：不同嵌入模型的多 KB 检索 400）；`resolveEmbeddingModelKeys` 补属主租户上下文解析（WithExecutionTenant 等价，org-share 跨租户同模型不再误判）；FAQ 迭代路径改按组涨 TopK（引擎复用不重解析）。绑定 ES store 的 KB 从此真实路由（2201 只剩"store 建不起来"的真不可用） |
| KB 绑定校验（Go knowledgebase.go L156/L231-283/L1244） | `KnowledgeBaseService.validateVectorStoreBinding`（畸形 UUID 快拒 2200 → `RetrieveEngineFactories.verifyBinding`：FORBIDDEN→2200 "vector store not found"、NOT_FOUND/UNAVAILABLE→2201 "…check its connection configuration"、取消透传、其余 500）；接线 createKnowledgeBase 与 duplicateKnowledgeBase 两处 |
| 评审批修复（见下"全面评审"） | `ImService` 启动拉起渠道 + PreDestroy 停止（对照 container.go L1664 + Service.Stop）；`TemporaryDocumentService.cleanupExpired` + 10 分钟守护 ticker（对照 container.go L1769-1790 + CleanupExpired）；`PluginSearch` 租户 web 配置 port 接线（对照 search.go L597-600——此前恒 null）+ `resolveWebSearchMaxResults` 租户缺省分支（对照 session_knowledge_qa.go L1285-1288）；`SessionKnowledgeQaService` @mention 收敛的 **Long 引用比较**修复（租户 10002 恒误判"跨租户 agent"，约定 §5 第 6 条复发）；`KnowledgeBaseService` 列表过滤的 **NUL 字节哨兵**清理（`"\0skip"` 字面量让 Edit/grep 把文件当二进制，改为提前 continue） |

**与 Go 的差异（备案）**：①适配器 `Save` 统一走 saveIndexRows 的 ON CONFLICT DO NOTHING/MERGE（Go 裸 Create 冲突即错；生产链路无单条 Save 调用方，不可达）；②`CopyIndices` 的目标行不写 is_enabled（照 GORM default:true 的省略语义，DB 默认 true 生效）；③多组扇出的组超时到点即判失败（Java 引擎不收 ctx，无法取消底层调用，超时线程自然跑完结果丢弃）；④嵌入模型构造期 SSRF 校验（Go 在传输层 embed 时才拦）——按既有 `ModelRuntimeFactory` 行为，ChunkServiceTest 相应注 127.0.0.1 白名单；⑤组序/结果序确定（延续检索批备案）。

**验证**：全量五批验收绿（`scripts/acceptance.sh`，B1a/B1b/B2/B3/B4）；新增测试——normalizer 5 / 适配器 7 / 装配 6 / storegroup 8 全绿；**实弹 hybrid-search A/B**（Go:8080 + Java:8082 新代码、同连 dev PG、walkadmin 租户 10122 的 GAC客服 KB）：hybrid 两场景公共前缀**逐字节一致**（含 score 浮点字节）；向量-only 的余差经两侧各自连跑两次交叉对比坐实为 **dashscope 嵌入 API 的调用间非确定性**（同侧两次亦漂移 ~1e-6，且 Java run1 分数集合与 Go run2 完全重合）——环境噪声而非翻译缺陷。

**全面评审（Explore 全仓扫描）其余发现与处置**：已修见上表评审批行。**确认为设计内降级不动**：BrowserSkillManager WS relay（需浏览器后端，在册）、DataAnalysis（DuckDB，在册）、Redis 限流器（在册）。**新立项 follow-up（W5γ4.6 立项；1、3 已于 W5γ4.7 落地，见 §0.-22）**：
1. ~~resetPendingTasks / recoverPendingWikiTasks~~ ✅ W5γ4.7 落地 `StartupTaskRecovery`（wiki 独槽排除 + 孤儿 span 取消 + 同步日志两模式；"重建触发器"的 wiki 存量行收尾路径即独槽排除的语义，随 wiki 消费面复用）；
2. **HousekeepingService**（Go `knowledge_housekeeping.go` 399 行 + container.go:1737 启动）：卡死行兜底清扫——`ChunkExtractService:297`/`KnowledgeProcessWorker:591`/`SpanTracker:38,161` 四处注释把兜底责任推给这个不存在的组件；
3. ~~知识写链的引擎路由~~ ✅ W5γ4.7 落地 `KnowledgeVectorWrites` 网关 + 各写链绑定分支（ChunkVectorIndexer/知识删除/FAQ/KB clone 向量复制/move 的 reuse_vectors 搬行——未绑定路径行为逐字节不变）；reparse 模式 move 收尾（清理 + 重新解析入队）仍开。

**下一步**：OpenSearch driver（独立店族）→ gRPC 族协议决策（Weaviate/Qdrant/Milvus/腾讯）→ SQLite/Doris → 上述三项 follow-up → provider-XDEP 族 / Owner 决策遗留。

## 0.-20 接线批·第 2 步：注册表 + 复合引擎 + 工厂函数（2026-09-25——W5γ4.5）

**背景**：§0.-19 落了引擎端口 + KV 包装层 + 引擎工厂，但它们**仍没有调用方**——Java 侧既无
注册表（env-store / DB-store 两张表），也无复合引擎与工厂函数。本步按 Go
`internal/application/service/retriever/` 的四件（`registry.go` 370 + `composite.go` 353 +
`factory.go` 260 + `ownership.go` 35 ≈ 1018 行）补齐"解析层"，让驱动层成为**可装配、可分类**的
服务；ChunkService 接线与 HybridSearch 路由见"下一步"（本步不改任何已 golden 锁定的读路径）。

| 件 | 说明 |
|---|---|
| `retrieval/engine/RetrieveEngineService`（新，端口） | 照 `interfaces.RetrieveEngineService`（14 方法）+ `KnowledgeIndexMover` 子口 + `KnowledgeIndexMoveValidator` 能力口（Go 的匿名接口断言 → Java `instanceof`）；`KeywordsVectorHybridRetrieveEngineService` 实现之 |
| `RetrievalEngineException`（新，哨兵族） | 照 factory.go L17-42 四个 sentinel + registry 两处 `fmt.Errorf` 的分类位。**文案与 Go 逐字一致且不含 store UUID**（防枚举泄漏，租户/store 只进结构化日志）；`isKind` 沿 cause 链等价 `errors.Is`；`isCancellation` = `CancellationException/TimeoutException/InterruptedException`（与 `ImFormat.isCanceledOrDeadline` 同约定） |
| `EngineRegistry`（新，照 registry.go） | 两张表（byEngineType env-store / byStoreID DB-store）+ **按需重建四道闸**：**冷却 30s**（后端持续宕机时不再每请求赔一次构建超时）、**代数**（构建前后采样复核——构建期间的注册/注销不被这次构建"撤销"）、**singleflight**（按 `tenantID:storeID` 分键折叠并发 miss，防止冷 store 变连接风暴）、**panic 兜底**（Java 的 `Error` → 可重试哨兵，且**不设冷却**，照 Go 的 recover 路径）。内置 `SingleFlight`（`DoChan` 子集：`value/error/shared` + 两个测试口 `onFlightJoin` / `flightObserver`） |
| `CompositeRetrieveEngine`（新，照 composite.go） | 按检索类型分派（`Retrieve`）+ 对全部引擎扇出；`Index`/`BatchIndex` 自算向量、`BatchIndex` 按 **SourceID** 去重；`EstimateStorageSize` 失败只记日志并返回**部分和**；迁移先**整体预检**（mover 断言 + 可选校验器）再逐个执行 |
| `RetrieveEngineFactories`（新，照 factory.go） | `createForKb` / `createFromPayload` / `verifyBinding` / `classifyLookupError`：无绑定→租户有效引擎；有绑定→归属校验（跨租户 FORBIDDEN）→注册表解析（未注册 NOT_FOUND）→单引擎仍包成复合（保住 `Support()` 驱动的类型匹配，且**有意压过租户级 effectiveEngines 过滤**，照 Go）。分类规则：取消/超时与三个 store 哨兵原样透传，其余一律 UNAVAILABLE（把未知失败当永久失败才是静默丢单的根源） |
| `StoreEngineFactory`（新，函数口） | 照 `interfaces.EngineFactory`：注册表只依赖本口，真实构造留在 `EngineFactory.createFromStore`；`withGuard(SsrfGuard)` 是生产装配 |
| `TenantStoreOwnership` + `VectorStoreRepoOwnership`（新，照 ownership.go） | 仓储 `getByID` 自带租户范围（`WHERE id=? AND tenant_id=?`）⇒ "在该租户下存在"即"属于该租户"，返回值上不必再比一次 tenant |
| `RetrieverEngineParams` + `EffectiveEngines`（新，抽取） | 把原先内嵌在 `HybridSearchService` 的 `GetEffectiveEngines` + `retrieverEngineMapping`（10 驱动 × 检索类型）抽出成共享件——工厂的 env-store 分支与 HybridSearch 的引擎路由必须用**同一份**；`HybridSearchService` 改为委托，**行为不变**（同映射表、同 `RETRIEVE_DRIVER` 语义、同去重规则） |
| `KeywordsVectorHybridRetrieveEngineService`（改造） | 实现端口 + mover/validator 两子口；**撤掉内嵌的薄口 `Embedder`**，改用全仓统一的 `com.ragagent.embedding.Embedder`（照 Go：端口收的就是同一个 `embedding.Embedder`，不该为检索引擎另造一个）——第 3 步的适配器因此可直接接 `ModelRuntimeFactory.getEmbeddingModel` |

**与 Go 的差异（备案）**：

- **无请求级取消**：Go 的构建 context 从发起请求上摘下来（`context.WithoutCancel`）+ `DoChan`
  的 select，让"首个调用方关标签页"不连带失败所有等待者；本仓无请求级取消，构建恒为共享航班，
  取消语义只保留在 `EngineBuildTimeout` 一处（虚拟线程 + `CompletableFuture.get(timeout)`，
  超时即打断构建线程 + 进冷却）。因此 Go 的「leader 取消不毒化等待者」在本仓**不可达**，
  改以「等待者确实加入同一次构建」验证同一意图（工厂调用计数 = 1 + `shared=true`）。
- **顺序确定（有意偏离 Go 的不确定性）**：`engineInfos`（Go `maps.Values` 随机序）与并发收集的
  结果（Go 完成序）本仓一律按**引擎序 / 入参序**回填；`common.Deduplicate`（Go 用 map 收集 →
  无序）→ 本仓**保首次出现顺序**；两表用 `LinkedHashMap` ⇒ `getAllRetrieveEngineServices()`
  顺序确定（注册序）。同一输入两次运行的结果逐项一致，对 golden 友好。
- **嵌入失败以 RuntimeException 表达**（Go 是 error 返回值）；`batchEmbedWithBackoff` 的
  `catch (Exception)` 语义不变。
- 日志走 slf4j；结构化字段（tenant_id / store_id / reason）照抄。

**测试**：`com.ragagent.retrieval.*` **93/93 绿**（本批 +50：工厂 19 + 注册表 15 + 复合 12 +
有效引擎 4；既有 43，其中 KV 服务的 `Embedder` 桩随统一口改了签名）。
覆盖：工厂全部哨兵分支（含"取消不是对 store 的判定"两条路径）、注册表两张表语义 + 双表隔离 +
并发安全 + 按需重建六态（折叠单次构建 / 失败冷却与注销清冷却 / Error 兜底 / 构建期间注销不复活 /
代数不被并发注册覆盖 / 缺 repo-factory 降级 / DB 故障与 store 不存在二分 / 构建超时）。

**下一步**：① `ChunkService` 的引擎创建接线（现为 `L464` 接缝注释）+ `Embedder` 适配器
（接 `ModelRuntimeFactory.getEmbeddingModel`）→ ② `HybridSearchService` 按引擎类型路由
（拆掉 `vector store is currently unavailable` 的 2201 硬编码；**唯一动 golden 锁定读路径的一步，
必须单独验回归 + 真 PG A/B**）→ ③ `retriever/normalizer.go`（分数归一化，多引擎路由落地后才有意义）
→ ④ OpenSearch driver → gRPC 族协议决策。

## 0.-19 接线批·第 1 步：引擎端口 + KV 包装层 + 引擎工厂（2026-09-25——W5γ4.4）

**背景**：§0.-16/§0.-17 落了 ES v7/v8 driver，但它们**没有调用方**（Java 侧既无引擎工厂、
也无包装层与路由）。本步按 Go 的两层补齐"每店层"，让 driver 成为可装配的引擎服务；
其余两步（registry/composite/`CreateRetrieveEngineForKB` 与 `HybridSearchService` 路由）见"下一步"。

| 件 | 说明 |
|---|---|
| `retrieval/engine/RetrieveEngineRepository`（新，端口） | 照 Go `interfaces.RetrieveEngineRepository` + `KnowledgeIndexMover` 子口；ES v7/v8 已实现（`moveKnowledgeIndices` 由 3 参扩到 6 参以对齐 Go 的接口签名，ES 侧忽略后三个） |
| `retrieval/engine/KeywordsVectorHybridRetrieveEngineService`（新，照 `service/retriever/keywords_vector_hybrid_indexer.go` 383 行） | 骨架纯转发（Retrieve/Support/三类删除/CopyIndices/两类批量更新）；Index/BatchIndex 负责嵌入落库；**净化**（仅含 `base64,` 时跑 4 条内联图正则 → `[image]`，随后按**码点**截断 20000 并告警）；**退避**（5 次、200ms 起翻倍）；**分批**（向量 40 / 非向量 10；批数 ≤5 全并发、否则限 5）；嵌入映射一律**按 SourceID**；迁移能力探测（未挂子口 → `retriever <engine> does not support moving indices`） |
| `retrieval/engine/EngineFactory`（新，照 `container/engine_factory.go` 391 行） | `createFromStore`：先跑**逐引擎地址策略**（postgres/sqlite 免检；ES/OpenSearch/Milvus/腾讯/Doris 检 `addr`；Qdrant 检 `host:port`、去方括号；Weaviate 检 `host`+`grpc_address`；未知类型 → `vector store engine "<t>" has no SSRF address policy`；失败文案 `<label> failed SSRF validation: <err>`），再按类型建服务——**ES v7/v8 真落地**（版本前缀 `7.` 判定、索引名/shards 缺省 0/replicas 缺省 -1、Basic Auth、构造即自举）；postgres 明确指向既有 JDBC 件；sqlite/Qdrant/Milvus/Weaviate/Doris/腾讯/OpenSearch → 诚实 XDEP |

**修复（有意偏离 Go，同 §0.-18 类）**：④ `EstimateStorageSize` 的占位向量 Go 以 `ChunkID` 为键，
而查表按 `SourceID` → 生成问题估不到向量字节；本仓按 SourceID 为键（§0.-18 表已补第 ④ 行）。

**与 Go 的差异（备案）**：
- 并发用 Java 21 虚拟线程 + `Semaphore`（对应 errgroup + 信道信号量），首个失败取消其余；
  `utils.ChunkSlice` 落到类内 `chunkSlice`（同语义）；
- 退避底延迟留了包内测试口（默认仍 200ms，照 Go 常量）；
- `Embedder` 是 Go `embedding.Embedder` 的薄口（`Embed`/`BatchEmbedWithPool`/`GetDimensions`），
  实现（接 `knowledge/service/EmbedderClient`）留待 `ChunkService` 接线那一步；
- Go 的 `Index`/`BatchIndex` 走 `Embedder` 抽象（本仓同），`auditSink`（OpenSearch 审计）随 OpenSearch 支。

**测试**：`com.ragagent.retrieval.*` 43/43 绿（引擎层 33：ES v8 11 + v7 10 + KV 包装 7 + 工厂 5；
既有 10）。

**下一步**：① registry/composite/`factory.go`（`CreateRetrieveEngineForKB` + 租户商店归属校验）
→ ② `ChunkService` 的引擎创建接线 + `Embedder` 适配器 → ③ `HybridSearchService` 按引擎类型路由
（动 golden 锁定读路径，单独验回归）。

## 0.-18 修复三处 Go 侧向量缺陷（2026-09-25——W5γ4.3，有意偏离 Go）

**背景**：W5γ4.1/γ4.2 落地 ES v7/v8 driver 时，按"逐字照抄"把三处 Go 缺陷复刻进了 Java。
**用户明确指示：发现的问题需要修复**——不是把 bug 复制过来。本批改为"有意偏离 + 逐处备案"。

| # | 位置 | Go 的行为 | 本仓修复 | 影响 |
|---|---|---|---|---|
| ① | v7 `CopyIndices`/`saveCopiedIndices` | `embeddingMap` 是**新建空 map**（`processSourceBatch` 收集的向量被丢弃）→ 复制过去的文档不带向量 | 向量随 `CopiedHit(indexInfo, embedding)` 回到 copyIndices，按**目标 SourceID** 为键写入 `additionalParams.embedding` | 复制后检索/重排不再缺向量 |
| ② | v7 `processHit` | 恒传 `MatchTypeKeywords` → **向量结果也标 1** | 按实际检索类型给（vector → MatchTypeEmbedding=0，keywords → 1），对齐 v8；日志措辞也对齐 | 下游按 matchType 分流不再错 |
| ③ | v8 `CopyIndices` | `embeddingMap` 以**目标 chunkID** 为键，而 `ToDBVectorEmbedding` 按 **SourceID** 查表 → 生成问题（`<chunk>-<qid>` 形态）取不到向量、同 chunk 多文档互相覆盖 | 键改为**目标 SourceID**（逐文档唯一） | 题项向量不再丢/串 |
| ④ | `KeywordsVectorHybridRetrieveEngineService.EstimateStorageSize`（W5γ4.4 落） | 占位向量以 `ChunkID` 为键，而查表按 `SourceID` → 生成问题估不到向量字节（低估） | 占位向量改按 **SourceID** 为键 | 估算不再低估 |

**为什么键取 SourceID**：`ToDBVectorEmbedding` 的查表语义由 `structs.go` 定为"按 SourceID"，
修键比改查表更小、更贴合原意（v7/v8 两处因此语义一致）。

**回归**：`com.ragagent.retrieval.*` 31/31 绿；3 条测试的断言从"照抄缺陷"翻转为"修复后行为"
（v7 复制带向量、v7 向量命中标 0、v8 复制逐文档带向量）。

**给上游的提示**：三处都是 Go 侧真缺陷（v7 两处 + v8 一处）。若上游修复，Java 侧无需回退
（本仓行为即修好的那一侧）；反向同步时注意别把这三处"照抄"回来。

## 0.-17 外部向量店 driver·第 2 支：ES v7 + 补 v8 的 move.go（2026-09-25——W5γ4.2）

**背景**：§0.-16 的续推。协议盘点后的"HTTP 族"里，先做 **ES v7**（1452 行，非 typed client），
并补上 §0.-16 **漏掉的 v8 `move.go`**（48 行 + Go 侧 9 例完整性表）。

**已落地**：

| 件 | 说明 |
|---|---|
| `ElasticsearchV8RetrieveRepository#moveKnowledgeIndices`（补） | 照 v8/move.go：`bool.filter` 用 **terms 数组**、脚本**不带 lang**、`?refresh=true`、完整性校验（total/updated 必在、total ≥ 0、total==updated、未 timed_out、version_conflicts==0、failures 空，否则 `move indices was incomplete`）——Go 的 9 例表全部移植 |
| `EngineTypes#resolveIndexName`（补） | 照 `types.ResolveIndexName`（vectorstore.go）抽成共享助手，v8 改委托 |
| `elasticsearch/ElasticsearchV7RetrieveRepository`（新） | 照 v7/repository.go + v7/move.go，**与 v8 的差异逐条照抄**：Support 只报 keywords（Retrieve 也只分派 keywords，vector 直呼才可用）；命中恒标 MatchTypeKeywords（含向量结果——Go 怪癖）；单条坏命中**跳过继续**（v8 整请求报错）；基础条件返回 **JSON 字符串**；建索引 settings 是**数字**、失败文案 `failed to create index <index>`；单条写入 `PUT /{index}/_create/{uuid}`；bulk 动作行 `{ "index" : { "_id" : "<uuid>" } }`（带空格）、`errors:true` 只告警不失败、响应解析失败也放行；改状态/标签的 query 是**直构 terms（不套 bool）**、脚本带 `lang`；move 用 **singular `term`** + 字符串值、脚本带 lang；向量查询无 `_source` 排除、script 源串无空格、`min_score` 是 float64 |
| `ElasticsearchV7RetrieveRepositoryTest`（新） | 10 条：数字 settings 与 Support、建索引失败文案与后缀、估算与 `_create/{uuid}`、bulk 动作行与容错、terms 删除、关键词检索（含坏命中跳过）、向量直呼与怪癖、直构 terms 与失败文案、CopyIndices 三态、move（term/lang/refresh + 完整性） |

**发现并修复的 Go 侧缺陷（原「照抄」两处已于 W5γ4.3 修复，见 §0.-18）**：

- v7 `CopyIndices` 的 `saveCopiedIndices` 里 `embeddingMap` 是**新建空 map**（`processSourceBatch`
  收集的向量被丢弃）→ 复制过去的文档**不带向量** → **已修（§0.-18 ①）**；
- v7 `processHit` 恒传 `MatchTypeKeywords` → **向量结果也标 1** → **已修（§0.-18 ②）**；
- （§0.-16 备案的 v8 `CopyIndices` 键与查表不符 → **已修（§0.-18 ③）**。）

**协议再盘点（决定剩余顺序）**：

- **OpenSearch**（2487 行、17 文件）是**独立店族**——自有 `transport.go`（SSRF）、`healthcheck.go`、
  `audit.go`、`errors.go`、`mapping.go`、`crud.go`/`query.go`/`retrieve.go`/`byquery.go`/`copy.go`/`move.go`
  → 单列一支；
- **Weaviate**（1304 行）不是纯 HTTP：读/写/删走 REST/GraphQL，但 `client.Batch().ObjectsBatcher()`
  在 weaviate-go-client **v5 走 gRPC** → 与 Qdrant/Milvus/腾讯同类的"协议决策"族；
- ES v7/v8 至此**同族两版齐**（HTTP 族只剩 OpenSearch）。

**测试**：`com.ragagent.retrieval.*` 31/31 绿（ES v8 11 + v7 10 + 既有 10）。

**下一步**：OpenSearch（独立一支）→ 接线批（engine_factory + ChunkService + HybridSearchService 路由）
→ gRPC 族协议决策（Weaviate/Qdrant/Milvus/腾讯）。

## 0.-16 外部向量店 driver·第 1 支：Elasticsearch v8（2026-09-25——W5γ4.1）

**背景**：§2.0「已知剩余」里的"外部向量店 driver"——Go `internal/application/repository/retriever/`
下 10 个店（doris / elasticsearch / milvus / neo4j / opensearch / postgres / qdrant / sqlite /
tencentvectordb / weaviate，约 13k 行）里，Java 只落了 postgres（读路径
`retrieval/engine/PgVectorRetrieveRepository` + 写路径 `knowledge/service/VectorStoreService`，
均为 JDBC 专用件）。本批起逐店补齐。

**协议盘点（决定顺序）**：ES v7/v8、OpenSearch、Weaviate = HTTP/JSON → 本地 stub 可端到端测 ✓；
Qdrant、Milvus、腾讯 = gRPC/SDK（需单独决策）；SQLite = C 绑定；Doris = SQL 协议。
→ **先做 ES v8**（820 行，纯 HTTP）。

**已落地**：

| 件 | 说明 |
|---|---|
| `retrieval/engine/EngineTypes` | 照 Go `types/{embedding,retriever}.go`：IndexInfo / RetrieveParams / IndexWithScore / RetrieveResult + 引擎/检索/匹配类型常量（既有 pg 窄口件不动——已 golden/A-B 锁定） |
| `retrieval/engine/elasticsearch/ElasticsearchV8RetrieveRepository` | 照 `elasticsearch/v8/repository.go`（820 行）+ `elasticsearch/structs.go`：**自举**（HEAD→PUT，settings 值转字符串；GET `_mapping` 判 `chunk_id` 是否 keyword → 决定全查询的 `.keyword` 后缀）、**存储估算**（内容 + 维度×4 + 250 + (内容+向量)×5/10）、**写入**（`_doc` 单条 / `_bulk` NDJSON 的 create 行；空向量报错、空列表跳过）、**三种 terms 删除**、**向量检索**（script_score + `cosineSimilarity(params.query_vector,'embedding')` + `min_score`=float32(threshold)）与**关键词检索**（bool{filter, must:[match content]}）、**update_by_query** 改状态/标签（painless，按值/按 tag 分组）、**CopyIndices**（批 500 分页 + 改名 + SourceID 三态 + 目标向量回填） |
| `ElasticsearchV8RetrieveRepositoryTest` | 10 条：自举与后缀两态、估算公式、单条/批量写入（含空向量报错与 NDJSON 形状）、三种删除、向量/关键词**请求体形状**与响应解析、改状态/标签（Map.of 无序 → 顺序无关断言）、CopyIndices（三态 SourceID + 分页 + 向量回填） |

**差异与备案**：

- Go 由 `container/engine_factory.go` 建 client（含 SSRF RoundTripper）；Java 在构造器做等价地址校验
  （guard 可空 = 测试口）+ Basic Auth；
- **驱动层已落地但未接线**：Go 的 factory/注册表（`engine_factory.go`）、`NewKVHybridRetrieveEngine`
  包装层、ChunkService 的 `CreateRetrieveEngineForKB`（Java 现为接缝，见 `ChunkService` L464 注释）
  与 HybridSearchService 的引擎路由，留到"接线批"统一处理——本部署 `RETRIEVE_DRIVER` 未配置，
  接线前行为不变；
- 未录 Go fixture：该批无 golden 面（无调用方、无端点），桩断言即"发出去的 JSON 长什么样"的契约
  （键序按本仓惯例与 Go 声明字段序一致，ES 不敏感键序）；
- ~~照抄来的一个怪癖~~ **该缺陷已于 W5γ4.3 修复**（键改目标 SourceID，见 §0.-18 ③）：`CopyIndices` 的 embeddingMap 原以**目标 chunkID** 为键而查表按 SourceID。

**测试**：`com.ragagent.retrieval.*` 20/20 绿（ES 10 + 既有 10）。

**下一步**：ES v7 / OpenSearch / Weaviate（同族 HTTP，可照法推进）→ **接线批**（factory + ChunkService +
HybridSearchService 路由）→ gRPC 族（Qdrant/Milvus/腾讯）协议决策。

## 0.-15 W5γ3 已收官 ✅：IM 九渠道出站客户端（2026-09-24~25，十二笔提交）

**背景**：`com.ragagent.im.runtime` 已翻入站核心（验签/解析/加解密/格式化/流分片），但九支
渠道适配器（Go `internal/im/{telegram,qqbot,slack,mattermost,wecom,wechat,feishu,dingtalk,
yunzhijia}`，约 13k 行）在 Java 侧**一支都没有**——`ImService.startChannel` 恒打
"no adapter factory for platform"。

**已落地**：

| 渠道 | Java | 说明 |
|---|---|---|
| telegram | `im/telegram/{TelegramAdapter,TelegramLongPollingClient,TelegramAdapterFactory}` + `config/ImAdapterWiringConfig` | 对照 `telegram/adapter.go`(505) + `longconn.go`(120)：验签（常量时间，失败**返回**异常对象照 Go 的 error 约定）、解析（群聊剥 @bot、document/photo）、sendReply（Markdown + thread_id）、StreamSender（"正在思考..." 占位 + editMessageText 原地替换 + 500ms 节流 + Markdown 失败回落纯文本）、FileDownloader（getFile + file/bot）；webhook 与 long-polling 两模式 |
| slack | `im/slack/{SlackAdapter,SlackSocketModeClient,SlackAdapterFactory}` | 对照 `slack/adapter.go`(343) + `longconn.go`(149)：**入站委托已翻核心**（`SlackAdapterCore` + `ImAdapterVerify.slackExpectedSignature`，另加 5 分钟时间戳窗照 slack-go `Ensure`）；出站走 Slack Web API（`chat.postMessage` / `chat.update` / `files.info` + Bearer 私有下载）——sendReply 文本**原样**（Slack 这支不做展示格式化，照 Go）、thread_ts 取 messageId、channel 回落 user_id、update 无节流、endStream 用累积内容收尾；Socket Mode 走 `apps.connections.open` + `java.net.http` WebSocket（先 ack 再处理、disconnect 即重连） |

| qqbot | `im/qqbot/{QqBotClient,QqBotAdapter,QqBotGatewayClient,QqBotAdapterFactory}` | 对照 `qqbot/{client.go 222, adapter.go 127, longconn.go 199, types.go 88, factory.go 47}`：access_token 缓存（60s 余量、`expires_in` 数字/字符串两形态、缺省 7200）、除取 token 外一律 `Authorization: QQBot <token>`、发送体 `{msg_type:2, markdown:{content}, msg_id, msg_seq:1}`、C2C/群两条路径、基址与 gateway 的 SSRF 校验（gateway 必须 wss）；网关 WS：hello→identify（`QQBot <token>`/`intents=1<<25`/`shard [0,1]`）→ 心跳 `op=1`（d=最近 s）→ dispatch 交解析；`op=7/9` 重连、退避 attempt 秒（上限 30s）。**只支持 websocket**（照 Go）。适配器**不验签**（照 Go），无流式/下载面 |

**坑**：①`AdapterInterfaces.VerifyException` 原是包内非静态嵌套类 → 跨包适配器用不了，改
`public static`（并纠正 javadoc：验签失败是"返回异常对象"，调用点 `ImCallbackController`
判空折 401/403，**不是**抛出）；②`@Configuration` 的构造器不能依赖自己 `@Bean` 方法定义的
bean（`BeanCurrentlyInCreation`）→ 工厂改 `@Component`；③Jackson 的 `readTree(byte[])` 遇
`cond ? "{}" : bytes` 混合三元推断失败 → 拆成显式分支。

| wecom（webhook + 长连接） | `im/wecom/{WecomWebhookAdapter,WecomLongConnClient,WecomWSAdapter,WecomAdapterFactory,WecomSupport}` | 对照 `wecom/{webhook_adapter.go 705, longconn.go 835, ws_adapter.go 181, quote.go 71, factory.go 79}`：webhook 面验签（`FeishuWecomCrypt.wecomVerifySignature`）、**自持 AES 解密**（共享件不校 corp_id → 自带信封 + corp_id 校验）、URL 验证回显、解析（`stripAtMentionBasic` 三形态 / text+image）、发送（群 `appchat/send` 失败回落 `message/send`）、token 缓存 7200s 留 5 分钟、下载三级文件名 + IM 主机白名单；长连接面（智能机器人 WS）：`aibot_subscribe` → `aibot_msg_callback`/`aibot_event_callback` → `aibot_respond_msg` 回帧 + 30s `ping`，读超时 3×心跳、心跳失败即重连、退避 1s·2^n 上限 30s（活过 30s 重置）、**流缓冲跨重连保留**（替换语义）、`EndStream` 重试 3×500ms、`disconnected_event` 触发重连、@提及**学机器人名**、五种消息类型 + quote 上下文、逐消息 `aes_key` 的 AES-CBC 文件解密（填充畸形原样返回，照 Go）。公共件 `WecomSupport` 单源（端点校验/wss 校验/下载/查表/逐消息解密） |

| feishu + lark（webhook 半支） | `im/feishu/{FeishuRegion,FeishuAdapter,FeishuAdapterFactory}` | 对照 `feishu/{adapter.go 1343, region.go 49, factory.go 69}`：**同一实现两个平台名**（飞书/Lark 两朵隔离云，仅域名与文案不同）；验签（`header.token` 比对，加密体先解密，未配则跳过）、URL 挑战回显（含加密形态）、解析（只认 `im.message.receive_v1`；threadID=root_id 回落 message_id；群聊剥 `@_user_`；text/file/image/post 四型）、发送（reply API 优先，回落码 {230019,230054,230071} 改走 send-message；message_id 含不安全字符直接拒 = 防篡改）、CardKit v1 流式（建卡 → interactive 消息 → PUT 元素带严格递增 seq → 关流时 PATCH settings 关 streaming_mode + 摘要预览 ≤120 字符）、卡片 markdown 图片换 image_key（下载 ≤10MB → multipart 上传；按 app 缓存 + URL 去 query；失败降级为纯链接）、token 缓存留 5 分钟、资源下载（`GetMessageResource`，文件名三级回落）。**未含** `longconn.go` 369 行（lark 官方 SDK 的 WS 事件流，即 websocket 模式）——工厂对 websocket 明确抛未落地 |

| dingtalk（webhook 半支） | `im/dingtalk/{DingtalkAdapter,DingtalkAdapterFactory}` | 对照 `dingtalk/{adapter.go 968, factory.go 51}`：验签（`Timestamp`/`Sign` 头 + ±1 小时时间窗，期望签名 = Base64(HMAC-SHA256(`<ts>\n<secret>`, secret))，走共享 `ImAdapterVerify.dingtalkExpectedSignature`，定长比较；空 secret 跳过）；解析四段链 **richText → file/picture → audio → text**（群聊判 `conversationType=="2"`；userId 取 senderStaffId 回落 senderId；richText 文本 trim+换行连接 + 首图 downloadCode（原图优先回落预览码）+ 多图提示"仅处理第一张"；图片无文件名 → `<msgId>.png`；audio 取 recognition）；下载（downloadCode 换 `/v1.0/robot/messageFiles/download` 的临时 URL → 宿主机白名单 `*.aliyuncs.com`/`*.dingtalk.com` 放行、其余 SSRF 校验 → GET 字节；robotCode 取回调回落 client_id）；发送（回调里的 `sessionWebhook` 优先 markdown 直发（先 SSRF 校验），否则 OpenAPI 群 `groupMessages/send`+openConversationId / 私聊 `oToMessages/batchSend`+userIds，`msgKey=sampleMarkdown`，头 `x-acs-dingtalk-access-token`）；流式（配 `card_template_id` 才建 **AI 卡片** `createAndDeliver`（`dtv1.card//IM_GROUP.<cid>` / `IM_ROBOT.<uid>`），逐次 `PUT /v1.0/card/streaming`（isFull/isFinalize）**500ms 节流**；EndStream 定稿卡片，无卡退回 webhook 整段）；token 缓存留 5 分钟；流 ID 确定性 `dt:<userId>:<messageId>`。**未含** `longconn.go` 68 行（钉钉 Stream SDK 的 WS，即 websocket 模式，且是 Go 的**默认模式**）——工厂对 websocket 明确抛未落地 |

| dingtalk Stream 长连接 | `im/dingtalk/DingtalkStreamClient`（+ 工厂接 websocket） | 协议逐字描自 Go SDK `client/client.go` + `payload/*.go`（Go 自己的 `longconn.go` 只是壳）：`POST /v1.0/gateway/connections/open`{clientId,clientSecret,subscriptions[SYSTEM/ping,SYSTEM/disconnect,CALLBACK//v1.0/im/bot/messages/get],ua,localIp,extras} → `{endpoint,ticket}`（本仓额外做 wss+SSRF 校验）→ WS `{endpoint}?ticket=` → 数据帧 JSON `{specVersion,type,time,headers{topic,contentType,messageId,time},data}`；回执 `{code,headers{contentType,messageId},message,data}`——普通 200、ping 回 `200+ok+data 原样`、未知 topic 404、处理器异常 500、**disconnect 先回执再关**；心跳 = 每 120s **WS 控制帧 ping**（5s 无 pong 即关，照 SDK 的 keepAliveIdle/pingWait）；SYSTEM 帧在消费线程同步处理（照 SDK：控制帧不被慢处理器饿死）、CALLBACK 交 4 线程池；重连退避 1→30s（SDK 内部循环改由本类承担）；流帧 → 统一消息复用 webhook 解析，`robot_code` 回落 clientId（照 Go 的 fallbackRobotCode） |

| feishu/lark 长连接 | `im/feishu/{FeishuLongConnClient,LarkFrame,LarkEventConverter}`（+ 工厂接 websocket） | 协议逐字描自 lark Go SDK 的 `ws` 包（`client.go` + `model.go` + `pbbp2.pb.go`）：`POST {domain}/callback/ws/endpoint`{AppID,AppSecret}（头 `locale: zh`）→ `{code,msg,data{URL,ClientConfig}}`（code 0/1/1000040343 分支；ClientConfig 覆盖 ReconnectCount/Interval/Nonce/PingInterval，默认 -1/120s/30/120s 照 `NewClient`）；WS 直连 URL（`device_id`/`service_id` 从查询串取，后者进 ping 帧）；帧 = **pbbp2 protobuf**（`method=0` 控制〔`type=pong`，payload 可带新配置〕、`method=1` 数据〔`type=event/card` + `sum/seq/message_id/trace_id`〕）——Java 侧手写编解码，**与 Go 逐字节一致**（fixture 由独立 Go 程序录制：字段升序、空串照写 0 长度、payload 非 nil 才写、headers 非空才写，LarkFrameTest 断言解码字段 + 重编码全等）；分片按 message_id 攒片（TTL 5s，照 `combine`）；回执 = **同帧回写** payload `{"code":200|500}` + 追加 `biz_rt`（处理毫秒）；心跳每 PingInterval 秒发控制帧 ping；重连按 count/interval/nonce（首次抖动 ≤nonce 秒）；事件 → 统一消息走 `LarkEventConverter`（照 `convertEvent`：**不设 threadId**、post **取首图按图片消息**——这两处是 Go 里 webhook/longconn 的既有分歧）；WS 地址额外做 wss+SSRF 校验（SDK 不校验） |

| wechat（iLink 机器人，仅长轮询） | `im/wechat/{WechatAdapter,WechatLongPollClient,WechatCrypto,WechatAdapterFactory}` | 对照 `wechat/{adapter.go 329, longpoll.go 376, crypto.go 72, factory.go 39}`：**不是公众号 XML 回调**，是腾讯 iLink 机器人（`ilinkai.weixin.qq.com`）。发送 `/ilink/bot/sendmessage`（`message_type:2` BOT / `message_state:2` FINISH / `item_list[0]={type:1,text_item}` / `context_token` 来自消息 extra；文本**原样不格式化**，照 Go）；输入中 `/ilink/bot/sendtyping`；每请求带 `base_info.channel_version=weknora-1.0.0` + 认证头（`AuthorizationType: ilink_bot_token` / `Bearer` / 随机 `X-WECHAT-UIN`）；长轮询 `/ilink/bot/getupdates`（`get_updates_buf` 游标推进、`errcode == -14` → token 过期即停、`ret!=0 && errcode!=0` 算错、退避 1s·2^n 上限 30s 且**轮询活过 30s 后失败清零计数**、每条消息 detached 处理）；解析只取 `item_list[0]`（1 text/2 image〔CDN URL + 优先 `aeskey`(hex) 回落 `media.aes_key`〕/3 voice 取转写/4 file〔`len`→字节数、文件名回落 `file_<id>`〕，`message_type==2` 丢弃）；下载 = CDN URL 先 SSRF 校验 → 有 `aes_key` 才 **AES-128-ECB** 解密（PKCS#7 **只在校验通过时去**，照 Go）；密钥三形态解析（base64 裸 16B / base64 的 32 hex / 裸 32 hex + 偶数 hex 兜底）；回调三面明确不支持（照 Go 文案）；**工厂不读 mode**（只支持长轮询，照 Go） |

| wechat 扫码登录（端点接缝补齐） | `im/wechat/WechatQRCodeService` + `ImChannelController` 两端点 | 对照 `wechat/qrcode.go`(165) + `handler/wechat_qrcode.go`(89)：`GET /ilink/bot/get_bot_qrcode?bot_type=3` → `{qrcode,qrcode_img_content}`（空码/非 200 折错）；`GET /ilink/bot/get_qrcode_status?qrcode=` 带 `iLink-App-ClientVersion: 1`，**长轮询 38s**（超时算 `wait` 不报错，照 Go 的 detached ctx 分支）；状态 `wait/scaned/confirmed/expired`，`confirmed` 带 `bot_token/ilink_bot_id/ilink_user_id(+baseurl)`。端点响应照 gin.H（map）→ **键按字典序**：取码 `{data:{qrcode,qrcode_url}}`、轮询 `{data:{status}}` / `{data:{baseurl,credentials,status}}`；失败 500 固定文案、缺 qrcode 400；bean 缺位时保留 W5γ2 接缝文案（不阻塞装配）。**注**：`/wechat/qrcode` 成功/失败与 `/qrcode/status` 出站两路 golden 刻意未录（W5γ2 备案），故端点侧以直连单测锁定响应体 |

| mattermost（outgoing webhook + REST） | `im/mattermost/{MattermostAdapter,MattermostClient,MattermostAdapterFactory}` | 对照 `mattermost/{adapter.go 357, client.go 245, factory.go 43, form_parse.go 27}`：入站体**三支**（`application/json` 含 `+json` 后缀解 JSON；`x-www-form-urlencoded` **或空 CT** 走表单；其他先试 JSON、token/channel_id 有一个非空才认，否则 `unsupported content-type: X`）；验签 = outgoing token 精确相等（未见配则跳过，走共享 `ImAdapterVerify.mattermostTokenMatches`）；**自环防护** `user_id == bot_user_id` 丢弃、空文本且无 file_ids 丢弃；**线程根三级回落**（`post_to_main` → 空；`root_id` 优先；缺省 `GET /posts/{id}` 查真根，查不到用自身 post_id），落进 extra 的 `thread_root_id`；file_ids 两形态（JSON 数组 / 逗号串），多枚时 extra 拼逗号；发送 `POST /api/v4/posts`（Bearer）+ 403 时给"把机器人加进频道"的提示文案（照 Go）；流式 = 建帖"正在思考..." → `PUT /posts/{id}/patch`（失败只告警）→ EndStream 用累积内容再 patch，**流表无 TTL 回收**（照 Go）；下载名字三级回落（info.name → msg.fileName → fileKey）；工厂**默认 webhook**（与其它平台相反）且非 webhook 报错、`outgoing_token` 必填、`site_url` 校验（必填 + http(s) + SSRF 白名单提示文案照抄） |

| yunzhijia（webhook + websocket） | `im/yunzhijia/{YunzhijiaAdapter,YunzhijiaLongConnClient,YunzhijiaSign,YunzhijiaUrl,YunzhijiaTypes,YunzhijiaAdapterFactory}` | 对照 `yunzhijia/{adapter.go 610, websocket.go 337, types.go 108, url.go 103, factory.go 77, sign.go 32}`：验签 = `sign` 头（三形态大小写）+ `Base64(HMAC-SHA1(secret, robotId,robotName,operatorOpenid,operatorName,time,msgId,content))`，定长比较，未配 secret 跳过；解析只认 `type==2`，**必须 @机器人**（内容剥 `@名字`〔其后须空白/`:`/`：`/`,`/`，`〕→ 回落 `notifyTo`/`desc.at`），空内容且无内嵌图丢弃；ID 回落链（operatorOpenid→operatorOid→openId→senderId→operatorId→operatorUserId）+ 线程根 `replyRootMsgId` 回落 `msgId`，extra 带 robot/group/operator/time（图片另有宽高）；发送 `POST send_msg_url`（msgtype=2 + `param{formatType:markdown}` + 有 msgId 时 `paramType=3` 带引用摘要与人名；`group_type=3` 不发 notifyParams；**无引用且显式关掉 markdown 时整体不出 param**——照 Go 的 opt-out）；下载 `downloadfileOpen?fileId=`（app access token 缓存留 60s 余量、`expireIn` 缺省 3600）+ **手动 ≤1 次重定向**（逐次重校验宿主、token 只带首发）+ 32MiB 上限 + 文件名 CD 优先、无扩展名按 CT 补 jpg/png/gif；出站端点校验（https + 允许后缀 + 公网 IP，IP 字面量/localhost 拒）；WS：`wss://<host>/xuntong/websocket?yzjtoken=…`（由 send_msg_url 推导），心跳 15s/读超时 45s/**坏帧上限 3**/队列 64，重连表 `[1,2,5,10,30,60]s` 且活过 60s 清零；帧分类（裸/JSON 串 ping·pong、**先试业务消息**〔六字符串字段 + type/time 整数〕、`robotMessage` 信封取 `msg`、`directpush`/`msgchg` 且 `needAck` 回 `{"cmd":"ack","seq":N}`）；工厂 **默认 webhook**，`send_msg_url` 必填、`timeout_seconds` 数字或数字串（缺省 10s）、`allowed_webhook_host_suffix` 必须非空。差异备案：Java 无自定义拨号器 → 连接前解析校验公网（`allowPrivateHosts` 为测试口）；读超时改由心跳线程判"45s 未收帧" |

**待办（W5γ3 九渠道全部落地）**：无。

## 0.-14 同日批次台账回填（2026-09-24：追踪 / 图库 / 评估 / 共享 agent / 标签 / 一致性）

> 本节为**回填**：2026-09-24 的 A3 之前的几笔提交当时只写了提交信息、没同步本文档
> （已核对：这些提交未触碰 `docs/`）。内容按提交信息与当轮验收记录整理；
> 明细以各自提交信息为准（都很详尽）。

| 提交 | 批次 | 要点 | 规模 |
|---|---|---|---|
| `dbf4cff` | 追踪 C-1（基建） | langfuse OTLP 链路：span→OTLP 渲染（hex→bytes id、属性全 string、RecordError→exception 事件、错误→Status{ERROR}）、`POST {host}/api/public/otel/v1/traces`（protobuf 体 + Basic + `x-langfuse-ingestion-version:4`）、批处理/配置/注册 | 35 文件 / +4656 |
| `50d359f` | 追踪 C-4/C-5（接线 + 验收） | 30 处 `InjectTracing` 对应物（载荷平铺 `lf_*` 五键）+ 8 处显式 span（`qa.setup`/`pipeline.*`/`retrieve`/`follow_up.suggestions`/`skill.install`/`skill.maintenance`/`sandbox.collect_artifacts`）+ `RetrievalObs.summarizeRetrieveOutput` 移植 + 端到端 stub OTLP 收集器（真 HTTP → `parseFrom` 断言跨线程续接） | 18 文件 / +550 |
| `87a82fc` | 评估执行体 | 9 个指标计算器（Precision/Recall/NDCG@3,@10/MRR/MAP/BLEU-1,2,4/ROUGE-1,2,L）+ `MetricSegmenter` 接缝（默认二字滑窗，真实 jieba 待接入，已备案）+ `metric_hook`/数据集执行/段落同步建索引 | 20 文件 / +1805 |
| `c6c4ffd` | 图库面（D） | `Neo4jGraphRepository` 三方法（`AddGraph`/`DelGraph`/`SearchNode`，Cypher 逐字照抄：`apoc.merge.node` + `apoc.coll.union` 写、`apoc.periodic.iterate` 删、一跳子图 CONTAINS 检索、ENTITY 前缀与连字符换下划线）+ `chunk:extract` 写入链 + 删除接线 + 自检 | 19 文件 / +1569 |
| `9cd5af2` | 共享 agent（读面） | `SessionLookupScope` 由空标记转真：QA 触发前打标 + 三条派生线程 replay | 4 文件 / +76 |
| `0d0b89c` | 共享 agent（范围） | `@mention` 收敛（仅"agent 租户 ≠ 会话租户"生效）：KB 按允许集过滤、知识按所属 KB 判定、tag 同规则；共享 KB 并入 | 2 文件 / +257 |
| `a4b6655` | 标签删除回收 | 向量索引回收由 no-op 改真删（100/批）+ 标签下文档批量删除接线 | 3 文件 / +101 |
| `5bea818` `089915b` | 一致性 / wiki 生成链 | wiki 图片富化生产 bean、IM 渠道清理、技能进度订阅、chunk 清理；wiki 生成链路接线 + 追踪接入 | 见提交信息 |

**验收**：各批次按模块分批回归绿（追踪 36~37、session 375、wiki+knowledge 747、sandbox 252 等，
见各提交信息与 `08-storage-a3.md` 的汇总表）。

## 0.-13 存储 provider 层（A3，2026-09-24——「云 provider SDK 层未翻译」从缺口清单划掉）

**做了什么**（五笔提交，明细与坑见 [known-issues/08-storage-a3.md](known-issues/08-storage-a3.md)）：

- **批次一** `6893a80`：provider 接口 + local 后端 + S3 协议族（s3/minio/obs/ks3）+
  `FileServiceFactory`（八个 provider 的完备性校验文案照 Go）+ 租户级回退；
- **批次二** `54a544f`：oss/cos/tos 三家**厂商原生 SDK**（阿里云 3.18.1 / 腾讯云 5.6.227 /
  火山 2.9.19），对象名布局、路径形态、临时桶、预签名 24h、服务端拷贝、跨后端拒绝逐项照抄；
- **A3-3 接线** `6c46f96`：`storageurl` 两个窄口首次有生产实现（`StorageUrlWiringConfig`
  的进程级默认服务 + `FileserveStorageBackendResolver`），`resource://` 手柄从此能派生
  `/r/<token>`；三处 `currentTenant()` 由恒 null 改为按 `TenantContext` 取实体；
- **A3-3 尾批** `77f5865`：知识**上传/读取/删除**走租户 provider 层（`TenantFileStorage`
  统一入口；本地 `resource://` 契约逐字节不变，云租户真落对象存储）；
- **A3 收尾** `ae62567`：FAQ 失败明细 CSV 走 `SaveBytes(temp=true)`（**全项目首次用到临时桶**）、
  skill 归档新增 `TenantSkillBundleStore`（本地委托）、环境投影补齐 cos/tos/oss/obs、
  `SafeFileName` 纠正为 Go 的 `Base(Clean(name))`（目录部分丢弃而非拒绝）。

**坑（最贵的四条）**：TOS SDK 版本 2.7.2 不存在且输入类是 V2 命名 + `HttpMethod` 是 String 常量
接口；COS `CopyObjectRequest` 四参构造靠 `javap -c` 字节码才确认"源在前"；`@JsonUnwrapped`
不支持 record 的 Creator 参数（追踪载体改为平铺 5 键）；`local://` 是 provider scheme 却归本地盘
（知识合同测试抓回 500）。

**验收**：storage 78 / storageurl+session.controller 274 / knowledge 210 / datasource 921 /
sandbox 252 / session 375 全绿；**尚未覆盖**真云连通（需凭据）与环境投影的 env 分支。

**剩余存储话题**：云对象整对象入堆（Go 流式）、OSS 大文件未走分片 Uploader、local 两支实现收敛——
三条都已备案在 08 分片，均需独立批次。

## 0.-12 全量回归 + 真实 Docker 排水 E2E（2026-09-24——验证面收口 + 一个真缺陷）

**做了什么**：①**全量分批回归**（本轮多批改动共享面后的全面排查）：8 个批次
~4,100 用例全绿（明细见 known-issues/06 尾部）；②**真实 Docker 产物排水全链
E2E**（ArtifactDrainDockerIT，门控同既有 Docker IT）：真容器 → 生产绑定形态 →
ArtifactCollector → 装饰存储（resource://）→ 磁盘回环/去重/引用历史——§0.-9 的
验证缺口关闭。**IT 抓回一个真生产缺陷并已修**：ResourceReferences 引用扫描正则
漏 base64url 的 `-_` 字符（约一半 handle 受影响，答案引用产物时 ReferencedHistory
静默失效）+ 缺 Go 的截断边界检查——已修并补纯单测。**坑**：IT 门控注解改类时勿丢；
resolvePath 返回的是 local:// 作用域路径；22 字符字面量用 repeat 生成。
**运维**：后台 Go/Java 用 nohup 后尽量在同一次调用内完成验证（进程组信号会优雅
杀掉后台服务，macOS 无 setsid）。

## 0.-11 LLM 分路径键序收口（2026-09-24——最后一个已知保真缺口关闭）

**做了什么**：RemoteApiChat 出站序列化按 Go 的**两条真实路径**分流——map 改写路径
（prompt-cache 策略命中）保持 goSorted 字母序；SDK 结构体直出/thinking 包装路径
新增 `structSorted`（openai-go v1.41.2 结构体声明序，包装字段尾随，工具 parameters
子树保留 jsonschema 结构体序=录入序）。Outbound 增加 cacheRewritten 标记分流；
rawPath（finish_reason 分野）不变。**验证**：结构体路径夹具（无 provider 键 +
thinking_control）双端全 body 两轮 0 差异（Go 顶层 model/messages/…/enable_thinking
尾随、tools type 先行，Java 逐字节跟随）；map 路径夹具两轮 MATCH；RemoteApiChatTest
键序测试重写为双路径断言；llm.chat 156 / agent 380 / chatpipeline 43 全绿。
**排查结论**：全仓无真 TODO；路由缺口仅 /swagger（非目标）；「not available」类
文案全部属备案降级。**仍开放**：真实 Docker drain E2E（install E2E 批）、
EvaluationService（Owner 暂缓）、γ3/W5δ 平台客户端（XDEP）、Redis 限流器/langfuse
（备案降级）。

## 0.-10 agent 出站体 messages/temperature 保真（2026-09-24——A/B 升级全 body 对拍）

**做了什么**：`ab-tools-web.sh` 升级为**全请求体对拍**（标题轮+tools 轮，掩
UUID/TS）后，用带 KB+rerank+temperature 的夹具复现并收口 2a~2c 的全部 messages
残留：①**RAG base 模板缺失**（862 字符）——引擎 setAppConfig 塞空配置，改为
loadAgentSystemPromptTemplates() 装 vendored yaml（有 KB→rag 模式，无 KB→pure）；
②**kbInfos 占位**——翻译 resolveKBAndDocInfos/getKnowledgeBaseInfos（真实
name/description/docCount/最近文档 top10/capabilities，IsTemporary 跳过，失败回落
ID-only）+ getSelectedDocumentInfos（@ 提及）；③**temperature 多发**——Java 缺省
0.7 改 0（Go 零值 omitempty 整键省略；内建的 0.7 来自 presets yaml 显式配置）。
**验收**：带 KB 夹具双端全 body 两轮逐字节一致（tools 轮 30813 + 标题轮 449 字节）；
无 KB 夹具同 MATCH；agent 380 / session 366 / chatpipeline 43 全绿；夹具全退。
**顺带**：PluginSearch.effectiveWebSearchConfig 补拷 apiKey（c1a0528）。
**仍开放**：SDK/map 分路径键序（LLM 批）；真实 Docker drain E2E（install E2E 批）。

## 0.-9 存储写字节面 + ArtifactCollector 生产装配（2026-09-24——§0.-2 剩余 1 收口）

**做了什么**：Go `file/local.go` 写面 + `file/resource_catalog.go` 装饰器 +
`resource.go Register/Bind/MarkDeleted` 全量翻译。`LocalFileContentService` 补
SaveBytes/DeleteFile（exports 目录 + 纳秒唯一名 + SafeFileName）；新接口
`WritableFileContentService`，装饰器实现之（物理落盘 → SHA-256 → 资源注册 →
resource:// 手柄；删除 = 物理删 + 软删）。`ResourceCatalogService` 补
register/bind/markDeleted，`ResourceRepository` 补三写方法（H2 无 ON CONFLICT →
吞 unique/duplicate/primary key 冲突）。
**ArtifactCollectorWiring**（对照 container Provide）：字节面 = 全局装饰服务，
产物存储 = message 仓储投影，绑定 = catalog::bind；collector 按回合构造
（Go 是进程单例——bound manager Java 按回合解析），**沙箱解析惰性到首次列文件**
（首版回合开始即解析、可能提前 provisioning，自查改掉）。**drain 点**接进
AgentStreamBridge 完成段（collectWithNotify + artifacts_pending 事件 + 引用改写 +
ReferencedHistory + clarify，collector 为 null/降级时与 Go 同形）。
**AgentWebPages 生产 Store/Binding 接上**（§0.-8 残留 ① 收口）。
**坑**：resolvePath 返回 provider 作用域路径（local://…）不是文件系统路径——归一
必须走装饰服务；location hash 输入也是该形态。
**验收**：StorageWriteFaceContractTest 6 + AgentWebPagesStoreTest 4；storage 55 /
session 366 全绿。真实 Docker drain E2E 随 §0.-2 剩余 2 顺带验证。

## 0.-8 agent 工具接线·切片 2d（2026-09-23——web_search/web_fetch 新翻落地，Unknown tool 清零）

**做了什么**：`WebFetchTool`/`WebSearchTool`（agent/tools）全文新翻（Go
web_fetch.go/web_search.go；此前 registerTools 对这两名只落 Unknown tool）。
schema/描述按 Go GenerateSchema 实录逐字节钉（描述的 `%d` 注入 maxResults）。
接线三处：registerTools switch 两件、`AgentToolBackends.createWebTool`（桥真实
WebSearchService + TenantService 落 Effective 配置打底）、**registerWebPageFiles**
（agent_web_pages.go：WithPageReader 共享快照 + read_file 缺席补注册——A/B 抓回
Go 在 web 启用时经此注册 read_file；存储写字节面走 AgentWebPages 的
Store/Binding 接缝，缺省 null = Go save-failure 分支，生产实现随存储写字节面批）。
**A/B 抓回四处真缺陷已修**：①thinking 描述两处行尾双空格被文本块剥掉（4.6b 潜伏，
`\s\s` 修复——注意 `\s` 行要放公共缩进上）；②getTool 的 Go err 检查翻成 Java 抛
异常未捕获 → web 开启 QA 恒败；③completeStore 漏摘 inflight → LRU 逐出页读到过期
快照；④schema 字面量多一个闭合括号且 Jackson 静默吞尾（教训：schema 测试必须断言
根键数，两份同样错的字符串会互相印证）。
**线格式分路径备案**：Go 出站体分 SDK 结构体序 vs 裸 HTTP map 序——model 参数带
`"provider":"openai"` 才走 map（字母序+prompt_cache_key）；Java goSorted 恒 map 序
只覆盖后者，SDK 路径键序随 LLM 批收口。
**验收**：`scripts/ab-tools-web.sh` 双端两轮 tools 段 **18825 字节逐字节一致**
（5 件 read_file/thinking/todo_write/web_fetch/web_search）；新增
WebToolsRecordingTest 18 用例；agent 380 / session 362 全绿；夹具全退。
**残留**：AgentWebPages 生产存储接缝、SDK 路径键序、PluginSearch
effectiveWebSearchConfig 漏拷 apiKey（chatpipeline 既有面备案）、照旧 messages/
temperature。**agent 工具接线族到此收口**——下一步候选见 §3。

## 0.-7 agent 工具接线·切片 2c（2026-09-23——wiki 十件落地 + 出站键序一次性收口）

**做了什么**：
- `AgentToolBackends` 新增 `createWikiTool`（对照 `agent_service.go` L1093-1116 的十个构造点）
  与 `wikiPages()`——`WikiSupport.WikiPages` 接缝桥到真实 `wiki.service.WikiPageService`。
  Go 语义翻译三处：① `repository.ErrWikiPageNotFound`（Java 侧是
  `WikiPageNotFoundException`）→ 接缝契约的「返回 null」，其余异常照抛；② 写归因
  `types.WithWikiEditSource(ctx, agent)` → `WikiEditContext.callWith/runWith`；
  ③ `time.Time` → Go `json.Marshal` 的 RFC3339Nano 文本（**尾零连小数点一起裁**，
  Java 的 `ISO_OFFSET_DATE_TIME` 会补齐到 3/6/9 位）。
  另：`createPage` 在接缝处**强制新 UUID**——Go 两个调用点都用字段字面量建页（ID 恒空），
  而 `wiki_rename_page` 在 Java 侧走 `PageView.copy()` 会带上旧 ID，不清就撞主键。
- `registerTools` 补齐 Go L886-895 的 scope 解析：`dedup → newWikiScopesFromSearchTargets →
  **用 scope 结果重建 wikiKBIDs**`，`hasWikiKb` 据窄化后的清单判定（畸形空 target 不会
  变成整库授权）；一个引擎一个 `WikiRouteResolver` 实例共享给十件（Go L870）。
- **A/B 抓回的最大一处**：Go 的整个出站 chat 请求体是经 **map** 序列化的，
  `encoding/json` 对 map 一律按 key 字节序输出——所以**每一层对象**都是字母序
  （顶层 `max_completion_tokens/messages/model/…`、messages 元素 `content/role`、
  工具 schema `properties/required/type`）。此前各切片手工修的是「工具字面量键序」，
  本批改在 `RemoteApiChat.goSorted()`（序列化前递归重排）一处收口，
  **同时清掉备案残留「外层请求体键序」**，10 件 wiki schema 无需逐个改。

**验收**：`scripts/ab-tools-wiki.sh`（新增，双端 stub 对拍 tools 段）——同注册 **11 件**
（KB 五件里的 knowledge_search + wiki 十件）、**tools 段 10692 字节逐字节一致**；
修前 A/B 报出十件 schema 全部键序差、修后 MATCH，顶层键序与 Go 完全一致
（仅 `temperature` 一项残留）。回归：wiki 554 / agent 362 / session 361 / llm.chat 155 全绿；
新增 `AgentToolBackendsWikiTest`（H2 真 service：null 翻译 / 往返 / 新 UUID / edit_source /
issue 时间文本 / 十件可构造）+ `RemoteApiChatTest.outboundKeysAreSortedLikeGoMap`。
夹具已全部回退（wiki_enabled、temp agent、rerank stub 行、六个临时会话）。

**残留（待专项）**：① messages 内容（system prompt 缺 Go 的 KB 使用段约 860 字符 +
user 缺 `<runtime_context>` 块）、`temperature`（Java 0.7 / Go 不发）照旧；
② `wiki_read_issue` 输出里 `suspected_knowledge_ids` 空值 Java 渲染 `[]`、Go 是 `null`
（Go 侧 nil slice 经 jsonb 'null' 往返仍是 nil）——工具**结果**字节差异，非本切片主题；
③ agent 建的 wiki 页 `tenant_id=0`（Go `wiki_write_page.go` 的 struct 字面量不带 TenantID，
仅 rename 带；Java 逐字对齐，读路径不按 tenant 过滤所以无感）；
④ `SkillInstallPipelineImpl:1123` 同款 governor 漏传。**下一步 = web_search/web_fetch**
（Java 无工具类，属新翻：Go `web_search.go` ~80 行 / `web_fetch.go` ~77 行 + 描述/实录）。

## 0.-6 agent 工具接线·切片 2b（2026-09-23——data_schema 接线；wiki/web 仍待）

**做了什么**：`data_schema` 接进 `createTool`（KnowledgeLookup/ChunkLister 走
`KnowledgeService.getKnowledgeByIdOnly` + `ChunkRepository.listPagedChunksByKnowledgeId`，
tenant 取 knowledge 行；`scopeEnforced` 时挂 `SearchAuth.authorizeKnowledgeInSearchTargets`
授权器）；schema 字面量按 Go 实录修正键序。

**验收**：A/B 双端同注册 **8 件**（KB 五件 + conversations + database_query + data_schema）→
**tools 段 14675 字节逐字节一致**；agent 362 / session 349 全绿。

**已完成（切片 2c，见 §0.-7）**：**wiki 10 件**接线 + 门控 + A/B 已落地。
**仍待**：**`web_search`/`web_fetch`**（Java 无工具类，属新翻，Go ~80/77 行 + 描述/实录）。

## 0.-5 agent 工具接线·切片 2a（2026-09-23——会话/记忆/DB 三件 + 记忆闸门回收）

**做了什么**：`search_conversations`（owner 装配期捕获 + `MessageService` owner 显式重载）、
`search_memory`（**闸门回收**：Go 是「先摘再按 MemoryAvailable 挂回」，Java 此前只摘不挂——
记忆开着也永远没有该工具）、`database_query`（JdbcTemplate 行扫描 + 值类型约定）三件接进
`createTool`；A/B 抓回两件 schema 字面量与 Go 不一致（已修；`search_memory` 的 schema 是 Go
手写字面量，待记忆开启部署复核）。

**验收**：双端同注册 **7 件**且 **tools 段 14285 字节逐字节一致**（`search_memory` 因双方
记忆闸门同判为关而都不注册——闸门一致）；agent 362 / session 349 全绿 + 映射钉/H2 行扫描用例。
细节见 known-issues/06-wave-5.md 尾部。

**下一步 = 切片 2b**：`data_schema`（ScopeAuthorizer 装配）+ wiki 10 件（WikiPages seam +
WikiSupport.newWikiScopes* + WikiRouteResolver）+ web_search/web_fetch（Java 无工具类，新翻）。

## 0.-4 agent 检索工具族接线·切片 1（2026-09-23——KB 五件接真实服务，Unknown tool 清零）

**做了什么**：`registerTools` 新增 knowledge_search / grep_chunks / list_knowledge_chunks /
query_knowledge_graph / get_document_info 的构造与注册（此前恒 `Unknown tool`）。新增
`session.service.AgentToolBackends`（9 个窄 seam → HybridSearchService / KnowledgeService /
ChunkRepository / Reranker / ImageInfoEnricher / JdbcTemplate；grep_chunks SQL 按 Go 整段移植，
方言 PG `~*` / 通用 REGEXP / H2 `REGEXP_LIKE`）；修 `KnowledgeSearchTool` seam 缺口
（`hybridSearch` 补 kbID 参数）；修 `SessionAgentQaService.chatModel` 的 governor/ollama
漏传（并发闸门装配后 agent 路径 LLM 调用必 NPE，被分派 bug 掩盖）。

**验收（双端 stub 实弹 A/B）**：`stub-llm-server.py` 增 `STUB_DUMP_DIR`（默认关闭）；
双端同指 stub（`SSRF_WHITELIST_EXTRA=127.0.0.1`）跑同一 smart-reasoning agent →
**tools 段 9685 字节前缀逐字节一致**（含 schema/键序/HTML 转义，A/B 抓回四处已修）。
回归：agent 362 / session 346 / knowledge 193 / llm.chat 154 + 新增两测试类。

**残留（待专项，非本切片）**：外层请求体键序（Go 字母序）、messages 差异（system prompt
缺 Go 的 KB 使用段 + user 消息缺 `<runtime_context>` 块）、`temperature`（Java 发 0.7 /
Go 不发）、`SkillInstallPipelineImpl:1123` 同款 governor 漏传。**下一步 = 切片 2**
（search_conversations SQL / search_memory / database_query+data_schema / wiki 10 件；
web_search/web_fetch 缺件单列）。细节见 known-issues/06-wave-5.md 尾部。

## 0.-3 agent 模式分派修复（2026-09-23，走查抓回——「智能推理」agent 恒走 RAG 快答）

**做了什么**（两处根因，一次修；此处「走查抓回」= 占位全面复查的最高危项，实弹对拍坐实）：
①`SessionKnowledgeQaService.isAgentMode` 谓词抄错：`"agent"` → Go 语义
`"smart-reasoning"`（`types/custom_agent.go` L553-556）；5 处消费点同源（QA 分派 /
本地浏览器门控 / agentEnabled 持久化 / 两处 prompt 模板选择）。
②`CustomAgentService.virtualAgent` 合成内建 agent 行不落 config 字符串 → 无 DB 行的
租户运行时拿到空配置（`resolveAgent` 只取 row 再 parse）；合成行补 `setConfig(...)`
（对照 Go `GetAgentByID` 的物化 agent）。

**验证**：新单测（谓词取值域）+ AgentContractTest 增「虚拟行 config 非空」钉；
session 339 / agentm 7 / agent 362 / org 2 / knowledge 193 定向回归全绿；
**双端活进程 2×2 实弹对拍**（同库同会话同请求体）：`builtin-smart-reasoning` 双端
`stage=agent_execution`（修复前 Java 为 `knowledge_qa_execution`）、
`agent_mode="agent"`（Go 视为非 agent）双端 RAG 流——分派完全对齐 Go。
细节见 known-issues/06-wave-5.md 尾部；漏网主因 = 无 golden 覆盖 `agent_execution`
（4.6d A/B 场景全避开了已解析的真实 agent）。

**立即遗留（建议下一批）**：agent 引擎的**检索工具族未注册**
（`SessionAgentQaService.registerTools` 只构造 thinking/todo_write，knowledge_search /
grep_chunks / list_knowledge_chunks / query_knowledge_graph / get_document_info 落
`default → "Unknown tool"`；工具实现（4.5b）与检索执行面（3cb4b2e）均已就位）——
分派修好后 agent 真实跑引擎但无 KB 工具，修完前端 agent 会话会出现
`Unknown tool: ...` 告警可作入口锚点。

## 0.-2 沙箱/技能执行面工程（2026-09-23，批 A→D2 落地——「技能与沙箱」从禁用到全链可用，两笔提交）

**做了什么**（commits `0352cb8` + `c1a8442`，61 文件 +16.5k 行；前端品牌改动为用户自己的工作树状态，勿动勿提交）：

- **批 A 能力与开关**：WebConfig 的 sandbox 能力翻真（原 `false` 是"波 3 未翻译"的过时部署标记）；SystemController 的 docker 活值改读 `SandboxBackendPolicy.dockerBackendEnabled()`（原硬编码 false）；SystemSettingService 补 `sandbox.docker_enabled` → SandboxBackendPolicy 推送桥（preload/Update/Reset）。
- **批 B 技能源**：`SkillSourceFetcher`（URL 下载步全文：hop 递归上限 3/JSON handoff/zip-markdown 判别/大小限额/SSRF），替换 "skill source fetch is not available" 占位。
- **批 C 执行面**：`SandboxSessionClient` 执行契约 + `DockerSandboxClient`（docker-java 3.7.1 **zerodep** transport：容器创建/exec 流式/文件族/ContainerCommit 快照/idle 回收/模板目录）+ `SessionBoundManager`/绑定存储(Redis+内存)/生命周期协调/一次性执行器 + `TenantSandboxResolverService` + `SessionSandboxExecutionService`（resolveForExecution/shell 与文件工具注册/initializeSkillsManager/holdSandboxTurn 回合租约）+ 附件 staging（session_attachment_staging.go 全文）+ SessionAgentQaService 接线（skillsForRun 注入本轮镜像技能、staged 提示注入查询、try-with-resources 租约窗口）。
- **批 D1 支撑面**：`SkillInstallTranscript`（事件回调/渐近进度 35+44·(1−e^(−k/12))/持久化=Redis 事件流+messages 两行——**没有 skill_install_transcript 表**，任务书有误）/`InstallSteerSink`（UUIDv5(SHA-1, OID) 确定性 ID，Go 实录向量钉死）/`SkillInstallPipeline` 接缝。
- **批 D2 管线本体**：`SkillInstallPipelineImpl`（~1700 行，runInstall 8 步全链：行所有权+虚拟线程心跳/维护会话/播种/installer agent 循环（builtin-skill-installer+特权 shell+steer 消费+修复轮）/manifest+env 声明/scratch 清理/快照台账先行+ContainerCommit/指针切换指纹复核/旧快照废弃/进度 100%）+ `InstallEngineFactory` 接缝（sandbox 接口 + session 实现：安装模式工具装配；循环依赖经 ObjectProvider）+ `SkillEnvDeclaration`；TenantSkillService 的 catalog install 后台受理改委托管线（虚拟线程）。
- **快照台账补列**：`parent_snapshot_id`/`planned_name`（迁移 000086/000088 已在 PG 与 Flyway 同步；实体字段+mapper INSERT+TestSchema 对齐）——废弃 building 行从此可按 planned_name 对账回收。

**验证**：四域回归 304 绿（sandbox.runtime/service+session.service+SystemContractTest）；**真实 Docker 集成测试 4/4**（OrbStack）；真 PG 快照台账两列确认；bootRun 冒烟 200（capabilities/skills-catalog）。

**关键教训（新会话必读）**：
1. **docker-java 传输选型**：httpclient5 传输的 exec hijack **不传输输出帧也不传 stdin**（与版本无关，3.4.0/3.7.1 双探针实锤）——必须用 zerodep transport；且 zerodep 的 **stdin 写端无半关闭**（EOF 不达，读 stdin 的命令挂死到 timeout 137）——WriteFile 走 PutArchive（tarSingleFile 最小 ustar）、exec stdin 走"种子文件+重定向"绕开 hijack 写路径。
2. **Go `%q` 动词**：Java String.format 不认（UnknownFormatConversionException）——SessionSandboxPaths 5 处已修；引号用 quoteGo 参数自带。
3. **任务书/测试预期会写错，Go 源+实录才是准绳**（本轮三例：dockerSanitizeImageName 对空格是丢弃而非转分隔符、isDirectArchivePath 只认 .zip/.tgz/.tar.gz/.tar/.md 后缀——非归档后缀走 registry 改写、UUIDv5 实录向量）。
4. **代理交付后立即跑测试可能撞陈旧增量编译产物**（4 条假失败在强制重编后消失）——验收先 `--rerun` 或 clean compile 确认。
5. **本机 Docker 是 OrbStack**：`/var/run/docker.sock` 符号链接失效，集成测试必须 `DOCKER_HOST=unix:///$HOME/.orbstack/run/docker.sock` + `WEKNORA_SANDBOX_DOCKER_ENABLED=true` + `WEKNORA_SANDBOX_DOCKER_IT=true`。
6. gradle 增量编译会漏报部分类型错误（RemoteSessionLifecycle 5 处漏报案例）——验收用独立全量 javac 或 --rerun。

**当前能力（全链可用）**：技能目录注册（文件上传）→ 安装到 docker 沙箱配置（LLM 驱动依赖安装 + 镜像快照）→ 对话中选技能真实执行（shell_exec/文件工具/凭据三层注入/附件 staging）。

**剩余（按优先级）**：
1. **ArtifactCollector 生产 bean 装配**：ArtifactFileStore=存储写字节面（StorageFileResolver/TenantStorageService 一族）、SessionArtifactStore=MessageRepository 包装（Go NewMessageRepoArtifactStore）、ResourceCatalogBinder=ResourceCatalogService；drain 点=agent_stream_handler.go L720-734 等价的引擎完成处（best-effort + emitArtifactsPending）；source 适配器 `SessionBoundArtifactSource` 已备。
2. **真实 LLM install E2E**：建 docker 沙箱配置（host 留空自动探测；OrbStack 注意 DOCKER_HOST）→ 上传技能 → 安装（install-events SSE 进度 + 快照 commit）→ 对话执行（shell_exec + 产物）。管线全链就绪，无已知阻塞。
3. **批 E 终端 PTY**（cube/e2b，Go cube_terminal.go/e2b_terminal.go ~400 行；docker 无 PTY）：注意 zerodep 传输 stdin 无半关闭对 PTY 双向流的影响需先评估。
4. SkillProgressStore 的 pub/sub 实时通道（现 no-op subscribe）+ langfuse span（备案级降级）。
5. 评估执行 E2E 前记得：docker 开关现在走 system_settings（DB 层）即开即用，无需重启。

---

## 0.-1 占位收口批（2026-09-23，全仓占位排查 → 十处缺口全修——「路由在、执行体占位」清零）

**排查**：按 HANDOFF 全仓扫描占位标记（占位/TODO/not translated/恒 null/固定 401/
"not available yet"）+ 逐条对照 Go 原文与 known-issues 备案，区分「备案理由已过期的
真缺口」vs「理由仍成立的在册降级」。**修复 10 项**（每项独立提交，均带测试）：

1. `9eec8a4` **会话删除三件套**（波 0/1 备案「随波 2/3 收口」未回补）：
   browserSkill.Forget/ForgetAll 接线（Go handler.go:385/471/509）+ 新增
   WebSearchTempKbStateService（web_search_state.go 全文，Redis tempkb:<sid>）+
   SessionTerminalService.destroyBoundSandbox（session.go L670-718，policy=nil 跳过
   kill switch；provider 会话级销毁仍 XDEP seam）。
2. `bfdd1d1` **AutoTagProvider 生产实现**：KnowledgeTagAutoTagProvider
   （FindOrCreateTagByName 语义），NoAutoTagProvider 占位删除（knowledge_tag 模块已落地）。
3. `a18bb23` **追问建议 LLM 生成步**（G3 备案降级，依赖已随 models/{id}/debug 就位）：
   generate/generateWithModel/generateFromKnowledge/buildGenerationContext 全族
   （message_suggestion.go L257-778）+ CustomAgentService.getKnowledgeSuggestedQuestions
   （includeCurated=false 变体）+ agent 租户切换 + token 用量回填。
4. `b02540d` **Artifact 版本澄清**：ArtifactVersions（artifact_versions.go 全文）+
   MessageService.clarifyReadArtifactVersions 全量接线（message_artifact_versions.go；
   原「随 G6 落地」TODO）+ AgentStreamBridge 补 Go L751-760 澄清点（collector seam）。
5. `ab902bd` **API 主体解析**（排查新坐实：波 2 只翻了配置面，中间件消费面恒回落）：
   resolveAPIPrincipal 两模式（direct_header/signed_token，手写 HS256 HMAC——golang-jwt
   不限密钥长度而 jjwt 拒短密钥）+ 首位用户路径（UserService.getUserByTenantIdFirst）
   + 401 文案逐字对照。
6. `45f8fc4` **消息搜索向量路径**（备案「随 retrieval 收口」已过期）：vectorSearchViaKB +
   rerankResults + GetMessagesByKnowledgeIDs（JOIN sessions，方法级 @Results 防 jsonb/is
   前缀陷阱）+ mode=vector 失败上抛/hybrid 降级，逐字对照 Go。
7. `0f66ff5` **Wiki 共享访问**（阶段 3 差异 3，kb_shares 已落地未接入）：
   requireWikiKB 接 org-share/shared-agent 两条读授予 + 写路径 Editor 级 org-share
   （Go rbac.go L226-230 透传 + KBAccessWrite(Editor)）——agent 报告的「写路径收紧」
   经主会话对照 Go 定夺修正为对齐。
8. `347eb18` **sandbox_file_progress**（备案「随阶段 7」，依赖波 3 已完成）：
   SandboxFileProgress 全文 + openai_stream.go L561-593 两处 emit 接通。
9. `7744fab` **ImageResolver 装配点**（备案 #6）：ChatLocalImageResolverWiring
   （container.go L489-536）+ **SsrfGuard 白名单泄漏修复**（snapshotWhitelist/restore
   + ImageResolverTest/RemoteApiChatTest 泄漏点——W5a「互踩专项」in-scope 实例，
   该泄漏此前让 storage 契约测试 2 条预存假红）。
10. 本批收尾：**DataSourceHttpContractTest 类顺序脆弱测试修复**（@BeforeAll 设的
    loopback 白名单被懒加载上下文的 ApplicationReadyEvent 预载覆盖——DB 值
    ["198.18.0.0/15"]；在 @BeforeEach 重设即稳。**基线 worktree 实测 a7aeacb 同挂，
    预存问题非本批引入**）。

**验收**：批次回归 4284 测试四批跑（B1 863 / B2 全绿 / B3 1717 / B4 全绿），
**仅剩 2 条失败均为 fake-ip 环境锚测试**（auth kvParserMatchesGo + sandbox
slk-catalog-register-src：golden 文案编码了 Clash TUN 198.18.0.0/15 拒绝，代理离线时
mineru.example.com 无解析落 DNS-failure 文案；代理恢复即绿，测试文件自带环境锚注释）。
bootRun 重启冒烟通过（capabilities/sessions/创建删除 200）。⚠️ 复训两条纪律：
①单发 `./gradlew test` 会撞**确定性的 1581 条 Mockito 自附风暴**（两次全量精确同数），
必须按 B1~B4 分批跑（handoff 旧知识「内存抖动」说 krat——同 JVM 全量对
@SpringBootTest 是结构性炸，分批是正解）；②后台 agent 并发跑 gradle 会顶满内存放大
自附风暴，agent 报告期不要另起全量。

**第二轮复查（同日，95a49c4 + 6e8c6d2）**：全仓重扫「未接线/随波/seam/恒空」后
再修 4 处——①**FAQ 搜索执行面**（SearchFAQEntries L922-1253：双优先级检索 + 命中
回填 + TagName 批补；原「随波 4 收口」备案，检索引擎已落地）+ FAQ KB 活动审计五处
（recordKBActivity 调用点）+ **后台并发闸门装配**（ConcurrencyGovernor 零调用点 →
ModelConcurrencyGovernorWiring，model.max_concurrency DB→env→32，LocalLimiter；
Redis 分布式版仍备案）；②**RbacInterceptor API-key 主体短路**（Go rbac.go L72-78：
角色阶梯不适用机器主体；此前拿影子角色评估会误拒 scoped key）+ **BackfillMissingKey
Hashes 启动钩子**（bootstrap.go L44-52；此前零调用点，迁移旧 Key 永远无法认证）。
两处「待接线」过期文档清除。

**在册不动**（理由仍成立）：provider-XDEP 族
（γ3 九渠道/W5δ 终端/tenant_skill install/VLM ollama+weknoracloud）、DataAnalysis
（DuckDB 依赖）、BrowserSkillManager 执行循环 seam（需浏览器后端）、OIDC enabled 网络步、
Redis 限流器/asynq/readability 降级族、tenant_skill install。
~~EvaluationService 执行步~~ ✅ 2026-09-24 `87a82fc` 落地（九指标 + metric hook + 数据集执行 +
段落同步建索引；jieba 以 `MetricSegmenter` 接缝降级，真实分词库待接入）；
~~langfuse~~ ✅ 2026-09-24 `dbf4cff`/`50d359f` 落地（见 §0.-14）；
~~云 provider SDK 层~~ ✅ 2026-09-24 A3 落地（见 §0.-13）。
**低优先遗留**（退化语义与 Go 的可选接缝缺席分支等价，已注记）：wiki pending-op 的
KB-active 原子守卫（Go TaskPendingOpsKnowledgeBaseGuard 属 knowledge 删除路径可选
接线）、KnowledgeService 复制不携带 wiki/reparse 衍生数据（见 :2473 注释）。

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

**同日手动走查又抓回两处（均已修复，详情 known-issues/06 与 05 尾部）**：
①新会话 QA 的 PluginSearch null addAll NPE（5baf927）；②「测试连接」失败文案对
BizException 直接 getMessage() 带出 `error code: ..., error message: ` 前缀（拆包修复），
并给 RemoteApiChat/AnthropicChat 的 `send request: null`（无消息 IOException）补了
异常类名兜底——该现象大半源于 TUN 代理 fake-IP（198.18.0.0/15）被双端 SSRF 同拦 +
连接池/DNS 腐化，重启即解，属环境教训而非翻译缺陷。
**第三处**：NORMAL 快答路径的完成监听在桥接虚拟线程触发、监听器内才读
TenantContext（必 null）→ `is_completed` 落不了库，追问建议恒 400；连
completeAssistantMessage 的建议线程也漏了 runWithTenant（纪律 #1 漏网分支，
AGENT/stop 路径都有包裹唯独它漏）。已修，e2e 双端复核一致。
**第四处**：QA 错误事件把 `com.ragagent...BizException: error code: ...` 包装前缀
泄漏给前端——Go 发的是 PluginError.Err 内层原文；executeQA catch 新增
errorEventText() 沿 cause 链拆包。A/B 取证 = 双端各指死代理制造同款失败对比
SSE error 事件（方法见 known-issues/06 尾部）。同场环境根因：Gradle 守护进程把
启动 shell 的代理环境变量固化成 JVM proxyHost 属性，Clash 端口一空 Java 全站
LLM 调用 ConnectException 而 Go 直连正常——**「Go 通 Java 不通」先 jcmd 查
proxyHost 再怀疑代码**。
**第五处**：文档上传解析落 failed、error_message 只剩裸 `java.net.ConnectException`
——环境根因是 KB 选了指停摆 stub（127.0.0.1:8181）的种子调试模型 md-emb，代码
DIFF 是 EmbedderClient 让裸 IOException 上抛丢 URL；已按 Go `send request: %w`
形态补 `send request: Post "<url>": <类名>` 兜底（详情 known-issues/03 尾部）。
**第六处**：SSRF 误拦 dashscope 等公网模型域名——IpClass 的 0.0.0.0/8 上界误写
0x0fffffff（实为 0.0.0.0/4，1.x–15.x 整段公网全误判）；同场对照 Go 谓词顺序拆开
UNSPECIFIED/LOOPBACK（0.0.0.0 双端文案此前不同）。新增 IpClassTest 钉边界
（详情 known-issues/00 尾部）。TUN fake-IP（198.18.0.0/15）被拦仍是设计行为。
**第七处（推迟项清零）**：GET /tenants/kv/prompt-templates 落地——Agent 编辑器
打开即 400「unsupported key」。新增 agent.PromptTemplateCatalog（vendored yaml
九文件 + LocalizeTemplates + Go 字段序/omitempty 保真输出），四组语言 A/B 逐字节
MATCH，当初 EXPECTED DIFF 转正为契约断言（详情 known-issues/03 尾部）。
**第八处**：embedding 入库批量硬编码 40 被 dashscope 拒（上限 20）——Go 是
BATCH_EMBED_SIZE env 默认 5；已对齐（含 strconv.Atoi 文案）。教训：stub 能过
≠ 真 provider 能过，批量/限流参数对照 Go 的 env 值（详情 known-issues/03 尾部）。
**第九处**：DB 存的 ssrf.whitelist 重启后静默失效——Go 在 preload（initial
sync）推一次白名单，Java 只在设置变更时推；已补 ApplicationReadyEvent 启动预载。
同场确认检索 0 结果 → 固定兜底回复是设计行为（最高相似度 0.56 < 阈值 0.7，
Go 同库同查询同样空集）（详情 known-issues/03 尾部）。
**第十处（检索恒空三连，走查疑点⑫）**：知识库有内容但工具检索恒空。三层叠加：
①env——cmd/server 不读 .env，RETRIEVE_DRIVER 需真实导出，dev-env.sh 已补
（env_value 改按 key 回落 WeKnora/.env）；②PluginSearch 三处 executor +
PipelineCommon.runParallel/parallelMap 虚拟线程丢 TenantContext（getModelByID
tenantId()=0 → ModelNotFound 静默降级关键词-only），全部补 capture/replay；
③SearchKnowledge 漏 setTenantId → Merge 扩块全跳过；④mergeOrderedContent
裸拼未折叠重叠 → 改调 ChunkSearchUtil.joinChunkContent。验证：knowledge-search
双端 A/B 前 6 条逐字节一致；agent @KB 工具检索 8 条 vs Go 7 条同形态。
残留：尾部集合因 jieba 二字滑窗近似（SearchTextUtil 声明的文档化降级）分叉
（详情 known-issues/06 尾部）。
**第十一处（本批，regenerate-summary 500 = 「阶段 7 占位」补全）**：知识库文档点
「生成摘要」恒 500——regenerate-summary 除无 model 的确定性 400 外是占位（模型已
配置即无条件 500）。route-recon 只对账路由，抓不到「路由在、执行体占位」。已全量
翻译 Go 摘要管线（doRegenerate 状态机 + getSummary + failGeneration + refresh
异步刷新 + controller 200 形态，见 known-issues/06 尾部）。同场对齐**处理管道三个
索引契约点**（索引文本 title 前缀、重处理预清理旧向量行、失败清理）并修正
**VectorStoreService 的 source_id 形态偏差**（原写 "chunk:"+id，Go 是 chunkID 无
前缀 + 问题行 chunkID-qID 折叠——双端共库会出重复向量行）。真实环境验证 200 +
completed + summary chunk 复用 + 向量行形态正确。**同族占位已同批接线**：ChunkService
四处（syncChunkIndex / enqueueSummaryRefresh / regenerateChunkQuestions 的 LLM 步 /
deleteGeneratedQuestion 的向量删除）→ 新积木 `ChunkVectorIndexer` +
`VectorStoreService.deleteBySourceId` + `PromptInstructions` 上提；真实环境三链路
闭环验证（编辑→ready+向量重建 / 生成问题→LLM 3 条+问题行折叠 source_id / 删除→
向量清理+metadata 复原）；踩坑与细节见 known-issues/06 尾部（含 needsEmbedding
**不得**套 isZero→Default 钩子的教训）。
**第十二处（本批，走查阻断回归：creator_id NULL → KB 列表 NPE 500）**：
knowledge_bases.creator_id 为 NULL 的行（dev 库 A/B 种子 BQ Alpha/Beta/Temp）让
`getCreatorId().isEmpty()` NPE → 列表 500。根因：实体 getter 无归一化（Go 非指针
string 恒 ""）。getter 归一化修复 + **A/B 实测**（Go :8080 同库输出 ""）。
**教训：走查期间的提交（c5a34f1 等）若服务未重启，回归潜伏到下次重启才暴露——
提交后应及时重启验证**。
**第十三处（本批，占位扫描 + FaqService 索引/导入执行面接线）**：全仓按占位文案扫描后
的真缺口清单与修复：FaqService 索引族**全链已接线**（创建/更新/删除/字段批量 +
同日续的导入分批循环 `executeImportBatches`；真实环境验证 completed 3/3 +
向量行形态 + success_entries）、updateImageInfo 向量重建（小）、WebSearchProvider
test（小-中）、EvaluationService（大）；其余为 provider-XDEP 设计内降级/deferral。新积木
`FaqIndexRows`/`TenantStorageService`/`VectorStoreService` 扩展（tag_id +
BatchUpdateChunkEnabledStatus/TagID + estimateStorageSize）。**踩坑①**：chunks 表
NOT NULL 列（source_content/last_editor_id/context_header）在 Save 全列语义下需
Go 零值归一（updateChunk 已修）；**踩坑②**：编译/测试后必须重启 bootRun 才能验证
（旧进程撞到已删除方法）。真实验证：临时 FAQ KB 全链路（创建 200 + 向量行形态 /
字段批量同步 / 删除 + 配额回退精确）+ 清理。详见 known-issues/06 尾部。
**测试纪律补充**：全量/多批回归若遇成片的 Mockito「Could not self-attach」，
是内存压力抖动（多守护进程 + bootRun + vite 并存顶满内存），勿误判为业务 bug；
缓解 = 释放内存后重跑。⚠️ 但若 Java 服务正在走查，`./gradlew --stop` **会把
bootRun 一起杀掉**（bootRun 托管在 Gradle 守护进程上）——停完必须
`scripts/java-server-up.sh` 重启，否则前端全部接口 500（2026-09-22 实踩）。
⚠️ 同族第二坑（同日实踩）：bootRun 服务期间跑 `./gradlew test/compileJava`
会**重写 bootRun 正在用的 build/classes 目录**，运行中的 JVM 随即对所有接口抛
`NoClassDefFoundError`（Spring 兜成 500「Internal Server Error」，无业务日志）——
**走查期间要跑测试就先重启服务再测，或测完立即重启**；看到全站 500 +
NoClassDefFoundError 不用查代码，重启即解。

**第十四处（本批，updateImageInfo 向量重建接线 + 读层差异二次修正）**：
`KnowledgeService.updateImageInfo` 的 `updateChunkVector` 占位清除，改真实调用
`ChunkVectorIndexer.updateChunkVector(kbId, updateChunks + addChunks)`（对照 Go
L3099 `append(updateChunk, addChunk...)`）。**踩坑③（读层差异）**：Go 的
`NeedsEmbeddingModel` 判定按调用点分两层——**服务层**（`kbService.GetKnowledgeBaseByID`
→ `EnsureDefaults()`：IsZero（4 字段全 false）→ Default）/ **repo 层**
（`kbRepository.GetKnowledgeBaseByID`：仅 Scan，NULL→Default、显式全 false 保持）。
上一批「统一去钩子」是过修：knowledge 185 绿的同时 kg-image golden 转红
（期望 1007 得 200）。修正后拆 `needsEmbeddingServiceLayer`/`needsEmbeddingRepoLayer`
（`ChunkVectorIndexer`），`KnowledgeService.kbNeedsEmbedding`（regenerate 路径，
Go L2302 服务层）恢复钩子——**updateChunkVector/regenerate 用服务层，
syncChunkIndex 用 repo 层**。判定证据：kg-image golden 的 KB fixture 是显式全
false 策略而实录 1007（服务层钩子存在）；chunk 编辑系列 golden 期望不走进
（repo 层无钩子）。验证：knowledge 185 全绿。详见 known-issues/06 尾部。

**第十五处（本批，WebSearchProvider test 端点接线）**：`doTestSearch` 的
`SEARCH_DEGRADED` 占位（"web search provider test is not available in this
deployment"）清除 → 对照 Go handler L412-431 全量：CreateProvider（失败包
"failed to create provider: " 前缀）→ `search("test", 1, false)`（失败原文透传）→
空结果 → `EmptyTestResults` 文案；三支出口 200 纯字符串。controller 注入
`WebSearchProviderRegistry`（域类/接口同名，用全限定名区分）。验证：websearch
39 全绿（38 golden + 新增执行面回归 `section7_testRealExecution`：stub 三出口 +
调用参数契约）；真实双端对拍（duckduckgo → 均真实出站 200 error 原文；nosuch →
双端同 "not registered" 文案）。详见 known-issues/06 尾部。

**第十九处（本批，span 写入侧全量接线——走查抓回「查看 Trace」按钮不显示）**：
Owner 决策全量翻译，四阶段：①模型 + 仓储（55a224f，upsert 动态列/BFS 级联/
三种 cancel，PG ON CONFLICT + H2 先查后写）→ ②读侧接真实表（d61c02a，attempt
选择/buildSpanTree(rows)/last_error span 优先/spanNodeFromRow 键序）→ ③SpanTracker
全文（879 行对照：openAttempt/beginStage 重入复用/beginSubSpan/end-fail-skip/
lookup/finalize 幂等/abort 平扫/依赖闭包/心跳）→ ④worker 埋点（docreader/chunking/
embedding(或 skip)/multimodal skip/postprocess + finalize；cancel → AbortAttempt）。
已知差异：reparse 的 attempt 分配推迟到 worker 启动（Go 在入口分配随 payload 传）；
span input/output 取关键子集。验证：knowledge 193 + 回归 868 全绿；真实 reparse →
spans 表 6 行（root+5 阶段，multimodal skipped）→ trace.span_id 非空 +
current_attempt=1 → 前端按钮显示。详见 known-issues/06 尾部。

**第十八处（本批，post-process 摘要 fan-out——走查抓回）**：现象 = 「重建知识」
后摘要恒空（首次导入亦不生成）。根因 = Java 缺「处理完成 → summary_status=none
→ post-process fan-out → 摘要任务」链路（对照 finalizeIndexedKnowledgeState +
knowledge_post_process.go L208/L562；任务体 ProcessSummaryGeneration）。修复：
worker 完成时（KB 有 summary model）落 none + 有文本块则调
`requestPostProcessSummaryGeneration`（复用刷新 worker 的重试/吞错语义）。
**条件化取舍**：仅 KB 配 summary model 时推进（进程内 worker 的异步副作用会污染
契约测试 HTTP 快照，3 例实测红）。验证：knowledge 185 绿 + 真实 reparse 摘要
30s 内重新生成。详见 known-issues/06 尾部。

**第十七处（本批，会话标题生成接线——走查抓回）**：现象 = 发消息后标题恒为
"新会话"。根因两处阶段占位：`SessionService.generateTitle` 的 LLM 步抛
"title model runtime is not available yet"；`KnowledgeQaController` 的
GenerateTitleAsync 触发点只打日志（原备案"两侧无 session_title 事件"论断有误）。
修复：generateTitle 全量接线（GetChatModel → GenerateSessionTitlePrompt +
language 占位 → Chat 0.3/thinking=false → sanitizeGeneratedTitle（剥 think 前缀 +
100 码点截断）→ 落库）+ 新增 generateTitleAsync（虚拟线程 + emit session_title
事件，AgentStreamBridge 转发 SSE）；**EventBus 非 Spring bean**（请求级实例）
→ 按 Go 签名传参（注入会 NoSuchBeanDefinition，实测踩坑）。验证：session 270
全绿；真实环境三链路（同步端点 0.9s 真实 LLM 生成 + 幂等二次调用 + 异步发消息
流内 session_title 事件 + 落库）。详见 known-issues/06 尾部。

**第十六处（本批，评估 dataset 前置 + 执行步暂缓决策）**：`DatasetService`
（对照 Go dataset.go 全文：GetDatasetByID 忽略入参恒取默认集 + PrintStats +
Iterate → QaPair）落地；数据加载为一次性转换的内嵌 JSON
（`resources/dataset/samples.json`，1 QA 对 / 4 passage——Go 读
`./dataset/samples/*.parquet`，转换用临时 Go 工具已删）。**EvaluationService
执行步按 Owner 决策暂缓**（2026-09-23）：前端无 `/v1/evaluation` 入口、
`CreateKnowledgeFromPassageSync` 无路由、需新增 jieba-analysis 依赖、
metrics 数值不承诺逐字节（ev-get 属部署差异）；剩余清单（metric 算法包 600 行 /
metric_hook 194 行 / CreateKnowledgeFromPassageSync ~300 行 / EvalDataset 147 行 /
接线）见 known-issues/06 尾部，恢复条件 = 后端出现评估调用需求。

> 最后更新：2026-09-24 · **基线：全量分批回归 ~4,100 全绿 + 真实 Docker 排水 E2E（引用扫描真缺陷修复，见 §0.-12 与 git log 顶部）· golden 1,719+24**
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
   ~~⑱ MCP initialize 契约对齐~~ ✅（W5γ4.21，§0.-36）、SkillEnvironment 位置、波 3 SkillFrontmatter snakeyaml
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

1. **本文件 §7「翻译约定正文」** —— 本项目最重要的资产（原 `docs/translation-conventions.md` 的 §1–§7.5）。
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

### 2.0 波次总表与已知剩余（原 `docs/translation-progress.md`，2026-09-25 并入）

> 详细台账：本文件（进度与交接 + §7 翻译约定）、`docs/translation-log.md`（§8 翻译日志 / 批次细节 / §9 坑索引）、
> `docs/known-issues/`（按批次分片的坑正文）。本小节只是快速索引，**细节以上述文档为准**。

| 维度 | 状态 |
|---|---|
| HTTP 路由对账（route-recon） | 交集 387，**功能性缺口清零**（swagger 非翻译目标；models/{id}/debug 已收官）——2026-09-22 阶段 7 终核 |
| golden 契约测试 | 1,719+24 全绿（全部录自 Go 实行为准；md-* 24 条为阶段 7 新增）——2026-09-22 |
| 全量测试 | **2026-09-25 W5γ4.10 复跑：五批全绿（ACCEPTANCE PASS）**。⚠️ 四件事：①**脚本覆盖面在 W5γ4.10 补全**——`scripts/acceptance.sh` 此前不含 `com.ragagent.retrieval.*` 与 `com.ragagent.config.*`（"五批全量"从未覆盖检索引擎域），现已并入 B4；声称"全量"前先核对脚本包清单与 `server/src/test/java/com/ragagent/` 目录；②单跑 `:server:test` 必假红（Mockito attach）——用脚本的五批划分；③并发跑测试（同事同时跑）会撞固定端口，出现偶发假红（2026-09-25 收口批 B2 的 bs-internal-bad-sig 一次 409 即此，单跑即绿）；dev docreader 常驻 50051 时 `SystemContractTest.parserEnginesOfflineShape` 可能翻红（环境相关，干净树同样失败，见 `known-issues/07-model-debug.md`）；④**验收门分档（2026-09-25 起）**：日常提交跑 `scripts/acceptance.sh --changed`（改动文件 → 领域包 → 受影响批，约 30~80s；命中共享面=build/gradle 文件、`gradle.properties`、`settings*`、`server/src` 下根包与资源、`migrations/`、未知新域 → 自动升格全量），批次交付/里程碑才跑无参数全量（约 3.5 分钟）。原因：五个 `--tests` 过滤器互为 task 输入 → 批次间不共享缓存，全量一次实测 216s；`--dry-run` 可先看映射计划 |
| 双端 A/B | 九族 GET + 写路径 + HybridSearch 两场景 + models/{id}/debug 24 场景逐字节 MATCH——2026-09-22 |

| 波 | 内容 | 状态 |
|---|---|---|
| 0 | memory / datasource | ✅ |
| 1 | 会话/消息面（CRUD/附件/产物/追问/steer） | ✅ |
| 2 | chunk / knowledge / faq / infra-config / members / system / 扫尾 | ✅ |
| 3 | sandbox / 协作 / agents / browserskill | ✅ |
| 4 | agent 核心 + tools + chat_pipeline + 前置缺口（4.1–4.6d） | ✅ |
| 5 | 共享 agent 收口（W5α）/ tenant_skill verify（W5β）/ im 执行体（W5γ：γ1 地基 + γ2 service + **γ3 九渠道全落地**）/ provider 终端（W5δ）/ 检索引擎批 | ✅ **W5γ 于 2026-09-25 收官**（九渠道全模式，含 feishu pbbp2 与 dingtalk Stream 自持实现；见 §0.-15）；W5δ 见下 |
| 7 | models/{id}/debug 模型调试端点（五类运行时工厂 + 24 golden + 双端 A/B） | ✅ |
| A4/C | 未接线项收口 + langfuse OTLP 追踪全链（渲染/导出/批处理 + 30 处注入 + 8 处显式 span + 端到端验收） | ✅ |
| D | Neo4j 图库面（三方法 Cypher 照抄 + chunk:extract 写入链）+ 共享 agent 收口 | ✅ |
| A3 | 存储 provider 层（local + s3/minio/obs/ks3 + oss/cos/tos 原生 SDK + 窄口接线 + 知识/skill/FAQ 改道） | ✅ |

**已知剩余（外部 provider 传输层，接缝与验收口径已备案）**：

- ~~IM 九渠道出站客户端~~ ✅ 2026-09-25 W5γ3 收官（九渠道全模式落地：telegram/slack/qqbot/wecom/feishu+lark/dingtalk/wechat/mattermost/yunzhijia；见 §0.-15）
- ~~存储 provider 的云 SDK 层~~ ✅ 2026-09-24 A3 落地（local + s3/minio/obs/ks3 + oss/cos/tos；见 known-issues/08）
- ~~`/wechat/qrcode` ×2 端点~~ ✅ 2026-09-25 `dd996bd`（扫码登录端点接真 iLink）
- cube/e2b 终端 PTY 的 SDK 流传输（中性层已翻，W5d 接缝在）
- ~~tenant_skill install 管线体（播种/installer agent 对话/快照构建/指针切换；需活沙箱+LLM）~~ ✅ 2026-09-23 批 D2 落地 + **2026-09-25 真实 LLM E2E 全链通过**（§0.-27，抓回并修复四处驱动缺陷）
- 外部向量店 driver：**九店全部落地、检索批收官**（W5γ4.1~γ4.16：ES v7/v8、OpenSearch、Doris、Qdrant、Weaviate、Milvus、腾讯 VectorDB、SQLite；postgres 走既有 JDBC 件；neo4j 属图仓、更早批已落地）——总表/差异/联调清单见 [`docs/retrieval-batch-closure.md`](retrieval-batch-closure.md)
- ArtifactCollector 的沙箱文件源生产装配（seam 在，需活沙箱）
- VLM 界面：**三个界面全部落地**（openai/ollama/weknoracloud；ollama 见 §0.-32、weknoracloud 见 §0.-34）

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

### 3.-3 检索批已无遗留（2026-09-25——W5γ4.8 收尾，留档）

§0.-21 立项的三项 follow-up：①启动复位（`StartupTaskRecovery`，W5γ4.7）；②`HousekeepingService`
（W5γ4.8）；③知识写链改道引擎口（W5γ4.7）+ move 的 reparse 模式收尾（W5γ4.8）——**全部落地**。
`git log` 的 W5γ4.1~γ4.8 八笔即检索批全貌。

**下一步候选（2026-09-25 W5γ4.19 刷新——检索批已收官、VLM 三界面齐；报告见 [`retrieval-batch-closure.md`](retrieval-batch-closure.md)）**：
1. ~~OpenSearch~~ ✅ W5γ4.9（§0.-24）；~~Doris~~ ✅ W5γ4.10（§0.-25）；
   ~~Qdrant~~ ✅ W5γ4.11（§0.-26）；~~Weaviate~~ ✅ W5γ4.13（§0.-28）；
   ~~Milvus~~ ✅ W5γ4.14（§0.-29）；~~腾讯 VectorDB~~ ✅ W5γ4.15（§0.-30）——
   **除 SQLite 外全部落地**（HTTP 族 / SQL 族 / gRPC 族均已走自持 REST/HTTP 口径）；
2. ~~SQLite~~ ✅ W5γ4.16（§0.-31）——**九家店全部落地，检索批收官**；
3. **备案小账批**：Milvus 的 shardsNum/模板参数差异、Weaviate 的 `ENABLE_TOKENIZER_GSE` 回填
   Go 仓 compose、腾讯的**分词接缝**（接真实 jieba 可与 Go 存量数据互通）、E2E 抓回的两个
   观察项（早错 SSE 不收流 / `list_sandbox_files` 注册时机）、VLM 界面文案、jieba 真实分词；
4. provider-XDEP 族 / Owner 决策遗留（W5δ PTY、存储三条备案之①③；~~initialize 契约对齐~~ ✅ W5γ4.21（§0.-36）；
   install 真实 LLM E2E 已于 §0.-27 收官）——均需真实 provider 或决策输入。

### 3.-2 当前续推点：接线的第 3~4 步（2026-09-25 起）—— ✅ 已收官（W5γ4.6，见 §0.-21）

检索引擎的"驱动层 + 解析层"已齐（§0.-16~§0.-20），但它们**还没有调用方**——下面两步把它接进
真实链路。**第 4 步是唯一会动已 golden 锁定读路径的一步**，务必单独跑回归 + 真 PG A/B。

1. **`ChunkService` 引擎创建接线 + `Embedder` 适配器**
   - `server/src/main/java/com/ragagent/knowledge/service/ChunkService.java:464` 现在是接缝注释
     （Go L832 `CreateRetrieveEngineForKB`，失败文案 `failed to create retrieve engine: %w`，
     注释里标着"不可达"需改成可达）；
   - `Embedder` 适配器：`com.ragagent.embedding.Embedder` → `ModelRuntimeFactory.getEmbeddingModel(modelId)`
     即 Go 的 `GetEmbeddingModel`（§0.-20 已把 KV 服务的薄口统一到该口，这一步只剩装配）；
   - 顺带扫同类散点：`ChunkService` 里既有 `vectorStore.deleteBySourceId(...)` 等是否该改走引擎口。
2. **`HybridSearchService` 按引擎类型路由**
   - 现在 `HybridSearchService.java` 对任何绑定了外部 store 的 KB 直接抛
     `vector store is currently unavailable`（2201），实际只走 postgres env-store 单路径；
   - 改为按 store 分组 → registry 解析 → composite 扇出（`EffectiveEngines` 已在 §0.-20 抽出，
     九类映射表与租户配置解析均已就位）；
   - **回归要求**：HybridSearch 相关 golden（`knowledge-search` 族 + agent @KB 工具）两轮 A/B 逐字节。
3. 之后：`retriever/normalizer.go`（分数归一化，多引擎路由落地后才有意义）→ OpenSearch driver
   → gRPC 族协议决策（Weaviate/Qdrant/Milvus/腾讯）→ provider-XDEP 族 / Owner 决策遗留。

### 3.-1 收口批（2026-09-25）抓到并修掉的两处（已闭环）

**① `W5bInitializationContractTest.upstreamFamily()`（桩侧过期，已修）**

- 现象：`POST /initialization/extract/text-relation` 返回 500
  `failed to parse JSON content: Unrecognized token 'stub'`；期望 golden `w5b-extract-graph.json`（200）。
- 定位（实证）：临时把桩收到的 chat 请求体落盘 → 出站体为
  `"role":"user","content":"# Question\nQ: \u003c\u003cSCENARIO:graph\u003e\u003e 请从下面的文本中抽取实体与关系。\nA: "`
  —— **标记被 HTML 转义**（`<`→`\u003c`），而桩仍在找裸 `<<SCENARIO:graph>>` → 命中默认分支
  `stub-chat-reply` → 图 JSON 解析失败。
- 判定：**转义形态就是 Go 形态**（Go `json.Marshal` 默认转义 `<`/`>`/`&`；且 §0.-10/§0.-11
  两批对 LLM 出站体做过全 body 逐字节对拍）→ 应用侧正确，**改桩**：标记匹配改为
  `SCENARIO:graph`（不再依赖裸尖括号）。修后该类 4/4 绿。
- 遗留的教训：凡以"请求/响应里出现某段裸文本"为判据的桩，都要考虑 Go 的 HTML 转义
  （尖括号/& → `\u00XX`）。

**② `ImService.startChannel` 让工厂异常冒成 500（本批引入，已修）**

Go 的语义是"渠道起不来 → 运行态无适配器 → 回调 503 `channel not available`"（golden
`w5a-im-callback-enabled-get/post.json` 即此）；现在 `startChannel` 捕获 RuntimeException →
记 WARN（id/platform/mode/err）+ 不入运行态。

**新会话开场动作**（按序）：
1. `git status`（确认在 `/Users/billy/ragagent-java`）+ 读 §0.0。
2. 真缺口用 `python3 scripts/route-recon.py` 复核（起点应为「真缺口候选 1」=
   `/swagger/{}` 非翻译目标）。⚠️ route-recon 只对账路由——「路由在、执行体占位」
   的缺口它抓不到，历史上已抓出两族：regenerate-summary（已补全）与 ChunkService
   三处（下一条）。
3. 之后：**占位扫描已完成**（2026-09-22~23，§0.0 第十九~十三处），FaqService
   索引族全链、updateImageInfo、WebSearchProvider test、评估 dataset 前置、
   会话标题、摘要 fan-out、span 写入侧均已落地；剩余真缺口仅
   ~~EvaluationService 执行步——Owner 决策暂缓~~ ✅ 2026-09-24 `87a82fc` 执行体补全
   （当日 Owner 恢复该批；jieba 分词以 `MetricSegmenter` 接缝降级实现，真实分词库仍待接入）；
   ~~im 执行体（W5γ1/γ2/γ3）~~ ✅ **2026-09-25 收官**（九渠道全模式，见 §0.-15 与 §2.0）；
   仍剩 **W5δ provider 终端执行体**（cube/e2b PTY SDK 流等——卡在需真实 provider/沙箱，
   与 install 管线体、ArtifactCollector 文件源、VLM 界面同属 XDEP 族，见 §2.0「已知剩余」）、
   检索引擎批（HybridSearch 执行面）；~~⑱ initialize 契约对齐~~ ✅ W5γ4.21（§0.-36）

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
/me/env-vars/{skill,sandbox}（波 3/5）、~~/wechat/qrcode ×2~~ ✅ 2026-09-25 `dd996bd` 落地、
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
- **文档/工具（收尾期新增）**：`docs/known-issues/`（坑正文分片）、`docs/translation-log.md`（日志/索引）、
  `scripts/route-recon.py`（路由缺口对账）、`scripts/acceptance.sh`（五批验收：全量 /
  `--changed` 只跑受影响批 / `--dry-run` / `--base <ref>` / `--with-ab`）

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
# 6) 更新 docs/translation-log.md 的 §8（日志行）+ docs/known-issues/ 对应分片（新细节/差异）
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
- 派 agent 时**第一句**永远是：「先读 `docs/HANDOFF.md` §7（翻译约定：§3 GORM 清单 / §7.5 约束） + `docs/known-issues/` 对应分片」
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
| 翻译约定正文（必读，§1–§7.5） | `docs/HANDOFF.md` §7 |
| 翻译日志 / 批次细节 / 坑索引 | `docs/translation-log.md` |
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


## 7. 翻译约定正文（原 `docs/translation-conventions.md` §1–§7.5，2026-09-25 并入）

> 每个翻译任务开工前必读本节（§1–§7 规则 + §7.5 派发约束）。
> 日志与坑：`docs/translation-log.md`（§8 日志 + §7 批次细节 + 坑索引）、
> `docs/known-issues/`（按批次分片的坑正文）。

### 约定 §1 技术栈映射

| Go | Java | 备注 |
|---|---|---|
| Gin handler | `@RestController` | 路由路径逐字符相同 |
| `gin.Context` | 方法参数按需组合：`HttpServletRequest`/`@RequestBody`/`@PathVariable` 等 | 不要注入裸 `HttpServletResponse` 除非必要（SSE 除外） |
| GORM | MyBatis-Plus | Mapper 接口 + `@TableName` 实体；见 §3 |
| `context.Context` | `TenantContext`（ThreadLocal，见 §5）+ 显式传参 | 不要把 Context 当参数层层传 |
| goroutine | 虚拟线程：`Thread.ofVirtual().start(...)` / `Executors.newVirtualThreadPerTaskExecutor()` | 已在 application.yml 开启 `spring.threads.virtual.enabled` |
| channel | `BlockingQueue` / `CompletableFuture` | |
| `error` 返回值 | 抛 `BizException`（见 §4） | Go 的 `if err != nil` 日志后返回 → 抛异常，由全局 handler 记日志 |
| `time.Time` | `java.time.Instant`（DB 存 `OffsetDateTime`） | JSON 序列化格式必须与 Go 的 RFC3339 一致（`yyyy-MM-dd'T'HH:mm:ss'Z'`） |
| `json:"xxx,omitempty"` | `@JsonInclude(NON_NULL)` | 字段级：`@JsonInclude(NON_NULL)` 放字段上 |
| `int64`/`float64` | `Long`/`Double` | JSON 数字精度对齐 |
| option 配置结构体 | `@ConfigurationProperties` record/class | |
| `sync.Mutex`/RWMutex | `ReentrantLock`/`ReentrantReadWriteLock` 或 `ConcurrentHashMap` | |
| `defer` | try-finally 或 try-with-resources | |

### 约定 §2 包结构约定

- Go `internal/handler/x.go` → `com.ragagent.x.XController`
- Go `internal/application/service/x.go` → `com.ragagent.x.XService`
- Go `internal/application/repository/x.go` → `com.ragagent.x.mapper.XMapper`（MyBatis-Plus 接口）
- Go `internal/types/x.go` 中的领域类型 → `com.ragagent.x.domain.X`（实体）+ `com.ragagent.x.dto.XxxRequest/XxxResponse`（传输对象）
- Go `internal/middleware/` → `com.ragagent.common.filter.*`（Servlet Filter 链，顺序 = Go 中间件顺序）

### 约定 §3 GORM → MyBatis-Plus 规则（翻译 model 前必做清单）

翻译每个 Go model 前，先扫描并**显式列出**以下隐式行为，翻成 MyBatis-Plus 等效配置：

1. **钩子**：`BeforeCreate`/`AfterFind`/`BeforeUpdate` 等 → MyBatis-Plus `@TableField(fill = ...)` + `MetaObjectHandler`，或在 service 层显式赋值。**列出每个钩子等效为哪段 Java 代码。**
2. **关联预加载**：`Preload(...)` → 是额外查询就在 service 层 join 查询，标清 N+1 风险。
3. **软删除**：`gorm.DeletedAt` / `gorm:"softDelete"` → `@TableLogic` 注解。
4. **默认排序**：`gorm:"default:..."` 和代码里隐式的 `Order(...)` → 显式出现在查询构造中。
5. **唯一索引/外键**：struct tag 里的 `uniqueIndex`/`index` → 在 `@TableField` 注释或迁移 SQL 核对（**迁移不改**，索引以迁移为准）。
6. **自动时间戳**：`CreatedAt/UpdatedAt` 自动写 → `MetaObjectHandler`。

翻译一个 model 不出这张清单 = 任务未完成。

### 约定 §4 错误与响应格式（逐字段锁定）

Go 的成功响应统一为：

```json
{"data": ..., "success": true}
```

Go 的错误响应有两种形态，**按源文件实际使用的翻译**（看 handler 里写的是 `c.JSON(status, gin.H{"error": ...})` 还是统一 error handler）：
- `{"error": "消息"}` + 对应 HTTP 状态码（handler 直接写的）
- 统一 `{"success": false, "message": "...", "code": ...}`（若走全局 error handler——翻译 router/middleware 时确认实际形态，记录到本文件 §8）

Java 实现：`com.ragagent.common.R<T>`（`{data, success}`）+ `@RestControllerAdvice` 全局异常处理器按 Go 实际形态输出。状态码必须一致（400/401/403/404/409/500）。

### 约定 §5 TenantContext（对照 Go context.Context）

Go 用 `context.Context` 传递 tenant/principal/visitor。Java：

- `com.ragagent.common.context.TenantContext`：ThreadLocal 持有 `tenantId`、`principalType`、`principalId`、`embedVisitorId`
- 由 Filter 链（对应 Go middleware 链）填充：auth → rbac → principal 解析
- service 层需要租户隔离的每个查询必须带 `TenantContext.currentTenantId()`，等效 GORM 的 `Where("tenant_id = ?")`
- **虚拟线程下 ThreadLocal 安全**（虚拟线程也是线程），但跨虚拟线程传递（异步任务）必须显式传递值，禁止共享 ThreadLocal

### 约定 §6 SSE 流式翻译纪律（agent/chat 模块最严格）

翻译任何涉及 `stream_emit` / SSE 的代码时：

1. **先把 Go 侧所有 emit 点列成表**：文件、行号、事件类型、payload 字段。
2. Java 侧逐一对照：每个 emit 点编号对应，事件顺序一致，`data:` 的 JSON 字段名和结构一致。
3. `SseEmitter` 用完必须 `complete()`；异常路径必须 `completeWithError()`。
4. 客户端断开检测：Go 的 `c.Stream` 断流 → `SseEmitter.onCompletion/onTimeout` 注册清理。
5. 心跳/keepalive 间隔与 Go 一致。

### 约定 §7 测试翻译规则

- Go 表测试（table-driven）→ JUnit 5 `@ParameterizedTest`
- `testify/assert` → AssertJ
- Go mock（gomock/mockery）→ Mockito
- httptest → `@WebMvcTest` + MockMvc
- ** golden 契约测试**：`server/src/test/resources/contracts/` 下按端点存 Go 版实际响应，Java 集成测试逐字段比对
- 翻译完成的定义：Go 测试语义对应的 Java 测试全部通过 + golden 通过

### 约定 §7.5 派发翻译 agent 的标准约束（每次必带）

每个翻译 agent 的任务书里**必须**包含以下段落。它们对应的是已经踩过的坑，
省掉任何一条都会以某种形式复发。

```
【项目强制约束——以下每条都对应踩过的坑】

1. 先读 docs/HANDOFF.md §7（翻译约定：§3 GORM 隐式行为清单 / §7.5 本约束）。
   ⚠️ 坑与细节在 `docs/known-issues/`（按批次分片；索引见 `docs/translation-log.md` 末节）——按你的模块选分片读：
   动 model/service 前优先 `00-foundation.md`，动 chat/SSE 优先 `01-mcp-stream-session.md`，
   收尾批看 `06-wave-5.md`。里面的规则都是复发的来源，写代码前逐条对照，别等到 review。

2. 领域对象上**任何** isXxx() / getXxx() 形式的派生访问器，先判断它在 Go 里是**方法**还是**字段**：
   - 方法 → 必须 @JsonIgnore（否则被 Jackson 当属性写进 jsonb，回读抛
     UnrecognizedPropertyException，整列不可用）
   - 这个坑在阶段 3、4.1 各复发一次，是**复发率最高**的错误。

3. 新增/修改的、会落 jsonb 或直接作响应体的类型，必须在
   `server/src/test/java/com/ragagent/common/JsonContractRoundTripTest.java` 里加一条
   `assertRoundTrips(...)`（工具见 `com.ragagent.common.JsonRoundTrip`，它用严格映射器
   自动抓「漏 @JsonIgnore」与「键名漏蛇形」）。

4. JSON 键名**逐字段对照 Go 的 json tag**：本项目 JSON 是契约。Go 的 tag 是蛇形就写
   @JsonProperty("snake_case")，不要按 Java 字段名输出。MCP/Wiki 里还有协议规定的
   驼峰（如 inputSchema / mimeType），照抄别改。

5. Go 非指针零值语义：string 字段默认 ""、计数器用原始类型（避免插 NULL）、
   omitempty 的 0/空/false 要省略（@JsonInclude(NON_DEFAULT)），无 omitempty 的恒输出。

6. jsonb 回读路径的 ObjectMapper 必须容忍未知属性
   （Go 的 json.Unmarshal 默认忽略，Jackson 默认失败），否则历史行读不出来。

7. 测试**禁止依赖真实网络**：
   - 不写真实公网域名（本机 DNS 可能把 api.openai.com 解析到 Teredo 段而被 SSRF 拒绝）
   - 需要出站校验时注入白名单（`SsrfGuard.reloadWhitelist(...)` /
     `LlmTransport.setSsrfGuard(...)`）或用 stub server

8. 测试命令**只跑你负责的包**，不要跑全量：
   `./gradlew test --tests "com.ragagent.<你的包>.*"`
   多个 agent 同时跑全量会争抢 build 目录（OOM / test-results 被并发写坏 → 假失败）。

9. 只改你负责的目录。`config/WebConfig.java` 的路由注册、`TestSchema.java` 的表结构
   （除非任务明确要求新增表）由主会话统一处理，避免并发写冲突。

10. 报告里必须给出：翻了的文件、**暴露给后续模块的关键签名**、测试数量与结果、
    与 Go 的已知差异、需要主会话决策的点。
```

**为什么要降并行度**：并行 agent 争抢 gradle/build 目录造成的 OOM 与"假失败"重跑，
在阶段 4 浪费了至少两轮。宁可串行，也别让两个 agent 同时跑全量测试。

