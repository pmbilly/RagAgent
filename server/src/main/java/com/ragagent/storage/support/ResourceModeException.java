package com.ragagent.storage.support;

/**
 * {@code resource_urls} 的取值不合法。
 * 调用方映射成 <b>400</b>——让集成方看见自己的笔误，而不是悄悄收到 handle。
 */
public class ResourceModeException extends RuntimeException {

    public ResourceModeException(String message) {
        super(message);
    }
}
