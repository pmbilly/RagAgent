# 存储 provider 层（A3）细节与坑

> 2026-09-24 落地，五笔提交：
>
> - `6893a80` 批次一：provider 接口 + local + S3 协议族（s3/minio/obs/ks3）+ 工厂 + 租户回退
> - `54a544f` 批次二：oss/cos/tos（三家厂商原生 SDK）
> - `6c46f96` A3-3 接线：storageurl 两个窄口接生产实现（URL 重写 + 文件读取面）+ 租户实体
> - `77f5865` A3-3 尾批：知识上传/读取/删除走租户 provider 层
> - `ae62567` A3 收尾：FAQ 导出走临时桶 / skill 归档租户化 / 环境投影补齐 / SafeFileName 纠正
>
> Go 源：`internal/application/service/file/*`（local/s3/minio/oss/cos/tos/obs/ks3，20+ 文件）、
> `internal/storageurl/*`、`internal/application/service/storagebackend.go`、
> `internal/types/{storagebackend,storage_engine_config}*.go`。
> Java 目标：`com.ragagent.storage.provider/*`、`com.ragagent.storage.fileserve/*`、
> `com.ragagent.knowledge.service.TenantFileStorage`、`com.ragagent.sandbox.service.TenantSkillBundleStore`。
> 新增依赖：`software.amazon.awssdk:s3:2.31.68`、`com.aliyun.oss:aliyun-sdk-oss:3.18.1`、
> `com.qcloud:cos_api:5.6.227`、`com.volcengine:ve-tos-java-sdk:2.9.19`（版本经 Maven Central 元数据核对）。

## 覆盖与对照

| provider | Java 实现 | 关键照抄点 |
|---|---|---|
| local | `LocalFileService` | `local://{prefix}{tenant}/{knowledge}/{nano}{ext}`、路径守卫 |
| s3/minio/obs/ks3 | `S3CompatibleFileService`（AWS SDK v2） | `requestChecksumCalculation=WhenRequired`（兼容服务常拒绝默认协商）、force path style、桶存在性检查 |
| oss | `OssFileService`（阿里云 SDK） | `{prefix}{tenant}/{knowledge}/{uuid}{ext}`（prefix 补斜杠）、SaveBytes 主桶/临时桶两套对象名、`oss://{bucket}/{key}`、按路径里的 bucket 选主/临时客户端、预签名 24h、409 用错误码判（`BucketAlreadyExists`/`BucketAlreadyOwnedByYou`） |
| cos | `CosFileService`（腾讯云 SDK） | `cos://{bucket}/{region}/{key}` **与遗留桶 URL 两形态**、其它 provider scheme 明确拒绝（`cos file service cannot resolve X path`）、prefix 默认 `weknora`（不补斜杠）、临时桶写回遗留 URL、`CopyObjectRequest` 四参 = **源在前** |
| tos | `TosFileService`（火山引擎 SDK） | `joinObjectKey` 逐段 trim/跳空、prefix 不设默认、HeadBucket 404→建桶 / 409 放行、临时桶短命客户端按 tempRegion 探测、`CopyObjectV2Input.setSrcBucket/setSrcKey`、预签名 24h |

接线（A3-3）：

- `storageurl.FileService` → `StorageUrlWiringConfig.storageUrlDefaultFileService`（进程级默认：
  local 基座 + resource 装饰 → `resource://` 手柄可派生 `/r/<token>`）；
  `storageurl.StorageBackendResolver` → `FileserveStorageBackendResolver`（按 tenantId 取实体 →
  `StorageFileResolver` 全语义）。
- `FileServiceResolver.buildFileServiceForProvider` 按 Go 的兜底链（真服务 → local/默认 → `defaultSvc`）。
- 知识链路：`TenantFileStorage` 统一 save/read/readChecked/delete（本地 → `LocalStorageService`
  原样；云 → provider 服务）；接力点 `KnowledgeService`×4、`KnowledgeProcessWorker`×1、
  `MapperKnowledgeBridge`×2、`FaqService`×1。
- 临时文档（`TemporaryDocumentService`）与产物/技能归档走 fileserve 的 `saveBytes` 面，
  自然继承 provider 解析。

## 新确认的坑

- **TOS Java SDK**：`ve-tos-java-sdk:2.7.2` 不存在（查 Maven Central 元数据 → 用 `2.9.19`）；
  输入类用 V2 命名（`GetObjectV2Input`/`CopyObjectV2Input`/`HeadBucketV2Input`/`CreateBucketV2Input`，
  后两者在 `model.bucket` 包）；`HttpMethod` 是 **String 常量接口**不是枚举；
  客户端入口是 `TOSClientConfiguration.builder()` + `new TOSV2ClientBuilder().build(config)`；
  `PutObjectInput` 没有 `setContentType`（走 `ObjectMetaRequestOptions.setContentType`）。
- **COS 拷贝参数序**：`CopyObjectRequest(String,String,String,String)` 经 `javap -c` 字节码核对
  为 **(源桶, 源 key, 目标桶, 目标 key)**——源在前；该版本没有 `CopySource` 类。
- **OSS 异常无状态码**：`OSSException` 不暴露 HTTP 状态码 → 409 改用错误码判定。
- **Jackson `@JsonUnwrapped` 不支持 record 的 Creator 参数** → 载荷里的追踪载体改为
  **平铺 5 个 `lf_*` 键**（与 Go 匿名字段嵌入同形，跨语言字节兼容）。
- **`local://` 是 provider scheme 但归本地盘**（Go local provider 的原生形态）：
  知识合同测试的 `local://` 种子行把初版"当成云引用 → 500"顶了出来。
- **`SafeFileName` 语义纠正**：Go 是 `filepath.Base(filepath.Clean(name))`——目录部分被
  **丢弃而非拒绝**（skill 归档 `tenant-skills/catalog/<id>.zip`、FAQ 导出依赖它）。
  A3 批次一/二初版过严（禁分隔符），收尾批统一为 Go 语义并更新两处旧断言。
- **Gradle 全量一次跑会成片假红**：`MockitoInitializationException → ByteBuddyAgent`（多 fork
  自附着失败），与代码无关；验收按模块分批跑（详见同目录 `07-model-debug.md` 尾部的同类记录）。

## 已知差异（备案）

- **云对象整对象入堆**：~~`ProviderFileContentService.getFile` 走 `OpenedFile.ofBytes`~~ ✅ **①a/①a2/①a3/①b 全部落地
  （W5γ5.1~γ5.4）**：HTTP 面流式化 + 凭据解密 + minio seekable + 知识下载面流式化（含 Range）；
  原描述留档：`OpenedFile.ofBytes`，
  Go 是流式 `io.ReadCloser`（HTTP 层直转）。大对象流式化要给 `FileTransport` 补第三种形态。
  → ✅ **①a 已落地（W5γ5.1，2026-09-25）**：`OpenedFile` 补流形态 + `serve` 分流 + `closeReader` 真关流，
  `ProviderFileContentService` 改 `ofStream`（打开仍即时 → 404 语义不变）——**golden 零重录**（B3+B4 全绿）。
  **真 A/B 的两条发现与处置**：
  ① **凭据未解密（真缺陷，W5γ5.2 已修）**：`toStorageEngineConfig` 把 `enc:v1:` 密文当明文喂 provider →
  云读一律 403（`The Access Key Id you provided does not exist in our records`）；现已就地解密两族命名（回归：
  `ProviderWiringTest` 第 5 例），修后 A/B 双端 200、**体逐字节一致**；
  ② ~~Go 对 MinIO 走 ServeContent~~ ✅ **W5γ5.3 已对齐**（`SeekableSource`/`SeekableFileService` 抽象 +
  minio 的 HeadObject/Range-GET 读；A/B 全量与 Range 两场景**头逐行一致 + 体一致**）；
  原发现留档：minio-go 的 Object 是 ReadSeeker，
  `Accept-Ranges: bytes`）→ Java 流形态给 `none`（既有差异精确化，待决：seekable 适配 or 备案）；
  ② Java 侧同一 minio 路径 404（Go 200）→ 归 ①a2 排查。正文见 [`storage-a3-plan.md`](../storage-a3-plan.md) §7。
  对账修正两点：① **报文字节不受影响**（Java 已在 `FileTransport.java:108-110` 照 Go 用
  `Options.size` 决定 `Content-Length`，与对象长度无关）→ 既有 golden **零重录**，受影响的只有
  两个锁 `ofBytes` 形态的单测；② 偏差分两处（`ProviderFileContentService` 的 HTTP 面 +
  `TenantFileStorage.readFromProvider` 的知识下载面），建议拆 **①a/①b** 两步。
- ~~**OSS 大文件未走分片 Uploader**~~ ✅ **W5γ4.20 已补**：照 Go（`>10MB` 走 `initiateMultipartUpload → uploadPart ×N（10MB/片、3 并发）→ completeMultipartUpload`，失败 best-effort `abortMultipartUpload`；小文件仍单次 `putObject`；错误前缀照 Go 的 `(multipart)` 分支）。`OssMultipartUploadTest` 5 条钉住：Go 常量（10MB/10MB/3）、小文件单次、片序与片大小（64/64/22 形态）、并发上限 ≤3、失败 abort + 两种错误前缀。
- **local 有两支实现**：`knowledge.LocalStorageService`（`resource://` 契约，golden 锁定）与
  `fileserve.LocalFileContentService`（provider 的 `local://` 契约）。二者都照 `local.go`，
  收敛属清理项，会影响既有 golden，未在本批动。
  → ✅ **③B 已落地（W5γ5.1，2026-09-25）**：新 `StoragePathGuard`（`stripKnownScheme`/`cleanPath`/
  `safePathUnderBase`，照 Go）——两支各自**委托**、保留自己的错误通道与引用形态（**不合并两支**）；
  `StoragePathGuardTest` 4 含**两支等价性**断言；golden 零重录。
  对账修正：**Go 侧根本不存在这两支**（全仓 `fileserve`/`LocalStorageService`/`FileContentService`
  0 命中；Go 是"单 `FileService` + `local.go` 单实现 + backendScoped/resourceCatalog 装饰器 +
  `filetransport` 出口"）——两支是 **Java 侧结构**，且**职责不重叠、两支护栏都正确（只是重复）**。
  故不建议"合并成一支"（代价 ≥8 测试类 + ~125 golden 重录、行为零收益），推荐**去重共享内部**。
- **ks3 无 env 投影**：与 Go 的 `StorageBackendFromEnvironment` 一致（Go 也没有 ks3 的 case，走 default）。
- **删除语义**：本地目录树恒清；云对象按 `file_path` 的 scheme 判定后 best-effort 删（失败只记日志）。

## 验收

| 批次 | 结果 |
|---|---|
| `storage.*` | 78/78 ✅（含批次二 4 条、收尾 3 条、`@SpringBootTest` 合同类 19 例） |
| `storageurl.*` + `session.controller.*` | 274/274 ✅ |
| `knowledge.*` | 210/210 ✅（门面契约 6 条：本地逐字节不变 / `local://` 归本地 / 云写面解析 / 配置不完备明确报错 / 读取信封 / 删除 best-effort） |
| `datasource.*` | 921/921 ✅（桥的 save/deleteTree 改道） |
| `sandbox.*` | 252/252 ✅（skill 归档 @Primary 接管 + local 委托） |
| `session.*` | 375/375 ✅ |

**尚未覆盖**：真云连通（云 provider 的桶探测/上传/预签名需凭据，属部署态）；
`storageBackendFromEnvironment` 的 env 分支（`System.getenv` 不可在进程内注入，无单测）。
