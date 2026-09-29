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
     * Go handler 直写的纯字符串错误形态（{@code c.JSON(status, gin.H{"error": msg})}），
     * 状态码随异常携带——system admin 组的 promote/revoke/reset-password 等大量使用。
     * 与 {@link #handleGuardForbidden(GuardForbiddenException)} 同族（那边恒 403 且
     * 消息带 "Forbidden: " 前缀），这边按 Go 原文原样输出。
     */
    @ExceptionHandler(PlainErrorException.class)
    public ResponseEntity<String> handlePlainError(PlainErrorException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        String escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"");
        return ResponseEntity.status(ex.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"" + escaped + "\"}");
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


    // ── 参数校验异常 → 400 信封（details 为字段级中文文案，多条 "\n" 连接）──

    /** 请求体约束校验失败（@Valid DTO）与 @ModelAttribute 绑定失败。 */
    @ExceptionHandler(org.springframework.validation.BindException.class)
    public ResponseEntity<Map<String, Object>> handleBind(org.springframework.validation.BindException ex) {
        boolean pagination = ex.getTarget() instanceof com.ragagent.common.web.PageParams
                || ex.getBindingResult().getFieldErrors().stream()
                        .allMatch(fe -> "page".equals(fe.getField()) || "page_size".equals(fe.getField()));
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (org.springframework.validation.FieldError fe : ex.getBindingResult().getFieldErrors()) {
            lines.add(fe.getField() + ": " + translateConstraint(fe));
        }
        if (lines.isEmpty()) {
            lines.add("请求参数不合法");
        }
        AppError e = new AppError(ErrorCode.BAD_REQUEST.value(),
                pagination ? "分页参数不合法" : "请求参数不合法",
                String.join("\n", lines), 400);
        return ResponseEntity.status(400).body(errorBody(e));
    }

    /** 方法级参数校验失败（Spring 6.1 内建方法校验，如 @ModelAttribute record 上的约束）。 */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(
            org.springframework.web.method.annotation.HandlerMethodValidationException ex) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        ex.getAllValidationResults().forEach(r ->
                r.getResolvableErrors().forEach(err -> lines.add(extractField(err) + ": " + translateMessage(err.getDefaultMessage()))));
        if (lines.isEmpty()) {
            lines.add("请求参数不合法");
        }
        AppError e = new AppError(ErrorCode.BAD_REQUEST.value(), "请求参数不合法",
                String.join("\n", lines), 400);
        return ResponseEntity.status(400).body(errorBody(e));
    }

    /** 单个 query 变量类型不匹配（如 page=abc）。 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        String name = ex.getName();
        boolean pagination = "page".equals(name) || "page_size".equals(name);
        AppError e = new AppError(ErrorCode.BAD_REQUEST.value(),
                pagination ? "分页参数不合法" : "请求参数不合法",
                name + ": 类型不正确", 400);
        return ResponseEntity.status(400).body(errorBody(e));
    }

    /** 请求体不可读：空体 / 畸形 JSON / 字段类型错。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        String details = "请求体格式不正确";
        Throwable cause = ex.getCause();
        if (ex.getMessage() != null && ex.getMessage().contains("Required request body")) {
            details = "请求体不能为空";
        } else if (cause instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException mie
                && !mie.getPath().isEmpty()) {
            String field = mie.getPath().get(mie.getPath().size() - 1).getFieldName();
            if (field != null) {
                details = field + ": 类型不正确";
            }
        }
        AppError e = new AppError(ErrorCode.BAD_REQUEST.value(), "请求参数不合法", details, 400);
        return ResponseEntity.status(400).body(errorBody(e));
    }

    /** 约束违规文案的中文归一：自定义消息原样，框架默认英文翻译为统一措辞。 */
    private String translateConstraint(org.springframework.validation.FieldError fe) {
        String msg = fe.getDefaultMessage();
        if (msg == null) {
            return "不合法";
        }
        if (msg.contains("type mismatch") || msg.contains("Failed to convert")
                || msg.startsWith("Failed to convert")) {
            return "类型不正确";
        }
        return translateMessage(msg);
    }

    private String translateMessage(String msg) {
        if (msg == null) {
            return "不合法";
        }
        if (msg.contains("characters")) {
            return msg; // 自定义消息（如长度说明）原样保留
        }
        if (msg.equals("must not be blank") || msg.equals("must not be null") || msg.equals("must not be empty")) {
            return "不能为空";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("must be greater than or equal to (\\d+)").matcher(msg);
        if (m.find()) {
            return "必须不小于 " + m.group(1);
        }
        m = java.util.regex.Pattern.compile("must be less than or equal to (\\d+)").matcher(msg);
        if (m.find()) {
            return "必须不大于 " + m.group(1);
        }
        m = java.util.regex.Pattern.compile("size must be between (\\d+) and (\\d+)").matcher(msg);
        if (m.find()) {
            return "长度必须在 " + m.group(1) + "-" + m.group(2) + " 之间";
        }
        if (msg.startsWith("must match")) {
            return "格式不正确";
        }
        return msg;
    }

    private String extractField(org.springframework.context.MessageSourceResolvable err) {
        Object[] args = err.getArguments();
        if (args != null) {
            for (Object a : args) {
                if (a instanceof jakarta.validation.ConstraintViolation<?> cv
                        && cv.getPropertyPath() != null) {
                    String s = cv.getPropertyPath().toString();
                    int idx = s.lastIndexOf('.');
                    return idx < 0 ? s : s.substring(idx + 1);
                }
            }
        }
        String[] codes = err.getCodes();
        return codes != null && codes.length > 0 ? codes[codes.length - 1] : "参数";
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
