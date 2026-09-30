package com.ragagent.auth.apikey.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

/**
 * API-Key 门禁的拒绝响应（对照 Go 里各中间件直接写的
 * {@code gin.H{"error": ...}}）。
 *
 * <p>Go 侧这几处都是"中间件直接 {@code c.AbortWithStatusJSON}"，因此形态是
 * **纯字符串 error 信封** {@code {"error":"..."}}（不是 AppError 信封）——
 * 与 {@code GuardForbiddenException} 属于同一类"守卫式 403"。
 * 键序按 encoding/json 的 map 字母序输出；这里只有一个键，无歧义。</p>
 */
final class APIKeyGateResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 {@code {"error": "Forbidden: API key scope does not allow this operation"}}（gate / file-serve）。 */
    static final String SCOPE_FORBIDDEN = "Forbidden: API key scope does not allow this operation";

    /** 对照 {@code {"error": "Forbidden: API keys cannot access this endpoint"}}（DenyAPIKeyPrincipal）。 */
    static final String API_KEY_DENIED = "Forbidden: API keys cannot access this endpoint";

    private APIKeyGateResponses() {
    }

    /** 写 403 + 纯字符串 error 信封，返回 false 便于 {@code return writeForbidden(...);}。 */
    static boolean writeForbidden(HttpServletResponse response, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(MAPPER.writeValueAsString(body));
        return false;
    }
}
