package com.ragagent.storage.support;

/**
 * 调用方要求 {@code public} 模式，但凭据不允许（对照 Go 的哨兵错误
 * {@code storageurl.ErrPublicModeForbidden}）。
 *
 * <p>调用方映射成 <b>403</b>：请求本身是合法的，不允许的是<b>授权范围</b>。
 * Go 侧靠 {@code errors.Is(err, ErrPublicModeForbidden)} 分辨这两种错误，
 * Java 侧用类型继承表达同一件事——<b>catch 子类在前</b>。</p>
 */
public class PublicModeForbiddenException extends ResourceModeException {

    public PublicModeForbiddenException(String message) {
        super(message);
    }
}
