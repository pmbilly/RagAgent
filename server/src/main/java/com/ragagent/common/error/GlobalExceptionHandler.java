package com.ragagent.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 对照 Go middleware/error_handler.go：
 * - AppError → 其 HTTPCode + {"success": false, "error": {code, message, details}}
 * - 其他异常 → 500 + {"success": false, "error": {1007, "Internal server error"}}
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(BizException ex) {
        AppError e = ex.appError();
        return ResponseEntity.status(e.httpCode()).body(errorBody(e));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        log.error("unhandled exception", ex);
        AppError e = new AppError(ErrorCode.INTERNAL_SERVER.value(), "Internal server error", null, 500);
        return ResponseEntity.status(500).body(errorBody(e));
    }

    /**
     * 与 Go ErrorHandler 的 JSON 字节序一致。
     * 实测 Go 信封是 gin.H（map），encoding/json 对 map 键按字母序输出：
     * {"error":{"code":N,"details":...,"message":"..."},"success":false}
     * （2026-09-17 用运行中的 Go dev server 实测确认，修正了骨架期的插入序假设）
     */
    private Map<String, Object> errorBody(AppError e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", e.code());
        error.put("details", e.details());
        error.put("message", e.message());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("success", false);
        return body;
    }
}
