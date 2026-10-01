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
import com.ragagent.session.domain.MessageImage;
import com.ragagent.session.domain.UsedMemory;
import org.junit.jupiter.api.Test;

/**
 * {@link Message} 响应体的 JSON 键序、键集与"不该泄漏的字段"（§14.9l S2 换锚后：
 * 键名＝Java 字段名、键序＝声明序、全部键恒输出）。
 *
 * <p>用 Jackson 的 {@code fieldNames()} 取**顶层**键序（而不是正则）：正则会把嵌套对象的键
 * 也一并捞进来，口径容易和 Go 那边对不齐，反而不稳。</p>
 */
class MessageJsonContractTest {

    private static final OffsetDateTime TS =
            OffsetDateTime.of(2026, 9, 18, 10, 30, 0, 0, ZoneOffset.ofHours(8));

    /** 23 个键、声明序（§14.9l S2 换锚后键名＝Java 字段名、全部恒输出）。 */
    private static final List<String> KEY_ORDER = List.of(
            "id", "sessionId", "requestId", "content", "role", "knowledgeReferences",
            "agentSteps", "mentionedItems", "images", "attachments", "artifacts",
            "completed", "fallback", "agentDurationMs", "usage", "channel", "agentId",
            "modelId", "knowledgeId", "usedMemories", "createdAt", "updatedAt", "deletedAt");

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
        // MessageAttachment.url 是内部句柄（provider://path）。Go 的 tag 是 json:"-"，
        // 且它的 Value() 也是 json.Marshal——所以这一个字段**响应和落库都不带**。
        // 外泄等于给出一个可跨会话下载的引用。
        String out = json(fullMessage());
        assertFalse(out.contains("secret://internal-handle"), "附件存储句柄泄漏到了 JSON: " + out);
    }

    @Test
    void renderedContentAndExecutionContextNeverLeak() {
        // 这两个字段是 json:"-"，只落库不出响应
        String out = json(fullMessage());
        assertFalse(out.contains("RAG-augmented"), "rendered_content 泄漏: " + out);
        assertFalse(out.contains("exec-ctx"), "execution_context 泄漏: " + out);
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
