package com.ragagent.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.ragagent.common.security.SsrfGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 从源注册/安装的抓取契约测试（对照 Go tenant_skill_source.go 的行为面）。
 * stub 用 com.sun.net.httpserver.HttpServer（127.0.0.1 + 白名单注入，零真实网络）；
 * 错误消息是 golden 契约，逐字断言。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkillSourceFetcherContractTest {

    private static HttpServer server;
    private static int port;
    private static String base;

    private SsrfGuard.Whitelist whitelistSnapshot;

    /** SKILL.md 最小正文（SkillFrontmatter 可解析）。 */
    private static final String SKILL_MD = "---\nname: weather\ndescription: a weather skill\n"
            + "version: 1.2.0\n---\n\nUse the forecast tool.\n";

    @BeforeAll
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4)); // 不设 executor 会串行卡死
        registerRoutes();
        server.start();
        port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;
    }

    @AfterAll
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void whitelistLoopback() {
        whitelistSnapshot = SsrfGuard.snapshotWhitelist();
        new SsrfGuard().reloadWhitelist("127.0.0.1");
    }

    @AfterEach
    void restoreWhitelistAndLimits() {
        SsrfGuard.restoreWhitelist(whitelistSnapshot);
        SkillSourceFetcher.maxSkillBundleSizeMBOverride = null;
    }

    private void registerRoutes() {
        final byte[] zip = SkillBundleParser.zipOf(java.util.Map.of(
                "SKILL.md", SKILL_MD, "scripts/run.sh", "echo hi\n"));

        // 直链 zip
        server.createContext("/skill.zip", ex -> sendBytes(ex, 200,
                "application/zip", zip));
        // 重定向链：/redirect.zip → 302 → /skill.zip
        server.createContext("/redirect.zip", ex -> {
            ex.getResponseHeaders().set("Location", base + "/skill.zip");
            sendBytes(ex, 302, "text/plain", new byte[0]);
        });
        // 重定向到受限 IP（SSRF 校验层拒绝，不出网）
        server.createContext("/redirect-internal.zip", ex -> {
            ex.getResponseHeaders().set("Location", "http://10.0.0.1:8/x.zip");
            sendBytes(ex, 302, "text/plain", new byte[0]);
        });
        // 404 带预览体
        server.createContext("/missing.zip", ex -> sendBytes(ex, 404,
                "text/plain", "nope".getBytes(StandardCharsets.UTF_8)));
        // 空 200 体
        server.createContext("/empty.zip", ex -> sendBytes(ex, 200,
                "application/zip", new byte[0]));
        // 非 zip 非 JSON 非 markdown（Go isDirectArchivePath 只认 .zip/.tgz/.tar.gz/.tar/.md
        // 后缀——非归档后缀会走 registry 改写，所以这里必须用 .zip 后缀挂非 zip 体）
        server.createContext("/plain.zip", ex -> sendBytes(ex, 200,
                "text/plain", "hello world".getBytes(StandardCharsets.UTF_8)));
        // markdown 直收（SKILL.md 形态）
        server.createContext("/SKILL.md", ex -> sendBytes(ex, 200,
                "text/markdown", SKILL_MD.getBytes(StandardCharsets.UTF_8)));
        // zip-slip：条目逃出归档根
        final byte[] slip = zipWithSlipEntry("../evil.txt");
        server.createContext("/slip.zip", ex -> sendBytes(ex, 200,
                "application/zip", slip));
        // registry download 端点：按 query 返回 handoff JSON（自指 → hop 超限）或 zip
        server.createContext("/api/v1/download", ex -> {
            String query = ex.getRequestURI().getRawQuery();
            if (query != null && query.contains("self-loop")) {
                // handoff 指回自身：hop 永不收敛
                sendBytes(ex, 200, "application/json",
                        ("{\"archiveUrl\":\"" + base + "/api/v1/download?self-loop=1\"}")
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (query != null && query.contains("refuse")) {
                sendBytes(ex, 200, "application/json",
                        "{\"ok\":false,\"message\":\"no such skill\"}"
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
            // 相对路径 archiveUrl：以 registry origin 补全
            sendBytes(ex, 200, "application/json",
                    ("{\"archiveUrl\":\"/skill.zip\"}").getBytes(StandardCharsets.UTF_8));
        });
        // 大响应（配合 MAX_SKILL_BUNDLE_SIZE_MB 测试钩子；.zip 后缀保证走直链分支）
        server.createContext("/big.zip", ex -> sendBytes(ex, 200,
                "application/octet-stream", new byte[2 * 1024 * 1024]));
    }

    private static void sendBytes(HttpExchange ex, int status, String contentType, byte[] body)
            throws java.io.IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
        ex.close();
    }

    /** 手工构造含 ../ 条目名的 zip（ZipOutputStream 会原样写入条目名）。 */
    private static byte[] zipWithSlipEntry(String slipName) {
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(buf)) {
                zos.putNextEntry(new java.util.zip.ZipEntry(slipName));
                zos.write("evil".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry("SKILL.md"));
                zos.write(SKILL_MD.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return buf.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 直链 zip ────────────────────────────────────────────────────────

    @Test
    void directZipRegisters() {
        SkillSourceFetcher.Fetched fetched =
                SkillSourceFetcher.fetchNormalizedSkillBundle(base + "/skill.zip");
        assertThat(fetched.bundle().name).isEqualTo("weather");
        assertThat(fetched.bundle().version).isEqualTo("1.2.0");
        // 重打的 zip 以 skill 根为界，SKILL.md 在根上
        assertThat(fetched.bundle().files).containsKey("SKILL.md");
        assertThat(fetched.bundle().sha256)
                .isEqualTo(SkillBundleParser.skillArchiveSHA256(fetched.archive()));
        // 重打归档可用 ParseSkillBundle 原路解析
        SkillBundleParser.SkillBundle reparsed =
                SkillBundleParser.parseSkillBundle(fetched.archive());
        assertThat(reparsed.name).isEqualTo("weather");
    }

    @Test
    void markdownSourceSynthesizesSkillMd() {
        SkillSourceFetcher.Fetched fetched =
                SkillSourceFetcher.fetchNormalizedSkillBundle(base + "/SKILL.md");
        assertThat(fetched.bundle().name).isEqualTo("weather");
        assertThat(fetched.bundle().files.keySet()).containsExactly("SKILL.md");
        assertThat(fetched.bundle().sha256)
                .isEqualTo(SkillBundleParser.skillArchiveSHA256(fetched.archive()));
    }

    @Test
    void redirectChainFollowsAndRevalidatesEachHop() {
        SkillSourceFetcher.Fetched fetched =
                SkillSourceFetcher.fetchNormalizedSkillBundle(base + "/redirect.zip");
        assertThat(fetched.bundle().name).isEqualTo("weather");
    }

    @Test
    void redirectBlockedBySsrfKeepsFormatMessage() {
        assertThatThrownBy(() ->
                SkillSourceFetcher.fetchNormalizedSkillBundle(base + "/redirect-internal.zip"))
                .isInstanceOf(TenantSkillService.SkillSourceInvalidException.class)
                .hasMessageStartingWith("skill source is invalid: skill source 未通过安全校验：")
                .hasMessageContaining("redirect blocked: target URL failed SSRF validation");
    }

    @Test
    void non200CarriesPreviewBody() {
        assertThatThrownBy(() ->
                SkillSourceFetcher.fetchNormalizedSkillBundle(base + "/missing.zip"))
                .isInstanceOf(TenantSkillService.SkillSourceInvalidException.class)
                .hasMessage("skill source is invalid: download returned HTTP 404: nope");
    }

    @Test
    void emptyBodyRejected() {
        assertThatThrownBy(() ->
                SkillSourceFetcher.fetchNormalizedSkillBundle(base + "/empty.zip"))
                .isInstanceOf(TenantSkillService.SkillSourceInvalidException.class)
                .hasMessage("skill source is invalid: remote returned an empty body");
    }

    @Test
    void nonArchiveBodyRejected() {
        assertThatThrownBy(() ->
                SkillSourceFetcher.fetchNormalizedSkillBundle(base + "/plain.zip"))
                .isInstanceOf(TenantSkillService.SkillSourceInvalidException.class)
                .hasMessage("skill source is invalid: remote did not return a zip skill bundle");
    }

    @Test
    void zipSlipEntryRejected() {
        assertThatThrownBy(() ->
                SkillSourceFetcher.fetchNormalizedSkillBundle(base + "/slip.zip"))
                .isInstanceOf(SkillBundleParser.BundleInvalidException.class)
                .hasMessage("skill bundle is invalid: entry \"../evil.txt\" "
                        + "escapes the archive root");
    }

    // ── registry handoff ────────────────────────────────────────────────

    @Test
    void registryHandoffResolvesRelativeArchiveUrl() {
        SkillSourceFetcher.Fetched fetched =
                SkillSourceFetcher.fetchNormalizedSkillBundle(base + "/skills/weather");
        assertThat(fetched.bundle().name).isEqualTo("weather");
    }

    @Test
    void handoffLoopHitsHopLimit() {
        assertThatThrownBy(() ->
                SkillSourceFetcher.fetchNormalizedSkillBundle(
                        base + "/skills/self-loop-skill"))
                .isInstanceOf(TenantSkillService.SkillSourceInvalidException.class)
                .hasMessage("skill source is invalid: too many source redirects");
    }

    @Test
    void registryRefusalCarriesRemoteMessage() {
        assertThatThrownBy(() ->
                SkillSourceFetcher.fetchNormalizedSkillBundle(base + "/skills/refuse-me"))
                .isInstanceOf(TenantSkillService.SkillSourceInvalidException.class)
                .hasMessage("skill source is invalid: no such skill");
    }

    // ── 限额与 SSRF ─────────────────────────────────────────────────────

    @Test
    void sizeLimitRejectsOversizedBody() {
        SkillSourceFetcher.maxSkillBundleSizeMBOverride = 1L;
        assertThatThrownBy(() ->
                SkillSourceFetcher.fetchNormalizedSkillBundle(base + "/big.zip"))
                .isInstanceOf(TenantSkillService.SkillSourceInvalidException.class)
                .hasMessage("skill source is invalid: skill bundle cannot exceed 1 MB");
    }

    @Test
    void ssrfRejectionForNonWhitelistedDirectIp() {
        // 白名单只放行 127.0.0.1；直连 IP 拒绝发生在 DNS 之前（无网络）
        assertThatThrownBy(() ->
                SkillSourceFetcher.fetchNormalizedSkillBundle("http://127.0.0.9:1/x.zip"))
                .isInstanceOf(TenantSkillService.SkillSourceInvalidException.class)
                .hasMessageStartingWith("skill source is invalid: skill source 未通过安全校验：")
                .hasMessageContaining("direct IP address access is not allowed");
    }

    // ── 解析层（不出网分支） ─────────────────────────────────────────────

    @Test
    void parserMapsSourceForms() {
        assertParsed(SkillSource.parse("@owner/slug"), SkillSource.KIND_REGISTRY,
                "https://clawhub.ai", "slug", "owner");
        assertParsed(SkillSource.parse("my-skill"), SkillSource.KIND_REGISTRY,
                "https://clawhub.ai", "my-skill", "");
        String[] sv = SkillSource.parse("my-skill@1.2.0").version().isEmpty()
                ? new String[0] : new String[]{"1.2.0"};
        assertThat(sv).containsExactly("1.2.0");

        SkillSource.Parsed sh = SkillSource.parse("skills-sh:owner/repo/slug");
        assertThat(sh.kind()).isEqualTo(SkillSource.KIND_SKILLS_SH);
        assertThat(SkillSource.fetchURL(sh)).isEqualTo(
                "https://clawhub.ai/api/v1/skills/slug/install?reference="
                        + "skills-sh%3Aowner%2Frepo%2Fslug");

        SkillSource.Parsed gh = SkillSource.parse(
                "https://github.com/owner/repo/tree/v1/skills/weather");
        assertThat(gh.kind()).isEqualTo(SkillSource.KIND_GITHUB);
        assertThat(gh.ref()).isEqualTo("v1");
        assertThat(gh.subdir()).isEqualTo("skills/weather");
        assertThat(SkillSource.fetchURL(gh)).isEqualTo(
                "https://codeload.github.com/owner/repo/zip/v1");

        SkillSource.Parsed gl = SkillSource.parse(
                "https://gitlab.com/group/project/-/tree/main/skills/demo");
        assertThat(gl.kind()).isEqualTo(SkillSource.KIND_GITLAB);
        assertThat(SkillSource.fetchURL(gl)).isEqualTo(
                "https://gitlab.com/group/project/-/archive/main/project-main.zip");

        SkillSource.Parsed self = SkillSource.parse(base + "/skills/weather");
        assertThat(self.kind()).isEqualTo(SkillSource.KIND_REGISTRY);
        assertThat(self.slug()).isEqualTo("weather");
        assertThat(SkillSource.fetchURL(self)).isEqualTo(
                base + "/api/v1/download?slug=weather");
    }

    @Test
    void ambiguousSlashSourceIsRefused() {
        assertThatThrownBy(() -> SkillSource.parse("owner/slug"))
                .isInstanceOf(TenantSkillService.SkillSourceInvalidException.class)
                .hasMessage("skill source is invalid: \"owner/slug\" is ambiguous; "
                        + "use @owner/slug for ClawHub, or paste a github.com / gitlab.com / "
                        + "skills.sh / skillhub URL");
    }

    private static void assertParsed(SkillSource.Parsed p, String kind,
            String registry, String slug, String owner) {
        assertThat(p.kind()).isEqualTo(kind);
        assertThat(p.registry()).isEqualTo(registry);
        assertThat(p.slug()).isEqualTo(slug);
        assertThat(p.owner()).isEqualTo(owner);
    }
}
