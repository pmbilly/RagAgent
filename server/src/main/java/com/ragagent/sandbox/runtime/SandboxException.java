package com.ragagent.sandbox.runtime;

/**
 * Go sentinel 错误的 Java 载体（对照 internal/sandbox/sandbox.go L98-126 的错误族
 * 与 internal/sandbox/session_lifecycle.go L30 的 ErrSandboxSessionDeleted）。
 *
 * <p>Go 的 {@code errors.Is(err, ErrXxx)} 分类在此映射为 {@link #kind} 判定。
 * message 逐字等于 Go 哨兵文本——它可能出现在响应体（如 Security validation failed
 * 分支的 result.Error）里，属契约的一部分。</p>
 *
 * <p>Go 的 Execute 同时返回 (result, error) 的形态（如安全校验失败时既带
 * ExitCode=-1 的 result 又带 ErrSecurityViolation）在 Java 折叠为异常：
 * 部分结果挂在 {@link #partialResult} 上，调用方需要输出给用户时读取。</p>
 */
public class SandboxException extends RuntimeException {

    public enum Kind {
        SANDBOX_DISABLED("sandbox is disabled"),
        TIMEOUT("execution timed out"),
        SCRIPT_NOT_FOUND("script not found"),
        INVALID_SCRIPT("invalid script"),
        EXECUTION_FAILED("script execution failed"),
        SECURITY_VIOLATION("security validation failed"),
        DANGEROUS_COMMAND("script contains dangerous command"),
        ARG_INJECTION("argument injection detected"),
        STDIN_INJECTION("stdin injection detected"),
        NO_LIVE_SESSION_SANDBOX("session has no live sandbox"),
        SANDBOX_PAUSED("session sandbox is paused"),
        TERMINAL_UNSUPPORTED("sandbox backend does not support interactive terminals"),
        SESSION_DELETED("sandbox session no longer exists"),
        /** 非哨兵错误（Go 的 fmt.Errorf 包装族）：message 自带原文。 */
        INTERNAL("");

        public final String defaultMessage;

        Kind(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }
    }

    public final Kind kind;

    /** Go 的 (result, err) 双返回中被折叠掉的 result；多数路径为 null。 */
    public final SandboxManager.ExecuteResult partialResult;

    public SandboxException(Kind kind, String message) {
        super(message == null || message.isEmpty() ? kind.defaultMessage : message);
        this.kind = kind;
        this.partialResult = null;
    }

    public SandboxException(Kind kind, String message, Throwable cause) {
        super(message == null || message.isEmpty() ? kind.defaultMessage : message, cause);
        this.kind = kind;
        this.partialResult = null;
    }

    private SandboxException(Kind kind, String message, SandboxManager.ExecuteResult partialResult) {
        super(message);
        this.kind = kind;
        this.partialResult = partialResult;
    }

    public static SandboxException sandboxDisabled() {
        return new SandboxException(Kind.SANDBOX_DISABLED, null);
    }

    public static SandboxException timeout() {
        return new SandboxException(Kind.TIMEOUT, null);
    }

    public static SandboxException scriptNotFound() {
        return new SandboxException(Kind.SCRIPT_NOT_FOUND, null);
    }

    public static SandboxException invalidScript() {
        return new SandboxException(Kind.INVALID_SCRIPT, null);
    }

    public static SandboxException securityViolation(String detail) {
        return new SandboxException(Kind.SECURITY_VIOLATION, detail);
    }

    public static SandboxException argInjection(String detail) {
        return new SandboxException(Kind.ARG_INJECTION, detail);
    }

    public static SandboxException stdinInjection(String detail) {
        return new SandboxException(Kind.STDIN_INJECTION, detail);
    }

    public static SandboxException noLiveSessionSandbox() {
        return new SandboxException(Kind.NO_LIVE_SESSION_SANDBOX, null);
    }

    public static SandboxException sessionDeleted() {
        return new SandboxException(Kind.SESSION_DELETED, null);
    }

    public static SandboxException internal(String message) {
        return new SandboxException(Kind.INTERNAL, message);
    }

    public static SandboxException internal(String message, Throwable cause) {
        return new SandboxException(Kind.INTERNAL, message, cause);
    }

    /** 携带部分结果重新抛出（对照 Go 的「有 result 也有 err」返回）。 */
    public SandboxException withPartialResult(SandboxManager.ExecuteResult result) {
        return new SandboxException(this.kind, getMessage(), result);
    }

    /** 对照 {@code errors.Is(err, ErrXxx)}：沿 cause 链找首个 SandboxException。 */
    public static SandboxException find(Throwable err) {
        while (err != null) {
            if (err instanceof SandboxException se) {
                return se;
            }
            err = err.getCause();
        }
        return null;
    }

    public static boolean hasKind(Throwable err, Kind kind) {
        SandboxException se = find(err);
        return se != null && se.kind == kind;
    }
}
