package com.ragagent.mcp.protocol;

import java.util.Map;

/**
 * MCP 服务端能力声明（对照 Go internal/mcp/types.go 的 {@code ServerCapabilities}）。
 *
 * <p>Go 把 ToolsCapability / ResourcesCapability / PromptsCapability 写成顶层类型；
 * Java 侧收成嵌套 record（同构，只是命名空间更紧）。三者的共同契约是：<b>不为 null 即代表
 * 服务端声明了该能力</b>，内部的 {@code listChanged} 是 omitempty 的布尔。</p>
 *
 * <p>本类目前只用作 initialize 结果的载体（Go 侧同样只透传、不做分支判断），
 * 保留字段是为了服务端能力探测的后续扩展。</p>
 */
public record ServerCapabilities(
        ToolsCapability tools,
        ResourcesCapability resources,
        PromptsCapability prompts,
        Map<String, Object> logging,
        Map<String, Object> experimental) {

    /** 对照 Go ToolsCapability。 */
    public record ToolsCapability(boolean listChanged) {
    }

    /** 对照 Go ResourcesCapability。 */
    public record ResourcesCapability(boolean subscribe, boolean listChanged) {
    }

    /** 对照 Go PromptsCapability。 */
    public record PromptsCapability(boolean listChanged) {
    }

    public static ServerCapabilities empty() {
        return new ServerCapabilities(null, null, null, null, null);
    }
}
