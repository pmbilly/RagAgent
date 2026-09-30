package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.retrieval.support.SearchTextUtil;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.SuggestionItem;
import com.ragagent.session.service.MessageSuggestionService.Evidence;
import com.ragagent.session.service.MessageSuggestionService.GenerationContext;

/**
 * {@code MessageSuggestionService} 的**无状态管道切片**（§14 步骤 2）：生成输入的上下文装配
 * （轮次分组 → 历史渲染 → 证据抽取）与输出的解析 / 合并 / 相关性排序 / 文本规范化。
 *
 * <p>为什么单独一类：这些成员全是静态纯函数（唯一读实例字段的 {@code buildGenerationContext}
 * 留在门面，由它调用这里的 {@code buildSuggestionGenerationContext}），不碰仓储/模型/租户上下文，
 * 与门面只有单向调用。两个跨簇共享的 record（{@code GenerationContext} / {@code Evidence}）
 * 按 §14.9 先例留在门面，本类 import 其嵌套类型；检索模式词表 {@code MODE_HYBRID} 门面也在用，
 * 本类按类名引用。</p>
 */
final class MessageSuggestionPipeline {

    private static final String THINK_BLOCK = "(?s)<think>.*?</think>";
    private static final java.util.regex.Pattern THINK_PATTERN =
            java.util.regex.Pattern.compile(THINK_BLOCK);
    private static final String TRAILING_CITATIONS = "(?s)(?:\\s*<(?:kb|web)>.*?</(?:kb|web)>)+\\s*$";
    private static final int SUGGESTION_HISTORY_RUNE_BUDGET = 6000;
    private static final int SUGGESTION_HISTORY_MESSAGE_RUNE_LIMIT = 2500;
    private static final int SUGGESTION_EVIDENCE_MAX_ITEMS = 5;
    private static final int SUGGESTION_EVIDENCE_SNIPPET_RUNE_LIMIT = 500;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final class ConversationTurn {
        String requestId = "";
        Message user;
        Message assistant;
    }

    static GenerationContext buildSuggestionGenerationContext(List<Message> messages,
            Message current, int maxTurns) {
        if (maxTurns < 1) {
            maxTurns = 2;
        }
        List<ConversationTurn> turns = groupSuggestionConversationTurns(messages);
        int currentIndex = -1;
        String currentQuery = "";
        for (int i = 0; i < turns.size(); i++) {
            ConversationTurn turn = turns.get(i);
            if (turn.assistant == null || current == null
                    || !turn.assistant.getId().equals(current.getId())) {
                continue;
            }
            currentIndex = i;
            if (turn.user != null) {
                // 建议用用户可见的问题；RenderedContent 带 RAG prompt 与原始块，刻意排除
                currentQuery = trimToEmpty(turn.user.getContent());
            }
            break;
        }
        if (currentIndex < 0) {
            currentIndex = turns.size();
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message candidate = messages.get(i);
                if (candidate != null && "user".equals(candidate.getRole())
                        && (current == null || current.getRequestId() == null
                                || current.getRequestId().isEmpty()
                                || candidate.getRequestId().equals(current.getRequestId()))) {
                    currentQuery = trimToEmpty(candidate.getContent());
                    break;
                }
            }
        }

        int previousLimit = maxTurns - 1; // maxTurns 含当前完成轮
        List<ConversationTurn> previous = new ArrayList<>(previousLimit);
        for (int i = currentIndex - 1; i >= 0 && previous.size() < previousLimit; i--) {
            ConversationTurn turn = turns.get(i);
            if (turn.user == null || turn.assistant == null || !turn.assistant.isCompleted()) {
                continue;
            }
            previous.add(turn);
        }
        java.util.Collections.reverse(previous);

        Evidence evidence = buildSuggestionEvidence(current);
        return new GenerationContext(renderSuggestionHistory(previous), currentQuery,
                evidence.text(), evidence.knowledgeIds());
    }

    private static List<ConversationTurn> groupSuggestionConversationTurns(List<Message> messages) {
        List<ConversationTurn> turns = new ArrayList<>(messages.size() / 2 + 1);
        Map<String, Integer> byRequestId = new HashMap<>();
        for (Message message : messages) {
            if (message == null
                    || (!"user".equals(message.getRole()) && !"assistant".equals(message.getRole()))) {
                continue;
            }
            String requestId = message.getRequestId();
            if (requestId != null && !requestId.isEmpty()) {
                Integer idx = byRequestId.get(requestId);
                if (idx == null) {
                    idx = turns.size();
                    byRequestId.put(requestId, idx);
                    ConversationTurn turn = new ConversationTurn();
                    turn.requestId = requestId;
                    turns.add(turn);
                }
                ConversationTurn turn = turns.get(idx);
                if ("user".equals(message.getRole())) {
                    turn.user = message;
                } else {
                    turn.assistant = message;
                }
                continue;
            }
            // 老数据没有 request_id：assistant 配对最近一个未匹配的匿名 user
            if ("user".equals(message.getRole())) {
                ConversationTurn turn = new ConversationTurn();
                turn.user = message;
                turns.add(turn);
                continue;
            }
            boolean attached = false;
            for (int i = turns.size() - 1; i >= 0; i--) {
                ConversationTurn turn = turns.get(i);
                if (turn.requestId.isEmpty() && turn.user != null && turn.assistant == null) {
                    turn.assistant = message;
                    attached = true;
                    break;
                }
            }
            if (!attached) {
                ConversationTurn turn = new ConversationTurn();
                turn.assistant = message;
                turns.add(turn);
            }
        }
        return turns;
    }

    static String renderSuggestionHistory(List<ConversationTurn> turns) {
        if (turns.isEmpty()) {
            return "";
        }
        List<String> blocks = new ArrayList<>(turns.size());
        int remaining = SUGGESTION_HISTORY_RUNE_BUDGET;
        for (int i = turns.size() - 1; i >= 0 && remaining > 0; i--) {
            String userContent = cleanSuggestionContent(turns.get(i).user.getContent(),
                    SUGGESTION_HISTORY_MESSAGE_RUNE_LIMIT);
            String assistantContent = cleanSuggestionContent(turns.get(i).assistant.getContent(),
                    SUGGESTION_HISTORY_MESSAGE_RUNE_LIMIT);
            if (userContent.isEmpty() || assistantContent.isEmpty()) {
                continue;
            }
            String block = "user: " + userContent + "\nassistant: " + assistantContent;
            block = truncateRunes(block, remaining);
            blocks.add(block);
            remaining -= block.codePointCount(0, block.length());
        }
        java.util.Collections.reverse(blocks);
        return String.join("\n", blocks);
    }

    private static String cleanSuggestionContent(String content, int limit) {
        String cleaned = THINK_PATTERN.matcher(content == null ? "" : content).replaceAll("").trim();
        return truncateRunes(cleaned, limit);
    }


    static Evidence buildSuggestionEvidence(Message current) {
        if (current == null || current.getKnowledgeReferences() == null
                || current.getKnowledgeReferences().isEmpty()) {
            return new Evidence("", List.of());
        }
        List<SearchResult> refs = new ArrayList<>(current.getKnowledgeReferences());
        refs.sort((left, right) -> Double.compare(
                left == null ? 0 : left.getScore(),
                right == null ? 0 : right.getScore()) * -1);

        Set<String> seenRefs = new HashSet<>();
        Set<String> seenKnowledge = new HashSet<>();
        List<String> knowledgeIds = new ArrayList<>(SUGGESTION_EVIDENCE_MAX_ITEMS);
        List<String> lines = new ArrayList<>(SUGGESTION_EVIDENCE_MAX_ITEMS);
        for (SearchResult ref : refs) {
            if (ref == null) {
                continue;
            }
            String refKnowledgeId = ref.getKnowledgeId() == null ? "" : ref.getKnowledgeId();
            if (!refKnowledgeId.isEmpty() && seenKnowledge.add(refKnowledgeId)) {
                knowledgeIds.add(refKnowledgeId);
            }
            String key = ref.getId() == null || ref.getId().isEmpty()
                    ? refKnowledgeId + ":" + ref.getChunkIndex() + ":"
                            + (ref.getKnowledgeTitle() == null ? "" : ref.getKnowledgeTitle())
                    : ref.getId();
            if (!seenRefs.add(key)) {
                continue;
            }
            String title = firstNonEmptyString(ref.getKnowledgeTitle(), ref.getKnowledgeFilename(),
                    ref.getKnowledgeSource(), "source " + (lines.size() + 1));
            String snippet = firstNonEmptyString(ref.getContent(), ref.getMatchedContent(),
                    ref.getKnowledgeDescription());
            String[] parts = cleanSuggestionContent(snippet,
                    SUGGESTION_EVIDENCE_SNIPPET_RUNE_LIMIT).split("\\s+");
            snippet = String.join(" ", parts).trim();
            if (snippet.isEmpty()) {
                continue;
            }
            lines.add("[" + (lines.size() + 1) + "] " + title + ": " + snippet);
            if (lines.size() == SUGGESTION_EVIDENCE_MAX_ITEMS) {
                break;
            }
        }
        return new Evidence(String.join("\n", lines), knowledgeIds);
    }

    static void rankKnowledgeSuggestions(List<Object[]> candidates, String contextText) {
        Set<String> contextTokens = suggestionRelevanceTokens(contextText);
        String contextNormalized = SearchTextUtil.normalizeContent(contextText);
        // Go sort.SliceStable：稳定排序（同 relevance 保持原序）——List.sort 即稳定
        candidates.sort((left, right) -> Double.compare(
                knowledgeSuggestionRelevance((String) right[0], contextTokens, contextNormalized),
                knowledgeSuggestionRelevance((String) left[0], contextTokens, contextNormalized)));
    }

    static double knowledgeSuggestionRelevance(String question,
            Set<String> contextTokens, String contextNormalized) {
        String questionNormalized = SearchTextUtil.normalizeContent(question);
        if (questionNormalized.isEmpty()) {
            return 0;
        }
        Set<String> questionTokens = suggestionRelevanceTokens(question);
        double score = SearchTextUtil.jaccard(questionTokens, contextTokens);
        double overlap = suggestionTokenOverlap(questionTokens, contextTokens);
        if (overlap > score) {
            score = overlap;
        }
        if (SearchTextUtil.isContentContained(questionNormalized, contextNormalized)) {
            score++;
        }
        return score;
    }

    static Set<String> suggestionRelevanceTokens(String text) {
        Set<String> raw = SearchTextUtil.tokenizeSimple(text);
        Set<String> cleaned = new HashSet<>(raw.size());
        for (String token : raw) {
            int begin = 0;
            int end = token.length();
            while (begin < end
                    && !Character.isLetterOrDigit(token.charAt(begin))) {
                begin++;
            }
            while (end > begin
                    && !Character.isLetterOrDigit(token.codePointBefore(end))) {
                end--;
            }
            if (end > begin) {
                String trimmed = token.substring(begin, end);
                if (trimmed.codePointCount(0, trimmed.length()) > 1) {
                    cleaned.add(trimmed);
                }
            }
        }
        return cleaned;
    }

    private static double suggestionTokenOverlap(Set<String> candidate, Set<String> contextTokens) {
        if (candidate.isEmpty() || contextTokens.isEmpty()) {
            return 0;
        }
        int intersection = 0;
        for (String token : candidate) {
            if (contextTokens.contains(token)) {
                intersection++;
            }
        }
        return (double) intersection / candidate.size();
    }

    private static String firstNonEmptyString(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    static String emptySuggestionSection(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? "(none)" : trimmed;
    }

    /** 对照 Go truncateRunes：按码点截断（不切断多字节字符）。 */
    static String truncateRunes(String s, int limit) {
        if (s == null) {
            return "";
        }
        if (s.codePointCount(0, s.length()) <= limit) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, limit));
    }

    /** 对照 parseGeneratedSuggestions（L816-865）：剥 think → 抠 {} → 解析 →
     *  200 码点上限 / 去重 / 类别白名单。 */
    static List<SuggestionItem> parseGeneratedSuggestions(String content,
            List<String> allowedCategories, int limit) {
        String cleaned = THINK_PATTERN.matcher(content == null ? "" : content).replaceAll("").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalStateException("model returned invalid suggestion JSON");
        }
        JsonNode envelope;
        try {
            envelope = JSON_MAPPER.readTree(cleaned.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalStateException("decode suggestion JSON: " + e.getMessage(), e);
        }
        Set<String> allowed = new HashSet<>(allowedCategories == null ? List.of() : allowedCategories);
        Set<String> seen = new HashSet<>();
        List<SuggestionItem> items = new ArrayList<>(limit);
        JsonNode questions = envelope.path("questions");
        if (!questions.isArray()) {
            return items;
        }
        for (JsonNode question : questions) {
            String text = question.path("text").asText("").trim();
            if (text.isEmpty() || text.codePointCount(0, text.length()) > 200) {
                continue;
            }
            String key = normalizeSuggestionText(text);
            if (!seen.add(key)) {
                continue;
            }
            String category = question.path("category").asText("");
            if (!allowed.isEmpty() && !allowed.contains(category)) {
                category = "";
            }
            SuggestionItem item = new SuggestionItem();
            item.setId(UUID.randomUUID().toString());
            item.setText(text);
            item.setCategory(category);
            item.setSource("model");
            items.add(item);
            if (items.size() == limit) {
                break;
            }
        }
        return items;
    }

    static List<SuggestionItem> mergeSuggestionItems(List<SuggestionItem> primary,
            List<SuggestionItem> fallback, int limit) {
        List<SuggestionItem> result = new ArrayList<>(limit);
        Set<String> seen = new HashSet<>();
        List<List<SuggestionItem>> groups = List.of(primary, fallback);
        for (List<SuggestionItem> group : groups) {
            for (SuggestionItem item : group) {
                String key = normalizeSuggestionText(item.getText());
                if (key.isEmpty() || !seen.add(key)) {
                    continue;
                }
                result.add(item);
                if (result.size() == limit) {
                    return result;
                }
            }
        }
        return result;
    }

    /** 对照 mergeHybridSuggestionItems：knowledge 保留约 1/3 槽位，双方互填空位。 */
    static List<SuggestionItem> mergeHybridSuggestionItems(List<SuggestionItem> model,
            List<SuggestionItem> knowledge, int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        int knowledgeSlots = limit > 1 ? (limit + 1) / 3 : 0;
        int modelSlots = limit - knowledgeSlots;

        List<SuggestionItem> result = new ArrayList<>(limit);
        Set<String> seen = new HashSet<>(limit);
        appendFrom(model, modelSlots, result, seen, limit);
        appendFrom(knowledge, knowledgeSlots, result, seen, limit);
        appendFrom(model, -1, result, seen, limit);
        appendFrom(knowledge, -1, result, seen, limit);
        return result;
    }

    private static void appendFrom(List<SuggestionItem> items, int max,
            List<SuggestionItem> result, Set<String> seen, int limit) {
        int added = 0;
        for (SuggestionItem item : items) {
            if (result.size() == limit || (max >= 0 && added == max)) {
                return;
            }
            String key = normalizeSuggestionText(item.getText());
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            result.add(item);
            added++;
        }
    }

    static String modeVal(Map<String, Object> map) {
        String mode = MessageSuggestionService.strVal(map, "mode");
        // 对照 QuestionSuggestionConfig.EnsureDefaults：缺省 = hybrid
        return mode.isEmpty() ? MessageSuggestionService.MODE_HYBRID : mode;
    }

    static List<String> strList(Map<String, Object> map, String key) {
        if (map == null || !(map.get(key) instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return out;
    }

    static int intVal(Map<String, Object> map, String key) {
        if (map != null && map.get(key) instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    // ── 辅助（逐字对照 Go 的包级函数） ──────────────────────────────────────

    /** 对照 Go {@code suggestionThinkBlock.ReplaceAllString}。 */
    static String stripThink(String content) {
        return content == null ? "" : content.replaceAll(THINK_BLOCK, "");
    }

    /** 对照 Go {@code answerEndsWithQuestion}（L933-936）。 */
    static boolean answerEndsWithQuestion(String answer) {
        String cleaned = answer.replaceAll(TRAILING_CITATIONS, "").trim();
        return cleaned.endsWith("?") || cleaned.endsWith("？");
    }

    /** 对照 Go {@code containsSuggestionID}（L924-931）。 */
    static boolean containsSuggestionId(List<SuggestionItem> items, String id) {
        if (items != null) {
            for (SuggestionItem item : items) {
                if (item.getId().equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 对照 Go {@code suggestionErrorCode}（L946-962）。 */
    static String suggestionErrorCode(Exception err) {
        if (err == null) {
            return "";
        }
        String value = err.getMessage() == null ? "" : err.getMessage().toLowerCase();
        if (value.contains("model")) {
            return "model_error";
        }
        if (value.contains("json")) {
            return "invalid_model_output";
        }
        return "generation_error";
    }

    /** 对照 Go {@code normalizeSuggestionText}（L915-922）：去空白与标点、小写。 */
    static String normalizeSuggestionText(String value) {
        StringBuilder builder = new StringBuilder();
        for (int cp : value.trim().codePoints().toArray()) {
            if (Character.isWhitespace(cp) || "?？!！,，.。:：;；\"'".indexOf(cp) >= 0) {
                continue;
            }
            builder.appendCodePoint(Character.toLowerCase(cp));
        }
        return builder.toString();
    }

    /**
     * 对照 Go {@code ResolveLanguage}（context_helpers.go L313-321）。
     * Language 中间件未翻译：message 带locale 用之，否则 DefaultLanguage
     * （WEKNORA_LANGUAGE env，缺省 zh-CN）。
     */
    static String resolveLanguage(String locale) {
        if (locale != null && !locale.trim().isEmpty()) {
            return locale;
        }
        String env = System.getenv("WEKNORA_LANGUAGE");
        return env == null || env.trim().isEmpty() ? "zh-CN" : env;
    }
}
