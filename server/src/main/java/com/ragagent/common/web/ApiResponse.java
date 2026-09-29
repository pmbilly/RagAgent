package com.ragagent.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 成功响应信封：{@code {"success":true,"data":...}}；data 为空时省键
 * （{@link JsonInclude.Include#NON_NULL}）。错误响应由 GlobalExceptionHandler 统一产出。
 */
public record ApiResponse<T>(@JsonInclude(JsonInclude.Include.NON_NULL) boolean success,
                             @JsonInclude(JsonInclude.Include.NON_NULL) T data) {

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data);
    }
}
