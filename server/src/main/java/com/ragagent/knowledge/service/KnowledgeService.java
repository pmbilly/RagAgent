package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.knowledge.dto.KnowledgeDtos.DuplicateKnowledgeDetails;
import com.ragagent.common.security.InputSanitizer;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.mapper.KnowledgeTagMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ragagent.chatpipeline.PipelinePorts.RetrieveGraphRepository;
import com.ragagent.knowledge.dto.KnowledgeDtos.UpdateKnowledgeRequest;
import com.ragagent.knowledge.dto.KnowledgeTaskDtos.KBCloneProgress;
import com.ragagent.knowledge.dto.KnowledgeTaskDtos.KnowledgeMoveProgress;
import com.ragagent.retrieval.engine.PgVectorEngineRepository;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import com.ragagent.knowledge.task.KnowledgeTaskProgressStore;
import com.ragagent.knowledge.task.KnowledgeProcessWorker;
import com.ragagent.knowledge.storage.LocalStorageService;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.knowledge.storage.TenantStorageService;

/**
 * （阶段 3 子集：file/url/manual 创建、分页列表、get/update/delete、folders；
 *  处理管道 = pending→processing→(docreader→chunk→embed)→completed/failed，
 *  任务队列 以进程内虚拟线程队列替代（响应契约一致，重试/取消语义见本仓约定））。
 * <p><b>文档操作面扩展</b>：spans 合成树、regenerate-summary（无 summary
 * model 的确定性 400）、manual 更新、reparse/cancel-parse、download/preview 文件解析、
 * image info、tags 批量、batch-delete/batch-reparse/clear-contents（任务队列 →
 * 同步尽力而为，HTTP 契约 = task_id + 文案）、folders 树升级为完整
 * BuildKnowledgeFolderTree、GET 侧回填 tags。</p>
 * <p><b>已知差异（记录于各类注释）</b>：
 *    软删（chunk+knowledge 行），HTTP 响应逐字节一致；② reparse 的资源清理只对齐
 *    "删 chunks"这一可观测子集；③ 刷新型摘要为进程内虚拟线程（任务队列 语义取舍，
 *    重试对齐 MaxRetry(3)）；④ updateChunkVector 全链已接线（ChunkVectorIndexer，
 *    含真 embedding 与生成问题行重建）。</p>
 */
@Service
public class KnowledgeService {

    // 规模例外（~830 行）：文档主链路的门面与共享工具（常量/静态助手/直创建路径），
    // 已按能力拆出 ProcessWorker/Summary/File/Parse/BatchOps 等专项服务，本类保持聚合面。

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** duplicate 的配置克隆用：知识实体带 OffsetDateTime，往返 mapper 必须挂 JSR310（本仓约定 步 3 教训）。 */
    private static final ObjectMapper CLONE_MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public static final List<String> ALL_STAGES =
            List.of("docreader", "chunking", "embedding", "multimodal", "postprocess");

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkMapper chunkMapper;
    private final KnowledgeTagMapper tagMapper;
    private final LocalStorageService storage;
    /** A3-3 尾批：租户感知文件存储（本地契约不变；云 provider 租户真正落对象存储）。 */
    private final TenantFileStorage fileStorage;
    private final KnowledgeProcessWorker worker;
    private final KnowledgeTaskProgressStore progressStore;
    private final ChunkVectorIndexer chunkVectorIndexer;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeVectorWrites vectorWrites;
    private final PgVectorEngineRepository pgVectorEngineRepository;
    private final TenantStorageService tenantStorage;
    /** 图库仓储（D 批）：知识移动后清源命名空间。 */
    private final RetrieveGraphRepository graphRepository;
    private final KnowledgeMoveService moveService;
    private final KnowledgeCloneService cloneService;
    private final KnowledgeSearchService searchService;
    private final KnowledgeFolderService folderService;
    private final KnowledgeSpanService spanService;
    private final KnowledgeSummaryService knowledgeSummaryService;
    private final KnowledgeFileService knowledgeFileService;
    private final KnowledgeParseService knowledgeParseService;
    private final KnowledgeBatchOpsService batchOpsService;

    public KnowledgeService(KnowledgeMapper knowledgeMapper,
                            KnowledgeBaseMapper kbMapper,
                            ChunkMapper chunkMapper,
                            KnowledgeTagMapper tagMapper,
                            LocalStorageService storage,
                            TenantFileStorage fileStorage,
                            @Lazy KnowledgeProcessWorker worker,
                            KnowledgeTaskProgressStore progressStore,
                            ChunkVectorIndexer chunkVectorIndexer,
                            RetrieveGraphRepository graphRepository,
                            KnowledgeBaseService knowledgeBaseService,
                            KnowledgeVectorWrites vectorWrites,
                            PgVectorEngineRepository pgVectorEngineRepository,
                            KnowledgeMoveService moveService,
                            KnowledgeCloneService cloneService,
                            KnowledgeSearchService searchService,
                            KnowledgeFolderService folderService,
                            KnowledgeSpanService spanService,
                            KnowledgeSummaryService knowledgeSummaryService,
                            KnowledgeFileService knowledgeFileService,
                            KnowledgeParseService knowledgeParseService,
                            KnowledgeBatchOpsService batchOpsService,
                            TenantStorageService tenantStorage) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.tagMapper = tagMapper;
        this.storage = storage;
        this.fileStorage = fileStorage;
        this.worker = worker;
        this.progressStore = progressStore;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.graphRepository = graphRepository;
        this.moveService = moveService;
        this.cloneService = cloneService;
        this.searchService = searchService;
        this.folderService = folderService;
        this.spanService = spanService;
        this.knowledgeSummaryService = knowledgeSummaryService;
        this.knowledgeFileService = knowledgeFileService;
        this.knowledgeParseService = knowledgeParseService;
        this.batchOpsService = batchOpsService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.vectorWrites = vectorWrites;
        this.pgVectorEngineRepository = pgVectorEngineRepository;
        this.tenantStorage = tenantStorage;
    }

    /** 同包开放（拆分出的子服务经门面复用，不各自复制）。 */
    static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    /** 按 id 查 KB（软删不可见）。 */
    public KnowledgeBase findKb(String kbId) {
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
    }

    public KnowledgeBase requireKb(String kbId) {
        // KB 受限的 API Key 不能触碰白名单外的库。
        TenantAPIKeyScope.authorizeKnowledgeBases(
                kbId == null ? java.util.List.of() : java.util.List.of(kbId));
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .eq(KnowledgeBase::getTenantId, tenantId())
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        return kb;
    }

    // ── 创建 ─────────────────────────────────────────────────────────────
    public Knowledge createFromFile(String kbId, byte[] fileContent, String fileName,
                                    String displayName, JsonNode customMetadata, String channel) {
        KnowledgeBase kb = requireKb(kbId);
        long maxBytes = LocalStorageService.maxFileSizeBytes();
        if (fileContent.length > maxBytes) {
            throw new BizException(AppError.badRequest(
                    String.format("文件大小不能超过%dMB", LocalStorageService.maxFileSizeMb())));
        }
        String hash = LocalStorageService.md5Hex(fileContent);
        // 重复文件检查
        Knowledge dup = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getFileHash, hash)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (dup != null) {
            throw new DuplicateKnowledgeException(dup, ErrorCode.KNOWLEDGE_DUPLICATE_FILE,
                    "文件已存在（相同内容）");
        }
        String title = displayName != null && !displayName.isEmpty() ? displayName : fileName;
        String fileType = fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";

        Knowledge k = newKnowledge(kb, "file", title, channel);
        k.setFileName(fileName);
        k.setFileType(fileType);
        k.setFileSize((long) fileContent.length);
        k.setFileHash(hash);
        k.setFilePath(fileStorage.save(tenantId(), k.getId(), fileName, fileContent));
        k.setCustomMetadata(mergeCustomMetadata(customMetadata));
        knowledgeMapper.insert(k);
        worker.enqueue(k.getId());
        return k;
    }

    /** 阶段 3 拉取 URL 内容按文件入库（SSRF 校验在 controller） */
    public Knowledge createFromUrl(String kbId, String url, String fileName, String fileType,
                                   String title, String channel) {
        KnowledgeBase kb = requireKb(kbId);
        byte[] content = fetchUrl(url);
        long maxBytes = LocalStorageService.maxFileSizeBytes();
        if (content.length > maxBytes) {
            throw new BizException(AppError.badRequest(
                    String.format("文件大小不能超过%dMB", LocalStorageService.maxFileSizeMb())));
        }
        String hash = LocalStorageService.md5Hex(content);
        Knowledge dup = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getSource, url)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (dup != null) {
            throw new DuplicateKnowledgeException(dup, ErrorCode.KNOWLEDGE_DUPLICATE_URL,
                    "URL 已存在");
        }
        String fname = fileName != null && !fileName.isEmpty() ? fileName : extractFileNameFromUrl(url);
        Knowledge k = newKnowledge(kb, "file", title != null && !title.isEmpty() ? title : fname, channel);
        k.setSource(url);
        k.setFileName(fname);
        k.setFileType(fileType != null && !fileType.isEmpty() ? fileType
                : (fname.contains(".") ? fname.substring(fname.lastIndexOf('.') + 1).toLowerCase() : ""));
        k.setFileSize((long) content.length);
        k.setFileHash(hash);
        k.setFilePath(fileStorage.save(tenantId(), k.getId(), fname, content));
        k.setCustomMetadata(mergeCustomMetadata(null));
        knowledgeMapper.insert(k);
        worker.enqueue(k.getId());
        return k;
    }

    /** status 仅 draft/publish */
    public Knowledge createManual(String kbId, String title, String content, String status,
                                  String channel) {
        KnowledgeBase kb = requireKb(kbId);
        if (!"draft".equals(status) && !"publish".equals(status)) {
            throw new BizException(AppError.validation("状态仅支持 draft 或 publish"));
        }
        Knowledge k = newKnowledge(kb, "manual", title, channel);
        k.setSource("manual");
        k.setFileName(ensureManualFileName(title));
        k.setFilePath("");
        k.setFileType("manual");
        k.setFileSize(0L);
        k.setFileHash("");
        // 入库后经 PG jsonb 规范化（键按长度+字节序），读回路径的 canonical 化在 PgJsonTypeHandler
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("content", content == null ? "" : content);
        metadata.put("format", "markdown");
        metadata.put("status", status);
        metadata.put("version", 1);
        metadata.put("updated_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        k.setMetadata(metadata);
        k.setCustomMetadata(mergeCustomMetadata(null));
        if ("draft".equals(status)) {
            k.setParseStatus("draft");
        }
        knowledgeMapper.insert(k);
        if ("publish".equals(status)) {
            worker.enqueue(k.getId());
        }
        return k;
    }

    /**
     * 段落<b>直接成 chunk</b>（不经 docreader/chunker），
     * 同步建索引后立即可检索。评估链路（EvalDataset 的临时 "evaluation" KB）专用。
     * <p>照抄语义：type="passage"、title 零值 ""、channel 空 → "web"；逐段 ValidateInput
     * 空段跳过后索引不回填）、Start/End 按字符数累计（len([]rune) 语义）；终态
     * enable_status=enabled + processed_at + updated_at，parse_status 有文本 chunk 时
     * 保持 processing。</p>
     * （KnowledgeService 无 audit 依赖，文件/手工路径同形）；② 问题生成
     * （QuestionGenerationConfig）与多模态未翻（与 worker 路径一致）；③ 向量/keyword
     */
    public Knowledge createFromPassageSync(String kbId, List<String> passages, String channel) {
        KnowledgeBase kb = requireKb(kbId);

        List<String> safePassages = new ArrayList<>(passages.size());
        for (int i = 0; i < passages.size(); i++) {
            String p = passages.get(i) == null ? "" : passages.get(i);
            String safe = InputSanitizer.validateInput(p);
            if (safe == null) {
                throw new BizException(AppError.validation("段落 " + (i + 1) + " 包含非法内容"));
            }
            safePassages.add(safe);
        }

        Knowledge k = newKnowledge(kb, "passage", "", channel);
        knowledgeMapper.insert(k);

        // 先原子翻 processing 再处理
        k.setParseStatus(Knowledge.PARSE_PROCESSING);
        k.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        knowledgeMapper.updateById(k);

        processPassagesSync(kb, k, safePassages);
        return k;
    }

    /**
     * 段落同步处理体：
     * 段落 1:1 成 chunk（空段跳过）→ 落库（前后链）→ 向量化 → 终态落库。
     */
    private void processPassagesSync(KnowledgeBase kb, Knowledge k, List<String> passages) {
        List<Chunk> chunks = new ArrayList<>(passages.size());
        int start = 0;
        int end = 0;
        String prevId = null;
        for (int i = 0; i < passages.size(); i++) {
            String p = passages.get(i);
            if (p.isEmpty()) {
                continue;
            }
            end += p.codePointCount(0, p.length());
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Chunk c = new Chunk();
            c.setId(UUID.randomUUID().toString());
            c.setCreatedAt(now);
            c.setUpdatedAt(now);
            c.setTenantId(k.getTenantId());
            c.setKnowledgeId(k.getId());
            c.setKnowledgeBaseId(k.getKnowledgeBaseId());
            c.setContent(p);
            c.setSourceContent(p);
            c.setChunkIndex(i); // 段落索引
            c.setStartAt(start);
            c.setEndAt(end);
            c.setChunkType("text");
            c.setIsEnabled(true); //  true（实体默认亦为 true）
            c.setPreChunkId(prevId);
            chunks.add(c);
            prevId = c.getId();
            start = end;
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (i + 1 < chunks.size()) {
                chunks.get(i).setNextChunkId(chunks.get(i + 1).getId());
            }
            chunkMapper.insert(chunks.get(i));
        }
        if (!chunks.isEmpty()) {
            chunkVectorIndexer.updateChunkVector(kb.getId(), chunks);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        k.setParseStatus(chunks.isEmpty() ? Knowledge.PARSE_COMPLETED : Knowledge.PARSE_PROCESSING);
        k.setEnableStatus("enabled");
        k.setProcessedAt(now);
        k.setUpdatedAt(now);
        knowledgeMapper.updateById(k);
    }

    private Knowledge newKnowledge(KnowledgeBase kb, String type, String title, String channel) {
        Knowledge k = new Knowledge();
        k.setId(UUID.randomUUID().toString());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        k.setTenantId(tenantId());
        k.setKnowledgeBaseId(kb.getId());
        k.setType(type);
        // Source 按来源各异——file 上传为零值 ""，url 记 url，manual 记 manual
        k.setTitle(title == null ? "" : title);
        k.setParseStatus(Knowledge.PARSE_PENDING);
        k.setEnableStatus("disabled"); // 契约样例锁定：上传后 disabled，处理完成转 enabled
        k.setEmbeddingModelId(kb.getEmbeddingModelId());
        k.setChannel(channel == null || channel.isEmpty() ? "web" : channel); // 缺省 web
        k.setFolderPath(""); // PG 列 NOT NULL，空串为缺省
        return k;
    }

    private static JsonNode mergeCustomMetadata(JsonNode provided) {
        if (provided != null && provided.isObject()) {
            return provided;
        }
        return MAPPER.createObjectNode();
    }

    /** 同包开放（KnowledgeSummaryPipelineService.updateManualKnowledge 复用）。 */
    static String ensureManualFileName(String title) {
        String base = title == null || title.isBlank() ? "manual" : title.trim();
        return base.endsWith(".md") ? base : base + ".md";
    }

    static String extractFileNameFromUrl(String url) {
        if (url == null) {
            return "download";
        }
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? "download" : name;
    }

    private static byte[] fetchUrl(String url) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofMinutes(2))
                    .GET()
                    .build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new BizException(AppError.badRequest("failed to fetch URL: HTTP " + resp.statusCode()));
            }
            return resp.body();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("failed to fetch URL: " + e.getMessage()));
        }
    }

    // ── 查询 / 更新 / 删除 ────────────────────────────────────────────────

    /** 真分页（page 默认 1，page_size 默认 20 上限 1000） */
    public Page<Knowledge> listKnowledge(String kbId, long page, long pageSize,
                                         String keyword, String parseStatus, String fileType,
                                         String folderPath, boolean folderPresent) {
        requireKb(kbId);
        LambdaQueryWrapper<Knowledge> qw = new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .isNull(Knowledge::getDeletedAt)
                .orderByDesc(Knowledge::getCreatedAt);
        if (keyword != null && !keyword.isEmpty()) {
            qw.like(Knowledge::getTitle, keyword);
        }
        if (parseStatus != null && !parseStatus.isEmpty()) {
            qw.eq(Knowledge::getParseStatus, parseStatus);
        }
        if (fileType != null && !fileType.isEmpty()) {
            qw.eq(Knowledge::getFileType, fileType);
        }
        if (folderPresent) {
            qw.eq(Knowledge::getFolderPath, folderPath == null ? "" : folderPath);
        }
        return knowledgeMapper.selectPage(new Page<>(page, pageSize), qw);
    }

    public Knowledge getKnowledge(String id) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            throw new BizException(AppError.notFound("Knowledge not found"));
        }
        // 按 knowledgeId 操作的端点，
        // 用其所属 KB 做 scope 校验（KB 受限的 Key 不得越界）。
        TenantAPIKeyScope.authorizeKnowledgeBases(
                java.util.List.of(k.getKnowledgeBaseId()));
        // 回填 tags（knowledge_tag_relations 连接查；
        // 无关系 → 保持 null，与 契约样例 "tags":null 一致）
        attachTags(k);
        return k;
    }

    /** 无租户过滤（守卫/权限解析用）。 */
    public Knowledge getKnowledgeByIdOnly(String id) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 调用者空间内的可空读取。 */
    public Knowledge getKnowledgeInTenant(long tenantId, String id) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /**
     * 按 (tenant, ids) 批量取，<b>不回填 tags</b>
     */
    public List<Knowledge> getKnowledgeBatch(long tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        return knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getTenantId, tenantId)
                .in(Knowledge::getId, ids)
                .isNull(Knowledge::getDeletedAt));
    }

    /**
     * 未翻译（本仓约定 阶段 3 差异 3），共享路径的"补捞"只对同租户行有效，而租户内行
     */
    public List<Knowledge> getKnowledgeBatchWithSharedAccess(long tenantId, List<String> ids) {
        return getKnowledgeBatch(tenantId, ids);
    }

    /** 有关系才回填（无关系保持 null）。 */
    public void attachTags(Knowledge k) {
        if (k == null) {
            return;
        }
        List<KnowledgeTag> rows = tagMapper.selectTagsWithKnowledgeId(List.of(k.getId()));
        if (!rows.isEmpty()) {
            k.setTags(new ArrayList<>(rows.stream().map(KnowledgeService::tagView).toList()));
        }
    }

    /** 标签视图（时间序列化与全局 Jackson 配置同式）。 */
    public static ObjectNode tagView(KnowledgeTag t) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", t.getId());
        n.put("seqId", t.getSeqId() == null ? 0L : t.getSeqId());
        n.put("knowledgeBaseId", t.getKnowledgeBaseId());
        n.put("name", t.getName());
        n.put("color", t.getColor() == null ? "" : t.getColor());
        n.put("sortOrder", t.getSortOrder() == null ? 0 : t.getSortOrder());
        n.put("createdAt", t.getCreatedAt() == null ? null : timeString(t.getCreatedAt()));
        n.put("updatedAt", t.getUpdatedAt() == null ? null : timeString(t.getUpdatedAt()));
        return n;
    }

    /** 同包开放（KnowledgeSpanService 渲染 span 时间戳复用，不各自复制）。 */
    static String timeString(OffsetDateTime v) {
        return v.atZoneSameInstant(java.time.ZoneId.systemDefault()).toOffsetDateTime()
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** title/description(指针)/custom_metadata 部分更新 */
    public Knowledge updateKnowledge(String id, UpdateKnowledgeRequest req) {
        Knowledge k = getKnowledge(id);
        if (req != null) {
            if (req.title() != null) {
                k.setTitle(req.title());
            }
            if (req.description() != null) {
                k.setDescription(req.description().isNull() ? "" : req.description().asText());
                // description 显式更新联动 summary_status
                k.setSummaryStatus(k.getDescription().isEmpty() ? "none" : "completed");
            }
            JsonNode cm = req.customMetadata();
            if (cm != null) {
                k.setCustomMetadata(cm.isObject() ? cm : MAPPER.createObjectNode());
            }
        }
        k.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        knowledgeMapper.updateById(k);
        return getKnowledge(id);
    }

    public String deleteKnowledge(String id) {
        Knowledge k = getKnowledge(id);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", k.getId()).set("deleted_at", now));
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .eq("knowledge_id", k.getId()).set("deleted_at", now));
        fileStorage.delete(tenantId(), k.getId(), k.getFilePath());
        return UUID.randomUUID().toString();
    }

    // ── folders / 文件夹移动 / 重命名（委托 KnowledgeFolderService） ─────

    public JsonNode folderTree(String kbId) {
        return folderService.folderTree(kbId);
    }
    public static String normalizeKnowledgeFolderPath(String raw) {
        return KnowledgeFolderService.normalizeKnowledgeFolderPath(raw);
    }

    @Transactional
    public long moveKnowledgeToFolder(String kbId, List<String> ids, String folderPath) {
        return folderService.moveKnowledgeToFolder(kbId, ids, folderPath);
    }

    @Transactional
    public long renameKnowledgeFolder(String kbId, String from, String to) {
        return folderService.renameKnowledgeFolder(kbId, from, to);
    }

    public List<Knowledge> loadKnowledgeWriteBatch(List<String> ids, String grantedKbId) {
        return folderService.loadKnowledgeWriteBatch(ids, grantedKbId);
    }

    public Knowledge loadKnowledgeWrite(String id) {
        return folderService.loadKnowledgeWrite(id);
    }

    // ── 文档操作面（委托 KnowledgeSpanService） ─────────────────────

    public ObjectNode knowledgeSpans(Knowledge knowledge, int requestedAttempt) {
        return spanService.knowledgeSpans(knowledge, requestedAttempt);
    }

    // ── 解析状态机 + 摘要管线（委托专项服务） ────

    public Knowledge regenerateKnowledgeSummary(String id) {
        return knowledgeSummaryService.regenerateKnowledgeSummary(id);
    }

    public void requestPostProcessSummaryGeneration(String knowledgeId) {
        knowledgeSummaryService.requestPostProcessSummaryGeneration(knowledgeId);
    }

    public void requestKnowledgeSummaryRefresh(String id) {
        knowledgeSummaryService.requestKnowledgeSummaryRefresh(id);
    }

    public Knowledge updateManualKnowledge(String id, String title, String content,
                                           String status, String channel) {
        return knowledgeFileService.updateManualKnowledge(id, title, content, status, channel);
    }

    public Knowledge reparseKnowledge(String id) {
        return knowledgeParseService.reparseKnowledge(id);
    }

    public Knowledge cancelKnowledgeParse(String id) {
        return knowledgeParseService.cancelKnowledgeParse(id);
    }

    public KnowledgeFileService.KnowledgeFileStream openKnowledgeFile(String id) {
        return knowledgeFileService.openKnowledgeFile(id);
    }

    @Transactional
    public void updateImageInfo(String knowledgeId, String chunkId, String rawImageInfo) {
        knowledgeFileService.updateImageInfo(knowledgeId, chunkId, rawImageInfo);
    }

    // ── tags 批量 ──────────────────────────────────────────────────

    /**
     * * authorizedKBID 为空 = 未显式给 kb_id（由首条 knowledge 推导的授权范围）。
     */
    @Transactional
    public void updateKnowledgeTagBatch(String authorizedKBID, Map<String, List<String>> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        List<String> knowledgeIDs = new ArrayList<>(updates.keySet());
        knowledgeIDs.sort(String::compareTo);
        // 授权 KB = 显式 kb_id；无 kb_id 时 = 首条（排序后最靠前的）knowledge 所属 KB
        String grantedKbId = authorizedKBID;
        if (grantedKbId == null || grantedKbId.isEmpty()) {
            Knowledge first = getKnowledge(knowledgeIDs.get(0));
            grantedKbId = first.getKnowledgeBaseId();
        }
        List<Knowledge> knowledgeList = loadKnowledgeWriteBatch(knowledgeIDs, grantedKbId);
        long tenantId = knowledgeList.get(0).getTenantId();

        if (authorizedKBID != null && !authorizedKBID.isEmpty()) {
            if (knowledgeList.size() != updates.size()) {
                throw BizException.forbidden("some knowledge IDs are not accessible in the authorized scope");
            }
            for (Knowledge k : knowledgeList) {
                if (!k.getKnowledgeBaseId().equals(authorizedKBID)) {
                    throw BizException.forbidden("knowledge " + k.getId()
                            + " does not belong to authorized knowledge base");
                }
            }
        }
        // 收集 + 校验标签
        java.util.Set<String> tagIDSet = new java.util.TreeSet<>();
        for (List<String> tagIDs : updates.values()) {
            for (String tagID : tagIDs) {
                if (tagID != null && !tagID.isEmpty()) {
                    tagIDSet.add(tagID);
                }
            }
        }
        Map<String, KnowledgeTag> tagMap = new java.util.HashMap<>();
        if (!tagIDSet.isEmpty()) {
            List<KnowledgeTag> tags = tagMapper.selectByTenantAndIds(tenantId, List.copyOf(tagIDSet));
            for (KnowledgeTag tag : tags) {
                tagMap.put(tag.getId(), tag);
            }
        }
        for (Knowledge k : knowledgeList) {
            List<String> tagIDs = updates.get(k.getId());
            if (tagIDs == null) {
                continue;
            }
            for (String tagID : tagIDs) {
                if (tagID == null || tagID.isEmpty()) {
                    continue;
                }
                KnowledgeTag tag = tagMap.get(tagID);
                if (tag == null) {
                    throw BizException.badRequest("标签 " + tagID + " 不存在");
                }
                if (tag.getTenantId() == null || tag.getTenantId() != tenantId
                        || !tag.getKnowledgeBaseId().equals(k.getKnowledgeBaseId())) {
                    throw BizException.badRequest("标签 " + tagID + " 不属于知识库 " + k.getKnowledgeBaseId());
                }
            }
        }
        for (String knowledgeID : knowledgeIDs) {
            setKnowledgeTags(knowledgeID, updates.get(knowledgeID));
        }
    }

    /** 删旧 + 插新（空/重复 id 跳过）。 */
    private void setKnowledgeTags(String knowledgeId, List<String> tagIDs) {
        tagMapper.deleteRelations(knowledgeId);
        if (tagIDs == null || tagIDs.isEmpty()) {
            return;
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String tagID : tagIDs) {
            if (tagID != null && !tagID.isEmpty() && seen.add(tagID)) {
                tagMapper.insertRelation(knowledgeId, tagID);
            }
        }
    }

    // ── 批量删除 / 批量重解析 / 清空（委托 KnowledgeBatchOpsService） ──

    @Transactional
    public String batchDeleteKnowledge(String kbId, List<String> ids) {
        return batchOpsService.batchDeleteKnowledge(kbId, ids);
    }

    public String batchReparseKnowledge(String kbId, List<String> ids) {
        return batchOpsService.batchReparseKnowledge(kbId, ids);
    }

    public int rebuildKnowledgeBaseIndex(String kbId) {
        return batchOpsService.rebuildKnowledgeBaseIndex(kbId);
    }

    @Transactional
    public int clearKnowledgeBaseContents(String kbId) {
        return batchOpsService.clearKnowledgeBaseContents(kbId);
    }

    public Knowledge loadById(String id) {
        return batchOpsService.loadById(id);
    }

    public void updateStatus(String id, String parseStatus, String errorMessage, Boolean enable) {
        batchOpsService.updateStatus(id, parseStatus, errorMessage, enable);
    }

    // ── 搜索（委托 KnowledgeSearchService） ──────────────────────────────

    public KnowledgeSearchService.SearchOutcome searchKnowledge(String keyword, int offset, int limit,
            List<String> fileTypes) {
        return searchService.searchKnowledge(keyword, offset, limit, fileTypes);
    }

    public KnowledgeSearchService.SearchOutcome searchKnowledgeInScopes(
            List<KnowledgeSearchService.KnowledgeSearchScope> scopes, String keyword,
            int offset, int limit, List<String> fileTypes) {
        return searchService.searchKnowledgeInScopes(scopes, keyword, offset, limit, fileTypes);
    }

    // ── Move（委托 KnowledgeMoveService；空间/段拆分见该类） ─────────────

    public void startKnowledgeMove(long tenantId, String taskId, List<String> knowledgeIds,
                                   String sourceKbId, String targetKbId, String mode) {
        moveService.startKnowledgeMove(tenantId, taskId, knowledgeIds, sourceKbId, targetKbId, mode);
    }

    public void saveKnowledgeMoveProgress(KnowledgeMoveProgress p) {
        moveService.saveKnowledgeMoveProgress(p);
    }

    public KnowledgeMoveProgress getKnowledgeMoveProgress(String taskId) {
        return moveService.getKnowledgeMoveProgress(taskId);
    }

    // ── KB clone / Duplicate / 跨库兼容性（委托 KnowledgeCloneService） ──

    public void startKBClone(long tenantId, String taskId, String sourceId, String targetId,
            boolean createTarget, String creatorId) {
        cloneService.startKBClone(tenantId, taskId, sourceId, targetId, createTarget, creatorId);
    }

    public void saveKBCloneProgress(KBCloneProgress p) {
        cloneService.saveKBCloneProgress(p);
    }

    public KBCloneProgress getKBCloneProgress(String taskId) {
        return cloneService.getKBCloneProgress(taskId);
    }

    public KnowledgeBase duplicateKnowledgeBase(String sourceId) {
        return cloneService.duplicateKnowledgeBase(sourceId);
    }

    public static void validateKBTransferCompatibility(KnowledgeBase source, KnowledgeBase target,
            String requestedVectorStoreId) {
        KnowledgeCloneService.validateKBTransferCompatibility(source, target, requestedVectorStoreId);
    }

    public static void validateCloneCompatibility(KnowledgeBase source, KnowledgeBase target) {
        KnowledgeCloneService.validateCloneCompatibility(source, target);
    }

    /**
     * 上传内容与库内已有文档重复（409）。
     *
     * <p>继承 {@link BizException}：错误体由全局异常处理器统一产出
     * （{@code {error:{code,message,details}, success:false}}），已存在文档的 ID 放
     * {@code details.existingKnowledgeId} 供前端跳转——取代历史上的特殊信封
     * （{@code {code,data,message,success}}）。</p>
     */
    public static class DuplicateKnowledgeException extends BizException {
        private final Knowledge existing;

        public DuplicateKnowledgeException(Knowledge existing, ErrorCode code, String message) {
            super(new AppError(code.value(), message, null, 409)
                    .withDetails(existing == null ? null
                            : new DuplicateKnowledgeDetails(existing.getId())));
            this.existing = existing;
        }

        public Knowledge existing() { return existing; }
    }

    /** 占位：worker bean 由 KnowledgeProcessWorker 提供（@Lazy 避免循环依赖） */
    public interface KnowledgeProcessWorker {
        void enqueue(String knowledgeId);
    }
}
