package com.ragagent.common.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成功响应统一形态（对照 Go handler 的 {"data": ..., "success": true}）。
 */
public record R<T>(T data, boolean success) {

    public static <T> R<T> ok(T data) {
        return new R<>(data, true);
    }

    /** 字段顺序：data → success（与 Go gin.H 序列化顺序一致） */
    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }
}
