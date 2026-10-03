package com.ragagent.mcp.protocol;

/**
 * resources/read 的单条内容。
 *
 * <p>文本资源填 {@code text}，二进制资源填 {@code blob}（Base64），二者互斥。</p>
 */
public record ResourceContent(String uri, String mimeType, String text, String blob) {
}
