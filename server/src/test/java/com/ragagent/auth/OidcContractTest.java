package com.ragagent.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.TestSchema;
import com.ragagent.auth.service.OidcStateCodec;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 波 2 扫尾批 2 契约测试：auth OIDC 4 端点（未配置 = disabled 分支全覆盖）。
 *
 * golden 来源：Go dev server（localhost:8080，2026-09-19 录制，scripts/record-oidc-golden.sh，
 * 13 条 oidc-*.json）。
 *
 * golden 两种形态：
 * - JSON 端点（config / url / start 出错分支）：响应体逐字节。
 * - 302 端点（callback 全部分支）：合成信封 JSON（键字母序 body/location/set_cookie/status），
 *   测试从 MockMvc 结果组同一信封比对——复刻 gin Redirect 字节行为
 *   （body=`<a href="<html 转义 Location>">Found</a>.\n\n`）。
 *
 * 合法 state 由测试经 OidcStateCodec 现签（与录制脚本的 python 锻造同密钥族：
 * 两侧都吃 JWT_SECRET；契约测试自签自验，响应内不含 state/nonce，故无掩码项）。
 * state/nonce 字母表为 base64url，query 携带无编码歧义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OidcContractTest {

    private static final String NONCE = "oidcprobemain1234567890abcdef";
    private static final String REDIRECT = "https://portal.example.com/oidc/callback";
    private static final String OTHER_NONCE = "some-other-nonce-value-0000";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OidcStateCodec stateCodec;

    @BeforeEach
    void seed() {
        // login-failed 分支会经 resolveDefaultTenantMode 读 system_settings（空表 → 缺省）
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    // ── 1) JSON 端点（disabled 分支，静态 golden 逐字节） ──────────────────

    @Test
    void oidcJsonEndpoints() throws Exception {
        assertGolden(get("/api/v1/auth/oidc/config"), 200, "oidc-config.json");
        assertGolden(get("/api/v1/auth/oidc/url"), 400, "oidc-url-noredirect.json");
        assertGolden(get("/api/v1/auth/oidc/url").param("redirect_uri", REDIRECT),
                403, "oidc-url-disabled.json");
        assertGolden(get("/api/v1/auth/oidc/start"), 403, "oidc-start-disabled.json");
    }

    // ── 2) callback：provider error 分支（含定制 escaper 特殊字符） ─────────

    @Test
    void callbackProviderError() throws Exception {
        assertEnvelope(get("/api/v1/auth/oidc/callback")
                        .param("error", "access_denied").param("error_description", "User denied"),
                "oidc-cb-error.json");
        // description 原文含 `+ & = ? # 空格`（对照录制脚本的 cb-error-specials）
        assertEnvelope(get("/api/v1/auth/oidc/callback")
                        .param("error", "a+b").param("error_description", "x+y&z=w?v#t u"),
                "oidc-cb-error-specials.json");
    }

    // ── 3) callback：state 家族（一切失败坍缩成 invalid_state，无 Set-Cookie） ──

    @Test
    void callbackStateFamily() throws Exception {
        assertEnvelope(get("/api/v1/auth/oidc/callback"), "oidc-cb-nostate.json");
        assertEnvelope(get("/api/v1/auth/oidc/callback").param("state", "garbage"),
                "oidc-cb-badstate.json");

        String stateOk = stateCodec.sign(NONCE, REDIRECT, 0);
        assertEnvelope(get("/api/v1/auth/oidc/callback").param("state", stateOk),
                "oidc-cb-state-nocookie.json");
        assertEnvelope(get("/api/v1/auth/oidc/callback").param("state", stateOk)
                        .cookie(new Cookie("weknora_oidc_nonce", OTHER_NONCE)),
                "oidc-cb-state-wrongnonce.json");

        String stateExpired = stateCodec.sign(NONCE, REDIRECT,
                Instant.now().getEpochSecond() - 3600);
        assertEnvelope(get("/api/v1/auth/oidc/callback").param("state", stateExpired)
                        .cookie(new Cookie("weknora_oidc_nonce", NONCE)),
                "oidc-cb-expired.json");
    }

    // ── 4) callback：合法 state + 配对 cookie（先清 cookie 再分派） ──────────

    @Test
    void callbackValidState() throws Exception {
        String stateOk = stateCodec.sign(NONCE, REDIRECT, 0);
        assertEnvelope(get("/api/v1/auth/oidc/callback").param("state", stateOk)
                        .cookie(new Cookie("weknora_oidc_nonce", NONCE)),
                "oidc-cb-missing-code.json");
        assertEnvelope(get("/api/v1/auth/oidc/callback").param("state", stateOk)
                        .param("code", "abc123")
                        .cookie(new Cookie("weknora_oidc_nonce", NONCE)),
                "oidc-cb-login-failed.json");
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    /** 静态 golden：状态码 + 响应体逐字节。 */
    private void assertGolden(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        assertEquals(golden(goldenName), raw(r), goldenName);
    }

    /** 302 信封 golden：MockMvc 结果组与录制脚本同构的信封 JSON 后逐字节比对。 */
    private void assertEnvelope(MockHttpServletRequestBuilder req, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(302, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));

        String setCookie = null;
        for (String v : r.getResponse().getHeaders("Set-Cookie")) {
            if (v != null && v.startsWith("weknora_oidc_nonce=")) {
                // MockHttpServletResponse 会解析 Set-Cookie 并重序列化（Max-Age=0 时被补
                // epoch Expires）；真容器（Tomcat）原样透传，字节由 A/B 钉住。此处还原。
                setCookie = v.replace("; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT;",
                        "; Max-Age=0;");
                break;
            }
        }
        Map<String, Object> env = new LinkedHashMap<>(); // 键字母序（对照录制脚本 sort_keys）
        env.put("body", raw(r));
        env.put("location", r.getResponse().getHeader("Location"));
        env.put("set_cookie", setCookie);
        env.put("status", 302);
        assertEquals(golden(goldenName), MAPPER.writeValueAsString(env), goldenName);
    }

    private static String raw(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static String golden(String name) throws Exception {
        return new String(new ClassPathResource("contracts/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
    }
}
