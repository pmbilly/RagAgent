package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.wiki.domain.WikiExtractionGranularity;
import com.ragagent.wiki.prompt.WikiPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ragagent.common.wiki.ExtractedItem;
import com.ragagent.common.wiki.SlugUpdate;

/**
 * 分块引用管线的纯算法与并发编排。
 *
 * <p>管线形态：<b>Pass 0</b>（候选 slug 骨架）→ <b>Pass 1..N</b>（把候选挂到具体
 * chunk）→ <b>Reduce</b>（用逐字 chunk 正文当证据写页面）。本类负责中间那段：
 * 分桶、渲染、并发分类、句柄翻译、引用合并；另含 Pass 0 的两个抽取器
 * （{@code extractCandidateSlugs} / {@code extractEntitiesAndConceptsNoUpsert}，
 * 后者共享同一套渲染与去重协议）。</p>
 *
 * <h2>句柄协议（cite 的核心不变量）</h2>
 * <p>每个引用批次持有一张<b>批次局部</b>的句柄表（{@code c000}、{@code c001}…），
 * prompt 里只见句柄、不见 UUID。模型输出回到应用状态<b>之前</b>，
 * {@link #classifyChunkCitations} 已经把句柄翻回真实 chunk ID——未知句柄被丢弃并记日志
 * （模型编出来的句柄绝不允许进入 {@code source_chunks}）。</p>
 *
 * <h2>“不中断兄弟批次”的失败语义</h2>
 * <p>单个引用批次失败（LLM 报错、JSON 解析失败）只记 warn 并跳过，
 * <b>不</b>让整个文档的引用阶段失败——其它批次的结果照常合并。</p>
 */
@Service
public class WikiIngestCitePipeline {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestCitePipeline.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WikiIngestService ingestService;
    private final ChunkMapper chunkMapper;

    public WikiIngestCitePipeline(WikiIngestService ingestService, ChunkMapper chunkMapper) {
        this.ingestService = ingestService;
        this.chunkMapper = chunkMapper;
    }

    // ═══════════════════════════════════════════════════════════════
    // 线格式类型
    // ═══════════════════════════════════════════════════════════════

    /**
     * 一次
     * {@code WikiChunkCitationPrompt} 调用期望的 JSON 形状。
     *
     * <p>只用于<b>解析</b>模型输出，从不序列化出站，因此不需要
     * {@code @JsonProperty}；Jackson 按字段名映射（snake 字段名见注解）。</p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static final class CitationBatchResult {
        @com.fasterxml.jackson.annotation.JsonProperty("citations")
        public Map<String, List<String>> citations;

        @com.fasterxml.jackson.annotation.JsonProperty("new_slugs")
        public List<NewSlugFromCitation> newSlugs;
    }

    /**
     * Pass 0 + 分类流程产出的
     * 原始计数，供 {@code mapOneDocument} 打一行统一统计。
     *
     * <p><b>注意</b>：这是可选的类型化载体；{@code mapOneDocument} 目前直接用
     * 局部变量拼统计行。</p>
     */
    public record CitationPipelineOutcome(
            int candidateCount,
            int chunkCount,
            int batchCount,
            int citedChunks,
            int uncitedSlugs,
            int newSlugCount) { }

    /**
     * 一次
     * {@code WikiChunkCitationPrompt} 调用里发送的分块组。
     *
     * <p>{@code totalRuneLen} 在分桶过程中累加，因此是<b>可变</b>字段。</p>
     */
    public static final class ChunkBatch {
        final List<Chunk> chunks = new ArrayList<>();
        final WikiChunkHandleTable handles = new WikiChunkHandleTable();
        int totalRuneLen;

        /** 已分配句柄数（测试断言用） */
        public int handleCount() {
            return handles.size();
        }

        public List<Chunk> chunks() {
            return chunks;
        }

        public WikiChunkHandleTable handles() {
            return handles;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Pass 0：候选 slug 抽取
    // ═══════════════════════════════════════════════════════════════

    /** Pass 0 的抽取结果 */
    public record CandidateSlugs(List<ExtractedItem> entities,
                                 List<ExtractedItem> concepts,
                                 Map<String, ExtractedItem> slugItems) { }

    /**
     * chunk-cited 管线的
     * <b>Pass 0</b>——扫全文，返回每个显著实体/概念的轻量<b>骨架</b>。
     * 与旧版单次抽取不同，这一遍<b>刻意不</b>要求 LLM 逐项改写完整事实；
     * 那些将由分块引用遍提供。
     *
     * <p>失败（LLM 或解析）时抛异常——调用方据此回落到旧版抽取器
     * （{@link #extractEntitiesAndConceptsNoUpsert}）。</p>
     */
    public CandidateSlugs extractCandidateSlugs(LlmChatClient chatModel,
                                                String kbId,
                                                String content,
                                                String lang,
                                                Set<String> oldPageSlugs,
                                                WikiBatchContext batchCtx) {
        String prevSlugsText = renderPreviousSlugs(oldPageSlugs);
        String granularity = resolveGranularity(batchCtx);

        String raw;
        try {
            raw = ingestService.generateWithTemplate(chatModel, WikiPrompts.WIKI_CANDIDATE_SLUG_PROMPT,
                    Map.of(
                            "Content", content == null ? "" : content,
                            "Language", lang == null ? "" : lang,
                            "PreviousSlugs", prevSlugsText,
                            "Granularity", granularity,
                            "GranularityGuidance", WikiPrompts.granularityGuidance(granularity),
                            "CustomInstructions", batchCtx == null ? "" : batchCtx.getExtractionInstructions(),
                            "InstructionScope", WikiBatchConstants.INSTRUCTION_SCOPE_EXTRACTION));
        } catch (RuntimeException e) {
            throw new IllegalStateException("candidate slug extraction failed: " + e.getMessage(), e);
        }

        raw = WikiTextUtils.cleanLLMJSON(raw);

        CombinedExtraction result = parseCombinedExtraction(raw, "candidate slug");
        return dedupeAndIndex(chatModel, kbId, result, batchCtx);
    }

    /**
     *
     * Pass 0 失败时回落的<b>旧版单次抽取器</b>。输出已经带有逐项改写过的 Details，
     * 因此不需要（也不应该）再跑 chunk 引用遍。
     *
     * <p>与 Pass 0 主抽取器共享同一套 prompt 渲染、JSON 清洗与去重收敛路径。</p>
     */
    public CandidateSlugs extractEntitiesAndConceptsNoUpsert(LlmChatClient chatModel,
                                                             String kbId,
                                                             String content,
                                                             String lang,
                                                             Set<String> oldPageSlugs,
                                                             WikiBatchContext batchCtx) {
        String prevSlugsText = renderPreviousSlugs(oldPageSlugs);

        String extractionJson;
        try {
            extractionJson = ingestService.generateWithTemplate(chatModel,
                    WikiPrompts.WIKI_KNOWLEDGE_EXTRACT_PROMPT,
                    Map.of(
                            "Content", content == null ? "" : content,
                            "Language", lang == null ? "" : lang,
                            "PreviousSlugs", prevSlugsText,
                            "CustomInstructions", batchCtx == null ? "" : batchCtx.getExtractionInstructions(),
                            "InstructionScope", WikiBatchConstants.INSTRUCTION_SCOPE_EXTRACTION));
        } catch (RuntimeException e) {
            throw new IllegalStateException("combined extraction failed: " + e.getMessage(), e);
        }

        extractionJson = WikiTextUtils.cleanLLMJSON(extractionJson);

        CombinedExtraction result = parseCombinedExtraction(extractionJson, "combined extraction");
        return dedupeAndIndex(chatModel, kbId, result, batchCtx);
    }

    /** 统一的 JSON 清洗 + 反序列化 + 失败包装 */
    private static CombinedExtraction parseCombinedExtraction(String raw, String what) {
        try {
            CombinedExtraction result = MAPPER.readValue(raw, CombinedExtraction.class);
            return result == null ? new CombinedExtraction() : result;
        } catch (Exception e) {
            log.warn("wiki ingest: failed to parse {} JSON: {}\nRaw: {}", what, e.getMessage(), raw);
            throw new IllegalStateException("parse " + what + " JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 抽取结果的统一收尾：去重 → 建 slug 索引。
     *
     * <p>去重预筛面向 wiki 页仓储的相似度检索；
     * 未接线 {@link WikiDedupSupport} 时它退化为"不去重"——这是<b>安全默认</b>
     * （LLM 合并调用拿不到候选列表，条目原样通过）。</p>
     */
    private WikiIngestCitePipeline.CandidateSlugs dedupeAndIndex(LlmChatClient chatModel,
                                                                String kbId,
                                                                CombinedExtraction result,
                                                                WikiBatchContext batchCtx) {
        List<ExtractedItem> entities = result.getEntities() == null
                ? new ArrayList<>() : new ArrayList<>(result.getEntities());
        List<ExtractedItem> concepts = result.getConcepts() == null
                ? new ArrayList<>() : new ArrayList<>(result.getConcepts());

        com.ragagent.wiki.service.WikiIngestExtractDedup.ExtractedProjection projection =
                ingestService.extractDedup.deduplicateExtractedBatch(chatModel, kbId, entities, concepts, batchCtx);
        entities = projection.entities();
        concepts = projection.concepts();

        Map<String, ExtractedItem> slugItems = new LinkedHashMap<>();
        for (ExtractedItem item : entities) {
            if (!item.getSlug().isEmpty() && !item.getName().isEmpty()) {
                slugItems.put(item.getSlug(), item);
            }
        }
        for (ExtractedItem item : concepts) {
            if (!item.getSlug().isEmpty() && !item.getName().isEmpty()) {
                slugItems.put(item.getSlug(), item);
            }
        }
        return new CandidateSlugs(entities, concepts, slugItems);
    }

    /** 取批次声明的抽取粒度（缺省 standard） */
    private static String resolveGranularity(WikiBatchContext batchCtx) {
        if (batchCtx == null || batchCtx.getExtractionGranularity() == null) {
            return WikiExtractionGranularity.STANDARD.value();
        }
        return batchCtx.getExtractionGranularity().value();
    }

    /**
     * 只保留 {@code entity/} / {@code concept/} 前缀的 slug——summary slug 是代码按
     * knowledge ID 生成的，永远不会出现在抽取输出里，带进去只是浪费 token。
     */
    static String renderPreviousSlugs(Set<String> oldPageSlugs) {
        StringBuilder sb = new StringBuilder();
        if (oldPageSlugs != null) {
            for (String slug : oldPageSlugs) {
                if (slug == null) {
                    continue;
                }
                if (!slug.startsWith("entity/") && !slug.startsWith("concept/")) {
                    continue;
                }
                sb.append("- ").append(slug).append('\n');
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? WikiBatchConstants.NO_PREVIOUS_SLUGS_HINT : out;
    }

    // ═══════════════════════════════════════════════════════════════
    // 分桶与渲染
    // ═══════════════════════════════════════════════════════════════

    /**
     * 把 chunk 分成
     * 累计码点数不超过 {@link WikiBatchConstants#MAX_RUNES_PER_CITATION_BATCH} 的批次。
     *
     * <p>顺序（按 ChunkIndex）保留；单个超过预算的 chunk <b>独占一个批次</b>，
     * 因此永远不会静默丢内容。每个 chunk 拿到一个短句柄（{@code c000}、{@code c001}…），
     * prompt 用它代替原始 UUID；调用局部的句柄表在任何结果进入应用状态<b>之前</b>
     * 把模型输出翻回稳定的 chunk ID。</p>
     */
    public static List<ChunkBatch> splitChunksIntoCitationBatches(List<Chunk> chunks) {
        // 只引用文本 chunk —— image/ocr chunk 已经由 reconstructEnrichedContent
        // 合并进文本正文，LLM 不会把它们当成独立的单元。
        List<Chunk> filtered = new ArrayList<>();
        if (chunks != null) {
            for (Chunk c : chunks) {
                if (c == null || c.getContent() == null || c.getContent().isEmpty()) {
                    continue;
                }
                String type = c.getChunkType();
                if (!WikiIngestService.CHUNK_TYPE_TEXT.equals(type) && type != null && !type.isEmpty()) {
                    continue;
                }
                filtered.add(c);
            }
        }
        if (filtered.isEmpty()) {
            return List.of();
        }

        // 保留文档顺序，让引用对人类可读。稳定排序：ChunkIndex 并列时按 StartAt
        // 破平（真实数据里 chunk ID 唯一，不会触发进一步歧义）。
        filtered.sort(Comparator
                .comparingInt(Chunk::getChunkIndex)
                .thenComparingInt(Chunk::getStartAt));

        List<ChunkBatch> batches = new ArrayList<>();
        ChunkBatch current = new ChunkBatch();
        for (Chunk c : filtered) {
            int runeLen = c.getContent().codePointCount(0, c.getContent().length());
            // 若加入该 chunk 会超预算且当前批次非空，先冲刷，免得超大的 chunk
            // 与已排队的合并。超大的 chunk 仍然照发——只是独占一个批次。
            if (!current.chunks.isEmpty()
                    && current.totalRuneLen + runeLen > WikiBatchConstants.MAX_RUNES_PER_CITATION_BATCH) {
                batches.add(current);
                current = new ChunkBatch();
            }
            // 只登记、分配句柄
            current.handles.register(c.getId());
            current.chunks.add(c);
            current.totalRuneLen += runeLen;
        }
        if (!current.chunks.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /**
     * 把候选 slug 渲染成
     * 适合 prompt 的 {@code <candidate_slugs>} 块的紧凑列表。
     */
    public static String renderCandidateSlugsXML(List<ExtractedItem> entities,
                                                 List<ExtractedItem> concepts) {
        StringBuilder sb = new StringBuilder();
        if (entities != null) {
            for (ExtractedItem item : entities) {
                if (item.getSlug().isEmpty() || item.getName().isEmpty()) {
                    continue;
                }
                writeCandidate(sb, item, "entity");
            }
        }
        if (concepts != null) {
            for (ExtractedItem item : concepts) {
                if (item.getSlug().isEmpty() || item.getName().isEmpty()) {
                    continue;
                }
                writeCandidate(sb, item, "concept");
            }
        }
        return sb.toString();
    }

    /**
     * {@code name} 与 {@code aliases}
     * 用引号包裹（goQuote 语义），{@code slug}/{@code type}/{@code description} 是裸值。
     * 格式保持稳定（prompt 前缀缓存命中率取决于此）。
     */
    private static void writeCandidate(StringBuilder sb, ExtractedItem item, String kind) {
        String aliases = "";
        if (!item.getAliases().isEmpty()) {
            aliases = " aliases=" + WikiIngestExtractDedup.goQuote(String.join(", ", item.getAliases()));
        }
        sb.append("- slug: ").append(item.getSlug())
                .append(", type: ").append(kind)
                .append(", name: ").append(WikiIngestExtractDedup.goQuote(item.getName()))
                .append(aliases)
                .append(", description: ").append(item.getDescription())
                .append('\n');
    }

    /**
     * 把一个批次的 chunk 渲染进
     * {@code <chunks>} 块，使用<b>批次局部句柄</b>（c000、c001…）而不是原始 UUID。
     *
     * <p>句柄用 {@code handleForKey}（已注册则取回，<b>不</b>新建）；
     * 由于分桶时已逐个注册，此处必然命中。</p>
     */
    public static String renderChunksXML(ChunkBatch batch) {
        StringBuilder sb = new StringBuilder();
        for (Chunk c : batch.chunks) {
            String handle = batch.handles.handleForKey(c.getId());
            if (handle == null) {
                handle = "";
            }
            sb.append("<c id=").append(WikiIngestExtractDedup.goQuote(handle))
                    .append(" index=\"").append(c.getChunkIndex()).append("\">\n")
                    .append(c.getContent() == null ? "" : c.getContent())
                    .append("\n</c>\n");
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // Pass 1..N：分块分类
    // ═══════════════════════════════════════════════════════════════

    /** 分类结果：slug → chunk 引用、新 slug、批次数 */
    public record CitationResult(Map<String, List<String>> citations,
                                 List<NewSlugFromCitation> newSlugs,
                                 int batchCount) { }

    /**
     * chunk-cited 管线的
     * <b>Pass 1..N</b>——给定候选 slug 集合（来自 Pass 0）与文档分块，
     * 问 LLM 哪些 chunk <b>实质性</b>讨论了每个候选。
     *
     * <p>跨批次的结果合并成单一的 slug → union(chunk_id) 映射；Pass 0 漏掉的
     * "new_slugs" 单独收集。{@code citations} 的键是 slug、值是<b>真实</b>的
     * chunk UUID（句柄已在批次内翻译完）。</p>
     *
     * <p><b>并发</b>：各批次并行跑（上限见 {@code MAX_CITATION_BATCH_CONCURRENCY}），
     * 合并状态用一把锁串行化。</p>
     */
    public CitationResult classifyChunkCitations(LlmChatClient chatModel,
                                                 String candidatesXml,
                                                 List<Chunk> chunks,
                                                 String lang,
                                                 WikiBatchContext batchCtx) {
        List<ChunkBatch> batches = splitChunksIntoCitationBatches(chunks);
        if (batches.isEmpty() || candidatesXml == null || candidatesXml.trim().isEmpty()) {
            return new CitationResult(new LinkedHashMap<>(), List.of(), 0);
        }

        // 合并状态：按 (slug, chunkID) 去重；顺序最后按 chunk ChunkIndex 重新施加。
        final Object mergeLock = new Object();
        final Map<String, Set<String>> citationSet = new LinkedHashMap<>();
        final List<NewSlugFromCitation> newSlugsAll = new ArrayList<>();

        List<Runnable> bodies = new ArrayList<>(batches.size());
        for (int bi = 0; bi < batches.size(); bi++) {
            final int batchIdx = bi;
            final ChunkBatch batch = batches.get(bi);
            // 渲染在并行段之外完成：它只读批次局部状态，提前算好让并行体内只剩 LLM 调用。
            final String chunksXml = renderChunksXML(batch);
            bodies.add(() -> {
                String raw;
                try {
                    raw = ingestService.generateWithTemplate(chatModel, WikiPrompts.WIKI_CHUNK_CITATION_PROMPT,
                            Map.of(
                                    "CandidateSlugs", candidatesXml,
                                    "ChunksXML", chunksXml,
                                    "Language", lang == null ? "" : lang));
                } catch (RuntimeException e) {
                    // 失败只记日志并返回 —— 不中断兄弟批次
                    log.warn("wiki ingest: citation batch {} failed: {}", batchIdx, e.getMessage());
                    return;
                }

                raw = WikiTextUtils.cleanLLMJSON(raw);
                CitationBatchResult parsed;
                try {
                    parsed = MAPPER.readValue(raw, CitationBatchResult.class);
                } catch (Exception jerr) {
                    log.warn("wiki ingest: citation batch {} parse failed: {}\nRaw: {}",
                            batchIdx, jerr.getMessage(), raw);
                    return;
                }
                if (parsed == null) {
                    return;
                }

                // 句柄 → 真实 chunk UUID；丢弃未知句柄。
                synchronized (mergeLock) {
                    mergeCitations(batchIdx, batch, parsed, citationSet, newSlugsAll);
                }
            });
        }
        WikiBatchSupport.fanOut(WikiBatchConstants.MAX_CITATION_BATCH_CONCURRENCY, bodies);

        // 建立稳定的 chunk 顺序，让最终引用按文档序输出。
        Map<String, Integer> chunkOrder = new LinkedHashMap<>();
        if (chunks != null) {
            for (Chunk c : chunks) {
                if (c != null) {
                    chunkOrder.put(c.getId(), c.getChunkIndex());
                }
            }
        }

        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : citationSet.entrySet()) {
            List<String> ids = new ArrayList<>(e.getValue());
            ids.sort(Comparator.comparingInt(id -> chunkOrder.getOrDefault(id, 0)));
            out.put(e.getKey(), ids);
        }
        return new CitationResult(out, newSlugsAll, batches.size());
    }

    /** 合并临界区（调用方持锁串行调用） */
    private static void mergeCitations(int batchIdx,
                                       ChunkBatch batch,
                                       CitationBatchResult parsed,
                                       Map<String, Set<String>> citationSet,
                                       List<NewSlugFromCitation> newSlugsAll) {
        if (parsed.citations != null) {
            for (Map.Entry<String, List<String>> e : parsed.citations.entrySet()) {
                String slug = e.getKey();
                if (slug == null || slug.isEmpty()) {
                    continue;
                }
                Set<String> set = citationSet.computeIfAbsent(slug, k -> new LinkedHashSet<>());
                if (e.getValue() == null) {
                    continue;
                }
                for (String handle : e.getValue()) {
                    String realId = batch.handles.resolve(handle);
                    if (realId == null) {
                        log.warn("wiki ingest: citation batch {} referenced unknown chunk handle "
                                + "{} for slug {}", batchIdx, handle, slug);
                        continue;
                    }
                    set.add(realId);
                }
            }
        }
        if (parsed.newSlugs == null) {
            return;
        }
        for (NewSlugFromCitation ns : parsed.newSlugs) {
            if (ns == null || ns.slug() == null || ns.slug().isEmpty()
                    || ns.name() == null || ns.name().isEmpty()) {
                continue;
            }
            List<String> real = new ArrayList<>();
            if (ns.sourceChunks() != null) {
                for (String handle : ns.sourceChunks()) {
                    String id = batch.handles.resolve(handle);
                    if (id != null) {
                        real.add(id);
                    }
                }
            }
            newSlugsAll.add(new NewSlugFromCitation(
                    ns.type(), ns.name(), ns.slug(), ns.aliases(),
                    ns.description(), ns.details(), real));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Reduce 侧的 chunk 正文解析
    // ═══════════════════════════════════════════════════════════════

    /**
     * 按 knowledge ID 分批，
     * 一次性把给定 additions 引用到的每个 chunk 的<b>正文</b>查出来，
     * 返回 {@code map[chunkID]content}。缺失 / 跨租户的 chunk ID 被静默跳过
     * （warn 级日志），好让 Reduce 阶段优雅回落到 Details 改写版。
     *
     * <p>chunk 仓储查询按租户划界，理论上一次就能全取；仍按知识库分批，
     * 是为了让大 reduce 批次下的 {@code IN (...)} 列表有界。</p>
     */
    public Map<String, String> resolveCitedChunks(long tenantId, List<SlugUpdate> additions) {
        Map<String, Set<String>> byKnowledge = new LinkedHashMap<>();
        if (additions != null) {
            for (SlugUpdate add : additions) {
                if (add.getKnowledgeId().isEmpty()) {
                    continue;
                }
                for (String chunkId : add.sourceChunksOrEmpty()) {
                    if (chunkId == null || chunkId.isEmpty()) {
                        continue;
                    }
                    byKnowledge.computeIfAbsent(add.getKnowledgeId(), k -> new LinkedHashSet<>())
                            .add(chunkId);
                }
            }
        }
        if (byKnowledge.isEmpty()) {
            // 无引用时返回 null（与调用方的回落分支约定）
            return null;
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Set<String> idSet : byKnowledge.values()) {
            List<String> ids = new ArrayList<>(idSet);
            if (ids.isEmpty()) {
                continue;
            }
            List<Chunk> chunks;
            try {
                chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getTenantId, tenantId)
                        .in(Chunk::getId, ids));
            } catch (Exception e) {
                log.warn("wiki ingest: failed to resolve cited chunks: {}", e.getMessage());
                continue;
            }
            for (Chunk c : chunks) {
                if (c == null || c.getContent() == null || c.getContent().isEmpty()) {
                    continue;
                }
                out.put(c.getId(), c.getContent());
            }
        }
        return out;
    }

    /**
     * 按给定顺序把每个
     * 被引用 chunk 的<b>逐字</b>正文串起来。解析不到的 chunk ID 被静默丢弃
     * （上游已记日志）。
     */
    public static String collectCitedChunkContent(List<String> chunkIds,
                                                  Map<String, String> contentById) {
        if (chunkIds == null || chunkIds.isEmpty() || contentById == null || contentById.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : chunkIds) {
            String content = contentById.get(id);
            if (content == null || content.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(content);
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 引用合并
    // ═══════════════════════════════════════════════════════════════

    /** 合并结果：回填后的切片与未获引用计数 */
    public record MergedCitations(List<ExtractedItem> entities,
                                  List<ExtractedItem> concepts,
                                  int uncited) { }

    /**
     * 用引用映射回填每个
     * {@code extractedItem} 的 {@code SourceChunks}，并把引用遍发现的<b>真正新</b> slug
     * 追加进对应切片。不在引用映射里的条目保持原样——Reduce 会回落到它们的
     * Description/Details。
     *
     * <p>返回值里的 {@code uncited} 是"最终一个引用都没有"的候选数（可观测性用）。</p>
     */
    public static MergedCitations mergeCitationsIntoItems(List<ExtractedItem> entities,
                                                          List<ExtractedItem> concepts,
                                                          Map<String, List<String>> citations,
                                                          List<NewSlugFromCitation> newSlugs) {
        int uncited = 0;
        List<ExtractedItem> outEntities = entities == null ? new ArrayList<>() : entities;
        List<ExtractedItem> outConcepts = concepts == null ? new ArrayList<>() : concepts;
        Map<String, List<String>> citationsMap = citations == null ? Map.of() : citations;

        for (ExtractedItem item : outEntities) {
            List<String> ids = citationsMap.get(item.getSlug());
            item.setSourceChunks(ids);
            if (ids == null || ids.isEmpty()) {
                uncited++;
            }
        }
        for (ExtractedItem item : outConcepts) {
            List<String> ids = citationsMap.get(item.getSlug());
            item.setSourceChunks(ids);
            if (ids == null || ids.isEmpty()) {
                uncited++;
            }
        }

        // 追加引用遍发现的 new_slugs，避免重复。
        Set<String> existingSlugs = new LinkedHashSet<>();
        for (ExtractedItem e : outEntities) {
            existingSlugs.add(e.getSlug());
        }
        for (ExtractedItem c : outConcepts) {
            existingSlugs.add(c.getSlug());
        }

        // 跨批次按 slug 聚合："新发现"的同一个 slug 在多个批次冒出来时合并成一项。
        Map<String, ExtractedItem> merged = new LinkedHashMap<>();
        Map<String, String> mergedType = new LinkedHashMap<>();
        List<String> slugOrder = new ArrayList<>();

        if (newSlugs != null) {
            for (NewSlugFromCitation ns : newSlugs) {
                if (ns == null || ns.slug() == null || ns.slug().isEmpty()
                        || ns.name() == null || ns.name().isEmpty()) {
                    continue;
                }
                if (existingSlugs.contains(ns.slug())) {
                    // 视作对既有候选的引用
                    continue;
                }
                String kind = ns.type() == null ? "" : ns.type().trim().toLowerCase(java.util.Locale.ROOT);
                if (kind.isEmpty()) {
                    kind = ns.slug().startsWith("concept/") ? "concept" : "entity";
                }
                ExtractedItem existing = merged.get(ns.slug());
                if (existing == null) {
                    ExtractedItem item = new ExtractedItem();
                    item.setName(ns.name());
                    item.setSlug(ns.slug());
                    item.setAliases(ns.aliases() == null ? new ArrayList<>() : new ArrayList<>(ns.aliases()));
                    item.setDescription(ns.description());
                    item.setDetails(ns.details());
                    item.setSourceChunks(ns.sourceChunks() == null
                            ? new ArrayList<>() : new ArrayList<>(ns.sourceChunks()));
                    merged.put(ns.slug(), item);
                    mergedType.put(ns.slug(), kind);
                    slugOrder.add(ns.slug());
                    continue;
                }
                // 求并集
                Set<String> seen = new LinkedHashSet<>(existing.sourceChunksOrEmpty());
                List<String> union = new ArrayList<>(existing.sourceChunksOrEmpty());
                if (ns.sourceChunks() != null) {
                    for (String id : ns.sourceChunks()) {
                        if (seen.add(id)) {
                            union.add(id);
                        }
                    }
                }
                existing.setSourceChunks(union);
            }
        }

        for (String slug : slugOrder) {
            ExtractedItem item = merged.get(slug);
            if ("concept".equals(mergedType.get(slug))) {
                outConcepts.add(item);
            } else {
                outEntities.add(item);
            }
        }

        return new MergedCitations(outEntities, outConcepts, uncited);
    }

    /** 供日志用的小工具：把引用映射转成"被引用 chunk 去重集合" */
    public static Set<String> citedChunkSet(Map<String, List<String>> citations) {
        Set<String> set = new LinkedHashSet<>();
        if (citations == null) {
            return set;
        }
        for (List<String> ids : citations.values()) {
            if (ids == null) {
                continue;
            }
            for (String id : ids) {
                set.add(id);
            }
        }
        return set;
    }
}
