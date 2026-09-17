package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.ragagent.knowledge.domain.KbIndexingStrategy;
import com.ragagent.knowledge.domain.KnowledgeBaseJsons;
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

/**
 * 对照 Go internal/application/service/knowledgebase.go 的 knowledgeBaseService
 * （阶段 3 子集：CRUD + pin + move-targets + 计数回填；
 * copy/duplicate/clear-contents/共享访问/审计随后续阶段）。
 *
 * 默认值链（对照 CreateKnowledgeBase L118-177）：
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

    public KnowledgeBaseService(KnowledgeBaseMapper kbMapper,
                                KnowledgeMapper knowledgeMapper,
                                ChunkMapper chunkMapper,
                                UserKbPinMapper pinMapper,
                                StorageBackendMapper storageBackendMapper,
                                TenantService tenantService,
                                UserService userService) {
        this.kbMapper = kbMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.chunkMapper = chunkMapper;
        this.pinMapper = pinMapper;
        this.storageBackendMapper = storageBackendMapper;
        this.tenantService = tenantService;
        this.userService = userService;
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

    /** 对照 CreateKnowledgeBase（含 EnsureDefaults/存储后端解析） */
    public KnowledgeBase createKnowledgeBase(KnowledgeBase kb) {
        if (kb.getId() == null || kb.getId().isEmpty()) {
            kb.setId(UUID.randomUUID().toString());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kb.setTenantId(tenantId());
        String uid = TenantContext.currentUserId();
        // 对照 L136：合成用户（API key）不记录创建者，KB 归租户所有
        if (uid != null && !uid.startsWith("system-")) {
            kb.setCreatorId(uid);
        }
        ensureDefaults(kb);
        // Go string 零值：embedding/summary_model_id 缺省 "" 入库（GORM 写零值非 NULL）
        kb.setEmbeddingModelId(kb.getEmbeddingModelId());
        kb.setSummaryModelId(kb.getSummaryModelId());
        applyTenantDefaultStorageProvider(kb);
        applyAndValidateStorageBackend(kb);
        kb.normalizeVectorStoreId();
        // 阶段 3：显式 vector_store_id 绑定不做 vector_stores 表校验（无绑定表支持），原样存储
        kbMapper.insert(kb);
        log.info("Knowledge base created successfully, ID: {}, name: {}", kb.getId(), kb.getName());
        return kb;
    }

    /** 对照 EnsureDefaults（types/knowledgebase.go L727） */
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
            kb.setIndexingStrategy(KbIndexingStrategy.defaultStrategy());
        }
        // legacy ExtractConfig.Enabled → GraphEnabled 同步
        if (kb.getExtractConfig() != null && kb.getExtractConfig().path("enabled").asBoolean(false)
                && !kb.getIndexingStrategy().isGraphEnabled()) {
            kb.getIndexingStrategy().setGraphEnabled(true);
        }
    }

    /** 对照 applyTenantDefaultStorageProvider（L213）：租户默认 → allow-list 首个 */
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

    /** 对照 storageallowlist：STORAGE_ALLOW_LIST env（默认仅 local） */
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
     * 对照 applyAndValidateStorageBackend（L179）：
     * 显式 id → 租户默认 → provider legacy alias；命中则写回 storageBackendId+provider。
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

    /** 对照 repo.GetByID：本租户 + 未删除 */
    public KnowledgeBase getById(long tid, String id) {
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getTenantId, tid)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 对照 GetKnowledgeBase：带计数回填 */
    public KnowledgeBase getKnowledgeBase(String id) {
        KnowledgeBase kb = getById(tenantId(), id);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        fillCounts(kb);
        fillPin(kb, TenantContext.currentUserId());
        return kb;
    }

    /** 对照 ListKnowledgeBases：全量（无分页）+ 计数/置顶/创建者名回填 */
    public List<KnowledgeBase> listKnowledgeBases(String creator) {
        // 对照 repository L88：Order("created_at DESC") 最新在前
        List<KnowledgeBase> all = kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getTenantId, tenantId())
                .isNull(KnowledgeBase::getDeletedAt)
                .orderByDesc(KnowledgeBase::getCreatedAt));
        String uid = TenantContext.currentUserId();
        for (KnowledgeBase kb : all) {
            fillCounts(kb);
            fillPin(kb, uid);
            // creator=mine/others 内存过滤：空 CreatorID 的行两边都不出现
            if (creator != null && !creator.isEmpty()) {
                boolean mine = "mine".equals(creator);
                boolean has = !kb.getCreatorId().isEmpty();
                if (mine != has) {
                    kb.setId(" skip");
                }
            }
            if (!kb.getCreatorId().isEmpty()) {
                var user = userService.getUserById(kb.getCreatorId());
                if (user != null) {
                    kb.setCreatorName(user.getUsername());
                }
            }
        }
        all.removeIf(kb -> " skip".equals(kb.getId()));
        return all;
    }

    /** 对照 FillKnowledgeBaseCounts：knowledge_count/chunk_count/is_processing/processing_count */
    private void fillCounts(KnowledgeBase kb) {
        Long kc = knowledgeMapper.selectCount(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kb.getId())
                .isNull(Knowledge::getDeletedAt));
        Long cc = chunkMapper.selectCount(new LambdaQueryWrapper<com.ragagent.knowledge.domain.Chunk>()
                .eq(com.ragagent.knowledge.domain.Chunk::getKnowledgeBaseId, kb.getId())
                .isNull(com.ragagent.knowledge.domain.Chunk::getDeletedAt));
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
            kb.setPinnedAt(pin.getCreatedAt());
        }
    }

    // ── 更新 / 删除 / 置顶 ────────────────────────────────────────────────

    /** 对照 UpdateKnowledgeBase 的 config 合并（service L520-560 语义） */
    public KnowledgeBase updateKnowledgeBase(KnowledgeBase existing,
                                             String name, String description,
                                             com.fasterxml.jackson.databind.JsonNode config) {
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

    private static void applyUpdateConfig(KnowledgeBase kb, com.fasterxml.jackson.databind.JsonNode config) {
        if (config.hasNonNull("chunking_config")) {
            kb.setChunkingConfig(com.ragagent.knowledge.domain.KnowledgeBaseJsons.readChunking(config.get("chunking_config")));
        }
        if (config.hasNonNull("image_processing_config")) {
            kb.setImageProcessingConfig(
                    com.ragagent.knowledge.domain.KnowledgeBaseJsons.readImageProcessing(config.get("image_processing_config")));
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
            KbIndexingStrategy strategy = KnowledgeBaseJsons.readIndexing(config.get("indexing_strategy"));
            if (!strategy.hasAnyIndexing()) {
                throw new BizException(AppError.badRequest("at least one indexing strategy must be enabled"));
            }
            kb.setIndexingStrategy(strategy);
            // 对照 L548-556：wiki/graph 联动
            if (kb.getWikiConfig() == null && strategy.isWikiEnabled()) {
                com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
                kb.setWikiConfig(m.createObjectNode());
            }
            if (kb.getExtractConfig() != null && kb.getExtractConfig().isObject()) {
                com.fasterxml.jackson.databind.node.ObjectNode ec =
                        (com.fasterxml.jackson.databind.node.ObjectNode) kb.getExtractConfig();
                ec.put("enabled", strategy.isGraphEnabled());
            }
        }
    }

    /** 对照 DeleteKnowledgeBase：阶段 3 同步级联软删（Go 为异步任务队列，响应契约一致） */
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
        chunkMapper.update(null, new UpdateWrapper<com.ragagent.knowledge.domain.Chunk>()
                .eq("knowledge_base_id", id).set("deleted_at", now));
        pinMapper.delete(new LambdaQueryWrapper<UserKbPin>()
                .eq(UserKbPin::getKnowledgeBaseId, id));
        log.info("Knowledge base deleted: {}", id);
    }

    /** 对照 TogglePinKnowledgeBase：per-(user,kb) 幂等切换 */
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
            pin.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            pinMapper.insert(pin);
        }
        fillCounts(kb);
        fillPin(kb, uid);
        return kb;
    }

    /** 对照 ListMoveTargets：同 type + 同 embedding_model_id + 非临时 + 排除自身 */
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
                // 对照 ListKnowledgeBases 复用：created_at DESC
                .orderByDesc(KnowledgeBase::getCreatedAt));
    }
}
