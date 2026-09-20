package com.ragagent.agent.tools;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.protocol.ContentItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 工具族纯函数的 Go 实录回放：名称消毒/截断、内容提取、超时合并、ref 与注册名、
 * 分页、参数解码、OAuth 等待与连接错误。
 */
class McpRecordingTest {

    private static McpService svc(String id, String name) {
        McpService s = new McpService();
        s.setId(id);
        s.setName(name);
        s.setEnabled(true);
        return s;
    }

    private static McpToolWrapper wrapper(McpService service, String toolName, String description, String schema) {
        // schema 以原始字符串进 McpTool（对照 Go 的 json.RawMessage——ref 哈希的是原字节）
        com.ragagent.mcp.domain.McpTool tool = new com.ragagent.mcp.domain.McpTool(
                toolName, description, schema);
        return new McpToolWrapper(service, tool, null, null, 0, 7);
    }

    @Test
    void namesMatchGo() {
        JsonNode cases = Tools45cFakes.rec45c("mcp_tool", "names").get("cases");
        // 探针的服务列表（按序）：Stub Service / Zhang San's 数据分析 / 超长名，最后一条是长工具名
        String[] serviceNames = {"Stub Service", "Zhang San's 数据分析",
                "very_long_service_name_".repeat(5), "Stub Service"};
        String schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}";
        assertThat(cases.size()).isEqualTo(serviceNames.length);
        for (int i = 0; i < cases.size(); i++) {
            JsonNode c = cases.get(i);
            String toolName = i == cases.size() - 1 ? "t".repeat(80) : "Query Orders";
            McpToolWrapper w = wrapper(svc("svc-" + (i + 1), serviceNames[i]), toolName, "d", schema);
            assertThat(w.getName()).as("case %d", i).isEqualTo(c.get("name").asText());
        }
    }

    @Test
    void sanitizeMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("mcp_tool", "sanitize").get("cases");
        for (JsonNode c : cases) {
            assertThat(McpToolWrapper.sanitizeName(c.get("in").asText()))
                    .as("in=%s", c.get("in").asText())
                    .isEqualTo(c.get("out").asText());
        }
    }

    @Test
    void descriptionAndParametersMatchGo() {
        McpService svc = svc("svc-1", "Stub Service");
        McpToolWrapper empty = wrapper(svc, "q", "", null);
        McpToolWrapper full = wrapper(svc, "q", "Query the orders DB", null);
        assertThat(empty.getDescription()).isEqualTo(Tools45cFakes.rec45c("mcp_tool", "description_empty").get("out").asText());
        assertThat(full.getDescription()).isEqualTo(Tools45cFakes.rec45c("mcp_tool", "description_full").get("out").asText());
        // 缺省 schema：值形态一致（键序无关；Go 是 struct marshal 但这里 map 只有两键）
        assertThat(RecordingSupport.canonicalJson(empty.getParameters()))
                .isEqualTo(RecordingSupport.canonicalJson(RecordingSupport.readTree(
                        Tools45cFakes.rec45c("mcp_tool", "parameters_default").get("out").asText())));
        // 有 schema 原样透传
        assertThat(RecordingSupport.canonicalJson(full.getParameters()))
                .isEqualTo(RecordingSupport.canonicalJson(RecordingSupport.readTree(
                        Tools45cFakes.rec45c("mcp_tool", "parameters_raw").get("out").asText())));
    }

    @Test
    void callTimeoutMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("mcp_tool", "call_timeout").get("cases");
        // 逐 case 重建（Go 探针的 timeout_sec = 0/-5/30/120 + nil）
        int[] secs = {0, -5, 30, 120};
        for (int sec : secs) {
            McpService s = svc("svc-t", "t");
            s.setAdvancedConfig(new com.ragagent.mcp.domain.McpAdvancedConfig(sec, 0, 0));
            McpToolWrapper w = wrapper(s, "q", "", null);
            JsonNode row = findTimeoutRow(cases, sec);
            assertThat(SandboxExecuteResult.GoDuration.of(w.callToolTimeout(Duration.ZERO)))
                    .isEqualTo(row.get("call_60").asText());
            assertThat(SandboxExecuteResult.GoDuration.of(w.callToolTimeout(Duration.ofSeconds(10))))
                    .isEqualTo(row.get("call_10").asText());
            assertThat(SandboxExecuteResult.GoDuration.of(w.callToolTimeout(Duration.ofSeconds(300))))
                    .isEqualTo(row.get("call_300").asText());
        }
        McpToolWrapper noAdv = wrapper(svc("s", "q"), "q", "", null);
        JsonNode nilRow = findTimeoutRow(cases, -999);
        assertThat(SandboxExecuteResult.GoDuration.of(noAdv.callToolTimeout(Duration.ZERO)))
                .isEqualTo(nilRow.get("call_60").asText());
    }

    private static JsonNode findTimeoutRow(JsonNode cases, int sec) {
        for (JsonNode c : cases) {
            if (c.get("timeout_sec").asInt(-999) == sec) {
                return c;
            }
        }
        return cases.get(cases.size() - 1);
    }

    @Test
    void extractContentTextMatchesGo() {
        List<ContentItem> items = List.of(
                new ContentItem("text", "hello", "", ""),
                new ContentItem("image", "", "AAAA", "image/png"),
                new ContentItem("image", "", "BBBB", ""),
                new ContentItem("resource", "", "", "application/pdf"),
                new ContentItem("audio", "", "CC", ""),
                new ContentItem("text", "", "", ""));
        assertThat(McpToolWrapper.extractContentText(items))
                .isEqualTo(Tools45cFakes.rec45c("mcp_tool", "extract_text").get("out").asText());
        assertThat(McpToolWrapper.extractContentText(List.of()))
                .isEqualTo(Tools45cFakes.rec45c("mcp_tool", "extract_text_empty").get("out").asText());
    }

    @Test
    void extractContentAndImagesMatchesGo() {
        // ok
        List<ContentItem> items = List.of(
                new ContentItem("text", "before", "", ""),
                new ContentItem("image", "", "cGFuZGE=", "image/png"),
                new ContentItem("text", "after", "", ""));
        McpToolWrapper.ContentExtract got = McpToolWrapper.extractContentAndImages(items);
        JsonNode r = Tools45cFakes.rec45c("mcp_tool", "extract_images_ok");
        assertThat(got.text()).isEqualTo(r.get("out").asText());
        assertThat(got.images()).containsExactlyElementsOf(jsonStringList(r.get("images")));
        assertThat(got.skippedImages()).isEqualTo(r.get("skipped").asInt());
        // Go 侧是 struct marshal（声明序 type,text,data,mimeType，omitempty）——不能用
        // GoJsonCodec（map 排序）；用 GO_ENCODER（插入序 + Go 转义）
        assertThat(McpCatalog.goEncoderJson(toOmitemptyMaps(McpToolWrapper.redactImageData(items))))
                .isEqualTo(r.get("redacted").asText());

        // overflow（7 张 jpeg）
        List<ContentItem> tooMany = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            tooMany.add(new ContentItem("image", "", "Zg==", "image/jpeg"));
        }
        got = McpToolWrapper.extractContentAndImages(tooMany);
        r = Tools45cFakes.rec45c("mcp_tool", "extract_images_overflow");
        assertThat(got.text()).isEqualTo(r.get("out").asText());
        assertThat(got.images()).containsExactlyElementsOf(jsonStringList(r.get("images")));
        assertThat(got.skippedImages()).isEqualTo(r.get("skipped").asInt());

        // oversize + 不允许的 MIME
        String oversize = "A".repeat((int) ((10L << 20) / 3) + 100);
        List<ContentItem> mixed = List.of(
                new ContentItem("image", "", oversize, "image/webp"),
                new ContentItem("image", "", "Zg==", "image/svg+xml"));
        got = McpToolWrapper.extractContentAndImages(mixed);
        r = Tools45cFakes.rec45c("mcp_tool", "extract_images_skipped");
        assertThat(got.text()).isEqualTo(r.get("out").asText());
        assertThat(got.images()).containsExactlyElementsOf(jsonStringList(r.get("images")));
        assertThat(got.skippedImages()).isEqualTo(r.get("skipped").asInt());

        // 空
        got = McpToolWrapper.extractContentAndImages(List.of());
        r = Tools45cFakes.rec45c("mcp_tool", "extract_images_empty");
        assertThat(got.text()).isEqualTo(r.get("out").asText());
        assertThat(got.skippedImages()).isEqualTo(r.get("skipped").asInt());
    }

    private static List<String> jsonStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    /** ContentItem 的 Go marshal 是 omitempty（type 恒输出；text/data/mimeType 空则省）。 */
    private static List<Map<String, Object>> toOmitemptyMaps(List<ContentItem> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContentItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", item.type());
            if (!item.text().isEmpty()) {
                m.put("text", item.text());
            }
            if (!item.data().isEmpty()) {
                m.put("data", item.data());
            }
            if (!item.mimeType().isEmpty()) {
                m.put("mimeType", item.mimeType());
            }
            out.add(m);
        }
        return out;
    }

    @Test
    void disabledResultMatchesGo() {
        // disabled 的 out_json 是 Go json.Marshal(*ToolResult) 的完整结果（data 是
        // omitempty 的 map，含 status 键）；用 canonical（键序无关）比对值形态。
        ToolResult disabled = McpToolWrapper.disabledMcpToolResult(null);
        JsonNode recorded = RecordingSupport.readTree(
                Tools45cFakes.rec45c("mcp_tool", "disabled").get("out_json").asText());
        assertThat(disabled.isSuccess()).isEqualTo(recorded.get("success").asBoolean());
        assertThat(disabled.getError()).isEqualTo(recorded.get("error").asText());
        assertThat(recorded.has("data")).isFalse();

        ToolResult err = McpToolWrapper.disabledMcpToolResult("db down");
        JsonNode recordedErr = RecordingSupport.readTree(
                Tools45cFakes.rec45c("mcp_tool", "disabled_err").get("out_json").asText());
        assertThat(err.getError()).isEqualTo(recordedErr.get("error").asText());
        assertThat(err.isSuccess()).isEqualTo(recordedErr.get("success").asBoolean())
                .as("disabled_err 的 data 缺席（Go 无 data map）").isFalse();
    }

    @Test
    void oauthWaitTimeoutMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("mcp_oauth", "wait_timeout").get("cases");
        for (JsonNode c : cases) {
            McpOAuthSupport.McpOAuthSession sess = switch (c.get("name").asText()) {
                case "zero" -> new McpOAuthSupport.McpOAuthSession(null, "", "", "", "", null, null, 0);
                case "negative" -> new McpOAuthSupport.McpOAuthSession(null, "", "", "", "", null, null, -3);
                case "s30" -> new McpOAuthSupport.McpOAuthSession(null, "", "", "", "", null, null, 30);
                case "s90" -> new McpOAuthSupport.McpOAuthSession(null, "", "", "", "", null, null, 90);
                default -> null;
            };
            Duration out = McpOAuthSupport.oauthWaitTimeout(sess);
            assertThat(out.toString()).as("case %s", c.get("name").asText())
                    .isEqualTo(normalizeGoDuration(c.get("out").asText()));
        }
    }

    /** Go Duration.String() 与 Java Duration.toString 的对齐（同为"绝对时长"的两个记法）。 */
    private static String normalizeGoDuration(String go) {
        return switch (go) {
            case "0s" -> "PT0S";
            case "30s" -> "PT30S";
            case "1m30s" -> "PT1M30S";
            default -> go;
        };
    }

    @Test
    void isAuthorizationRequiredMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("mcp_oauth", "is_auth_required").get("cases");
        for (JsonNode c : cases) {
            String in = c.get("in").asText();
            Throwable err = in.isEmpty() ? null : new RuntimeException(in);
            if (in.equals("reauth")) {
                err = new com.ragagent.mcp.oauth.OAuthReauthorizationRequiredException("no token is stored");
            }
            assertThat(McpOAuthSupport.isAuthorizationRequired(err))
                    .as("in=%s", in).isEqualTo(c.get("required").asBoolean());
        }
    }

    @Test
    void oauthAwareConnectErrorMatchesGo() {
        JsonNode r = Tools45cFakes.rec45c("mcp_oauth", "connect_errors");
        McpService oauthSvc = svc("svc-o", "Order Upstream");
        com.ragagent.mcp.domain.McpAuthConfig auth = new com.ragagent.mcp.domain.McpAuthConfig();
        auth.setAuthType(com.ragagent.mcp.domain.McpAuthType.OAUTH);
        oauthSvc.setAuthConfig(auth);
        McpService plainSvc = svc("svc-p", "Plain Upstream");
        assertThat(McpOAuthSupport.oauthAwareConnectError(oauthSvc, new RuntimeException("request failed: authorization required")))
                .isEqualTo(r.get("oauth_required").asText());
        assertThat(McpOAuthSupport.oauthAwareConnectError(oauthSvc, new RuntimeException("dial tcp: refused")))
                .isEqualTo(r.get("oauth_plain").asText());
        assertThat(McpOAuthSupport.oauthAwareConnectError(plainSvc, new RuntimeException("dial tcp: refused")))
                .isEqualTo(r.get("plain").asText());
    }

    @Test
    void oauthSessionFromToolExecMatchesGo() {
        JsonNode nil = Tools45cFakes.rec45c("mcp_oauth", "session_from_meta_nil");
        assertThat(McpOAuthSupport.oauthSessionFromToolExec(null) != null)
                .isEqualTo(nil.get("out").asBoolean());
        JsonNode noBus = Tools45cFakes.rec45c("mcp_oauth", "session_no_bus");
        assertThat(McpOAuthSupport.oauthSessionFromToolExec(
                new ToolExecContext("s1", "", "", "tc1", "u1", null, null, 0)) != null)
                .isEqualTo(noBus.get("out").asBoolean());
        JsonNode nilWait = Tools45cFakes.rec45c("mcp_oauth", "with_auth_wait_nil");
        assertThat(McpOAuthSupport.oauthSessionFromToolExec(null) != null)
                .isEqualTo(nilWait.get("out").asBoolean());
    }

    // ===================== catalog 纯函数 =====================

    @Test
    void schemaConstantsMatchGo() {
        assertThat(McpCatalog.MCP_DISCOVERY_SCHEMA.replaceAll("\\s+", ""))
                .isEqualTo(Tools45cFakes.rec45c("mcp_catalog", "schema_discovery").get("out").asText().replaceAll("\\s+", ""));
        assertThat(McpCatalog.MCP_CALL_SCHEMA.replaceAll("\\s+", ""))
                .isEqualTo(Tools45cFakes.rec45c("mcp_catalog", "schema_call").get("out").asText().replaceAll("\\s+", ""));
        assertThat(McpCatalog.MCP_DISCOVERY_DESCRIPTION)
                .isEqualTo(Tools45cFakes.rec45c("mcp_catalog", "description_const").get("out").asText());
        assertThat(McpCatalog.MCP_EXTERNAL_DATA_NOTICE)
                .isEqualTo(Tools45cFakes.rec45c("mcp_catalog", "notice_const").get("out").asText());
        assertThat(ToolRegistry.MCP_CALL_ARGUMENTS_HINT)
                .isEqualTo(Tools45cFakes.rec45c("mcp_catalog", "hint_const").get("out").asText());
    }

    @Test
    void registeredNameAndRefMatchGo() {
        JsonNode name = Tools45cFakes.rec45c("mcp_catalog", "registered_name");
        McpService service = svc("server-1", "订单");
        McpToolWrapper tool = wrapper(service, "tool_001", "",
                "{\n  \"type\": \"object\",\n  \"properties\": {\n    \"count\": {\n      \"type\": \"integer\"\n    }\n  },\n  \"required\": [\n    \"count\"\n  ]\n}");
        assertThat(McpCatalog.mcpRegisteredName(tool)).isEqualTo(name.get("out").asText());

        JsonNode ref = Tools45cFakes.rec45c("mcp_catalog", "tool_ref");
        assertThat(McpCatalog.mcpToolRef(tool)).isEqualTo(ref.get("out").asText());

        JsonNode refB = Tools45cFakes.rec45c("mcp_catalog", "tool_ref_other_schema");
        McpToolWrapper toolB = wrapper(svc("server-1", "订单"), "tool_001", "", "{\"type\":\"object\"}");
        assertThat(McpCatalog.mcpToolRef(toolB)).isEqualTo(refB.get("out").asText());

        JsonNode uni = Tools45cFakes.rec45c("mcp_catalog", "unicode_name");
        McpToolWrapper toolC = wrapper(svc("server-1", "订单"), "订单中心", "", null);
        assertThat(McpCatalog.mcpRegisteredName(toolC)).isEqualTo(uni.get("registered").asText());
        assertThat(toolC.getName()).isEqualTo(uni.get("plain").asText());
    }

    @Test
    void shortDescriptionMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("mcp_catalog", "short_description").get("cases");
        String[] inputs = {"", "short", "a".repeat(200), "a".repeat(201), "文".repeat(150)};
        int i = 0;
        for (JsonNode c : cases) {
            assertThat(McpCatalog.shortMcpDescription(inputs[i])).as("case %d", i)
                    .isEqualTo(c.get("out").asText());
            i++;
        }
    }

    @Test
    void decodeMcpCallMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("mcp_catalog", "decode_call").get("cases");
        for (JsonNode c : cases) {
            String in = c.get("in").asText();
            String err = "";
            String ref = "";
            try {
                JsonNode tree;
                try {
                    tree = RecordingSupport.readTree(in);
                } catch (Exception e) {
                    // 已知差异：Go 的 json.Unmarshal 错误文案（"invalid character 'o' in literal
                    // null..."）是 Go encoding/json 专有；Java 侧 args 在上游已解析为 JsonNode，
                    // 不可达该文案——只断言两侧都拒绝。
                    assertThat(c.get("err").asText()).isNotEmpty();
                    continue;
                }
                McpCatalog.DecodeResult d = McpCatalog.decodeMcpCall(tree);
                ref = d.toolRef();
            } catch (IllegalArgumentException e) {
                err = e.getMessage();
            }
            assertThat(ref).as("ref %s", in).isEqualTo(c.get("ref").asText());
            assertThat(err).as("err %s", in).isEqualTo(c.get("err").asText());
        }
    }
}
