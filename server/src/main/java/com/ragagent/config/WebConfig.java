package com.ragagent.config;

import java.util.List;

import com.ragagent.auth.domain.TenantRole;
import com.ragagent.auth.filter.AuthFilter;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.UserService;
import com.ragagent.common.filter.RequestIdFilter;
import com.ragagent.common.web.RbacInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 对照 Go internal/router/router.go 的全局装配：
 * CORS → RequestID → (Language/Logger/Recovery 由 Spring 等价物承担) → Auth。
 * 错误处理：Go 的 Recovery/ErrorHandler 由 GlobalExceptionHandler + Spring 默认错误机制承担，
 * 响应契约由 golden 测试锁定。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TenantProperties tenantProperties;

    public WebConfig(TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
    }

    /** 对照 gin cors.Config：通配 Origin、显式头清单、MaxAge 12h */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Origin", "Content-Type", "Accept", "Authorization", "X-API-Key",
                "X-Request-ID", "X-Tenant-ID", "X-Embed-Session",
                "X-External-User-ID", "X-External-User-Token"));
        config.setExposedHeaders(List.of("Content-Length", "Access-Control-Allow-Origin"));
        config.setAllowCredentials(true);
        config.setMaxAge(12L * 3600);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(corsConfigurationSource()));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> bean = new FilterRegistrationBean<>(new RequestIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /** 对照 Go Auth 中间件（engine 全局，覆盖 /*） */
    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter(UserService userService,
                                                         TenantService tenantService,
                                                         TenantMemberService memberService,
                                                         TenantProperties tenantProperties) {
        FilterRegistrationBean<AuthFilter> bean =
                new FilterRegistrationBean<>(new AuthFilter(userService, tenantService, memberService, tenantProperties));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /**
     * 对照 Go router/rbac.go 的守卫矩阵（阶段 2：models + weknoracloud）。
     * 拦截器运行在 servlet filter（Auth）之后、controller 之前，顺序与 Go 中间件链一致。
     * 静态段（providers / weknoracloud/status）规则先于 /{id} 通配注册，等价 gin 静态优先。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RbacInterceptor rbac = new RbacInterceptor(tenantProperties);
        // /models 组（对照 RegisterModelRoutes）
        rbac.addRule("GET", "/api/v1/models", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/models/providers", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/models/weknoracloud/status", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/models/*/debug", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/models", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/models/*/credentials", TenantRole.ADMIN, true);
        rbac.addRule("DELETE", "/api/v1/models/*/credentials/*", TenantRole.ADMIN, true);
        rbac.addRule("PUT", "/api/v1/models/*", TenantRole.ADMIN, true);
        rbac.addRule("DELETE", "/api/v1/models/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/models/*", TenantRole.VIEWER, false);
        // weknoracloud（对照 RegisterWeKnoraCloudRoutes）
        rbac.addRule("POST", "/api/v1/weknoracloud/credentials", TenantRole.ADMIN, false);
        registry.addInterceptor(rbac).addPathPatterns("/api/v1/models/**", "/api/v1/weknoracloud/credentials");
    }
}
