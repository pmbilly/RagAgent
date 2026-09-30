/**
 * 跨域基础设施（被 30 个包依赖，位置最底）：统一响应与异常（{@code web/}、{@code error/}）、
 * 安全拦截（{@link com.ragagent.common.security.SsrfGuard}、{@code RbacInterceptor}）、JSON 工厂（{@link com.ragagent.common.web.JsonMappers}）、
 * 上下文/加密/文本/时间等无状态工具。**本包不得依赖任何业务域**。
  */
package com.ragagent.common;
