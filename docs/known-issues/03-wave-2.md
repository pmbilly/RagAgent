# 已确认细节与坑 · 波 2（chunk / knowledge / FAQ / 基础设施配置 / 成员 / 系统管理端 / 扫尾）

> 本文件是 `docs/translation-conventions.md` §9 的一个分片（按批次拆分，**内容未改动**）。
> 代码注释与任务书里的「约定 §9「XXX」」按条目标题在本目录内检索即可。
> 回链：[`translation-conventions.md`](../translation-conventions.md) §9 索引 ｜
> 同目录兄弟文件：00 基础 / 01 阶段 4.1–5.2 / 02 波 0–1 / 03 波 2 / 04 波 3 / 05 波 4 / 06 W5。


- **波 2 chunk（编辑面）新确认的细节与坑——前四条都会复发**：
  1. **同一个 service 异常在三个 handler 的 HTTP 形态各不相同**（本轮最重要的发现）：
     `UpdateDocumentChunk` 的业务失败（空内容/加图/非 text/超 200000 字节）在 Go 是
     `fmt.Errorf` → update handler 包 **500** 信封 code=1007 且 message=原文；
     revert handler 对非 AppError 包 **400**；questions 三端点把**一切**错误包成
     **400** `NewBadRequestError(err.Error())`（含 AppError 的双前缀原文）。golden 实测：
     `PUT` 空内容 → 500 `"chunk content cannot be empty"`。**不能给 ChunkService 写一个
     统一的异常映射**——每个端点单独 catch。
  2. **分页钳位是三段 if，不是 clamp**（golden 抓回的真 bug）：Go L117-125 是
     `page<1→1`（**无上限**，page=5 合法）、`size<1→10`（**缺省语义**，size=2 是合法值
     不会被抬高）、`size>100→100`。写成 `clamp(size, 10, 100)` 会让 page_size=2 静默
     变 10、clamp(page,1,1) 让所有页码变 1——列表"看起来对"但翻页坏了。
  3. **revert 未知 revision → 400 "record not found"**（gorm 原文透传，非 404）；
     `revision` 缺失 → validator 原文 `Key: 'RevertChunkRequest.Revision' ... 'required' tag`
     （required 挂在 ***int** 上，`null`/缺失都触发）；`question:"   "` **过** binding
     （required 对 string 是"非零值"），由 service 落 `"question cannot be empty"`——
     与 G4 的"required 先于业务 trim"是**相反**的顺序，按端点实录。
  4. **delete-question 的模型链在 metadata 变更之前**：无 embedding 模型的 KB 上
     `DELETE /chunks/by-id/:id/questions` 三连发全部落 400
     `"failed to get embedding model: model ID cannot be empty"`——问题**从未**被真正
     删除，metadata 不变。service 内的顺序（writableChunk → 找问题 → kb → engine → model）
     必须逐字照抄，不能把"找不到问题"的 400 提前到模型校验之后。
  5. **gin.H 字母序的两个新形态**：update/revert 成功响应是
     `data < description < success < summary_status`；list 是
     `data < page < page_size < success < total`。knowledge 重载失败时
     description/summary_status 两个键**整体缺席**（只剩 data+success）。
  6. **Chunk 的响应化注解**：`source_content`/`context_header` 是 `json:"-"`；
     三个 json 列（relation_chunks/indirect_relation_chunks/metadata）对照
     types.JSON.MarshalJSON——空输出 `null`；**7 个非指针 string 列的 getter 归一化
     NULL→""**（tag_id/parent_chunk_id/pre_chunk_id/next_chunk_id/content_hash/
     last_editor_id/image_info，对照 GORM 扫描 NULL 进非指针 string 的零值语义——
     H2 列可空，不归一化会输出 `null`）。`is_enabled` 字段名带 is 前缀但
     getter `isIsEnabled()` 隐式属性名与字段一致 → 合并成一个属性，安全。
  7. **ChunkAccessGuard 的分层与放行语义**（对照 RequireOwnershipOrRole +
     RequireKBAccess 的中间件链）：ownership 守卫里资源在调用者空间**不存在 → 放行**
     （ErrResourceNotFound 透传，交给后续守卫/handler 出 404）；KB 访问层
     knowledge 缺失 → 404 `"Knowledge not found"`（大写 K）、chunk 缺失 → 404
     `"Chunk not found"`、KB 缺失 → 404 `"knowledge base not found"`（小写 k）、
     跨租户 → 403 信封 `"Permission denied to access this knowledge base"`、
     非创建者写 → 403 **纯字符串**。by-id 的 ownership 查找显式重校验租户
     （GetChunkByIDOnly 无空间过滤）。判定顺序 golden 依赖，不能重排。
  8. **契约测试的固定 id 必须是纯十六进制**：掩码正则认 `[0-9a-f-]`，`kkk…` 开头的
     种子 id 不会被掩码 → 与 golden 的真 uuid 对不上。A/B 的 seq_id 来自 PG 序列、
     两侧必然不同 → A/B 里掩码 `"seq_id":`，契约测试里播种精确复刻 golden 值。
  9. **A/B 残留清理要含 chunk_revisions**：只清 chunks/knowledges 的话，上一轮的
     revision 快照会让下一次 update 撞 `idx_chunk_revisions_chunk_revision` 唯一索引
     ——两侧同型 500 但 JDBC 错误包装文案不同 → DIFF。幂等种子 =
     `DELETE chunk_revisions → chunks → knowledges`（外键序）。
  10. **旧 Java server 进程占 8082**（本轮实际踩到）：上次会话的 bootRun 还在跑时，
     新启动端口冲突直接 BUILD FAILED，但 `wait_for_port` 打到**旧进程**照样报 ready
     ——表现为"新路由 404"。`java-server-up.sh` 前先 `kill` 旧进程（`lsof -i :8082`）。
  - 已知差异（记录在 ChunkService 类注释）：syncChunkIndex 只对齐"策略关 → 早退"分支
    （测试数据全走这条）；KB 需要 embedding 时，模型行缺失与 Go 同形报错，模型存在则
    Java 恒 failed（检索引擎未接线，随波 3/4）；Regenerate 的 LLM 生成步降级
    （summary model 存在时报 "summary model is not available in this deployment"，随阶段 7）；
    delete-question 的向量删除 WARN+no-op（模型行校验保留）。
- **波 2 knowledge 文档操作面（15+1 条路由）新确认的细节与坑——前四条都会复发**：
  1. **⚠️ EnsureDefaults 会把"全关索引"重置成默认值**（本轮最值钱的发现）：
     Go 每次经 `knowledgeBaseService.GetKnowledgeBaseByID` 读 KB 都跑
     `kb.EnsureDefaults()`，其中 `IndexingStrategy.IsZero()`（vector/keyword/wiki/graph
     **全 false**）会被替换成 `DefaultIndexingStrategy()`（vector+keyword=true）——
     所以"用 SQL 关掉全部索引"对 service 层的 NeedsEmbedding 判定**无效**。
     实测：UpdateImageInfo 链尾的 updateChunkVector 恒走
     `GetEmbeddingModel("")` → 500 `"model ID cannot be empty"`（golden
     kg-image-update/again/mismatch 钉住）。Java 侧照抄：读 KB 后
     `isZero() → defaultStrategy()` 再判 NeedsEmbedding。同理"关 summary model"只影响
     regenerate-summary 的判定（那里读的是 SummaryModelID，不受 EnsureDefaults 影响）。
  2. **ValidateInput 的 XSS 正则要求闭合标签**：`"<script>x"` **不**命中
     `<script[^>]*>.*?</script>`，会通过校验并归一化成文件夹 `<scriptx`
     （golden kg-move-badpath=200、kg-rename-badto=200 moved_count=2 钉住）。
     common.security.InputSanitizer 的 16 条正则逐条照抄，别"补全"安全性。
  3. **同一个"跨租户"有两种 403 文案，取决于守卫在哪一层**：路由挂了
     KBAccessFromKnowledgeIDParam 的（stages/spans/download/preview/reparse/cancel/
     manual/image/regenerate）在中间件层拒绝 → `"Permission denied to access this
     knowledge base"`；body 路由（/knowledge/tags 无 kb_id，从首条 knowledge 推导 KB）
     在 handler 的 resolveKnowledgeAndValidateKBAccess 里拒绝 →
     `"Permission denied to access this knowledge"`（不带 base）。判定顺序：
     tags 路径的 handler 链**不做** requireKbAccess 的 KB 查询（Go 直接用
     knowledge 行上的 tenantID 判），Java 侧单独走 resolveKnowledgeHandlerLevel。
  4. **clear-contents 的两次连续调用都是 "task submitted"+相同计数**：Go 异步
     worker 没跑完时第二次 list 仍看到行。Java 用 parse_status='deleting' 标记 +
     计数复刻该窗口（golden kg-clear-again 钉住）；list 侧只排除 parse_status=
     'deleting' 的过滤同时作用于 folders 计数（ListKnowledgeFolderCounts）与
     clear 的行清单——别在 clear 的 list 里额外加 status 过滤。
  5. **批处理路由的行校验文案三处刻意不同**：batch-delete 的 count 不符 →
     `"One or more knowledge entries not found"`；batch-reparse 的 →
     `"some knowledge entries were not found"`；move（requireKnowledgeInKB）→
     `"One or more..."`。且 batch-delete/batch-reparse 逐行先 `RejectMovingKnowledge`
     （409）再查跨 KB；move 则由 service 层 loadKnowledgeWriteBatch 兜
     （404 "knowledge not found" 小写 / 409 / 403 `"knowledge outside target KB"`）。
  6. **tags 的授权 grant 只覆盖一个 KB**：kb_id 路径 = 显式 kb_id；无 kb_id =
     首条 knowledge 的 KB。loadKnowledgeWriteBatch 逐 KB 校验
     requireKBWrite，落在授权 KB 之外 → 403 `"无权修改该知识库"`（service 层，
     早于 authorizedKBID 的 scope 校验；golden kg-tags-cross-kb 钉住）。
     而 tags-unknown-knowledge 在同一批加载里先出 404 `"knowledge not found"`
     （小写 k）。两个文案的先后顺序 golden 依赖，不能重排。
  7. **gin.H 的字母序有两处新形态**：spans 响应 data 键序
     `attempt < current_attempt < current_stage < knowledge_id < last_error <
     latest_attempt < parse_status < trace`，last_error 内
     `code < error_code < error_message < finished_at < message < name < stage`；
     SpanTreeNode 按 struct 声明序（children 恒最后，空缺席）。合成树的
     created_at/updated_at 是响应时刻（掩码）。
  8. **文件下载/预览的头是逐字节契约**：下载固定 `Content-Type: application/octet-stream`
     + `Content-Description/-Transfer-Encoding/Expires`；预览按
     SafeContentTypeByFilename（.md → `text/markdown; charset=utf-8` inline）。
     Content-Disposition 走 mime.FormatMediaType：token 安全（ASCII 且非 tspecials）
     → `filename=kg-doc.txt`；否则 `filename*=utf-8''%E6...`（小写 utf-8、大写十六进制）。
     Seeker（磁盘文件）→ `Accept-Ranges: bytes`；manual（内存 reader）→
     `Accept-Ranges: none` + 显式 Content-Length。manual 文件名 =
     sanitizeManualDownloadFilename(title)（换行删除、斜杠转 `-`、引号转 `'`、补 .md）。
  9. **GET /knowledge/batch 的绑定顺序**：uint64 form 字段的 strconv 映射错误
     （details=`strconv.ParseUint: parsing "abc": invalid syntax`，message 仍是
     `"Invalid request parameters"`）先于 validator 的 IDs required；`?ids=`（空值）
     通过 required 进服务层 → `data:[]`。agent 共享路径未翻译恒 403
     `"no permission for this shared agent"`（与 Go 的 agents==nil/not-found 同文案）。
  10. **manual 更新的响应 metadata 是内存对象**（content,format,status,version,updated_at
      声明序），version=旧值+1，updated_at 是 RFC3339 秒级 UTC；经落库再读回（如
      reparse 响应）才变成 jsonb 规范化键序（format,status,content,...）——两种形态
      在同一轮 golden 里并存，别统一。
  - 已知差异（记录在 KnowledgeService 类注释）：① batch-delete/clear-contents 为同步
    尽力而为（batch-delete=软删、clear=parse_status='deleting' 标记；HTTP 契约一致，
    真正的向量/文件/wiki 回收缺位）；② reparse 的 process_config 覆盖不落地（仅支持
    null/缺省，覆盖校验随 worker 收口）；③ regenerate-summary/向量更新在模型存在时
    报 "…not available in this deployment"（运行时模型工厂随阶段 7）；④ spRepo 未
    翻译 → spans 恒走 spanRepo==nil 分支（rows 空 + latest_attempt=0 + ?attempt=N
    透传），buildSpanTree/knowledgeSpansLastError 全量翻译非降级；⑤ shared-agent /
    org-share 两条授予路径未翻译（恒 403/仅同租户），与 wiki/chunk 同源。
  - 测试基建：TestSchema 增 knowledge_tags/knowledge_tag_relations（迁移 000001 §10 +
    000063；seq_id 播种显式给值）；错误 message 里内嵌的 UUID（"…does not belong to
    knowledge base X"）要用**裸 UUID 掩码**（不带键名上下文），本测试类 mask() 已带。
    录制脚本 scripts/record-knowledge-golden.sh 的 KG 行 file_name/file_hash 逐行
    固定（文档 body 断言 file_size/hash 是字面量不是掩码）；KG1 的文件落在
    LOCAL_STORAGE_BASE_DIR/kgdocs/（local://kgdocs/kg-doc.txt，两侧同布局）。

- **波 2 knowledge 搜索与移动/复制（8 条路由，knowledge 域收官）新确认的细节与坑——前四条都会复发**：
  1. **同一个"binding 失败"，move 和 copy 的 HTTP 形态刻意不同**（本轮最重要的发现）：
     move 是 `NewBadRequestError("Invalid request parameters: " + err.Error())` →
     **message 带前缀、details=null**，且 validator 把**全部**失败字段按 struct 序用 `\n`
     连接成一条 message（`{}` 空 body → 四行 Key: ... required tag；部分合法 → 只列失败字段）；
     copy 是 `NewBadRequestError("Invalid request parameters").WithDetails(err.Error())` →
     **message 固定、validator 原文在 details**。hybrid-search 也是 details 形态
     （EOF / GoJsonBindError 原文）。三处别统一。
  2. **跨租户 KB 的 403/404 取决于守卫在哪一层**：move 的 handler 直接
     `GetKnowledgeBaseByID`（租户无关）再自己判租户 → 403 "No permission to access
     source/target knowledge base"；copy/duplicate 走 `resolveHandlerKBAccessFor` /
     路由 KBAccessRead → cross-tenant source 是 **403 "Permission denied to access this
     knowledge base"**（access.ResolveKB 的 ErrForbidden，不是注释说的 NotFound——golden
     实测推翻了源码注释）；duplicate 的 404 "Source knowledge base not found"（大写 S）
     被路由中间件的 404 小写 "knowledge base not found" 挡成**不可达死代码**。
  3. **进度终态的 `created_at` 恒为 0**：handler 准入时 SetNX 的 pending 进度带
     created_at=now，但 asynq worker 每步都 `&types.KBCloneProgress{...}` 新对象
     **不带 created_at** → SET 覆写后读回来的就是 0。Java 侧照抄（新 record 构造传 0），
     别"顺手保留"。move 终态 message 是逐条推进的 "Moved X/N knowledge items"
     （完成块不改 message），clone 终态是 "Knowledge base clone completed successfully"
     （覆盖逐条 "Processed X/N clone operations"）。
  4. ** EnsureDefaults 会传染进 duplicate/copy-create**：duplicate 的响应
     `indexing_strategy`/`capabilities` 是**读路径 EnsureDefaults 之后**的形态
     （DB 全关 → 响应 vector+keyword=true）；clone-create 的 Go worker 建 KB 行**不复制**
     源的 indexing_strategy 字段，EnsureDefaults 补成 vector+keyword。Java 用
     JSON 往返（CLONE_MAPPER，**必须挂 JavaTimeModule**——KnowledgeBase 带
     OffsetDateTime，裸 mapper 直接 500）+ `KnowledgeBaseService.ensureDefaults`。
  5. **`vector_store_engine_type` 键的有无 = 部署状态**：Go 的 buildKBResponse 只在
     `storeView.EngineType != ""` 时写键；envDefaultStoreView 的 EngineType 取
     `envStores[0]`——阶段 3 的 kb-get golden（09-17 录）有 "postgres"、本轮 duplicate
     golden（09-19 录）没有：当前 Go dev 的 envStores 为空。Java 侧
     `KnowledgeBaseResponseBuilder.build(kb, driver, includeEngineType)` 重载分开两条路，
     **别把两份 golden 互相"修"成一个样子**。
  6. **dev PG 的存储后端回填**：Go API 建 KB 时 applyAndValidateStorageBackend 会把
     租户的 System LOCAL（legacy alias、source=env）写进 storage_backend_id +
     provider=local——duplicate 响应里这两个字段依赖它。契约测试要**种子一个
     storage_backends 行 + KB 行带 storage_backend_id/storage_provider_config**，
     否则 duplicate 的 storage_backend_id 输出 null、provider 输出 ""。
  7. **搜索是租户级全库扫描**：SearchKnowledge 的 scope = 本租户全部 document KB
     （repo JOIN 再按 type=document 过滤）；`recent=true` 的 total=租户全量行数——
     **dev PG 有历史残留，不过滤的 recent golden 不是契约稳定值**（本轮只录
     file_types=url 收敛后的 recent，脚本里留了注释占位）。keyword 搜索用租户内唯一
     关键词（ksdoc）收敛命中集合；种子行 created_at 必须显式且互不相同（Go 按
     created_at DESC，并列顺序不稳定）；file_types 别名（xlsx↔xls/docx↔doc/
     jpg↔jpeg↔png、url/html→type='url'）与 `% _ \` 的 LIKE 转义要逐字照抄。
  8. **task id 是跨请求契约**：`<type>_<tenant>_<millis>_<8hex>[_<biz12>]`，进度路由按
     嵌入租户段隔离（解析失败 400 "invalid task ID"、跨租户 404 "task not found"、
     同租户查无 404 专属文案 "Knowledge move task not found" / "KB clone task not found"）。
     Java 照 ParseTaskID 的"定位 (tenant,ts) 对"算法实现（type 段可含下划线）。
  9. **hybrid-search 的确定性降级**：retriever 未接线（波 4）→ 前置确定性分支
     （KBAccessRead 守卫、query_text 必填、resource_urls 解析、multi-KB scope 授权
     ——空集/越权/主库缺席都是 404 小写）逐字翻译后，检索执行落 Go 的"零结果"出口
     `{"data":null,"success":true}`（空库+空 embedding 的 dev KB 在 Go 也是这个形态，
     golden 钉住）。**有绑定且命中数据时 Go 能出结果**——已知差异记在 Javadoc。
  10. **GET 带-body 的兼容路由**：`GET /knowledge-bases/{id}/hybrid-search` 与 POST 同
      handler（#1727），JSON body 缺失同样 400 EOF——别按"GET 无 body"写绑定。
  - 已知差异（记录在 KnowledgeService/KnowledgeTaskProgressStore 类注释）：
    ① hybrid-search 的检索执行随波 4（含 multi-KB embedding 一致性校验）；② move/clone
    只做到行级（knowledge+chunk 行），向量索引/文件对象/wiki/FAQ tag 映射不复制；
    ③ 进度存储是进程内 map（24h 读路径 TTL 对照 Redis SET EX；单实例语义一致，多副本
    无跨进程可见性）；④ asynq 的 retry/preflight-failed 中间态不翻译（进度直接落终态）；
    ⑤ search 的 org-shared 补捞与 agent_id 分支未翻译（scope 恒本租户文档库；agent_id
    恒 403 "no permission for this shared agent"，与 batch 路由同款）。
- **波 2 FAQ 补充（真 PG A/B 抓回的跨列缺陷）**：
  - **PG 列 DEFAULT 演进会让旧 Java 实体整行读不出来**：`knowledge_bases.chunking_config`
    的 PG 默认值（迁移后新增）含 `split_markers`/`keep_separator`，Go 的
    `json.Unmarshal` 静默丢弃未知键，Java 的 `KbChunkingConfig` 裸抛
    UnrecognizedPropertyException → **该 KB 的一切读写 500**。修法：全部 Kb*Config
    jsonb 类挂 `@JsonIgnoreProperties(ignoreUnknown = true)`（对照 Go 的容忍语义）。
    **凡"裸 SQL/新迁移写的行"都可能有 Java 实体不认识的键**——契约测试用 API 建的行
    永远踩不到，只有 A/B 的裸种子行暴露（H2 绿 PG 炸的又一变种）。
  - **裸种子 KB 行的 NULL jsonb 列**：`KnowledgeBase.getIndexingStrategy()` 对 null
    兜底 defaultStrategy()（照 Go Scan 的 NULL→零值）——这类兜底要逐列检查，别只兜一个。
  - FAQ 的其余实录坑（无细节 500 形态、先落库后 500、mode 缺失即 400、
    "分页参数不合法"单独文案、dry_run 进度富化、faq-upsert-running 为 A/B 预期 DIFF）
    见 FaqService/FaqController 类注释与 §8 台账行。
- **波 2 基础设施配置三组补充（真 PG A/B 抓回的三个真缺陷）**：
  1. **自定义 jsonb TypeHandler 写库必须 `setObject(i, json, Types.OTHER)`**——三个新
     handler（WebSearchParams/ConnectionConfig/IndexConfig）都写成 `setString`，H2 全绿、
     PG 直接 "column ... is of type jsonb but expression is of type character varying"
     （§9 阶段 2 的结论在自定义 handler 上复发：**setString 不行，与 handler 声明无关**）。
  2. **GORM TableName() 覆写是陷阱**：`types.StoredResource.TableName()` = **"resources"**
     ——agent 按结构体名造了 `stored_resources` 影子表并在 TestSchema 建出来自圆其说，
     H2 绿、真 PG 500 "relation does not exist"。**翻译守卫查询前先查 TableName()**。
  3. **create 响应的 AutoCreateTime 回写**：GORM Create 会把 now 回写内存对象，
     controller 直接序列化实体——Java 的 `repo.create(entity, now)` 把 now 当独立参数
     就丢了回写 → 响应恒 year-1。**insert 后显式 setCreatedAt/setUpdatedAt(now)**
     （datasource 波 §9 的 PUT updated_at 回写是同族教训）。
  4. wsp 的 PUT 把 created_at 清零（Go `Select("*").Updates` 写零值 year-1，之后所有
     GET 恒 year-1）是**字面契约**，Java 写 SQL NULL 读 null→GO_ZERO 字面量跨语言等价；
     vs 的 PUT 只 Select("name") created_at 保持——同文件族内两组行为刻意不同，照抄。
  - 其余实录坑（同一 404 两种形态、test 端点的 AppError 双前缀差异、Go map 迭代随机、
    PreserveIfRedacted、env stores 部署状态、viewer 用例误带 owner 头的录制坑）
    见各 Controller/Service 类注释与 §8 台账行。
- **波 2 成员/邀请/api-principal 补充（真 PG A/B 抓回的 clearStaleHomeTenant 落库链）**：
  - **clearStaleHomeTenant 的落库有两个专属坑**：① `updateById(user)` 会把
    `tenant_id=0` 写进 UPDATE → FK `fk_users_tenant` 直接炸（Go 是
    `Omit("tenant_id").Save` + `UpdateColumn("tenant_id", nil)`——显式 NULL，不是 0）；
    ② `users.preferences` 是 jsonb 列，wrapper 两参 `.set(col, obj)` 缺 typeHandler 直接
    MyBatisSystemException（§9 三参规则）。**净修法：单条 wrapper 只写
    `tenant_id=NULL`**；preferences 存量偏差无害（pref==home 与 home 走同一解析路径，
    已记录为已知偏差）。
  - **B 的两种 token 形态是契约场景**：曾入成员→登录→成员行被删的 JWT（带租户上下文）
    访问 /me/* 落 403 "not a member of the target workspace"；从未是成员的重登 JWT
    （tenantless）访问 /me/* 走 tenant-optional 200。契约测试必须复刻对应的登录时序，
    不能用"从未是成员"的 token 去比 403 场景。
  - 其余实录坑（@PathVariable 名字必须与模板一致、MP 分页 count 不能带 orderBy、
    gin.H 内层 map 字母序、邀请 seq_id/invite_url/JWT 的掩码策略）见
    TenantMemberContractTest 与各 Controller 注释。
- **波 2 系统管理端补充（真 PG A/B 抓回）**：
  - **阶段 3 的 UserKbPin 列映射是错的**：真表列名是 `kb_id`/`pinned_at`，实体按驼峰
    映射成 `knowledge_base_id`/`created_at`——fillPin 在真 PG 直接 500。**H2 的 DDL 是
    照实体写的，永远发现不了**；pin 路由此前从未过真 PG A/B 所以潜伏至今。
    **凡"表 DDL 以迁移为准"的纪律同样适用于 TestSchema**——照实体写 DDL 等于自欺。
  - **Go 常量文案要取到源头文件**（anydoc 不可用原因分三段拼接在
    `anydoc/backend_stub.go`，Java 只抄了第一段）；运行环境相关的 long 常量
    （ affected 租户数 / key 数字 id / tenant_id）在 A/B 里按部署掩码。
  - evaluation 执行步是部署能力（Go dev 真跑 LLM 流水线带真实指标），执行态三文件
    （ev-post/ev-get/ev-get-viewer）A/B 归部署态跳过，确定性校验分支照常比对。
  - RequireSystemAdmin 的拒绝文案是 "Forbidden: system administrator required"
    （RbacInterceptor 既有实现写错，golden 纠正——audit-log 路由的既有文案随之修正）。
- **波 2 扫尾批 1（auth 注册族）补充**：
  - **MyBatis 走 getter 取属性**——`User.getTenantId()` 的「null→0 归一化」会把
    tenantless 注册写成 `tenant_id=0`，真 PG 违反 `fk_users_tenant`（H2 无 FK 永远不暴露）。
    凡「Go Omit → SQL NULL」语义的**写入**路径都不能依赖实体 getter：
    `UserMapper.insertTenantless` 显式省略 tenant_id 列（register-by-invite /
    OIDC provisioning 共用；update 侧参照波 2 第六批的 `tenant_id=NULL` wrapper 写法）。
    **这是 §5.6 之后的又一代复发坑：getter 归一化只服务于读/序列化，写库要绕开它。**
  - **`tenants.context_config` 的零值对象契约**（实测当前 Go 二进制）：Go 注册路径经
    GORM Create 对 nil `*ContextConfig` 调 `Value()` → `json.Marshal(nil)` → 落库的是
    **jsonb `'null'` 字面量**（非 SQL NULL）；读回 `Scan([]byte("null"))` 落在已分配的
    零值 struct 上 → 响应**恒输出零值对象**
    `{"max_tokens":0,"compression_strategy":"","recent_message_count":0,"summarize_threshold":0}`
    （struct 声明序）。Java 侧：createTenant 在 null 时写 `MAPPER.nullNode()`（与 Go
    落库字节一致），TenantService 读路径 `normalizeContextConfig` 把 null/NullNode/任意
    存储序对象统一归一成 4 键 struct 序（jsonb 存储序 ≠ Go 输出序，实测 PG 10002 行）。
    **login-success.json（9/17 录）因此键缺失已过时，按当前二进制重录**——golden
    的时效以「构建中的 Go 二进制」为准，旧 golden 与新二进制冲突时重录并记台账。
  - **匿名 struct 的 binding 错误无 Key 前缀**：change-password 的请求体是 handler 内联
    匿名 struct，validator 错误为 `Key: 'OldPassword' ...`（无 `RegisterRequest.` 式前缀）；
    invitations/lookup 的 message 是 "token is required"（非 "Invalid registration
    parameters"）。逐条以 golden 为准，勿凭一致性想象。
  - **updateMyPreferences 的 max=4000 校验在 binding 层**（go-playground 按 rune 计），
    4001 字符的响应 details 是 `Key: 'updateMyPreferencesRequest.BrowserSearchInstructions'
    Error:...'max' tag`——service 层的 4000 复查只是兜底，响应形态由 binding 决定。
  - **register-by-invite 的 updated_at 二次刷新**：user 建号（UTC now）→ 回填 tenant_id
    再 Save（GORM 自动刷 updated_at）→ 响应里 created_at ≠ updated_at 且时区形态可不同
    （A/B 掩码覆盖；断言勿假设两值相等）。
  - 注册成功响应的 `user` 是**完整 User 实体**（含 `"deleted_at":null`），而
    /auth/validate 与 /auth/me 的 user 是 **UserInfo 投影（无 deleted_at）**——同一用户
    两种形状，UserInfo.from 静态工厂收口。

- **波 2 扫尾批 2（auth OIDC）补充**：
  - **gin Redirect 的 302 是有 body 的**：`<a href="<Location>">Found</a>.\n\n`，
    Content-Type `text/html; charset=utf-8`，且 body 里 href 的 Location 经
    **Go html.EscapeString** 转义（`&`→`&amp;` 等 5 字符）而 **Location 头保持原样**
    （实测含 `&` 的 oidc_error 场景两形态并存）。Spring 的 302 ResponseEntity 默认无
    body——`redirectFound` 手写 status/Location/Content-Type/body 四件套；golden 对
    302 端点用**合成信封 JSON**（键字母序 body/location/set_cookie/status）落盘，
    契约测试从 MockMvc 结果组同一信封比对（约定写在 record-oidc-golden.sh 头注释）。
  - **MockHttpServletResponse 会解析并重序列化 Set-Cookie**：`Max-Age=0` 被补
    `Expires=Thu, 01 Jan 1970 00:00:00 GMT`（MockCookie 行为）；真容器（Tomcat）
    原样透传（A/B 逐字节证实 Go 的清算头是 `weknora_oidc_nonce=; Path=/; Max-Age=0;
    HttpOnly`）。MockMvc 契约测试对这条头做窄化还原，真字节由 A/B 钉住。
  - **OIDC state 是 HMAC 签名的自包含令牌**（oidc_state.go）：
    `b64url_nopad(json).b64url_nopad(hmac-sha256)`，json 字段序 nonce,redirect_uri,iat，
    密钥=env JWT_SECRET（空则随机 32B，sync.Once）。**跨语言互验已实测**：python
    锻造的 state 被 Go 接受（录制脚本）、Java OidcStateCodec 自签自验（契约测试）。
    verify 的 6 条失败（段数/b64/HMAC/redirect_uri/iat/新鲜度 ±10min/-1min）在 handler
    层全部坍缩成 `invalid_state` 302——**不区分原因、无 Set-Cookie**；只有 verify+nonce
    cookie 双过才发清算头（`Max-Age=0`），再分派 missing_code / login_failed。
  - **urlQueryEscape 是定制 replacer 不是标准 percent-encoding**（auth.go L494-505）：
    只转义 `% # & + = ?` 与空格共 7 个，其余原样（`/`、`:`、多字节 UTF-8 都不动）。
    而授权 URL 构建用的是 `url.Values.Encode()`（**Go QueryEscape 语义**：alnum 与
    `-_.~` 原样、空格 `+`、其余 %XX 大写；**键按字母序** client_id,redirect_uri,
    response_type,scope,state）——两个 escaper 职责不同，勿混用（Java URLEncoder 会把
    `~` 编成 %7E，不可用，OidcService.goQueryEscape 手写）。
  - **OIDC 配置链只有 env+缺省两层**（Java 侧）：Go 另有 config.yaml `oidc_auth` 段，
    Java 仓无该加载器；env 名与 Go 完全同名（OIDC_AUTH_* + OIDC_USER_INFO_MAPPING_*），
    缺省 ProviderDisplayName="OIDC"、Scopes=[openid,profile,email]、mapping name/email。
    dev 两侧 config.yaml 均无此段，行为等价；若将来接 config.yaml 需补 OidcConfig。
  - **nonce cookie 名 `weknora_oidc_nonce`**：下发（url/start 成功分支，dev 不可达）
    Max-Age=600、HttpOnly、SameSite=Lax、secure=TLS 或 X-Forwarded-Proto=https；
    Set-Cookie 字节序对照 Go Cookie.String()（Path; Max-Age; Secure; HttpOnly; SameSite）。
    /oidc/start 的回调地址由请求自身 Host 头推导（oidcCallbackURL），外部平台深链用。

- **波 2 扫尾批 3（跨空间租户目录 + KV 配置分发器）补充**：
  - **PathTenantMatch 必须按 BEST_MATCHING_PATTERN 门控**：旧实现按「路径以
    /api/v1/tenants/ 开头」裸匹配，会把 /tenants/all、/tenants/search、/tenants/kv/*
    全当跨租户越权 403 掉（all/search/kv 的 {id} 段根本不是租户 id）。改为仅当
    匹配模板以 `/api/v1/tenants/{` 开头才查目标租户——literal 段路由（all/search/kv）
    由各自守卫管。这是本批唯一的存量行为修正（旧路由无这些模板，回归由全量套件钉住）。
  - **跨空间守卫在 RbacInterceptor 不走 EnableRBAC 判定**：flag off → 403
    「Cross-workspace access is disabled」；非超管 → 403「Insufficient permissions
    for cross-workspace operation」（均 code 1002，c.Error 信封，不审计）。
    POST /tenants 不登记 RBAC 规则（任何登录用户可自助，部署级开关在 handler 内
    三层解析：DB > env > config 底座）。
  - **创建族三种错误形态并存**：binding 失败 400 code 1010 details 是
    go-playground 原文（`Key: 'createTenantRequest.Name' Error:...'required' tag`，
    rune 计长，min=1/max=128、description max=512；空 body → details "EOF"）；
    空名/空格名穿过 binding 在 service 抛 → 500 code 1007 details
    "workspace name cannot be empty"；配额预检 cap>0 且 owned≥cap → 429 code 1006；
    self-service 关停 → 403 code 2005。超管全字段路径 ShouldBindJSON(&types.Tenant)
    绑定整个实体（status 请求值被 service 恒写 "active"，id 恒由 DB 生成）。
  - **TOCTOU 复检与回滚顺序**：ensureOwner（DuplicateKeyException 重读胜出行）→
    提交后再数一遍 owner 数，超帽回滚成员+租户；tenantless 回填失败同样回滚。
    auto_create_api_key 失败只 warn 不拖垮创建（对照 Go `_ =` 尽力语义）。
  - **autokey 响应是 map 深排序**：tenantWithAPIKey 把实体序列化成 map 再加
    api_key——Go map[string]any 序列化**各层键都字母序**（api_key 排最前），
    与实体 @JsonPropertyOrder 的声明序完全不同。Java 用 springMapper.valueToTree
    （保 +08:00 时间串）再递归 TreeMap 深排序复刻。
  - **KV GET 默认形态六 key 各异**（golden 钉死）：web-search 是 `data:null`
    （指针列 SQL NULL → nil）；parser/storage/chat/retrieval 是**零值对象**
    （GORM Scan 对 SQL NULL 留给已分配 struct；jsonb 'null' 字面量也 Scan 成零值
    对象——parseConfig 对 NullNode 返回 newInstance 复刻）；memory 多一层
    Normalize 默认值（write_mode=explicit_only/max_items=200/delay=90/interval=300/
    interest=3/vector_recall+retrieval_conditioning=null）。
  - **PreserveIfRedacted 语义 = 空串或 "***" 都保留旧值**（ws 的 api_key/proxy_url、
    parser 的 mineru_api_key 等）；**S3 例外只认 "***"**（空串是真清字段）。
    响应侧 api_key 恒不输出（write-only），proxy_url/secret_access_key 等掩成 "***"。
  - **KV PUT 校验顺序坑**：storage 的 provider 归一+白名单、retrieval 的五段范围、
    memory 的七段校验都在**租户上下文检查之前**（对照 Go handler 行序）——无租户
    时这些 400 先于 "Workspace is empty"。PUT 成功信封键字母序
    `{"data":...,"message":...,"success":true}`；message 文案 ws/retrieval/memory/chat
    英文、parser/storage 中文（"解析引擎配置已更新"/"存储引擎配置已更新"）。
  - **chat-history enable 自动建隐藏 KB**：enabled+有模型+无存量 KB → 建
    __chat_history__（is_temporary）；模型未变再 PUT 沿用存量 knowledge_base_id。
    embedding_model_id 不做存在性校验（Go 同）。KnowledgeBaseService 对无后端租户
    容忍（backend null 直接返回，不落 backend 关联）。
  - **parser 无成功路径 golden**：三条录制全是 SSRF 1010（example.com 在本机
    fake-ip DNS 下解析到 198.18.0.0/15 受限段；127.0.0.1 字面量字节稳定）。
    A/B 与契约测试对 "resolves to restricted IP <ip>" 的 IP 段掩码。若将来
    dev 环境 DNS 变化，可补成功路径 golden。
  - **prompt-templates 推迟**：GET 是 Go 独有（vendor config/prompt_templates/*.yaml
    + Language 中间件，47KB payload），Java 落 default → 400 unsupported key；
    PUT 两侧本来都 400（Go 分发器无此 key）。A/B 列 EXPECTED DIFF（ab-ct.sh 的
    EXPECTED_DIFFS 清单）。
  - **search 的宽松解析**：tenant_id 非数字→0 忽略；page<1→1；page_size<1→20、
    >100→100；keyword+tenant_id 是 OR 关系；恒 created_at DESC。list/search 响应
    恒按 viewer 形态裁剪（调用方自家角色不解锁别家秘密）：省略四个秘密字段，
    且 context_config 因 GORM jsonb 'null' Scan 语义输出**零值对象而非 null**。
  - **设置族回归**：tenant.{max_owned_per_user,self_service_creation_enabled,
    auto_create_api_key} 三键注册进 SystemSettingRegistry（int/bool/bool），
    布尔 PUT 必须发 JSON bool（{"value":true}，发字符串 "true" 400
    "expected bool, got string"——录制脚本第一轮的坑）。
  - **双构造器 record 必须 @ConstructorBinding**：TenantProperties 加第 4 组件后
    保留了三参兼容构造，@ConfigurationPropertiesScan 找不到绑定构造器就退化成
    无参实例化 → 启动 NoSuchMethodException。钉在 canonical 构造器上解决。
- **波 2 终扫批（favorites + chunker 预览）补充——波 2 到此全部收官**：
  - **GORM Find 空结果 → `"data":[]` 非 null**：Go `var list []*T; Find(&list)`
    零命中时 list 是**空非 nil 切片**，序列化成 `[]`。别按"Go nil slice→null"
    的通用规则去猜——golden fav-list-empty-kb.json 钉死。同批另一处 nil 语义
    相反：`Diagnostics.Rejected` 未发生拒绝时保持 nil → `"rejected":null`
    （append 过才变数组）；`Chunks` 用 make 初始化 → 恒 `[]`。**同一个 handler
    里三种形态并存，逐字段对 Go 源码**。
  - **空 strategy = legacy，不是 auto**：`resolveChainWithProfile` 的 switch 里
    `case StrategyLegacy, "":` 同档；只有 `auto` 和**未知值**（default 分支）才走
    画像选链。此时 diag.Profile 为 null，由 **handler** 调 ProfileDocument 物化
    （避免二次切分）——preview 响应里的 profile 恒非 null 但来源分两种。
  - **preview 的错误体是裸 gin.H**：`{"error":"<字符串>","success":false}`，
    不走 AppError 信封（fav 的 400 才是信封+details）。413 三键字母序
    error<limit<success。binding 文案 `"invalid request body: "+err.Error()`
    拼在 error 字符串里，复用 GoJsonBindError。深层结构类型错误
    （chunk_size:"five"）Go/Jackson 措辞差异大，**刻意不录**（已知差异）。
  - **profile 的两个编码陷阱**（§9.2 在本批的实例）：double 三字段
    （avg_line_len/std_line_len/code_ratio）挂 GoDoubleSerializer——golden 里
    `"avg_line_len":91`（Go 整值 float64 无 .0）；`md_heading_counts` 是
    `map[int]int`：键**数字升序**输出（Jackson 用按键排序的 LinkedHashMap）、
    空表恒 `{}`（profiler 恒 make）。
  - **favorites 幽灵删除也是 200** `{"success":true}`（repo 返回 0 行，Handler
    不分支）；类型白名单/空 id 的 400 文案逐字对照 Go sentinel error；FirstOrCreate
    = 先 SELECT 四键再 INSERT（复合主键 → MyBatis-Plus 无 @TableId，**纯 SQL mapper**）。
  - **收藏表无外键**：resource_id 任意字符串即合法（不校验资源存在），录制/测试
    用固定假 id 不依赖真实 KB/agent；RBAC Viewer 三条 + chunker/preview 一条；
    API key 侧 favorites **不登记**（默认拒绝），preview 登记
    `retrieve(ingest(fullAccess()))`（单条路由双能力组合的又一例）。
  - **MockMvc 编码陷阱复发**（陷阱清单第 12 条的兄弟）：`getContentAsString()`
    在响应缺 charset 时按 ISO-8859-1 解码——全角破折号（"text is empty — paste…"）
    和中文 content 全部变 mojibake。**契约测试的 raw() 一律
    `new String(getContentAsByteArray(), UTF_8)`**。
  - **测试堆 2g→3g**：3298 条 + 契约文件过千后，2g 再次随机 OOM（仍是
    「Gradle Test Executor N failed to execute tests」，OOM 点在 Spring 资源扫描
    的 substring 里，极易误判业务 bug——见 server/build.gradle.kts 注释史：
    512MB→1g→2g→3g，随测试量继续上调）。
  - 本批是**零缺陷批**：A/B 首轮 ALL MATCH，没有抓回任何 H2 绿/PG 红问题——
    小模块+纯 SQL+既有基础设施（GoJsonBindError/GoTimeSerializer/GoDoubleSerializer）
    全复用时的预期形态。

- **走查抓回（2026-09-22，手动验收「创建知识库」场景，已修复）**：前端编辑器全字段
  payload 创建 KB 恒 400「Invalid request parameters」。根因：`KnowledgeBase` 实体字段
  没有 `@JsonProperty` snake 别名（嵌套配置类都有，顶层实体漏了），控制器 MAPPER 又是
  裸 `new ObjectMapper()`（FAIL_ON_UNKNOWN_PROPERTIES 默认开）→ 第一个未知键
  `wiki_config` 即抛。既有契约测试只发 name/description 极简 body 所以全绿漏网。
  修复 = 实体 30 个字段按 Go types/knowledgebase.go 的 json 标签逐字段补注解。
  - **同场 A/B 又抓回第二个 DIFF**：extract_config/wiki_config 等 JsonNode 透传字段
    把前端发来的空串/空数组原样存库回显，Go 则绑进带 omitempty 的 struct（入库
    Value() marshal 与响应 json.Marshal 都丢空值）——Go 的 extract_config 只剩
    `{"enabled":false}`。修复 = bindKnowledgeBase 里 normalizeConfigOmitEmpty()
    按各 struct 的 omitempty 标签剔空值（keep 集 = 无 omitempty 的键；bool 不剔——
    skip_if_tagged 是 *bool，非 nil 的 false Go 也保留）。faq_config 两字段无
    omitempty 故不归一。修复后同 payload 双端响应逐字节 MATCH。
  - `vector_store_engine_type` 键差（Java 恒出 "postgres" / Go 缺席）是**部署态
    差异非缺陷**：Go 的 EngineType 取 envStores[0]，本部署 envStores 空；golden
    录制时非空。维持既有「按部署各自断言」纪律不动。
  - **教训**：实体的 Jackson 绑定形态要用「前端真实全字段 payload」过一遍，契约测试
    的极简 body 覆盖不到未知键拒绝；omitempty 归一适用于所有「Go struct 绑定 →
    jsonb 入库」的配置列。

- **走查抓回（2026-09-22，手动验收「文档上传解析」场景，已修复）**：上传 md 文档后
  解析落 `failed`，error_message 只剩裸类名 `java.net.ConnectException`，看不出连的谁。
  根因两层：①**环境**——该 KB 的 embedding 模型选了种子调试模型 md-emb
  （`b0000000-…-003`，base_url 指 stub-llm 127.0.0.1:8181），stub 没起 → 连接被拒；
  ②**代码 DIFF**——EmbedderClient.embedBatch 的 http.send 让裸 IOException 直接上抛，
  worker catch 里 `e.getMessage()==null` 回落 `e.toString()` 只剩类名；Go 的
  OpenAIEmbedder 是 `send request: %w` 包 url.Error（含方法与地址）。
  修复 = send 外包一层 `send request: Post "<base_url>/embeddings": <ioDetail>`，
  ioDetail 复用疑点③同款兜底（message 为空取类名）；dial tcp 等传输层内文仍属
  掩码 DIFF 族。验证 = reparse 接口复现拿到新文案 → 起 stub 8181 再 reparse →
  completed + chunks 落库。**教训**：「文档解析失败」先看 error_message 里的地址，
  embedding/rerank 这类出站调用失败的报错路径同样要过「无消息 IOException」兜底。

- **走查抓回（2026-09-22，Agent 编辑器打开即 400，推迟项清零）**：前端
  AgentEditorModal 加载依赖时打 `GET /tenants/kv/prompt-templates`，Java 按
  当初的推迟登记落 400「unsupported key」。本次补翻落地：新增
  `agent.PromptTemplateCatalog`（classpath vendored yaml 九文件装载 +
  LocalizeTemplates 本地化 + Go struct 序/omitempty 逐字段保真的 ObjectNode
  输出），控制器 GET 分支接线，locale 复用 middleware/language.go 等价物
  （env → Accept-Language 首 tag → zh-CN）。
  - **保真点**：①handler 的 localized 副本只搬 9 字段——graph_extraction/
    generate_questions 恒缺席（Go 源码如此，勿"顺手补全"）；②system_prompt 等
    四个无 omitempty 字段空值是 null 而非键缺席；③模板级 omitempty 对
    user/has_knowledge_base/has_web_search/default/mode 逐字段生效，i18n 恒不出
    响应；④本地化只换 name/description 且只在覆盖值非空时换。
  - **验证**：zh-CN/en-US/ja-JP/空 Accept-Language 四组双端 A/B 全部逐字节
    MATCH（47,856 字节）；已录的 ct-kv-get-prompt-templates.json golden 与今日
    Go 响应 cmp 一致——当初的 EXPECTED DIFF 转正，契约测试从「钉 400 推迟」
    改为对 golden 断言 200。

- **走查抓回（2026-09-22，真实 embedding 模型入库失败，已修复）**：配好 dashscope
  embedding 后文档解析落 failed：`batch size is invalid, it should not be larger
  than 20`。根因：KnowledgeProcessWorker 硬编码 EMBED_BATCH=40，而 Go 是
  `BATCH_EMBED_SIZE` env、默认 **5**（batch.go L32-37）——任何批量上限 <40 的
  provider 都会炸，此前只用 stub（无批量限制）验证过所以漏网。修复 =
  embedBatchSize() 对齐 Go：env 空 → 5，非法值照抄 strconv.Atoi 文案抛错
  （会落进 error_message）。验证 = 真实 dashscope 模型 reparse 走查文档 →
  completed + chunks 落库。**教训**：「stub 能过 ≠ 真 provider 能过」——批量/
  限流/长度类参数要对照 Go 的 env 可调值，不要用自以为合理的常量。

- **走查抓回（2026-09-22，DB 白名单重启后静默失效，已修复）**：用户在系统设置存了
  `ssrf.whitelist=["198.18.0.0/15"]`（TUN fake-IP 段），Java 重启后 dashscope 又被拦
  ——SsrfGuard 静态初始化只读 env，`dispatchSideEffects` 只在「设置变更」时推。
  Go 的 applySSRFWhitelist 还在 **preload（initial sync）** 调用（system_setting.go
  L405）——这是当初「Lite 取舍：无 preload」漏掉的可观测面：单实例下也可观测，
  不只是多副本广播。修复 = SystemSettingService 加 ApplicationReadyEvent 监听，
  启动后 dispatchSideEffects("ssrf.whitelist")，读失败降级 env-only（WARN）。
  - 验证：重启后直接检索/问答 dashscope 全通（此前重启即 400 SSRF）。
  - **同场排查结论（非缺陷）**：检索 0 结果 → 兜底固定回复 "Sorry, I am unable to
    answer this question." 是**设计行为**——查询「你好/需求总览」与分块最高余弦
    0.5614 < 默认 vector_threshold 0.7；Go 端同库同查询同样空集（重启 Go 预载
    白名单后 A/B 确认）。阈值在租户检索配置可调。
