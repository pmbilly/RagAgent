package com.ragagent.wiki.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Wiki prompt 常量与模板渲染器的对等测试（对照 Go
 * internal/agent/prompts_wiki_test.go，183 行，用例逐条翻译；
 * 外加一条 Go 侧没有的<b>字节级保真</b>测试）。
 */
class WikiPromptsTest {

    // ═══════════════════════════════════════════════════════════════
    // 字节级保真（Java 侧新增，钉住"逐字对照"这条硬约束）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 每个常量的 SHA-256，<b>由 Go 源文件 prompts_wiki.go 的 raw string 字面量原样算出</b>。
     *
     * <p>这条测试是"prompt 逐字对照"唯一可靠的机械化护栏：任何标点、空格、换行的改动
     * 都会让哈希漂移。生成方式见报告里的脚本说明。</p>
     */
    private static final Map<String, String> GO_SHA256 = new LinkedHashMap<>();

    static {
        GO_SHA256.put(WikiPrompts.WIKI_TAXONOMY_PLAN_PROMPT,
                "93f801fd2fbed1f4a7653f83bdb7d47b993e88a9764ed430b13ccd36dfcee750");
        GO_SHA256.put(WikiPrompts.WIKI_SUMMARY_PROMPT,
                "4212ac93efcd5da6841d8f0e899398c60b5ceae55d72df575e8c01adcc9658e0");
        GO_SHA256.put(WikiPrompts.WIKI_KNOWLEDGE_EXTRACT_PROMPT,
                "a755872a85d2717371c94567c0513d7d3540cbd02a87f022d176159ad602baf7");
        GO_SHA256.put(WikiPrompts.WIKI_CANDIDATE_SLUG_PROMPT,
                "2fee760db4de8a1ec10ea4ecabcf924ba27941a402da9ebc31adf178be0861c9");
        GO_SHA256.put(WikiPrompts.WIKI_CHUNK_CITATION_PROMPT,
                "d82a866a92d0fa722862ae6e295b211ba5651b0c82ae93a8b794dbce5f988eb8");
        GO_SHA256.put(WikiPrompts.WIKI_PAGE_MODIFY_SYSTEM_PROMPT,
                "1462bfba7c5432c356550d886e70813708302934735cc54170e898c6cbb30867");
        GO_SHA256.put(WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT,
                "b50a456e0fb900533436bff07d51557fe4aaf615e011e7105e0b047108468805");
        GO_SHA256.put(WikiPrompts.WIKI_INDEX_INTRO_PROMPT,
                "7e6817475ba95eb35855577ed70478c7d2265d9dd3de0f9871027efa26b91693");
        GO_SHA256.put(WikiPrompts.WIKI_INDEX_INTRO_UPDATE_PROMPT,
                "f40b22826daaed60a72ddb84edfbe1b6b476ea349a8a002d41f184745ba2ae09");
        GO_SHA256.put(WikiPrompts.WIKI_DEDUPLICATION_PROMPT,
                "d026ebb09c3bb7d890fbf40ceca368b2eee4ac89fb1a59e5ced7b3adec64f57c");
        GO_SHA256.put(WikiPrompts.WIKI_GRANULARITY_GUIDANCE_FOCUSED,
                "d5a4a2c52426d6160a404d64dbbf50bb3912d87f9be90f60ad76f9853a21ad34");
        GO_SHA256.put(WikiPrompts.WIKI_GRANULARITY_GUIDANCE_STANDARD,
                "4ab09e1a44c026cf1ede35d736cb935a8cab0fe50e8b8a0760291f8fff0b3b35");
        GO_SHA256.put(WikiPrompts.WIKI_GRANULARITY_GUIDANCE_EXHAUSTIVE,
                "0bd5ba91baacfe17833c3522ea387129b1e0625438c20add1ca7de2494a06088");
    }

    @Test
    @DisplayName("prompt 常量与 Go 源文件的 raw string 逐字节一致（SHA-256 钉死）")
    void promptsAreByteIdenticalToGo() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, String> e : GO_SHA256.entrySet()) {
            String actual = HexFormat.of().formatHex(
                    digest.digest(e.getKey().getBytes(StandardCharsets.UTF_8)));
            assertThat(actual)
                    .as("prompt bytes drifted from Go prompts_wiki.go;\n--- content ---\n%s",
                            e.getKey())
                    .isEqualTo(e.getValue());
        }
        assertThat(WikiPrompts.ALL_PROMPTS).hasSize(GO_SHA256.size());
    }

    // ═══════════════════════════════════════════════════════════════
    // TestWikiGranularityGuidance_*（Go prompts_wiki_test.go L9-64）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TestWikiGranularityGuidance_RoutesByKey：三个 key 各自路由到对应块")
    void granularityRoutesByKey() {
        assertThat(WikiPrompts.granularityGuidance("focused"))
                .isEqualTo(WikiPrompts.WIKI_GRANULARITY_GUIDANCE_FOCUSED);
        assertThat(WikiPrompts.granularityGuidance("standard"))
                .isEqualTo(WikiPrompts.WIKI_GRANULARITY_GUIDANCE_STANDARD);
        assertThat(WikiPrompts.granularityGuidance("exhaustive"))
                .isEqualTo(WikiPrompts.WIKI_GRANULARITY_GUIDANCE_EXHAUSTIVE);
    }

    @ParameterizedTest(name = "未知粒度 \"{0}\" → STANDARD")
    @DisplayName("TestWikiGranularityGuidance_UnknownDefaultsToStandard：未知值一律回落 standard")
    @ValueSource(strings = {"", "FOCUSED", "detailed", "minimal", "full", "unknown"})
    void granularityUnknownDefaultsToStandard(String granularity) {
        assertThat(WikiPrompts.granularityGuidance(granularity))
                .isEqualTo(WikiPrompts.WIKI_GRANULARITY_GUIDANCE_STANDARD);
    }

    @Test
    @DisplayName("TestWikiGranularityGuidance_BlocksAreDistinct：三块互不相同且各自自报模式")
    void granularityBlocksAreDistinct() {
        List<String> blocks = List.of(
                WikiPrompts.WIKI_GRANULARITY_GUIDANCE_FOCUSED,
                WikiPrompts.WIKI_GRANULARITY_GUIDANCE_STANDARD,
                WikiPrompts.WIKI_GRANULARITY_GUIDANCE_EXHAUSTIVE);

        assertThat(blocks).doesNotContain("");
        assertThat(blocks).doesNotHaveDuplicates();

        // 每块都必须自报模式名，模型不可能在未被察觉的情况下拿到错的指引
        assertThat(WikiPrompts.WIKI_GRANULARITY_GUIDANCE_FOCUSED).contains("FOCUSED");
        assertThat(WikiPrompts.WIKI_GRANULARITY_GUIDANCE_STANDARD).contains("STANDARD");
        assertThat(WikiPrompts.WIKI_GRANULARITY_GUIDANCE_EXHAUSTIVE).contains("EXHAUSTIVE");
    }

    // ═══════════════════════════════════════════════════════════════
    // 渲染器（替代 Go text/template）的对等性质
    // ═══════════════════════════════════════════════════════════════

    private static String renderChunkCitation(String candidateSlugs, String chunksXml, String lang) {
        return WikiPromptTemplate.render(WikiPrompts.WIKI_CHUNK_CITATION_PROMPT, Map.of(
                "CandidateSlugs", candidateSlugs,
                "ChunksXML", chunksXml,
                "Language", lang));
    }

    /**
     * TestWikiChunkCitationPrompt_StablePrefixAcrossBatches（Go L89-115）：
     * 同一文档内候选 slug 与静态规则不变，只有逐批的 {@code <chunks>} 块变化，
     * 因此 {@code <chunks>} 之前的全部内容必须逐字节相同。若静态规则排在
     * {@code <chunks>} 之后，它们会每一批都被重新计费，而这个前缀也会发散。
     */
    @Test
    @DisplayName("TestWikiChunkCitationPrompt_StablePrefixAcrossBatches：provider 前缀缓存的前提")
    void chunkCitationStablePrefixAcrossBatches() {
        String slugs = "entity/acme = Acme Corp\nconcept/rag = Retrieval-Augmented Generation";
        String a = renderChunkCitation(slugs, "<c id=\"c001\">first batch text</c>", "English");
        String b = renderChunkCitation(slugs, "<c id=\"c099\">a completely different second batch</c>", "English");

        // 匹配独立的 <chunks> 标签行（逐批数据块），而不是说明文字里的 "<chunks> block"
        String marker = "\n<chunks>\n";
        int ia = a.indexOf(marker);
        int ib = b.indexOf(marker);
        assertThat(ia).isGreaterThanOrEqualTo(0);
        assertThat(ib).isGreaterThanOrEqualTo(0);
        assertThat(a.substring(0, ia))
                .as("prompt prefix before <chunks> differs across batches — prefix cache will miss")
                .isEqualTo(b.substring(0, ib));

        // 静态规则与逐文档稳定的候选 slug 块必须在共享前缀里
        for (String must : List.of("### Primary task", "### JSON Formatting Rules", "\n<candidate_slugs>\n")) {
            int idx = a.indexOf(must);
            assertThat(idx)
                    .as("%s must appear before <chunks> to be part of the cached prefix", must)
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(ia);
        }
    }

    /**
     * TestWikiChunkCitationPrompt_PreservesPlaceholders（Go L119-125）：
     * 防止未来重排时误丢模板字段。
     */
    @Test
    @DisplayName("TestWikiChunkCitationPrompt_PreservesPlaceholders：模板字段一个都不能少")
    void chunkCitationPreservesPlaceholders() {
        for (String field : List.of("{{.Language}}", "{{.CandidateSlugs}}", "{{.ChunksXML}}")) {
            assertThat(WikiPrompts.WIKI_CHUNK_CITATION_PROMPT).contains(field);
        }
    }

    /**
     * TestWikiPageModifyUserPrompt_HidesInternalChunkHandles（Go L127-144）：
     * 页面上绝不能出现内联 chunk 句柄。
     */
    @Test
    @DisplayName("TestWikiPageModifyUserPrompt_HidesInternalChunkHandles：chunk 句柄措辞在位、过时措辞已删")
    void pageModifyHidesInternalChunkHandles() {
        String combined = WikiPrompts.WIKI_PAGE_MODIFY_SYSTEM_PROMPT + "\n"
                + WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT;
        for (String guidance : List.of(
                "NEVER output them in the page body or summary",
                "Source associations are stored separately by the system",
                "clean Markdown without inline chunk IDs")) {
            assertThat(combined)
                    .as("WikiPageModifyUserPrompt missing chunk-handle guidance %s", guidance)
                    .contains(guidance);
        }
        for (String obsolete : List.of("Preserve Citations", "followed by an inline citation")) {
            assertThat(combined)
                    .as("WikiPageModifyUserPrompt still contains obsolete inline-citation rule %s", obsolete)
                    .doesNotContain(obsolete);
        }
    }

    private static String renderPageModify(String sourceContexts, String title) {
        return WikiPromptTemplate.render(WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT, Map.of(
                "HasAdditions", "1",
                "SharedSourceContexts", sourceContexts,
                "PageSlug", "concept/" + title.toLowerCase(java.util.Locale.ROOT),
                "PageTitle", title,
                "PageType", "concept",
                "ExistingContent", "(New page)",
                "NewContent", "page-specific chunks for " + title,
                "Language", "English"));
    }

    /**
     * TestWikiPageModifyUserPrompt_SharedSourceContextPrecedesPageVariables（Go L168-183）：
     * 共享源上下文必须在 {@code <page_metadata>} 之前，才能成为可缓存前缀。
     */
    @Test
    @DisplayName("TestWikiPageModifyUserPrompt_SharedSourceContextPrecedesPageVariables：共享上下文领先页面变量")
    void pageModifySharedSourceContextPrecedesPageVariables() {
        String shared = "<document><title>Same Source</title>"
                + "<context>long shared summary</context></document>";
        String a = renderPageModify(shared, "Alpha");
        String b = renderPageModify(shared, "Beta");
        String marker = "\n<page_metadata>\n";
        int ia = a.indexOf(marker);
        int ib = b.indexOf(marker);
        assertThat(ia).isGreaterThanOrEqualTo(0);
        assertThat(ib).isGreaterThanOrEqualTo(0);
        assertThat(a.substring(0, ia))
                .as("shared source prefix differs across pages")
                .isEqualTo(b.substring(0, ib));
        assertThat(a.substring(0, ia))
                .as("shared source context is not part of the cacheable prefix")
                .contains(shared);
    }

    // ═══════════════════════════════════════════════════════════════
    // 渲染器语义（对照 Go text/template）
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WikiPromptTemplate 语义（对照 Go text/template）")
    class TemplateSemantics {

        @Test
        @DisplayName("{{.X}} 缺失键渲染成空串（Go 对 map 取不存在的键返回零值）")
        void missingKeyRendersEmpty() {
            assertThat(WikiPromptTemplate.render("a{{.Missing}}b", Map.of())).isEqualTo("ab");
            assertThat(WikiPromptTemplate.render("a{{.Missing}}b", null)).isEqualTo("ab");
        }

        @Test
        @DisplayName("{{if .X}}：非空为真、空串为假（Go 对字符串的真值判定）")
        void ifTruthiness() {
            assertThat(WikiPromptTemplate.render("[{{if .Has}}yes{{end}}]", Map.of("Has", "1")))
                    .isEqualTo("[yes]");
            assertThat(WikiPromptTemplate.render("[{{if .Has}}yes{{end}}]", Map.of("Has", "")))
                    .isEqualTo("[]");
            assertThat(WikiPromptTemplate.render("[{{if .Has}}yes{{end}}]", Map.of()))
                    .isEqualTo("[]");
        }

        @Test
        @DisplayName("{{if}} 可嵌套，配平扫描正确闭合")
        void nestedIf() {
            String tpl = "{{if .A}}A{{if .B}}B{{end}}{{end}}!";
            assertThat(WikiPromptTemplate.render(tpl, Map.of("A", "1", "B", "1"))).isEqualTo("AB!");
            assertThat(WikiPromptTemplate.render(tpl, Map.of("A", "1"))).isEqualTo("A!");
            assertThat(WikiPromptTemplate.render(tpl, Map.of("B", "1"))).isEqualTo("!");
        }

        @Test
        @DisplayName("不支持的构造（range/管道）抛异常——静默保留会把变量当字面量喂给模型")
        void unsupportedActionsThrow() {
            assertThatThrownBy(() -> WikiPromptTemplate.render("{{range .Items}}x{{end}}", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsupported template action");
            assertThatThrownBy(() -> WikiPromptTemplate.render("{{.A | printf}}", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> WikiPromptTemplate.render("{{end}}", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no matching {{if}}");
        }

        @Test
        @DisplayName("{{end}} 缺失时抛异常")
        void missingEndThrows() {
            assertThatThrownBy(() -> WikiPromptTemplate.render("{{if .A}}x", Map.of("A", "1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing {{end}}");
        }

        @Test
        @DisplayName("示例里的 JSON 单花括号不受影响（只有 {{ 才是 action）")
        void singleBracesUntouched() {
            assertThat(WikiPromptTemplate.render("{\"a\":{\"b\":1}}", Map.of()))
                    .isEqualTo("{\"a\":{\"b\":1}}");
        }
    }

    @Nested
    @DisplayName("purposeOf（对照 Go wikiPromptPurpose）")
    class Purpose {

        @ParameterizedTest(name = "{0}")
        @DisplayName("8 个模板各自映射到 Go 的 purpose 字面量")
        @CsvSource({
                "wiki_page_modify",
                "wiki_chunk_citation",
                "wiki_candidate_slug",
                "wiki_summary",
                "wiki_knowledge_extract",
                "wiki_taxonomy_plan",
                "wiki_deduplication",
                "wiki_index_intro"
        })
        void purposes(String expected) {
            String tpl = switch (expected) {
                case "wiki_page_modify" -> WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT;
                case "wiki_chunk_citation" -> WikiPrompts.WIKI_CHUNK_CITATION_PROMPT;
                case "wiki_candidate_slug" -> WikiPrompts.WIKI_CANDIDATE_SLUG_PROMPT;
                case "wiki_summary" -> WikiPrompts.WIKI_SUMMARY_PROMPT;
                case "wiki_knowledge_extract" -> WikiPrompts.WIKI_KNOWLEDGE_EXTRACT_PROMPT;
                case "wiki_taxonomy_plan" -> WikiPrompts.WIKI_TAXONOMY_PLAN_PROMPT;
                case "wiki_deduplication" -> WikiPrompts.WIKI_DEDUPLICATION_PROMPT;
                case "wiki_index_intro" -> WikiPrompts.WIKI_INDEX_INTRO_PROMPT;
                default -> throw new IllegalStateException(expected);
            };
            assertThat(WikiPrompts.purposeOf(tpl)).isEqualTo(expected);
        }

        @Test
        @DisplayName("索引导语的更新版与首版共享同一个 purpose")
        void indexIntroVariantsSharePurpose() {
            assertThat(WikiPrompts.purposeOf(WikiPrompts.WIKI_INDEX_INTRO_UPDATE_PROMPT))
                    .isEqualTo("wiki_index_intro");
        }

        @Test
        @DisplayName("未知模板 / null → wiki_generation（对照 Go 的 default 分支）")
        void unknownTemplateFallsBack() {
            assertThat(WikiPrompts.purposeOf("some other prompt")).isEqualTo("wiki_generation");
            assertThat(WikiPrompts.purposeOf(null)).isEqualTo("wiki_generation");
        }
    }
}
