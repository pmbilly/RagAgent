package com.ragagent.audit.domain;

/**
 * 审计动作常量（逐值对照 Go internal/types/audit_log.go 的 {@code AuditAction}
 * 与其常量块 L15-166）。
 *
 * <p>Go 里 {@code AuditAction} 是 {@code type AuditAction string}——**不是枚举**，
 * 列里是 varchar(64)。因此 Java 侧刻意用 String 常量而不是 enum：
 * audit_logs.action 列允许任意值（前向兼容 {@code agent.*} / {@code kb.*} 新命名空间），
 * 查询过滤器也必须能接受库里已有的任意字符串，enum 会在未知值上炸。</p>
 *
 * <p>命名空间点分（{@code <area>.<event>}）。下面按 Go 注释里的分区排列，
 * 值必须逐字一致——前端 {@code src/api/tenant/audit-log.ts} 与
 * {@code src/i18n/auditActionRegistry.ts} 按这些字符串做展示映射。</p>
 */
public final class AuditAction {

    private AuditAction() {}

    // ── RBAC（PR 6 第一批） ──────────────────────────────────────────────

    /** 添加空间成员：actor 是邀请人，target 是被邀用户。 */
    public static final String MEMBER_ADDED = "rbac.member_added";
    public static final String MEMBER_REMOVED = "rbac.member_removed";
    /** Details 带 old_role / new_role。 */
    public static final String MEMBER_ROLE_CHANGED = "rbac.member_role_changed";
    /** POST /tenants/:id/leave：actor 与 target 同一人。 */
    public static final String MEMBER_LEFT = "rbac.member_left";
    /**
     * RBAC 中间件拒绝（Go middleware/rbac.go 的 RequireRole /
     * RequireOwnershipOrRole 在 EnableRBAC=true 下拒绝请求时写）。
     * 受 1 分钟滑动窗口去重保护——见 {@link com.ragagent.audit.service.AuditLogService#logDenied}。
     */
    public static final String ACCESS_DENIED = "rbac.access_denied";
    public static final String INVITATION_SENT = "rbac.invitation_sent";
    public static final String INVITATION_ACCEPTED = "rbac.invitation_accepted";
    public static final String INVITATION_DECLINED = "rbac.invitation_declined";
    public static final String INVITATION_REVOKED = "rbac.invitation_revoked";
    /** 惰性清扫把超期 pending 置为 expired；actor 为空（system）。 */
    public static final String INVITATION_EXPIRED = "rbac.invitation_expired";

    // ── VectorStore 生命周期（VectorStoreService 发出） ──────────────────

    public static final String VECTOR_STORE_CREATED = "vector_store.created";
    public static final String VECTOR_STORE_UPDATED = "vector_store.updated";
    public static final String VECTOR_STORE_DELETED = "vector_store.deleted";

    // ── OpenSearch 派生资源（集群侧副作用） ─────────────────────────────

    public static final String OPENSEARCH_INDEX_CREATED = "opensearch.index_created";
    public static final String OPENSEARCH_INDEX_DELETED = "opensearch.index_deleted";
    public static final String OPENSEARCH_REINDEX_EXECUTED = "opensearch.reindex_executed";

    // ── 系统级（tenant_id=0） ─────────────────────────────────────────────

    public static final String SYSTEM_SETTING_CHANGED = "system.setting_changed";
    public static final String SYSTEM_ADMIN_PROMOTED = "system.admin_promoted";
    public static final String SYSTEM_ADMIN_REVOKED = "system.admin_revoked";
    public static final String SYSTEM_USER_PASSWORD_RESET = "system.user_password_reset";
    public static final String SYSTEM_USER_CREATED = "system.user_created";
    public static final String SYSTEM_API_KEY_CREATED = "system.api_key_created";
    public static final String SYSTEM_API_KEY_REVOKED = "system.api_key_revoked";
    public static final String SYSTEM_QUEUE_TASK_RETRIED = "system.queue_task_retried";
    public static final String SYSTEM_QUEUE_TASK_DELETED = "system.queue_task_deleted";
    public static final String SYSTEM_QUEUE_TASK_RUN_NOW = "system.queue_task_run_now";
    public static final String SYSTEM_QUEUE_TASK_CANCELLED = "system.queue_task_cancelled";
    public static final String SYSTEM_QUEUE_ARCHIVED_PURGED = "system.queue_archived_purged";

    // ── 知识库活动（scope_type=knowledge_base, scope_id=<kb id>） ────────

    public static final String KB_CREATED = "kb.created";
    public static final String KB_UPDATED = "kb.updated";
    public static final String KB_DELETED = "kb.deleted";
    public static final String KB_DUPLICATED = "kb.duplicated";
    public static final String KB_CLONE_STARTED = "kb.clone_started";
    public static final String KB_CLONE_COMPLETED = "kb.clone_completed";
    public static final String KB_CLONE_FAILED = "kb.clone_failed";

    public static final String KNOWLEDGE_CREATED = "knowledge.created";
    public static final String KNOWLEDGE_UPDATED = "knowledge.updated";
    public static final String KNOWLEDGE_DELETED = "knowledge.deleted";
    public static final String KNOWLEDGE_BATCH_DELETED = "knowledge.batch_deleted";
    public static final String KNOWLEDGE_REPARSE_STARTED = "knowledge.reparse_started";
    public static final String KNOWLEDGE_PARSE_CANCELED = "knowledge.parse_canceled";
    public static final String KNOWLEDGE_MOVE_STARTED = "knowledge.move_started";
    public static final String KNOWLEDGE_MOVE_COMPLETED = "knowledge.move_completed";
    public static final String KNOWLEDGE_MOVE_FAILED = "knowledge.move_failed";

    public static final String TAG_CREATED = "tag.created";
    public static final String TAG_UPDATED = "tag.updated";
    public static final String TAG_DELETED = "tag.deleted";

    public static final String DATASOURCE_CREATED = "datasource.created";
    public static final String DATASOURCE_UPDATED = "datasource.updated";
    public static final String DATASOURCE_DELETED = "datasource.deleted";
    public static final String DATASOURCE_SYNC_STARTED = "datasource.sync_started";
    public static final String DATASOURCE_SYNC_COMPLETED = "datasource.sync_completed";
    public static final String DATASOURCE_SYNC_FAILED = "datasource.sync_failed";
    public static final String DATASOURCE_PAUSED = "datasource.paused";
    public static final String DATASOURCE_RESUMED = "datasource.resumed";

    public static final String KB_SHARE_ADDED = "kb.share_added";
    public static final String KB_SHARE_PERMISSION_CHANGED = "kb.share_permission_changed";
    public static final String KB_SHARE_REMOVED = "kb.share_removed";
    /**
     * Wiki 内容变更（人工编辑 + ingest 批量摘要）。
     * 对照 Go {@code kb_activity.go RecordWikiContentActivity}：TargetType={@code wiki}，
     * TargetID=kbID，Details={@code {"count":N,"actions":{...}}}。
     */
    public static final String WIKI_CONTENT_CHANGED = "wiki.content_changed";

    public static final String FAQ_IMPORT_STARTED = "faq.import_started";
    public static final String FAQ_IMPORT_COMPLETED = "faq.import_completed";
    public static final String FAQ_IMPORT_FAILED = "faq.import_failed";
}
