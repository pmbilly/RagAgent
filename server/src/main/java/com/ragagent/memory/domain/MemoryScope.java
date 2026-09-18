package com.ragagent.memory.domain;

/**
 * 一次记忆操作所属的（工作区, 主体）对
 * （对照 Go {@code interfaces.MemoryScope}，internal/types/interfaces/memory.go L14-21）。
 *
 * <p><b>它总是从请求上下文推导出来，绝不来自客户端传的 id</b>——这就是整套隔离模型：
 * 不存在任何一条"客户端可以用 id 选中别人的记忆空间"的代码路径，所以每个端点都不必
 * 单独审计这件事。subjectId 是 {@code Principal.StorageID()}，同时覆盖 Web 用户、IM 用户、
 * API 外部用户和 embed 访客；再与工作区配对，同一个人在多个工作区之间的记忆也就不会串。</p>
 */
public record MemoryScope(long tenantId, String subjectId) {

    /**
     * 对照 Go {@code MemoryScope.Valid()}：租户 > 0 **且** subject 非空。
     *
     * <p>注意 {@code MemoryVectorQuery} 的两条查询路径都用它做前置判断，
     * 所以这个方法不能放宽。</p>
     */
    public boolean valid() {
        return tenantId > 0 && subjectId != null && !subjectId.isEmpty();
    }
}
