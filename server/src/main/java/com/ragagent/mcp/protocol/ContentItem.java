package com.ragagent.mcp.protocol;

/**
 * tools/call 的内容项（对照 Go internal/mcp/types.go 的 {@code ContentItem}）。
 *
 * <p>{@code type} 取值 "text" / "image"（Go 的注释还列了 "resource"，但 client.go 的转换
 * 只处理 text 与 image 两种——见 {@code DefaultMcpClient#callTool}）。</p>
 */
public record ContentItem(String type, String text, String data, String mimeType) {

    public static ContentItem text(String text) {
        return new ContentItem("text", text, null, null);
    }

    public static ContentItem image(String data, String mimeType) {
        return new ContentItem("image", null, data, mimeType);
    }
}
