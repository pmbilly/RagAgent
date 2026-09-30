package com.ragagent.wiki.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.wiki.domain.TaskPendingOp;
import com.ragagent.wiki.prompt.WikiPromptTemplate;
import com.ragagent.wiki.prompt.WikiPrompts;
import com.ragagent.common.wiki.SlugUpdate;
import com.ragagent.common.wiki.WikiLanguageSupport;
import com.ragagent.common.wiki.GoStrings;

/**
 * 语言持久化与 prompt 渲染的对等测试（对照 Go
 * internal/application/service/wiki_ingest_language_test.go，134 行，用例逐条翻译）。
 *
 * <p>Go 的 {@code ctx} 语言 → Java 的 {@link WikiLanguageSupport#setCurrentLocale}
 * （线程本地），语义一一对应：入队时落定 locale，下游 worker 从 op 载荷读。</p>
 */
class WikiIngestLanguageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearLocale() {
        WikiLanguageSupport.clearCurrentLocale();
    }

    // ── TestNewWikiIngestPendingOpPersistsResolvedLanguage（Go L18-44） ──

    @Test
    @DisplayName("TestNewWikiIngestPendingOpPersistsResolvedLanguage：请求 locale 被保留")
    void persistsRequestLocale() throws Exception {
        WikiLanguageSupport.setCurrentLocale("en-US");

        TaskPendingOp row = new WikiIngestService(null, null, null, null, null, null,
                null, null, null, null, null, null, null)
                .newWikiIngestPendingOp(7, "kb-1", "knowledge-1");

        WikiPendingOp op = MAPPER.treeToValue(row.getPayload(), WikiPendingOp.class);
        assertThat(op.getLanguage()).isEqualTo("en-US");
        assertThat(row.getTenantId()).isEqualTo(7L);
        assertThat(row.getTaskType()).isEqualTo(WikiIngestConstants.TASK_TYPE);
        assertThat(row.getScope()).isEqualTo(WikiIngestConstants.TASK_SCOPE);
        assertThat(row.getScopeId()).isEqualTo("kb-1");
        assertThat(row.getOp()).isEqualTo(WikiIngestConstants.OP_INGEST);
        assertThat(row.getDedupKey()).isEqualTo("knowledge-1");
    }

    @Test
    @DisplayName("TestNewWikiIngestPendingOpPersistsResolvedLanguage：无语言的后台上下文回落默认值")
    void fallsBackToDefaultLanguage() throws Exception {
        WikiLanguageSupport.clearCurrentLocale();

        TaskPendingOp row = new WikiIngestService(null, null, null, null, null, null,
                null, null, null, null, null, null, null)
                .newWikiIngestPendingOp(7, "kb-1", "knowledge-1");

        WikiPendingOp op = MAPPER.treeToValue(row.getPayload(), WikiPendingOp.class);
        assertThat(op.getLanguage()).isEqualTo(WikiLanguageSupport.defaultLanguage());
        // 关键不变量：绝不持久化空串
        assertThat(op.getLanguage()).isNotEmpty();
    }

    // ── TestResolveLanguageNameRecoversLegacyPendingOp（Go L49-57） ──

    @Test
    @DisplayName("TestResolveLanguageNameRecoversLegacyPendingOp：语言字段为空的旧行仍产出本地化页面")
    void recoversLegacyPendingOp() {
        WikiLanguageSupport.setCurrentLocale("ko-KR");
        // 旧行的 Language 是空串 —— worker 必须拿 ctx + 默认值解析，而不是信载荷
        assertThat(WikiLanguageSupport.resolveLanguageName("")).isEqualTo("Korean");

        WikiLanguageSupport.clearCurrentLocale();
        assertThat(WikiLanguageSupport.resolveLanguageName("")).isNotEmpty();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("locale → 人类可读语言名（对照 Go LanguageLocaleName）")
    @CsvSource({
            "zh-CN, Chinese (Simplified)",
            "zh, Chinese (Simplified)",
            "zh-Hans, Chinese (Simplified)",
            "zh-TW, Chinese (Traditional)",
            "en-US, English",
            "en, English",
            "ko-KR, Korean",
            "ja-JP, Japanese",
            "ru-RU, Russian",
            "fr-FR, French",
            "de-DE, German",
            "es-ES, Spanish",
            "pt-BR, Portuguese",
            // 未知 locale 原样透传（Go 的 default 分支）
            "xx-YY, xx-YY"
    })
    void localeNameMapping(String locale, String want) {
        assertThat(WikiLanguageSupport.localeName(locale)).isEqualTo(want);
    }

    // ── TestResolveSlugUpdateLanguage（Go L59-98） ──

    @Test
    @DisplayName("TestResolveSlugUpdateLanguage：4 个子用例逐条对照")
    void resolveSlugUpdateLanguage() {
        WikiLanguageSupport.setCurrentLocale("en-US");

        // 用更新自己携带的语言
        assertThat(WikiLanguageSupport.resolveSlugUpdateLanguage(
                List.of(slugUpdate("Korean")))).isEqualTo("Korean");

        // 页面会聚合多篇文档的贡献，第一个不保证带语言
        assertThat(WikiLanguageSupport.resolveSlugUpdateLanguage(
                List.of(slugUpdateWithType("retract", ""), slugUpdate("Korean"))))
                .isEqualTo("Korean");

        // 回落到请求语言
        assertThat(WikiLanguageSupport.resolveSlugUpdateLanguage(
                List.of(slugUpdateWithType("retract", ""), slugUpdateWithType("entity", ""))))
                .isEqualTo("English");

        // 空更新集同样回落
        assertThat(WikiLanguageSupport.resolveSlugUpdateLanguage(null)).isEqualTo("English");
        assertThat(WikiLanguageSupport.resolveSlugUpdateLanguage(List.of())).isEqualTo("English");
    }

    private static SlugUpdate slugUpdate(String language) {
        SlugUpdate u = new SlugUpdate("entity/x", SlugUpdate.TYPE_ENTITY);
        u.setLanguage(language);
        return u;
    }

    private static SlugUpdate slugUpdateWithType(String type, String language) {
        SlugUpdate u = new SlugUpdate("entity/x", type);
        u.setLanguage(language);
        return u;
    }

    // ── TestWikiPromptsNeverRenderAnEmptyLanguage（Go L102-134） ──

    /**
     * 护栏针对的可观测症状：未解析的语言会把编辑指令渲染成 {@code "Write in ."}，
     * 从而把输出语言交给模型自行决定。
     */
    @Test
    @DisplayName("TestWikiPromptsNeverRenderAnEmptyLanguage：8 个 prompt 都不会渲染出空语言")
    void promptsNeverRenderEmptyLanguage() {
        Map<String, String> prompts = Map.of(
                "WikiPageModifyUserPrompt", WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT,
                "WikiSummaryPrompt", WikiPrompts.WIKI_SUMMARY_PROMPT,
                "WikiIndexIntroPrompt", WikiPrompts.WIKI_INDEX_INTRO_PROMPT,
                "WikiIndexIntroUpdatePrompt", WikiPrompts.WIKI_INDEX_INTRO_UPDATE_PROMPT,
                "WikiKnowledgeExtractPrompt", WikiPrompts.WIKI_KNOWLEDGE_EXTRACT_PROMPT,
                "WikiCandidateSlugPrompt", WikiPrompts.WIKI_CANDIDATE_SLUG_PROMPT,
                "WikiChunkCitationPrompt", WikiPrompts.WIKI_CHUNK_CITATION_PROMPT,
                "WikiTaxonomyPlanPrompt", WikiPrompts.WIKI_TAXONOMY_PLAN_PROMPT);

        for (Map.Entry<String, String> e : prompts.entrySet()) {
            String rendered = WikiPromptTemplate.render(e.getValue(), Map.of(
                    "HasAdditions", "1",
                    "Language", WikiLanguageSupport.resolveLanguageName("")));
            assertThat(rendered)
                    .as("%s rendered without a language", e.getKey())
                    .doesNotContain("Write in .")
                    .doesNotContain("in <no value>");
        }
    }

    // ── Java 侧补充：解析链与 goSpace 语义 ──

    @Test
    @DisplayName("resolveLanguage：显式 locale 优先于上下文，上下文优先于默认")
    void resolveLanguagePrecedence() {
        WikiLanguageSupport.setCurrentLocale("ko-KR");
        assertThat(WikiLanguageSupport.resolveLanguage("ja-JP")).isEqualTo("ja-JP");
        assertThat(WikiLanguageSupport.resolveLanguage("  ")).isEqualTo("ko-KR");
        WikiLanguageSupport.clearCurrentLocale();
        assertThat(WikiLanguageSupport.resolveLanguage("")).isEqualTo(WikiLanguageSupport.defaultLanguage());
    }

    @Test
    @DisplayName("GoStrings.trimSpace 覆盖 Go 的空白定义（含 NBSP / 全角空格）")
    void goTrimSpaceCoversGoWhitespace() {
        assertThat(GoStrings.trimSpace("  x  ")).isEqualTo("x");
        assertThat(GoStrings.trimSpace("　x　")).isEqualTo("x");
        assertThat(GoStrings.trimSpace("  x  ")).isEqualTo("x");
        // Java 的 String.strip() 不会裁 NBSP，这正是本工具存在的理由
        assertThat(" x ".strip()).isNotEqualTo("x");
    }
}
