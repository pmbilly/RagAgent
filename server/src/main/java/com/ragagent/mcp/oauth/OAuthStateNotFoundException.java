package com.ragagent.mcp.oauth;

/**
 * state / attempt 不存在或已过期（对照 Go 里 {@code fmt.Errorf("oauth state not found or
 * expired")} 与 {@code "oauth attempt not found or expired"} 两个错误值）。
 *
 * <p>Go 用裸 error 字符串承载；Java 需要能区分"过期/不存在"与"仓储故障"，
 * 故显式建模。<b>文案逐字保留</b>——它会出现在回调重定向后的日志与 500 响应里。</p>
 */
public class OAuthStateNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private OAuthStateNotFoundException(String message) {
        super(message);
    }

    /** 对照 Go {@code "oauth state not found or expired"}。 */
    public static OAuthStateNotFoundException state() {
        return new OAuthStateNotFoundException("oauth state not found or expired");
    }

    /** 对照 Go {@code "oauth attempt not found or expired"}。 */
    public static OAuthStateNotFoundException attempt() {
        return new OAuthStateNotFoundException("oauth attempt not found or expired");
    }
}
