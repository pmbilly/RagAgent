package com.ragagent.datasource.service;

import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.mapper.KnowledgeTagRepository;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeTagService;
import org.springframework.stereotype.Component;

/**
 * 自动标签的生产实现（对照 Go {@code tag.go} 的 knowledgeTagService.FindOrCreateTagByName
 * L475-506）：同名标签存在 → 直接用；否则走 {@link KnowledgeTagService#createTag}
 * 建（其内部承载 KB 404 / 写权限 403 / 重名 409 的校验链）。
 *
 * <p><b>标签失败不致命</b>：调用方（{@code resolveAutoTagIds}）按 Go 语义 catch 后
 * warn 并继续同步（条目只是没有自动标签）。2026-09-23 走查批接线——此前
 * knowledge_tag 模块未翻译时由 {@code NoAutoTagProvider} 恒回 null 占位。</p>
 */
@Component
public class KnowledgeTagAutoTagProvider implements AutoTagProvider {

    private final KnowledgeBaseService kbService;
    private final KnowledgeTagRepository tagRepo;
    private final KnowledgeTagService tagService;

    public KnowledgeTagAutoTagProvider(KnowledgeBaseService kbService,
            KnowledgeTagRepository tagRepo, KnowledgeTagService tagService) {
        this.kbService = kbService;
        this.tagRepo = tagRepo;
        this.tagService = tagService;
    }

    @Override
    public String findOrCreateTagId(String kbId, String name) {
        String trimmed = name == null ? "" : name.strip();
        if (kbId == null || kbId.isEmpty() || trimmed.isEmpty()) {
            // 对照 L478-480：werrors.NewBadRequestError("知识库ID和标签名称不能为空")
            throw new BizException(com.ragagent.common.error.AppError.badRequest(
                    "知识库ID和标签名称不能为空"));
        }
        // 对照 L482-485：GetKnowledgeBaseByID（无租户过滤的按 id 读）
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw BizException.notFound("knowledge base not found");
        }
        // 对照 L493-497：先查现有标签（tenant + kb + name）
        KnowledgeTag existing = tagRepo.getByName(kb.getTenantId(), kbId, trimmed);
        if (existing != null) {
            return existing.getId();
        }
        // 对照 L505：CreateTag(ctx, kbID, name, "", 0)——校验链（写权限/重名）在 createTag 内
        KnowledgeTag created = tagService.createTag(kbId, trimmed, null, 0);
        return created.getId();
    }
}
