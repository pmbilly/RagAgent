package com.ragagent.datasource.connector.notion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * {@code NotionConnector} 的对等测试（对照 Go 的 connector_test.go）。
 *
 * <p>夹具镜像 Go 的 {@code fakeNotion()}：同样的 endpoint、同样的页面/数据库/
 * 记录数据，期望值取 Go 实录。增量同步那几条用例刻意复刻
 * {@code TestConnectorFetchIncremental_NoChanges} 的场景：
 * <b>cursor 里的 {@code "2026-01-15T10:00:00Z"} 与页面返回的
 * {@code "2026-01-15T10:00:00.000Z"} 必须判等</b>——Go 用
 * {@code time.Time.Equal} 比瞬时，Java 侧也要比 {@code Instant}
 * （比字面量的话这条就废了）。</p>
 */
class NotionConnectorTest {

    private static final String PAGE_1 = "{\"id\":\"page-1\",\"object\":\"page\","
            + "\"url\":\"https://notion.so/Page-1\",\"last_edited_time\":\"2026-01-15T10:00:00.000Z\","
            + "\"in_trash\":false,\"parent\":{\"type\":\"workspace\"},"
            + "\"properties\":{\"title\":{\"type\":\"title\","
            + "\"title\":[{\"plain_text\":\"Test Page\"}]}}}";

    @BeforeAll
    static void allowLoopback() {
        NotionTestSupport.allowLoopback();
    }

    @AfterAll
    static void restoreSsrf() {
        NotionTestSupport.restoreSsrf();
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    /** 对照 Go {@code fakeNotion()}：登记标准的 page / database / record 端点。 */
    private static void fakeNotion(NotionStubServer server) {
        server.route("/v1/users/me", exchange -> {
            if (!"Bearer test-token".equals(
                    exchange.getRequestHeaders().getFirst("Authorization"))) {
                NotionStubServer.respond(exchange, 401,
                        "{\"status\":401,\"code\":\"unauthorized\","
                                + "\"message\":\"API token is invalid.\"}");
                return;
            }
            NotionStubServer.respond(exchange, 200,
                    "{\"object\":\"user\",\"id\":\"bot-user-id\",\"type\":\"bot\","
                            + "\"name\":\"Test Integration\"}");
        });

        server.json("/v1/search",
                "{\"object\":\"list\",\"results\":[" + PAGE_1 + ","
                        + "{\"id\":\"db-1\",\"object\":\"data_source\","
                        + "\"url\":\"https://notion.so/DB-1\","
                        + "\"last_edited_time\":\"2026-01-16T10:00:00.000Z\",\"in_trash\":false,"
                        + "\"parent\":{\"type\":\"workspace\"},"
                        + "\"title\":[{\"plain_text\":\"Test Database\"}]}],\"has_more\":false}");

        server.json("/v1/blocks/page-1/children",
                "{\"object\":\"list\",\"results\":["
                        + "{\"id\":\"blk-1\",\"type\":\"paragraph\",\"has_children\":false,"
                        + "\"paragraph\":{\"rich_text\":[" + NotionStubServer.richText("Hello world")
                        + "]}},"
                        + "{\"id\":\"blk-2\",\"type\":\"child_page\",\"has_children\":true,"
                        + "\"child_page\":{\"title\":\"Sub Page\"}}],\"has_more\":false}");

        server.json("/v1/pages/page-1", PAGE_1);

        server.json("/v1/databases/db-1",
                "{\"id\":\"db-1\",\"object\":\"database\",\"url\":\"https://notion.so/DB-1\","
                        + "\"last_edited_time\":\"2026-01-16T10:00:00.000Z\",\"in_trash\":false,"
                        + "\"parent\":{\"type\":\"workspace\"},"
                        + "\"title\":[{\"plain_text\":\"Test Database\"}],"
                        + "\"data_sources\":[{\"id\":\"ds-1\",\"name\":\"Default\"}]}");

        server.json("/v1/data_sources/ds-1",
                "{\"id\":\"ds-1\",\"object\":\"data_source\",\"properties\":{"
                        + "\"Name\":{\"type\":\"title\",\"title\":{}},"
                        + "\"Status\":{\"type\":\"select\",\"select\":{}}}}");

        server.json("/v1/data_sources/ds-1/query",
                "{\"object\":\"list\",\"results\":[{\"id\":\"record-1\",\"object\":\"page\","
                        + "\"url\":\"https://notion.so/Record-1\","
                        + "\"last_edited_time\":\"2026-01-17T10:00:00.000Z\",\"in_trash\":false,"
                        + "\"parent\":{\"type\":\"data_source_id\",\"data_source_id\":\"ds-1\"},"
                        + "\"properties\":{"
                        + "\"Name\":{\"type\":\"title\","
                        + "\"title\":[{\"plain_text\":\"Record One\"}]},"
                        + "\"Status\":{\"type\":\"select\",\"select\":{\"name\":\"Done\"}}}}],"
                        + "\"has_more\":false}");

        server.json("/v1/pages/record-1",
                "{\"id\":\"record-1\",\"object\":\"page\",\"url\":\"https://notion.so/Record-1\","
                        + "\"last_edited_time\":\"2026-01-17T10:00:00.000Z\",\"in_trash\":false,"
                        + "\"parent\":{\"type\":\"data_source_id\",\"data_source_id\":\"ds-1\"},"
                        + "\"properties\":{"
                        + "\"Name\":{\"type\":\"title\","
                        + "\"title\":[{\"plain_text\":\"Record One\"}]},"
                        + "\"Status\":{\"type\":\"select\",\"select\":{\"name\":\"Done\"}}}}");

        server.json("/v1/blocks/record-1/children",
                "{\"object\":\"list\",\"results\":[],\"has_more\":false}");
    }

    // ── 类型与配置 ───────────────────────────────────────────────────────

    @Test
    void typeIsNotion() {
        assertThat(new NotionConnector().type())
                .isEqualTo(DataSourceConstants.CONNECTOR_TYPE_NOTION)
                .isEqualTo("notion");
    }

    @Test
    void validatePingsTheApi() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            fakeNotion(server);
            NotionConnector connector = NotionTestSupport.fastConnector();
            connector.validate(NotionTestSupport.config("test-token", server.baseUrl(), null));

            assertThatThrownBy(() -> connector.validate(
                    NotionTestSupport.config("bad-token", server.baseUrl(), null)))
                    .isInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessage("invalid credentials: {\"status\":401,\"code\":\"unauthorized\","
                            + "\"message\":\"API token is invalid.\"}");
        }
    }

    @Test
    void configValidationMessages() {
        assertThatThrownBy(() -> NotionConfig.parse(null))
                .isInstanceOf(ConnectorException.InvalidConfig.class)
                .hasMessage("invalid configuration");

        DataSourceConfig missing = new DataSourceConfig();
        missing.setCredentials(new LinkedHashMap<>());
        assertThatThrownBy(() -> NotionConfig.parse(missing))
                .isInstanceOf(ConnectorException.InvalidCredentials.class)
                .hasMessage("invalid credentials: missing api_key");

        // credentials 整张 map 为 null → 同样是 missing
        assertThatThrownBy(() -> NotionConfig.parse(new DataSourceConfig()))
                .isInstanceOf(ConnectorException.InvalidCredentials.class)
                .hasMessage("invalid credentials: missing api_key");

        DataSourceConfig empty = new DataSourceConfig();
        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("api_key", "");
        empty.setCredentials(creds);
        assertThatThrownBy(() -> NotionConfig.parse(empty))
                .isInstanceOf(ConnectorException.InvalidCredentials.class)
                .hasMessage("invalid credentials: api_key must be a non-empty string");

        // 非字符串：Go 的类型断言与"空串"合并成同一条分支
        DataSourceConfig numeric = new DataSourceConfig();
        Map<String, Object> numericCreds = new LinkedHashMap<>();
        numericCreds.put("api_key", 42);
        numeric.setCredentials(numericCreds);
        assertThatThrownBy(() -> NotionConfig.parse(numeric))
                .isInstanceOf(ConnectorException.InvalidCredentials.class)
                .hasMessage("invalid credentials: api_key must be a non-empty string");

        // null 值同理
        DataSourceConfig nullValue = new DataSourceConfig();
        Map<String, Object> nullCreds = new LinkedHashMap<>();
        nullCreds.put("api_key", null);
        nullValue.setCredentials(nullCreds);
        assertThatThrownBy(() -> NotionConfig.parse(nullValue))
                .isInstanceOf(ConnectorException.InvalidCredentials.class)
                .hasMessage("invalid credentials: api_key must be a non-empty string");
    }

    @Test
    void extractBaseUrl() {
        assertThat(NotionConnector.extractBaseUrl(new DataSourceConfig()))
                .isEqualTo("https://api.notion.com");
        assertThat(NotionConnector.extractBaseUrl(null))
                .isEqualTo("https://api.notion.com");
        DataSourceConfig empty = new DataSourceConfig();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("base_url", "");
        empty.setSettings(settings);
        assertThat(NotionConnector.extractBaseUrl(empty)).isEqualTo("https://api.notion.com");
        DataSourceConfig nonString = new DataSourceConfig();
        Map<String, Object> settings2 = new LinkedHashMap<>();
        settings2.put("base_url", 7);
        nonString.setSettings(settings2);
        assertThat(NotionConnector.extractBaseUrl(nonString)).isEqualTo("https://api.notion.com");
        assertThat(NotionConnector.extractBaseUrl(
                NotionTestSupport.config("tok", "http://127.0.0.1:1", null)))
                .isEqualTo("http://127.0.0.1:1");
    }

    // ── ListResources / ResolveResourceAncestors ─────────────────────────

    @Test
    void listResourcesReturnsPagesAndDatabases() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            fakeNotion(server);
            NotionConnector connector = NotionTestSupport.fastConnector();
            List<Resource> resources = connector.listResources(
                    NotionTestSupport.config("test-token", server.baseUrl(), null), "");

            assertThat(resources).hasSize(2);
            assertThat(resources.get(0).getExternalId()).isEqualTo("page-1");
            assertThat(resources.get(0).getType()).isEqualTo("page");
            assertThat(resources.get(0).getName()).isEqualTo("Test Page");
            assertThat(resources.get(0).getUrl()).isEqualTo("https://notion.so/Page-1");
            assertThat(resources.get(0).getParentId()).isEmpty();
            assertThat(resources.get(0).isHasChildren()).isFalse();

            assertThat(resources.get(1).getExternalId()).isEqualTo("db-1");
            assertThat(resources.get(1).getType()).isEqualTo("database");
            assertThat(resources.get(1).getName()).isEqualTo("Test Database");
        }
    }

    @Test
    void listResourcesIsAlwaysEmptyForLazyLoadRequests() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            fakeNotion(server);
            NotionConnector connector = NotionTestSupport.fastConnector();
            assertThat(connector.listResources(
                    NotionTestSupport.config("test-token", server.baseUrl(), null), "page-1"))
                    .isEmpty();
            assertThat(connector.resolveResourceAncestors(
                    NotionTestSupport.config("test-token", server.baseUrl(), null),
                    List.of("a", "b"))).isEmpty();
        }
    }

    /** 完整树（含 trash、data_source 的 database_parent、孙辈）的实录。 */
    @Test
    void listResourcesBuildsTreeFromSearch() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            treeFixture(server);
            NotionConnector connector = NotionTestSupport.fastConnector();
            List<Resource> resources = connector.listResources(
                    NotionTestSupport.config("tok", server.baseUrl(), null), "");

            assertThat(resources).hasSize(6);
            assertThat(describe(resources)).containsExactly(
                    "root|Root|page|parent=|children=true",
                    "child|Child|page|parent=root|children=true",
                    "grand|Grand|page|parent=child|children=false",
                    "other|Other|page|parent=|children=false",
                    // data_source 用 database_parent 定位（不是 parent 指向的数据库容器）
                    "ds1|DS One|database|parent=root|children=false",
                    "dbcont|DB Cont|database|parent=|children=false");
        }
    }

    private static List<String> describe(List<Resource> resources) {
        List<String> out = new ArrayList<>();
        for (Resource r : resources) {
            out.add(r.getExternalId() + "|" + r.getName() + "|" + r.getType()
                    + "|parent=" + r.getParentId() + "|children=" + r.isHasChildren());
        }
        return out;
    }

    private static void treeFixture(NotionStubServer server) {
        server.json("/v1/search", "{\"object\":\"list\",\"results\":["
                + "{\"id\":\"root\",\"object\":\"page\",\"url\":\"https://notion.so/root\","
                + "\"last_edited_time\":\"2026-01-15T10:00:00Z\",\"parent\":{\"type\":\"workspace\"},"
                + "\"properties\":{\"Name\":{\"type\":\"title\","
                + "\"title\":[{\"plain_text\":\"Root\"}]}}},"
                + "{\"id\":\"child\",\"object\":\"page\",\"url\":\"https://notion.so/child\","
                + "\"last_edited_time\":\"2026-01-15T11:00:00Z\","
                + "\"parent\":{\"type\":\"page_id\",\"page_id\":\"root\"},"
                + "\"properties\":{\"Name\":{\"type\":\"title\","
                + "\"title\":[{\"plain_text\":\"Child\"}]}}},"
                + "{\"id\":\"grand\",\"object\":\"page\",\"url\":\"https://notion.so/grand\","
                + "\"last_edited_time\":\"2026-01-15T12:00:00Z\","
                + "\"parent\":{\"type\":\"page_id\",\"page_id\":\"child\"},"
                + "\"properties\":{\"Name\":{\"type\":\"title\","
                + "\"title\":[{\"plain_text\":\"Grand\"}]}}},"
                + "{\"id\":\"other\",\"object\":\"page\",\"url\":\"https://notion.so/other\","
                + "\"last_edited_time\":\"2026-01-15T13:00:00Z\",\"parent\":{\"type\":\"workspace\"},"
                + "\"properties\":{\"Name\":{\"type\":\"title\","
                + "\"title\":[{\"plain_text\":\"Other\"}]}}},"
                + "{\"id\":\"trashed\",\"object\":\"page\",\"url\":\"https://notion.so/trashed\","
                + "\"last_edited_time\":\"2026-01-15T14:00:00Z\",\"in_trash\":true,"
                + "\"parent\":{\"type\":\"page_id\",\"page_id\":\"root\"},"
                + "\"properties\":{\"Name\":{\"type\":\"title\","
                + "\"title\":[{\"plain_text\":\"Trashed\"}]}}},"
                + "{\"id\":\"ds1\",\"object\":\"data_source\",\"url\":\"https://notion.so/ds1\","
                + "\"last_edited_time\":\"2026-01-15T15:00:00Z\","
                + "\"parent\":{\"type\":\"database_id\",\"database_id\":\"dbcont\"},"
                + "\"database_parent\":{\"type\":\"page_id\",\"page_id\":\"root\"},"
                + "\"title\":[{\"plain_text\":\"DS One\"}]},"
                + "{\"id\":\"dbcont\",\"object\":\"database\",\"url\":\"https://notion.so/dbcont\","
                + "\"last_edited_time\":\"2026-01-15T16:00:00Z\",\"parent\":{\"type\":\"workspace\"},"
                + "\"title\":[{\"plain_text\":\"DB Cont\"}]}],\"has_more\":false}");
        // 页面直接取：把 id 回显成标题（够用且确定）
        server.fallback(exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/v1/pages/")) {
                String id = exchange.getRequestURI().getPath().substring("/v1/pages/".length());
                NotionStubServer.respond(exchange, 200,
                        "{\"id\":\"" + id + "\",\"object\":\"page\","
                                + "\"url\":\"https://notion.so/" + id + "\","
                                + "\"last_edited_time\":\"2026-01-15T10:00:00Z\","
                                + "\"parent\":{\"type\":\"workspace\"},"
                                + "\"properties\":{\"Name\":{\"type\":\"title\","
                                + "\"title\":[{\"plain_text\":\"" + id + "\"}]}}}");
                return;
            }
            if (exchange.getRequestURI().getPath().endsWith("/children")) {
                NotionStubServer.respond(exchange, 200,
                        "{\"object\":\"list\",\"results\":[{\"id\":\"blk\",\"type\":\"paragraph\","
                                + "\"has_children\":false,\"paragraph\":{\"rich_text\":["
                                + NotionStubServer.richText("body") + "]}}],\"has_more\":false}");
                return;
            }
            NotionStubServer.respond(exchange, 404, "{}");
        });
    }

    // ── FetchAll ─────────────────────────────────────────────────────────

    @Test
    void fetchAllPageProducesMarkdownItem() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            fakeNotion(server);
            NotionConnector connector = NotionTestSupport.fastConnector();
            List<FetchedItem> items = connector.fetchAll(
                    NotionTestSupport.config("test-token", server.baseUrl(), List.of("page-1")),
                    List.of("page-1"));

            assertThat(items).hasSize(1);
            FetchedItem item = items.get(0);
            assertThat(item.getExternalId()).isEqualTo("page-1");
            assertThat(item.getTitle()).isEqualTo("Test Page");
            assertThat(item.getFileName()).isEqualTo("Test Page.md");
            assertThat(item.getContentType()).isEqualTo("text/markdown");
            assertThat(item.getUrl()).isEqualTo("https://notion.so/Page-1");
            assertThat(item.getUpdatedAt().toInstant())
                    .isEqualTo(OffsetDateTime.parse("2026-01-15T10:00:00Z").toInstant());
            assertThat(item.getMetadata())
                    .containsEntry("channel", "notion")
                    .containsEntry("object_type", "page");
            // 段落 + child_page 链接（子页面本身因 404 抓不到，被跳过）。
            // 注意链接里的 ID 被去掉了连字符（Go 的 ReplaceAll(id, "-", "")）
            assertThat(NotionTestSupport.contentOf(item))
                    .isEqualTo("Hello world\n\n- [Sub Page](https://notion.so/blk2)\n");
        }
    }

    @Test
    void fetchAllDatabaseProducesSingleTableItem() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            fakeNotion(server);
            NotionConnector connector = NotionTestSupport.fastConnector();
            List<FetchedItem> items = connector.fetchAll(
                    NotionTestSupport.config("test-token", server.baseUrl(), List.of("db-1")),
                    List.of("db-1"));

            assertThat(items).hasSize(1);
            FetchedItem item = items.get(0);
            assertThat(item.getExternalId()).isEqualTo("db-1");
            assertThat(item.getTitle()).isEqualTo("Test Database");
            assertThat(item.getFileName()).isEqualTo("Test Database.md");
            assertThat(item.getUrl()).isEqualTo("https://notion.so/db1");
            assertThat(item.getMetadata())
                    .containsEntry("channel", "notion")
                    .containsEntry("object_type", "database");
            assertThat(NotionTestSupport.contentOf(item))
                    .isEqualTo("# Test Database\n\n| Title | Status |\n|---|---|\n"
                            + "| Record One | Done |");
        }
    }

    @Test
    void fetchAllSingleRecordUsesRecordBuilder() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            fakeNotion(server);
            NotionConnector connector = NotionTestSupport.fastConnector();
            List<FetchedItem> items = connector.fetchAll(
                    NotionTestSupport.config("test-token", server.baseUrl(), List.of("record-1")),
                    List.of("record-1"));

            assertThat(items).hasSize(1);
            FetchedItem item = items.get(0);
            assertThat(item.getExternalId()).isEqualTo("record-1");
            assertThat(item.getTitle()).isEqualTo("Record One");
            assertThat(item.getFileName()).isEqualTo("Record One.md");
            // database 这一项是**父库标题**：记录的父是 data_source（ds-1），
            // 而桩里的 data_source 响应没有顶层 title → Go 的 extractTitle 回 ""
            // （Go 的同名用例也只断言 object_type，不查这个键）
            assertThat(item.getMetadata())
                    .containsEntry("object_type", "page")
                    .containsEntry("database", "");
            // 属性列表（**不**转义 |、**不**把换行换成 <br>——那是表格才有的处理）
            assertThat(NotionTestSupport.contentOf(item))
                    .isEqualTo("# Record One\n\n- **Status**: Done");
        }
    }

    @Test
    void fetchAllSwallowsPerResourceFailures() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.status("/v1/search", 401, "unauthorized");
            NotionConnector connector = NotionTestSupport.fastConnector();
            // Go 实录：FetchAll 对每个资源失败只记日志，整体不报错、返回 0 条
            assertThat(connector.fetchAll(
                    NotionTestSupport.config("tok", server.baseUrl(), List.of("p1")),
                    List.of("p1"))).isEmpty();
        }
    }

    // ── 增量同步 ─────────────────────────────────────────────────────────

    /** 对照 Go {@code TestConnectorFetchIncremental_NoChanges}。 */
    @Test
    void incrementalSyncDetectsNoChangesByInstant() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            fakeNotion(server);
            NotionConnector connector = NotionTestSupport.fastConnector();
            DataSourceConfig config =
                    NotionTestSupport.config("test-token", server.baseUrl(), List.of("page-1"));

            connector.fetchAll(config, List.of("page-1"));

            // cursor 里是 "…Z"，页面返回的是 "….000Z" —— 必须按**瞬时**判等
            SyncCursor cursor = new SyncCursor();
            Map<String, Object> editTimes = new LinkedHashMap<>();
            editTimes.put("page-1", "2026-01-15T10:00:00Z");
            Map<String, Object> connectorCursor = new LinkedHashMap<>();
            connectorCursor.put("page_edit_times", editTimes);
            cursor.setConnectorCursor(connectorCursor);

            Connector.FetchIncrementalResult result =
                    connector.fetchIncremental(config, cursor);
            assertThat(result.items()).isEmpty();
            assertThat(result.cursor()).isNotNull();
        }
    }

    @Test
    void firstSyncReusesFetchAllAndBuildsCursor() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            treeFixture(server);
            NotionConnector connector = NotionTestSupport.fastConnector();
            DataSourceConfig config =
                    NotionTestSupport.config("tok", server.baseUrl(), List.of("root"));

            Connector.FetchIncrementalResult result =
                    connector.fetchIncremental(config, new SyncCursor());

            assertThat(ids(result.items())).containsExactly("root");
            // cursor 形状：{"page_edit_times":{...}}，键排序、时间是 Go 的 RFC3339Nano
            Map<String, Object> connectorCursor = result.cursor().getConnectorCursor();
            assertThat(connectorCursor).containsOnlyKeys("page_edit_times");
            Map<?, ?> rootTimes = (Map<?, ?>) connectorCursor.get("page_edit_times");
            assertThat(rootTimes.get("root")).isEqualTo("2026-01-15T10:00:00Z");
            assertThat(result.cursor().getLastSyncTime()).isNotNull();
        }
    }

    @Test
    void incrementalSyncReportsChangedAndDeletedButNotDeselected() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            treeFixture(server);
            NotionConnector connector = NotionTestSupport.fastConnector();
            DataSourceConfig config =
                    NotionTestSupport.config("tok", server.baseUrl(), List.of("root"));

            SyncCursor prev = new SyncCursor();
            Map<String, Object> editTimes = new LinkedHashMap<>();
            editTimes.put("root", "2026-01-15T10:00:00Z");
            editTimes.put("child", "2020-01-01T00:00:00Z"); // 变了
            editTimes.put("other", "2026-01-15T13:00:00Z"); // 没变
            editTimes.put("vanish", "2026-01-01T00:00:00Z"); // 真的消失了
            Map<String, Object> connectorCursor = new LinkedHashMap<>();
            connectorCursor.put("page_edit_times", editTimes);
            prev.setConnectorCursor(connectorCursor);

            Connector.FetchIncrementalResult result = connector.fetchIncremental(config, prev);

            // child 变了 → 重抓；grand 是新发现的 → 抓；vanish 不在新集合里且
            // **不**在排除集里 → 报删除；other 不在选中子树下 → 算"取消勾选"、不报删除
            assertThat(ids(result.items()))
                    .containsExactlyInAnyOrder("child", "grand", "vanish(deleted)");

            FetchedItem deleted = result.items().stream()
                    .filter(FetchedItem::isDeleted).findFirst().orElseThrow();
            assertThat(deleted.getExternalId()).isEqualTo("vanish");
            assertThat(deleted.getMetadata()).containsOnlyKeys("channel");
            assertThat(deleted.getMetadata()).containsEntry("channel", "notion");

            Map<String, Object> newTimes =
                    (Map<String, Object>) result.cursor().getConnectorCursor().get("page_edit_times");
            assertThat(newTimes).containsOnlyKeys("child", "ds1", "grand", "root");
            assertThat(newTimes).doesNotContainKey("vanish");
        }
    }

    @Test
    void incrementalSyncWithoutResourceIdsFails() {
        NotionConnector connector = NotionTestSupport.fastConnector();
        assertThatThrownBy(() -> connector.fetchIncremental(
                NotionTestSupport.config("tok", "http://127.0.0.1:1", null), null))
                .isInstanceOf(ConnectorException.class)
                .hasMessage("no resource IDs configured");
    }

    private static List<String> ids(List<FetchedItem> items) {
        List<String> out = new ArrayList<>();
        for (FetchedItem item : items) {
            out.add(item.isDeleted() ? item.getExternalId() + "(deleted)" : item.getExternalId());
        }
        return out;
    }

    // ── buildCursor ──────────────────────────────────────────────────────

    @Test
    void buildCursorSortsKeysAndKeepsOffsets() {
        Map<String, OffsetDateTime> editTimes = new LinkedHashMap<>();
        editTimes.put("p1", OffsetDateTime.parse("2026-01-15T10:00:00Z"));
        editTimes.put("a2", OffsetDateTime.parse("2026-01-15T10:00:00.123Z"));
        editTimes.put("z9", OffsetDateTime.parse("2026-09-18T14:07:39.482344+08:00"));
        editTimes.put("k5", OffsetDateTime.parse("2026-01-15T10:00:00+05:30"));

        SyncCursor cursor = NotionConnector.buildCursor(editTimes);
        Map<?, ?> times = (Map<?, ?>) cursor.getConnectorCursor().get("page_edit_times");

        // Go 的 json.Marshal 对 map 恒按键排序；时间保留自己的偏移（不归一化时区）
        List<String> keys = new ArrayList<>();
        for (Object key : times.keySet()) {
            keys.add((String) key);
        }
        assertThat(keys).containsExactly("a2", "k5", "p1", "z9");
        assertThat(times.get("a2")).isEqualTo("2026-01-15T10:00:00.123Z");
        assertThat(times.get("k5")).isEqualTo("2026-01-15T10:00:00+05:30");
        assertThat(times.get("p1")).isEqualTo("2026-01-15T10:00:00Z");
        assertThat(times.get("z9")).isEqualTo("2026-09-18T14:07:39.482344+08:00");
    }

    @Test
    void buildCursorEmptyEditTimes() {
        SyncCursor cursor = NotionConnector.buildCursor(new LinkedHashMap<>());
        Map<?, ?> times = (Map<?, ?>) cursor.getConnectorCursor().get("page_edit_times");
        assertThat(times).isEmpty();
        assertThat(cursor.getConnectorCursor()).containsOnlyKeys("page_edit_times");
    }

    // ── 纯函数：排除集 / 父子判定 ────────────────────────────────────────

    @Test
    void computeExcludedSetOnlyExcludesNodesWithoutSelectedAncestor() {
        Map<String, String> parentOf = new LinkedHashMap<>();
        parentOf.put("a", "");
        parentOf.put("b", "a");
        parentOf.put("c", "b");
        parentOf.put("x", "");
        parentOf.put("y", "x");
        List<String> visible = List.of("a", "b", "c", "x", "y");

        assertThat(NotionConnector.computeExcludedSet(visible, parentOf, List.of("a")))
                .containsOnlyKeys("x", "y");
        assertThat(NotionConnector.computeExcludedSet(visible, parentOf, List.of("b")))
                .containsOnlyKeys("a", "x", "y");
        assertThat(NotionConnector.computeExcludedSet(visible, parentOf, List.of("a", "x", "y")))
                .isEmpty();
        assertThat(NotionConnector.computeExcludedSet(visible, parentOf, null))
                .containsOnlyKeys("a", "b", "c", "x", "y");
        assertThat(NotionConnector.computeExcludedSet(visible, parentOf, List.of("zzz")))
                .containsOnlyKeys("a", "b", "c", "x", "y");
        assertThat(NotionConnector.computeExcludedSet(null, parentOf, null)).isEmpty();
    }

    @Test
    void computeExcludedSetTerminatesOnCyclicParents() {
        Map<String, String> parentOf = new LinkedHashMap<>();
        parentOf.put("a", "b");
        parentOf.put("b", "a");
        // Go 在这里会死循环；Java 侧加了 seen 集合（刻意的防御性差异）
        assertThat(NotionConnector.computeExcludedSet(List.of("a", "b"), parentOf, List.of("c")))
                .containsOnlyKeys("a", "b");
    }

    @Test
    void resolveParentIdSemantics() {
        Set<String> allIds = Set.of("p1", "p2", "db", "ds");

        assertThat(NotionConnector.resolveParentId(page("p2", "page",
                "{\"type\":\"page_id\",\"page_id\":\"p1\"}", null), allIds)).isEqualTo("p1");
        assertThat(NotionConnector.resolveParentId(page("p1", "page",
                "{\"type\":\"workspace\"}", null), allIds)).isEmpty();
        // 父不在 allIDs 里 → 当成根
        assertThat(NotionConnector.resolveParentId(page("p2", "page",
                "{\"type\":\"page_id\",\"page_id\":\"nope\"}", null), allIds)).isEmpty();
        // data_source 用 database_parent
        assertThat(NotionConnector.resolveParentId(page("ds", "data_source",
                "{\"type\":\"database_id\",\"database_id\":\"db\"}",
                "{\"type\":\"page_id\",\"page_id\":\"p1\"}"), allIds)).isEqualTo("p1");
        assertThat(NotionConnector.resolveParentId(page("ds", "data_source",
                "{\"type\":\"workspace\"}", null), allIds)).isEmpty();
        assertThat(NotionConnector.resolveParentId(page("ds", "data_source", null,
                "{\"type\":\"page_id\",\"page_id\":\"zz\"}"), allIds)).isEmpty();
        assertThat(NotionConnector.resolveParentId(page("db", "database", null,
                "{\"type\":\"page_id\",\"page_id\":\"p1\"}"), allIds)).isEqualTo("p1");
        assertThat(NotionConnector.resolveParentId(page("p2", "page",
                "{\"type\":\"block_id\",\"block_id\":\"p1\"}", null), allIds)).isEqualTo("p1");
        assertThat(NotionConnector.resolveParentId(page("p2", "page",
                "{\"type\":\"data_source_id\",\"data_source_id\":\"ds\"}", null), allIds))
                .isEqualTo("ds");
        assertThat(NotionConnector.resolveParentId(page("p2", "page", null, null), allIds))
                .isEmpty();
    }

    private static NotionPage page(String id, String object, String parentJson,
                                   String databaseParentJson) {
        StringBuilder sb = new StringBuilder("{\"id\":\"" + id + "\",\"object\":\"" + object + "\"");
        if (parentJson != null) {
            sb.append(",\"parent\":").append(parentJson);
        }
        if (databaseParentJson != null) {
            sb.append(",\"database_parent\":").append(databaseParentJson);
        }
        sb.append('}');
        try {
            return NotionJson.MAPPER.readValue(sb.toString(), NotionPage.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── buildRecordItem / buildDatabaseItem（逐字符契约） ────────────────

    @Test
    void buildRecordItemEscapesNothingButTrims() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/blocks/rec-1/children",
                    "{\"object\":\"list\",\"results\":[],\"has_more\":false}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            NotionConnector connector = NotionTestSupport.fastConnector();

            NotionPage record = new NotionPage();
            record.id = "rec-1";
            record.object = "page";
            record.url = "https://notion.so/r1";
            record.lastEditedTime = OffsetDateTime.parse("2026-01-17T10:00:00Z");
            record.title = "Record One";
            record.rawProperties = NotionTestSupport.json(
                    "{\"Tag\":{\"type\":\"select\",\"select\":{\"name\":\"X|Y\"}},"
                            + "\"Status\":{\"type\":\"select\","
                            + "\"select\":{\"name\":\"Done\\nLine2|Pipe\"}}}");

            FetchedItem item = connector.buildRecordItem(client, record,
                    NotionProperties.extractPropertySchema(record), "Test Database");

            assertThat(NotionTestSupport.contentOf(item)).isEqualTo(
                    "# Record One\n\n- **Status**: Done\nLine2|Pipe\n- **Tag**: X|Y");
            assertThat(item.getMetadata()).containsOnlyKeys("channel", "object_type", "database");
            assertThat(item.getMetadata()).containsEntry("database", "Test Database");
        }
    }

    @Test
    void buildRecordItemReturnsNullOnlyWhenBodyIsEmpty() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/blocks/rec-empty/children",
                    "{\"object\":\"list\",\"results\":[],\"has_more\":false}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            NotionConnector connector = NotionTestSupport.fastConnector();

            NotionPage empty = new NotionPage();
            empty.id = "rec-empty";
            empty.object = "page";
            // 没有任何属性、也没有块内容 —— 但标题行恒被写入，所以**不是** null
            FetchedItem item = connector.buildRecordItem(client, empty, null, "DB");
            assertThat(item).isNotNull();
            assertThat(item.getTitle()).isEqualTo("Untitled");
            assertThat(NotionTestSupport.contentOf(item)).isEqualTo("# Untitled");
        }
    }

    @Test
    void buildRecordItemAppendsBlockContent() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/blocks/rec-1/children",
                    "{\"object\":\"list\",\"results\":[{\"id\":\"b\",\"type\":\"paragraph\","
                            + "\"has_children\":false,\"paragraph\":{\"rich_text\":["
                            + NotionStubServer.richText("record body") + "]}}],\"has_more\":false}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            NotionConnector connector = NotionTestSupport.fastConnector();

            NotionPage record = new NotionPage();
            record.id = "rec-1";
            record.object = "page";
            record.title = "WithContent";
            record.rawProperties = NotionTestSupport.json(
                    "{\"Status\":{\"type\":\"select\",\"select\":{\"name\":\"Deep\"}}}");

            FetchedItem item = connector.buildRecordItem(client, record,
                    NotionProperties.extractPropertySchema(record), "DB");
            assertThat(NotionTestSupport.contentOf(item))
                    .isEqualTo("# WithContent\n\n- **Status**: Deep\n\nrecord body");
        }
    }

    /** 表格组装：{@code |} 转义、换行 → {@code <br>}、trash 记录整行跳过、附加小节。 */
    @Test
    void buildDatabaseItemAssemblesTable() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/blocks/rec-with-content/children",
                    "{\"object\":\"list\",\"results\":[{\"id\":\"b\",\"type\":\"paragraph\","
                            + "\"has_children\":false,\"paragraph\":{\"rich_text\":["
                            + NotionStubServer.richText("record body") + "]}}],\"has_more\":false}");
            server.json("/v1/blocks/rec-1/children",
                    "{\"object\":\"list\",\"results\":[],\"has_more\":false}");
            server.json("/v1/blocks/rec-2/children",
                    "{\"object\":\"list\",\"results\":[],\"has_more\":false}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            NotionConnector connector = NotionTestSupport.fastConnector();

            NotionPage rec1 = record("rec-1", "Record One", "2026-01-17T10:00:00Z",
                    "{\"Status\":{\"type\":\"select\",\"select\":{\"name\":\"Done\\nLine2|Pipe\"}},"
                            + "\"Tag\":{\"type\":\"select\",\"select\":{\"name\":\"X|Y\"}}}");
            NotionPage rec2 = record("rec-2", "", "2026-01-18T10:00:00Z",
                    "{\"Status\":{\"type\":\"select\",\"select\":{\"name\":\"Open\"}}}");
            NotionPage rec3 = record("rec-3", "Trashed", null, null);
            rec3.inTrash = true;
            NotionPage rec4 = record("rec-with-content", "WithContent", null,
                    "{\"Status\":{\"type\":\"select\",\"select\":{\"name\":\"Deep\"}}}");

            FetchedItem item = connector.buildDatabaseItem(client, "db-9", "Test Database",
                    List.of(rec1, rec2, rec3, rec4));

            assertThat(NotionTestSupport.contentOf(item)).isEqualTo(
                    "# Test Database\n\n| Title | Status | Tag |\n|---|---|---|\n"
                            + "| Record One | Done<br>Line2\\|Pipe | X\\|Y |\n"
                            + "| Untitled | Open |  |\n"
                            + "| WithContent | Deep |  |\n"
                            + "\n\n## WithContent 内容\n\nrecord body");
            assertThat(item.getExternalId()).isEqualTo("db-9");
            assertThat(item.getFileName()).isEqualTo("Test Database.md");
            assertThat(item.getUrl()).isEqualTo("https://notion.so/db9");
            assertThat(item.getUpdatedAt().toInstant())
                    .isEqualTo(OffsetDateTime.parse("2026-01-17T10:00:00Z").toInstant());
        }
    }

    @Test
    void buildDatabaseItemOnEmptyRecordsReturnsNull() {
        NotionConnector connector = NotionTestSupport.fastConnector();
        assertThat(connector.buildDatabaseItem(null, "db-9", "T", List.of())).isNull();
        assertThat(connector.buildDatabaseItem(null, "db-9", "T", null)).isNull();
    }

    @Test
    void buildDatabaseItemWithoutTitleFallsBackToUntitled() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/blocks/rec-2/children",
                    "{\"object\":\"list\",\"results\":[],\"has_more\":false}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            NotionConnector connector = NotionTestSupport.fastConnector();
            NotionPage rec2 = record("rec-2", "", null,
                    "{\"Status\":{\"type\":\"select\",\"select\":{\"name\":\"Open\"}}}");

            FetchedItem item = connector.buildDatabaseItem(client, "db-9", "",
                    List.of(rec2));
            assertThat(item.getTitle()).isEqualTo("Untitled");
            assertThat(item.getUrl()).isEqualTo("https://notion.so/db9");
            assertThat(NotionTestSupport.contentOf(item))
                    .isEqualTo("# Untitled\n\n| Title | Status |\n|---|---|\n| Untitled | Open |");
        }
    }

    private static NotionPage record(String id, String title, String editedAt,
                                     String propertiesJson) {
        NotionPage page = new NotionPage();
        page.id = id;
        page.object = "page";
        page.title = title;
        if (editedAt != null) {
            page.lastEditedTime = OffsetDateTime.parse(editedAt);
        }
        if (propertiesJson != null) {
            page.rawProperties = NotionTestSupport.json(propertiesJson);
        }
        return page;
    }

    // ── resolveFileUploads ───────────────────────────────────────────────

    /**
     * {@code file_upload} 型的块要被**就地替换**成重新取回的块内容
     * （Notion 会把临时下载地址塞在那个响应里）。
     */
    @Test
    void resolveFileUploadsReplacesRawContentInPlace() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/blocks/blk-file",
                    "{\"id\":\"blk-file\",\"type\":\"image\",\"has_children\":false,"
                            + "\"image\":{\"type\":\"file\",\"file\":{\"url\":"
                            + "\"https://s3.example.com/resolved.png\"}}}");
            // 子块里的 file_upload 也要被解析（递归）
            server.json("/v1/blocks/blk-child",
                    "{\"id\":\"blk-child\",\"type\":\"pdf\",\"has_children\":false,"
                            + "\"pdf\":{\"type\":\"file\",\"file\":{\"url\":"
                            + "\"https://s3.example.com/resolved.pdf\"}}}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());

            NotionBlock fileBlock = NotionJson.MAPPER.readValue(
                    "{\"id\":\"blk-file\",\"type\":\"image\",\"has_children\":false,"
                            + "\"image\":{\"type\":\"file_upload\","
                            + "\"file_upload\":{\"id\":\"fu-1\"}}}", NotionBlock.class);

            NotionBlock child = NotionJson.MAPPER.readValue(
                    "{\"id\":\"blk-child\",\"type\":\"pdf\",\"has_children\":false,"
                            + "\"pdf\":{\"type\":\"file_upload\","
                            + "\"file_upload\":{\"id\":\"fu-2\"}}}", NotionBlock.class);
            NotionBlock parent = NotionJson.MAPPER.readValue(
                    "{\"id\":\"blk-parent\",\"type\":\"toggle\",\"has_children\":true}",
                    NotionBlock.class);
            parent.children = List.of(child);

            List<NotionBlock> blocks = new ArrayList<>(List.of(fileBlock, parent));
            NotionConnector.resolveFileUploads(client, blocks);

            assertThat(blocks.get(0).rawContent.get("file").get("url").asText())
                    .isEqualTo("https://s3.example.com/resolved.png");
            assertThat(blocks.get(1).children.get(0).rawContent.get("file").get("url").asText())
                    .isEqualTo("https://s3.example.com/resolved.pdf");

            // 解析后的 markdown 里应当是真实地址
            NotionMarkdown.Result result = NotionMarkdown.blocksToMarkdown(blocks);
            assertThat(result.markdown).contains("https://s3.example.com/resolved.png");
            assertThat(result.attachments).hasSize(2);
        }
    }

    @Test
    void resolveFileUploadsKeepsBlockWhenResolveFails() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.status("/v1/blocks/blk-file", 404, "{}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            NotionBlock fileBlock = NotionJson.MAPPER.readValue(
                    "{\"id\":\"blk-file\",\"type\":\"image\",\"has_children\":false,"
                            + "\"image\":{\"type\":\"file_upload\","
                            + "\"file_upload\":{\"id\":\"fu-1\"}}}", NotionBlock.class);
            List<NotionBlock> blocks = new ArrayList<>(List.of(fileBlock));

            NotionConnector.resolveFileUploads(client, blocks);
            // 失败时保留原 RawContent（Go 的 continue）
            assertThat(blocks.get(0).rawContent.get("file_upload").get("id").asText())
                    .isEqualTo("fu-1");
        }
    }

    // ── 附件 ─────────────────────────────────────────────────────────────

    /** 图片**不**单独下载；pdf 等附件各成一条（并带上页面前缀的 external_id）。 */
    @Test
    void fetchAllDownloadsNonImageAttachments() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/search",
                    "{\"object\":\"list\",\"results\":[" + PAGE_1 + "],\"has_more\":false}");
            server.json("/v1/pages/page-1", PAGE_1);
            server.json("/v1/blocks/page-1/children",
                    "{\"object\":\"list\",\"results\":["
                            + "{\"id\":\"img\",\"type\":\"image\",\"has_children\":false,"
                            + "\"image\":{\"type\":\"file\",\"file\":{\"url\":\""
                            + server.baseUrl() + "/pic.png\"}}},"
                            + "{\"id\":\"doc\",\"type\":\"pdf\",\"has_children\":false,"
                            + "\"pdf\":{\"type\":\"file\",\"file\":{\"url\":\""
                            + server.baseUrl() + "/doc.pdf\"}}}],\"has_more\":false}");
            server.route("/pic.png", exchange -> NotionStubServer.respond(exchange, 200, "PNG"));
            server.route("/doc.pdf", exchange -> NotionStubServer.respond(exchange, 200, "PDF"));

            NotionConnector connector = NotionTestSupport.fastConnector();
            List<FetchedItem> items = connector.fetchAll(
                    NotionTestSupport.config("tok", server.baseUrl(), List.of("page-1")),
                    List.of("page-1"));

            assertThat(items).hasSize(2);
            FetchedItem attachment = items.get(1);
            assertThat(attachment.getExternalId()).isEqualTo("page-1:doc.pdf");
            assertThat(attachment.getTitle()).isEqualTo("doc.pdf");
            assertThat(attachment.getFileName()).isEqualTo("doc.pdf");
            assertThat(attachment.getContentType()).isEqualTo("application/pdf");
            assertThat(attachment.getSourceResourceId()).isEqualTo("page-1");
            assertThat(attachment.getMetadata())
                    .containsEntry("object_type", "attachment");
            assertThat(NotionTestSupport.contentOf(attachment)).isEqualTo("PDF");
            // 图片只出现在 markdown 里，不做单独条目
            assertThat(NotionTestSupport.contentOf(items.get(0)))
                    .contains("![](" + server.baseUrl() + "/pic.png)");
        }
    }

    @Test
    void fetchAllSkipsEmptyPages() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/search",
                    "{\"object\":\"list\",\"results\":[" + PAGE_1 + "],\"has_more\":false}");
            server.json("/v1/pages/page-1", PAGE_1);
            server.json("/v1/blocks/page-1/children",
                    "{\"object\":\"list\",\"results\":[],\"has_more\":false}");
            NotionConnector connector = NotionTestSupport.fastConnector();
            assertThat(connector.fetchAll(
                    NotionTestSupport.config("tok", server.baseUrl(), List.of("page-1")),
                    List.of("page-1"))).isEmpty();
        }
    }

    /** 请求头与 base_url 的默认值：默认打到 api.notion.com（这里只断言字符串）。 */
    @Test
    void defaultBaseUrlConstant() {
        assertThat(NotionConstants.DEFAULT_BASE_URL).isEqualTo("https://api.notion.com");
        assertThat(NotionConstants.API_VERSION).isEqualTo("2026-03-11");
        assertThat(NotionConstants.MAX_BLOCK_DEPTH).isEqualTo(5);
        assertThat(NotionConstants.MAX_BLOCKS_PER_PAGE).isEqualTo(1000);
        assertThat(NotionConstants.MAX_DOWNLOAD_SIZE).isEqualTo(100 * 1024 * 1024);
        assertThat(NotionConstants.MAX_RETRIES).isEqualTo(3);
        assertThat(Set.of(
                NotionConstants.PARENT_TYPE_WORKSPACE,
                NotionConstants.PARENT_TYPE_PAGE_ID,
                NotionConstants.PARENT_TYPE_DATABASE_ID,
                NotionConstants.PARENT_TYPE_DATA_SOURCE_ID,
                NotionConstants.PARENT_TYPE_BLOCK_ID))
                .containsExactlyInAnyOrder("workspace", "page_id", "database_id",
                        "data_source_id", "block_id");
    }

    /** 指数退避的默认实现（对照 Go 的 {@code 1<<attempt} 秒）。 */
    @Test
    void defaultBackoffIsExponentialSeconds() {
        NotionClient.Backoff backoff = NotionClient.Backoff.exponentialSeconds();
        assertThat(backoff.delayMillis(0)).isEqualTo(1000L);
        assertThat(backoff.delayMillis(1)).isEqualTo(2000L);
        assertThat(backoff.delayMillis(2)).isEqualTo(4000L);
    }

    /** 时间格式化：Go 的 RFC3339Nano（保留偏移、去尾随零）。 */
    @Test
    void rfc3339NanoFormatting() {
        assertThat(NotionValues.rfc3339Nano(
                OffsetDateTime.parse("2026-01-15T10:00:00.000Z")))
                .isEqualTo("2026-01-15T10:00:00Z");
        assertThat(NotionValues.rfc3339Nano(
                OffsetDateTime.parse("2026-09-18T14:07:39.482344+08:00")))
                .isEqualTo("2026-09-18T14:07:39.482344+08:00");
        assertThat(NotionValues.rfc3339Nano(
                OffsetDateTime.of(2026, 1, 15, 10, 0, 0, 123_000_000, ZoneOffset.UTC)))
                .isEqualTo("2026-01-15T10:00:00.123Z");
        assertThat(NotionValues.rfc3339Nano(
                OffsetDateTime.parse("2026-01-15T10:00:00+05:30")))
                .isEqualTo("2026-01-15T10:00:00+05:30");
    }

    /** 计数是为了让 {@code AtomicLong} 的 import 有意义——顺便钉住"每次同步只建一个客户端"。 */
    @Test
    void connectorBuildsClientPerCall() throws Exception {
        AtomicLong clients = new AtomicLong();
        try (NotionStubServer server = new NotionStubServer()) {
            fakeNotion(server);
            NotionConnector connector = new NotionConnector((token, baseUrl) -> {
                clients.incrementAndGet();
                return NotionTestSupport.fastClient(token, baseUrl, null);
            });
            connector.validate(NotionTestSupport.config("test-token", server.baseUrl(), null));
            connector.listResources(NotionTestSupport.config("test-token", server.baseUrl(), null), "");
            assertThat(clients.get()).isEqualTo(2);
        }
    }
}
