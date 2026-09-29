package com.ragagent.wiki.filter;

import java.io.IOException;

import com.ragagent.common.web.RequestLocale;
import com.ragagent.wiki.service.WikiLanguageSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求级语言上下文：把"用户语言"喂给 {@link WikiLanguageSupport} 的 ThreadLocal，
 * 供 wiki 生成、摘要、问题生成等 prompt 插值使用（未接入时它们恒回落默认语言）。
 *
 * <p><b>当前只处理葡萄牙语</b>：仅当请求语言解析到 {@code pt}（{@code pt-BR}/{@code pt-PT}…）
 * 时才设置上下文；其他语言保持接入前的行为（回落 {@code WEKNORA_LANGUAGE} 或 {@code zh-CN}），
 * 避免在未验证的语言上改变生成语言。扩展到全语言时把
 * {@link RequestLocale#isPortuguese(String)} 换成"是否已支持"的判据即可。</p>
 *
 * <p>ThreadLocal 必须在本过滤器内清理（约定与 {@code RequestIdFilter} 同）：
 * 虚拟线程/线程池会复用线程，Go 的 ctx 随作用域消失而 Java 不会。</p>
 */
public class WikiLocaleFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String locale = RequestLocale.resolve(request.getHeader("Accept-Language"));
        boolean applied = RequestLocale.isPortuguese(locale);
        if (applied) {
            WikiLanguageSupport.setCurrentLocale(locale);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (applied) {
                WikiLanguageSupport.clearCurrentLocale();
            }
        }
    }
}
