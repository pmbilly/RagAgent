package com.ragagent.wiki.service;

import java.util.List;
import java.util.Locale;
import com.ragagent.common.web.RequestLocale;

/**
 * prompt 语言的解析与命名（对照 Go internal/types/context_helpers.go 的
 * {@code DefaultLanguage} / {@code EnvLanguage} / {@code ResolveLanguage} /
 * {@code ResolveLanguageName} / {@code LanguageFromContextOrDefault} /
 * {@code LanguageNameFromContext} / {@code LanguageLocaleName}）。
 *
 * <h2>为什么 wiki ingest 非要它不可</h2>
 * <p>wiki 工作会从后台路径（克隆/移动、重解析、内部重试）入队，而那些路径<b>从不</b>
 * 经过 HTTP 语言中间件。在那里持久化一个空 locale 会让整篇文档的语言丢失——
 * worker 是从排队的 op 解析 prompt 语言的（{@code WikiPendingOp.Language}）。</p>
 *
 * <h2>context → ThreadLocal 的映射</h2>
 * <p>Go 从 {@code ctx} 的 {@code types.LanguageContextKey} 取当前请求的 locale。
 * Java 侧用 {@link #setCurrentLocale} 暴露同一个位置，由语言中间件（或调用方）
 * 在请求入口设置；线程本地，不跨虚拟线程传递（约定文档 §5），
 * 因此<b>入队时就要把 locale 落进 op 载荷</b>——这正是 Go 的
 * {@code newWikiIngestPendingOp} 做的事。</p>
 *
 * <p>未接语言中间件时，{@link #languageFromContextOrDefault} 恒返回
 * {@link #defaultLanguage()}（{@code WEKNORA_LANGUAGE} 或 {@code zh-CN}），
 * 与 Go 在无 ctx 语言时的行为一致——<b>绝不是空串</b>。</p>
 */
public final class WikiLanguageSupport {

    private WikiLanguageSupport() {}

    /** 对照 Go {@code DefaultLanguage} 的兜底值 */
    public static final String FALLBACK_LANGUAGE = "zh-CN";

    /** 对照 Go {@code types.LanguageContextKey} 的线程本地对应物 */
    private static final ThreadLocal<String> CURRENT_LOCALE = new ThreadLocal<>();

    // ═══════════════════════════════════════════════════════════════
    // locale 上下文
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go {@code types.WithLanguage(ctx, locale)}：设置当前请求的 locale。 */
    public static void setCurrentLocale(String locale) {
        if (locale == null || locale.isEmpty()) {
            CURRENT_LOCALE.remove();
            return;
        }
        CURRENT_LOCALE.set(locale);
    }

    /** 清除。Java 必须显式清，否则污染线程池（Go 随 ctx 生命周期自动消失）。 */
    public static void clearCurrentLocale() {
        CURRENT_LOCALE.remove();
    }

    /** 对照 Go {@code types.LanguageFromContext}：返回 (locale, 是否存在)。 */
    public static String languageFromContext() {
        String v = CURRENT_LOCALE.get();
        return v == null ? "" : v;
    }

    // ═══════════════════════════════════════════════════════════════
    // 解析
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go {@code EnvLanguage}：{@code WEKNORA_LANGUAGE} 的 trim 值，未设则空串。 */
    public static String envLanguage() {
        String v = System.getenv("WEKNORA_LANGUAGE");
        return v == null ? "" : v.trim();
    }

    /**
     * 对照 Go {@code DefaultLanguage}：读 {@code WEKNORA_LANGUAGE}；未设回落
     * {@code "zh-CN"}。
     */
    public static String defaultLanguage() {
        String env = envLanguage();
        return env.isEmpty() ? FALLBACK_LANGUAGE : env;
    }

    /**
     * 对照 Go {@code ResolveLanguage}（context_helpers.go L313-321）：
     * 显式 locale 优先 → ctx locale → 默认语言。<b>永不返回空串</b>。
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
     * 对照 Go {@code ResolveLanguageName}（L328-330）：把 locale 渲染成 prompt 模板
     * 插值用的人类可读名称（如 {@code "Chinese (Simplified)"}）。
     *
     * <p>对<b>已经解析过</b>的名称是幂等的：{@link #localeName} 会把未知值原样透传，
     * 因此重复解析一个显示名会原样返回它。</p>
     */
    public static String resolveLanguageName(String locale) {
        return localeName(resolveLanguage(locale));
    }

    /**
     * 对照 Go {@code LanguageFromContextOrDefault}（L335-337）：把 locale 持久化到
     * 异步任务载荷时用它，下游 worker 因此绝不会继承一个空语言。
     */
    public static String languageFromContextOrDefault() {
        return resolveLanguage("");
    }

    /** 对照 Go {@code LanguageNameFromContext}（L342-344）：prompt 用的人类可读语言名。 */
    public static String languageNameFromContext() {
        return resolveLanguageName("");
    }

    /**
     * 对照 Go {@code LanguageLocaleName}（L347-370）：locale 码 → 人类可读名。
     * 未知 locale <b>原样返回</b>（Go 的 default 分支）。
     */
    public static String localeName(String locale) {
        if (locale == null) {
            return "";
        }
        // 葡萄牙语：Go 只列了 pt-BR/pt，但请求语言判定（RequestLocale.isPortuguese）
        // 按语言子标签识别，两侧必须一致——否则 pt-PT 用户会拿到 "Write in pt-PT" 这类
        // 未本地化的插值。此处按 primary subtag 兜住所有 pt-* 变体。
        if (RequestLocale.isPortuguese(locale)) {
            return "Portuguese";
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
     * 对照 Go {@code resolveSlugUpdateLanguage}（wiki_ingest_batch.go L1684-1691）：
     * 一个页面会聚合多篇文档的贡献，因此语言取<b>第一个携带语言</b>的更新；
     * 都没有则回落到请求语言（{@code LanguageNameFromContext}）。
     *
     * <p>Go 把本函数放在 {@code wiki_ingest_batch.go}（reduce 阶段用）。Java 侧放在
     * 语言工具里，因为它纯粹是语言解析的一部分，
     * 且 {@code wiki_ingest_language_test.go} 的对等测试需要它。</p>
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

    /** 对照 Go {@code strings.ToLower} 的 locale 归一化（诊断/比较用）。 */
    static String normalizeLocale(String locale) {
        return locale == null ? "" : locale.toLowerCase(Locale.ROOT);
    }
}
