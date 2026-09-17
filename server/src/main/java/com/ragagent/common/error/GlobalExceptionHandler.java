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

    /** 与 Go ErrorHandler 的 JSON 字段顺序一致：success → error{code, message, details} */
    private Map<String, Object> errorBody(AppError e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", e.code());
        error.put("message", e.message());
        error.put("details", e.details());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", error);
        return body;
    }
}
