package com.ragagent.mcp.protocol;

import java.util.function.Consumer;

/**
 * MCP 传输层（对照 mcp-go {@code client/transport.Interface} 中被本项目用到的子集）。
 *
 * <p>分层：客户端只负责握手/分页/类型转换，
 * 报文收发全在传输实现里——{@code DefaultMcpClient#listRawTools} 依赖这条通道绕开 SDK 的
 * 强类型 Schema 转换。</p>
 *
 * <p>两种实现的差别：</p>
 * <ul>
 *   <li>{@link StreamableHttpTransport}：单端点 POST，响应可能是 JSON 也可能是 SSE 流；</li>
 *   <li>{@link SseTransport}：GET 建一条长连 SSE 流收消息，POST 到 endpoint 帧给出的地址发请求。</li>
 * </ul>
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 建立传输。
     *
     * <p>Streamable HTTP 是<b>无状态</b>的，start 基本是空操作（无需持久连接）；
     * HTTP+SSE 则必须在这里把流拉起来并等到 {@code endpoint} 帧，
     * 否则后续请求无地址可发（默认等 30s）。</p>
     */
    void start(McpContext ctx);

    /**
     * 发一条 JSON-RPC 请求并等响应（对照 mcp-go {@code SendRequest}）。
     *
     * @return 响应；通知类报文可能返回 null（服务端 202 Accepted）
     * @throws McpException 传输失败 / 超时 / 服务端返回 error 对象
     */
    JsonRpcResponse send(JsonRpcRequest request, McpContext ctx);

    /**
     * 发一条 JSON-RPC <b>通知</b>（无 id，不等响应）。
     *
     * <p>MCP 要求 initialize 成功后必须补一条 {@code notifications/initialized}，
     * 否则服务端不认为握手完成——该通知发送失败会让整个 initialize 失败。</p>
     */
    void sendNotification(String method, Object params, McpContext ctx);

    /** 注册连接断开回调。 */
    void setConnectionLostHandler(Consumer<Throwable> handler);

    /** 当前会话 ID（服务端经 {@code Mcp-Session-Id} 下发；未协商时为 null）。 */
    default String sessionId() {
        return null;
    }

    @Override
    void close();
}
