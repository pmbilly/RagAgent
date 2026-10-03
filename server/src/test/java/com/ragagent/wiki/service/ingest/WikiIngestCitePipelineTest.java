package com.ragagent.wiki.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.wiki.prompt.WikiPrompts;
import com.ragagent.common.wiki.ExtractedItem;
import com.ragagent.wiki.service.page.NewSlugFromCitation;

/**
 * {@link WikiIngestCitePipeline} 的测试：覆盖 {@code mergeCitationsIntoItems} 与
 * {@code splitChunksIntoCitationBatches} 两个纯函数，以及<b>句柄表</b>与
 * {@code classifyChunkCitations} 的句柄翻译（句柄翻译是整个协议最容易出错的地方）。
 */
class WikiIngestCitePipelineTest {

    private static Chunk textChunk(int idx, int runes, String id) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setChunkIndex(idx);
        c.setContent("a".repeat(runes));
        c.setChunkType(WikiIngestService.CHUNK_TYPE_TEXT);
        return c;
    }

    private static ExtractedItem item(String slug, String name) {
        ExtractedItem it = new ExtractedItem();
        it.setSlug(slug);
        it.setName(name);
        return it;
    }

    private static ExtractedItem findBySlug(List<ExtractedItem> items, String slug) {
        for (ExtractedItem it : items) {
            if (it.getSlug().equals(slug)) {
                return it;
            }
        }
        return null;
    }

    private static NewSlugFromCitation newSlug(String type, String name, String slug, List<String> chunks) {
        return new NewSlugFromCitation(type, name, slug, new ArrayList<>(), "desc", "details", chunks);
    }

    // ═══════════════════════════════════════════════════════════════
    // mergeCitationsIntoItems
    // ═══════════════════════════════════════════════════════════════

    /**
     * 分类遍返回的引用要回填到匹配的候选条目上，未引用的候选保持原样。
     */
    @Test
    @DisplayName("引用回填候选的 SourceChunks（对照 Go TestMergeCitationsIntoItems_PopulatesSourceChunksOnCandidates）")
    void mergeCitationsPopulatesSourceChunksOnCandidates() {
        List<ExtractedItem> entities = new ArrayList<>(List.of(
                item("entity/acme", "Acme"),
                item("entity/beta", "Beta")));
        List<ExtractedItem> concepts = new ArrayList<>(List.of(item("concept/rag", "RAG")));
        Map<String, List<String>> citations = new LinkedHashMap<>();
        citations.put("entity/acme", List.of("chunk-1", "chunk-3"));
        citations.put("concept/rag", List.of("chunk-2"));

        WikiIngestCitePipeline.MergedCitations got = WikiIngestCitePipeline.mergeCitationsIntoItems(
                entities, concepts, citations, null);

        assertThat(got.entities()).hasSize(2);
        assertThat(got.concepts()).hasSize(1);
        assertThat(findBySlug(got.entities(), "entity/acme").sourceChunksOrEmpty())
                .containsExactly("chunk-1", "chunk-3");
        assertThat(findBySlug(got.entities(), "entity/beta").sourceChunksOrEmpty())
                .as("entity/beta 不该有引用")
                .isEmpty();
        assertThat(findBySlug(got.concepts(), "concept/rag").sourceChunksOrEmpty())
                .containsExactly("chunk-2");
        assertThat(got.uncited()).isEqualTo(1);
    }

    /**
     * Pass 0 漏掉的崭新 slug 要追加到正确的类型切片；同一 slug 在两个批次出现时要合并
     * 引用 chunk 的并集；与既有候选重复的条目不产生重复项。
     */
    @Test
    @DisplayName("引用遍新增 slug 并跨批次求并集（对照 Go TestMergeCitationsIntoItems_AddsNewSlugsAndUnionsChunksAcrossBatches）")
    void mergeCitationsAddsNewSlugsAndUnionsChunksAcrossBatches() {
        List<ExtractedItem> entities = new ArrayList<>(List.of(item("entity/known", "Known")));
        List<ExtractedItem> concepts = new ArrayList<>();

        List<NewSlugFromCitation> newSlugs = List.of(
                newSlug("entity", "Fresh Entity", "entity/fresh", List.of("c001", "c002")),
                // 同一 slug 在另一个批次再次出现 —— 必须求并集
                newSlug("entity", "Fresh Entity", "entity/fresh", List.of("c002", "c003")),
                newSlug("concept", "New Concept", "concept/new-concept", List.of("c010")),
                // 与既有候选重复 —— 不该产生重复项
                newSlug("entity", "Known", "entity/known", List.of("c020")));

        WikiIngestCitePipeline.MergedCitations got = WikiIngestCitePipeline.mergeCitationsIntoItems(
                entities, concepts, null, newSlugs);

        assertThat(got.entities()).hasSize(2);
        assertThat(got.concepts()).hasSize(1);
        List<String> fresh = new ArrayList<>(findBySlug(got.entities(), "entity/fresh").sourceChunksOrEmpty());
        fresh.sort(String::compareTo);
        assertThat(fresh).containsExactly("c001", "c002", "c003");
        assertThat(findBySlug(got.concepts(), "concept/new-concept").sourceChunksOrEmpty())
                .containsExactly("c010");
    }

    // ═══════════════════════════════════════════════════════════════
    // 分桶
    // ═══════════════════════════════════════════════════════════════

    /**
     * 分桶绝不超预算、保持文档顺序、超大的 chunk 独占一个批次；并且每个批次的句柄表
     * 条目数等于该批次的 chunk 数。
     */
    @Test
    @DisplayName("分桶遵守预算与顺序（对照 Go TestSplitChunksIntoCitationBatches_RespectsBudgetAndOrder）")
    void splitChunksIntoCitationBatchesRespectsBudgetAndOrder() {
        // 每个小 chunk 5000 码点 → 3 个放不进一个批次（15k > 12k 上限），会溢出到第二个批次。
        List<Chunk> chunks = List.of(
                textChunk(0, 5000, "id-0"),
                textChunk(1, 5000, "id-1"),
                textChunk(2, 5000, "id-2"),
                // 超大的 chunk 独占一个批次
                textChunk(3, 20000, "id-big"),
                textChunk(4, 1000, "id-small"));

        List<WikiIngestCitePipeline.ChunkBatch> batches =
                WikiIngestCitePipeline.splitChunksIntoCitationBatches(chunks);
        assertThat(batches).as("期望至少 3 个批次").hasSizeGreaterThanOrEqualTo(3);

        List<String> seen = new ArrayList<>();
        for (WikiIngestCitePipeline.ChunkBatch b : batches) {
            for (Chunk c : b.chunks()) {
                seen.add(c.getId());
            }
        }
        assertThat(seen).containsExactly("id-0", "id-1", "id-2", "id-big", "id-small");

        for (int bi = 0; bi < batches.size(); bi++) {
            assertThat(batches.get(bi).handleCount())
                    .as("批次 %d 的句柄数 %d != chunk 数 %d",
                            bi, batches.get(bi).handleCount(), batches.get(bi).chunks().size())
                    .isEqualTo(batches.get(bi).chunks().size());
        }
    }

    /** 非 text 类型与空内容的 chunk 被过滤；全空/空入参时返回空 */
    @Test
    @DisplayName("分桶只引用 text chunk")
    void splitChunksFiltersNonTextAndEmpty() {
        Chunk img = new Chunk();
        img.setId("img-1");
        img.setChunkIndex(0);
        img.setContent("image ocr text");
        img.setChunkType(WikiIngestService.CHUNK_TYPE_IMAGE_OCR);

        Chunk blank = textChunk(1, 0, "blank-1");
        blank.setContent("");

        assertThat(WikiIngestCitePipeline.splitChunksIntoCitationBatches(
                List.of(img, blank))).isEmpty();
        assertThat(WikiIngestCitePipeline.splitChunksIntoCitationBatches(null)).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // 句柄表
    // ═══════════════════════════════════════════════════════════════

    /**
     * {@code c000} 起编号、
     * 零填充 3 位、重复注册稳定、未知句柄 resolve 返回 null、空键返回空句柄。
     */
    @Test
    @DisplayName("c000 句柄表：零填充、稳定、未知句柄拒绝")
    void chunkHandleTable() {
        WikiChunkHandleTable table = new WikiChunkHandleTable();
        assertThat(table.register("chunk-a")).isEqualTo("c000");
        assertThat(table.register("chunk-b")).isEqualTo("c001");
        assertThat(table.register("chunk-a")).as("重复注册必须稳定").isEqualTo("c000");
        assertThat(table.register("")).isEmpty();
        assertThat(table.register(null)).isEmpty();
        assertThat(table.size()).isEqualTo(2);

        assertThat(table.resolve("c001")).isEqualTo("chunk-b");
        assertThat(table.resolve("c999")).as("未知句柄必须被拒绝").isNull();
        assertThat(table.resolve("")).isNull();

        // 不分配句柄的反查
        assertThat(table.handleForKey("chunk-a")).isEqualTo("c000");
        assertThat(table.handleForKey("chunk-zzz")).isNull();
        assertThat(table.size()).as("handleForKey 不得分配新句柄").isEqualTo(2);
    }

    /** 编号超过宽度时只加宽、不截断 */
    @Test
    @DisplayName("句柄编号超过宽度不截断")
    void chunkHandleTableWidens() {
        WikiChunkHandleTable table = new WikiChunkHandleTable();
        for (int i = 0; i < 1000; i++) {
            table.register("chunk-" + i);
        }
        assertThat(table.register("chunk-1000")).isEqualTo("c1000");
    }

    /** 用句柄代替原始 UUID，并带上 chunk index 属性 */
    @Test
    @DisplayName("renderChunksXML 使用句柄与 index 属性")
    void renderChunksXml() {
        List<WikiIngestCitePipeline.ChunkBatch> batches =
                WikiIngestCitePipeline.splitChunksIntoCitationBatches(
                        List.of(textChunk(0, 10, "uuid-0"), textChunk(7, 10, "uuid-1")));
        assertThat(batches).hasSize(1);
        String xml = WikiIngestCitePipeline.renderChunksXML(batches.get(0));
        assertThat(xml).contains("<c id=\"c000\" index=\"0\">");
        assertThat(xml).contains("<c id=\"c001\" index=\"7\">");
        assertThat(xml).doesNotContain("uuid-0");
    }

    /** 跳过空 slug / 空 name 的候选，aliases 带引号输出 */
    @Test
    @DisplayName("renderCandidateSlugsXML 渲染候选清单")
    void renderCandidateSlugsXml() {
        ExtractedItem a = item("entity/acme", "Acme");
        a.setAliases(new ArrayList<>(List.of("ACME", "Acme Inc")));
        a.setDescription("desc");
        ExtractedItem empty = item("", "NoSlug");
        ExtractedItem noName = item("entity/noname", "");

        String xml = WikiIngestCitePipeline.renderCandidateSlugsXML(
                List.of(a, empty, noName), List.of(item("concept/rag", "RAG")));

        assertThat(xml).isEqualTo(
                "- slug: entity/acme, type: entity, name: \"Acme\" aliases=\"ACME, Acme Inc\", description: desc\n"
                        + "- slug: concept/rag, type: concept, name: \"RAG\", description: \n");
    }

    // ═══════════════════════════════════════════════════════════════
    // 分类：句柄翻译
    // ═══════════════════════════════════════════════════════════════

    /**
     * 分类遍的核心不变量：模型输出里的句柄必须翻回<b>真实</b> chunk UUID；
     * 未知句柄被丢弃；跨批次的引用求并集；new_slugs 的 SourceChunks 同样被翻译。
     *
     * <p>用打桩的 {@link WikiIngestService#generateWithTemplate} 返回值驱动——
     * <b>不触碰网络</b>（约定 §6）。</p>
     */
    @Test
    @DisplayName("分类遍把 cNNN 句柄翻回真实 chunk UUID（未知句柄丢弃）")
    void classifyChunkCitationsTranslatesHandles() {
        WikiIngestService svc = mock(WikiIngestService.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);

        // 两个批次：每个批次各自从 c000 起编号，因此同一个句柄在两个批次里指向不同 chunk。
        // 这正是"句柄必须按批次翻译"的原因——把它当成全局编号就会串页。
        when(svc.generateWithTemplate(any(LlmChatClient.class), anyString(),
                org.mockito.ArgumentMatchers.<String, String>anyMap()))
                .thenAnswer(inv -> {
                    String template = inv.getArgument(1);
                    Map<String, String> data = inv.getArgument(2);
                    if (!WikiPrompts.WIKI_CHUNK_CITATION_PROMPT.equals(template)) {
                        return "";
                    }
                    String chunksXml = data.get("ChunksXML");
                    // renderChunksXML 只输出句柄，因此按 index 属性区分批次：
                    // 索引 2 是那个 20000 码点的超大 chunk（独占第二批）。
                    if (chunksXml.contains("index=\"2\"")) {
                        // 第二个批次（超大 chunk 独占）
                        return "{\"citations\":{\"entity/acme\":[\"c000\"]},"
                                + "\"new_slugs\":[{\"type\":\"concept\",\"name\":\"Fresh\","
                                + "\"slug\":\"concept/fresh\",\"source_chunks\":[\"c000\",\"c777\"]}]}";
                    }
                    // 第一个批次
                    return "{\"citations\":{\"entity/acme\":[\"c000\",\"c001\",\"c999\"],"
                            + "\"entity/beta\":[\"c001\"]},\"new_slugs\":[]}";
                });

        // 第一个批次：uuid-0/uuid-1；第二个批次：uuid-big
        List<Chunk> chunks = List.of(
                textChunk(0, 100, "uuid-0"),
                textChunk(1, 100, "uuid-1"),
                textChunk(2, 20000, "uuid-big"));

        WikiIngestCitePipeline pipeline = new WikiIngestCitePipeline(svc, chunkMapper);
        WikiIngestCitePipeline.CitationResult result = pipeline.classifyChunkCitations(
                mock(LlmChatClient.class), "- slug: entity/acme\n", chunks, "Chinese", null);

        assertThat(result.batchCount()).isEqualTo(2);
        // entity/acme：批次 1 给出 uuid-0/uuid-1（c999 未知 → 丢弃），批次 2 给出 uuid-big
        assertThat(result.citations().get("entity/acme"))
                .as("句柄未翻译或未知句柄未被丢弃：%s", result.citations())
                .containsExactly("uuid-0", "uuid-1", "uuid-big");
        assertThat(result.citations().get("entity/beta")).containsExactly("uuid-1");

        // new_slugs 的 SourceChunks 同样被翻译，未知句柄（c777）被丢弃
        assertThat(result.newSlugs()).hasSize(1);
        assertThat(result.newSlugs().get(0).slug()).isEqualTo("concept/fresh");
        assertThat(result.newSlugs().get(0).sourceChunks()).containsExactly("uuid-big");
    }

    /** 空候选 XML 或没有可分桶的 chunk 时直接返回空结果，不打 LLM */
    @Test
    @DisplayName("无候选或无分块时分类遍短路")
    void classifyShortCircuits() {
        WikiIngestService svc = mock(WikiIngestService.class);
        WikiIngestCitePipeline pipeline = new WikiIngestCitePipeline(svc, mock(ChunkMapper.class));

        WikiIngestCitePipeline.CitationResult r1 = pipeline.classifyChunkCitations(
                mock(LlmChatClient.class), "  ", List.of(textChunk(0, 5, "x")), "zh", null);
        assertThat(r1.batchCount()).isZero();
        assertThat(r1.citations()).isEmpty();

        WikiIngestCitePipeline.CitationResult r2 = pipeline.classifyChunkCitations(
                mock(LlmChatClient.class), "candidates", List.of(), "zh", null);
        assertThat(r2.batchCount()).isZero();
    }

    // ═══════════════════════════════════════════════════════════════
    // 引用正文解析
    // ═══════════════════════════════════════════════════════════════

    /** 按给定顺序拼接、空内容跳过、双换行分隔 */
    @Test
    @DisplayName("collectCitedChunkContent 按序拼接逐字正文")
    void collectCitedChunkContent() {
        Map<String, String> byId = new LinkedHashMap<>();
        byId.put("c1", "first");
        byId.put("c2", "  ");
        byId.put("c3", "third");

        assertThat(WikiIngestCitePipeline.collectCitedChunkContent(
                List.of("c1", "c2", "c3", "missing"), byId)).isEqualTo("first\n\nthird");
        assertThat(WikiIngestCitePipeline.collectCitedChunkContent(List.of("c1"), Map.of())).isEmpty();
        assertThat(WikiIngestCitePipeline.collectCitedChunkContent(null, byId)).isEmpty();
    }

    /** citedChunkSet 是全部引用的去重集合 */
    @Test
    @DisplayName("citedChunkSet 去重")
    void citedChunkSet() {
        Map<String, List<String>> citations = new LinkedHashMap<>();
        citations.put("a", List.of("c1", "c2"));
        citations.put("b", List.of("c2", "c3"));
        assertThat(WikiIngestCitePipeline.citedChunkSet(citations)).containsExactly("c1", "c2", "c3");
        assertThat(WikiIngestCitePipeline.citedChunkSet(null)).isEmpty();
    }

    /** 前序 slug 提示只保留 entity/ concept/ 前缀 */
    @Test
    @DisplayName("前序 slug 提示只保留 entity/concept 前缀")
    void previousSlugsHint() {
        assertThat(WikiIngestCitePipeline.renderPreviousSlugs(null))
                .isEqualTo(WikiBatchConstants.NO_PREVIOUS_SLUGS_HINT);
        assertThat(WikiIngestCitePipeline.renderPreviousSlugs(
                java.util.Set.of("summary/abc", "index")))
                .isEqualTo(WikiBatchConstants.NO_PREVIOUS_SLUGS_HINT);
        String rendered = WikiIngestCitePipeline.renderPreviousSlugs(
                new java.util.LinkedHashSet<>(List.of("entity/a", "concept/b", "summary/c")));
        assertThat(rendered).isEqualTo("- entity/a\n- concept/b\n");
    }
}
