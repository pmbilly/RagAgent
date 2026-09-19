package com.ragagent.sandbox.runtime;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * provider 中立的错误载体（对照 Go {@code RemoteError}，
 * internal/sandbox/remote_errors.go L70-132）。
 *
 * <h2>Error() 字符串逐字对照 Go</h2>
 * <pre>
 *   消息为空且有 cause：  "{provider} {op}: {kind}: {cause}"
 *   有消息且有 cause：    "{provider} {op}: {kind}: {message}: {cause}"
 *   有消息无 cause：      "{provider} {op}: {kind}: {message}"
 * </pre>
 * <p>provider 空串回落 "remote"。这个字符串会出现在 QueryTemplates 的 500 信封
 * message 里（respondSandboxConfigServiceError 的非 sentinel 分支直通）——其中
 * cause 部分是各运行时的拨号措辞（Go/Java 不同），A/B 对该 golden 掩码 message。</p>
 *
 * <h2>分类规则（adapters 共享）</h2>
 * <ul>
 *   <li>{@link #httpErrorKind}：HTTP 状态码 → Kind（对照 Go remote_errors.go
 *       L258-283，Create 的 404 特判为 InvalidRequest）</li>
 *   <li>{@link #classifyTransport}：传输层异常 → Kind（对照
 *       normalizeCubeError L1134-1148：net.Error 非超时 → Unavailable、
 *       超时 → Timeout；Java 的 ConnectException/UnknownHostException/SSLException
 *       都对应 Go 的 net.Error 非超时分支）</li>
 * </ul>
 */
public class RemoteError extends RuntimeException {

    public final RemoteErrorKind kind;
    public final String provider;
    public final String op;
    public final String message;
    public final int statusCode;

    public RemoteError(String provider, String op, RemoteErrorKind kind,
            String message, Throwable cause, int statusCode) {
        super(format(provider, op, kind, message, cause), cause);
        this.kind = kind;
        this.provider = provider == null || provider.isEmpty() ? "remote" : provider;
        this.op = op;
        this.message = message;
        this.statusCode = statusCode;
    }

    public static RemoteError of(String provider, String op, RemoteErrorKind kind, String message) {
        return new RemoteError(provider, op, kind, message, null, 0);
    }

    private static String format(String provider, String op, RemoteErrorKind kind,
            String message, Throwable cause) {
        String prov = provider == null || provider.isEmpty() ? "remote" : provider;
        if (message != null && !message.isEmpty() && cause != null) {
            return prov + " " + op + ": " + kind.wire() + ": " + message + ": " + cause;
        }
        if (cause != null) {
            return prov + " " + op + ": " + kind.wire() + ": " + cause;
        }
        return prov + " " + op + ": " + kind.wire() + ": " + message;
    }

    /**
     * 对照 {@code httpErrorKind}（remote_errors.go L258-283）：所有 adapter 后端
     * （Cube、E2B）共享的状态码映射。op="Create" 的 404 是模板不存在 → InvalidRequest。
     */
    public static RemoteErrorKind httpErrorKind(String op, int status) {
        return switch (status) {
            case 400, 422 -> RemoteErrorKind.INVALID_REQUEST;
            case 401, 403 -> RemoteErrorKind.AUTHENTICATION;
            case 404 -> "Create".equals(op)
                    ? RemoteErrorKind.INVALID_REQUEST
                    : RemoteErrorKind.NOT_FOUND;
            case 408, 504 -> RemoteErrorKind.TIMEOUT;
            case 409 -> RemoteErrorKind.CONFLICT;
            case 410 -> RemoteErrorKind.TERMINAL;
            case 429, 507 -> RemoteErrorKind.CAPACITY;
            default -> status >= 500 ? RemoteErrorKind.UNAVAILABLE : RemoteErrorKind.INTERNAL;
        };
    }

    /**
     * 对照 normalizeCubeError/normalizeE2BError 的传输层分支（cube L1134-1148）：
     * 超时 → Timeout；其余 IOException（拒连、DNS 解析失败、TLS、reset）→
     * Unavailable（Go 的 net.Error 非超时分支）。
     */
    public static RemoteErrorKind classifyTransport(IOException err) {
        if (err instanceof SocketTimeoutException || err instanceof HttpTimeoutException) {
            return RemoteErrorKind.TIMEOUT;
        }
        return RemoteErrorKind.UNAVAILABLE;
    }

    /** 便捷判定：拒连类（ConnectException）——与其它 Unavailable 同 Kind。 */
    public static boolean isConnectionRefused(Throwable err) {
        while (err != null) {
            if (err instanceof ConnectException) {
                return true;
            }
            err = err.getCause();
        }
        return false;
    }

    /** DNS 解析失败（对照 Go net.DNSError，net.Error 非超时 → Unavailable）。 */
    public static boolean isUnknownHost(Throwable err) {
        while (err != null) {
            if (err instanceof UnknownHostException) {
                return true;
            }
            err = err.getCause();
        }
        return false;
    }
}
