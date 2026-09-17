package com.ragagent.agent.approval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.llm.domain.ResponseType;

/**
 * 事件包络（对照 Go {@code event.Event}，internal/event/event.go:95-102）。
 *
 * <p>字段与 Go 完全一致：ID / Type / SessionID / Data / Metadata / RequestID。
 * {@code type} 复用 {@link ResponseType}——它已是线上契约的集中定义处
 * （见其类注释：“常量在此集中定义，避免各模块各写一份字符串”），
 * 本包只用其中的 TOOL_APPROVAL_REQUIRED / TOOL_APPROVAL_RESOLVED /
 * MCP_OAUTH_REQUIRED / MCP_OAUTH_RESOLVED 四个取值。</p>
 *
 * <p><b>与 Go 的差异</b>：Go 的 Emit 会在 ID 为空时补一个 UUID；Java 侧
 * {@link #of} 强制生成，gate 传入的 ID 本来就非空（{@code pendingID + "-approval-required"}），
 * 不受影响。</p>
 */
public record Event(
        String id,
        ResponseType type,
        String sessionId,
        Object data,
        Map<String, Object> metadata,
        String requestId) {

    public Event {
        sessionId = sessionId == null ? "" : sessionId;
        requestId = requestId == null ? "" : requestId;
        metadata = metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static Event of(String id, ResponseType type, String sessionId, Object data,
                           Map<String, Object> metadata, String requestId) {
        return new Event(id, type, sessionId, data, metadata, requestId);
    }
}
