package com.ragagent.mcp.oauth;

/**
 * state / attempt 不存在或已过期（两种固定文案分别对应 state 与 attempt）。
 *
 * <p>需要能区分"过期/不存在"与"仓储故障"，故显式建模。
 * <b>文案固定</b>——它会出现在回调重定向后的日志与 500 响应里。</p>
 */
public class OAuthStateNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private OAuthStateNotFoundException(String message) {
        super(message);
    }

    /** state 维度的固定文案。 */
    public static OAuthStateNotFoundException state() {
        return new OAuthStateNotFoundException("oauth state not found or expired");
    }

    /** attempt 维度的固定文案。 */
    public static OAuthStateNotFoundException attempt() {
        return new OAuthStateNotFoundException("oauth attempt not found or expired");
    }
}
