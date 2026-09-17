package com.ragagent.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
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

    /**
     * 路由守卫式 403（对照 Go 中间件的纯字符串形态 {@code {"error":"Forbidden: ..."}}）。
     *
     * <p>与 {@link BizException} 的 403 **形态不同**：后者是 AppError 信封。Go 里两种并存，
     * 按拒绝发生在中间件还是 handler 区分——控制器里做的所有权判定属于前者，
     * 详见 {@link GuardForbiddenException} 的类注释。</p>
     */
    @ExceptionHandler(GuardForbiddenException.class)
    public ResponseEntity<String> handleGuardForbidden(GuardForbiddenException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        String escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"");
        return ResponseEntity.status(403)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Forbidden: " + escaped + "\"}");
    }

    /**
     * Spring 6.1 对未映射路径抛 NoResourceFoundException（落到 handleOther 会变 500）。
     * 对照 gin 默认 404："404 page not found"（text/plain）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(404)
                .contentType(MediaType.TEXT_PLAIN)
                .body("404 page not found");
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
