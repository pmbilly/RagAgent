package com.ragagent.session.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agentm.service.CustomAgentService;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.filter.WsAuthSupport;
import com.ragagent.common.context.TenantContext;
import com.ragagent.config.TenantProperties;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.service.SandboxTerminalAuthService;
import com.ragagent.session.service.SandboxTerminalAuthService.TerminalAuthDeniedException;
import com.ragagent.session.service.SandboxTerminalTicketService;
import com.ragagent.session.service.SandboxTerminalTicketService.TicketClaims;
import com.ragagent.session.service.SessionService;
import com.ragagent.session.service.SessionTerminalService;
import com.ragagent.session.service.SessionTerminalService.OpenResult;
import com.ragagent.session.service.TerminalBridge;
import com.ragagent.session.service.TerminalWebSocketServer;
import com.ragagent.session.service.TerminalWebSocketUpgradeHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 沙箱终端 HTTP 面（对照 Go internal/handler/session/sandbox_terminal_ws.go 全文 426 行）：
 *
 * <ul>
 *   <li>{@code POST /api/v1/sessions/:session_id/sandbox/terminal-ticket} —
 *       IssueSandboxTerminalTicket：普通认证 POST 铸 2 分钟握手票据（access JWT
 *       不进 WS URL/nginx 日志）；</li>
 *   <li>{@code GET /api/v1/sessions/:id/sandbox/terminal} — SandboxTerminalWS：
 *       **票据自鉴权的 WS 升级**（Go 注册在 Auth 之前；Java 侧 AuthFilter 通道 1.8
 *       让路 + APIKeyGate/RBAC 拦截器 exclude）。升级后 open 失败 → close 帧
 *       1008 + SANDBOX_NOT_BOUND / SANDBOX_PAUSED / TERMINAL_UNSUPPORTED / INTERNAL；
 *       成功 → bridge 泵组（dev 无 provider 执行体，成功路径 XDEP）。</li>
 * </ul>
 *
 * <p>每会话并发 PTY 上限 5（对照 sessionTerminalLimiter，进程内）——dev 每条连接在
 * 升级后立即关断，429 分支不可达（照抄语义）。</p>
 */
@RestController
public class SandboxTerminalController {

    private static final Logger log = LoggerFactory.getLogger(SandboxTerminalController.class);

    private final SessionService sessionService;
    private final SandboxTerminalTicketService ticketService;
    private final SandboxTerminalAuthService terminalAuth;
    private final SessionTerminalService terminalService;
    private final WsAuthSupport wsAuthSupport;
    @SuppressWarnings("unused")
    private final TenantProperties tenantProperties;
    private final ObjectMapper mapper;
    private final CustomAgentService agentService;

    /** 对照 sessionTerminalLimiter（Go 包级单例 → Java 静态；进程内语义一致）。 */
    private static final Map<String, AtomicInteger> TERMINAL_COUNTS = new ConcurrentHashMap<>();

    public SandboxTerminalController(SessionService sessionService,
            SandboxTerminalTicketService ticketService,
            SandboxTerminalAuthService terminalAuth,
            SessionTerminalService terminalService,
            WsAuthSupport wsAuthSupport,
            TenantProperties tenantProperties,
            ObjectMapper mapper,
            CustomAgentService agentService) {
        this.sessionService = sessionService;
        this.ticketService = ticketService;
        this.terminalAuth = terminalAuth;
        this.terminalService = terminalService;
        this.wsAuthSupport = wsAuthSupport;
        this.tenantProperties = tenantProperties;
        this.mapper = mapper;
        this.agentService = agentService;
    }

    // ── POST /sessions/:session_id/sandbox/terminal-ticket ──────────────────

    @PostMapping("/api/v1/sessions/{session_id}/sandbox/terminal-ticket")
    public void issueTicket(@PathVariable("session_id") String sessionId,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        issueTicketInternal(sessionId, request, response);
    }

    /** 对照 IssueSandboxTerminalTicket（L324-380）。 */
    void issueTicketInternal(String rawSessionId, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String sessionId = trim(rawSessionId);
        if (sessionId.isEmpty()) {
            writeGinError(response, 400, "session id is required");
            return;
        }
        try {
            sessionService.getOwnedSession(sessionId);
        } catch (SessionNotFoundException e) {
            writeGinError(response, 404, "session not found");
            return;
        } catch (RuntimeException e) {
            log.error("[sandbox-terminal] failed to load session {}: {}", sessionId, e.toString());
            writeGinError(response, 500, "failed to load session");
            return;
        }
        String userId = TenantContext.currentUserId();
        if (userId == null || userId.isEmpty()) {
            writeGinError(response, 401, "Unauthorized");
            return;
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            writeGinError(response, 401, "Unauthorized");
            return;
        }
        String accessToken = bearerToken(request);
        if (accessToken.isEmpty()) {
            writeGinError(response, 401, "Unauthorized: access token required");
            return;
        }
        var record = terminalAuth.getAccessTokenByValue(accessToken);
        if (record == null || trim(record.id()).isEmpty()) {
            writeGinError(response, 401, "Unauthorized");
            return;
        }
        if (!SandboxTerminalTicketService.accessTokenStillActive(record, userId,
                OffsetDateTime.now())) {
            writeGinError(response, 401, "Unauthorized");
            return;
        }
        String ticket;
        try {
            ticket = ticketService.issue(userId, tenantId, sessionId, record.id(),
                    SandboxTerminalTicketService.DEFAULT_TTL);
        } catch (RuntimeException e) {
            log.error("[sandbox-terminal] failed to issue ticket {}: {}", sessionId, e.toString());
            writeGinError(response, 500, "failed to issue ticket");
            return;
        }
        // gin.H 嵌套 map：外层 data < success；内层 expires_in < ticket（字母序）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expires_in", (int) SandboxTerminalTicketService.DEFAULT_TTL.getSeconds());
        data.put("ticket", ticket);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        writeJson(response, 200, body);
    }

    // ── GET /sessions/:id/sandbox/terminal（WS 升级） ────────────────────────

    @GetMapping("/api/v1/sessions/{id}/sandbox/terminal")
    public void terminalWs(@PathVariable("id") String sessionId,
            HttpServletRequest request, HttpServletResponse response)
            throws IOException, jakarta.servlet.ServletException {
        terminalWsInternal(sessionId, request, response);
    }

    /** 对照 SandboxTerminalWS（L145-248）。 */
    void terminalWsInternal(String rawSessionId, HttpServletRequest request,
            HttpServletResponse response) throws IOException, jakarta.servlet.ServletException {
        String sessionId = trim(rawSessionId);
        if (sessionId.isEmpty()) {
            writeGinError(response, 400, "session id is required");
            return;
        }

        TicketClaims claims;
        try {
            claims = ticketService.parse(request.getParameter("ticket"));
        } catch (RuntimeException e) {
            writeGinError(response, 401, "Unauthorized: invalid or expired ticket");
            return;
        }
        if (!claims.sessionId().equals(sessionId)) {
            writeGinError(response, 403, "ticket is not valid for this session");
            return;
        }
        // 一次调用覆盖整个握手身份：铸票 token 仍活、用户活跃、成员完整、会话仍属调用方
        User user;
        try {
            user = terminalAuth.checkSandboxTerminalAuth(claims, true);
        } catch (TerminalAuthDeniedException e) {
            writeGinError(response, 401, "Unauthorized: invalid or expired ticket");
            return;
        } catch (RuntimeException e) {
            log.error("[sandbox-terminal] failed to validate ticket {}: {}", sessionId,
                    e.toString());
            writeGinError(response, 500, "failed to validate ticket");
            return;
        }
        if (!wsAuthSupport.attachAuthenticatedUser(request, response, user, claims.tenantId())) {
            return;
        }

        // 对照 sessionTerminalLimiter.acquire：满 5 → 429（release 幂等）。
        AtomicInteger counter = TERMINAL_COUNTS.computeIfAbsent(sessionId, k -> new AtomicInteger());
        if (counter.incrementAndGet() > TerminalBridge.MAX_PER_SESSION) {
            counter.decrementAndGet();
            writeGinError(response, 429, "too many terminals for this session");
            return;
        }
        boolean upgraded;
        try {
            upgraded = runTerminal(request, response, sessionId, claims, counter);
        } catch (IOException | jakarta.servlet.ServletException | RuntimeException e) {
            releaseTerminalCount(sessionId, counter);
            throw e;
        }
        if (!upgraded) {
            // 升级失败族：响应已写（对照 "Upgrade already wrote the HTTP error response"）
            releaseTerminalCount(sessionId, counter);
        }
        // 升级成功：连接生命周期移交给 TerminalWebSocketUpgradeHandler.init，
        // 计数释放在升级后续跑闭包的 finally 里。
    }

    private static void releaseTerminalCount(String sessionId, AtomicInteger counter) {
        if (counter.decrementAndGet() <= 0) {
            TERMINAL_COUNTS.remove(sessionId, counter);
        }
    }

    /**
     * 升级段：判定 → 101 头 → arm 续跑闭包 → request.upgrade。
     * 返回 false 表示升级失败（HTTP 错误响应已写）。
     */
    private boolean runTerminal(HttpServletRequest request, HttpServletResponse response,
            String sessionId, TicketClaims claims, AtomicInteger counter)
            throws IOException, jakarta.servlet.ServletException {
        if (!TerminalWebSocketServer.preUpgradeCheck(request, response)) {
            return false;
        }

        // provision 是逐连接显式选择：GET 面板不创建/唤醒（对照 L199-206 注释）。
        // 升级前算好（init 回调时过滤器链已退出，TenantContext/request 均不可用）。
        boolean allowProvision = flagParam(request.getParameter("provision"));
        String sandboxConfigId = allowProvision
                ? provisionConfigId(request.getParameter("agent_id"),
                        request.getParameter("agent_source_tenant_id"))
                : "";
        long tenantId = TenantContext.currentTenantId() == null
                ? claims.tenantId() : TenantContext.currentTenantId();

        TerminalWebSocketServer.writeUpgradeHeaders(response,
                request.getHeader("Sec-WebSocket-Key"));
        TerminalWebSocketUpgradeHandler.arm(conn -> {
            try {
                runPostUpgrade(conn, tenantId, sessionId, allowProvision, sandboxConfigId);
            } finally {
                releaseTerminalCount(sessionId, counter);
            }
        });
        request.upgrade(TerminalWebSocketUpgradeHandler.class);
        return true;
    }

    /**
     * 升级后续跑（TerminalWebSocketUpgradeHandler.init 回调，Tomcat 同线程但已过
     * 过滤器链——租户经参数显式传入，对照 HANDOFF §2.3 纪律 3）。
     */
    private void runPostUpgrade(TerminalWebSocketServer conn, long tenantId, String sessionId,
            boolean allowProvision, String sandboxConfigId) {
        OpenResult opened = allowProvision
                ? terminalService.ensureSessionTerminal(tenantId, sessionId, sandboxConfigId)
                : terminalService.openSessionTerminal(tenantId, sessionId);

        if (opened.failure() != null) {
            log.warn("[sandbox-terminal] open failed session={} provision={} code={}",
                    sessionId, allowProvision, opened.failure().code);
            conn.writeClose(TerminalWebSocketServer.CLOSE_POLICY_VIOLATION,
                    opened.failure().code);
            conn.close();
            return;
        }
        if (opened.terminal() == null) {
            // 波 5 接缝：dev 不会走到这里（failure 已覆盖所有可达分支）
            conn.writeClose(TerminalWebSocketServer.CLOSE_POLICY_VIOLATION,
                    SessionTerminalService.Failure.INTERNAL.code);
            conn.close();
            return;
        }

        log.info("[sandbox-terminal] opened session={} backend={}", sessionId,
                opened.terminal().backend());
        // 对照 bridge 装配（L234-247）：生产 PtySession 随波 5 的 provider 执行体接线。
        // opened.terminal() 当前恒 null（XDEP），本分支仅承载波 5 的装配形状。
        throw new IllegalStateException("sandbox terminal pty session requires wave 5 provider runtime");
    }

    /**
     * 对照 terminalProvisionConfigID（L269-282）：provision=1 时解析 agent 的
     * sandbox_config_id。resolveAgent 的共享分支是 4.6d 备案的波 5 缺口
     * （own-agent 分支与 Go 逐行对应——Java 复用同源 CustomAgentService）。
     */
    private String provisionConfigId(String rawAgentId, String rawSourceTenant) {
        String agentId = trim(rawAgentId);
        if (agentId.isEmpty()) {
            return "";
        }
        long sourceTenant = tenantParam(rawSourceTenant);
        if (sourceTenant != 0) {
            // 共享 agent 解析随波 5（Go 的 resolveAgent shared 分支）
            return "";
        }
        try {
            var result = agentService.getAgentByID(agentId, null);
            if (result == null || result.row() == null) {
                return "";
            }
            JsonNode cfg = mapper.readTree(result.row().getConfig() == null
                    ? "{}" : result.row().getConfig());
            return trim(cfg.path("sandbox_config_id").asText(""));
        } catch (IOException | RuntimeException e) {
            log.warn("[sandbox-terminal] resolve agent {} failed: {}", agentId, e.toString());
            return "";
        }
    }

    /** 对照 terminalTenantParam：非数字/0 → 0（Go 的 ParseUint 负数也落 err）。 */
    static long tenantParam(String raw) {
        String v = trim(raw);
        if (v.isEmpty()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(v);
            return parsed <= 0 ? 0 : parsed;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 对照 terminalFlagParam：非显式真值一律 false（缺省/畸形不可授权副作用）。 */
    static boolean flagParam(String raw) {
        String v = trim(raw).toLowerCase(Locale.ROOT);
        return "1".equals(v) || "true".equals(v) || "yes".equals(v);
    }

    /** 对照 sandboxTerminalBearerToken。 */
    static String bearerToken(HttpServletRequest request) {
        String header = trim(request.getHeader("Authorization"));
        if (!header.startsWith("Bearer ")) {
            return "";
        }
        return trim(header.substring("Bearer ".length()));
    }

    /** 对照 c.JSON(status, gin.H{"error": msg})。 */
    private static void writeGinError(HttpServletResponse response, int status, String message)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        writeJson(response, status, body);
    }

    private static void writeJson(HttpServletResponse response, int status, Object body)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        byte[] bytes = new ObjectMapper().writeValueAsBytes(body);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
