package com.ragagent.auth.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.web.GoJsonBindError;

/**
 * 租户目录的 Go 绑定错误形态助手（自 {@link TenantCatalogController} 机械搬出，全静态）：
 * 请求绑定 mapper、ShouldBindJSON 等价绑定（EOF/语法/类型错误 → Go 风格文案）、
 * validator 与 UnmarshalTypeError 文案复刻、Unicode trim。四簇协作者共用。
 */
final class TenantBindSupport {

    /** 请求绑定 mapper：Go json.Unmarshal 忽略未知字段（FAIL_ON_UNKNOWN off）；
     *  JavaTimeModule 供全字段路径（types.Tenant 含 created_at 等）往返 */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    /** Go json.Decoder 的值种别（UnmarshalTypeError 文案用）。 */
    static String goJsonKind(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isTextual()) return "string";
        if (node.isBoolean()) return "bool";
        if (node.isArray()) return "array";
        if (node.isObject()) return "object";
        return "number";
    }

    /** string 字段类型检查；违规返回 Go UnmarshalTypeError 原文，否则 null。 */
    static String goStringField(com.fasterxml.jackson.databind.JsonNode root, String field) {
        com.fasterxml.jackson.databind.JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.isTextual()) {
            return null;
        }
        return "json: cannot unmarshal " + goJsonKind(node)
                + " into Go struct field updateTenantRequest." + field + " of type string";
    }
    // ── 绑定与错误形态（对照 AuthController 的既有模式） ─────────────────────

    /**
     * 对照 c.ShouldBindJSON：空 body → details "EOF"；语法/类型错误 →
     * Go 风格文案（{@link GoJsonBindError}）。body 是 JSON null 字面量时
     * Go 做零值绑定不报错——Jackson readValue 返回 null，调用方按零值处理。
     */
    static <T> T bindBody(String rawBody, Class<T> type, String message) {
        if (rawBody == null || rawBody.isBlank()) {
            throw invalidParams(message, "EOF");
        }
        try {
            return MAPPER.readValue(rawBody, type);
        } catch (Exception e) {
            throw invalidParams(message, GoJsonBindError.message(rawBody, e.getMessage()));
        }
    }

    static String bindingError(String structName, String field, String tag) {
        String key = structName == null || structName.isEmpty() ? field : structName + "." + field;
        return "Key: '" + key + "' Error:Field validation for '" + field
                + "' failed on the '" + tag + "' tag";
    }

    /** 对照 NewValidationError(message).WithDetails(err.Error()) */
    static BizException invalidParams(String message, String details) {
        return new BizException(AppError.validation(message).withDetails(details));
    }

    /** Go strings.TrimSpace 等价（Unicode 空白） */
    static String trimGo(String s) {
        return s == null ? "" : s.strip();
    }
}
