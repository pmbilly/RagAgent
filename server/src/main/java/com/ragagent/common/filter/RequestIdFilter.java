package com.ragagent.common.filter;

import java.io.IOException;

import com.ragagent.common.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 对照 Go middleware.RequestID()：生成/透传 X-Request-ID，写入 MDC 与 TenantContext。
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rid = request.getHeader(HEADER);
        if (rid == null || rid.isBlank()) {
            rid = java.util.UUID.randomUUID().toString();
        }
        response.setHeader(HEADER, rid);
        TenantContext.setRequestId(rid);
        MDC.put("request_id", rid);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("request_id");
            TenantContext.clear();
        }
    }
}
