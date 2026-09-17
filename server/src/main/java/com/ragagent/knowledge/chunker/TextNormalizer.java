package com.ragagent.knowledge.chunker;

/**
 * 文本规范化（对照 Go internal/infrastructure/chunker/strategy.go:207 NormalizeLineEndings）。
 *
 * <p>浏览器 textarea 会把粘贴的 CRLF 归一化为 LF，而上传文件保留原始行尾；
 * 不做归一化，同一文档会产生不同的字符数与切分边界。</p>
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /** 对照 Go NormalizeLineEndings（strategy.go:207）。 */
    public static String normalizeLineEndings(String text) {
        if (!text.contains("\r")) {
            return text;
        }
        text = text.replace("\r\n", "\n");
        return text.replace("\r", "\n");
    }
}
