package com.ragagent.datasource;

import com.ragagent.common.web.JsonMappers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.JsonRoundTrip;
import com.ragagent.datasource.domain.DataSourceConstants;
import org.junit.jupiter.api.Test;

/**
 * 连接器框架层（{@code ConnectorException} / {@code ConnectorRegistry} /
 * {@code ConnectorCatalog}）的语义测试。
 *
 * <p>对照 Go：{@code internal/datasource/connector.go} 的
 * {@code ConnectorRegistry} + {@code ConnectorMetadataRegistry} +
 * {@code ListAvailableConnectors}，{@code internal/datasource/errors.go} 的哨兵。</p>
 *
 * <h2>期望值来源</h2>
 * <p>{@link ConnectorException} 的 message 断言是<b>Go 源码里 {@code errors.New(...)} 的原文</b>；
 * {@link ConnectorCatalog} 的 17 条元数据是 <b>Go 实录</b>——把
 * {@code ConnectorMetadataRegistry} 的字面量原样抄进一个独立 Go 程序跑
 * {@code json.Marshal} 得到的键序与字节。见
 * {@code ConnectorCatalogTest} 的常量表。</p>
 */
class ConnectorFrameworkTest {

    // ── ConnectorException：message 与 Go 哨兵逐字一致 ─────────────────────

    @Test
    void exceptionMessagesMatchGoSentinels() {
        assertThat(new ConnectorException.NilConnector().getMessage())
                .isEqualTo("connector is nil");
        assertThat(new ConnectorException.EmptyConnectorType().getMessage())
                .isEqualTo("connector type is empty");
        assertThat(new ConnectorException.NotFound().getMessage())
                .isEqualTo("connector type not found in registry");
        assertThat(new ConnectorException.InvalidConfig().getMessage())
                .isEqualTo("invalid configuration");
        assertThat(new ConnectorException.InvalidConfig("settings.projects is required").getMessage())
                .isEqualTo("invalid configuration: settings.projects is required");
        assertThat(new ConnectorException.InvalidCredentials().getMessage())
                .isEqualTo("invalid credentials");
        assertThat(new ConnectorException.InvalidCredentials("api_token is required").getMessage())
                .isEqualTo("invalid credentials: api_token is required");
        assertThat(new ConnectorException.FetchFailed("rate limited: x").getMessage())
                .isEqualTo("failed to fetch items from source: rate limited: x");
        assertThat(new ConnectorException.ResourceNotFound("/v1/pages/abc").getMessage())
                .isEqualTo("resource not found in source system: /v1/pages/abc");
    }

    /**
     * 对照 Go {@code (*PartialFetchError).Error()} 的两种形态：
     * 空/nil 细节 → {@code "partial fetch: some resources failed"}；
     * 有细节 → {@code "partial fetch: " + strings.Join(details, "; ")}。
     */
    @Test
    void partialFetchErrorMessageMatchesGo() {
        assertThat(new ConnectorException.PartialFetch(null).getMessage())
                .isEqualTo("partial fetch: some resources failed");
        assertThat(new ConnectorException.PartialFetch(List.of()).getMessage())
                .isEqualTo("partial fetch: some resources failed");
        assertThat(new ConnectorException.PartialFetch(List.of("a: x", "b: y")).getMessage())
                .isEqualTo("partial fetch: a: x; b: y");
        assertThat(new ConnectorException.PartialFetch(List.of("a: x", "b: y")).getDetails())
                .containsExactly("a: x", "b: y");
    }

    /**
     * 类型判定必须穿透 cause 链（对照 Go 的 {@code errors.Is} 会 unwrap {@code %w}）。
     *
     * <p>这是 <b>service 层下一步的判型入口</b>：连接器会把
     * {@code InvalidCredentials} 包在若干层 {@code ConnectorException("...", cause)} 里，
     * 直接写 {@code instanceof} 会漏判，从而把"凭据失效"当成"可重试的瞬时故障"。</p>
     */
    @Test
    void typeDetectionWalksTheCauseChainLikeErrorsIs() {
        ConnectorException inner = new ConnectorException.InvalidCredentials("status=401 body=x");
        ConnectorException mid = new ConnectorException("list wiki spaces: " + inner.getMessage(), inner);
        ConnectorException outer = new ConnectorException("fetch wiki nodes: " + mid.getMessage(), mid);

        // 直接写 instanceof 会漏（这正是要避免的写法）
        assertThat(outer).isNotInstanceOf(ConnectorException.InvalidCredentials.class);
        // 走工具函数才对
        assertThat(ConnectorException.isInvalidCredentials(outer)).isTrue();
        assertThat(ConnectorException.is(outer, ConnectorException.InvalidCredentials.class)).isTrue();
        assertThat(ConnectorException.find(outer, ConnectorException.InvalidCredentials.class))
                .isSameAs(inner);

        // 非凭据类错误不得被误判
        ConnectorException timeout = new ConnectorException("execute request: timed out");
        assertThat(ConnectorException.isInvalidCredentials(timeout)).isFalse();
        assertThat(ConnectorException.is(null, ConnectorException.InvalidCredentials.class)).isFalse();
    }

    /** 对照 Go 的 {@code errors.As(err, &partial)}：连 items/cursor 一起捞出来。 */
    @Test
    void findPartialFetchReturnsTheCarrierWithItsResults() {
        ConnectorException.PartialFetch partial =
                new ConnectorException.PartialFetch(List.of("a: x"));
        ConnectorException wrapped = new ConnectorException("sync: " + partial.getMessage(), partial);

        ConnectorException.PartialFetch found = ConnectorException.findPartialFetch(wrapped);
        assertThat(found).isSameAs(partial);
        assertThat(found.getDetails()).containsExactly("a: x");
        assertThat(ConnectorException.findPartialFetch(new ConnectorException("x"))).isNull();
    }

    // ── ConnectorRegistry ─────────────────────────────────────────────────

    /** 最小的连接器替身：只实现 {@code type()}，其余方法不该被注册表碰到。 */
    private static Connector stubConnector(String type) {
        return new Connector() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void validate(com.ragagent.datasource.domain.DataSourceConfig config) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<com.ragagent.datasource.domain.Resource> listResources(
                    com.ragagent.datasource.domain.DataSourceConfig config, String parentId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<String> resolveResourceAncestors(
                    com.ragagent.datasource.domain.DataSourceConfig config, List<String> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<com.ragagent.datasource.domain.FetchedItem> fetchAll(
                    com.ragagent.datasource.domain.DataSourceConfig config, List<String> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FetchIncrementalResult fetchIncremental(
                    com.ragagent.datasource.domain.DataSourceConfig config,
                    com.ragagent.datasource.domain.SyncCursor cursor) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void registryRejectsNilAndEmptyType() {
        ConnectorRegistry registry = new ConnectorRegistry();
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(ConnectorException.NilConnector.class)
                .hasMessage("connector is nil");
        assertThatThrownBy(() -> registry.register(stubConnector("")))
                .isInstanceOf(ConnectorException.EmptyConnectorType.class)
                .hasMessage("connector type is empty");
    }

    @Test
    void registryGetUnknownTypeThrowsNotFoundWithGoMessage() {
        ConnectorRegistry registry = new ConnectorRegistry();
        assertThatThrownBy(() -> registry.get("confluence"))
                .isInstanceOf(ConnectorException.NotFound.class)
                // Go 的 Get 不拼请求的 type，照抄
                .hasMessage("connector type not found in registry");
    }

    @Test
    void registryRegistersOverwritesAndListsInRegistrationOrder() {
        ConnectorRegistry registry = new ConnectorRegistry();
        Connector first = stubConnector("feishu");
        Connector second = stubConnector("notion");
        registry.register(second);
        registry.register(first);

        assertThat(registry.get("feishu")).isSameAs(first);
        assertThat(registry.get("notion")).isSameAs(second);
        assertThat(registry.list()).containsExactly("notion", "feishu");

        // 重复注册 = 覆盖（Go 的 map 赋值语义），不报错
        Connector replacement = stubConnector("feishu");
        registry.register(replacement);
        assertThat(registry.get("feishu")).isSameAs(replacement);
        assertThat(registry.list()).containsExactly("notion", "feishu");
    }

    // ── ConnectorCatalog ──────────────────────────────────────────────────

    /** Go {@code ConnectorMetadataRegistry} 的 17 个键。 */
    private static final List<String> GO_REGISTRY_KEYS = List.of(
            "feishu", "lark", "feishu_drive", "lark_drive", "notion", "confluence", "yuque",
            "ima", "github", "google_drive", "onedrive", "dingtalk", "web_crawler", "slack",
            "imap", "rss", "gitlab");

    @Test
    void catalogHasAllSeventeenGoEntries() {
        assertThat(ConnectorCatalog.registeredTypes())
                .containsExactlyInAnyOrderElementsOf(GO_REGISTRY_KEYS);
    }

    /**
     * 排序 = Go 的"收集全部 → 按 Priority 稳定排序"。
     *
     * <p>⚠️ Go 的初始顺序来自 map 迭代（随机），所以同优先级之间的顺序<b>每次调用都不同</b>。
     * Java 固定为声明序（见 {@code ConnectorCatalog} 的类注释）。本断言钉住的正是
     * Java 的确定性顺序，而不是 Go 的随机顺序。</p>
     */
    @Test
    void listAvailableConnectorsIsSortedByPriorityStably() {
        List<ConnectorMetadata> all = ConnectorCatalog.listAvailableConnectors();
        assertThat(all).hasSize(17);
        assertThat(all).extracting(ConnectorMetadata::type).containsExactly(
                "feishu", "lark", "feishu_drive", "lark_drive",   // priority 0，声明序
                "notion",                                          // 1
                "confluence",                                      // 2
                "yuque", "ima",                                    // 3，声明序
                "github",                                          // 4
                "google_drive",                                    // 5
                "onedrive",                                        // 6
                "dingtalk",                                        // 7
                "gitlab",                                          // 8
                "web_crawler",                                     // 9
                "slack",                                           // 10
                "imap",                                            // 11
                "rss");                                            // 12
        List<Integer> priorities = all.stream().map(ConnectorMetadata::priority).toList();
        assertThat(priorities).isSorted();
    }

    @Test
    void catalogEntriesMatchGoLiterals() {
        ConnectorMetadata feishu = ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_FEISHU);
        assertThat(feishu.name()).isEqualTo("Feishu (飞书)");
        assertThat(feishu.description()).isEqualTo("Sync documents, wikis, and content from Feishu");
        assertThat(feishu.priority()).isZero();
        assertThat(feishu.authType()).isEqualTo("oauth2");
        assertThat(feishu.capabilities()).containsExactly("incremental", "deletion_sync");
        assertThat(feishu.icon()).isEmpty();

        ConnectorMetadata larkDrive =
                ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_LARK_DRIVE);
        assertThat(larkDrive.name()).isEqualTo("Lark Drive");
        assertThat(larkDrive.description())
                .isEqualTo("Sync documents and files from a Lark Drive folder");

        ConnectorMetadata ima = ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_IMA);
        assertThat(ima.name()).isEqualTo("Tencent IMA (ima.qq.com)");
        assertThat(ima.priority()).isEqualTo(3);
        assertThat(ima.capabilities()).containsExactly("incremental", "deletion_sync");

        ConnectorMetadata dingtalk =
                ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_DINGTALK);
        assertThat(dingtalk.name()).isEqualTo("DingTalk (钉钉)");

        ConnectorMetadata onedrive =
                ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_ONEDRIVE);
        assertThat(onedrive.name()).isEqualTo("OneDrive / SharePoint");

        ConnectorMetadata gitlab = ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_GITLAB);
        assertThat(gitlab.priority()).isEqualTo(8);
        assertThat(gitlab.authType()).isEqualTo("token");
        assertThat(gitlab.capabilities()).containsExactly("incremental", "hierarchical");

        ConnectorMetadata rss = ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_RSS);
        assertThat(rss.priority()).isEqualTo(12);
        assertThat(rss.authType()).isEqualTo("custom");

        // 未注册的类型回 null（对照 Go 的 map 取值）
        assertThat(ConnectorCatalog.metadata("nope")).isNull();
    }

    /**
     * {@code capabilities} 的两种"空"形态是**不同**的线上字节：
     * WebCrawler / IMAP 在 Go 里显式给了 {@code []string{}} → {@code []}；
     * 其余都没有 nil 的情形。Java 侧不能把空列表归一成 null（见
     * {@code ConnectorMetadata} 紧凑构造器的注释）。
     */
    @Test
    void emptyCapabilitiesSerializeAsArrayNotNull() throws Exception {
        ObjectMapper mapper = JsonMappers.lenient();
        ConnectorMetadata webCrawler =
                ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_WEB_CRAWLER);
        assertThat(webCrawler.capabilities()).isNotNull().isEmpty();
        assertThat(mapper.writeValueAsString(webCrawler)).isEqualTo(
                "{\"type\":\"web_crawler\",\"name\":\"Web Crawler (Sitemap)\","
                        + "\"description\":\"Crawl websites via Sitemap.xml\",\"icon\":\"\","
                        + "\"priority\":9,\"authType\":\"none\",\"capabilities\":[]}");

        ConnectorMetadata imap = ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_IMAP);
        assertThat(mapper.writeValueAsString(imap)).isEqualTo(
                "{\"type\":\"imap\",\"name\":\"Email (IMAP)\","
                        + "\"description\":\"Sync email content from IMAP servers\",\"icon\":\"\","
                        + "\"priority\":11,\"authType\":\"password\",\"capabilities\":[]}");
    }

    /**
     * 逐字节：键序＝声明序；§1.6 后 {@code icon} 空串也**恒输出**（不再整键消失）。
     */
    @Test
    void connectorMetadataJsonIsByteExact() throws Exception {
        ObjectMapper mapper = JsonMappers.lenient();
        ConnectorMetadata rss = ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_RSS);
        assertThat(mapper.writeValueAsString(rss)).isEqualTo(
                "{\"type\":\"rss\",\"name\":\"RSS / Atom Feed\","
                        + "\"description\":\"Sync articles from RSS/Atom feeds\",\"icon\":\"\","
                        + "\"priority\":12,\"authType\":\"custom\","
                        + "\"capabilities\":[\"incremental\"]}");

        // icon 有值时照写
        ConnectorMetadata withIcon = new ConnectorMetadata(
                "notion", "Notion", "Sync pages and databases from Notion", "n.svg",
                1, "api_key", List.of("incremental"));
        assertThat(mapper.writeValueAsString(withIcon)).startsWith(
                "{\"type\":\"notion\",\"name\":\"Notion\","
                        + "\"description\":\"Sync pages and databases from Notion\","
                        + "\"icon\":\"n.svg\",\"priority\":1,");
    }

    /**
     * 契约往返体检（§7.5 第 3 条）：{@code ConnectorMetadata} 是
     * {@code GET /api/v1/datasource/types} 的响应体，必须能被按契约读回。
     * 用严格映射器往返，任何"多吐一个键"（漏 {@code @JsonIgnore}）都会被抓到。
     *
     * <p>本模块刻意把这条断言放在自己的测试里，而不是写进共享的
     * {@code JsonContractRoundTripTest}——与上一轮 datasource 领域类型的处置一致
     * （{@code DataSourceJsonTest} 也是模块内自持）。</p>
     */
    @Test
    void connectorMetadataRoundTrips() {
        JsonRoundTrip.assertRoundTrips(
                ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_FEISHU),
                ConnectorMetadata.class, "datasource.ConnectorMetadata ← ConnectorMetadata");
        JsonRoundTrip.assertRoundTrips(
                ConnectorCatalog.metadata(DataSourceConstants.CONNECTOR_TYPE_WEB_CRAWLER),
                ConnectorMetadata.class, "datasource.ConnectorMetadata ← ConnectorMetadata（空 capabilities）");
        JsonRoundTrip.assertRoundTrips(
                new ConnectorMetadata("x", "X", "d", "", 99, "none", List.of()),
                ConnectorMetadata.class, "datasource.ConnectorMetadata ← ConnectorMetadata（最小）");
    }

    // ── 目录与注册表的对应关系（不是 bug，是刻意的） ───────────────────────

    /**
     * 目录里 17 项，但只有 9 个类型真正有连接器实现
     * （{@code container.initConnectorRegistry} 登记的那 9 个实例）。
     * 这条断言把这个"表比实现大"的事实钉住，免得后来人以为目录少写了。
     */
    @Test
    void onlyNineTypesHaveRealConnectorImplementations() {
        List<String> implemented = new ArrayList<>(List.of(
                "feishu", "lark", "feishu_drive", "lark_drive",
                "notion", "yuque", "ima", "rss", "gitlab"));
        assertThat(ConnectorCatalog.registeredTypes()).containsAll(implemented);

        List<String> plannedOnly = new ArrayList<>(GO_REGISTRY_KEYS);
        plannedOnly.removeAll(implemented);
        assertThat(plannedOnly).containsExactlyInAnyOrder(
                "confluence", "github", "google_drive", "onedrive", "dingtalk",
                "web_crawler", "slack", "imap");
        assertThat(plannedOnly).hasSize(8);
        assertThat(implemented).hasSize(9);
        assertThat(ConnectorCatalog.registeredTypes()).hasSize(17);
    }

    /** 便于人工核对的键序表（与 Go 的 map 书写顺序一致，仅作文档）。 */
    static final Map<String, Integer> DECLARATION_ORDER = Map.ofEntries(
            Map.entry("feishu", 0), Map.entry("lark", 1), Map.entry("feishu_drive", 2),
            Map.entry("lark_drive", 3), Map.entry("notion", 4), Map.entry("confluence", 5),
            Map.entry("yuque", 6), Map.entry("ima", 7), Map.entry("github", 8),
            Map.entry("google_drive", 9), Map.entry("onedrive", 10), Map.entry("dingtalk", 11),
            Map.entry("web_crawler", 12), Map.entry("slack", 13), Map.entry("imap", 14),
            Map.entry("rss", 15), Map.entry("gitlab", 16));
}
