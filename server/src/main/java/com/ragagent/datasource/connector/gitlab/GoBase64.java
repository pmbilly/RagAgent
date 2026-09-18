package com.ragagent.datasource.connector.gitlab;

/**
 * Go {@code encoding/base64.StdEncoding.DecodeString} 的逐行复刻。
 *
 * <h2>为什么不直接用 {@code java.util.Base64}</h2>
 * <p>只有一件事需要它：<b>错误消息里的字节偏移</b>。Go 的失败统一是
 * {@code CorruptInputError(offset)}，消息为
 * {@code illegal base64 data at input byte N}；JDK 的
 * {@code Base64.Decoder} 抛的是 {@code IllegalArgumentException}，消息形态完全不同，
 * 而且它<b>不区分</b>"在哪里坏掉的"（{@code ab==cd}、"A==="、"a" 三类都只说"输入格式非法"）。
 * 连接器把这条消息原样包进 {@code gitlab file content: decode base64: %w}，
 * 是运维排查"GitLab 返回的 content 到底怎么了"的唯一线索，所以照抄。</p>
 *
 * <h2>算法出处</h2>
 * <p>逐行对照 Go 1.26 {@code encoding/base64} 的 {@code decodeQuantum}：
 * 每 4 个字符一个 quantum，{@code \n} / {@code \r} 在任意位置被跳过（并占用一个
 * {@code j} 槽位），{@code =} 只能出现在 quantum 的第 3、4 位且必须是输入的尾部。
 * 各种边界的偏移量（{@code si} 是<b>绝对</b>下标，且已被读过一位）已由
 * {@code GoBase64Test} 用 Go 实录钉住。</p>
 *
 * <p><b>内部工具，不是契约</b>：只在 {@code raw()} 的 base64 回落分支上使用，
 * 不落 jsonb、不进响应体。</p>
 */
final class GoBase64 {

    /** 标准字母表（{@code StdEncoding}，{@code +} / {@code /}，padding {@code =}）。 */
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private static final byte[] DECODE_MAP = buildDecodeMap();

    private static final byte PADDING = '=';

    private GoBase64() {
    }

    private static byte[] buildDecodeMap() {
        byte[] map = new byte[256];
        java.util.Arrays.fill(map, (byte) 0xFF);
        for (int i = 0; i < ALPHABET.length(); i++) {
            map[ALPHABET.charAt(i)] = (byte) i;
        }
        return map;
    }

    /**
     * 对照 Go {@code base64.StdEncoding.DecodeString}。
     *
     * @param input 待解码文本（按 <b>UTF-8 字节</b>处理，与 Go 的 {@code []byte(s)} 一致）
     * @return 解出的字节
     * @throws CorruptInputException 编码非法；{@link CorruptInputException#offset()} 即
     *                               Go 消息里的那个字节下标
     */
    static byte[] decodeString(String input) {
        byte[] src = input == null ? new byte[0] : input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (src.length == 0) {
            return new byte[0];
        }
        // 长度上界：Go 的 DecodedLen(n) = n/4*3（含 padding），4 字节最多出 3 字节
        byte[] out = new byte[src.length / 4 * 3 + 3];
        int si = 0;
        int n = 0;

        // 对照 Go Decode：外层 `for si < len(src)`。Go 的两条批量快路径
        // （assemble32/64）只在 4/8 字符全部合法时才走，结果与逐 quantum 一致，
        // 故这里只保留 decodeQuantum 一条路径——错误偏移也更直观。
        while (si < src.length) {
            byte[] dbuf = new byte[4];
            int dlen = 4;
            int trailingGarbageOffset = -1;

            for (int j = 0; j < 4; j++) {
                if (src.length == si) {
                    if (j == 0) {
                        // Go: case j == 0 → return si, 0, nil（不产出字节）
                        dlen = 0;
                        break;
                    }
                    // Go: case j == 1, enc.padChar != NoPadding → CorruptInputError(si - j)
                    throw new CorruptInputException(si - j);
                }
                byte in = src[si];
                si++;

                int decoded = DECODE_MAP[in & 0xFF];
                if ((decoded & 0xFF) != 0xFF) {
                    dbuf[j] = (byte) decoded;
                    continue;
                }
                if (in == '\n' || in == '\r') {
                    j--;
                    continue;
                }
                if (in != PADDING) {
                    // Go: CorruptInputError(si - 1)（si 已自增，指向坏字符的下一格）
                    throw new CorruptInputException(si - 1);
                }

                // 到这里 in == '='，即 padding
                if (j == 0 || j == 1) {
                    throw new CorruptInputException(si - 1);
                }
                if (j == 2) {
                    // "==" 里的第二个 '=' 还没消费；跳过换行后必须真的是 '='
                    while (si < src.length && (src[si] == '\n' || src[si] == '\r')) {
                        si++;
                    }
                    if (si == src.length) {
                        throw new CorruptInputException(src.length);
                    }
                    if (src[si] != PADDING) {
                        throw new CorruptInputException(si - 1);
                    }
                    si++;
                }
                // 跳过换行；若后面还有内容，Go 记为 trailing garbage
                while (si < src.length && (src[si] == '\n' || src[si] == '\r')) {
                    si++;
                }
                if (si < src.length) {
                    trailingGarbageOffset = si;
                }
                dlen = j;
                break;
            }

            if (dlen == 0) {
                break;
            }

            // 对照 Go：val := dbuf[0]<<18 | dbuf[1]<<12 | dbuf[2]<<6 | dbuf[3]
            int val = ((dbuf[0] & 0xFF) << 18) | ((dbuf[1] & 0xFF) << 12)
                    | ((dbuf[2] & 0xFF) << 6) | (dbuf[3] & 0xFF);
            int b0 = (val >>> 16) & 0xFF;
            int b1 = (val >>> 8) & 0xFF;
            int b2 = val & 0xFF;
            switch (dlen) {
                case 4:
                    out[n + 2] = (byte) b2;
                    // fallthrough
                case 3:
                    out[n + 1] = (byte) b1;
                    // fallthrough
                case 2:
                    out[n] = (byte) b0;
                    break;
                default:
                    break;
            }
            n += dlen - 1;

            if (trailingGarbageOffset >= 0) {
                // Go 是"先产出字节、再带错返回"，调用方（DecodeString）会丢掉这部分输出
                throw new CorruptInputException(trailingGarbageOffset);
            }
        }

        byte[] result = new byte[n];
        System.arraycopy(out, 0, result, 0, n);
        return result;
    }

    /** 对照 Go 的 {@code base64.CorruptInputError}（消息逐字一致）。 */
    static class CorruptInputException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int offset;

        CorruptInputException(int offset) {
            super("illegal base64 data at input byte " + offset);
            this.offset = offset;
        }

        int offset() {
            return offset;
        }
    }
}
