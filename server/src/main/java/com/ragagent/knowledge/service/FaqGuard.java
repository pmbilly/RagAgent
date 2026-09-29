package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.dto.FaqEntryDtos;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.mapper.FaqChunkRepository;
import com.ragagent.knowledge.mapper.ChunkRepository;
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
    private final ChunkRepository chunkRepository;
    private final FaqChunkRepository faqChunkRepository;

    public FaqGuard(KnowledgeService knowledgeService,
                    KnowledgeTagMapper tagMapper,
                    KnowledgeTagRepository tagRepository,
                    ChunkRepository chunkRepository,
                    FaqChunkRepository faqChunkRepository) {
        this.knowledgeService = knowledgeService;
        this.tagMapper = tagMapper;
        this.tagRepository = tagRepository;
        this.chunkRepository = chunkRepository;
        this.faqChunkRepository = faqChunkRepository;
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
    public FaqChunkMetadata sanitizeFAQEntryPayload(FaqEntryDtos.FaqEntryPayload payload) {
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
    public String resolveTagID(String kbId, FaqEntryDtos.FaqEntryPayload payload) {
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
        return findOrCreateTagByName(kbId, FaqEntryDtos.UNTAGGED_TAG_NAME).getId();
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
        int sortOrder = FaqEntryDtos.UNTAGGED_TAG_NAME.equals(name) ? -1 : 0;
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

    // ── 批量写计划（命令面与导入面共享） ──
    /** 按条目 ID 装载本 KB 的 FAQ chunk 行；ID 非法 400、他库/他租户 403、缺失 404。 */
    public Map<Long, Chunk> loadFAQWriteChunks(KnowledgeBase kb, List<Long> ids) {
        Set<Long> wanted = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new BizException(AppError.badRequest("FAQ 条目 ID 必须为正整数"));
            }
            wanted.add(id);
        }
        Map<Long, Chunk> result = new LinkedHashMap<>();
        if (wanted.isEmpty()) {
            return result;
        }
        List<Chunk> chunks = faqChunkRepository.listChunksBySeqId(kb.getTenantId(), new ArrayList<>(wanted));
        for (Chunk chunk : chunks) {
            if (chunk == null || !wanted.contains(chunk.getSeqId())) {
                continue;
            }
            if (chunk.getTenantId() == null || kb.getTenantId() == null
                    || chunk.getTenantId().longValue() != kb.getTenantId().longValue()
                    || !kb.getId().equals(chunk.getKnowledgeBaseId())
                    || !"faq".equals(chunk.getChunkType())) {
                throw new BizException(AppError.forbidden("FAQ 条目不属于当前知识库"));
            }
            result.put(chunk.getSeqId(), chunk);
        }
        if (result.size() != wanted.size()) {
            throw new BizException(AppError.notFound("FAQ 条目不存在"));
        }
        return result;
    }
    public static final class FaqFieldPlan {
        final Map<Long, Chunk> chunks;
        final Map<String, Chunk> chunksById = new LinkedHashMap<>();
        final Map<Long, KnowledgeTag> tags = new LinkedHashMap<>();
        final List<String> excludeIds = new ArrayList<>();

        FaqFieldPlan(Map<Long, Chunk> chunks) {
            this.chunks = chunks;
        }
    }

    /** 批量字段更新的写计划：按 ID/排除/标签三个维度装载 chunk 与标签并做作用域校验。 */
    public FaqFieldPlan planFAQFields(KnowledgeBase kb, FaqEntryDtos.FaqEntryFieldsBatchUpdate req) {
        List<Long> ids = new ArrayList<>();
        if (req.byId() != null) {
            ids.addAll(sortedIds(req.byId().keySet()));
        }
        if (req.excludeIds() != null) {
            ids.addAll(req.excludeIds());
        }
        Map<Long, Chunk> chunks = loadFAQWriteChunks(kb, ids);
        FaqFieldPlan plan = new FaqFieldPlan(chunks);
        for (Chunk chunk : chunks.values()) {
            plan.chunksById.put(chunk.getId(), chunk);
        }
        if (req.excludeIds() != null) {
            for (Long id : req.excludeIds()) {
                Chunk c = chunks.get(id);
                plan.excludeIds.add(c == null ? null : c.getId());
            }
        }
        Set<Long> tagIds = new LinkedHashSet<>();
        if (req.byTag() != null) {
            for (Long id : req.byTag().keySet()) {
                if (id == null || id <= 0) {
                    throw new BizException(AppError.badRequest("标签 ID 必须为正整数"));
                }
                tagIds.add(id);
            }
        }
        if (req.byTag() != null) {
            for (FaqEntryDtos.FaqEntryFieldsUpdate update : req.byTag().values()) {
                if (update.tagId() != null && update.tagId() > 0) {
                    tagIds.add(update.tagId());
                }
            }
        }
        if (req.byId() != null) {
            for (FaqEntryDtos.FaqEntryFieldsUpdate update : req.byId().values()) {
                if (update.tagId() != null && update.tagId() > 0) {
                    tagIds.add(update.tagId());
                }
            }
        }
        if (!tagIds.isEmpty()) {
            List<KnowledgeTag> tags = tagMapper.selectByTenantAndSeqIds(kb.getTenantId(), new ArrayList<>(tagIds));
            for (KnowledgeTag tag : tags) {
                if (tag == null || !tagIds.contains(tag.getSeqId())) {
                    continue;
                }
                validateFAQTagScope(tag, kb.getTenantId(), kb.getId());
                plan.tags.put(tag.getSeqId(), tag);
            }
            for (Long id : sortedIds(tagIds)) {
                if (!plan.tags.containsKey(id)) {
                    throw new BizException(AppError.notFound("标签 " + id + " 不存在"));
                }
            }
        }
        return plan;
    }

    /** 去重升序排序。 */
    public static List<Long> sortedIds(Set<Long> values) {
        List<Long> ids = new ArrayList<>(values);
        ids.sort(Comparator.naturalOrder());
        return ids;
    }
}
