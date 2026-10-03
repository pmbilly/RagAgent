package com.ragagent.agent.support;

/**
 * 抓取失败的可机读错误。
 *
 * <p>失败码区分「可重试的网络错误」与「永久失败」；{@link #getCode} /
 * {@link #isRetryable} / {@link #getMessage()} 提供 code/retryable/msg 三元组。非 FetchException 的
 * 异常按 {@link Code#CONNECTION} + retryable 处理。</p>
 */
public class FetchException extends RuntimeException {

    /** 抓取失败的阶段与类别。 */
    public enum Code {
        INVALID_URL("invalid_url"),
        DNS("dns_failed"),
        TIMEOUT("connection_timeout"),
        TLS("tls_failed"),
        HTTP_403("http_403"),
        HTTP_429("http_429"),
        HTTP_5XX("http_5xx"),
        HTTP_STATUS("http_status"),
        SSRF_REJECTED("ssrf_rejected"),
        REDIRECT_REJECTED("redirect_rejected"),
        READ("read_failed"),
        HTML_PARSE("html_parse_failed"),
        EMPTY_CONTENT("empty_content"),
        CONNECTION("connection_failed"),
        BODY_TOO_LARGE("body_too_large"),
        UNSUPPORTED_CONTENT("unsupported_content"),
        SNAPSHOT_EXPIRED("snapshot_expired");

        private final String wire;

        Code(String wire) {
            this.wire = wire;
        }

        /** 对外输出的错误码字符串值。 */
        public String wire() {
            return wire;
        }
    }

    private final Code code;
    private final boolean retryable;

    public FetchException(Code code, boolean retryable, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public Code getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** 非 FetchException → connection + retryable。 */
    public static Code codeOf(Throwable err) {
        if (err instanceof FetchException fe) {
            return fe.code;
        }
        return Code.CONNECTION;
    }

    public static boolean retryableOf(Throwable err) {
        if (err instanceof FetchException fe) {
            return fe.retryable;
        }
        return true;
    }
}
