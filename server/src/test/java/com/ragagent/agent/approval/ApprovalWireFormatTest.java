package com.ragagent.agent.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.domain.ResponseType;
import org.junit.jupiter.api.Test;

/**
 * 线上契约测试：事件的 JSON 字段名/结构必须与 Go 逐字一致
 * （前端与（滚动升级期的）Go 实例都按 Go 的 json tag 解析）。
 *
 * <p>尤其钉住两处易错点：
 * <ul>
 *   <li>Go 的 {@code json.RawMessage}（args_json / modified_args）是**内联 JSON**，不是字符串；</li>
 *   <li>Go 的 omitempty 字段（"timeout_seconds" 之外的省略项）在空值时必须缺席。</li>
 * </ul>
 */
class ApprovalWireFormatTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode parse(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new AssertionError("invalid json: " + s, e);
        }
    }

    @Test
    void toolApprovalRequiredMatchesGoTags() {
        ToolApprovalRequiredData data = new ToolApprovalRequiredData(
                "p1", 7, "s1", "m1", "svc", "svcname", "tool", "mcp_tool",
                "desc", Map.of("a", 1), "{\"a\":1}", 60, 1700000000L, "tc1", "r1");

        JsonNode node = parse(ApprovalJson.write(data));
        assertEquals("p1", node.get("pending_id").asText());
        assertEquals(7, node.get("tenant_id").asLong());
        assertEquals("s1", node.get("session_id").asText());
        assertEquals("m1", node.get("assistant_message_id").asText());
        assertEquals("svc", node.get("service_id").asText());
        assertEquals("svcname", node.get("service_name").asText());
        assertEquals("tool", node.get("mcp_tool_name").asText());
        assertEquals("mcp_tool", node.get("registered_tool_name").asText());
        assertEquals("desc", node.get("description").asText());
        assertEquals(1, node.get("args").get("a").asInt());
        assertEquals("{\"a\":1}", node.get("args_json").asText());
        assertEquals(60, node.get("timeout_seconds").asInt());
        assertEquals(1700000000L, node.get("requested_at").asLong());
        assertEquals("tc1", node.get("tool_call_id").asText());
        assertEquals("r1", node.get("request_id").asText());

        // 空值省略（Go 的 omitempty）：args/args_json/request_id
        String minimal = ApprovalJson.write(new ToolApprovalRequiredData(
                "p1", 7, "s1", "m1", "svc", "svcname", "tool", "mcp_tool",
                "desc", null, "", 60, 1700000000L, "tc1", ""));
        assertFalse(minimal.contains("args_json"));
        assertFalse(minimal.contains("request_id"));
        assertTrue(minimal.contains("timeout_seconds"));
    }

    @Test
    void toolApprovalResolvedMatchesGoTags() {
        JsonNode node = parse(ApprovalJson.write(
                new ToolApprovalResolvedData("p1", false, "no", true, false)));
        assertEquals("p1", node.get("pending_id").asText());
        assertFalse(node.get("approved").asBoolean());
        assertEquals("no", node.get("reason").asText());
        assertTrue(node.get("timed_out").asBoolean());
        assertFalse(node.get("canceled").asBoolean());

        // reason 为空 → omitempty 缺席
        assertFalse(ApprovalJson.write(new ToolApprovalResolvedData("p1", true, "", false, false))
                .contains("reason"));
    }

    @Test
    void oauthPayloadsMatchGoTags() {
        JsonNode required = parse(ApprovalJson.write(new McpOauthRequiredData(
                "p1", 7, "s1", "m1", "svc", "svcname", "tool", 30, 1700000000L, "tc1", "r1")));
        assertEquals("p1", required.get("pending_id").asText());
        assertTrue(required.has("mcp_tool_name"));
        assertTrue(required.has("tool_call_id"));
        assertTrue(required.has("timeout_seconds"));

        JsonNode resolved = parse(ApprovalJson.write(
                new McpOauthResolvedData("p1", "svc", true, "ok", false, false)));
        assertEquals("svc", resolved.get("service_id").asText());
        assertTrue(resolved.get("authorized").asBoolean());
    }

    /** 跨实例报文：字段名与 Go resolveMessage 一致，modified_args 必须是内联 JSON 对象。 */
    @Test
    void resolveMessageMatchesGoTags() {
        String payload = ApprovalJson.write(ResolveMessage.of(
                7, "u1", "p1", Decision.allowWith("{\"a\":1}"), "reply-chan", "origin-1", "nonce-1"));

        JsonNode node = parse(payload);
        assertEquals(7, node.get("tenant_id").asLong());
        assertEquals("u1", node.get("user_id").asText());
        assertEquals("p1", node.get("pending_id").asText());
        assertTrue(node.get("approved").asBoolean());
        assertTrue(node.get("modified_args").isObject(), "RawMessage 必须内联成 JSON 对象");
        assertEquals(1, node.get("modified_args").get("a").asInt());
        assertEquals("reply-chan", node.get("reply_channel").asText());
        assertEquals("origin-1", node.get("origin_id").asText());
        assertEquals("nonce-1", node.get("request_nonce").asText());
        // timed_out / canceled 为 false → omitempty 缺席
        assertFalse(node.has("timed_out"));
        assertFalse(node.has("canceled"));
        assertFalse(node.has("reason"));

        // 反向：超时/取消为 true 时必须出现
        JsonNode timeout = parse(ApprovalJson.write(ResolveMessage.of(
                7, null, "p1", Decision.timeout("approval timeout"), null, "o", "n")));
        assertTrue(timeout.get("timed_out").asBoolean());
        assertFalse(timeout.has("user_id"));

        // 解回来仍是等价的决策
        ResolveMessage decoded = ApprovalJson.read(payload, ResolveMessage.class);
        assertNotNull(decoded);
        Decision d = decoded.toDecision();
        assertTrue(d.approved());
        assertEquals(parse("{\"a\":1}"), parse(d.modifiedArgs()));
    }

    @Test
    void resolveAckMatchesGoTags() {
        JsonNode node = parse(ApprovalJson.write(
                ResolveAck.of("p1", ResolveAck.STATUS_ALREADY_RESOLVED, "B", "nonce")));
        assertEquals("p1", node.get("pending_id").asText());
        assertEquals("already_resolved", node.get("status").asText());
        assertEquals("B", node.get("origin_id").asText());
        assertEquals("nonce", node.get("request_nonce").asText());

        // 非法 JSON 不应抛异常，只返回 null（对照 Go：log.Warnf + continue）
        assertNull(ApprovalJson.read("{not json", ResolveAck.class));
    }

    @Test
    void pubsubChannelHonorsNamespace() {
        // 未设置 WEKNORA_REDIS_NAMESPACE 时为裸前缀
        assertEquals(Gate.PUBSUB_CHANNEL_BASE, Gate.pubsubChannel());
        assertEquals("weknora:mcp_approval:resolve", Gate.PUBSUB_CHANNEL_BASE);
    }

    /**
     * 事件包络本身（id/type/sessionId/data/metadata/requestId）与 Go event.Event 对齐；
     * response_type 的字符串取值是前端契约（见 ResponseType）。
     */
    @Test
    void eventEnvelopeUsesContractResponseTypes() {
        assertEquals("tool_approval_required", ResponseType.TOOL_APPROVAL_REQUIRED.value());
        assertEquals("tool_approval_resolved", ResponseType.TOOL_APPROVAL_RESOLVED.value());
        assertEquals("mcp_oauth_required", ResponseType.MCP_OAUTH_REQUIRED.value());
        assertEquals("mcp_oauth_resolved", ResponseType.MCP_OAUTH_RESOLVED.value());

        Event evt = Event.of("id-1", ResponseType.TOOL_APPROVAL_REQUIRED, "s1",
                new ToolApprovalResolvedData("p1", true, "", false, false),
                Map.of("pending_id", "p1"), "r1");
        assertEquals("id-1", evt.id());
        assertEquals("s1", evt.sessionId());
        assertEquals("p1", evt.metadata().get("pending_id"));
        assertEquals("r1", evt.requestId());
    }
}
