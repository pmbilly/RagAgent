package com.ragagent.session.controller;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageNotFoundException;
import com.ragagent.session.domain.MessageSearchResult;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.dto.SearchMessagesRequest;
import com.ragagent.session.service.MessageService;
import com.ragagent.storage.support.FileService;
import com.ragagent.storage.support.Mode;
import com.ragagent.storage.support.PublicModeForbiddenException;
import com.ragagent.storage.support.ResourceModeException;
import com.ragagent.session.support.MessageReferenceRewriter;
import com.ragagent.storage.support.Rewriter;
import com.ragagent.storage.support.StorageBackendResolver;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息 HTTP 层。
 *
 * <h2>响应形态（§2.1）</h2>
 * <ul>
 *   <li>load → 裸 {@code [Message]}；search → 裸 {@link MessageSearchResult}；
 *       stats → 裸 {@code ChatHistoryKbStats}；</li>
 *   <li>删除 → <b>204</b>（§1.13；旧 {@code {"message":…,"success":true}} 退役）。</li>
 * </ul>
 *
 * <h2>错误门槛</h2>
 * <ul>
 *   <li>{@code resourceUrls} 非法：public 拒绝 → 403，其他坏值 → 400；</li>
 *   <li>{@code limit} 非整数**容错**回落 20（解析失败 → 默认值，不是 400）；</li>
 *   <li>{@code beforeTime} 解析失败 → 400（固定中文文案）；</li>
 *   <li>会话不可见 → 404 "session not found"；消息不存在 → 404 "record not found"
 *       （两个 404 文案**不同**）；</li>
 *   <li>搜索 {@code query} 缺失/为空 → 400 {@code query: 不能为空}（DTO 上的 {@code @NotBlank}）。</li>
 * </ul>
 */
@RestController
public class MessageController {

    private static final Logger log = LoggerFactory.getLogger(MessageController.class);

    /** 消息不存在时 404 的文案。 */
    private static final String RECORD_NOT_FOUND = "record not found";

    private final MessageService messageService;
    private final FileService fileService;
    private final StorageBackendResolver storageBackendResolver;
    private final com.ragagent.auth.service.TenantService tenantService;

    public MessageController(MessageService messageService,
                             ObjectProvider<FileService> fileService,
                             ObjectProvider<StorageBackendResolver> storageBackendResolver,
                             com.ragagent.auth.service.TenantService tenantService) {
        this.messageService = messageService;
        // 两个端口按 ObjectProvider 取（A3-3 起 StorageBackendResolver 有生产实现；
        // FileService 的进程级实现仍属装配项），缺 bean 时按未装配分支降级。
        this.fileService = fileService.getIfAvailable();
        this.storageBackendResolver = storageBackendResolver.getIfAvailable();
        this.tenantService = tenantService;
    }

    // ══════════════════════════ 加载消息历史 ══════════════════════════

    /**
     * <p>⚠️ {@code limit} 是**容错**的：非整数回落默认 20（解析失败 → 记日志 +
     * 默认值），与分页参数解析失败即 400 不同；limit 进日志前也过
     * {@code SanitizeForLog}。</p>
     */
    @GetMapping("/api/v1/messages/{sessionId}/load")
    public ResponseEntity<List<Message>> loadMessages(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "beforeTime", required = false) String beforeTime,
            @RequestParam(name = "resourceUrls", required = false) String resourceUrls) {
        String sid = LogSanitizer.sanitize(sessionId);

        Rewriter rewriter = resolveResourceRewriter(resourceUrls);

        int limitInt = 20;
        if (limit != null && !limit.isEmpty()) {
            try {
                limitInt = Integer.parseInt(LogSanitizer.sanitize(limit));
            } catch (NumberFormatException e) {
                log.warn("Invalid limit value, using default value 20, input: {}", limit);
            }
        }

        List<Message> messages;
        if (beforeTime == null || beforeTime.isEmpty()) {
            try {
                messages = messageService.getRecentMessages(sid, limitInt);
            } catch (SessionNotFoundException e) {
                log.warn("Session not found, ID: {}", sid);
                throw BizException.notFound(e.getMessage());
            } catch (RuntimeException e) {
                throw toInternal(e);
            }
        } else {
            OffsetDateTime before = parseBeforeTime(LogSanitizer.sanitize(beforeTime));
            try {
                messages = messageService.getMessagesBeforeTime(sid, before, limitInt);
            } catch (SessionNotFoundException e) {
                log.warn("Session not found, ID: {}", sid);
                throw BizException.notFound(e.getMessage());
            } catch (RuntimeException e) {
                throw toInternal(e);
            }
        }

        // 消息字段级重写在会话侧（MessageReferenceRewriter）：URL 重写内核在 storage，编排在 session，
        // 依赖方向保持「会话 → 存储」单向（原先 message 重写放在 storage 会反向依赖 session）。
        return ResponseEntity.ok(
                new MessageReferenceRewriter(rewriter).rewriteMessagesResponse(messages));
    }

    /**
     * RFC3339 / RFC3339Nano 都收（{@link OffsetDateTime#parse} 覆盖两者），
     * 失败 → 400 固定文案。
     */
    private static OffsetDateTime parseBeforeTime(String raw) {
        if (raw == null || raw.isBlank()) {
            throw beforeTimeError();
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw beforeTimeError();
        }
    }

    /** 游标时间格式错的固定文案（RFC3339 / RFC3339Nano 都收）。 */
    private static BizException beforeTimeError() {
        return new BizException(
                AppError.badRequest("beforeTime: 必须是 RFC3339 时间"));
    }

    // ══════════════════════════ 删除消息 ══════════════════════════

    /**
     * 两个 404 文案**刻意不同**：
     * 会话不可见是 "session not found"；消息不存在是 "record not found"。
     */
    @DeleteMapping("/api/v1/messages/{sessionId}/{id}")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("id") String messageId) {
        String sid = LogSanitizer.sanitize(sessionId);
        String mid = LogSanitizer.sanitize(messageId);
        try {
            messageService.deleteMessage(sid, mid);
        } catch (SessionNotFoundException e) {
            log.warn("Session not found, ID: {}", sid);
            throw BizException.notFound(e.getMessage());
        } catch (MessageNotFoundException e) {
            log.warn("Message not found, session ID: {}, message ID: {}", sid, mid);
            throw BizException.notFound(RECORD_NOT_FOUND);
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════ 搜索 ══════════════════════════

    /**
     * <p>⚠️ {@code query} 空串/缺失在 DTO 校验层就被拒（{@code @NotBlank}，
     * 文案 {@code query: 不能为空}）；handler 内没有第二道空 query 检查。</p>
     * <p>{@code query} 进搜索前还要过一遍 {@code SanitizeForLog}。</p>
     */
    @PostMapping("/api/v1/messages/search")
    public ResponseEntity<MessageSearchResult> searchMessages(
            @RequestBody @Valid SearchMessagesRequest request) {
        MessageSearchResult result;
        try {
            result = messageService.searchMessages(
                    LogSanitizer.sanitize(request.query()),
                    request.mode(),
                    request.limit() == null ? 0 : request.limit(),
                    request.sessionIds());
        } catch (RuntimeException e) {
            throw toInternal(e);
        }

        return ResponseEntity.ok(result);
    }

    // ══════════════════════════ 聊天历史统计 ══════════════════════════

    @GetMapping("/api/v1/messages/chat-history-stats")
    public ResponseEntity<com.ragagent.session.domain.ChatHistoryKbStats> getChatHistoryKbStats() {
        com.ragagent.session.domain.ChatHistoryKbStats stats;
        try {
            stats = messageService.getChatHistoryKbStats();
        } catch (RuntimeException e) {
            throw toInternal(e);
        }
        return ResponseEntity.ok(stats);
    }

    // ══════════════════════════ 公共 ══════════════════════════

    /**
     * public 拒绝 → 403，
     * 其他坏值 → 400。缺 bean 降级与 SessionStreamController 的 ObjectProvider 模式一致。
     */
    private Rewriter resolveResourceRewriter(String resourceUrls) {
        Mode mode;
        try {
            mode = Mode.resolve(resourceUrls);
        } catch (PublicModeForbiddenException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw BizException.forbidden(e.getMessage());
        } catch (ResourceModeException e) {
            log.warn("Rejected resource URL mode: {}", e.getMessage());
            throw BizException.badRequest(e.getMessage());
        }
        return Rewriter.forRequest(mode, currentTenant(), fileService, storageBackendResolver);
    }

    /**
     * 读者租户实体（A3-3 接线；此前恒 null）——Rewriter 用它解析"引用不带 provider
     * scheme 时的租户默认 provider"。
     */
    private com.ragagent.auth.domain.Tenant currentTenant() {
        Long tid = com.ragagent.common.context.TenantContext.currentTenantId();
        try {
            return tid == null || tid <= 0 ? null : tenantService.getTenantById(tid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 已带形态的业务错误必须原样透传（二次包装会把 "error code: N, error message: "
     * 前缀叠两层，见 docs/known-issues/02-wave-0-1.md 的记录）。
     */
    private static BizException toInternal(RuntimeException e) {
        if (e instanceof BizException biz) {
            return biz;
        }
        return BizException.internal(e.getMessage());
    }
}
