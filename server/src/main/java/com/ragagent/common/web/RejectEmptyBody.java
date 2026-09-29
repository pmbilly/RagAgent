package com.ragagent.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 @RequestBody 参数拒绝 0 字节空请求体（400「请求体不能为空」），与
 * {@code required = false} 组合使用：空体由 RequestBodyAdvice 在此拦截，而字面量
 * {@code null} 仍按零值绑定放行（null = 无变更语义）。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RejectEmptyBody {
}
