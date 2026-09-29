package com.ragagent.common.web;

/**
 * 带 data 与提示消息的成功响应：{@code {"data":...,"message":"...","success":true}}
 * （异步受理/操作确认类端点的既有契约形状）。
 */
public record DataMessageResponse<T>(T data, String message, boolean success) {

    public static <T> DataMessageResponse<T> of(T data, String message) {
        return new DataMessageResponse<>(data, message, true);
    }
}
