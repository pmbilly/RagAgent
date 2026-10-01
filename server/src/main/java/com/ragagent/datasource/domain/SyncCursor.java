package com.ragagent.datasource.domain;

import com.ragagent.common.web.JsonMappers;
import java.time.OffsetDateTime;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 增量同步的位置/状态（对照 Go {@code types.SyncCursor}，
 * internal/types/datasource.go L404-415）。
 *
 * <p>它落 {@code data_sources.last_sync_cursor} 这个 jsonb 列——是
 * {@code DataSource.LastSyncCursor} 的**值形状**，而不是一个独立契约；
 * 连接器私有的字段全塞进 {@code connector_cursor} 这张 map 里，所以结构可以随连接器演进。</p>
 *
 * <h2>Go 实录（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   SyncCursor{} →
 *   {"last_sync_time":"0001-01-01T00:00:00Z","connector_cursor":null,"last_schema_hash":""}
 *   SyncCursor(全字段) →
 *   {"last_sync_time":"2026-09-18T10:00:00+08:00",
 *    "connector_cursor":{"n":2,"page_token":"p"},"last_schema_hash":"h"}
 * </pre>
 * <p>{@code connectorCursor} nil 时输出 {@code null}（§1.6 恒输出）；
 * 且它的键序按 Go 的 map 规则排序（{@code n} 在 {@code page_token} 前）。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子/软删除/自动时间戳/唯一索引/关联预加载</b>：全无——本类型不落表，
 *       只作为 {@code last_sync_cursor} 列的 JSON 载荷。</li>
 *   <li><b>默认排序</b>：无。</li>
 * </ol>
 */
public class SyncCursor {

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 上次同步的时间（值类型零值也输出字面量）。 */
    private OffsetDateTime lastSyncTime = GoTimeSerializer.GO_ZERO_DATE_TIME;

    /** 连接器私有游标（分页 token、偏移量 …）。§1.6：nil 时输出 {@code null}。 */
    @JsonSerialize(using = DataSourceMapSerializer.class)
    private Map<String, Object> connectorCursor;

    /** 上次全量同步的哈希，用来发现 schema 变化。 */
    private String lastSchemaHash = "";

    public OffsetDateTime getLastSyncTime() { return lastSyncTime; }
    public void setLastSyncTime(OffsetDateTime v) {
        lastSyncTime = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public Map<String, Object> getConnectorCursor() { return connectorCursor; }
    public void setConnectorCursor(Map<String, Object> v) { connectorCursor = v; }

    public String getLastSchemaHash() { return lastSchemaHash; }
    public void setLastSchemaHash(String v) { lastSchemaHash = v == null ? "" : v; }

    /**
     * 对照 Go {@code SyncCursor.ToJSON}。
     *
     * @return 写进 jsonb 列的 JSON；接收者为 null 时回 {@code null}
     */
    public JsonNode toJSON() {
        return MAPPER.valueToTree(this);
    }

    /**
     * 对照 Go 的 {@code json.Unmarshal(d.LastSyncCursor, &cursor)}。
     *
     * <p>两态与 Go 一致：列是 SQL NULL（Java 侧 {@code node == null}）→ Go 的
     * {@code len(...) == 0} 短路，回 {@code null}；列里是字面量 {@code null}
     * （{@code NullNode}）→ {@code json.Unmarshal("null", &cursor)} **成功**且留下零值，
     * 所以回一个零值对象而不是 {@code null}。后者实践中不可达，但照抄不亏。</p>
     */
    public static SyncCursor fromJson(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNull()) {
            return new SyncCursor();
        }
        return MAPPER.convertValue(node, SyncCursor.class);
    }
}
