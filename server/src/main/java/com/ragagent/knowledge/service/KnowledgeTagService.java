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
import com.ragagent.knowledge.dto.KnowledgeTagDtos.KnowledgeTagWithStats;
import com.ragagent.knowledge.dto.KnowledgeTagDtos.TagPageResult;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeTagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * KB 标签 CRUD 面（W5a，对照 Go internal/application/service/tag.go 的
 * knowledgeTagService + tag_access.go，292 行 handler + 507 行 service）。
 *
 * <h2>路由链与 Java 落地</h2>
 * Go 路由（routes_knowledge.go RegisterKnowledgeTagRoutes L264-283）：
 * GET = g.Viewer() + KBAccessRead；POST/PUT/DELETE = g.OwnedKBOrAdmin +
 * KBAccessWrite（**无角色门**）。路由级守卫在 {@link ChunkAccessGuard}
 * （{@code requireKbAccess} / {@code requireOwnedKbInCallerSpace}，控制器调用）；
 * 本类只承担 service 层语义。
 *
 * <h2>错误形态（逐条对照 Go）</h2>
 * <ul>
 *   <li>service 层 AppError → 信封（BizException 直通）：重复名 409 "标签名称已存在"、
 *       空名 400 "标签名称不能为空"、requireKBWrite 403 "无权修改该知识库"、
 *       标签不属于当前知识库 403 "标签不属于当前知识库"、
 *       force 删除仍有引用 400 "标签仍有知识或FAQ条目引用，无法删除"、
 *       排除项校验族（仅 FAQ 型 400 / 跨库 403 / 缺失 404）。</li>
 *   <li>service 层普通 error（如 GetByID 的 gorm "record not found"）→
 *       {@link IllegalStateException} → 控制器本地 handler 输出 500 code=1007
 *       "Internal server error" 无 details 键（FAQ 同款，golden 实录）。</li>
 * </ul>
 *
 * <h2>已知差异（备案）</h2>
 * <ul>
 *   <li>DeleteTag 的 force/content_only 在 Go 是 asynq 异步回收
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

    /** 对照 types.UntaggedTagName（faq.go L386）。 */
    public static final String UNTAGGED_TAG_NAME = "未分类";

    /** 对照 kb_activity.go 的 scope 常量。 */
    private static final String SCOPE_KNOWLEDGE_BASE = "knowledge_base";

    private final KnowledgeBaseService kbService;
    private final KnowledgeTagRepository tagRepo;
    private final ChunkRepository chunkRepo;
    private final AuditLogService auditService;

    public KnowledgeTagService(KnowledgeBaseService kbService,
                               KnowledgeTagRepository tagRepo,
                               ChunkRepository chunkRepo,
                               AuditLogService auditService) {
        this.kbService = kbService;
        this.tagRepo = tagRepo;
        this.chunkRepo = chunkRepo;
        this.auditService = auditService;
    }

    // ── 读：ListTags（tag.go L66-130） ──────────────────────────────────────

    /**
     * @param page/pageSize 已按 Go Pagination 归一前的原始值（null 允许）
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
        return new TagPageResult(result.total(), result.page(), result.pageSize(), data);
    }

    // ── 写：CreateTag（tag.go L133-183） ────────────────────────────────────

    public KnowledgeTag createTag(String kbId, String name, String color, int sortOrder) {
        String trimmedName = name == null ? "" : name.strip();
        if (kbId == null || kbId.isEmpty() || trimmedName.isEmpty()) {
            throw BizException.badRequest("知识库ID和标签名称不能为空");
        }
        KnowledgeBase kb = requireKb(kbId);
        requireKbWrite(kb);
        long tenantId = kb.getTenantId();

        // 同名检查（GetByName err==nil && tag!=nil → 409；gorm not-found → 继续）
        KnowledgeTag existing = tagRepo.getByName(tenantId, kb.getId(), trimmedName);
        if (existing != null) {
            throw new BizException(com.ragagent.common.error.AppError.conflict("标签名称已存在"));
        }

        OffsetDateTime now = OffsetDateTime.now();
        // "未分类" 标签排最前（对照 L164-166）
        if (UNTAGGED_TAG_NAME.equals(trimmedName)) {
            sortOrder = -1;
        }
        KnowledgeTag tag = tagRepo.createTag(tenantId, kb.getId(), trimmedName,
                color == null ? "" : color.strip(), sortOrder);
        recordKbActivity(tenantId, tag.getKnowledgeBaseId(), AuditAction.TAG_CREATED,
                "knowledge_tag", tag.getId(), details("name", tag.getName()));
        return tag;
    }

    // ── 写：UpdateTag（tag.go L186-229） ────────────────────────────────────

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

    // ── 写：DeleteTag（tag.go L234-374 + tag_access.go validateTagDeleteExclusions） ──

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

        // contentOnly：只清内容保标签。document 型走异步 knowledge 删除（Java no-op，
        // 见类注释）；否则同步删 chunks（Go 同步）。
        if (contentOnly) {
            if (isDocument(kb) && kCount > 0) {
                enqueueKnowledgeListDeleteNoop(kb, tag);
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
                enqueueKnowledgeListDeleteNoop(kb, tag);
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

    /** 对照 GetByID 的普通 error 透传：非 AppError → 控制器 plain-500 分支。 */
    private KnowledgeTag loadTagOrInternal(long tenantId, String id) {
        KnowledgeTag tag = tagRepo.getById(tenantId, id);
        if (tag == null) {
            throw new IllegalStateException("record not found");
        }
        return tag;
    }

    /** 对照 tenantID 缺失分支的 "无权修改标签" 403（post-auth 不可达，防御性保留）。 */
    private static long currentTenantIdOrForbidden() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw BizException.forbidden("无权修改标签");
        }
        return tenantId;
    }

    /** 对照 GetKnowledgeBaseByID（service 层）：缺失 → 404 "knowledge base not found"。 */
    private KnowledgeBase requireKb(String kbId) {
        KnowledgeBase kb = kbService.getById(
                TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId(), kbId);
        if (kb == null) {
            throw BizException.notFound("knowledge base not found");
        }
        return kb;
    }

    /** 对照 requireKBWrite（knowledgebase_access.go）：同租户即过（org-share 未翻译，放行不扩大）。
     *  ⚠️ Long 比较用 equals——10002 超出 Long 缓存区间，`!=` 是引用比较（约定 §5 #6）。 */
    private static void requireKbWrite(KnowledgeBase kb) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || kb.getTenantId() == null || !tenantId.equals(kb.getTenantId())) {
            throw BizException.forbidden("无权修改该知识库");
        }
    }

    /**
     * 对照 requireTagWrite（tag_access.go L12-27）：tag → KB 读取 →
     * kb.ID/kb.TenantID 与 tag 不一致 → 403 "标签不属于当前知识库" → requireKBWrite。
     *
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
            // 对照 Go：DeleteChunksByTagID err → NewInternalServerError("删除标签下的数据失败")
            throw BizException.internal("删除标签下的数据失败");
        }
        if (!deleted.isEmpty()) {
            // 对照 enqueueIndexDeleteTask：向量索引回收随检索引擎批（已知差异，WARN 备案）
            log.warn("[tag] index delete skipped (vector engine unwired): kb={} chunks={}",
                    kb.getId(), deleted.size());
        }
        log.info("Deleted {} chunks under tag {}", deleted.size(), tag.getId());
    }

    /** 对照 enqueueKnowledgeListDeleteTask：document 型的 knowledge 文件异步删除（Java no-op）。 */
    private void enqueueKnowledgeListDeleteNoop(KnowledgeBase kb, KnowledgeTag tag) {
        log.warn("[tag] knowledge list delete skipped (worker unwired): kb={} tag={}",
                kb.getId(), tag.getId());
    }

    /** details 组装（成对参数；序列化时按字母序输出，对照 Go map 的键序）。 */
    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /**
     * 对照 recordKBActivity（kb_activity.go L91-160）：尽力而为的 KB 活动审计。
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
}
