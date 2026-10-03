package com.ragagent.mcp.oauth;

/**
 * 回调处理失败，<b>并携带"即便失败也要把浏览器弹回去"的两个值</b>。
 *
 * <p>部分结果直接挂到异常上，调用方能区分失败发生的阶段：
 * <ul>
 *   <li>{@code state} 消费失败时，连 serviceID 都还不知道 → 两者都是空串
 *       → 控制器回落到默认前端地址 {@code "/"}；</li>
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
