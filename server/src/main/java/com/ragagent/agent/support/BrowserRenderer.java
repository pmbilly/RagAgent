package com.ragagent.agent.support;

/**
 * 无头浏览器渲染接缝。
 *
 * <p><b>降级备案</b>：无 Java 侧等价的无头浏览器驱动（Selenium/Playwright 需要外部二进制，
 * 本项目不允许新增依赖）。默认实现 {@link #UNAVAILABLE} 恒失败——即
 * "browser unavailable" 分支（SPA 页面最终报 empty_content）。恢复无头渲染只需提供
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
     * @throws RuntimeException 渲染失败
     */
    String render(String url);
}
