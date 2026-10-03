package com.ragagent.memory.service;

import com.ragagent.common.context.TenantContext;
import com.ragagent.memory.domain.MemoryScope;

/**
 * 只从请求上下文推导记忆空间。
 *
 * <h2>推导而不是接受 scope，就是整套隔离模型</h2>
 * <p>不存在任何"客户端可以用自己传的 id 选中一个记忆空间"的代码路径，
 * 所以没有任何端点需要为这件事被审计。主体是 {@code Principal.StorageID()}，
 * 它同时覆盖 Web 用户、IM 用户、API 外部用户和 embed 访客；
 * 再与工作区配对，同一个人在多个工作区之间的记忆也就不会串。</p>
 *
 * <h2>失败表达</h2>
 * <p>失败用 {@link MemoryScopeExceptions.NoScope} 表达——读路径把异常当"没有记忆"，
 * API 路径把它转成 401（因为"没有主人的记忆管理器"是 bug，不是空状态）。</p>
 * <p>没有 context 参数：租户与主体走 {@link TenantContext}（约定 §5）。</p>
 */
public final class MemoryScopes {

    private MemoryScopes() {}

    /**
     * 推导记忆 scope。
     *
     * <p>三条前置逐条检查：租户必须存在且 **非 0**；主体必须存在；主体的
     * {@code StorageID()} 必须非空。</p>
     *
     * @throws MemoryScopeExceptions.NoScope 任一前置不满足
     */
    public static MemoryScope resolve() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0L) {
            throw new MemoryScopeExceptions.NoScope();
        }
        TenantContext.Principal principal = TenantContext.currentPrincipal();
        if (principal == null) {
            throw new MemoryScopeExceptions.NoScope();
        }
        String subjectId = storageId(principal);
        if (subjectId.isEmpty()) {
            throw new MemoryScopeExceptions.NoScope();
        }
        return new MemoryScope(tenantId, subjectId);
    }

    /**
     * {@code Type + ":" + ID} 形式的主体 id，两者都去空白后非空才算有效。
     *
     * <p>Java 侧没有独立的 Principal 领域类型（{@code TenantContext.Principal} 是 record），
     * 所以 {@code Normalize}/{@code Valid}/{@code StorageID} 三个方法在这里合一。</p>
     *
     * <p>⚠️ 去空白按 Unicode White_Space 语义（含 U+00A0 / U+0085），**不是**
     * {@link String#strip()}
     * ——后者不含不换行空格（与 §9 记的那条 {@code \s} 差异同族）。</p>
     */
    public static String storageId(TenantContext.Principal principal) {
        if (principal == null) {
            return "";
        }
        String type = trimSpace(principal.type());
        String id = trimSpace(principal.id());
        if (type.isEmpty() || id.isEmpty()) {
            return "";
        }
        return type + ":" + id;
    }

    /** 前后裁剪 Unicode White_Space 空白。 */
    static String trimSpace(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start && isGoSpace(s.codePointBefore(end))) {
            end -= Character.charCount(s.codePointBefore(end));
        }
        return s.substring(start, end);
    }

    /** Unicode White_Space 语义的空白判定。 */
    private static boolean isGoSpace(int cp) {
        if (Character.isSpaceChar(cp)) {
            return true;
        }
        return cp == 0x09 || cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D || cp == 0x85;
    }
}
