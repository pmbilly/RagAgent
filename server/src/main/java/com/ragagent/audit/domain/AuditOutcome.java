package com.ragagent.audit.domain;

/**
 * 审计结果常量（逐值对照 Go internal/types/audit_log.go 的
 * {@code AuditOutcome} 与常量块 L168-178）。
 *
 * <p>同 {@link AuditAction}：Go 里是 {@code type AuditOutcome string}，
 * 列 varchar(16)，用 String 常量而非 enum。</p>
 */
public final class AuditOutcome {

    private AuditOutcome() {}

    /** 终态成功。 */
    public static final String SUCCESS = "success";
    /**
     * 已受理：durable 请求已创建、异步工作已提交，但后台操作尚未到终态。
     * UI 靠它避免把排队中的操作显示成已完成。
     */
    public static final String ACCEPTED = "accepted";
    /** 中间件级拒绝。 */
    public static final String DENIED = "denied";
    public static final String FAILED = "failed";
    public static final String PARTIAL = "partial";
    public static final String CANCELED = "canceled";

    /**
     * 对照 Go {@code AuditLog.Outcome} 的 gorm 默认值 {@code default:success}，
     * 以及 service.Log 的 {@code if entry.Outcome == "" { entry.Outcome = success }}。
     */
    public static final String DEFAULT = SUCCESS;
}
