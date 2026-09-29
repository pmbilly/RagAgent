package com.ragagent.common.web;

/**
 * 无 data 的操作确认响应：{@code {"message":"...","success":true}}。
 */
public record MessageResponse(String message, boolean success) {
}
