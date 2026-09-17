package com.ragagent.agent.approval;

/**
 * 审批门的错误哨兵（对照 Go approval 包的四个 package-level error）：
 *
 * <ul>
 *   <li>{@code ErrPendingNotFound} → {@link Kind#PENDING_NOT_FOUND}</li>
 *   <li>{@code ErrTenantMismatch} → {@link Kind#TENANT_MISMATCH}</li>
 *   <li>{@code ErrAlreadyResolved} → {@link Kind#ALREADY_RESOLVED}</li>
 *   <li>{@code ErrUserMismatch} → {@link Kind#USER_MISMATCH}</li>
 * </ul>
 *
 * Go 侧 handler 用 {@code errors.Is(err, approval.ErrPendingNotFound)} 判别并映射 HTTP 状态码
 * （见 internal/handler/mcp_service.go:716、internal/handler/mcp_oauth.go:311）。
 * Java 没有 sentinel error，改为**带 kind 的异常**：handler 用 {@code e.kind()} 做 switch 即可，
 * 语义与 errors.Is 一一对应（Kind 同时保留 Go 的原始英文消息，便于日志对照）。
 *
 * <p>另外 {@link Kind#INTERNAL} 承载 Go 侧以 {@code fmt.Errorf} 返回的非哨兵错误
 * （EventBus 为 nil、emit 失败、跨实例订阅失败等）。</p>
 *
 * <p>本异常是 <b>unchecked</b>：Go 的 error 返回值在 Java 里统一走异常通道，
 * 且 checker/事件总线实现（Spring/MyBatis）抛的都是运行时异常，无需 checked 声明。</p>
 */
public class ApprovalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 与 Go 哨兵一一对应的判别码 */
    public enum Kind {
        /** Go: ErrPendingNotFound = errors.New("tool approval pending not found") */
        PENDING_NOT_FOUND("tool approval pending not found"),
        /** Go: ErrTenantMismatch = errors.New("workspace mismatch for tool approval") */
        TENANT_MISMATCH("workspace mismatch for tool approval"),
        /** Go: ErrAlreadyResolved = errors.New("tool approval already resolved") */
        ALREADY_RESOLVED("tool approval already resolved"),
        /** Go: ErrUserMismatch = errors.New("user mismatch for tool approval") */
        USER_MISMATCH("user mismatch for tool approval"),
        /** Go 侧无哨兵的非哨兵错误（fmt.Errorf 包装） */
        INTERNAL("tool approval internal error");

        private final String defaultMessage;

        Kind(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        public String defaultMessage() {
            return defaultMessage;
        }
    }

    private final Kind kind;

    private ApprovalException(Kind kind, String message, Throwable cause) {
        super(message != null ? message : kind.defaultMessage(), cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean is(Kind candidate) {
        return kind == candidate;
    }

    /** 对照 Go ErrPendingNotFound */
    public static ApprovalException pendingNotFound() {
        return new ApprovalException(Kind.PENDING_NOT_FOUND, null, null);
    }

    /** 对照 Go ErrTenantMismatch */
    public static ApprovalException tenantMismatch() {
        return new ApprovalException(Kind.TENANT_MISMATCH, null, null);
    }

    /** 对照 Go ErrAlreadyResolved */
    public static ApprovalException alreadyResolved() {
        return new ApprovalException(Kind.ALREADY_RESOLVED, null, null);
    }

    /** 对照 Go ErrUserMismatch */
    public static ApprovalException userMismatch() {
        return new ApprovalException(Kind.USER_MISMATCH, null, null);
    }

    /** 对照 Go {@code fmt.Errorf("...: %w", err)} 这类非哨兵错误 */
    public static ApprovalException internal(String message) {
        return new ApprovalException(Kind.INTERNAL, message, null);
    }

    /** 对照 Go {@code fmt.Errorf("...: %w", err)}，保留原因链 */
    public static ApprovalException internal(String message, Throwable cause) {
        return new ApprovalException(Kind.INTERNAL, message, cause);
    }
}
