package com.ragagent.im.feishu;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * lark WS 的 {@code pbbp2} 帧（protobuf 线格式，schema 如下）。
 *
 * <pre>
 * message Header { bytes key = 1; bytes value = 2; }
 * message Frame {
 *   uint64 SeqID = 1;  uint64 LogID = 2;  int32 service = 3;  int32 method = 4;
 *   repeated Header headers = 5;  string payload_encoding = 6;  string payload_type = 7;
 *   bytes payload = 8;  string LogIDNew = 9;
 * }
 * </pre>
 *
 * <p><b>线格式要点</b>（行为由 {@code LarkFrameTest} 钉住）：字段升序书写；
 * {@code payload_encoding}/{@code payload_type}/
 * {@code LogIDNew} <b>空串也照写</b>（0 长度字段，无条件写）；
 * {@code payload} 仅在非 null 时写；{@code headers} 仅在非空时写。</p>
 */
public final class LarkFrame {

    /** 帧头键值对。 */
    public static final class Header {
        public final String key;
        public final String value;

        public Header(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String toString() {
            return "{" + key + " " + value + "}";
        }
    }

    // 帧类型
    public static final int METHOD_CONTROL = 0;
    public static final int METHOD_DATA = 1;

    public long seqId;
    public long logId;
    public int service;
    public int method;
    public final List<Header> headers = new ArrayList<>();
    public String payloadEncoding = "";
    public String payloadType = "";
    public byte[] payload;
    public String logIdNew = "";

    /** 首个匹配即返回，无则空串。 */
    public String header(String key) {
        for (Header h : headers) {
            if (h.key.equals(key)) {
                return h.value;
            }
        }
        return "";
    }

    /** 解析失败算 0。 */
    public int intHeader(String key) {
        String value = header(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 追加（允许重复键）。 */
    public void addHeader(String key, String value) {
        headers.add(new Header(key, value));
    }

    /** 控制帧 + 仅 {@code type=ping} 头。 */
    public static LarkFrame ping(int serviceId) {
        LarkFrame frame = new LarkFrame();
        frame.method = METHOD_CONTROL;
        frame.service = serviceId;
        frame.addHeader("type", "ping");
        return frame;
    }

    /** 回执上行（同帧回写 + payload 为响应 JSON）。 */
    public static LarkFrame response(int code) {
        LarkFrame frame = new LarkFrame();
        frame.payload = ("{\"code\":" + code + "}").getBytes(StandardCharsets.UTF_8);
        return frame;
    }

    // ── 编码 ────────────────────────────────────────────────────────────────

    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarintField(out, 1, seqId);
        writeVarintField(out, 2, logId);
        writeVarintField(out, 3, service);
        writeVarintField(out, 4, method);
        if (!headers.isEmpty()) {
            for (Header header : headers) {
                ByteArrayOutputStream inner = new ByteArrayOutputStream();
                writeBytesField(inner, 1, header.key.getBytes(StandardCharsets.UTF_8));
                writeBytesField(inner, 2, header.value.getBytes(StandardCharsets.UTF_8));
                writeBytesField(out, 5, inner.toByteArray());
            }
        }
        writeBytesField(out, 6, payloadEncoding.getBytes(StandardCharsets.UTF_8));
        writeBytesField(out, 7, payloadType.getBytes(StandardCharsets.UTF_8));
        if (payload != null) {
            writeBytesField(out, 8, payload);
        }
        writeBytesField(out, 9, logIdNew.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        out.write((field << 3) | 0);
        writeVarint(out, value);
    }

    private static void writeBytesField(ByteArrayOutputStream out, int field, byte[] value) {
        out.write((field << 3) | 2);
        writeVarint(out, value.length);
        out.writeBytes(value);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while (true) {
            if ((v & ~0x7FL) == 0) {
                out.write((int) v);
                return;
            }
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
    }

    // ── 解码 ────────────────────────────────────────────────────────────────

    public static LarkFrame decode(byte[] raw) {
        LarkFrame frame = new LarkFrame();
        int i = 0;
        int length = raw == null ? 0 : raw.length;
        while (i < length) {
            long[] tag = readVarint(raw, i);
            int key = (int) tag[0];
            i = (int) tag[1];
            int field = key >>> 3;
            int wireType = key & 0x7;
            switch (wireType) {
                case 0 -> {
                    long[] value = readVarint(raw, i);
                    i = (int) value[1];
                    switch (field) {
                        case 1 -> frame.seqId = value[0];
                        case 2 -> frame.logId = value[0];
                        case 3 -> frame.service = (int) value[0];
                        case 4 -> frame.method = (int) value[0];
                        default -> {
                            // 未知 varint 字段：跳过
                        }
                    }
                }
                case 2 -> {
                    long[] len = readVarint(raw, i);
                    int size = (int) len[0];
                    i = (int) len[1];
                    byte[] data = new byte[Math.max(size, 0)];
                    if (size > 0) {
                        System.arraycopy(raw, i, data, 0, size);
                        i += size;
                    }
                    switch (field) {
                        case 5 -> frame.headers.addAll(decodeHeader(data));
                        case 6 -> frame.payloadEncoding = new String(data, StandardCharsets.UTF_8);
                        case 7 -> frame.payloadType = new String(data, StandardCharsets.UTF_8);
                        case 8 -> frame.payload = data;
                        case 9 -> frame.logIdNew = new String(data, StandardCharsets.UTF_8);
                        default -> {
                            // 未知长字段：跳过
                        }
                    }
                }
                case 5 -> i += 4;   // fixed32：跳过
                case 1 -> i += 8;   // fixed64：跳过
                default -> {
                    return frame;   // 3/4 组边界等：停止解析（防御）
                }
            }
        }
        return frame;
    }

    private static List<Header> decodeHeader(byte[] data) {
        List<Header> result = new ArrayList<>(2);
        String key = "";
        String value = "";
        int i = 0;
        while (i < data.length) {
            long[] tag = readVarint(data, i);
            int field = (int) (tag[0] >>> 3);
            int wireType = (int) (tag[0] & 0x7);
            i = (int) tag[1];
            if (wireType != 2) {
                if (wireType == 0) {
                    i = (int) readVarint(data, i)[1];
                } else if (wireType == 5) {
                    i += 4;
                } else if (wireType == 1) {
                    i += 8;
                } else {
                    break;
                }
                continue;
            }
            long[] len = readVarint(data, i);
            int size = (int) len[0];
            i = (int) len[1];
            String text = size <= 0 ? "" : new String(data, i, size, StandardCharsets.UTF_8);
            i += Math.max(size, 0);
            if (field == 1) {
                key = text;
            } else if (field == 2) {
                value = text;
            }
        }
        result.add(new Header(key, value));
        return result;
    }

    /** 读 varint，返回 {值, 新偏移}。 */
    private static long[] readVarint(byte[] data, int offset) {
        long result = 0;
        int shift = 0;
        int i = offset;
        while (i < data.length) {
            int b = data[i++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 63) {
                break;
            }
        }
        return new long[] {result, i};
    }

    @Override
    public String toString() {
        return "Frame{seq=" + seqId + ", log=" + logId + ", service=" + service
                + ", method=" + method + ", headers=" + headers
                + ", payload=" + (payload == null ? 0 : payload.length) + "B}";
    }
}
