package com.ragagent.agent.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次定向替换与它的批量应用算法（对照 Go {@code sandbox_edit.go} 的 SandboxEdit /
 * applySandboxEdits / indexAllNonOverlapping 与四个错误构造器，逐字移植）。
 *
 * <p>所有 old_string 都对<b>原始内容</b>求值再拼接结果：模型是看着同一个版本的文件写出
 * 全部 old_string 的，所以它们必须能在那个版本里各自求值。这也把重叠变成了可检出的
 * 错误而不是静默损坏——两个编辑声称同一批字节时，写入前就会出现相交区间。</p>
 *
 * <p><b>字节语义（Go string 的 len/Index 是字节）</b>：indexAllNonOverlapping 返回
 * <b>UTF-8 字节偏移</b>、拼接按字节区间进行——Go 实录（"中文中文" 找 "文" → [3, 9]）
 * 就是字节口径。匹配端点天然落在 rune 边界（子串本身是合法 UTF-8），不会切坏字符。</p>
 */
public final class SandboxEdits {

    private SandboxEdits() {
    }

    /** 一次定向替换（对照 SandboxEdit）。 */
    public record SandboxEdit(String oldString, String newString, boolean replaceAll) {
    }

    /** applySandboxEdits 的结果：更新后内容 + 替换次数。 */
    public record Applied(String content, int replacements) {
    }

    /** 应用失败（对照 Go 的 error 返回值；message 逐字复刻）。 */
    public static final class EditException extends RuntimeException {
        public EditException(String message) {
            super(message);
        }
    }

    /**
     * 对原始内容应用全部编辑并拼接结果（对照 applySandboxEdits）。
     * 任何编辑失败抛 {@link EditException}，文件保持原样。
     */
    public static Applied applySandboxEdits(String content, List<SandboxEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            throw new EditException(
                    "edits is required: an array of {old_string, new_string}, "
                            + "with one entry even for a single change");
        }
        boolean multi = edits.size() > 1;

        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        List<Span> spans = new ArrayList<>(edits.size());
        for (int i = 0; i < edits.size(); i++) {
            SandboxEdit e = edits.get(i);
            validateSandboxEdit(e, i, multi);
            List<Integer> found = indexAllNonOverlapping(content, e.oldString());
            if (found.isEmpty()) {
                throw new EditException(notFoundSandboxEditError(i, multi));
            }
            if (found.size() > 1 && !e.replaceAll()) {
                throw new EditException(ambiguousSandboxEditError(i, multi, found.size()));
            }
            for (int start : found) {
                spans.add(new Span(start, start + e.oldString().getBytes(StandardCharsets.UTF_8).length, i,
                        e.newString()));
            }
        }

        spans.sort((a, b) -> Integer.compare(a.start, b.start));
        for (int i = 1; i < spans.size(); i++) {
            if (spans.get(i).start < spans.get(i - 1).end) {
                throw new EditException(
                        overlappingSandboxEditError(spans.get(i - 1).index, spans.get(i).index));
            }
        }

        ByteArrayOutputStream b = new ByteArrayOutputStream(contentBytes.length);
        int prev = 0;
        for (Span s : spans) {
            b.write(contentBytes, prev, s.start - prev);
            byte[] replacement = s.newString.getBytes(StandardCharsets.UTF_8);
            b.write(replacement, 0, replacement.length);
            prev = s.end;
        }
        b.write(contentBytes, prev, contentBytes.length - prev);
        return new Applied(new String(b.toByteArray(), StandardCharsets.UTF_8), spans.size());
    }

    private record Span(int start, int end, int index, String newString) {
    }

    /**
     * 返回 sub 的每个<b>字节</b>出现位置（对照 indexAllNonOverlapping）：匹配后从匹配尾
     * 继续扫描，计数口径与 strings.Count 一致。<b>调用方必须先保证 sub 非空</b>——Go 侧
     * validateSandboxEdit 先拒掉空 old_string（Go 的同款算法对空子串同样死循环）；
     * Java 侧对空子串返回空列表作为安全替代。
     */
    public static List<Integer> indexAllNonOverlapping(String content, String sub) {
        List<Integer> found = new ArrayList<>();
        byte[] haystack = content.getBytes(StandardCharsets.UTF_8);
        byte[] needle = sub.getBytes(StandardCharsets.UTF_8);
        if (needle.length == 0) {
            return found;
        }
        int offset = 0;
        while (true) {
            int i = indexOfBytes(haystack, needle, offset);
            if (i < 0) {
                return found;
            }
            found.add(i);
            offset = i + needle.length;
        }
    }

    private static int indexOfBytes(byte[] haystack, byte[] needle, int from) {
        if (from > haystack.length) {
            return -1;
        }
        for (int i = Math.max(from, 0); i <= haystack.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static void validateSandboxEdit(SandboxEdit e, int index, boolean multi) {
        if (e.oldString().isEmpty()) {
            if (multi) {
                throw new EditException(
                        "edits[" + index + "].old_string is required; copy the exact text to change, including whitespace");
            }
            throw new EditException("old_string is required; copy the exact text to change, including whitespace");
        }
        if (e.oldString().equals(e.newString())) {
            if (multi) {
                throw new EditException("edits[" + index + "].old_string and new_string are identical; no change would be made");
            }
            throw new EditException("old_string and new_string are identical; no change would be made");
        }
    }

    static String notFoundSandboxEditError(int index, boolean multi) {
        if (multi) {
            return "edits[" + index + "].old_string was not found in the file. Copy the exact text (including whitespace) from the file";
        }
        return "old_string was not found in the file. Copy the exact text (including whitespace) from the file";
    }

    static String ambiguousSandboxEditError(int index, boolean multi, int occurrences) {
        if (multi) {
            return "edits[" + index + "].old_string matched " + occurrences
                    + " times. Include more surrounding context so it is unique, or set replace_all on that entry";
        }
        return "old_string matched " + occurrences
                + " times. Include more surrounding context so it is unique, or set replace_all";
    }

    static String overlappingSandboxEditError(int a, int b) {
        return "edits[" + a + "] and edits[" + b + "] cover overlapping text. Merge them into one edit that spans both changes";
    }
}
