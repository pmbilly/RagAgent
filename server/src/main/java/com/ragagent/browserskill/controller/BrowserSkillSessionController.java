package com.ragagent.browserskill.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.browserskill.domain.BrowserStatus;
import com.ragagent.browserskill.domain.Scope;
import com.ragagent.browserskill.service.BrowserSkillManager;
import com.ragagent.common.context.TenantContext;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.service.SessionService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 对照 Go session.Handler 的 {@code BrowserSkillConnection}
 * （internal/handler/session/browserskill.go L20-81）——**会话侧** local-browser
 * 连接端点（收尾批 W5d；对照波 3 已翻的引擎级 extension/authorize/internal 三条）：
 *
 * <ul>
 *   <li>{@code GET /api/v1/sessions/:id/local-browser}</li>
 *   <li>{@code POST /api/v1/sessions/:session_id/local-browser}</li>
 * </ul>
 *
 * <p>挂在 sessions 组（Auth + RBAC Viewer 下限 + API-Key chat 门禁；gin 两条路由
 * 共用一个 handler，参数名 :id/:session_id 的差异照 Go 的取参顺序消化）。
 * 行序对照（golden 依赖）：</p>
 * <ol>
 *   <li>GetOwnedSession 失败 → 404 {"error":"session not found"}（先于一切）；</li>
 *   <li>Cache-Control: no-store；</li>
 *   <li>GET：GetStatus → 503（err）/ 200 {"data":status,"success":true}；
 *       dev 未配 BROWSERSKILL_BINARY 时 Status.enabled=false 但**不报错**（与
 *       /me/browser 的 GET 同形—— Enabled 检查只在 POST 路径）；</li>
 *   <li>POST：!Enabled → 503 "local browser is unavailable"（先于 body 校验）→
 *       MaxBytesReader(4096) + 绑定失败 → 400 "invalid browser action" →
 *       action 分派（preview/focus/select/start/resume/pause/stop；其余 400）→
 *       err → 409 {"error":err.Error()} → 成功再 GetStatus → 200。</li>
 * </ol>
 *
 * <p>preview 的成功响应是 {@code c.Data(200,"application/json",
 * '{"success":true,"data":'+frame+'}')}——原生字节拼接（frame 是模型已序列化的
 * RawMessage），与 gin.H 的信封键序一致（data < success）但走字节通路。</p>
 */
@RestController
public class BrowserSkillSessionController {

    private static final int MAX_BODY_BYTES = 4096;

    private final BrowserSkillManager browserSkill;
    private final SessionService sessionService;
    private final ObjectMapper mapper;

    public BrowserSkillSessionController(BrowserSkillManager browserSkill,
            SessionService sessionService, ObjectMapper mapper) {
        this.browserSkill = browserSkill;
        this.sessionService = sessionService;
        this.mapper = mapper;
    }

    /** GET /api/v1/sessions/{id}/local-browser（GET 树的通配名是 :id）。 */
    @GetMapping("/api/v1/sessions/{id}/local-browser")
    public void getConnection(@org.springframework.web.bind.annotation.PathVariable("id") String id,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleConnection(id, request, response);
    }

    /** POST /api/v1/sessions/{session_id}/local-browser（POST 树的通配名是 :session_id）。 */
    @PostMapping("/api/v1/sessions/{session_id}/local-browser")
    public void postConnection(
            @org.springframework.web.bind.annotation.PathVariable("session_id") String sessionId,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleConnection(sessionId, request, response);
    }

    private void handleConnection(String id, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        // Go：c.Param("session_id") 空 → c.Param("id")；两条路由各挂一个参数，取到的即值
        String sessionId = id == null ? "" : id.trim();
        if (sessionId.isEmpty()) {
            GinJson.error(response, 404, mapper, "session not found");
            return;
        }
        // 对照 GetOwnedSession（40s ctx 超时是 Go 的网络兜底，Java 阻塞调用无对应物）
        try {
            sessionService.getOwnedSession(sessionId);
        } catch (SessionNotFoundException e) {
            GinJson.error(response, 404, mapper, "session not found");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        Scope scope = scope();
        if ("GET".equals(request.getMethod())) {
            try {
                BrowserStatus status = browserSkill.getStatus(scope, sessionId);
                GinJson.dataSuccess(response, mapper, status);
            } catch (RuntimeException e) {
                GinJson.error(response, 503, mapper,
                        e.getMessage() == null ? e.toString() : e.getMessage());
            }
            return;
        }
        if (!browserSkill.enabled()) {
            GinJson.error(response, 503, mapper, "local browser is unavailable");
            return;
        }
        ConnectionInput input = readInput(request);
        if (input == null) {
            GinJson.error(response, 400, mapper, "invalid browser action");
            return;
        }
        String action = input.action == null ? "" : input.action;
        RuntimeException failure = null;
        switch (action) {
            case "preview" -> {
                try {
                    com.fasterxml.jackson.databind.JsonNode frame =
                            browserSkill.preview(scope, sessionId);
                    byte[] frameBytes = mapper.writeValueAsBytes(frame);
                    byte[] prefix = "{\"success\":true,\"data\":".getBytes(
                            java.nio.charset.StandardCharsets.UTF_8);
                    byte[] body = new byte[prefix.length + frameBytes.length + 1];
                    System.arraycopy(prefix, 0, body, 0, prefix.length);
                    System.arraycopy(frameBytes, 0, body, prefix.length, frameBytes.length);
                    body[body.length - 1] = '}';
                    response.setStatus(200);
                    response.setHeader("Content-Type", "application/json");
                    response.setContentLength(body.length);
                    response.getOutputStream().write(body);
                    return;
                } catch (RuntimeException e) {
                    failure = e;
                }
            }
            case "focus" -> {
                try {
                    browserSkill.focus(scope, sessionId);
                } catch (RuntimeException e) {
                    failure = e;
                }
            }
            case "select", "start", "resume", "pause", "stop" -> {
                try {
                    browserSkill.control(scope, sessionId, action);
                } catch (RuntimeException e) {
                    failure = e;
                }
            }
            default -> {
                GinJson.error(response, 400, mapper, "invalid browser action");
                return;
            }
        }
        if (failure != null) {
            GinJson.error(response, 409, mapper,
                    failure.getMessage() == null ? failure.toString() : failure.getMessage());
            return;
        }
        try {
            BrowserStatus status = browserSkill.getStatus(scope, sessionId);
            GinJson.dataSuccess(response, mapper, status);
        } catch (RuntimeException e) {
            GinJson.error(response, 503, mapper,
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** 对照 browserSkillScope：认证主体 → (tenant, user)。 */
    private static Scope scope() {
        Long tenant = TenantContext.currentTenantId();
        String user = TenantContext.currentUserId();
        return new Scope(tenant == null ? 0 : tenant, user == null ? "" : user);
    }

    /**
     * 对照 MaxBytesReader(4096) + ShouldBindJSON：超限/非 JSON/空 body 一律
     * 400 "invalid browser action"；"null" 字面量绑定成功、action 空 → default 分支。
     */
    private ConnectionInput readInput(HttpServletRequest request) throws IOException {
        InputStream in = request.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (buf.size() + n > MAX_BODY_BYTES) {
                return null;
            }
            buf.write(chunk, 0, n);
        }
        try {
            ConnectionInput parsed = mapper.readValue(buf.toByteArray(), ConnectionInput.class);
            return parsed != null ? parsed : new ConnectionInput();
        } catch (IOException e) {
            return null;
        }
    }

    /** 会话连接请求体（对照匿名 struct：仅 action；未知键忽略 = Go 语义）。 */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConnectionInput {
        public String action;
    }
}
