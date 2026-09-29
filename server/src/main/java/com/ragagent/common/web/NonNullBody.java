package com.ragagent.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 @RequestBody 参数拒绝字面量 {@code null} 请求体（400「请求体不能为空」）。
 * 未标注的端点保持 JSON null → null 参数的缺省语义（部分端点以 null 体表示
 * 全零值绑定，是合法请求）。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface NonNullBody {
}
