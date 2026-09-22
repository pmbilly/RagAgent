package com.ragagent.system.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.system.domain.SystemSetting;
import com.ragagent.system.mapper.SystemSettingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 系统设置服务（对照 Go internal/application/service/system_setting.go 的 Lite 模式）。
 *
 * <p><b>已知差异（Lite 取舍，Javadoc 即契约）</b>：Go 有 "preload + Redis pubsub 失效"
 * 的跨副本缓存；Java 侧（进程内队列同族取舍）是<b>单实例</b>语义——每请求直读 DB，
 * 无缓存无 pubsub。行为差异只在多副本部署下可见（本副本 UI 改动不再即时广播给对端），
 * 单实例的解析优先级与响应形态与 Go 逐字段一致。</p>
 *
 * <p>三层解析（逐字对照 Go resolveRaw/GetXxx）：DB 行 → ENV → default；DB 读失败
 * 降级到 ENV/default（记 WARN，不 500）。已知"引导默认行"（last_modified_by 为空且值
 * ==registry 默认）在<b>读取</b>时折叠回 ENV/default（isBootstrapDefaultRow）。</p>
 *
 * <p>副作用桥（dispatchSideEffects）：ssrf.whitelist 更新后推给
 * {@link SsrfGuard#reloadWhitelist(String)}（含 SSRF_WHITELIST_EXTRA 合并，对照
 * applySSRFWhitelist）。model.max_concurrency / sandbox.docker_enabled 的桥随
 * 阶段 7 / 波 3 的消费方接线（Go 的 limiter.SetGlobalLimit / sandbox.SetDockerBackendEnabled）。</p>
 *
 * <p><b>启动预载</b>（对照 Go preload 的 initial sync，走查补翻）：应用就绪后把
 * DB 的 ssrf.whitelist 推给 SsrfGuard——否则重启后 DB 白名单静默失效（guard 静态
 * 初始化只读 env），单实例下也可观测（走查实案：UI 存了 198.18.0.0/15，重启后
 * dashscope fake-IP 又被拦）。读失败降级 env-only（WARN，不阻断启动）。</p>
 */
@Service
public class SystemSettingService {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SystemSettingMapper mapper;
    private final UserMapper userMapper;
    private final AuditLogService auditService;
    /** 白名单是进程级静态——注入任意实例即可（reloadWhitelist 改的是静态字段）。 */
    private final SsrfGuard ssrfGuard;
    /** 对照 Go 的 cfg.Auth.RegistrationMode 兜底（dev 未配置 → self_serve）。 */
    @Value("${weknora.auth.registration-mode:}")
    private String configuredRegistrationMode;

    public SystemSettingService(SystemSettingMapper mapper,
                                UserMapper userMapper,
                                AuditLogService auditService,
                                SsrfGuard ssrfGuard) {
        this.mapper = mapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.ssrfGuard = ssrfGuard;
    }

    // ── 三层解析（业务侧 GetXxx） ─────────────────────────────────────────

    /** 对照 resolveRaw：DB 行（非引导默认）→ 有值；否则 ENV/default。 */
    private record Resolved(JsonNode raw, boolean fromDB) {
    }

    private Resolved resolveRaw(String key) {
        SystemSettingRegistry.Spec spec = SystemSettingRegistry.get(key);
        SystemSetting row = selectByKey(key);
        if (row != null) {
            if (spec != null && isBootstrapDefaultRow(row, spec)) {
                return new Resolved(null, false);
            }
            return new Resolved(row.getValue(), true);
        }
        return new Resolved(null, false);
    }

    /** 对照 GetInt：DB（容忍 "42" 字符串行）→ ENV → def。任何错误路径回落 def。 */
    public long getInt(String key, String envName, long def) {
        Resolved r = resolveRaw(key);
        if (r.fromDB()) {
            JsonNode raw = r.raw();
            if (raw != null && raw.isNumber()) {
                return raw.asLong();
            }
            if (raw != null && raw.isTextual()) {
                try {
                    return Long.parseLong(raw.asText());
                } catch (NumberFormatException ignored) {
                    // fall through to env/default
                }
            }
            log.warn("[system_settings] {}: cannot parse as int, falling back", key);
        }
        if (envName != null && !envName.isEmpty()) {
            String v = System.getenv(envName);
            if (v != null && !v.isEmpty()) {
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return def;
    }

    /** 对照 GetString。 */
    public String getString(String key, String envName, String def) {
        Resolved r = resolveRaw(key);
        if (r.fromDB() && r.raw() != null && r.raw().isTextual()) {
            return r.raw().asText();
        }
        if (envName != null && !envName.isEmpty()) {
            String v = System.getenv(envName);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return def;
    }

    /** 对照 GetBool（ENV 走 Go strconv.ParseBool 语义：1/t/T/TRUE/true/True 等）。 */
    public boolean getBool(String key, String envName, boolean def) {
        Resolved r = resolveRaw(key);
        if (r.fromDB() && r.raw() != null && r.raw().isBoolean()) {
            return r.raw().asBoolean();
        }
        if (envName != null && !envName.isEmpty()) {
            String v = System.getenv(envName);
            if (v != null && !v.isEmpty()) {
                Boolean parsed = goParseBool(v);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return def;
    }

    /** 对照 GetStringList：ENV 按逗号拆分 + trim + 丢弃空项；返回恒非 null。 */
    public List<String> getStringList(String key, String envName, List<String> def) {
        Resolved r = resolveRaw(key);
        if (r.fromDB() && r.raw() != null && r.raw().isArray()) {
            List<String> out = new ArrayList<>();
            r.raw().forEach(n -> out.add(n.asText()));
            return out;
        }
        if (envName != null && !envName.isEmpty()) {
            String raw = System.getenv(envName);
            if (raw != null && !raw.isEmpty()) {
                List<String> out = new ArrayList<>();
                for (String entry : raw.split(",")) {
                    String t = entry.trim();
                    if (!t.isEmpty()) {
                        out.add(t);
                    }
                }
                return out;
            }
        }
        return def == null ? new ArrayList<>() : def;
    }

    /** Go strconv.ParseBool 的可接受集合。 */
    private static Boolean goParseBool(String v) {
        return switch (v) {
            case "1", "t", "T", "true", "TRUE", "True" -> Boolean.TRUE;
            case "0", "f", "F", "false", "FALSE", "False" -> Boolean.FALSE;
            default -> null;
        };
    }

    // ── 管理面（List / Get / Update / Reset） ─────────────────────────────

    /**
     * 对照 List：持久行 + registry 虚拟行（按 key 排序），退役键剔除，
     * 额外（未知）行按 key 排序缀尾。
     */
    public List<SystemSetting> list() {
        Map<String, SystemSetting> byKey = new ConcurrentHashMap<>();
        for (SystemSetting row : mapper.selectList(new LambdaQueryWrapper<>())) {
            byKey.put(row.getKey(), row);
        }
        byKey.remove(SystemSettingRegistry.RETIRED_KEY);

        List<SystemSetting> out = new ArrayList<>();
        for (String key : SystemSettingRegistry.keys()) {
            SystemSettingRegistry.Spec spec = SystemSettingRegistry.get(key);
            SystemSetting row = byKey.remove(key);
            if (row != null) {
                row.setEnumOptions(spec.enumOptions().isEmpty() ? null : spec.enumOptions());
                if (isBootstrapDefaultRow(row, spec)) {
                    row.setValue(fallbackJsonForSpec(key, spec));
                }
                out.add(row);
            } else {
                out.add(virtualSetting(key, spec));
            }
        }
        List<String> extraKeys = new ArrayList<>(byKey.keySet());
        java.util.Collections.sort(extraKeys);
        for (String key : extraKeys) {
            out.add(byKey.get(key));
        }
        enrichModifiedBy(out);
        return out;
    }

    /**
     * 对照 Get：未知 key → 抛 IllegalStateException（handler 折叠成 400，
     * 消息 = {@code unknown setting key "x"}）；行缺失 → 虚拟行（nil 行语义在
     * handler 里就是 200 虚拟行，Go 的 (nil,nil) 分支只在 DB 错误时出现）。
     */
    public SystemSetting get(String key) {
        SystemSettingRegistry.Spec spec = SystemSettingRegistry.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("unknown setting key \"" + key + "\"");
        }
        SystemSetting row = selectByKey(key);
        if (row != null) {
            row.setEnumOptions(spec.enumOptions().isEmpty() ? null : spec.enumOptions());
            if (isBootstrapDefaultRow(row, spec)) {
                row.setValue(fallbackJsonForSpec(key, spec));
            }
            return row;
        }
        return virtualSetting(key, spec);
    }

    /**
     * 对照 Update：类型/枚举/结构校验 → upsert → 审计。错误消息 = Go 原文
     * （handler 原文当 400 响应）。
     */
    public SystemSetting update(String key, JsonNode rawValue) {
        SystemSettingRegistry.Spec spec = SystemSettingRegistry.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("unknown setting key \"" + key + "\"");
        }
        JsonNode encoded;
        try {
            encoded = SystemSettingRegistry.encodeForType(spec.type(), rawValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid value for \"" + key + "\" (expected " + spec.type() + "): "
                            + e.getMessage());
        }
        if (!spec.enumOptions().isEmpty() && "string".equals(spec.type())) {
            String str = rawValue.isTextual() ? rawValue.asText() : null;
            if (str == null || !spec.enumOptions().contains(str)) {
                throw new IllegalArgumentException("invalid value for \"" + key + "\": \""
                        + (str == null ? "" : str) + "\" not in " + goStringList(spec.enumOptions()));
            }
        }
        validateRegistryEntry(key, rawValue);

        SystemSetting prev = selectByKey(key);
        String category;
        String description;
        boolean isSecret = false;
        boolean requiresRestart;
        if (prev != null) {
            category = prev.getCategory();
            description = prev.getDescription();
            isSecret = prev.isIsSecret();
            requiresRestart = prev.isRequiresRestart();
        } else {
            category = spec.category().isEmpty() ? SystemSettingRegistry.GENERAL_CATEGORY : spec.category();
            description = spec.description();
            requiresRestart = spec.requiresRestart();
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SystemSetting row = prev != null ? prev : new SystemSetting();
        row.setKey(key);
        row.setValue(encoded);
        row.setValueType(spec.type());
        row.setCategory(category);
        row.setDescription(description);
        row.setIsSecret(isSecret);
        row.setRequiresRestart(requiresRestart);
        row.setLastModifiedBy(auditActor());
        row.setUpdatedAt(now);
        if (prev == null) {
            row.setCreatedAt(now);
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }

        SystemSetting persisted = selectByKey(key);
        if (persisted == null) {
            persisted = row;
        }
        persisted.setEnumOptions(spec.enumOptions().isEmpty() ? null : spec.enumOptions());
        enrichModifiedBy(List.of(persisted));

        dispatchSideEffects(key);
        emitChangeAudit(key, spec.type(), prev == null ? null : prev.getValue(), encoded);
        return persisted;
    }

    /** 对照 Reset：未知 key 仍 400；幂等成功；仅真删除写审计。 */
    public void reset(String key) {
        SystemSettingRegistry.Spec spec = SystemSettingRegistry.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("unknown setting key \"" + key + "\"");
        }
        SystemSetting prev = selectByKey(key);
        int deleted = mapper.delete(new LambdaQueryWrapper<SystemSetting>()
                .eq(SystemSetting::getKey, key));
        boolean rowDeleted = deleted > 0;
        dispatchSideEffects(key);
        if (rowDeleted && prev != null) {
            emitChangeAudit(key, spec.type(), prev.getValue(), null);
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private SystemSetting selectByKey(String key) {
        return mapper.selectOne(new LambdaQueryWrapper<SystemSetting>()
                .eq(SystemSetting::getKey, key)
                .last("LIMIT 1"));
    }

    /** 对照 virtualSetting：id=0 + Go 零值时间（实体字段默认值即零值）。 */
    private SystemSetting virtualSetting(String key, SystemSettingRegistry.Spec spec) {
        SystemSetting row = new SystemSetting();
        row.setId(0L);
        row.setKey(key);
        row.setValue(fallbackJsonForSpec(key, spec));
        row.setValueType(spec.type());
        row.setCategory(spec.category().isEmpty() ? SystemSettingRegistry.GENERAL_CATEGORY : spec.category());
        row.setDescription(spec.description());
        row.setIsSecret(false);
        row.setRequiresRestart(spec.requiresRestart());
        row.setLastModifiedBy("");
        row.setEnumOptions(spec.enumOptions().isEmpty() ? null : spec.enumOptions());
        return row;
    }

    /**
     * 对照 fallbackJSONForSpec：ENV → (auth.registration_mode 的 cfg 兜底) → 内置默认。
     * ENV 编码失败静默落默认（与 Go 逐分支一致）。
     */
    private JsonNode fallbackJsonForSpec(String key, SystemSettingRegistry.Spec spec) {
        if (!spec.envName().isEmpty()) {
            String raw = System.getenv(spec.envName());
            if (raw != null && !raw.trim().isEmpty()) {
                try {
                    return switch (spec.type()) {
                        case "int" -> SystemSettingRegistry.encodeForType("int", new TextNode(raw.trim()));
                        case "string" -> SystemSettingRegistry.encodeForType("string", new TextNode(raw));
                        case "bool" -> SystemSettingRegistry.encodeForType("bool", new TextNode(raw));
                        case "string_list" -> SystemSettingRegistry.encodeForType("string_list", new TextNode(raw));
                        default -> null;
                    };
                } catch (IllegalArgumentException ignored) {
                    // fall through to config/default（Go 同样忽略编码失败）
                }
            }
        }
        if ("auth.registration_mode".equals(key)) {
            String mode = configuredRegistrationMode == null || configuredRegistrationMode.isBlank()
                    ? SystemSettingRegistry.AUTH_REGISTRATION_MODE_DEFAULT
                    : configuredRegistrationMode.trim();
            return MAPPER.valueToTree(mode);
        }
        return SystemSettingRegistry.encodeDefault(spec);
    }

    /** 对照 isBootstrapDefaultRow：无操作者（last_modified_by 空）且值 ==registry 默认。 */
    private boolean isBootstrapDefaultRow(SystemSetting row, SystemSettingRegistry.Spec spec) {
        if (row == null || !row.getLastModifiedBy().trim().isEmpty()) {
            return false;
        }
        JsonNode def;
        try {
            def = SystemSettingRegistry.encodeDefault(spec);
        } catch (RuntimeException e) {
            return false;
        }
        return jsonEquals(row.getValue(), def);
    }

    /** Go json.Equal(Compact(a), Compact(b))——JSON 值等价比较。 */
    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.equals(b);
    }

    /**
     * 对照 Go preload 的 initial sync（system_setting.go L405）：应用就绪后把 DB 的
     * ssrf.whitelist 推给 SsrfGuard。走查实案：UI 保存的白名单在重启后静默失效
     * （guard 静态初始化只读 env），单实例即可观测。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void applyWhitelistOnStartup() {
        try {
            dispatchSideEffects("ssrf.whitelist");
        } catch (RuntimeException e) {
            log.warn("startup ssrf.whitelist apply failed, env-only fallback: {}", e.toString());
        }
    }

    /** 对照 dispatchSideEffects：ssrf.whitelist 已接线；其余桥随消费方模块接线。 */
    private void dispatchSideEffects(String changedKey) {        if ("ssrf.whitelist".equals(changedKey)) {
            List<String> list = getStringList("ssrf.whitelist", "SSRF_WHITELIST", new ArrayList<>());
            String primary = String.join(",", list);
            String extra = System.getenv("SSRF_WHITELIST_EXTRA");
            String merged = primary;
            if (extra != null && !extra.trim().isEmpty()) {
                merged = merged.isEmpty() ? extra : merged + "," + extra;
            }
            ssrfGuard.reloadWhitelist(merged);
        }
    }

    /** 对照 emitChangeAudit：tenant_id=0 + old_value/new_value（RawMessage 直嵌）。 */
    private void emitChangeAudit(String key, String valueType, JsonNode oldValue, JsonNode newValue) {
        if (auditService == null) {
            return;
        }
        var details = MAPPER.createObjectNode();
        details.put("key", key);
        details.put("value_type", valueType);
        details.set("old_value", oldValue == null ? MAPPER.nullNode() : oldValue);
        details.set("new_value", newValue == null ? MAPPER.nullNode() : newValue);
        AuditLog entry = new AuditLog();
        entry.setTenantId(0L);
        entry.setActorUserId(auditActor());
        entry.setActorRole("system_admin");
        entry.setAction(AuditAction.SYSTEM_SETTING_CHANGED);
        entry.setTargetType("system_setting");
        entry.setTargetId(key);
        entry.setOutcome(AuditOutcome.SUCCESS);
        entry.setDetails(details);
        try {
            auditService.logBestEffort(entry);
        } catch (RuntimeException e) {
            log.warn("[system_settings] audit write failed (ignored): {}", e.toString());
        }
    }

    /** 对照 enrichSettingsModifiedBy：批量解析显示名（username → email 回落；失败静默）。 */
    private void enrichModifiedBy(List<SystemSetting> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, String> nameById = new ConcurrentHashMap<>();
        for (SystemSetting r : rows) {
            String id = r.getLastModifiedBy().trim();
            if (!id.isEmpty()) {
                nameById.put(id, "");
            }
        }
        if (nameById.isEmpty()) {
            return;
        }
        for (SystemSetting r : rows) {
            String id = r.getLastModifiedBy();
            if (id.trim().isEmpty() || nameById.get(id) == null) {
                continue;
            }
            if (!nameById.get(id).isEmpty()) {
                continue;
            }
            User u = userMapper.selectById(id);
            if (u != null) {
                if (!u.getUsername().trim().isEmpty()) {
                    nameById.put(id, u.getUsername());
                } else if (!u.getEmail().trim().isEmpty()) {
                    nameById.put(id, u.getEmail());
                }
            }
        }
        for (SystemSetting r : rows) {
            String name = nameById.get(r.getLastModifiedBy());
            if (name != null && !name.isEmpty()) {
                r.setLastModifiedByName(name);
            }
        }
    }

    /** 对照 auditActor：ctx 里的 UserID（系统管理员自己）。 */
    private static String auditActor() {
        String uid = TenantContext.currentUserId();
        return uid == null ? "" : uid;
    }

    /** Go fmt 的 %v 对 []string：[a b c]。 */
    private static String goStringList(List<String> values) {
        return "[" + String.join(" ", values) + "]";
    }

    /** 对照 validateRegistryEntry 的 asynq 正整数 + ssrf 白名单结构校验。 */
    private static void validateRegistryEntry(String key, JsonNode rawValue) {
        if (key.startsWith("asynq.") && key.endsWith("_concurrency")) {
            long n;
            try {
                if (rawValue.isNumber()) {
                    double d = rawValue.asDouble();
                    if (d != Math.rint(d)) {
                        throw new IllegalArgumentException("expected integer value");
                    }
                    n = (long) d;
                } else if (rawValue.isTextual()) {
                    n = Long.parseLong(rawValue.asText());
                } else {
                    throw new IllegalArgumentException(
                            "expected integer, got " + SystemSettingRegistry.goTypeName(rawValue));
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "expected integer, got \"" + rawValue.asText() + "\"");
            }
            if (n < 1) {
                throw new IllegalArgumentException("concurrency must be at least 1");
            }
        }
        // ssrf.whitelist 的条目结构校验（ValidateSSRFWhitelistEntries）随 SsrfGuard 对齐：
        // 拒绝空串/非法 CIDR——当前 SsrfGuard.reloadWhitelist 接受任意条目，先与 Go 的
        // "校验失败 400" 行为对齐在 encodeForType 的 string_list 归一（trim+去空）层面。
    }
}
