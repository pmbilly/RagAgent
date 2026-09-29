package com.ragagent.datasource.connector.feishu.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.connector.feishu.core.FeishuRegion;
import com.ragagent.datasource.connector.feishu.core.FeishuSupport;
import com.ragagent.datasource.connector.feishu.core.FeishuTestServer;
import com.ragagent.datasource.connector.feishu.core.FeishuTestSupport;
import com.ragagent.datasource.connector.feishu.core.SyncEngine;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 对照 Go {@code feishu/wiki/connector_test.go} 全文（含 {@code connector_golden_test.go}）。
 *
 * <p>覆盖：连接器接口面、惰性加载、祖先解析、各 obj_type 的抓取、docx 的 blocks 路径
 * 与导出回落、附件/图片的白名单与大小过滤、增量与删除检测、部分列举、
 * 游标线格式的往返。</p>
 */
class WikiConnectorTest {

    private static FeishuTestServer server;

    @BeforeAll
    static void beforeAll() {
        FeishuTestSupport.allowLoopback();
    }

    @AfterAll
    static void afterAll() {
        FeishuTestSupport.restoreSsrf();
    }

    @BeforeEach
    void start() throws IOException {
        server = new FeishuTestServer();
        WikiFixtures.tokenRoute(server);
        WikiFixtures.spacesRoute(server);
    }

    @AfterEach
    void stop() {
        server.close();
        WikiFixtures.resetParseMode();
    }

    private WikiConnector connector() {
        return new WikiConnector(FeishuRegion.FEISHU);
    }

    private DataSourceConfig config(List<String> resourceIds) {
        return FeishuTestSupport.wikiConfig(server.baseUrl(), resourceIds);
    }

    /** 对照 Go 的 {@code fakeFeishu(nodes)}：只有顶层节点。 */
    private void fakeFeishu(WikiFixtures.Node... nodes) {
        WikiFixtures.hierarchyRoute(server, List.of(nodes), Map.of());
    }

    // ──────────────────────────────────────────────────────────────────
    // 连接器接口面
    // ──────────────────────────────────────────────────────────────────

    @Test
    void connectorType() {
        assertThat(connector().type()).isEqualTo("feishu");
        assertThat(new WikiConnector(FeishuRegion.LARK).type()).isEqualTo("lark");
    }

    @Test
    void connectorValidate() {
        connector().validate(config(null)); // 不抛异常即通过
    }

    @Test
    @DisplayName("凭据解析失败时 validate 抛 ConnectorException（不落到真实连接）")
    void connectorValidateBadCredentials() {
        DataSourceConfig ds = new DataSourceConfig();
        ds.setCredentials(new LinkedHashMap<>(Map.of(
                "app_id", "bad", "app_secret", "bad",
                "base_url", "http://127.0.0.1:1")));

        assertThatThrownBy(() -> connector().validate(ds))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("feishu connection failed");
    }

    // ──────────────────────────────────────────────────────────────────
    // ListResources / ResolveResourceAncestors
    // ──────────────────────────────────────────────────────────────────

    @Test
    void listResourcesRootReturnsSpaces() {
        List<Resource> resources = connector().listResources(config(null), "");
        assertThat(resources).hasSize(1);
        assertThat(resources.get(0).getExternalId()).isEqualTo("space1");
        assertThat(resources.get(0).getName()).isEqualTo("Test Space");
        assertThat(resources.get(0).getType()).isEqualTo("wiki_space");
        assertThat(resources.get(0).getDescription()).isEqualTo("desc");
        assertThat(resources.get(0).getUrl()).isEqualTo("https://feishu.cn/wiki/space1");
        assertThat(resources.get(0).isHasChildren()).isTrue();
        assertThat(resources.get(0).getMetadata())
                .containsEntry("visibility", "public")
                .containsEntry("space_id", "space1");
    }

    @Test
    void listResourcesNullParentIdIsRoot() {
        assertThat(connector().listResources(config(null), null)).hasSize(1);
    }

    @Test
    @DisplayName("惰性加载：一次只加载一层（#1672）")
    void listResourcesLazyLoadsOneLevel() {
        WikiFixtures.Node root = WikiFixtures.Node.of("nt-root", "obj-root", "docx", "Root", "100")
                .child();
        WikiFixtures.Node peer = WikiFixtures.Node.of("nt-peer", "obj-peer", "docx", "Peer", "200");
        Map<String, List<WikiFixtures.Node>> children = new LinkedHashMap<>();
        children.put("nt-root", List.of(
                WikiFixtures.Node.of("nt-child", "obj-child", "docx", "Child", "300")));
        WikiFixtures.hierarchyRoute(server, List.of(root, peer), children);

        // 根：只有空间，没有后代
        List<Resource> spaces = connector().listResources(config(null), "");
        assertThat(spaces).hasSize(1);
        assertThat(spaces.get(0).isHasChildren()).isTrue();

        // 展开空间：只有顶层节点
        List<Resource> top = connector().listResources(config(null), "space1");
        assertThat(top).hasSize(2);
        Map<String, Resource> byId = new LinkedHashMap<>();
        for (Resource r : top) {
            byId.put(r.getExternalId(), r);
        }
        Resource rootRes = byId.get("space1:nt-root");
        assertThat(rootRes.getParentId()).isEqualTo("space1");
        assertThat(rootRes.isHasChildren()).isTrue();
        assertThat(rootRes.getUrl()).isEqualTo("https://feishu.cn/wiki/nt-root");

        // 展开节点：只有直接子节点
        List<Resource> kids = connector().listResources(config(null), "space1:nt-root");
        assertThat(kids).hasSize(1);
        Resource child = kids.get(0);
        assertThat(child.getExternalId()).isEqualTo("space1:nt-child");
        assertThat(child.getParentId()).isEqualTo("space1:nt-root");
        assertThat(child.getName()).isEqualTo("Child");
        assertThat(child.getType()).isEqualTo("wiki_node");
        assertThat(child.getMetadata())
                .containsEntry("space_id", "space1")
                .containsEntry("node_token", "nt-child")
                .containsEntry("obj_token", "obj-child")
                .containsEntry("obj_type", "docx");
    }

    @Test
    void resolveResourceAncestors() {
        WikiFixtures.Node root = WikiFixtures.Node.of("nt-root", "obj-root", "docx", "Root", "100")
                .child();
        WikiFixtures.Node child = WikiFixtures.Node.of("nt-child", "obj-child", "docx", "Child", "200")
                .child();
        Map<String, List<WikiFixtures.Node>> children = new LinkedHashMap<>();
        children.put("nt-root", List.of(child));
        children.put("nt-child", List.of(
                WikiFixtures.Node.of("nt-grandchild", "obj-gc", "docx", "Grandchild", "300")));
        WikiFixtures.hierarchyRoute(server, List.of(root), children);

        // 顺序就是 Go 的插入序：先空间，然后**自底向上**逐级父节点
        List<String> ancestors = connector().resolveResourceAncestors(
                config(null), List.of("space1:nt-grandchild"));
        assertThat(ancestors).containsExactly("space1", "space1:nt-child", "space1:nt-root");
        assertThat(ancestors).doesNotContain("space1:nt-grandchild");

        assertThat(connector().resolveResourceAncestors(config(null), List.of("space1:nt-root")))
                .containsExactly("space1");

        assertThat(connector().resolveResourceAncestors(config(null), List.of("space1"))).isEmpty();

        // 去重：两个选中项共享同一个祖先链时只回一次
        assertThat(connector().resolveResourceAncestors(
                config(null), List.of("space1:nt-grandchild", "space1:nt-child")))
                .containsExactly("space1", "space1:nt-child", "space1:nt-root");
    }

    @Test
    @DisplayName("祖先解析是尽力而为：取不到的节点不抛异常，路径保持折叠")
    void resolveResourceAncestorsBestEffort() {
        WikiFixtures.Node root = WikiFixtures.Node.of("nt-root", "obj-root", "docx", "Root", "100")
                .child();
        WikiFixtures.hierarchyRoute(server, List.of(root), Map.of());

        List<String> ancestors = connector().resolveResourceAncestors(
                config(null), List.of("space1:nt-unknown"));
        assertThat(ancestors).containsExactly("space1");
    }

    // ──────────────────────────────────────────────────────────────────
    // FetchAll：各 obj_type
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FetchAll")
    class FetchAllTests {

        @Test
        @DisplayName("docx（默认 export 模式）：单条目、二进制、时间戳取 obj_*")
        void docxNode() {
            fakeFeishu(WikiFixtures.Node.of("nt1", "obj-docx-1", "docx", "My Document", "1711000000")
                    .withCreate("1700000000", "1700000001"));
            WikiFixtures.exportTrio(server, "fake-docx-content");

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(1);
            FetchedItem item = items.get(0);
            assertThat(item.getExternalId()).isEqualTo("nt1");
            assertThat(item.getTitle()).isEqualTo("My Document");
            assertThat(new String(item.getContent(), StandardCharsets.UTF_8))
                    .isEqualTo("fake-docx-content");
            assertThat(item.getFileName()).isEqualTo("exported.docx");
            assertThat(item.getContentType()).isEqualTo("application/octet-stream");
            assertThat(item.getMetadata())
                    .containsEntry("obj_type", "docx")
                    .containsEntry("channel", "feishu")
                    .containsEntry("obj_token", "obj-docx-1")
                    .containsEntry("node_token", "nt1")
                    .containsEntry("space_id", "space1");
            assertThat(item.getSourceResourceId()).isEqualTo("space1");
            assertThat(item.getUrl()).isEqualTo("https://feishu.cn/wiki/nt1");
            // 源时间戳来自文档（obj_*），不是 wiki 节点属性——改名/移动不算内容编辑
            assertThat(item.getUpdatedAt().toEpochSecond()).isEqualTo(1711000000L);
            assertThat(item.getCreatedAt().toEpochSecond()).isEqualTo(1700000000L);
        }

        @Test
        @DisplayName("时间回落到 node_*（对照 TestContentTimes_FallBackToNodeTimes）")
        void contentTimesFallBackToNodeTimes() {
            com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNode n =
                    new com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNode();
            n.setNodeCreateTime("1700000001");
            n.setNodeEditTime("1711468800");
            assertThat(WikiConnector.contentEditTime(n).toEpochSecond()).isEqualTo(1711468800L);
            assertThat(WikiConnector.contentCreateTime(n).toEpochSecond()).isEqualTo(1700000001L);

            com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNode empty =
                    new com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNode();
            // 缺失的时间保持 Go 零值，而不是 epoch
            assertThat(WikiConnector.contentEditTime(empty).toInstant())
                    .isEqualTo(java.time.Instant.parse("0001-01-01T00:00:00Z"));
            assertThat(WikiConnector.contentCreateTime(empty).toInstant())
                    .isEqualTo(java.time.Instant.parse("0001-01-01T00:00:00Z"));
        }

        @Test
        void sheetNode() {
            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-sheet", "obj-sheet-1", "sheet",
                    "Sales Report", "1711468800"));
            WikiFixtures.exportTrio(server, "fake-docx-content");

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getMetadata()).containsEntry("obj_type", "sheet");
        }

        @Test
        void bitableNode() {
            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-bitable", "obj-bitable-1", "bitable",
                    "Project Tracker", "1711468800"));
            WikiFixtures.exportTrio(server, "fake-docx-content");

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getMetadata()).containsEntry("obj_type", "bitable");
        }

        @Test
        void fileNode() {
            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-file", "obj-file-1", "file",
                    "manual.pdf", "1711468800"));
            WikiFixtures.driveFileDownloadRoute(server, "fake-pdf-binary");

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(1);
            FetchedItem item = items.get(0);
            assertThat(new String(item.getContent(), StandardCharsets.UTF_8))
                    .isEqualTo("fake-pdf-binary");
            assertThat(item.getFileName()).isEqualTo("manual.pdf");
            assertThat(item.getMetadata()).containsEntry("obj_type", "file");
        }

        @Test
        @DisplayName("mindnote + slides 被跳过（没有内容读取 API）")
        void skipsMindnoteAndSlides() {
            fakeFeishu(
                    WikiFixtures.Node.of("nt-mn", "obj-mn", "mindnote", "Brain Map", "1"),
                    WikiFixtures.Node.of("nt-sl", "obj-sl", "slides", "Presentation", "2"));

            assertThat(connector().fetchAll(config(List.of("space1")), List.of("space1"))).isEmpty();
        }

        @Test
        void mixedTypes() {
            fakeFeishu(
                    WikiFixtures.Node.withNodeTime("nt1", "obj1", "docx", "Doc", "1711468800"),
                    WikiFixtures.Node.withNodeTime("nt2", "obj2", "sheet", "Sheet", "1711468800"),
                    WikiFixtures.Node.withNodeTime("nt3", "obj3", "file", "report.pdf", "1711468800"),
                    WikiFixtures.Node.withNodeTime("nt4", "obj4", "mindnote", "Mind", "1711468800"),
                    WikiFixtures.Node.withNodeTime("nt5", "obj5", "slides", "Slides", "1711468800"),
                    WikiFixtures.Node.withNodeTime("nt6", "obj6", "bitable", "Table", "1711468800"));
            WikiFixtures.exportTrio(server, "fake-docx-content");
            WikiFixtures.driveFileDownloadRoute(server, "fake-pdf-binary");

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(4);
        }

        @Test
        @DisplayName("汇总日志带跳过明细（对照 TestFetchAll_LogsSummaryWithSkipBreakdown）")
        void logsSummaryWithSkipBreakdown() {
            fakeFeishu(
                    WikiFixtures.Node.withNodeTime("nt1", "obj1", "docx", "Doc", "1711468800"),
                    WikiFixtures.Node.withNodeTime("nt4", "obj4", "mindnote", "Mind", "1711468800"),
                    WikiFixtures.Node.withNodeTime("nt5", "obj5", "slides", "Slides", "1711468800"));
            WikiFixtures.exportTrio(server, "fake-docx-content");

            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            ch.qos.logback.classic.Logger engineLogger = (ch.qos.logback.classic.Logger)
                    LoggerFactory.getLogger(SyncEngine.class);
            // ⚠️ 必须显式把 logger 压到 INFO：测试期 application.yml 把
            // logging.level.com.ragagent 设成 WARN，而 Logback 的级别过滤发生在
            // **appender 之前**——不设的话本用例在"单跑这个包"时通过（没有 Spring
            // 上下文，级别还是 Logback 默认值），而一旦与任何 @SpringBootTest 同批跑
            // （例如整个 com.ragagent.datasource.*），级别被上下文改成 WARN，
            // INFO 事件根本到不了 appender，断言拿到空串。
            // 这就是约定 §7.5 说的"单跑绿、全量红"那一类隐藏耦合。
            ch.qos.logback.classic.Level previousLevel = engineLogger.getLevel();
            engineLogger.addAppender(appender);
            engineLogger.setLevel(ch.qos.logback.classic.Level.INFO);
            try {
                connector().fetchAll(config(List.of("space1")), List.of("space1"));
            } finally {
                engineLogger.detachAppender(appender);
                engineLogger.setLevel(previousLevel);
            }

            String out = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            for (String want : List.of("stream summary", "discovered=3", "fetched=1",
                    "skipped_unsupported=2", "mindnote:1", "slides:1")) {
                assertThat(out).as("summary log must contain %s", want).contains(want);
            }
        }

        @Test
        @DisplayName("子节点列举失败：不中止，nodes 仍可用，失败子树变成错误条目")
        void childNodeListErrorReturnsPartialItems() {
            WikiFixtures.Node parent = WikiFixtures.Node.withNodeTime("nt-parent", "obj-parent",
                    "file", "Parent.pdf", "100").child();
            WikiFixtures.Node peer = WikiFixtures.Node.withNodeTime("nt-peer", "obj-peer",
                    "file", "Peer.pdf", "200");
            WikiFixtures.hierarchyRoute(server, List.of(parent, peer), Map.of(), "nt-parent");
            WikiFixtures.driveFileDownloadRoute(server, "fake-file-content");

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(3);

            FetchedItem placeholder = null;
            int fetched = 0;
            for (FetchedItem it : items) {
                if (it.getMetadata() != null && it.getMetadata().get("error") != null
                        && !it.getMetadata().get("error").isEmpty()) {
                    placeholder = it;
                } else {
                    fetched++;
                }
            }
            assertThat(fetched).isEqualTo(2);
            assertThat(placeholder).isNotNull();
            assertThat(placeholder.getExternalId()).isEqualTo("nt-parent");
            assertThat(placeholder.getTitle()).isEqualTo("Parent.pdf");
            assertThat(placeholder.getMetadata())
                    .containsEntry("channel", "feishu")
                    .containsEntry("node_token", "nt-parent")
                    .containsEntry("space_id", "space1")
                    .containsEntry("failure_stage", "list_children");
            assertThat(placeholder.getMetadata().get("error"))
                    .contains("list children of nt-parent");
        }

        @Test
        @DisplayName("选中子树时只同步该子树（镜像 Go 的 resourceID 语义）")
        void wikiNodeResourceSyncsSelectedSubtree() {
            WikiFixtures.Node root = WikiFixtures.Node.withNodeTime("nt-root", "obj-root",
                    "file", "Root.pdf", "100").child();
            WikiFixtures.Node peer = WikiFixtures.Node.withNodeTime("nt-peer", "obj-peer",
                    "file", "Peer.pdf", "200");
            Map<String, List<WikiFixtures.Node>> children = new LinkedHashMap<>();
            children.put("nt-root", List.of(WikiFixtures.Node.withNodeTime("nt-child", "obj-child",
                    "file", "Child.pdf", "300")));
            WikiFixtures.hierarchyRoute(server, List.of(root, peer), children);
            WikiFixtures.driveFileDownloadRoute(server, "fake-file-content");

            String resourceId = "space1:nt-root";
            List<FetchedItem> items = connector().fetchAll(config(List.of(resourceId)),
                    List.of(resourceId));
            assertThat(items).hasSize(2);
            Map<String, FetchedItem> byId = new LinkedHashMap<>();
            for (FetchedItem it : items) {
                byId.put(it.getExternalId(), it);
            }
            assertThat(byId).containsKeys("nt-root", "nt-child").doesNotContainKey("nt-peer");
            assertThat(byId.get("nt-root").getSourceResourceId()).isEqualTo(resourceId);
            assertThat(byId.get("nt-child").getSourceResourceId()).isEqualTo(resourceId);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // FetchIncremental
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FetchIncremental")
    class FetchIncrementalTests {

        @Test
        void firstSync() {
            fakeFeishu(
                    WikiFixtures.Node.withNodeTime("nt1", "obj1", "docx", "Doc1", "100"),
                    WikiFixtures.Node.withNodeTime("nt2", "obj2", "file", "file.pdf", "200"));
            WikiFixtures.exportTrio(server, "fake-docx-content");
            WikiFixtures.driveFileDownloadRoute(server, "fake-pdf-binary");

            DataSourceConfig ds = config(List.of("space1"));
            com.ragagent.datasource.Connector.FetchIncrementalResult result =
                    connector().fetchIncremental(ds, null);
            assertThat(result.items()).hasSize(2);
            assertThat(result.cursor()).isNotNull();
            assertThat(result.cursor().getLastSyncTime()).isNotEqualTo(
                    com.ragagent.common.web.GoTimeSerializer.GO_ZERO_DATE_TIME);
        }

        @Test
        void noChanges() {
            fakeFeishu(WikiFixtures.Node.withNodeTime("nt1", "obj1", "docx", "Doc1", "100"));
            WikiFixtures.exportTrio(server, "fake-docx-content");

            DataSourceConfig ds = config(List.of("space1"));
            SyncCursor cursor = connector().fetchIncremental(ds, null).cursor();
            com.ragagent.datasource.Connector.FetchIncrementalResult second =
                    connector().fetchIncremental(ds, cursor);
            assertThat(second.items()).isEmpty();
        }

        @Test
        @DisplayName("删除检测：只在完整列举时做")
        void detectsDeleted() {
            fakeFeishu(
                    WikiFixtures.Node.withNodeTime("nt1", "obj1", "docx", "Doc1", "100"),
                    WikiFixtures.Node.withNodeTime("nt2", "obj2", "docx", "Doc2", "200"));
            WikiFixtures.exportTrio(server, "fake-docx-content");

            DataSourceConfig ds = config(List.of("space1"));
            SyncCursor cursor = connector().fetchIncremental(ds, null).cursor();

            server.close();
            try {
                server = new FeishuTestServer();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            WikiFixtures.tokenRoute(server);
            WikiFixtures.spacesRoute(server);
            fakeFeishu(WikiFixtures.Node.withNodeTime("nt1", "obj1", "docx", "Doc1", "100"));
            WikiFixtures.exportTrio(server, "fake-docx-content");

            DataSourceConfig ds2 = config(List.of("space1"));
            com.ragagent.datasource.Connector.FetchIncrementalResult result =
                    connector().fetchIncremental(ds2, cursor);

            int deleted = 0;
            for (FetchedItem it : result.items()) {
                if (it.isDeleted()) {
                    deleted++;
                    assertThat(it.getExternalId()).isEqualTo("nt2");
                    assertThat(it.getSourceResourceId()).isEqualTo("space1");
                }
            }
            assertThat(deleted).isEqualTo(1);
        }

        @Test
        void noResourceIds() {
            DataSourceConfig ds = config(List.of());
            assertThatThrownBy(() -> connector().fetchIncremental(ds, null))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("no resource IDs (wiki space IDs or wiki node IDs) configured");
        }

        @Test
        void fetchStreamRejectsEmptyResourceIds() {
            DataSourceConfig ds = config(List.of());
            assertThatThrownBy(() -> connector().fetchStream(ds, null,
                    new FeishuTestSupport.RecordingHandler()))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("no resource IDs (wiki space IDs or wiki node IDs) configured");
        }

        @Test
        @DisplayName("部分列举也返回 cursor（部分成功），并保留先前子节点的游标条目")
        void childNodeListErrorReturnsPartialItemsAndCursor() {
            WikiFixtures.Node parent = WikiFixtures.Node.withNodeTime("nt-parent", "obj-parent",
                    "file", "Parent.pdf", "100").child();
            WikiFixtures.Node peer = WikiFixtures.Node.withNodeTime("nt-peer", "obj-peer",
                    "file", "Peer.pdf", "200");
            WikiFixtures.hierarchyRoute(server, List.of(parent, peer), Map.of(), "nt-parent");
            WikiFixtures.driveFileDownloadRoute(server, "fake-file-content");

            com.ragagent.datasource.Connector.FetchIncrementalResult result =
                    connector().fetchIncremental(config(List.of("space1")), null);
            assertThat(result.cursor()).isNotNull();
            assertThat(result.items()).hasSize(3);

            FetchedItem placeholder = null;
            for (FetchedItem it : result.items()) {
                if (it.getMetadata() != null && it.getMetadata().get("error") != null
                        && !it.getMetadata().get("error").isEmpty()) {
                    placeholder = it;
                }
            }
            assertThat(placeholder).isNotNull();
            assertThat(placeholder.getMetadata()).containsEntry("node_token", "nt-parent");
        }

        @Test
        @DisplayName("部分列举不得把先前见过的子节点标成删除，且游标条目必须保留")
        void childNodeListErrorDoesNotDeletePreviouslySeenChildren() {
            WikiFixtures.Node parent = WikiFixtures.Node.withNodeTime("nt-parent", "obj-parent",
                    "file", "Parent.pdf", "100").child();
            Map<String, List<WikiFixtures.Node>> firstChildren = new LinkedHashMap<>();
            firstChildren.put("nt-parent", List.of(WikiFixtures.Node.withNodeTime("nt-child",
                    "obj-child", "file", "Child.pdf", "150")));
            WikiFixtures.hierarchyRoute(server, List.of(parent), firstChildren);
            WikiFixtures.driveFileDownloadRoute(server, "fake-file-content");

            com.ragagent.datasource.Connector.FetchIncrementalResult first =
                    connector().fetchIncremental(config(List.of("space1")), null);
            assertThat(first.items()).hasSize(2);

            server.close();
            try {
                server = new FeishuTestServer();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            WikiFixtures.tokenRoute(server);
            WikiFixtures.spacesRoute(server);
            WikiFixtures.hierarchyRoute(server, List.of(parent), Map.of(), "nt-parent");
            WikiFixtures.driveFileDownloadRoute(server, "fake-file-content");

            com.ragagent.datasource.Connector.FetchIncrementalResult second =
                    connector().fetchIncremental(config(List.of("space1")), first.cursor());
            for (FetchedItem it : second.items()) {
                assertThat(it.isDeleted())
                        .as("部分子节点列举失败不得标记先前子节点为删除: %s", it.getExternalId())
                        .isFalse();
            }
            Map<String, Map<String, String>> times =
                    com.ragagent.datasource.connector.feishu.core.FeishuCursorCodec
                            .decodeSpaceNodeTimes(second.cursor().getConnectorCursor());
            assertThat(times.get("space1")).containsEntry("nt-child", "150");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // docx blocks 路径
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("docx blocks 路径（parse mode = blocks）")
    class DocxBlocksTests {

        @Test
        @DisplayName("主 Markdown 条目 + 附件子条目（ReplacesSubtree=true）")
        void multiItem() {
            WikiFixtures.useBlocksParseMode();
            String attToken = "ft-att-1";
            byte[] attContent = WikiFixtures.repeat("x", FeishuSupport.MIN_ATTACHMENT_BYTES + 1);

            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-docx", "obj-docx", "docx",
                    "My Blocks Doc", "1711468800"));
            WikiFixtures.blocksRoute(server, "obj-docx", WikiFixtures.blocks(
                    WikiFixtures.pageBlockJson("b1"),
                    WikiFixtures.textBlockJson("b2", 2, "Hello blocks"),
                    WikiFixtures.fileBlockJson("b3", attToken, "report.pdf")));
            WikiFixtures.mediaRoute(server, Map.of(attToken, attContent));

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(2);

            FetchedItem main = items.get(0);
            assertThat(main.getExternalId()).isEqualTo("nt-docx");
            assertThat(main.getContentType()).isEqualTo("text/markdown");
            assertThat(main.isReplacesSubtree()).isTrue();
            assertThat(main.getFileName()).isEqualTo("My Blocks Doc.md");
            assertThat(new String(main.getContent(), StandardCharsets.UTF_8))
                    .contains("Hello blocks");
            assertThat(main.getSubtreeKeep()).containsExactly("nt-docx#file#" + attToken);

            FetchedItem att = items.get(1);
            assertThat(att.getExternalId()).isEqualTo("nt-docx#file#" + attToken);
            assertThat(att.getTitle()).isEqualTo("report.pdf");
            assertThat(att.getMetadata())
                    .containsEntry("attachment", "true")
                    .containsEntry("parent_node_token", "nt-docx")
                    .containsEntry("channel", "feishu");
            assertThat(att.getContent()).isEqualTo(attContent);
            assertThat(att.getFileName()).isEqualTo("report.pdf");
        }

        @Test
        @DisplayName("blocks API 500 → 导出回落：单条目、octet-stream、不设 ReplacesSubtree")
        void blocksFailFallsBackToExport() {
            WikiFixtures.useBlocksParseMode();
            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-fallback", "obj-fallback", "docx",
                    "Fallback Doc", "600"));
            WikiFixtures.blocksFailureRoute(server, "obj-fallback");
            WikiFixtures.exportTrio(server, "fake-exported-fallback-binary");

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(1);
            FetchedItem item = items.get(0);
            assertThat(item.getExternalId()).isEqualTo("nt-fallback");
            assertThat(item.getContentType()).isEqualTo("application/octet-stream");
            assertThat(item.isReplacesSubtree()).isFalse();
            assertThat(item.getMetadata().get("error")).isNull();
        }

        @Test
        @DisplayName("blocks 渲染成空 Markdown → 也回落导出")
        void blocksEmptyFallsBackToExport() {
            WikiFixtures.useBlocksParseMode();
            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-blank", "obj-blank", "docx",
                    "Blank Doc", "600"));
            WikiFixtures.blocksRoute(server, "obj-blank",
                    WikiFixtures.blocks(WikiFixtures.pageBlockJson("b1")));
            WikiFixtures.exportTrio(server, "fake-exported-fallback-binary");

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getContentType()).isEqualTo("application/octet-stream");
            assertThat(items.get(0).isReplacesSubtree()).isFalse();
        }

        @Test
        @DisplayName("附件下载失败：文档主体照常灌入，失败附件变成可见错误条目，且留在 SubtreeKeep")
        void attachmentDownloadFailure() {
            WikiFixtures.useBlocksParseMode();
            String attToken = "ft-att-bad";
            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-docx-fail", "obj-docx-fail", "docx",
                    "Doc With Bad Attachment", "1711468800"));
            WikiFixtures.blocksRoute(server, "obj-docx-fail", WikiFixtures.blocks(
                    WikiFixtures.pageBlockJson("b1"),
                    WikiFixtures.textBlockJson("b2", 2, "Hello"),
                    WikiFixtures.fileBlockJson("b3", attToken, "slides.pdf")));
            server.handle("/open-apis/drive/v1/medias/", (ex, body) ->
                    FeishuTestServer.sendStatus(ex, 500, "{\"code\":99,\"msg\":\"server error\"}"));

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(2);

            FetchedItem main = items.get(0);
            FetchedItem errItem = items.get(1);
            assertThat(main.getContentType()).isEqualTo("text/markdown");
            assertThat(main.isReplacesSubtree()).isTrue();
            assertThat(main.getSubtreeKeep()).contains("nt-docx-fail#file#" + attToken);
            assertThat(errItem.getExternalId()).isEqualTo("nt-docx-fail#file#" + attToken);
            assertThat(errItem.getContent() == null || errItem.getContent().length == 0).isTrue();
            assertThat(errItem.getMetadata().get("error")).isNotEmpty();
        }

        @Test
        @DisplayName("内嵌图片：多模态开→子条目；关→不产出但仍留在 SubtreeKeep")
        void embeddedImage() {
            WikiFixtures.useBlocksParseMode();
            String imgToken = "media-img-1";
            byte[] png = concat(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'},
                    WikiFixtures.repeat("x", FeishuSupport.MIN_ATTACHMENT_BYTES));

            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-docx-img", "obj-docx-img", "docx",
                    "Doc With Image", "1711468800"));
            WikiFixtures.blocksRoute(server, "obj-docx-img", WikiFixtures.blocks(
                    WikiFixtures.pageBlockJson("b1"),
                    WikiFixtures.textBlockJson("b2", 2, "Hello"),
                    WikiFixtures.imageBlockJson("b3", imgToken)));
            WikiFixtures.mediaRoute(server, Map.of(imgToken, png));

            String childId = "nt-docx-img#image#" + imgToken;

            DataSourceConfig on = config(List.of("space1"));
            on.setMultimodalEnabled(true);
            List<FetchedItem> items = connector().fetchAll(on, List.of("space1"));
            assertThat(items).hasSize(2);
            FetchedItem main = items.get(0);
            FetchedItem img = items.get(1);
            assertThat(img.getExternalId()).isEqualTo(childId);
            assertThat(img.getFileName()).isEqualTo("image-" + imgToken + ".png");
            assertThat(img.getContentType()).isEqualTo("image/png");
            assertThat(img.getTitle()).isEqualTo("Doc With Image（内嵌图片）");
            assertThat(img.getMetadata())
                    .containsEntry("embedded_image", "true")
                    .doesNotContainKey("attachment");
            assertThat(img.getContent()).isEqualTo(png);
            assertThat(main.getSubtreeKeep()).contains(childId);

            DataSourceConfig off = config(List.of("space1"));
            off.setMultimodalEnabled(false);
            List<FetchedItem> itemsOff = connector().fetchAll(off, List.of("space1"));
            assertThat(itemsOff).hasSize(1);
            assertThat(itemsOff.get(0).getExternalId()).isEqualTo("nt-docx-img");
            assertThat(itemsOff.get(0).getSubtreeKeep()).contains(childId);
        }

        @Test
        @DisplayName("图片下载失败：可见错误子条目 + 留在 SubtreeKeep；多模态关时不尝试下载")
        void imageDownloadFailure() {
            WikiFixtures.useBlocksParseMode();
            String imgToken = "media-img-bad";
            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-docx-img-fail", "obj-docx-img-fail",
                    "docx", "Doc With Bad Image", "1711468800"));
            WikiFixtures.blocksRoute(server, "obj-docx-img-fail", WikiFixtures.blocks(
                    WikiFixtures.pageBlockJson("b1"),
                    WikiFixtures.imageBlockJson("b2", imgToken)));
            server.handle("/open-apis/drive/v1/medias/", (ex, body) ->
                    FeishuTestServer.sendStatus(ex, 500, "boom"));

            String childId = "nt-docx-img-fail#image#" + imgToken;

            DataSourceConfig on = config(List.of("space1"));
            on.setMultimodalEnabled(true);
            List<FetchedItem> items = connector().fetchAll(on, List.of("space1"));
            assertThat(items).hasSize(2);
            FetchedItem main = items.get(0);
            FetchedItem errItem = items.get(1);
            assertThat(errItem.getExternalId()).isEqualTo(childId);
            assertThat(errItem.getContent() == null || errItem.getContent().length == 0).isTrue();
            assertThat(errItem.getMetadata().get("error")).isNotEmpty();
            assertThat(errItem.getMetadata()).containsEntry("embedded_image", "true");
            assertThat(main.getSubtreeKeep()).contains(childId);
            int mediasBefore = server.countPath("/open-apis/drive/v1/medias/" + imgToken + "/download");

            DataSourceConfig off = config(List.of("space1"));
            off.setMultimodalEnabled(false);
            assertThat(connector().fetchAll(off, List.of("space1"))).hasSize(1);
            assertThat(server.countPath("/open-apis/drive/v1/medias/" + imgToken + "/download"))
                    .isEqualTo(mediasBefore);
        }

        @Test
        @DisplayName("非白名单扩展名（.png）不升级为子条目，但内联引用仍在主文档里")
        void nonWhitelistedExtNotPromoted() {
            WikiFixtures.useBlocksParseMode();
            String attToken = "ft-icon";
            byte[] content = WikiFixtures.repeat("x", FeishuSupport.MIN_ATTACHMENT_BYTES + 100);

            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-docx-png", "obj-docx-png", "docx",
                    "Doc With PNG", "1711468800"));
            WikiFixtures.blocksRoute(server, "obj-docx-png", WikiFixtures.blocks(
                    WikiFixtures.pageBlockJson("b1"),
                    WikiFixtures.textBlockJson("b2", 2, "Hello"),
                    WikiFixtures.fileBlockJson("b3", attToken, "icon.png")));
            WikiFixtures.mediaRoute(server, Map.of(attToken, content));

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(1);
            assertThat(new String(items.get(0).getContent(), StandardCharsets.UTF_8))
                    .contains("📎 附件：icon.png");
        }

        @Test
        @DisplayName("白名单扩展名但体积过小 → 不升级为子条目")
        void whitelistedTinyAttachmentNotPromoted() {
            WikiFixtures.useBlocksParseMode();
            String attToken = "ft-tiny-pdf";
            byte[] content = WikiFixtures.repeat("x", 100);

            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-docx-tiny", "obj-docx-tiny", "docx",
                    "Doc With Tiny PDF", "1711468800"));
            WikiFixtures.blocksRoute(server, "obj-docx-tiny", WikiFixtures.blocks(
                    WikiFixtures.pageBlockJson("b1"),
                    WikiFixtures.textBlockJson("b2", 2, "Hello"),
                    WikiFixtures.fileBlockJson("b3", attToken, "tiny.pdf")));
            WikiFixtures.mediaRoute(server, Map.of(attToken, content));

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(1);
            assertThat(new String(items.get(0).getContent(), StandardCharsets.UTF_8))
                    .contains("📎 附件：tiny.pdf");
        }

        @Test
        @DisplayName("Golden：一篇富 docx 走通全链路（blocks/sheets/bitable/medias），校验顺序与不漏 token")
        void goldenRichDocxAllCapabilities() {
            WikiFixtures.useBlocksParseMode();
            String docToken = "obj-golden";
            byte[] bigPdf = WikiFixtures.repeat("A", FeishuSupport.MIN_ATTACHMENT_BYTES + 512);
            byte[] tinyPdf = WikiFixtures.repeat("B", 100);

            fakeFeishu(WikiFixtures.Node.withNodeTime("nt-golden", docToken, "docx",
                    "季度报告文档", "1711468800"));
            WikiFixtures.blocksRoute(server, docToken, WikiFixtures.blocks(
                    WikiFixtures.pageBlockJson("root"),
                    WikiFixtures.headingBlockJson("h1", 1, "季度报告"),
                    WikiFixtures.textBlockJson("p1", 2, "本季度概览。"),
                    WikiFixtures.headingBlockJson("h2", 2, "关键指标"),
                    WikiFixtures.textBlockJson("b1", 12, "收入增长"),
                    WikiFixtures.textBlockJson("o1", 13, "第一步立项"),
                    WikiFixtures.textBlockJson("code1", 14, "SELECT 1"),
                    WikiFixtures.textBlockJson("q1", 15, "重要提示"),
                    WikiFixtures.textBlockJson("todo1", 17, "完成复盘"),
                    WikiFixtures.textBlockJson("call1", 19, "注意风险"),
                    WikiFixtures.dividerBlockJson("div1"),
                    WikiFixtures.tableBlockJson("tbl1", 2, "c1", "c2", "c3", "c4"),
                    WikiFixtures.sheetBlockJson("sh1", "sht_spread_0"),
                    WikiFixtures.bitableBlockJson("bt1", "bascApp_tblMain"),
                    WikiFixtures.imageBlockJson("im1", "img-tok-SECRET"),
                    WikiFixtures.fileBlockJson("fbig", "tok-big", "手册.pdf"),
                    WikiFixtures.fileBlockJson("flogo", "tok-logo", "logo.png"),
                    WikiFixtures.fileBlockJson("fsmall", "tok-small", "small.pdf"),
                    WikiFixtures.cellBlockJson("c1", "c1_txt"),
                    WikiFixtures.cellBlockJson("c2", "c2_txt"),
                    WikiFixtures.cellBlockJson("c3", "c3_txt"),
                    WikiFixtures.cellBlockJson("c4", "c4_txt"),
                    WikiFixtures.textBlockJson("c1_txt", 2, "列A"),
                    WikiFixtures.textBlockJson("c2_txt", 2, "列B"),
                    WikiFixtures.textBlockJson("c3_txt", 2, "1"),
                    WikiFixtures.textBlockJson("c4_txt", 2, "2")));

            server.handle("/open-apis/sheets/v2/spreadsheets/sht_spread/values/0", (ex, body) ->
                    FeishuTestServer.sendJson(ex,
                            "{\"code\":0,\"msg\":\"\",\"data\":{\"valueRange\":{\"values\":"
                                    + "[[\"名称\",\"数量\"],[\"苹果\",3]]}}}"));
            server.handle("/open-apis/bitable/v1/apps/bascApp/tables/tblMain/fields",
                    (ex, body) -> FeishuTestServer.sendJson(ex,
                            "{\"code\":0,\"msg\":\"\",\"data\":{\"items\":[{\"field_name\":\"任务\"},"
                                    + "{\"field_name\":\"状态\"}]}}"));
            server.handle("/open-apis/bitable/v1/apps/bascApp/tables/tblMain/records/search",
                    (ex, body) -> FeishuTestServer.sendJson(ex,
                            "{\"code\":0,\"msg\":\"\",\"data\":{\"has_more\":false,\"page_token\":\"\","
                                    + "\"items\":[{\"fields\":{\"任务\":\"写码\",\"状态\":\"完成\"}}]}}"));
            WikiFixtures.mediaRoute(server, Map.of("tok-big", bigPdf, "tok-small", tinyPdf,
                    "img-tok-SECRET", WikiFixtures.repeat("x", FeishuSupport.MIN_ATTACHMENT_BYTES)));

            List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                    List.of("space1"));
            assertThat(items).hasSize(2);

            FetchedItem main = items.get(0);
            assertThat(main.getContentType()).isEqualTo("text/markdown");
            assertThat(main.isReplacesSubtree()).isTrue();
            assertThat(main.getFileName()).isEqualTo("季度报告文档.md");
            String md = new String(main.getContent(), StandardCharsets.UTF_8);

            for (String fragment : List.of(
                    "# 季度报告", "本季度概览。", "## 关键指标", "- 收入增长", "1. 第一步立项",
                    "```\nSELECT 1\n```", "> 重要提示", "- [ ] 完成复盘", "> 注意风险", "---",
                    "| 列A | 列B |", "| 1 | 2 |", "| 名称 | 数量 |", "| 苹果 | 3 |",
                    "| 任务 | 状态 |", "| 写码 | 完成 |", "![图片]()", "📎 附件：手册.pdf",
                    "📎 附件：logo.png", "📎 附件：small.pdf")) {
                assertThat(md).as("markdown 缺少片段 %s", fragment).contains(fragment);
            }

            // 文档顺序保持：标题 → 表格 → sheet → bitable → 附件
            int last = -1;
            for (String fragment : List.of("# 季度报告", "## 关键指标", "| 列A | 列B |",
                    "| 名称 | 数量 |", "| 任务 | 状态 |", "📎 附件：手册.pdf")) {
                int idx = md.indexOf(fragment);
                assertThat(idx).as("片段 %s 乱序", fragment).isGreaterThan(last);
                last = idx;
            }

            // 内部图片 media token 绝不能泄漏进 embedding
            assertThat(md).doesNotContain("img-tok-SECRET");

            FetchedItem att = items.get(1);
            assertThat(att.getExternalId()).isEqualTo("nt-golden#file#tok-big");
            assertThat(att.getTitle()).isEqualTo("手册.pdf");
            assertThat(att.getContent()).isEqualTo(bigPdf);
            assertThat(att.getMetadata())
                    .containsEntry("attachment", "true")
                    .containsEntry("parent_node_token", "nt-golden");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 游标线格式
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FeishuCursor 往返：space_node_times 经 JSON 快照后仍可读")
    void feishuCursorRoundTrip() {
        Map<String, Map<String, String>> times = Map.of("space1", Map.of("nt1", "100", "nt2", "200"));
        OffsetDateTime lastSync = OffsetDateTime.now();
        SyncCursor cursor = com.ragagent.datasource.connector.feishu.core.FeishuCursorCodec
                .encodeSpaceNodeTimes(times, lastSync);

        assertThat(cursor.getConnectorCursor()).containsKey("last_sync_time");
        assertThat(cursor.getConnectorCursor()).containsKey("space_node_times");
        assertThat(cursor.getLastSyncTime().toInstant()).isEqualTo(lastSync.toInstant());

        // 模拟一次 jsonb 落库再读回
        Map<String, Object> snapshot = FeishuTestSupport.deepCopy(cursor.getConnectorCursor());
        Map<String, Map<String, String>> restored =
                com.ragagent.datasource.connector.feishu.core.FeishuCursorCodec
                        .decodeSpaceNodeTimes(snapshot);
        assertThat(restored.get("space1")).containsEntry("nt1", "100").containsEntry("nt2", "200");
    }

    @Test
    @DisplayName("游标 omitempty：times 为空时 space_node_times 键整个消失（对照 Go 的 omitempty）")
    void cursorOmitsEmptyTimes() {
        SyncCursor cursor = com.ragagent.datasource.connector.feishu.core.FeishuCursorCodec
                .encodeSpaceNodeTimes(Map.of(), OffsetDateTime.now());
        assertThat(cursor.getConnectorCursor()).doesNotContainKey("space_node_times");
        assertThat(cursor.getConnectorCursor()).containsKey("last_sync_time");

        assertThat(com.ragagent.datasource.connector.feishu.core.FeishuCursorCodec
                .decodeSpaceNodeTimes(Map.of())).isNull();
    }

    @Test
    @DisplayName("resourceID 编解码：makeWikiNodeResourceID / parseWikiResourceID")
    void resourceIdHelpers() {
        assertThat(WikiConnector.makeWikiNodeResourceId("space1", "nt1")).isEqualTo("space1:nt1");
        assertThat(WikiConnector.parseWikiResourceId("space1:nt1")).containsExactly("space1", "nt1");
        assertThat(WikiConnector.parseWikiResourceId("space1")).containsExactly("space1", "");
        // strings.Cut 在第一个分隔符处切
        assertThat(WikiConnector.parseWikiResourceId("a:b:c")).containsExactly("a", "b:c");
    }

    @Test
    @DisplayName("导出文件名：飞书不带扩展名时补上（大小写不敏感的后缀判定）")
    void exportFileNameGetsExtension() {
        WikiFixtures.useBlocksParseMode();
        fakeFeishu(WikiFixtures.Node.withNodeTime("nt-sheet", "obj-sheet-1", "sheet",
                "Sales Report", "1711468800"));
        server.handle(WikiFixtures.EXPORT_CREATE_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"msg\":\"\",\"data\":{\"ticket\":\"ticket-123\"}}"));
        server.handle("/open-apis/drive/v1/export_tasks/ticket-123", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"result\":{"
                        + "\"file_token\":\"ft-abc\",\"fileSize\":100,\"job_status\":0,"
                        + "\"job_error_msg\":\"\",\"fileName\":\"Sales Report\"}}}"));
        server.handle("/open-apis/drive/v1/export_tasks/file/ft-abc/download", (ex, body) ->
                FeishuTestServer.sendBytes(ex, "application/octet-stream",
                        "x".getBytes(StandardCharsets.UTF_8)));

        List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                List.of("space1"));
        assertThat(items.get(0).getFileName()).isEqualTo("Sales Report.xlsx");
    }

    @Test
    @DisplayName("导出任务失败（job_status=3）→ 节点抓取失败、游标不推进、发出错误条目")
    void exportJobFailureSurfacesErrorItem() {
        fakeFeishu(WikiFixtures.Node.withNodeTime("nt1", "obj1", "docx", "Doc", "100"));
        server.handle(WikiFixtures.EXPORT_CREATE_PATH, (ex, body) -> FeishuTestServer.sendJson(ex,
                "{\"code\":0,\"msg\":\"\",\"data\":{\"ticket\":\"ticket-123\"}}"));
        server.handle("/open-apis/drive/v1/export_tasks/ticket-123", (ex, body) ->
                FeishuTestServer.sendJson(ex, "{\"code\":0,\"msg\":\"\",\"data\":{\"result\":{"
                        + "\"file_token\":\"\",\"fileSize\":0,\"job_status\":3,"
                        + "\"job_error_msg\":\"rate limited\",\"fileName\":\"\"}}}"));

        List<FetchedItem> items = connector().fetchAll(config(List.of("space1")),
                List.of("space1"));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getExternalId()).isEqualTo("nt1");
        // 分类按 Go 的 switch 顺序：原文里带 "rate limited"（job_error_msg）→ 限流分支
        // **先于** "export task failed" 分支命中。这条顺序是 Go 钉死的，别按直觉写。
        assertThat(items.get(0).getMetadata())
                .containsEntry("error_reason_code", "feishu_rate_limited")
                .containsEntry("error_reason",
                        "Feishu API rate limited; will retry on the next sync")
                .doesNotContainKey("error_reason_code_value");
    }

    @Test
    @DisplayName("unsupported obj_type 走导出 API 时立刻失败（对照 ExportAndDownload 的两道 map 查询）")
    void unsupportedObjTypeForExport() {
        assertThatThrownBy(() -> new com.ragagent.datasource.connector.feishu.core.FeishuClient(
                new com.ragagent.datasource.connector.feishu.core.FeishuConfig())
                .exportAndDownload("obj-token-1", "mindnote"))
                .isInstanceOf(ConnectorException.class)
                .hasMessage("unsupported obj_type for export: mindnote");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
