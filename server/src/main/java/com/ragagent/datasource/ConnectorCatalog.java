package com.ragagent.datasource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.datasource.domain.DataSourceConstants;

/**
 * 可用连接器的元数据目录（对照 Go {@code datasource.ConnectorMetadataRegistry}
 * 与 {@code datasource.ListAvailableConnectors}，internal/datasource/connector.go L149-310）。
 *
 * <h2>它是一条真实的响应路径</h2>
 * <p>{@code GET /api/v1/datasource/types} 直接返回
 * {@link #listAvailableConnectors()} 的结果数组。下一步的 handler 翻译会直接用它。</p>
 *
 * <h2>表里 17 项、注册表里只有 8 个连接器</h2>
 * <p>这不是 bug，照抄 Go：目录里绝大多数条目是<b>规划中/尚未实现</b>的
 * （confluence / github / google_drive / onedrive / dingtalk / web_crawler / slack / imap），
 * 前端据此展示"即将支持"的选项。真正能在
 * {@link ConnectorRegistry} 里 {@code get} 得到实现的只有
 * feishu / lark / feishu_drive / lark_drive / notion / yuque / ima / rss / gitlab
 * ——也就是本模块翻译的那些（{@code container.initConnectorRegistry} 登记的那 9 个连接器实例）。</p>
 *
 * <h2>⚠️ 与 Go 的已知差异：排序是确定性的</h2>
 * <p>Go 的 {@code ListAvailableConnectors} 先把 map <b>随机顺序</b>遍历进一个 slice，
 * 再对它做<b>稳定</b>插入排序（按 Priority 升序）。于是<b>同优先级的条目之间顺序每次调用都不同</b>
 * ——四个 priority=0 的飞书系（feishu/lark/feishu_drive/lark_drive）与
 * priority=3 的（yuque/ima）就是这样。</p>
 * <p>这意味着 Go 的这条响应<b>本来就不可能逐字节复现</b>（golden 契约测试必须先对数组排序
 * 或按 type 建索引再比）。Java 侧用 {@link LinkedHashMap} 固定为<b>声明序</b>，
 * 信息量完全相同、且可复现——差异方向是"Java 更稳定"，不是漏字段。
 * 下一步写契约测试时要按 type 建索引比对，别按数组下标比。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子 / 软删除 / 自动时间戳 / 唯一索引 / 关联预加载 / 默认排序</b>：全无——
 *       本类型不落表，是纯静态目录。</li>
 * </ol>
 */
public final class ConnectorCatalog {

    private ConnectorCatalog() {
    }

    /** 对照 Go 的 {@code ConnectorMetadataRegistry}（{@code map[string]ConnectorMetadata}）。 */
    private static final Map<String, ConnectorMetadata> REGISTRY = buildRegistry();

    private static Map<String, ConnectorMetadata> buildRegistry() {
        Map<String, ConnectorMetadata> m = new LinkedHashMap<>();
        // 顺序 = Go 源码里 map 字面量的书写顺序。Go 侧这个顺序其实会被 map 打乱，
        // 这里保留书写顺序只是为了"读源码时能一一对上"，不影响任何语义。
        m.put(DataSourceConstants.CONNECTOR_TYPE_FEISHU, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_FEISHU,
                "Feishu (飞书)",
                "Sync documents, wikis, and content from Feishu",
                0, "oauth2", List.of("incremental", "deletion_sync")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_LARK, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_LARK,
                "Lark",
                "Sync documents, wikis, and content from Lark (Feishu international)",
                0, "oauth2", List.of("incremental", "deletion_sync")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_FEISHU_DRIVE, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_FEISHU_DRIVE,
                "Feishu Drive (飞书云盘)",
                "Sync documents and files from a Feishu Drive folder",
                0, "oauth2", List.of("incremental", "deletion_sync")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_LARK_DRIVE, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_LARK_DRIVE,
                "Lark Drive",
                "Sync documents and files from a Lark Drive folder",
                0, "oauth2", List.of("incremental", "deletion_sync")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_NOTION, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_NOTION,
                "Notion",
                "Sync pages and databases from Notion",
                1, "api_key", List.of("incremental")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_CONFLUENCE, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_CONFLUENCE,
                "Confluence",
                "Sync spaces and pages from Atlassian Confluence",
                2, "api_key", List.of("incremental")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_YUQUE, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_YUQUE,
                "Yuque (语雀)",
                "Sync knowledge bases and documents from Yuque",
                3, "api_key", List.of("incremental")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_IMA, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_IMA,
                "Tencent IMA (ima.qq.com)",
                "Sync knowledge bases and documents from Tencent IMA",
                3, "api_key", List.of("incremental", "deletion_sync")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_GITHUB, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_GITHUB,
                "GitHub",
                "Sync repositories, wikis, and issues from GitHub",
                4, "oauth2", List.of("incremental")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_GOOGLE_DRIVE, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_GOOGLE_DRIVE,
                "Google Drive",
                "Sync documents and files from Google Drive",
                5, "oauth2", List.of("incremental")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_ONEDRIVE, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_ONEDRIVE,
                "OneDrive / SharePoint",
                "Sync documents and files from Microsoft OneDrive",
                6, "oauth2", List.of("incremental")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_DINGTALK, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_DINGTALK,
                "DingTalk (钉钉)",
                "Sync documents and content from DingTalk",
                7, "api_key", List.of("incremental")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_WEB_CRAWLER, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_WEB_CRAWLER,
                "Web Crawler (Sitemap)",
                "Crawl websites via Sitemap.xml",
                9, "none", List.of()));
        m.put(DataSourceConstants.CONNECTOR_TYPE_SLACK, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_SLACK,
                "Slack",
                "Sync channel messages and files from Slack",
                10, "oauth2", List.of("incremental")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_IMAP, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_IMAP,
                "Email (IMAP)",
                "Sync email content from IMAP servers",
                11, "password", List.of()));
        m.put(DataSourceConstants.CONNECTOR_TYPE_RSS, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_RSS,
                "RSS / Atom Feed",
                "Sync articles from RSS/Atom feeds",
                12, "custom", List.of("incremental")));
        m.put(DataSourceConstants.CONNECTOR_TYPE_GITLAB, new ConnectorMetadata(
                DataSourceConstants.CONNECTOR_TYPE_GITLAB,
                "GitLab",
                "Sync files from GitLab projects",
                8, "token", List.of("incremental", "hierarchical")));
        // 刻意不用 Map.copyOf：它返回的不可变 map **不保证迭代序**，
        // 会把下面 listAvailableConnectors 里"声明序 + 稳定排序"的确定性毁掉。
        return java.util.Collections.unmodifiableMap(m);
    }

    /** 全部已登记的连接器类型（对照 Go 的 {@code ConnectorMetadataRegistry} 的键集）。 */
    public static Set<String> registeredTypes() {
        return REGISTRY.keySet();
    }

    /** 按类型取元数据；未登记时返回 {@code null}（对照 Go 的 map 取值）。 */
    public static ConnectorMetadata metadata(String connectorType) {
        return REGISTRY.get(connectorType);
    }

    /**
     * 返回全部可用连接器的元数据，按 {@code Priority} 升序（形参小者在前）。
     *
     * <p>对照 Go 的 {@code ListAvailableConnectors}：同样的"收集全部 → 按优先级稳定排序"。
     * 差别只在于输入的初始顺序确定（见类注释）。</p>
     */
    public static List<ConnectorMetadata> listAvailableConnectors() {
        List<ConnectorMetadata> metadata = new ArrayList<>(REGISTRY.values());
        // 稳定排序：同优先级的保持插入序（Java 的 List.sort 是 TimSort，保证稳定）
        metadata.sort(Comparator.comparingInt(ConnectorMetadata::priority));
        return metadata;
    }
}
