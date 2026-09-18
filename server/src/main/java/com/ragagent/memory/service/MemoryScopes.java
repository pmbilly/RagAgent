package com.ragagent.memory.service;

import com.ragagent.common.context.TenantContext;
import com.ragagent.memory.domain.MemoryScope;

/**
 * 只从请求上下文推导记忆空间（对照 Go
 * {@code internal/application/service/memory/scope.go} 的 {@code ResolveScope}）。
 *
 * <h2>推导而不是接受 scope，就是整套隔离模型</h2>
 * <p>不存在任何"客户端可以用自己传的 id 选中一个记忆空间"的代码路径，
 * 所以没有任何端点需要为这件事被审计。主体是 {@code Principal.StorageID()}，
 * 它同时覆盖 Web 用户、IM 用户、API 外部用户和 embed 访客；
 * 再与工作区配对，同一个人在多个工作区之间的记忆也就不会串。</p>
 *
 * <h2>Java 与 Go 的形状差异</h2>
 * <p>Go 把 {@code (MemoryScope, error)} 作为返回值；Java 用
 * {@link MemoryScopeExceptions.NoScope} 表达同一个失败——读路径把异常当"没有记忆"，
 * API 路径把它转成 401（因为"没有主人的记忆管理器"是 bug，不是空状态）。</p>
 * <p>没有 context 参数：租户与主体走 {@link TenantContext}（约定 §5）。</p>
 */
public final class MemoryScopes {

    private MemoryScopes() {}

    /**
     * 对照 Go {@code ResolveScope}。
     *
     * <p>三条前置逐条照抄：租户必须存在且 **非 0**；主体必须存在；主体的
     * {@code StorageID()} 必须非空。</p>
     *
     * @throws MemoryScopeExceptions.NoScope 对应 Go 的 {@code ErrNoMemoryScope}
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
     * 对照 Go {@code Principal.StorageID()}：{@code Type + ":" + ID}，两者都去空白后非空才算有效。
     *
     * <p>Java 侧没有独立的 Principal 领域类型（{@code TenantContext.Principal} 是 record），
     * 所以 {@code Normalize}/{@code Valid}/{@code StorageID} 三个方法在这里合一。</p>
     *
     * <p>⚠️ 去空白用的是 Go 的 {@code strings.TrimSpace} 语义
     * （{@code unicode.IsSpace}：含 U+00A0 / U+0085），**不是** {@link String#strip()}
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

    /** 对照 Go {@code strings.TrimSpace}（{@code unicode.IsSpace} 的前后裁剪）。 */
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

    /** 对照 Go {@code unicode.IsSpace}。 */
    private static boolean isGoSpace(int cp) {
        if (Character.isSpaceChar(cp)) {
            return true;
        }
        return cp == 0x09 || cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D || cp == 0x85;
    }
}
