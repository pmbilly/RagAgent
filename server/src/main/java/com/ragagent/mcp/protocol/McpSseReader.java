package com.ragagent.mcp.protocol;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * MCP 的 SSE 消息流解析器（对照 mcp-go {@code readSSE}，
 * client/transport/streamable_http.go:757-822 与 sse.go:280-330——两处实现逐行相同）。
 *
 * <p><b>与 {@code com.ragagent.llm.chat.SseReader} 的关键差别</b>（别把两者当同一个东西用）：</p>
 * <ul>
 *   <li>LLM 聊天流只关心 {@code data:} 的文本载荷，且一条 data 就是一个 chunk；
 *       MCP 的 SSE 是 <b>JSON-RPC 消息流</b>，必须同时识别 {@code event:}
 *       （{@code endpoint} 帧给出 POST 地址、{@code message} 帧才是 JSON-RPC 报文）；</li>
 *   <li>mcp-go 对同一条消息里的多个 {@code data:} 行是<b>覆盖</b>而非拼接
 *       （{@code data = strings.TrimSpace(dataStr)}，不是 {@code +=}）——本类照抄该行为；</li>
 *   <li>没有 {@code data: [DONE]} 这种终止帧；流的结束就是 EOF 或连接关闭。</li>
 * </ul>
 *
 * <p>派发纪律（逐条对照）：</p>
 * <ol>
 *   <li>行尾只裁 {@code \r\n}（{@code TrimRight(line, "\r\n")}），不做左裁；</li>
 *   <li>空行 = 事件结束：只有 {@code data} 非空才派发，且派发后 {@code event}/{@code data} 双清零；</li>
 *   <li>EOF 时若还有未派发的 {@code data}，同样派发一次（Go 的"Process any pending event before exit"）；</li>
 *   <li>{@code event:} 缺席时默认事件名 {@code "message"}；</li>
 *   <li>同一块里的 {@code event:} 取<b>最后一个</b>（Go 是覆盖赋值）。</li>
 * </ol>
 *
 * <p>非线程安全：一个流一个实例，由单个读取线程持有。</p>
 */
public final class McpSseReader implements Closeable {

    /** 单行上限（防恶意服务端用超长行撑爆内存；Go 侧 bufio 默认 64KB，这里给足余量）。 */
    public static final int MAX_LINE_BYTES = 1024 * 1024;

    private final InputStream in;

    private String currentEvent = "";
    private String currentData = "";
    private boolean eof;

    public McpSseReader(InputStream in) {
        this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in);
    }

    /** 一个 SSE 帧（对照 mcp-go 的 {@code handler(event, data)} 两个参数）。 */
    public record SseMessage(String event, String data) {
    }

    /**
     * 读下一个事件；流结束返回 null（对照 Go 在 EOF 处 return）。
     *
     * @throws IOException 读失败（含连接被关闭——调用方据此判定 connection lost）
     */
    public SseMessage next() throws IOException {
        if (eof) {
            return null;
        }
        while (true) {
            String line = readLine();
            if (line == null) {
                eof = true;
                // EOF 前把未派发的事件补发出去（对照 Go 的 "Process any pending event before exit"）
                if (!currentData.isEmpty()) {
                    return dispatch();
                }
                return null;
            }
            if (line.isEmpty()) {
                if (!currentData.isEmpty()) {
                    return dispatch();
                }
                continue;
            }
            if (line.startsWith("event:")) {
                currentEvent = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                // 覆盖而非拼接——逐字对照 mcp-go。
                currentData = line.substring(5).trim();
            }
            // 其它字段（id:/retry:/注释行）一律忽略，对照 Go
        }
    }

    private SseMessage dispatch() {
        String event = currentEvent.isEmpty() ? McpProtocol.SSE_EVENT_MESSAGE : currentEvent;
        SseMessage message = new SseMessage(event, currentData);
        currentEvent = "";
        currentData = "";
        return message;
    }

    /** 读一行（不含换行，裁掉尾部 {@code \r}）；EOF 且无内容时返回 null。 */
    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return decode(line);
            }
            line.write(b);
            if (line.size() > MAX_LINE_BYTES) {
                throw new IOException("MCP SSE line exceeds " + MAX_LINE_BYTES + " bytes");
            }
        }
        if (line.size() == 0) {
            return null;
        }
        return decode(line);
    }

    /** 对照 Go {@code strings.TrimRight(line, "\r\n")}：只裁尾部 CR/LF。 */
    private static String decode(ByteArrayOutputStream line) {
        byte[] bytes = line.toByteArray();
        int end = bytes.length;
        while (end > 0 && (bytes[end - 1] == '\r' || bytes[end - 1] == '\n')) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
