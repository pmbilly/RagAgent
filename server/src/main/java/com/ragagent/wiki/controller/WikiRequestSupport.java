package com.ragagent.wiki.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.wiki.domain.WikiFolderConflictException;
import com.ragagent.wiki.domain.WikiFolderNotEmptyException;
import com.ragagent.wiki.domain.WikiFolderNotFoundException;
import com.ragagent.wiki.domain.WikiPageNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ragagent.wiki.controller.WikiPageController.RawJsonError;

/**
 * WikiPageController 的静态解析与绑定助手：查询参数清洗、宽松整数解析、
 * 空白裁剪、错误文案与错误信封、raw-JSON 请求体绑定（必填校验文案复刻）。
 * 全部静态、零字段依赖；绑定三件以 ObjectMapper 首参传入
 * ——必须沿用 Spring 注入的 mapper（其 lenient 语义是行为的一部分）。
 */
final class WikiRequestSupport {

    private WikiRequestSupport() {}

    /**
     * catch-all 路径参数捕获值带前导 "/"，
     * 先剥掉再 TrimSpace。
     */
    static String getSlugParam(String raw) {
        if (raw == null) {
            return "";
        }
        String slug = raw.startsWith("/") ? raw.substring(1) : raw;
        return trimSpace(slug);
    }

    /**
     * 按 "/" 切分、逐段 TrimSpace、
     * 丢掉空段；整串为空时返回空列表（服务层同样视为"不过滤"）。
     */
    static List<String> parseWikiCategoryPath(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || trimSpace(raw).isEmpty()) {
            return out;
        }
        for (String part : raw.split("/", -1)) {
            String trimmed = trimSpace(part);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** 缺席与空值都返回 ""。 */
    static String q(HttpServletRequest request, String name) {
        String v = request.getParameter(name);
        return v == null ? "" : v;
    }

    /** 查询参数是否存在（区分"提供了空值"与"没提供"）。 */
    static boolean hasParam(HttpServletRequest request, String name) {
        return request.getParameterMap().containsKey(name);
    }

    /**
     * <b>存在即返回其值</b>（哪怕是空串），
     * 只有完全缺席才回落到默认值。
     */
    static String query(HttpServletRequest request, String name, String def) {
        return hasParam(request, name) ? q(request, name) : def;
    }

    /** 宽松整数解析：解析失败返回 0。 */
    static int atoi(String s) {
        Integer v = atoiOrNull(s);
        return v == null ? 0 : v;
    }

    /** 返回 null 表示解析失败（用于"解析成功才生效"的分支）。 */
    static Integer atoiOrNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 按原实现的空白集合裁剪
     * （Java 的 {@code String.trim()} 只认 &lt;= U+0020，会漏掉 NBSP 等）。
     */
    static String trimSpace(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end) {
            int cp = s.codePointAt(start);
            if (!isGoSpace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!isGoSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }

    /** 空白判定（Java 两个判定取并集才覆盖原实现的空白集合）。 */
    private static boolean isGoSpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    /** 异常文案（BizException 的 message 与原实现的错误文案格式逐字相同）。 */
    static String errText(RuntimeException e) {
        return e.getMessage() == null ? "" : e.getMessage();
    }

    /**
     * 统一错误文案格式：
     * {@code "error code: %d, error message: %s"}。
     */
    static String appErrorText(int code, String message) {
        return "error code: " + code + ", error message: " + message;
    }

    /** handler 直写错误信封：单键 {@code {"error":...}} map。 */
    static ResponseEntity<Map<String, Object>> rawError(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    /** 500 错误：{@code {"error": <异常消息>}} 形态。 */
    static RawJsonError internal(String message) {
        return new RawJsonError(HttpStatus.INTERNAL_SERVER_ERROR.value(), message);
    }

    /** 单键 {@code {"message":...}} 响应。 */
    static Map<String, Object> message(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", text);
        return body;
    }

    static long currentTenantId() {
        Long tenantId = TenantContext.currentTenantId();
        return tenantId == null ? 0L : tenantId;
    }

    static String sanitize(String value) {
        return LogSanitizer.sanitize(value);
    }

    /**
     * 文件夹/页面的 sentinel error → 状态码。
     * 其余错误一律 500，文案取异常消息。
     */
    static RawJsonError mapFolderError(RuntimeException e) {
        if (e instanceof WikiFolderNotFoundException || e instanceof WikiPageNotFoundException) {
            return new RawJsonError(HttpStatus.NOT_FOUND.value(), errText(e));
        }
        if (e instanceof WikiFolderConflictException || e instanceof WikiFolderNotEmptyException) {
            return new RawJsonError(HttpStatus.CONFLICT.value(), errText(e));
        }
        return internal(errText(e));
    }

    /**
     * 请求体解析：空 body → {@code EOF} 文案；否则解析。
     *
     * <p>先拿到 JsonNode 而不是直接绑到 DTO，是为了能在同一处复刻
     * 必填字段校验（Jackson 不做这类校验）。</p>
     */
    static JsonNode readJsonBody(ObjectMapper json, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(), "Invalid request body: EOF");
        }
        try {
            JsonNode node = json.readTree(rawBody);
            if (node == null || node.isNull()) {
                // "null" 请求体在原实现里是 no-op，各字段保持零值。
                // 用 null 节点继续走 required 校验会 NPE，这里换成一个空对象。
                return json.createObjectNode();
            }
            if (!node.isObject()) {
                throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                        "Invalid request body: json: cannot unmarshal non-object into Go value");
            }
            return node;
        } catch (RawJsonError e) {
            throw e;
        } catch (Exception e) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid request body: " + e.getMessage());
        }
    }

    /** 把已解析的 JSON 节点绑到 DTO。 */
    static <T> T toType(ObjectMapper json, JsonNode node, Class<T> type) {
        try {
            return json.treeToValue(node, type);
        } catch (Exception e) {
            throw new RawJsonError(HttpStatus.BAD_REQUEST.value(),
                    "Invalid request body: " + e.getMessage());
        }
    }

    /** {@link #readJsonBody} + {@link #toType} 的组合（无 required 字段的 DTO 用它）。 */
    static <T> T bind(ObjectMapper json, String rawBody, Class<T> type) {
        return toType(json, readJsonBody(json, rawBody), type);
    }

    /**
     * 复刻原实现的必填字段校验。
     *
     * <p>报错文案是
     * {@code Key: '<Struct>.<Field>' Error:Field validation for '<Field>' failed on the 'required' tag}
     * （这条文案对客户端可见，必须逐字一致），
     * 多个字段同时失败时用换行连接。
     * 匿名结构（无结构体名）的 Key 不带前缀（{@code Key: 'Status'}）。</p>
     *
     * @param structName 具名结构的名字；null / 空表示匿名结构
     * @param fields     字段的<b>声明序</b>（决定报错顺序）
     * @return 校验错误串；全部通过时返回 null
     */
    static String requiredFieldErrors(JsonNode node, String structName, String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            if (!isZeroValue(node.get(toJsonName(field)))) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String key = (structName == null || structName.isEmpty())
                    ? field : structName + "." + field;
            sb.append("Key: '").append(key)
                    .append("' Error:Field validation for '").append(field)
                    .append("' failed on the 'required' tag");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 字段名 → JSON 键（本模块涉及的字段都是单驼峰转蛇形，逐字列出避免猜错）。 */
    private static String toJsonName(String goField) {
        return switch (goField) {
            case "Slug" -> "slug";
            case "Version" -> "version";
            case "Status" -> "status";
            default -> goField;
        };
    }

    /** 必填校验的零值判定：缺失 / null / "" / 0 都算零值。 */
    private static boolean isZeroValue(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return true;
        }
        if (v.isTextual()) {
            return v.asText().isEmpty();
        }
        if (v.isNumber()) {
            return v.asDouble() == 0d;
        }
        if (v.isBoolean()) {
            return !v.asBoolean();
        }
        return false;
    }
}
