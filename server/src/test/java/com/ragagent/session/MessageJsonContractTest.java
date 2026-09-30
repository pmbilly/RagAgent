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
 * {@link Message} 响应体的 JSON 键序、键集与"不该泄漏的字段"——期望值实测 Go 得出。
 *
 * <p>用 Jackson 的 {@code fieldNames()} 取**顶层**键序（而不是正则）：正则会把嵌套对象的键
 * 也一并捞进来，口径容易和 Go 那边对不齐，反而不稳。</p>
 */
class MessageJsonContractTest {

    private static final OffsetDateTime TS =
            OffsetDateTime.of(2026, 9, 18, 10, 30, 0, 0, ZoneOffset.ofHours(8));

    @Test
    void topLevelKeyOrderMatchesGo() {
        // Go 实测：
        // id session_id request_id content role knowledge_references agent_steps mentioned_items
        // images attachments artifacts is_completed is_fallback agent_duration_ms usage
        // channel agent_id model_id knowledge_id used_memories created_at updated_at deleted_at
        assertEquals(List.of(
                "id", "session_id", "request_id", "content", "role", "knowledge_references",
                "agent_steps", "mentioned_items", "images", "attachments", "artifacts",
                "is_completed", "is_fallback", "agent_duration_ms", "usage", "channel", "agent_id",
                "model_id", "knowledge_id", "used_memories", "created_at", "updated_at",
                "deleted_at"),
                fieldNames(json(fullMessage())));
    }

    @Test
    void emptyMessageKeepsOnlyTheAlwaysOutputKeys() {
        // Go 实测（全空实例）：id session_id request_id content role knowledge_references
        //                     is_completed created_at updated_at deleted_at
        // 注意 knowledge_references **没有 omitempty**，空也要占位。
        Message m = new Message();
        assertEquals(List.of(
                "id", "session_id", "request_id", "content", "role", "knowledge_references",
                "is_completed", "created_at", "updated_at", "deleted_at"),
                fieldNames(json(m)));
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

    @Test
    void attachmentBooleanFieldEmitsTheGoSnakeCaseKeyOnly() {
        // 同 Session.is_pinned 那类坑：字段名不带 is 前缀
        MessageAttachment a = new MessageAttachment();
        a.setFileName("f.pdf");
        a.setFileSize(10);
        a.setTruncated(true);

        String out = json(a);
        assertTrue(out.contains("\"is_truncated\":true"), out);
        assertFalse(out.contains("\"truncated\""), "多吐了驼峰重复键: " + out);
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
