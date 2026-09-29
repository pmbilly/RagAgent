# 知识库模块 Java 本位重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 知识库（knowledge 包）按 Java 惯例完成结构、DTO、注释三层重构：神类消失、Controller 全部标准 DTO + `@Valid` 绑定、注释以本仓语言重写；200 路径契约语义不变，开发者可以按 Java 项目的方式通读迭代。

**Architecture:** 三波推进，严格一次一个轴。A 波纯内部拆分（沿既有分段注释边界移动代码，零契约变化）；B 波 Controller DTO 化（唯一允许的契约变化 = 错误措辞换为本仓标准，信封形态不变）；C 波数据访问层契约重写与收尾审计。每个任务 = 一个独立可绿的 PR，串行合入。

**Tech Stack:** Java 21 / Spring Boot 3.3.5 / MyBatis-Plus / Jackson / jakarta.validation；前端 Vue 3 + TS（仅验证，预期零改动）。

**Spec:** `HANDOFF.md` §0（总目标）、§2（已定决策）、§3（红线）、§5 阶段 2。本计划是阶段 2 中 knowledge 域的执行方案；agent 域（AgentEngine 等）不在本计划内。

---

## 全局约束

1. **行为不变底线**：所有 200 路径响应 JSON 语义等价（键序不敏感）。每任务收尾跑 `./gradlew :server:test` 全绿 + `cd frontend && npm run test && npm run type-check` 全绿。
2. **红线 #1 一次一个轴**：A 波不动任何 Controller/URL/序列化；B 波不拆 service 结构；C 波不动任何契约形态。
3. **错误信封形态保持** `{"success":false,"error":{"code":1000,"message":…,"details":…}}`；`message` 的中文类别文案保持现值（"请求参数不合法" / "分页参数不合法"）；`details` 在 B 波换为字段级中文文案（格式见 Task 7，这是全计划唯一允许的对外契约变化）。
4. **字段命名 snake_case 保持**：新 DTO 统一用 `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`，不再逐字段 `@JsonProperty`。
5. **`error.code` 字符串值逐字保持**（`duplicate_file` / `duplicate_url` / 时间线 `error_code` 族）——前端 `KnowledgeBase.vue`、`knowledge-processing-timeline.vue` 有分支依赖。
6. **半成功响应（HTTP 200 + `success:false`）保持**——`KBChunkingDebug.vue` 等按 `resp.success !== true` 判失败。
7. **Go 序列化器不删**：`GoTimeSerializer` / `GoDoubleSerializer` 等注解随 DTO 字段原样迁移保留，阶段 3 全仓换锚时统一删除——本计划不得产生半删状态。
8. **fixture 只重录错误路径**：200 路径 fixture 一个字节不动；受键序影响的 raw 对比改走 `ContractJson.semantic`（HANDOFF PR4 既定模式）。
9. **注释清洗逢触碰必做**（标准见下节），未触碰文件不动。
10. **提交信息**沿用仓库惯例：`refactor(knowledge): …` / `test(knowledge): …`。

## Java 惯例标准（全任务统一执行）

1. **类规模**：单类目标 ≤ 500 行、硬顶 800 行（门面与状态机 worker 可例外并在类 javadoc 注明原因）；方法 ≤ 60 行；嵌套 ≤ 3 层。
2. **命名**：类名即职责（`XxxService` / `XxxRepository` / `XxxController` / `XxxRequest|Response`）；禁止 `goXxx` 前缀与 Go 术语（omitempty、UnmarshalTypeError、strconv、gin 等）出现在标识符与注释中。
3. **DTO**：`record` + `@JsonNaming(SnakeCaseStrategy)` + jakarta.validation 注解；按业务域分文件（`FaqEntryDtos` / `FaqImportDtos` / `KnowledgeDtos` …），一文件一族，不再有 15 个 record 挤一个文件的 Go 文件镜像。
4. **Controller**：薄层——`@Valid` DTO 绑定 → 委托 service → 返回 `ApiResponse<T>`；不允许私有绑定/解析助手；通用逻辑上移 `common`。
5. **Service**：构造器注入（沿用现风格）；一个 service 一个业务能力族；无状态共享逻辑抽为协作 `@Component` 或 record 上的静态变换方法；各服务只注入自己实际使用的依赖。
6. **注释标准**：
   - 类 javadoc：一句话职责 + 契约要点（HTTP 形态 / 状态机 / 线程模型等非显然约束），≤ 10 行，不提 Go；
   - public 方法 javadoc：语义契约（参数含义 / 返回 / 异常 / 副作用）；自解释方法（`getXxx` / `listXxx`）不写；
   - 行内注释：只写"为什么"与不变量（例："失败标 failed 后返回 chunk 不抛"这种语义钉子必须保留），不写"做什么"、不写代码来历（`对照 Go chunk.go L405-529` 一律清洗：先摘出真实不变量改写为中性表述，再删锚点）。
7. **序列化**：成功响应统一 `ApiResponse<T>`（新）；错误统一经 `GlobalExceptionHandler`；阶段 2 结束时 knowledge 包内 Controller 不再出现手搓 `Map` 信封、`ObjectNode` 拼装与 `GoJsonBindError` 引用。

## 契约决策：错误措辞换锚（B 波唯一契约变化）

| 场景 | 旧（Go 复刻） | 新（本仓标准） |
|---|---|---|
| 校验失败（required/min/oneof） | `Key: 'CreateKnowledgeFromURLRequest.Url' Error:Field validation for 'Url' failed on the 'required' tag` | `details` = `url: 不能为空`、`page_size: 必须为 1-100 的整数`、`mode: 必须为 append 或 replace` 这类 `<snake字段>: <中文说明>`，多条以 `\n` 连接 |
| JSON 不可读 / 字段类型错 | `json: cannot unmarshal number 1.5 into Go struct field …` / Jackson 措辞混合 | `details` = `请求体格式不正确` 或 `<字段>: 类型不正确` |
| 空 body / 字面量 `"null"` | `"EOF"` | `details` = `请求体不能为空` |
| 分页参数非法（query） | `strconv.ParseInt: parsing "abc": invalid syntax` | `message` = `分页参数不合法`，`details` = `page: 必须为正整数` 等 |
| HTTP 状态 / code / message 类别文案 | 400 / 1000 / 中文类别文案 | **不变** |

前端影响 = 零（已核实：全局拦截器只读 `error.message` 用于展示、`error.code` 用于分支；全库无对知识库中文文案或 Go 格式串的匹配；无键序依赖）。唯一文案匹配点 `FAQEntryManager.vue:2120` 的 `includes('not found')` 与 404 状态码 `||` 兜底，状态码分支已覆盖。

## Review Focus

实现者按任务顺序执行；以下五类"契约暗礁"不在任何单任务的显式输入里，各任务的红外验证必须覆盖：

1. **分页边界**：`page=0 / -1 / 1.5 / abc / 99999999`——现状全 400；DTO 化后必须同样 400 且绝不 500（Task 8/9 重录 fixture 并补 `page=0`、`page=1.5` 两个新契约用例）。
2. **畸形载荷**：空 body、字面量 `"null"`、截断 JSON、字段类型错——必须 400 非 500（Task 7 新增全局契约测试钉死；`faq-create-empty`、`kg-tags-missing` 重录）。
3. **`error.code` 字符串**：`duplicate_file` / `duplicate_url` 逐字保持（Task 12 回归点名 `kg-` 系 duplicate fixture 必须原样绿）。
4. **半成功 200**：chunker preview 等 `200 + success:false` 形态不变（Task 13 回归点名 `cprev-` 系 fixture）。
5. **FAQ 导入进度轮询面**：`GET /api/v1/faq/import/progress/:taskId` 的 404 状态码、`data` 内 snake_case 字段（`progress/total/processed/display_status/success_count/...`）形状不变——`FAQEntryManager.vue` 3 秒轮询依赖（Task 2/8 回归点名）。

6. **下载文件名**：`sanitizeManualDownloadFilename` 语义与 `Content-Disposition` 输出逐字节不变（Task 5 迁移时其测试随迁）。

---

## 波次总览

| 波 | 轴 | 任务 | 契约 |
|---|---|---|---|
| A | service 拆分 | 1 Faq 共享基建 → 2 FaqImport → 3 Faq 终结 → 4 ChunkService → 5 SummaryPipeline → 6 Worker 整理 | 零变化 |
| B | Controller DTO 化 | 7 全局基建 → 8 Faq → 9 Chunk → 10 Tag → 11 KnowledgeBase → 12 Knowledge(+拆二) → 13 ChunkerDebug | 仅错误措辞 |
| C | 数据访问层与注释收尾 | 14 FaqChunkRepository → 15 ChunkRepository 重写 → 16 包内 Go 注释清零与导览 → 17 终检审计 | 零变化 |

依赖：B 波各任务依赖 Task 7；Task 14 依赖 Task 1-3（FAQ 方法的新调用方已就位）；其余 A 波任务间无依赖但按序串行（红线 #2）。

---

### Task 1: Faq 共享基建抽取（FaqService 四协作类）

**Files:**
- Create: `server/src/main/java/com/ragagent/knowledge/service/FaqGuard.java`、`FaqChunkCodec.java`、`FaqIndexWriter.java`、`FaqImportTaskStore.java`
- Modify: `server/src/main/java/com/ragagent/knowledge/service/FaqService.java`（改为委托，public API 不变）
- Test: 既有 `FaqContractTest` 等不动，全绿即验收

**Interfaces:**
- Produces（方法签名一律原样迁移，只改所属类）:
  - `FaqGuard`（@Component）：`validateFAQKnowledgeBase` / `writableFAQKnowledgeBase` / `resolveKBReadTenant` / `ensureDefaults` / `faqIndexMode` / `faqQuestionIndexMode` / `sanitizeFAQEntryPayload` / `resolveTagID` / `findOrCreateTagByName` / `validateFAQTagScope` / `validateFAQImportTags`
  - `FaqChunkCodec`（@Component）：`chunkToFAQEntry` / `buildFAQChunkContent` / `sanitizedFaqMetadata` / `setFaqMetadata` / `currentFaqMetadata`
  - `FaqIndexWriter`（@Component）：`indexFAQChunks` / `deleteFAQChunkVectors` / `requireEmbeddingModel` / `createChunks` / `ensureFAQKnowledge` / `findFAQKnowledge` / `faqIndexInfos` / `embeddingDimensions` / `findModelRow`
  - `FaqImportTaskStore`（@Component，原 FaqService 内 `public static class` 顶层化）：7 个 public 方法签名不变
- 归属规则：仅"多段共享"的方法进上述四类；单段私有方法（如导入段 20+ 个、`planFAQFields` 族、`sha256Hex`、审计三件套）**留在 FaqService**，随 Task 2/3 走。

- [ ] **Step 1: 建 `FaqImportTaskStore` 顶层类**，迁移嵌套类（progress/running/createGuards 三个 map 与 7 个方法），FaqService 构造器改注入并委托；`./gradlew :server:test --tests "com.ragagent.knowledge.*"` 绿。
- [ ] **Step 2: 依 Interfaces 清单迁移 `FaqGuard` / `FaqChunkCodec` / `FaqIndexWriter`**；FaqService 对应调用改为经注入的协作类；迁移时按注释标准重写四类 javadoc，被迁方法上的 Go 对照注释就地清洗（不变量改中性表述）。编译警告即未用注入，顺手收窄。
- [ ] **Step 3: 全量验证**：`./gradlew :server:test` 全绿；`grep -n "对照 Go" FaqGuard.java FaqChunkCodec.java FaqIndexWriter.java FaqImportTaskStore.java` 应为 0。
- [ ] **Step 4: Commit** `refactor(knowledge): 抽取 FaqService 共享基建（Guard/ChunkCodec/IndexWriter/ImportTaskStore）`

### Task 2: FaqImportService 拆出（导入与进度，~1,000 行段）

**Files:**
- Create: `server/src/main/java/com/ragagent/knowledge/service/FaqImportService.java`
- Modify: `FaqService.java`（`upsertEntries` / `getImportProgress` / `updateLastImportResultDisplayStatus` 改委托后删除实现）；`FaqController.java`（不改 URL，暂仍经 FaqService）
- Test: 既有不动；`record ImportJob` 随迁为 `FaqImportService` 嵌套 record

**Interfaces:**
- Consumes: Task 1 四协作类
- Produces: `FaqImportService`（@Service）：`upsertEntries` / `getImportProgress` / `updateLastImportResultDisplayStatus`（签名原样）；内部私有方法族（dry-run 校验、append/replace 两模式、批次执行、finalize、CSV 失败清单）整段随迁

- [ ] **Step 1: 整段迁移导入实现到 `FaqImportService`**（S9+S10 段：`processImportInner` / `validateAppendMode` / `validateReplaceMode` / `executeImportBatches` / `finalizeImport` / `markImportFailed` / `saveImportResultToDatabase` 等全部私有方法）；FaqService 委托。
- [ ] **Step 2: 验证**：`./gradlew :server:test --tests "com.ragagent.knowledge.*"` 绿；重点点名 Review Focus #5（`faq-` 系导入进度 fixture 原样绿）。
- [ ] **Step 3: Commit** `refactor(knowledge): FaqService 导入段拆出 FaqImportService`

### Task 3: FaqService 终结（命令/查询两服务 + 门面删除）

**Files:**
- Create: `knowledge/service/FaqEntryCommandService.java`、`FaqEntryQueryService.java`
- Delete: `knowledge/service/FaqService.java`
- Modify: `FaqController.java`（注入新服务）；`session/service/SessionAgentQaService.java`（`faqService.listEntries` 改注入 `FaqEntryQueryService`——全仓唯一跨包调用方）；`FaqIndexRows.java` 头部历史注释同步
- Test: 既有不动

**Interfaces:**
- Consumes: Task 1/2 全部产出
- Produces:
  - `FaqEntryQueryService`：`listEntries` / `getEntry` / `searchEntries` / `exportCsv` / `exportJson`（签名原样；导出与搜索的私有辅助随迁）
  - `FaqEntryCommandService`：`createEntry` / `updateEntry` / `addSimilarQuestions` / `updateEntryTagBatch` / `updateEntryFieldsBatch` / `deleteEntries`（签名原样；审计三件套 `recordKbActivity`/`appendSampleTitles`/`faqChunkQuestion`、`FaqFieldPlan`/`planFAQFields`、`checkFAQQuestionDuplicate`、`sha256Hex` 等随迁私有化）
  - `withXxx` record 变换系列 → 挂到 `FaqEntryDtos` 对应 record 上的静态方法（Task 8 落位，本任务先随消费方）

- [ ] **Step 1: 建 `FaqEntryQueryService`**，迁移 5 个查询方法及私有辅助；FaqController/SessionAgentQaService 改注入。
- [ ] **Step 2: 建 `FaqEntryCommandService`**，迁移 6 个命令方法及私有辅助。
- [ ] **Step 3: 删除 `FaqService.java`**；`grep -rn "FaqService" server/src/main` 应仅剩注释性提及（FaqIndexRows 一处，顺手改写）。
- [ ] **Step 4: 验证**：`./gradlew :server:test` 全绿 + 前端 `npm run test && npm run type-check`。
- [ ] **Step 5: Commit** `refactor(knowledge): FaqService 终结拆分为 Entry 命令/查询服务`

### Task 4: ChunkService 拆分（生成问题 / 版本化编辑）

**Files:**
- Create: `knowledge/service/ChunkQuestionService.java`、`ChunkEditService.java`
- Modify: `knowledge/service/ChunkAccessGuard.java`（吸收 `writableChunk` 及写路径校验段）、`ChunkController.java`、`QuestionGenerationService.java`（改注入 `ChunkQuestionService`——唯二同包调用方）
- Delete: `knowledge/service/ChunkService.java`（跨包调用方为 0，已核实）
- Test: 既有 `ChunkServiceTest`（896 行）按新归属拆为 `ChunkQuestionServiceTest` / `ChunkEditServiceTest`（用例逐条迁移，不改断言）

**Interfaces:**
- Produces（签名原样迁移）:
  - `ChunkQuestionService`：`upsertGeneratedQuestion` / `deleteGeneratedQuestion` / `regenerateChunkQuestions` + package-private `generateAndStoreQuestionsForWorker`（现被 `QuestionGenerationService` 消费，随段迁入）+ 邻块上下文/LLM 调用私有族（S2 段 477 行）
  - `ChunkEditService`：`updateDocumentChunk` / `revertDocumentChunk` / `listChunkRevisions` / `deleteChunk` / `deleteChunksByKnowledgeId` + 图片子块同步/父内容重建段
  - `ChunkAccessGuard`：`writableChunk`（public 面不变，跨文档移动拒绝等校验注释按标准重写）
  - 共享私有（`syncChunkIndex`/`mustTenantId`/`findKnowledgeRow`/`loadKnowledgeWrite`）：分别就近归属——`syncChunkIndex` 已有 `ChunkVectorIndexer` 则改调它，否则进 `ChunkEditService` 并对 `ChunkQuestionService` 开放 package-private

- [ ] **Step 1: `ChunkQuestionService` 迁移 + 对应用例拆出**；定向测试绿。
- [ ] **Step 2: `ChunkEditService` + `ChunkAccessGuard` 扩充迁移**；定向测试绿。
- [ ] **Step 3: 删 `ChunkService.java`，测试类迁移完成**；`./gradlew :server:test` 全绿；`grep -n "对照 Go" 新三文件` = 0（ChunkService 原 38 处注释随迁移清洗）。
- [ ] **Step 4: Commit** `refactor(knowledge): ChunkService 拆分为生成问题/版本化编辑服务`

### Task 5: KnowledgeSummaryPipelineService 拆分（摘要 / 文件 / 解析生命周期）

**Files:**
- Create: `knowledge/service/KnowledgeSummaryService.java`、`KnowledgeFileService.java`、`KnowledgeParseService.java`
- Delete: `knowledge/service/KnowledgeSummaryPipelineService.java`（跨包调用方为 0）
- Modify: `KnowledgeController.java`、`KnowledgeService.java`、`KnowledgeBatchOpsService.java`（改注入；`reset/updateKnowledgeRow` 同包开放点迁入 `KnowledgeFileService` 并改调用方）
- Test: 既有 `SummaryPipelineLogicTest`（178 行）随归属迁移；`sanitizeManualDownloadFilename` 相关用例随 `KnowledgeFileService` 迁移

**Interfaces:**
- Produces（签名原样）:
  - `KnowledgeSummaryService`：`regenerateKnowledgeSummary` / `requestPostProcessSummaryGeneration` / `requestKnowledgeSummaryRefresh` + SummaryStatus 常量/哨兵异常/fallback 阈值/MaxRetry 与 fan-out 私有族
  - `KnowledgeFileService`：`openKnowledgeFile` / `updateManualKnowledge` / `sanitizeManualDownloadFilename` / `updateImageInfo` + `KnowledgeFileStream` record
  - `KnowledgeParseService`：`reparseKnowledge` / `cancelKnowledgeParse`
- 注意：对 `KnowledgeService` 门面与 `@Lazy worker` 的既有环依赖缓解点原样保留（`@Lazy` 注解随迁）。

- [ ] **Step 1: 三服务按清单迁移**，调用方改注入，门面 javadoc 按标准重写（原类头"从 KnowledgeService 拆出"历史注释删除）。
- [ ] **Step 2: 验证**：`./gradlew :server:test` 全绿；Review Focus #6 点名（下载文件名用例绿）。
- [ ] **Step 3: Commit** `refactor(knowledge): SummaryPipeline 按摘要/文件/解析生命周期三拆`

### Task 6: KnowledgeProcessWorker 内部整理（不拆类）

**Files:**
- Modify: `knowledge/service/KnowledgeProcessWorker.java`（822 行）
- Test: 既有不动

**Interfaces:** 对外仅 `enqueue(String)`（实现 `KnowledgeService.KnowledgeProcessWorker` 接口），不变。

- [ ] **Step 1: `processInner`（284 行）按既有链路边界拆为私有阶段方法**（CAS 接管 → 预清理 → docreader → 分块 → 写 chunks → 向量化 → finalizing fan-out → 收口/失败清理），每阶段 ≤ 60 行、javadoc 一句话语义。
- [ ] **Step 2: 类 javadoc 重写**（状态机 + 虚拟线程队列 + 跨线程 TenantContext 显式传值三个非显然约束保留），Go 对照注释清洗。
- [ ] **Step 3: 验证 + Commit** `refactor(knowledge): KnowledgeProcessWorker 阶段方法化与注释本位化`

### Task 7: 契约基建（ApiResponse + 全局校验异常处理 + PageParams）

**Files:**
- Create: `server/src/main/java/com/ragagent/common/web/ApiResponse.java`、`PageParams.java`
- Modify: `common/error/GlobalExceptionHandler.java`（增补校验异常处理；既有方法不动）
- Test: Create `server/src/test/java/com/ragagent/common/web/ValidationContractTest.java`

**Interfaces:**
- Produces:
  - `ApiResponse<T>`：`record ApiResponse<T>(@JsonInclude(NON_NULL) boolean success, T data)`，静态工厂 `ok()` / `ok(T data)`；序列化 `{"success":true,"data":…}`，data 空时省键
  - `PageParams`：`record PageParams(@Min(1) @Max(...) Integer page, @Min(1) @Max(100) Integer pageSize)`，query 绑定用
  - `GlobalExceptionHandler` 新增：`MethodArgumentNotValidException` / `HandlerMethodValidationException` / `MethodArgumentTypeMismatchException` / `HttpMessageNotReadableException` → 400 / code 1000 / `message="请求参数不合法"`（分页参数类 = `"分页参数不合法"`）/ `details` 按"契约决策"表生成（字段级中文、`\n` 连接；空 body 与 `"null"` → `"请求体不能为空"`）

- [ ] **Step 1: 写失败契约测试**：`ValidationContractTest` 用临时探针端点（`@RestController TestOnlyController`，test 源码内）钉四类异常的 400 形态与 details 文案（含空 body、`"null"`、类型错、越界四场景——Review Focus #1/#2）。
- [ ] **Step 2: 跑测试确认红**（异常落到 500 handler）。
- [ ] **Step 3: 实现 `ApiResponse` / `PageParams` / 四个异常处理方法**；测试转绿。
- [ ] **Step 4: 全量回归**：`./gradlew :server:test` 全绿（既有 controller 未用 @Valid，不受影响）。
- [ ] **Step 5: Commit** `feat(common): ApiResponse 与全局参数校验异常处理（错误措辞换锚基建）`

### Task 8: FaqController DTO 化 + FaqDtos 分域

**Files:**
- Create: `knowledge/dto/FaqEntryDtos.java`、`FaqImportDtos.java`、`FaqSearchDtos.java`
- Delete: `knowledge/dto/FaqDtos.java`（15 record 按域迁入三文件；`withXxx` 静态变换挂对应 record）
- Modify: `FaqController.java`（9 个 rawBody 端点改 `@Valid` DTO；删 `bindBody`/`invalidRequest`/手搓 validator 文案；返回改 `ApiResponse<T>`；controller-local `IllegalStateException` handler 上移 GlobalExceptionHandler 或按语义保留并注释）
- Test: `FaqContractTest` 错误路径 fixture 重录 + raw 对比改 `ContractJson.semantic`

**Interfaces:**
- Consumes: Task 7 产出
- Produces: DTO record 加 `@JsonNaming(SnakeCaseStrategy)` + `@NotBlank`/`@NotNull`/`@Size`/`@Min` 与 oneof 枚举校验（`FaqBatchUpsertPayload.mode` 等）；时间/浮点字段的 Go 序列化注解随迁保留

- [ ] **Step 1: 契约先行**——按"契约决策"表更新 `faq-` 系错误 fixture（`faq-list-badpage` / `faq-create-empty` / `faq-tags-empty` / strconv 系等），并新增 `page=0`、`page=1.5` 两用例（Review Focus #1）；跑 `FaqContractTest` 确认红。
- [ ] **Step 2: Controller 改造 + DTO 迁移**；导入进度轮询面不动（Review Focus #5 点名回归）。
- [ ] **Step 3: 全量验证**：`./gradlew :server:test` + 前端 `npm run test && npm run type-check`。
- [ ] **Step 4: Commit** `refactor(knowledge): FaqController 标准 DTO 绑定，错误契约换本仓文案`

### Task 9: ChunkController DTO 化

**Files:**
- Create: `knowledge/dto/ChunkDtos.java`（4 个 controller 局部 record 提升：`UpdateChunkRequest`/`RevertChunkRequest`/`UpsertGeneratedQuestionRequest`/`DeleteGeneratedQuestionRequest`）
- Modify: `ChunkController.java`（4 个 rawBody + `bindPagination` 复刻全删，分页改 `PageParams`；返回 `ApiResponse<T>`；注意 chunk 系现状把分页错误放 `message`——统一到新标准，`chunk-list-badpage` fixture 重录）
- Test: `ChunkContractTest` 重录错误 fixture + 补 `page=0` 用例

- [ ] **Step 1: 契约先行**（重录 `chunk-` 系错误 fixture，确认红）。
- [ ] **Step 2: Controller 改造**；`./gradlew :server:test` 全绿 + 前端三绿。
- [ ] **Step 3: Commit** `refactor(knowledge): ChunkController 标准 DTO 绑定`

### Task 10: KnowledgeTagController DTO 化

**Files:**
- Create: `knowledge/dto/KnowledgeTagDtos.java`（并入现有 3 record + 新 `CreateTagRequest`/`UpdateTagRequest`/`DeleteTagRequest` record 化）
- Modify: `KnowledgeTagController.java`（删 `parseJsonBody`/`goStringField`/`goIntField` 逐字段 UnmarshalTypeError 复刻与 `requiredError`；3 个 rawBody 改 `@Valid`；controller-local handler 处理同 Task 8）
- Test: `KnowledgeOperationsContractTest` 重录 `kg-tags-*` 错误 fixture（含 `kg-tags-missing` 的 EOF → 新文案）

- [ ] **Step 1: 契约先行**（重录 fixture，红）→ **Step 2: 改造转绿** → **Step 3: 全量验证** → **Step 4: Commit** `refactor(knowledge): KnowledgeTagController 标准 DTO 绑定`

### Task 11: KnowledgeBaseController DTO 化

**Files:**
- Create: `knowledge/dto/KnowledgeBaseDtos.java`（`CreateKnowledgeBaseRequest`/`UpdateKnowledgeBaseRequest`/`HybridSearchRequest`/`CopyKnowledgeBaseRequest` 等）
- Modify: `KnowledgeBaseController.java`（5 个 rawBody 改 `@Valid`；`UpdateKbRequest` 私有 record 提升；create 的 legacy `cos_config` 改写逻辑保持在 service 侧调用点不变；`dropEmpty` omitempty 归一语义在 DTO 上用 `@JsonInclude(NON_DEFAULT)` 或等价物保持——行为对拍 fixture；`bindSearchParams`/`bindCopyBody` 删）
- Test: `KnowledgeSearchMoveContractTest`（已 semantic）错误 fixture 重录

- [ ] **Step 1: 契约先行** → **Step 2: 改造**（hybrid-search GET/POST 共用同一 DTO）→ **Step 3: 全量验证**（重点：hybrid search 200 路径 fixture 原样绿）→ **Step 4: Commit** `refactor(knowledge): KnowledgeBaseController 标准 DTO 绑定`

### Task 12: KnowledgeController DTO 化 + 拆二

**Files:**
- Create: `knowledge/dto/KnowledgeDtos.java`（12 个 rawBody 端点的请求 record + `MoveKnowledgeResponse` 等响应从 `KnowledgeTaskDtos` 归并）；`knowledge/controller/KnowledgeOpsController.java`
- Modify: `KnowledgeController.java`（12 个 rawBody 改 `@Valid`；删 `bindMoveBody`/`bindBatchBody`/`bindTagBody`/`bindRequiredBody`/`parseBody`/`requireField`/`duplicateResponse` 等 ~300 行绑定与信封助手）
- Test: `KnowledgeOperationsContractTest` / `KnowledgeSearchMoveContractTest` 重录错误 fixture

**Interfaces:**
- Produces: 路由全不变。`KnowledgeController` 留文档主体 18 端点（创建×3/列表/详情/批量取/spans/摘要/手工更新/重解析/取消/下载/预览/图片信息/update/delete/clear）；`KnowledgeOpsController` 接 8 端点（search/move/move-progress/tag-batch/batch-delete/batch-reparse/folder 移动与改名）。`multipart` 文件上传端点（createFromFile）用 `@RequestPart` 组合 DTO，绑定语义对拍现有 fixture；现 `duplicateResponse` 助手的 duplicate 半成功响应（200 + `success:false` + `code:"duplicate_file"`）组装逻辑**迁入 service 侧**，controller 只透传。
- Review Focus #3 点名：`kg-` 系 duplicate_file/duplicate_url fixture 原样绿。

- [ ] **Step 1: 契约先行**（重录 `ks-`/`kg-` 系错误 fixture，红）。
- [ ] **Step 2: DTO 化改造 + 拆出 `KnowledgeOpsController`**；`KnowledgeSummaryPipelineService` 残留的 `GoJsonBindError` 引用随本任务清零（knowledge.service 侧最后一处）。
- [ ] **Step 3: 全量验证 + 前端三绿** → **Step 4: Commit** `refactor(knowledge): KnowledgeController DTO 化并拆出运营操作控制器`

### Task 13: ChunkerDebugController 清洗

**Files:**
- Modify: `ChunkerDebugController.java`（局部 `PreviewRequest`/`PreviewPayload` 提升 `knowledge/dto/ChunkerDtos.java`；`GoBindException` 删，改标准 400 形态——`cprev-` 错误 fixture 重录；`GoDoubleSerializer` 等 Go 序列化注解**保留**（约束 #7）；413/504 分支不动）
- Test: `ChunkerPreviewContractTest` 重录错误 fixture；Review Focus #4 点名（`cprev-` 200 半成功形态原样绿）

- [ ] **Step 1: 契约先行** → **Step 2: 改造** → **Step 3: 全量验证** → **Step 4: Commit** `refactor(knowledge): ChunkerDebugController 去 Go 绑定复刻`

### Task 14: FaqChunkRepository 抽取

**Files:**
- Create: `knowledge/mapper/FaqChunkRepository.java`（迁 `ChunkRepository` 的「FAQ 专用」段 11 方法，签名原样）
- Modify: `ChunkRepository.java`（删该段）；调用方（A 波已就位的 Fax 服务族）改注入
- Test: `ChunkRepositoryTest` 中 FAQ 用例拆出 `FaqChunkRepositoryTest`（断言不改）

- [ ] **Step 1: 迁移 + 调用方改指**；定向测试绿。
- [ ] **Step 2: Commit** `refactor(knowledge): ChunkRepository FAQ 段独立为 FaqChunkRepository`

### Task 15: ChunkRepository 契约重写（去 GORM 复刻）

**Files:**
- Modify: `knowledge/mapper/ChunkRepository.java`（~530 行）
- Test: `ChunkRepositoryTest`（H2 集成，18 用例）断言不动——本任务是文档与命名重写，行为由该测试钉死

**Interfaces:** public 方法签名**不变**（5 个跨包消费方：EmbedChannelService / DefaultWikiImageEnricher / HybridSearchService / QaWiring / AgentToolBackends）。

- [ ] **Step 1: 类 javadoc 重写**——删除"复刻 GORM 隐式行为清单"（现 L16-69），改写为本仓数据契约文档：软删语义（三张面孔一律 `deleted_at IS NULL`）、乐观锁影响行数约定、全字段 UPDATE+`updated_at` 回写、PG/MySQL 方言分支的存在原因（jsonb 查询）。
- [ ] **Step 2: Go 命名清理**——`goTrimSpace` → `trimSpace`、`isGoSpace` → `isWhitespace`、`parseFaqMetadata` 注释去 Go 锚点；18 处 GORM 注释按"摘不变量、删锚点"清洗。
- [ ] **Step 3: `./gradlew :server:test` 全绿**（行为零变化由测试证明）→ **Step 4: Commit** `refactor(knowledge): ChunkRepository 注释与命名本位化（GORM 复刻说明退役）`

### Task 16: 知识库包注释清零与模块导览（domain / chunker / 未触碰 service）

> 背景：A/B 波只清洗被触碰文件；实测包内仍有 domain 17 文件 / chunker 14 文件 / textconv 1 文件 / service 未触碰小文件带 Go 对照注释（合计约 4,700 行）。实体层是开发者最先阅读的层，必须达到同等标准。本任务是**实质清洗任务**，不是审计。

**Files:**
- Modify: `knowledge/domain/` 全部实体（`Knowledge` / `KnowledgeBase` / `Chunk` / `FaqChunkMetadata` 等 17 个文件）、`knowledge/chunker/` 14 文件、`knowledge/textconv/` 1 文件、`knowledge/service/` 未被 A/B 波触碰的文件（`KnowledgeBaseService` / `KnowledgeTagService` / `HousekeepingService` / `SpanTracker` 等）
- Create: `knowledge/` 及 5 个子包 `package-info.java`（各 ≤ 15 行：包职责一句话 + 主要协作关系）

**Interfaces:** 纯注释与文档变更，零代码行为变化；实体上的 Go 序列化注解保留（约束 #7）。

- [ ] **Step 1: domain 实体层本位化**——每个实体的类 javadoc 重写为数据契约文档（表、唯一性、软删语义、metadata JSON 结构、状态机字段取值域），删除全部 Go 锚点；字段注释只保留非显然约束。
- [ ] **Step 2: chunker / textconv / 未触碰 service 清洗**——算法不变量（分块边界规则、头跟踪语义等）改中性表述保留，Go 对照删除；chunker 分包注释若无包级说明则补入 `package-info`。
- [ ] **Step 3: 模块导览**——`knowledge/package-info.java` 写处理链路一页导览：上传/URL/手工三入口 → `KnowledgeProcessWorker`（解析状态机）→ chunker 分块 → 向量化 → finalizing fan-out（摘要/生成问题/wiki/图谱）→ 检索消费方（session/retrieval）；FAQ 独立链路（FaqEntryCommand/Query/Import 三服务 + FaqChunkCodec）一段话。
- [ ] **Step 4: 验证**：`./gradlew :server:test` 全绿（零行为变化由测试证明）；`grep -rn "对照 Go\|GORM" server/src/main/java/com/ragagent/knowledge` = 0。
- [ ] **Step 5: Commit** `docs(knowledge): 实体契约化与包级导览，Go 对照注释清零`

### Task 17: 终检审计与文档同步

**Files:**
- Modify: `HANDOFF.md`（执行记录追加本计划完成状态）

- [ ] **Step 1: grep 审计清零复核**（main 源码 knowledge 包）：`对照 Go|GORM|对照Go` = 0；`go[A-Z]` 标识符 = 0；`String rawBody` = 0；`GoJsonBindError` = 0；`ObjectNode` 拼装（controller）= 0。有残留则回到所属任务补刀（Task 16 已消灭注释残留，此处预期为零）。
- [ ] **Step 2: 规模复查**：`wc -l` 知识库包 Java 文件，> 800 行者逐个说明（预期仅 KnowledgeProcessWorker/SpanTracker 例外且已注明）；千行类 = 0。
- [ ] **Step 3: 全量三绿**：`./gradlew :server:test` + 前端 `npm run test && npm run type-check`。
- [ ] **Step 4: 更新 HANDOFF 执行记录** → **Step 5: Commit** `docs(handoff): 知识库域 Java 本位重构完成记录`

---

## 明确不在本计划内（防止顺手扩散）

- 阶段 3 全局换锚（Problem Details / 删 Go 序列化器 / jsr310 / NON_NULL 全仓）——错误信封形态本计划保持；
- agent 域五神类（AgentEngine 等）——阶段 2 另一半，单独规划；
- `System.getenv()` 收敛、ArchUnit、Gradle 多模块——阶段 4；
- SpanTracker / HousekeepingService / chunker 包的**类拆分**——规模与内聚可接受，不拆类；其注释清洗已纳入 Task 16。
