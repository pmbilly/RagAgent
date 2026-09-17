package com.ragagent.audit.domain;

/**
 * 审计日志列表的游标 + 过滤条件（对照 Go
 * internal/types/interfaces/audit_log.go 的 {@code AuditLogQuery} L17-26）。
 *
 * <p>{@code afterId} 是上一页最后一条的 id（返回 id &lt; afterId 的行，最新在前），
 * 0 表示"从最新开始"。{@code limit} 无论调用方传什么，仓储层都会截到 100 以内——
 * 让误配的客户端无法触发表扫描。</p>
 *
 * <p><b>不是 JSON 契约</b>：它是查询参数的内存形态，不出现在任何响应体里，
 * 因此不需要 JsonContractRoundTripTest 断言。</p>
 *
 * @param afterId      游标：只返回 id 小于此值的行；0 = 从最新开始
 * @param limit        页大小；&lt;= 0 时仓储用默认 50，&gt; 100 时截到 100
 * @param action       按 action 精确匹配（空 = 不过滤）
 * @param outcome      按 outcome 精确匹配（空 = 不过滤）
 * @param actorUserId  按 actor_user_id 精确匹配（空 = 不过滤）
 * @param scopeType    按 scope_type 精确匹配（空 = 不过滤）
 * @param scopeId      按 scope_id 精确匹配（空 = 不过滤）
 * @param unscopedOnly true 时只返回 {@code scope_type = ''} 的行（租户级 feed 专用）
 */
public record AuditLogQuery(
        long afterId,
        int limit,
        String action,
        String outcome,
        String actorUserId,
        String scopeType,
        String scopeId,
        boolean unscopedOnly) {

    /** 对照 Go 空查询的零值：全部过滤器为空、游标 0、limit 交给仓储取默认。 */
    public static AuditLogQuery empty() {
        return new AuditLogQuery(0, 0, "", "", "", "", "", false);
    }

    /** 空过滤器归一：null 与 "" 等价（Go 的 string 零值语义）。 */
    private static boolean has(String v) {
        return v != null && !v.isEmpty();
    }

    public boolean hasAction() { return has(action); }
    public boolean hasOutcome() { return has(outcome); }
    public boolean hasActorUserId() { return has(actorUserId); }
    public boolean hasScopeType() { return has(scopeType); }
    public boolean hasScopeId() { return has(scopeId); }
}
