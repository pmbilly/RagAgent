package com.ragagent.mcp.oauth;

/**
 * 当前 principal 尚未授权该服务（哨兵文案 {@code "no token available"}）。
 *
 * <p>必须与"仓储故障"区分开：前者意味着
 * <b>需要用户重新授权</b>，后者是运维故障。<b>带外层包装也照样能被识别</b>——
 * 判定走异常链遍历。</p>
 */
public class OAuthNoTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OAuthNoTokenException() {
        super("no token available");
    }

    /** 异常链里出现本类即视为"无 token"。 */
    public static boolean isNoToken(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof OAuthNoTokenException) {
                return true;
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return false;
    }
}
