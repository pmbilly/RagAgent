package com.ragagent.session.domain;

/**
 * 会话列表查询参数（对照 Go {@code types.SessionListQuery}，internal/types/session.go L214-222）。
 *
 * <p>Go 的结构体**没有 json tag**——它是服务层内部对象，从来不是请求体/响应体。
 * Java 侧同样不做 Jackson 注解。请求参数由控制器逐个从 query string 取（对照 Go 的
 * {@code c.Query("keyword")}）。</p>
 *
 * @param tenantId 空间 ID（服务层从上下文填，不从请求取）
 * @param userId   所有者范围；**空串 = 不做按人裁剪**（API-Key 调用方 / 历史行）
 * @param keyword  标题模糊匹配，忽略大小写
 * @param source   来源过滤：{@code ""} / {@code web} / {@code api} / {@code embed} /
 *                 {@code embed:{channelID}} / IM 平台名（feishu、wechat…）。
 *                 取值大小写不敏感——Go 在 SQL 前先 {@code ToLower}
 * @param agentId  按 Agent 过滤；**只对已经绑定 IM 渠道的会话生效**（走 ics.agent_id）
 */
public record SessionListQuery(
        long tenantId,
        String userId,
        String keyword,
        String source,
        String agentId,
        int page,
        int pageSize) {

    /** 方便从控制器构造：租户由服务层补。 */
    public static SessionListQuery of(String keyword, String source, String agentId, int page, int pageSize) {
        return new SessionListQuery(0L, "", keyword, source, agentId, page, pageSize);
    }

    /** 服务层填租户与 owner 后的副本（对照 Go 直接改 query 的 TenantID/UserID）。 */
    public SessionListQuery withScope(long tenantId, String userId) {
        return new SessionListQuery(tenantId, userId, keyword, source, agentId, page, pageSize);
    }
}
