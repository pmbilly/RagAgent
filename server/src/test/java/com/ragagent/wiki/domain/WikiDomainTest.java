package com.ragagent.wiki.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/types/wiki_page_test.go（332 行）的语义对等测试。
 *
 * <p>与 Go 版本的对应关系逐条标注在方法注释里。Go 测试里有两项在本模块不可覆盖，
 * 已在报告中说明：{@code TestKnowledgeBaseEnsureDefaultsWiki}（属于知识库模块的
 * EnsureDefaults）与 {@code TestChunkTypeWikiPage}（属于 chunk 模块的类型常量）。</p>
 */
class WikiDomainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── TestWikiPageTypes ──

    @Test
    void pageTypeConstantsAreNonEmptyAndUnique() {
        List<String> pageTypes = List.of(
                WikiConstants.PAGE_TYPE_SUMMARY,
                WikiConstants.PAGE_TYPE_ENTITY,
                WikiConstants.PAGE_TYPE_CONCEPT,
                WikiConstants.PAGE_TYPE_INDEX,
                WikiConstants.PAGE_TYPE_SYNTHESIS,
                WikiConstants.PAGE_TYPE_COMPARISON);
        assertThat(pageTypes).doesNotContain("");
        assertThat(pageTypes).doesNotHaveDuplicates();
        // 已下线的 wiki log 页类型不得再合法
        assertThat(WikiConstants.isValidPageType("log")).isFalse();
        assertThat(WikiConstants.isValidPageStatus("log")).isFalse();
        for (String pt : pageTypes) {
            assertThat(WikiConstants.isValidPageType(pt)).isTrue();
        }
        assertThat(WikiConstants.isValidPageStatus("archived")).isTrue();
        assertThat(WikiConstants.isValidPageStatus("Archived")).isFalse();
    }

    // ── TestWikiConfigValueScan / TestWikiConfigScanNil ──

    @Test
    void wikiConfigJsonRoundTrip() {
        WikiConfig config = new WikiConfig();
        config.setSynthesisModelId("model-123");
        config.setMaxPagesPerIngest(20);

        WikiConfig restored = WikiConfig.fromJson(config.toJson());
        assertThat(restored.getSynthesisModelId()).isEqualTo("model-123");
        assertThat(restored.getMaxPagesPerIngest()).isEqualTo(20);
    }

    @Test
    void wikiConfigScanNilDoesNotError() {
        assertThat(WikiConfig.fromJson(null)).isNull();
        assertThat(WikiConfig.fromJson("")).isNull();
    }

    // ── TestStringArrayValueScan / TestStringArrayEmpty ──

    @Test
    void stringArrayJsonRoundTrip() {
        List<String> arr = List.of("a", "b", "c");
        String encoded = WikiStringListTypeHandler.encode(arr);
        assertThat(encoded).isEqualTo("[\"a\",\"b\",\"c\"]");
        assertThat(WikiStringListTypeHandler.decode(encoded)).isEqualTo(arr);
    }

    @Test
    void emptyStringArrayDecodesToEmptyList() {
        // 写路径对齐 Go：nil / 空列表都走 SQL NULL（见 handler 类注释），
        // encode() 的序列化结果对应 Go 的字面量 null；读路径宽容，一律回空列表。
        assertThat(WikiStringListTypeHandler.encode(null)).isEqualTo("null");
        assertThat(WikiStringListTypeHandler.encode(List.of())).isEqualTo("null");
        assertThat(WikiStringListTypeHandler.decode("[]")).isEmpty();
        assertThat(WikiStringListTypeHandler.decode("null")).isEmpty();
        assertThat(WikiStringListTypeHandler.decode("")).isEmpty();
        assertThat(WikiStringListTypeHandler.decode(null)).isEmpty();
    }

    // ── TestWikiPageJSON ──

    @Test
    void wikiPageJsonRoundTrip() throws Exception {
        WikiPage page = new WikiPage();
        page.setId("test-id");
        page.setSlug("entity/test");
        page.setTitle("Test Entity");
        page.setPageType(WikiConstants.PAGE_TYPE_ENTITY);
        page.setContent("# Test\n\nSome content with [[concept/related]]");
        page.setSummary("A test entity page");
        page.setSourceRefs(List.of("source-1", "source-2"));
        page.setOutLinks(List.of("concept/related"));
        page.setInLinks(List.of("summary/doc1"));
        page.setVersion(3);

        String data = JSON.writeValueAsString(page);
        WikiPage restored = JSON.readValue(data, WikiPage.class);

        assertThat(restored.getSlug()).isEqualTo("entity/test");
        assertThat(restored.getVersion()).isEqualTo(3);
        assertThat(restored.getOutLinks()).containsExactly("concept/related");
        assertThat(restored.getSourceRefs()).hasSize(2);
    }

    /**
     * 契约钉住：Go struct 的 json tag 是 snake_case，且 omitempty 字段在零值时必须整键省略；
     * {@code deleted_at} 恒输出（Go 的 gorm.DeletedAt 未删除时是 JSON null）。
     */
    @Test
    void wikiPageJsonKeys() throws Exception {
        WikiPage page = new WikiPage();
        page.setId("p1");
        page.setSlug("entity/a");
        page.setTitle("A");
        page.setPageType("entity");
        page.setStatus("published");
        page.setWikiPath("entity/A");
        JsonNode node = JSON.readTree(JSON.writeValueAsString(page));

        assertThat(node.has("knowledgeBaseId")).isTrue();
        assertThat(node.has("pageType")).isTrue();
        assertThat(node.has("sourceRefs")).isTrue();
        assertThat(node.has("chunkRefs")).isTrue();
        assertThat(node.has("pageMetadata")).isTrue();
        assertThat(node.has("createdAt")).isTrue();
        assertThat(node.has("deletedAt")).isTrue();
        assertThat(node.get("deletedAt").isNull()).isTrue();

        // 恒输出（§1.6）：空串 / 0 显式输出，不再整键省略
        assertThat(node.has("parentSlug")).isTrue();
        assertThat(node.has("folderId")).isTrue();
        assertThat(node.has("categoryPath")).isTrue();
        assertThat(node.has("depth")).isTrue();
        assertThat(node.has("sortOrder")).isTrue();
        assertThat(node.has("lastEditSource")).isTrue();
        assertThat(node.has("lastEditorId")).isTrue();

        // ⚠️ 便捷方法必须 @JsonIgnore：否则会写进 jsonb 并让回读炸 UnrecognizedPropertyException
        assertThat(node.has("sourceKnowledgeIDs")).isFalse();
        assertThat(node.has("sourceKnowledgeIDs")).isFalse();
    }

    @Test
    void wikiPageMetadataJsonNodeRoundTrips() throws Exception {
        WikiPage page = new WikiPage();
        page.setPageMetadata(JsonNodeFactory.instance.objectNode().put("tag", "x"));
        WikiPage restored = JSON.readValue(JSON.writeValueAsString(page), WikiPage.class);
        assertThat(restored.getPageMetadata().get("tag").asText()).isEqualTo("x");
    }

    // ── TestWikiSourceKnowledgeID ──

    @Test
    void wikiSourceKnowledgeIdExtraction() {
        assertThat(WikiCategoryPaths.sourceKnowledgeID("doc-1|排班手册")).isEqualTo("doc-1");
        assertThat(WikiCategoryPaths.sourceKnowledgeID("doc-1")).isEqualTo("doc-1");
        assertThat(WikiCategoryPaths.sourceKnowledgeID("  doc-1 | title ")).isEqualTo("doc-1");
        assertThat(WikiCategoryPaths.sourceKnowledgeID("")).isEqualTo("");
        // 前导竖线（index 0）不切分——对照 Go strings.IndexByte(ref,'|') > 0
        assertThat(WikiCategoryPaths.sourceKnowledgeID("|doc-1")).isEqualTo("|doc-1");

        WikiPage page = new WikiPage();
        page.setSourceRefs(List.of("doc-1|手册", "doc-2"));
        assertThat(page.builtFrom(Set.of("doc-1"))).isTrue();
        assertThat(page.builtFrom(Set.of("doc-9"))).isFalse();
        assertThat(page.builtFrom(Set.of())).isFalse();
        assertThat(page.sourceKnowledgeIDs()).containsExactly("doc-1", "doc-2");
    }

    // ── TestWikiGraphDataJSON ──

    @Test
    void wikiGraphDataJsonRoundTrip() throws Exception {
        WikiGraph.Data graph = new WikiGraph.Data();
        WikiGraph.Node a = new WikiGraph.Node();
        a.setSlug("entity/a");
        a.setTitle("A");
        a.setPageType("entity");
        a.setLinkCount(2);
        WikiGraph.Node b = new WikiGraph.Node();
        b.setSlug("concept/b");
        b.setTitle("B");
        b.setPageType("concept");
        b.setLinkCount(1);
        graph.setNodes(List.of(a, b));
        graph.setEdges(List.of(new WikiGraph.Edge("entity/a", "concept/b")));

        WikiGraph.Data restored = JSON.readValue(JSON.writeValueAsString(graph), WikiGraph.Data.class);
        assertThat(restored.getNodes()).hasSize(2);
        assertThat(restored.getEdges()).hasSize(1);
        assertThat(restored.getEdges().get(0).getSource()).isEqualTo("entity/a");
        assertThat(restored.getEdges().get(0).getTarget()).isEqualTo("concept/b");
    }

    // ── TestWikiExtractionGranularity_IsValid / Normalize ──

    @Test
    void extractionGranularityIsValid() {
        for (WikiExtractionGranularity g : WikiExtractionGranularity.values()) {
            assertThat(WikiExtractionGranularity.isValid(g.value())).isTrue();
        }
        for (String invalid : List.of("", "FOCUSED", "strict", "none", "FULL")) {
            assertThat(WikiExtractionGranularity.isValid(invalid)).isFalse();
        }
    }

    @Test
    void extractionGranularityNormalize() {
        Map<String, String> cases = Map.of(
                "", "standard",               // 历史行 / 未设置
                "unknown", "standard",
                "FOCUSED", "standard",        // 刻意大小写敏感
                "focused", "focused",
                "standard", "standard",
                "exhaustive", "exhaustive");
        for (Map.Entry<String, String> e : cases.entrySet()) {
            assertThat(WikiExtractionGranularity.normalize(e.getKey()))
                    .as("normalize(%s)", e.getKey())
                    .isEqualTo(e.getValue());
        }
        assertThat(WikiExtractionGranularity.normalize(null)).isEqualTo("standard");
    }

    // ── TestWikiConfig_JSONRoundTrip_WithGranularity ──

    @Test
    void wikiConfigJsonRoundTripWithGranularity() {
        WikiConfig original = new WikiConfig();
        original.setSynthesisModelId("m-1");
        original.setMaxPagesPerIngest(20);
        original.setExtractionGranularity(WikiExtractionGranularity.FOCUSED.value());
        assertThat(WikiConfig.fromJson(original.toJson()).getExtractionGranularity())
                .isEqualTo("focused");

        // 历史行：既没有 granularity 字段，还带已退役的 enabled / auto_ingest 键。
        // Go 的 json.Unmarshal 默认忽略未知字段——Jackson 必须同样宽容（§9）。
        String legacy = "{\"enabled\":true,\"auto_ingest\":true,"
                + "\"synthesis_model_id\":\"\",\"max_pages_per_ingest\":0}";
        WikiConfig old = WikiConfig.fromJson(legacy);
        assertThat(old.getExtractionGranularity()).isEmpty();
        assertThat(old.normalizedExtractionGranularity()).isEqualTo("standard");
    }

    /** Go 的 Value() 用 omitempty：空串 / 0 的字段整键省略 */
    @Test
    void wikiConfigKeepsEmptyOptionalFields() throws Exception {
        WikiConfig config = new WikiConfig();
        config.setSynthesisModelId("m-1");
        JsonNode node = JSON.readTree(config.toJson());
        assertThat(node.has("extractionGranularity")).isTrue();
        assertThat(node.has("ingestBatchSize")).isTrue();
        assertThat(node.get("maxPagesPerIngest").asInt()).isZero();
        assertThat(node.get("synthesisModelId").asText()).isEqualTo("m-1");
        // ⚠️ *OrDefault 便捷方法必须 @JsonIgnore，否则会被写进 wiki_config jsonb
        assertThat(node.has("ingestBatchSizeOrDefault")).isFalse();
        assertThat(node.has("normalizedExtractionGranularity")).isFalse();
        assertThat(node.has("ingestBatchsizeOrDefault")).isFalse();
    }

    @Test
    void wikiConfigOrDefaultHelpers() {
        WikiConfig none = new WikiConfig();
        assertThat(none.ingestBatchSizeOrDefault(5)).isEqualTo(5);
        assertThat(none.ingestMapParallelOrDefault(10)).isEqualTo(10);
        assertThat(none.ingestReduceParallelOrDefault(10)).isEqualTo(10);
        assertThat(none.ingestMaxInflightOrDefault(4)).isEqualTo(4);
        // Go 的 nil 接收者语义
        assertThat(WikiConfig.ingestBatchSizeOrDefault(null, 5)).isEqualTo(5);
        assertThat(WikiConfig.ingestMaxInflightOrDefault(null, 4)).isEqualTo(4);

        WikiConfig set = new WikiConfig();
        set.setIngestBatchSize(9);
        set.setIngestMapParallel(11);
        set.setIngestReduceParallel(12);
        set.setIngestMaxInflight(13);
        assertThat(set.ingestBatchSizeOrDefault(5)).isEqualTo(9);
        assertThat(set.ingestMapParallelOrDefault(10)).isEqualTo(11);
        assertThat(set.ingestReduceParallelOrDefault(10)).isEqualTo(12);
        assertThat(set.ingestMaxInflightOrDefault(4)).isEqualTo(13);
        assertThat(WikiConfig.ingestBatchSizeOrDefault(set, 5)).isEqualTo(9);
    }

    // ── TestWikiFolderPathSegmentsKeepTypeLikeFolderNames + 路径清洗纯函数 ──

    @Test
    void folderPathSegmentsKeepTypeLikeFolderNames() {
        assertThat(WikiCategoryPaths.folderPathSegments("概念/ 概念 /Concepts//实体"))
                .containsExactly("概念", "概念", "Concepts", "实体");
        assertThat(WikiCategoryPaths.folderPathSegments("  ")).isEmpty();
        assertThat(WikiCategoryPaths.folderPathSegments(null)).isEmpty();
        assertThat(WikiCategoryPaths.folderPathSegments("")).isEmpty();

        // 模型标签清洗器仍会丢掉同样的名字，这正说明文件夹路径不能走它
        assertThat(WikiCategoryPaths.cleanCategoryPath(List.of("概念"))).isEmpty();
    }

    @Test
    void cleanCategoryPartNormalizesSeparatorsQuotesAndTypeLabels() {
        assertThat(WikiCategoryPaths.cleanCategoryPart("  AI／LLM  ")).containsExactly("AI", "LLM");
        assertThat(WikiCategoryPaths.cleanCategoryPart("\"实体\"")).isEmpty();
        assertThat(WikiCategoryPaths.cleanCategoryPart("[RAG]")).containsExactly("RAG");
        assertThat(WikiCategoryPaths.cleanCategoryPart("（AI）")).containsExactly("AI");
        assertThat(WikiCategoryPaths.cleanCategoryPart("Concepts")).isEmpty();
        assertThat(WikiCategoryPaths.cleanCategoryPart("")).isEmpty();
        assertThat(WikiCategoryPaths.cleanCategoryPart(null)).isEmpty();
        // 类型标签大小写不敏感 + 允许尾随 s（Go 的 lower + TrimSuffix "s"）。
        // 注意 Go 只去掉**一个**尾随 s：复数 "Entities"/"Summaries" 折叠成
        // "entitie"/"summarie" 后并不命中名单——这是 Go 的既有行为，Java 照抄。
        assertThat(WikiCategoryPaths.isTypeCategoryLabel("Entity")).isTrue();
        assertThat(WikiCategoryPaths.isTypeCategoryLabel("ENTITIES")).isFalse();
        assertThat(WikiCategoryPaths.isTypeCategoryLabel("Summaries")).isFalse();
        assertThat(WikiCategoryPaths.isTypeCategoryLabel("RAG")).isFalse();
    }

    @Test
    void cleanCategoryPathDedupesAndCapsDepth() {
        assertThat(WikiCategoryPaths.cleanCategoryPath(List.of("AI", "AI", "RAG")))
                .containsExactly("AI", "RAG");
        // 上限 3 层（WikiCategoryMaxDepth）
        assertThat(WikiCategoryPaths.cleanCategoryPath(List.of("a", "b", "c", "d")))
                .containsExactly("a", "b", "c");
        assertThat(WikiCategoryPaths.cleanCategoryPath(List.of())).isEmpty();
        assertThat(WikiCategoryPaths.cleanCategoryPath(null)).isEmpty();
        // 内嵌分隔符会被拆开，因此单个元素也可能贡献多个标签
        assertThat(WikiCategoryPaths.cleanCategoryPath(List.of("AI/LLM")))
                .containsExactly("AI", "LLM");
    }

    @Test
    void trimFolderSegmentsKeepsVerbatimAndDropsBlanks() {
        assertThat(WikiCategoryPaths.trimFolderSegments(List.of(" a ", "", "  ", "b")))
                .containsExactly("a", "b");
        assertThat(WikiCategoryPaths.trimFolderSegments(null)).isEmpty();
    }

    @Test
    void splitPageTypesSplitsDedupesAndTrims() {
        assertThat(WikiCategoryPaths.splitPageTypes("entity,concept"))
                .containsExactly("entity", "concept");
        assertThat(WikiCategoryPaths.splitPageTypes(" entity , entity , concept "))
                .containsExactly("entity", "concept");
        assertThat(WikiCategoryPaths.splitPageTypes(",,,")).isEmpty();
        assertThat(WikiCategoryPaths.splitPageTypes("  ")).isEmpty();
        assertThat(WikiCategoryPaths.splitPageTypes(null)).isEmpty();
    }

    @Test
    void normalizeEditSourceFallsBackToPipeline() {
        assertThat(WikiConstants.normalizeEditSource("agent")).isEqualTo("agent");
        assertThat(WikiConstants.normalizeEditSource("user")).isEqualTo("user");
        assertThat(WikiConstants.normalizeEditSource("revert")).isEqualTo("revert");
        assertThat(WikiConstants.normalizeEditSource("pipeline")).isEqualTo("pipeline");
        assertThat(WikiConstants.normalizeEditSource("")).isEqualTo("pipeline");
        assertThat(WikiConstants.normalizeEditSource("bogus")).isEqualTo("pipeline");
        assertThat(WikiConstants.PRUNABLE_EDIT_SOURCES).containsExactly("", "pipeline");
    }

    @Test
    void sourceRefNeedleEscapesLikeMetacharacters() {
        SourceRefNeedle n = WikiSourceRefs.of("doc-1");
        assertThat(n.getNeedle()).isEqualTo("[\"doc-1\"]");
        assertThat(n.getExactLike()).isEqualTo("%\"doc-1\"%");
        assertThat(n.getPrefixLike()).isEqualTo("%\"doc-1|%");

        // 含元字符的 id 不得逃出引号或变成通配符
        SourceRefNeedle evil = WikiSourceRefs.of("a%b_c\\d\"e");
        assertThat(WikiSourceRefs.escapeLikePattern("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
        assertThat(evil.getNeedle()).isEqualTo("[\"a%b_c\\\\d\\\"e\"]");
        assertThat(evil.getExactLike()).contains("\\%").contains("\\_").contains("\\\\");
    }

    @Test
    void folderNodeUnwrapsFolderAndFlattensKeys() throws Exception {
        WikiFolder folder = new WikiFolder();
        folder.setId("f-1");
        folder.setName("AI");
        folder.setParentId("");
        WikiFolderNode node = new WikiFolderNode(folder, 3, true);
        JsonNode json = JSON.readTree(JSON.writeValueAsString(node));
        // 对照 Go 的匿名嵌入：folder 字段扁平化到顶层
        assertThat(json.get("id").asText()).isEqualTo("f-1");
        assertThat(json.get("name").asText()).isEqualTo("AI");
        assertThat(json.get("parentId").asText()).isEmpty();
        assertThat(json.get("pageCount").asLong()).isEqualTo(3);
        assertThat(json.get("hasChildren").asBoolean()).isTrue();
        assertThat(json.has("folder")).isFalse();
        // 不得出现被 @JsonUnwrapped / 字段+getter 合并搞出的重复键
        assertThat(json.has("folder")).isFalse();
    }

    @Test
    void statsWireFormat() throws Exception {
        WikiStats stats = new WikiStats();
        stats.setActive(true);
        JsonNode json = JSON.readTree(JSON.writeValueAsString(stats));
        assertThat(json.has("active")).isTrue();
        assertThat(json.get("active").asBoolean()).isTrue();
        // isActive() 读取器推导的键与字段名一致,不得出现第二副本
        assertThat(json.has("isActive")).isFalse();
        assertThat(json.has("is_active")).isFalse();
        // Go struct 无 omitempty → 全字段恒输出
        assertThat(json.has("totalPages")).isTrue();
        assertThat(json.has("pagesByType")).isTrue();
        assertThat(json.has("recentUpdates")).isTrue();
        assertThat(json.has("orphanCount")).isTrue();
    }

    /** 投影类型（MyBatis 映射 + 可能的 JSON 输出）的键名也必须逐字对照 Go tag */
    @Test
    void projectionTypesUseJavaFieldNames() throws Exception {
        WikiPageLite lite = new WikiPageLite();
        lite.setSlug("entity/a");
        lite.setPageType("entity");
        lite.setOutLinks(List.of("concept/c"));
        lite.setAliases(List.of());
        JsonNode liteJson = JSON.readTree(JSON.writeValueAsString(lite));
        assertThat(liteJson.has("pageType")).isTrue();
        assertThat(liteJson.has("outLinks")).isTrue();
        assertThat(liteJson.has("page_type")).isFalse();
        // 恒输出（§1.6）：空数组显式输出 []
        assertThat(liteJson.has("aliases")).isTrue();

        WikiIndexEntry entry = new WikiIndexEntry();
        entry.setSlug("entity/a");
        entry.setWikiPath("entity/A");
        entry.setCategoryPath(List.of("AI"));
        entry.setDepth(1);
        JsonNode entryJson = JSON.readTree(JSON.writeValueAsString(entry));
        assertThat(entryJson.has("wikiPath")).isTrue();
        assertThat(entryJson.has("categoryPath")).isTrue();
        assertThat(entryJson.has("sortOrder")).isTrue();
        assertThat(entryJson.has("sort_order")).isFalse();
        assertThat(entryJson.has("parentSlug")).isTrue();
        assertThat(entryJson.has("depth")).isTrue();

        WikiPageRevision rev = new WikiPageRevision();
        rev.setPageId("p");
        rev.setEditSource("user");
        JsonNode revJson = JSON.readTree(JSON.writeValueAsString(rev));
        assertThat(revJson.has("pageId")).isTrue();
        assertThat(revJson.has("editSource")).isTrue();
        assertThat(revJson.has("editedAt")).isTrue();
        // content 恒输出（§1.6）
        assertThat(revJson.has("content")).isTrue();
    }

    /** 图谱与索引的嵌套类型键名 */
    @Test
    void graphAndIndexKeys() throws Exception {
        WikiGraph.Meta meta = new WikiGraph.Meta();
        meta.setMode("ego");
        meta.setCenter("entity/a");
        meta.setFamiliarCount(2);
        JsonNode metaJson = JSON.readTree(JSON.writeValueAsString(meta));
        assertThat(metaJson.has("familiarCount")).isTrue();
        assertThat(metaJson.has("familiar_count")).isFalse();

        WikiIndex.Group group = new WikiIndex.Group();
        group.setType("entity");
        group.setNextCursor("20");
        JsonNode groupJson = JSON.readTree(JSON.writeValueAsString(group));
        assertThat(groupJson.has("nextCursor")).isTrue();
        assertThat(groupJson.has("next_cursor")).isFalse();
        assertThat(groupJson.has("items")).isTrue();

        WikiIndex.Response resp = new WikiIndex.Response();
        JsonNode respJson = JSON.readTree(JSON.writeValueAsString(resp));
        assertThat(respJson.has("intro")).isTrue();
        assertThat(respJson.has("version")).isTrue();
        assertThat(respJson.has("groups")).isTrue();
    }

    @Test
    void exceptionMessagesMatchGoSentinels() {
        WikiException notFound = new WikiPageNotFoundException();
        assertThat(notFound).isInstanceOf(WikiException.class);
        assertThat(notFound).hasMessage("wiki page not found");
        assertThat(new WikiPageConflictException()).hasMessage("wiki page version conflict");
        assertThat(new WikiFolderNotFoundException()).hasMessage("wiki folder not found");
        assertThat(new WikiFolderConflictException()).hasMessage("wiki folder name conflict");
        assertThat(new WikiFolderNotEmptyException()).hasMessage("wiki folder is not empty");
        assertThatThrownBy(() -> {
            throw new WikiPageNotFoundException();
        }).isInstanceOf(WikiException.class);
    }
}
