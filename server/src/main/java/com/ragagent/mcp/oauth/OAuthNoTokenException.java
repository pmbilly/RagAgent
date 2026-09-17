package com.ragagent.mcp.oauth;

/**
 * 当前 principal 尚未授权该服务（对照 mcp-go {@code transport.ErrNoToken} 哨兵，
 * oauth.go:22，文案 {@code "no token available"}）。
 *
 * <p>必须与"仓储故障"区分开（Go 的 TokenStore 接口文档明确要求）：前者意味着
 * <b>需要用户重新授权</b>，后者是运维故障。<b>带外层包装也照样能被识别</b>——
 * 判定走异常链遍历，等价 Go 的 {@code errors.Is(err, ErrNoToken)}。</p>
 */
public class OAuthNoTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OAuthNoTokenException() {
        super("no token available");
    }

    /** 对照 Go {@code errors.Is}：异常链里出现本类即视为 ErrNoToken。 */
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
