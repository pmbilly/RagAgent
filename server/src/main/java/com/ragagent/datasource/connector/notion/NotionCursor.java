package com.ragagent.datasource.connector.notion;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 增量同步的私有游标。
 *
 * <p>它的 JSON 形态就是 {@code data_sources.last_sync_cursor} 里
 * {@code connector_cursor} 那一层：{@code {"page_edit_times":{"<page_id>":"<RFC3339>"}}}。</p>
 *
 * <p><b>它是落 jsonb 的值形状</b>，但与 {@code SyncCursor} 不同——游标 map 由
 * 连接器自己构造（{@code buildCursor}），不经 Jackson 序列化，
 * 时间用 {@link NotionValues#rfc3339Nano} 手写。
 * 这样 cursor 里的时间字面量与既有数据**逐字节一致**（保留 Notion 给的 UTC 偏移）。</p>
 *
 * <p>只有 {@code pageEditTimes} 参与差分；{@code lastSyncTime} 住在
 * {@code types.SyncCursor} 上。</p>
 */
public final class NotionCursor {

    @JsonProperty("page_edit_times")
    public Map<String, OffsetDateTime> pageEditTimes;

    public Map<String, OffsetDateTime> pageEditTimes() {
        return pageEditTimes == null ? new LinkedHashMap<>() : pageEditTimes;
    }
}
