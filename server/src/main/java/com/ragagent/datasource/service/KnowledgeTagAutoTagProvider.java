package com.ragagent.datasource.service;

import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.repository.KnowledgeTagRepository;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeTagService;
import org.springframework.stereotype.Component;

/**
 * 自动标签的生产实现：同名标签存在 → 直接用；否则走 {@link KnowledgeTagService#createTag}
 * 建（其内部承载 KB 404 / 写权限 403 / 重名 409 的校验链）。
 *
 * <p><b>标签失败不致命</b>：调用方（{@code resolveAutoTagIds}）catch 后
 * warn 并继续同步（条目只是没有自动标签）。</p>
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
            // 入参校验：kbId 与标签名都必填
            throw new BizException(com.ragagent.common.error.AppError.badRequest(
                    "知识库ID和标签名称不能为空"));
        }
        // 先按 id 读 KB（无租户过滤）
        KnowledgeBase kb = kbService.getAllTenantById(kbId);
        if (kb == null) {
            throw BizException.notFound("knowledge base not found");
        }
        // 先查现有标签（tenant + kb + name）
        KnowledgeTag existing = tagRepo.getByName(kb.getTenantId(), kbId, trimmed);
        if (existing != null) {
            return existing.getId();
        }
        // 建标签；校验链（写权限/重名）在 createTag 内
        KnowledgeTag created = tagService.createTag(kbId, trimmed, null, 0);
        return created.getId();
    }
}
