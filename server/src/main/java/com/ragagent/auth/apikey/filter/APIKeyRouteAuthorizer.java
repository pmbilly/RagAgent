package com.ragagent.auth.apikey.filter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;

/**
 * 逐路由 API-Key 策略注册表 + 授权判定。
 *
 * <p>注册发生在**应用启动期（单线程）**，请求期只读——天然无锁。
 * Java 侧再叠加一层不可变快照（{@link #freeze()}）供运行期只读查询。</p>
 *
 * <h2>路由键的形态：gin 模板 → Spring 模板</h2>
 * <p>gin 形态的路由键，例如
 * {@code /api/v1/knowledge-bases/:id/knowledge/file}。</p>
 * <p>Java 侧等价物是 Spring MVC 的 <b>best-matching pattern</b>
 * （{@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}），写法是
 * {@code /api/v1/knowledge-bases/{id}/knowledge/file}。两种模板的对应关系：</p>
 * <ul>
 *   <li>gin {@code :param}  ↔ Spring {@code {param}}（单段匹配）</li>
 *   <li>gin {@code *wildcard} ↔ Spring {@code {*wildcard}}（路径尾部多段）
 *       —— 注意 gin 的 {@code *} 只能出现在路径末尾，Spring 亦同；
 *       wiki 的 {@code /pages/*slug} 在 Spring 里就是 {@code /pages/{*slug}}</li>
 *   <li>路由键统一是完整绝对路径；Spring 的 pattern 是控制器的绝对映射，
 *       两种形态最终字符串一致</li>
 * </ul>
 * <p>为了少一次心智转换，{@link #registerGin(String, String, APIKeyRoutePolicy)}
 * 提供了按 gin 风格模板登记策略的入口：它把 {@code :x} / {@code *x} 就地转成
 * Spring 形态。{@link #normalizeRoutePath(String)} 的归一规则（折叠重复斜杠、
 * 去掉尾斜杠）对两种形态都适用。</p>
 *
 * <p><b>查表必须与注册用同一种归一化</b>，否则 {@code /api/v1/models/} 这类
 * "带尾斜杠的请求"会 miss 并静默 403。Java 侧的自检手段是
 * {@link #declaredNodes()} + 启动期对账（策略必须指向已注册的路由）。</p>
 */
public final class APIKeyRouteAuthorizer {

    /**
     * 策略表：HTTP 方法 → 归一化 full-path → 策略。
     * 用 {@link TreeMap} 让 {@link #registeredRoutes()} 的输出有序（便于自检与断言可读）。
     */
    private final Map<String, Map<String, APIKeyRoutePolicy>> policies = new TreeMap<>();

    /** 注册新的 (method, fullPath) 策略。 */
    public void register(String method, String fullPath, APIKeyRoutePolicy policy) {
        put(method, fullPath, policy);
    }

    /**
     * 按 gin 路由模板注册（{@code :param} / {@code *wildcard} 就地转 Spring 形态），
     * 便于按 gin 风格模板直接书写策略表，避免手工转换出错。
     */
    public void registerGin(String method, String ginFullPath, APIKeyRoutePolicy policy) {
        put(method, ginPathToSpringPath(ginFullPath), policy);
    }

    private void put(String method, String fullPath, APIKeyRoutePolicy policy) {
        String key = normalizeMethod(method);
        String path = normalizeRoutePath(fullPath);
        policies.computeIfAbsent(key, k -> new TreeMap<>()).put(path, policy);
    }

    /**
     * 查策略：未声明 → {@code null}（调用方 default-deny）。
     *
     * <p>返回值是 {@code null} 而不是空政策对象，是因为"没声明"与"声明了宽松策略"
     * 必须可区分——前者拒绝，后者放行。</p>
     */
    public APIKeyRoutePolicy lookup(String method, String fullPath) {
        Map<String, APIKeyRoutePolicy> byPath = policies.get(normalizeMethod(method));
        if (byPath == null) {
            return null;
        }
        return byPath.get(normalizeRoutePath(fullPath));
    }

    /** 该 (method, path) 是否已声明策略。 */
    public boolean isDeclared(String method, String fullPath) {
        return lookup(method, fullPath) != null;
    }

    /**
     * 方法 → 已声明的路径列表。
     * 供启动自检检测"策略指向了不存在的路由"。
     */
    public Map<String, List<String>> registeredRoutes() {
        Map<String, List<String>> out = new TreeMap<>();
        for (Map.Entry<String, Map<String, APIKeyRoutePolicy>> e : policies.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue().keySet()));
        }
        return out;
    }

    /** 供自检用的扁平 "METHOD path" 集合（key 已归一化）。 */
    public List<String> declaredNodes() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, APIKeyRoutePolicy>> e : policies.entrySet()) {
            for (String path : e.getValue().keySet()) {
                out.add(e.getKey() + " " + path);
            }
        }
        return out;
    }

    /**
     * 授权判定。判定顺序即语义，<b>看似多余的判断也是刻意的，别删</b>。
     *
     * <ol>
     *   <li><b>无策略 → 拒绝</b>（default deny）；</li>
     *   <li>{@code PlatformOnly && !scope.IsPlatform()} → 拒绝：租户 Key 不许进控制面，
     *       <b>哪怕它是 full-access</b>；</li>
     *   <li>{@code PlatformOnly && 策略无可能力} → 拒绝：
     *       "平台专用但没写明要求什么能力"被视为配置错误，fail-closed
     *       （腐坏的平台 full-access Key 也过不去）；</li>
     *   <li>{@code scope.FullAccess} → 放行；</li>
     *   <li>策略能力 any-of 命中 scope 的能力 → 放行（空串能力被跳过）；</li>
     *   <li>既不要 full-access、也没有能力清单 → 放行（"对任何有效 Key 开放"）；</li>
     *   <li>否则拒绝。</li>
     * </ol>
     *
     * @return {@code true} = 放行
     */
    public boolean authorize(TenantAPIKeyScope scope, String method, String fullPath) {
        APIKeyRoutePolicy policy = lookup(method, fullPath);
        if (policy == null) {
            return false;
        }
        if (policy.platformOnly() && !scope.isPlatform()) {
            return false;
        }
        if (policy.platformOnly() && policy.hasNoCapabilities()) {
            return false;
        }
        if (scope.fullAccess()) {
            return true;
        }
        for (String capability : policy.capabilities()) {
            if (capability != null && !capability.isEmpty() && scope.hasCapability(capability)) {
                return true;
            }
        }
        if (!policy.requireFullAccess() && policy.hasNoCapabilities()) {
            return true;
        }
        return false;
    }

    // ── 路径与方法的归一化 ──

    /**
     * 折叠重复斜杠 + 去掉尾斜杠（长度 &gt; 1 时），让"分组拼接出来的路径"与
     * "框架上报的 full path" 严格相等。
     *
     * <pre>
     * "/api/v1//models"  → "/api/v1/models"
     * "/api/v1/models/"  → "/api/v1/models"
     * "/"                → "/"          （长度 1，不裁剪）
     * </pre>
     *
     * <p>额外做一步 {@code null → ""} 的归一，
     * 调用方不必先判空。</p>
     */
    public static String normalizeRoutePath(String p) {
        if (p == null || p.isEmpty()) {
            return "";
        }
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        // 只裁一个尾斜杠。
        // 上面的折叠保证此时最多只剩一个。
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * gin 路由模板 → Spring MVC pattern：
     * {@code :param} → {@code {param}}，{@code *wildcard} → {@code {*wildcard}}。
     *
     * <p>只处理**段首**的占位符（gin 的 {@code :x} / {@code *x} 后面不能跟别的字符，
     * 所以段内出现即整段是参数），普通字面段原样保留。转换是幂等的：
     * 已经是 Spring 形态的路径不会被再次改写。</p>
     */
    public static String ginPathToSpringPath(String ginPath) {
        if (ginPath == null || ginPath.isEmpty()) {
            return ginPath;
        }
        String[] segments = ginPath.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            String seg = segments[i];
            if (seg.startsWith(":") && seg.length() > 1) {
                sb.append('{').append(seg.substring(1)).append('}');
            } else if (seg.startsWith("*") && seg.length() > 1) {
                sb.append("{*").append(seg.substring(1)).append('}');
            } else {
                sb.append(seg);
            }
        }
        return sb.toString();
    }

    /** 方法统一大写（先 trim 空白）。 */
    private static String normalizeMethod(String method) {
        return method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
    }

    /** 调试用：把已声明的策略表打成可读文本（启动日志/失败信息）。 */
    public Map<String, Map<String, APIKeyRoutePolicy>> snapshot() {
        Map<String, Map<String, APIKeyRoutePolicy>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, APIKeyRoutePolicy>> e : policies.entrySet()) {
            out.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return out;
    }
}
