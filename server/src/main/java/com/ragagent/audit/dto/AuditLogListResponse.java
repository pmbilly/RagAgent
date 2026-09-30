package com.ragagent.audit.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.audit.domain.AuditLog;

/**
 * 审计列表的响应信封（对照 Go internal/handler/audit_log.go 的
 * {@code auditLogListResponse} L31-35）。
 *
 * <p>Go 是 struct → 键按<b>声明序</b>输出：{@code success, data, next_cursor}。
 * Java 用 record，组件顺序即声明序，键序天然一致。</p>
 *
 * <p>{@code next_cursor} 是最后一行的 id（行按 id DESC 排序），空页时为 0——
 * 前端据此停止翻页（"没有更老的了"）。</p>
 *
 * <p><b>已知差异（本项目统一处置）</b>：Go 的 {@code Data} 是
 * {@code []*types.AuditLog}，nil slice 序列化成 {@code null} 而非 {@code []}，
 * 所以 Go 空页输出 {@code {"success":true,"data":null,"next_cursor":0}}；
 * Java 侧统一归一为 {@code []}（前端 {@code resp.data || []} 已有守卫）。
 * 这与约定 §9「空列表 vs null」条目的处置一致（Wiki/KB 的列表端点同）。</p>
 *
 * @param success    恒 true（错误走 AppError 信封，不在这个类型里）
 * @param data       本页审计行（最新在前）；空页为 {@code []}
 * @param nextCursor 下一页游标；0 = 没有更老的行
 */
public record AuditLogListResponse(
        boolean success,
        List<AuditLog> data,
        @JsonProperty("next_cursor") long nextCursor) {

    /** 对照三个 handler 的组装：{@code nextCursor = entries[last].ID}（空页 0）。 */
    public static AuditLogListResponse of(List<AuditLog> entries) {
        long nextCursor = 0L;
        if (entries != null && !entries.isEmpty()) {
            Long id = entries.get(entries.size() - 1).getId();
            nextCursor = id == null ? 0L : id;
        }
        return new AuditLogListResponse(true, entries == null ? List.of() : entries, nextCursor);
    }
}
