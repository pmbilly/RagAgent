package com.ragagent.session.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.common.error.BizException;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageNotFoundException;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.service.MessageService;
import com.ragagent.session.service.SessionService;
import com.ragagent.session.sse.SseContract;
import com.ragagent.session.sse.SseFrameWriter;
import com.ragagent.session.sse.StreamEventEmitter;
import com.ragagent.storage.support.Mode;
import com.ragagent.storage.support.PublicModeForbiddenException;
import com.ragagent.storage.support.ResourceModeException;
import com.ragagent.storage.support.Rewriter;
import com.ragagent.storage.support.StorageBackendResolver;
import com.ragagent.storage.support.FileService;
import com.ragagent.storage.support.StreamRewriter;
import com.ragagent.stream.StreamBatch;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;

/**
 * 继续接收活跃流（对照 Go {@code internal/handler/session/stream.go} 的
 * {@code ContinueStream}，L29-204）。
 *
 * <p>路由：{@code GET /api/v1/sessions/continue-stream/:session_id?message_id=…}，
 * Viewer 角色 + API-Key 的 chat 能力（full-access）。</p>
 *
 * <h2>流程（顺序有意义，逐条对应 Go）</h2>
 * <ol>
 *   <li>取 session_id（路径）与 message_id（查询），各自做日志消毒；</li>
 *   <li><b>先解析 {@code resource_urls}</b>——必须在写任何 SSE 头之前，
 *       这样非法的取值还能落成普通 400 JSON；</li>
 *   <li>{@code GetSession}——不存在 404，其它 500；</li>
 *   <li>{@code GetMessage}——会话不可见 404、消息不存在 404（文案是
 *       {@code gorm.ErrRecordNotFound.Error()}）、其它 500；</li>
 *   <li>从 offset 0 读事件；**一个都没有就 404**；</li>
 *   <li>设 SSE 头，回放全部事件；若其中已有 {@code complete} 就直接收尾返回；</li>
 *   <li>否则进入 100ms 轮询，直到收到 {@code complete} 或客户端断开。</li>
 * </ol>
 *
 * <h2>⚠️ 客户端断开检测与 Go 的差异</h2>
 * <p>Go 用 {@code c.Request.Context().Done()}——连接一断就立刻返回。
 * Java 的阻塞式 Servlet 拿不到等价的即时通知（要拿到得走 {@code AsyncContext} + 容器钩子，
 * 而那条路要求过滤器链声明 {@code asyncSupported}，风险高于收益）。
 * 这里改用<b>写失败</b>检测：{@link SseFrameWriter#write} 抛 {@link IOException}
 * 即视为断开。</p>
 * <p>后果是<b>延迟</b>而非<b>错误</b>：客户端在流中途断线时，要等下一次有事件可写
 * 才会发现（活跃生成下就是下一个分片，亚秒级）；只有"流完全停滞"这种情形会多挂一会儿。
 * 差异有界且方向安全，未复刻 Go 的即时性。</p>
 */
@RestController
public class SessionStreamController {

    private static final Logger log = LoggerFactory.getLogger(SessionStreamController.class);

    /** 对照 Go {@code gorm.ErrRecordNotFound.Error()}——消息不存在时 404 的文案。 */
    private static final String RECORD_NOT_FOUND = "record not found";

    /** 对照 Go 的轮询间隔（{@code time.NewTicker(100 * time.Millisecond)}）。 */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    private final SessionService sessionService;
    private final MessageService messageService;
    private final StreamManager streamManager;
    private final StreamEventEmitter emitter;
    private final FileService fileService;
    private final StorageBackendResolver storageBackendResolver;
    private final com.ragagent.auth.service.TenantService tenantService;

    public SessionStreamController(
            SessionService sessionService,
            MessageService messageService,
            StreamManager streamManager,
            StreamEventEmitter emitter,
            ObjectProvider<FileService> fileService,
            ObjectProvider<StorageBackendResolver> storageBackendResolver,
            com.ragagent.auth.service.TenantService tenantService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.streamManager = streamManager;
        this.emitter = emitter;
        // A3-3 起 StorageBackendResolver 有生产实现（fileserve 桥）；FileService 的
        // 进程级实现仍属装配项。缺 bean 时按 Go 的 nil 分支降级：引用原样保留成 handle。
        this.fileService = fileService.getIfAvailable();
        this.storageBackendResolver = storageBackendResolver.getIfAvailable();
        this.tenantService = tenantService;
    }

    // ── 对照 Go resource_urls.go 的前两个函数 ────────────────────────────────

    /**
     * 对照 Go {@code resolveResourceRewriter}：由请求的 {@code resource_urls} 参数
     * （缺省时取部署默认）构造重写器。
     */
    Rewriter resolveResourceRewriter(String resourceUrls) {
        Mode mode = Mode.resolve(resourceUrls);
        return Rewriter.forRequest(mode, currentTenant(), fileService, storageBackendResolver);
    }

    /**
     * 对照 Go {@code resolveStreamRewriter}：再加一层 SSE 用的扣留缓冲。
     *
     * <p>因为一条存储引用可能横跨两个增量，必须**在任何 SSE 头写出之前**调用——
     * 非法取值还能落成普通 JSON 错误。</p>
     */
    StreamRewriter resolveStreamRewriter(String resourceUrls) {
        return new StreamRewriter(resolveResourceRewriter(resourceUrls));
    }

    /**
     * 当前租户的实体，供 {@link com.ragagent.storage.support.FileServiceResolver} 读
     * {@code storage_engine_config.default_provider}。
     *
     * <p><b>A3-3 接线</b>：Go 从 ctx 里取已加载好的 {@code *types.Tenant}（认证中间件放进去的），
     * Java 的 {@code TenantContext} 只存 tenantId、不存实体，故此处按 id 取实体
     * （与 {@code SystemController} / {@code HybridSearchService} 同一写法）。
     * 取不到时返回 null，等价于 Go 的 ctx 无租户降级。</p>
     */
    private Tenant currentTenant() {
        Long tid = com.ragagent.common.context.TenantContext.currentTenantId();
        try {
            return tid == null || tid <= 0 ? null : tenantService.getTenantById(tid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── 端点 ───────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/sessions/continue-stream/{session_id}")
    public void continueStream(
            @PathVariable("session_id") String rawSessionId,
            @RequestParam(value = "message_id", required = false) String rawMessageId,
            @RequestParam(value = Mode.QUERY_PARAM, required = false) String resourceUrls,
            HttpServletResponse response) throws IOException {

        // 对照 Go 的 secutils.SanitizeForLog：去掉换行/制表/控制字符，防日志注入。
        String sessionId = sanitizeForLog(rawSessionId);
        if (sessionId.isEmpty()) {
            throw BizException.badRequest("invalid session id");
        }
        String messageId = sanitizeForLog(rawMessageId);
        if (messageId.isEmpty()) {
            throw BizException.badRequest("Missing message ID");
        }

        log.info("Continuing stream, session ID: {}, message ID: {}", sessionId, messageId);

        // 必须在写任何 SSE 头之前解析：非法 resource_urls 要能落成普通 400 JSON。
        StreamRewriter resourceRewriter;
        try {
            resourceRewriter = resolveStreamRewriter(resourceUrls);
        } catch (PublicModeForbiddenException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw BizException.forbidden(e.getMessage());
        } catch (ResourceModeException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw BizException.badRequest(e.getMessage());
        }

        // 会话必须存在且属于本租户
        try {
            sessionService.getSession(sessionId);
        } catch (SessionNotFoundException e) {
            log.warn("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (RuntimeException e) {
            throw BizException.internal(e.getMessage());
        }

        // 取这条未完成的消息。注意三种失败各自映射（照抄 Go）：
        //   会话不可见 → 404 "session not found"（PR #1309 把 user 范围接进了这层检查）
        //   消息不存在 → 404 "record not found"（gorm.ErrRecordNotFound 的原文）
        //   其它       → 500
        Message message;
        try {
            message = messageService.getMessage(sessionId, messageId);
        } catch (SessionNotFoundException e) {
            log.warn("Session not found, ID: {}", sessionId);
            throw BizException.notFound(e.getMessage());
        } catch (MessageNotFoundException e) {
            log.warn("Message not found, session ID: {}, message ID: {}", sessionId, messageId);
            throw BizException.notFound(RECORD_NOT_FOUND);
        } catch (RuntimeException e) {
            throw BizException.internal(e.getMessage());
        }

        if (message == null) {
            // Go 的死代码：仓储零行时返回的是 (nil, err)、不是 (nil, nil)，
            // 所以这一支在 Go 里不可达。Java 侧同样不可达（仓储抛异常）。
            // 保留是为了让调用序列与 Go 逐行对应，将来若仓储改了行为这里有落点。
            log.warn("Incomplete message not found, session ID: {}, message ID: {}", sessionId, messageId);
            writeJsonError(response, 404, "Incomplete message not found");
            return;
        }

        String requestId = message.getRequestId();

        // 从 offset 0 读初始事件
        StreamBatch batch;
        try {
            batch = streamManager.getEvents(sessionId, messageId, 0);
        } catch (RuntimeException e) {
            throw BizException.internal("Failed to get stream data: " + e.getMessage());
        }
        List<StreamEvent> events = batch.events();

        if (events.isEmpty()) {
            log.warn("No events found in stream, session ID: {}, message ID: {}", sessionId, messageId);
            writeJsonError(response, 404, "No stream events found");
            return;
        }

        log.info("Preparing to replay {} events and continue streaming, session ID: {}, message ID: {}",
                events.size(), sessionId, messageId);

        // 到这里才开始写 SSE —— 前面任何一步失败都还能落成普通 JSON 错误
        SseContract.setSSEHeaders(response);
        SseFrameWriter.applyRenderedContentType(response);

        AtomicBoolean clientGone = new AtomicBoolean(false);
        StreamEventEmitter.ClientState client = clientGone::get;

        // 流是否已经结束（回放里出现 complete）
        boolean streamCompleted = false;
        for (StreamEvent evt : events) {
            if (evt.getType() == ResponseType.COMPLETE) {
                streamCompleted = true;
                break;
            }
        }

        try {
            for (StreamEvent evt : events) {
                emitter.emitStreamEvent(response, evt, requestId, resourceRewriter, client);
            }
        } catch (IOException e) {
            clientGone.set(true);
            log.debug("Client connection closed during replay");
            return;
        }

        if (streamCompleted) {
            log.info("Stream already completed, session ID: {}, message ID: {}", sessionId, messageId);
            SseContract.sendCompletionEvent(response, requestId);
            return;
        }

        // 继续轮询新事件
        int currentOffset = batch.nextOffset();
        while (true) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (clientGone.get()) {
                log.debug("Client connection closed");
                return;
            }

            StreamBatch next;
            try {
                next = streamManager.getEvents(sessionId, messageId, currentOffset);
            } catch (RuntimeException e) {
                // 读事件失败：把扣留缓冲里还剩的吐出去，然后收工（照抄 Go）
                log.error("Failed to get new events: {}", e.toString());
                try {
                    emitter.flushHeldStreamContent(response, requestId, resourceRewriter, client);
                } catch (IOException ignored) {
                    // 客户端已经走了——没有别人可发
                }
                return;
            }

            boolean completedNow = false;
            try {
                for (StreamEvent evt : next.events()) {
                    if (evt.getType() == ResponseType.COMPLETE) {
                        completedNow = true;
                    }
                    emitter.emitStreamEvent(response, evt, requestId, resourceRewriter, client);
                }
            } catch (IOException e) {
                clientGone.set(true);
                log.debug("Client connection closed while streaming");
                return;
            }

            currentOffset = next.nextOffset();

            if (completedNow) {
                log.info("Stream completed, session ID: {}, message ID: {}", sessionId, messageId);
                SseContract.sendCompletionEvent(response, requestId);
                return;
            }
        }
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    /**
     * SSE 起流前的 JSON 错误体：{@code {"error":"…"}}（与全局纯字符串错误形态同族）。
     *
     * <p>只能在 SSE 头写出**之前**调用：一旦开始流就不可能退回 JSON 了。</p>
     */
    static void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        String body = "{\"error\":\"" + escapeJsonString(message) + "\"}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    /** 对照 Go {@code secutils.SanitizeForLog}（internal/utils/security.go:469-492）。 */
    static String sanitizeForLog(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            // 换行/制表先归一成空格；其余控制字符直接丢弃
            if (c == '\n' || c == '\r' || c == '\t') {
                builder.append(' ');
            } else if (c >= 32) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static String escapeJsonString(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
