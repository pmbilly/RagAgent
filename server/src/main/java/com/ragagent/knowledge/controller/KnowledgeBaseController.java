package com.ragagent.knowledge.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.KnowledgeBaseResponseBuilder;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.retrieval.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go internal/handler/knowledgebase.go（阶段 3 子集 + 波 2 第三批：
 * hybrid-search（POST+GET）/ copy / duplicate / copy/progress）。
 *
 * <p>请求体直接绑定 types.KnowledgeBase（无 CreateRequest 结构）——含 legacy cos_config 兼容
 * （对照 UnmarshalJSON L412）。响应 data 经「实体→map 合并」输出，全部键字母序
 * （KnowledgeBaseResponseBuilder）。</p>
 *
 * <p><b>波 2 第三批的守卫顺序</b>（golden 依赖，不能重排）：
 * hybrid-search / duplicate 的路由带 {@code KBAccessRead}（Java 在控制器内 =
 * {@code guard.requireKbAccess}：缺失 → 404 小写 / 跨租户 → 403 信封）；copy 的
 * source/target 在 body 里，handler 内 resolveHandlerKBAccessFor——跨租户在
 * access.ResolveKB 出 403 "Permission denied to access this knowledge base"
 * （golden ks-copy-cross-source），与 move 的 handler 租户检查文案不同。</p>
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeBaseService kbService;
    private final com.ragagent.knowledge.service.KnowledgeService knowledgeService;
    private final com.ragagent.knowledge.service.KnowledgeAccessGuard guard;
    private final HybridSearchService hybridSearchService;

    public KnowledgeBaseController(KnowledgeBaseService kbService,
                                   com.ragagent.knowledge.service.KnowledgeService knowledgeService,
                                   com.ragagent.knowledge.service.KnowledgeAccessGuard guard,
                                   HybridSearchService hybridSearchService) {
        this.kbService = kbService;
        this.knowledgeService = knowledgeService;
        this.guard = guard;
        this.hybridSearchService = hybridSearchService;
    }

    /** 对照 CreateKnowledgeBase — Contributor+ */
    @PostMapping
    public ResponseEntity<?> createKnowledgeBase(@RequestBody(required = false) String rawBody) {
        log.info("Start creating knowledge base");
        KnowledgeBase kb = bindKnowledgeBase(rawBody);
        kb = kbService.createKnowledgeBase(kb);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(envelope(KnowledgeBaseResponseBuilder.build(kb, kbService.retrieveDriver())));
    }

    /** 对照 ShouldBindJSON(&types.KnowledgeBase) + UnmarshalJSON legacy 兼容 */
    private static KnowledgeBase bindKnowledgeBase(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return new KnowledgeBase();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("Invalid request parameters").withDetails(e.getMessage()));
        }
        // legacy cos_config → storage_config（对照 UnmarshalJSON）
        if (node.hasNonNull("cos_config") && !node.hasNonNull("storage_config")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                    .set("storage_config", node.get("cos_config"));
        }
        if (node.hasNonNull("storage_config") && node.get("storage_config").hasNonNull("provider")
                && !node.hasNonNull("storage_provider_config")) {
            ObjectNodeCompat.setProvider(node, node.get("storage_config").get("provider").asText());
        }
        try {
            KnowledgeBase kb = MAPPER.convertValue(node, KnowledgeBase.class);
            normalizeConfigOmitEmpty(kb);
            return kb;
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest("Invalid request parameters").withDetails(e.getMessage()));
        }
    }

    /**
     * 对照 Go 的 omitempty 归一：Go 把配置绑进**带 omitempty 标签的 struct**，
     * 入库（Value() marshal）与响应（json.Marshal）都会丢掉空值字段；Java 侧这些
     * 字段是 JsonNode 透传，若不归一，前端编辑器发来的空串/空数组会被原样
     * 存库并回显（走查抓回的 A/B DIFF：Go 的 extract_config 只有 {"enabled":false}）。
     * keep 集合 = 无 omitempty 的标签（恒保留）；其余字段空值（null/""/0/[]/{}）剔除。
     */
    private static void normalizeConfigOmitEmpty(KnowledgeBase kb) {
        kb.setExtractConfig(dropEmpty(kb.getExtractConfig(), Set.of("enabled")));
        kb.setWikiConfig(dropEmpty(kb.getWikiConfig(), Set.of("synthesis_model_id", "max_pages_per_ingest")));
        kb.setAutoTagConfig(dropEmpty(kb.getAutoTagConfig(), Set.of("enabled")));
        kb.setQuestionGenerationConfig(dropEmpty(kb.getQuestionGenerationConfig(),
                Set.of("enabled", "question_count")));
        // faq_config：Go 的 FAQConfig 两个字段都无 omitempty → 原样保留，不归一
    }

    /** omitempty 语义：keep 之外的字段，值为 null/空串/0/空数组/空对象时剔除。
     *  注意 bool 不剔——相关配置里唯一的 omitempty bool 是 *bool（skip_if_tagged），
     *  Go 对非 nil 指针的 false 也保留。 */
    private static JsonNode dropEmpty(JsonNode node, Set<String> keep) {
        if (node == null || !node.isObject()) {
            return node;
        }
        com.fasterxml.jackson.databind.node.ObjectNode obj =
                (com.fasterxml.jackson.databind.node.ObjectNode) node;
        List<String> drop = new ArrayList<>();
        obj.fields().forEachRemaining(e -> {
            if (keep.contains(e.getKey())) {
                return;
            }
            JsonNode v = e.getValue();
            boolean empty = v == null || v.isNull()
                    || (v.isTextual() && v.asText().isEmpty())
                    || (v.isNumber() && v.numberValue().doubleValue() == 0d)
                    || (v.isContainerNode() && v.isEmpty());
            if (empty) {
                drop.add(e.getKey());
            }
        });
        drop.forEach(obj::remove);
        return obj;
    }

    /** 对照 ListKnowledgeBases — Viewer+；creator=mine|others 过滤。 */
    @GetMapping
    public ResponseEntity<?> listKnowledgeBases(
            @RequestParam(value = "creator", required = false) String creator) {
        log.info("Start listing knowledge bases");
        List<KnowledgeBase> kbs = kbService.listKnowledgeBases(creator);
        // 对照 filterKnowledgeBasesForAPIKeyScope：KB 受限的 API Key 只看得到白名单内的库。
        // 这是**数据面**校验（门禁层只校验路由能力），scoped Key 的收口强度取决于此处。
        var scope = com.ragagent.apikey.domain.APIKeyScopeContext.current();
        if (scope != null && scope.isKnowledgeBaseRestricted()) {
            kbs = kbs.stream().filter(kb -> scope.allowsKnowledgeBase(kb.getId())).toList();
        }
        List<Map<String, Object>> data = new ArrayList<>(kbs.size());
        for (KnowledgeBase kb : kbs) {
            data.add(KnowledgeBaseResponseBuilder.buildListItem(kb, kbService.retrieveDriver()));
        }
        return ResponseEntity.ok(envelope(data));
    }

    /** 对照 GetKnowledgeBase — Viewer+（KBAccessRead：本租户全员可读） */
    @GetMapping("/{id}")
    public ResponseEntity<?> getKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start retrieving knowledge base, ID: {}", id);
        KnowledgeBase kb = kbService.getKnowledgeBase(id);
        return ResponseEntity.ok(envelope(KnowledgeBaseResponseBuilder.build(kb, kbService.retrieveDriver())));
    }

    /** 对照 UpdateKnowledgeBase — OwnedKBOrAdmin + KBAccessWrite（handler 内 permission 校验阶段 3 简化） */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateKnowledgeBase(@PathVariable("id") String id,
                                                 @RequestBody(required = false) String rawBody) {
        log.info("Start updating knowledge base, ID: {}", id);
        KnowledgeBase existing = kbService.getKnowledgeBase(id);
        checkOwnership(existing);
        UpdateKbRequest req = parseUpdate(rawBody);
        if (req == null) {
            throw new BizException(AppError.badRequest("EOF"));
        }
        if (req.name() != null && req.name().isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "Key: 'UpdateKnowledgeBaseRequest.Name' Error:Field validation for 'Name' failed on the 'required' tag"));
        }
        existing = kbService.updateKnowledgeBase(existing, req.name(), req.description(), req.config());
        return ResponseEntity.ok(envelope(KnowledgeBaseResponseBuilder.build(existing, kbService.retrieveDriver())));
    }

    /** 对照 OwnedKBOrAdmin：创建者本人或 Admin+，否则 403（阶段 3 不泄漏存在性语义同 Go） */
    private static void checkOwnership(KnowledgeBase kb) {
        String role = TenantContext.currentRole();
        String uid = TenantContext.currentUserId();
        boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
        if (!admin && (kb.getCreatorId().isEmpty() || !kb.getCreatorId().equals(uid))) {
            throw GuardForbiddenException.mustOwnResourceOrHaveRole();
        }
    }

    private record UpdateKbRequest(String name, String description, JsonNode config) {}

    private static UpdateKbRequest parseUpdate(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            JsonNode config = node.hasNonNull("config") ? node.get("config") : null;
            return new UpdateKbRequest(text(node, "name"), text(node, "description"), config);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("Invalid request parameters").withDetails(e.getMessage()));
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** 对照 DeleteKnowledgeBase — 所有者租户 + Admin */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start deleting knowledge base, ID: {}", id);
        KnowledgeBase existing = kbService.getKnowledgeBase(id);
        checkOwnership(existing);
        if (!TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN)) {
            throw new BizException(AppError.forbidden("Only knowledge base owner can delete"));
        }
        kbService.deleteKnowledgeBase(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Knowledge base deleted successfully");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 TogglePinKnowledgeBase — Viewer+（per-(user,kb)） */
    @PutMapping("/{id}/pin")
    public ResponseEntity<?> togglePin(@PathVariable("id") String id) {
        log.info("Start toggling pin for knowledge base, ID: {}", id);
        KnowledgeBase kb = kbService.togglePin(id);
        return ResponseEntity.ok(envelope(KnowledgeBaseResponseBuilder.build(kb, kbService.retrieveDriver())));
    }

    /** 对照 ListMoveTargets — Viewer+；返回原始实体序列化（struct 声明序） */
    @GetMapping("/{id}/move-targets")
    public ResponseEntity<?> listMoveTargets(@PathVariable("id") String id) {
        log.info("Start listing move targets, ID: {}", id);
        List<KnowledgeBase> targets = kbService.listMoveTargets(id);
        List<Map<String, Object>> data = new ArrayList<>(targets.size());
        for (KnowledgeBase kb : targets) {
            data.add(KnowledgeBaseResponseBuilder.buildRaw(kb));
        }
        return ResponseEntity.ok(envelope(data));
    }

    /** gin.H 信封：key 字母序（data < success） */
    static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }

    // ── 波 2 第三批：hybrid-search / copy / duplicate / copy progress ─────

    /**
     * 对照 HybridSearch（POST 为主；GET 携带 JSON body 兼容 #1727——两条路由同一 handler）。
     * 检索引擎（retriever）未翻译（波 4）：需要真实向量/关键词执行的部分不可达，Java 落
     * Go 的「零结果」出口（{@code {"data":null,"success":true}}，golden 钉住）。
     * <b>已知差异：绑定过向量库且命中数据时 Go 能出结果</b>；前置的确定性分支
     * （KB 访问守卫、query_text 必填、resource_urls 解析、多库 scope 授权）逐字翻译。
     */
    @PostMapping("/{id}/hybrid-search")
    public ResponseEntity<?> hybridSearchPost(@PathVariable("id") String id,
                                              @RequestBody(required = false) String rawBody,
                                              @RequestParam(value = "resource_urls", required = false) String resourceUrls) {
        return hybridSearch(id, rawBody, resourceUrls);
    }

    /** 对照 GET /knowledge-bases/{id}/hybrid-search（同 handler，body 语义一致）。 */
    @GetMapping("/{id}/hybrid-search")
    public ResponseEntity<?> hybridSearchGet(@PathVariable("id") String id,
                                             @RequestBody(required = false) String rawBody,
                                             @RequestParam(value = "resource_urls", required = false) String resourceUrls) {
        return hybridSearch(id, rawBody, resourceUrls);
    }

    private ResponseEntity<?> hybridSearch(String id, String rawBody, String resourceUrls) {
        log.info("Start hybrid search");
        // 对照 validateAndGetKnowledgeBase → 路由 KBAccessRead 的控制器内落地
        KnowledgeBase kb = guard.requireKbAccess(id);
        // 对照 ShouldBindJSON(&types.SearchParams)：错误形态是 message+details（EOF/解析器原文）
        JsonNode req = bindSearchParams(rawBody);
        String queryText = req.path("query_text").asText("");
        JsonNode embedding = req.get("query_embedding");
        boolean precomputedVectorOnly = embedding != null && embedding.isArray() && !embedding.isEmpty()
                && req.path("disable_keywords_match").asBoolean(false)
                && !req.path("disable_vector_match").asBoolean(false);
        if (queryText.trim().isEmpty() && !precomputedVectorOnly) {
            throw new BizException(AppError.badRequest("query_text is required"));
        }
        // 对照 resolveResourceRewriter：public 拒绝 → 403；其他坏值 → 400
        try {
            com.ragagent.storageurl.Mode.resolve(resourceUrls);
        } catch (com.ragagent.storageurl.PublicModeForbiddenException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw new BizException(AppError.forbidden(e.getMessage()));
        } catch (com.ragagent.storageurl.ResourceModeException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        // 对照 service.HybridSearch 的前置确定性段：
        // GetKnowledgeBaseByIDs（租户无关）→ 空集 404；authorizeKBAccess → 未授权 404；
        // pickPrimary 缺席 → 404。检索执行已随检索引擎批接线（下方）。
        List<String> searchKbIds = new ArrayList<>();
        JsonNode idsNode = req.get("knowledge_base_ids");
        if (idsNode != null && idsNode.isArray() && !idsNode.isEmpty()) {
            idsNode.forEach(n -> searchKbIds.add(n.asText()));
        } else {
            searchKbIds.add(kb.getId());
        }
        com.ragagent.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeBases(searchKbIds);
        List<KnowledgeBase> kbs = new ArrayList<>();
        for (String kbId : searchKbIds) {
            KnowledgeBase row = kbService.getAllTenantById(kbId);
            if (row != null) {
                kbs.add(row);
            }
        }
        if (kbs.isEmpty()) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        // 空间分享裁撤：检索面只认本租户 KB（跨租户 org-share 授权链已退役）。
        Long caller = TenantContext.currentTenantId();
        for (KnowledgeBase row : kbs) {
            if (row.getTenantId() == null || !row.getTenantId().equals(caller)) {
                throw new BizException(AppError.notFound("knowledge base not found"));
            }
        }
        boolean primaryFound = kbs.stream().anyMatch(row -> row.getId().equals(kb.getId()));
        if (!primaryFound) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        // 检索引擎批（2026-09-22）：执行面接入 HybridSearchService
        // （pgvector + ParadeDB BM25 + RRF + FAQ 后处理 + 富化装配）。
        // Go 在空管线/零命中时返回 nil → data:null（形态保持不变）。
        com.ragagent.chatpipeline.SearchParams params = new com.ragagent.chatpipeline.SearchParams();
        params.setQueryText(queryText);
        if (embedding != null && embedding.isArray() && !embedding.isEmpty()) {
            float[] vec = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vec[i] = (float) embedding.get(i).asDouble();
            }
            params.setQueryEmbedding(vec);
        }
        params.setVectorThreshold(req.path("vector_threshold").asDouble(0.0));
        params.setKeywordThreshold(req.path("keyword_threshold").asDouble(0.0));
        params.setMatchCount(req.path("match_count").asInt(0));
        params.setDisableKeywordsMatch(req.path("disable_keywords_match").asBoolean(false));
        params.setDisableVectorMatch(req.path("disable_vector_match").asBoolean(false));
        params.setSkipContextEnrichment(req.path("skip_context_enrichment").asBoolean(false));
        params.setKnowledgeIds(asStringList(req.get("knowledge_ids")));
        params.setTagIds(asStringList(req.get("tag_ids")));
        params.setKnowledgeBaseIds(searchKbIds);
        List<com.ragagent.retrieval.domain.SearchResult> results =
                hybridSearchService.hybridSearch(kb.getId(), params);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", results);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    private static List<String> asStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    /** 对照 ShouldBindJSON(&SearchParams) 的 400 形态（message 固定 + details 解析器原文）。 */
    private static JsonNode bindSearchParams(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("Invalid request parameters").withDetails("EOF"));
        }
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            if (node == null || !node.isObject()) {
                throw new BizException(AppError.badRequest("Invalid request parameters").withDetails("EOF"));
            }
            return node;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("Invalid request parameters")
                    .withDetails(com.ragagent.common.web.GoJsonBindError.message(rawBody, e.getMessage())));
        }
    }

    /**
     * 对照 CopyKnowledgeBase（POST /knowledge-bases/copy，Contributor；源在 body）。
     * 绑定错误是 details 形态（"Invalid request parameters" + validator/解析器原文，
     * 与 move 的 message 前缀形态刻意不同——golden 双向钉住）。create 目标在准入时保留
     * UUID（响应即返回），worker 落行。
     */
    @PostMapping("/copy")
    public ResponseEntity<?> copyKnowledgeBase(@RequestBody(required = false) String rawBody) {
        log.info("Start copying knowledge base");
        JsonNode body = bindCopyBody(rawBody);
        String sourceId = body.path("source_id").asText("");
        String targetId = body.path("target_id").asText("");
        String explicitTaskId = body.path("task_id").asText("");
        long caller = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        if (caller == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        // 对照 resolveHandlerKBAccessFor(source, Viewer)：白名单 → 404 小写 / 跨租户 403 信封
        KnowledgeBase sourceKb = resolveHandlerKbAccess(sourceId);
        if (sourceKb.getTenantId() == null || sourceKb.getTenantId() != caller) {
            throw new BizException(AppError.forbidden("No permission to copy this knowledge base"));
        }
        String taskId = explicitTaskId;
        if (taskId.isEmpty()) {
            taskId = com.ragagent.knowledge.service.KnowledgeTaskIds.generateTaskId("kb_clone", caller, sourceId);
        } else {
            requireTaskProgressTenant(taskId);
        }
        boolean create = targetId.isEmpty();
        KnowledgeBase targetKb = new KnowledgeBase();
        targetKb.setId(java.util.UUID.randomUUID().toString());
        targetKb.setTenantId(caller);
        String creatorId = "";
        if (create) {
            String uid = TenantContext.currentUserId();
            if (uid != null && !uid.startsWith("system-")) {
                targetKb.setCreatorId(uid);
                creatorId = uid;
            }
        } else {
            KnowledgeBase existing = resolveHandlerKbAccess(targetId);
            if (existing.getTenantId() == null || existing.getTenantId() != caller) {
                throw new BizException(AppError.forbidden("No permission to copy to this knowledge base"));
            }
            // 对照 EvaluateOwnershipOrRole(Admin, creator)：非创建者且非 Admin+ 拒绝
            String role = TenantContext.currentRole();
            boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
            if (!admin && (existing.getCreatorId() == null || existing.getCreatorId().isEmpty()
                    || !existing.getCreatorId().equals(TenantContext.currentUserId()))) {
                throw new BizException(AppError.forbidden("No permission to replace this knowledge base's contents"));
            }
            try {
                com.ragagent.knowledge.service.KnowledgeService.validateCloneCompatibility(sourceKb, existing);
            } catch (IllegalArgumentException e) {
                throw new BizException(AppError.badRequest(e.getMessage()));
            }
            targetKb = existing;
        }
        String reservedTargetId = targetKb.getId();
        knowledgeService.startKBClone(caller, taskId, sourceId, reservedTargetId, create, creatorId);
        var resp = new com.ragagent.knowledge.dto.KnowledgeTaskDtos.CopyKnowledgeBaseResponse(
                taskId, sourceId, reservedTargetId, "Knowledge base copy task started");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", resp);
        out.put("success", true);
        return ResponseEntity.ok(out);
    }

    /**
     * 对照 CopyKnowledgeBaseRequest 绑定：SourceID required；错误放 **details**
     * （handler 用 NewBadRequestError("Invalid request parameters").WithDetails(err.Error())）。
     */
    private static JsonNode bindCopyBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(AppError.badRequest("Invalid request parameters").withDetails("EOF"));
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("Invalid request parameters")
                    .withDetails(com.ragagent.common.web.GoJsonBindError.message(rawBody, e.getMessage())));
        }
        if (body == null || !body.isObject()) {
            throw new BizException(AppError.badRequest("Invalid request parameters").withDetails("EOF"));
        }
        JsonNode source = body.get("source_id");
        if (source == null || source.isNull() || source.asText("").isEmpty()) {
            throw new BizException(AppError.badRequest("Invalid request parameters")
                    .withDetails("Key: 'CopyKnowledgeBaseRequest.SourceID' Error:Field validation for "
                            + "'SourceID' failed on the 'required' tag"));
        }
        return body;
    }

    /**
     * 对照 resolveHandlerKBAccessFor(Viewer)：白名单 → 查行（租户无关）→ 授权。
     * 缺失 → 404 "knowledge base not found"；跨租户（org-share 未翻译）→ 403 信封
     * "Permission denied to access this knowledge base"（golden ks-copy-cross-source）。
     */
    private KnowledgeBase resolveHandlerKbAccess(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge base ID cannot be empty"));
        }
        com.ragagent.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeBases(List.of(kbId));
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        Long caller = TenantContext.currentTenantId();
        if (kb.getTenantId() == null || caller == null || !kb.getTenantId().equals(caller)) {
            throw new BizException(AppError.forbidden("Permission denied to access this knowledge base"));
        }
        return kb;
    }

    /** 对照 GetKBCloneProgress：租户隔离 + 404 "KB clone task not found"。 */
    @GetMapping("/copy/progress/{taskId}")
    public ResponseEntity<?> getKBCloneProgress(@PathVariable("taskId") String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new BizException(AppError.badRequest("Task ID cannot be empty"));
        }
        requireTaskProgressTenant(taskId);
        var progress = knowledgeService.getKBCloneProgress(taskId);
        if (progress == null) {
            throw new BizException(AppError.notFound("KB clone task not found"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", progress);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 requireTaskProgressTenant（与 KnowledgeController 的私有实现同源）。 */
    private void requireTaskProgressTenant(String taskId) {
        Long taskTenant = com.ragagent.knowledge.service.KnowledgeTaskIds.taskTenantId(taskId);
        if (taskTenant == null) {
            throw new BizException(AppError.badRequest("invalid task ID"));
        }
        Long caller = TenantContext.currentTenantId();
        if (caller == null || caller == 0) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        if (!taskTenant.equals(caller)) {
            throw new BizException(AppError.notFound("task not found"));
        }
    }

    /**
     * 对照 DuplicateKnowledgeBase（POST /{id}/duplicate，201）：路由 KBAccessRead 先拒
     * （缺失 404 小写 / 跨租户 403 信封——handler 的 "Source knowledge base not found"
     * 因此不可达），租户校验后同步克隆**设置**（名字带 " 副本"，重名去重）。
     */
    /** 重建索引（对照 Go POST /knowledge-bases/:id/rebuild-index）：索引策略变更后
     *  对 KB 内全部知识重跑处理管线；前端改策略保存后的确认框调用，读
     *  {@code data.document_count}。守卫与 duplicate 同款（KBAccessRead + 租户归属）。 */
    @PostMapping("/{id}/rebuild-index")
    public ResponseEntity<?> rebuildIndex(@PathVariable("id") String id) {
        log.info("Start rebuilding knowledge base index, ID: {}", id);
        String kbId = id == null ? "" : id;
        if (kbId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge base ID cannot be empty"));
        }
        guard.requireKbAccess(kbId);
        long callerTenant = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        KnowledgeBase sourceKb = kbService.getAllTenantById(kbId);
        if (sourceKb == null) {
            throw new BizException(AppError.notFound("Knowledge base not found"));
        }
        if (sourceKb.getTenantId() == null || sourceKb.getTenantId() != callerTenant) {
            log.warn("Knowledge base rebuild rejected: belongs to another tenant");
            throw new BizException(AppError.forbidden("No permission to rebuild this knowledge base"));
        }
        int count = knowledgeService.rebuildKnowledgeBaseIndex(kbId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("document_count", (long) count);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data);
        out.put("success", true);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<?> duplicateKnowledgeBase(@PathVariable("id") String id) {
        log.info("Start duplicating knowledge base, ID: {}", id);
        String sourceId = id == null ? "" : id;
        if (sourceId.isEmpty()) {
            throw new BizException(AppError.badRequest("Knowledge base ID cannot be empty"));
        }
        // 对照路由 KBAccessRead("id") 的中间件层拒绝
        guard.requireKbAccess(sourceId);
        long callerTenant = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        KnowledgeBase sourceKb = kbService.getAllTenantById(sourceId);
        if (sourceKb == null) {
            // 路由守卫已兜住缺失；此分支保留对照 handler 的 NotFound 文案
            throw new BizException(AppError.notFound("Source knowledge base not found"));
        }
        if (sourceKb.getTenantId() == null || sourceKb.getTenantId() != callerTenant) {
            log.warn("Knowledge base duplicate rejected: source belongs to another tenant");
            throw new BizException(AppError.forbidden("No permission to duplicate this knowledge base"));
        }
        KnowledgeBase targetKb = knowledgeService.duplicateKnowledgeBase(sourceId);
        var resp = new com.ragagent.knowledge.dto.KnowledgeTaskDtos.DuplicateKnowledgeBaseResponse(
                sourceId, targetKb.getId(), "Knowledge base duplicate created",
                // resolveKBStoreView → envDefaultStoreView：EngineType 取 envStores[0]，
                // 当前部署为空 → buildKBResponse 不写 vector_store_engine_type 键（golden 钉住）
                KnowledgeBaseResponseBuilder.build(targetKb, kbService.retrieveDriver(), false));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", resp);
        out.put("success", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    /** legacy 兼容的小工具（避免在 bind 里散落强转） */
    static final class ObjectNodeCompat {
        static void setProvider(JsonNode node, String provider) {
            ObjectMapper m = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode spc = m.createObjectNode();
            spc.put("provider", provider);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).set("storage_provider_config", spc);
        }
    }
}
