package com.ragagent.im.runtime;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 平台回调的一次 HTTP 交互（请求绑定/查询参数/请求头/响应写出的窄抽象）。
 *
 * <p>Java 的回调面是 Spring MVC，控制器把
 * HttpServletRequest/Response 包成 {@link Servlet} 实现交给适配器——适配器因此
 * 不依赖 servlet API，单测可用内存 fake。</p>
 */
public interface CallbackExchange {

    /** HTTP 方法（GET/POST）。 */
    String method();

    /** 查询参数（缺失返回 ""）。 */
    String query(String name);

    /** 请求头（缺失返回 ""）。 */
    String header(String name);

    /** 全部请求头（小写名）。 */
    Map<String, String> headers();

    /** 原始请求体字节。 */
    byte[] body();

    /** 写 JSON 响应。 */
    void json(int status, Object body);

    /** 写纯文本响应。 */
    void plain(int status, String contentType, String text);

    /** 已提交响应？（近似语义——重复写会被吞。） */
    boolean committed();

    /** servlet 实现层（ImCallbackController 装配用）。 */
    final class Servlet implements CallbackExchange {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private byte[] body;

        public Servlet(HttpServletRequest request, HttpServletResponse response) {
            this.request = request;
            this.response = response;
        }

        @Override
        public String method() {
            return request.getMethod();
        }

        @Override
        public String query(String name) {
            String v = request.getParameter(name);
            return v == null ? "" : v;
        }

        @Override
        public String header(String name) {
            return request.getHeader(name);
        }

        @Override
        public Map<String, String> headers() {
            Map<String, String> out = new LinkedHashMap<>();
            for (String name : java.util.Collections.list(request.getHeaderNames())) {
                out.put(name.toLowerCase(), request.getHeader(name));
            }
            return out;
        }

        @Override
        public byte[] body() {
            if (body == null) {
                try (var in = request.getInputStream()) {
                    body = in.readAllBytes();
                } catch (Exception e) {
                    body = new byte[0];
                }
            }
            return body;
        }

        @Override
        public void json(int status, Object body) {
            try {
                response.setStatus(status);
                response.setContentType("application/json; charset=utf-8");
                byte[] bytes = ImJson.encode(body).getBytes(StandardCharsets.UTF_8);
                response.setContentLength(bytes.length);
                response.getOutputStream().write(bytes);
            } catch (Exception ignored) {
                // 客户端断开等写失败：吞掉不上抛
            }
        }

        @Override
        public void plain(int status, String contentType, String text) {
            try {
                response.setStatus(status);
                response.setContentType(contentType);
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                response.setContentLength(bytes.length);
                response.getOutputStream().write(bytes);
            } catch (Exception ignored) {
                // 同上
            }
        }

        @Override
        public boolean committed() {
            return response.isCommitted();
        }
    }

    /** 最小 JSON 编码（键序=插入序；复用全局 GoMapSerializer 语义面）。 */
    final class ImJson {
        private ImJson() {
        }

        public static String encode(Object o) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                return mapper.writeValueAsString(o);
            } catch (Exception e) {
                return "{}";
            }
        }

        /** 便捷构造。 */
        public static Map<String, Object> h(Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                m.put((String) kv[i], kv[i + 1]);
            }
            return m;
        }

    }
}
