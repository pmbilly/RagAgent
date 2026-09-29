# 知识库模块接口契约规范 v1（camelCase）

> **状态**：**v1.0（决策点 A–D 已定稿，2026-09-29）**
> **适用范围**：知识库模块全部对外 HTTP 接口——`/api/v1/knowledge-bases/**`、`/api/v1/knowledge/**`、`/api/v1/chunks/**`、`/api/v1/faq/**`（含 hybrid-search、分块调试等内部接口）
> **依据决策（2026-09-29）**：
> ① JSON 全面改用 camelCase；
> ② 顺手统一历史怪癖；
> ③ 不保留 Go 字节兼容层（`wirecompat` 不做）；
> ④ 系统未正式上线，无兼容包袱，**前端适配后端**，无过渡期。

---

## 1. 通用规范

| # | 规则 | 说明 |
|---|---|---|
| 1.1 | **JSON 字段名 = Java 字段名**（camelCase） | 禁止逐字段 `@JsonProperty`；禁止 `@JsonNaming` 命名策略做下划线转换 |
| 1.2 | 布尔字段不带 `is` 前缀 ✅定稿 | JSON = Java 字段名，如 `pinned`、`enabled`、`recommended`；禁止 `isIsPinned()` 这类双 is |
| 1.3 | 时间一律 ISO-8601 带时区字符串 | 例：`2026-09-17T15:44:16.950624+08:00`；无值 = `null` |
| 1.4 | ID 一律 `String`（UUID） | 不再有数字 ID；`tagId`、`chunkId`、`knowledgeId` 统一 |
| 1.5 | 可空字段**显式输出 `null`** | 禁止用空串 / `0` / 空数组代替 `null` |
| 1.6 | **禁止条件键** | 任何字段不得"有时出现有时消失"（历史实例：`vector_store_id`、`storage_backend_id`、`vector_store_engine_type`、`creator_name`） |
| 1.7 | 枚举输出小写字符串 | Java `enum` + `@JsonValue`，如 `"pending"`、`"failed"`；禁止魔法字符串散落 |
| 1.8 | 内部字段不外泄（白名单原则） | 明确不输出：`tenantId`、`filePath`、`storageProviderConfig`、`deletedAt`（详见 §3 第 3/4/12 条） |
| 1.9 | 键顺序 = DTO 声明顺序 | 前端不得依赖键顺序（现状的"字母序"是复刻 Go map 行为的实现细节，一并去除） |
| 1.10 | 请求体用 DTO（record） | 禁止把数据库实体直接绑定为请求参数；未提供的可选字段语义 = "不修改" |
| 1.11 | 成功/错误响应信封统一 | 见 §2 |
| 1.12 | 分层纪律 | Controller 只做参数校验与响应装配；分页/租户/软删条件统一经查询助手，不手写 |
| 1.13 | 删除类接口返回 **HTTP 204**（无响应体）✅定稿 | 前端按状态码分支处理，不再解析 `message` |
| 1.14 | **异步受理类接口返回 HTTP 202** + 任务标识 | 删除/重析/清空/搬移/复制的索引工作异步进行，202 表示"已受理未完成"；体为 `{taskId}` 或 `{deletedCount}`。**同步完成**的删除才是 204（1.13） |
| 1.15 | 创建类接口返回 **HTTP 201** + 新资源视图 | 上传/URL/手工创建文档、创建知识库、创建副本均 201 |
| 1.16 | **查询参数与路径变量同样 camelCase** | `pageSize`、`fileTypes`、`tagIds`、`folderPath`、`{tagId}`；校验文案里的字段名同步（如 `pageSize must be between 1 and 1000`） |
| 1.17 | 不返回服务端生成的 UI 文案 | 受理/操作类响应不带 `message`（文案由前端本地化）；进度类载荷的 `message` 属运行时状态，保留 |
| 1.18 | 枚举化只用于**取值由代码收敛**的字段 | 反例：文档 `type` 是"来源类型"（file/manual/passage/url/document/faq…，经 String 传参、随接入方式扩展），保留 `String`——强枚举会静默丢值 |
| 1.19 | **jsonb 字段保持不透明** | `metadata`/`relationChunks`/`indirectRelationChunks` 等 jsonb 载荷的内部键由写入方定义，读路径**不重写**（重写会导致存量数据与契约不一致）；其内部键名沿用历史格式（如 `generated_questions`） |
| 1.20 | 复合响应体用**具名键**而非并列字段 | 例：分块更新/回滚返回 `{chunk, description, summaryStatus}`（分块本身 + 所属文档摘要），不再把 `data` 与 `description` 平铺 |
| 1.21 | **导入/导出交换格式与请求侧同批改名** | FAQ 的导出 JSON 与导入解析是同一套字段（`standardQuestion` 等），必须一起改以保往返；故本批 FAQ **响应**已 camelCase，而导出/导入载荷按请求侧批次处理 |
| 1.22 | 落库 jsonb 的 DTO 保留 snake_case | `FaqImportResult`（写入文档 `last_faq_import_result` 列）与 `FaqChunkMetadata`（chunk 元数据）字段名即库内键名；改名等于改存量数据格式 |

---

## 2. 响应信封与分页（本次一并统一）

### 2.1 成功响应

| | 现状 | 目标 |
|---|---|---|
| 单资源 | `{"data": {...}, "success": true}` | **直接返回资源对象** `{...}` |
| 列表 | `{"data": [...], "success": true}` | **直接返回数组** `[...]` |
| 分页 | `{"data": [...], "page": 1, "page_size": 20, "total": 123, "success": true}` | `{"items": [...], "page": 1, "pageSize": 20, "total": 123}` |
| 删除类 | `{"message": "...", "success": true}` | **HTTP 204（无响应体）** ✅定稿 |
| 异步受理 | `{"data": {...}, "message": "...", "success": true}` | **HTTP 202** + `{...}`（任务标识/计数，无 message） |
| 创建 | `{"data": {...}, "success": true}` | **HTTP 201** + 资源视图 |

### 2.2 错误响应（全部统一）

```json
{
  "error": {
    "code": 2200,
    "message": "vector store not found",
    "details": { "field": "vectorStoreId" }
  }
}
```

- HTTP 状态码语义化：`400` 参数/业务校验、`401` 未认证、`403` 无权限、`404` 不存在、`409` 冲突、`500` 服务端错误；
- 保留数值 `code`（现有 1000–2300 号段，前端已有分支逻辑）；
- 废除特殊信封（现状：重复文档 409 走 `code/data/message/success` 独立格式）。

### 2.3 示例：创建知识库

请求（`POST /api/v1/knowledge-bases`）：

```json
{ "name": "产品手册", "description": "", "type": "document" }
```

响应 `201`：

```json
{
  "id": "c730730a-70f5-4d86-a7e1-58972cf27567",
  "name": "产品手册",
  "type": "document",
  "pinned": false,
  "processing": false,
  "chunkingConfig": { "chunkSize": 512, "chunkOverlap": 80 },
  "vectorStore": { "id": null, "name": "System default", "source": "env", "engineType": "postgres", "status": "available" },
  "createdAt": "2026-09-29T10:00:00.123456+08:00",
  "updatedAt": "2026-09-29T10:00:00.123456+08:00"
}
```

---

## 3. 历史怪癖处理单

| # | 现状（Go 遗留怪癖） | 决定 | 影响面 |
|---|---|---|---|
| 1 | `storage_backend_id`/`vector_store_id`/`vector_store_engine_type` 条件出现 | 恒输出，无值 = `null` | 前端判空逻辑简化 |
| 2 | `description`/`embedding_model_id`/`summary_model_id` 空串代替 null | 统一 `null` | 前端 `?? ""` 兜底 |
| 3 | `tenant_id` null → `0` | **删除该字段**（内部字段） | 前端若使用需改为从会话上下文取 |
| 4 | `deleted_at` 恒输出（恒 null） | **删除该字段** | 前端无需处理 |
| 5 | `creator_id` 空串表示"系统创建" | `creatorId: null` 表示系统 / 未记录 | 前端展示"系统"分支调整 |
| 6 | `vector_store_name: "System default"` 魔法字符串 + 三个扁平字段 | 收敛为 `vectorStore: {id, name, source, engineType, status}`；无绑定 = `null` | 前端取值路径变更 |
| 7 | 全部键按字母序（TreeMap 复刻 Go map） | 按 DTO 声明顺序 | 前端不得依赖顺序（现状也不应依赖） |
| 8 | 4 种隐式响应形态 | **统一为 1 个 `KnowledgeBaseResponse`**（实测 `buildSharedListItem` 是死代码，其余仅键序/两三个字段差异） | 已落地（2026-09-29 批次 1） |
| 9 | Java 侧 `isIsPinned()` 双 is | Java 字段 `pinned`，JSON `pinned` ✅定稿 | 前端取值改为 `kb.pinned` |
| 10 | 重复文档 409 特殊信封 | 统一标准错误体（§2.2） | 前端错误处理收敛 |
| 11 | 分页 `data/page/page_size/total/success` | `{items, page, pageSize, total}` | 前端分页组件适配 |
| 12 | `file_path`/`storage_provider_config` 等内部字段暴露 | 不输出内部字段；**存储提供方名以 `storageProvider` 单独下发**（UI 多模态判断需要）；凭据一律不下发——含 VLM 配置的 `apiKey`（视图层剔除） | 前端改读 `kb.storageProvider`；VLM 表单只回显 enabled/modelId |
| 13 | FAQ 检索命中 `score`/`match_type`/`matched_question` **条件出现** | ✅已落地：收敛为 `match: {score, type, matchedQuestion}`；列表/详情场景 `match: null`，不再有"有时出现有时消失"的键 |
| 14 | FAQ `tag_id` 为数字，其他 ID 为字符串 | 统一 `String` | 前端类型定义统一 |
| 15 | 数值错误码 1000–2300 | **保留**（前端已有分支），仅统一外层结构 | 无 |

---

## 4. 字段映射表（旧 snake_case → 新 camelCase）

> **数据来源**：脚本对源码静态盘点（2026-09-29），共 469 条字段声明、去重后 326 个唯一旧键，其中 **287 条需要改名**；脚本输出已人工复核异常项。
> 本表只列需要改名的键；不含下划线的单字段名（`id`、`name`、`status`、`total`、`message` 等）保持不变。
> ⚠ 本表是**前端迁移的直接依据**，Phase 2 落地前需与前端逐区确认。

<!-- 统计：字段条目 469 条，需改名 287 条，去重后唯一旧键 326 个 -->

### 知识库（Knowledge Base）

来源文件：`domain/KnowledgeBase.java`、`domain/KbChunkingConfig.java`、`domain/KbAsrConfig.java`、`domain/KbVlmConfig.java`、`domain/KbImageProcessingConfig.java`、`domain/KbIndexingStrategy.java`、`domain/KbStorageConfig.java`、`domain/KbStorageProviderConfig.java`、`dto/KnowledgeBaseDtos.java`、`dto/KnowledgeTaskDtos.java`

| 旧 JSON 键 | 新 JSON 键 | Java 字段 | 备注 |
|---|---|---|---|
| `is_temporary` | `isTemporary` | `isTemporary` |  |
| `tenant_id` | `tenantId` | `tenantId` |  |
| `creator_id` | `creatorId` | `creatorId` |  |
| `chunking_config` | `chunkingConfig` | `chunkingConfig` |  |
| `image_processing_config` | `imageProcessingConfig` | `imageProcessingConfig` |  |
| `embedding_model_id` | `embeddingModelId` | `embeddingModelId` |  |
| `summary_model_id` | `summaryModelId` | `summaryModelId` |  |
| `vlm_config` | `vlmConfig` | `vlmConfig` |  |
| `asr_config` | `asrConfig` | `asrConfig` |  |
| `storage_provider_config` | `storageProviderConfig` | `storageProviderConfig` |  |
| `storage_backend_id` | `storageBackendId` | `storageBackendId` |  |
| `storage_config` | `storageConfig` | `storageConfig` |  |
| `vector_store_id` | `vectorStoreId` | `vectorStoreId` |  |
| `extract_config` | `extractConfig` | `extractConfig` |  |
| `faq_config` | `faqConfig` | `faqConfig` |  |
| `question_generation_config` | `questionGenerationConfig` | `questionGenerationConfig` |  |
| `auto_tag_config` | `autoTagConfig` | `autoTagConfig` |  |
| `wiki_config` | `wikiConfig` | `wikiConfig` |  |
| `indexing_strategy` | `indexingStrategy` | `indexingStrategy` |  |
| `created_at` | `createdAt` | `createdAt` |  |
| `updated_at` | `updatedAt` | `updatedAt` |  |
| `deleted_at` | `deletedAt` | `deletedAt` |  |
| `is_pinned` | `isPinned` | `isPinned` |  |
| `pinned_at` | `pinnedAt` | `pinnedAt` |  |
| `knowledge_count` | `knowledgeCount` | `knowledgeCount` |  |
| `chunk_count` | `chunkCount` | `chunkCount` |  |
| `is_processing` | `isProcessing` | `isProcessing` |  |
| `processing_count` | `processingCount` | `processingCount` |  |
| `share_count` | `shareCount` | `shareCount` |  |
| `creator_name` | `creatorName` | `creatorName` |  |
| `chunk_size` | `chunkSize` | `chunkSize` |  |
| `chunk_overlap` | `chunkOverlap` | `chunkOverlap` |  |
| `parser_engine_rules` | `parserEngineRules` | `parserEngineRules` |  |
| `enable_parent_child` | `enableParentChild` | `enableParentChild` |  |
| `parent_chunk_size` | `parentChunkSize` | `parentChunkSize` |  |
| `child_chunk_size` | `childChunkSize` | `childChunkSize` |  |
| `token_limit` | `tokenLimit` | `tokenLimit` |  |
| `table_metadata_instructions` | `tableMetadataInstructions` | `tableMetadataInstructions` |  |
| `file_types` | `fileTypes` | `fileTypes` |  |
| `xlsx_first_row_as_header` | `xlsxFirstRowAsHeader` | `xlsxFirstRowAsHeader` |  |
| `model_id` | `modelId` | `modelId` |  |
| `model_id` | `modelId` | `modelId` |  |
| `description_language` | `descriptionLanguage` | `descriptionLanguage` |  |
| `custom_instructions` | `customInstructions` | `customInstructions` |  |
| `model_name` | `modelName` | `modelName` |  |
| `base_url` | `baseUrl` | `baseUrl` |  |
| `api_key` | `apiKey` | `apiKey` |  |
| `interface_type` | `interfaceType` | `interfaceType` |  |
| `model_id` | `modelId` | `modelId` |  |
| `vector_enabled` | `vectorEnabled` | `vectorEnabled` |  |
| `keyword_enabled` | `keywordEnabled` | `keywordEnabled` |  |
| `wiki_enabled` | `wikiEnabled` | `wikiEnabled` |  |
| `graph_enabled` | `graphEnabled` | `graphEnabled` |  |
| `secret_id` | `secretId` | `secretId` |  |
| `secret_key` | `secretKey` | `secretKey` |  |
| `bucket_name` | `bucketName` | `bucketName` |  |
| `app_id` | `appId` | `appId` |  |
| `path_prefix` | `pathPrefix` | `pathPrefix` |  |
| `use_ssl` | `useSsl` | `useSsl` |  |
| `force_path_style` | `forcePathStyle` | `forcePathStyle` |  |
| `query_text` | `queryText` | `queryText` |  |
| `query_embedding` | `queryEmbedding` | `queryEmbedding` |  |
| `vector_threshold` | `vectorThreshold` | `vectorThreshold` |  |
| `keyword_threshold` | `keywordThreshold` | `keywordThreshold` |  |
| `match_count` | `matchCount` | `matchCount` |  |
| `disable_keywords_match` | `disableKeywordsMatch` | `disableKeywordsMatch` |  |
| `disable_vector_match` | `disableVectorMatch` | `disableVectorMatch` |  |
| `skip_context_enrichment` | `skipContextEnrichment` | `skipContextEnrichment` |  |
| `knowledge_base_ids` | `knowledgeBaseIds` | `knowledgeBaseIds` |  |
| `knowledge_ids` | `knowledgeIds` | `knowledgeIds` |  |
| `source_id` | `sourceId` | `sourceId` |  |
| `target_id` | `targetId` | `targetId` |  |
| `document_count` | `documentCount` | `documentCount` |  |
| `task_id` | `taskId` | `taskId` |  |
| `source_kb_id` | `sourceKbId` | `sourceKbId` |  |
| `target_kb_id` | `targetKbId` | `targetKbId` |  |
| `knowledge_count` | `knowledgeCount` | `knowledgeCount` |  |
| `source_id` | `sourceId` | `sourceId` |  |
| `target_id` | `targetId` | `targetId` |  |
| `knowledge_base` | `knowledgeBase` | `knowledgeBase` |  |
| `created_at` | `createdAt` | `createdAt` |  |
| `updated_at` | `updatedAt` | `updatedAt` |  |

### 文档（Knowledge）

来源文件：`domain/Knowledge.java`、`dto/KnowledgeDtos.java`

| 旧 JSON 键 | 新 JSON 键 | Java 字段 | 备注 |
|---|---|---|---|
| `tenant_id` | `tenantId` | `tenantId` |  |
| `knowledge_base_id` | `knowledgeBaseId` | `knowledgeBaseId` |  |
| `parse_status` | `parseStatus` | `parseStatus` |  |
| `pending_subtasks_count` | `pendingSubtasksCount` | `pendingSubtasksCount` |  |
| `summary_status` | `summaryStatus` | `summaryStatus` |  |
| `enable_status` | `enableStatus` | `enableStatus` |  |
| `embedding_model_id` | `embeddingModelId` | `embeddingModelId` |  |
| `file_name` | `fileName` | `fileName` |  |
| `folder_path` | `folderPath` | `folderPath` |  |
| `file_type` | `fileType` | `fileType` |  |
| `file_size` | `fileSize` | `fileSize` |  |
| `file_hash` | `fileHash` | `fileHash` |  |
| `file_path` | `filePath` | `filePath` |  |
| `storage_size` | `storageSize` | `storageSize` |  |
| `custom_metadata` | `customMetadata` | `customMetadata` |  |
| `last_faq_import_result` | `lastFaqImportResult` | `lastFaqImportResult` |  |
| `created_at` | `createdAt` | `createdAt` |  |
| `updated_at` | `updatedAt` | `updatedAt` |  |
| `processed_at` | `processedAt` | `processedAt` |  |
| `error_message` | `errorMessage` | `errorMessage` |  |
| `deleted_at` | `deletedAt` | `deletedAt` |  |
| `knowledge_base_name` | `knowledgeBaseName` | `knowledgeBaseName` |  |
| `file_name` | `fileName` | `fileName` |  |
| `file_type` | `fileType` | `fileType` |  |
| `kb_id` | `kbId` | `kbId` |  |
| `knowledge_ids` | `knowledgeIds` | `knowledgeIds` |  |
| `source_kb_id` | `sourceKbId` | `sourceKbId` |  |
| `target_kb_id` | `targetKbId` | `targetKbId` |  |
| `page_size` | `pageSize` | `pageSize` |  |
| `deleted_count` | `deletedCount` | `deletedCount` |  |
| `task_id` | `taskId` | `taskId` |  |

### 分块（Chunk）

来源文件：`domain/Chunk.java`、`domain/ChunkRevision.java`、`domain/DocumentChunkMetadata.java`、`domain/GeneratedQuestion.java`、`dto/ChunkDtos.java`

| 旧 JSON 键 | 新 JSON 键 | Java 字段 | 备注 |
|---|---|---|---|
| `seq_id` | `seqId` | `seqId` |  |
| `tenant_id` | `tenantId` | `tenantId` |  |
| `knowledge_id` | `knowledgeId` | `knowledgeId` |  |
| `knowledge_base_id` | `knowledgeBaseId` | `knowledgeBaseId` |  |
| `tag_id` | `tagId` | `tagId` |  |
| `content_revision` | `contentRevision` | `contentRevision` |  |
| `index_status` | `indexStatus` | `indexStatus` |  |
| `last_editor_id` | `lastEditorId` | `lastEditorId` |  |
| `chunk_index` | `chunkIndex` | `chunkIndex` |  |
| `is_enabled` | `isEnabled` | `isEnabled` |  |
| `start_at` | `startAt` | `startAt` |  |
| `end_at` | `endAt` | `endAt` |  |
| `pre_chunk_id` | `preChunkId` | `preChunkId` |  |
| `next_chunk_id` | `nextChunkId` | `nextChunkId` |  |
| `chunk_type` | `chunkType` | `chunkType` |  |
| `parent_chunk_id` | `parentChunkId` | `parentChunkId` |  |
| `relation_chunks` | `relationChunks` | `relationChunks` |  |
| `indirect_relation_chunks` | `indirectRelationChunks` | `indirectRelationChunks` |  |
| `content_hash` | `contentHash` | `contentHash` |  |
| `image_info` | `imageInfo` | `imageInfo` |  |
| `created_at` | `createdAt` | `createdAt` |  |
| `updated_at` | `updatedAt` | `updatedAt` |  |
| `deleted_at` | `deletedAt` | `deletedAt` |  |
| `tenant_id` | `tenantId` | `tenantId` |  |
| `knowledge_base_id` | `knowledgeBaseId` | `knowledgeBaseId` |  |
| `knowledge_id` | `knowledgeId` | `knowledgeId` |  |
| `chunk_id` | `chunkId` | `chunkId` |  |
| `is_enabled` | `isEnabled` | `enabled` | 源码核对：Java 字段非驼峰形式 |
| `editor_id` | `editorId` | `editorId` |  |
| `edit_source` | `editSource` | `editSource` |  |
| `edited_at` | `editedAt` | `editedAt` |  |
| `created_at` | `createdAt` | `createdAt` |  |
| `generated_questions` | `generatedQuestions` | `generatedQuestions` |  |
| `generated_questions_revision` | `generatedQuestionsRevision` | `generatedQuestionsRevision` |  |
| `content_revision` | `contentRevision` | `contentRevision` |  |
| `is_enabled` | `isEnabled` | `isEnabled` |  |
| `expected_revision` | `expectedRevision` | `expectedRevision` |  |
| `question_id` | `questionId` | `questionId` |  |

### FAQ

来源文件：`domain/FaqChunkMetadata.java`、`dto/FaqEntryDtos.java`、`dto/FaqImportDtos.java`、`dto/FaqSearchDtos.java`

| 旧 JSON 键 | 新 JSON 键 | Java 字段 | 备注 |
|---|---|---|---|
| `standard_question` | `standardQuestion` | `standardQuestion` |  |
| `similar_questions` | `similarQuestions` | `similarQuestions` |  |
| `negative_questions` | `negativeQuestions` | `negativeQuestions` |  |
| `answer_strategy` | `answerStrategy` | `answerStrategy` |  |
| `chunk_id` | `chunkId` | `chunkId` |  |
| `knowledge_id` | `knowledgeId` | `knowledgeId` |  |
| `knowledge_base_id` | `knowledgeBaseId` | `knowledgeBaseId` |  |
| `tag_id` | `tagId` | `tagId` |  |
| `tag_name` | `tagName` | `tagName` |  |
| `is_enabled` | `isEnabled` | `isEnabled` |  |
| `is_recommended` | `isRecommended` | `isRecommended` |  |
| `standard_question` | `standardQuestion` | `standardQuestion` |  |
| `similar_questions` | `similarQuestions` | `similarQuestions` |  |
| `negative_questions` | `negativeQuestions` | `negativeQuestions` |  |
| `answer_strategy` | `answerStrategy` | `answerStrategy` |  |
| `index_mode` | `indexMode` | `indexMode` |  |
| `updated_at` | `updatedAt` | `updatedAt` |  |
| `created_at` | `createdAt` | `createdAt` |  |
| `match_type` | `matchType` | `matchType` |  |
| `chunk_type` | `chunkType` | `chunkType` |  |
| `matched_question` | `matchedQuestion` | `matchedQuestion` |  |
| `by_id` | `byId` | `byId` |  |
| `by_tag` | `byTag` | `byTag` |  |
| `exclude_ids` | `excludeIds` | `excludeIds` |  |
| `display_status` | `displayStatus` | `displayStatus` |  |
| `knowledge_id` | `knowledgeId` | `knowledgeId` |  |
| `task_id` | `taskId` | `taskId` |  |
| `failure_type` | `failureType` | `failureType` |  |
| `is_partial_failure` | `isPartialFailure` | `isPartialFailure` |  |
| `tag_name` | `tagName` | `tagName` |  |
| `standard_question` | `standardQuestion` | `standardQuestion` |  |
| `similar_questions` | `similarQuestions` | `similarQuestions` |  |
| `negative_questions` | `negativeQuestions` | `negativeQuestions` |  |
| `answer_all` | `answerAll` | `answerAll` |  |
| `is_disabled` | `isDisabled` | `isDisabled` |  |
| `removed_similar_questions` | `removedSimilarQuestions` | `removedSimilarQuestions` |  |
| `removed_negative_questions` | `removedNegativeQuestions` | `removedNegativeQuestions` |  |
| `answer_changed` | `answerChanged` | `answerChanged` |  |
| `new_similar_count` | `newSimilarCount` | `newSimilarCount` |  |
| `new_negative_count` | `newNegativeCount` | `newNegativeCount` |  |
| `seq_id` | `seqId` | `seqId` |  |
| `tag_id` | `tagId` | `tagId` |  |
| `kb_id` | `kbId` | `kbId` |  |
| `success_count` | `successCount` | `successCount` |  |
| `failed_count` | `failedCount` | `failedCount` |  |
| `partial_failed_count` | `partialFailedCount` | `partialFailedCount` |  |
| `skipped_count` | `skippedCount` | `skippedCount` |  |
| `failed_entries` | `failedEntries` | `failedEntries` |  |
| `failed_entries_url` | `failedEntriesUrl` | `failedEntriesUrl` |  |
| `success_entries` | `successEntries` | `successEntries` |  |
| `valid_entry_indices` | `validEntryIndices` | `validEntryIndices` |  |
| `merge_entry_indices` | `mergeEntryIndices` | `mergeEntryIndices` |  |
| `merged_count` | `mergedCount` | `mergedCount` |  |
| `added_count` | `addedCount` | `addedCount` |  |
| `merge_details` | `mergeDetails` | `mergeDetails` |  |
| `created_at` | `createdAt` | `createdAt` |  |
| `updated_at` | `updatedAt` | `updatedAt` |  |
| `dry_run` | `dryRun` | `dryRun` |  |
| `import_mode` | `importMode` | `importMode` |  |
| `imported_at` | `importedAt` | `importedAt` |  |
| `display_status` | `displayStatus` | `displayStatus` |  |
| `processing_time` | `processingTime` | `processingTime` |  |
| `query_text` | `queryText` | `queryText` |  |
| `vector_threshold` | `vectorThreshold` | `vectorThreshold` |  |
| `match_count` | `matchCount` | `matchCount` |  |
| `first_priority_tag_ids` | `firstPriorityTagIds` | `firstPriorityTagIds` |  |
| `second_priority_tag_ids` | `secondPriorityTagIds` | `secondPriorityTagIds` |  |
| `only_recommended` | `onlyRecommended` | `onlyRecommended` |  |

### 标签（Tag）

来源文件：`domain/KnowledgeTag.java`、`dto/KnowledgeTagDtos.java`

| 旧 JSON 键 | 新 JSON 键 | Java 字段 | 备注 |
|---|---|---|---|
| `seq_id` | `seqId` | `seqId` |  |
| `tenant_id` | `tenantId` | `tenantId` |  |
| `knowledge_base_id` | `knowledgeBaseId` | `knowledgeBaseId` |  |
| `sort_order` | `sortOrder` | `sortOrder` |  |
| `created_at` | `createdAt` | `createdAt` |  |
| `updated_at` | `updatedAt` | `updatedAt` |  |
| `knowledge_count` | `knowledgeCount` | `knowledgeCount` |  |
| `chunk_count` | `chunkCount` | `chunkCount` |  |
| `page_size` | `pageSize` | `pageSize` |  |
| `exclude_ids` | `excludeIds` | `excludeIds` |  |

### 分块调试（Chunker Debug，内部）

来源文件：`controller/ChunkerDebugController.java`、`dto/ChunkerDtos.java`

| 旧 JSON 键 | 新 JSON 键 | Java 字段 | 备注 |
|---|---|---|---|
| `selected_tier` | `selectedTier` | `selectedTier` |  |
| `tier_chain` | `tierChain` | `tierChain` |  |
| `total_chars` | `totalChars` | `totalChars` |  |
| `total_lines` | `totalLines` | `totalLines` |  |
| `avg_line_len` | `avgLineLen` | `avgLineLen` |  |
| `std_line_len` | `stdLineLen` | `stdLineLen` |  |
| `md_heading_counts` | `mdHeadingCounts` | `mdHeadingCounts` |  |
| `md_heading_total` | `mdHeadingTotal` | `mdHeadingTotal` |  |
| `numbered_section_count` | `numberedSectionCount` | `numberedSectionCount` |  |
| `all_caps_short_line_count` | `allCapsShortLineCount` | `allCapsShortLineCount` |  |
| `blank_paragraph_breaks` | `blankParagraphBreaks` | `blankParagraphBreaks` |  |
| `form_feed_count` | `formFeedCount` | `formFeedCount` |  |
| `visual_sep_count` | `visualSepCount` | `visualSepCount` |  |
| `german_chapter_count` | `germanChapterCount` | `germanChapterCount` |  |
| `english_chapter_count` | `englishChapterCount` | `englishChapterCount` |  |
| `chinese_chapter_count` | `chineseChapterCount` | `chineseChapterCount` |  |
| `repeated_footer_count` | `repeatedFooterCount` | `repeatedFooterCount` |  |
| `has_tables` | `hasTables` | `hasTables` |  |
| `has_code` | `hasCode` | `hasCode` |  |
| `code_ratio` | `codeRatio` | `codeRatio` |  |
| `detected_langs` | `detectedLangs` | `detectedLangs` |  |
| `size_chars` | `sizeChars` | `sizeChars` |  |
| `size_tokens_approx` | `sizeTokensApprox` | `sizeTokensApprox` |  |
| `context_header` | `contextHeader` | `contextHeader` |  |
| `avg_chars` | `avgChars` | `avgChars` |  |
| `min_chars` | `minChars` | `minChars` |  |
| `max_chars` | `maxChars` | `maxChars` |  |
| `stddev_chars` | `stddevChars` | `stddevChars` |  |
| `truncated_to` | `truncatedTo` | `truncatedTo` |  |
| `chunk_size` | `chunkSize` | `chunkSize` |  |
| `chunk_overlap` | `chunkOverlap` | `chunkOverlap` |  |
| `enable_parent_child` | `enableParentChild` | `enableParentChild` |  |
| `parent_chunk_size` | `parentChunkSize` | `parentChunkSize` |  |
| `child_chunk_size` | `childChunkSize` | `childChunkSize` |  |
| `token_limit` | `tokenLimit` | `tokenLimit` |  |

### 内部任务载荷（不对外暴露）

来源文件：`service/ExtractChunkPayload.java`、`service/QuestionBatchPayload.java`

| 旧 JSON 键 | 新 JSON 键 | Java 字段 | 备注 |
|---|---|---|---|
| `tenant_id` | `tenantId` | `tenantId` |  |
| `chunk_id` | `chunkId` | `chunkId` |  |
| `model_id` | `modelId` | `modelId` |  |
| `knowledge_id` | `knowledgeId` | `knowledgeId` |  |
| `chunk_index` | `chunkIndex` | `chunkIndex` |  |
| `lf_trace_id` | `lfTraceId` | `lfTraceId` |  |
| `lf_parent_obs_id` | `lfParentObsId` | `lfParentObsId` |  |
| `lf_traceparent` | `lfTraceparent` | `lfTraceparent` |  |
| `lf_user_id` | `lfUserId` | `lfUserId` |  |
| `lf_session_id` | `lfSessionId` | `lfSessionId` |  |
| `tenant_id` | `tenantId` | `tenantId` |  |
| `knowledge_base_id` | `knowledgeBaseId` | `knowledgeBaseId` |  |
| `knowledge_id` | `knowledgeId` | `knowledgeId` |  |
| `question_count` | `questionCount` | `questionCount` |  |
| `chunk_ids` | `chunkIds` | `chunkIds` |  |
| `batch_index` | `batchIndex` | `batchIndex` |  |
| `prev_chunk_id` | `prevChunkId` | `prevChunkId` |  |
| `next_chunk_id` | `nextChunkId` | `nextChunkId` |  |
| `lf_trace_id` | `lfTraceId` | `lfTraceId` |  |
| `lf_parent_obs_id` | `lfParentObsId` | `lfParentObsId` |  |
| `lf_traceparent` | `lfTraceparent` | `lfTraceparent` |  |
| `lf_user_id` | `lfUserId` | `lfUserId` |  |
| `lf_session_id` | `lfSessionId` | `lfSessionId` |  |


---

## 5. 消费者改造清单

| 消费者 | 位置 | 工作量估计 |
|---|---|---|
| 前端 | `frontend/src/api/knowledge-base/index.ts`（API 层与类型）+ 26 个引用文件 | 建议后端 DTO 生成 TS 类型，杜绝手工漂移 |
| MCP Server | `mcp-server/weknora_mcp_server.py`（依赖 35+ 个下划线字段：`tag_ids`、`knowledge_base_id`、`summary_model_id`、`query_text`、`match_count`、`page_size` 等） | 约 1 天；系统未上线，可直接改 |
| 后端测试 | 6 个契约测试（`KnowledgeContractTest`、`ChunkContractTest`、`FaqContractTest`、`KnowledgeOperationsContractTest`、`KnowledgeSearchMoveContractTest`、`ChunkerPreviewContractTest`）+ `TestSchema` | Phase 2 转换为新契约快照测试 |
| 对拍数据 | `GoRecording*.java` 中知识库相关常量 | Phase 3 随对拍退役删除 |

---

## 6. 已定稿决策（A–D，2026-09-29）

| 编号 | 决策点 | 定稿结论 | 落地要求 |
|---|---|---|---|
| **A** | 布尔字段命名 | **不保留 `is` 前缀**：JSON = Java 字段名（`pinned`/`enabled`/`recommended`） | 前端取值改为 `kb.pinned`；后端禁止 `@JsonProperty("is_xxx")` 与双 is 命名 |
| **B** | 响应信封统一 | **本期一起做**（§2.1 / §2.2 全部落地）：成功直接返回资源/数组，分页 `{items,page,pageSize,total}`，错误统一 `{error:{code,message,details}}` | 前端统一请求层改造（拦截器级），取消逐接口解析 `data`/`success` |
| **C** | 枚举化范围 | **对外契约字段全枚举化**（`status`/`type`/`answerStrategy`/`indexMode` 等），输出小写字符串；内部 DTO 不强制 | 后端用 `enum` + `@JsonValue`；前端用字符串联合类型承接 |
| **D** | 删除类接口语义 | **HTTP 204（无响应体）** | 前端按状态码处理，不再解析 `message` 字段 |

> 以上四项自 Phase 2 起生效；Phase 0–1 期间 JSON 保持不变（结构重构 + 测试护栏）。

---

## 7. 与其他文档的关系

- 本规范是重构方案（v2/v3）中 **Phase 2 的契约基线**；
- Phase 0–1 期间 JSON 保持不变（结构重构 + 测试护栏），本规范自 Phase 2 起生效；
- 字段盘点脚本与原始输出可在评审时索取复跑，确保与源码同步。

---

## 8. 实施进度（滚动更新）

| 批次 | 内容 | 状态 |
|---|---|---|
| **1** | 知识库对象：camelCase + 裸资源信封 + 删除 204 + 内部字段收敛（含 `storageProvider`） | ✅ 2026-09-29（后端 `8dab32e`/`cb960a8`、前端 `87c090b`；后端 4665 用例 + 前端 685 用例全绿） |
| **2** | 嵌套配置对象：`chunkingConfig`/`imageProcessingConfig`/`vlmConfig`/`asrConfig`/`indexingStrategy` 改**视图 DTO**（camelCase + 显式 null + 去 apiKey） | ✅ 2026-09-29（新增 `KnowledgeBaseConfigViews`；领域类 Jackson 注解继续决定 jsonb 落库格式，二者解耦） |
| **3a** | **创建知识库**请求 DTO 化（不再绑定数据库实体）：新增 `CreateKbRequest`（复用批次 2 视图 + VLM 请求形态含 apiKey），删除 omitempty 归一三辅助；前端创建载荷同步 camelCase | ✅ 2026-09-29 |
| **3b** | 其余请求侧 DTO 化：`updateKnowledge`（显式部分更新 DTO）、chunk 删除生成问题、批量重解析、标签删除（复用既有 DTO 并去 snake_case）；4 处手搓解析全部删除。仅 `POST /knowledge/{id}/reparse` 保留忽略体（有意为之：只做语法校验） | ✅ 2026-09-29 |
| **4** | 知识库模块停用 `Go*` 序列化器（时间走全局 OffsetDateTime 序列化器，double 走标准 Jackson；`GoJsonBindError` 改标准文案）+ 注释打磨（黑话转人话、清空 JavaDoc） | ✅ 2026-09-29 |

> **四批收官（2026-09-29）**：知识库模块的对外契约与请求侧已完成 Java 本位化——camelCase、裸资源信封、显式 null、DTO 解耦（实体不再直连 API）、手搓解析清零、Go 序列化器不再被引用。
> **仍待办（后续专项）**：① 全仓序列化层一次性删除（本模块已不再引用，删除需全仓同批，见 HANDOFF 判定）；② CI 门禁（Checkstyle/ArchUnit）与规范文档；③ 其余域（agent/session/wiki…）的同类改造。
