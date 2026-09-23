package com.ragagent.sandbox.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.error.BizException;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.sandbox.domain.SkillEnvVar;
import com.ragagent.sandbox.domain.SkillStatus;
import com.ragagent.sandbox.domain.TenantSandboxConfigEntity;
import com.ragagent.sandbox.domain.TenantSkillEntity;
import com.ragagent.sandbox.domain.TenantUserEnvVar;
import com.ragagent.sandbox.mapper.TenantSandboxConfigMapper;
import com.ragagent.sandbox.mapper.TenantSkillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对照 Go {@code service.UserEnvService}（internal/application/service/user_env.go，
 * 475 行的成员面子集）：<b>一个身份为自己保存的值</b>，两种作用域——配置级变量与
 * skill 声明的凭据。它与 TenantSkillService 分开，因为权威性质不同：每个方法都只从
 * 上下文推导工作区与身份、只碰以该身份为键的行，这正是端点对任何登录成员安全、
 * 而 install 与 listing 保持 Admin+ 的原因（Go 注释原文）。
 *
 * <h2>响应形状（golden 钉死）</h2>
 * <ul>
 *   <li>ListMine → 每配置一组 {@link ConfigEnvGroup}；值<b>永不</b>回显；</li>
 *   <li>source 三态：unset / workspace（声明的 value 非空）/ user（调用者自己的值，
 *       带 updated_at）——CaptureSkillEnv 的 admin 来源（workspace）不出现在本批
 *       golden（种子声明无 value）。</li>
 * </ul>
 *
 * <h2>对照 Go 的钩子等效（§3 清单第 1 条）</h2>
 * TenantUserEnvVar 的 BeforeSave/AfterFind（value 的 AES-GCM 加解密）在这里显式做：
 * 写前加密（无 key 或空值原样）、读后宽容解密（解不开置空——该行保持可列出，
 * 成员能看到需要重填，Go 注释原文）。
 */
@Service
public class UserEnvService {

    private static final Logger log = LoggerFactory.getLogger(UserEnvService.class);

    /** source 三态（Go user_env.go L19-23）。 */
    public static final String ENV_SOURCE_UNSET = "unset";
    public static final String ENV_SOURCE_WORKSPACE = "workspace";
    public static final String ENV_SOURCE_USER = "user";

    /** 对照 {@code MaxEnvValueBytes}（tenant_skill_env_declare.go）。 */
    public static final int MAX_ENV_VALUE_BYTES = 8 * 1024;

    /** 对照 {@code MaxUserEnvVarsPerScope}。 */
    public static final int MAX_USER_ENV_VARS_PER_SCOPE = 50;

    /** 对照 {@code envNamePattern}。 */
    private static final java.util.regex.Pattern ENV_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z_][A-Z0-9_]{0,127}$");

    /**
     * 对照 {@code reservedEnvNames}：第三层不是安全边界——skill 本就能在沙箱里跑
     * 任意代码；这是防"自伤式、无法诊断的故障"：填 PATH 的人会把 skill 自己 venv
     * 里的 python 顶掉（Go 注释原文）。末四项 = InjectedSandboxEnvVars()。
     */
    private static final Set<String> RESERVED_ENV_NAMES = Set.of(
            "PATH", "HOME", "USER", "SHELL", "LD_PRELOAD", "LD_LIBRARY_PATH",
            "PYTHONPATH", "PYTHONHOME", "NODE_OPTIONS",
            "WEKNORA_SKILL_OUTPUT_DIR", "WEKNORA_SESSION_INPUT_DIR",
            "WEKNORA_SKILL_HISTORY_ROOT", "WEKNORA_SKILL_DIR", "NODE_PATH");

    /** 对照 {@code reservedEnvPrefix}。 */
    private static final String RESERVED_ENV_PREFIX = "WEKNORA_SKILL_";

    private final TenantSkillMapper skills;
    private final TenantSandboxConfigMapper configs;
    private final CryptoService cryptoService;

    public UserEnvService(TenantSkillMapper skills, TenantSandboxConfigMapper configs,
            CryptoService cryptoService) {
        this.skills = skills;
        this.configs = configs;
        this.cryptoService = cryptoService;
    }

    // ── 响应投影（EnvVarView / SkillEnvGroup / ConfigEnvGroup） ───────────

    /**
     * 对照 {@code EnvVarView}：刻意没有 value 字段——这个视图的存在意义是让成员
     * 知道还缺什么，存下的值永不回读（Go 注释原文）。updated_at 只在调用者自己的
     * 值上有；工作区值没有逐变量时间戳可报。
     */
    @JsonPropertyOrder({"name", "description", "required", "source", "updated_at"})
    public record EnvVarView(
            @JsonProperty("name") String name,
            @JsonProperty("description") @JsonInclude(JsonInclude.Include.NON_EMPTY) String description,
            @JsonProperty("required") @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean required,
            @JsonProperty("source") String source,
            @JsonProperty("updated_at") @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonSerialize(using = GoTimeSerializer.class) OffsetDateTime updatedAt) {
    }

    /** 对照 {@code SkillEnvGroup}：只带 skill 的身份（名字与 SKILL.md 一句话）。 */
    @JsonPropertyOrder({"skill_id", "skill_name", "description", "vars"})
    public record SkillEnvGroup(
            @JsonProperty("skill_id") String skillId,
            @JsonProperty("skill_name") String skillName,
            @JsonProperty("description") @JsonInclude(JsonInclude.Include.NON_EMPTY) String description,
            @JsonProperty("vars") List<EnvVarView> vars) {
    }

    /** 对照 {@code ConfigEnvGroup}：一个沙箱配置 = 调用者的配置级变量 + 已装 skill 的声明。 */
    @JsonPropertyOrder({"sandbox_config_id", "sandbox_config_name", "description", "vars", "skills"})
    public record ConfigEnvGroup(
            @JsonProperty("sandbox_config_id") String sandboxConfigId,
            @JsonProperty("sandbox_config_name") String sandboxConfigName,
            @JsonProperty("description") @JsonInclude(JsonInclude.Include.NON_EMPTY) String description,
            @JsonProperty("vars") List<EnvVarView> vars,
            @JsonProperty("skills") List<SkillEnvGroup> skills) {
    }

    // ── caller（user_env.go L81-93） ─────────────────────────────────────

    /**
     * 对照 {@code caller}：主体缺失是错误而不是默认——回落到别的身份会让一个成员
     * 读/覆盖另一个成员的值（Go 注释原文）。
     */
    private record Caller(long tenantId, TenantContext.Principal principal) {
    }

    private Caller caller() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            throw BizException.unauthorized(
                    "workspace context is required to access environment variables");
        }
        TenantContext.Principal principal = TenantContext.currentPrincipal();
        if (principal == null) {
            throw BizException.unauthorized(
                    "principal context is required to access environment variables");
        }
        // 对照 Principal.Normalize
        return new Caller(tenantId, new TenantContext.Principal(
                principal.type() == null ? "" : principal.type().trim(),
                principal.id() == null ? "" : principal.id().trim()));
    }

    // ── ListMine（user_env.go L105-180） ─────────────────────────────────

    /**
     * 对照 {@code ListMine}：配置即使什么都不带也列出——配置级编辑器必须可达；
     * skill 只在声明过东西时列出——空声明没的可问（Go 注释原文）。只列启用且
     * ready 的 skill：禁用或半安装的 skill 永远不会交给 agent。
     */
    public List<ConfigEnvGroup> listMine() {
        Caller me = caller();
        List<TenantSandboxConfigEntity> configRows = configs.listByTenant(me.tenantId());
        List<TenantSkillEntity> skillRows = skills.listSkillsByTenant(me.tenantId());

        Map<String, List<TenantSkillEntity>> skillsByConfig = new HashMap<>();
        for (TenantSkillEntity row : skillRows) {
            if (skillAcceptsUserEnvs(row) && row.getEnvs() != null && !row.getEnvs().isEmpty()) {
                skillsByConfig.computeIfAbsent(row.getSandboxConfigId(), k -> new ArrayList<>())
                        .add(row);
            }
        }

        List<ConfigEnvGroup> groups = new ArrayList<>(configRows.size());
        for (TenantSandboxConfigEntity cfg : configRows) {
            if (cfg == null) {
                continue;
            }
            List<TenantUserEnvVar> owned = decrypt(
                    skills.listUserEnvVarsByConfig(me.tenantId(), me.principal().type(),
                            me.principal().id(), cfg.getId()));
            Map<String, Map<String, TenantUserEnvVar>> mineBySkill = new HashMap<>();
            for (TenantUserEnvVar row : owned) {
                if (row == null) {
                    continue;
                }
                mineBySkill.computeIfAbsent(row.getSkillId(), k -> new HashMap<>())
                        .put(row.getName(), row);
            }

            // 两个列表都初始化，让它们序列化成 [] 而非 null：设置页读 .length（Go 注释原文）
            ConfigEnvGroup group = new ConfigEnvGroup(cfg.getId(), cfg.getName(),
                    cfg.getDescription(),
                    configWideViews(mineBySkill.get("")),
                    new ArrayList<>());
            List<TenantSkillEntity> installed =
                    skillsByConfig.getOrDefault(cfg.getId(), List.of());
            for (TenantSkillEntity row : installed) {
                group.skills().add(new SkillEnvGroup(row.getId(), row.getName(),
                        row.getDescription(),
                        declaredViews(row.getEnvs(), mineBySkill.get(row.getId()))));
            }
            // 仓储按创建序给 skill；按名字排序让一次批量安装两行时页面也稳定（Go 注释原文）
            group.skills().sort((a, b) -> {
                if (!a.skillName().equals(b.skillName())) {
                    return a.skillName().compareTo(b.skillName());
                }
                return a.skillId().compareTo(b.skillId());
            });
            groups.add(group);
        }
        groups.sort((a, b) -> {
            if (!a.sandboxConfigName().equals(b.sandboxConfigName())) {
                return a.sandboxConfigName().compareTo(b.sandboxConfigName());
            }
            return a.sandboxConfigId().compareTo(b.sandboxConfigId());
        });
        return groups;
    }

    /** 对照 {@code configWideViews}：配置级变量没有声明背书，每个都天然属于调用者。 */
    private List<EnvVarView> configWideViews(Map<String, TenantUserEnvVar> mine) {
        List<EnvVarView> views = new ArrayList<>(mine == null ? 0 : mine.size());
        if (mine != null) {
            for (TenantUserEnvVar row : mine.values()) {
                EnvVarView view = new EnvVarView(row.getName(), "", false,
                        ENV_SOURCE_UNSET, null);
                views.add(applyOwnValue(view, row));
            }
        }
        views.sort((a, b) -> a.name().compareTo(b.name()));
        return views;
    }

    /**
     * 对照 {@code declaredViews}：把调用者自己的值叠到声明上，顺序与 resolver 注入
     * 的一致——成员看到的即运行得到的（Go 注释原文）。
     */
    private List<EnvVarView> declaredViews(com.ragagent.sandbox.domain.SkillEnvVars declared,
            Map<String, TenantUserEnvVar> mine) {
        List<EnvVarView> views = new ArrayList<>(
                declared == null ? 0 : declared.size());
        if (declared == null) {
            return views;
        }
        for (SkillEnvVar entry : declared) {
            EnvVarView view = new EnvVarView(entry.getName(), entry.getDescription(),
                    entry.isRequired(), ENV_SOURCE_UNSET, null);
            if (!entry.getValue().isEmpty()) {
                view = new EnvVarView(view.name(), view.description(), view.required(),
                        ENV_SOURCE_WORKSPACE, null);
            }
            views.add(applyOwnValue(view, mine == null ? null : mine.get(entry.getName())));
        }
        return views;
    }

    /**
     * 对照 {@code applyOwnValue}：value 回来是空的行保持原 source——那只发生在存量
     * 密钥解不开的时候，报成已设置会藏起成员唯一需要做的事（Go 注释原文）。
     */
    private EnvVarView applyOwnValue(EnvVarView view, TenantUserEnvVar own) {
        if (own == null || own.getValue().isEmpty()) {
            return view;
        }
        return new EnvVarView(view.name(), view.description(), view.required(),
                ENV_SOURCE_USER, own.getUpdatedAt());
    }

    // ── 写面（SetMineSkill / DeleteMineSkill / SetMineSandbox / DeleteMineSandbox） ──

    /** 对照 {@code SetMineSkill}：声明之外的名字拒绝——自由名字属于配置级作用域。 */
    public void setMineSkill(String skillId, String name, String value) {
        Caller me = caller();
        TenantSkillEntity skill = findVisibleSkill(me.tenantId(), trim(skillId));
        String trimmed = trim(name);
        if (skill.getEnvs() == null || skill.getEnvs().get(trimmed) == null) {
            throw BizException.badRequest(
                    "skill " + skill.getName() + " does not declare " + trimmed);
        }
        upsertMine(me, skill.getSandboxConfigId(), skill.getId(), trimmed, value);
    }

    /**
     * 对照 {@code DeleteMineSkill}：刻意不要求 skill 可见——删除本就限于该身份自己的行，
     * 且管理员禁用 skill 之后撤销凭据必须仍然可用，那恰恰是最想删的时刻（Go 注释原文）。
     */
    public void deleteMineSkill(String skillId, String name) {
        Caller me = caller();
        String trimmedSkill = trim(skillId);
        String trimmedName = trim(name);
        if (trimmedSkill.isEmpty() || trimmedName.isEmpty()) {
            throw BizException.badRequest("skill_id and name are required");
        }
        // config 从调用者自己的行上读，而不是从请求上读：删除就无法瞄准别的配置
        for (TenantSkillEntity row : skills.listSkillsByTenant(me.tenantId())) {
            if (row != null && row.getId().equals(trimmedSkill)) {
                deleteMine(me, row.getSandboxConfigId(), trimmedSkill, trimmedName);
                return;
            }
        }
        throw envVarNotFound();
    }

    /** 对照 {@code SetMineSandbox}：名字自由格式——这是管理员沙箱 env_vars 的成员侧对应物。 */
    public void setMineSandbox(String configId, String name, String value) {
        Caller me = caller();
        String resolved = visibleConfigID(me, configId);
        upsertMine(me, resolved, "", trim(name), value);
    }

    /** 对照 {@code DeleteMineSandbox}。 */
    public void deleteMineSandbox(String configId, String name) {
        Caller me = caller();
        String trimmedConfig = trim(configId);
        String trimmedName = trim(name);
        if (trimmedConfig.isEmpty() || trimmedName.isEmpty()) {
            throw BizException.badRequest("sandbox_config_id and name are required");
        }
        deleteMine(me, trimmedConfig, "", trimmedName);
    }

    /** 对照 {@code upsertMine}：两种作用域共用的唯一写路径——同校验、同配额、同行形状。 */
    private void upsertMine(Caller me, String configId, String skillId, String name,
            String value) {
        String nameError = validateUserEnvName(name);
        if (nameError != null) {
            throw BizException.badRequest(nameError);
        }
        if (value == null || value.isEmpty()) {
            throw BizException.badRequest(
                    "a value is required; delete " + name + " to clear it");
        }
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ENV_VALUE_BYTES) {
            throw BizException.badRequest("value of " + name + " cannot exceed "
                    + MAX_ENV_VALUE_BYTES + " bytes");
        }

        List<TenantUserEnvVar> owned = decrypt(skills.listUserEnvVars(me.tenantId(),
                me.principal().type(), me.principal().id(), configId, skillId));
        // 配额限制一个身份在一个作用域里保留多少名字，覆盖不在其列：
        // 已到上限的用户仍必须能轮换自己持有的密钥（Go 注释原文）
        boolean overwrite = false;
        for (TenantUserEnvVar e : owned) {
            if (e != null && e.getName().equals(name)) {
                overwrite = true;
                break;
            }
        }
        if (!overwrite && owned.size() >= MAX_USER_ENV_VARS_PER_SCOPE) {
            throw BizException.badRequest("you can keep at most "
                    + MAX_USER_ENV_VARS_PER_SCOPE + " environment variables here");
        }

        OffsetDateTime now = OffsetDateTime.now();
        String stored = encrypt(value, name);
        if (overwrite) {
            skills.updateUserEnvVarValue(me.tenantId(), me.principal().type(),
                    me.principal().id(), configId, skillId, name, stored, now);
            return;
        }
        TenantUserEnvVar row = new TenantUserEnvVar();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(me.tenantId());
        row.setPrincipalType(me.principal().type());
        row.setPrincipalId(me.principal().id());
        row.setSandboxConfigId(configId);
        row.setSkillId(skillId);
        row.setName(name);
        row.setValue(stored);
        try {
            skills.insertUserEnvVar(row, now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 对照 OnConflict DoUpdates：并发首插时退回 UPDATE 臂
            skills.updateUserEnvVarValue(me.tenantId(), me.principal().type(),
                    me.principal().id(), configId, skillId, name, stored, now);
        }
    }

    /** 对照 DeleteUserEnvVar 的 0 行 → ErrEnvVarNotFound（handler 升 404）。 */
    private void deleteMine(Caller me, String configId, String skillId, String name) {
        int affected = skills.deleteUserEnvVar(me.tenantId(), me.principal().type(),
                me.principal().id(), configId, skillId, name);
        if (affected == 0) {
            throw envVarNotFound();
        }
    }

    /**
     * 对照 {@code types.ErrEnvVarNotFound} 的 handler 映射
     * （me_env_var.go respondEnvVarError：nothing to delete 是 404）。sentinel 的
     * 分类决策在服务层做出，与 Go 的观察行为一致（404 "this environment variable is not set"）。
     */
    private static BizException envVarNotFound() {
        return BizException.notFound("this environment variable is not set");
    }

    // ── CaptureSkillEnv（user_env.go L240-293）：随 agent 执行面（波 4）接线 ──
    //
    // 对照签名保留在注释里：CaptureSkillEnv(ctx, configID, skillName, pairs)——
    // 只写已声明的名字、只填空白（rotation 属于设置页）；调用方是 skill 执行管线，
    // 本批不可达，不翻。

    // ── 解析（visibleConfigID / findVisibleSkill / skillAcceptsUserEnvs） ──

    /**
     * 对照 {@code visibleConfigID}：把沙箱配置对回调用者自己的工作区。否则别的
     * 工作区的 ID 会被接受并写出行，那个工作区的运行随后会读到它（Go 注释原文）。
     */
    private String visibleConfigID(Caller me, String configId) {
        String trimmed = trim(configId);
        if (trimmed.isEmpty()) {
            throw BizException.badRequest("sandbox_config_id is required");
        }
        TenantSandboxConfigEntity cfg = configs.getByID(me.tenantId(), trimmed);
        if (cfg == null) {
            throw BizException.badRequest(
                    "sandbox config " + trimmed + " is not available in this workspace");
        }
        return cfg.getId();
    }

    /**
     * 对照 {@code findVisibleSkill}：把 skill ID 对回调用者自己的工作区（跨配置——
     * 成员面是关于人的，不是关于某一个沙箱配置）。"不是你的"/"未安装"/"不可用"
     * 共用一条消息：是哪一种本身就是披露（Go 注释原文）。
     */
    private TenantSkillEntity findVisibleSkill(long tenantId, String skillId) {
        if (skillId.isEmpty()) {
            throw BizException.badRequest("skill_id is required");
        }
        for (TenantSkillEntity row : skills.listSkillsByTenant(tenantId)) {
            if (row != null && row.getId().equals(skillId) && skillAcceptsUserEnvs(row)) {
                return row;
            }
        }
        throw BizException.badRequest("skill " + skillId + " is not available in this workspace");
    }

    /** 对照 {@code skillAcceptsUserEnvs}：agent 真能运行的 skill 才值得向成员要凭据。 */
    static boolean skillAcceptsUserEnvs(TenantSkillEntity row) {
        return row != null && row.isEnabled() && SkillStatus.READY.equals(row.getStatus());
    }

    // ── 校验与加解密（tenant_skill_env_declare.go / tenant_env_vars.go 钩子） ──

    /** 对照 {@code validateUserEnvName}：格式层 + 保留名层（bundle 匹配层只属安装声明）。 */
    static String validateUserEnvName(String name) {
        if (name == null || !ENV_NAME_PATTERN.matcher(name).matches()) {
            return "environment variable name \"" + name
                    + "\" must be UPPER_SNAKE_CASE and at most 128 characters";
        }
        if (RESERVED_ENV_NAMES.contains(name) || name.startsWith(RESERVED_ENV_PREFIX)) {
            return "environment variable name \"" + name + "\" is reserved by the sandbox";
        }
        return null;
    }

    /** 对照 Go {@code encryptEnvValue}：无 key 或空值原样；idempotent（enc:v1: 前缀不重加密）。 */
    private String encrypt(String value, String label) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        byte[] key = cryptoService.getAESKey();
        if (key == null) {
            return value;
        }
        try {
            return cryptoService.encryptAESGCM(value, key);
        } catch (RuntimeException e) {
            // 加密失败中止写入：把明文写进可查询的列是静默泄漏（Go 注释原文）
            throw new IllegalStateException("encrypt tenant_user_env_vars." + label
                    + ": " + e.getMessage(), e);
        }
    }

    /** 对照 AfterFind → decryptEnvValue：解不开置空并记日志，行保持可列出。 */
    /**
     * 凭据解析器的一次作用域读（对照 Go userEnvReader.ListUserEnvVars + 解密后
     * 返回）：给 {@link UserEnvResolver} 的 overlayMine 用——值按调用方隔离，
     * 解密失败的条目按 unset 处理。
     */
    public List<TenantUserEnvVar> listDecryptedForResolver(long tenantId,
            TenantContext.Principal principal, String configId, String skillId) {
        return decrypt(skills.listUserEnvVars(tenantId, principal.type(), principal.id(),
                configId, skillId));
    }

    private List<TenantUserEnvVar> decrypt(List<TenantUserEnvVar> rows) {        for (TenantUserEnvVar row : rows) {
            String stored = row.getValue();
            if (stored == null || stored.isEmpty()) {
                row.setValue("");
                continue;
            }
            CryptoService.LenientResult r = cryptoService.decryptStoredSecretLenient(stored);
            if (!r.ok()) {
                log.warn("[crypto] tenant_user_env_vars.{}: decrypt failed "
                        + "(SYSTEM_AES_KEY missing/rotated?), treating as unset", row.getName());
            }
            row.setValue(r.plaintext() == null ? "" : r.plaintext());
        }
        return rows;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
