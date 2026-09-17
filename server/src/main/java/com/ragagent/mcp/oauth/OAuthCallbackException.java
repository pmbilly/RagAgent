package com.ragagent.mcp.oauth;

/**
 * 回调处理失败，<b>并携带"即便失败也要把浏览器弹回去"的两个值</b>（对照 Go
 * {@code OAuthManager.CompleteAuthorization} 的
 * {@code (frontendRedirect, serviceID string, err error)} 三返回值签名）。
 *
 * <p>Go 用多返回值同时给出"部分结果 + 错误"；Java 没有这种形态，故把两个部分结果
 * 挂到异常上。<b>两者的取值时机完全一致</b>：
 * <ul>
 *   <li>{@code state} 消费失败时，连 serviceID 都还不知道 → 两者都是空串
 *       （Go 返回 {@code "", "", err}）→ 控制器回落到默认前端地址 {@code "/"}；</li>
 *   <li>{@code state} 消费成功之后的所有失败，两者都已就绪 → 控制器仍能带
 *       {@code serviceID} 去回收旧连接。</li>
 * </ul>
 */
public class OAuthCallbackException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String frontendRedirect;
    private final String serviceId;

    public OAuthCallbackException(String frontendRedirect, String serviceId,
                                  String message, Throwable cause) {
        super(message, cause);
        this.frontendRedirect = frontendRedirect == null ? "" : frontendRedirect;
        this.serviceId = serviceId == null ? "" : serviceId;
    }

    public String frontendRedirect() {
        return frontendRedirect;
    }

    public String serviceId() {
        return serviceId;
    }
}
