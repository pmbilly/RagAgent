package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.security.InputSanitizer;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.knowledge.support.ImageInfoEnricher;
import com.ragagent.common.pipeline.ChunkTypes;
import com.ragagent.retrieval.obs.RetrievalObs;
import com.ragagent.session.MessageAttachmentsPrompt;

/**
 * INTO_CHAT_MESSAGE 阶段插件（对照 Go chat_pipeline/into_chat_message.go）：
 * 检索结果 → 用户消息上下文。
 *
 * <h2>编排（实录组 into_chat ×7 钉住）</h2>
 * <ul>
 *   <li>FAQ 优先：faq 与文档分组；高分 FAQ（≥ FAQDirectAnswerThreshold）打
 *       {@code match="exact"} 标记；文档头 {@code <documents>} 列出每份知识
 *       （title/description/metadata 走 Go html.EscapeString 五字符转义）。</li>
 *   <li>查询经 utils.ValidateInput（InputSanitizer.validateInput）校验，非法 →
 *       TEMPLATE_EXECUTE + "user query contains invalid content"；非法改写回落原查询。</li>
 *   <li>无检索意图：模板渲染只注入 query/language（contexts 空）；图片描述仅在
 *       模型不支持视觉时以 "[用户上传图片内容]" 前缀追加；引用上下文与附件提示词随后。</li>
 *   <li>渲染后的 UserContent 异步持久化（UpdateMessageRenderedContent）。</li>
 * </ul>
 */
public final class PluginIntoChatMessage implements Plugin {

    private final PipelinePorts.MessageService messageService;

    public PluginIntoChatMessage(PipelinePorts.MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.INTO_CHAT_MESSAGE};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("session_id", chatManage.getSessionId());
        in.put("merge_result_cnt", chatManage.getMergeResult() == null ? 0 : chatManage.getMergeResult().size());
        in.put("template_len", chatManage.getSummaryConfig().getContextTemplate().length());
        PipelineLog.info("IntoChatMessage", "input", in);

        List<SearchResult> mergeResult = chatManage.getMergeResult() == null
                ? new ArrayList<>() : chatManage.getMergeResult();

        // FAQ 优先时的分组
        List<SearchResult> faqResults = new ArrayList<>();
        List<SearchResult> docResults = new ArrayList<>();
        boolean hasHighConfidenceFAQ = false;

        if (chatManage.isFaqPriorityEnabled()) {
            for (SearchResult result : mergeResult) {
                if (ChunkTypes.FAQ.equals(result.getChunkType())) {
                    faqResults.add(result);
                    if (result.getScore() >= chatManage.getFaqDirectAnswerThreshold() && !hasHighConfidenceFAQ) {
                        hasHighConfidenceFAQ = true;
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("chunk_id", result.getId());
                        f.put("score", RetrievalObs.goFmt4(result.getScore()));
                        f.put("threshold", chatManage.getFaqDirectAnswerThreshold());
                        PipelineLog.info("IntoChatMessage", "high_confidence_faq", f);
                    }
                } else {
                    docResults.add(result);
                }
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("faq_count", faqResults.size());
            f.put("doc_count", docResults.size());
            f.put("has_high_confidence", hasHighConfidenceFAQ);
            PipelineLog.info("IntoChatMessage", "faq_separation", f);
        }

        // 查询安全性校验
        String safeQuery = InputSanitizer.validateInput(chatManage.getQuery());
        if (safeQuery == null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            PipelineLog.warn("IntoChatMessage", "invalid_query", f);
            return PluginError.TEMPLATE_EXECUTE.withError(
                    new RuntimeException("user query contains invalid content"));
        }

        // 意图化无检索路径：模板渲染只注 runtime 元数据
        if (!chatManage.needsRetrieval()) {
            String userContent = safeQuery;
            String rewrite = chatManage.getRewriteQuery().trim();
            if (!rewrite.isEmpty()) {
                String safeRewrite = InputSanitizer.validateInput(rewrite);
                if (safeRewrite != null) {
                    userContent = safeRewrite;
                } else {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("session_id", chatManage.getSessionId());
                    PipelineLog.warn("IntoChatMessage", "invalid_rewrite_query_fallback", f);
                }
            }
            if (!chatManage.getImageDescription().isEmpty() && !chatManage.isChatModelSupportsVision()) {
                userContent += "\n\n[用户上传图片内容]\n" + chatManage.getImageDescription();
            }
            if (!chatManage.getQuotedContext().isEmpty()) {
                userContent += "\n\n" + chatManage.getQuotedContext();
            }
            if (chatManage.getAttachments() != null && !chatManage.getAttachments().isEmpty()) {
                userContent += MessageAttachmentsPrompt.build(chatManage.getAttachments());
            }

            String tpl = chatManage.getSummaryConfig().getContextTemplate();
            if (!tpl.isEmpty()) {
                Map<String, String> vals = new LinkedHashMap<>();
                vals.put("query", userContent);
                vals.put("contexts", "");
                vals.put("language", chatManage.getLanguage());
                chatManage.setUserContent(
                        com.ragagent.common.prompt.AgentPromptPlaceholders.renderPromptPlaceholders(tpl, vals));
            } else {
                chatManage.setUserContent(userContent);
            }

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("user_content_len", chatManage.getUserContent().length());
            f.put("has_template", !chatManage.getSummaryConfig().getContextTemplate().isEmpty());
            PipelineLog.info("IntoChatMessage", "no_search_with_template", f);
            return next.next();
        }

        StringBuilder contextsBuilder = new StringBuilder();

        // 唯一文档元数据（每份知识一次）
        List<SearchResult> allResults = new ArrayList<>(mergeResult);
        if (chatManage.isFaqPriorityEnabled() && !faqResults.isEmpty()) {
            allResults = new ArrayList<>(faqResults);
            allResults.addAll(docResults);
        }
        String docHeader = buildDocumentHeader(allResults);
        if (!docHeader.isEmpty()) {
            contextsBuilder.append(docHeader).append("\n");
        }

        if (chatManage.isFaqPriorityEnabled() && !faqResults.isEmpty()) {
            contextsBuilder.append("<source type=\"faq\" priority=\"high\">\n");
            for (int i = 0; i < faqResults.size(); i++) {
                SearchResult result = faqResults.get(i);
                String passage = getEnrichedPassageForChat(result);
                if (hasHighConfidenceFAQ && i == 0) {
                    contextsBuilder.append(String.format(
                            "<context id=\"FAQ-%d\" match=\"exact\">%s</context>\n", i + 1, passage));
                } else {
                    contextsBuilder.append(String.format(
                            "<context id=\"FAQ-%d\">%s</context>\n", i + 1, passage));
                }
            }
            contextsBuilder.append("</source>\n");

            if (!docResults.isEmpty()) {
                contextsBuilder.append("<source type=\"document\" priority=\"supplementary\">\n");
                for (int i = 0; i < docResults.size(); i++) {
                    SearchResult result = docResults.get(i);
                    String passage = getEnrichedPassageForChat(result);
                    contextsBuilder.append(String.format(
                            "<context id=\"DOC-%d\">%s</context>\n", i + 1, passage));
                }
                contextsBuilder.append("</source>");
            }
        } else {
            for (int i = 0; i < mergeResult.size(); i++) {
                SearchResult result = mergeResult.get(i);
                String passage = getEnrichedPassageForChat(result);
                if (i > 0) {
                    contextsBuilder.append("\n");
                }
                contextsBuilder.append(String.format(
                        "<context id=\"%d\">%s</context>", i + 1, passage));
            }
        }

        chatManage.setRenderedContexts(contextsBuilder.toString());

        Map<String, String> vals = new LinkedHashMap<>();
        vals.put("query", safeQuery);
        vals.put("contexts", chatManage.getRenderedContexts());
        vals.put("language", chatManage.getLanguage());
        String userContent = com.ragagent.common.prompt.AgentPromptPlaceholders.renderPromptPlaceholders(
                chatManage.getSummaryConfig().getContextTemplate(), vals);

        if (!chatManage.getImageDescription().isEmpty() && !chatManage.isChatModelSupportsVision()) {
            userContent += "\n\n[用户上传图片内容]\n" + chatManage.getImageDescription();
        }
        if (!chatManage.getQuotedContext().isEmpty()) {
            userContent += "\n\n" + chatManage.getQuotedContext();
        }
        if (chatManage.getAttachments() != null && !chatManage.getAttachments().isEmpty()) {
            userContent += MessageAttachmentsPrompt.build(chatManage.getAttachments());
        }

        chatManage.setUserContent(userContent);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", chatManage.getSessionId());
        out.put("user_content_len", chatManage.getUserContent().length());
        out.put("faq_priority", chatManage.isFaqPriorityEnabled());
        out.put("intent", chatManage.getIntent());
        out.put("image_description", chatManage.getImageDescription());
        out.put("chat_model_supports_vision", chatManage.isChatModelSupportsVision());
        PipelineLog.info("IntoChatMessage", "output", out);

        persistRenderedContent(chatManage);
        return next.next();
    }

    /** 对照 persistRenderedContent：UserContent ≠ Query 时异步回写用户消息。 */
    private void persistRenderedContent(ChatManage chatManage) {
        if (chatManage.getUserMessageId().isEmpty() || chatManage.getUserContent().isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("user_message_id", chatManage.getUserMessageId());
            f.put("has_user_content", !chatManage.getUserContent().isEmpty());
            f.put("reason", "empty_id_or_content");
            PipelineLog.info("IntoChatMessage", "persist_rendered_content_skip", f);
            return;
        }
        if (chatManage.getUserContent().equals(chatManage.getQuery())) {
            return;
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("session_id", chatManage.getSessionId());
        f.put("user_message_id", chatManage.getUserMessageId());
        f.put("rendered_content_len", chatManage.getUserContent().length());
        PipelineLog.info("IntoChatMessage", "persist_rendered_content", f);
        final ChatManage cm = chatManage;
        Thread.ofVirtual().start(() -> {
            try {
                messageService.updateMessageRenderedContent(cm.getSessionId(),
                        cm.getUserMessageId(), cm.getUserContent());
            } catch (RuntimeException e) {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("session_id", cm.getSessionId());
                w.put("user_message_id", cm.getUserMessageId());
                w.put("error", e.getMessage());
                PipelineLog.warn("IntoChatMessage", "persist_rendered_content_error", w);
            }
        });
    }

    /** 对照 buildDocumentHeader：&lt;documents&gt; 元数据头（title/desc/metadata 转义）。 */
    static String buildDocumentHeader(List<SearchResult> results) {
        record DocMeta(String title, String description, String metadata) {}

        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<DocMeta> docs = new ArrayList<>();

        for (SearchResult r : results) {
            if (r.getKnowledgeId().isEmpty()) {
                continue;
            }
            if (seen.containsKey(r.getKnowledgeId())) {
                continue;
            }
            seen.put(r.getKnowledgeId(), Boolean.TRUE);

            String title = r.getKnowledgeTitle();
            if (title.isEmpty()) {
                title = r.getKnowledgeFilename();
            }
            if (title.isEmpty()) {
                continue;
            }
            docs.add(new DocMeta(title,
                    r.getKnowledgeDescription() == null ? "" : r.getKnowledgeDescription(),
                    r.getKnowledgeCustomMetadata() == null ? "" : r.getKnowledgeCustomMetadata()));
        }

        if (docs.isEmpty()) {
            return "";
        }

        StringBuilder b = new StringBuilder();
        b.append("<documents>\n");
        for (DocMeta d : docs) {
            b.append("<document>\n");
            b.append(String.format("<title>%s</title>\n", escapeHtml(d.title())));
            if (!d.description().isEmpty()) {
                b.append(String.format("<description>%s</description>\n", escapeHtml(d.description())));
            }
            if (!d.metadata().isEmpty()) {
                b.append(String.format("<metadata>%s</metadata>\n", escapeHtml(d.metadata())));
            }
            b.append("</document>\n");
        }
        b.append("</documents>");
        return b.toString();
    }

    /** Go html.EscapeString（五字符）。 */
    private static String escapeHtml(String s) {
        return MessageAttachmentsPrompt.escapeHtml(s);
    }

    /** 对照 getEnrichedPassageForChat：内容 + 图片信息合并（委托 searchutil）。 */
    static String getEnrichedPassageForChat(SearchResult result) {
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
