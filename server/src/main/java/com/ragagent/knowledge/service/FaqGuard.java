package com.ragagent.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.dto.FaqDtos;
import com.ragagent.knowledge.mapper.KnowledgeTagMapper;
import com.ragagent.knowledge.mapper.KnowledgeTagRepository;
import org.springframework.stereotype.Component;

/**
 * FAQ 写路径的域守卫：知识库存在性/类型校验、租户越权判定、标签解析与作用域校验、
 * 入口载荷清洗。全部为纯校验/解析——不改数据行（标签的按需创建除外，见
 * {@link #findOrCreateTagByName}）。
 */
@Component
public class FaqGuard {

    private final KnowledgeService knowledgeService;
    private final KnowledgeTagMapper tagMapper;
    private final KnowledgeTagRepository tagRepository;

    public FaqGuard(KnowledgeService knowledgeService,
                    KnowledgeTagMapper tagMapper,
                    KnowledgeTagRepository tagRepository) {
        this.knowledgeService = knowledgeService;
        this.tagMapper = tagMapper;
        this.tagRepository = tagRepository;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    /**
     * 校验 KB 存在且类型为 faq，返回 KB 行。KB ID 空 → 400；不存在 → 404；
     * 非 FAQ 类型 → 400。
     */
    public KnowledgeBase validateFAQKnowledgeBase(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            throw new BizException(AppError.badRequest("知识库 ID 不能为空"));
        }
        KnowledgeBase kb = knowledgeService.findKb(kbId);
        if (kb == null || !kb.getId().equals(kbId)) {
            throw new BizException(AppError.notFound("知识库不存在"));
        }
        ensureDefaults(kb);
        if (!"faq".equals(kb.getType())) {
            throw new BizException(AppError.badRequest("仅 FAQ 知识库支持该操作"));
        }
        return kb;
    }

    /**
     * 读路径租户判定：仅同租户可读，越权 → 403（跨租户共享未启用）。
     */
    public long resolveKBReadTenant(KnowledgeBase kb) {
        Long current = TenantContext.currentTenantId();
        if (kb != null && current != null && current.equals(kb.getTenantId())) {
            return kb.getTenantId();
        }
        throw new BizException(AppError.forbidden("无权访问该知识库"));
    }

    /**
     * 写路径 KB 守卫。当前租户模型下与读守卫等价（写权限即所有权）。
     */
    public KnowledgeBase writableFAQKnowledgeBase(String kbId) {
        return validateFAQKnowledgeBase(kbId);
    }

    /**
     * FAQ 配置缺省补全的读路径形态：本仓不改 KB 行，缺省值由
     * {@link FaqChunkCodec#faqIndexMode}/{@link FaqChunkCodec#faqQuestionIndexMode}
     * 在读取时兜底，因此这里是显式空操作。
     */
    public void ensureDefaults(KnowledgeBase kb) {
        // 空实现：缺省值在读路径兜底（见方法 javadoc）
    }

    /**
     * 条目载荷清洗与校验：answer_strategy 合法值检查、去空白、normalize 后的
     * 非空断言（标准问/答案）。返回可直接落库的 metadata。
     */
    public FaqChunkMetadata sanitizeFAQEntryPayload(FaqDtos.FaqEntryPayload payload) {
        String answerStrategy = "all";
        if (payload.answerStrategy() != null && !payload.answerStrategy().isEmpty()) {
            if (FaqChunkMetadata.ANSWER_STRATEGY_ALL.equals(payload.answerStrategy())
                    || FaqChunkMetadata.ANSWER_STRATEGY_RANDOM.equals(payload.answerStrategy())) {
                answerStrategy = payload.answerStrategy();
            } else {
                throw new BizException(AppError.badRequest("answer_strategy 必须是 'all' 或 'random'"));
            }
        }
        FaqChunkMetadata meta = new FaqChunkMetadata();
        meta.standardQuestion = FaqChunkMetadata.trimSpace(payload.standardQuestion());
        meta.similarQuestions = payload.similarQuestions();
        meta.negativeQuestions = payload.negativeQuestions();
        meta.answers = payload.answers();
        meta.answerStrategy = answerStrategy;
        meta.version = 1;
        meta.source = "faq";
        meta.normalize();
        if (meta.standardQuestion == null || meta.standardQuestion.isEmpty()) {
            throw new BizException(AppError.badRequest("标准问不能为空"));
        }
        if (meta.answers == null || meta.answers.isEmpty()) {
            throw new BizException(AppError.badRequest("至少提供一个答案"));
        }
        return meta;
    }

    /**
     * 解析条目归属标签：tag_id 优先（须存在且属于本 KB），其次 tag_name（按需创建），
     * 兜底"未分类"标签。tag_id 无效 → IllegalStateException（500 plain 形态）。
     */
    public String resolveTagID(String kbId, FaqDtos.FaqEntryPayload payload) {
        long tid = tenantId();
        if (payload.tagId() != 0) {
            KnowledgeTag tag = tagMapper.selectByTenantAndSeqId(tid, payload.tagId());
            if (tag == null) {
                throw new IllegalStateException("failed to find tag by seq_id " + payload.tagId() + ": record not found");
            }
            validateFAQTagScope(tag, tid, kbId);
            return tag.getId();
        }
        if (payload.tagName() != null && !payload.tagName().isEmpty()) {
            KnowledgeTag tag = findOrCreateTagByName(kbId, payload.tagName());
            return tag.getId();
        }
        return findOrCreateTagByName(kbId, FaqDtos.UNTAGGED_TAG_NAME).getId();
    }

    /**
     * 按名取标签，不存在则创建（空描述；"未分类"固定 sort_order=-1 排最前）。
     * 这是守卫族中唯一的写操作。
     */
    public KnowledgeTag findOrCreateTagByName(String kbId, String name) {
        name = FaqChunkMetadata.trimSpace(name);
        if (kbId == null || kbId.isEmpty() || name.isEmpty()) {
            throw new BizException(AppError.badRequest("知识库ID和标签名称不能为空"));
        }
        KnowledgeBase kb = knowledgeService.findKb(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        long tid = kb.getTenantId();
        KnowledgeTag existing = tagMapper.selectByTenantKbAndName(tid, kbId, name);
        if (existing != null) {
            return existing;
        }
        int sortOrder = FaqDtos.UNTAGGED_TAG_NAME.equals(name) ? -1 : 0;
        return tagRepository.createTag(tid, kbId, name, "", sortOrder);
    }

    /**
     * 标签作用域校验：须属于当前租户且当前 KB，违者 403；标签缺失 404。
     */
    public void validateFAQTagScope(KnowledgeTag tag, long tenantId, String kbId) {
        if (tag == null) {
            throw new BizException(AppError.notFound("标签不存在"));
        }
        if (tag.getTenantId() == null || tag.getTenantId().longValue() != tenantId
                || !kbId.equals(tag.getKnowledgeBaseId())) {
            throw new BizException(AppError.forbidden("标签不属于当前知识库"));
        }
    }
}
