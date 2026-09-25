# 存储 A3：读路径① + local 双实现③ 的对齐方案（**方案稿，待批准**）

> 来源：`known-issues/08-storage-a3.md`「已知差异」三条备案之 ①③（② OSS 分片已由 W5γ4.20 落地）。
> 纪律：①③ 会触及既有 golden 锁定面 → **先方案、后批准、再动代码**。本文件不含代码改动。
> 对账时间 2026-09-25：两侧代码逐处核过，证据带 `file:line`。

## 0. 结论速览

| 项 | 事实（对账结论） | 建议 | 代价 |
|---|---|---|---|
| **① 云对象整对象入堆** | **真偏差**（Java 侧引入）：`ProviderFileContentService.getFile` 用 `in.readAllBytes()` → `OpenedFile.ofBytes`；Go 是 `SDK resp.Body → io.Copy` 直转 | **做**，拆两步：**①a** provider/HTTP 面流式化（**报文字节不变**、golden 零重录）；**①b** 知识下载面（需另评） | ①a ≈5 文件 / 100–200 行 + 2 个测试类 |
| **③ local 双实现** | **Go 侧根本不存在这两支**（全仓 `fileserve` / `LocalStorageService` / `FileContentService` 0 命中）——两支是 Java 侧结构，且**职责不重叠**、**两支护栏都正确**（只是重复） | **不合并**；走**方案 B：内部去重 + 结构备案** | ≈2 文件 / 几十行 + 边界用例 |
| （对照）③ 方案 A：合并成一支 | 行为不变、纯结构收益 | **不做** | ≥8 测试类 + ~125 golden 重录 + `resource://` 引用形态风险 |

## 1. 两侧事实（证据）

### 1.1 Go 侧（权威形状）

- **单接口 + 单 local 实现 + 装饰器 + 统一出口**：
  - `internal/types/interfaces/file.go:11-31`：`GetFile(ctx, path) (io.ReadCloser, error)`、`SaveFile(ctx, *multipart.FileHeader, …)`、`SaveBytes(…, []byte, …)`——**读恒流式，没有 `[]byte` 形读接口**。
  - `internal/application/service/file/local.go:41/87-99/121`：唯一 local 实现（写 `os.Create`+`io.Copy`，读 `os.Open` 前先 `SafePathUnderBase` 守卫）。
  - 装饰器：`backend_scoped.go:18-27`（路径里的 backendID 编解码）、`resource_catalog.go:125-136`（`resource://` 物理解析）——**Java 两支的职责在 Go 是"装饰器 + 接口"**。
  - 出口：`internal/filetransport/response.go:52-69`——seekable（`*os.File`）→ `http.ServeContent`（Range/Content-Length/HEAD）；否则 `Accept-Ranges: none` + **仅当 `options.Size > 0` 才设 Content-Length** + `io.Copy`（chunked）。
  - `Size` 只有 artifact 下载传：`handler/session/artifact_download.go:246`。
- **云读一律 SDK body 直传、无缓冲、无 Range**：`oss.go:299-307`、`s3.go:263-271`、`cos.go:139-143`、`tos.go:291-298`、`obs.go:197-205`、`minio.go:154-158`。

### 1.2 Java 侧（现状）

- **读实现本身是流式的**（六个 provider 都返回 `InputStream`）：`LocalFileService.java:113-117`、`S3CompatibleFileService.java:220-228`、`OssFileService.java:299-309`、`CosFileService.java:138-147`、`TosFileService.java:183-195`。
- **入堆发生在这两处**：
  - `storage/fileserve/ProviderFileContentService.java:40-43`：`in.readAllBytes()` → `ofBytes`（类注释 20-24 行自认差异）；
  - `knowledge/service/TenantFileStorage.java:192-205`（`readFromProvider` 读满）+ `LocalStorageService.java:65,82`（本地也读满，供 `ResponseEntity<byte[]>` 出口）。
- **传输层已具备 Go 的两分支语义**：`storage/fileserve/FileTransport.java`
  - `OpenedFile` 只有两形态：`ofSeekable(Path, size)` / `ofBytes(byte[])`（58-67）；
  - 非 seekable 支路（100-117）：`Accept-Ranges: none` + **`options.size() > 0` 才设 Content-Length**（108-110）→ **与 Go 同源**，报文字节由调用方的 `Options.size` 决定，**不由对象长度决定**。
- **出口三形态**：(a) `FileTransport.serve` 直写响应（`/files`、`/r/{token}`、presigned、KB/消息/embed scoped）；(b) 知识下载 `KnowledgeController.java:1201-1216` 的 `ResponseEntity<byte[]>`（手写 Content-Length / Accept-Ranges）；(c) 附件预览 `TemporaryDocumentController.java:217-223` 手写头 + 写 byte[]。

### 1.3 ③ 的两支（Java 侧结构，非 Go 镜像）

| | `knowledge/service/LocalStorageService.java`（183 行） | `storage/fileserve/LocalFileContentService.java`（352 行） |
|---|---|---|
| 引用形态 | `resource://{tenant}/{knowledge}/{name}`（`save` 46-53） | `local://{rel}` + 预签名 URL（`getFileURL` 51-75） |
| 布局 | `{base}/{tenant}/{knowledge}/…` | `{base}/{tenant}/exports/<base>_<nano><ext>`（`saveBytes` 115-126） |
| 读取 | `read`（只认 `resource://`）/ `readChecked`（三 scheme + 守卫） | `getFile` → `ofSeekable`（服务 HTTP 流） |
| 错误通道 | `BizException("Failed to retrieve file")` 信封 | `IOException` → 404 |
| 调用方 | `KnowledgeService:92/210/244/2028`、`TenantFileStorage:51/88/130/135/148/166` | `StorageFileResolver:214/730-731`、`FileProxyService:69`、`ChatLocalImageResolverWiring` |
| 重复段 | 路径守卫 `resolveUnderBase`（89-108） | `safePathUnderBase`（341-351）+ `cleanPath`(229)/`goRel`(269)/`normalizePathForBase`(174) |

- **两支护栏都正确**（都做 normalize + 包含判定，语义等价于 Go 的 `SafePathUnderBase`；一支抛 `BizException`、一支抛 `IOException` 是各自错误通道需要）→ **没有安全缺口**，只是**同一条 Go 语义写了两遍**。
- Go 侧无对应物：`resource://` 是 **Java 侧约定**（golden 锁定：10 个 json 含该串 + `kg-*` 头/体）；Go 靠 `resourceCatalogFileService` 装饰器 + `PathPrefix` 完成同目的。

## 2. ① 改造方案

### 2.1 ①a：provider/HTTP 面流式化（建议本批做）

| # | 改动点 | 说明 |
|---|---|---|
| 1 | `FileTransport.OpenedFile` 加第三形态 `ofStream(InputStream in, long size)` | 保留 `ofSeekable`（本地，走 ServeContent/Range）与 `ofBytes`（legacy 手工路由/知识 byte[] 出口） |
| 2 | `FileTransport.serve` 非 seekable 支路分流 | `bytes != null` → 照旧；`stream != null` → `in.transferTo(out)`；`closeReader` 从 no-op 变**真关流**（Go 的 `defer reader.Close()` 对应物） |
| 3 | `ProviderFileContentService.getFile` → `ofStream(inner.getFile(path), 0)` | **打开动作保持即时**（现在就调 `inner.getFile`，异常仍折 `IOException` → 404，与 Go 的 `GetFile` 即时开一致）；只把"读体"延后 |
| 4 | 选配：`saveBytes`/`getFileURL` 不动 | 与 Go 同形 |

**报文字节不变性（关键论证，也是 golden 零重录的依据）**：
- 头部由 `Options` 决定（`Content-Type`/`Disposition`/`Cache-Control` 照旧；`Accept-Ranges: none`；`Content-Length` 仍**只在 `options.size() > 0` 时**设）——流式化**不改变**这些字段（Java 现在也没用对象长度当 CL）。
- 体字节：流式与读满后转发**逐字节相同**（同一 `InputStream`）。
- 因此既有 golden（`w5c-*` 85 + `w5f-*` 18 + `kg-*` 16 + `w5d-emb-files-*` 6 等）**无需重录**；受影响的只有两个锁 `ofBytes` 形态的**单测**：`ProviderWiringTest.java:39-41`（`opened.bytes().length == 3`）、`ChatLocalImageResolverWiringTest.java:188`。

**A/B 方案（云对象无本地真服务，故用 MinIO 造真云面）**：

```bash
# 1) 起 MinIO（OrbStack）
docker run -d --name WeKnora-minio-ab -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio server /data --console-address ":9001"
# 2) 两端同连（Go: STORAGE_TYPE=minio/s3；Java: 对应 provider 配置，同一 endpoint/bucket/key）
# 3) 同一路由、同一文件，双端各取一次响应
curl -sD - -o /tmp/ab-java.bin "$JAVA/…/files?…"    # 头（掩 Date/X-Request-Id）+ 体
curl -sD - -o /tmp/ab-go.bin   "$GO/…/files?…"
cmp /tmp/ab-go.bin /tmp/ab-java.bin && diff <(norm_headers /tmp/ab-go.h) <(norm_headers /tmp/ab-java.h)
# 4) 内存实证：Java 侧 -Xmx 收紧（如 256MB）后下载 ≥2×Xmx 的对象，不 OOM = 流式成立
```

- 判据：状态行 + 归一化头**逐行一致**（含 `Accept-Ranges: none`、**无** `Content-Length`、`Transfer-Encoding: chunked`，与 Go 的非 seekable 分支一致）+ 体 sha256 一致。
- 补充对拍（无 MinIO 时）：Go 侧一个最小探针（`filetransport.Serve` + 非 seekable reader）↔ Java `MockHttpServletResponse` 单测，比头形态（弱于真 A/B，仅作兜底）。

### 2.2 ①b：知识下载面（**需另评，建议下一批**）

- 现状：`TenantFileStorage.readFromProvider`（198-199）读满 → `KnowledgeController:1201-1216` 以 `ResponseEntity<byte[]>` 出；Go 的知识下载走 `handler/knowledge.go:1477/1533 → filetransport.Serve`（**流式**）。
- 对照风险点：本地文件走 `Accept-Ranges: bytes`（golden `kg-download.bin.headers` 锁的就是这个）；云对象在 Go 是 `none` + **无** `Content-Length`，Java 现在给 `byte[]` 出口 → 头可能不一致（**无 golden 覆盖云对象**，所以属"未锁定面"）。
- 建议：①a 落地并 A/B 通过后，再把知识下载出口改成"本地 seekable / 云 stream"双通道（**保留本地语义不变 → kg golden 仍绿**），单独一批。

### 2.3 ① 的明确不做项

- `ChatLocalImageResolver`（`io.ReadAll` 供 base64）与 `container.go:536`（Go 侧**同样**读满，供多模态 base64）→ 不是偏差，**保留**。

## 3. ③ 方案对照

| 方案 | 内容 | golden/测试影响 | 结论 |
|---|---|---|---|
| **A 合并成一支** | 单一 local 服务同时认 `resource://`/`local://`，统一错误通道 | ≥8 测试类 + ~125 golden 重录候选 + 10 个含 `resource://` 的 json；`resource://` 引用形态若变则牵连 KB/消息/embed 三条路由 | **不做**（行为零收益） |
| **B 内部去重（推荐）** | 抽出包内工具（如 `StoragePathGuard`）：① `safePathUnderBase` 语义一份实现（含 `clean/rel/join`）；② 三态 scheme 解析（`resource://`/`local://`/裸路径）一份实现。两支**改为委托**，各自保留引用形态、布局、错误通道 | **零 golden 影响**；新增边界用例（`..`、`//`、绝对路径、空、越界），既有 `LocalFileContentServiceTest`/`TenantFileStorageTest` 保持绿 | **本批可做** |
| **C 只备案** | 在 `08-storage-a3.md` 写清"两支是 Java 结构、Go 无对应物、职责不重叠" | 无 | 底线（B 的成本极低，建议 B） |

**③ 的对齐目标应改写为**："两支各自都不偏离 Go 的单实现行为"，而不是"消灭一支"——验收方式：为守卫与 scheme 解析补**参数化边界用例**（同一组输入喂两支，断言等价）。

## 4. 需 Owner 决策的点

1. **①a 是否本批做**（推荐：是）——它不动 golden、只改内存行为，且能做真 A/B（MinIO）。
2. **①b（知识下载面）是否紧接着做**（推荐：①a A/B 绿后再单批）——它可能动 `kg-*` 头形态（本地分支要保住 `Accept-Ranges: bytes`）。
3. **③ 走 B（去重）还是 C（只备案）**（推荐：B；A 明确不做）。
4. **是否接受引入 MinIO 容器做 A/B**（本机 OrbStack 已有 weaviate/milvus 测试容器先例）。

## 5. 建议批次切分与验收

| 批次 | 内容 | 验收 |
|---|---|---|
| **W5γ5.1**（①a + ③B） | provider 流式化 + 路径/守卫去重 | `--changed` 或定向（B4 storage + B3 knowledge）；`ProviderWiringTest`/`ChatLocalImageResolverWiringTest` 改形态断言；③ 加等价性边界用例；**金面**：`w5c-*`/`w5f-*`/`kg-*`/`w5d-emb-files-*` 全绿且**不重录** |
| **W5γ5.2**（①b，可选） | 知识下载出口双通道 | MinIO A/B：本地分支头=bytes（golden 不动）+ 云分支头=none/无 CL；`-Xmx` 收紧实证 |

**MinIO A/B 与内存实证命令**见 §2.1；若不做 MinIO，则退化为"单侧头形态对拍 + 单测"，并在文档注明降级。

## 6. 附：本方案涉及的 golden 锁定面（供判断重录）

- 逐字节锁定的读面：`w5c-*` 85 + `w5f-*` 18 + `kg-download/preview*` 16 + `w5d-emb-files-*` 6 + `att-preview*` 3 = **128 个文件**（其中 31 个 `*.headers` 逐行锁 `Accept-Ranges`/`Content-Length`/`Content-Type`/`Content-Disposition`/`Cache-Control`）。
- 重录脚本（若某批确实需要）：`scripts/record-w5c-golden.sh`（w5c/w5f/w5d 同源双端）+ `scripts/ab-w5c.sh`。
- **本方案 ①a/③B 的预期：零重录**（报文字节不变）；①b 若做，也只影响"云对象"这一**未被 golden 覆盖**的面。

## 7. 执行记录（2026-09-25 W5γ5.1）

- **①a + ③B 已落地**：见 HANDOFF §0.-37 与 `known-issues/08` 的划账行；验收 = `StoragePathGuardTest` 4 +
  `ProviderWiringTest` 4（流形态/关流/头形态）+ `FileTransportTest` 10 + **B3+B4 全绿（133s）**，
  **128 个存储 golden 零重录**（方案预测成立：①a 不动报文字节）。
- **真 A/B（`scripts/ab-storage-stream.sh`，MinIO 真云面）：跑通但未 PASS**，且发现两点（正文见 HANDOFF §0.-37）：
  1. Go 对 MinIO 走 ServeContent（`Accept-Ranges: bytes`）——minio-go 的 `*minio.Object` 是 `io.ReadSeeker`；
     "云对象非 seekable"只对 aws-sdk 族成立 → minio 面 Java 给 `none`（既有差异，①a 未回归）；
  2. Java 侧同一 minio 路径 **404**（Go 200）→ 归 **①a2**（minio 路径解析/env 回退面）。
- **①a2 待决**：minio 的 seekable 语义（补 Range 重发型 seekable 适配 vs 差异备案）+ Java minio 404 排查；
  之后才是 **①b**（知识下载面）。
- **①a3（W5γ5.3）**：minio seekable 补适配已落地——`SeekableSource`/`SeekableFileService` 抽象 +
  `FileTransport` 泛化（本地行为不变，golden 未重录）+ minio 的 HeadObject/Range-GET 读；
  **A/B 全 PASS**：全量与 `Range: bytes=0-99` 两场景头逐行一致（含 `Content-Range`）+ 体逐字节一致。
  角落差异记档：缺失对象的 Go=500（ServeContent Seek 失败）/ 本仓=404（HeadObject 阶段）。
- 环境：本机 9000/9001 = rustfs、18080-18082 = rocketmq → A/B 用 9100/19080/19082；MinIO 用
  `brew install minio minio-mc`（docker 镜像站 403 / dl.min.io darwin 410）。
- **①a2（W5γ5.2）**：① 修**凭据解密缺口**（实例行密文被当明文 → 云读 403；`toStorageEngineConfig` 就地解密）——
- ~~①b（知识下载面）~~ ✅ **W5γ5.4 已落地**：`KnowledgeService.openKnowledgeFile`（不读内容）→
  `TenantFileStorage.open`（本地可 seek / 云复用同一能力分流）→ 控制器走 `FileTransport.serve`；
  **kg-* golden 零重录** + 新增 Range 断言用例（206 + `Content-Range: bytes 0-5/34`）；
  云引用的 kg 端到端 A/B 未做（做法见 HANDOFF §0.-40）。
  —— 存储 ①③ 至此全部收官。
- **①a/①b 的收官验证（W5γ5.5）**：`AB_STREAM_KG=1` 的 kg 云引用端到端 A/B **PASS**（全量 200/bytes/CL4096、
  Range 206/`Content-Range: bytes 0-99/4096`，两场景头逐行一致 + 体一致）；`AB_STREAM_MEM=1` 的**内存实证 PASS**
  （192MB 对象 / `-Xmx96m` / 0 OOM）。顺带抓到"知识文件 provider 解析策略"差异（Go 按 backend、本仓按 scheme）
  → 记入 `known-issues/08`，是否对齐属决策项。
  A/B 复验：双端 200、**体逐字节一致**，头只剩 `Accept-Ranges` 一项差异；② **minio seekable 决策 = 补适配**，
  排 **①a3**（`SeekableSource` 抽象 + provider 侧 range 缝 + A/B 判据含 206/Content-Range）；详见 HANDOFF §0.-38。
  `brew install minio minio-mc`（docker 镜像站 403 / dl.min.io darwin 410）。
