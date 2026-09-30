package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.dto.KnowledgeTagWithStats;
import com.ragagent.knowledge.dto.TagPageResult;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.repository.KnowledgeTagRepository;
import com.ragagent.knowledge.repository.FaqChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.retrieval.engine.CompositeRetrieveEngine;
import com.ragagent.common.error.AppError;
import com.ragagent.knowledge.task.KnowledgeTaskExecutor;
import com.ragagent.retrieval.engine.VectorStoreService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * <h2>路由链与 Java 落地</h2>
 * GET = g.Viewer() + KBAccessRead；POST/PUT/DELETE = g.OwnedKBOrAdmin +
 * KBAccessWrite（**无角色门**）。路由级守卫在 {@link ChunkAccessGuard}
 * （{@code requireKbAccess} / {@code requireOwnedKbInCallerSpace}，控制器调用）；
 * 本类只承担 service 层语义。
 * <ul>
 *   <li>service 层 AppError → 信封（BizException 直通）：重复名 409 "标签名称已存在"、
 *       空名 400 "标签名称不能为空"、requireKBWrite 403 "无权修改该知识库"、
 *       标签不属于当前知识库 403 "标签不属于当前知识库"、
 *       force 删除仍有引用 400 "标签仍有知识或FAQ条目引用，无法删除"、
 *       排除项校验族（仅 FAQ 型 400 / 跨库 403 / 缺失 404）。</li>
 *       {@link IllegalStateException} → 控制器本地 handler 输出 500 code=1007
 *       "Internal server error" 无 details 键（FAQ 同款，契约样例 实录）。</li>
 * </ul>
 * <h2>已知差异（备案）</h2>
 * <ul>
 *       （TypeKnowledgeListDelete / TypeIndexDelete）：Java 侧索引删除 no-op
 *       （向量索引随检索引擎批），document 型 KB 的 knowledge 文件异步删除同样
 *       不落地——HTTP 契约（{"success":true}）与 FAQ 型 chunk 的同步删除路径一致。</li>
 *   <li>org-share / shared-agent 授予路径未翻译（同 wiki/chunk 的已知收紧）。</li>
 * </ul>
 */
@Service
public class KnowledgeTagService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTagService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 未打标签的 FAQ 条目统一显示名。 */
    public static final String UNTAGGED_TAG_NAME = "未分类";

    private static final String SCOPE_KNOWLEDGE_BASE = "knowledge_base";

    private final KnowledgeBaseService kbService;
    private final KnowledgeTagRepository tagRepo;
    private final FaqChunkRepository faqChunkRepository;
    private final ChunkRepository chunkRepo;
    private final AuditLogService auditService;
    /** 向量索引回收。 */
    private final VectorStoreService vectorStore;
    /** 绑定 store 的向量写路由（绑定 KB 的索引清理走引擎口）。 */
    private final KnowledgeVectorWrites vectorWrites;
    /** 引擎路径删除需要的维度解析（照 deleteKnowledgeVectors 的取数口径）。 */
    private final ModelRuntimeFactory modelRuntimeFactory;
    /**
     * 标签下文档的批量删除。ObjectProvider：KnowledgeService 依赖面极广，
     * 延迟解析规避任何潜在的装配环。
     */
    private final ObjectProvider<KnowledgeService>
            knowledgeServiceProvider;

    private static final int INDEX_DELETE_BATCH_SIZE = 100;
    /** 后台任务执行器（统一命名与关停）。 */
    private final KnowledgeTaskExecutor taskExecutor;


    public KnowledgeTagService(FaqChunkRepository faqChunkRepository,
                               KnowledgeTaskExecutor taskExecutor, KnowledgeBaseService kbService,
                               KnowledgeTagRepository tagRepo,
                               ChunkRepository chunkRepo,
                               AuditLogService auditService,
                               VectorStoreService vectorStore,
                               KnowledgeVectorWrites vectorWrites,
                               ModelRuntimeFactory modelRuntimeFactory,
                               ObjectProvider<KnowledgeService>
                                       knowledgeServiceProvider) {
        this.faqChunkRepository = faqChunkRepository;

        this.taskExecutor = taskExecutor;
        this.kbService = kbService;
        this.tagRepo = tagRepo;
        this.chunkRepo = chunkRepo;
        this.auditService = auditService;
        this.vectorStore = vectorStore;
        this.vectorWrites = vectorWrites;
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.knowledgeServiceProvider = knowledgeServiceProvider;
    }

    // ── 读：ListTags（tag） ──────────────────────────────────────

    /**
     * @return PageResult 形态 {total, page, page_size, data:[tag+stats]}
     */
    public TagPageResult listTags(String kbId, Integer page, Integer pageSize, String keyword) {
        if (kbId == null || kbId.isEmpty()) {
            throw BizException.badRequest("知识库ID不能为空");
        }
        String trimmedKeyword = keyword == null ? "" : keyword.strip();
        KnowledgeBase kb = requireKb(kbId);
        // resolveKBReadTenant：路由级 KBAccessRead 已放行 → 同租户必过
        //（org-share 分支未翻译，见类注释）；requireKBWrite 同理。
        long tenantId = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();

        KnowledgeTagRepository.TagPage result = tagRepo.listByKb(tenantId, kb.getId(), page, pageSize, trimmedKeyword);
        List<KnowledgeTagWithStats> data = new ArrayList<>();
        if (!result.items().isEmpty()) {
            List<String> tagIds = new ArrayList<>(result.items().size());
            for (KnowledgeTag t : result.items()) {
                if (t != null) {
                    tagIds.add(t.getId());
                }
            }
            Map<String, long[]> counts = tagRepo.batchCountReferences(tenantId, kb.getId(), tagIds);
            for (KnowledgeTag t : result.items()) {
                if (t == null) {
                    continue;
                }
                long[] c = counts.getOrDefault(t.getId(), new long[]{0, 0});
                data.add(KnowledgeTagWithStats.from(t, c[0], c[1]));
            }
        }
        return new TagPageResult(data, result.page(), result.pageSize(), result.total());
    }

    // ── 写：CreateTag（tag） ────────────────────────────────────

    public KnowledgeTag createTag(String kbId, String name, String color, int sortOrder) {
        String trimmedName = name == null ? "" : name.strip();
        if (kbId == null || kbId.isEmpty() || trimmedName.isEmpty()) {
            throw BizException.badRequest("知识库ID和标签名称不能为空");
        }
        KnowledgeBase kb = requireKb(kbId);
        requireKbWrite(kb);
        long tenantId = kb.getTenantId();

        KnowledgeTag existing = tagRepo.getByName(tenantId, kb.getId(), trimmedName);
        if (existing != null) {
            throw new BizException(AppError.conflict("标签名称已存在"));
        }

        // "未分类" 标签排最前
        if (UNTAGGED_TAG_NAME.equals(trimmedName)) {
            sortOrder = -1;
        }
        KnowledgeTag tag = tagRepo.createTag(tenantId, kb.getId(), trimmedName,
                color == null ? "" : color.strip(), sortOrder);
        recordKbActivity(tenantId, tag.getKnowledgeBaseId(), AuditAction.TAG_CREATED,
                "knowledge_tag", tag.getId(), details("name", tag.getName()));
        return tag;
    }

    // ── 写：UpdateTag（tag） ────────────────────────────────────

    public KnowledgeTag updateTag(String id, String name, String color, Integer sortOrder) {
        if (id == null || id.isEmpty()) {
            throw BizException.badRequest("标签ID不能为空");
        }
        long tenantId = currentTenantIdOrForbidden();
        KnowledgeTag tag = loadTagOrInternal(tenantId, id);
        requireTagWrite(tag);

        if (name != null) {
            String newName = name.strip();
            if (newName.isEmpty()) {
                throw BizException.badRequest("标签名称不能为空");
            }
            tag.setName(newName);
        }
        if (color != null) {
            tag.setColor(color.strip());
        }
        if (sortOrder != null) {
            tag.setSortOrder(sortOrder);
        }
        tag.setUpdatedAt(OffsetDateTime.now());
        tagRepo.update(tag);
        recordKbActivity(tag.getTenantId(), tag.getKnowledgeBaseId(), AuditAction.TAG_UPDATED,
                "knowledge_tag", tag.getId(), details("name", tag.getName()));
        return tag;
    }


    /**
     * @param excludeUUIDs handler 已校验并换算过的 chunk UUID（可为空）
     */
    public void deleteTag(String id, boolean force, boolean contentOnly, List<String> excludeUUIDs) {
        if (id == null || id.isEmpty()) {
            throw BizException.badRequest("标签ID不能为空");
        }
        long tenantId = currentTenantIdOrForbidden();
        KnowledgeTag tag = loadTagOrInternal(tenantId, id);
        KnowledgeBase kb = requireTagWrite(tag);
        // validateTagDeleteExclusions 的 tag 侧等价校验已在 controller 完成（排除项
        // 按 URL :id 绑定 KB）；这里只需要 UUID 清单。

        long[] counts = tagRepo.countReferences(tenantId, tag.getKnowledgeBaseId(), tag.getId());
        long kCount = counts[0];
        long cCount = counts[1];

        // contentOnly：只清内容保标签。document 型走异步 knowledge 列表删除
        if (contentOnly) {
            if (isDocument(kb) && kCount > 0) {
                deleteKnowledgeListUnderTag(kb, tag);
            } else if (cCount > 0) {
                deleteChunksAndNoopIndex(tenantId, kb, tag, excludeUUIDs);
            }
            recordKbActivity(tenantId, tag.getKnowledgeBaseId(), AuditAction.TAG_UPDATED,
                    "knowledge_tag", tag.getId(),
                    details("name", tag.getName(), "content_cleared", true,
                            "excluded_count", excludeUUIDs.size()));
            return;
        }

        if (!force && (kCount > 0 || cCount > 0)) {
            throw BizException.badRequest("标签仍有知识或FAQ条目引用，无法删除");
        }
        if (force) {
            if (isDocument(kb) && kCount > 0) {
                deleteKnowledgeListUnderTag(kb, tag);
            } else if (cCount > 0) {
                deleteChunksAndNoopIndex(tenantId, kb, tag, excludeUUIDs);
            }
        }
        if (!excludeUUIDs.isEmpty()) {
            recordKbActivity(tenantId, tag.getKnowledgeBaseId(), AuditAction.TAG_UPDATED,
                    "knowledge_tag", tag.getId(),
                    details("name", tag.getName(), "content_cleared", true,
                            "excluded_count", excludeUUIDs.size()));
            return;
        }
        tagRepo.delete(tenantId, id);
        recordKbActivity(tenantId, tag.getKnowledgeBaseId(), AuditAction.TAG_DELETED,
                "knowledge_tag", tag.getId(),
                details("name", tag.getName(), "force", force));
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private static boolean isDocument(KnowledgeBase kb) {
        return "document".equals(kb.getType());
    }

    /** 非 AppError → 控制器 plain-500 分支。 */
    private KnowledgeTag loadTagOrInternal(long tenantId, String id) {
        KnowledgeTag tag = tagRepo.getById(tenantId, id);
        if (tag == null) {
            throw new IllegalStateException("record not found");
        }
        return tag;
    }

    private static long currentTenantIdOrForbidden() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw BizException.forbidden("无权修改标签");
        }
        return tenantId;
    }

    /** 缺失 → 404 "knowledge base not found"。 */
    private KnowledgeBase requireKb(String kbId) {
        KnowledgeBase kb = kbService.getById(
                TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId(), kbId);
        if (kb == null) {
            throw BizException.notFound("knowledge base not found");
        }
        return kb;
    }

    /** 同租户即过（org-share 未翻译，放行不扩大）。
     *  ⚠️ Long 比较用 equals——10002 超出 Long 缓存区间，`!=` 是引用比较（本仓约定 #6）。 */
    private static void requireKbWrite(KnowledgeBase kb) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || kb.getTenantId() == null || !tenantId.equals(kb.getTenantId())) {
            throw BizException.forbidden("无权修改该知识库");
        }
    }

    /**
     * tag → KB 读取 →
     * kb.ID/kb.TenantID 与 tag 不一致 → 403 "标签不属于当前知识库" → requireKBWrite。
     * @return 标签所属 KB
     */
    private KnowledgeBase requireTagWrite(KnowledgeTag tag) {
        if (tag == null) {
            throw BizException.notFound("标签不存在");
        }
        KnowledgeBase kb = requireKb(tag.getKnowledgeBaseId());
        if (kb.getId() == null || !kb.getId().equals(tag.getKnowledgeBaseId())
                || !kb.getTenantId().equals(tag.getTenantId())) {
            throw BizException.forbidden("标签不属于当前知识库");
        }
        requireKbWrite(kb);
        return kb;
    }

    /** FAQ 型（或 document 无 knowledge 引用）的同步 chunk 删除 + 索引删除 no-op。 */
    private void deleteChunksAndNoopIndex(long tenantId, KnowledgeBase kb, KnowledgeTag tag,
                                          List<String> excludeUUIDs) {
        List<String> deleted;
        try {
            deleted = chunkRepo.deleteChunksByTagId(tenantId, kb.getId(), tag.getId(), excludeUUIDs);
        } catch (RuntimeException e) {
            // DeleteChunksByTagID err → NewInternalServerError("删除标签下的数据失败")
            throw BizException.internal("删除标签下的数据失败");
        }
        if (!deleted.isEmpty()) {
            // 队列（可重试 + 租户所有权校验）；Java 单实例下用虚拟线程异步执行等价
            scheduleIndexDelete(kb, deleted);
        }
        log.info("Deleted {} chunks under tag {}", deleted.size(), tag.getId());
    }

    /**
     * 绑定外部 store 的 KB：向量行在外部店，走引擎口删除（2026-09-28 评审补接线）；
     * 引擎与维度在请求线程上解析（TenantContext 只在这里有效），解析失败只 WARN
     */
    private void scheduleIndexDelete(KnowledgeBase kb, List<String> chunkIds) {
        List<String> ids = List.copyOf(chunkIds);
        CompositeRetrieveEngine boundEngine = null;
        int dim = 0;
        try {
            boundEngine = vectorWrites.boundEngine(kb);
            if (boundEngine != null) {
                dim = modelRuntimeFactory.getEmbeddingModel(kb.getEmbeddingModelId()).getDimensions();
            }
        } catch (Exception e) {
            log.warn("[tag] bound engine resolve failed, external index rows not deleted (kb={}, chunks={}): {}",
                    kb.getId(), ids.size(), e.toString());
            return;
        }
        if (boundEngine != null) {
            final CompositeRetrieveEngine engine = boundEngine;
            final int dimensions = dim;
            final String kbType = kb.getType();
            taskExecutor.submit("tag-index-delete", () -> {
                try {
                    engine.deleteByChunkIdList(ids, dimensions, kbType);
                    log.info("[tag] deleted index rows for {} chunks (kb={}, engine)", ids.size(), kb.getId());
                } catch (Exception e) {
                    log.warn("[tag] index delete failed (kb={}, chunks={}): {}",
                            kb.getId(), ids.size(), e.toString());
                }
            });
            return;
        }
        taskExecutor.submit("tag-index-delete", () -> {
            try {
                for (int i = 0; i < ids.size(); i += INDEX_DELETE_BATCH_SIZE) {
                    int end = Math.min(i + INDEX_DELETE_BATCH_SIZE, ids.size());
                    vectorStore.deleteByChunkId(ids.subList(i, end));
                }
                log.info("[tag] deleted index rows for {} chunks (kb={})", ids.size(), kb.getId());
            } catch (RuntimeException e) {
                log.warn("[tag] index delete failed (kb={}, chunks={}): {}",
                        kb.getId(), ids.size(), e.toString());
            }
        });
    }

    /**
     * （MaxRetry 3、Timeout 2h）；Java 侧用虚拟线程异步执行等价清理（批量删除可能
     * 很慢，同步会拖住标签删除请求），失败只 WARN。
     */
    private void deleteKnowledgeListUnderTag(KnowledgeBase kb, KnowledgeTag tag) {
        List<String> knowledgeIds = tagRepo.listKnowledgeIdsByTagIds(
                tag.getTenantId(), tag.getKnowledgeBaseId(), List.of(tag.getId()));
        if (knowledgeIds.isEmpty()) {
            return;
        }
        KnowledgeService knowledgeService = knowledgeServiceProvider.getIfAvailable();
        if (knowledgeService == null) {
            log.warn("[tag] knowledge service unavailable, skip list delete: kb={} tag={}",
                    kb.getId(), tag.getId());
            return;
        }
        taskExecutor.submit("tag-knowledge-delete", () -> {
            try {
                knowledgeService.batchDeleteKnowledge(kb.getId(), knowledgeIds);
                log.info("[tag] deleted {} knowledge under tag {}", knowledgeIds.size(), tag.getId());
            } catch (RuntimeException e) {
                log.warn("[tag] knowledge list delete failed (kb={}, tag={}, count={}): {}",
                        kb.getId(), tag.getId(), knowledgeIds.size(), e.toString());
            }
        });
    }

    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /**
     * 尽力而为的 KB 活动审计。
     * ScopeType=knowledge_base、ScopeID=kbID、TargetType/TargetID 如实、
     * Outcome=success、details 按字母序（map 序列化语义）。
     */
    private void recordKbActivity(long tenantId, String kbId, String action,
                                  String targetType, String targetId, Map<String, Object> details) {
        if (kbId == null || kbId.isEmpty()) {
            return;
        }
        long tid = tenantId;
        if (tid == 0) {
            Long ctxTenant = TenantContext.currentTenantId();
            tid = ctxTenant == null ? 0L : ctxTenant;
        }
        if (tid == 0) {
            return;
        }
        String actorId = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        String actorRole = actorId.isEmpty() ? "" : TenantContext.currentRole() == null
                ? "" : TenantContext.currentRole();

        AuditLog entry = new AuditLog();
        entry.setTenantId(tid);
        entry.setActorUserId(actorId);
        entry.setActorRole(actorRole);
        entry.setAction(action);
        entry.setScopeType(SCOPE_KNOWLEDGE_BASE);
        entry.setScopeId(kbId);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setOutcome(AuditOutcome.SUCCESS);
        ObjectNode detailsNode = MAPPER.createObjectNode();
        details.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEachOrdered(e -> detailsNode.set(e.getKey(), MAPPER.valueToTree(e.getValue())));
        entry.setDetails(detailsNode);
        auditService.logBestEffort(entry);
    }

    /**
     * tag_id 是整数 → 按 seq_id 解析（查不到 → 404「标签不存在」）；否则当作 UUID 原样透传。
     */
    public String resolveTagId(String raw) {
        try {
            long seqId = Long.parseLong(raw);
            long tenantId = TenantContext.currentTenantId();
            KnowledgeTag tag = tagRepo.getBySeqId(tenantId, seqId);
            if (tag == null) {
                throw new BizException(AppError.notFound("标签不存在"));
            }
            return tag.getId();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /**
     * 校验排除条目：非法 ID → 400、他库/他租户/非 FAQ chunk → 403、缺失 → 404；
     * 返回可用的 chunk UUID 列表。
     */
    public List<String> resolveExcludeUUIDs(String kbId, List<Long> excludeIds) {
        List<String> excludeUUIDs = new ArrayList<>();
        if (excludeIds == null || excludeIds.isEmpty()) {
            return excludeUUIDs;
        }
        long tenantId = TenantContext.currentTenantId();
        Map<Long, Boolean> wanted = new HashMap<>();
        for (Long seqId : excludeIds) {
            if (seqId == null || seqId <= 0) {
                throw new BizException(AppError.badRequest("排除条目 ID 必须为正整数"));
            }
            wanted.put(seqId, Boolean.TRUE);
        }
        List<Chunk> chunks = faqChunkRepository.listChunksBySeqId(tenantId, excludeIds);
        for (Chunk chunk : chunks) {
            if (chunk == null || chunk.getSeqId() == null || !wanted.containsKey(chunk.getSeqId())) {
                continue;
            }
            if (chunk.getTenantId() == null || chunk.getTenantId() != tenantId
                    || !kbId.equals(chunk.getKnowledgeBaseId())
                    || !"faq".equals(chunk.getChunkType())) {
                throw new BizException(AppError.forbidden("排除条目不属于当前知识库"));
            }
            excludeUUIDs.add(chunk.getId());
            wanted.remove(chunk.getSeqId());
        }
        if (!wanted.isEmpty()) {
            throw new BizException(AppError.notFound("排除条目不存在"));
        }
        return excludeUUIDs;
    }
}
