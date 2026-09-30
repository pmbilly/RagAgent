package com.ragagent.datasource.domain;

import com.ragagent.common.web.JsonMappers;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 一次同步的成果汇总（对照 Go {@code types.SyncResult}，
 * internal/types/datasource.go L417-446）。
 *
 * <p>它落 {@code data_sources.last_sync_result} 与 {@code sync_logs.result}
 * 两个 jsonb 列。</p>
 *
 * <h2>Go 实录（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   SyncResult{} → {"total":0,"created":0,"updated":0,"deleted":0,"skipped":0,"failed":0}
 *   SyncResult(全字段) →
 *   {"total":1,"created":2,"updated":3,"deleted":4,"skipped":5,"failed":6,
 *    "deletion_failed":7,
 *    "errors":[{"title":"t","code":"c","params":{"code":"1663"},"message":"m"}],
 *    "next_cursor":{"last_sync_time":"0001-01-01T00:00:00Z","connector_cursor":null,
 *                   "last_schema_hash":"h"}}
 * </pre>
 * <p>三个 omitempty 的处置**各不相同**，别一刀切：</p>
 * <ul>
 *   <li>{@code deletion_failed} 是 int → 0 省略（{@code NON_DEFAULT}）；</li>
 *   <li>{@code errors} 是切片 → nil **与空切片都省略**（{@code NON_EMPTY}）；</li>
 *   <li>{@code next_cursor} 是指针 → nil 省略（{@code NON_NULL}）。</li>
 * </ul>
 * <p>前六个计数器**没有** omitempty，所以零值也恒输出。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子/软删除/自动时间戳/唯一索引/关联预加载/默认排序</b>：全无——
 *       本类型不落表，只作为两列 jsonb 的载荷。</li>
 * </ol>
 */
@JsonPropertyOrder({"total", "created", "updated", "deleted", "skipped", "failed",
        "deletion_failed", "errors", "next_cursor"})
public class SyncResult {

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 处理过的条目总数。 */
    @JsonProperty("total")
    private int total;

    @JsonProperty("created")
    private int created;

    @JsonProperty("updated")
    private int updated;

    @JsonProperty("deleted")
    private int deleted;

    /** 无变化的条目。 */
    @JsonProperty("skipped")
    private int skipped;

    @JsonProperty("failed")
    private int failed;

    /**
     * 删除失败（{@code failed} 的子集）。因为已经越过连接器游标，
     * 通常只有下一次全量同步才会重试它们。omitempty → 0 省略。
     */
    @JsonProperty("deletion_failed")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int deletionFailed;

    /** 逐条失败样本（有上限），显示在同步日志 UI 里。omitempty。 */
    @JsonProperty("errors")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<SyncItemError> errors;

    /** 供下次增量同步用的新游标。omitempty。 */
    @JsonProperty("next_cursor")
    @JsonInclude(JsonInclude.Include.NON_NULL)
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
     * 对照 Go {@code SyncResult.ToJSON}。
     *
     * @return 写进 jsonb 列的 JSON；接收者为 null 时回 {@code null}
     */
    public JsonNode toJSON() {
        return MAPPER.valueToTree(this);
    }

    /**
     * 对照 Go 的 {@code json.Unmarshal(d.LastSyncResult, &result)}。
     *
     * <p>两态与 Go 一致：SQL NULL（{@code node == null}）→ {@code len == 0} 短路回
     * {@code null}；字面量 {@code null}（{@code NullNode}）→ 零值对象。</p>
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
