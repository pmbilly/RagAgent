package com.ragagent.browserskill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.TestSchema;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.browserskill.service.BrowserSkillManager;
import com.ragagent.browserskill.service.BrowserSkillStore;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 波 3 browserskill 批契约测试：/me/browser 账户面 + /me/browser/extension 下载 +
 * /api/v1/local-browser 三条免 Auth 路由的鉴权失败族（对照 golden 逐字节）。
 *
 * golden 来源：Go dev server（localhost:8080，2026-09-19 录制，
 * scripts/record-bs-golden.sh，43 条 bs-*.json + 1 对 bs-download.bin[.headers]）。
 *
 * ⚠️ 录制环境：两侧 server 均带 BROWSERSKILL_BINARY=/usr/bin/false +
 * CLUSTER_SECRET + EXTENSION_PATH（record 脚本头注释）——本测试用
 * {@link EnabledManagerConfig} 复刻同一部署形态（H2 播种同一用户/成员关系）。
 *
 * ⚠️ 状态依赖：真实服务器跨请求持久（pair → authorize 兑换 → WS → revoke 是
 * 同一条状态链），所以主流程收敛为**单个**按录制序执行的方法
 * {@code browserFlowGolden}；拆成多个 @Test 会被每方法的 resetData 清掉状态
 * （首轮实测：pairingToken/device 行全丢 → 假红）。
 *
 * 掩码面（与 ab-bs.sh 一致）：pairing_link 的一次性 token、device_id/device.id、
 * expires_at/renew_after/created_at/last_seen_at。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BrowserSkillContractTest {

    private static final String SECRET = "bs-ab-cluster-secret-0123456789abcdef0123456789abcdef";
    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String OWNER_EMAIL = "java-phase1@weknora.test";
    private static final String EXTENSION_ORIGIN = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";

    /**
     * 对照录制 env：BROWSERSKILL_BINARY=/usr/bin/false（Enabled=true 但 daemon
     * 必然起不来 → WS 握手 503 "browser runtime unavailable"）。extensionPath
     * 指向测试构建目录里的固定 zip——字节与 golden 一致，mtime 钉到 golden 的
     * Last-Modified（录制机的 /tmp 文件 mtime，跨机器对齐该头）。
     */
    @TestConfiguration
    static class EnabledManagerConfig {
        @Bean
        @Primary
        BrowserSkillManager enabledBrowserSkillManager(ObjectMapper mapper, BrowserSkillStore store)
                throws Exception {
            Path zip = Path.of("build", "test-bs-ext", "browser-skill-weknora.zip");
            Files.createDirectories(zip.getParent());
            byte[] bytes = new ClassPathResource("contracts/bs-download.bin").getInputStream().readAllBytes();
            if (!Files.exists(zip)) {
                Files.write(zip, bytes);
            }
            // golden 的 Last-Modified 头（bs-download.bin.headers）→ 文件 mtime 对齐
            String headers = new String(
                    new ClassPathResource("contracts/bs-download.bin.headers").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            java.time.OffsetDateTime goldenMtime = null;
            for (String line : headers.split("\n")) {
                if (line.toLowerCase().startsWith("last-modified:")) {
                    goldenMtime = com.ragagent.browserskill.service.BrowserSkillHttp.parseHttpTime(
                            line.substring(line.indexOf(':') + 1).trim());
                }
            }
            if (goldenMtime != null) {
                Files.setLastModifiedTime(zip, FileTime.from(goldenMtime.toInstant()));
            }
            return new BrowserSkillManager(mapper, store, "/usr/bin/false", "", SECRET, "",
                    zip.toAbsolutePath().toString());
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private TenantMemberMapper memberMapper;
    @Autowired
    private BrowserSkillManager manager;
    @Autowired
    private BrowserSkillStore store;

    private String token;

    private void seedOnce() throws Exception {
        if (token != null) {
            return;
        }
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(OWNER);
        user.setUsername("phase1test");
        user.setEmail(OWNER_EMAIL);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);

        TenantMember member = new TenantMember();
        member.setUserId(OWNER);
        member.setTenantId(TENANT);
        member.setRole("owner");
        member.setStatus("active");
        member.setJoinedAt(java.time.OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, java.time.ZoneOffset.UTC));
        memberMapper.insert(member);

        token = login();
    }

    // ── 1) 鉴权族 + API-Key default deny（无状态） ───────────────────────────

    @Test
    @Order(1)
    void authFamily() throws Exception {
        seedOnce();
        assertGolden(get("/api/v1/me/browser"), 401, "bs-account-unauth.json");
        assertGolden(get("/api/v1/me/browser").header("Authorization", "Bearer garbage-token"),
                401, "bs-account-badtoken.json");
        // API-Key 直访：/me/browser 未声明进 APIKeyRoutePolicies → default deny
        //（对照 Go：路由直接注册在 v1 上、未经 apiKeyGroup 声明）
        MvcResult created = mockMvc.perform(
                        post("/api/v1/tenants/10002/api-keys")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"bs-ab-key\",\"full_access\":true}"))
                .andReturn();
        Matcher createdToken = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(raw(created));
        assertTrue(createdToken.find(), "create 响应应含明文 token: " + raw(created));
        assertGolden(get("/api/v1/me/browser").header("X-API-Key", createdToken.group(1)),
                403, "bs-account-apikey-denied.json");
    }

    // ── 2) 主流程：按录制序重放全部状态依赖场景 ─────────────────────────────

    @Test
    @Order(2)
    void browserFlowGolden() throws Exception {
        seedOnce();
        String auth = "Bearer " + token;

        // ═══ 3) GET 空形态 + POST 校验族 ═══
        assertGolden(get("/api/v1/me/browser").header("Authorization", auth),
                200, "bs-account-get.json");
        assertGolden(post("/api/v1/me/browser").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("not-json"),
                400, "bs-account-post-badjson.json");
        String oversize = "{\"action\":\"pair\",\"origin\":\"" + "x".repeat(4200) + "\"}";
        assertGolden(post("/api/v1/me/browser").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(oversize),
                400, "bs-account-post-oversize.json");
        assertGolden(post("/api/v1/me/browser").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"bogus\"}"),
                400, "bs-account-post-unknown.json");
        assertGolden(post("/api/v1/me/browser").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pair\",\"origin\":\"example.com\"}"),
                409, "bs-pair-origin-noscheme.json");
        assertGolden(post("/api/v1/me/browser").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pair\",\"origin\":\"http://example.com\"}"),
                409, "bs-pair-origin-wss.json");
        // 成功 pair：mask 后比对，并记下一次性票据供 authorize 兑换
        MvcResult paired = mockMvc.perform(post("/api/v1/me/browser")
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pair\",\"origin\":\"https://browser.example.com\"}"))
                .andReturn();
        assertEquals(200, paired.getResponse().getStatus(), raw(paired));
        Matcher link = Pattern.compile("pairing_link\":\"[^\"]*#([^\"]+)\"").matcher(raw(paired));
        assertTrue(link.find(), "pairing_link 应含一次性票据");
        String pairingToken = link.group(1);
        assertMasked(paired, 200, "bs-pair-ok.json");

        // ═══ 4) authorize 失败族 + 一次性票据兑换 ═══
        String authz = "/api/v1/local-browser/extension/authorize";
        String deviceToken = randomB64Token();
        assertPlain(post(authz).header("Authorization", "Bearer " + pairingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"renew\",\"next_token\":\"x\"}"),
                403, "bs-authz-no-origin.json");
        assertPlain(post(authz).header("Origin", "https://example.com")
                        .header("Authorization", "Bearer " + pairingToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                403, "bs-authz-bad-origin.json");
        assertPlain(post(authz).header("Origin", EXTENSION_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                400, "bs-authz-no-token.json");
        assertPlain(post(authz).header("Origin", EXTENSION_ORIGIN)
                        .header("Authorization", "Bearer short")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                400, "bs-authz-bad-token.json");
        assertPlain(post(authz).header("Origin", EXTENSION_ORIGIN)
                        .header("Authorization", "Bearer " + pairingToken)
                        .content("not-json"),
                400, "bs-authz-bad-body.json");
        assertPlain(post(authz).header("Origin", EXTENSION_ORIGIN)
                        .header("Authorization", "Bearer " + pairingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pair\",\"next_token\":\"short\"}"),
                400, "bs-authz-bad-next.json");
        assertPlain(post(authz).header("Origin", EXTENSION_ORIGIN)
                        .header("Authorization", "Bearer " + pairingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pair\",\"next_token\":\"" + pairingToken + "\"}"),
                400, "bs-authz-same-token.json");
        assertPlain(post(authz).header("Origin", EXTENSION_ORIGIN)
                        .header("Authorization", "Bearer " + pairingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"bogus\",\"next_token\":\"" + randomB64Token() + "\"}"),
                400, "bs-authz-bad-action.json");
        assertPlain(post(authz).header("Origin", EXTENSION_ORIGIN)
                        .header("Authorization", "Bearer " + pairingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pair\",\"next_token\":\"" + randomB64Token()
                                + "\",\"label\":\"" + "L".repeat(101) + "\"}"),
                400, "bs-authz-label-long.json");
        assertPlain(post(authz).header("Origin", EXTENSION_ORIGIN)
                        .header("Authorization", "Bearer " + pairingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"renew\",\"next_token\":\"" + randomB64Token() + "\"}"),
                401, "bs-authz-renew-unpaired.json");
        // 成功兑换：一次性票据被消费（device_id/时间戳掩码；响应尾随换行=Encoder）
        MvcResult redeemed = mockMvc.perform(post(authz)
                        .header("Origin", EXTENSION_ORIGIN)
                        .header("Authorization", "Bearer " + pairingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pair\",\"next_token\":\"" + deviceToken
                                + "\",\"label\":\"AB Device\"}"))
                .andReturn();
        assertEquals(200, redeemed.getResponse().getStatus(), raw(redeemed));
        assertMasked(redeemed, 200, "bs-authz-pair-ok.json");
        // 一次性：同票据复兑 → 401
        assertPlain(post(authz).header("Origin", EXTENSION_ORIGIN)
                        .header("Authorization", "Bearer " + pairingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pair\",\"next_token\":\"" + deviceToken
                                + "\",\"label\":\"AB Device\"}"),
                401, "bs-authz-pair-reuse.json");

        // ═══ 5) GET 配对后形态（device 出现） ═══
        assertMasked(mockMvc.perform(get("/api/v1/me/browser")
                        .header("Authorization", auth)).andReturn(),
                200, "bs-account-get-paired.json");

        // ═══ 6) extension WS 握手失败族（免 Auth 路由） ═══
        String ext = "/api/v1/local-browser/extension";
        assertPlain(get(ext), 403, "bs-ext-no-origin.json");
        assertPlain(get(ext).header("Origin", "https://example.com"), 403, "bs-ext-bad-origin.json");
        assertPlain(get(ext).header("Origin", EXTENSION_ORIGIN), 401, "bs-ext-no-proto.json");
        assertPlain(get(ext).header("Origin", EXTENSION_ORIGIN)
                        .header("Sec-WebSocket-Protocol", "other-protocol"),
                401, "bs-ext-bad-proto.json");
        assertPlain(get(ext).header("Origin", EXTENSION_ORIGIN)
                        .header("Sec-WebSocket-Protocol", "bsk-auth.short"),
                401, "bs-ext-bad-token-len.json");
        assertPlain(get(ext).header("Origin", EXTENSION_ORIGIN)
                        .header("Sec-WebSocket-Protocol", "bsk-auth." + randomB64Token()),
                401, "bs-ext-unpaired.json");
        // 已兑换设备令牌 + daemon 起不来 → 503（连接失败分类）
        assertPlain(get(ext).header("Origin", EXTENSION_ORIGIN)
                        .header("Sec-WebSocket-Protocol", "bsk-auth." + deviceToken),
                503, "bs-ext-runtime.json");

        // ═══ 7) extension 下载（头 + 字节） ═══
        assertGolden(get("/api/v1/me/browser/extension"), 401, "bs-download-unauth.json");
        MvcResult download = mockMvc.perform(get("/api/v1/me/browser/extension")
                .header("Authorization", auth)).andReturn();
        assertEquals(200, download.getResponse().getStatus(), raw(download));
        byte[] expectedBytes = new ClassPathResource("contracts/bs-download.bin")
                .getInputStream().readAllBytes();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(expectedBytes);
        // 契约头逐个比对（忽略 Date/X-Request-Id 等容器噪音；Last-Modified 靠
        // EnabledManagerConfig 把 mtime 钉到 golden 值）
        for (String line : golden("bs-download.bin.headers").split("\n")) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String name = line.substring(0, idx).trim();
            String lower = name.toLowerCase();
            if (lower.equals("date") || lower.equals("x-request-id") || lower.equals("keep-alive")
                    || lower.equals("connection")) {
                continue;
            }
            String expectedHeader = line.substring(idx + 1).trim();
            assertThat(download.getResponse().getHeader(name))
                    .as("bs-download: %s", name)
                    .isEqualTo(expectedHeader);
        }
        assertThat(download.getResponse().getHeaderNames())
                .contains("Content-Type", "Content-Disposition", "Content-Length",
                        "Accept-Ranges", "Last-Modified");

        // ═══ 8) internal 签名族（HMAC 消息 = ts + "\n" + nonce + "\n" + body） ═══
        String internal = "/api/v1/local-browser/internal";
        String body = "{\"node\":\"unknown-node\",\"scope\":{\"tenant\":10002,\"user\":\"someone\"},"
                + "\"session\":\"\",\"operation\":\"status\",\"method\":\"\"}";
        long ts = Instant.now().getEpochSecond();
        String nonce = randomHex32();
        String sig = sign(ts + "\n" + nonce + "\n" + body);
        assertPlain(post(internal), 401, "bs-internal-no-ts.json");
        assertPlain(post(internal).header("X-Browser-Timestamp", Long.toString(ts - 120))
                        .header("X-Browser-Nonce", nonce).header("X-Browser-Signature", sig)
                        .content(body),
                401, "bs-internal-stale-ts.json");
        assertPlain(post(internal).header("X-Browser-Timestamp", Long.toString(ts + 120))
                        .header("X-Browser-Nonce", nonce).header("X-Browser-Signature", sig)
                        .content(body),
                401, "bs-internal-future-ts.json");
        assertPlain(post(internal).header("X-Browser-Timestamp", Long.toString(ts))
                        .header("X-Browser-Nonce", nonce).header("X-Browser-Signature", "zz" + sig)
                        .content(body),
                401, "bs-internal-nonhex-sig.json");
        assertPlain(post(internal).header("X-Browser-Timestamp", Long.toString(ts))
                        .header("X-Browser-Nonce", nonce)
                        .header("X-Browser-Signature", sig.substring(0, 63) + "0")
                        .content(body),
                401, "bs-internal-bad-sig.json");
        String shortNonceSig = sign(ts + "\nshort\n" + body);
        assertPlain(post(internal).header("X-Browser-Timestamp", Long.toString(ts))
                        .header("X-Browser-Nonce", "short")
                        .header("X-Browser-Signature", shortNonceSig)
                        .content(body),
                401, "bs-internal-bad-nonce.json");
        // 全部通过 → node 不匹配 409；随后同请求重放 → 防重放 409（nonce 已登记）
        assertPlain(post(internal).header("X-Browser-Timestamp", Long.toString(ts))
                        .header("X-Browser-Nonce", nonce).header("X-Browser-Signature", sig)
                        .contentType(MediaType.APPLICATION_JSON).content(body),
                409, "bs-internal-wrong-node.json");
        assertPlain(post(internal).header("X-Browser-Timestamp", Long.toString(ts))
                        .header("X-Browser-Nonce", nonce).header("X-Browser-Signature", sig)
                        .contentType(MediaType.APPLICATION_JSON).content(body),
                409, "bs-internal-replay.json");
        assertPlain(post(internal).header("X-Browser-Timestamp", Long.toString(ts))
                        .header("X-Browser-Nonce", nonce).header("X-Browser-Signature", sig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pad\":\"" + "x".repeat(1050000) + "\"}"),
                400, "bs-internal-oversize.json");

        // ═══ 9) revoke 收尾（状态收敛） ═══
        assertGolden(post("/api/v1/me/browser").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"revoke\"}"),
                200, "bs-revoke.json");
        assertGolden(get("/api/v1/me/browser").header("Authorization", auth),
                200, "bs-account-get-revoked.json");
    }

    // ── 3) 禁用形态（deployment 默认；Go 侧无 env 实测过文案） ────────────────

    @Test
    @Order(3)
    void disabledManagerForms() throws Exception {
        seedOnce();
        BrowserSkillManager disabled = new BrowserSkillManager(new ObjectMapper(), store, "", "", "", "", "");
        assertEquals(false, disabled.enabled());
        MockHttpServletResponse res = new MockHttpServletResponse();
        disabled.authorizeHTTP(new MockHttpServletRequest("POST",
                "/api/v1/local-browser/extension/authorize"), res);
        assertEquals(503, res.getStatus());
        assertEquals("browser unavailable\n", res.getContentAsString(StandardCharsets.UTF_8));

        MockHttpServletResponse dres = new MockHttpServletResponse();
        disabled.downloadExtension(new MockHttpServletRequest("GET", "/x"), dres);
        assertEquals(404, dres.getStatus());
        assertEquals("extension package is not configured\n",
                dres.getContentAsString(StandardCharsets.UTF_8));

        MockHttpServletResponse eres = new MockHttpServletResponse();
        disabled.serveExtension(new MockHttpServletRequest("GET", "/x"), eres);
        assertEquals(503, eres.getStatus());
        assertEquals("unavailable\n", eres.getContentAsString(StandardCharsets.UTF_8));

        MockHttpServletResponse ires = new MockHttpServletResponse();
        disabled.internalHTTP(new MockHttpServletRequest("POST", "/x"), ires);
        assertEquals(404, ires.getStatus());
        assertEquals("unavailable\n", ires.getContentAsString(StandardCharsets.UTF_8));

        // 405 分支需要 Enabled=true（禁用态在 Enabled 检查先落 503）+ 合法 extension origin：
        // 经 gin 不可达（authorize 路由只注册 POST），这里钉 manager 级行为
        MockHttpServletResponse mres = new MockHttpServletResponse();
        manager.authorizeHTTP(new MockHttpServletRequest("GET", "/x") {{
            addHeader("Origin", EXTENSION_ORIGIN);
        }}, mres);
        assertEquals(405, mres.getStatus());
        assertEquals("method not allowed\n", mres.getContentAsString(StandardCharsets.UTF_8));
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + OWNER_EMAIL + "\",\"password\":\"Passw0rd!\"}"))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), raw(result));
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(raw(result));
        assertTrue(m.find(), "login 响应应含 token: " + raw(result));
        return m.group(1);
    }

    private static String randomB64Token() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String randomHex32() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String sign(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 静态/纯文本 golden：状态码 + 逐字节（http.Error 体含尾随换行）。 */
    private void assertGolden(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                              int status, String goldenName) throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        assertEquals(golden(goldenName), raw(r), goldenName);
    }

    private void assertPlain(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                             int status, String goldenName) throws Exception {
        assertGolden(req, status, goldenName);
    }

    /** 掩码 golden（token/device_id/时间戳）。 */
    private void assertMasked(MvcResult r, int status, String goldenName) throws Exception {
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        assertEquals(mask(golden(goldenName)), mask(raw(r)), goldenName);
    }

    // 掩码规则（与 ab-bs.sh 的 mask 同族）
    private static final Pattern LINK_TOKEN =
            Pattern.compile("(\"pairing_link\":\")([^\"]+)(\")");
    private static final Pattern DEVICE_ID =
            Pattern.compile("(\"device_id\":\")([0-9a-f]{32})(\")");
    private static final Pattern DEVICE_NESTED_ID =
            Pattern.compile("(\"device\":\\{\"id\":\")([0-9a-f]{32})(\")");
    private static final Pattern DEVICE_TS = Pattern.compile(
            "(\"(?:expires_at|renew_after|created_at|last_seen_at)\":\")([^\"]*)(\")");

    private static String mask(String s) {
        s = LINK_TOKEN.matcher(s).replaceAll("$1" + "<link>" + "$3");
        s = DEVICE_NESTED_ID.matcher(s).replaceAll("$1" + "<device_id>" + "$3");
        s = DEVICE_ID.matcher(s).replaceAll("$1" + "<device_id>" + "$3");
        s = DEVICE_TS.matcher(s).replaceAll("$1" + "<ts>" + "$3");
        return s;
    }

    private static String golden(String name) throws Exception {
        return new String(new ClassPathResource("contracts/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /** 响应体按 UTF-8 字节解码（陷阱清单第 12 条）。 */
    private static String raw(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
