package com.ragagent.agent.support;

/**
 * 无头浏览器渲染接缝（对照 Go {@code renderWithChromium}，chromedp）。
 *
 * <p><b>降级备案</b>：chromedp 无 Java 等价物（Selenium/Playwright 需要外部二进制，
 * 本项目不允许新增依赖）。默认实现 {@link #UNAVAILABLE} 恒失败——与 Go 里
 * "browser unavailable" 分支同形（SPA 页面最终报 empty_content，对照 Go 测试
 * TestFetcherReturnsErrorWhenBrowserFallbackFailsOnSPA）。恢复无头渲染只需提供
 * 本接口实现（读 target 的 URL 渲染出最终 HTML 字符串）。</p>
 */
@FunctionalInterface
public interface BrowserRenderer {

    BrowserRenderer UNAVAILABLE = target -> {
        throw new IllegalStateException("chromium render failed: browser unavailable");
    };

    /**
     * 渲染目标页并返回最终 HTML。
     *
     * @throws RuntimeException 渲染失败（对照 Go 的 error 返回）
     */
    String render(String url);
}
