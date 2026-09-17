package com.ragagent.llm.chat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 从流式 JSON 片段里增量抽取字符串字段的值
 * （对照 Go internal/models/chat/json_field_extractor.go 全文）。
 *
 * <p>用于 LLM 工具调用的 arguments 增量分片。例如 fieldName="answer"、
 * 期望 JSON 形如 {@code {"answer":"...content..."}}：状态机跳过 JSON 前缀，只把
 * 值的增量吐出来（转义已还原）。</p>
 *
 * <p>行为契约（逐条对齐 Go）：</p>
 * <ul>
 *   <li>只在找到 {@code "fieldName"} 冒号后的开引号之后才开始产出；</li>
 *   <li>值结束（闭引号）后 {@link #isDone()} 为真，之后 {@link #feed} 一律返回空串；</li>
 *   <li>不完整的转义（末尾的单个 {@code \}、{@code \\uXXXX} 不足 6 字节）停在边界前，
 *       等下一次 feed 补齐；</li>
 *   <li>支持 {@code \" \\ \/ \n \r \t \b \f \\uXXXX}，其余 {@code \x} 原样保留两个字符
 *       （对照 Go 的 default 分支）；</li>
 *   <li>{@code \\uXXXX} 里非十六进制字符按 0 计入（Go 的 switch 无 default，贡献 0）；</li>
 *   <li>代理区码点（U+D800~U+DFFF）按 Go 的 {@code utf8.EncodeRune} 语义写成 U+FFFD。</li>
 * </ul>
 *
 * <p><b>多字节 UTF-8 边界</b>：内部按 UTF-8 <b>字节</b>记账（与 Go 的 string 逐字节对应），
 * 扫描时按 rune 步进而不是按字节，因此多字节字符永远不会被从中间切开。
 * Go 在遇到<b>半截</b>多字节序列（缓冲区末尾只有前 1~2 个字节）时会先把半截字节吐出去
 * （Go 的 string 是字节序列，下次拼接能自愈）；Java 的 {@code String} 表示不了半个字符，
 * 故这里改为停在边界之前、等补齐再吐——这一步对经 {@code String} 传入的增量不可达
 * （每个 String 增量编码出的都是完整序列），是为字节级分片预留的防御。</p>
 *
 * <p>非线程安全：一个流一个实例（与 Go 相同）。</p>
 */
public final class JsonFieldExtractor {

    private final String fieldName;
    /** 累积的完整 arguments（UTF-8 字节，与 Go 的 string buffer 逐字节对应）。 */
    private byte[] buffer = new byte[256];
    private int length;
    /** 字段值内容的起始字节偏移；-1 表示还没找到。 */
    private int valueStart = -1;
    /** 上次已产出的位置（相对 valueStart 的偏移）。 */
    private int lastEmit;
    /** 是否已见到值的闭引号。 */
    private boolean done;

    public JsonFieldExtractor(String fieldName) {
        this.fieldName = fieldName == null ? "" : fieldName;
    }

    /**
     * 对照 Go jsonFieldExtractor.Feed：处理一段新的 arguments 增量，
     * 返回本次可以产出的内容（已还原转义）；没有新内容时返回空串。
     */
    public String feed(String argsDelta) {
        if (done) {
            return "";
        }
        if (argsDelta != null && !argsDelta.isEmpty()) {
            append(argsDelta.getBytes(StandardCharsets.UTF_8));
        }

        // 还没定位到值的起点，继续找
        if (valueStart < 0) {
            int idx = findFieldValueStart(buffer, length, ("\"" + fieldName + "\"").getBytes(StandardCharsets.UTF_8));
            if (idx < 0) {
                return ""; // 还没见到值的开引号
            }
            valueStart = idx;
            lastEmit = 0;
        }

        // 从上次产出的位置继续扫描，找出本次可以安全产出的终点
        // （safeEnd 相对 valueStart；finished = 见到了值的闭引号）
        SafeEnd safe = findSafeEnd(buffer, valueStart, length, lastEmit);
        int safeEnd = safe.end();
        boolean finished = safe.finished();

        if (safeEnd <= lastEmit) {
            if (finished) {
                done = true;
            }
            return "";
        }

        String unescaped = unescapeJsonString(buffer, valueStart + lastEmit, valueStart + safeEnd);
        lastEmit = safeEnd;
        if (finished) {
            done = true;
        }
        return unescaped;
    }

    /** 对照 Go jsonFieldExtractor.IsDone：闭引号是否已出现。 */
    public boolean isDone() {
        return done;
    }

    // ------------------------------------------------------------------
    // 状态机（对照 json_field_extractor.go:81-151）
    // ------------------------------------------------------------------

    /**
     * 对照 Go findFieldValueStart：返回字段字符串值内容开始的字节偏移
     * （= 开引号之后一格）；没找到返回 -1。
     */
    private static int findFieldValueStart(byte[] buf, int len, byte[] key) {
        int idx = indexOf(buf, 0, len, key);
        if (idx < 0) {
            return -1;
        }
        int pos = idx + key.length;
        while (pos < len) {
            byte ch = buf[pos];
            if (ch == ':') {
                pos++;
                continue;
            }
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                pos++;
                continue;
            }
            if (ch == '"') {
                return pos + 1; // 值的开引号
            }
            return -1; // 非预期字符
        }
        return -1; // 还没见到开引号
    }

    /**
     * 对照 Go findSafeEnd 的返回值 {@code (safeEnd, finished)}：
     * {@code end} 相对 valueStart，{@code finished=true} 表示见到了值的闭引号
     * （此时 end = 闭引号位置）。
     */
    private record SafeEnd(int end, boolean finished) {
    }

    /**
     * 对照 Go findSafeEnd：从 from 起扫描值内容。
     */
    private static SafeEnd findSafeEnd(byte[] buf, int valueStart, int end, int from) {
        int i = valueStart + from;
        while (i < end) {
            byte ch = buf[i];
            if (ch == '\\') {
                // 转义序列至少还需要 1 字节
                if (i + 1 >= end) {
                    return new SafeEnd(i - valueStart, false); // 末尾的不完整转义：停在它之前
                }
                if (buf[i + 1] == 'u') {
                    // \\uXXXX 一共 6 字节
                    if (i + 5 >= end) {
                        return new SafeEnd(i - valueStart, false);
                    }
                    i += 6;
                } else {
                    i += 2;
                }
            } else if (ch == '"') {
                return new SafeEnd(i - valueStart, true); // 值的闭引号
            } else {
                int size = runeSize(buf, i, end);
                if (size == 0) {
                    size = 1;
                }
                i += size;
            }
        }
        return new SafeEnd(i - valueStart, false);
    }

    /**
     * 对照 Go 的 {@code utf8.DecodeRuneInString} 只取 size 的语义：完整合法序列返回 2/3/4，
     * 其它（ASCII、非法首字节、不完整序列、非法续字节）返回 1。
     *
     * <p>与 Go 的差异：Go 对不完整序列返回 size=1，于是会把半截字符吐出去；这里对
     * "<b>末尾截断</b>"的序列返回 0，由调用方停在边界前（Java 侧 String 无法表示半个字符）。</p>
     */
    private static int runeSize(byte[] buf, int i, int end) {
        int c = buf[i] & 0xFF;
        if (c < 0x80) {
            return 1;
        }
        int n;
        if ((c & 0xE0) == 0xC0) {
            if (c < 0xC2) {
                return 1; // overlong
            }
            n = 2;
        } else if ((c & 0xF0) == 0xE0) {
            n = 3;
        } else if ((c & 0xF8) == 0xF0) {
            if (c > 0xF4) {
                return 1;
            }
            n = 4;
        } else {
            return 1;
        }
        if (i + n > end) {
            return 0; // 后面还要补字节，先不吐
        }
        for (int k = 1; k < n; k++) {
            if ((buf[i + k] & 0xC0) != 0x80) {
                return 1; // 非法续字节：按单字节推进（与 Go 的 size=1 一致）
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 反转义（对照 json_field_extractor.go:153-224）
    // ------------------------------------------------------------------

    /** 对照 Go unescapeJSONString，作用在字节区间 {@code [from, to)} 上。 */
    private static String unescapeJsonString(byte[] data, int from, int to) {
        boolean hasBackslash = false;
        for (int i = from; i < to; i++) {
            if (data[i] == '\\') {
                hasBackslash = true;
                break;
            }
        }
        if (!hasBackslash) {
            return new String(data, from, to - from, StandardCharsets.UTF_8);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, to - from));
        int i = from;
        while (i < to) {
            if (data[i] == '\\' && i + 1 < to) {
                switch (data[i + 1]) {
                    case '"' -> { out.write('"'); i += 2; }
                    case '\\' -> { out.write('\\'); i += 2; }
                    case '/' -> { out.write('/'); i += 2; }
                    case 'n' -> { out.write('\n'); i += 2; }
                    case 'r' -> { out.write('\r'); i += 2; }
                    case 't' -> { out.write('\t'); i += 2; }
                    case 'b' -> { out.write('\b'); i += 2; }
                    case 'f' -> { out.write('\f'); i += 2; }
                    case 'u' -> {
                        if (i + 5 < to) {
                            int codepoint = parseHex4(data, i + 2);
                            writeCodePointUtf8(out, codepoint);
                            i += 6;
                        } else {
                            out.write(data[i]);
                            i++;
                        }
                    }
                    default -> { out.write(data[i]); i++; }
                }
            } else {
                out.write(data[i]);
                i++;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 对照 Go 逐字符的十六进制累加：非 hex 字符贡献 0（switch 无 default）。 */
    private static int parseHex4(byte[] data, int start) {
        int codepoint = 0;
        for (int k = 0; k < 4; k++) {
            byte h = data[start + k];
            codepoint <<= 4;
            if (h >= '0' && h <= '9') {
                codepoint += h - '0';
            } else if (h >= 'a' && h <= 'f') {
                codepoint += h - 'a' + 10;
            } else if (h >= 'A' && h <= 'F') {
                codepoint += h - 'A' + 10;
            }
        }
        return codepoint;
    }

    /** 对照 Go 的 {@code b.WriteRune}：非法/代理区码点写成 U+FFFD，其余按 UTF-8 编码。 */
    private static void writeCodePointUtf8(ByteArrayOutputStream out, int codepoint) {
        int cp = codepoint;
        if (cp < 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            cp = 0xFFFD;
        }
        out.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 字节工具
    // ------------------------------------------------------------------

    private void append(byte[] data) {
        ensureCapacity(length + data.length);
        System.arraycopy(data, 0, buffer, length, data.length);
        length += data.length;
    }

    private void ensureCapacity(int required) {
        if (required <= buffer.length) {
            return;
        }
        int newLength = buffer.length;
        while (newLength < required) {
            newLength = newLength + (newLength >> 1) + 16;
        }
        buffer = Arrays.copyOf(buffer, newLength);
    }

    /** 朴素子串查找（对照 Go 的 strings.Index / bytes.Index）。 */
    private static int indexOf(byte[] haystack, int from, int to, byte[] needle) {
        if (needle.length == 0) {
            return from;
        }
        if (needle.length > to - from) {
            return -1;
        }
        outer:
        for (int i = from; i <= to - needle.length; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (haystack[i + k] != needle[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
