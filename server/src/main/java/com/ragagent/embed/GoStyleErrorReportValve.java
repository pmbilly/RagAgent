package com.ragagent.embed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

/**
 * 容器级错误报文（**协议层拒绝**输出纯文本
 * {@code "<status> <message>"}：例如畸形请求头行 → body 恰为
 * {@code "400 Bad Request"}，见 golden emb-pub-load-badvisitor）。
 *
 * <p>通过 {@code host.setErrorReportValveClass} 替换 Tomcat 默认的 HTML 错误页阀
 * （注册见 {@link EmbedWiring}）。只接管「响应体一个字节都没写过」的错误——
 * 即容器自己报错（HTTP 解析器拒绝、sendError 无人接手）的场景；凡是应用写了
 * 响应体的错误（GlobalExceptionHandler / Boot /error 的 JSON），本阀一律不碰，
 * 不影响任何既有契约。</p>
 */
public class GoStyleErrorReportValve extends ErrorReportValve {

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        if (response.isCommitted() || response.getStatus() < 400) {
            return;
        }
        if (response.getContentWritten() > 0) {
            // 应用已写过响应体（正常业务错误）——不接管
            return;
        }
        String message = response.getMessage() == null ? "" : response.getMessage();
        if (message.isEmpty()) {
            // Tomcat 的 status line 文案在协议层拒绝时尚未落到 message；
            // 用标准 reason phrase 兜底。
            try {
                message = org.springframework.http.HttpStatus.valueOf(response.getStatus()).getReasonPhrase();
            } catch (IllegalArgumentException ignored) {
                message = "";
            }
        }
        String body = response.getStatus() + (message.isEmpty() ? "" : " " + message);
        try {
            response.setContentType("text/plain; charset=utf-8");
            response.setCharacterEncoding("utf-8");
            response.setContentLength(body.getBytes(StandardCharsets.UTF_8).length);
            response.getWriter().write(body);
            response.flushBuffer();
        } catch (IOException | IllegalStateException ignored) {
            // 流已不可写：交给调用方按原样收口
        }
    }
}
