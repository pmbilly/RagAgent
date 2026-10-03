package com.ragagent.llm.chat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * SSE 流读取器。
 *
 * <p>解析规则：</p>
 * <ol>
 *   <li>空行跳过；</li>
 *   <li>整行 {@code == "data: [DONE]"} → 结束事件（<b>全行精确匹配</b>，
 *       所以 {@code "data: [DONE] "}（尾空格）不会被当成结束标记）；</li>
 *   <li>前缀 {@code "data: "} → 取其后全部内容为数据（含前导空格的兼容写法）；</li>
 *   <li>前缀 {@code "data:"}（冒号后没空格）→ 同样取其后全部内容（增强兼容性）；</li>
 *   <li>{@code event:} / {@code id:} / 其它行一律跳过（本读取器不消费这些字段）；</li>
 *   <li>行长上限 1MB（思维链内容可能很长）；
 *       超限报 "bufio.Scanner: token too long"；</li>
 *   <li>流结束（EOF）→ 返回空 {@link Optional}。</li>
 * </ol>
 *
 * <p>行尾的 {@code \r\n} 会剥掉 {@code \r}。</p>
 *
 * <p>非线程安全：一个流一个实例。</p>
 */
public final class SseReader {

    /** 1MB 行缓冲上限。 */
    public static final int MAX_LINE_BYTES = 1024 * 1024;

    private final InputStream in;

    public SseReader(InputStream reader) {
        this.in = reader instanceof BufferedInputStream ? reader : new BufferedInputStream(reader);
    }

    /** 一个 SSE 事件。 */
    public record SseEvent(byte[] data, boolean done) {

        /** 结束事件（data 为 null）。 */
        public static SseEvent doneEvent() {
            return new SseEvent(null, true);
        }

        /** data 行的 UTF-8 文本（SSE 的 data 都是 UTF-8 JSON）。 */
        public String dataText() {
            if (data == null) {
                return "";
            }
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    /**
     * 读下一个事件；
     * 流已结束返回 {@link Optional#empty()}。
     */
    public Optional<SseEvent> readEvent() throws IOException {
        while (true) {
            byte[] lineBytes = readLine();
            if (lineBytes == null) {
                // EOF：无更多事件
                return Optional.empty();
            }
            String line = new String(lineBytes, StandardCharsets.UTF_8);

            if (line.isEmpty()) {
                continue; // 空行，跳过
            }
            if ("data: [DONE]".equals(line)) {
                return Optional.of(SseEvent.doneEvent());
            }
            if (line.startsWith("data: ")) {
                return Optional.of(new SseEvent(line.substring(6).getBytes(StandardCharsets.UTF_8), false));
            }
            if (line.startsWith("data:")) {
                return Optional.of(new SseEvent(line.substring(5).getBytes(StandardCharsets.UTF_8), false));
            }
            // 其它行（event:、id: 等）跳过
        }
    }

    /**
     * 读一行（不含换行符，剥掉尾部 {@code \r}）；EOF 且无内容时返回 null。
     * 超过 {@link #MAX_LINE_BYTES} 时抛 IOException。
     */
    private byte[] readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return stripCr(line);
            }
            line.write(b);
            if (line.size() > MAX_LINE_BYTES) {
                throw new IOException("bufio.Scanner: token too long");
            }
        }
        if (line.size() == 0) {
            return null; // EOF
        }
        return stripCr(line);
    }

    /** 行尾的 \r 不计入内容。 */
    private static byte[] stripCr(ByteArrayOutputStream line) {
        byte[] bytes = line.toByteArray();
        if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
            return Arrays.copyOf(bytes, bytes.length - 1);
        }
        return bytes;
    }
}
