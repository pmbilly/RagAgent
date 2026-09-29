package com.ragagent.knowledge.service;

import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import org.springframework.stereotype.Component;

/**
 * knowledge 文档操作面路由（第二批）的守卫入口——语义与 {@link ChunkAccessGuard}
 * <ul>
 *   <li><b>读路由</b>（/stages /spans /preview）：KBAccessReadFromKnowledgeIDParam ——
 *       knowledge 缺失 → 404 "Knowledge not found"（大写 K）；KB 缺失 → 404
 *       "knowledge base not found"（小写 k）；跨租户 → 403 信封 "Permission denied
 *       to access this knowledge base"。</li>
 *   <li><b>写路由</b>（regenerate-summary / manual / reparse / cancel-parse / image /
 *       download）：先 {@code OwnedKnowledgeKBOrAdmin}（Admin+ 或 KB 创建者本人；
 *       knowledge 在调用者空间不存在 → <b>放行</b>，交给后续守卫出 404），再走
 *       KBAccessWrite。判定顺序 契约样例依赖，不能重排。</li>
 * </ul>
 * <p>已知收紧（与 ChunkAccessGuard 同源）：org-share / shared-agent 两条授予路径
 */
@Component
public class KnowledgeAccessGuard {

    private final ChunkAccessGuard delegate;

    public KnowledgeAccessGuard(ChunkAccessGuard delegate) {
        this.delegate = delegate;
    }

    /** knowledge（无租户过滤）缺失 → 404 "Knowledge not found"。 */
    public String kbIdFromKnowledgeParam(String knowledgeId) {
        return delegate.kbIdFromKnowledgeParam(knowledgeId);
    }

    /** 404/403 文案与知识库路由完全一致。 */
    public KnowledgeBase requireKbAccess(String kbId) {
        return delegate.requireKbAccess(kbId);
    }

    /** knowledge_id → KB.CreatorID；缺失 → 放行；
     *  非创建者且非 Admin+ → 403 纯字符串（GuardForbiddenException）。 */
    public void requireOwnedKnowledgeKb(String knowledgeId) {
        delegate.requireOwnedChunkKbByKnowledge(knowledgeId);
    }

    /** KB 行已有则直接判；缺失由调用方放行。 */
    public void requireOwnedKb(KnowledgeBase kb) {
        delegate.requireOwnedKb(kb);
    }

    /**
     * handler 内的兜底加载：按调用者租户取 knowledge，缺失 → 404 "Knowledge not found"
     * （code 1003，契约样例锁定）；附带 API-Key KB 白名单收口（getKnowledge 内置）。
     */
    public Knowledge requireKnowledgeInCallerSpace(KnowledgeService knowledgeService, String id) {
        return knowledgeService.getKnowledge(id);
    }

    /** handler 内 requireKBOwnershipOrAdmin（body/query 携带 kb_id 的批处理路由）：
     *  与路由守卫不同，这里出的是 <b>AppError 信封</b>（404 "knowledge base not found" /
     *  403 "No permission to operate on this knowledge base"），不是纯字符串。 */
    public void requireKbOwnershipOrAdminEnvelope(KnowledgeBase kb) {
        String role = com.ragagent.common.context.TenantContext.currentRole();
        String uid = com.ragagent.common.context.TenantContext.currentUserId();
        boolean admin = com.ragagent.auth.domain.TenantRole.fromString(role)
                .hasPermission(com.ragagent.auth.domain.TenantRole.ADMIN);
        if (!admin && (kb == null || kb.getCreatorId() == null || kb.getCreatorId().isEmpty()
                || !kb.getCreatorId().equals(uid))) {
            throw BizException.forbidden("No permission to operate on this knowledge base");
        }
    }
}
