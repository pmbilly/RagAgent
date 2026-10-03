package com.ragagent.wiki.controller;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import org.springframework.http.HttpStatus;

import com.ragagent.wiki.controller.WikiPageController.RawJsonError;

/**
 * WikiPageController 的 KB 访问与所有权守卫：API-Key 数据面白名单、租户判定、
 * KB 存在性/归属、写路径所有权、wiki 启用检查。判定顺序与路由中间件链一致，
 * 细节见 {@link #requireWikiKB} 的判定矩阵。
 */
final class WikiKbAccessGuard {

    private final KnowledgeBaseMapper kbMapper;

    WikiKbAccessGuard(KnowledgeBaseMapper kbMapper) {
        this.kbMapper = kbMapper;
    }

    /**
     * KB 访问与所有权判定。
     *
     * <p>判定顺序与中间件链一致（角色下限由 WebConfig 的 RbacInterceptor 先行）：</p>
     * <ol>
     *   <li>API-Key 数据面 KB 白名单：KB 受限 Key 指向白名单外 → 403；
     *       web 用户 / full-access Key 恒放行。在 KB 查找<b>之前</b>做。</li>
     *   <li>调用方租户为空/0 → <b>401</b> "Unauthorized"（同样在 KB 查找之前）。</li>
     *   <li>KB 不存在 → <b>404</b> {@code {"success":false,"error":{"code":1003,...,"message":"knowledge base not found"}}}
     *       ——走全局错误处理（BizException.notFound）。</li>
     *   <li>KB 属于别的空间 → 直接 <b>403</b>
     *       {@code {"success":false,"error":{"code":1002,...,"message":"Permission denied to
     *       access this knowledge base"}}}——跨租户授予链（org-share / shared-agent）已裁撤。</li>
     *   <li><b>写路径不经过共享授予</b>：共享场景一律 read-only——写端点一律 403 同文案，
     *       不放大权限。</li>
     *   <li>写路径：创建者本人或 Admin+，否则 <b>403</b> "must own the resource or have the required role"
     *       （文案与 {@code KnowledgeBaseController} 的所有权检查一致）。</li>
     *   <li>KB 未启用 wiki → <b>400</b> 且是 handler 直写的
     *       {@code {"error":"error code: 400, error message: Wiki feature is not enabled for this knowledge base"}}。</li>
     * </ol>
     *
     * <p>共享 agent 的 {@code agent_id}/{@code agent_source_tenant_id} 取自 query——经
     * {@code RequestContextHolder} 取当前请求，不改动端点签名。</p>
     *
     * @param write 该端点是否属于写一侧
     */
    KnowledgeBase requireWikiKB(String kbId, boolean write) {
        if (kbId == null || kbId.isEmpty()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    WikiRequestSupport.appErrorText(400, "Knowledge base ID is required"));
        }

        // API-Key 数据面 KB 白名单（在 KB 查找之前）：
        // KB 受限 Key 指向白名单外 → 403；其余主体恒放行（与 KnowledgeService.requireKb 同源收口）。
        TenantAPIKeyScope.authorizeKnowledgeBases(List.of(kbId));

        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            // caller 租户为 0 → 401（同样在 KB 查找之前）。
            throw BizException.unauthorized("Unauthorized");
        }

        // 必须先按 id 找到（查询不带空间过滤），
        // 才能把"库里没有"（404）与"不是你的"（403）区分开。
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            throw BizException.notFound("knowledge base not found");
        }

        if (!tenantId.equals(kb.getTenantId())) {
            // 空间分享裁撤：跨租户授予链（org-share / shared-agent）已退役 → 直接拒绝
            throw BizException.forbidden("Permission denied to access this knowledge base");
        }

        if (write) {
            checkOwnership(kb);
        }

        if (!kb.getIndexingStrategy().isWikiEnabled()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    WikiRequestSupport.appErrorText(400, "Wiki feature is not enabled for this knowledge base"));
        }
        return kb;
    }

    /** 所有权判定：创建者本人或 Admin+，否则 403（同 KnowledgeBaseController 的检查语义）。 */
    private static void checkOwnership(KnowledgeBase kb) {
        String role = TenantContext.currentRole();
        String uid = TenantContext.currentUserId();
        boolean admin = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
        if (!admin && (kb.getCreatorId().isEmpty() || !kb.getCreatorId().equals(uid))) {
            throw GuardForbiddenException.mustOwnResourceOrHaveRole();
        }
    }
}
