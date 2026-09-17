package com.ragagent.mcp.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 请求（对照 mcp-go {@code client/transport.JSONRPCRequest}）。
 *
 * <p>序列化纪律（必须逐字段一致，服务端按 JSON-RPC 严格解析）：</p>
 * <ul>
 *   <li>{@code jsonrpc} 恒为 {@code "2.0"}；</li>
 *   <li>{@code id} 恒输出（Go 的 RequestId 支持数字与字符串两种形态，
 *       数字 ID 与 SDK 客户端自增 ID 对应，字符串 ID 用于 raw 调用——见
 *       {@code DefaultMcpClient#listRawTools} 的 {@code "weknora-tools-<uuid>"}）；</li>
 *   <li>{@code params} 带 omitempty：null 时整个键省略（Go 的 {@code json:"params,omitempty"}）。</li>
 * </ul>
 */
public final class JsonRpcRequest {

    /** 对照 Go mcp.JSONRPC_VERSION。 */
    public static final String JSONRPC_VERSION = "2.0";

    private final Object id;
    private final String method;
    private final Object params;

    public JsonRpcRequest(Object id, String method, Object params) {
        this.id = id;
        this.method = method;
        this.params = params;
    }

    public Object id() {
        return id;
    }

    public String method() {
        return method;
    }

    public Object params() {
        return params;
    }

    /** 按 Go 的字段序/omitempty 语义构造线上 JSON：jsonrpc, id, method, params。 */
    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("jsonrpc", JSONRPC_VERSION);
        node.set("id", mapper.valueToTree(id));
        node.put("method", method);
        if (params != null) {
            node.set("params", mapper.valueToTree(params));
        }
        return node;
    }

    /** ID 的线上契约形式：数字 ID → 十进制字符串；字符串 ID 原样。用于 pending 表键。 */
    public String idKey() {
        return String.valueOf(id);
    }
}
