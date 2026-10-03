package com.ragagent.retrieval.engine.sqlite;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 关键词面的纯函数族：CJK 二元切分与 FTS5 查询串净化，
 * 外加 sqlite-vec 的 float32 序列化（小端 float32）。
 *
 * <p>口径要点：连续汉字串切成 <b>重叠二元组</b>（单字串保留原样）；非 CJK 段原样成词；
 * 空白/标点/符号是分隔符。查询串同样切二元组后用 {@code "tok" OR "tok"} 连接（模糊匹配）。</p>
 */
public final class SqliteCjkBigram {

    private SqliteCjkBigram() {
    }

    /** 连续 Han 段 → 重叠二元组；其余按分隔符切词。 */
    public static String tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        List<Integer> cjk = new ArrayList<>();
        StringBuilder nonCjk = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isHan(cp)) {
                flushNonCjk(parts, nonCjk);
                cjk.add(cp);
            } else if (isDelimiter(cp)) {
                flushCjk(parts, cjk);
                flushNonCjk(parts, nonCjk);
            } else {
                flushCjk(parts, cjk);
                nonCjk.appendCodePoint(cp);
            }
        }
        flushCjk(parts, cjk);
        flushNonCjk(parts, nonCjk);
        return String.join(" ", parts);
    }

    /** 查询串净化：切成 {@code "a" OR "b"} 形态；无词元 → ""。 */
    public static String sanitizeQuery(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        List<String> parts = new ArrayList<>();
        for (String field : tokenize(trimmed).split("\\s+")) {
            if (!field.isEmpty()) {
                parts.add("\"" + field + "\"");
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return String.join(" OR ", parts);
    }

    /** 小端 float32 字节序。 */
    public static byte[] serializeFloat32(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    /** 反序列化（读回向量用；sqlite-vec 同款小端 float32）。 */
    public static float[] deserializeFloat32(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[bytes.length / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = buffer.getFloat();
        }
        return out;
    }

    private static boolean isHan(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN;
    }

    /** Unicode 空白/标点/符号三分判定。 */
    private static boolean isDelimiter(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || isPunctOrSymbol(codePoint);
    }

    private static boolean isPunctOrSymbol(int cp) {
        int type = Character.getType(cp);
        return switch (type) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                 Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    private static void flushCjk(List<String> parts, List<Integer> cjk) {
        if (cjk.isEmpty()) {
            return;
        }
        if (cjk.size() == 1) {
            parts.add(new String(Character.toChars(cjk.get(0))));
        } else {
            for (int i = 0; i < cjk.size() - 1; i++) {
                parts.add(new String(Character.toChars(cjk.get(i)))
                        + new String(Character.toChars(cjk.get(i + 1))));
            }
        }
        cjk.clear();
    }

    private static void flushNonCjk(List<String> parts, StringBuilder nonCjk) {
        if (nonCjk.length() > 0) {
            parts.add(nonCjk.toString());
            nonCjk.setLength(0);
        }
    }
}
