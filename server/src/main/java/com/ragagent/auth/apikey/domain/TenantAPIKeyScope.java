package com.ragagent.auth.apikey.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ragagent.common.error.BizException;

/**
 * 请求上下文里的 API Key 授权投影（对照 Go {@code types.TenantAPIKeyScope}，
 * internal/types/tenant_api_key.go L268-355）。
 *
 * <p>它是认证阶段（middleware/auth.go 的 {@code attachAPIKeyAuthContext}）从
 * {@link TenantAPIKey} 快照出来的**不可变**视图，之后由
 * {@link APIKeyScopeContext} 挂在请求线程上，供
 * {@link com.ragagent.auth.apikey.filter.APIKeyRouteAuthorizer} 与下游 KB 白名单判定读取。</p>
 *
 * <p>所有判定方法都先 {@link #normalize()}——对照 Go 的
 * {@code Normalize()}，语义是"输入永远先规范化一遍"，所以在构造后直接调用也是安全的。</p>
 *
 * <p>本类型**不参与 JSON**（只在进程内传递），因此没有序列化注解。</p>
 */
public final class TenantAPIKeyScope {

    private final long keyId;
    private final String scopeType;
    private final boolean fullAccess;
    private final List<String> knowledgeBaseIds;
    private final List<String> capabilities;

    public TenantAPIKeyScope(long keyId, String scopeType, boolean fullAccess,
                             List<String> knowledgeBaseIds, List<String> capabilities) {
        this.keyId = keyId;
        this.scopeType = scopeType;
        this.fullAccess = fullAccess;
        this.knowledgeBaseIds = knowledgeBaseIds;
        this.capabilities = capabilities;
    }

    /** 空 scope（对照 Go 零值 {@code TenantAPIKeyScope{}}）：tenant + scoped。 */
    public static TenantAPIKeyScope empty() {
        return new TenantAPIKeyScope(0L, APIKeyScopeType.TENANT, false, null, null);
    }

    public long keyId() { return keyId; }
    public String scopeType() { return scopeType; }
    public boolean fullAccess() { return fullAccess; }
    public List<String> knowledgeBaseIds() { return knowledgeBaseIds; }
    public List<String> capabilities() { return capabilities; }

    /**
     * 对照 {@code (TenantAPIKeyScope).Normalize()}（L292-300）：
     * scopeType 归一（未知 → tenant）、KB 数组 trim+去空+去重、
     * 能力清单丢弃未知项。{@code FullAccess} **原样保留**——
     * Go 有两条测试专门钉这一点（不能因为归一化把 full access 弄丢，也不能凭空造出）。
     */
    public TenantAPIKeyScope normalize() {
        return new TenantAPIKeyScope(
                keyId,
                APIKeyScopeType.normalize(scopeType),
                fullAccess,
                normalizeIdArray(knowledgeBaseIds),
                APIKeyCapability.normalizeAll(capabilities));
    }

    /** 对照 {@code NormalizeAPIKeyScopeType} 后的 {@code IsPlatform()}。 */
    public boolean isPlatform() {
        return APIKeyScopeType.PLATFORM.equals(APIKeyScopeType.normalize(scopeType));
    }

    /**
     * 对照 {@code HasCapability}（L307-318）：未知能力永远返回 false
     * （先 normalize，normalize 失败立即 false，不会误命中）。
     */
    public boolean hasCapability(String capability) {
        String norm = APIKeyCapability.normalize(capability);
        if (norm == null) {
            return false;
        }
        for (String item : APIKeyCapability.normalizeAll(capabilities)) {
            if (item.equals(norm)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对照 {@code AllowsKnowledgeBase}（L320-335）：空白 ID 恒 false；
     * **白名单为空 = 不限制**（返回 true）。
     */
    public boolean allowsKnowledgeBase(String kbId) {
        if (kbId == null || kbId.trim().isEmpty()) {
            return false;
        }
        String target = kbId.trim();
        TenantAPIKeyScope s = normalize();
        if (s.knowledgeBaseIds.isEmpty()) {
            return true;
        }
        return s.knowledgeBaseIds.contains(target);
    }

    /** 对照 {@code IsKnowledgeBaseRestricted}（L337-339）。 */
    public boolean isKnowledgeBaseRestricted() {
        return !normalize().knowledgeBaseIds.isEmpty();
    }

    /**
     * 对照 {@code AllowsKnowledgeBases}（L341-355）：
     * 不限制 → true；限制且入参为空 → **false**（限制型 Key 不许"未指定即全放"）。
     */
    public boolean allowsKnowledgeBases(List<String> kbIds) {
        TenantAPIKeyScope s = normalize();
        if (s.knowledgeBaseIds.isEmpty()) {
            return true;
        }
        if (kbIds == null || kbIds.isEmpty()) {
            return false;
        }
        for (String kbId : kbIds) {
            if (!s.allowsKnowledgeBase(kbId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 对照 Go 包级 {@code normalizeIDArray}（L357-372）：trim + 去空 + 去重，
     * 返回**可变**空列表（Go 是 {@code make(StringArray, 0, len(in))}）。
     */
    private static List<String> normalizeIdArray(List<String> in) {
        List<String> out = new ArrayList<>(in == null ? 0 : in.size());
        Set<String> seen = new LinkedHashSet<>();
        if (in == null) {
            return out;
        }
        for (String item : in) {
            if (item == null) {
                continue;
            }
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (seen.add(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    // ── 下游 KB 白名单判定（对照 Go 的四个包级函数，L374-444） ──
    //
    // Go 侧这四处在 handler / application/access / application/service 共约 18 个
    // 调用点（kb_access.go、knowledge.go、session/qa.go、custom_agent.go 等），
    // 用来把"Key 的 KB 白名单"渗透到数据面。Java 侧先提供等价物，
    // 等对应模块（知识库/agent/会话）回补时接线——见任务报告"需决策点"。

    /**
     * 对照 {@code AuthorizeTenantAPIKeyKnowledgeBases}（L376-385）：
     * KB 受限的 Key 一旦指向白名单外的知识库就 403；非受限 Key / 非 API Key 主体恒放行。
     */
    public static void authorizeKnowledgeBases(List<String> kbIds) {
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null || !scope.isKnowledgeBaseRestricted()) {
            return;
        }
        if (kbIds != null && !kbIds.isEmpty() && !scope.allowsKnowledgeBases(kbIds)) {
            throw BizException.forbidden("API key scope does not allow one or more knowledge bases");
        }
    }

    /**
     * 对照 {@code AuthorizeTenantAPIKeyKnowledgeTargets}（L390-402）：
     * KB 受限的 Key 携带 {@code knowledge_ids} 一律拒绝（无法验证这些文档归属于
     * 白名单内的知识库），另加白名单外的 {@code kb_ids} 拒绝。
     *
     * <p><b>顺序有语义</b>：先判 knowledge_ids（无条件拒绝），再判 kb_ids。</p>
     */
    public static void authorizeKnowledgeTargets(List<String> kbIds, List<String> knowledgeIds) {
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null || !scope.isKnowledgeBaseRestricted()) {
            return;
        }
        if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
            throw BizException.forbidden(
                    "API key scope does not allow knowledge_ids without a verified knowledge base");
        }
        if (kbIds != null && !kbIds.isEmpty() && !scope.allowsKnowledgeBases(kbIds)) {
            throw BizException.forbidden("API key scope does not allow one or more knowledge bases");
        }
    }

    /**
     * 对照 {@code AuthorizeTenantAPIKeyOptionalTagIDs}（L406-415）：
     * 标签解析可能跨知识库取文档，所以 KB 受限的 Key 一律不许带 {@code tag_ids}。
     */
    public static void authorizeOptionalTagIds(List<String> tagIds) {
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null || !scope.isKnowledgeBaseRestricted()) {
            return;
        }
        if (tagIds != null && !tagIds.isEmpty()) {
            throw BizException.forbidden(
                    "API key scope does not allow tag_ids without a verified knowledge base");
        }
    }

    /**
     * 对照 {@code FilterKnowledgeBasesForTenantAPIKeyScope}（L420-444）：
     * 把"解析出来的 KB 集合"与白名单**求交**。
     *
     * <p>两条分支的差别是刻意的：调用方**显式**给了 kb_ids 时逐个校验、越界即 403
     * （用户明说了要哪个，不该被静默过滤）；kb_ids 为空时（如 agent 的默认 KB）
     * 走交集，安静地去掉白名单外的。</p>
     */
    public static List<String> filterKnowledgeBases(List<String> requestedKbIds, List<String> resolvedKbIds) {
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null || !scope.isKnowledgeBaseRestricted()) {
            return resolvedKbIds;
        }
        if (requestedKbIds != null && !requestedKbIds.isEmpty()) {
            if (!scope.allowsKnowledgeBases(requestedKbIds)) {
                throw BizException.forbidden("API key scope does not allow one or more knowledge bases");
            }
            return resolvedKbIds;
        }
        Set<String> allowed = new LinkedHashSet<>(scope.knowledgeBaseIds());
        List<String> filtered = new ArrayList<>();
        if (resolvedKbIds != null) {
            for (String id : resolvedKbIds) {
                if (allowed.contains(id)) {
                    filtered.add(id);
                }
            }
        }
        return filtered;
    }
}
