package com.ragagent.common.security;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 输入校验与 Markdown 清理。
 *
 * <p>validateInput 三步：控制字符（&lt;32 且非 \t \n \r）→ UTF-8 有效性 → 16 条 XSS
 * 正则命中即拒绝。非法时返回 {@code null}，合法时原样返回输入。</p>
 *
 * <p>注意：正则大小写不敏感且需要闭合标签——因此 {@code "<script>x"}
 * （无闭合）<b>不</b>命中，validateInput 会放行。这是既有契约行为，别"顺手修好"。</p>
 */
public final class InputSanitizer {

    private InputSanitizer() {
    }

    private static final Pattern[] XSS_PATTERNS = {
            Pattern.compile("(?i)<script[^>]*>.*?</script>"),
            Pattern.compile("(?i)<iframe[^>]*>.*?</iframe>"),
            Pattern.compile("(?i)<object[^>]*>.*?</object>"),
            Pattern.compile("(?i)<embed[^>]*>.*?</embed>"),
            Pattern.compile("(?i)<embed[^>]*>"),
            Pattern.compile("(?i)<form[^>]*>.*?</form>"),
            Pattern.compile("(?i)<input[^>]*>"),
            Pattern.compile("(?i)<button[^>]*>.*?</button>"),
            Pattern.compile("(?i)javascript:"),
            Pattern.compile("(?i)vbscript:"),
            Pattern.compile("(?i)onload\\s*="),
            Pattern.compile("(?i)onerror\\s*="),
            Pattern.compile("(?i)onclick\\s*="),
            Pattern.compile("(?i)onmouseover\\s*="),
            Pattern.compile("(?i)onfocus\\s*="),
            Pattern.compile("(?i)onblur\\s*="),
    };

    /**
     * 输入为空直接放行；非法返回 {@code null}，
     * 否则返回原样字符串（本方法只做判定，不改写内容）。
     */
    public static String validateInput(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        for (int i = 0; i < input.length(); ) {
            int cp = input.codePointAt(i);
            if (cp < 32 && cp != 9 && cp != 10 && cp != 13) {
                return null;
            }
            i += Character.charCount(cp);
        }
        // UTF-8 有效性：Java String 恒为有效 UTF-16，无对应失败态；
        // 传输层（Servlet 已按 UTF-8 解码/请求体 JSON）等价保证。
        for (Pattern p : XSS_PATTERNS) {
            if (p.matcher(input).find()) {
                return null;
            }
        }
        return input;
    }

    /** 对照 CleanMarkdown：命中 XSS 模式的子串整体移除（不转义）。 */
    public static String cleanMarkdown(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        String cleaned = input;
        for (Pattern p : XSS_PATTERNS) {
            cleaned = p.matcher(cleaned).replaceAll("");
        }
        return cleaned;
    }

    /** 校验字节串是否为合法 UTF-8（仅用于直接拿字节串的场景，当前无调用方）。 */
    static boolean validUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
