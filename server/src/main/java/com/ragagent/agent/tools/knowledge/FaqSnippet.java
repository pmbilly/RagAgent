package com.ragagent.agent.tools.knowledge;

import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;

/**
 * FAQ 元数据在工具输出中的投影。
 *
 * <p>四处消费方（知识工具）：knowledge_search 的 {@code <chunk>} 里嵌
 * {@code <faq>} 块（{@link #writeFaqMetadataXml}）；list_knowledge_chunks 的顶层
 * {@code <faq>} 条目（{@link #writeFaqEntryXml}）；结构化 data map 的附加字段
 * （{@link #appendFaqChunkData}/{@link #normalizeFaqChunkDataMap}）；检索命中摘要
 * "Q: … | A: …"（{@link #faqMatchSnippet} / {@link #faqMatchSnippetFromQueries}）。</p>
 *
 * <p>相似问展示上限 5 条，超出追加 {@code <similar_questions_omitted count="N" />}；
 * 空白答案跳过；XML 转义五字符（& ' " < > 的最小转义——输出由 LLM
 * 宽容解析，不是严格 XML 处理器）。</p>
 */
public final class FaqSnippet {

    /** 工具输出里相似问的展示上限。 */
    public static final int FAQ_MAX_SIMILAR_QUESTIONS_DISPLAY = 5;

    // ---- 检索工具命中摘要的边界（grep_chunks / knowledge_search）----
    static final int SNIPPET_MAX_TOTAL_RUNES = 800;
    static final int SNIPPET_MAX_ANSWER_RUNES = 600;

    private FaqSnippet() {
    }

    /** 展示裁剪：返回展示列表与被省略数。 */
    public record SimilarQuestionsDisplay(List<String> display, int omitted) {
    }

    public static SimilarQuestionsDisplay truncateSimilarQuestionsForDisplay(List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            return new SimilarQuestionsDisplay(List.of(), 0);
        }
        if (questions.size() <= FAQ_MAX_SIMILAR_QUESTIONS_DISPLAY) {
            return new SimilarQuestionsDisplay(questions, 0);
        }
        return new SimilarQuestionsDisplay(questions.subList(0, FAQ_MAX_SIMILAR_QUESTIONS_DISPLAY),
                questions.size() - FAQ_MAX_SIMILAR_QUESTIONS_DISPLAY);
    }

    /** 相似问 XML（每行一个 <similar_question> + 省略标记）。 */
    public static void writeSimilarQuestionsXml(StringBuilder b, List<String> questions) {
        SimilarQuestionsDisplay d = truncateSimilarQuestionsForDisplay(questions);
        for (String sq : d.display()) {
            b.append("<similar_question>").append(xmlEscape(sq)).append("</similar_question>\n");
        }
        if (d.omitted() > 0) {
            b.append("<similar_questions_omitted count=\"").append(d.omitted()).append("\" />\n");
        }
    }

    /** 结构化 chunkData 的相似问附加。 */
    public static void appendSimilarQuestionsToChunkData(java.util.Map<String, Object> chunkData,
                                                         List<String> questions) {
        SimilarQuestionsDisplay d = truncateSimilarQuestionsForDisplay(questions);
        if (d.display().isEmpty()) {
            return;
        }
        chunkData.put("faq_similar_questions", d.display());
        if (d.omitted() > 0) {
            chunkData.put("faq_similar_questions_omitted", d.omitted());
        }
    }

    /**
     * question / similar_question / answer 子元素（无包裹）。
     * meta 为 null 时不输出。
     */
    public static void writeFaqFieldsXml(StringBuilder b, FaqChunkMetadata meta) {
        if (meta == null) {
            return;
        }
        if (meta.standardQuestion != null && !meta.standardQuestion.isEmpty()) {
            b.append("<question>").append(xmlEscape(meta.standardQuestion)).append("</question>\n");
        }
        writeSimilarQuestionsXml(b, meta.similarQuestions);
        if (meta.answers != null) {
            for (String ans : meta.answers) {
                if (ans == null || ans.strip().isEmpty()) {
                    continue;
                }
                b.append("<answer>").append(xmlEscape(ans)).append("</answer>\n");
            }
        }
    }

    /** 字段是否全空。 */
    public static boolean faqFieldsEmpty(FaqChunkMetadata meta) {
        if (meta == null) {
            return true;
        }
        return isEmpty(meta.standardQuestion)
                && (meta.similarQuestions == null || meta.similarQuestions.isEmpty())
                && (meta.answers == null || meta.answers.isEmpty());
    }

    /** 嵌套 {@code <faq>} 块（knowledge_search 的 <chunk> 内用）。 */
    public static void writeFaqMetadataXml(StringBuilder b, FaqChunkMetadata meta) {
        if (faqFieldsEmpty(meta)) {
            return;
        }
        b.append("<faq>\n");
        writeFaqFieldsXml(b, meta);
        b.append("</faq>\n");
    }

    /**
     * 顶层 FAQ 条目（list_knowledge_chunks 用，不包 <chunk>）。
     * 元数据缺失时若有标准问题则作为 question 属性 + <question> 子元素呈现。
     */
    public static void writeFaqEntryXml(StringBuilder b, Chunk c) {
        if (c == null || !isFaqChunk(c)) {
            return;
        }
        FaqChunkMetadata meta = faqMetadata(c);

        String q = faqStandardQuestion(c);
        String questionAttr = "";
        if (!q.isEmpty()) {
            questionAttr = " question=\"" + xmlEscape(q) + "\"";
        }
        b.append("<faq faq_id=\"").append(xmlEscape(c.getId())).append("\" index=\"")
                .append(c.getChunkIndex()).append("\"").append(questionAttr).append(">\n");

        if (!faqFieldsEmpty(meta)) {
            writeFaqFieldsXml(b, meta);
        } else if (!q.isEmpty()) {
            b.append("<question>").append(xmlEscape(q)).append("</question>\n");
        }

        b.append("</faq>\n");
    }

    /**
     * JSON 载荷里用 faq_id / index 代替 chunk_id / chunk_index
     * （非 FAQ chunk 或 map 为 null 时不做）。
     */
    public static void normalizeFaqChunkDataMap(java.util.Map<String, Object> chunkData, Chunk c) {
        if (c == null || !isFaqChunk(c) || chunkData == null) {
            return;
        }
        chunkData.put("faq_id", c.getId());
        chunkData.put("index", c.getChunkIndex());
        chunkData.remove("chunk_id");
        chunkData.remove("chunk_index");
    }

    /** FAQ 元数据附加进结构化结果 map。 */
    public static void appendFaqChunkData(java.util.Map<String, Object> chunkData, Chunk c) {
        if (c == null || !isFaqChunk(c)) {
            return;
        }
        FaqChunkMetadata meta = faqMetadata(c);
        if (meta == null) {
            return;
        }
        String q = meta.standardQuestion == null ? "" : meta.standardQuestion.strip();
        if (!q.isEmpty()) {
            chunkData.put("faq_question", q);
        }
        appendSimilarQuestionsToChunkData(chunkData, meta.similarQuestions);
        if (meta.answers != null && !meta.answers.isEmpty()) {
            chunkData.put("faq_answers", meta.answers);
        }
    }

    // ---- 命中摘要 ----

    /**
     * knowledge_search 命中的 "Q: … | A: …" 摘要（查询词命中的相似问优先于标准问题）。
     */
    public static String faqMatchSnippetFromQueries(FaqChunkMetadata meta, List<String> queries) {
        if (meta == null) {
            return "";
        }
        String question = faqMatchedQuestionFromQueries(meta, queries);
        if (question.isEmpty()) {
            question = meta.standardQuestion == null ? "" : meta.standardQuestion.strip();
        }
        return formatFaqMatchSnippet(question, meta.answers);
    }

    /**
     * grep_chunks 正则命中的 "Q: … | A: …" 摘要（compiled 与 patterns 一一对应，null 元素跳过）。
     */
    public static String faqMatchSnippet(Chunk chunk, List<java.util.regex.Pattern> compiled) {
        if (chunk == null) {
            return "";
        }
        FaqChunkMetadata meta = faqMetadata(chunk);
        if (meta == null) {
            return "";
        }
        String question = faqMatchedQuestionFromRegex(meta, compiled);
        if (question.isEmpty()) {
            question = meta.standardQuestion == null ? "" : meta.standardQuestion.strip();
        }
        if (question.isEmpty()) {
            return "";
        }
        return formatFaqMatchSnippet(question, meta.answers);
    }

    /** "Q: … | A: …" 渲染（总长 800 rune 上限）。 */
    static String formatFaqMatchSnippet(String question, List<String> answers) {
        question = question == null ? "" : question.strip();
        if (question.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        b.append("Q: ");
        b.append(question);
        String answer = faqAnswersForSnippet(answers);
        if (!answer.isEmpty()) {
            b.append(" | A: ");
            b.append(answer);
        }
        String snippet = b.toString().strip();
        if (snippet.codePointCount(0, snippet.length()) > SNIPPET_MAX_TOTAL_RUNES) {
            snippet = truncateRunes(snippet, SNIPPET_MAX_TOTAL_RUNES);
        }
        return snippet;
    }

    /** 正则命中的相似问优先，其次标准问题。 */
    static String faqMatchedQuestionFromRegex(FaqChunkMetadata meta, List<java.util.regex.Pattern> compiled) {
        if (meta == null) {
            return "";
        }
        if (meta.similarQuestions != null) {
            for (String sq : meta.similarQuestions) {
                if (regexMatchesAny(sq, compiled)) {
                    return sq;
                }
            }
        }
        if (regexMatchesAny(meta.standardQuestion, compiled)) {
            return meta.standardQuestion;
        }
        return meta.standardQuestion;
    }

    /** 查询词命中的相似问优先，其次标准问题。 */
    static String faqMatchedQuestionFromQueries(FaqChunkMetadata meta, List<String> queries) {
        if (meta == null) {
            return "";
        }
        List<String> tokens = searchQueryTokens(queries);
        if (meta.similarQuestions != null) {
            for (String sq : meta.similarQuestions) {
                if (textMatchesSearchQueries(sq, queries, tokens)) {
                    return sq;
                }
            }
        }
        if (textMatchesSearchQueries(meta.standardQuestion, queries, tokens)) {
            return meta.standardQuestion;
        }
        return meta.standardQuestion;
    }

    /** 答案拼接（空白答案跳过，" | " 连接，600 rune 上限）。 */
    static String faqAnswersForSnippet(List<String> answers) {
        if (answers == null || answers.isEmpty()) {
            return "";
        }
        StringJoiner parts = new StringJoiner(" | ");
        for (String a : answers) {
            String trimmed = a == null ? "" : a.strip();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        String joined = parts.toString();
        if (joined.isEmpty()) {
            return "";
        }
        return truncateRunes(joined, SNIPPET_MAX_ANSWER_RUNES);
    }

    /** 全文小写包含判定（整查询优先，再 token）。 */
    static boolean textMatchesSearchQueries(String text, List<String> queries, List<String> tokens) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lowered = text.toLowerCase();
        if (queries != null) {
            for (String q : queries) {
                String lq = q == null ? "" : q.strip().toLowerCase();
                if (!lq.isEmpty() && lowered.contains(lq)) {
                    return true;
                }
            }
        }
        if (tokens != null) {
            for (String tok : tokens) {
                if (lowered.contains(tok)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 查询分词：按空白与标点切、小写、去重、丢弃 &lt;2 rune 的碎片
     * （切分字符表见实现内注释）。
     */
    public static List<String> searchQueryTokens(List<String> queries) {
        List<String> tokens = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        if (queries == null) {
            return tokens;
        }
        for (String q : queries) {
            if (q == null) {
                continue;
            }
            // 切分字符表：' ' \t \n \r , . ; : ? ! ( ) [ ] { } " '
            for (String tok : q.split("[ \\t\\n\\r,.;:?!()\\[\\]{}\"']+")) {
                String t = tok.strip().toLowerCase();
                if (t.codePointCount(0, t.length()) < 2) {
                    continue;
                }
                if (seen.add(t)) {
                    tokens.add(t);
                }
            }
        }
        return tokens;
    }

    // ---- 依赖 knowledge 域的适配 ----

    static boolean isFaqChunk(Chunk c) {
        return "faq".equals(c.getChunkType());
    }

    /**
     * chunk 元数据解析（空 metadata → null；解析失败 → null；
     * 解析后做基础清理）。
     */
    static FaqChunkMetadata faqMetadata(Chunk c) {
        JsonNodeLikeAdapter adapter = new JsonNodeLikeAdapter();
        return adapter.read(c);
    }

    /** 标准问题（trim 后；非 FAQ/无元数据为空串）。 */
    public static String faqStandardQuestion(Chunk c) {
        if (c == null || !isFaqChunk(c)) {
            return "";
        }
        FaqChunkMetadata meta = faqMetadata(c);
        if (meta == null) {
            return "";
        }
        return meta.standardQuestion == null ? "" : meta.standardQuestion.strip();
    }

    // ---- 共享小工具 ----

    /** XML 最小转义（& ' " < >）。 */
    public static String xmlEscape(String s) {
        String replaced = s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
        return replaced;
    }

    /** rune 截断 + "..."。 */
    public static String truncateRunes(String s, int maxRunes) {
        int runeCount = s.codePointCount(0, s.length());
        if (runeCount <= maxRunes) {
            return s;
        }
        int end = s.offsetByCodePoints(0, maxRunes);
        return s.substring(0, end) + "...";
    }

    /** 正则命中判定（空文本/空表 false，null 模式跳过）。 */
    public static boolean regexMatchesAny(String text, List<java.util.regex.Pattern> compiled) {
        if (text == null || text.isEmpty() || compiled == null || compiled.isEmpty()) {
            return false;
        }
        for (java.util.regex.Pattern re : compiled) {
            if (re != null && re.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /** chunk.metadata(JsonNode) → FaqChunkMetadata 的适配（isolated 以隔离 knowledge 域类型）。 */
    private static final class JsonNodeLikeAdapter {
        FaqChunkMetadata read(Chunk c) {
            com.fasterxml.jackson.databind.JsonNode node = c.getMetadata();
            if (node == null || node.isNull() || node.isMissingNode() || node.isEmpty()) {
                return null;
            }
            FaqChunkMetadata meta = FaqChunkMetadata.fromJson(node);
            if (meta != null) {
                meta.sanitize();
            }
            return meta;
        }
    }
}
