package com.ragagent.datasource.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import com.ragagent.apikey.filter.APIKeyRouteAuthorizer;
import com.ragagent.apikey.filter.APIKeyRoutePolicies;
import com.ragagent.apikey.filter.APIKeyRoutePolicy;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.DataSourceSyncTaskQueue;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 数据源 HTTP 层的契约测试（对照 Go {@code internal/handler/datasource.go} +
 * {@code datasource_credentials.go} 的 17 个端点，路由见
 * {@code internal/router/routes_infra.go} L292-333）。
 *
 * <h2>期望值来源：Go 实录</h2>
 * <p>全部 golden 都是对<b>运行中的 Go dev server</b>（:8080，db=localhost:15432）
 * 打真实请求录下来的，录制脚本是 {@code scripts/record-datasource-golden.sh}，
 * 文件在 {@code server/src/test/resources/contracts/ds-*.json}。</p>
 *
 * <p><b>录制前的额外准备</b>：Go server 带
 * {@code SSRF_WHITELIST=127.0.0.1,::1,localhost} 启动，且 127.0.0.1:18099 上跑着一个
 * stub feed（{@code python3 -m http.server}，内容与
 * {@code src/test/resources/datasource/stub-feed.xml} 同一份字节）。这一轮刻意让
 * RSS 数据源真的去抓那个 loopback feed——因为资源枚举（{@code /resources}）的响应
 * 里带 feed 的标题与条目数，用假的连接器就录不出来。</p>
 *
 * <p>录制序（<b>顺序会影响响应内容</b>：列表内容、日志分页、updated_at）：</p>
 * <pre>
 *   建两个 KB（一个放数据源、一个保持空）
 *   → types / list(无 kb_id, 400) / list(空库, [])
 *   → create(无 body / 非法 JSON / 未注册连接器 / 未知库 / 缺 kb_id / 坏配置 / 成功 201)
 *   → get / put / get / list / get(未知 id)
 *   → validate / validate-credentials(缺字段, 成功) / resources / resource-ancestors(空, 非空)
 *   → credentials PUT(缺字段 / 空 map / 无秘密 / 有 auth_headers) / PUT 非法字段 / get
 *   → sync → logs(limit=0 / limit=abc / offset=-5 / limit=2) → log / log(未知)
 *   → pause / resume → viewer 403 → 删数据源 → get(已删)
 * </pre>
 *
 * <h2>掩码</h2>
 * <p>UUID（数据源 / 知识库 / 同步日志的 id）与 RFC3339 时间戳两侧同掩码后逐字节比对
 * （中文按原始字节，见 §9）。<b>但 Go 零值时间 {@code 0001-01-01T00:00:00Z} 与真实
 * 时间戳是两种形态</b>，掩码会把它们抹平，所以另有专门断言钉住
 * "PUT 的响应里 created_at 是零值、updated_at 是真实时间"这条 GORM 写回语义。</p>
 *
 * <h2>为什么把 {@link DataSourceSyncTaskQueue} 换成 mock</h2>
 * <p>{@code POST /{id}/sync} 在真队列下会在后台虚拟线程里跑完整同步（抓 stub feed →
 * 往 H2 写 knowledge 行），既与本次要断言的东西无关，又会与其它用例共享的表互相干扰。
 * 队列是"传输"，不是本模块的契约面——mock 掉它，其余全部用真 bean（含真 RSS 连接器、
 * 真仓储、真审计）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DataSourceHttpContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final long TENANT = 10002L;
    private static final String USER_ID = "11111111-2222-3333-4444-555555555501";
    private static final String USER_EMAIL = "datasource-contract@weknora.test";
    private static final String VIEWER_ID = "11111111-2222-3333-4444-555555555502";
    private static final String VIEWER_EMAIL = "datasource-viewer@weknora.test";

    private static final String KB_MAIN = "11111111-aaaa-1111-1111-111111111111";
    private static final String KB_EMPTY = "22222222-bbbb-2222-2222-222222222222";
    private static final String UNKNOWN_ID = "11111111-2222-3333-4444-999999999999";

    /**
     * golden 录制时用的 feed 地址（Go 那轮跑在 18099）。
     *
     * <p>⚠️ <b>运行时不用这个端口</b>：stub 起在临时端口（{@link #stubFeedUrl}），
     * 比对前把它替换回这个录制值。固定端口会让"本机恰好有别的进程占着 18099"
     * 变成整类 red（真实踩过：全量跑时上一个手工 stub 还在），而这条端点要验的是
     * 契约、不是端口。</p>
     */
    private static final String RECORDED_FEED_URL = "http://127.0.0.1:18099/feed.xml";

    /** 本次运行实际用的 feed 地址；{@code @BeforeAll} 里定。 */
    private static String FEED_URL;

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([a-z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    /** 只匹配**真实**时间戳（年份 2xxx），以免把 Go 零值时间也抹掉。 */
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"([a-z_]+)\":\"[2-9]\\d{3}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");

    private static HttpServer stubFeed;
    private static SsrfGuard originalGuard;

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
    @MockBean
    private DataSourceSyncTaskQueue taskQueue;

    private String bearer;

    // ══════════════════════════ 夹具 ══════════════════════════

    /**
     * loopback 上的 stub feed。
     *
     * <p>⚠️ {@code HttpServer} 必须 {@code setExecutor(...)}：默认分派器是<b>单线程</b>的，
     * 一旦某个请求没被及时消费就会拖住后面所有请求（表现为整类超时）。§9 已记过这个坑。</p>
     */
    @BeforeAll
    static void startStubFeed() throws Exception {
        byte[] body = new ClassPathResource("datasource/stub-feed.xml")
                .getInputStream().readAllBytes();
        // 端口 0 = 让内核挑一个空闲端口（见 RECORDED_FEED_URL 的注释）
        stubFeed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FEED_URL = "http://127.0.0.1:" + stubFeed.getAddress().getPort() + "/feed.xml";
        stubFeed.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        stubFeed.createContext("/feed.xml", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        stubFeed.start();

        // SSRF 白名单是**进程级静态**：放行 loopback、结束后还原（与 RssConnectorTest 同一处置）
        originalGuard = ConnectorHttp.ssrfGuard();
        reloadLoopbackWhitelist();
    }

    /**
     * ⚠️ 每个测试方法前都**重设**一次 loopback 白名单：@SpringBootTest 的上下文在
     * 首个测试方法时才懒加载，启动完成事件会按 DB 里的 ssrf.whitelist
     * （dev 库 = ["198.18.0.0/15"]）**覆盖**进程级静态白名单——若只在 @BeforeAll 设，
     * 类顺序（谁先加载这个上下文）决定成败（known-issues W5a「SsrfGuard 互踩」家族）。
     */
    private static void reloadLoopbackWhitelist() {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1,::1,localhost");
        ConnectorHttp.setSsrfGuard(guard);
    }

    @BeforeEach
    void reassertLoopbackWhitelist() {
        reloadLoopbackWhitelist();
    }

    @AfterAll
    static void stopStubFeed() {
        if (stubFeed != null) {
            stubFeed.stop(0);
        }
        ConnectorHttp.setSsrfGuard(originalGuard == null ? new SsrfGuard() : originalGuard);
    }

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.update("DELETE FROM sync_logs");
        jdbc.update("DELETE FROM data_sources");

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("datasource-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        seedUser(USER_ID, "dsowner", USER_EMAIL, "owner");
        seedUser(VIEWER_ID, "dsviewer", VIEWER_EMAIL, "viewer");

        seedKb(KB_MAIN, "ds-golden-main");
        seedKb(KB_EMPTY, "ds-golden-empty");

        org.mockito.Mockito.when(taskQueue.enqueue(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(DataSourceSyncTaskQueue.Outcome.ENQUEUED);

        bearer = "Bearer " + login(USER_EMAIL);
    }

    private void seedUser(String id, String username, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);

        TenantMember member = new TenantMember();
        member.setUserId(id);
        member.setTenantId(TENANT);
        member.setRole(role);
        member.setStatus("active");
        memberMapper.insert(member);
    }

    private void seedKb(String id, String name) {
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, description, creator_id, "
                        + "chunking_config, cos_config, indexing_strategy) "
                        + "VALUES (?, ?, ?, 'datasource golden', ?, '{}', '{}', '{}')",
                id, name, TENANT, USER_ID);
    }

    // ══════════════════════════ 1. 目录 / 列表 ══════════════════════════

    /**
     * 连接器目录：<b>裸数组</b>，没有 data/success 信封。
     *
     * <p>⚠️ 按 <b>type 建索引</b>比对，<b>不</b>按下标：Go 的实现先遍历 map（随机序）
     * 再做稳定排序，同优先级的条目顺序每次调用都不同（golden 里
     * {@code feishu_drive} 与 {@code lark_drive} 的顺序就是录制那一刻的偶然）。
     * 这里额外钉住"优先级非递减"这条真正稳定的性质。</p>
     */
    @Test
    void connectorTypesMatchGoIgnoringSamePriorityOrder() throws Exception {
        MvcResult r = perform(get("/api/v1/datasource/types").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertConnectorTypesMatchGo(raw(r), "ds-types.json");
    }

    /**
     * 按 type 建索引逐字段比对连接器目录。
     *
     * <p>⚠️ <b>不能按下标比</b>：Go 的实现先遍历 map（随机序）再做<b>稳定</b>插入排序，
     * 于是同优先级的条目顺序每次调用都不同——实测两次相邻调用里
     * {@code feishu_drive}/{@code lark_drive} 就换了位置（golden 文件的
     * {@code ds-types.json} 与 {@code ds-types-viewer.json} 正是两次不同调用的产物，
     * 顺序确实不同）。能稳定断言的是"条数 + 每个 type 的字段 + priority 非递减"。</p>
     */
    private void assertConnectorTypesMatchGo(String actualJson, String goldenFile) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode actual = mapper.readTree(actualJson);
        com.fasterxml.jackson.databind.JsonNode golden = mapper.readTree(golden(goldenFile));

        assertEquals(golden.size(), actual.size(), "连接器条数");
        java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> byType = new java.util.HashMap<>();
        int lastPriority = Integer.MIN_VALUE;
        for (com.fasterxml.jackson.databind.JsonNode node : actual) {
            byType.put(node.get("type").asText(), node);
            int priority = node.get("priority").asInt();
            assertThat(priority).as("priority 必须非递减").isGreaterThanOrEqualTo(lastPriority);
            lastPriority = priority;
        }
        for (com.fasterxml.jackson.databind.JsonNode g : golden) {
            com.fasterxml.jackson.databind.JsonNode a = byType.get(g.get("type").asText());
            assertThat(a).as("连接器 " + g.get("type").asText()).isNotNull();
            assertEquals(g.toString(), a.toString(), "连接器 " + g.get("type").asText() + " 的字段");
        }
        // 内置连接器 icon 都是空串 → omitempty 让整个键消失
        assertThat(actualJson).doesNotContain("\"icon\"");
    }

    @Test
    void listWithoutKbIdIsBadRequest() throws Exception {
        MvcResult r = perform(get("/api/v1/datasource").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-list-kb-required.json"), raw(r));
    }

    /** 空仓库列表是 {@code []} 而不是 {@code null}（Go 显式做了 make）。 */
    @Test
    void listEmptyKbMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/datasource?kb_id=" + KB_EMPTY)
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals("[]", raw(r));
        assertEquals(golden("ds-list-empty.json"), raw(r));
    }

    // ══════════════════════════ 2. 创建 ══════════════════════════

    @Test
    void createWithoutBodyIsBadRequest() throws Exception {
        MvcResult r = perform(post("/api/v1/datasource")
                .contentType("application/json").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-create-no-body.json"), raw(r));
    }

    @Test
    void createWithInvalidJsonIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/datasource"), "not-json")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-create-bad-json.json"), raw(r));
    }

    /** 未登记的连接器类型：目录里有 confluence、但注册表里没有 → 400 固定文案。 */
    @Test
    void createWithUnregisteredConnectorIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/datasource"),
                "{\"name\":\"x\",\"type\":\"confluence\",\"knowledge_base_id\":\"" + KB_MAIN + "\"}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-create-bad-connector.json"), raw(r));
    }

    @Test
    void createWithUnknownKbIsNotFound() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/datasource"),
                "{\"name\":\"x\",\"type\":\"rss\",\"knowledge_base_id\":\"" + UNKNOWN_ID + "\"}")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-create-bad-kb.json"), raw(r));
    }

    /** 缺 {@code knowledge_base_id} 走的是"kb_id is required"（400），不是 404。 */
    @Test
    void createWithoutKbIdIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/datasource"),
                "{\"name\":\"x\",\"type\":\"rss\"}").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-create-missing-kb.json"), raw(r));
    }

    /**
     * 没有 config 的 RSS：连接器校验把它拒了，文案是
     * {@code "invalid configuration: config is nil"}（RSS 的 parseConfig 对 nil 配置的原文）。
     *
     * <p>这条顺带钉住一件事：service 的 {@code validateDataSourceConfig} 必须把
     * {@code null} 配置<b>原样递给连接器</b>（Go 就是那么写的），而不是提前折叠成
     * 泛泛的 {@code "invalid configuration"}。</p>
     */
    @Test
    void createWithNullConfigSurfacesConnectorError() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/datasource"),
                "{\"name\":\"x\",\"type\":\"rss\",\"knowledge_base_id\":\"" + KB_MAIN + "\"}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-create-bad-creds.json"), raw(r));
    }

    /** 成功：<b>201</b> Created + 裸 DTO（无信封）。 */
    @Test
    void createMatchesGo() throws Exception {
        MvcResult r = createDataSource();

        assertEquals(201, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("ds-create.json")), mask(raw(r)));
        // config 里只有 settings（resource_ids 是 nil → omitempty 省略）
        // PR4：键序归一后邻接子串不可靠 → 树断言
        {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw(r));
            var cfg = root.path("config");
            org.assertj.core.api.Assertions.assertThat(cfg.path("type").asText()).isEqualTo("rss");
            org.assertj.core.api.Assertions.assertThat(
                    cfg.path("settings").path("feed_urls").asText()).isEqualTo(FEED_URL);
            var creds = root.path("credentials").path("credentials");
            org.assertj.core.api.Assertions.assertThat(creds.path("configured").asBoolean()).isFalse();
        }
        assertThat(raw(r)).doesNotContain("app_token").doesNotContain("enc:v1:");
    }

    // ══════════════════════════ 3. 读 / 改 ══════════════════════════

    @Test
    void getMatchesGo() throws Exception {
        String id = createId();
        MvcResult r = perform(get("/api/v1/datasource/" + id).header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("ds-get.json")), mask(raw(r)));
    }

    @Test
    void getUnknownIdIsNotFound() throws Exception {
        MvcResult r = perform(get("/api/v1/datasource/" + UNKNOWN_ID).header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-unknown-id.json"), raw(r));
    }

    /**
     * PUT 的响应是<b>请求对象</b>（handler 只把 ID/租户/库 id 覆盖过去），
     * 不是重新读出来的行——所以 {@code type}/{@code sync_schedule}/{@code status}/
     * {@code created_at} 都是零值，只有 {@code updated_at} 是真实的（GORM 的
     * struct Updates 把新时间写回了内存对象）。
     *
     * <p>同时钉住"凭据永不从这条端点流入"：body 里带的 {@code api_token} 不会出现，
     * 原来在 credentials 里的 {@code feed_urls} 也会被整块换成库里的旧值
     * （旧值是 nil）——所以响应里根本没有 credentials 键。</p>
     */
    @Test
    void updateEchoesRequestObjectAndIgnoresCredentials() throws Exception {
        String id = createId();
        MvcResult r = perform(jsonBody(put("/api/v1/datasource/" + id),
                "{\"name\":\"golden-rss-renamed\",\"sync_mode\":\"full\",\"sync_deletions\":false,"
                        + "\"error_message\":\"\",\"config\":{\"type\":\"rss\",\"settings\":"
                        + "{\"feed_urls\":\"" + FEED_URL + "\"},\"credentials\":{\"feed_urls\":\""
                        + FEED_URL + "\",\"api_token\":\"should-be-ignored\"}}}")
                .header("Authorization", bearer));

        assertEquals(200, r.getResponse().getStatus(), raw(r));
        String body = raw(r);
        assertEquals(mask(golden("ds-update.json")), mask(body));

        // 反直觉但真实的三条（掩码前单独钉住，否则掩码会把它们抹平）
        assertThat(body).contains("\"created_at\":\"0001-01-01T00:00:00Z\"");
        assertThat(body).contains("\"type\":\"\"");
        assertThat(body).contains("\"status\":\"\"");
        assertThat(body).doesNotContain("should-be-ignored");
        // config 里只剩 settings：credentials 被库里那份（nil）整块替换掉了
        {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            var cfg = root.path("config");
            org.assertj.core.api.Assertions.assertThat(cfg.path("type").asText()).isEqualTo("rss");
            org.assertj.core.api.Assertions.assertThat(
                    cfg.path("settings").path("feed_urls").asText()).isEqualTo(FEED_URL);
        }

        // 库里真实的行没有被这些零值覆盖：type/status/schedule 都还在
        MvcResult after = perform(get("/api/v1/datasource/" + id).header("Authorization", bearer));
        assertEquals(mask(golden("ds-get-after-update.json")), mask(raw(after)));
        assertThat(raw(after)).contains("\"type\":\"rss\"").contains("\"status\":\"active\"")
                .contains("\"sync_schedule\":\"0 0 * * * *\"");
    }

    @Test
    void listAfterCreateMatchesGo() throws Exception {
        String id = createId();
        perform(jsonBody(put("/api/v1/datasource/" + id),
                "{\"name\":\"golden-rss-renamed\",\"sync_mode\":\"full\",\"sync_deletions\":false,"
                        + "\"error_message\":\"\",\"config\":{\"type\":\"rss\",\"settings\":"
                        + "{\"feed_urls\":\"" + FEED_URL + "\"},\"credentials\":{\"feed_urls\":\""
                        + FEED_URL + "\",\"api_token\":\"should-be-ignored\"}}}")
                .header("Authorization", bearer));

        MvcResult r = perform(get("/api/v1/datasource?kb_id=" + KB_MAIN)
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("ds-list.json")), mask(raw(r)));
        // 还没有同步日志 → latest_sync_log 的 omitempty 让它整个键消失
        assertThat(raw(r)).doesNotContain("latest_sync_log");
    }

    // ══════════════════════════ 4. 连接校验 / 资源 ══════════════════════════

    @Test
    void validateConnectionMatchesGo() throws Exception {
        String id = createId();
        MvcResult r = perform(post("/api/v1/datasource/" + id + "/validate")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-validate.json"), raw(r));
    }

    @Test
    void validateCredentialsWithoutFieldsIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/datasource/validate-credentials"), "{}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-validate-credentials-bad.json"), raw(r));
    }

    /** 裸凭据试连（不落库）：{@code feed_urls} 可以走 credentials 这个历史位置。 */
    @Test
    void validateCredentialsMatchesGoAndPersistsNothing() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/datasource/validate-credentials"),
                "{\"type\":\"rss\",\"credentials\":{\"feed_urls\":\"" + FEED_URL + "\"}}")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-validate-credentials.json"), raw(r));
        assertEquals(0, (int) jdbc.queryForObject("SELECT COUNT(*) FROM data_sources", Integer.class));
    }

    /**
     * 资源枚举真的去抓了 stub feed：名字与条目数都来自 feed 内容。
     *
     * <p>{@code external_id} 就是配置里的 feed 地址，所以要先做端口归一化；
     * 其余字段（含 {@code modified_at} 的 Go 零值）<b>逐字节</b>比。</p>
     */
    @Test
    void listResourcesMatchesGo() throws Exception {
        String id = createId();
        MvcResult r = perform(get("/api/v1/datasource/" + id + "/resources")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-resources.json"), normalizeFeed(raw(r)));
    }

    /**
     * 空 {@code resource_ids} 回 {@code {"ancestors":[]}}。
     *
     * <p>注意：Go 的<b>短路在 service 里</b>（{@code len(resourceIDs) == 0} 直接返回空切片），
     * 但 handler 仍然先跑 {@code getOwnedDataSource}，所以"未知 id + 空列表"回的
     * 是 <b>404</b> 而不是 200——本用例只覆盖前者（golden 也是那么录的）。</p>
     */
    @Test
    void resolveAncestorsWithEmptyListShortCircuits() throws Exception {
        String id = createId();
        MvcResult r = perform(jsonBody(post("/api/v1/datasource/" + id + "/resource-ancestors"),
                "{\"resource_ids\":[]}").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-ancestors-empty.json"), raw(r));
    }

    /** feed 是扁平列表：非空 resource_ids 也回空祖先集。 */
    @Test
    void resolveAncestorsMatchesGo() throws Exception {
        String id = createId();
        MvcResult r = perform(jsonBody(post("/api/v1/datasource/" + id + "/resource-ancestors"),
                "{\"resource_ids\":[\"" + FEED_URL + "\"]}").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-ancestors.json"), raw(r));
    }

    // ══════════════════════════ 5. 凭据子资源 ══════════════════════════

    /**
     * 缺 {@code credentials} 时回的是 <b>go-playground/validator 的 required 原文</b>
     * （gin 的 ShouldBindJSON 把 err.Error() 直接当 message）——逐字复刻，不掩码。
     */
    @Test
    void credentialsPutWithoutFieldIsBadRequest() throws Exception {
        String id = createId();
        MvcResult r = perform(jsonBody(put("/api/v1/datasource/" + id + "/credentials"), "{}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-credentials-put-missing.json"), raw(r));
    }

    @Test
    void credentialsPutEmptyMapIsBadRequest() throws Exception {
        String id = createId();
        MvcResult r = perform(jsonBody(put("/api/v1/datasource/" + id + "/credentials"),
                "{\"credentials\":{}}").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-credentials-put-empty.json"), raw(r));
    }

    /**
     * RSS 的 {@code feed_urls} 是<b>非密钥配置</b>：它会被从 credentials 里剥掉，
     * 所以"配没配"仍是 false。这一条把"configured 的判据按连接器各异"钉住了。
     */
    @Test
    void credentialsPutStripsRssFeedUrlsAndStaysUnconfigured() throws Exception {
        String id = createId();
        MvcResult r = perform(jsonBody(put("/api/v1/datasource/" + id + "/credentials"),
                "{\"credentials\":{\"feed_urls\":\"" + FEED_URL + "\"}}")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-credentials-put.json"), raw(r));

        // 读回来确认：credentials 里没有 feed_urls（被剥掉）、settings 里照旧，
        // configured 仍是 false。⚠️ 这里不逐字节比 ds-get-after-credentials.json
        // ——那条 golden 录在"先 PUT 改过名字与 sync_mode"之后，状态不同。
        MvcResult after = perform(get("/api/v1/datasource/" + id).header("Authorization", bearer));
        String afterBody = raw(after);
        assertThat(afterBody).contains("\"configured\":false");
        assertThat(afterBody).contains(
                "\"settings\":{\"feed_urls\":\"" + FEED_URL + "\"}");
        assertThat(afterBody).doesNotContain("enc:v1:");
        // 落库的 config 里 credentials 为 null（剥完 map 空了 → Go 的 nil）
        String stored = jdbc.queryForObject(
                "SELECT config FROM data_sources WHERE id = ?", String.class, id);
        assertThat(stored).contains("\"credentials\":null");
        assertThat(stored).doesNotContain("\"credentials\":{\"");
    }

    /** 给了 {@code auth_headers}（这才是 RSS 的密钥）→ configured 翻成 true。 */
    @Test
    void credentialsPutWithAuthHeadersReportsConfigured() throws Exception {
        String id = createId();
        MvcResult r = perform(jsonBody(put("/api/v1/datasource/" + id + "/credentials"),
                "{\"credentials\":{\"feed_urls\":\"" + FEED_URL
                        + "\",\"auth_headers\":\"X-Token: abc\"}}")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-credentials-put-auth-headers.json"), raw(r));
        // 密钥值本身永不出现在响应里
        assertThat(raw(r)).doesNotContain("X-Token");
    }

    @Test
    void credentialsDeleteUnknownFieldIsBadRequest() throws Exception {
        String id = createId();
        MvcResult r = perform(delete("/api/v1/datasource/" + id + "/credentials/nope")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-credentials-delete-bad-field.json"), raw(r));
    }

    /** 唯一合法的字段名：清空 → <b>204</b>，无响应体；幂等。 */
    @Test
    void credentialsDeleteWipesAndIsIdempotent() throws Exception {
        String id = createId();
        perform(jsonBody(put("/api/v1/datasource/" + id + "/credentials"),
                "{\"credentials\":{\"auth_headers\":\"X-Token: abc\"}}")
                .header("Authorization", bearer));

        MvcResult first = perform(delete("/api/v1/datasource/" + id + "/credentials/credentials")
                .header("Authorization", bearer));
        assertEquals(204, first.getResponse().getStatus(), raw(first));
        assertEquals("", raw(first));

        MvcResult second = perform(delete("/api/v1/datasource/" + id + "/credentials/credentials")
                .header("Authorization", bearer));
        assertEquals(204, second.getResponse().getStatus(), raw(second));

        String after = raw(perform(get("/api/v1/datasource/" + id).header("Authorization", bearer)));
        assertThat(after).contains("\"configured\":false");
    }

    // ══════════════════════════ 6. 同步控制与日志 ══════════════════════════

    /** 手动同步：返回新建的 sync_log 裸实体（status=running、finished_at=null）。 */
    @Test
    void manualSyncMatchesGo() throws Exception {
        String id = createId();
        MvcResult r = perform(post("/api/v1/datasource/" + id + "/sync")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("ds-sync.json")), mask(raw(r)));
        // 计数器恒输出（无 omitempty），空串 error_message 也照输出
        assertThat(raw(r)).contains("\"finished_at\":null").contains("\"error_message\":\"\"")
                .contains("\"items_total\":0").contains("\"result\":null");
    }

    @Test
    void manualSyncOnUnknownIdIsNotFound() throws Exception {
        MvcResult r = perform(post("/api/v1/datasource/" + UNKNOWN_ID + "/sync")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-unknown-id.json"), raw(r));
    }

    /** {@code limit} 在 1..100 之外（含非数字）一律 400——与 memory 那套"容错"相反。 */
    @Test
    void listLogsRejectsBadLimit() throws Exception {
        String id = createId();
        MvcResult r = perform(get("/api/v1/datasource/" + id + "/logs?limit=0")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-logs-bad-limit.json"), raw(r));

        MvcResult abc = perform(get("/api/v1/datasource/" + id + "/logs?limit=abc")
                .header("Authorization", bearer));
        assertEquals(400, abc.getResponse().getStatus(), raw(abc));
        assertEquals(golden("ds-logs-bad-limit-abc.json"), raw(abc));
    }

    /** 但 {@code offset} 是容错的：非法/负数一律归 0（200）。 */
    @Test
    void listLogsToleratesNegativeOffset() throws Exception {
        String id = createId();
        perform(post("/api/v1/datasource/" + id + "/sync").header("Authorization", bearer));

        MvcResult r = perform(get("/api/v1/datasource/" + id + "/logs?offset=-5")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("ds-logs-tolerant-offset.json")), mask(raw(r)));
    }

    @Test
    void listLogsMatchesGo() throws Exception {
        String id = createId();
        perform(post("/api/v1/datasource/" + id + "/sync").header("Authorization", bearer));
        MvcResult r = perform(get("/api/v1/datasource/" + id + "/logs?limit=2&offset=0")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("ds-logs.json")), mask(raw(r)));
    }

    @Test
    void getLogMatchesGo() throws Exception {
        String id = createId();
        String logId = createdLogId(perform(post("/api/v1/datasource/" + id + "/sync")
                .header("Authorization", bearer)));

        MvcResult r = perform(get("/api/v1/datasource/logs/" + logId)
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("ds-log.json")), mask(raw(r)));
    }

    @Test
    void getUnknownLogIsNotFoundWithItsOwnMessage() throws Exception {
        MvcResult r = perform(get("/api/v1/datasource/logs/" + UNKNOWN_ID)
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ds-log-unknown.json"), raw(r));
    }

    @Test
    void pauseAndResumeMatchGo() throws Exception {
        String id = createId();
        MvcResult paused = perform(post("/api/v1/datasource/" + id + "/pause")
                .header("Authorization", bearer));
        assertEquals(200, paused.getResponse().getStatus(), raw(paused));
        assertEquals(golden("ds-pause.json"), raw(paused));

        MvcResult resumed = perform(post("/api/v1/datasource/" + id + "/resume")
                .header("Authorization", bearer));
        assertEquals(200, resumed.getResponse().getStatus(), raw(resumed));
        assertEquals(golden("ds-resume.json"), raw(resumed));
    }

    // ══════════════════════════ 7. 删除 / 权限 ══════════════════════════

    @Test
    void deleteReturns204AndGetAfterwardsIs404() throws Exception {
        String id = createId();
        MvcResult r = perform(delete("/api/v1/datasource/" + id).header("Authorization", bearer));
        assertEquals(204, r.getResponse().getStatus(), raw(r));
        assertEquals("", raw(r));

        MvcResult after = perform(get("/api/v1/datasource/" + id).header("Authorization", bearer));
        assertEquals(404, after.getResponse().getStatus(), raw(after));
        assertEquals(golden("ds-deleted-get.json"), raw(after));
    }

    /** Viewer 能读目录（{@code GET /types} 是 Viewer+），但建不了数据源（Admin+）。 */
    @Test
    void viewerCanReadTypesButCannotCreate() throws Exception {
        String viewerToken = "Bearer " + login(VIEWER_EMAIL);

        MvcResult types = perform(get("/api/v1/datasource/types").header("Authorization", viewerToken));
        assertEquals(200, types.getResponse().getStatus(), raw(types));
        assertConnectorTypesMatchGo(raw(types), "ds-types-viewer.json");

        MvcResult create = perform(jsonBody(post("/api/v1/datasource"),
                "{\"name\":\"x\",\"type\":\"rss\",\"knowledge_base_id\":\"" + KB_MAIN + "\"}")
                .header("Authorization", viewerToken));
        assertEquals(403, create.getResponse().getStatus(), raw(create));
        assertEquals(golden("ds-create-forbidden.json"), raw(create));
    }

    /**
     * 17 条路由在 API-Key 策略表里的登记形态：整组都是
     * {@code manageDataSources(fullAccess())}（对照
     * {@code g.apiKeyGroup(r.Group("/datasource"), apiKeyManageDataSources(apiKeyFullAccess()))}）。
     */
    @Test
    void allDataSourceRoutesAreRegisteredWithTheSamePolicy() {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        APIKeyRoutePolicies.registerAll(a);

        List<String[]> routes = List.of(
                new String[] {"GET", "/api/v1/datasource/types"},
                new String[] {"POST", "/api/v1/datasource/validate-credentials"},
                new String[] {"POST", "/api/v1/datasource"},
                new String[] {"GET", "/api/v1/datasource"},
                new String[] {"GET", "/api/v1/datasource/{id}"},
                new String[] {"PUT", "/api/v1/datasource/{id}"},
                new String[] {"DELETE", "/api/v1/datasource/{id}"},
                new String[] {"PUT", "/api/v1/datasource/{id}/credentials"},
                new String[] {"DELETE", "/api/v1/datasource/{id}/credentials/{field}"},
                new String[] {"POST", "/api/v1/datasource/{id}/validate"},
                new String[] {"GET", "/api/v1/datasource/{id}/resources"},
                new String[] {"POST", "/api/v1/datasource/{id}/resource-ancestors"},
                new String[] {"POST", "/api/v1/datasource/{id}/sync"},
                new String[] {"POST", "/api/v1/datasource/{id}/pause"},
                new String[] {"POST", "/api/v1/datasource/{id}/resume"},
                new String[] {"GET", "/api/v1/datasource/{id}/logs"},
                new String[] {"GET", "/api/v1/datasource/logs/{log_id}"});

        APIKeyRoutePolicy expected = APIKeyRoutePolicy.manageDataSources(
                APIKeyRoutePolicy.fullAccess());
        for (String[] route : routes) {
            APIKeyRoutePolicy policy = a.lookup(route[0], route[1]);
            assertEquals(expected, policy, route[0] + " " + route[1] + " 的策略");
        }
        assertEquals(17, routes.size(), "routes_infra.go 注册的 datasource 路由数");
    }

    /**
     * 门禁的行为验证：只带 {@code chat} / {@code retrieve} 的 scoped Key 进不来，
     * full-access 放行（数据源能凭 scoped 能力被管理就意味着它能读别家的连接器凭据）。
     */
    @Test
    void scopedApiKeyIsDeniedOnEveryDataSourceRoute() throws Exception {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        APIKeyRoutePolicies.registerAll(a);

        com.ragagent.apikey.domain.TenantAPIKeyScope scoped =
                new com.ragagent.apikey.domain.TenantAPIKeyScope(
                        0L, "tenant", false, null, List.of("chat"));
        assertThat(gateAllows(a, scoped, "GET", "/api/v1/datasource/types")).isFalse();
        assertThat(gateAllows(a, scoped, "POST", "/api/v1/datasource/{id}/sync")).isFalse();

        com.ragagent.apikey.domain.TenantAPIKeyScope full =
                new com.ragagent.apikey.domain.TenantAPIKeyScope(0L, "tenant", true, null, null);
        assertThat(gateAllows(a, full, "GET", "/api/v1/datasource/types")).isTrue();

        // 行为层：scoped Key 打真实请求 → 403（纯字符串形态，不是 AppError 信封）
        String scopedKey = createApiKey("{\"name\":\"ds-scoped\",\"full_access\":false,"
                + "\"capabilities\":[\"chat\"]}");
        MvcResult r = perform(get("/api/v1/datasource/types").header("X-API-Key", scopedKey));
        assertEquals(403, r.getResponse().getStatus(), raw(r));
        assertEquals("{\"error\":\"Forbidden: API key scope does not allow this operation\"}", raw(r));
    }

    private static boolean gateAllows(APIKeyRouteAuthorizer authorizer,
                                      com.ragagent.apikey.domain.TenantAPIKeyScope scope,
                                      String method, String pattern) throws Exception {
        com.ragagent.apikey.domain.APIKeyScopeContext.set(scope);
        try {
            org.springframework.mock.web.MockHttpServletRequest request =
                    new org.springframework.mock.web.MockHttpServletRequest(method, pattern);
            request.setAttribute(org.springframework.web.servlet.HandlerMapping
                    .BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
            org.springframework.mock.web.MockHttpServletResponse response =
                    new org.springframework.mock.web.MockHttpServletResponse();
            boolean allowed = new com.ragagent.apikey.filter.APIKeyGateInterceptor(authorizer)
                    .preHandle(request, response, new Object());
            return allowed && response.getStatus() == 200;
        } finally {
            com.ragagent.apikey.domain.APIKeyScopeContext.clear();
        }
    }

    // ══════════════════════════ 工具 ══════════════════════════

    private MvcResult createDataSource() throws Exception {
        return perform(jsonBody(post("/api/v1/datasource"),
                "{\"name\":\"golden-rss\",\"type\":\"rss\",\"knowledge_base_id\":\"" + KB_MAIN
                        + "\",\"sync_schedule\":\"0 0 * * * *\",\"config\":{\"type\":\"rss\","
                        + "\"settings\":{\"feed_urls\":\"" + FEED_URL + "\"},"
                        + "\"credentials\":{\"feed_urls\":\"" + FEED_URL + "\"}}}")
                .header("Authorization", bearer));
    }

    private String createId() throws Exception {
        MvcResult r = createDataSource();
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(raw(r));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private String createdLogId(MvcResult result) throws Exception {
        String body = raw(result);
        assertEquals(200, result.getResponse().getStatus(), body);
        Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** 建一把 API Key 并返回**明文**（响应里 data.token 只此一次）。 */
    private String createApiKey(String body) throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/tenants/" + TENANT + "/api-keys"), body)
                .header("Authorization", bearer));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        Matcher m = TOKEN.matcher(raw(r));
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

    /**
     * 两侧同掩码：UUID 值 + <b>真实</b>时间戳（{@code 2xxx-…}）。
     *
     * <p>Go 零值时间 {@code 0001-01-01T00:00:00Z} 刻意<b>不</b>掩码——它是"这个字段
     * 从未被赋值"的信号，掩掉就把两种形态混为一谈了。需要它的用例另行显式断言。</p>
     */
    private static String mask(String s) {
        // PR4 语义比较入口：键序/转义归一后再掩码
        s = com.ragagent.support.ContractJson.semantic(s);
        // 先把运行时那个临时端口的 feed 地址换回录制时的 18099，再掩 UUID 与时间戳
        String out = normalizeFeed(s);
        out = UUID_VALUE.matcher(out).replaceAll("\"$1\":\"<uuid>\"");
        return TS_VALUE.matcher(out).replaceAll("\"$1\":\"<ts>\"");
    }

    /**
     * 把运行时临时端口的 stub feed 地址换回 golden 里的 18099。
     *
     * <p><b>只做这一件事</b>——不掩 UUID / 时间戳，所以对 {@code /resources} 这类
     * 本来就没有动态字段的响应来说，它就是一次纯粹的地址归一化。</p>
     */
    private static String normalizeFeed(String s) {
        return FEED_URL == null ? s : s.replace(FEED_URL, RECORDED_FEED_URL);
    }
}
