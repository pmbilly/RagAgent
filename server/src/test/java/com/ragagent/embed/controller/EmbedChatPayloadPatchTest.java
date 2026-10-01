package com.ragagent.embed.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.embed.domain.EmbedChannelEntity;
import com.ragagent.session.dto.QaRequests.CreateKnowledgeQARequest;

/**
 * embed 访客请求改写器的键对齐契约（§14.9l S4）。
 *
 * <p>改写器写回的键必须与 {@code CreateKnowledgeQARequest} 的字段名一致：写错不报错，
 * 只会静默丢掉渠道约束——KB 注入失效意味着访客能拿渠道外的检索面，权限上不可接受。
 * 这里把改写结果**直接用 DTO 反序列化**（DTO 的键名即 Jackson 字段名，与
 * {@code QaRequestBinder} 走的是同一套映射），并断言渠道开关真的落到了请求体上。</p>
 */
class EmbedChatPayloadPatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmbedChannelEntity channel(boolean allowWebSearch, boolean allowFileUpload) {
        EmbedChannelEntity ch = new EmbedChannelEntity();
        ch.setId("chan-1");
        ch.setAgentId("agent-1");
        ch.setAllowWebSearch(allowWebSearch);
        ch.setAllowFileUpload(allowFileUpload);
        return ch;
    }

    private CreateKnowledgeQARequest patch(String rawBody, boolean allowWebSearch,
            boolean allowFileUpload, boolean agentMode) {
        String patched = EmbedChannelDelegateOps.patchEmbedChatPayload(rawBody,
                channel(allowWebSearch, allowFileUpload), agentMode);
        try {
            return MAPPER.readValue(patched, CreateKnowledgeQARequest.class);
        } catch (Exception e) {
            throw new AssertionError("改写后的请求体无法按 DTO 反序列化：" + patched, e);
        }
    }

    @Test
    void injectsChannelScopeWithDtoFieldNames() throws Exception {
        CreateKnowledgeQARequest req = patch("""
                {"query":"访客问题","knowledgeBaseIds":["客户端乱给的"],"agentId":"客户端乱给的",
                 "mcpServiceIds":["客户端乱给的"],"webSearchEnabled":true,"agentEnabled":false}
                """, true, true, true);

        // 渠道值覆盖客户端值（对照 Go patchEmbedChatPayload）
        assertThat(req.agentId).isEqualTo("agent-1");
        assertThat(req.agentEnabled).isTrue();
        assertThat(req.mcpServiceIds()).isEmpty();
        assertThat(req.channel).isEmpty();
        // 渠道 KB 由服务端注入（此处渠道未配 KB → 空数组，不是 null）
        assertThat(req.knowledgeBaseIds()).isEmpty();
        ObjectNode patched = (ObjectNode) MAPPER.readTree(
                EmbedChannelDelegateOps.patchEmbedChatPayload("{}", channel(true, true), true));
        assertThat(patched.has("knowledgeBaseIds")).isTrue();
        assertThat(patched.get("knowledgeBaseIds").isArray()).isTrue();
    }

    @Test
    void clientWebSearchCountsOnlyWhenChannelAllowsIt() {
        assertThat(patch("{\"webSearchEnabled\":true}", true, true, false).webSearchEnabled).isTrue();
        assertThat(patch("{\"webSearchEnabled\":true}", false, true, false).webSearchEnabled).isFalse();
        // Go：仅当客户端给了 bool 才算 opt-in（字符串/数字一律 false）
        assertThat(patch("{\"webSearchEnabled\":\"yes\"}", true, true, false).webSearchEnabled).isFalse();
    }

    @Test
    void fileUploadDisabledStripsClientPayloads() throws Exception {
        String patched = EmbedChannelDelegateOps.patchEmbedChatPayload("""
                {"query":"q","images":[{"data":"x"}],"attachmentUploads":[{"data":"x","fileName":"a.txt","fileSize":1}],
                 "attachmentIds":["att-1"]}
                """, channel(true, false), false);
        ObjectNode node = (ObjectNode) MAPPER.readTree(patched);

        assertThat(node.has("images")).isFalse();
        assertThat(node.has("attachmentUploads")).isFalse();
        assertThat(node.has("attachmentIds")).isFalse();

        // 渠道允许上传时原样保留（键名不变形）
        String kept = EmbedChannelDelegateOps.patchEmbedChatPayload("""
                {"query":"q","attachmentIds":["att-1"]}
                """, channel(true, true), false);
        assertThat(MAPPER.readTree(kept).path("attachmentIds").get(0).asText()).isEqualTo("att-1");
    }

    @Test
    void malformedBodiesKeepExistingContract() {
        // 空体 → 视同 {}
        assertThat(patch("", true, true, false).query).isEmpty();
        // 字面量 null → 视同空体（Go 的 nil map）
        assertThat(patch("null", true, true, false).query).isEmpty();
        // 非对象 → 400 invalid json（PlainErrorException 带 400）
        assertThatThrownBy(() -> EmbedChannelDelegateOps.patchEmbedChatPayload(
                "123", channel(true, true), false))
                .hasMessageContaining("invalid json");
    }
}
