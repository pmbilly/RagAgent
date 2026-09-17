package com.ragagent.apikey.service;

import java.util.List;

import com.ragagent.apikey.domain.APIKeyCapability;
import com.ragagent.apikey.domain.TenantAPIKeyRequest;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;

/**
 * API Key 请求校验（对照 Go internal/handler/tenant.go 的
 * {@code validateTenantAPIKeyRequest} L802-827、
 * {@code validateTenantAPIKeyKnowledgeBaseIDs} L831-843、
 * {@code validateTenantAPIKeyKnowledgeBaseIDsWithLookup} L847-867）。
 *
 * <p><b>校验顺序即契约</b>（创建与更新共用，Go 侧 UpdateAPIKey 直接把
 * updateRequest 强转成 createRequest 复用同一个函数）：</p>
 * <ol>
 *   <li>{@code name} trim 后为空 → 400 {@code name is required}；</li>
 *   <li><b>full-access 立即通过</b>——注意 KB 白名单**完全不再校验**
 *       （既然授权是全量，白名单本就无意义，Go 也不会拦）。</li>
 *   <li>归一化后的能力清单为空 → 400
 *       {@code capabilities are required for scoped API keys}；</li>
 *   <li>原始清单里**非空但无法识别**的能力 → 400
 *       {@code capabilities contains an unknown capability}。
 *       与第 3 步的顺序是刻意的：{@code ["bogus"]} 归一化后为空，
 *       命中的是第 3 步的文案，而不是第 4 步。</li>
 *   <li>KB 白名单逐个查库校验归属。</li>
 * </ol>
 *
 * <p>KB 归属校验的两条错误文案必须逐字保持（前端与集成方按文案断言，Go 测试也钉了）：</p>
 * <ul>
 *   <li>查不到（不存在 / 查询出错）→ 400
 *       {@code knowledge_base_ids contains an unknown knowledge base}；</li>
 *   <li>存在但属于别的租户 → <b>403</b>
 *       {@code knowledge_base_ids contains a knowledge base outside this workspace}。</li>
 * </ul>
 */
public final class TenantAPIKeyValidator {

    /**
     * KB 归属查询端口。对照 Go 传入的
     * {@code lookup func(context.Context, string) (*types.KnowledgeBase, error)}：
     * 校验只用到"存不存在"与"属于哪个租户"两点，所以这里把返回值收敛为租户 ID
     * （{@code null} = 不存在或查询失败，两者在 Go 里走同一个分支）。
     */
    @FunctionalInterface
    public interface KnowledgeBaseLookup {
        /** @return 该知识库所属租户 ID；知识库不存在（或查询失败）返回 {@code null} */
        Long tenantIdOf(String knowledgeBaseId);
    }

    private TenantAPIKeyValidator() {
    }

    /** 对照 {@code validateTenantAPIKeyRequest}。 */
    public static void validate(TenantAPIKeyRequest req, long tenantId, KnowledgeBaseLookup lookup) {
        String name = req.name();
        if (name == null || name.trim().isEmpty()) {
            throw new BizException(AppError.validation("name is required"));
        }
        if (req.fullAccess()) {
            return;
        }
        List<String> caps = APIKeyCapability.normalizeAll(req.capabilities());
        if (caps.isEmpty()) {
            throw new BizException(
                    AppError.validation("capabilities are required for scoped API keys"));
        }
        if (req.capabilities() != null) {
            for (String cap : req.capabilities()) {
                if (cap == null || cap.trim().isEmpty()) {
                    continue;
                }
                if (APIKeyCapability.normalize(cap) == null) {
                    throw new BizException(
                            AppError.validation("capabilities contains an unknown capability"));
                }
            }
        }
        validateKnowledgeBaseIds(tenantId, req.knowledgeBaseIds(), lookup);
    }

    /** 对照 {@code validateTenantAPIKeyKnowledgeBaseIDs}：空清单直接通过。 */
    public static void validateKnowledgeBaseIds(long tenantId, List<String> knowledgeBaseIds,
                                                KnowledgeBaseLookup lookup) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return;
        }
        for (String kbId : knowledgeBaseIds) {
            if (kbId == null) {
                continue;
            }
            String trimmed = kbId.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Long ownerTenantId = lookup.tenantIdOf(trimmed);
            if (ownerTenantId == null) {
                throw new BizException(AppError.validation(
                        "knowledge_base_ids contains an unknown knowledge base"));
            }
            if (ownerTenantId != tenantId) {
                throw new BizException(AppError.forbidden(
                        "knowledge_base_ids contains a knowledge base outside this workspace"));
            }
        }
    }
}
