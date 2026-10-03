package com.ragagent.datasource.domain;

import com.ragagent.common.web.JsonMappers;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 一次同步的成果汇总。
 *
 * <p>它落 {@code data_sources.last_sync_result} 与 {@code sync_logs.result}
 * 两个 jsonb 列。</p>
 *
 * <h2>JSON 形状（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   SyncResult{} → {"total":0,"created":0,"updated":0,"deleted":0,"skipped":0,"failed":0}
 *   SyncResult(全字段) →
 *   {"total":1,"created":2,"updated":3,"deleted":4,"skipped":5,"failed":6,
 *    "deletion_failed":7,
 *    "errors":[{"title":"t","code":"c","params":{"code":"1663"},"message":"m"}],
 *    "next_cursor":{"last_sync_time":"0001-01-01T00:00:00Z","connector_cursor":null,
 *                   "last_schema_hash":"h"}}
 * </pre>
 * <p>九个键**全部恒输出**（键名＝字段名）——{@code deletionFailed} 零值写 0、
 * {@code errors} 写 {@code null}（null 与空列表同形）、{@code nextCursor} 写 {@code null}。
 * 统一口径是"显式 null / 零值照写"。</p>
 *
 * <h2>持久化语义</h2>
 * <ol>
 *   <li><b>钩子/软删除/自动时间戳/唯一索引/关联预加载/默认排序</b>：全无——
 *       本类型不落表，只作为两列 jsonb 的载荷。</li>
 * </ol>
 */
public class SyncResult {

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 处理过的条目总数。 */
    private int total;

    private int created;

    private int updated;

    private int deleted;

    /** 无变化的条目。 */
    private int skipped;

    private int failed;

    /**
     * 删除失败（{@code failed} 的子集）。因为已经越过连接器游标，
     * 通常只有下一次全量同步才会重试它们。0 也恒输出。
     */
    private int deletionFailed;

    /** 逐条失败样本（有上限），显示在同步日志 UI 里。{@code null} 写 {@code null}。 */
    private List<SyncItemError> errors;

    /** 供下次增量同步用的新游标。{@code null} 写 {@code null}。 */
    private SyncCursor nextCursor;

    public int getTotal() { return total; }
    public void setTotal(int v) { total = v; }

    public int getCreated() { return created; }
    public void setCreated(int v) { created = v; }

    public int getUpdated() { return updated; }
    public void setUpdated(int v) { updated = v; }

    public int getDeleted() { return deleted; }
    public void setDeleted(int v) { deleted = v; }

    public int getSkipped() { return skipped; }
    public void setSkipped(int v) { skipped = v; }

    public int getFailed() { return failed; }
    public void setFailed(int v) { failed = v; }

    public int getDeletionFailed() { return deletionFailed; }
    public void setDeletionFailed(int v) { deletionFailed = v; }

    public List<SyncItemError> getErrors() { return errors; }
    public void setErrors(List<SyncItemError> v) { errors = v; }

    public SyncCursor getNextCursor() { return nextCursor; }
    public void setNextCursor(SyncCursor v) { nextCursor = v; }

    /**
     * 序列化成写进 jsonb 列的 JSON。
     *
     * @return 写进 jsonb 列的 JSON；接收者为 null 时回 {@code null}
     */
    public JsonNode toJSON() {
        return MAPPER.valueToTree(this);
    }

    /**
     * 从 jsonb 列反序列化。
     *
     * <p>两态：SQL NULL（{@code node == null}）→ 回 {@code null}；
     * 字面量 {@code null}（{@code NullNode}）→ 零值对象。</p>
     */
    public static SyncResult fromJson(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNull()) {
            return new SyncResult();
        }
        return MAPPER.convertValue(node, SyncResult.class);
    }
}
