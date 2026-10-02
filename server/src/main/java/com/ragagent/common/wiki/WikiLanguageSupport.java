package com.ragagent.common.wiki;

import java.util.List;
import java.util.Locale;

/**
 * prompt 语言的解析与命名。
 *
 * <h2>为什么 wiki ingest 非要它不可</h2>
 * <p>wiki 工作会从后台路径（克隆/移动、重解析、内部重试）入队，而那些路径<b>从不</b>
 * 经过 HTTP 语言中间件。在那里持久化一个空 locale 会让整篇文档的语言丢失——
 * worker 是从排队的 op 解析 prompt 语言的（{@code WikiPendingOp.Language}）。</p>
 *
 * <h2>locale 的线程本地承载</h2>
 * <p>当前请求的 locale 由语言中间件（或调用方）经 {@link #setCurrentLocale} 在请求入口
 * 设置；线程本地，不跨虚拟线程传递，
 * 因此<b>入队时就要把 locale 落进 op 载荷</b>。</p>
 *
 * <p>未接语言中间件时，{@link #languageFromContextOrDefault} 恒返回
 * {@link #defaultLanguage()}（{@code WEKNORA_LANGUAGE} 或 {@code zh-CN}）——
 * <b>绝不是空串</b>。</p>
 */
public final class WikiLanguageSupport {

    private WikiLanguageSupport() {}

    /** 默认语言兜底值 */
    public static final String FALLBACK_LANGUAGE = "zh-CN";

    /** 当前请求 locale 的线程本地承载 */
    private static final ThreadLocal<String> CURRENT_LOCALE = new ThreadLocal<>();

    // ═══════════════════════════════════════════════════════════════
    // locale 上下文
    // ═══════════════════════════════════════════════════════════════

    /** 设置当前请求的 locale（空值 = 清除）。 */
    public static void setCurrentLocale(String locale) {
        if (locale == null || locale.isEmpty()) {
            CURRENT_LOCALE.remove();
            return;
        }
        CURRENT_LOCALE.set(locale);
    }

    /** 清除。必须显式清，否则污染线程池。 */
    public static void clearCurrentLocale() {
        CURRENT_LOCALE.remove();
    }

    /** 当前线程的 locale；未设置为空串。 */
    public static String languageFromContext() {
        String v = CURRENT_LOCALE.get();
        return v == null ? "" : v;
    }

    // ═══════════════════════════════════════════════════════════════
    // 解析
    // ═══════════════════════════════════════════════════════════════

    /**
     * 部署默认语言的原始值（{@code WEKNORA_LANGUAGE}）——**启动期快照**。
     *
     * <p>本类是纯静态工具（17 处调用点散布在 wiki/session/agent.management），没有 Spring 装配点，
     * 故值由 {@code config.RuntimeSnapshotWiring} 在启动期写入一次（B6 批 6）；
     * <b>只允许装配层调用 install</b>，运行期不得改写（那是状态不是配置）。</p>
     */
    private static volatile String configuredLanguage = "";

    /** 启动期安装默认语言（{@code null}/空白 → 回落 {@code zh-CN}，见 {@link #defaultLanguage()}）。 */
    public static void installLanguage(String raw) {
        configuredLanguage = raw == null ? "" : raw;
    }

    /** {@code WEKNORA_LANGUAGE} 的 trim 值，未设则空串。 */
    public static String envLanguage() {
        return configuredLanguage.trim();
    }

    /**
     * 默认语言：读 {@code WEKNORA_LANGUAGE}；未设回落
     * {@code "zh-CN"}。
     */
    public static String defaultLanguage() {
        String env = envLanguage();
        return env.isEmpty() ? FALLBACK_LANGUAGE : env;
    }

    /**
     * 显式 locale 优先 → 线程 locale → 默认语言。<b>永不返回空串</b>。
     */
    public static String resolveLanguage(String locale) {
        String trimmed = GoStrings.trimSpace(locale == null ? "" : locale);
        if (!trimmed.isEmpty()) {
            return trimmed;
        }
        String contextLocale = languageFromContext();
        if (contextLocale != null && !contextLocale.isEmpty()) {
            return contextLocale;
        }
        return defaultLanguage();
    }

    /**
     * 把 locale 渲染成 prompt 模板
     * 插值用的人类可读名称（如 {@code "Chinese (Simplified)"}）。
     *
     * <p>对<b>已经解析过</b>的名称是幂等的：{@link #localeName} 会把未知值原样透传，
     * 因此重复解析一个显示名会原样返回它。</p>
     */
    public static String resolveLanguageName(String locale) {
        return localeName(resolveLanguage(locale));
    }

    /**
     * 把 locale 持久化到
     * 异步任务载荷时用它，下游 worker 因此绝不会继承一个空语言。
     */
    public static String languageFromContextOrDefault() {
        return resolveLanguage("");
    }

    /** prompt 用的人类可读语言名。 */
    public static String languageNameFromContext() {
        return resolveLanguageName("");
    }

    /**
     * locale 码 → 人类可读名。
     * 未知 locale <b>原样返回</b>。
     */
    public static String localeName(String locale) {
        if (locale == null) {
            return "";
        }
        return switch (locale) {
            case "zh-CN", "zh", "zh-Hans" -> "Chinese (Simplified)";
            case "zh-TW", "zh-HK", "zh-Hant" -> "Chinese (Traditional)";
            case "en-US", "en", "en-GB" -> "English";
            case "ko-KR", "ko" -> "Korean";
            case "ja-JP", "ja" -> "Japanese";
            case "ru-RU", "ru" -> "Russian";
            case "fr-FR", "fr" -> "French";
            case "de-DE", "de" -> "German";
            case "es-ES", "es" -> "Spanish";
            case "pt-BR", "pt" -> "Portuguese";
            default -> locale;
        };
    }

    /**
     * 一个页面会聚合多篇文档的贡献，因此语言取<b>第一个携带语言</b>的更新；
     * 都没有则回落到请求语言（{@code languageNameFromContext}）。
     */
    public static String resolveSlugUpdateLanguage(List<SlugUpdate> updates) {
        if (updates != null) {
            for (SlugUpdate u : updates) {
                if (u != null && u.getLanguage() != null && !u.getLanguage().isEmpty()) {
                    return u.getLanguage();
                }
            }
        }
        return languageNameFromContext();
    }

    /** locale 归一化（诊断/比较用）。 */
    static String normalizeLocale(String locale) {
        return locale == null ? "" : locale.toLowerCase(Locale.ROOT);
    }
}
