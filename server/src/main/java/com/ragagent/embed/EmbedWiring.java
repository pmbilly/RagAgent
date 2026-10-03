package com.ragagent.embed;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.ragagent.embed.filter.EmbedAuthFilter;
import com.ragagent.embed.service.EmbedChannelService;

/**
 * embed 模块的组合根。
 *
 * <p>顺序：EmbedAuthFilter 排在 AuthFilter（HIGHEST+20）之后（HIGHEST+30）。
 * AuthFilter 对 {@code /api/v1/embed/} 前缀整体放行（embed 公开路由不走全局认证组），
 * 因此本过滤器是这些请求的**唯一**认证层。</p>
 */
@Configuration
public class EmbedWiring {

    @Bean
    public FilterRegistrationBean<EmbedAuthFilter> embedAuthFilter(
            EmbedChannelService embedService,
            com.ragagent.auth.service.TenantService tenantService,
            EmbedRateLimiter limiter) {
        FilterRegistrationBean<EmbedAuthFilter> bean =
                new FilterRegistrationBean<>(new EmbedAuthFilter(embedService, tenantService, limiter));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        bean.addUrlPatterns("/api/v1/embed/*");
        return bean;
    }

    /**
     * 容器级错误报文用纯文本（对照协议层拒绝的输出惯例；
     * 见 {@link GoStyleErrorReportValve} 类注释）。只接管"应用没写过响应体"的
     * 容器错误，Spring 的 JSON 错误契约不受影响。
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory>
            goStyleErrorReportValveCustomizer() {
        return factory -> factory.addContextCustomizers(sc -> {
            if (sc.getParent() instanceof org.apache.catalina.core.StandardHost host) {
                // 换掉 Tomcat 默认的 HTML 错误页阀（协议层错误用纯文本）
                host.setErrorReportValveClass(GoStyleErrorReportValve.class.getName());
            }
        });
    }
}
