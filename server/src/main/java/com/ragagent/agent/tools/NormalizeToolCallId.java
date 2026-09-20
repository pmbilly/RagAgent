package com.ragagent.agent.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 工具调用 ID 归一化（对照 Go {@code normalize_id.go} 的 NormalizeToolCallID，逐字移植）。
 *
 * <p>各家 LLM provider 返回的 ID 形态各异：OpenAI 是规整的 {@code call_abc123}；
 * 有的返回空串、超长 UUID、带特殊字符的串。本函数保证 ID：</p>
 * <ul>
 *   <li>非空（空时由 toolName+index 生成确定性 ID，实录：("", "search", 0) → call_702692b71127）；</li>
 *   <li>不超长（超长截断 + hash 后缀保唯一性，实录 205 字符 → 前 55 字符 + "_3aaa786e"）；</li>
 *   <li>字符安全（字母数字与 {@code _ -} 之外全部替换为 _；Go 按 rune 替换，中文一个字 → 一个 _）。</li>
 * </ul>
 *
 * <p>注意：Go 的长度判断按<b>字节</b>——但 sanitizer 之后 ID 只剩 ASCII，字节长 = 字符长。</p>
 */
public final class NormalizeToolCallId {

    /** 工具调用 ID 的最大长度（部分 provider 超长、部分为空）。对照 maxToolCallIDLen。 */
    public static final int MAX_TOOL_CALL_ID_LEN = 64;

    private NormalizeToolCallId() {
    }

    public static String normalize(String id, String toolName, int index) {
        id = id == null ? "" : id.strip();

        // 空则生成确定性 ID
        if (id.isEmpty()) {
            byte[] hash = sha256((toolName == null ? "" : toolName) + "_" + index);
            id = "call_" + hex(hash, 6);
        }

        // 去掉不安全字符（按 code point 替换，与 Go 的 rune 语义一致）
        id = sanitize(id);

        // 超长截断，用 hash 后缀保唯一性
        if (id.length() > MAX_TOOL_CALL_ID_LEN) {
            byte[] hash = sha256(id);
            String suffix = "_" + hex(hash, 4);
            id = id.substring(0, MAX_TOOL_CALL_ID_LEN - suffix.length()) + suffix;
        }

        return id;
    }

    /** 对照 validIDChars = [^a-zA-Z0-9_-] 的 ReplaceAllString(id, "_")。 */
    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            boolean ok = (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')
                    || (cp >= '0' && cp <= '9') || cp == '_' || cp == '-';
            sb.appendCodePoint(ok ? cp : '_');
        });
        return sb.toString();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 对照 fmt "%x"（hash[:n] 的小写十六进制）。 */
    private static String hex(byte[] hash, int nBytes) {
        StringBuilder sb = new StringBuilder(nBytes * 2);
        for (int i = 0; i < nBytes; i++) {
            sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(hash[i] & 0xF, 16));
        }
        return sb.toString();
    }
}
