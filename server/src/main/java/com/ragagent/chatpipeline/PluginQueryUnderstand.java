package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.session.domain.Message;
import com.ragagent.common.web.JsonMappers;

/**
 * QUERY_UNDERSTAND 阶段插件（对照 Go chat_pipeline/query_understand.go）：
 * 查询改写 + 意图分类 + 图片描述；文本/图文/纯图三种输入组合。
 *
 * <h2>关键语义（实录组 qu_* 钉住）</h2>
 * <ul>
 *   <li>无改写开关且无图 → 直接 next（RewriteQuery 先行落原始查询）。</li>
 *   <li>模型选择：有图优先 vision 模型（chat 支持 → chat；否则 VLM），无图走
 *       QueryUnderstandModelID 覆写，失败回落 ChatModelID；全失败 → next()。</li>
 *   <li>LLM 调用失败 / 输出不可解析 → 保持原查询原意图（降级不短路）。</li>
 *   <li>非检索意图时应用 intent 系统提示词覆写（agent 覆写优先，空白回落全局）。</li>
 *   <li>图片描述落库是<b>异步</b>（context.WithoutCancel → Java 虚拟线程，无取消语义）。</li>
 *   <li>记忆背景（asker_background）只进改写提示词，不进 UsedMemories。</li>
 * </ul>
 *
 * <p>结构化输出的宽容解析（parseStructuredQueryOutput）：直接 JSON → 失败时截取
 * 首个 { 到末个 } 再试；query 键别名族 rewrite_query/rewritten_query/query/question；
 * 图片描述键别名族 desc 族 + ocr 族（desc 与 ocr 合并规则实录钉住）。</p>
 */
public final class PluginQueryUnderstand implements Plugin {

    private static final ObjectMapper JSON = JsonMappers.lenient();

    private final PipelinePorts.ModelService modelService;
    private final PipelinePorts.MessageService messageService;
    private final PipelinePorts.MemoryService memoryService;
    private final PipelineConfig config;

    public PluginQueryUnderstand(PipelinePorts.ModelService modelService,
                                 PipelinePorts.MessageService messageService,
                                 PipelinePorts.MemoryService memoryService,
                                 PipelineConfig config) {
        this.modelService = modelService;
        this.messageService = messageService;
        this.memoryService = memoryService;
        this.config = config;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.QUERY_UNDERSTAND};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        chatManage.setRewriteQuery(chatManage.getQuery());

        boolean hasImages = chatManage.getImages() != null && !chatManage.getImages().isEmpty();
        boolean needRewrite = chatManage.isEnableRewrite();
        if (!needRewrite && !hasImages) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("reason", "rewrite_disabled_no_images");
            PipelineLog.info("QueryUnderstand", "skip", f);
            return next.next();
        }

        Map<String, Object> in = new LinkedHashMap<>();
        in.put("session_id", chatManage.getSessionId());
        in.put("tenant_id", chatManage.getTenantId());
        in.put("user_query", chatManage.getQuery());
        in.put("has_images", hasImages);
        in.put("enable_rewrite", chatManage.isEnableRewrite());
        PipelineLog.info("QueryUnderstand", "input", in);

        // --- 载入会话历史 ---
        List<History> historyList;
        if (chatManage.getHistory() != null && !chatManage.getHistory().isEmpty()) {
            historyList = chatManage.getHistory();
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("rounds", historyList.size());
            PipelineLog.info("QueryUnderstand", "history_reused", f);
        } else {
            historyList = loadHistory(chatManage);
        }

        // --- 选模型 ---
        ModelChoice choice = selectModel(chatManage, hasImages);
        if (choice.model == null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            PipelineLog.error("QueryUnderstand", "get_model", f);
            return next.next();
        }

        // --- 组提示词 ---
        String[] prompts = buildPrompts(chatManage, historyList);
        String systemContent = prompts[0];
        String userContent = prompts[1];

        ChatMessage userMsg = new ChatMessage("user", userContent);
        if (choice.useImages) {
            userMsg.setImages(chatManage.getImages());
        }

        int maxTokens = choice.useImages ? 500 : 150;
        boolean thinking = false;

        // --- 调模型（失败降级） ---
        ChatOptions opt = new ChatOptions();
        opt.setTemperature(0.3);
        opt.setMaxCompletionTokens(maxTokens);
        opt.setThinking(Boolean.FALSE);
        List<ChatMessage> callMessages = new ArrayList<>();
        callMessages.add(new ChatMessage("system", systemContent));
        callMessages.add(userMsg);
        com.ragagent.llm.domain.ChatResponse response;
        try {
            response = choice.model.chat(callMessages, opt);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("error", e.getMessage());
            PipelineLog.error("QueryUnderstand", "model_call", f);
            return next.next();
        }

        // --- 解析结构化输出 ---
        parseOutput(chatManage, response.getContent());

        // 图片描述异步落库（不阻塞本轮结果）
        if (!chatManage.getImageDescription().isEmpty() && !chatManage.getUserMessageId().isEmpty()) {
            Thread.ofVirtual().start(() -> updateUserMessageImageCaption(chatManage));
        }

        // --- 非检索意图的提示词覆写 ---
        if (!chatManage.needsRetrieval()) {
            if (applyIntentPromptOverride(chatManage, config.getIntentSystemPrompts())) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("session_id", chatManage.getSessionId());
                f.put("intent", chatManage.getIntent());
                PipelineLog.info("QueryUnderstand", "prompt_override", f);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", chatManage.getSessionId());
        out.put("rewrite_query", chatManage.getRewriteQuery());
        out.put("intent", chatManage.getIntent());
        out.put("has_image_desc", !chatManage.getImageDescription().isEmpty());
        out.put("has_prompt_override", !chatManage.getSystemPromptOverride().isEmpty());
        out.put("original_output", response.getContent());
        PipelineLog.info("QueryUnderstand", "output", out);
        return next.next();
    }

    /** 对照 updateUserMessageImageCaption：把生成的图片描述写回用户消息。 */
    private void updateUserMessageImageCaption(ChatManage chatManage) {
        Message msg;
        try {
            msg = messageService.getMessage(chatManage.getSessionId(), chatManage.getUserMessageId());
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("user_message_id", chatManage.getUserMessageId());
            f.put("error", e.getMessage());
            PipelineLog.warn("QueryUnderstand", "get_user_message", f);
            return;
        }
        if (msg == null) {
            return;
        }
        if (msg.getImages() == null || msg.getImages().isEmpty()) {
            return;
        }
        msg.getImages().get(0).setCaption(chatManage.getImageDescription());
        try {
            messageService.updateMessageImages(chatManage.getSessionId(),
                    chatManage.getUserMessageId(), msg.getImages());
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("user_message_id", chatManage.getUserMessageId());
            f.put("error", e.getMessage());
            PipelineLog.warn("QueryUnderstand", "update_image_caption", f);
        }
    }

    /** 对照 loadHistory：MaxRounds ≤ 0 视为显式关闭多轮，不回落全局默认。 */
    private List<History> loadHistory(ChatManage chatManage) {
        if (chatManage.getMaxRounds() <= 0) {
            return null;
        }
        int maxRounds = chatManage.getMaxRounds();
        List<History> historyList;
        try {
            historyList = PipelineCommon.loadAndProcessHistory(messageService,
                    chatManage.getSessionId(), maxRounds, 20);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("error", e.getMessage());
            PipelineLog.warn("QueryUnderstand", "history_fetch", f);
            return null;
        }
        chatManage.setHistory(historyList);
        if (historyList != null && !historyList.isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("history_rounds", historyList.size());
            PipelineLog.info("QueryUnderstand", "history_ready", f);
        }
        return historyList;
    }

    /** 模型选择结果（对照 Go 的 (model, useImages) 双返回）。 */
    private record ModelChoice(LlmChatClient model, boolean useImages) {}

    /** 对照 selectModel：有图优先 vision，再 VLM；无图走 QU 覆写并回落。 */
    private ModelChoice selectModel(ChatManage chatManage, boolean hasImages) {
        if (hasImages) {
            if (chatManage.isChatModelSupportsVision()) {
                try {
                    LlmChatClient m = modelService.getChatModel(chatManage.getChatModelId());
                    return new ModelChoice(m, true);
                } catch (RuntimeException e) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("session_id", chatManage.getSessionId());
                    f.put("error", e.getMessage());
                    PipelineLog.warn("QueryUnderstand", "vision_model_fallback", f);
                }
            }
            if (!chatManage.getVlmModelId().isEmpty()) {
                try {
                    LlmChatClient m = modelService.getChatModel(chatManage.getVlmModelId());
                    return new ModelChoice(m, true);
                } catch (RuntimeException e) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("session_id", chatManage.getSessionId());
                    f.put("vlm_model_id", chatManage.getVlmModelId());
                    f.put("error", e.getMessage());
                    PipelineLog.warn("QueryUnderstand", "vlm_model_fallback", f);
                }
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            PipelineLog.warn("QueryUnderstand", "no_vision_model", f);
        }

        String textModelId = chatManage.getChatModelId();
        if (!chatManage.getQueryUnderstandModelId().isEmpty()) {
            textModelId = chatManage.getQueryUnderstandModelId();
        }
        try {
            return new ModelChoice(modelService.getChatModel(textModelId), false);
        } catch (RuntimeException e) {
            // 专用理解模型解析失败（删除/停用）→ 回落 ChatModelID
            if (!chatManage.getQueryUnderstandModelId().isEmpty()
                    && !textModelId.equals(chatManage.getChatModelId())) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("session_id", chatManage.getSessionId());
                f.put("query_understand_model_id", chatManage.getQueryUnderstandModelId());
                f.put("error", e.getMessage());
                PipelineLog.warn("QueryUnderstand", "query_understand_model_fallback", f);
                try {
                    return new ModelChoice(modelService.getChatModel(chatManage.getChatModelId()), false);
                } catch (RuntimeException ignored) {
                    // 落到失败日志
                }
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("chat_model_id", textModelId);
            f.put("error", e.getMessage());
            PipelineLog.error("QueryUnderstand", "get_model", f);
            return new ModelChoice(null, false);
        }
    }

    /** 对照 buildPrompts：system/user 提示词（conversation/query/language 占位符）。 */
    String[] buildPrompts(ChatManage chatManage, List<History> historyList) {
        String userPrompt = config.getRewritePromptUser();
        if (!chatManage.getRewritePromptUser().isEmpty()) {
            userPrompt = chatManage.getRewritePromptUser();
        }
        String systemPrompt = config.getRewritePromptSystem();
        if (!chatManage.getRewritePromptSystem().isEmpty()) {
            systemPrompt = chatManage.getRewritePromptSystem();
        }

        String conversationText = formatConversationHistory(historyList);

        String queryContent = chatManage.getQuery();
        int imgCount = chatManage.getImages() == null ? 0 : chatManage.getImages().size();
        if (imgCount > 0) {
            queryContent += "\n\n<images_uploaded count=\"" + imgCount + "\" />";
        } else {
            queryContent += "\n\n<no_image_attached />";
        }
        if (chatManage.getAttachments() != null && !chatManage.getAttachments().isEmpty()) {
            queryContent += MessageAttachmentsPrompt.build(chatManage.getAttachments());
        } else {
            queryContent += "\n<no_document_attached />";
        }
        queryContent += memoryBackground(chatManage);

        Map<String, String> vals = new LinkedHashMap<>();
        vals.put("conversation", conversationText);
        vals.put("query", queryContent);
        vals.put("language", chatManage.getLanguage());

        return new String[] {
                com.ragagent.common.prompt.AgentPromptPlaceholders.renderPromptPlaceholders(systemPrompt, vals),
                com.ragagent.common.prompt.AgentPromptPlaceholders.renderPromptPlaceholders(userPrompt, vals),
        };
    }

    /**
     * 对照 memoryBackground：给改写器"谁在问"的常驻背景。刻意不建议进 UsedMemories
     * （这里读的是全量背景，MEMORY_RECALL 才按相关性与本轮答案对账）。
     */
    private String memoryBackground(ChatManage chatManage) {
        if (memoryService == null) {
            return "";
        }
        var memCtx = memoryService.retrievalContextFor();
        if (memCtx == null || memCtx.empty()) {
            return "";
        }

        StringBuilder b = new StringBuilder();
        b.append("\n\n<asker_background note=\"背景仅用于消解指代和补全检索词，不要当作问题的一部分\">");
        if (!memCtx.background().isEmpty()) {
            b.append("\n").append(memCtx.background());
        }
        if (memCtx.interests() != null && !memCtx.interests().isEmpty()) {
            b.append("\n长期关注：").append(String.join("、", memCtx.interests()));
        }
        if (memCtx.documents() != null && !memCtx.documents().isEmpty()) {
            b.append("\n常查资料：").append(String.join("、", memCtx.documents()));
        }
        b.append("\n</asker_background>");

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("session_id", chatManage.getSessionId());
        fields.put("interests", memCtx.interests() == null ? 0 : memCtx.interests().size());
        fields.put("documents", memCtx.documents() == null ? 0 : memCtx.documents().size());
        fields.put("items", memCtx.items() == null ? 0 : memCtx.items().size());
        if (memCtx.interests() != null && !memCtx.interests().isEmpty()) {
            fields.put("interest_previews", memCtx.interests());
        }
        PipelineLog.info("QueryUnderstand", "memory_background", fields);
        return b.toString();
    }

    /**
     * 对照 parseOutput：解析失败保持原查询与原意图（blank 直接返回）。
     */
    void parseOutput(ChatManage chatManage, String raw) {
        String content = raw == null ? "" : raw.trim();
        if (content.isEmpty()) {
            return;
        }

        StructuredQueryOutput output = parseStructuredQueryOutput(content);
        if (output != null) {
            String rewrite = output.rewriteQuery == null ? "" : output.rewriteQuery.trim();
            if (!rewrite.isEmpty()) {
                chatManage.setRewriteQuery(rewrite);
            }
            chatManage.setIntent(output.intent);
            chatManage.setImageDescription(output.imageDescription == null
                    ? "" : output.imageDescription.trim());
        }
        // 解析失败：保留原查询与意图
    }

    /** 对照 queryUnderstandOutput。 */
    static final class StructuredQueryOutput {
        String rewriteQuery = "";
        String intent = "";
        String imageDescription = "";
    }

    /** 对照 parseStructuredQueryOutput：直接 JSON → 截 {..} 再试。 */
    static StructuredQueryOutput parseStructuredQueryOutput(String raw) {
        String content = raw == null ? "" : raw.trim();
        if (content.isEmpty()) {
            return null;
        }

        StructuredQueryOutput parsed = parseStructuredQueryOutputJson(content);
        if (parsed != null) {
            return parsed;
        }

        // 容忍偶尔的 markdown 包裹或前后散文
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String candidate = content.substring(start, end + 1);
        return parseStructuredQueryOutputJson(candidate);
    }

    /** 对照 parseStructuredQueryOutputJSON：map[string]RawMessage + 键别名族。 */
    static StructuredQueryOutput parseStructuredQueryOutputJson(String content) {
        JsonNode obj;
        try {
            obj = JSON.readTree(content);
        } catch (Exception e) {
            return null;
        }
        if (obj == null || !obj.isObject()) {
            return null;
        }

        StructuredQueryOutput out = new StructuredQueryOutput();
        out.rewriteQuery = firstStringField(obj,
                "rewrite_query", "rewritten_query", "query", "question").trim();

        String intentStr = firstStringField(obj, "intent").trim();
        if (!intentStr.isEmpty()) {
            out.intent = intentStr;
        }

        String desc = firstStringField(obj,
                "image_description", "image_desc", "image_text", "image_ocr_text", "description").trim();
        String ocr = firstStringField(obj,
                "ocr_text", "ocr", "full_ocr", "image_ocr", "ocr_content").trim();
        MergeResult merged = mergeImageDescAndOCR(desc, ocr);
        if (merged.set) {
            out.imageDescription = merged.value;
        }
        return out;
    }

    record MergeResult(String value, boolean set) {}

    static MergeResult mergeImageDescAndOCR(String desc, String ocr) {
        if (desc.isEmpty() && ocr.isEmpty()) {
            return new MergeResult("", false);
        }
        if (desc.isEmpty()) {
            return new MergeResult(ocr, true);
        }
        if (ocr.isEmpty()) {
            return new MergeResult(desc, true);
        }
        if (desc.contains(ocr)) {
            return new MergeResult(desc, true);
        }
        return new MergeResult(desc + "\n\n[OCR]\n" + ocr, true);
    }

    /** 对照 firstStringField：键序尝试，取第一个能解成 string 的值。 */
    static String firstStringField(JsonNode obj, String... keys) {
        for (String key : keys) {
            JsonNode raw = obj.get(key);
            if (raw == null || raw.isMissingNode() || raw.isNull()) {
                continue;
            }
            if (raw.isTextual()) {
                return raw.asText();
            }
        }
        return "";
    }

    /** 对照 applyIntentPromptOverride：agent 覆写优先，空白回落全局。返回是否应用。 */
    static boolean applyIntentPromptOverride(ChatManage chatManage, Map<String, String> globalPrompts) {
        String intentKey = chatManage.getIntent();
        if (chatManage.getIntentPromptOverrides() != null) {
            String raw = chatManage.getIntentPromptOverrides().get(intentKey);
            if (raw != null && !raw.trim().isEmpty()) {
                chatManage.setSystemPromptOverride(raw);
            }
        }
        if (chatManage.getSystemPromptOverride().isEmpty()) {
            if (globalPrompts != null) {
                String prompt = globalPrompts.get(intentKey);
                if (prompt != null) {
                    chatManage.setSystemPromptOverride(prompt);
                }
            }
        }
        return !chatManage.getSystemPromptOverride().isEmpty();
    }

    /** 对照 formatConversationHistory。 */
    static String formatConversationHistory(List<History> historyList) {
        if (historyList == null || historyList.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (History h : historyList) {
            builder.append("------BEGIN------\n");
            builder.append("User question: ");
            builder.append(h.getQuery());
            builder.append("\nAssistant answer: ");
            builder.append(h.getAnswer());
            builder.append("\n------END------\n");
        }
        return builder.toString();
    }
}
