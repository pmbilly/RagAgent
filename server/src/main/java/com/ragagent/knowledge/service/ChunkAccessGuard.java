package com.ragagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import org.springframework.stereotype.Component;

/**
 * chunk 路由的 KB 访问/所有权守卫（对照 Go 中间件链的 Java 落地）。
 *
 * <p>Go 的 chunk 路由挂着两段守卫（routes_knowledge.go L27-53）：</p>
 * <ol>
 *   <li><b>所有权</b>：{@code middleware.RequireOwnershipOrRole(Admin, creatorLookup, cfg)}
 *       —— 角色达标或资源创建者本人，否则 403 纯字符串
 *       {@code "Forbidden: must own the resource or have the required role"}；
 *       资源在调用者空间里不存在时返回 ErrResourceNotFound，中间件<b>放行</b>
 *       （交给后续守卫/handler 出真正的 404）。</li>
 *   <li><b>KB 访问</b>：{@code middleware.RequireKBAccess(KBIDFromXxxParam, Viewer/Editor, ...)}
 *       —— 解析 KB（404）→ API-Key 白名单 → 同空间授予（跨空间 403 信封）。</li>
 * </ol>
 *
 * <p>Wiki 控制器（{@code WikiPageController#requireWikiKB}）已确立同样的模式；
 * chunk 与 wiki 的差别在解析链多一跳（knowledge_id/chunk_id → kb_id），且
 * by-id 路由的 ownership 查找显式重校验租户（GetChunkByIDOnly 无空间过滤）。</p>
 *
 * <p><b>判定顺序必须逐层复刻</b>（golden 依赖顺序）：</p>
 * <ul>
 *   <li>写路由（:knowledge_id）：ownership（缺失→放行）→ KB 访问（knowledge 缺失→404
 *       "Knowledge not found"；KB 缺失→404 "knowledge base not found"；跨租户→403）→
 *       handler（chunk 缺失→404 "Chunk not found"；chunk 与 knowledge_id 不符→403
 *       "No permission to access this chunk"）。</li>
 *   <li>by-id 写路由：ownership（chunk 缺失/跨租户→放行）→ KB 访问（chunk 缺失→404
 *       "Chunk not found"）→ handler。</li>
 * </ul>
 *
 * <p><b>已知收紧</b>（与 wiki 同源，约定 §9 阶段 3 差异 3）：Go 的 resolveKBAccess
 * 还认 org-share 与 shared-agent 两条路径，Java 侧 kb_shares / agent shares 未翻译，
 * 只保留"同空间"——不放行比 Go 更多的访问。</p>
 */
@Component
public class ChunkAccessGuard {

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkMapper chunkMapper;

    public ChunkAccessGuard(KnowledgeMapper knowledgeMapper,
                            KnowledgeBaseMapper kbMapper,
                            ChunkMapper chunkMapper) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 对照 {@code KBIDFromKnowledgeIDParam}（kb_access.go L89-113）：
     * {@code :knowledge_id} → knowledge（<b>无租户过滤</b>）→ kb_id。
     * knowledge 缺失 → 404 {@code "Knowledge not found"}（AppError 信封）。
     */
    public String kbIdFromKnowledgeParam(String knowledgeId) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            throw BizException.notFound("Knowledge not found");
        }
        return k.getKnowledgeBaseId();
    }

    /**
     * 对照 {@code KBIDFromChunkIDParam}（kb_access.go L115-157）：
     * {@code chunk.KnowledgeBaseID} 反范式在行上，单跳即可。
     * chunk 缺失（或 kb_id 为空的 legacy 行）→ 404 {@code "Chunk not found"}。
     * ⚠️ 这里<b>不</b>校验租户——跨租户 chunk 解析出外部 KB，由
     * {@link #requireKbAccess} 按空间比对出 403（照抄 Go 的行为分布）。
     */
    public String kbIdFromChunkParam(String chunkId) {
        Chunk c = chunkMapper.selectById(chunkId);
        if (c == null || c.getDeletedAt() != null) {
            throw BizException.notFound("Chunk not found");
        }
        if (c.getKnowledgeBaseId() == null || c.getKnowledgeBaseId().isEmpty()) {
            throw BizException.notFound("Chunk not found");
        }
        return c.getKnowledgeBaseId();
    }

    /**
     * 对照 {@code RequireKBAccess} → {@code access.ResolveKB}：
     * <ol>
     *   <li>API-Key KB 白名单（数据面收口点，与 KnowledgeService.requireKb 同源）；</li>
     *   <li>KB 缺失 → 404 {@code "knowledge base not found"}（小写 k，照抄 Go 文案）；</li>
     *   <li>跨租户 → 403 信封 {@code "Permission denied to access this knowledge base"}。</li>
     * </ol>
     * 成功返回 KB 行（handler/后续守卫复用，免二次查询）。
     */
    public KnowledgeBase requireKbAccess(String kbId) {
        TenantAPIKeyScope.authorizeKnowledgeBases(
                kbId == null ? java.util.List.of() : java.util.List.of(kbId));
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            throw BizException.notFound("knowledge base not found");
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || !tenantId.equals(kb.getTenantId())) {
            throw BizException.forbidden("Permission denied to access this knowledge base");
        }
        return kb;
    }

    /**
     * 对照 {@code OwnedChunkKBOrAdmin}（经 :knowledge_id 的 chunk 变更路由）：
     * 链路 knowledge_id → KB.CreatorID（tenant 范围内查询）。
     * knowledge 在调用者空间不存在 → <b>放行</b>（对照 ErrResourceNotFound 透传，
     * 后续 KB 访问守卫会出 404）；存在但调用者既非 Admin+ 也非创建者 → 403 纯字符串。
     */
    public void requireOwnedChunkKbByKnowledge(String knowledgeId) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, TenantContext.currentTenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            return; // ErrResourceNotFound → 中间件放行
        }
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, k.getKnowledgeBaseId())
                .eq(KnowledgeBase::getTenantId, k.getTenantId())
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            return; // ErrKnowledgeBaseNotFound → 同上
        }
        checkOwnership(kb);
    }

    /**
     * 对照 {@code OwnedChunkKBOrAdminFromChunkID}（by-id 的 questions 变更路由）：
     * 链路 chunk_id → chunk.KnowledgeID → KB.CreatorID。chunk 无空间过滤，
     * 显式重校验租户（缺失/跨租户 → 放行，与 Go 同）。
     */
    public void requireOwnedChunkKbByChunk(String chunkId) {
        Chunk c = chunkMapper.selectById(chunkId);
        if (c == null || c.getDeletedAt() != null) {
            return;
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || c.getTenantId() == null || !tenantId.equals(c.getTenantId())) {
            return; // 跨租户撞库挡在 ownership（照抄显式重校验）
        }
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, c.getKnowledgeBaseId())
                .eq(KnowledgeBase::getTenantId, tenantId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            return;
        }
        checkOwnership(kb);
    }

    /**
     * 对照 RequireOwnershipOrRole 的判定矩阵：角色 ≥ Admin 直接放行（不查 lookup）；
     * 系统管理员放行；创建者空串（tenant-owned/legacy）或非本人 → 403 纯字符串。
     */
    private static void checkOwnership(KnowledgeBase kb) {
        String role = TenantContext.currentRole();
        String uid = TenantContext.currentUserId();
        boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
        if (!admin && (kb.getCreatorId().isEmpty() || !kb.getCreatorId().equals(uid))) {
            throw GuardForbiddenException.mustOwnResourceOrHaveRole();
        }
    }

    /** 波 2：KB 已在手的所有权判定（OwnedKBOrAdmin 的路由级形态，供复用）。 */
    public void requireOwnedKb(KnowledgeBase kb) {
        if (kb == null) {
            return; // 与 ErrResourceNotFound 放行语义一致
        }
        checkOwnership(kb);
    }

    /**
     * 波 2 FAQ（OwnedKBOrAdmin 的 URL :id 直指 KB 形态）：先在<b>调用者空间</b>查 KB
     * （缺失 → 放行，交给后续 KBAccess 层出 404/403），存在则判创建者/Admin+。
     * FAQ 的写路由（POST /entry 等）用这条；判定顺序 golden 依赖，不能重排。
     */
    public void requireOwnedKbInCallerSpace(String kbId) {
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .eq(KnowledgeBase::getTenantId, TenantContext.currentTenantId())
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            return; // ErrResourceNotFound → 中间件放行
        }
        checkOwnership(kb);
    }
}
