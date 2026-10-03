package com.ragagent.storage.support;

/**
 * 调用方要求 {@code public} 模式，但凭据不允许。
 *
 * <p>调用方映射成 <b>403</b>：请求本身是合法的，不允许的是<b>授权范围</b>。
 * 用类型继承（本类是 {@link ResourceModeException} 的子类）与"取值不合法"的
 * 400 区分——<b>catch 子类在前</b>。</p>
 */
public class PublicModeForbiddenException extends ResourceModeException {

    public PublicModeForbiddenException(String message) {
        super(message);
    }
}
