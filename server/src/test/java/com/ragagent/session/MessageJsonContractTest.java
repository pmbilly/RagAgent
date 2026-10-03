package com.ragagent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ragagent.agent.domain.AgentStep;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.session.domain.MentionedItem;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageArtifact;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.MessageExecutionContext;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.session.domain.SuggestionAttribution;
import com.ragagent.session.domain.UsedMemory;
import org.junit.jupiter.api.Test;

/**
 * {@link Message} 响应体的 JSON 键序、键集与"不该泄漏的字段"（键名＝Java 字段名、
 * 键序＝声明序、全部键恒输出）。
 *
 * <p>用 Jackson 的 {@code fieldNames()} 取**顶层**键序（而不是正则）：正则会把嵌套对象的键
 * 也一并捞进来，口径反而不稳。</p>
 */
class MessageJsonContractTest {

    private static final OffsetDateTime TS =
            OffsetDateTime.of(2026, 9, 18, 10, 30, 0, 0, ZoneOffset.ofHours(8));

    /** 23 个键、声明序（键名＝Java 字段名、全部恒输出）。 */
    private static final List<String> KEY_ORDER = List.of(
            "id", "sessionId", "requestId", "content", "role", "knowledgeReferences",
            "agentSteps", "mentionedItems", "images", "attachments", "artifacts",
            "completed", "fallback", "agentDurationMs", "usage", "channel", "agentId",
            "modelId", "knowledgeId", "usedMemories", "createdAt", "updatedAt", "deletedAt");

    /** execution_context 的 12 个键、声明序（同样无条件输出）。 */
    private static final List<String> EXECUTION_CONTEXT_KEYS = List.of(
            "agentConfigHash", "questionSuggestions", "knowledgeBaseIds", "knowledgeIds",
            "tagIds", "tagScopes", "mcpServiceIds", "skillNames",
            "webSearchEnabled", "locale", "suggestionAttribution", "langfuseTraceparent");

    @Test
    void topLevelKeyOrderMatchesDeclaration() {
        assertEquals(KEY_ORDER, fieldNames(json(fullMessage())));
    }

    /** 换锚后没有条件键：全空实例也输出全部 23 个键（空列表写 {@code []}、缺值写 {@code null}）。 */
    @Test
    void emptyMessageStillEmitsEveryKey() {
        assertEquals(KEY_ORDER, fieldNames(json(new Message())));
    }

    @Test
    void attachmentStorageUrlNeverLeaks() {
        // MessageAttachment.url 是内部句柄（provider://path），@JsonIgnore 让
        // 响应和落库都不带这个字段。
        // 外泄等于给出一个可跨会话下载的引用。
        String out = json(fullMessage());
        assertFalse(out.contains("secret://internal-handle"), "附件存储句柄泄漏到了 JSON: " + out);
    }

    @Test
    void renderedContentAndExecutionContextNeverLeak() {
        // 这两个字段带 @JsonIgnore，只落库不出响应
        String out = json(fullMessage());
        assertFalse(out.contains("RAG-augmented"), "rendered_content 泄漏: " + out);
        assertFalse(out.contains("exec-ctx"), "execution_context 泄漏: " + out);
    }

    /**
     * {@code execution_context} 的**落库**键集。
     *
     * <p>这一列不出响应，所以没有任何 HTTP 夹具能守它——漏改不会报错，只会让追问建议之类的
     * 派生体验读不到当时的作用域（静默降级）。此处把键集与"旧下划线键不得出现"钉死，
     * 存量行由 SQL 迁移负责改名。</p>
     */
    @Test
    void executionContextJsonbKeysMatchFieldNames() {
        MessageExecutionContext ctx = new MessageExecutionContext();
        ctx.setAgentConfigHash("h");
        ctx.setQuestionSuggestions(java.util.Map.of("enabled", true));
        ctx.setKnowledgeBaseIds(new ArrayList<>(List.of("kb1")));
        ctx.setKnowledgeIds(new ArrayList<>(List.of("k1")));
        ctx.setTagIds(new ArrayList<>(List.of("t1")));
        ctx.setTagScopes(new ArrayList<>(List.of(java.util.Map.of("knowledgeBaseId", "kb1"))));
        ctx.setMcpServiceIds(new ArrayList<>(List.of("m1")));
        ctx.setSkillNames(new ArrayList<>(List.of("s1")));
        ctx.setWebSearchEnabled(true);
        ctx.setLocale("zh");
        ctx.setSuggestionAttribution(new SuggestionAttribution());
        ctx.setLangfuseTraceparent("00-abc-def-01");

        String out = json(ctx);
        assertEquals(EXECUTION_CONTEXT_KEYS, fieldNames(out));
        // 全空实例同样输出全部 12 个键（条件输出的 @JsonInclude 已摘除，键恒出现）
        assertEquals(EXECUTION_CONTEXT_KEYS, fieldNames(json(new MessageExecutionContext())));
        // 旧下划线键不得出现（否则等于旧行读不出来）
        assertFalse(out.contains("agent_config_hash"), out);
        assertFalse(out.contains("langfuse_traceparent"), out);
        assertFalse(out.contains("web_search_enabled"), out);
        // 跨模块透传的**内层**键刻意保留下划线（agent 域配置，见类注释）
        assertTrue(out.contains("\"enabled\""), out);
    }

    /** 同 {@code Session.pinned} 那类坑：布尔字段不带 {@code is} 前缀，且只出一个键。 */
    @Test
    void attachmentBooleanFieldEmitsOneKeyOnly() {
        MessageAttachment a = new MessageAttachment();
        a.setFileName("f.pdf");
        a.setFileSize(10);
        a.setTruncated(true);

        String out = json(a);
        assertTrue(out.contains("\"truncated\":true"), out);
        assertFalse(out.contains("\"isTruncated\""), "多吐了重复键: " + out);
        assertFalse(out.contains("\"is_truncated\""), "旧下划线键不该出现: " + out);
    }

    // ── 构造 ────────────────────────────────────────────────────────────────

    private static Message fullMessage() {
        Message m = new Message();
        m.setId("m1");
        m.setSessionId("s1");
        m.setRequestId("r1");
        m.setContent("hi");
        m.setRole(Message.ROLE_ASSISTANT);

        SearchResult ref = new SearchResult();
        ref.setId("k1");
        m.setKnowledgeReferences(new ArrayList<>(List.of(ref)));

        AgentStep step = new AgentStep();
        step.setIteration(0);
        m.setAgentSteps(new ArrayList<>(List.of(step)));

        MentionedItem mi = new MentionedItem();
        mi.setId("mi1");
        mi.setName("n");
        mi.setType("kb");
        m.setMentionedItems(new ArrayList<>(List.of(mi)));

        MessageImage img = new MessageImage();
        img.setUrl("u");
        img.setCaption("c");
        m.setImages(new ArrayList<>(List.of(img)));

        MessageAttachment att = new MessageAttachment();
        att.setId("a1");
        att.setUrl("secret://internal-handle");
        att.setFileName("f");
        att.setFileSize(10);
        m.setAttachments(new ArrayList<>(List.of(att)));

        MessageArtifact art = new MessageArtifact();
        art.setUrl("p://a");
        art.setFileName("f");
        art.setModTime(TS);
        m.setArtifacts(new ArrayList<>(List.of(art)));

        m.setCompleted(true);
        m.setFallback(true);
        m.setAgentDurationMs(42);

        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(1);
        usage.setCompletionTokens(2);
        usage.setTotalTokens(3);
        m.setUsage(usage);

        m.setRenderedContent("RAG-augmented");
        m.setChannel("web");
        m.setAgentId("ag1");
        m.setAgentTenantId(7);
        m.setModelId("md1");
        m.setKnowledgeId("kn1");

        UsedMemory um = new UsedMemory();
        um.setId("u1");
        um.setKind("fact");
        um.setContent("c");
        m.setUsedMemories(new ArrayList<>(List.of(um)));

        m.setCreatedAt(TS);
        m.setUpdatedAt(TS);
        return m;
    }

    private static String json(Object value) {
        try {
            ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> fieldNames(String json) {
        try {
            ObjectMapper mapper = JsonMapper.builder().build();
            List<String> out = new ArrayList<>();
            mapper.readTree(json).fieldNames().forEachRemaining(out::add);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
