package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.SuggestionItem;

/**
 * 追问建议生成的纯函数面（对照 Go message_suggestion.go 的
 * parseGeneratedSuggestions / merge / rank / history-render 家族）。
 * LLM 步经 stub chat 客户端另行覆盖（A/B 用）。
 */
class MessageSuggestionGenerateTest {

    private static SuggestionItem item(String text) {
        SuggestionItem i = new SuggestionItem();
        i.setId("id-" + text);
        i.setText(text);
        return i;
    }

    /** 对照 parseGeneratedSuggestions：剥 think / 抠最外层 {} / 200 码点上限 /
     *  去重 / 类别白名单外置空。 */
    @Test
    void parseGeneratedSuggestionsFiltersAndValidates() {
        String content = "<think>\n思考过程</think>\nHere are some:\n"
                + "{\"questions\":["
                + "{\"text\":\"什么是 RAG？\",\"category\":\"clarify\"},"
                + "{\"text\":\"什么是 RAG？\",\"category\":\"clarify\"}," // 去重
                + "{\"text\":\"" + "长".repeat(201) + "\",\"category\":\"clarify\"}," // 超长
                + "{\"text\":\"如何部署？\",\"category\":\"bogus\"}," // 白名单外 → 空类别
                + "{\"text\":\"   \",\"category\":\"clarify\"}," // 空文本
                + "{\"text\":\"如何评测？\",\"category\":\"deepen\"}"
                + "]}";
        List<SuggestionItem> items = MessageSuggestionService.parseGeneratedSuggestions(
                content, List.of("clarify", "deepen"), 10);
        assertThat(items).hasSize(3);
        assertThat(items.get(0).getText()).isEqualTo("什么是 RAG？");
        assertThat(items.get(0).getCategory()).isEqualTo("clarify");
        assertThat(items.get(0).getSource()).isEqualTo("model");
        // 白名单外的类别置空但条目保留（Go: category = ""）
        assertThat(items.get(1).getText()).isEqualTo("如何部署？");
        assertThat(items.get(1).getCategory()).isEmpty();
        assertThat(items.get(2).getText()).isEqualTo("如何评测？");
    }

    /** 非 JSON / 无大括号 → model returned invalid suggestion JSON。 */
    @Test
    void parseGeneratedSuggestionsRejectsNonJson() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> MessageSuggestionService.parseGeneratedSuggestions("no json here", List.of(), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid suggestion JSON");
    }

    /** 对照 mergeSuggestionItems：primary 优先、normalize 去重、limit 截断。 */
    @Test
    void mergeDedupesAndLimits() {
        SuggestionItem a = item("What is RAG?");
        SuggestionItem b = item("what is rag"); // normalize 后与 a 同键（标点/大小写）
        SuggestionItem c = item("How to deploy?");
        List<SuggestionItem> merged = MessageSuggestionService.mergeSuggestionItems(
                List.of(a, c), List.of(b), 2);
        assertThat(merged).containsExactly(a, c);
    }

    /** 对照 mergeHybridSuggestionItems：knowledge 槽 = ⌈limit/3⌉，双方互填。 */
    @Test
    void hybridReservesKnowledgeSlots() {
        List<SuggestionItem> model = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            model.add(item("model-q-" + i));
        }
        List<SuggestionItem> knowledge = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            knowledge.add(item("kb-q-" + i));
        }
        // limit=6 → knowledge 槽 2，model 槽 4；先 model4 → knowledge2 → 互填不足 6 则回补
        List<SuggestionItem> merged = MessageSuggestionService.mergeHybridSuggestionItems(
                model, knowledge, 6);
        assertThat(merged).hasSize(6);
        assertThat(merged.subList(0, 4)).allMatch(i -> i.getText().startsWith("model-q-"));
        assertThat(merged.subList(4, 6)).allMatch(i -> i.getText().startsWith("kb-q-"));
    }

    /** 对照 buildSuggestionGenerationContext：轮次分组 + 当前轮定位 + 历史渲染。 */
    @Test
    void generationContextGroupsTurnsAndLocatesCurrent() {
        Message u1 = msg("user", "问题一", "req-1");
        Message a1 = msg("assistant", "回答一", "req-1");
        u1.setId("m1");
        a1.setId("m2");
        a1.setCompleted(true);
        Message u2 = msg("user", "问题二", "req-2");
        Message a2 = msg("assistant", "回答二", "req-2");
        u2.setId("m3");
        a2.setId("m4");
        a2.setCompleted(true);

        MessageSuggestionService.GenerationContext ctx =
                MessageSuggestionService.buildSuggestionGenerationContext(
                        List.of(u1, a1, u2, a2), a2, 2);
        // 当前轮 user 问题 = 问题二；历史 = 上一轮（更早轮被 maxTurns-1 截断）
        assertThat(ctx.currentQuery()).isEqualTo("问题二");
        assertThat(ctx.history()).contains("user: 问题一").contains("assistant: 回答一");
        assertThat(ctx.history()).doesNotContain("问题二");
    }

    /** request_id 缺失的 legacy 行：assistant 配对最近一个未匹配匿名 user。 */
    @Test
    void legacyRowsPairByProximity() {
        Message u1 = msg("user", "老问题", null);
        Message a1 = msg("assistant", "老回答", null);
        u1.setId("n1");
        a1.setId("n2");
        MessageSuggestionService.GenerationContext ctx =
                MessageSuggestionService.buildSuggestionGenerationContext(
                        List.of(u1, a1), a1, 2);
        assertThat(ctx.currentQuery()).isEqualTo("老问题");
    }

    /** 对照 truncateRunes：码点截断不切断多字节字符。 */
    @Test
    void truncateRunesByCodePoint() {
        String s = "aé𐍈b"; // 4 码点（𐍈 是增补平面）
        assertThat(MessageSuggestionService.truncateRunes(s, 3)).isEqualTo("aé𐍈");
        assertThat(MessageSuggestionService.truncateRunes(s, 10)).isEqualTo(s);
    }

    /** 对照 suggestionRelevanceTokens：去标点、滤单 rune、小写。 */
    @Test
    void relevanceTokensCleanAndFilter() {
        Set<String> tokens = MessageSuggestionService.suggestionRelevanceTokens(
                "What is RAG?");
        assertThat(tokens).contains("what", "is", "rag");
        assertThat(tokens).noneMatch(t -> t.equals("?") || t.equals("a"));
    }

    /** rankKnowledgeSuggestions：与上下文重叠多的排前。 */
    @Test
    void rankOrdersByRelevance() {
        List<Object[]> candidates = new java.util.ArrayList<>();
        candidates.add(new Object[] {"向量数据库如何选型", "faq", "kb-1"});
        candidates.add(new Object[] {"RAG 检索增强生成是什么", "document", "kb-1"});
        String context = "请解释 RAG 检索增强生成的原理";
        MessageSuggestionService.rankKnowledgeSuggestions(candidates, context);
        assertThat((String) candidates.get(0)[0]).isEqualTo("RAG 检索增强生成是什么");
    }

    /** 对照 buildSuggestionEvidence：按分排序 + 去重 + 截 5 条 + knowledgeIDs 收集。 */
    @Test
    void evidenceSortedDedupedAndCapped() {
        Message current = new Message();
        com.ragagent.retrieval.domain.SearchResult low =
                new com.ragagent.retrieval.domain.SearchResult();
        low.setId("r1");
        low.setScore(0.2);
        low.setKnowledgeId("k-1");
        low.setKnowledgeTitle("文档一");
        low.setContent("低分证据内容");
        com.ragagent.retrieval.domain.SearchResult high =
                new com.ragagent.retrieval.domain.SearchResult();
        high.setId("r2");
        high.setScore(0.9);
        high.setKnowledgeId("k-2");
        high.setKnowledgeTitle("文档二");
        high.setContent("高分证据内容");
        com.ragagent.retrieval.domain.SearchResult dup =
                new com.ragagent.retrieval.domain.SearchResult();
        dup.setId("r1"); // 与 low 同 id（0.8 分）→ 先于 low(0.2) 处理，low 被去重
        dup.setScore(0.8);
        dup.setKnowledgeId("k-1");
        dup.setKnowledgeTitle("文档一");
        dup.setContent("重复引用");
        current.setKnowledgeReferences(new java.util.ArrayList<>(
                List.of(low, high, dup)));

        MessageSuggestionService.Evidence evidence =
                MessageSuggestionService.buildSuggestionEvidence(current);
        assertThat(evidence.text()).contains("[1] 文档二: 高分证据内容");
        assertThat(evidence.text()).contains("[2] 文档一: 重复引用");
        assertThat(evidence.text()).doesNotContain("低分证据内容"); // 同 id 的低分行被去重
        assertThat(evidence.knowledgeIds()).containsExactly("k-2", "k-1");
    }

    private static Message msg(String role, String content, String requestId) {
        Message m = new Message();
        m.setRole(role);
        m.setContent(content);
        m.setRequestId(requestId);
        return m;
    }
}
