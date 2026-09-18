package com.ragagent.session.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ragagent.common.context.TenantContext;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageNotFoundException;
import com.ragagent.session.domain.MessageSuggestionEvent;
import com.ragagent.session.domain.MessageSuggestionSet;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.domain.SessionOwnerIds;
import com.ragagent.session.domain.SuggestionItem;
import com.ragagent.session.mapper.MessageSuggestionRepository;

/**
 * 追问建议服务（对照 Go {@code internal/application/service/message_suggestion.go}）。
 *
 * <h2>波 1 G3 落地的部分</h2>
 * <ul>
 *   <li>{@code EnsureFollowUps}：消息校验 → AcquireGeneration 抢占 → 四条 suppress
 *       分支（disabled / fallback_answer / empty_answer / answer_asks_question）→
 *       保存与事件；</li>
 *   <li>{@code GetFollowUps} / {@code RecordEvent} 全量；</li>
 *   <li>纯函数辅助：answerEndsWithQuestion / containsSuggestionID /
 *       suggestionErrorCode / normalizeSuggestionText。</li>
 * </ul>
 *
 * <h2>已知差异（对照 Go，随后续波次收口）</h2>
 * <ol>
 *   <li><b>LLM 生成步降级为 failed</b>：{@code generateWithModel} 依赖
 *       ModelService 的运行时工厂（GetChatModel，阶段 7）、知识推荐依赖
 *       customAgentService（波 2/4）——Java 侧配置了 follow-ups 时把集合落成
 *       {@code failed / generation_error}（HTTP 形态与 Go 的生成失败一致），
 *       而不是真调 LLM。未配置 follow-ups 的租户（默认）两侧逐字节一致——
 *       走的是 {@code suppress("disabled")} 快路径，generate 根本不会被调。</li>
 *   <li><b>语言解析</b>：Go 的 ResolveLanguage 会读请求的 Language 上下文
 *       （Language 中间件），Java 侧该中间件未翻译——message 带locale 时一致，
 *       否则两侧都落 DefaultLanguage（WEKNORA_LANGUAGE env，缺省 zh-CN）。</li>
 * </ol>
 */
@Service
public class MessageSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(MessageSuggestionService.class);

    /** 对照 Go {@code suggestionThinkBlock}：<think>...</think> 剥离。 */
    private static final String THINK_BLOCK = "(?s)<think>.*?</think>";
    /** 对照 Go {@code trailingCitationTags}：结尾的 <kb>/<web> 引用块。 */
    private static final String TRAILING_CITATIONS = "(?s)(?:\\s*<(?:kb|web)>.*?</(?:kb|web)>)+\\s*$";

    public static final String EVENT_IMPRESSION = "impression";
    public static final String EVENT_CLICK = "click";
    public static final String EVENT_DISMISS = "dismiss";

    private final MessageSuggestionRepository suggestionRepository;
    private final MessageService messageService;

    public MessageSuggestionService(MessageSuggestionRepository suggestionRepository,
                                    MessageService messageService) {
        this.suggestionRepository = suggestionRepository;
        this.messageService = messageService;
    }

    // ── Ensure（对照 Go EnsureFollowUps，L69-167） ──────────────────────────

    public MessageSuggestionSet ensureFollowUps(String sessionId, String assistantMessageId,
            boolean regenerate) {
        Message message = messageService.getMessage(sessionId, assistantMessageId);
        if (!"assistant".equals(message.getRole()) || !message.isCompleted()) {
            // handler 的 writeError 按 "completed assistant" 子串落 400
            throw new IllegalArgumentException(
                    "follow-up suggestions require a completed assistant message");
        }

        long tenantId = requireTenantId();
        var ec = message.getExecutionContext();
        String locale = resolveLanguage(ec == null ? null : ec.getLocale());
        String configHash = ec == null || ec.getAgentConfigHash() == null
                || ec.getAgentConfigHash().isEmpty()
                ? "no-agent-config" : ec.getAgentConfigHash();
        Map<String, Object> config = ec == null ? null : ec.getQuestionSuggestions();
        Map<String, Object> followUps = followUps(config);
        boolean enabled = followUps != null
                && Boolean.TRUE.equals(followUps.get("enabled"));
        boolean allowRegenerate = followUps != null
                && Boolean.TRUE.equals(followUps.get("allow_regenerate"));

        if (regenerate && (config == null || !enabled || !allowRegenerate)) {
            // writeError 按 "not allowed" 子串落 400
            throw new IllegalArgumentException("suggestion regeneration is not allowed");
        }

        MessageSuggestionSet candidate = new MessageSuggestionSet();
        candidate.setTenantId(tenantId);
        candidate.setSessionId(sessionId);
        candidate.setAssistantMessageId(assistantMessageId);
        candidate.setAgentId(message.getAgentId() == null ? "" : message.getAgentId());
        candidate.setAgentTenantId(0); // Go 的 message.AgentTenantID（json:"-"，共享 agent 随波 5）
        candidate.setPlacement(MessageSuggestionSet.PLACEMENT_AFTER_ANSWER);
        candidate.setConfigHash(configHash);
        candidate.setLocale(locale);
        candidate.setAllowRegenerate(config != null && allowRegenerate);

        var acquire = suggestionRepository.acquireGeneration(candidate, regenerate);
        MessageSuggestionSet set = acquire.set();
        if (!acquire.acquired()) {
            return set;
        }

        if (config == null || !enabled) {
            return suppress(set, "disabled");
        }
        boolean suppressOnFallback = boolVal(followUps, "suppress_on_fallback");
        if (message.isFallback() && suppressOnFallback) {
            return suppress(set, "fallback_answer");
        }
        String answer = stripThink(message.getContent()).trim();
        if (answer.isEmpty()) {
            return suppress(set, "empty_answer");
        }
        boolean suppressWhenAnswerAsksQuestion =
                boolVal(followUps, "suppress_when_answer_asks_question");
        if (suppressWhenAnswerAsksQuestion && answerEndsWithQuestion(answer)) {
            return suppress(set, "answer_asks_question");
        }

        long startedAt = System.currentTimeMillis();
        String modelId = strVal(followUps, "model_id");
        if (modelId == null || modelId.isEmpty()) {
            modelId = message.getModelId() == null ? "" : message.getModelId();
        }
        set.setModelId(modelId);

        List<SuggestionItem> questions;
        try {
            questions = generate(message, answer, followUps);
        } catch (RuntimeException generateErr) {
            set.setStatus(MessageSuggestionSet.STATUS_FAILED);
            set.setErrorCode(suggestionErrorCode(generateErr));
            set.setLatencyMs(System.currentTimeMillis() - startedAt);
            set.setLeaseUntil(null);
            set.setGeneratedAt(OffsetDateTime.now());
            suggestionRepository.save(set);
            log.error("Suggestion generation failed (session {}, message {}, set {}): {}",
                    sessionId, assistantMessageId, set.getId(), generateErr.toString());
            return set;
        }
        long latency = System.currentTimeMillis() - startedAt;

        if (questions.isEmpty()) {
            return suppress(set, "no_candidates");
        }
        set.setQuestions(questions);
        set.setStatus(MessageSuggestionSet.STATUS_READY);
        set.setErrorCode("");
        set.setLatencyMs(latency);
        set.setLeaseUntil(null);
        set.setGeneratedAt(OffsetDateTime.now());
        suggestionRepository.save(set);
        if (regenerate) {
            createEvent(set, "", "regenerate");
        }
        return set;
    }

    /** 对照 Go {@code suppress}（L762-777）。 */
    private MessageSuggestionSet suppress(MessageSuggestionSet set, String reason) {
        set.setStatus(MessageSuggestionSet.STATUS_SUPPRESSED);
        set.setSuppressionReason(reason);
        set.setQuestions(List.of());
        set.setLeaseUntil(null);
        set.setGeneratedAt(OffsetDateTime.now());
        suggestionRepository.save(set);
        return set;
    }

    // ── Get（对照 Go GetFollowUps，L169-192） ───────────────────────────────

    public MessageSuggestionSet getFollowUps(String sessionId, String assistantMessageId) {
        Message message = messageService.getMessage(sessionId, assistantMessageId);
        long tenantId = requireTenantId();
        var ec = message.getExecutionContext();
        String locale = resolveLanguage(ec == null ? null : ec.getLocale());
        String configHash = ec == null || ec.getAgentConfigHash() == null
                || ec.getAgentConfigHash().isEmpty()
                ? "no-agent-config" : ec.getAgentConfigHash();
        return suggestionRepository.getByCacheKey(tenantId, assistantMessageId,
                MessageSuggestionSet.PLACEMENT_AFTER_ANSWER, configHash, locale);
    }

    // ── RecordEvent（对照 Go RecordEvent，L194-218） ────────────────────────

    public void recordEvent(String sessionId, String setId, String questionId, String eventType) {
        if (!EVENT_IMPRESSION.equals(eventType) && !EVENT_CLICK.equals(eventType)
                && !EVENT_DISMISS.equals(eventType)) {
            throw new IllegalArgumentException("invalid suggestion event type");
        }
        long tenantId = requireTenantId();
        MessageSuggestionSet set = suggestionRepository.getById(tenantId, sessionId, setId);
        if (questionId != null && !questionId.isEmpty()
                && !containsSuggestionId(set.getQuestions(), questionId)) {
            throw new IllegalArgumentException("question does not belong to suggestion set");
        }
        if (EVENT_CLICK.equals(eventType) && (questionId == null || questionId.isEmpty())) {
            throw new IllegalArgumentException("click event requires question_id");
        }
        createEvent(set, questionId == null ? "" : questionId, eventType);
    }

    /** 对照 Go {@code createEvent}（L779-797）：actor 取主体派生 id。 */
    private void createEvent(MessageSuggestionSet set, String questionId, String eventType) {
        MessageSuggestionEvent event = new MessageSuggestionEvent();
        event.setTenantId(set.getTenantId());
        event.setSessionId(set.getSessionId());
        event.setSuggestionSetId(set.getId());
        event.setQuestionId(questionId);
        event.setEventType(eventType);
        event.setActorId(SessionOwnerIds.currentSessionOwnerId());
        suggestionRepository.createEvent(event);
    }

    // ── 生成（对照 Go generate，L257-329；Java 降级见类注释第 1 条） ─────────

    private List<SuggestionItem> generate(Message message, String answer,
            Map<String, Object> followUps) {
        // Go 在此按 mode 调 generateWithModel（ModelService 运行时工厂，阶段 7）
        // 与 generateFromKnowledge（customAgentService，波 2/4）。两者就位前
        // 统一落 generation_error，让集合进 failed 态（HTTP 形态与失败一致）。
        throw new UnsupportedOperationException(
                "suggestion generation is not available yet (runtime model factory untranslated)");
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

    private static Map<String, Object> followUps(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        Object raw = config.get("follow_ups");
        @SuppressWarnings("unchecked")
        Map<String, Object> followUps = raw instanceof Map ? (Map<String, Object>) raw : null;
        return followUps;
    }

    private static boolean boolVal(Map<String, Object> map, String key) {
        return map != null && Boolean.TRUE.equals(map.get(key));
    }

    private static String strVal(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object raw = map.get(key);
        return raw instanceof String s ? s : "";
    }

    private static long requireTenantId() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("types.TenantIDContextKey not set in context");
        }
        return tenantId;
    }
}
