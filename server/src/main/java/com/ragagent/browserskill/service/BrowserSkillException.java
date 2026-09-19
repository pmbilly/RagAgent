package com.ragagent.browserskill.service;

/**
 * browserskill 的普通业务错误（对照 Go 的 {@code errors.New} 返回值：
 * 消息即契约——这些字符串逐字出现在 HTTP 响应与 internal RPC 的 error 字段里，
 * 见 BrowserSkillManager 各调用点的固定文案）。刻意不继承
 * BizException：本模块的错误**不走** AppError 信封（全部是
 * {"error":"..."} 纯字符串或 http.Error 纯文本），由控制器直接映射状态码。
 */
public class BrowserSkillException extends RuntimeException {

    public BrowserSkillException(String message) {
        super(message);
    }
}
