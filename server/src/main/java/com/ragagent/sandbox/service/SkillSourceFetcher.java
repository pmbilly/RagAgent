package com.ragagent.sandbox.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;

/**
 * 对照 Go {@code service/tenant_skill_source.go} 的抓取切片（波 4 接缝的落地）：
 * fetchSkillSourceBytes（hop 上限 3 + JSON handoff 递归）、getSkillURL
 * （SSRF 校验 + 限额 + 状态码）、normalizeFetchedSkill（markdown / zip 判别），
 * 以及 tenant_skill_bundle.go 的 zipSkillFiles / 手写限额的入口。
 *
 * <p>SSRF 基建复用 {@link SsrfGuard}（与 {@code LlmTransport}/{@code McpHttp} 同策略：
 * 发送前 + 每一次重定向跳转前校验，JDK HttpClient 不能替换 dialer）。错误文案是
 * golden 契约：全部拒绝 = {@code "skill source is invalid: <细节>"}。</p>
 *
 * <h2>与 Go 的已知差异</h2>
 * <ul>
 *   <li>传输层错误内文（Go {@code dial tcp ...: connect: connection refused} vs JDK 措辞）
 *       —— 外层形态保持 {@code download failed: Get "<url>": <err>}，A/B 按既有纪律掩码；</li>
 *   <li>Go 的 5min 超时是 client 级（覆盖整个 Do 含重定向），Java 落到每个请求的
 *       {@code HttpRequest.timeout}（每跳 5min）；</li>
 *   <li>Go 用 SDK 解析 zip 外部属性可检测 symlink，Java 标准库不可——
 *       {@code entry %q is a symlink} 拒绝分支缺失（SkillBundleParser 既有备案）。</li>
 * </ul>
 */
final class SkillSourceFetcher {

    /** 对照 {@code skillSourceUserAgent}。 */
    private static final String USER_AGENT =
            "WeKnora-SkillInstaller (+https://github.com/Tencent/WeKnora)";

    /** 对照 {@code skillSourceFetchTimeout = 5 * time.Minute}。 */
    private static final Duration FETCH_TIMEOUT = Duration.ofMinutes(5);

    /** 对照 {@code skillSourceMaxHops}：JSON handoff 递归上限。 */
    static final int MAX_HOPS = 3;

    /** 对照 {@code MaxRedirects: 10}（Go DefaultSSRFSafeHTTPClientConfig）。 */
    private static final int MAX_REDIRECTS = 10;

    /** 对照 {@code Accept} 头（getSkillURL 原文）。 */
    private static final String ACCEPT_HEADER =
            "application/zip, application/octet-stream, application/json, "
                    + "text/plain;q=0.9, */*;q=0.8";

    /** 对照 {@code defaultMaxSkillBundleSizeMB}。 */
    private static final long DEFAULT_MAX_SKILL_BUNDLE_SIZE_MB = 256;
    private static final long DEFAULT_MAX_FILE_SIZE_MB = 50;
    private static final long MAX_SKILL_BUNDLE_SIZE_MB_CEILING = 512;

    /** 测试钩子：钉死 MAX_SKILL_BUNDLE_SIZE_MB 的解析结果（生产恒 null）。 */
    static volatile Long maxSkillBundleSizeMBOverride;

    private SkillSourceFetcher() {
    }

    /** 对照 {@code fetchNormalizedSkillBundle} 的返回：(bundle, archive)。 */
    record Fetched(SkillBundleParser.SkillBundle bundle, byte[] archive) {
    }

    // ── 入口 ────────────────────────────────────────────────────────────

    /**
     * 对照 {@code fetchNormalizedSkillBundle}：解析 → 抓取（handoff 递归）→
     * 归一为以 skill 根重挂的 bundle + 重打的 zip。
     */
    static Fetched fetchNormalizedSkillBundle(String source) {
        SkillSource.Parsed parsed = SkillSource.parse(source);
        FetchedBytes fetchedBytes = fetchSkillSourceBytes(parsed, 0);
        return normalizeFetchedSkill(fetchedBytes.body(), fetchedBytes.contentType(),
                fetchedBytes.subdir());
    }

    /** 对照 {@code fetchSkillArchive} 的归档形态（供需要裸归档的调用方）。 */
    static byte[] fetchSkillArchive(String source) {
        return fetchNormalizedSkillBundle(source).archive();
    }

    // ── 抓取与 handoff ──────────────────────────────────────────────────

    /** 对照 {@code fetchedSkillSource}。 */
    private record FetchedBytes(byte[] body, String contentType, String subdir) {
    }

    /** getSkillURL 的返回（对照 Go 的 (body, contentType) 双返回）。 */
    private record SkillURLResult(byte[] body, String contentType) {
    }

    /** 对照 {@code fetchSkillSourceBytes}：hop 上限 + JSON handoff 递归取源。 */
    private static FetchedBytes fetchSkillSourceBytes(SkillSource.Parsed src, int hop) {
        if (hop > MAX_HOPS) {
            throw SkillSource.invalid("too many source redirects");
        }
        String target = SkillSource.fetchURL(src);
        SkillURLResult got = getSkillURL(target);
        FetchedBytes fetched = new FetchedBytes(got.body(), got.contentType(), src.subdir());
        if (isZipMagic(fetched.body()) || looksLikeSkillMarkdown(fetched.body())) {
            return fetched;
        }
        if (!looksLikeJSON(fetched.contentType(), fetched.body())) {
            if (isZipPayload(fetched.contentType(), fetched.body())) {
                return fetched;
            }
            throw SkillSource.invalid("remote did not return a zip skill bundle");
        }
        Handoff handoff = parseHandoff(fetched.body());
        SkillSource.Parsed next = sourceFromHandoff(src, handoff);
        return fetchSkillSourceBytes(next, hop + 1);
    }

    /** 对照 {@code skillSourceHandoff}（json tag 逐字段；未知键容忍）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Handoff {
        @JsonProperty("ok")
        Boolean ok;
        @JsonProperty("message")
        String message;
        @JsonProperty("reason")
        String reason;
        @JsonProperty("installKind")
        String installKind;
        @JsonProperty("sourceRef")
        String sourceRef;
        @JsonProperty("repo")
        String repo;
        @JsonProperty("commit")
        String commit;
        @JsonProperty("path")
        String path;
        @JsonProperty("archiveUrl")
        String archiveUrl;
        @JsonProperty("downloadUrl")
        String downloadUrl;
        @JsonProperty("github")
        GitHubHandoff github;
        @JsonProperty("archive")
        ArchiveHandoff archive;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class GitHubHandoff {
        @JsonProperty("repo")
        String repo;
        @JsonProperty("path")
        String path;
        @JsonProperty("commit")
        String commit;
        @JsonProperty("sourceUrl")
        String sourceUrl;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ArchiveHandoff {
        @JsonProperty("version")
        String version;
        @JsonProperty("downloadUrl")
        String downloadUrl;
    }

    private static final ObjectMapper HANDOFF_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Handoff parseHandoff(byte[] body) {
        try {
            return HANDOFF_MAPPER.readValue(new String(body, StandardCharsets.UTF_8), Handoff.class);
        } catch (IOException e) {
            throw SkillSource.invalid("remote JSON is not a skill archive");
        }
    }

    /** 对照 {@code sourceFromHandoff}。 */
    private static SkillSource.Parsed sourceFromHandoff(SkillSource.Parsed prev, Handoff handoff) {
        if (handoff.ok != null && !handoff.ok) {
            String msg = trim(handoff.message);
            if (msg.isEmpty()) {
                msg = trim(handoff.reason);
            }
            if (msg.isEmpty()) {
                msg = "registry refused the skill";
            }
            throw SkillSource.invalid(truncateSkillError(msg));
        }

        String archiveURL = trim(handoff.archiveUrl);
        if (archiveURL.isEmpty()) {
            archiveURL = trim(handoff.downloadUrl);
        }
        if (archiveURL.isEmpty() && handoff.archive != null) {
            archiveURL = trim(handoff.archive.downloadUrl);
        }
        if (!archiveURL.isEmpty()) {
            return sourceFromArchiveHandoff(prev, handoff, archiveURL);
        }
        GitHubHandoff gh = handoffGitHub(handoff);
        if (gh != null) {
            return sourceFromGitHubHandoff(gh);
        }
        throw SkillSource.invalid("registry response has no archive URL");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 对照 {@code handoffGitHub}。 */
    private static GitHubHandoff handoffGitHub(Handoff handoff) {
        if (handoff.github != null) {
            return handoff.github;
        }
        if (!trim(handoff.installKind).equalsIgnoreCase("github")) {
            return null;
        }
        if (trim(handoff.repo).isEmpty() && trim(handoff.commit).isEmpty()) {
            return null;
        }
        GitHubHandoff gh = new GitHubHandoff();
        gh.repo = handoff.repo;
        gh.path = handoff.path;
        gh.commit = handoff.commit;
        return gh;
    }

    /** 对照 {@code sourceFromArchiveHandoff}。 */
    private static SkillSource.Parsed sourceFromArchiveHandoff(
            SkillSource.Parsed prev, Handoff handoff, String archiveURL) {
        if (archiveURL.startsWith("/")) {
            if (prev.registry().isEmpty()) {
                throw SkillSource.invalid("registry response has a relative archive URL");
            }
            archiveURL = stripTrailingSlash(prev.registry()) + archiveURL;
        }
        // 不是可解析的 http(s) URL 的 handoff 一律拒绝而不是透传成 direct 目标：
        // SSRF 校验会给无 scheme 的串前置 https://，把畸形响应变成一次从未同意的
        // 主机抓取（Go 注释原文）
        SkillSource.Parsed next;
        try {
            next = SkillSource.parseSourceURL(archiveURL);
        } catch (TenantSkillService.SkillSourceInvalidException e) {
            throw SkillSource.invalid("registry archive URL is not usable: " + e.getMessage());
        }
        String path = trim(handoff.path);
        if (path.isEmpty() && handoff.github != null) {
            path = trim(handoff.github.path);
        }
        String subdir = next.subdir();
        if (subdir.isEmpty() && !path.isEmpty()) {
            subdir = trimSlashBothSides(path);
        }
        String commit = trim(handoff.commit);
        if (commit.isEmpty() && handoff.github != null) {
            commit = trim(handoff.github.commit);
        }
        String ref = next.ref();
        if (next.kind().equals(SkillSource.KIND_GITHUB) && ref.equals("HEAD") && !commit.isEmpty()) {
            ref = commit;
        }
        return new SkillSource.Parsed(next.kind(), next.registry(), next.slug(), next.version(),
                next.owner(), next.repo(), ref, subdir, next.directURL());
    }

    /** 对照 {@code sourceFromGitHubHandoff}。 */
    private static SkillSource.Parsed sourceFromGitHubHandoff(GitHubHandoff gh) {
        String srcURL = trim(gh.sourceUrl);
        if (!srcURL.isEmpty()) {
            try {
                SkillSource.Parsed next = SkillSource.parseSourceURL(srcURL);
                if (next.kind().equals(SkillSource.KIND_GITHUB)) {
                    return applyGitHubHandoffMeta(next, gh);
                }
            } catch (TenantSkillService.SkillSourceInvalidException ignored) {
                // 解析失败照 Go 落回 repo/commit 组装
            }
        }
        String[] rr = splitGitHubRepo(gh.repo);
        if (rr == null) {
            throw SkillSource.invalid("registry github handoff is missing repo");
        }
        String ref = trim(gh.commit);
        if (ref.isEmpty()) {
            ref = "HEAD";
        }
        return new SkillSource.Parsed(SkillSource.KIND_GITHUB, "", "", "", rr[0], rr[1], ref,
                trimSlashBothSides(trim(gh.path)), "");
    }

    private static SkillSource.Parsed applyGitHubHandoffMeta(SkillSource.Parsed next, GitHubHandoff gh) {
        if (!next.kind().equals(SkillSource.KIND_GITHUB)) {
            return next;
        }
        String commit = trim(gh.commit);
        String subdir = next.subdir();
        String ref = next.ref();
        if (!commit.isEmpty()) {
            ref = commit;
        }
        if (subdir.isEmpty() && !trim(gh.path).isEmpty()) {
            subdir = trimSlashBothSides(gh.path);
        }
        return new SkillSource.Parsed(next.kind(), next.registry(), next.slug(), next.version(),
                next.owner(), next.repo(), ref, subdir, next.directURL());
    }

    /** 对照 {@code splitGitHubRepo}：失败返回 null。 */
    private static String[] splitGitHubRepo(String spec) {
        String s = trim(spec);
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        int cut = s.indexOf('/');
        if (cut < 0) {
            return null;
        }
        String owner = s.substring(0, cut);
        String repo = s.substring(cut + 1);
        if (owner.isEmpty() || repo.isEmpty() || repo.contains("/")) {
            return null;
        }
        return new String[]{owner, repo};
    }

    // ── getSkillURL（HTTP GET + SSRF + 限额） ────────────────────────────

    /**
     * 对照 {@code getSkillURL}：SSRF 校验 → GET → Content-Length/读取两级限额 →
     * 状态码检查。返回 body + contentType。
     */
    private static SkillURLResult getSkillURL(String rawURL) {
        SsrfGuard guard = new SsrfGuard();
        try {
            guard.validateURLForSSRF(rawURL);
        } catch (SsrfGuard.SsrfException e) {
            throw SkillSource.invalid(guard.formatSSRFError("skill source", rawURL, e));
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(rawURL))
                    .timeout(FETCH_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", ACCEPT_HEADER)
                    .GET()
                    .build();
        } catch (RuntimeException e) {
            // 对照 http.NewRequestWithContext 失败分支
            throw SkillSource.invalid("invalid source URL");
        }

        long maxBytes = getMaxSkillBundleSize();
        HttpResponse<InputStream> resp;
        try {
            resp = sendFollowingRedirects(request, guard, rawURL);
        } catch (FetchException e) {
            if (e.redirectBlocked) {
                // 对照 errors.Is(err, ErrSSRFRedirectBlocked)：host 用原始请求 URL
                throw SkillSource.invalid(guard.formatSSRFError("skill source", rawURL,
                        new Exception(e.getMessage())));
            }
            throw SkillSource.invalid("download failed: " + e.getMessage());
        } catch (IOException e) {
            throw SkillSource.invalid("download failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SkillSource.invalid("download failed: " + e.getMessage());
        }

        long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        // 上限是下载体本身——GitHub zipball 是整个仓库，不是 SKILL.md 子树
        //（解析时限额另算；Go 注释原文）
        if (contentLength > maxBytes) {
            closeQuietly(resp);
            throw tooLargeError();
        }
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            String preview = readPreview(resp, 512);
            closeQuietly(resp);
            String msg = preview.trim();
            if (msg.isEmpty()) {
                msg = goRespStatus(status);
            }
            throw SkillSource.invalid("download returned HTTP " + status + ": "
                    + truncateSkillError(msg));
        }
        byte[] body = readLimited(resp, maxBytes);
        closeQuietly(resp);
        return new SkillURLResult(body,
                resp.headers().firstValue("Content-Type").orElse(""));
    }

    /** Fetch 传输异常：携带 redirect-blocked 分类（对照 ErrSSRFRedirectBlocked）。 */
    private static final class FetchException extends IOException {
        final boolean redirectBlocked;

        FetchException(String message, boolean redirectBlocked) {
            super(message);
            this.redirectBlocked = redirectBlocked;
        }
    }

    /**
     * 对照 Go 的 {@code client.Do}（CheckRedirect 语义）：发送前校验 URL，每一跳重新校验
     * （含 scheme），跳数超限报 {@code stopped after N redirects}，包装成
     * {@code Get "<url>": <err>} 形态（url.Error）。
     */
    private static HttpResponse<InputStream> sendFollowingRedirects(
            HttpRequest request, SsrfGuard guard, String requestURL)
            throws IOException, InterruptedException {
        HttpRequest current = request;
        int hops = 0;
        while (true) {
            try {
                guard.validateURLForSSRF(current.uri().toString());
            } catch (SsrfGuard.SsrfException e) {
                throw new FetchException(getErrorWrap(requestURL,
                        "redirect blocked: target URL failed SSRF validation: " + e.getMessage()),
                        true);
            }
            HttpResponse<InputStream> response;
            try {
                response = sharedClient().send(current, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException | RuntimeException e) {
                throw new FetchException(getErrorWrap(requestURL, e.getMessage()), false);
            }
            if (!isRedirect(response.statusCode())) {
                return response;
            }
            Optional<String> location = response.headers().firstValue("Location");
            if (location.isEmpty() || location.get().isBlank()) {
                return response; // 3xx 但没 Location：不跟随，原样返回（对照 Go）
            }
            closeQuietly(response);
            if (hops >= MAX_REDIRECTS) {
                throw new FetchException(getErrorWrap(requestURL,
                        "stopped after " + MAX_REDIRECTS + " redirects"), false);
            }
            URI target;
            try {
                target = current.uri().resolve(location.get().trim());
            } catch (IllegalArgumentException e) {
                throw new FetchException(getErrorWrap(requestURL,
                        "redirect blocked: invalid location " + location.get()), true);
            }
            String scheme = target.getScheme() == null ? ""
                    : target.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new FetchException(getErrorWrap(requestURL,
                        "redirect blocked: target URL failed SSRF validation: invalid scheme "
                                + scheme), true);
            }
            try {
                guard.validateURLForSSRF(target.toString());
            } catch (SsrfGuard.SsrfException e) {
                throw new FetchException(getErrorWrap(requestURL,
                        "redirect blocked: target URL failed SSRF validation: " + e.getMessage()),
                        true);
            }
            // 本请求不带凭据头（匿名抓取），无需 stripRedirectSensitiveHeaders
            HttpRequest.Builder builder = HttpRequest.newBuilder(target);
            current.timeout().ifPresent(builder::timeout);
            builder.header("User-Agent", USER_AGENT);
            builder.header("Accept", ACCEPT_HEADER);
            builder.GET();
            current = builder.build();
            hops++;
        }
    }

    /** Go {@code url.Error} 的 {@code Get "<url>": <err>} 形态。 */
    private static String getErrorWrap(String requestURL, String message) {
        return "Get \"" + requestURL + "\": " + (message == null ? "" : message);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** 进程级共享客户端（对照 skillSourceHTTPOnce 的 sync.Once）。 */
    private static final class ClientHolder {
        private static final HttpClient INSTANCE = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER) // 手动跟随：每一跳重做 SSRF 校验
                .build();
    }

    private static HttpClient sharedClient() {
        return ClientHolder.INSTANCE;
    }

    /** 非 2xx 时读前 limit 字节做错误预览（对照 io.LimitReader(resp.Body, 512)）。 */
    private static String readPreview(HttpResponse<InputStream> resp, int limit) {
        try (InputStream in = resp.body()) {
            byte[] buf = in.readNBytes(limit);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 读取体（对照 io.ReadAll(io.LimitReader(resp.Body, maxBytes+1))）：
     * 超 {@code maxBytes} → too-large；空 → empty body；读失败 → read 错误。
     */
    private static byte[] readLimited(HttpResponse<InputStream> resp, long maxBytes) {
        try (InputStream in = resp.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw tooLargeError();
                }
                out.write(buf, 0, n);
            }
            if (out.size() == 0) {
                throw SkillSource.invalid("remote returned an empty body");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw SkillSource.invalid("failed to read skill archive");
        }
    }

    private static TenantSkillService.SkillSourceInvalidException tooLargeError() {
        return SkillSource.invalid("skill bundle cannot exceed "
                + getMaxSkillBundleSizeMB() + " MB");
    }

    private static void closeQuietly(HttpResponse<InputStream> resp) {
        if (resp == null) {
            return;
        }
        try {
            resp.body().close();
        } catch (IOException ignored) {
            // 已在关闭路径上
        }
    }

    /** Go {@code resp.Status} 的近似（"404 Not Found" 形态）。 */
    private static String goRespStatus(int code) {
        String reason = switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 413 -> "Request Entity Too Large";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
        return reason.isEmpty() ? String.valueOf(code) : code + " " + reason;
    }

    // ── 大小限制（utils/filesize.go） ────────────────────────────────────

    /** 对照 {@code GetMaxSkillBundleSize}。 */
    static long getMaxSkillBundleSize() {
        return getMaxSkillBundleSizeMB() * 1024 * 1024;
    }

    /**
     * 对照 {@code GetMaxSkillBundleSizeMB}：MAX_SKILL_BUNDLE_SIZE_MB 默认 256，
     * 不低于 MAX_FILE_SIZE_MB，封顶 512。
     */
    static long getMaxSkillBundleSizeMB() {
        Long override = maxSkillBundleSizeMBOverride;
        if (override != null) {
            return override;
        }
        long skillMB = envSizeMB("MAX_SKILL_BUNDLE_SIZE_MB", DEFAULT_MAX_SKILL_BUNDLE_SIZE_MB);
        long fileMB = envSizeMB("MAX_FILE_SIZE_MB", DEFAULT_MAX_FILE_SIZE_MB);
        if (skillMB < fileMB) {
            skillMB = fileMB;
        }
        return Math.min(skillMB, MAX_SKILL_BUNDLE_SIZE_MB_CEILING);
    }

    /** 对照 {@code envSizeMB}。 */
    private static long envSizeMB(String key, long fallback) {
        String sizeStr = System.getenv(key);
        if (sizeStr != null && !sizeStr.isEmpty()) {
            try {
                long size = Long.parseLong(sizeStr.trim());
                if (size > 0) {
                    return size;
                }
            } catch (NumberFormatException ignored) {
                // 非法 env 落回默认
            }
        }
        return fallback;
    }

    // ── 归一（normalizeFetchedSkill） ────────────────────────────────────

    /**
     * 对照 {@code normalizeFetchedSkill}：markdown 直收（合成 SKILL.md bundle），
     * zip 走宽松解析（Subdir + AllowExtraFiles + AllowNestedSkill），两者都重打
     * 确定性 zip 并以新归档覆盖 SHA256。
     */
    private static Fetched normalizeFetchedSkill(byte[] body, String contentType, String subdir) {
        if (looksLikeSkillMarkdown(body)) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("SKILL.md", body);
            SkillBundleParser.SkillBundle bundle =
                    SkillBundleParser.skillBundleFromFiles(body, files);
            byte[] archive = SkillBundleParser.zipSkillFiles(files);
            bundle.sha256 = SkillBundleParser.skillArchiveSHA256(archive);
            return new Fetched(bundle, archive);
        }
        if (!isZipPayload(contentType, body)) {
            throw SkillSource.invalid("remote did not return a zip skill bundle");
        }
        SkillBundleParser.SkillBundle bundle = SkillBundleParser.parseSkillBundleWithOptions(
                body, new SkillBundleParser.ParseOptions(subdir, true, true));
        byte[] archive = SkillBundleParser.zipSkillFiles(bundle.files);
        bundle.sha256 = SkillBundleParser.skillArchiveSHA256(archive);
        return new Fetched(bundle, archive);
    }

    // ── 判别助手 ────────────────────────────────────────────────────────

    /** 对照 {@code isZipMagic}：PK\x03\x04。 */
    static boolean isZipMagic(byte[] body) {
        return body != null && body.length >= 4
                && body[0] == 'P' && body[1] == 'K' && body[2] == 3 && body[3] == 4;
    }

    /** 对照 {@code isZipPayload}。 */
    static boolean isZipPayload(String contentType, byte[] body) {
        if (isZipMagic(body)) {
            return true;
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return ct.contains("zip");
    }

    /** 对照 {@code looksLikeJSON}。 */
    static boolean looksLikeJSON(String contentType, byte[] body) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("json")) {
            return true;
        }
        byte[] trimmed = trimSpace(body);
        return trimmed.length > 0 && trimmed[0] == '{';
    }

    /** 对照 {@code looksLikeSkillMarkdown}：BFO 剥离后 "---" 开头且含 "\nname:"。 */
    static boolean looksLikeSkillMarkdown(byte[] body) {
        if (body == null) {
            return false;
        }
        int start = 0;
        if (body.length >= 3 && body[0] == (byte) 0xEF && body[1] == (byte) 0xBB
                && body[2] == (byte) 0xBF) {
            start = 3; // BOM
        }
        byte[] trimmed = trimSpace(slice(body, start));
        if (!startsWith(trimmed, "---")) {
            return false;
        }
        return contains(trimmed, "\nname:") || contains(trimmed, "\nname :");
    }

    /** 对照 {@code truncateSkillError}：折叠空白、按字节截 200。 */
    static String truncateSkillError(String msg) {
        String collapsed = String.join(" ", msg.trim().split("\\s+"));
        if (collapsed.isEmpty() && !msg.isEmpty()) {
            collapsed = msg.trim();
        }
        byte[] bytes = collapsed.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 200) {
            // 按字节截断（Go len 语义），不切破多字节字符
            int end = 200;
            while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
                end--;
            }
            return new String(bytes, 0, end, StandardCharsets.UTF_8);
        }
        return collapsed;
    }

    // ── 字节小助手 ──────────────────────────────────────────────────────

    private static byte[] slice(byte[] b, int from) {
        if (from <= 0) {
            return b;
        }
        if (from >= b.length) {
            return new byte[0];
        }
        byte[] out = new byte[b.length - from];
        System.arraycopy(b, from, out, 0, out.length);
        return out;
    }

    private static byte[] trimSpace(byte[] b) {
        int s = 0;
        int e = b.length;
        while (s < e && isSpace(b[s])) {
            s++;
        }
        while (e > s && isSpace(b[e - 1])) {
            e--;
        }
        if (s == 0 && e == b.length) {
            return b;
        }
        byte[] out = new byte[e - s];
        System.arraycopy(b, s, out, 0, out.length);
        return out;
    }

    private static boolean isSpace(byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == 0x0C;
    }

    private static boolean startsWith(byte[] b, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
        if (b.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (b[i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(byte[] b, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        if (n.length == 0 || b.length < n.length) {
            return n.length == 0;
        }
        for (int i = 0; i <= b.length - n.length; i++) {
            boolean hit = true;
            for (int j = 0; j < n.length; j++) {
                if (b[i + j] != n[j]) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** 对照 strings.Trim(path, "/")。 */
    private static String trimSlashBothSides(String s) {
        if (s == null) {
            return "";
        }
        int b = 0;
        int e = s.length();
        while (b < e && s.charAt(b) == '/') {
            b++;
        }
        while (e > b && s.charAt(e - 1) == '/') {
            e--;
        }
        return s.substring(b, e);
    }
}
