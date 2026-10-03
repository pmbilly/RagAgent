package com.ragagent.retrieval.engine.doris;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;

/**
 * Doris Stream Load 客户端——partial update 行写入与 Stream Load 的 SSRF/重定向纪律。
 *
 * <p>语义要点：FE 收 PUT 后可能 307 重定向到 BE，基本认证头要跟着走；
 * 只在"同主机或显式在白名单里的目标"上转发凭据。</p>
 *
 * <p><b>实现说明</b>：① 不发 {@code Expect: 100-continue} 头（JDK
 * {@link HttpClient} 把该头列为禁设头；Doris 不依赖它，仅是提前拒收的优化）；
 * ② 只跟随 307/308 重定向
 * （保留方法与 body）——Stream Load 的实际路径就是 307，其余 3xx 由上层按非 2xx 报错；
 * ③ 重定向上限 10 次；④ 请求体按键字母序序列化——要求调用方传 {@code TreeMap} 或有序 map，
 * 由 {@code Legacy} 路径显式保证。</p>
 */
public final class DorisStreamLoadClient {

    private static final Logger log = LoggerFactory.getLogger(DorisStreamLoadClient.class);

    /** 单批 Stream Load 的 JSON body 上限（1 MiB 保守值）。 */
    static final int MAX_BATCH_BYTES = 1 << 20;

    /** 重定向上限。 */
    static final int MAX_REDIRECTS = 10;

    private final HttpClient http;
    private final String feHttpBase;
    private final String database;
    private final String username;
    private final String password;
    private final SsrfGuard guard;
    private final ObjectMapper mapper = new ObjectMapper();

    public DorisStreamLoadClient(String feHttpBase, String database, String username, String password,
                                 SsrfGuard guard) {
        this.feHttpBase = trimRightSlash(feHttpBase);
        this.database = database == null ? "" : database;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.guard = guard;
        // 不设整体请求超时：Stream Load 自带调用方上下文期限。
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 拼装某张表的 Stream Load HTTP 端点。 */
    String streamLoadUrl(String table) {
        return feHttpBase + "/api/" + pathEscape(database) + "/" + pathEscape(table)
                + "/_stream_load";
    }

    /**
     * 把若干行通过 Stream Load 的 partial update 模式写回
     * 目标表。{@code columns} 必须包含 UNIQUE KEY 列（即 "id"）。
     */
    public void partialUpdateRows(String table, List<String> columns,
                                  List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (List<Map<String, Object>> batch : chunkRows(rows, MAX_BATCH_BYTES)) {
            streamLoadOnce(table, columns, batch);
        }
    }

    /**
     * 按累积 JSON 体大小切分，每段不超过 maxBytes
     * （粗略估计：单行序列化字节 + 逗号位，header = "[" + "]" 两个字节）。
     */
    static List<List<Map<String, Object>>> chunkRows(List<Map<String, Object>> rows, int maxBytes) {
        List<List<Map<String, Object>>> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> curr = new ArrayList<>();
        int size = 0;
        final int header = 2;
        for (Map<String, Object> row : rows) {
            byte[] raw;
            try {
                raw = JSON.writeValueAsBytes(row);
            } catch (JsonProcessingException e) {
                // marshal 失败时把这一行单独成段，由 streamLoadOnce 再次 marshal 报错。
                if (!curr.isEmpty()) {
                    out.add(curr);
                }
                List<Map<String, Object>> single = new ArrayList<>();
                single.add(row);
                out.add(single);
                curr = new ArrayList<>();
                size = 0;
                continue;
            }
            int need = raw.length;
            if (!curr.isEmpty()) {
                need++;
            }
            if (size + need + header > maxBytes && !curr.isEmpty()) {
                out.add(curr);
                curr = new ArrayList<>();
                size = 0;
            }
            curr.add(row);
            size += need;
        }
        if (!curr.isEmpty()) {
            out.add(curr);
        }
        return out;
    }

    /** 复用一份静态 ObjectMapper（chunkRows 是静态方法）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 发出一次 Stream Load HTTP 请求。 */
    private void streamLoadOnce(String table, List<String> columns,
                               List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("marshal stream load body: " + e.getMessage(), e);
        }
        String url = streamLoadUrl(table);
        if (guard != null) {
            try {
                guard.validateURLForSSRF(url);
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "stream load URL blocked by SSRF validation: " + e.getMessage(), e);
            }
        }
        HttpResponse<byte[]> resp = sendFollowingTrustedRedirects(url, body, columns);
        handleResponse(table, resp);
    }

    /**
     * 发送并在"可信目标"上跟随 307/308（同主机或白名单
     * 目标才继续转发 Basic 凭据，其余目标的跨主机跳转直接拒绝）。
     */
    private HttpResponse<byte[]> sendFollowingTrustedRedirects(String url, byte[] body,
                                                              List<String> columns) {
        String currentUrl = url;
        for (int redirects = 0; ; redirects++) {
            HttpResponse<byte[]> resp;
            try {
                resp = http.send(buildRequest(currentUrl, body, columns),
                        HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                throw new IllegalStateException("stream load HTTP: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("stream load HTTP: interrupted", e);
            }
            int status = resp.statusCode();
            if (status != 307 && status != 308) {
                return resp;
            }
            String location = resp.headers().firstValue("location").orElse("");
            if (location.isEmpty() || redirects >= MAX_REDIRECTS) {
                return resp;
            }
            String target = URI.create(currentUrl).resolve(location).toString();
            String sourceHost = URI.create(currentUrl).getHost();
            String targetHost = URI.create(target).getHost();
            if (!sameHost(sourceHost, targetHost)
                    && !(guard != null && guard.isWhitelisted(targetHost))) {
                throw new IllegalStateException("stream load redirect blocked: target host \""
                        + targetHost + "\" is not trusted to receive credentials");
            }
            if (guard != null) {
                try {
                    guard.validateURLForSSRF(target);
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "stream load URL blocked by SSRF validation: " + e.getMessage(), e);
                }
            }
            currentUrl = target;
        }
    }

    private HttpRequest buildRequest(String url, byte[] body, List<String> columns) {
        String basic = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/json")
                .header("format", "json")
                .header("strip_outer_array", "true")
                .header("partial_columns", "true")
                .header("columns", String.join(",", columns))
                .header("merge_type", "APPEND")
                .build();
    }

    private void handleResponse(String table, HttpResponse<byte[]> resp) {
        String raw = new String(resp.body(), StandardCharsets.UTF_8);
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "stream load HTTP " + resp.statusCode() + ": " + raw);
        }
        StreamLoadResponse result;
        try {
            result = mapper.readValue(raw, StreamLoadResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("decode stream load response: " + e.getMessage()
                    + " (raw=" + raw + ")", e);
        }
        String status = result.status() == null ? "" : result.status();
        if ("Success".equals(status) || "Publish Timeout".equals(status)) {
            log.info("[Doris] Stream load {} OK: rows={}, loaded={}, label={}",
                    table, result.numberTotalRows(), result.numberLoadedRows(), result.label());
            return;
        }
        throw new IllegalStateException("stream load failed: status=" + status
                + " msg=" + result.message() + " err_url=" + result.errorUrl());
    }

    /** Stream Load 响应体（只保留上游消费得到的字段，未知键容忍）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StreamLoadResponse(
            @JsonProperty("Label") String label,
            @JsonProperty("Status") String status,
            @JsonProperty("Message") String message,
            @JsonProperty("NumberTotalRows") Long numberTotalRows,
            @JsonProperty("NumberLoadedRows") Long numberLoadedRows,
            @JsonProperty("ErrorURL") String errorUrl) {
    }

    static boolean sameHost(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    static String trimRightSlash(String s) {
        String out = s == null ? "" : s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** 路径段转义（表名/库名受标识符校验约束，这里只做保守转义）。 */
    static String pathEscape(String segment) {
        return URLEncoder.encode(segment == null ? "" : segment, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
