package com.ragagent.rerank;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 单条重排结果（对照 Go {@code rerank.RankResult} 与其自定义
 * {@code UnmarshalJSON}）。
 *
 * <p><b>宽容解析</b>（Go 注释逐条对照）：{@code document} 可以是字符串也可以是
 * {@code {"text":...}} 对象；分数字段先看 {@code relevance_score}，缺失回落
 * {@code score}；两者都没有时为 0。序列化形如
 * {@code {"index":N,"document":{"text":"..."},"relevance_score":X}}
 * （Go struct 字段序，DocumentInfo 恒输出对象）。</p>
 */
public final class RankResult {

    private int index;
    private final DocumentInfo document = new DocumentInfo();
    private double relevanceScore;

    public int getIndex() {
        return index;
    }

    public void setIndex(int v) {
        index = v;
    }

    public DocumentInfo getDocument() {
        return document;
    }

    public double getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(double v) {
        relevanceScore = v;
    }

    /** 文档信息（对照 Go {@code DocumentInfo}；自身按 {@code {"text":"..."}} 序列化）。 */
    public static final class DocumentInfo {
        private String text = "";

        public String getText() {
            return text;
        }

        public void setText(String v) {
            text = v == null ? "" : v;
        }

        /** 对照 DocumentInfo.MarshalJSON（Go 默认 marshal：{"text":"..."}）。 */
        public String marshal() {
            var node = GoJson.object();
            node.put("text", text);
            return new String(GoJson.marshal(node), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 对照 RankResult.UnmarshalJSON：relevance_score 优先，score 回落。 */
    public static RankResult parse(JsonNode node) {
        if (node == null) {
            return null;
        }
        RankResult r = new RankResult();
        r.index = node.path("index").asInt();
        JsonNode doc = node.path("document");
        if (doc.isTextual()) {
            r.document.setText(doc.asText());
        } else if (doc.isObject()) {
            r.document.setText(doc.path("text").asText(""));
        } else if (doc.isMissingNode() || doc.isNull()) {
            // Go：字段缺失时 DocumentInfo 保持零值 ""
        }
        JsonNode rel = node.path("relevance_score");
        JsonNode score = node.path("score");
        if (rel.isNumber()) {
            r.relevanceScore = rel.asDouble();
        } else if (score.isNumber()) {
            r.relevanceScore = score.asDouble();
        }
        return r;
    }

    /** 对照 RankResult.MarshalJSON（index/document/relevance_score 声明序，恒输出）。 */
    public String marshal() {
        var node = GoJson.object();
        node.put("index", index);
        node.putObject("document").put("text", document.getText());
        node.put("relevance_score", relevanceScore);
        return new String(GoJson.marshal(node), java.nio.charset.StandardCharsets.UTF_8);
    }
}
