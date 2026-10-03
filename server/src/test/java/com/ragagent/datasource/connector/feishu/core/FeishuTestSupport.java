package com.ragagent.datasource.connector.feishu.core;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.StreamHandler;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SyncCursor;
import com.ragagent.datasource.domain.DataSourceConstants;

/**
 * 飞书测试的公共夹具：SSRF 白名单放行 loopback、配置构造、记录型 StreamHandler、
 * 标准鉴权桩路由。
 *
 * <h2>SSRF 白名单是进程级静态状态（必须还原）</h2>
 * <p>env 里放的值是 {@code SSRF_WHITELIST=127.0.0.1,::1,localhost}。Java 进程内改不了 env，
 * 按 {@code McpSseTransportTest} 的既有惯例：{@code @BeforeAll} 调
 * {@link #allowLoopback()}，{@code @AfterAll} 调 {@link #restoreSsrf()}。</p>
 *
 * <p>{@code SsrfGuard} 的白名单字段是 {@code static volatile}（见其类注释），
 * 所以 {@link #restoreSsrf()} 必须**同时**把白名单内容还原成 env 推导的那份，
 * 否则同一 JVM 里后跑的测试类会继承「放行 loopback」这份状态
 * （{@code notion/NotionTestSupport} 也是这么做的）。</p>
 */
public final class FeishuTestSupport {

    /**
     * 放行本机回环与飞书官方 origin 的白名单
     * （{@code SSRF_WHITELIST=127.0.0.1,localhost,open.feishu.cn,open.larksuite.com}）。
     *
     * <p>飞书那两个域名必须放行：配置解析会拿解析出来的 base_url
     * （region 默认就是 {@code https://open.feishu.cn}）过一遍 SSRF 策略，
     * 而**测试不会真的连它们**——只是不让白名单校验把配置解析挡掉。</p>
     */
    public static final String LOOPBACK_WHITELIST =
            "127.0.0.1,::1,localhost,open.feishu.cn,open.larksuite.com";

    private FeishuTestSupport() {
    }

    /** 让 {@link ConnectorHttp} 放行 127.0.0.1 的桩服务器。 */
    public static void allowLoopback() {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist(LOOPBACK_WHITELIST);
        ConnectorHttp.setSsrfGuard(guard);
    }

    /**
     * 还原：既还原连接器持有的 guard 引用，也把**进程级静态白名单**放回 env 推导出来的那份。
     *
     * <p>只 {@code setSsrfGuard(new SsrfGuard())} 是不够的——{@code SsrfGuard} 的白名单字段是
     * {@code static volatile}（见其类注释），改过就会一直留着，影响同一 JVM 里后续
     * 测试类的 SSRF 断言。这与 {@code notion/NotionTestSupport} 的处置一致。</p>
     */
    public static void restoreSsrf() {
        ConnectorHttp.setSsrfGuard(new SsrfGuard());
        ConnectorHttp.ssrfGuard().reloadWhitelist(envWhitelistRaw());
    }

    /**
     * 把两个 env 合并成一份原始白名单（与 {@code SsrfGuard} 的合并语义一致：
     * 主表空则取附加表，附加表空则取主表，都有则逗号拼接）。
     * （{@code SsrfGuard.mergeRaws} 是包级可见、本包调不到，故就地实现这几行。）
     */
    private static String envWhitelistRaw() {
        String primary = System.getenv("SSRF_WHITELIST");
        String extra = System.getenv("SSRF_WHITELIST_EXTRA");
        primary = primary == null ? "" : primary.trim();
        extra = extra == null ? "" : extra.trim();
        if (primary.isEmpty()) {
            return extra;
        }
        if (extra.isEmpty()) {
            return primary;
        }
        return primary + "," + extra;
    }

    /**
     * 造一份指向本机桩服务器的数据源配置。
     *
     * @param connectorType 连接器类型（{@code feishu} / {@code feishu_drive} / {@code lark_drive}）
     */
    public static DataSourceConfig config(String connectorType, String baseUrl,
                                          List<String> resourceIds) {
        return config(connectorType, baseUrl, resourceIds, false);
    }

    /** 带多模态开关的版本。 */
    public static DataSourceConfig config(String connectorType, String baseUrl,
                                          List<String> resourceIds, boolean multimodal) {
        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("app_id", "test-app-id");
        creds.put("app_secret", "test-app-secret");
        creds.put("base_url", baseUrl);

        DataSourceConfig c = new DataSourceConfig();
        c.setType(connectorType);
        c.setCredentials(creds);
        c.setResourceIds(resourceIds == null ? new ArrayList<>() : new ArrayList<>(resourceIds));
        c.setMultimodalEnabled(multimodal);
        return c;
    }

    /** wiki 连接器的配置（{@code feishu}）。 */
    public static DataSourceConfig wikiConfig(String baseUrl, List<String> resourceIds) {
        return config(DataSourceConstants.CONNECTOR_TYPE_FEISHU, baseUrl, resourceIds);
    }

    /** 云盘连接器的配置。 */
    public static DataSourceConfig driveConfig(String baseUrl, List<String> resourceIds,
                                               boolean multimodal) {
        return config(DataSourceConstants.CONNECTOR_TYPE_FEISHU_DRIVE, baseUrl, resourceIds, multimodal);
    }

    /** 往桩上注册标准鉴权路由（每个用例都要有）。 */
    public static void tokenRoute(FeishuTestServer server) {
        server.handle("/open-apis/auth/v3/tenant_access_token/internal",
                (exchange, body) -> FeishuTestServer.sendJson(exchange,
                        "{\"code\":0,\"tenant_access_token\":\"fake-token\",\"expire\":7200}"));
    }

    /**
     * 记录型 StreamHandler：记录 Emit 的条目与 Checkpoint 的
     * 游标快照（快照在调用时取）。
     */
    public static final class RecordingHandler implements StreamHandler {

        /** 收到的条目（顺序即 Emit 顺序）。 */
        public final List<FetchedItem> emitted = new ArrayList<>();

        /** 每次 Checkpoint 时的 {@code space_node_times} 快照（wiki 用例）。 */
        public final List<Map<String, Map<String, String>>> spaceNodeTimes = new ArrayList<>();

        /** 每次 Checkpoint 时的 {@code file_times} 快照（drive 用例）。 */
        public final List<Map<String, Map<String, String>>> fileTimes = new ArrayList<>();

        /**
         * 每次 Checkpoint 时的**整张** connectorCursor 快照（含 last_sync_time），
         * 用于断言"游标线格式"本身。
         */
        public final List<Map<String, Object>> cursorSnapshots = new ArrayList<>();

        /** 非空时，Emit 会把它的返回值当异常抛出（用例里注入失败用）。 */
        public RuntimeException emitFailure;

        /** 非空时，Checkpoint 抛这个异常（连接器应当只记日志、继续）。 */
        public RuntimeException checkpointFailure;

        @Override
        public void emit(FetchedItem item) {
            if (emitFailure != null) {
                throw emitFailure;
            }
            emitted.add(item);
        }

        @Override
        public void checkpoint(SyncCursor cursor) {
            if (cursor != null && cursor.getConnectorCursor() != null) {
                cursorSnapshots.add(deepCopy(cursor.getConnectorCursor()));
                spaceNodeTimes.add(FeishuCursorCodec.decodeSpaceNodeTimes(cursor.getConnectorCursor()));
                fileTimes.add(FeishuCursorCodec.decodeFileTimes(cursor.getConnectorCursor()));
            }
            if (checkpointFailure != null) {
                throw checkpointFailure;
            }
        }

        /** 条目 ExternalID 列表。 */
        public List<String> emittedIds() {
            List<String> ids = new ArrayList<>();
            for (FetchedItem item : emitted) {
                ids.add(item.getExternalId());
            }
            return ids;
        }

        /** 把最后一次 Checkpoint 快照还原成一个可继续用的 {@link SyncCursor}。 */
        public SyncCursor lastCheckpointCursor() {
            if (cursorSnapshots.isEmpty()) {
                return null;
            }
            SyncCursor c = new SyncCursor();
            c.setConnectorCursor(cursorSnapshots.get(cursorSnapshots.size() - 1));
            c.setLastSyncTime(OffsetDateTime.now());
            return c;
        }

        /** 造一个"客户端已知状态"的游标，用来测续跑/增量（wiki）。 */
        public static SyncCursor wikiCursor(Map<String, Map<String, String>> spaceNodeTimes) {
            return FeishuCursorCodec.encodeSpaceNodeTimes(spaceNodeTimes, OffsetDateTime.now());
        }

        /** 造一个"客户端已知状态"的游标（drive）。 */
        public static SyncCursor driveCursor(Map<String, Map<String, String>> fileTimes) {
            return FeishuCursorCodec.encodeFileTimes(fileTimes, OffsetDateTime.now());
        }
    }

    /** 深拷贝一张 JSON 形态的 map（模拟"落库再读回"的快照隔离）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                out.put(e.getKey(), deepCopy((Map<String, Object>) m));
            } else if (v instanceof List<?> l) {
                out.put(e.getKey(), new ArrayList<>(l));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}
