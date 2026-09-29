package com.ragagent.common.web;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;

import java.lang.reflect.Type;

/**
 * 拒绝字面量 {@code null} 请求体：JSON "null" 反序列化为 null 参数，校验框架对其
 * 不触发——这里统一转 400「请求体不能为空」。空请求体走 HttpMessageNotReadableException
 * 处理器（handleEmptyBody 返回 null 维持缺省语义）。
 */
@RestControllerAdvice
public class NonNullRequestBodyAdvice implements RequestBodyAdvice {

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return inputMessage;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (body == null) {
            throw new BizException(AppError.badRequest("请求参数不合法").withDetails("请求体不能为空"));
        }
        return body;
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }
}
