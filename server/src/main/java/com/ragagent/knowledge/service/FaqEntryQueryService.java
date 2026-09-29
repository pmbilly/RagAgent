package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.dto.FaqSearchDtos;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.dto.FaqEntryDtos.FaqEntry;
import com.ragagent.knowledge.dto.FaqEntryDtos.FaqEntryPage;
import com.ragagent.knowledge.dto.FaqEntryDtos.FaqExportEntry;
import com.ragagent.knowledge.mapper.FaqChunkRepository;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.chatpipeline.SearchParams;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.knowledge.mapper.KnowledgeTagMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ragagent.knowledge.security.FaqGuard;

/**
 * FAQ 条目查询面：分页列表、详情、导出（CSV/JSON）与混合检索。
 * 读路径经 {@link FaqGuard} 做存在性与租户校验，条目视图经 {@link FaqChunkCodec} 转换。
 */
@Service
public class FaqEntryQueryService {

    private static final Logger log = LoggerFactory.getLogger(FaqEntryQueryService.class);

    private final ChunkRepository chunkRepository;
    private final FaqChunkRepository faqChunkRepository;
    private final KnowledgeTagMapper tagMapper;
    private final HybridSearchService hybridSearchService;
    private final FaqGuard faqGuard;
    private final FaqChunkCodec faqChunkCodec;
    private final FaqIndexWriter faqIndexWriter;

    public FaqEntryQueryService(ChunkRepository chunkRepository,
                                KnowledgeTagMapper tagMapper,
                                HybridSearchService hybridSearchService,
                                FaqGuard faqGuard,
                                FaqChunkCodec faqChunkCodec,
                                FaqIndexWriter faqIndexWriter,
                                FaqChunkRepository faqChunkRepository) {
        this.chunkRepository = chunkRepository;
        this.faqChunkRepository = faqChunkRepository;
        this.tagMapper = tagMapper;
        this.hybridSearchService = hybridSearchService;
        this.faqGuard = faqGuard;
        this.faqChunkCodec = faqChunkCodec;
        this.faqIndexWriter = faqIndexWriter;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    // ══════════════════ 列表 ═══════════════════════════════════════════

    /**
     * 分页已由 handler 解析钳位。
     */
    public FaqEntryPage listEntries(String kbId, int page, int pageSize,
                                           List<String> tagUuids, long legacyTagSeqId,
                                           String keyword, String searchField,
                                           String sortOrder, Boolean isEnabled) {
        keyword = FaqChunkMetadata.trimSpace(keyword);

        KnowledgeBase kb = faqGuard.validateFAQKnowledgeBase(kbId);
        long effectiveTenant = faqGuard.resolveKBReadTenant(kb);

        Knowledge faqKnowledge = faqIndexWriter.findFAQKnowledge(effectiveTenant, kb.getId());
        List<FaqEntry> entries = new ArrayList<>();
        long total = 0;
        if (faqKnowledge != null) {
            List<String> tags = tagUuids == null ? new ArrayList<>() : new ArrayList<>(tagUuids);
            if (tags.isEmpty() && legacyTagSeqId > 0) {
                KnowledgeTag tag = tagMapper.selectByTenantAndSeqId(effectiveTenant, legacyTagSeqId);
                if (tag == null) {
                    throw new BizException(AppError.notFound("标签不存在"));
                }
                tags = List.of(tag.getId());
            }
            ChunkRepository.ChunkPage result = chunkRepository.listPagedChunksByKnowledgeId(
                    effectiveTenant, faqKnowledge.getId(), (page - 1) * pageSize, pageSize,
                    List.of("faq"), tags, keyword, searchField, sortOrder, "faq", isEnabled);
            total = result.total();

            Map<String, String> tagNameMap = new LinkedHashMap<>();
            Map<String, Long> tagSeqIdMap = new LinkedHashMap<>();
            LinkedHashSet<String> tagIds = new LinkedHashSet<>();
            for (Chunk chunk : result.items()) {
                if (!chunk.getTagId().isEmpty()) {
                    tagIds.add(chunk.getTagId());
                }
            }
            if (!tagIds.isEmpty()) {
                List<KnowledgeTag> tags2 = tagMapper.selectByTenantAndIds(effectiveTenant, new ArrayList<>(tagIds));
                for (KnowledgeTag t : tags2) {
                    tagNameMap.put(t.getId(), t.getName());
                    tagSeqIdMap.put(t.getId(), t.getSeqId());
                }
            }

            faqGuard.ensureDefaults(kb);
            for (Chunk chunk : result.items()) {
                FaqEntry entry = faqChunkCodec.chunkToFAQEntry(chunk, kb, tagSeqIdMap);
                if (!chunk.getTagId().isEmpty()) {
                    entry = entry.withTagName(tagNameMap.get(chunk.getTagId()));
                }
                entries.add(entry);
            }
        }
        return new FaqEntryPage(entries, page, pageSize, total);
    }

    // ══════════════════ 详情 ═══════════════════════════════════════════
    public FaqEntry getEntry(String kbId, long entrySeqId) {
        if (entrySeqId <= 0) {
            throw new BizException(AppError.badRequest("条目ID不能为空"));
        }
        KnowledgeBase kb = faqGuard.validateFAQKnowledgeBase(kbId);
        faqGuard.ensureDefaults(kb);
        long tid = tenantId();

        Chunk chunk = faqChunkRepository.getChunkBySeqId(tid, entrySeqId);
        if (chunk == null) {
            throw new BizException(AppError.notFound("FAQ条目不存在"));
        }
        if (!kb.getId().equals(chunk.getKnowledgeBaseId()) || chunk.getTenantId() == null
                || chunk.getTenantId() != tid) {
            throw new BizException(AppError.notFound("FAQ条目不存在"));
        }
        if (!"faq".equals(chunk.getChunkType())) {
            throw new BizException(AppError.notFound("FAQ条目不存在"));
        }
        Map<String, Long> tagSeqIdMap = new LinkedHashMap<>();
        if (!chunk.getTagId().isEmpty()) {
            KnowledgeTag tag = tagMapper.selectByTenantAndIds(tid, List.of(chunk.getTagId()))
                    .stream().findFirst().orElse(null);
            if (tag != null) {
                tagSeqIdMap.put(tag.getId(), tag.getSeqId());
            }
        }
        FaqEntry entry = faqChunkCodec.chunkToFAQEntry(chunk, kb, tagSeqIdMap);
        if (!chunk.getTagId().isEmpty()) {
            KnowledgeTag tag = tagMapper.selectByTenantAndIds(tid, List.of(chunk.getTagId()))
                    .stream().findFirst().orElse(null);
            if (tag != null) {
                entry = entry.withTagName(tag.getName());
            }
        }
        return entry;
    }


    // ══════════════════ 导出 ═══════════════════════════════════════════
    public byte[] exportCsv(String kbId) {
        KnowledgeBase kb = faqGuard.validateFAQKnowledgeBase(kbId);
        long tid = tenantId();
        Knowledge faqKnowledge = faqIndexWriter.findFAQKnowledge(tid, kb.getId());
        List<Chunk> chunks = faqKnowledge == null
                ? List.of()
                : faqChunkRepository.listAllFAQChunksForExport(tid, faqKnowledge.getId());
        Map<String, String> tagMap = buildTagMap(tid, kbId);
        return buildFAQCSV(chunks, tagMap);
    }

    /** ；空库输出 {@code []}。 */
    public byte[] exportJson(String kbId) {
        KnowledgeBase kb = faqGuard.validateFAQKnowledgeBase(kbId);
        long tid = tenantId();
        Knowledge faqKnowledge = faqIndexWriter.findFAQKnowledge(tid, kb.getId());
        List<Chunk> chunks = faqKnowledge == null
                ? List.of()
                : faqChunkRepository.listAllFAQChunksForExport(tid, faqKnowledge.getId());
        Map<String, String> tagMap = buildTagMap(tid, kbId);
        return buildFAQJSON(chunks, tagMap);
    }

    private Map<String, String> buildTagMap(long tenantId, String kbId) {
        Map<String, String> tagMap = new LinkedHashMap<>();
        int pageSize = 1000;
        for (int pageNum = 1; ; pageNum++) {
            List<KnowledgeTag> tags = tagMapper.listByKB(tenantId, kbId, pageSize, (pageNum - 1) * pageSize);
            for (KnowledgeTag tag : tags) {
                tagMap.put(tag.getId(), tag.getName());
            }
            if (tags.size() < pageSize) {
                break;
            }
        }
        return tagMap;
    }

    private byte[] buildFAQCSV(List<Chunk> chunks, Map<String, String> tagMap) {
        // UTF-8 BOM 由 controller 写出，service 侧不带——别两头写
        StringBuilder buf = new StringBuilder();
        buf.append("分类(必填),问题(必填),相似问题(选填-多个用##分隔),反例问题(选填-多个用##分隔),")
                .append("机器人回答(必填-多个用##分隔),是否全部回复(选填-默认FALSE),")
                .append("是否停用(选填-默认FALSE),是否禁止被推荐(选填-默认False 可被推荐)")
                .append('\n');
        for (Chunk chunk : chunks) {
            FaqChunkMetadata meta = faqChunkCodec.sanitizedFaqMetadata(chunk);
            if (meta == null) {
                continue;
            }
            String tagName = "";
            if (!chunk.getTagId().isEmpty() && tagMap != null) {
                tagName = tagMap.getOrDefault(chunk.getTagId(), "");
            }
            String[] row = {
                    escapeCSVField(tagName),
                    escapeCSVField(meta.standardQuestion),
                    escapeCSVField(String.join("##", meta.similarQuestions == null ? List.of() : meta.similarQuestions)),
                    escapeCSVField(String.join("##", meta.negativeQuestions == null ? List.of() : meta.negativeQuestions)),
                    escapeCSVField(String.join("##", meta.answers == null ? List.of() : meta.answers)),
                    boolToCSV(FaqChunkMetadata.ANSWER_STRATEGY_ALL.equals(meta.answerStrategy)),
                    boolToCSV(!chunk.isIsEnabled()),
                    boolToCSV((chunk.getFlags() & 1) == 0),
            };
            buf.append(String.join(",", row)).append('\n');
        }
        return buf.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] buildFAQJSON(List<Chunk> chunks, Map<String, String> tagMap) {
        List<FaqExportEntry> entries = new ArrayList<>();
        for (Chunk chunk : chunks) {
            FaqChunkMetadata meta = faqChunkCodec.sanitizedFaqMetadata(chunk);
            if (meta == null) {
                continue;
            }
            String tagName = "";
            if (!chunk.getTagId().isEmpty() && tagMap != null) {
                tagName = tagMap.getOrDefault(chunk.getTagId(), "");
            }
            entries.add(new FaqExportEntry(
                    chunk.getSeqId() == null ? 0 : chunk.getSeqId(),
                    tagName,
                    meta.standardQuestion,
                    meta.similarQuestions,
                    meta.negativeQuestions,
                    meta.answers,
                    meta.answerStrategy,
                    chunk.isIsEnabled(),
                    (chunk.getFlags() & 1) != 0));
        }
        try {
            return FaqChunkMetadata.JSON.writeValueAsBytes(entries);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String escapeCSVField(String field) {
        if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    private static String boolToCSV(boolean b) {
        return b ? "TRUE" : "FALSE";
    }

    // ══════════════════ 搜索 ═══════════════════════════════════════════

    /**
     * 参数归一化与
     * searchResults 为空 → {@code []} 的出口逐行保留；<b>HybridSearch 的执行面
     * 空结果出口（契约样例 faq-search-embed-missing 钉住 {@code data:[]}）。
     */
    public List<FaqEntry> searchEntries(String kbId, FaqSearchDtos.FaqSearchRequest req) {
        KnowledgeBase kb = faqGuard.validateFAQKnowledgeBase(kbId);

        double vectorThreshold = req.vectorThreshold();
        if (vectorThreshold <= 0) {
            vectorThreshold = 0.7;
        }
        int matchCount = req.matchCount();
        if (matchCount <= 0) {
            matchCount = 10;
        }
        if (matchCount > 50) {
            matchCount = 50;
        }
        long tid = tenantId();

        List<String> firstPriorityTagUuids = new ArrayList<>();
        List<String> secondPriorityTagUuids = new ArrayList<>();
        Set<Long> firstPrioritySeqIdSet = new LinkedHashSet<>();
        Set<Long> secondPrioritySeqIdSet = new LinkedHashSet<>();
        if (req.firstPriorityTagIds() != null && !req.firstPriorityTagIds().isEmpty()) {
            List<KnowledgeTag> tags = tagMapper.selectByTenantAndSeqIds(tid, req.firstPriorityTagIds());
            for (KnowledgeTag tag : tags) {
                firstPriorityTagUuids.add(tag.getId());
                firstPrioritySeqIdSet.add(tag.getSeqId());
            }
        }
        if (req.secondPriorityTagIds() != null && !req.secondPriorityTagIds().isEmpty()) {
            List<KnowledgeTag> tags = tagMapper.selectByTenantAndSeqIds(tid, req.secondPriorityTagIds());
            for (KnowledgeTag tag : tags) {
                secondPriorityTagUuids.add(tag.getId());
                secondPrioritySeqIdSet.add(tag.getSeqId());
            }
        }

        boolean hasPriorityFilter = !firstPriorityTagUuids.isEmpty() || !secondPriorityTagUuids.isEmpty();

        // HybridSearch 执行面（2026-09-23 走查批接线——原「随收口」备案，
        // FirstPriority 先结果后 SecondPriority 按 chunkID 去重合并；
        List<SearchResult> searchResults = searchFaqChunks(kbId, req.queryText(),
                vectorThreshold, matchCount, req.onlyRecommended(),
                hasPriorityFilter, firstPriorityTagUuids, secondPriorityTagUuids);
        if (searchResults.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> chunkIds = new ArrayList<>(searchResults.size());
        Map<String, Double> chunkScores = new LinkedHashMap<>();
        Map<String, Integer> chunkMatchTypes = new LinkedHashMap<>();
        Map<String, String> chunkMatchedContents = new LinkedHashMap<>();
        for (SearchResult result : searchResults) {
            chunkIds.add(result.getId());
            chunkScores.put(result.getId(), result.getScore());
            chunkMatchTypes.put(result.getId(), result.getMatchType());
            chunkMatchedContents.put(result.getId(),
                    result.getMatchedContent() == null ? "" : result.getMatchedContent());
        }

        List<Chunk> chunks = chunkRepository.listChunksById(tid, chunkIds);
        List<FaqEntry> entries = convertSearchResults(kb, chunks, tid, matchCount,
                hasPriorityFilter, firstPriorityTagUuids, secondPriorityTagUuids,
                chunkScores, chunkMatchTypes, chunkMatchedContents);

        // 批量补 TagName（L1214-1250）：entry.TagID(seq) → tag 名
        if (!entries.isEmpty()) {
            List<Long> tagSeqIds = new ArrayList<>();
            for (FaqEntry entry : entries) {
                if (entry.tagId() != 0 && !tagSeqIds.contains(entry.tagId())) {
                    tagSeqIds.add(entry.tagId());
                }
            }
            if (!tagSeqIds.isEmpty()) {
                Map<Long, String> tagNameMap = new LinkedHashMap<>();
                try {
                    for (KnowledgeTag tag : tagMapper.selectByTenantAndSeqIds(tid, tagSeqIds)) {
                        tagNameMap.put(tag.getSeqId(), tag.getName());
                    }
                } catch (RuntimeException e) {
                    log.warn("Failed to batch query tags: {}", e.toString());
                }
                if (!tagNameMap.isEmpty()) {
                    List<FaqEntry> filled = new ArrayList<>(entries.size());
                    for (FaqEntry entry : entries) {
                        String name = entry.tagId() != 0 ? tagNameMap.get(entry.tagId()) : null;
                        filled.add(name == null ? entry : entry.withTagName(name));
                    }
                    entries = filled;
                }
            }
        }
        return entries;
    }

    /**
     * 优先级过滤时两级各自检索（原实现并发执行，Java
     * 顺序执行——合并序固定为先 First 后 Second，结果序等价）；无过滤单次全量检索。
     * 参数逐字段对照：DisableKeywordsMatch=true（关键词在 messages/FAQ 自身层面）、
     */
    private List<SearchResult> searchFaqChunks(String kbId, String queryText,
            double vectorThreshold, int matchCount, boolean onlyRecommended,
            boolean hasPriorityFilter, List<String> firstPriorityTagUuids,
            List<String> secondPriorityTagUuids) {
        if (!hasPriorityFilter) {
            SearchParams params = new SearchParams();
            params.setQueryText(LogSanitizer.sanitize(queryText));
            params.setVectorThreshold(vectorThreshold);
            params.setMatchCount(matchCount);
            params.setDisableKeywordsMatch(true);
            List<SearchResult> results = hybridSearchService.hybridSearch(kbId, params);
            return results == null ? List.of() : results;
        }
        Map<String, List<SearchResult>> byLevel = new LinkedHashMap<>();
        fillPriorityLevel(kbId, queryText, vectorThreshold, matchCount, onlyRecommended,
                firstPriorityTagUuids, byLevel, "first");
        fillPriorityLevel(kbId, queryText, vectorThreshold, matchCount, onlyRecommended,
                secondPriorityTagUuids, byLevel, "second");
        List<SearchResult> merged = new ArrayList<>();
        Set<String> seenChunkIds = new LinkedHashSet<>();
        for (String level : new String[] {"first", "second"}) {
            for (SearchResult result : byLevel.getOrDefault(level, List.of())) {
                if (seenChunkIds.add(result.getId())) {
                    merged.add(result);
                }
            }
        }
        return merged;
    }

    private void fillPriorityLevel(String kbId, String queryText, double vectorThreshold,
            int matchCount, boolean onlyRecommended, List<String> tagUuids,
            Map<String, List<SearchResult>> out, String level) {
        if (tagUuids == null || tagUuids.isEmpty()) {
            return; // 该优先级未提供时不发起检索
        }
        SearchParams params = new SearchParams();
        params.setQueryText(LogSanitizer.sanitize(queryText));
        params.setVectorThreshold(vectorThreshold);
        params.setMatchCount(matchCount);
        params.setDisableKeywordsMatch(true);
        params.setTagIds(tagUuids);
        params.setOnlyRecommended(onlyRecommended);
        List<SearchResult> results = hybridSearchService.hybridSearch(kbId, params);
        out.put(level, results == null ? List.of() : results);
    }

    /** 命中转换/优先级排序/限流（score/matchType/matchedQuestion
     *  在转换时按 chunkID 回填，排序与截断在前，TagName 批补在调用方末尾）。 */
    private List<FaqEntry> convertSearchResults(KnowledgeBase kb, List<Chunk> chunks, long tid,
                                                int matchCount, boolean hasPriorityFilter,
                                                List<String> firstPriority, List<String> secondPriority,
                                                Map<String, Double> chunkScores,
                                                Map<String, Integer> chunkMatchTypes,
                                                Map<String, String> chunkMatchedContents) {
        List<FaqEntry> entries = new ArrayList<>();
        Map<String, Long> tagSeqIdMap = new LinkedHashMap<>();
        LinkedHashSet<String> tagIds = new LinkedHashSet<>();
        for (Chunk chunk : chunks) {
            if (!chunk.getTagId().isEmpty()) {
                tagIds.add(chunk.getTagId());
            }
        }
        if (!tagIds.isEmpty()) {
            for (KnowledgeTag t : tagMapper.selectByTenantAndIds(tid, new ArrayList<>(tagIds))) {
                tagSeqIdMap.put(t.getId(), t.getSeqId());
            }
        }
        faqGuard.ensureDefaults(kb);
        for (Chunk chunk : chunks) {
            if (!"faq".equals(chunk.getChunkType()) || !chunk.isIsEnabled()) {
                continue;
            }
            FaqEntry entry = faqChunkCodec.chunkToFAQEntry(chunk, kb, tagSeqIdMap);
            // 负例问题过滤已在 HybridSearch 内处理）
            Double score = chunkScores.get(chunk.getId());
            Integer matchType = chunkMatchTypes.get(chunk.getId());
            String matched = chunkMatchedContents.get(chunk.getId());
            if (score != null || matchType != null || (matched != null && !matched.isEmpty())) {
                // 条目刚由 chunk 组装，尚未带命中信息，直接构造即可
                entry = entry.withMatch(new FaqEntry.FaqMatch(
                        score == null ? 0 : score,
                        matchType == null ? 0 : matchType,
                        matched == null || matched.isEmpty() ? null : matched));
            }
            entries.add(entry);
        }
        if (hasPriorityFilter) {
            Set<String> firstSet = new LinkedHashSet<>(firstPriority);
            Set<String> secondSet = new LinkedHashSet<>(secondPriority);
            entries.sort((a, b) -> {
                int aPriority = priorityOf(a, firstSet, secondSet);
                int bPriority = priorityOf(b, firstSet, secondSet);
                if (aPriority != bPriority) {
                    return aPriority - bPriority;
                }
                return Double.compare(scoreOf(b), scoreOf(a));
            });
        } else {
            entries.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));
        }
        if (entries.size() > matchCount) {
            entries = new ArrayList<>(entries.subList(0, matchCount));
        }
        // 批量补 TagName（L1214-1250；检索未接线时 entries 恒空，骨架保留）
        return entries;
    }

    /** 检索分数（无命中信息按 0 处理，仅用于排序）。 */
    private static double scoreOf(FaqEntry entry) {
        return entry.match() == null ? 0 : entry.match().score();
    }

    private static int priorityOf(FaqEntry entry, Set<String> firstSet, Set<String> secondSet) {
        return firstSet.contains(entry.chunkId()) ? 0 : secondSet.contains(entry.chunkId()) ? 1 : 2;
    }

}
