package com.ragagent.sandbox.service;

import java.net.URI;

/**
 * 对照 Go {@code service/tenant_skill_source.go} 的 <b>纯逻辑切片</b>：定位符解析中
 * 不出网的分支。波 3 子批 4 的 golden 只钉两条：空 source 与 direct 归档 URL 的
 * SSRF 拒绝；registry（ClawHub/SkillHub/skills.sh）与 github/gitlab 抓取管线
 * （handoff JSON、重定向、限额）属<b>波 4 接缝</b>——解析层对这些 kind 直接抛
 * {@link TenantSkillService.SkillSourceInvalidException}（handler 升 400），
 * 不发任何出站请求。
 */
final class SkillSource {

    private SkillSource() {
    }

    /** 对照 {@code parsedSkillSource}（只保留本批消费的字段）。 */
    record Parsed(String kind, String directURL) {
    }

    /** 对照 {@code isDirectArchivePath}：zip/tgz/tar/md 结尾走 direct。 */
    static boolean isDirectArchivePath(String p) {
        String lower = p == null ? "" : p.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".tgz") || lower.endsWith(".tar.gz")
                || lower.endsWith(".tar") || lower.endsWith(".md");
    }

    /**
     * 对照 {@code parseSkillSource} 的切片：source 必填、URL 解析、scheme 白名单、
     * 默认主机的 direct 归档判定。registry/git 类定位符在解析成功前即按接缝拒绝
     * （Go 会继续走 fetchURL → SSRF 校验 → 抓取；dev 无网等价）。
     */
    static Parsed parse(String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) {
            throw invalid("source is required");
        }
        input = stripTrailingSlashes(input);
        if (input.contains("://")) {
            return parseURL(input);
        }
        throw invalid("only direct archive URLs are supported in this deployment");
    }

    private static String stripTrailingSlashes(String input) {
        while (input.endsWith("/")) {
            input = input.substring(0, input.length() - 1);
        }
        return input;
    }

    /** 对照 {@code parseSkillSourceURL}（default 主机分支之外全部接缝拒绝）。 */
    private static Parsed parseURL(String raw) {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid("only http(s) sources are allowed");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("github.com") || host.equals("www.github.com")
                || host.equals("codeload.github.com") || host.equals("gitlab.com")
                || host.equals("www.gitlab.com") || host.equals("skills.sh")
                || host.equals("www.skills.sh") || host.equals("clawhub.ai")
                || host.equals("www.clawhub.ai") || host.equals("clawhub.com")
                || host.equals("www.clawhub.com") || host.equals("skillhub.cn")
                || host.equals("www.skillhub.cn") || host.equals("api.skillhub.cn")) {
            throw invalid("only direct archive URLs are supported in this deployment");
        }
        if (isDirectArchivePath(uri.getRawPath())) {
            return new Parsed("direct", uri.toString());
        }
        throw invalid("only direct archive URLs are supported in this deployment");
    }

    private static TenantSkillService.SkillSourceInvalidException invalid(String detail) {
        return new TenantSkillService.SkillSourceInvalidException(
                TenantSkillService.SENTINEL_SKILL_SOURCE_INVALID + ": " + detail);
    }
}
