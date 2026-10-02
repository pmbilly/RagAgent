package com.ragagent.auth.apikey.service;

import java.util.LinkedHashMap;
import com.ragagent.common.deployment.AppEnvLookup;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 建租户时的自动发 Key 支持（对照 Go internal/handler/tenant.go
 * 的 {@code autoCreateTenantAPIKey} L513-526 与 {@code tenantWithAPIKey} L496-511）。
 *
 * <p><b>为什么单独成类</b>：这两段逻辑属于"创建空间"这条链，而 Java 侧的
 * {@code POST /api/v1/tenants} 尚未翻译（auth 模块目前只有登录链）。
 * 先把两个纯函数搬过来，等建租户的控制器落地时直接调用，不必重新推导语义。</p>
 *
 * <h2>1. 要不要自动建 Key（legacy 兼容开关）</h2>
 * <p>Go 用系统设置的 3 层解析器：
 * {@code system_settings DB 行 > WEKNORA_TENANT_AUTO_CREATE_API_KEY env > false}。
 * Java 尚无 {@code SystemSettingService}，所以这里保留 **env 层**，
 * DB 层由调用方（系统设置模块翻译后）从上层传入——见
 * {@link #resolveAutoCreateApiKey(Boolean)}。</p>
 *
 * <p><b>默认关闭</b>：现代部署通过 tenant_api_keys 显式建 Key。</p>
 *
 * <h2>2. 响应里嵌入明文 Key</h2>
 * <p>{@code tenantWithAPIKey} 把租户序列化成 map 后塞一个 {@code api_key} 字段，
 * 让创建响应保持"变更前契约"（集成方依赖 {@code data.api_key}）。
 * 它**不往 types.Tenant 上加回 api_key 列**（迁移 000065 已把该列删掉）。</p>
 *
 * <p>⚠️ <b>键序契约</b>：Go 的实现是 {@code json.Marshal(tenant)} → {@code Unmarshal}
 * 进 {@code map[string]any} → 加键 → 交给 {@code gin.H} 输出。
 * 一旦走 map，encoding/json 就会**按字母序**输出键（且递归对所有嵌套 map 生效）。
 * 所以带 api_key 的创建响应与不带 api_key 的普通租户响应**键序不同**——
 * 前者全字母序，后者是 struct 声明序。Java 侧用
 * {@code ORDER_MAP_ENTRIES_BY_KEYS} 递归排序复刻。</p>
 */
public final class TenantAPIKeyBootstrap {

    /** 自动建的默认 Key 名（对照 Go 的字面量 {@code "default"}）。 */
    public static final String DEFAULT_KEY_NAME = "default";

    /** 对照 {@code WEKNORA_TENANT_AUTO_CREATE_API_KEY}。 */
    public static final String AUTO_CREATE_ENV = "WEKNORA_TENANT_AUTO_CREATE_API_KEY";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TenantAPIKeyBootstrap() {
    }

    /**
     * 对照 {@code TenantHandler.autoCreateTenantAPIKey} 的 3 层解析结果。
     *
     * @param systemSettingValue {@code system_settings} 里 {@code tenant.auto_create_api_key}
     *                           的布尔值；没有该行时传 {@code null}（未翻译 → 调用方传 null）
     * @return DB 值优先；否则看 env；都缺省 → false
     */
    public static boolean resolveAutoCreateApiKey(Boolean systemSettingValue) {
        if (systemSettingValue != null) {
            return systemSettingValue;
        }
        return parseBoolEnv(AUTO_CREATE_ENV);
    }

    /** Go 的 {@code strconv.ParseBool} 语义（systemsettings 层用它解析 env 字符串）。 */
    static boolean parseBoolEnv(String name) {
        String raw = AppEnvLookup.get(name);
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "1", "t", "true", "yes", "y", "on" -> true;
            default -> false;
        };
    }

    /**
     * 对照 {@code tenantWithAPIKey}（handler/tenant.go L496-511）：
     * 返回一个**递归按字母序排键**的 map，并嵌入 {@code api_key}。
     *
     * <p>入参可以是任意会被 Jackson 序列化的租户投影（如
     * {@code com.ragagent.auth.dto.TenantResponse}）。</p>
     *
     * <p>Go 在 Marshal/Unmarshal 失败时返回错误、由调用方降级为"不带 Key 的租户"
     * ——Java 侧序列化失败同样抛 {@link IllegalStateException}，
     * 由调用方捕获后回落。</p>
     */
    public static Map<String, Object> tenantWithApiKey(Object tenant, String token) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.convertValue(tenant, Map.class);
            Map<String, Object> merged = new LinkedHashMap<>(map);
            merged.put("apiKey", token);
            return sortRecursively(merged);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to embed api_key into tenant response", e);
        }
    }

    /** 递归按字母序排键——复刻 encoding/json 对 map 的排序（嵌套 map 同样排序）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sortRecursively(Map<String, Object> in) {
        java.util.TreeMap<String, Object> out = new java.util.TreeMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Object value = e.getValue();
            if (value instanceof Map<?, ?> nested) {
                value = sortRecursively((Map<String, Object>) nested);
            } else if (value instanceof java.util.List<?> list) {
                value = sortListRecursively(list);
            }
            out.put(e.getKey(), value);
        }
        return out;
    }

    private static java.util.List<Object> sortListRecursively(java.util.List<?> in) {
        java.util.List<Object> out = new java.util.ArrayList<>(in.size());
        for (Object item : in) {
            if (item instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) nested;
                out.add(sortRecursively(cast));
            } else if (item instanceof java.util.List<?> nestedList) {
                out.add(sortListRecursively(nestedList));
            } else {
                out.add(item);
            }
        }
        return out;
    }
}
