package com.ragagent.browserskill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * browserskill 控制器的 gin.H 响应助手。
 *
 * <p>本模块错误全部是 {@code {"error":"消息"}} 纯字符串信封（gin c.JSON 直写，
 * **不走** AppError 全局信封）；成功是 {@code {"success":true,"data":...}}——
 * gin.H 是 map，键按 encoding/json 字母序（data &lt; success；约定 §9 键序规则）。
 * Content-Type 对照 gin：{@code application/json; charset=utf-8}（带空格原样写）。 </p>
 */
final class GinJson {

    private GinJson() {}

    /** 对照 c.JSON(status, gin.H{"error": msg}) */
    static void error(HttpServletResponse response, int status, ObjectMapper mapper, String message)
            throws IOException {
        LinkedHashMap<Object, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        write(response, status, mapper, body);
    }

    /** 对照 c.JSON(status, gin.H{"data": ..., "success": true})——键字母序 data<success */
    static void dataSuccess(HttpServletResponse response, ObjectMapper mapper, Object data)
            throws IOException {
        LinkedHashMap<Object, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        write(response, 200, mapper, body);
    }

    private static void write(HttpServletResponse response, int status, ObjectMapper mapper,
                              Object body) throws IOException {
        response.setStatus(status);
        response.setHeader("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = mapper.writeValueAsBytes(body);
        response.setContentLength(bytes.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }
}
