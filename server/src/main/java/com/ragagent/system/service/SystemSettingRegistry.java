package com.ragagent.system.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * system_settings 的 in-code 注册表（对照 Go internal/application/service/system_setting.go
 * 的 {@code registry map[string]settingSpec}，17 条）。
 *
 * <p>它是"哪些 key 合法 + 类型 + ENV 回退名 + 内置默认值"的唯一权威：
 * Update 拒绝任何不在表内的 key；List/Get 对没有 DB 行的 key 产出
 * <b>虚拟行</b>（值 = ENV → cfg → default 三层回退的生效值）。</p>
 *
 * <p>Description 文案逐字对照 Go 源（golden adm-settings-list.json 钉住）——
 * 这些字符串是响应体的一部分，不是注释。</p>
 */
public final class SystemSettingRegistry {

    /** 对照 Go settingSpec（Default 字段按类型解释；Enum 只对 string 有意义）。 */
    public record Spec(String type, String envName, Object defaultValue,
                       List<String> enumOptions, String category,
                       String description, boolean requiresRestart) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** auth.registration_mode 的 cfg 兜底常量（Go config.AuthRegistrationModeSelfServe）。 */
    public static final String AUTH_REGISTRATION_MODE_DEFAULT = "self_serve";

    private static final Map<String, Spec> REGISTRY = new TreeMap<>();

    private static void spec(String key, String type, String envName, String category,
                             String description, boolean requiresRestart,
                             List<String> enumOptions, Object defaultValue) {
        REGISTRY.put(key, new Spec(type, envName, defaultValue, enumOptions, category,
                description, requiresRestart));
    }

    static {
        spec("asynq.core_concurrency", "int", "WEKNORA_ASYNQ_CORE_CONCURRENCY", "worker",
                "文档解析、手工重解析等核心任务的每实例保底并发。可额外使用共享弹性池；修改后需重启。",
                true, List.of(), 8L);
        spec("asynq.enrichment_concurrency", "int", "WEKNORA_ASYNQ_ENRICHMENT_CONCURRENCY", "worker",
                "摘要、图片、图谱和问题生成的每实例保底并发。可额外使用共享弹性池；修改后需重启。",
                true, List.of(), 12L);
        spec("asynq.maintenance_concurrency", "int", "WEKNORA_ASYNQ_MAINTENANCE_CONCURRENCY", "worker",
                "数据源同步、批处理、移动和删除清理的每实例保底并发，与用户面流水线硬隔离；修改后需重启。",
                true, List.of(), 4L);
        spec("asynq.postprocess_concurrency", "int", "WEKNORA_ASYNQ_POSTPROCESS_CONCURRENCY", "worker",
                "解析完成后的轻量编排与富化扇出专用并发，避免被长时间文档解析阻塞；修改后需重启。",
                true, List.of(), 2L);
        spec("asynq.shared_concurrency", "int", "WEKNORA_ASYNQ_SHARED_CONCURRENCY", "worker",
                "核心解析与内容富化共用的每实例弹性并发。空闲容量由有积压的一侧借用；修改后需重启。",
                true, List.of(), 6L);
        spec("asynq.wiki_concurrency", "int", "WEKNORA_WIKI_ASYNQ_CONCURRENCY", "worker",
                "Wiki 生成专用池的 worker 并发数（与文档解析池相互隔离）。Wiki 生成以合成大模型调用为主，"
                        + "独立并发预算可避免上传高峰期被解析任务饿死，同时不会因 Wiki 洪峰拖慢用户面解析。修改后需重启服务进程方可生效。",
                true, List.of(), 8L);
        spec("auth.complex_password_enabled", "bool", "WEKNORA_AUTH_COMPLEX_PASSWORD_ENABLED", "auth",
                "是否启用复杂密码。开启后密码必须包含大小写字母、数字和特殊字符。修改后立即生效，"
                        + "只影响新注册用户或新密码修改/重置操作。特殊字符包含：!@#$%^&*()_+-=[]{}|;:,.<>?",
                false, List.of(), Boolean.FALSE);
        spec("auth.default_tenant_mode", "string", "WEKNORA_AUTH_DEFAULT_TENANT_MODE", "auth",
                "公开注册成功后的默认空间策略。create_personal = 自动创建个人空间并设为 Owner；"
                        + "tenantless = 仅创建用户，等待接受邀请或主动创建空间。修改后只影响新注册用户。",
                false, List.of("create_personal", "tenantless"), "create_personal");
        spec("auth.registration_mode", "string", "", "auth",
                "自助注册模式。self_serve = 任何人可注册账号；invite_only = 关闭公网注册，"
                        + "仅 Owner/Admin 可邀请。修改后立即生效，但谨慎对待 self_serve（公网会接受 spam）。",
                false, List.of("self_serve", "invite_only"), AUTH_REGISTRATION_MODE_DEFAULT);
        spec("model.max_concurrency", "int", "WEKNORA_MODEL_MAX_CONCURRENCY", "worker",
                "后台任务（文档入库/富化）对单个模型的默认并发上限，按模型 ID 全副本共享。"
                        + "每次调用实时读取，修改后立即生效、无需重启。0 或负数表示关闭默认限制"
                        + "（各模型仍会尊重自身在模型管理里配置的上限）。仅影响后台任务，不影响交互式对话。",
                false, List.of(), 32L);
        spec("ssrf.whitelist", "string_list", "SSRF_WHITELIST", "security",
                "SSRF 防护白名单。可填入 example.com / *.foo.com / 10.0.0.0/8 / 2001:db8::1。"
                        + "修改后立即生效。SSRF_WHITELIST_EXTRA 环境变量仍由部署方维护，不在此处覆盖。",
                false, List.of(), List.of());
        spec("tenant.auto_accept_invitation", "bool", "WEKNORA_TENANT_AUTO_ACCEPT_INVITATION", "tenant",
                "全局开关：开启后，空间管理员通过邮箱邀请已注册用户加入空间时，"
                        + "被邀请人将被立即自动加入（直接写入成员关系），无需在收件箱手动接受，"
                        + "也不再生成待接受的邀请记录。关闭时保持原有「发出邀请 → 被邀请人收件箱确认」流程。"
                        + "每次邀请时实时读取，修改后立即生效。默认 false。",
                false, List.of(), Boolean.FALSE);
        spec("tenant.auto_create_api_key", "bool", "WEKNORA_TENANT_AUTO_CREATE_API_KEY", "tenant",
                "创建空间时是否自动生成一个全量权限（full_access）的 API Key，并在创建接口的响应中返回其明文 token。"
                        + "用于兼容旧版本「创建空间即下发默认 API Key」的行为（属于破坏性变更的回退开关）。"
                        + "每次创建空间时实时读取，修改后立即生效。默认 false（不自动创建，需通过 API Key 管理显式创建）。",
                false, List.of(), Boolean.FALSE);
        spec("tenant.default_storage_quota_gb", "int", "WEKNORA_TENANT_DEFAULT_STORAGE_QUOTA_GB", "tenant",
                "新建空间时默认分配的存储配额（GB），包含向量、原文、文本、索引等。"
                        + "仅在创建时读取，修改后只对之后新建的空间生效，不会回写已存在的空间。"
                        + "0 或负数表示使用内置默认值 10GB。",
                false, List.of(), 10L);
        spec("tenant.max_owned_per_user", "int", "WEKNORA_TENANT_MAX_OWNED_PER_USER", "tenant",
                "每个非超管用户通过自助创建可拥有的最大空间数。每次创建空间时实时读取，修改后立即生效。"
                        + "0 表示使用内置默认值 10；负数表示完全关闭限制（不建议在公开部署使用）。",
                false, List.of(), 10L);
        spec("tenant.self_service_creation_enabled", "bool",
                "WEKNORA_TENANT_SELF_SERVICE_CREATION_ENABLED", "tenant",
                "是否允许非超管用户主动创建空间。关闭后，普通用户只能通过邀请加入已有空间；"
                        + "跨空间超管仍可创建。修改后立即生效。",
                false, List.of(), Boolean.TRUE);
    }

    /** 已注册（合法）key 的有序视图——Go 的 map + sort.Strings == 字典序。 */
    public static List<String> keys() {
        return List.copyOf(REGISTRY.keySet());
    }

    public static Spec get(String key) {
        return REGISTRY.get(key);
    }

    public static boolean isKnown(String key) {
        return REGISTRY.containsKey(key);
    }

    /** List 时显式退役的旧聚合键（Go 注释：keeping an old DB row visible would lie）。 */
    public static final String RETIRED_KEY = "asynq.concurrency";

    /** "general" 兜底分类（virtualSetting 的 category=="" 分支；registry 内无空分类，防御保留）。 */
    public static final String GENERAL_CATEGORY = "general";

    /**
     * 对照 Go encodeDefault：内置默认值的 JSONB 编码（类型必须与 Type 一致）。
     * string_list 的 nil → []。
     */
    public static JsonNode encodeDefault(Spec spec) {
        Object v = spec.defaultValue();
        return switch (spec.type()) {
            case "int" -> MAPPER.valueToTree(((Number) v).longValue());
            case "string" -> MAPPER.valueToTree((String) v);
            case "bool" -> MAPPER.valueToTree((Boolean) v);
            case "string_list" -> {
                ArrayNode arr = MAPPER.createArrayNode();
                if (v instanceof List<?> list) {
                    for (Object item : list) {
                        arr.add(String.valueOf(item));
                    }
                }
                yield arr;
            }
            default -> MAPPER.nullNode();
        };
    }

    /**
     * 对照 Go encodeForType：把任意 JSON 解码值规范化成声明类型的 JSON 编码。
     * 错误消息逐字对照（handler 把它原文当 400 响应）。
     */
    public static JsonNode encodeForType(String declared, JsonNode rawValue) {
        switch (declared) {
            case "int": {
                if (rawValue.isNumber()) {
                    double d = rawValue.asDouble();
                    if (d != Math.rint(d)) {
                        throw new IllegalArgumentException("expected integer, got " + d);
                    }
                    return MAPPER.valueToTree((long) d);
                }
                if (rawValue.isTextual()) {
                    try {
                        return MAPPER.valueToTree(Long.parseLong(rawValue.asText()));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "expected integer, got \"" + rawValue.asText() + "\"");
                    }
                }
                throw new IllegalArgumentException("expected integer, got " + goTypeName(rawValue));
            }
            case "string": {
                if (!rawValue.isTextual()) {
                    throw new IllegalArgumentException("expected string, got " + goTypeName(rawValue));
                }
                return MAPPER.valueToTree(rawValue.asText());
            }
            case "bool": {
                if (!rawValue.isBoolean()) {
                    throw new IllegalArgumentException("expected bool, got " + goTypeName(rawValue));
                }
                return MAPPER.valueToTree(rawValue.asBoolean());
            }
            case "string_list": {
                ArrayNode out = MAPPER.createArrayNode();
                if (rawValue.isArray()) {
                    for (JsonNode item : rawValue) {
                        if (!item.isTextual()) {
                            throw new IllegalArgumentException(
                                    "expected string at index " + indexOf(rawValue, item)
                                            + ", got " + goTypeName(item));
                        }
                        String s = item.asText().trim();
                        if (!s.isEmpty()) {
                            out.add(s);
                        }
                    }
                } else if (rawValue.isTextual()) {
                    for (String s : rawValue.asText().split(",", -1)) {
                        String t = s.trim();
                        if (!t.isEmpty()) {
                            out.add(t);
                        }
                    }
                } else {
                    throw new IllegalArgumentException("expected string array, got " + goTypeName(rawValue));
                }
                return out;
            }
            default:
                throw new IllegalArgumentException("unknown declared type: " + declared);
        }
    }

    private static int indexOf(JsonNode array, JsonNode item) {
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i) == item) {
                return i;
            }
        }
        return -1;
    }

    /** Go %T 的 JSON 值TypeName 对应（错误消息里出现，golden 钉住 string/int 形态）。 */
    static String goTypeName(JsonNode node) {
        if (node == null || node.isNull()) {
            return "<nil>";
        }
        if (node.isBoolean()) {
            return "bool";
        }
        if (node.isNumber()) {
            return "float64";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isArray()) {
            return "[]interface {}";
        }
        return "map[string]interface {}";
    }

    private SystemSettingRegistry() {
    }
}
