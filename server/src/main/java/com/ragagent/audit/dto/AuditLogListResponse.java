package com.ragagent.audit.dto;

import java.util.List;

import com.ragagent.audit.domain.AuditLog;

/**
 * 审计列表的响应（游标分页形态：{@code {"items":[...],"nextCursor":N}}）。
 *
 * <p>{@code nextCursor} 是最后一行的 id（行按 id DESC 排序），空页时为 0——
 * 前端据此停止翻页（"没有更老的了"）。空页 {@code items} 是 {@code []} 不是
 * {@code null}（空列表 vs null 的统一处置）。</p>
 */
public record AuditLogListResponse(
        List<AuditLog> items,
        long nextCursor) {

    /** 组装：{@code nextCursor = entries[last].id}（空页 0；items 为 null 时归一为 []）。 */
    public static AuditLogListResponse of(List<AuditLog> entries) {
        long nextCursor = 0L;
        if (entries != null && !entries.isEmpty()) {
            Long id = entries.get(entries.size() - 1).getId();
            nextCursor = id == null ? 0L : id;
        }
        return new AuditLogListResponse(entries == null ? List.of() : entries, nextCursor);
    }
}
