package com.ragagent.datasource.connector.feishu.wiki;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.datasource.connector.feishu.core.DocxBlocks;
import com.ragagent.datasource.connector.feishu.core.DocxFetcher;
import com.ragagent.datasource.connector.feishu.core.FeishuTestServer;

/**
 * wiki 连接器测试的飞书 API 桩夹具（层级节点、块 JSON、导出任务等路由）。
 *
 * <p>用<b>手写 JSON</b> 而不是序列化 Java 对象：桩要"诚实地"还原飞书的线上形状
 * （含 {@code has_more} / {@code page_token} / 嵌套 data），序列化本地 DTO 会让
 * "Java 侧解析器与 Java 侧 DTO 同时改错"这种问题测不出来。</p>
 */
final class WikiFixtures {

    static final String TOKEN_PATH = "/open-apis/auth/v3/tenant_access_token/internal";
    static final String SPACES_PATH = "/open-apis/wiki/v2/spaces";
    static final String SPACE1_NODES_PATH = "/open-apis/wiki/v2/spaces/space1/nodes";
    static final String GET_NODE_PATH = "/open-apis/wiki/v2/spaces/get_node";
    static final String EXPORT_CREATE_PATH = "/open-apis/drive/v1/export_tasks";

    private WikiFixtures() {
    }

    /** 一个 wiki 节点的桩规格（空串 = 响应里不带该字段）。 */
    record Node(String nodeToken, String objToken, String objType, String title,
                String objEditTime, String nodeEditTime, String objCreateTime,
                String nodeCreateTime, boolean hasChild) {

        static Node of(String nodeToken, String objToken, String objType, String title,
                       String editTime) {
            return new Node(nodeToken, objToken, objType, title, editTime, "", "", "", false);
        }

        static Node withNodeTime(String nodeToken, String objToken, String objType, String title,
                                 String nodeEditTime) {
            return new Node(nodeToken, objToken, objType, title, "", nodeEditTime, "", "", false);
        }

        Node withCreate(String objCreateTime, String nodeCreateTime) {
            return new Node(nodeToken, objToken, objType, title, objEditTime, nodeEditTime,
                    objCreateTime, nodeCreateTime, hasChild);
        }

        Node child() {
            return new Node(nodeToken, objToken, objType, title, objEditTime, nodeEditTime,
                    objCreateTime, nodeCreateTime, true);
        }
    }

    static String nodeJson(Node n, String parentToken) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"space_id\":\"space1\",");
        sb.append("\"node_token\":").append(FeishuTestServer.jsonString(n.nodeToken())).append(',');
        sb.append("\"obj_token\":").append(FeishuTestServer.jsonString(n.objToken())).append(',');
        sb.append("\"obj_type\":").append(FeishuTestServer.jsonString(n.objType())).append(',');
        sb.append("\"title\":").append(FeishuTestServer.jsonString(n.title())).append(',');
        if (parentToken != null && !parentToken.isEmpty()) {
            sb.append("\"parent_node_token\":")
                    .append(FeishuTestServer.jsonString(parentToken)).append(',');
        }
        if (n.objEditTime() != null && !n.objEditTime().isEmpty()) {
            sb.append("\"obj_edit_time\":")
                    .append(FeishuTestServer.jsonString(n.objEditTime())).append(',');
        }
        if (n.nodeEditTime() != null && !n.nodeEditTime().isEmpty()) {
            sb.append("\"node_edit_time\":")
                    .append(FeishuTestServer.jsonString(n.nodeEditTime())).append(',');
        }
        if (n.objCreateTime() != null && !n.objCreateTime().isEmpty()) {
            sb.append("\"obj_create_time\":")
                    .append(FeishuTestServer.jsonString(n.objCreateTime())).append(',');
        }
        if (n.nodeCreateTime() != null && !n.nodeCreateTime().isEmpty()) {
            sb.append("\"node_create_time\":")
                    .append(FeishuTestServer.jsonString(n.nodeCreateTime())).append(',');
        }
        sb.append("\"has_child\":").append(n.hasChild());
        return sb.append('}').toString();
    }

    static String nodeListResponse(List<String> nodeJsons) {
        return "{\"code\":0,\"msg\":\"\",\"data\":{\"items\":["
                + String.join(",", nodeJsons) + "],\"has_more\":false,\"page_token\":\"\"}}";
    }

    static String nodeListResponse(Node... nodes) {
        java.util.List<String> jsons = new java.util.ArrayList<>();
        for (Node n : nodes) {
            jsons.add(nodeJson(n, null));
        }
        return nodeListResponse(jsons);
    }

    // ── 路由注册 ────────────────────────────────────────────────────────

    static void tokenRoute(FeishuTestServer server) {
        server.handle(TOKEN_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"tenant_access_token\":\"fake-token\",\"expire\":7200}"));
    }

    /** 一个空间："space1" / "Test Space"。 */
    static void spacesRoute(FeishuTestServer server) {
        server.handle(SPACES_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"msg\":\"\",\"data\":{\"items\":[{"
                        + "\"space_id\":\"space1\",\"name\":\"Test Space\","
                        + "\"description\":\"desc\",\"visibility\":\"public\"}],"
                        + "\"has_more\":false,\"page_token\":\"\"}}"));
    }

    /**
     * 层级节点路由：顶层节点 + 按 parent_node_token 分组的子节点，
     * 并可指定某个父节点的列举<b>失败</b>（对照 {@code fakeFeishuHierarchy}）。
     */
    static void hierarchyRoute(FeishuTestServer server, List<Node> topNodes,
                               Map<String, List<Node>> childNodes, String failingParentToken) {
        Map<String, String> nodeByToken = new LinkedHashMap<>();
        for (Node n : topNodes) {
            nodeByToken.put(n.nodeToken(), nodeJson(n, null));
        }
        for (Map.Entry<String, List<Node>> e : childNodes.entrySet()) {
            for (Node n : e.getValue()) {
                nodeByToken.put(n.nodeToken(), nodeJson(n, e.getKey()));
            }
        }

        server.handle(SPACE1_NODES_PATH, (ex, body) -> {
            String parent = queryParam(ex, "parent_node_token");
            if (!parent.isEmpty() && parent.equals(failingParentToken)) {
                FeishuTestServer.sendStatus(ex, 500, "{\"code\":1663,\"msg\":\"internal error\"}");
                return;
            }
            List<Node> nodes = topNodes;
            java.util.List<String> jsons = new java.util.ArrayList<>();
            if (!parent.isEmpty()) {
                nodes = childNodes.getOrDefault(parent, List.of());
                for (Node n : nodes) {
                    jsons.add(nodeJson(n, parent));
                }
            } else {
                for (Node n : nodes) {
                    jsons.add(nodeJson(n, null));
                }
            }
            FeishuTestServer.sendJson(ex, nodeListResponse(jsons));
        });

        server.handle(GET_NODE_PATH, (ex, body) -> {
            String token = queryParam(ex, "token");
            String json = nodeByToken.get(token);
            if (json == null) {
                FeishuTestServer.sendJson(ex, "{\"code\":1663,\"msg\":\"node not found\"}");
                return;
            }
            FeishuTestServer.sendJson(ex,
                    "{\"code\":0,\"msg\":\"\",\"data\":{\"node\":" + json + "}}");
        });
    }

    static void hierarchyRoute(FeishuTestServer server, List<Node> topNodes,
                               Map<String, List<Node>> childNodes) {
        hierarchyRoute(server, topNodes, childNodes, "");
    }

    /** 导出三件套：建任务（ticket-123）→ 轮询成功（ft-abc）→ 下载。 */
    static void exportTrio(FeishuTestServer server, String downloadContent) {
        server.handle(EXPORT_CREATE_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"msg\":\"\",\"data\":{\"ticket\":\"ticket-123\"}}"));
        server.handle("/open-apis/drive/v1/export_tasks/ticket-123", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"result\":{"
                        + "\"file_token\":\"ft-abc\",\"file_size\":100,\"job_status\":0,"
                        + "\"job_error_msg\":\"\",\"file_name\":\"exported.docx\"}}}"));
        server.handle("/open-apis/drive/v1/export_tasks/file/ft-abc/download", (ex, body) ->
                FeishuTestServer.sendBytes(ex, "application/octet-stream",
                        downloadContent.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code /open-apis/drive/v1/files/<token>/download} → 固定内容。 */
    static void driveFileDownloadRoute(FeishuTestServer server, String content) {
        server.handle("/open-apis/drive/v1/files/", (ex, body) -> {
            if (ex.getRequestURI().getPath().endsWith("/download")) {
                FeishuTestServer.sendBytes(ex, "application/octet-stream",
                        content.getBytes(StandardCharsets.UTF_8));
                return;
            }
            org.junit.jupiter.api.Assertions.fail("unexpected drive files path: "
                    + ex.getRequestURI().getPath());
        });
    }

    /** {@code /open-apis/drive/v1/medias/<token>/download} → 按 token 分发字节。 */
    static void mediaRoute(FeishuTestServer server, Map<String, byte[]> mediaByToken) {
        server.handle("/open-apis/drive/v1/medias/", (ex, body) -> {
            String path = ex.getRequestURI().getPath();
            if (!path.endsWith("/download")) {
                FeishuTestServer.sendStatus(ex, 404, "");
                return;
            }
            String token = path.substring("/open-apis/drive/v1/medias/".length(),
                    path.length() - "/download".length());
            byte[] data = mediaByToken.get(token);
            if (data == null) {
                FeishuTestServer.sendStatus(ex, 404, "");
                return;
            }
            FeishuTestServer.sendBytes(ex, "application/octet-stream", data);
        });
    }

    /** docx blocks 路由：一次给全（不分页）。 */
    static void blocksRoute(FeishuTestServer server, String docToken, String blocksJson) {
        server.handle("/open-apis/docx/v1/documents/" + docToken + "/blocks", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{"
                        + "\"items\":" + blocksJson + ",\"has_more\":false,\"page_token\":\"\"}}"));
    }

    /** docx blocks 路由：固定返回 HTTP 500（触发导出回落）。 */
    static void blocksFailureRoute(FeishuTestServer server, String docToken) {
        server.handle("/open-apis/docx/v1/documents/" + docToken + "/blocks", (ex, body) ->
                FeishuTestServer.sendStatus(ex, 500,
                        "{\"code\":99991400,\"msg\":\"insufficient scope\"}"));
    }

    // ── block JSON 构造 ─────────────────────────────────────────────────

    /**
     * 文本块的 JSON。
     *
     * <p><b>关键</b>：docx 把块的文本存在<b>与类型同名</b>的字段里
     * （bullet 块 → {@code "bullet"}，code 块 → {@code "code"}），
     * 所以这里按 block_type 选字段名。写死 {@code "text"} 会让
     * bullet/quote/todo 的文本被解析器忽略（{@code textFieldName} 取不到）。</p>
     */
    static String textBlockJson(String blockId, int blockType, String content) {
        String field = textFieldName(blockType);
        return "{\"block_id\":" + FeishuTestServer.jsonString(blockId)
                + ",\"block_type\":" + blockType
                + ",\"" + field + "\":{\"elements\":[{\"text_run\":{\"content\":"
                + FeishuTestServer.jsonString(content) + "}}]}}";
    }

    /** 块的文本承载字段名（与类型同名）。 */
    static String textFieldName(int blockType) {
        return switch (blockType) {
            case 12 -> "bullet";
            case 13 -> "ordered";
            case 14 -> "code";
            case 15 -> "quote";
            case 17 -> "todo";
            case 19 -> "callout";
            default -> "text";
        };
    }

    static String headingBlockJson(String blockId, int level, String content) {
        int blockType = DocxBlocks.BLOCK_TYPE_HEADING1 + level - 1;
        String field = "\"heading" + level + "\":{\"elements\":[{\"text_run\":{\"content\":"
                + FeishuTestServer.jsonString(content) + "}}]}";
        return "{\"block_id\":" + FeishuTestServer.jsonString(blockId)
                + ",\"block_type\":" + blockType + "," + field + "}";
    }

    static String pageBlockJson(String blockId) {
        return "{\"block_id\":" + FeishuTestServer.jsonString(blockId) + ",\"block_type\":1}";
    }

    static String dividerBlockJson(String blockId) {
        return "{\"block_id\":" + FeishuTestServer.jsonString(blockId) + ",\"block_type\":22}";
    }

    static String fileBlockJson(String blockId, String token, String name) {
        return "{\"block_id\":" + FeishuTestServer.jsonString(blockId)
                + ",\"block_type\":23,\"file\":{\"token\":" + FeishuTestServer.jsonString(token)
                + ",\"name\":" + FeishuTestServer.jsonString(name) + "}}";
    }

    static String imageBlockJson(String blockId, String token) {
        return "{\"block_id\":" + FeishuTestServer.jsonString(blockId)
                + ",\"block_type\":27,\"image\":{\"token\":" + FeishuTestServer.jsonString(token)
                + "}}";
    }

    static String sheetBlockJson(String blockId, String token) {
        return "{\"block_id\":" + FeishuTestServer.jsonString(blockId)
                + ",\"block_type\":30,\"sheet\":{\"token\":" + FeishuTestServer.jsonString(token)
                + "}}";
    }

    static String bitableBlockJson(String blockId, String token) {
        return "{\"block_id\":" + FeishuTestServer.jsonString(blockId)
                + ",\"block_type\":18,\"bitable\":{\"token\":" + FeishuTestServer.jsonString(token)
                + "}}";
    }

    static String cellBlockJson(String cellId, String childId) {
        return "{\"block_id\":" + FeishuTestServer.jsonString(cellId)
                + ",\"block_type\":32,\"children\":[" + FeishuTestServer.jsonString(childId) + "]}";
    }

    static String tableBlockJson(String blockId, int cols, String... cellIds) {
        StringBuilder cells = new StringBuilder("[");
        for (int i = 0; i < cellIds.length; i++) {
            if (i > 0) {
                cells.append(',');
            }
            cells.append(FeishuTestServer.jsonString(cellIds[i]));
        }
        cells.append(']');
        return "{\"block_id\":" + FeishuTestServer.jsonString(blockId)
                + ",\"block_type\":31,\"table\":{\"cells\":" + cells
                + ",\"property\":{\"column_size\":" + cols + "}}}";
    }

    /** 把 blocks 拼成 JSON 数组。 */
    static String blocks(String... blockJsons) {
        return "[" + String.join(",", blockJsons) + "]";
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    static String queryParam(com.sun.net.httpserver.HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isEmpty()) {
            return "";
        }
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            if (k.equals(name)) {
                return i < 0 ? "" : java.net.URLDecoder.decode(kv.substring(i + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /**
     * 切换 docx 解析模式（设 {@code FEISHU_DOCX_PARSE_MODE} 的等效开关）。
     * 调用方负责在 {@code @AfterEach} 里 {@link #resetParseMode()}。
     */
    static void useBlocksParseMode() {
        DocxFetcher.parseMode = () -> "blocks";
    }

    static void resetParseMode() {
        DocxFetcher.parseMode = () -> System.getenv("FEISHU_DOCX_PARSE_MODE");
    }

    static byte[] repeat(String s, int times) {
        return s.repeat(times).getBytes(StandardCharsets.UTF_8);
    }
}
