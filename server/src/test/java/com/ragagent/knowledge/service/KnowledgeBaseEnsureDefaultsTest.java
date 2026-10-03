package com.ragagent.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.domain.KnowledgeBase;

/**
 * 覆盖 {@code KnowledgeBaseService.ensureDefaults}。
 *
 * <p>2026-09-25 线上抓回（用户报"导入文档后新建提问看不到推荐问题"）：DB 里**零值**的
 * `indexing_strategy`（四标志全 false，如 FAQ 型 `faq-golden-kb`）在 Java 读出
 * vector/keyword=false ⇒ `capabilities()` 全假 ⇒ 被 quick-answer 的能力过滤丢弃 ⇒
 * `/agents/{id}/suggested-questions`（无 KB 范围）返回空数组 ✗；读路径应调
 * `ensureDefaults()` 把零值回填成 `DefaultIndexingStrategy()`（vector+keyword=true）✓。
 * 根因是 **list 路径漏调** 本类的 {@code ensureDefaults}（get 路径 `getAllTenantById` 一直在调 ✓）。</p>
 */
class KnowledgeBaseEnsureDefaultsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KnowledgeBase kb(String type) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-test");
        kb.setType(type);
        return kb;
    }

    private static KnowledgeBaseIndexingStrategy allFalse() {
        return new KnowledgeBaseIndexingStrategy();
    }

    @Test
    @DisplayName("零值策略 ⇒ 回填默认（vector+keyword=true）——本次线上缺陷的判定点")
    void zeroStrategyGetsDefault() {
        KnowledgeBase kb = kb("faq");
        kb.setIndexingStrategy(allFalse());

        KnowledgeBaseService.ensureDefaults(kb);

        assertThat(kb.getIndexingStrategy().isVectorEnabled()).isTrue();
        assertThat(kb.getIndexingStrategy().isKeywordEnabled()).isTrue();
        assertThat(kb.getIndexingStrategy().isWikiEnabled()).isFalse();
        assertThat(kb.getIndexingStrategy().isGraphEnabled()).isFalse();
    }

    @Test
    @DisplayName("非零策略原样保留（只要有一个标志为真就不回默认）")
    void nonZeroStrategyUntouched() {
        KnowledgeBase kb = kb("document");
        KnowledgeBaseIndexingStrategy s = allFalse();
        s.setWikiEnabled(true);
        kb.setIndexingStrategy(s);

        KnowledgeBaseService.ensureDefaults(kb);

        assertThat(kb.getIndexingStrategy().isWikiEnabled()).isTrue();
        assertThat(kb.getIndexingStrategy().isVectorEnabled()).isFalse();
        assertThat(kb.getIndexingStrategy().isKeywordEnabled()).isFalse();
    }

    @Test
    @DisplayName("legacy extract_config.enabled ⇒ graph_enabled 同步")
    void extractConfigSyncsGraphFlag() throws Exception {
        KnowledgeBase kb = kb("document");
        KnowledgeBaseIndexingStrategy s = allFalse();
        s.setWikiEnabled(true); // 避免零值分支，专测同步
        kb.setIndexingStrategy(s);
        kb.setExtractConfig(MAPPER.readTree("{\"enabled\": true}"));

        KnowledgeBaseService.ensureDefaults(kb);

        assertThat(kb.getIndexingStrategy().isGraphEnabled()).isTrue();
    }

    @Test
    @DisplayName("类型与类型专属配置的默认/清理")
    void typeAndTypeSpecificConfigDefaults() throws Exception {
        KnowledgeBase empty = kb("");
        empty.setIndexingStrategy(allFalse());
        KnowledgeBaseService.ensureDefaults(empty);
        assertThat(empty.getType()).isEqualTo("document");
        assertThat(empty.getFaqConfig()).isNull();

        KnowledgeBase doc = kb("document");
        doc.setIndexingStrategy(allFalse());
        doc.setFaqConfig(MAPPER.readTree("{\"indexMode\":\"question_only\"}"));
        doc.setAutoTagConfig(MAPPER.readTree("{\"enabled\":true}"));
        KnowledgeBaseService.ensureDefaults(doc);
        assertThat(doc.getFaqConfig()).as("非 faq 类型清掉 faq_config").isNull();
        assertThat(doc.getAutoTagConfig()).as("document 保留 auto_tag_config").isNotNull();
    }
}
