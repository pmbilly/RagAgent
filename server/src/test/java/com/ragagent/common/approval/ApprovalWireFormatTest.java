package com.ragagent.common.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.llm.ResponseType;
import org.junit.jupiter.api.Test;

/**
 * 审批 Redis 内部线的契约测试：JSON 键 = Java 字段名（camelCase，无逐字段注解）。
 * 前端可见的是 event/ 的 SSE 同键类型（仍是 snake，归 session 域切片）——本测试只钉后端内部 wire。
 *
 * <p>钉住两处易错点：
 * <ul>
 *   <li>{@code modifiedArgs} / {@code argsJson} 语义：modifiedArgs 必须内联成 JSON 对象（不是字符串）；</li>
 *   <li>{@code @JsonInclude(NON_NULL)} 的三态编码：false 以外的缺席语义（timedOut/canceled/reason 等空值缺席）。</li>
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
    void toolApprovalRequiredWireFormat() {
        ToolApprovalRequiredData data = new ToolApprovalRequiredData(
                "p1", 7, "s1", "m1", "svc", "svcname", "tool", "mcp_tool",
                "desc", Map.of("a", 1), "{\"a\":1}", 60, 1700000000L, "tc1", "r1");

        JsonNode node = parse(ApprovalJson.write(data));
        assertEquals("p1", node.get("pendingId").asText());
        assertEquals(7, node.get("tenantId").asLong());
        assertEquals("s1", node.get("sessionId").asText());
        assertEquals("m1", node.get("assistantMessageId").asText());
        assertEquals("svc", node.get("serviceId").asText());
        assertEquals("svcname", node.get("serviceName").asText());
        assertEquals("tool", node.get("mcpToolName").asText());
        assertEquals("mcp_tool", node.get("registeredToolName").asText());
        assertEquals("desc", node.get("description").asText());
        assertEquals(1, node.get("args").get("a").asInt());
        assertEquals("{\"a\":1}", node.get("argsJson").asText());
        assertEquals(60, node.get("timeoutSeconds").asInt());
        assertEquals(1700000000L, node.get("requestedAtUnix").asLong());
        assertEquals("tc1", node.get("toolCallId").asText());
        assertEquals("r1", node.get("requestId").asText());

        // 空值省略：args/argsJson/request_id
        String minimal = ApprovalJson.write(new ToolApprovalRequiredData(
                "p1", 7, "s1", "m1", "svc", "svcname", "tool", "mcp_tool",
                "desc", null, "", 60, 1700000000L, "tc1", ""));
        assertFalse(minimal.contains("argsJson"));
        assertFalse(minimal.contains("requestId"));
        assertTrue(minimal.contains("timeoutSeconds"));
    }

    @Test
    void toolApprovalResolvedWireFormat() {
        JsonNode node = parse(ApprovalJson.write(
                new ToolApprovalResolvedData("p1", false, "no", true, false)));
        assertEquals("p1", node.get("pendingId").asText());
        assertFalse(node.get("approved").asBoolean());
        assertEquals("no", node.get("reason").asText());
        assertTrue(node.get("timedOut").asBoolean());
        assertFalse(node.get("canceled").asBoolean());

        // reason 为空 → 键缺席
        assertFalse(ApprovalJson.write(new ToolApprovalResolvedData("p1", true, "", false, false))
                .contains("reason"));
    }

    @Test
    void oauthPayloadsWireFormat() {
        JsonNode required = parse(ApprovalJson.write(new McpOauthRequiredData(
                "p1", 7, "s1", "m1", "svc", "svcname", "tool", 30, 1700000000L, "tc1", "r1")));
        assertEquals("p1", required.get("pendingId").asText());
        assertTrue(required.has("mcpToolName"));
        assertTrue(required.has("toolCallId"));
        assertTrue(required.has("timeoutSeconds"));

        JsonNode resolved = parse(ApprovalJson.write(
                new McpOauthResolvedData("p1", "svc", true, "ok", false, false)));
        assertEquals("svc", resolved.get("serviceId").asText());
        assertTrue(resolved.get("authorized").asBoolean());
    }

    /** 跨实例报文：字段名 = Java 字段名，modifiedArgs 必须是内联 JSON 对象。 */
    @Test
    void resolveMessageWireFormat() {
        String payload = ApprovalJson.write(ResolveMessage.of(
                7, "u1", "p1", Decision.allowWith("{\"a\":1}"), "reply-chan", "origin-1", "nonce-1"));

        JsonNode node = parse(payload);
        assertEquals(7, node.get("tenantId").asLong());
        assertEquals("u1", node.get("userId").asText());
        assertEquals("p1", node.get("pendingId").asText());
        assertTrue(node.get("approved").asBoolean());
        assertTrue(node.get("modifiedArgs").isObject(), "modifiedArgs 必须内联成 JSON 对象");
        assertEquals(1, node.get("modifiedArgs").get("a").asInt());
        assertEquals("reply-chan", node.get("replyChannel").asText());
        assertEquals("origin-1", node.get("originId").asText());
        assertEquals("nonce-1", node.get("requestNonce").asText());
        // timedOut / canceled 为 false → 键缺席
        assertFalse(node.has("timedOut"));
        assertFalse(node.has("canceled"));
        assertFalse(node.has("reason"));

        // 反向：超时/取消为 true 时必须出现
        JsonNode timeout = parse(ApprovalJson.write(ResolveMessage.of(
                7, null, "p1", Decision.timeout("approval timeout"), null, "o", "n")));
        assertTrue(timeout.get("timedOut").asBoolean());
        assertFalse(timeout.has("userId"));

        // 解回来仍是等价的决策
        ResolveMessage decoded = ApprovalJson.read(payload, ResolveMessage.class);
        assertNotNull(decoded);
        Decision d = decoded.toDecision();
        assertTrue(d.approved());
        assertEquals(parse("{\"a\":1}"), parse(d.modifiedArgs()));
    }

    @Test
    void resolveAckWireFormat() {
        JsonNode node = parse(ApprovalJson.write(
                ResolveAck.of("p1", ResolveAck.STATUS_ALREADY_RESOLVED, "B", "nonce")));
        assertEquals("p1", node.get("pendingId").asText());
        assertEquals("already_resolved", node.get("status").asText());
        assertEquals("B", node.get("originId").asText());
        assertEquals("nonce", node.get("requestNonce").asText());

        // 非法 JSON 不应抛异常，只返回 null
        assertNull(ApprovalJson.read("{not json", ResolveAck.class));
    }

    @Test
    void pubsubChannelHonorsNamespace() {
        // 未设置 WEKNORA_REDIS_NAMESPACE 时为裸前缀
        assertEquals(Gate.PUBSUB_CHANNEL_BASE, Gate.pubsubChannel());
        assertEquals("weknora:mcp_approval:resolve", Gate.PUBSUB_CHANNEL_BASE);
    }

    /**
     * 事件包络本身（id/type/sessionId/data/metadata/requestId）是固定线格式；
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
                Map.of("pendingId", "p1"), "r1");
        assertEquals("id-1", evt.id());
        assertEquals("s1", evt.sessionId());
        assertEquals("p1", evt.metadata().get("pendingId"));
        assertEquals("r1", evt.requestId());
    }
}
