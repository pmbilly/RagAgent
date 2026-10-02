package com.ragagent.chatpipeline.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelineCommon;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.knowledge.support.ImageInfoEnricher;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.agent.modelcontext.Registry;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.common.pipeline.ChunkTypes;

/**
 * references.go 的模型上下文装配（对照 chat_pipeline/references.go 全文）：
 * 把检索结果里的位置 ID 换成请求内 model 句柄。
 *
 * <h2>流程（实录组 references ×5 钉住）</h2>
 * prepareMessagesWithHistory 渲染基础消息 → 系统提示词尾接 Registry.ProtocolPrompt →
 * MergeResult 有内容时把知识行/web 行分别组装成两条 ModelToolResult（display_type=
 * search_results / web_search_results）注入消息：优先替换消息里现存的 RenderedContexts
 * 文本，替换失败则前置到最后一条消息。
 *
 * <p>FAQ 优先时 orderedPipelineReferences 把 FAQ 结果排到前面注册（句柄序 = 引用序）。
 * Registry（4.6a）承担句柄表/协议提示词/工具结果渲染的全部字节面。</p>
 */
public final class ReferencesSupport {

    private ReferencesSupport() {}

    /**
     * 对照 prepareMessagesWithModelContext：返回 (消息, Registry)。
     * citationsEnabled nil 视为开启（Registry 的构造参数语义）。
     */
    public static Assembly prepareMessagesWithModelContext(ChatManage chatManage) {
        boolean citationsEnabled = chatManage == null || chatManage.citationsEnabled();
        Registry registry = new Registry(citationsEnabled);
        if (chatManage == null) {
            return new Assembly(new ArrayList<>(), registry);
        }
        List<ChatMessage> messages = PipelineCommon.prepareMessagesWithHistory(chatManage);
        if (!messages.isEmpty()) {
            String trimmed = trimRightWhitespace(messages.get(0).getContent());
            messages.get(0).setContent(trimmed + registry.protocolPrompt());
        }
        if (chatManage.getMergeResult() == null || chatManage.getMergeResult().isEmpty()
                || messages.isEmpty()) {
            return new Assembly(messages, registry);
        }

        List<SearchResult> ordered = orderedPipelineReferences(chatManage);
        List<SearchResult> knowledgeResults = new ArrayList<>();
        List<Map<String, Object>> knowledgeRows = new ArrayList<>();
        List<Map<String, Object>> webRows = new ArrayList<>();
        for (SearchResult result : ordered) {
            if (result == null) {
                continue;
            }
            if (isPipelineWebReference(result)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("url", result.getId());
                row.put("title", firstPipelineTitle(result));
                row.put("snippet", result.getContent());
                row.put("published_at", result.getMetadata() == null
                        ? null : result.getMetadata().get("published_at"));
                webRows.add(row);
                continue;
            }
            knowledgeResults.add(result);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunk_id", result.getId());
            row.put("knowledge_id", result.getKnowledgeId());
            row.put("knowledge_base_id", result.getKnowledgeBaseId());
            row.put("knowledge_title", firstPipelineTitle(result));
            row.put("chunk_index", result.getChunkIndex());
            row.put("chunk_type", result.getChunkType());
            row.put("content", getEnrichedPassageForChat(result));
            knowledgeRows.add(row);
        }
        // Java 的 SearchResult 默认 null 字段在 Go 侧是 ""（Registry 按非空契约读取）
        for (SearchResult r : knowledgeResults) {
            if (r.getKnowledgeBaseId() == null) {
                r.setKnowledgeBaseId("");
            }
        }
        registry.registerSearchResults(knowledgeResults);
        List<String> contextParts = new ArrayList<>();
        if (!knowledgeRows.isEmpty()) {
            ToolResult result = new ToolResult();
            result.setSuccess(true);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("display_type", "search_results");
            data.put("results", knowledgeRows);
            result.setData(data);
            contextParts.add(registry.modelToolResult(result));
        }
        if (!webRows.isEmpty()) {
            ToolResult result = new ToolResult();
            result.setSuccess(true);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("display_type", "web_search_results");
            data.put("results", webRows);
            result.setData(data);
            contextParts.add(registry.modelToolResult(result));
        }
        String modelContexts = String.join("\n", contextParts);
        if (modelContexts.trim().isEmpty()) {
            return new Assembly(messages, registry);
        }

        int last = messages.size() - 1;
        boolean replaced = false;
        int[] candidates = {0, last};
        for (int index : candidates) {
            if (!chatManage.getRenderedContexts().isEmpty()
                    && messages.get(index).getContent().contains(chatManage.getRenderedContexts())) {
                messages.get(index).setContent(messages.get(index).getContent()
                        .replace(chatManage.getRenderedContexts(), modelContexts));
                replaced = true;
            }
        }
        if (!replaced) {
            messages.get(last).setContent(modelContexts + "\n\n" + messages.get(last).getContent());
        }
        return new Assembly(messages, registry);
    }

    /** (messages, registry) 双返回（对照 Go 的双值返回）。 */
    public record Assembly(List<ChatMessage> messages, Registry registry) {}

    /** 对照 TrimRight(s, " \t\r\n")。 */
    static String trimRightWhitespace(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    /** 对照 isPipelineWebReference。 */
    static boolean isPipelineWebReference(SearchResult result) {
        if (result == null) {
            return false;
        }
        return ChunkTypes.WEB_SEARCH.equalsIgnoreCase(result.getChunkType())
                || "web_search".equalsIgnoreCase(result.getKnowledgeSource());
    }

    /** 对照 orderedPipelineReferences：FAQ 优先时 FAQ 结果先注册。 */
    static List<SearchResult> orderedPipelineReferences(ChatManage chatManage) {
        if (chatManage == null) {
            return null;
        }
        if (!chatManage.isFaqPriorityEnabled()) {
            return chatManage.getMergeResult();
        }
        List<SearchResult> ordered = new ArrayList<>(chatManage.getMergeResult().size());
        for (SearchResult result : chatManage.getMergeResult()) {
            if (result != null && ChunkTypes.FAQ.equals(result.getChunkType())) {
                ordered.add(result);
            }
        }
        for (SearchResult result : chatManage.getMergeResult()) {
            if (result != null && !ChunkTypes.FAQ.equals(result.getChunkType())) {
                ordered.add(result);
            }
        }
        return ordered;
    }

    /** 对照 firstPipelineTitle。 */
    static String firstPipelineTitle(SearchResult result) {
        if (result == null) {
            return "";
        }
        if (!result.getKnowledgeTitle().isEmpty()) {
            return result.getKnowledgeTitle();
        }
        return result.getKnowledgeFilename();
    }
    /** 对照 getEnrichedPassageForChat：内容 + 图片信息合并（委托 knowledge.support）。 */
    public static String getEnrichedPassageForChat(SearchResult result) {
        if (result.getContent().isEmpty() && result.getImageInfo().isEmpty()) {
            return "";
        }
        if (result.getImageInfo().isEmpty()) {
            return result.getContent();
        }
        return ImageInfoEnricher.enrichContentWithImageInfoForChat(
                result.getContent(), result.getImageInfo());
    }

}
