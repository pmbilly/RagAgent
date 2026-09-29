package com.ragagent.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 会话附件（临时文档）的契约测试（波 1 G5）。
 * golden：scripts/record-attachment-golden.sh（Go 实录）。
 * 钉住：上传 202 uploaded；expires_at 本地偏移 vs created_at Z（双形态）；
 * file_type 带点；text 终态 metadata {"parser":"plain_text"} + image_refs null；
 * 非 multipart 的 FormFile 原文；删除幂等 204。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttachmentContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String USER_ID = "11111111-2222-3333-4444-555555555501";
    private static final String USER_EMAIL = "attachment-contract@weknora.test";
    private static final String UNKNOWN_ID = "11111111-2222-3333-4444-999999999999";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"([a-z_]+)\":\"[2-9]\\d{3}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");
    /** 动态 id：两侧各自生成（对照 G1 的掩码口径，按键名掩 UUID）。 */
    private static final Pattern UUID_FIELDS = Pattern.compile(
            "\"(id|session_id|attachment_id)\":\"[0-9a-f-]{32,36}\"");
    private static final byte[] TXT_CONTENT =
            "第一行内容\n第二行内容\n第三行内容\n".getBytes(StandardCharsets.UTF_8);

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

    private String bearer;
    private String sid;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM temporary_documents");

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("attachment-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(USER_ID);
        user.setUsername("attowner");
        user.setEmail(USER_EMAIL);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);

        TenantMember member = new TenantMember();
        member.setUserId(USER_ID);
        member.setTenantId(TENANT);
        member.setRole("owner");
        member.setStatus("active");
        memberMapper.insert(member);

        bearer = "Bearer " + login(USER_EMAIL);
        MvcResult r = perform(jsonBody(post("/api/v1/sessions"), "{\"title\":\"\"}")
                .header("Authorization", bearer));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(raw(r));
        assertThat(m.find()).isTrue();
        sid = m.group(1);
    }

    // ════════ 上传 ════════

    @Test
    void uploadMatchesGo() throws Exception {
        MvcResult r = perform(multipart("/api/v1/sessions/" + sid + "/attachments")
                .file(new MockMultipartFile("file", "note.txt", "text/plain", TXT_CONTENT))
                .header("Authorization", bearer));
        assertEquals(202, r.getResponse().getStatus(), raw(r));
        String body = raw(r);
        assertThat(body).contains("\"status\":\"uploaded\"")
                .contains("\"fileType\":\".txt\"")
                .contains("\"imageRefs\":[]")
                .contains("\"metadata\":{}")
                .contains("\"fileName\":\"note.txt\"")
                .contains("\"fileSize\":48")
                .doesNotContain("\"success\"");  // 裸资源：无信封
        assertEquals(mask(golden("att-upload.json")), mask(body));
    }

    @Test
    void uploadWithoutMultipartMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions/" + sid + "/attachments"), "{}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("att-upload-no-file.json"), raw(r));
    }

    @Test
    void uploadUnknownSessionMatchesGo() throws Exception {
        MvcResult r = perform(multipart("/api/v1/sessions/" + UNKNOWN_ID + "/attachments")
                .file(new MockMultipartFile("file", "note.txt", "text/plain", TXT_CONTENT))
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("att-upload-unknown-session.json"), raw(r));
    }

    @Test
    void uploadUnsupportedExtensionMatchesGo() throws Exception {
        MvcResult r = perform(multipart("/api/v1/sessions/" + sid + "/attachments")
                .file(new MockMultipartFile("file", "evil.exe", "application/octet-stream",
                        new byte[] { 'x' }))
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("att-upload-unsupported.json"), raw(r));
    }

    @Test
    void uploadEmptyFileMatchesGo() throws Exception {
        MvcResult r = perform(multipart("/api/v1/sessions/" + sid + "/attachments")
                .file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]))
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("att-upload-empty.json"), raw(r));
    }

    // ════════ 列表 / 详情 / 终态 ════════

    /**
     * 上传后轮询到 ready 终态（text 管线无 docreader，ms 级完成）。
     * 100×200ms=20s：全量重载下解析队列会被拖慢，4s 窗口实测假红一次
     * （§5.9 等待类变体——窗口放宽不影响断言语义，命中即退）。
     */
    private MvcResult awaitReady(String attId) throws Exception {
        for (int i = 0; i < 100; i++) {
            MvcResult r = perform(get("/api/v1/sessions/" + sid + "/attachments/" + attId)
                    .header("Authorization", bearer));
            if (r.getResponse().getStatus() == 200
                    && raw(r).contains("\"status\":\"ready\"")) {
                return r;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("attachment did not reach ready in time");
    }

    @Test
    void listAndGetReadyStateMatchGo() throws Exception {
        MvcResult up = perform(multipart("/api/v1/sessions/" + sid + "/attachments")
                .file(new MockMultipartFile("file", "note.txt", "text/plain", TXT_CONTENT))
                .header("Authorization", bearer));
        String attId = extractId(raw(up));

        MvcResult got = awaitReady(attId);
        String body = raw(got);
        assertThat(body).contains("\"metadata\":{\"parser\":\"plain_text\"}")
                .contains("\"imageRefs\":null")
                .contains("\"tokenCount\":11")
                .contains("\"chunkCount\":1");
        assertEquals(mask(golden("att-get.json")), mask(body));

        MvcResult list = perform(get("/api/v1/sessions/" + sid + "/attachments")
                .header("Authorization", bearer));
        assertEquals(mask(golden("att-list.json")), mask(raw(list)));
    }

    @Test
    void getUnknownAttachmentMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/attachments/" + UNKNOWN_ID)
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("att-get-404.json"), raw(r));
    }

    @Test
    void listUnknownSessionMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + UNKNOWN_ID + "/attachments")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("att-list-404.json"), raw(r));
    }

    // ════════ 预览 / 删除 ════════

    @Test
    void previewStreamsFileWithGoHeaders() throws Exception {
        MvcResult up = perform(multipart("/api/v1/sessions/" + sid + "/attachments")
                .file(new MockMultipartFile("file", "note.txt", "text/plain", TXT_CONTENT))
                .header("Authorization", bearer));
        String attId = extractId(raw(up));
        awaitReady(attId);

        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/attachments/" + attId
                + "/preview").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        // filetransport 头语义 + 字节体
        assertThat(r.getResponse().getHeader("Content-Type"))
                .isEqualTo("text/plain; charset=utf-8");
        assertThat(r.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("inline; filename=note.txt");
        assertThat(r.getResponse().getHeader("Cache-Control"))
                .isEqualTo("private, no-store");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .isEqualTo(new String(TXT_CONTENT, StandardCharsets.UTF_8));
    }

    @Test
    void previewUnknownAttachmentMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + sid + "/attachments/" + UNKNOWN_ID
                + "/preview").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("att-preview-404.json"), raw(r));
    }

    @Test
    void deleteIs204AndIdempotent() throws Exception {
        MvcResult up = perform(multipart("/api/v1/sessions/" + sid + "/attachments")
                .file(new MockMultipartFile("file", "note.txt", "text/plain", TXT_CONTENT))
                .header("Authorization", bearer));
        String attId = extractId(raw(up));

        MvcResult r = perform(delete("/api/v1/sessions/" + sid + "/attachments/" + attId)
                .header("Authorization", bearer));
        assertEquals(204, r.getResponse().getStatus(), raw(r));
        assertEquals("", raw(r));

        MvcResult list = perform(get("/api/v1/sessions/" + sid + "/attachments")
                .header("Authorization", bearer));
        assertEquals(golden("att-list-after-delete.json"), mask(raw(list)));

        // 再删：幂等 204
        MvcResult again = perform(delete("/api/v1/sessions/" + sid + "/attachments/" + attId)
                .header("Authorization", bearer));
        assertEquals(204, again.getResponse().getStatus(), raw(again));
    }

    // ════════ 工具 ════════
    private String extractId(String body) {
        Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private String login(String email) throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/auth/login"),
                "{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"));
        Matcher m = TOKEN.matcher(raw(r));
        assertThat(m.find()).as("login 响应应含 token: " + raw(r)).isTrue();
        return m.group(1);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andReturn();
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder,
                                                          String body) {
        return builder.contentType("application/json").content(body);
    }

    /** 按**原始字节**取响应体（MockMvc 默认 ISO-8859-1 会让中文变成 mojibake，§9）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper RAW_SEMANTIC_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String raw(MvcResult r) throws Exception {
        // PR4 语义比较：与 golden 同侧归一（非 JSON 文本原样）
        return com.ragagent.support.ContractJson.semantic(RAW_SEMANTIC_MAPPER,
                r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper GOLDEN_SEMANTIC_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String golden(String name) throws Exception {
        // PR4 语义比较：键序/HTML 转义归一后返回（非 JSON 文本原样），断言侧不变
        var resource = new org.springframework.core.io.ClassPathResource("contracts/" + name);
        if (!resource.exists()) {
            resource = new org.springframework.core.io.ClassPathResource("contracts/" + name + ".json");
        }
        String text = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return com.ragagent.support.ContractJson.semantic(GOLDEN_SEMANTIC_MAPPER, text);
    }

    /** 掩码真实时间戳（expires/created/updated/started/ready 两侧都动态）。 */
    private static String mask(String s) {
        String masked = UUID_FIELDS.matcher(s).replaceAll("\"$1\":\"<uuid>\"");
        return TS_VALUE.matcher(masked).replaceAll("\"$1\":\"<ts>\"");
    }
}
