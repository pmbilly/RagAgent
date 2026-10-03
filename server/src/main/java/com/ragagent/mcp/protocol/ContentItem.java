package com.ragagent.mcp.protocol;

/**
 * tools/call 的内容项。
 *
 * <p>{@code type} 取值只处理 "text" / "image" 两种（见 {@code DefaultMcpClient#callTool}，
 * 其余类型静默丢弃）。</p>
 */
public record ContentItem(String type, String text, String data, String mimeType) {

    public static ContentItem text(String text) {
        return new ContentItem("text", text, null, null);
    }

    public static ContentItem image(String data, String mimeType) {
        return new ContentItem("image", null, data, mimeType);
    }
}
