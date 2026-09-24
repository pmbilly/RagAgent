package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.CleanInvalidUtf8;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.InputSanitizer;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.KbIndexingStrategy;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.agent.AgentPromptPlaceholders;
import com.ragagent.config.ConversationProperties;
import com.ragagent.knowledge.dto.KnowledgeTaskDtos.KBCloneProgress;
import com.ragagent.knowledge.dto.KnowledgeTaskDtos.KnowledgeMoveProgress;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.mapper.KnowledgeTagMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.searchutil.ImageInfoEnricher;
import com.ragagent.searchutil.SearchChunkMerge;
import com.ragagent.wiki.service.WikiImageMarkup;
import com.ragagent.wiki.service.WikiLanguageSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对照 Go internal/application/service/knowledge.go + knowledge_create.go
 * （阶段 3 子集：file/url/manual 创建、分页列表、get/update/delete、folders；
 *  处理管道 = pending→processing→(docreader→chunk→embed)→completed/failed，
 *  asynq 以进程内虚拟线程队列替代（响应契约一致，重试/取消语义见约定 §9））。
 *
 * <p><b>波 2 扩展（文档操作面）</b>：spans 合成树、regenerate-summary（无 summary
 * model 的确定性 400）、manual 更新、reparse/cancel-parse、download/preview 文件解析、
 * image info、tags 批量、batch-delete/batch-reparse/clear-contents（asynq →
 * 同步尽力而为，HTTP 契约 = task_id + 文案）、folders 树升级为完整
 * BuildKnowledgeFolderTree、GET 侧回填 tags。</p>
 *
 * <p><b>已知差异（记录于各类注释）</b>：
 * ① 批量删除/清空在 Go 是 asynq 异步清理（向量/文件/wiki 一并回收），Java 为同步
 *    软删（chunk+knowledge 行），HTTP 响应逐字节一致；② reparse 的资源清理只对齐
 *    "删 chunks"这一可观测子集；③ 刷新型摘要为进程内虚拟线程（asynq 语义取舍，
 *    重试对齐 MaxRetry(3)）；④ updateChunkVector 全链已接线（ChunkVectorIndexer，
 *    含真 embedding 与生成问题行重建）。</p>
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** duplicate 的配置克隆用：知识实体带 OffsetDateTime，往返 mapper 必须挂 JSR310（§9 步 3 教训）。 */
    private static final ObjectMapper CLONE_MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Go types/knowledge_span.go 的 5 段 canonical 时间线（顺序即合成顺序） */
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
    private final ChunkRepository chunkRepo;
    private final ModelRuntimeFactory modelRuntimeFactory;
    private final ChunkVectorIndexer chunkVectorIndexer;
    private final ConversationProperties conversationProps;
    private final com.ragagent.knowledge.mapper.KnowledgeSpanRepository spanRepository;
    private final SpanTracker spanTracker;
    /** 图库仓储（D 批）：知识移动后清源命名空间（对照 Go knowledge_clone_move.go L1342-1352）。 */
    private final com.ragagent.chatpipeline.PipelinePorts.RetrieveGraphRepository graphRepository;

    public KnowledgeService(KnowledgeMapper knowledgeMapper,
                            KnowledgeBaseMapper kbMapper,
                            ChunkMapper chunkMapper,
                            KnowledgeTagMapper tagMapper,
                            LocalStorageService storage,
                            TenantFileStorage fileStorage,
                            @Lazy KnowledgeProcessWorker worker,
                            KnowledgeTaskProgressStore progressStore,
                            ChunkRepository chunkRepo,
                            ModelRuntimeFactory modelRuntimeFactory,
                            ChunkVectorIndexer chunkVectorIndexer,
                            ConversationProperties conversationProps,
                            com.ragagent.knowledge.mapper.KnowledgeSpanRepository spanRepository,
                            SpanTracker spanTracker,
                            com.ragagent.chatpipeline.PipelinePorts.RetrieveGraphRepository graphRepository) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.tagMapper = tagMapper;
        this.storage = storage;
        this.fileStorage = fileStorage;
        this.worker = worker;
        this.progressStore = progressStore;
        this.chunkRepo = chunkRepo;
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.chunkVectorIndexer = chunkVectorIndexer;
        this.conversationProps = conversationProps;
        this.spanRepository = spanRepository;
        this.spanTracker = spanTracker;
        this.graphRepository = graphRepository;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    /** 对照 knowledgeBaseService.GetKnowledgeBaseByID 的原始查找（nullable，路由级
     *  ownership 守卫用：缺失放行）；无 API-Key 白名单口（白名单在 requireKbAccess）。 */
    public KnowledgeBase findKb(String kbId) {
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
    }

    public KnowledgeBase requireKb(String kbId) {
        // 对照 AuthorizeTenantAPIKeyKnowledgeBases：KB 受限的 API Key 不能触碰白名单外的库。
        // 放在这里是因为所有文档端点都经过它——一处覆盖全部（Go 侧是分散在 handler 里逐个调的）。
        com.ragagent.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeBases(
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

    /** 对照 CreateKnowledgeFromFile（multipart 已在 controller 解析为字节） */
    public Knowledge createFromFile(String kbId, byte[] fileContent, String fileName,
                                    String displayName, JsonNode customMetadata, String channel) {
        KnowledgeBase kb = requireKb(kbId);
        long maxBytes = LocalStorageService.maxFileSizeBytes();
        if (fileContent.length > maxBytes) {
            throw new BizException(AppError.badRequest(
                    String.format("文件大小不能超过%dMB", LocalStorageService.maxFileSizeMb())));
        }
        String hash = LocalStorageService.md5Hex(fileContent);
        // 重复文件检查（对照 409 duplicate_file）
        Knowledge dup = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getFileHash, hash)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (dup != null) {
            throw new DuplicateKnowledgeException(dup, "duplicate_file", "文件已存在（相同内容）");
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

    /** 对照 CreateKnowledgeFromURL：阶段 3 拉取 URL 内容按文件入库（SSRF 校验在 controller） */
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
            throw new DuplicateKnowledgeException(dup, "duplicate_url", "URL 已存在");
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

    /** 对照 CreateKnowledgeFromManual：status 仅 draft/publish */
    public Knowledge createManual(String kbId, String title, String content, String status,
                                  String channel) {
        KnowledgeBase kb = requireKb(kbId);
        if (!"draft".equals(status) && !"publish".equals(status)) {
            throw new BizException(AppError.validation("状态仅支持 draft 或 publish"));
        }
        Knowledge k = newKnowledge(kb, "manual", title, channel);
        k.setSource("manual");
        k.setFileName(ensureManualFileName(title));
        // Go string 零值：file_path 恒输出 ""（golden 锁定）
        k.setFilePath("");
        k.setFileType("manual");
        k.setFileSize(0L);
        k.setFileHash("");
        // 键序 = Go ManualKnowledgeMetadata struct 声明序（create 响应是内存对象，逐字节契约）；
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
     * 对照 CreateKnowledgeFromPassageSync（knowledge_create.go L719-724 入口 +
     * createKnowledgeFromPassageInternal 的 syncMode 分支 + processDocumentFromPassage，
     * knowledge_process.go L155-186）：段落<b>直接成 chunk</b>（不经 docreader/chunker），
     * 同步建索引后立即可检索。评估链路（EvalDataset 的临时 "evaluation" KB）专用。
     *
     * <p>照抄语义：type="passage"、title 零值 ""、channel 空 → "web"；逐段 ValidateInput
     * （失败 → 400 "段落 N 包含非法内容"）；ChunkIndex=<b>原段落索引</b>（Go Seq=i，
     * 空段跳过后索引不回填）、Start/End 按字符数累计（len([]rune) 语义）；终态
     * enable_status=enabled + processed_at + updated_at，parse_status 有文本 chunk 时
     * 保持 processing（对照 finalizeIndexedKnowledgeState：唯一晋升者是 post-process——
     * 评估临时知识不入队 post-process，生命周期由 EvalDataset 的清理步收尾）。</p>
     *
     * <p><b>已知差异（备案）</b>：① Go sync 路径的 recordKBActivity 审计未接线
     * （KnowledgeService 无 audit 依赖，文件/手工路径同形）；② 问题生成
     * （QuestionGenerationConfig）与多模态未翻（与 worker 路径一致）；③ 向量/keyword
     * 全关的 KB 走 updateChunkVector 的内部短路（照 Go 的 needsEmbedding 判定）。</p>
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

        // 对照 processDocumentFromPassage 首步：先原子翻 processing 再处理
        k.setParseStatus(Knowledge.PARSE_PROCESSING);
        k.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        knowledgeMapper.updateById(k);

        processPassagesSync(kb, k, safePassages);
        return k;
    }

    /**
     * 段落同步处理体（对照 processDocumentFromPassage → processChunks 的段落路径）：
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
            c.setChunkIndex(i); // 对照 Go ChunkIndex = int(chunkData.Seq)（原段落索引）
            c.setStartAt(start);
            c.setEndAt(end);
            c.setChunkType("text");
            c.setIsEnabled(true); // 对照 Go 的 IsEnabled: true（实体默认亦为 true）
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
        // 对照 Go：Source 按来源各异——file 上传为零值 ""，url 记 url，manual 记 manual
        k.setTitle(title == null ? "" : title);
        k.setParseStatus(Knowledge.PARSE_PENDING);
        k.setEnableStatus("disabled"); // golden 锁定：上传后 disabled，处理完成转 enabled
        k.setEmbeddingModelId(kb.getEmbeddingModelId());
        k.setChannel(channel == null || channel.isEmpty() ? "web" : channel); // 对照 defaultChannel
        k.setFolderPath(""); // Go 零值，PG 列 NOT NULL
        return k;
    }

    private static JsonNode mergeCustomMetadata(JsonNode provided) {
        if (provided != null && provided.isObject()) {
            return provided;
        }
        return MAPPER.createObjectNode();
    }

    private static String ensureManualFileName(String title) {
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

    /** 对照 ListKnowledge：真分页（page 默认 1，page_size 默认 20 上限 1000） */
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
        // 对照 AuthorizeTenantAPIKeyKnowledgeTargets：按 knowledgeId 操作的端点，
        // 用其所属 KB 做 scope 校验（KB 受限的 Key 不得越界）。
        com.ragagent.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeBases(
                java.util.List.of(k.getKnowledgeBaseId()));
        // 对照 GetKnowledgeByID（service 层）：回填 tags（knowledge_tag_relations 连接查；
        // 无关系 → 保持 null，与 golden "tags":null 一致）
        attachTags(k);
        return k;
    }

    /** 对照 repo.GetKnowledgeByIDOnly：无租户过滤（守卫/权限解析用）。 */
    public Knowledge getKnowledgeByIdOnly(String id) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 调用者空间内的可空读取（对照 move handler 里 service GetKnowledgeByID 的
     *  租户过滤语义；查不到返回 null，由调用方决定错误文案）。 */
    public Knowledge getKnowledgeInTenant(long tenantId, String id) {
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /**
     * 对照 repo.GetKnowledgeBatch：按 (tenant, ids) 批量取，<b>不回填 tags</b>
     * （Go 只有 GetKnowledgeByID/list 分页路径回填；batch 响应恒 "tags":null）。
     * GORM Find 恒返回非 nil 切片 → Java 恒返回 List（空也 []，不 null）。
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
     * 对照 GetKnowledgeBatchWithSharedAccess 的同租户收敛形态：kb_shares / shared-agent
     * 未翻译（约定 §9 阶段 3 差异 3），共享路径的"补捞"只对同租户行有效，而租户内行
     * 已被第一条批量查询覆盖——净效果即按租户的批量读。恒非 null（GORM Find 语义）。
     */
    public List<Knowledge> getKnowledgeBatchWithSharedAccess(long tenantId, List<String> ids) {
        return getKnowledgeBatch(tenantId, ids);
    }

    /** 对照 attachTagsToKnowledge：有关系才回填（无关系保持 null）。 */
    public void attachTags(Knowledge k) {
        if (k == null) {
            return;
        }
        List<KnowledgeTag> rows = tagMapper.selectTagsWithKnowledgeId(List.of(k.getId()));
        if (!rows.isEmpty()) {
            k.setTags(new ArrayList<>(rows.stream().map(KnowledgeService::tagView).toList()));
        }
    }

    /** 对照 KnowledgeTag struct 的 JSON 形态（字段声明序，color 零值 ""）。
     *  时间与 JacksonConfig 的 OffsetDateTime 序列化同式（JVM 默认时区 + ISO_OFFSET）。 */
    public static ObjectNode tagView(KnowledgeTag t) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", t.getId());
        n.put("seq_id", t.getSeqId() == null ? 0L : t.getSeqId());
        n.put("tenant_id", t.getTenantId() == null ? 0L : t.getTenantId());
        n.put("knowledge_base_id", t.getKnowledgeBaseId());
        n.put("name", t.getName());
        n.put("color", t.getColor() == null ? "" : t.getColor());
        n.put("sort_order", t.getSortOrder() == null ? 0 : t.getSortOrder());
        n.put("created_at", t.getCreatedAt() == null ? null : goTimeString(t.getCreatedAt()));
        n.put("updated_at", t.getUpdatedAt() == null ? null : goTimeString(t.getUpdatedAt()));
        return n;
    }

    private static String goTimeString(OffsetDateTime v) {
        return v.atZoneSameInstant(java.time.ZoneId.systemDefault()).toOffsetDateTime()
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** 对照 UpdateKnowledge：title/description(指针)/custom_metadata 部分更新 */
    public Knowledge updateKnowledge(String id, JsonNode body) {
        Knowledge k = getKnowledge(id);
        if (body != null) {
            if (body.hasNonNull("title")) {
                k.setTitle(body.get("title").asText());
            }
            if (body.has("description")) {
                k.setDescriptionSpecified(true);
                k.setDescription(body.get("description").isNull() ? "" : body.get("description").asText());
                // 对照 UpdateKnowledge：description 显式更新联动 summary_status
                k.setSummaryStatus(k.getDescription().isEmpty() ? "none" : "completed");
            }
            if (body.has("custom_metadata")) {
                JsonNode cm = body.get("custom_metadata");
                k.setCustomMetadata(cm != null && cm.isObject() ? cm : MAPPER.createObjectNode());
            }
        }
        k.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        knowledgeMapper.updateById(k);
        return getKnowledge(id);
    }

    /** 对照 DeleteKnowledge：异步语义（Go 入队删除）→ 阶段 3 同步软删 + 返回 task_id（响应契约一致） */
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

    // ── folders（波 2 升级：对照 types.BuildKnowledgeFolderTree 完整树） ──

    /**
     * 对照 KnowledgeFolderTree：root_document_count/total_document_count/folders。
     * 计数只排除 parse_status='deleting'（draft 计入）+ 软删行；中间空目录会被
     * 具体化以保持层级连通；同名排序按 path 字节序（Go strings.&lt;）。
     */
    public JsonNode folderTree(String kbId) {
        requireKb(kbId);
        // 对照 ListKnowledgeFolderCounts：GROUP BY folder_path（Java 侧取列后内存聚合，
        // 语义一致：tenant+kb+parse_status<>'deleting'+deleted_at IS NULL）
        List<Knowledge> docs = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .select(Knowledge::getFolderPath)
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getTenantId, tenantId())
                .isNull(Knowledge::getDeletedAt)
                .ne(Knowledge::getParseStatus, Knowledge.PARSE_DELETING));
        Map<String, Long> counts = new TreeMap<>();
        for (Knowledge d : docs) {
            counts.merge(d.getFolderPath() == null ? "" : d.getFolderPath(), 1L, Long::sum);
        }

        long rootCount = 0;
        long totalCount = 0;
        Map<String, ObjectNode> nodes = new TreeMap<>();
        Map<String, List<ObjectNode>> children = new TreeMap<>();
        ArrayNode top = MAPPER.createArrayNode();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            long count = e.getValue();
            totalCount += count;
            String path = normalizeKnowledgeFolderPath(e.getKey());
            if (path.isEmpty()) {
                rootCount += count;
                continue;
            }
            ObjectNode node = ensureFolderNode(path, nodes, children, top);
            node.put("document_count", node.path("document_count").asLong() + count);
        }
        // 对照：深度优先回卷 total_count（深路径先算，父级累加子树）
        List<String> paths = new ArrayList<>(nodes.keySet());
        paths.sort((a, b) -> {
            int da = countChar(a, '/'), db = countChar(b, '/');
            return da != db ? Integer.compare(db, da) : a.compareTo(b);
        });
        for (String path : paths) {
            ObjectNode node = nodes.get(path);
            long total = node.path("document_count").asLong() + children
                    .getOrDefault(path, List.of()).stream()
                    .mapToLong(c -> c.path("total_count").asLong()).sum();
            node.put("total_count", total);
        }
        // 对照 sortNodes：children/Folders 都按 name 的小写序排；空 children 整键缺席
        //（KnowledgeFolderNode.Children omitempty）
        for (Map.Entry<String, List<ObjectNode>> e : children.entrySet()) {
            List<ObjectNode> list = e.getValue();
            list.sort(byNameLower);
            nodes.get(e.getKey()).set("children", MAPPER.createArrayNode().addAll(list));
        }
        List<ObjectNode> topList = new ArrayList<>();
        top.forEach(n -> topList.add((ObjectNode) n));
        topList.sort(byNameLower);
        top.removeAll();
        topList.forEach(top::add);
        ObjectNode tree = MAPPER.createObjectNode();
        tree.put("root_document_count", rootCount);
        tree.put("total_document_count", totalCount);
        tree.set("folders", top);
        return tree;
    }

    private static final java.util.Comparator<ObjectNode> byNameLower =
            java.util.Comparator.comparing(n -> n.path("name").asText("").toLowerCase(java.util.Locale.ROOT));

    /** 对照 ensure：节点 + 缺失祖先具体化；顶级挂 Folders，子级挂 parent.Children。 */
    private static ObjectNode ensureFolderNode(String path, Map<String, ObjectNode> nodes,
                                               Map<String, List<ObjectNode>> children, ArrayNode top) {
        ObjectNode existing = nodes.get(path);
        if (existing != null) {
            return existing;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("path", path);
        node.put("name", folderName(path));
        node.put("document_count", 0);
        node.put("total_count", 0);
        nodes.put(path, node);
        String parent = folderParent(path);
        if (parent.isEmpty()) {
            top.add(node);
        } else {
            ensureFolderNode(parent, nodes, children, top);
            children.computeIfAbsent(parent, k -> new ArrayList<>()).add(node);
        }
        return node;
    }

    private static String folderName(String path) {
        if (path.isEmpty()) {
            return "";
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static String folderParent(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(0, idx) : "";
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /**
     * 对照 types.NormalizeKnowledgeFolderPath（knowledge_folder.go L38-82）：
     * \ → /、分段 trim、去尾部 ". "、跳过空/./.. 段、单段 ≤128 字节、深度 ≤16、总长 ≤1024。
     */
    public static String normalizeKnowledgeFolderPath(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        raw = raw.replace('\\', '/');
        List<String> segments = new ArrayList<>(8);
        for (String segment : raw.split("/", -1)) {
            segment = segment.trim();
            segment = stripTrailing(segment, ". ");
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                continue;
            }
            if (segment.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128) {
                byte[] bytes = segment.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                int cut = 128;
                while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
                    cut--; // 回退到 rune 起点
                }
                segment = new String(bytes, 0, cut, java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            if (segment.isEmpty()) {
                continue;
            }
            segments.add(segment);
            if (segments.size() >= 16) {
                break;
            }
        }
        String path = String.join("/", segments);
        while (path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 && !segments.isEmpty()) {
            segments = segments.subList(0, segments.size() - 1);
            path = String.join("/", segments);
        }
        return path;
    }

    private static String stripTrailing(String s, String cutset) {
        while (!s.isEmpty() && cutset.indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ── 文件夹移动 / 重命名（波 2） ──────────────────────────────────────

    /**
     * 对照 MoveKnowledgeToFolder（service 层）。调用前 handler 已做
     * requireKnowledgeInKB，这里的 loadKnowledgeWriteBatch/kb 校验属 Go 的双保险，
     * Java 保留同序（writeResourceIDs 空 id → 400；跨 KB → 403 "knowledge outside target KB"）。
     *
     * @return affected 行数（UpdateKnowledgeFolderPath 的 RowsAffected）
     */
    @Transactional
    public long moveKnowledgeToFolder(String kbId, List<String> ids, String folderPath) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.badRequest("knowledge_ids cannot be empty");
        }
        String normalized = normalizeTargetFolderPath(folderPath);
        List<Knowledge> rows = loadKnowledgeWriteBatch(ids, kbId);
        List<String> checkedIds = new ArrayList<>(rows.size());
        for (Knowledge row : rows) {
            if (!row.getKnowledgeBaseId().equals(kbId)) {
                throw BizException.forbidden("knowledge outside target KB");
            }
            checkedIds.add(row.getId());
        }
        long tenantId = rows.get(0).getTenantId();
        return knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("tenant_id", tenantId)
                .eq("knowledge_base_id", kbId)
                .in("id", checkedIds)
                .set("folder_path", normalized)
                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * 对照 RenameKnowledgeFolder（service 层）：source/target 规范化、同路径短路面、
     * 不能移进自身子目录、KB 缺失 → 404。@return affected 行数。
     */
    @Transactional
    public long renameKnowledgeFolder(String kbId, String from, String to) {
        String source = normalizeKnowledgeFolderPath(from);
        if (source.isEmpty()) {
            throw BizException.badRequest("源文件夹路径不能为空");
        }
        String target = normalizeTargetFolderPath(to);
        if (target.isEmpty()) {
            throw BizException.badRequest("目标文件夹路径不能为空");
        }
        if (target.equals(source)) {
            return 0;
        }
        if (target.startsWith(source + "/")) {
            throw BizException.badRequest("不能将文件夹移动到它自己的子目录下");
        }
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null || !kb.getId().equals(kbId)) {
            throw BizException.notFound("knowledge base not found");
        }
        // 对照 RenameKnowledgeFolderPath：行级重写（folder_path = source 或 source+"/%"），
        // 目标 = Normalize(to + suffix)，按目标分组批量 UPDATE（Go 双重循环的净效果）
        List<Knowledge> rows = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .select(Knowledge::getId, Knowledge::getFolderPath)
                .eq(Knowledge::getTenantId, kb.getTenantId())
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .and(w -> w.eq(Knowledge::getFolderPath, source)
                        .or().likeRight(Knowledge::getFolderPath, source + "/")));
        if (rows.isEmpty()) {
            return 0;
        }
        Map<String, List<String>> byTarget = new TreeMap<>();
        for (Knowledge row : rows) {
            String suffix = row.getFolderPath().startsWith(source)
                    ? row.getFolderPath().substring(source.length()) : row.getFolderPath();
            byTarget.computeIfAbsent(normalizeKnowledgeFolderPath(target + suffix), k -> new ArrayList<>())
                    .add(row.getId());
        }
        long affected = 0;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (Map.Entry<String, List<String>> e : byTarget.entrySet()) {
            affected += knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                    .eq("tenant_id", kb.getTenantId())
                    .eq("knowledge_base_id", kbId)
                    .in("id", e.getValue())
                    .set("folder_path", e.getKey())
                    .set("updated_at", now));
        }
        return affected;
    }

    /** 对照 normalizeTargetFolderPath：trim → ValidateInput（非法 → 1010）→ Normalize。 */
    private static String normalizeTargetFolderPath(String folderPath) {
        String trimmed = folderPath == null ? "" : folderPath.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String safe = InputSanitizer.validateInput(trimmed);
        if (safe == null) {
            throw new BizException(AppError.validation("文件夹路径包含非法字符"));
        }
        return normalizeKnowledgeFolderPath(safe);
    }

    /**
     * 对照 loadKnowledgeWriteBatch（knowledge_write.go L101-145）：逐 id 校验存在性、
     * moving 状态、KB 绑定与 <b>requireKBWrite 授权</b>；缺行 → 404 "knowledge not
     * found"（小写，golden 钉住）；行落在授权 KB 之外 → 403 "无权修改该知识库"
     * （golden kg-tags-cross-kb 钉住——Go 的 grant 只覆盖进入 handler 时解析的那一个 KB）。
     *
     * @param grantedKbId 当前请求已授权的那个 KB（kb_id 路径 = 显式 kb_id；无 kb_id 路径 =
     *                    首条 knowledge 的 KB；单行 loadKnowledgeWrite 的调用方传 null）
     */
    public List<Knowledge> loadKnowledgeWriteBatch(List<String> ids, String grantedKbId) {
        List<String> cleaned = new ArrayList<>(ids.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String id : ids) {
            if (id == null || id.trim().isEmpty()) {
                throw BizException.badRequest("resource ID cannot be empty");
            }
            if (seen.add(id)) {
                cleaned.add(id);
            }
        }
        List<Knowledge> rows = getKnowledgeBatch(tenantId(), cleaned);
        Map<String, Knowledge> byId = new java.util.HashMap<>();
        for (Knowledge row : rows) {
            byId.put(row.getId(), row);
        }
        List<Knowledge> result = new ArrayList<>(cleaned.size());
        java.util.Set<String> checkedKbs = new java.util.HashSet<>();
        for (String id : cleaned) {
            Knowledge row = byId.get(id);
            if (row == null) {
                throw BizException.notFound("knowledge not found");
            }
            rejectMovingKnowledge(row);
            if (checkedKbs.add(row.getKnowledgeBaseId())) {
                // knowledgeWriteKB：KB 行与 (id, tenant) 绑定一致，否则 403
                KnowledgeBase kb = findKb(row.getKnowledgeBaseId());
                if (kb == null || !kb.getTenantId().equals(row.getTenantId())) {
                    throw BizException.forbidden("knowledge does not belong to its knowledge base");
                }
                // requireKBWrite：grant 只覆盖授权 KB（org-share 分支未翻译）
                if (grantedKbId == null || !grantedKbId.equals(row.getKnowledgeBaseId())) {
                    throw BizException.forbidden("无权修改该知识库");
                }
            }
            result.add(row);
        }
        return result;
    }

    /** 对照 loadKnowledgeWrite 的单行版（校验同上 + KB 绑定一致性）。 */
    public Knowledge loadKnowledgeWrite(String id) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            throw BizException.notFound("knowledge not found");
        }
        rejectMovingKnowledge(k);
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, k.getKnowledgeBaseId())
                .eq(KnowledgeBase::getTenantId, k.getTenantId())
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null || !kb.getId().equals(k.getKnowledgeBaseId())
                || !kb.getTenantId().equals(k.getTenantId())) {
            throw BizException.forbidden("knowledge does not belong to its knowledge base");
        }
        return k;
    }

    /** 对照 access.RejectMovingKnowledge：transfer metadata 里 operation=move 且
     *  phase=moving → 409（本批路由的固定状态防线）。 */
    private static void rejectMovingKnowledge(Knowledge k) {
        JsonNode metadata = k.getMetadata();
        if (metadata == null || !metadata.has("_knowledge_transfer")) {
            return;
        }
        JsonNode state = metadata.get("_knowledge_transfer");
        if ("move".equals(state.path("operation").asText(""))
                && "moving".equals(state.path("phase").asText(""))) {
            throw BizException.conflict("knowledge has an unfinished move; retry the move first");
        }
    }

    // ── 波 2：文档操作面 ──────────────────────────────────────────────────

    /**
     * 对照 GetKnowledgeSpans（handler L608-690）：attempt 选择（显式 ?attempt=N 优先，
     * 否则 spans 表的 latestAttempt）→ ListByAttempt → buildSpanTree（真实行建树 +
     * 缺失 canonical stage 合成）→ last_error（span 失败行优先）。
     * 2026-09-23 起 span 写入侧已接线（此前 spanRepo==nil 分支的备案差异作废）。
     *
     * @return data 信封内层（gin.H 键按字母序：attempt/current_attempt/current_stage/
     *         knowledge_id/[last_error]/latest_attempt/parse_status/trace）
     */
    public ObjectNode knowledgeSpans(Knowledge knowledge, int requestedAttempt) {
        int latestAttempt = spanRepository.latestAttempt(knowledge.getId());
        int currentAttempt = requestedAttempt > 0 ? requestedAttempt : latestAttempt;
        List<com.ragagent.knowledge.domain.KnowledgeProcessingSpan> rows =
                currentAttempt > 0
                        ? spanRepository.listByAttempt(knowledge.getId(), currentAttempt)
                        : List.of();
        SpanTree tree = buildSpanTree(knowledge.getId(), currentAttempt, rows,
                knowledge.getParseStatus());

        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("attempt", currentAttempt);
        resp.put("current_attempt", currentAttempt);
        resp.put("current_stage", tree.currentStage());
        resp.put("knowledge_id", knowledge.getId());
        JsonNode lastError = knowledgeSpansLastError(currentAttempt, latestAttempt,
                knowledge, tree.lastFailure());
        if (lastError != null) {
            resp.set("last_error", lastError);
        }
        resp.put("latest_attempt", latestAttempt);
        resp.put("parse_status", knowledge.getParseStatus() == null ? "" : knowledge.getParseStatus());
        resp.set("trace", tree.root());
        return resp;
    }

    record SpanTree(ObjectNode root, String currentStage,
                    com.ragagent.knowledge.domain.KnowledgeProcessingSpan lastFailure) {
    }

    /**
     * 对照 buildSpanTree（handler/knowledge.go L748-858）：真实行按 span_id 建索引
     * （保 rows 序）→ root（首个 kind=root）/首个 running stage（current_stage）/
     * 末个 failed 行（lastFailure）→ children 按 rows 序链接（无父/孤儿挂 root）→
     * 缺失 canonical stage 合成占位（AllStages 序）。rows 为空时与旧实现逐字节一致
     * （全合成，status 由 parse_status 推导：completed→done、failed→failed、其余 pending）。
     */
    private static SpanTree buildSpanTree(
            String knowledgeId, int attempt,
            List<com.ragagent.knowledge.domain.KnowledgeProcessingSpan> rows,
            String parseStatus) {
        String syntheticStatus = "pending";
        if (Knowledge.PARSE_COMPLETED.equals(parseStatus)) {
            syntheticStatus = "done";
        } else if (Knowledge.PARSE_FAILED.equals(parseStatus)) {
            syntheticStatus = "failed";
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Map<String, ObjectNode> nodes = new java.util.LinkedHashMap<>();
        com.ragagent.knowledge.domain.KnowledgeProcessingSpan rootRow = null;
        Map<String, com.ragagent.knowledge.domain.KnowledgeProcessingSpan> stageRowByName =
                new java.util.LinkedHashMap<>();
        String currentStage = "";
        com.ragagent.knowledge.domain.KnowledgeProcessingSpan lastFailure = null;
        for (com.ragagent.knowledge.domain.KnowledgeProcessingSpan r : rows) {
            nodes.put(r.getSpanId(), spanNodeFromRow(r));
            if ("root".equals(r.getKind()) && rootRow == null) {
                rootRow = r;
            }
            if ("stage".equals(r.getKind())) {
                stageRowByName.put(r.getName(), r);
            }
            if ("running".equals(r.getStatus()) && "stage".equals(r.getKind())
                    && currentStage.isEmpty()) {
                currentStage = r.getName();
            }
            if ("failed".equals(r.getStatus())) {
                lastFailure = r;
            }
        }

        ObjectNode root;
        if (rootRow == null) {
            root = spanNode(knowledgeId, attempt, "", "knowledge_processing", "root",
                    syntheticStatus, null, now);
        } else {
            root = nodes.get(rootRow.getSpanId());
        }

        // 真实 children 链接（按 rows 序——Go 刻意不遍历 map，保证 fan-out 子 span 稳定序）
        for (com.ragagent.knowledge.domain.KnowledgeProcessingSpan r : rows) {
            ObjectNode n = nodes.get(r.getSpanId());
            if (n == null || n == root) {
                continue;
            }
            ObjectNode parent = r.getParentSpanId().isEmpty()
                    ? null : nodes.get(r.getParentSpanId());
            if (parent == null) {
                parent = root;
            }
            ArrayNode children = parent.has("children")
                    ? (ArrayNode) parent.get("children") : MAPPER.createArrayNode();
            children.add(n);
            parent.set("children", children);
        }

        // 缺失 stage 合成（AllStages 序，保证 5 段布局确定性）
        for (String stage : ALL_STAGES) {
            if (stageRowByName.containsKey(stage)) {
                continue;
            }
            ArrayNode children = root.has("children")
                    ? (ArrayNode) root.get("children") : MAPPER.createArrayNode();
            children.add(spanNode(knowledgeId, attempt, "", stage, "stage",
                    syntheticStatus, null, now));
            root.set("children", children);
        }
        return new SpanTree(root, currentStage, lastFailure);
    }

    /**
     * 真实 span 行的 trace 节点渲染：键序 = Go {@code KnowledgeProcessingSpan} 声明序；
     * omitempty 字段（parent_span_id/input/output/metadata/error_code/error_message/
     * started_at/finished_at/duration_ms）缺席即省略；error_detail 是 {@code json:"-"} 不输出；
     * created_at/updated_at 恒输出。
     */
    private static ObjectNode spanNodeFromRow(
            com.ragagent.knowledge.domain.KnowledgeProcessingSpan r) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("knowledge_id", r.getKnowledgeId());
        n.put("attempt", r.getAttempt());
        n.put("span_id", r.getSpanId());
        if (!r.getParentSpanId().isEmpty()) {
            n.put("parent_span_id", r.getParentSpanId());
        }
        n.put("name", r.getName());
        n.put("kind", r.getKind());
        n.put("status", r.getStatus());
        if (r.getInput() != null) {
            n.set("input", MAPPER.valueToTree(r.getInput()));
        }
        if (r.getOutput() != null) {
            n.set("output", MAPPER.valueToTree(r.getOutput()));
        }
        if (r.getMetadata() != null) {
            n.set("metadata", MAPPER.valueToTree(r.getMetadata()));
        }
        if (!r.getErrorCode().isEmpty()) {
            n.put("error_code", r.getErrorCode());
        }
        if (!r.getErrorMessage().isEmpty()) {
            n.put("error_message", r.getErrorMessage());
        }
        if (r.getStartedAt() != null) {
            n.put("started_at", goTimeString(r.getStartedAt()));
        }
        if (r.getFinishedAt() != null) {
            n.put("finished_at", goTimeString(r.getFinishedAt()));
        }
        if (r.getDurationMs() != 0) {
            n.put("duration_ms", r.getDurationMs());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        n.put("created_at", goTimeString(r.getCreatedAt() == null ? now : r.getCreatedAt()));
        n.put("updated_at", goTimeString(r.getUpdatedAt() == null ? now : r.getUpdatedAt()));
        return n;
    }

    private static ObjectNode spanNode(String knowledgeId, int attempt, String spanId,
                                       String name, String kind, String status,
                                       String parentSpanId, OffsetDateTime now) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("knowledge_id", knowledgeId);
        n.put("attempt", attempt);
        n.put("span_id", spanId);
        if (parentSpanId != null && !parentSpanId.isEmpty()) {
            n.put("parent_span_id", parentSpanId);
        }
        n.put("name", name);
        n.put("kind", kind);
        n.put("status", status);
        n.put("created_at", goTimeString(now));
        n.put("updated_at", goTimeString(now));
        return n;
    }

    /**
     * 对照 knowledgeSpansLastError（handler L697-732）：span 失败行优先（字母序
     * code/error_code/error_message/finished_at/message/name/stage；finished_at 为
     * null 时输出 null）；否则 currentAttempt==latestAttempt 且 parse_status=failed
     * 且 error_message 非空才落知识行回退（SERVER_RESTART 文案 EqualFold 判定照抄）。
     */
    private static JsonNode knowledgeSpansLastError(
            int currentAttempt, int latestAttempt, Knowledge knowledge,
            com.ragagent.knowledge.domain.KnowledgeProcessingSpan spanFailure) {
        if (spanFailure != null) {
            ObjectNode e = MAPPER.createObjectNode();
            e.put("code", spanFailure.getErrorCode());
            e.put("error_code", spanFailure.getErrorCode());
            e.put("error_message", spanFailure.getErrorMessage());
            if (spanFailure.getFinishedAt() == null) {
                e.putNull("finished_at");
            } else {
                e.put("finished_at", goTimeString(spanFailure.getFinishedAt()));
            }
            e.put("message", spanFailure.getErrorMessage());
            e.put("name", spanFailure.getName());
            e.put("stage", spanFailure.getName());
            return e;
        }
        String parseStatus = knowledge.getParseStatus() == null ? "" : knowledge.getParseStatus();
        String message = knowledge.getErrorMessage() == null ? "" : knowledge.getErrorMessage();
        if (currentAttempt != latestAttempt || !Knowledge.PARSE_FAILED.equals(parseStatus)
                || message.isEmpty()) {
            return null;
        }
        String errorCode = "UNKNOWN";
        if ("Task interrupted due to application restart"
                .equalsIgnoreCase(message.trim())) {
            errorCode = "SERVER_RESTART";
        }
        // gin.H → encoding/json 键按字母序输出（code < error_code < error_message <
        // finished_at < message < name < stage），golden 钉住
        ObjectNode e = MAPPER.createObjectNode();
        e.put("code", errorCode);
        e.put("error_code", errorCode);
        e.put("error_message", message);
        e.put("finished_at", knowledge.getUpdatedAt() == null
                ? null : goTimeString(knowledge.getUpdatedAt()));
        e.put("message", message);
        e.put("name", "knowledge_processing");
        e.put("stage", "knowledge_processing");
        return e;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 摘要生成管线（对照 knowledge_process.go L2291-2443 全量 +
    // knowledge_summary_refresh.go —— 2026-09-22 走查补全，此前是阶段占位）
    // ══════════════════════════════════════════════════════════════════════

    /** Go types 的 SummaryStatus 五值（internal/types/knowledge.go L73-84）。 */
    private static final String SUMMARY_NONE = "none";
    private static final String SUMMARY_PENDING = "pending";
    private static final String SUMMARY_PROCESSING = "processing";
    private static final String SUMMARY_COMPLETED = "completed";
    private static final String SUMMARY_FAILED = "failed";

    /**
     * 对照 Go 的三个哨兵错误（errInsufficientSummaryContent / errEmptySummaryOutput /
     * ErrSummaryRefreshStale）：非 AppError → handler 包 {@code NewBadRequestError(err.Error())}
     * → 400 信封 + 原文案（既有 golden 钉住 "summary model is not configured" 同款形态）。
     * 用实例身份（==）判别，避免文案比较。
     */
    private static final BizException ERR_INSUFFICIENT_SUMMARY_CONTENT =
            new BizException(AppError.badRequest("insufficient text content for summary generation"));
    private static final BizException ERR_EMPTY_SUMMARY_OUTPUT =
            new BizException(AppError.badRequest("summary model returned empty output"));
    private static final BizException ERR_SUMMARY_REFRESH_STALE =
            new BizException(AppError.badRequest("summary refresh superseded"));

    /** Go summaryFallbackMaxRunes / imageDominatedTextThreshold / defaultMaxInputChars。 */
    private static final int SUMMARY_FALLBACK_MAX_RUNES = 500;
    private static final int IMAGE_DOMINATED_TEXT_THRESHOLD = 200;
    private static final int DEFAULT_SUMMARY_MAX_INPUT_CHARS = 1024 * 24;
    /** asynq MaxRetry(3)：刷新任务最多 1 次初始 + 3 次重试（进程内虚拟线程替代）。 */
    private static final int SUMMARY_MAX_RETRY = 3;

    /**
     * 对照 RegenerateKnowledgeSummary（knowledge_process.go L2294-2443）：HTTP 同步路径。
     * 非 asynq worker → {@code summaryTaskWillRetry}(ctx) 恒 false（终态失败处理）。
     * 失败时按 Go 语义先落库对应状态再抛错误（handler 侧非 AppError → 400 信封原文）。
     */
    public Knowledge regenerateKnowledgeSummary(String id) {
        return doRegenerateKnowledgeSummary(id, false);
    }

    private Knowledge doRegenerateKnowledgeSummary(String id, boolean willRetry) {
        Knowledge knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (knowledge == null) {
            throw BizException.notFound("record not found");
        }
        KnowledgeBase kb = requireKb(knowledge.getKnowledgeBaseId());
        if (kb.getSummaryModelId() == null || kb.getSummaryModelId().isEmpty()) {
            throw new BizException(AppError.badRequest("summary model is not configured"));
        }
        // Go ListChunksByKnowledgeID 本身 text-only；此处的类型/启用过滤是双保险（照抄）
        List<Chunk> allChunks = chunkRepo.listChunksByKnowledgeID(tenantId(), id);
        List<Chunk> textChunks = new ArrayList<>();
        for (Chunk chunk : allChunks) {
            if ("text".equals(chunk.getChunkType()) && chunk.isIsEnabled()) {
                textChunks.add(chunk);
            }
        }
        if (textChunks.isEmpty()) {
            knowledge.setDescription("");
            knowledge.setSummaryStatus(SUMMARY_FAILED);
            knowledge.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            updateKnowledgeRow(knowledge, knowledge.getMetadata());
            throw ERR_INSUFFICIENT_SUMMARY_CONTENT;
        }
        textChunks.sort(Comparator.comparingInt(Chunk::getChunkIndex));
        String metadataVersion = customMetadataVersion(knowledge);
        knowledge.setSummaryStatus(SUMMARY_PROCESSING);
        updateKnowledgeRow(knowledge, knowledge.getMetadata());

        LlmChatClient chatModel;
        try {
            chatModel = modelRuntimeFactory.getChatModel(kb.getSummaryModelId());
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            // ModelRuntimeFactory.getModelDirect 的取数失败文案；对照 Go errors.As
            // 解出内层 AppError（404 "Model not found"）透传、其余包 "get chat model: " 前缀
            if ("model not found".equals(msg)) {
                throw failGeneration(knowledge, textChunks, metadataVersion, willRetry,
                        new BizException(AppError.notFound("Model not found")));
            }
            throw failGeneration(knowledge, textChunks, metadataVersion, willRetry,
                    new BizException(AppError.badRequest("get chat model: " + msg)));
        }
        String summary;
        try {
            summary = getSummary(chatModel, knowledge, textChunks);
        } catch (RuntimeException e) {
            throw failGeneration(knowledge, textChunks, metadataVersion, willRetry, e);
        }
        boolean stale;
        try {
            stale = summarySourceChanged(knowledge.getTenantId(), id, metadataVersion, textChunks);
        } catch (RuntimeException e) {
            throw new BizException(AppError.badRequest(
                    "verify summary freshness: " + (e.getMessage() == null ? e.toString() : e.getMessage())));
        }
        if (stale) {
            log.info("Discarding stale summary refresh for knowledge {}", id);
            throw ERR_SUMMARY_REFRESH_STALE;
        }
        knowledge.setDescription(summary);
        knowledge.setSummaryStatus(SUMMARY_COMPLETED);
        knowledge.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        updateKnowledgeRow(knowledge, knowledge.getMetadata());
        if (kbNeedsEmbedding(kb)) {
            int maxIndex = 0;
            for (Chunk chunk : allChunks) {
                if (chunk.getChunkIndex() > maxIndex) {
                    maxIndex = chunk.getChunkIndex();
                }
            }
            // allChunks 是 text-only，永远不含已有 summary chunk——必须按类型另查
            // （对照 Go L2404-2414 的修复：否则每次刷新都会并排追加一个新 summary chunk）
            List<Chunk> existingSummaries = chunkRepo.listChunksByKnowledgeIDAndTypes(
                    tenantId(), id, List.of("summary"));
            List<Chunk> summaryChunks = new ArrayList<>();
            for (Chunk chunk : existingSummaries) {
                chunk.setContent("# Summary\n" + summary);
                chunk.setSourceContent(chunk.getContent());
                chunk.setIsEnabled(true);
                chunk.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                chunkMapper.updateById(chunk);
                summaryChunks.add(chunk);
            }
            if (summaryChunks.isEmpty()) {
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                Chunk summaryChunk = new Chunk();
                summaryChunk.setId(UUID.randomUUID().toString());
                summaryChunk.setTenantId(tenantId());
                summaryChunk.setKnowledgeId(knowledge.getId());
                summaryChunk.setKnowledgeBaseId(knowledge.getKnowledgeBaseId());
                summaryChunk.setContent("# Summary\n" + summary);
                summaryChunk.setSourceContent(summaryChunk.getContent());
                summaryChunk.setChunkIndex(maxIndex + 1);
                summaryChunk.setIsEnabled(true);
                summaryChunk.setChunkType("summary");
                summaryChunk.setParentChunkId(textChunks.get(0).getId());
                summaryChunk.setCreatedAt(now);
                summaryChunk.setUpdatedAt(now);
                chunkMapper.insert(summaryChunk);
                summaryChunks.add(summaryChunk);
            }
            chunkVectorIndexer.updateChunkVector(knowledge.getKnowledgeBaseId(), summaryChunks);
        }
        return knowledge;
    }

    /**
     * 对照 Go handleGenerationFailure（L2336-2371）：insufficient → 清 description + failed；
     * willRetry（asynq 还有重试额度）→ pending（保留既有 description）；否则终态——
     * 先做新鲜度校验（源已变 → 丢弃结果），再落首 chunk 兜底 + failed。返回原错误供抛出。
     */
    private RuntimeException failGeneration(Knowledge knowledge, List<Chunk> textChunks,
                                            String metadataVersion, boolean willRetry,
                                            RuntimeException generationErr) {
        if (generationErr == ERR_INSUFFICIENT_SUMMARY_CONTENT) {
            knowledge.setDescription("");
            knowledge.setSummaryStatus(SUMMARY_FAILED);
            knowledge.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            updateKnowledgeRow(knowledge, knowledge.getMetadata());
            return generationErr;
        }
        if (willRetry) {
            applyRetryableSummaryFailureState(knowledge, textChunks, true);
            try {
                updateKnowledgeRow(knowledge, knowledge.getMetadata());
            } catch (RuntimeException e) {
                log.warn("Failed to mark summary refresh pending for retry: {}", e.toString());
            }
            return generationErr;
        }
        boolean stale;
        try {
            stale = summarySourceChanged(knowledge.getTenantId(), knowledge.getId(),
                    metadataVersion, textChunks);
        } catch (RuntimeException staleErr) {
            knowledge.setSummaryStatus(SUMMARY_FAILED);
            try {
                updateKnowledgeRow(knowledge, knowledge.getMetadata());
            } catch (RuntimeException ignored) {
                // 对照 Go 的 `_ = s.repo.UpdateKnowledge(...)`
            }
            return new BizException(AppError.badRequest("verify summary fallback freshness: "
                    + (staleErr.getMessage() == null ? staleErr.toString() : staleErr.getMessage())));
        }
        if (stale) {
            return ERR_SUMMARY_REFRESH_STALE;
        }
        applyRetryableSummaryFailureState(knowledge, textChunks, false);
        updateKnowledgeRow(knowledge, knowledge.getMetadata());
        return generationErr;
    }

    /**
     * 对照 applyRetryableSummaryFailureState（L790-805）：willRetry → pending（description 不动）；
     * 终态 → description = 首 chunk 内容（截 500 码点）+ failed + updated_at=now。
     */
    private static void applyRetryableSummaryFailureState(Knowledge knowledge,
                                                          List<Chunk> textChunks, boolean willRetry) {
        knowledge.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        if (willRetry) {
            knowledge.setSummaryStatus(SUMMARY_PENDING);
            return;
        }
        String fallback = firstTextChunkSummaryFallback(textChunks);
        knowledge.setDescription(fallback);
        knowledge.setSummaryStatus(SUMMARY_FAILED);
    }

    /** 对照 firstTextChunkSummaryFallback（L778-788）：首 chunk trim 后按码点截 500。 */
    private static String firstTextChunkSummaryFallback(List<Chunk> textChunks) {
        if (textChunks.isEmpty() || textChunks.get(0) == null) {
            return "";
        }
        String fallback = ChunkRepository.goTrimSpace(
                textChunks.get(0).getContent() == null ? "" : textChunks.get(0).getContent());
        int count = fallback.codePointCount(0, fallback.length());
        if (count > SUMMARY_FALLBACK_MAX_RUNES) {
            fallback = fallback.substring(0, fallback.offsetByCodePoints(0, SUMMARY_FALLBACK_MAX_RUNES));
        }
        return fallback;
    }

    /**
     * 对照 summarySourceChanged（knowledge_summary_refresh.go L29-56）：metadata 文本或
     * 任一源 chunk 的 content_revision / is_enabled 变化 → stale。仓储错误单独抛出
     * （调用方不得把瞬时读错误当成 stale 任务丢弃）。
     */
    private boolean summarySourceChanged(long tenantId, String knowledgeId,
                                         String metadataVersion, List<Chunk> sourceChunks) {
        Knowledge latest = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (latest == null) {
            throw BizException.notFound("record not found");
        }
        if (!customMetadataVersion(latest).equals(metadataVersion)) {
            return true;
        }
        for (Chunk sourceChunk : sourceChunks) {
            Chunk latestChunk = chunkRepo.getChunkById(tenantId, sourceChunk.getId());
            if (latestChunk.getContentRevision() != sourceChunk.getContentRevision()
                    || latestChunk.isIsEnabled() != sourceChunk.isIsEnabled()) {
                return true;
            }
        }
        return false;
    }

    /** 对照 Go {@code string(knowledge.CustomMetadata)}（用于新鲜度比较的版本串）。 */
    private static String customMetadataVersion(Knowledge knowledge) {
        return knowledge.getCustomMetadata() == null ? "" : knowledge.getCustomMetadata().toString();
    }

    /**
     * 对照 CustomMetadataText（types/knowledge.go L197-224）：用户自撰元数据的稳定文本
     * （键排序、跳过 null 值、"{key}: {value}" 逐行）；内部摄取元数据刻意排除。
     */
    private static String customMetadataText(Knowledge knowledge) {
        if (knowledge == null || knowledge.getCustomMetadata() == null
                || !knowledge.getCustomMetadata().isObject()) {
            return "";
        }
        JsonNode node = knowledge.getCustomMetadata();
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        java.util.Collections.sort(keys);
        List<String> lines = new ArrayList<>();
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.isValueNode() ? value.asText() : value.toString();
            text = ChunkRepository.goTrimSpace(text);
            if (!ChunkRepository.goTrimSpace(key).isEmpty() && !text.isEmpty()) {
                lines.add(ChunkRepository.goTrimSpace(key) + ": " + text);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * 对照 getSummary（L863-997）：重建文档正文（编辑过按 chunk_index 拼接、否则
     * StartAt 重叠合并）→ 图片富化（正文极短走 caption+OCR、否则仅 caption）→
     * 长度采样 → 充分性闸门 → custom metadata 前缀 → LLM（temperature 0.3 /
     * thinking=false / max_tokens=2048 缺省）→ 空白输出视为错误。
     */
    private String getSummary(LlmChatClient summaryModel, Knowledge knowledge, List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            throw new BizException(AppError.badRequest("no chunks provided for summary generation"));
        }
        int maxInputChars = conversationProps.getSummaryMaxInputChars();
        if (maxInputChars <= 0) {
            maxInputChars = DEFAULT_SUMMARY_MAX_INPUT_CHARS;
        }
        List<Chunk> sortedChunks = sortChunksForSummary(chunks);
        boolean hasEditedChunk = false;
        for (Chunk chunk : sortedChunks) {
            if (chunk.getContentRevision() > 0) {
                hasEditedChunk = true;
                break;
            }
        }
        String chunkContents;
        if (hasEditedChunk) {
            // 解析偏移描述不可变源文；被替换过的 chunk 长度已变，按当前内容拼接
            List<String> parts = new ArrayList<>(sortedChunks.size());
            for (Chunk chunk : sortedChunks) {
                if (chunk.isIsEnabled() && !ChunkRepository.goTrimSpace(
                        chunk.getContent() == null ? "" : chunk.getContent()).isEmpty()) {
                    parts.add(chunk.getContent());
                }
            }
            chunkContents = String.join("\n\n", parts);
        } else {
            chunkContents = SearchChunkMerge.mergeTextChunks(sortedChunks, "");
        }
        List<String> chunkIds = new ArrayList<>(sortedChunks.size());
        for (Chunk chunk : sortedChunks) {
            chunkIds.add(chunk.getId());
        }
        Map<String, String> imageInfoMap = ImageInfoEnricher.collectImageInfoByChunkIds(
                chunkRepo::listChunksByParentIDs, knowledge.getTenantId(), chunkIds);
        String mergedImageInfo = ImageInfoEnricher.mergeImageInfoJson(imageInfoMap);
        if (mergedImageInfo != null && !mergedImageInfo.isEmpty()) {
            // 图片优先文档（正文极短）：caption 信号不足，OCR 才是真内容；文本正文够长
            // 的文档走 caption-only，避免页眉/水印 OCR 噪声稀释主题
            if (WikiImageMarkup.realTextRuneCount(chunkContents) < IMAGE_DOMINATED_TEXT_THRESHOLD) {
                chunkContents = ImageInfoEnricher.enrichContentCaptionAndOcr(chunkContents, mergedImageInfo);
            } else {
                chunkContents = ImageInfoEnricher.enrichContentCaptionOnly(chunkContents, mergedImageInfo);
            }
        }
        chunkContents = sampleLongContent(chunkContents, maxInputChars);

        // LLM 调用前的充分性闸门：扫描件剥掉图片标记后没有可用文本 → 直接失败，
        // 不把文件名喂给模型（否则会按 "MX5280.pdf" 之类幻觉出扫描仪说明书）
        if (WikiImageMarkup.realTextRuneCount(chunkContents) < WikiImageMarkup.getMinTextContentRunes()) {
            log.warn("summary content check: knowledge {} has insufficient text after stripping image markup"
                    + " (real_text_runes={}, min={}); skipping LLM call",
                    knowledge.getId(), WikiImageMarkup.realTextRuneCount(chunkContents),
                    WikiImageMarkup.getMinTextContentRunes());
            throw ERR_INSUFFICIENT_SUMMARY_CONTENT;
        }
        String contentWithMetadata = chunkContents;
        String custom = customMetadataText(knowledge);
        if (!custom.isEmpty()) {
            contentWithMetadata = "Document metadata:\n" + custom + "\n\nDocument content:\n" + chunkContents;
        }
        contentWithMetadata = sampleLongContent(contentWithMetadata, maxInputChars);

        int maxTokens = conversationProps.getSummaryMaxCompletionTokens();
        if (maxTokens <= 0) {
            maxTokens = 2048;
        }
        String summaryPrompt = AgentPromptPlaceholders.renderPromptPlaceholders(
                conversationProps.getGenerateSummaryPrompt(),
                Map.of("language", WikiLanguageSupport.languageNameFromContext()));
        ChatOptions options = new ChatOptions();
        options.setTemperature(0.3); // Go 硬编码 0.3（不用 config 的 summaryTemperature，照抄）
        options.setMaxTokens(maxTokens);
        options.setThinking(Boolean.FALSE);
        ChatResponse response;
        try {
            response = summaryModel.chat(
                    List.of(ChatMessage.system(summaryPrompt), ChatMessage.user(contentWithMetadata)),
                    options);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.badRequest(
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        return validateSummaryOutput(response);
    }

    /** 对照 validateSummaryOutput（L761-773）：nil / 空白输出 → errEmptySummaryOutput。 */
    private static String validateSummaryOutput(ChatResponse response) {
        if (response == null) {
            throw ERR_EMPTY_SUMMARY_OUTPUT;
        }
        String content = ChunkRepository.goTrimSpace(
                response.getContent() == null ? "" : response.getContent());
        if (content.isEmpty()) {
            throw ERR_EMPTY_SUMMARY_OUTPUT;
        }
        return content;
    }

    /**
     * 对照 sortChunksForSummary（L841-861）：有任一 chunk 被编辑过（content_revision>0）
     * 按 chunk_index（同 index 用 id 决胜）；否则解析器 StartAt 偏移权威。
     */
    private static List<Chunk> sortChunksForSummary(List<Chunk> chunks) {
        List<Chunk> sorted = new ArrayList<>(chunks);
        boolean edited = false;
        for (Chunk chunk : sorted) {
            if (chunk.getContentRevision() > 0) {
                edited = true;
                break;
            }
        }
        final boolean editedFinal = edited;
        sorted.sort((a, b) -> {
            if (editedFinal) {
                if (a.getChunkIndex() != b.getChunkIndex()) {
                    return Integer.compare(a.getChunkIndex(), b.getChunkIndex());
                }
                return a.getId().compareTo(b.getId());
            }
            return Integer.compare(a.getStartAt(), b.getStartAt());
        });
        return sorted;
    }

    /**
     * 对照 sampleLongContent（L1003-1042）：超限时头 60% + 中段 20% + 尾 20%
     * （以 "[...content omitted...]" 标记衔接）；预算不足 100 直接截断。按码点切分。
     */
    private static String sampleLongContent(String content, int maxChars) {
        int count = content.codePointCount(0, content.length());
        if (count <= maxChars) {
            return content;
        }
        String omitMarker = "\n\n[...content omitted...]\n\n";
        int omitRunes = omitMarker.codePointCount(0, omitMarker.length());
        int usable = maxChars - 2 * omitRunes;
        if (usable < 100) {
            return content.substring(0, content.offsetByCodePoints(0, maxChars));
        }
        int headLen = usable * 60 / 100;
        int tailLen = usable * 20 / 100;
        int midLen = usable - headLen - tailLen;

        String head = content.substring(0, content.offsetByCodePoints(0, headLen));
        String tail = content.substring(content.offsetByCodePoints(0, count - tailLen));

        int midStart = count / 2 - midLen / 2;
        if (midStart < headLen) {
            midStart = headLen;
        }
        int midEnd = midStart + midLen;
        if (midEnd > count - tailLen) {
            midEnd = count - tailLen;
            midStart = midEnd - midLen;
            if (midStart < headLen) {
                midStart = headLen;
            }
        }
        String middle = content.substring(
                content.offsetByCodePoints(0, midStart), content.offsetByCodePoints(0, midEnd));
        return head + omitMarker + middle + omitMarker + tail;
    }

    /**
     * post-process 的摘要 fan-out（对照 knowledge_post_process.go L208
     * {@code willSpawnSummary = len(textChunks) > 0} + L346-390 的 summary_status 落库；
     * 任务体 = ProcessSummaryGeneration，knowledge_process.go L1121-1330——与刷新
     * 共用同一生成管线，差异仅在「错误不抛给调用方」）。
     *
     * <p>调用方 {@link KnowledgeProcessWorker} 在索引完成后调用（知识行此时已落
     * {@code summary_status=none}，对照 finalizeIndexedKnowledgeState L215-242）。
     * 本方法完成三件事：cancelled/deleting → 跳过（L1148-1156）；无 summary model
     * → 落 failed 不抛（L1133-1138）；否则 pending 落库 + 异步生成（重试/吞错语义
     * 同 {@link #spawnSummaryRefreshWorker}）。租户取知识行，不依赖调用线程的
     * TenantContext（worker 线程无上下文）。</p>
     */
    public void requestPostProcessSummaryGeneration(String knowledgeId) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            return;
        }
        String parseStatus = k.getParseStatus() == null ? "" : k.getParseStatus();
        if (Knowledge.PARSE_CANCELLED.equals(parseStatus)
                || Knowledge.PARSE_DELETING.equals(parseStatus)) {
            return;
        }
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, k.getKnowledgeBaseId())
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            return;
        }
        if (kb.getSummaryModelId() == null || kb.getSummaryModelId().isEmpty()) {
            // 对照 L1133-1138：无 summary model → summary_status=failed（任务不抛错）
            markSummaryFailed(knowledgeId);
            return;
        }
        // 对照 post_process L346 + ProcessSummaryGeneration L1159：pending 先落库再异步
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .set("summary_status", SUMMARY_PENDING));
        spawnSummaryRefreshWorker(knowledgeId, k.getTenantId());
    }

    /**
     * 对照 RequestKnowledgeSummaryRefresh → enqueueSummaryRefresh
     * （knowledge_summary_refresh.go L83-151）：summary 已启用（非空非 none）才入队；
     * 无 summary model → 先落 failed（markFailed）再抛原文错误；成功 → 落 pending +
     * 进程内虚拟线程执行刷新（asynq Refresh:true 的替代，重试语义对齐 MaxRetry(3)）。
     */
    public void requestKnowledgeSummaryRefresh(String id) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            throw BizException.notFound("record not found");
        }
        String status = k.getSummaryStatus() == null ? "" : k.getSummaryStatus();
        if (status.isEmpty() || SUMMARY_NONE.equals(status)) {
            return; // 对照 enqueueSummaryRefresh L91：未启用摘要 → 静默成功
        }
        KnowledgeBase kb = requireKb(k.getKnowledgeBaseId());
        if (kb.getSummaryModelId() == null || kb.getSummaryModelId().isEmpty()) {
            markSummaryFailed(k.getId());
            throw new BizException(AppError.badRequest("summary model is not configured"));
        }
        // pending 必须先落库（对照 Go L132 的注释：入队可能同步执行）
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", k.getId())
                .set("summary_status", SUMMARY_PENDING));
        spawnSummaryRefreshWorker(k.getId(), k.getTenantId());
    }

    private void markSummaryFailed(String knowledgeId) {
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .set("summary_status", SUMMARY_FAILED));
    }

    /**
     * 对照 ProcessSummaryGeneration 的 Refresh 分支（L1067-1085）+ asynq MaxRetry(3)：
     * 虚拟线程内显式拷 TenantContext（§5：不跨虚拟线程共享 ThreadLocal）；
     * stale / insufficient 静默丢弃（状态已由 doRegenerate 落库），其余错误按
     * 「还有重试额度 → pending」重试，耗尽后由 willRetry=false 分支落终态。
     */
    private void spawnSummaryRefreshWorker(String knowledgeId, long tenantId) {
        final String role = TenantContext.currentRole();
        final String userId = TenantContext.currentUserId();
        Thread.ofVirtual().start(() -> {
            TenantContext.set(tenantId, null, role, false, userId, false);
            try {
                for (int attempt = 0; ; attempt++) {
                    boolean willRetry = attempt < SUMMARY_MAX_RETRY;
                    try {
                        doRegenerateKnowledgeSummary(knowledgeId, willRetry);
                        return;
                    } catch (RuntimeException e) {
                        if (e == ERR_SUMMARY_REFRESH_STALE) {
                            log.info("Discarding stale summary refresh for knowledge {}", knowledgeId);
                            return;
                        }
                        if (e == ERR_INSUFFICIENT_SUMMARY_CONTENT) {
                            return;
                        }
                        log.warn("Summary refresh failed for knowledge {} (attempt {}/{}): {}",
                                knowledgeId, attempt + 1, SUMMARY_MAX_RETRY + 1, e.getMessage());
                        if (!willRetry) {
                            return;
                        }
                    }
                }
            } finally {
                TenantContext.clear();
            }
        });
    }

    /**
     * 对照 KB.NeedsEmbeddingModel 经<b>服务层</b>读法（Go RegenerateKnowledgeSummary
     * L2302 的 kb 来自 {@code kbService.GetKnowledgeBaseByID} → {@code EnsureDefaults()}：
     * IsZero（4 字段全 false）→ Default），即 vector||keyword。
     *
     * <p><b>读层差异（2026-09-22 二次踩坑修正）</b>：Go 的判定按调用点分两层语义——
     * 服务层（kbService，含 EnsureDefaults 钩子）与 repo 层（kbRepository，仅 Scan：
     * NULL→Default、全 false 保持）。本方法对应服务层；{@link ChunkVectorIndexer}
     * 内部按调用点分别用服务层/repo 层判定。证据：全 false 策略的 KB 在 Go 的
     * updateImageInfo/regenerate 路径仍被判定为需要 embedding（golden 1007 实录）。</p>
     */
    private static boolean kbNeedsEmbedding(KnowledgeBase kb) {
        KbIndexingStrategy strategy = kb.getIndexingStrategy();
        if (strategy == null || strategy.isZero()) {
            strategy = KbIndexingStrategy.defaultStrategy();
        }
        return strategy.isVectorEnabled() || strategy.isKeywordEnabled();
    }

    /**
     * 对照 UpdateManualKnowledge（knowledge_create.go L991-1111）。payload 为
     * handler 解析出的字段（null = 请求体 null 字面量）。@return 响应体 knowledge
     * （内存对象，metadata 为声明序键，updated_at RFC3339 秒级 UTC）。
     */
    public Knowledge updateManualKnowledge(String id, String title, String content,
                                           String status, String channel) {
        if (content == null && title == null && status == null && channel == null) {
            throw BizException.badRequest("请求内容不能为空");
        }
        String cleanContent = InputSanitizer.cleanMarkdown(content == null ? "" : content);
        if (cleanContent.trim().isEmpty()) {
            throw new BizException(AppError.validation("内容不能为空"));
        }
        if (cleanContent.length() > 200000) {
            throw new BizException(AppError.validation("内容长度超出限制（最多200000个字符）"));
        }
        String safeTitle = InputSanitizer.validateInput(title == null ? "" : title);
        if (safeTitle == null) {
            throw new BizException(AppError.validation("标题包含非法字符或超出长度限制"));
        }
        String normalizedStatus = status == null ? "" : status.trim().toLowerCase();
        if (normalizedStatus.isEmpty()) {
            normalizedStatus = "draft";
        }
        if (!"draft".equals(normalizedStatus) && !"publish".equals(normalizedStatus)) {
            throw new BizException(AppError.validation("状态仅支持 draft 或 publish"));
        }

        Knowledge existing = loadKnowledgeWrite(id);
        if (!"manual".equals(existing.getType())) {
            throw BizException.badRequest("仅支持手工知识的在线编辑");
        }
        KnowledgeBase kb = requireKb(existing.getKnowledgeBaseId());

        int version = 1;
        JsonNode oldMeta = existing.getMetadata();
        if (oldMeta != null && oldMeta.hasNonNull("version")) {
            version = oldMeta.path("version").asInt(0) + 1;
            if (version <= 1) {
                version = 1;
            }
        }
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("content", cleanContent);
        meta.put("format", "markdown");
        meta.put("status", normalizedStatus);
        meta.put("version", version);
        // Go time.Format(RFC3339)：秒恒输出（秒为 0 时 toString 会塌缩成分钟精度，掩码后仍不同）
        meta.put("updated_at", OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));

        if (!safeTitle.isEmpty()) {
            existing.setTitle(safeTitle);
        } else if (existing.getTitle() == null || existing.getTitle().isEmpty()) {
            existing.setTitle("手工知识-" + java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMdd-HHmmss").format(OffsetDateTime.now()));
        }
        existing.setFileName(ensureManualFileName(existing.getTitle()));
        existing.setFileType("manual");
        existing.setType("manual");
        existing.setSource("manual");
        existing.setEnableStatus("disabled");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        existing.setUpdatedAt(now);
        existing.setEmbeddingModelId(kb.getEmbeddingModelId());

        if ("draft".equals(normalizedStatus)) {
            existing.setParseStatus("draft");
            existing.setDescription("");
            existing.setProcessedAt(null);
            updateKnowledgeRow(existing, meta);
            existing.setMetadata(meta);
            return existing;
        }

        // Publish：pending + 异步清理重建（对照 L1076-1090）
        existing.setParseStatus("pending");
        existing.setDescription("");
        existing.setProcessedAt(null);
        updateKnowledgeRow(existing, meta);
        existing.setMetadata(meta);
        worker.enqueue(existing.getId());
        return existing;
    }

    /**
     * 对照 repo.UpdateKnowledge（Save 全列写 + Omit DeletedAt/PendingSubtasksCount）：
     * description=""/processed_at=NULL 这类零值也必须落库，不能走 MP 默认的跳空列。
     */
    private void updateKnowledgeRow(Knowledge k, JsonNode metadata) {
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", k.getId())
                .set("type", k.getType())
                .set("title", k.getTitle())
                .set("description", k.getDescription())
                .set("source", k.getSource())
                .set("parse_status", k.getParseStatus())
                .set("summary_status", k.getSummaryStatus())
                .set("enable_status", k.getEnableStatus())
                .set("embedding_model_id", k.getEmbeddingModelId())
                .set("file_name", k.getFileName())
                .set("file_type", k.getFileType())
                .set("file_size", k.getFileSize() == null ? 0L : k.getFileSize())
                .set("file_hash", k.getFileHash())
                .set("file_path", k.getFilePath())
                .set("metadata", metadata,
                        "typeHandler=com.ragagent.common.web.PgJsonTypeHandler")
                .set("updated_at", k.getUpdatedAt())
                .set("processed_at", k.getProcessedAt())
                .set("error_message", k.getErrorMessage()));
    }

    /** 对照 sanitizeManualDownloadFilename：换行/制表删除、斜杠转连字符、引号转单引号、
     *  空白回落 untitled、补 .md 后缀。 */
    public static String sanitizeManualDownloadFilename(String title) {
        String safeName = title == null ? "" : title
                .replace("\n", "").replace("\r", "").replace("\t", "")
                .replace("/", "-").replace("\\", "-").replace("\"", "'");
        if (safeName.trim().isEmpty()) {
            safeName = "untitled";
        }
        if (!safeName.toLowerCase().endsWith(".md")) {
            safeName = safeName + ".md";
        }
        return safeName;
    }

    /**
     * 对照 ReparseKnowledge（knowledge_process.go L2447-2728 的确定性前缀）：
     * loadKnowledgeWrite → （override 校验仅当显式传入）→ reset → 落库 → 入队。
     * @return 响应体 knowledge（内存对象，重置后的状态；时间戳掩码外逐字段一致）
     */
    public Knowledge reparseKnowledge(String id) {
        Knowledge existing = loadKnowledgeWrite(id);
        KnowledgeBase kb = requireKb(existing.getKnowledgeBaseId());
        resetKnowledgeForReparse(existing, kb);
        updateKnowledgeRow(existing, existing.getMetadata());
        worker.enqueue(existing.getId());
        return existing;
    }

    /** 对照 resetKnowledgeForReparse（L2733-2743）。 */
    private static void resetKnowledgeForReparse(Knowledge k, KnowledgeBase kb) {
        k.setParseStatus(Knowledge.PARSE_PENDING);
        k.setEnableStatus("disabled");
        k.setDescription("");
        k.setProcessedAt(null);
        k.setErrorMessage("");
        k.setEmbeddingModelId(kb.getEmbeddingModelId());
        k.setPendingSubtasksCount(0);
    }

    /**
     * 对照 CancelKnowledgeParse（knowledge_process.go L2763-2845）：cancelled 幂等、
     * completed/failed → 400 "解析已结束，无法取消"、deleting → 400 "知识正在删除中，
     * 无法取消解析"、其余状态（含 unknown）放行改 cancelled。
     */
    public Knowledge cancelKnowledgeParse(String id) {
        Knowledge existing = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (existing == null) {
            throw BizException.notFound("knowledge not found");
        }
        switch (existing.getParseStatus() == null ? "" : existing.getParseStatus()) {
            case Knowledge.PARSE_CANCELLED -> {
                return existing; // 幂等
            }
            case Knowledge.PARSE_COMPLETED, Knowledge.PARSE_FAILED ->
                throw BizException.badRequest("解析已结束，无法取消");
            case Knowledge.PARSE_DELETING ->
                throw BizException.badRequest("知识正在删除中，无法取消解析");
            default -> {
                // pending/processing/finalizing/unknown → 可取消（unknown Go 仅记日志放行）
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", existing.getId())
                .set("parse_status", Knowledge.PARSE_CANCELLED)
                .set("error_message", "用户已取消解析")
                .set("pending_subtasks_count", 0)
                .set("updated_at", now));
        existing.setParseStatus(Knowledge.PARSE_CANCELLED);
        existing.setErrorMessage("用户已取消解析");
        existing.setPendingSubtasksCount(0);
        existing.setUpdatedAt(now);
        // 对照 Go CancelKnowledgeParse：LatestAttempt → AbortAttempt（平扫非终态子 span +
        // 收口 root 为 cancelled；best-effort，nil/missing attempt no-op）
        int spanAttempt = spanTracker.latestAttempt(existing.getId());
        if (spanAttempt > 0) {
            spanTracker.abortAttempt(existing.getId(), spanAttempt,
                    "USER_CANCELLED", "用户已取消解析", "用户已取消解析");
        }
        return existing;
    }

    /**
     * 对照 GetKnowledgeFile（service/knowledge.go L681-712）：manual 流 metadata.content；
     * document 走本地文件。路径解析对照 local provider：resource://（阶段 3 布局）与
     * local://{rel}（Go provider 原生）都支持；路径越界 → Go 的
     * "invalid file path: path traversal denied: ..." 原文（golden 钉住）。
     *
     * @return (bytes, filename, manual)；manual = 内存 reader（Go 侧非 Seeker →
     *         Accept-Ranges: none），document = 磁盘文件（Seeker → bytes）
     */
    public record KnowledgeFile(byte[] content, String filename, boolean manual) {
    }

    public KnowledgeFile getKnowledgeFile(String id) {
        Knowledge knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (knowledge == null) {
            throw BizException.notFound("record not found");
        }
        if ("manual".equals(knowledge.getType())) {
            String content = knowledge.getMetadata() != null
                    && knowledge.getMetadata().hasNonNull("content")
                    ? knowledge.getMetadata().get("content").asText() : "";
            return new KnowledgeFile(
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    sanitizeManualDownloadFilename(knowledge.getTitle()), true);
        }
        String filePath = knowledge.getFilePath() == null ? "" : knowledge.getFilePath();
        return new KnowledgeFile(fileStorage.readChecked(tenantId(), filePath),
                knowledge.getFileName(), false);
    }

    /**
     * 对照 UpdateImageInfo（knowledge_process.go L2942-3123 全链）：
     * 解析 image_info（非 JSON → Go json 原文的 500）、恰好 1 张图才动、
     * chunk 归属校验（403）、子块 caption/OCR 同步、缺块补建、
     * {@code updateChunkVector(updateChunks + addChunks)}（模型 ID 空 → 1007
     * "model ID cannot be empty"，golden 钉住）、
     * knowledge.file_hash = md5(knowledgeID+fileHash+imageInfo)。
     */
    @Transactional
    public void updateImageInfo(String knowledgeId, String chunkId, String rawImageInfo) {
        Knowledge knowledge = loadKnowledgeWrite(knowledgeId);
        String imageInfo = CleanInvalidUtf8.clean(rawImageInfo == null ? "" : rawImageInfo);
        final JsonNode images;
        try {
            images = MAPPER.readTree(imageInfo);
        } catch (Exception e) {
            // 对照 json.Unmarshal 失败 → 非 AppError → 500 message=原文
            throw new BizException(AppError.internal(com.ragagent.common.web.GoJsonBindError
                    .message(imageInfo, e.getMessage())));
        }
        if (!images.isArray() || images.size() != 1) {
            log.warn("Expected exactly one image info, got {}",
                    images.isArray() ? images.size() : -1);
            return; // Go 返回 nil → 200
        }
        JsonNode image = images.get(0);

        Chunk chunk = chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getId, chunkId)
                .eq(Chunk::getTenantId, tenantId())
                .isNull(Chunk::getDeletedAt)
                .last("LIMIT 1"));
        if (chunk == null) {
            // 对照 chunkRepo.GetChunkByID 的 "chunk not found"（handler 包 500）
            throw new BizException(AppError.internal("chunk not found"));
        }
        if (!chunk.getId().equals(chunkId) || !chunk.getKnowledgeId().equals(knowledge.getId())
                || !chunk.getTenantId().equals(knowledge.getTenantId())
                || !chunk.getKnowledgeBaseId().equals(knowledge.getKnowledgeBaseId())) {
            throw BizException.forbidden("chunk does not belong to its knowledge document");
        }
        chunk.setImageInfo(imageInfo);
        long tenantId = tenantId();
        List<Chunk> chunkChildren = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getParentChunkId, chunkId)
                .eq(Chunk::getTenantId, tenantId)
                .isNull(Chunk::getDeletedAt));

        List<Chunk> updateChunks = new ArrayList<>();
        updateChunks.add(chunk);
        List<Chunk> addChunks = new ArrayList<>();
        boolean hasOcr = false;
        boolean hasCaption = false;
        String originalUrl = image.path("original_url").asText("");
        String caption = image.path("caption").asText("");
        String ocrText = image.path("ocr_text").asText("");
        for (Chunk child : chunkChildren) {
            JsonNode childImages;
            try {
                childImages = MAPPER.readTree(child.getImageInfo() == null ? "" : child.getImageInfo());
            } catch (Exception e) {
                continue; // Go WARN + continue
            }
            if (!childImages.isArray() || childImages.isEmpty()) {
                continue;
            }
            if (!originalUrl.equals(childImages.get(0).path("original_url").asText(""))) {
                continue;
            }
            switch (child.getChunkType() == null ? "" : child.getChunkType()) {
                case "image_caption" -> {
                    hasCaption = true;
                    if (!caption.equals(childImages.get(0).path("caption").asText(""))) {
                        child.setContent(caption);
                        child.setImageInfo(imageInfo);
                        updateChunks.add(child);
                    }
                }
                case "image_ocr" -> {
                    hasOcr = true;
                    if (!ocrText.equals(childImages.get(0).path("ocr_text").asText(""))) {
                        child.setContent(ocrText);
                        child.setImageInfo(imageInfo);
                        updateChunks.add(child);
                    }
                }
                default -> {
                }
            }
        }
        if (!hasCaption && !caption.isEmpty()) {
            addChunks.add(newImageChunk(knowledge, chunk, "image_caption", caption, imageInfo));
        }
        if (!hasOcr && !ocrText.isEmpty()) {
            addChunks.add(newImageChunk(knowledge, chunk, "image_ocr", ocrText, imageInfo));
        }
        for (Chunk c : addChunks) {
            chunkMapper.insert(c);
        }
        for (Chunk c : updateChunks) {
            chunkMapper.updateById(c);
        }
        // 对照 updateChunkVector(ctx, chunk.KnowledgeBaseID, append(updateChunk, addChunk...))：
        // 内部过 NeedsEmbedding（策略判定在 ChunkVectorIndexer）→ GetEmbeddingModel；
        // 模型 ID 空 → 1007 "model ID cannot be empty"（golden kg-image-update/again 钉住）
        List<Chunk> vectorChunks = new ArrayList<>(updateChunks.size() + addChunks.size());
        vectorChunks.addAll(updateChunks);
        vectorChunks.addAll(addChunks);
        chunkVectorIndexer.updateChunkVector(chunk.getKnowledgeBaseId(), vectorChunks);
        // 对照：knowledge.file_hash = calculateStr(knowledgeID, fileHash, imageInfo)
        Knowledge fresh = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (fresh != null) {
            String fileHash = LocalStorageService.md5Hex((knowledgeId + (fresh.getFileHash() == null
                    ? "" : fresh.getFileHash()) + imageInfo)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                    .eq("id", fresh.getId())
                    .set("file_hash", fileHash)
                    .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        }
    }

    private static Chunk newImageChunk(Knowledge k, Chunk parent, String type,
                                       String content, String imageInfo) {
        Chunk c = new Chunk();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        c.setId(UUID.randomUUID().toString());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c.setTenantId(k.getTenantId());
        c.setKnowledgeId(parent.getKnowledgeId());
        c.setKnowledgeBaseId(parent.getKnowledgeBaseId());
        c.setContent(content);
        c.setChunkType(type);
        c.setParentChunkId(parent.getId());
        c.setImageInfo(imageInfo);
        c.setIsEnabled(true);
        return c;
    }

    // ── 波 2：tags 批量 ──────────────────────────────────────────────────

    /**
     * 对照 UpdateKnowledgeTagBatch（service/knowledge.go L955-1041）。
     * authorizedKBID 为空 = 未显式给 kb_id（由首条 knowledge 推导的授权范围）。
     */
    @Transactional
    public void updateKnowledgeTagBatch(String authorizedKBID, Map<String, List<String>> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        List<String> knowledgeIDs = new ArrayList<>(updates.keySet());
        knowledgeIDs.sort(String::compareTo);
        // 授权 KB = 显式 kb_id；无 kb_id 时 = 首条（排序后最靠前的）knowledge 所属 KB
        //（Go 的 grant 来自 handler 对首条 knowledge 的 resolve，同构）
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
        // 收集 + 校验标签（对照 L987-1031）
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

    /** 对照 repo.SetKnowledgeTags：删旧 + 插新（空/重复 id 跳过）。 */
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

    // ── 波 2：批量删除 / 批量重解析 / 清空（asynq → 同步尽力而为，响应契约一致） ──

    /**
     * 对照 BatchDeleteKnowledge 的 handler 校验链之后的入队（Go 异步清理）。
     * Java 同步软删（chunk + knowledge + 本地文件），HTTP 契约（task_id/文案）一致。
     * 注意调用方已做过 RejectMoving/kb 归属校验。
     */
    @Transactional
    public String batchDeleteKnowledge(String kbId, List<String> ids) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (String id : ids) {
            knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                    .eq("id", id)
                    .isNull("deleted_at")
                    .set("deleted_at", now));
            chunkMapper.update(null, new UpdateWrapper<Chunk>()
                    .eq("knowledge_id", id)
                    .set("deleted_at", now));
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 对照 ProcessKnowledgeListReparse 的提交面：逐条 reset 到 pending + 入队。
     * 调用方已完成 requireKnowledgeInKB / RejectMoving 校验。
     */
    public String batchReparseKnowledge(String kbId, List<String> ids) {
        KnowledgeBase kb = requireKb(kbId);
        for (String id : ids) {
            Knowledge k = getKnowledge(id);
            resetKnowledgeForReparse(k, kb);
            updateKnowledgeRow(k, k.getMetadata());
            worker.enqueue(k.getId());
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 对照 ClearKnowledgeBaseContents 的入队面。Go 是 asynq 异步清理（响应只含
     * 列表计数），录制的两次连续 clear 都是 "task submitted" + 相同计数（worker 尚未
     * 动行）——Java 用 parse_status='deleting' 标记 + 计数复刻这个窗口（行为收敛：
     * 后续读路径对 KB2 无感知；真正的回收与既有 deleteKnowledge 语义一致地缺位，
     * 见类注释已知差异 ①）。
     *
     * @return 本次列入清理的条数
     */
    @Transactional
    public int clearKnowledgeBaseContents(String kbId) {
        List<Knowledge> rows = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getTenantId, tenantId())
                .isNull(Knowledge::getDeletedAt));
        for (Knowledge row : rows) {
            rejectMovingKnowledge(row);
        }
        if (rows.isEmpty()) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .in("id", rows.stream().map(Knowledge::getId).toList())
                .set("parse_status", Knowledge.PARSE_DELETING)
                .set("updated_at", now));
        return rows.size();
    }

    /** 阶段 3 内部：worker 使用的按 id 加载（无租户条件，任务可能跨请求线程） */
    public Knowledge loadById(String id) {
        return knowledgeMapper.selectById(id);
    }

    public void updateStatus(String id, String parseStatus, String errorMessage, Boolean enable) {
        UpdateWrapper<Knowledge> uw = new UpdateWrapper<Knowledge>().eq("id", id);
        uw.set("parse_status", parseStatus);
        uw.set("updated_at", OffsetDateTime.now(ZoneOffset.UTC));
        if (errorMessage != null) {
            uw.set("error_message", errorMessage);
        }
        if (enable != null) {
            uw.set("enable_status", enable ? "enabled" : "disabled");
            uw.set("processed_at", OffsetDateTime.now(ZoneOffset.UTC));
        }
        knowledgeMapper.update(null, uw);
    }

    // ── 波 2 第三批：搜索与移动/复制（8 条路由的服务面） ──────────────────

    /** 搜索结果（对照 Go 的 (knowledges, hasMore, total, err) 四元返回）。 */
    public record SearchOutcome(List<Knowledge> knowledges, boolean hasMore, long total) {}

    /** 对照 types.KnowledgeSearchScope（跨库搜索的 (tenant, kb) 对）。 */
    public record KnowledgeSearchScope(long tenantId, String kbId) {}

    /**
     * 对照 knowledgeService.SearchKnowledge（own + org-shared 文档库的关键词搜索）。
     * <b>已知差异</b>：org-share（kbShareService）未翻译——共享库的补捞分支恒空，
     * 与 ChunkAccessGuard/KnowledgeAccessGuard 的既有收紧同源；本租户文档库路径完整翻译
     * （含 keyword LIKE 转义、file_types 别名、offset/limit+has_more、knowledge_base_name 回填）。
     *
     * <p>scopes 为空时 Go 返回 nil 切片 → 响应 {@code "data":null}；查到 0 行时返回
     * 空**非 nil** 切片 → {@code "data":[]}（GORM make 语义）。Java 用 null data 复刻。</p>
     */
    public SearchOutcome searchKnowledge(String keyword, int offset, int limit, List<String> fileTypes) {
        long tid = tenantId();
        List<KnowledgeSearchScope> scopes = new ArrayList<>();
        for (KnowledgeBase kb : kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getTenantId, tid)
                .isNull(KnowledgeBase::getDeletedAt))) {
            if ("document".equals(kb.getType())) {
                scopes.add(new KnowledgeSearchScope(tid, kb.getId()));
            }
        }
        // org-shared KBs（Go: kbShareService.ListSharedKnowledgeBases）未翻译 → 不补捞
        return searchKnowledgeInScopes(scopes, keyword, offset, limit, fileTypes);
    }

    /**
     * 对照 repo.SearchKnowledgeInScopes：JOIN knowledge_bases 限定 (tenant,kb) 对 +
     * {@code knowledge_bases.type='document'}，keyword 对 LOWER(file_name)/LOWER(title)
     * LIKE（%/_/\ 转义，对照 escapeLikeKeyword），file_types 按扩展名别名展开
     * （xlsx↔xls / docx↔doc / jpg↔jpeg↔png，url/html → type='url'），
     * created_at DESC + limit+1 探测 has_more，total 是过滤后的全量计数。
     */
    public SearchOutcome searchKnowledgeInScopes(List<KnowledgeSearchScope> scopes, String keyword,
                                                 int offset, int limit, List<String> fileTypes) {
        if (scopes == null || scopes.isEmpty()) {
            return new SearchOutcome(null, false, 0);
        }
        // JOIN 语义：KB 行必须存在（同租户）且 type=document
        List<KnowledgeSearchScope> valid = new ArrayList<>();
        for (KnowledgeSearchScope s : scopes) {
            KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getId, s.kbId())
                    .eq(KnowledgeBase::getTenantId, s.tenantId())
                    .isNull(KnowledgeBase::getDeletedAt)
                    .last("LIMIT 1"));
            if (kb != null && "document".equals(kb.getType())) {
                valid.add(s);
            }
        }
        if (valid.isEmpty()) {
            return new SearchOutcome(null, false, 0);
        }
        LambdaQueryWrapper<Knowledge> qw = new LambdaQueryWrapper<Knowledge>().isNull(Knowledge::getDeletedAt);
        qw.and(w -> {
            for (KnowledgeSearchScope s : valid) {
                w.or(i -> i.eq(Knowledge::getTenantId, s.tenantId())
                        .eq(Knowledge::getKnowledgeBaseId, s.kbId()));
            }
        });
        String kw = keyword == null ? "" : keyword;
        if (!kw.isEmpty()) {
            String pat = "%" + escapeLikeKeyword(kw.toLowerCase()) + "%";
            qw.and(w -> w.apply("LOWER(file_name) LIKE {0}", pat)
                    .or()
                    .apply("LOWER(title) LIKE {0}", pat));
        }
        List<String> patterns = fileTypePatterns(fileTypes);
        boolean includeUrl = patterns.remove("<<url>>");
        if (!patterns.isEmpty() || includeUrl) {
            qw.and(w -> {
                for (int i = 0; i < patterns.size(); i++) {
                    if (i > 0) {
                        w.or();
                    }
                    w.apply("LOWER(file_name) LIKE {0}", patterns.get(i));
                }
                if (includeUrl) {
                    if (!patterns.isEmpty()) {
                        w.or();
                    }
                    w.eq(Knowledge::getType, "url");
                }
            });
        }
        long total = knowledgeMapper.selectCount(qw);
        List<Knowledge> rows = knowledgeMapper.selectList(qw
                .orderByDesc(Knowledge::getCreatedAt)
                .last("LIMIT " + (limit + 1) + " OFFSET " + offset));
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        // knowledge_base_name 回填（对照 JOIN 列；kb 行前面已按 scope 校验存在）
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (KnowledgeSearchScope s : valid) {
            KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getId, s.kbId())
                    .eq(KnowledgeBase::getTenantId, s.tenantId())
                    .isNull(KnowledgeBase::getDeletedAt)
                    .last("LIMIT 1"));
            if (kb != null) {
                names.put(kb.getId(), kb.getName());
            }
        }
        for (Knowledge row : rows) {
            row.setKnowledgeBaseName(names.getOrDefault(row.getKnowledgeBaseId(), ""));
        }
        return new SearchOutcome(rows, hasMore, total);
    }

    /** 对照 escapeLikeKeyword：\、%、_ 前加反斜杠（LIKE 默认转义符，H2/PG 一致）。 */
    static String escapeLikeKeyword(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 对照 repo 的 file_types 展开逻辑：小写、去前导点、保序去重、别名互认
     * （xlsx↔xls / docx↔doc / jpg↔jpeg↔png）；url/html 折成 type='url' 条件（哨兵 &lt;&lt;url&gt;&gt;）。
     */
    static List<String> fileTypePatterns(List<String> fileTypes) {
        List<String> patterns = new ArrayList<>();
        if (fileTypes == null) {
            return patterns;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String ft : fileTypes) {
            String f = ft == null ? "" : ft.toLowerCase();
            while (f.startsWith(".")) {
                f = f.substring(1);
            }
            if ("url".equals(f) || "html".equals(f)) {
                if (!seen.contains("<<url>>")) {
                    seen.add("<<url>>");
                    patterns.add("<<url>>");
                }
                continue;
            }
            String pat = "%." + f;
            if (!seen.contains(pat)) {
                seen.add(pat);
                patterns.add(pat);
            }
            List<String> aliases = switch (f) {
                case "xlsx" -> List.of("%.xls");
                case "xls" -> List.of("%.xlsx");
                case "docx" -> List.of("%.doc");
                case "doc" -> List.of("%.docx");
                case "jpg" -> List.of("%.jpeg", "%.png");
                case "jpeg" -> List.of("%.jpg", "%.png");
                case "png" -> List.of("%.jpg", "%.jpeg");
                default -> List.<String>of();
            };
            for (String alias : aliases) {
                if (!seen.contains(alias)) {
                    seen.add(alias);
                    patterns.add(alias);
                }
            }
        }
        return patterns;
    }

    // ── 任务 ID（对照 utils/taskid.go） ──────────────────────────────────

    /**
     * 对照 GenerateTaskID：{@code <type>_<tenant>_<millis>_<8hex>_<business12>}，
     * business 段取前 12 字符并剔除 - _ :。进度路由按嵌入的租户段做隔离校验。
     */
    public static String generateTaskId(String taskType, long tenantId, String businessId) {
        String type = taskType == null ? "" : taskType;
        type = type.replace(":", "_").replace("-", "_").replace(" ", "_").toLowerCase();
        String biz = businessId == null ? "" : businessId;
        if (biz.length() > 12) {
            biz = biz.substring(0, 12);
        }
        biz = biz.replace("-", "").replace("_", "").replace(":", "");
        String shortUuid = UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String id = type + "_" + tenantId + "_" + System.currentTimeMillis() + "_" + shortUuid;
        if (!biz.isEmpty()) {
            id += "_" + biz;
        }
        return id;
    }

    /**
     * 对照 utils.ParseTaskID/TaskTenantID：从 {@code <type>_<tenant>_<ts>_<uuid>[_<biz>]}
     * 里定位 (tenant, timestamp) 对（type 段可含下划线）。解析失败返回 null → 调用方出
     * 400 "invalid task ID"。
     */
    public static Long taskTenantId(String taskId) {
        if (taskId == null) {
            return null;
        }
        String[] parts = taskId.split("_");
        if (parts.length < 4) {
            return null;
        }
        for (int i = 1; i < parts.length - 2; i++) {
            Long tenant = parseUint(parts[i]);
            if (tenant == null || tenant == 0) {
                continue;
            }
            Long ts = parseLong(parts[i + 1]);
            if (ts == null || ts < 1_000_000_000_000L) {
                continue;
            }
            return tenant;
        }
        return null;
    }

    private static Long parseUint(String s) {
        try {
            return Long.parseUnsignedLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Move（asynq → 进程内虚拟线程；HTTP 契约 = 立即返回 + 进度查询） ────

    /**
     * 入队 move 任务（对照 handler 的 asynq.Enqueue + SaveKnowledgeMoveProgress）。
     * 初始进度 SetNX（键已存在不覆写）；worker 在虚拟线程里真实驱动状态机：
     * pending → processing（total=items）→ 逐条搬行（"Moved X/N knowledge items"）→
     * completed/100（error=""，created_at=0——Go worker 的新对象不带 created_at，实录）。
     *
     * <p><b>已知差异</b>：只搬 DB 行（knowledges.knowledge_base_id + chunks），向量索引
     * / wiki / reparse 衍生数据不复制（检索引擎未接线，随波 4）；asynq 的
     * retry/marker 语义不翻译（既有取舍）。</p>
     */
    public void startKnowledgeMove(long tenantId, String taskId, List<String> knowledgeIds,
                                   String sourceKbId, String targetKbId, String mode) {
        progressStore.saveMoveInitial(new com.ragagent.knowledge.dto.KnowledgeTaskDtos.KnowledgeMoveProgress(
                taskId, sourceKbId, targetKbId, "pending", 0, knowledgeIds.size(), 0, 0,
                "Task queued, waiting to start...", "", epochNow(), epochNow()));
        // §5：跨虚拟线程显式传值，不共享 ThreadLocal
        final String role = TenantContext.currentRole();
        final String userId = TenantContext.currentUserId();
        Thread.ofVirtual().start(() -> {
            TenantContext.set(tenantId, null, role, false, userId, false);
            try {
                runKnowledgeMove(tenantId, taskId, knowledgeIds, sourceKbId, targetKbId);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private void runKnowledgeMove(long tenantId, String taskId, List<String> knowledgeIds,
                                  String sourceKbId, String targetKbId) {
        int total = knowledgeIds.size();
        progressStore.saveMove(new KnowledgeMoveProgress(
                taskId, sourceKbId, targetKbId, "processing", 0, total, 0, 0, "", "", 0, epochNow()));
        int processed = 0;
        int failed = 0;
        String failures = null;
        for (String id : knowledgeIds) {
            try {
                moveOneKnowledgeRow(tenantId, id, targetKbId);
            } catch (RuntimeException e) {
                failed++;
                String itemFailure = "knowledge " + id + ": " + e.getMessage();
                failures = failures == null ? itemFailure : failures + "\n" + itemFailure;
            }
            processed++;
            int done = processed - failed;
            progressStore.saveMove(new KnowledgeMoveProgress(
                    taskId, sourceKbId, targetKbId, "processing", processed * 100 / total,
                    total, processed, failed,
                    "Moved " + done + "/" + total + " knowledge items", "", 0, epochNow()));
        }
        if (failures != null) {
            progressStore.saveMove(new KnowledgeMoveProgress(
                    taskId, sourceKbId, targetKbId, "failed", processed * 100 / total,
                    total, processed, failed, "Moved " + (processed - failed) + "/" + total
                            + " knowledge items", failures, 0, epochNow()));
            return;
        }
        progressStore.saveMove(new KnowledgeMoveProgress(
                taskId, sourceKbId, targetKbId, "completed", 100, total, processed, failed,
                "Moved " + (processed - failed) + "/" + total + " knowledge items", "", 0, epochNow()));
    }

    /** 单条搬行：knowledge 行 + chunks 行换 KB（向量索引不搬，见 startKnowledgeMove 差异）。 */
    private void moveOneKnowledgeRow(long tenantId, String knowledgeId, String targetKbId) {
        Knowledge row = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, tenantId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (row == null) {
            throw new IllegalStateException("not found");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String sourceKbId = row.getKnowledgeBaseId();
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .set("knowledge_base_id", targetKbId)
                .set("updated_at", now));
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .eq("knowledge_id", knowledgeId)
                .set("knowledge_base_id", targetKbId)
                .set("updated_at", now));
        // 对照 Go knowledge_clone_move.go L1342-1352：搬走后源 KB 的命名空间不得继续
        // 暴露该文档（失败上抛——移动任务据此重试；命名空间删除可重复执行）
        graphRepository.delGraph(List.of(
                new com.ragagent.chatpipeline.ChatManage.NameSpace(sourceKbId, knowledgeId)));
    }

    public void saveKnowledgeMoveProgress(com.ragagent.knowledge.dto.KnowledgeTaskDtos.KnowledgeMoveProgress p) {
        progressStore.saveMoveInitial(p);
    }

    /** 查不到（含过期）→ null；对照 Go 的 404 "Knowledge move task not found"。 */
    public com.ragagent.knowledge.dto.KnowledgeTaskDtos.KnowledgeMoveProgress getKnowledgeMoveProgress(String taskId) {
        return progressStore.getMove(taskId);
    }

    // ── KB clone（copy 路由的 worker 面） ─────────────────────────────────

    /**
     * 入队 KB clone 任务（对照 handler 的 asynq.Enqueue + SaveKBCloneProgress）。
     * worker 进程内执行：create 目标时按 Go 保留字段建 KB 行（**不含** indexing_strategy —
     * EnsureDefaults 补成 vector+keyword）；已有目标做 preflight（add=源里 target 没有的、
     * remove=target 里的多余行；file_hash+completed 二次匹配），total=add+remove，
     * 逐步 "Processed X/N clone operations"，终态 completed/100 +
     * "Knowledge base clone completed successfully"（created_at=0 实录）。
     *
     * <p><b>已知差异</b>：克隆只到"行级"（KB 行 + knowledge 行 + chunk 行），向量索引/
     * 文件对象/wiki/FAQ tag 映射不复制；transfer-state 续跑/重试语义不翻译。</p>
     */
    public void startKBClone(long tenantId, String taskId, String sourceId, String targetId,
                             boolean createTarget, String creatorId) {
        progressStore.saveCloneInitial(new com.ragagent.knowledge.dto.KnowledgeTaskDtos.KBCloneProgress(
                taskId, sourceId, targetId, "pending", 0, 0, 0,
                "Task queued, waiting to start...", "", epochNow(), epochNow()));
        Thread.ofVirtual().start(() -> {
            TenantContext.set(tenantId, null, null, false, null, false);
            try {
                runKBClone(tenantId, taskId, sourceId, targetId, createTarget, creatorId);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private void runKBClone(long tenantId, String taskId, String sourceId, String targetId,
                            boolean createTarget, String creatorId) {
        KnowledgeBase source = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, sourceId)
                .eq(KnowledgeBase::getTenantId, tenantId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (source == null) {
            progressStore.saveClone(new KBCloneProgress(
                    taskId, sourceId, targetId, "failed", 0, 0, 0, "Clone preflight failed",
                    "knowledge base not found", 0, epochNow()));
            return;
        }
        KBCloneProgress progress = new KBCloneProgress(
                taskId, sourceId, targetId, "processing", 0, 0, 0,
                "Starting knowledge base clone...", "", 0, epochNow());
        progressStore.saveClone(progress);
        try {
            KnowledgeBase dst = targetRowForClone(tenantId, targetId, createTarget, creatorId, source);
            // preflight：add = 源里 target 没有的（file_hash+completed 二次匹配）；
            // remove = target 里的多余行；源里未完成的行报错（对照 planKnowledgeClone）
            List<Knowledge> srcRows = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getKnowledgeBaseId, sourceId)
                    .eq(Knowledge::getTenantId, tenantId)
                    .isNull(Knowledge::getDeletedAt));
            List<Knowledge> dstRows = createTarget ? new ArrayList<>()
                    : knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                            .eq(Knowledge::getKnowledgeBaseId, targetId)
                            .eq(Knowledge::getTenantId, tenantId)
                            .isNull(Knowledge::getDeletedAt));
            List<Knowledge> toAdd = new ArrayList<>();
            java.util.Set<String> matched = new java.util.HashSet<>();
            for (Knowledge k : srcRows) {
                if (!Knowledge.PARSE_COMPLETED.equals(k.getParseStatus())) {
                    throw new IllegalStateException("source knowledge " + k.getId() + " is not completed");
                }
                boolean hit = false;
                if (k.getFileHash() != null && !k.getFileHash().isEmpty()) {
                    for (Knowledge o : dstRows) {
                        if (!matched.contains(o.getId()) && k.getFileHash().equals(o.getFileHash())
                                && Knowledge.PARSE_COMPLETED.equals(o.getParseStatus())) {
                            matched.add(o.getId());
                            hit = true;
                            break;
                        }
                    }
                }
                if (!hit) {
                    toAdd.add(k);
                }
            }
            List<String> toRemove = new ArrayList<>();
            for (Knowledge o : dstRows) {
                if (matched.contains(o.getId())) {
                    continue;
                }
                if (Knowledge.PARSE_PROCESSING.equals(o.getParseStatus())
                        || Knowledge.PARSE_PENDING.equals(o.getParseStatus())
                        || Knowledge.PARSE_DELETING.equals(o.getParseStatus())) {
                    throw new IllegalStateException("target knowledge " + o.getId() + " is busy");
                }
                toRemove.add(o.getId());
            }
            int total = toAdd.size() + toRemove.size();
            progress = new KBCloneProgress(taskId, sourceId, targetId, "processing", 0, total, 0,
                    progress.message(), "", 0, epochNow());
            progressStore.saveClone(progress);
            int done = 0;
            for (String id : toRemove) {
                removeKnowledgeRow(id);
                done++;
                progress = progress.withDone(done);
                progressStore.saveClone(progress);
            }
            for (Knowledge k : toAdd) {
                cloneKnowledgeRow(k, dst);
                done++;
                progress = progress.withDone(done);
                progressStore.saveClone(progress);
            }
            progressStore.saveClone(new KBCloneProgress(
                    taskId, sourceId, targetId, "completed", 100, total,
                    total, "Knowledge base clone completed successfully", "", 0, epochNow()));
        } catch (RuntimeException e) {
            progressStore.saveClone(new KBCloneProgress(
                    taskId, sourceId, targetId, "failed", progress.progress(), progress.total(),
                    progress.processed(), "Failed to clone knowledge", String.valueOf(e.getMessage()),
                    0, epochNow()));
        }
    }

    /** create 目标：按 Go ProcessKBClone/CopyKnowledgeBase 的保留字段建行（索引策略走默认）。 */
    private KnowledgeBase targetRowForClone(long tenantId, String targetId, boolean create,
                                            String creatorId, KnowledgeBase source) {
        if (!create) {
            return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getId, targetId)
                    .eq(KnowledgeBase::getTenantId, tenantId)
                    .isNull(KnowledgeBase::getDeletedAt)
                    .last("LIMIT 1"));
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(targetId);
        kb.setTenantId(tenantId);
        kb.setCreatorId(creatorId);
        kb.setName(source.getName());
        kb.setType(source.getType());
        kb.setDescription(source.getDescription());
        kb.setChunkingConfig(source.getChunkingConfig());
        kb.setImageProcessingConfig(source.getImageProcessingConfig());
        kb.setEmbeddingModelId(source.getEmbeddingModelId());
        kb.setSummaryModelId(source.getSummaryModelId());
        kb.setVlmConfig(source.getVlmConfig());
        kb.setStorageProviderConfig(source.getStorageProviderConfig());
        kb.setStorageBackendId(source.getStorageBackendId());
        kb.setStorageConfig(source.getStorageConfig());
        kb.setFaqConfig(source.getFaqConfig());
        kb.setVectorStoreId(source.getVectorStoreId());
        kb.setIndexingStrategy(KbIndexingStrategy.defaultStrategy());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kb.normalizeVectorStoreId();
        kbMapper.insert(kb);
        return kb;
    }

    /** 对照 deleteReferencedKnowledge 的可观测子集：knowledge 软删 + chunk 软删。 */
    private void removeKnowledgeRow(String knowledgeId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId)
                .isNull("deleted_at")
                .set("deleted_at", now));
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .eq("knowledge_id", knowledgeId)
                .set("deleted_at", now));
    }

    /** 行级克隆：新 knowledge id + 新 chunk id（向量/文件对象不复制，见 startKBClone 差异）。 */
    private void cloneKnowledgeRow(Knowledge src, KnowledgeBase dst) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String newId = UUID.randomUUID().toString();
        Knowledge copy = new Knowledge();
        copy.setId(newId);
        copy.setTenantId(dst.getTenantId());
        copy.setKnowledgeBaseId(dst.getId());
        copy.setType(src.getType());
        copy.setTitle(src.getTitle());
        copy.setDescription(src.getDescription());
        copy.setSource(src.getSource());
        copy.setParseStatus(src.getParseStatus());
        copy.setSummaryStatus(src.getSummaryStatus());
        copy.setEnableStatus(src.getEnableStatus());
        copy.setEmbeddingModelId(src.getEmbeddingModelId());
        copy.setFileName(src.getFileName());
        copy.setFolderPath(src.getFolderPath());
        copy.setFileType(src.getFileType());
        copy.setFileSize(src.getFileSize());
        copy.setFileHash(src.getFileHash());
        copy.setFilePath(src.getFilePath());
        copy.setMetadata(src.getMetadata());
        copy.setCustomMetadata(src.getCustomMetadata());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        copy.setErrorMessage(src.getErrorMessage());
        knowledgeMapper.insert(copy);
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKnowledgeId, src.getId())
                .eq(Chunk::getTenantId, src.getTenantId())
                .isNull(Chunk::getDeletedAt));
        for (Chunk c : chunks) {
            Chunk nc = new Chunk();
            nc.setId(UUID.randomUUID().toString());
            nc.setTenantId(dst.getTenantId());
            nc.setKnowledgeId(newId);
            nc.setKnowledgeBaseId(dst.getId());
            nc.setContent(c.getContent());
            nc.setChunkIndex(c.getChunkIndex());
            nc.setIsEnabled(c.isIsEnabled());
            nc.setChunkType(c.getChunkType());
            nc.setContentHash(c.getContentHash());
            nc.setStartAt(c.getStartAt());
            nc.setEndAt(c.getEndAt());
            nc.setCreatedAt(now);
            nc.setUpdatedAt(now);
            chunkMapper.insert(nc);
        }
    }

    public void saveKBCloneProgress(com.ragagent.knowledge.dto.KnowledgeTaskDtos.KBCloneProgress p) {
        progressStore.saveCloneInitial(p);
    }

    /** 查不到（含过期）→ null；对照 Go 的 404 "KB clone task not found"。 */
    public com.ragagent.knowledge.dto.KnowledgeTaskDtos.KBCloneProgress getKBCloneProgress(String taskId) {
        return progressStore.getClone(taskId);
    }

    // ── Duplicate（同步，settings-only） ──────────────────────────────────

    /**
     * 对照 knowledgeBaseService.DuplicateKnowledgeBase：JSON 往返克隆配置，新 id/租户，
     * 名字带 " 副本"（zh 缺省；重名 " 2"、" 3"...），creator=调用者（非合成用户），
     * 计数/置顶/临时全清零，EnsureDefaults + Normalize 后落库。**只复制设置**——
     * knowledge/chunk/索引/分享/置顶都不带（Go 同义）。
     */
    public KnowledgeBase duplicateKnowledgeBase(String sourceId) {
        KnowledgeBase source = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, sourceId)
                .eq(KnowledgeBase::getTenantId, tenantId())
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (source == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        KnowledgeBaseService.ensureDefaults(source);
        KnowledgeBase target;
        try {
            // JSON 往返深拷贝（对照 cloneKnowledgeBaseConfiguration 的 Marshal/Unmarshal）。
            // 注意 MAPPER 是裸 ObjectMapper（无 JSR310）——KnowledgeBase 带 OffsetDateTime，
            // 必须用带 JavaTimeModule 的独立 mapper（与 AbstractJsonListTypeHandler 的教训同族）。
            target = CLONE_MAPPER.convertValue(source, KnowledgeBase.class);
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.internal("failed to clone knowledge base configuration")
                    .withDetails(String.valueOf(e.getMessage())));
        }
        target.setId(UUID.randomUUID().toString());
        target.setTenantId(tenantId());
        target.setName(buildDuplicateKnowledgeBaseName(tenantId(), source.getName()));
        String uid = TenantContext.currentUserId();
        target.setCreatorId(uid != null && !uid.startsWith("system-") ? uid : "");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        target.setDeletedAt(null);
        target.setIsTemporary(false);
        target.setIsPinned(false);
        target.setPinnedAt(null);
        target.setKnowledgeCount(0);
        target.setChunkCount(0);
        target.setIsProcessing(false);
        target.setProcessingCount(0);
        target.setShareCount(0);
        target.setCreatorName("");
        KnowledgeBaseService.ensureDefaults(target);
        target.normalizeVectorStoreId();
        kbMapper.insert(target);
        return target;
    }

    /** 对照 buildDuplicateKnowledgeBaseName：zh 缺省后缀 " 副本"，重名追加 " 2"/" 3"...。 */
    private String buildDuplicateKnowledgeBaseName(long tid, String sourceName) {
        String baseName = sourceName == null ? "" : sourceName.trim();
        if (baseName.isEmpty()) {
            baseName = "知识库";
        }
        String suffix = " 副本";
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (KnowledgeBase kb : kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getTenantId, tid)
                .isNull(KnowledgeBase::getDeletedAt))) {
            existing.add(kb.getName());
        }
        String candidate = baseName + suffix;
        if (!existing.contains(candidate)) {
            return candidate;
        }
        for (int i = 2; ; i++) {
            candidate = baseName + suffix + " " + i;
            if (!existing.contains(candidate)) {
                return candidate;
            }
        }
    }

    // ── 跨库兼容性（对照 access.ValidateKBTransferCompatibility） ─────────

    /** 消息逐字对照 Go（handler 包成 400 AppError，message=原文）。 */
    public static void validateKBTransferCompatibility(KnowledgeBase source, KnowledgeBase target,
                                                       String mode) {
        if (!source.getType().equals(target.getType())) {
            throw new IllegalArgumentException("source and target knowledge bases must have the same type");
        }
        String se = source.getEmbeddingModelId() == null ? "" : source.getEmbeddingModelId();
        String te = target.getEmbeddingModelId() == null ? "" : target.getEmbeddingModelId();
        if (!se.equals(te)) {
            throw new IllegalArgumentException("source and target knowledge bases use different embedding models");
        }
        if (!"reuse_vectors".equals(mode) && !"reparse".equals(mode)) {
            throw new IllegalArgumentException("unknown move mode: " + mode);
        }
        if ("reuse_vectors".equals(mode) && !sharesStoreWith(source, target)) {
            throw new IllegalArgumentException(
                    "source and target knowledge bases use different vector stores; use reparse mode for moves");
        }
    }

    /** clone（无 mode）的兼容性判定：mode 传 null 跳过 move 专属检查。 */
    public static void validateCloneCompatibility(KnowledgeBase source, KnowledgeBase target) {
        if (!source.getType().equals(target.getType())) {
            throw new IllegalArgumentException("source and target knowledge bases must have the same type");
        }
        String se = source.getEmbeddingModelId() == null ? "" : source.getEmbeddingModelId();
        String te = target.getEmbeddingModelId() == null ? "" : target.getEmbeddingModelId();
        if (!se.equals(te)) {
            throw new IllegalArgumentException("source and target knowledge bases use different embedding models");
        }
        if (!sharesStoreWith(source, target)) {
            throw new IllegalArgumentException(
                    "source and target knowledge bases use different vector stores; use reparse mode for moves");
        }
    }

    /** 对照 KnowledgeBase.SharesStoreWith：两边都没绑定（null/空串）视为共享。 */
    static boolean sharesStoreWith(KnowledgeBase a, KnowledgeBase b) {
        String sa = normalizeStore(a);
        String sb = normalizeStore(b);
        if (sa.isEmpty() && sb.isEmpty()) {
            return true;
        }
        return !sa.isEmpty() && sa.equals(sb);
    }

    private static String normalizeStore(KnowledgeBase kb) {
        String v = kb == null || kb.getVectorStoreId() == null ? "" : kb.getVectorStoreId();
        return v.trim();
    }

    private static long epochNow() {
        return java.time.Instant.now().getEpochSecond();
    }

    /** 409 重复文档（对照 handler L173-178 特殊信封，不走 error_handler） */
    public static class DuplicateKnowledgeException extends RuntimeException {
        private final Knowledge existing;
        private final String code;

        public DuplicateKnowledgeException(Knowledge existing, String code, String message) {
            super(message);
            this.existing = existing;
            this.code = code;
        }

        public Knowledge existing() { return existing; }
        public String code() { return code; }
    }

    /** 占位：worker bean 由 KnowledgeProcessWorker 提供（@Lazy 避免循环依赖） */
    public interface KnowledgeProcessWorker {
        void enqueue(String knowledgeId);
    }
}
