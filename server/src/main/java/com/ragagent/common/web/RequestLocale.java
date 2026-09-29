package com.ragagent.common.web;

import java.util.Locale;

/**
 * 对照 Go {@code middleware/language.go}：请求 locale 的解析规则
 * （{@code WEKNORA_LANGUAGE} → {@code Accept-Language} 首个 tag → {@code zh-CN}）。
 *
 * <p>解析规则只此一处：agent 目录接口（{@code BuiltinAgentRegistry}）与
 * 请求级语言上下文（{@code WikiLocaleFilter}）共用它，避免两份实现漂移。</p>
 */
public final class RequestLocale {

    /** 兜底 locale（对照 Go 的 {@code "zh-CN"}）。 */
    public static final String FALLBACK = "zh-CN";

    private RequestLocale() {}

    /**
     * 对照 Go {@code middleware/language.go}：环境变量优先，其次是
     * {@code Accept-Language} 的首个 tag，都没有时回落 {@link #FALLBACK}。
     *
     * @param acceptLanguage 请求头原文，可为 {@code null}（取 {@code *} 语义即无语言偏好）
     */
    public static String resolve(String acceptLanguage) {
        String env = System.getenv("WEKNORA_LANGUAGE");
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        String lang = "";
        if (acceptLanguage != null && !acceptLanguage.isEmpty()) {
            String first = acceptLanguage.split(",", 2)[0].trim();
            lang = first.split(";", 2)[0].trim();
        }
        return lang.isEmpty() ? FALLBACK : lang;
    }

    /**
     * locale 是否指向葡萄牙语（{@code pt}、{@code pt-BR}、{@code pt-PT}…）。
     *
     * <p>按语言子标签判断（BCP-47 的 primary subtag），因此地区变体都算葡萄牙语，
     * 大小写不敏感（{@code pt-br} 也认）。</p>
     */
    public static boolean isPortuguese(String locale) {
        if (locale == null || locale.isEmpty()) {
            return false;
        }
        String tag = locale.split(",", 2)[0].split(";", 2)[0].trim();
        if (tag.isEmpty()) {
            return false;
        }
        String primary = tag.split("[-_]", 2)[0];
        return "pt".equals(primary.toLowerCase(Locale.ROOT));
    }
}
