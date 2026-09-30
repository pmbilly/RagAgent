package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.UserService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.StorageBackend;
import com.ragagent.knowledge.domain.UserKbPin;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.mapper.StorageBackendMapper;
import com.ragagent.knowledge.mapper.UserKbPinMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.retrieval.engine.RetrieveEngineException;
import com.ragagent.retrieval.engine.RetrieveEngineRegistry;
import com.ragagent.retrieval.engine.TenantStoreOwnership;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.retrieval.engine.RetrieveEngineFactories;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseImageProcessingConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * （覆盖 CRUD + pin + move-targets + 计数回填；
 * copy/duplicate/clear-contents/共享访问/审计随后续阶段）。
 * 默认值链：
 *  EnsureDefaults → applyTenantDefaultStorageProvider → applyAndValidateStorageBackend
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ChunkMapper chunkMapper;
    private final UserKbPinMapper pinMapper;
    private final StorageBackendMapper storageBackendMapper;
    private final TenantService tenantService;
    private final UserService userService;
    private final String retrieveDriver;
    private final RetrieveEngineRegistry retrieveEngineRegistry;
    private final TenantStoreOwnership storeOwnership;

    public KnowledgeBaseService(KnowledgeBaseMapper kbMapper,
                                KnowledgeMapper knowledgeMapper,
                                ChunkMapper chunkMapper,
                                UserKbPinMapper pinMapper,
                                StorageBackendMapper storageBackendMapper,
                                TenantService tenantService,
                                UserService userService,
                                RetrieveEngineRegistry retrieveEngineRegistry,
                                TenantStoreOwnership storeOwnership) {
        this.kbMapper = kbMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.chunkMapper = chunkMapper;
        this.pinMapper = pinMapper;
        this.storageBackendMapper = storageBackendMapper;
        this.tenantService = tenantService;
        this.userService = userService;
        this.retrieveEngineRegistry = retrieveEngineRegistry;
        this.storeOwnership = storeOwnership;
        String env = System.getenv("RETRIEVE_DRIVER");
        this.retrieveDriver = env == null || env.isBlank() ? "postgres" : env;
    }

    public String retrieveDriver() {
        return retrieveDriver;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    // ── 创建 ─────────────────────────────────────────────────────────────
    public KnowledgeBase createKnowledgeBase(KnowledgeBase kb) {
        if (kb.getId() == null || kb.getId().isEmpty()) {
            kb.setId(UUID.randomUUID().toString());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kb.setTenantId(tenantId());
        String uid = TenantContext.currentUserId();
        // 合成用户（API key）不记录创建者，KB 归租户所有
        if (uid != null && !uid.startsWith("system-")) {
            kb.setCreatorId(uid);
        }
        ensureDefaults(kb);
        // 下面两行是 null → "" 规整：setter 承担归一，读回再写回即完成（非冗余赋值）
        kb.setEmbeddingModelId(kb.getEmbeddingModelId());
        kb.setSummaryModelId(kb.getSummaryModelId());
        applyTenantDefaultStorageProvider(kb);
        applyAndValidateStorageBackend(kb);
        kb.normalizeVectorStoreId();
        // 2026-09-25 接线批：vector_store_id 绑定校验
        if (kb.hasVectorStore()) {
            validateVectorStoreBinding(tenantId(), kb.getVectorStoreId());
        }
        kbMapper.insert(kb);
        log.info("Knowledge base created successfully, ID: {}, name: {}", kb.getId(), kb.getName());
        return kb;
    }

    /**
     * 走
     * {@code retriever.VerifyBinding} 让归属 + 注册表哨兵层级保持单源。服务层负责：
     * ①畸形 UUID 快拒（省一次 DB 往返，也挡 "' OR 1=1 --" 式类型混淆输入）；②哨兵 →
     * 用户可见的 2200/2201 文案（不含 store UUID——UUID 只进结构化日志，经 sanitizer）。
     */
    public void validateVectorStoreBinding(long tenantId, String storeId) {
        String sanitized = LogSanitizer.sanitize(storeId);
        try {
            UUID.fromString(storeId);
        } catch (IllegalArgumentException e) {
            log.warn("[kb.create] vector store id is not a valid UUID: tenant_id={} store_id={}",
                    tenantId, sanitized);
            throw new BizException(new AppError(
                    ErrorCode.VECTOR_STORE_BINDING_INVALID.value(),
                    "vector store not found", null, 400));
        }
        try {
            RetrieveEngineFactories.verifyBinding(
                    retrieveEngineRegistry, storeOwnership, tenantId, storeId);
        } catch (RuntimeException err) {
            if (err instanceof RetrieveEngineException re) {
                switch (re.kind()) {
                    case VECTOR_STORE_FORBIDDEN:
                        log.warn("[kb.create] vector store not owned by tenant: tenant_id={} "
                                + "store_id={}", tenantId, sanitized);
                        throw new BizException(new AppError(
                                ErrorCode.VECTOR_STORE_BINDING_INVALID.value(),
                                "vector store not found", null, 400));
                    case VECTOR_STORE_NOT_FOUND:
                    case VECTOR_STORE_UNAVAILABLE:
                        log.warn("[kb.create] vector store currently unavailable: tenant_id={} "
                                + "store_id={}", tenantId, sanitized);
                        throw new BizException(new AppError(
                                ErrorCode.VECTOR_STORE_UNAVAILABLE.value(),
                                "vector store is currently unavailable; check its connection "
                                        + "configuration", null, 400));
                    default:
                        break;
                }
            }
            if (RetrieveEngineException.isCancellation(err)) {
                throw err;
            }
            log.error("[kb.create] binding verification failed: tenant_id={} store_id={} err={}",
                    tenantId, sanitized, err.toString());
            throw new BizException(AppError.internal("failed to verify vector store binding"));
        }
    }
    static void ensureDefaults(KnowledgeBase kb) {
        if (kb.getType().isEmpty()) {
            kb.setType("document");
        }
        if (!"faq".equals(kb.getType())) {
            kb.setFaqConfig(null);
        }
        if (!"document".equals(kb.getType())) {
            kb.setAutoTagConfig(null);
        }
        // IndexingStrategy 零值 → 默认（vector+keyword）
        if (kb.getIndexingStrategy().isZero()) {
            kb.setIndexingStrategy(KnowledgeBaseIndexingStrategy.defaultStrategy());
        }
        // legacy ExtractConfig.Enabled → GraphEnabled 同步
        if (kb.getExtractConfig() != null && kb.getExtractConfig().path("enabled").asBoolean(false)
                && !kb.getIndexingStrategy().isGraphEnabled()) {
            kb.getIndexingStrategy().setGraphEnabled(true);
        }
    }

    /** 租户默认 → allow-list 首个 */
    private void applyTenantDefaultStorageProvider(KnowledgeBase kb) {
        if (!kb.getStorageProvider().isEmpty()) {
            return;
        }
        String provider = "";
        Tenant tenant = tenantService.getTenantById(tenantId());
        if (tenant != null && tenant.getStorageEngineConfig() != null
                && tenant.getStorageEngineConfig().path("default_provider").isTextual()) {
            provider = tenant.getStorageEngineConfig().path("default_provider").asText().toLowerCase().trim();
        }
        if (provider.isEmpty() || !isStorageAllowed(provider)) {
            provider = firstAllowedStorage();
        }
        if (provider.isEmpty()) {
            return;
        }
        kb.setStorageProvider(provider);
    }

    /** STORAGE_ALLOW_LIST env（默认仅 local） */
    static boolean isStorageAllowed(String provider) {
        String raw = System.getenv("STORAGE_ALLOW_LIST");
        if (raw == null || raw.isBlank()) {
            return "local".equals(provider);
        }
        for (String p : raw.split(",")) {
            if (p.trim().equalsIgnoreCase(provider)) {
                return true;
            }
        }
        return false;
    }

    static String firstAllowedStorage() {
        String raw = System.getenv("STORAGE_ALLOW_LIST");
        if (raw == null || raw.isBlank()) {
            return "local";
        }
        for (String p : raw.split(",")) {
            if (!p.trim().isEmpty()) {
                return p.trim().toLowerCase();
            }
        }
        return "";
    }

    /**
     * * 显式 id → 租户默认 → provider legacy alias；命中则写回 storageBackendId+provider。
     */
    private void applyAndValidateStorageBackend(KnowledgeBase kb) {
        Tenant tenant = tenantService.getTenantById(tenantId());
        if (tenant == null) {
            throw new BizException(AppError.badRequest("workspace context missing"));
        }
        String id = kb.getStorageBackendId() == null ? "" : kb.getStorageBackendId().trim();
        if (id.isEmpty() && tenant.getDefaultStorageBackendId() != null
                && !tenant.getDefaultStorageBackendId().trim().isEmpty()) {
            id = tenant.getDefaultStorageBackendId().trim();
        }
        StorageBackend backend = null;
        if (!id.isEmpty()) {
            backend = storageBackendMapper.selectOne(new LambdaQueryWrapper<StorageBackend>()
                    .eq(StorageBackend::getId, id)
                    .eq(StorageBackend::getTenantId, tenantId())
                    .isNull(StorageBackend::getDeletedAt)
                    .last("LIMIT 1"));
            if (backend == null) {
                throw new BizException(AppError.badRequest("storage backend is unavailable"));
            }
        } else {
            String provider = kb.getStorageProvider();
            if (!provider.isEmpty()) {
                backend = storageBackendMapper.selectOne(new LambdaQueryWrapper<StorageBackend>()
                        .eq(StorageBackend::getTenantId, tenantId())
                        .eq(StorageBackend::getProvider, provider)
                        .eq(StorageBackend::isLegacyAlias, true)
                        .isNull(StorageBackend::getDeletedAt)
                        .last("LIMIT 1"));
            }
        }
        if (backend == null) {
            return;
        }
        kb.setStorageBackendId(backend.getId());
        kb.setStorageProvider(backend.getProvider());
    }

    // ── 查询 ─────────────────────────────────────────────────────────────

    /** 本租户 + 未删除 */
    public KnowledgeBase getById(long tid, String id) {
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getTenantId, tid)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
    }

    /**
     * * move/copy/duplicate 的 handler 链先按 id 找行、再自己判租户——跨租户行"存在"是
     * 403/404 分歧的前提，不能提前按租户收敛。
     */
    public KnowledgeBase getAllTenantById(String id) {
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, id)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb != null) {
            ensureDefaults(kb);
        }
        return kb;
    }

    /** 带计数回填 */
    public KnowledgeBase getKnowledgeBase(String id) {
        KnowledgeBase kb = getById(tenantId(), id);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        fillCounts(kb);
        fillPin(kb, TenantContext.currentUserId());
        return kb;
    }

    /**
     * * tenant \u5168\u91cf + is_temporary=false + created_at DESC\u3002\u4e0d\u56de\u586b pin/creator_name/
     * share_count\uff08Go \u8be5\u8def\u5f84\u4e0d\u505a\uff09\uff1b\u8ba1\u6570\u53ea\u8986\u76d6\u7c7b\u578b\u76f8\u5173\u5b57\u6bb5\uff08document\u2192
     * knowledge_count\u3001faq\u2192chunk_count\uff09\uff0cprocessing \u72b6\u6001\u53ea\u7b97 pending/processing\u3002
     */
    public List<KnowledgeBase> listKnowledgeBasesByTenantId(long tenantId) {
        List<KnowledgeBase> all = kbMapper.selectList(
                new QueryWrapper<KnowledgeBase>()
                        .eq("tenant_id", tenantId)
                        .eq("is_temporary", false)
                        .isNull("deleted_at")
                        .orderByDesc("created_at"));
        for (KnowledgeBase kb : all) {
            // 对零值策略原样返回（capabilities 全假），见 W5sSharedAgentContractTest.kbListAgentBranch。
            fillCountsForSharedList(kb);
        }
        return all;
    }

    /** 只覆盖类型相关计数字段，另一个保留库表原值。 */
    private void fillCountsForSharedList(KnowledgeBase kb) {
        if ("document".equals(kb.getType())) {
            Long kc = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getKnowledgeBaseId, kb.getId())
                    .isNull(Knowledge::getDeletedAt));
            kb.setKnowledgeCount(kc == null ? 0 : kc);
        } else if ("faq".equals(kb.getType())) {
            Long cc = chunkMapper.selectCount(new LambdaQueryWrapper<Chunk>()
                    .eq(Chunk::getKnowledgeBaseId, kb.getId())
                    .isNull(Chunk::getDeletedAt));
            kb.setChunkCount(cc == null ? 0 : cc);
        }
        Long pc = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kb.getId())
                .isNull(Knowledge::getDeletedAt)
                .in(Knowledge::getParseStatus, Knowledge.PARSE_PENDING, Knowledge.PARSE_PROCESSING));
        kb.setProcessingCount(pc == null ? 0 : pc);
        kb.setIsProcessing(pc != null && pc > 0);
    }

    /** 全量（无分页）+ 计数/置顶/创建者名回填 */
    public List<KnowledgeBase> listKnowledgeBases(String creator) {
        // Order("created_at DESC") 最新在前
        List<KnowledgeBase> all = kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getTenantId, tenantId())
                .isNull(KnowledgeBase::getDeletedAt)
                .orderByDesc(KnowledgeBase::getCreatedAt));
        String uid = TenantContext.currentUserId();
        List<KnowledgeBase> out = new ArrayList<>(all.size());
        for (KnowledgeBase kb : all) {
            // 2026-09-25 线上抓回：缺这一步 ⇒ capabilities() 全假 ⇒ quick-answer 能力过滤
            // 丢弃 KB ⇒ /agents/{id}/suggested-questions 无范围时返回空数组）
            ensureDefaults(kb);
            fillCounts(kb);
            fillPin(kb, uid);
            // creator=mine/others 内存过滤：空 CreatorID 的行两边都不出现
            if (creator != null && !creator.isEmpty()) {
                boolean mine = "mine".equals(creator);
                boolean has = !kb.getCreatorId().isEmpty();
                if (mine != has) {
                    continue;
                }
            }
            if (!kb.getCreatorId().isEmpty()) {
                var user = userService.getUserById(kb.getCreatorId());
                if (user != null) {
                    kb.setCreatorName(user.getUsername());
                }
            }
            out.add(kb);
        }
        return out;
    }

    /** knowledge_count/chunk_count/is_processing/processing_count */
    private void fillCounts(KnowledgeBase kb) {
        Long kc = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kb.getId())
                .isNull(Knowledge::getDeletedAt));
        Long cc = chunkMapper.selectCount(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKnowledgeBaseId, kb.getId())
                .isNull(Chunk::getDeletedAt));
        Long pc = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kb.getId())
                .isNull(Knowledge::getDeletedAt)
                .in(Knowledge::getParseStatus,
                        Knowledge.PARSE_PENDING, Knowledge.PARSE_PROCESSING, Knowledge.PARSE_FINALIZING));
        kb.setKnowledgeCount(kc == null ? 0 : kc);
        kb.setChunkCount(cc == null ? 0 : cc);
        kb.setProcessingCount(pc == null ? 0 : pc);
        kb.setIsProcessing(pc != null && pc > 0);
        kb.setShareCount(0); // 共享（kb_shares）随组织模块翻译
    }

    private void fillPin(KnowledgeBase kb, String uid) {
        if (uid == null || uid.isEmpty()) {
            return;
        }
        UserKbPin pin = pinMapper.selectOne(new LambdaQueryWrapper<UserKbPin>()
                .eq(UserKbPin::getUserId, uid)
                .eq(UserKbPin::getKnowledgeBaseId, kb.getId())
                .last("LIMIT 1"));
        if (pin != null) {
            kb.setIsPinned(true);
            kb.setPinnedAt(pin.getPinnedAt());
        }
    }

    // ── 更新 / 删除 / 置顶 ────────────────────────────────────────────────

    public KnowledgeBase updateKnowledgeBase(KnowledgeBase existing,
                                             String name, String description,
                                             JsonNode config) {
        if (name != null && !name.isEmpty()) {
            existing.setName(name);
        }
        if (description != null) {
            existing.setDescription(description);
        }
        if (config != null) {
            applyUpdateConfig(existing, config);
        }
        existing.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        kbMapper.updateById(existing);
        fillCounts(existing);
        fillPin(existing, TenantContext.currentUserId());
        return existing;
    }

    private static void applyUpdateConfig(KnowledgeBase kb, JsonNode config) {
        if (config.hasNonNull("chunking_config")) {
            kb.setChunkingConfig(KnowledgeBaseChunkingConfig.from(config.get("chunking_config")));
        }
        if (config.hasNonNull("image_processing_config")) {
            kb.setImageProcessingConfig(
                    KnowledgeBaseImageProcessingConfig.from(config.get("image_processing_config")));
        }
        if (config.hasNonNull("faq_config")) {
            kb.setFaqConfig(config.get("faq_config"));
        }
        if (config.hasNonNull("wiki_config")) {
            kb.setWikiConfig(config.get("wiki_config"));
        }
        if (config.hasNonNull("auto_tag_config")) {
            kb.setAutoTagConfig(config.get("auto_tag_config"));
        }
        // indexing_strategy：指针语义 nil=不变；HasAnyIndexing 为 false → 400
        if (config.has("indexing_strategy") && config.get("indexing_strategy") != null
                && config.get("indexing_strategy").isObject()) {
            KnowledgeBaseIndexingStrategy strategy = KnowledgeBaseIndexingStrategy.from(config.get("indexing_strategy"));
            if (!strategy.hasAnyIndexing()) {
                throw new BizException(AppError.badRequest("at least one indexing strategy must be enabled"));
            }
            kb.setIndexingStrategy(strategy);
            // wiki/graph 联动
            if (kb.getWikiConfig() == null && strategy.isWikiEnabled()) {
                ObjectMapper m = new ObjectMapper();
                kb.setWikiConfig(m.createObjectNode());
            }
            if (kb.getExtractConfig() != null && kb.getExtractConfig().isObject()) {
                ObjectNode ec =
                        (ObjectNode) kb.getExtractConfig();
                ec.put("enabled", strategy.isGraphEnabled());
            }
        }
    }

    public void deleteKnowledgeBase(String id) {
        KnowledgeBase kb = getById(tenantId(), id);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        kbMapper.update(null, new UpdateWrapper<KnowledgeBase>()
                .eq("id", id).set("deleted_at", now));
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("knowledge_base_id", id).set("deleted_at", now));
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .eq("knowledge_base_id", id).set("deleted_at", now));
        pinMapper.delete(new LambdaQueryWrapper<UserKbPin>()
                .eq(UserKbPin::getKnowledgeBaseId, id));
        log.info("Knowledge base deleted: {}", id);
    }

    /** per-(user,kb) 幂等切换 */
    public KnowledgeBase togglePin(String id) {
        KnowledgeBase kb = getById(tenantId(), id);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        String uid = TenantContext.currentUserId();
        UserKbPin existing = pinMapper.selectOne(new LambdaQueryWrapper<UserKbPin>()
                .eq(UserKbPin::getUserId, uid)
                .eq(UserKbPin::getKnowledgeBaseId, id)
                .last("LIMIT 1"));
        if (existing != null) {
            pinMapper.delete(new LambdaQueryWrapper<UserKbPin>()
                    .eq(UserKbPin::getUserId, uid)
                    .eq(UserKbPin::getKnowledgeBaseId, id));
        } else {
            UserKbPin pin = new UserKbPin();
            pin.setUserId(uid);
            pin.setKnowledgeBaseId(id);
            pin.setPinnedAt(OffsetDateTime.now(ZoneOffset.UTC));
            pinMapper.insert(pin);
        }
        fillCounts(kb);
        fillPin(kb, uid);
        return kb;
    }

    /** 同 type + 同 embedding_model_id + 非临时 + 排除自身 */
    public List<KnowledgeBase> listMoveTargets(String id) {
        KnowledgeBase source = getById(tenantId(), id);
        if (source == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        return kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getTenantId, tenantId())
                .eq(KnowledgeBase::getType, source.getType())
                .eq(KnowledgeBase::getEmbeddingModelId,
                        source.getEmbeddingModelId() == null ? "" : source.getEmbeddingModelId())
                .eq(KnowledgeBase::isIsTemporary, false)
                .ne(KnowledgeBase::getId, id)
                .isNull(KnowledgeBase::getDeletedAt)
                // created_at DESC
                .orderByDesc(KnowledgeBase::getCreatedAt));
    }
}
