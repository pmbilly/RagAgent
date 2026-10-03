package com.ragagent.wiki.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.common.wiki.ExtractedItem;
import com.ragagent.common.wiki.SlugUpdate;
import com.ragagent.wiki.service.WikiModelResolver;
import com.ragagent.wiki.service.page.WikiPageService;

/**
 * {@link WikiIngestTaxonomy} 的测试，另含
 * {@code WikiIngestService.formatExistingTaxonomyForPrompt} 的目录树渲染。
 */
class WikiIngestTaxonomyTest {

    private final WikiPageService wikiService = mock(WikiPageService.class);
    private final WikiModelResolver modelResolver = mock(WikiModelResolver.class);
    private final WikiIngestTaxonomy taxonomy = new WikiIngestTaxonomy(wikiService, modelResolver);

    // ═══════════════════════════════════════════════════════════════
    // formatExistingTaxonomyForPrompt
    // ═══════════════════════════════════════════════════════════════

    /**
     * 同级标签按<b>码点序</b>输出，
     * 每层两个空格缩进。
     */
    @Test
    @DisplayName("目录树渲染（对照 Go TestFormatExistingTaxonomyForPrompt）")
    void formatExistingTaxonomyForPrompt() {
        String got = WikiIngestService.formatExistingTaxonomyForPrompt(List.of(
                List.of("春节", "传统习俗"),
                List.of("春节", "文化习俗", "节日习俗"),
                List.of("春节习俗"),
                List.of("产品定位")));
        String want = "产品定位\n"
                + "春节\n"
                + "  传统习俗\n"
                + "  文化习俗\n"
                + "    节日习俗\n"
                + "春节习俗";
        assertThat(got).isEqualTo(want);
    }

    /** 空目录树渲染为空串 */
    @Test
    @DisplayName("空目录树渲染为空串（对照 Go TestFormatExistingTaxonomyForPromptEmpty）")
    void formatExistingTaxonomyForPromptEmpty() {
        assertThat(WikiIngestService.formatExistingTaxonomyForPrompt(null)).isEmpty();
        assertThat(WikiIngestService.formatExistingTaxonomyForPrompt(List.of())).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // parseTaxonomyAssignments
    // ═══════════════════════════════════════════════════════════════

    /**
     * 剥掉 markdown 围栏、丢弃空 slug、
     * 保留空 path 的条目。
     */
    @Test
    @DisplayName("解析规划结果（对照 Go TestParseTaxonomyAssignments）")
    void parseTaxonomyAssignments() {
        String raw = "```json\n{\"assignments\":["
                + "{\"slug\":\"entity/zhang-san\",\"path\":[\"人物\"]},"
                + "{\"slug\":\"concept/spring\",\"path\":[\"节日\",\"传统节日\"]},"
                + "{\"slug\":\"  \",\"path\":[\"X\"]},"
                + "{\"slug\":\"entity/unclassified\",\"path\":[]}"
                + "]}\n```";

        Map<String, List<String>> got = WikiIngestTaxonomy.parseTaxonomyAssignments(raw);
        assertThat(got).as("空 slug 应被丢弃，期望 3 条：%s", got).hasSize(3);
        assertThat(String.join("/", got.get("entity/zhang-san"))).isEqualTo("人物");
        assertThat(String.join("/", got.get("concept/spring"))).isEqualTo("节日/传统节日");
        assertThat(got).containsKey("entity/unclassified");
        assertThat(got.get("entity/unclassified")).isEmpty();
    }

    /** 畸形输出返回 null */
    @Test
    @DisplayName("畸形规划输出返回 null（对照 Go TestParseTaxonomyAssignmentsMalformed）")
    void parseTaxonomyAssignmentsMalformed() {
        assertThat(WikiIngestTaxonomy.parseTaxonomyAssignments("not json at all")).isNull();
        assertThat(WikiIngestTaxonomy.parseTaxonomyAssignments("")).isNull();
        assertThat(WikiIngestTaxonomy.parseTaxonomyAssignments(null)).isNull();
        // 合法 JSON 但缺 assignments 数组
        assertThat(WikiIngestTaxonomy.parseTaxonomyAssignments("{\"foo\":1}")).isNull();
    }

    // ═══════════════════════════════════════════════════════════════
    // 向量相似度
    // ═══════════════════════════════════════════════════════════════

    /** 全等 ≈1、正交 0、长度不匹配 0、零范数 0 */
    @Test
    @DisplayName("余弦相似度（对照 Go TestCosineSimilarity）")
    void cosineSimilarity() {
        assertThat(WikiIngestTaxonomy.cosineSimilarity(new float[] { 1, 0 }, new float[] { 1, 0 }))
                .isGreaterThan(0.999);
        assertThat(WikiIngestTaxonomy.cosineSimilarity(new float[] { 1, 0 }, new float[] { 0, 1 }))
                .isEqualTo(0.0);
        assertThat(WikiIngestTaxonomy.cosineSimilarity(new float[] { 1, 0 }, null))
                .isEqualTo(0.0);
        assertThat(WikiIngestTaxonomy.cosineSimilarity(new float[] { 0, 0 }, new float[] { 1, 1 }))
                .isEqualTo(0.0);
    }

    /**
     * 只保留排进任意条目 top-K 的深层目录，
     * 并<b>按输入顺序</b>输出。
     */
    @Test
    @DisplayName("按向量选夹（对照 Go TestSelectFoldersByVectors）")
    void selectFoldersByVectors() {
        List<List<String>> deeper = List.of(
                List.of("AI", "厂商"), // 0
                List.of("AI", "模型"), // 1
                List.of("地理", "城市")); // 2
        List<float[]> folderVecs = List.of(
                new float[] { 1, 0, 0 },
                new float[] { 0.9f, 0.1f, 0 },
                new float[] { 0, 0, 1 });
        // 条目最接近目录 0（目录 1 是次选）；与目录 2 正交。
        List<float[]> itemVecs = List.of(new float[] { 1, 0, 0 });

        List<List<String>> got = WikiIngestTaxonomy.selectFoldersByVectors(deeper, folderVecs, itemVecs, 2);
        assertThat(got).as("期望 2 个目录：%s", got).hasSize(2);
        assertThat(String.join("/", got.get(0))).isEqualTo("AI/厂商");
        assertThat(String.join("/", got.get(1))).isEqualTo("AI/模型");
    }

    /** 长度不匹配 / 无条目 / 上限为 0 时返回空 */
    @Test
    @DisplayName("选夹的防御分支（对照 Go TestSelectFoldersByVectorsGuards）")
    void selectFoldersByVectorsGuards() {
        assertThat(WikiIngestTaxonomy.selectFoldersByVectors(
                List.of(List.of("a")),
                List.of(new float[] { 1 }, new float[] { 2 }),
                List.of(new float[] { 1 }), 1))
                .isEmpty();
        assertThat(WikiIngestTaxonomy.selectFoldersByVectors(
                List.of(List.of("a")), List.of(new float[] { 1 }), List.of(), 1))
                .isEmpty();
        assertThat(WikiIngestTaxonomy.selectFoldersByVectors(
                List.of(List.of("a")), List.of(new float[] { 1 }), List.of(new float[] { 1 }), 0))
                .isEmpty();
    }

    /** max<=0 不限，否则截断 */
    @Test
    @DisplayName("capFolders 截断")
    void capFolders() {
        List<List<String>> paths = List.of(List.of("a"), List.of("b"), List.of("c"));
        assertThat(WikiIngestTaxonomy.capFolders(paths, 2)).hasSize(2);
        assertThat(WikiIngestTaxonomy.capFolders(paths, 0)).hasSize(3);
        assertThat(WikiIngestTaxonomy.capFolders(null, 2)).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // 条目收集
    // ═══════════════════════════════════════════════════════════════

    /**
     * 跳过 summary / retract，按确定的 slug
     * 顺序输出（让分块边界稳定），about 取条目的 description。
     */
    @Test
    @DisplayName("收集待分类条目（对照 Go TestCollectTaxonomyItems）")
    void collectTaxonomyItems() {
        Map<String, List<SlugUpdate>> slugUpdates = new LinkedHashMap<>();
        slugUpdates.put("entity/b", List.of(entityUpdate("entity/b", "B", "about B")));
        slugUpdates.put("entity/a", List.of(conceptUpdate("entity/a", "A", "")));
        slugUpdates.put("sum/x", List.of(new SlugUpdate("sum/x", SlugUpdate.TYPE_SUMMARY)));
        slugUpdates.put("ret/y", List.of(new SlugUpdate("ret/y", SlugUpdate.TYPE_RETRACT)));

        List<WikiIngestTaxonomy.TaxonomyItem> items = WikiIngestTaxonomy.collectTaxonomyItems(slugUpdates);
        assertThat(items).as("summary/retract 应跳过：%s", items).hasSize(2);
        assertThat(items.get(0).slug()).isEqualTo("entity/a");
        assertThat(items.get(1).slug()).isEqualTo("entity/b");
        assertThat(items.get(1).about()).isEqualTo("about B");
        // 每个 slug 只取一条（break 语义）
        assertThat(items.get(0).pageType()).isEqualTo(WikiConstants.PAGE_TYPE_CONCEPT);
    }

    private static SlugUpdate entityUpdate(String slug, String name, String about) {
        SlugUpdate u = new SlugUpdate(slug, SlugUpdate.TYPE_ENTITY);
        ExtractedItem it = new ExtractedItem();
        it.setSlug(slug);
        it.setName(name);
        it.setDescription(about);
        u.setItem(it);
        return u;
    }

    private static SlugUpdate conceptUpdate(String slug, String name, String about) {
        SlugUpdate u = new SlugUpdate(slug, SlugUpdate.TYPE_CONCEPT);
        ExtractedItem it = new ExtractedItem();
        it.setSlug(slug);
        it.setName(name);
        it.setDescription(about);
        u.setItem(it);
        return u;
    }

    /** name 为空白时 title 回落到 slug */
    @Test
    @DisplayName("收集条目时 title 回落")
    void collectTaxonomyItemsTitleFallback() {
        Map<String, List<SlugUpdate>> updates = new LinkedHashMap<>();
        SlugUpdate u = new SlugUpdate("entity/only-slug", SlugUpdate.TYPE_ENTITY);
        ExtractedItem it = new ExtractedItem();
        it.setSlug("entity/only-slug");
        it.setName("   ");
        u.setItem(it);
        updates.put("entity/only-slug", List.of(u));

        List<WikiIngestTaxonomy.TaxonomyItem> items = WikiIngestTaxonomy.collectTaxonomyItems(updates);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("entity/only-slug");
    }

    // ═══════════════════════════════════════════════════════════════
    // 选夹（embedding 路径）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 目录池不超过
     * {@code TAXONOMY_FEED_ALL_MAX_FOLDERS} 时整体喂入，<b>不</b>调用 embedding。
     */
    @Test
    @DisplayName("小目录池整体喂入（不调 embedding）")
    void selectRelevantFoldersFeedsAllForSmallPools() {
        List<List<String>> pool = new ArrayList<>();
        for (int i = 0; i < WikiBatchConstants.TAXONOMY_FEED_ALL_MAX_FOLDERS; i++) {
            pool.add(List.of("cat-" + i));
        }
        List<List<String>> got = taxonomy.selectRelevantFolders(
                kb("model-1"), List.of(new WikiIngestTaxonomy.TaxonomyItem("entity/a", "A", "entity", "")), pool);
        assertThat(got).hasSameSizeAs(pool);
    }

    /**
     * KB <b>没有</b> embedding 模型时回落到
     * "限量喂全部"（上限 {@code TAXONOMY_PROMPT_MAX_PATHS}），
     * 一级目录永远保留。
     */
    @Test
    @DisplayName("无 embedding 模型时回落到限量喂全部")
    void selectRelevantFoldersFallsBackWithoutEmbeddingModel() {
        List<List<String>> pool = new ArrayList<>();
        for (int i = 0; i < WikiBatchConstants.TAXONOMY_PROMPT_MAX_PATHS + 50; i++) {
            pool.add(List.of("cat-" + i, "sub-" + i));
        }
        KnowledgeBase kb = kb("");
        List<List<String>> got = taxonomy.selectRelevantFolders(
                kb, List.of(new WikiIngestTaxonomy.TaxonomyItem("entity/a", "A", "entity", "")), pool);
        assertThat(got).hasSize(WikiBatchConstants.TAXONOMY_PROMPT_MAX_PATHS);
        // 一级锚点全部保留且排在最前
        assertThat(got.get(0)).containsExactly("cat-0", "sub-0");
    }

    /**
     * 配置了 embedding 模型时，一级目录永远保留，深层目录按每个条目的
     * top-K 相似度挑选。
     */
    @Test
    @DisplayName("有 embedding 模型时按相似度选夹并保留一级锚点")
    void selectRelevantFoldersSelectsByVectors() {
        List<List<String>> pool = new ArrayList<>();
        // 一级锚点
        for (int i = 0; i < WikiBatchConstants.TAXONOMY_FEED_ALL_MAX_FOLDERS + 3; i++) {
            pool.add(List.of("l1-" + i));
        }
        // 深层候选
        pool.add(List.of("ai", "vendors"));
        pool.add(List.of("geo", "cities"));

        WikiModelResolver.WikiEmbeddingModel embedder = mock(WikiModelResolver.WikiEmbeddingModel.class);
        try {
            when(embedder.batchEmbed(anyList())).thenAnswer(inv -> {
                List<String> texts = inv.getArgument(0);
                List<float[]> out = new ArrayList<>(texts.size());
                for (String t : texts) {
                    // "ai / vendors" 与条目文本都映射到 e0 方向；其它正交
                    out.add(t.contains("ai") ? new float[] { 1, 0 } : new float[] { 0, 1 });
                }
                return out;
            });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        when(modelResolver.getEmbeddingModel(anyString())).thenReturn(embedder);

        List<List<String>> got = taxonomy.selectRelevantFolders(
                kb("emb-1"),
                List.of(new WikiIngestTaxonomy.TaxonomyItem("entity/a", "ai", "entity", "vendors")),
                pool);

        assertThat(got).contains(List.of("ai", "vendors"));
        assertThat(got).contains(List.of("l1-0"));
    }

    /** embedding 模型取不到时只记 warn 并回落到喂全部 */
    @Test
    @DisplayName("embedding 模型不可用时回落")
    void selectRelevantFoldersDegradesWhenEmbedderUnavailable() {
        List<List<String>> pool = new ArrayList<>();
        for (int i = 0; i < WikiBatchConstants.TAXONOMY_FEED_ALL_MAX_FOLDERS + 3; i++) {
            pool.add(List.of("l1-" + i));
        }
        pool.add(List.of("ai", "vendors"));
        when(modelResolver.getEmbeddingModel(anyString()))
                .thenThrow(new IllegalStateException("embedding model unavailable"));

        List<List<String>> got = taxonomy.selectRelevantFolders(
                kb("emb-1"),
                List.of(new WikiIngestTaxonomy.TaxonomyItem("entity/a", "A", "entity", "")), pool);
        assertThat(got).hasSameSizeAs(pool);
    }

    // ═══════════════════════════════════════════════════════════════
    // 规划 / 实体化
    // ═══════════════════════════════════════════════════════════════

    /** kb 为 null 或没有可分类条目时返回 null */
    @Test
    @DisplayName("规划短路：kb 为空 / 无条目")
    void planShortCircuits() {
        assertThat(taxonomy.planBatchTaxonomy(mock(com.ragagent.llm.LlmChatClient.class), null,
                Map.of(), "zh", null)).isNull();
        assertThat(taxonomy.planBatchTaxonomy(mock(com.ragagent.llm.LlmChatClient.class), kb(""),
                Map.of(), "zh", null)).isNull();
    }

    /**
     * 不同路径只解析一次（缓存），
     * 空路径与解析失败都落到根并省略，负缓存避免逐 slug 重试。
     */
    @Test
    @DisplayName("目录实体化：路径缓存 + 负缓存")
    void resolvePlannedFoldersCaches() {
        when(wikiService.findOrCreateFolderPath(anyString(), org.mockito.ArgumentMatchers.any(),
                anyList()))
                .thenAnswer(inv -> {
                    List<String> path = inv.getArgument(2);
                    if (path.contains("bad")) {
                        throw new IllegalStateException("boom");
                    }
                    return new WikiPageService.FindOrCreateResult("folder-" + String.join("-", path), path);
                });

        Map<String, List<String>> planned = new LinkedHashMap<>();
        planned.put("entity/a", List.of("人物"));
        planned.put("concept/b", List.of("人物"));
        planned.put("entity/c", List.of());
        planned.put("entity/d", List.of("bad"));
        planned.put("entity/e", List.of("bad"));

        Map<String, String> got = taxonomy.resolvePlannedFolders(kb(""), planned);

        assertThat(got.get("entity/a")).isEqualTo("folder-人物");
        assertThat(got.get("concept/b")).isEqualTo("folder-人物");
        assertThat(got).doesNotContainKey("entity/c");
        assertThat(got).doesNotContainKey("entity/d");

        // 负缓存：坏路径只解析一次
        org.mockito.Mockito.verify(wikiService, org.mockito.Mockito.times(2))
                .findOrCreateFolderPath(anyString(), org.mockito.ArgumentMatchers.any(), anyList());
    }

    /** resolvePlannedFolders 的短路：kb / planned / wikiService 任一缺席都返回 null */
    @Test
    @DisplayName("目录实体化短路")
    void resolvePlannedFoldersShortCircuits() {
        assertThat(taxonomy.resolvePlannedFolders(null, Map.of("a", List.of("b")))).isNull();
        assertThat(taxonomy.resolvePlannedFolders(kb(""), null)).isNull();
        assertThat(taxonomy.resolvePlannedFolders(kb(""), Map.of())).isNull();
    }

    private static KnowledgeBase kb(String embeddingModelId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setTenantId(1L);
        kb.setEmbeddingModelId(embeddingModelId);
        return kb;
    }
}
