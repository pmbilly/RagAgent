package com.ragagent.sandbox.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 对照 Go {@code service/tenant_skill_source.go} 的定位符解析切片
 * （parseSkillSource 及其全部辅助，L169-567）：把一次粘贴映射到唯一的 kind，
 * 不出网探测。波 4 起全部 kind 都交给 {@link SkillSourceFetcher} 真实抓取。
 *
 * <p>错误消息是 golden 契约：每个拒绝 = {@code "skill source is invalid: <细节>"}
 * （Go 的 {@code fmt.Errorf("%w: ...", ErrSkillSourceInvalid)} 形态）。</p>
 */
final class SkillSource {

    static final String DEFAULT_SKILL_REGISTRY_ORIGIN = "https://clawhub.ai";

    static final String KIND_REGISTRY = "registry";
    static final String KIND_SKILLS_SH = "skills-sh";
    static final String KIND_GITHUB = "github";
    static final String KIND_GITLAB = "gitlab";
    static final String KIND_DIRECT = "direct";

    private static final String SKILLS_SH_REF_PREFIX = "skills-sh:";

    private static final String SKILL_HUB_CN_API_ORIGIN = "https://api.skillhub.cn";

    /** 对照 {@code semverLike}。 */
    private static final Pattern SEMVER_LIKE =
            Pattern.compile("^v?\\d+\\.\\d+(\\.\\d+)?([.-][0-9A-Za-z.-]+)?$");

    private SkillSource() {
    }

    /** 对照 {@code parsedSkillSource}（全字段）。 */
    record Parsed(String kind, String registry, String slug, String version, String owner,
                  String repo, String ref, String subdir, String directURL) {
    }

    // ── parseSkillSource ────────────────────────────────────────────────

    /**
     * 对照 {@code parseSkillSource}：不出网猜测——owner/slug 既是 ClawHub 定位符也是
     * GitHub 仓库名，所以无 URL 的斜杠与前导 @ 一律拒绝而不是抓两次（Go 注释原文）。
     */
    static Parsed parse(String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) {
            throw invalid("source is required");
        }
        input = stripTrailingSlashes(input);

        if (input.contains("://")) {
            return parseSkillSourceURL(input);
        }
        String payload = skillsShBarePayload(input);
        if (payload != null) {
            return parseSkillsShLocator(DEFAULT_SKILL_REGISTRY_ORIGIN, splitPath(payload));
        }
        if (input.startsWith("@")) {
            return parseRegistrySlug(DEFAULT_SKILL_REGISTRY_ORIGIN, input.substring(1));
        }
        if (input.contains("/")) {
            throw invalid(SkillBundleParser.quote(input) + " is ambiguous; use @" + input
                    + " for ClawHub, or paste a github.com / gitlab.com / skills.sh / skillhub URL");
        }
        return parseRegistrySlug(DEFAULT_SKILL_REGISTRY_ORIGIN, input);
    }

    private static String stripTrailingSlashes(String input) {
        while (input.endsWith("/")) {
            input = input.substring(0, input.length() - 1);
        }
        return input;
    }

    // ── URL 形态 ────────────────────────────────────────────────────────

    /** handoff 侧复用的 URL 解析（对照 Go 直接调 parseSkillSourceURL）。 */
    static Parsed parseSourceURL(String raw) {
        return parseSkillSourceURL(raw);
    }

    /** 对照 {@code parseSkillSourceURL}：host 决定形态。 */
    private static Parsed parseSkillSourceURL(String raw) {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("not a valid URL");
        }
        String scheme = uri.getScheme() == null ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid("only http(s) sources are allowed");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("github.com") || host.equals("www.github.com")
                || host.equals("codeload.github.com")) {
            if (host.equals("codeload.github.com")) {
                return new Parsed(KIND_DIRECT, "", "", "", "", "", "", "", uri.toString());
            }
            return parseGitHubURL(uri);
        }
        if (host.equals("gitlab.com") || host.equals("www.gitlab.com")) {
            return parseGitLabURL(uri);
        }
        if (host.equals("skills.sh") || host.equals("www.skills.sh")) {
            return parseSkillsShURL(uri);
        }
        if (isClawHubHost(host)) {
            return parseRegistryURL(uri);
        }
        if (isSkillHubCNHost(host)) {
            return parseSkillHubCNURL(uri);
        }
        if (isDirectArchivePath(uri.getPath())) {
            return new Parsed(KIND_DIRECT, "", "", "", "", "", "", "", uri.toString());
        }
        return parseRegistryURL(uri);
    }

    /** 对照 {@code isClawHubHost}。 */
    static boolean isClawHubHost(String host) {
        return switch (host == null ? "" : host.toLowerCase(Locale.ROOT)) {
            case "clawhub.ai", "www.clawhub.ai", "clawhub.com", "www.clawhub.com" -> true;
            default -> false;
        };
    }

    /** 对照 {@code isSkillHubCNHost}。 */
    static boolean isSkillHubCNHost(String host) {
        return switch (host == null ? "" : host.toLowerCase(Locale.ROOT)) {
            case "skillhub.cn", "www.skillhub.cn", "api.skillhub.cn" -> true;
            default -> false;
        };
    }

    /** 对照 {@code parseSkillHubCNURL}：页面 URL 映射到 api.skillhub.cn 的下载 API。 */
    private static Parsed parseSkillHubCNURL(URI u) {
        String trimmed = trimSlash(u.getPath());
        if (trimmed.startsWith("api/v1/download")) {
            String direct = SKILL_HUB_CN_API_ORIGIN + "/api/v1/download";
            String rawQuery = u.getRawQuery();
            if (rawQuery != null && !rawQuery.isEmpty()) {
                direct += "?" + rawQuery;
            }
            return new Parsed(KIND_DIRECT, SKILL_HUB_CN_API_ORIGIN, "", "", "", "", "", "", direct);
        }
        List<String> parts = splitPath(trimmed);
        if (!parts.isEmpty() && parts.get(0).equalsIgnoreCase("skills")) {
            parts = parts.subList(1, parts.size());
        }
        if (parts.isEmpty() || parts.size() > 2) {
            throw invalid("unrecognized registry path");
        }
        String slug = parts.get(parts.size() - 1);
        String version = u.getFragment() == null ? "" : u.getFragment().trim();
        String qVersion = queryParam(u, "version");
        if (!qVersion.trim().isEmpty()) {
            version = qVersion.trim();
        }
        String[] sp = splitTrailingVersion(slug);
        slug = sp[0];
        String fromSpec = sp[1];
        if (!fromSpec.isEmpty()) {
            version = fromSpec;
        }
        if (slug.isEmpty()) {
            throw invalid("skill slug is required");
        }
        return new Parsed(KIND_REGISTRY, SKILL_HUB_CN_API_ORIGIN, slug, version, "", "", "", "", "");
    }

    /** 对照 {@code parseRegistryURL}。 */
    private static Parsed parseRegistryURL(URI u) {
        String origin = originOf(u);
        String trimmed = trimSlash(u.getPath());
        if (trimmed.startsWith("api/v1/download")) {
            return new Parsed(KIND_DIRECT, origin, "", "", "", "", "", "", u.toString());
        }
        List<String> parts = splitPath(trimmed);
        if (!parts.isEmpty() && parts.get(0).equalsIgnoreCase("skills-sh")) {
            return parseSkillsShLocator(origin, parts.subList(1, parts.size()));
        }
        String[] sv = slugAndVersionFromPath(trimmed, u.getFragment());
        String slug = sv[0];
        String version = sv[1];
        String qVersion = queryParam(u, "version");
        if (!qVersion.trim().isEmpty()) {
            version = qVersion.trim();
        }
        Parsed src = new Parsed(KIND_REGISTRY, origin, slug, version, "", "", "", "", "");
        if (isClawHubHost(u.getHost())) {
            String[] os = splitClawHubOwnerSlug(slug);
            src = new Parsed(src.kind(), src.registry(), os[1], src.version(), os[0],
                    src.repo(), src.ref(), src.subdir(), src.directURL());
        }
        return src;
    }

    /** 对照 {@code u.Scheme + "://" + u.Host}（Host 含端口、不含 userinfo）。 */
    private static String originOf(URI u) {
        String host = u.getHost() == null ? "" : u.getHost();
        int port = u.getPort();
        return schemeOf(u) + "://" + (port == -1 ? host : host + ":" + port);
    }

    private static String schemeOf(URI u) {
        return u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    }

    /** 对照 {@code parseRegistrySlug}（@owner/slug 与裸 slug 共用）。 */
    private static Parsed parseRegistrySlug(String origin, String spec) {
        String s = spec == null ? "" : spec.trim();
        if (s.startsWith("@")) {
            s = s.substring(1).trim();
        }
        if (s.isEmpty()) {
            throw invalid("skill slug is required");
        }
        String[] sp = splitTrailingVersion(s);
        String slug = sp[0];
        String version = sp[1];
        if (slug.isEmpty()) {
            throw invalid("skill slug is required");
        }
        Parsed src = new Parsed(KIND_REGISTRY, origin, slug, version, "", "", "", "", "");
        if (isClawHubOrigin(origin)) {
            String[] os = splitClawHubOwnerSlug(slug);
            src = new Parsed(src.kind(), src.registry(), os[1], src.version(), os[0],
                    src.repo(), src.ref(), src.subdir(), src.directURL());
        }
        return src;
    }

    /** 对照 {@code isClawHubOrigin}。 */
    private static boolean isClawHubOrigin(String origin) {
        String host;
        try {
            URI u = URI.create(origin);
            host = u.getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null || host.isEmpty()) {
            return false;
        }
        return isClawHubHost(host);
    }

    /**
     * 对照 {@code splitClawHubOwnerSlug}：GET /api/v1/download 按 skill slug 键控，
     * publisher 走 ownerHandle（Go 注释原文）。
     */
    private static String[] splitClawHubOwnerSlug(String spec) {
        List<String> parts = splitPath(spec);
        return switch (parts.size()) {
            case 0 -> new String[]{"", spec};
            case 1 -> new String[]{"", parts.get(0)};
            case 2 -> new String[]{parts.get(0), parts.get(1)};
            default -> new String[]{"", spec};
        };
    }

    /** 对照 {@code slugAndVersionFromPath}，返回 {slug, version}。 */
    private static String[] slugAndVersionFromPath(String trimmed, String fragment) {
        if (trimmed == null || trimmed.isEmpty()) {
            throw invalid("skill slug is required");
        }
        List<String> parts = splitPath(trimmed);
        if (!parts.isEmpty() && parts.get(0).equalsIgnoreCase("skills")) {
            parts = parts.subList(1, parts.size());
        }
        if (parts.size() >= 3 && parts.get(1).equalsIgnoreCase("skills")) {
            parts = List.of(parts.get(0), parts.get(2));
        }
        if (parts.size() > 2) {
            throw invalid("unrecognized registry path");
        }
        String slug = String.join("/", parts);
        String version = fragment == null ? "" : fragment.trim();
        String[] sp = splitTrailingVersion(slug);
        slug = sp[0];
        String fromSpec = sp[1];
        if (!fromSpec.isEmpty()) {
            version = fromSpec;
        }
        if (slug.isEmpty()) {
            throw invalid("skill slug is required");
        }
        return new String[]{slug, version};
    }

    // ── GitHub / GitLab / skills.sh ─────────────────────────────────────

    /** 对照 {@code parseGitHubURL}。 */
    private static Parsed parseGitHubURL(URI u) {
        List<String> parts = splitPath(u.getPath());
        if (parts.size() < 2) {
            throw invalid("github URL must be owner/repo");
        }
        String owner = parts.get(0);
        String repo = stripGitSuffix(parts.get(1));
        Parsed src = new Parsed(KIND_GITHUB, "", "", "", owner, repo, "HEAD", "", "");
        List<String> rest = parts.subList(2, parts.size());
        if (rest.isEmpty()) {
            return src;
        }
        switch (rest.get(0)) {
            case "tree", "blob" -> {
                if (rest.size() < 2) {
                    throw invalid("github tree URL is missing a ref");
                }
                String subdir = String.join("/", rest.subList(2, rest.size()));
                if (rest.get(0).equals("blob")
                        && baseName(subdir).equalsIgnoreCase("SKILL.md")) {
                    subdir = dirName(subdir);
                    if (subdir.equals(".")) {
                        subdir = "";
                    }
                }
                src = new Parsed(src.kind(), src.registry(), src.slug(), src.version(),
                        src.owner(), src.repo(), rest.get(1), subdir, src.directURL());
            }
            case "archive", "releases" -> src = new Parsed(KIND_DIRECT, src.registry(),
                    src.slug(), src.version(), src.owner(), src.repo(), src.ref(),
                    src.subdir(), u.toString());
            default -> src = new Parsed(src.kind(), src.registry(), src.slug(), src.version(),
                    src.owner(), src.repo(), src.ref(), String.join("/", rest), src.directURL());
        }
        return src;
    }

    /** 对照 {@code parseGitLabURL}。 */
    private static Parsed parseGitLabURL(URI u) {
        String trimmed = trimSlash(u.getPath());
        String project = trimmed;
        String extra = "";
        int cut = trimmed.indexOf("/-/");
        if (cut >= 0) {
            project = trimmed.substring(0, cut);
            extra = trimmed.substring(cut + 3);
        }
        List<String> parts = splitPath(project);
        if (parts.size() < 2) {
            throw invalid("gitlab URL must be group/project");
        }
        String repo = stripGitSuffix(parts.get(parts.size() - 1));
        String owner = String.join("/", parts.subList(0, parts.size() - 1));
        Parsed src = new Parsed(KIND_GITLAB, "", "", "", owner, repo, "HEAD", "", "");
        if (extra.isEmpty()) {
            return src;
        }
        List<String> extraParts = splitPath(extra);
        switch (extraParts.get(0)) {
            case "tree", "blob" -> {
                if (extraParts.size() < 2) {
                    throw invalid("gitlab tree URL is missing a ref");
                }
                String subdir = String.join("/", extraParts.subList(2, extraParts.size()));
                if (extraParts.get(0).equals("blob")
                        && baseName(subdir).equalsIgnoreCase("SKILL.md")) {
                    subdir = dirName(subdir);
                    if (subdir.equals(".")) {
                        subdir = "";
                    }
                }
                src = new Parsed(src.kind(), src.registry(), src.slug(), src.version(),
                        src.owner(), src.repo(), extraParts.get(1), subdir, src.directURL());
            }
            case "archive" -> src = new Parsed(KIND_DIRECT, src.registry(), src.slug(),
                    src.version(), src.owner(), src.repo(), src.ref(), src.subdir(),
                    u.toString());
            default -> {
                // 其它 extra 前缀按 Go 落回默认（保留 owner/repo/ref=HEAD）
            }
        }
        return src;
    }

    /** 对照 {@code parseSkillsShURL}。 */
    private static Parsed parseSkillsShURL(URI u) {
        List<String> parts = splitPath(u.getPath());
        if (parts.size() < 2) {
            throw invalid("skills.sh URL must be owner/repo");
        }
        if (parts.size() >= 3) {
            // 目录页是 owner/repo/slug；GitHub 子目录由 ClawHub 的 install 解析器点名
            return parseSkillsShLocator(DEFAULT_SKILL_REGISTRY_ORIGIN, parts);
        }
        return new Parsed(KIND_GITHUB, "", "", "", parts.get(0),
                stripGitSuffix(parts.get(1)), "HEAD", "", "");
    }

    /** 对照 {@code skillsShBarePayload}：skills-sh: / skills-sh/ 前缀（大小写不敏感）。 */
    private static String skillsShBarePayload(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.startsWith(SKILLS_SH_REF_PREFIX)) {
            return input.substring(SKILLS_SH_REF_PREFIX.length()).trim();
        }
        if (lower.startsWith("skills-sh/")) {
            return input.substring("skills-sh/".length()).trim();
        }
        return null;
    }

    /**
     * 对照 {@code parseSkillsShLocator}：恒为 owner/repo/slug 三段；GitHub 目录
     * 不假定等于 slug，抓取时问 ClawHub（Go 注释原文）。
     */
    private static Parsed parseSkillsShLocator(String registry, List<String> parts) {
        if (parts.size() != 3) {
            throw invalid("skills.sh locator must be owner/repo/slug");
        }
        String owner = parts.get(0).trim().toLowerCase(Locale.ROOT);
        String repo = stripGitSuffix(parts.get(1).trim()).toLowerCase(Locale.ROOT);
        String slug = splitTrailingVersion(parts.get(2).trim().toLowerCase(Locale.ROOT))[0];
        if (owner.isEmpty() || repo.isEmpty() || slug.isEmpty()
                || owner.contains("..") || repo.contains("..") || slug.contains("..")) {
            throw invalid("skills.sh locator must be owner/repo/slug");
        }
        String reg = registry == null || registry.isEmpty()
                ? DEFAULT_SKILL_REGISTRY_ORIGIN : registry;
        return new Parsed(KIND_SKILLS_SH, reg, slug, "", owner, repo, "", "", "");
    }

    // ── 版本/路径辅助 ───────────────────────────────────────────────────

    /** 对照 {@code splitTrailingVersion}，返回 {slug, version}。 */
    static String[] splitTrailingVersion(String spec) {
        int i = spec.lastIndexOf('@');
        if (i <= 0) {
            return new String[]{spec, ""};
        }
        String candidate = spec.substring(i + 1);
        if (candidate.equalsIgnoreCase("latest") || SEMVER_LIKE.matcher(candidate).matches()) {
            return new String[]{spec.substring(0, i), candidate};
        }
        return new String[]{spec, ""};
    }

    /** 对照 {@code splitPath}：去掉空段与 "."。 */
    static List<String> splitPath(String p) {
        List<String> out = new ArrayList<>();
        String trimmed = trimSlash(p);
        for (String part : trimmed.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            out.add(part);
        }
        return out;
    }

    private static String trimSlash(String p) {
        if (p == null) {
            return "";
        }
        int b = 0;
        int e = p.length();
        while (b < e && p.charAt(b) == '/') {
            b++;
        }
        while (e > b && p.charAt(e - 1) == '/') {
            e--;
        }
        return p.substring(b, e);
    }

    private static String stripGitSuffix(String s) {
        return s != null && s.endsWith(".git") ? s.substring(0, s.length() - 4) : s;
    }

    private static String baseName(String path) {
        String p = SkillBundleParser.goPathClean(path);
        if (p.equals("/") || p.equals(".")) {
            return p;
        }
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static String dirName(String path) {
        String p = SkillBundleParser.goPathClean(path);
        int i = p.lastIndexOf('/');
        if (i < 0) {
            return "";
        }
        String dir = p.substring(0, i);
        if (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        return dir.equals(".") ? "" : dir;
    }

    /** 对照 {@code isDirectArchivePath}：zip/tgz/tar/md 结尾走 direct。 */
    static boolean isDirectArchivePath(String p) {
        String lower = p == null ? "" : p.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".tgz") || lower.endsWith(".tar.gz")
                || lower.endsWith(".tar") || lower.endsWith(".md");
    }

    // ── fetchURL ────────────────────────────────────────────────────────

    /**
     * 对照 {@code parsedSkillSource.fetchURL}：把解析结果映射到要 GET 的 URL。
     * registry 的查询串按键字母序编码（Go 的 url.Values.Encode）。
     */
    static String fetchURL(Parsed s) {
        if (!s.directURL().isEmpty()) {
            return s.directURL();
        }
        switch (s.kind()) {
            case KIND_REGISTRY: {
                Map<String, String> q = new TreeMap<>();
                q.put("slug", s.slug());
                if (!s.owner().isEmpty()) {
                    q.put("ownerHandle", s.owner());
                }
                if (!s.version().isEmpty() && !s.version().equalsIgnoreCase("latest")) {
                    if (SEMVER_LIKE.matcher(s.version()).matches()) {
                        q.put("version", s.version());
                    } else {
                        q.put("tag", s.version());
                    }
                }
                return stripTrailingSlashes(s.registry()) + "/api/v1/download" + encodeQuery(q);
            }
            case KIND_SKILLS_SH: {
                String registry = stripTrailingSlashes(s.registry());
                if (registry.isEmpty()) {
                    registry = DEFAULT_SKILL_REGISTRY_ORIGIN;
                }
                Map<String, String> q = new TreeMap<>();
                q.put("reference", SKILLS_SH_REF_PREFIX + s.owner() + "/" + s.repo() + "/" + s.slug());
                return registry + "/api/v1/skills/" + goPathEscape(s.slug())
                        + "/install" + encodeQuery(q);
            }
            case KIND_GITHUB: {
                String ref = s.ref().isEmpty() ? "HEAD" : s.ref();
                return "https://codeload.github.com/" + escapePathSegments(s.owner())
                        + "/" + goPathEscape(s.repo()) + "/zip/" + escapePathSegments(ref);
            }
            case KIND_GITLAB: {
                String ref = s.ref().isEmpty() ? "HEAD" : s.ref();
                return "https://gitlab.com/" + escapePathSegments(s.owner())
                        + "/" + goPathEscape(s.repo()) + "/-/archive/" + escapePathSegments(ref)
                        + "/" + goPathEscape(s.repo() + "-" + ref) + ".zip";
            }
            default:
                throw invalid("cannot resolve source");
        }
    }

    /** 对照 Go 的 {@code u.Query(); q.Set(...); u.RawQuery = q.Encode()}（键字母序）。 */
    private static String encodeQuery(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(goQueryEscape(e.getKey())).append('=').append(goQueryEscape(e.getValue()));
        }
        return sb.toString();
    }

    /**
     * Go {@code url.QueryEscape}：空格 → {@code +}，保留 {@code A-Za-z0-9-_.~}，
     * 其余 %XX 大写。JDK URLEncoder 的差异（{@code *} 转义、{@code ~} 不转义）在此抹平。
     */
    static String goQueryEscape(String s) {
        String encoded = java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        return encoded.replace("%7E", "~").replace("*", "%2A");
    }

    /**
     * Go {@code url.PathEscape}（encodePathSegment 模式）：保留 unreserved 与
     * {@code $&+,;=:} 与 {@code @}，其余（含 {@code /}、空格）%XX 大写。
     */
    static String goPathEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~'
                    || c == '$' || c == '&' || c == '+' || c == ',' || c == ';'
                    || c == '=' || c == ':' || c == '@') {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%').append(String.format("%02X", b));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 对照 {@code escapePathSegments}：跨段的值（如 refs/heads/main）逐段转义，
     * 不把分隔符变成 %2F。
     */
    static String escapePathSegments(String p) {
        String[] parts = p.split("/", -1);
        StringBuilder sb = new StringBuilder(p.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(goPathEscape(parts[i]));
        }
        return sb.toString();
    }

    /** 查询单参数（对照 u.Query().Get）。 */
    private static String queryParam(URI u, String name) {
        String rawQuery = u.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (goQueryUnescape(k).equals(name)) {
                String v = eq >= 0 ? pair.substring(eq + 1) : "";
                return goQueryUnescape(v).replace("+", " ");
            }
        }
        return "";
    }

    private static String goQueryUnescape(String s) {
        // 最小实现：%XX 与 '+'（+/空格语义由调用方处理）；解析失败按原文返回
        if (s.indexOf('%') < 0) {
            return s;
        }
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    /** 拒绝异常：消息 = sentinel + ": " + detail（对照 fmt.Errorf("%w: ...")）。 */
    static TenantSkillService.SkillSourceInvalidException invalid(String detail) {
        return new TenantSkillService.SkillSourceInvalidException(
                TenantSkillService.SENTINEL_SKILL_SOURCE_INVALID + ": " + detail);
    }
}
