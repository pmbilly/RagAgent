package com.ragagent.common.security;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 对照 Go internal/utils/security.go 的 ValidateInput / CleanMarkdown（波 2 knowledge 文档操作面引入）。
 *
 * <p>ValidateInput 三步：控制字符（&lt;32 且非 \t \n \r）→ UTF-8 有效性 → 16 条 XSS
 * 正则命中即拒绝。返回 (cleaned, valid)；Java 侧以 {@link #validateInput} 返回 null 表示
 * Go 的 ("", false)。</p>
 *
 * <p>注意：正则逐条照抄 Go（大小写不敏感、需要闭合标签）——因此 {@code "<script>x"}
 * （无闭合）<b>不</b>命中，validateInput 会放行（golden kg-move-badpath / kg-rename-badto
 * 实录钉住了这个 Go 侧行为，别"顺手修好"）。</p>
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
     * 对照 ValidateInput：输入为空直接放行；返回 null 等价 Go 的 ("", false)，
     * 否则返回原样字符串（Go 不改写内容，只做判定）。
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
        // UTF-8 有效性：Java String 恒为有效 UTF-16；Go 侧检查的是字节串可解码性，
        // 传输层（Servlet 已按 UTF-8 解码/请求体 JSON）等价保证，无对应失败态。
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

    /** Go utf8.ValidString 的字节级对位（仅用于直接拿字节串的场景，当前无调用方）。 */
    static boolean validUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
