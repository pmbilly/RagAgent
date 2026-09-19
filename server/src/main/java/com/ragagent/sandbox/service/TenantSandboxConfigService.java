package com.ragagent.sandbox.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.error.BizException;
import com.ragagent.sandbox.domain.CubeSandboxConfig;
import com.ragagent.sandbox.domain.DockerSandboxConfig;
import com.ragagent.sandbox.domain.E2BSandboxConfig;
import com.ragagent.sandbox.domain.SandboxConfigRedaction;
import com.ragagent.sandbox.domain.SandboxConstants;
import com.ragagent.sandbox.domain.SandboxNetworkPolicy;
import com.ragagent.sandbox.domain.TenantSandboxConfig;
import com.ragagent.sandbox.domain.TenantSandboxConfigEntity;
import com.ragagent.sandbox.mapper.TenantSkillReadOnlyMapper;
import com.ragagent.sandbox.mapper.TenantSandboxConfigMapper;
import com.ragagent.sandbox.runtime.ConfigSandboxClient;
import com.ragagent.sandbox.runtime.CubeDns;
import com.ragagent.sandbox.runtime.EffectiveConfig;
import com.ragagent.sandbox.runtime.EffectiveConfigResolver;
import com.ragagent.sandbox.runtime.OutboundUrlGuard;
import com.ragagent.sandbox.runtime.SandboxBackendPolicy;
import com.ragagent.sandbox.runtime.SandboxIdentity;
import com.ragagent.sandbox.runtime.SandboxTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import static com.ragagent.sandbox.service.SandboxConfigServiceErrors.NamedSandboxBackendUnsupportedException;
import static com.ragagent.sandbox.service.SandboxConfigServiceErrors.SandboxesStillLiveException;
import static com.ragagent.sandbox.service.SandboxConfigServiceErrors.SandboxConfigCordonedException;
import static com.ragagent.sandbox.service.SandboxConfigServiceErrors.SandboxConfigNameRequiredException;
import static com.ragagent.sandbox.service.SandboxConfigServiceErrors.SandboxInventoryUnverifiableException;
import static com.ragagent.sandbox.service.SandboxConfigServiceErrors.SkillSnapshotBlocksTemplateChangeException;
import static com.ragagent.sandbox.service.SandboxConfigServiceErrors.SkillSnapshotReleaseFailedException;

/**
 * 对照 Go {@code service.TenantSandboxConfigService} 的本批子集
 * （internal/application/service/tenant_sandbox_config.go：SanitizeSandboxConfig L335-400、
 * WorkspaceScriptsDisabled/SetWorkspaceScriptsDisabled L441-479、Create/List/Get L481-535、
 * configHasInFlightSkill L740-762、Update L963-1031、Delete L1045-1077、
 * Inventory L923-940、listSandboxes/inventoryFromSummaries/writeConfig/clientFor/
 * clearCordonAfterRequest/sweepAfterWrite/sandboxConfigEndpoints/sandboxConfigHasSecrets）。
 *
 * <h2>承载规则（Go 文件头注释的浓缩）</h2>
 * 某些字段决定配置能否继续<b>操作</b>它已创建的沙箱。沙箱活着时原地覆写它们会以两种方式
 * 弄坏沙箱：丢控制面（provider/API URL/API key）→ 新凭据对旧沙箱再无权限，且 onTimeout=pause
 * 使 provider TTL 也不回收——泄漏永久且计费；丢数据面（E2B domain；Cube proxy/domain）→
 * 清理仍可能但每个 envd 请求去错主机，配置上的所有活会话同时失败。两组在沙箱存在时都被拒。
 *
 * <h2>子批 1 的显式接缝</h2>
 * <ul>
 *   <li>{@link SandboxClientFactory}：provider 客户端构建点——占位实现恒抛，dev 只覆盖
 *       Update/Delete/Inventory/QueryTemplates 的<b>失败分支</b>（Go L1002-1029 的
 *       "旧凭据不可用 → proceed 并跳过清扫" 正是接线前的稳定形态）；</li>
 *   <li>{@link SandboxConfigSkills}：快照 ledger 只接 ListSkillsByConfig（in-flight 判定），
 *       其余空行为——Delete 的 releaseSkillSnapshots 在空 ledger 世界与 Go 同形；</li>
 *   <li>agent 名单（inventoryFromSummaries 的 agent_names）：SandboxConfigAgentRepo 未翻译
 *       → 可选依赖缺席时跳过（对照 Go 的 {@code s.agents == nil} 分支），agent 引擎
 *       （波 4/5）落地时注入。</li>
 * </ul>
 *
 * <p>QueryTemplates 的模板目录/标准模板保障段（Go L619-691）在接缝之后，子批 2 随
 * RemoteTemplateCatalog 一起翻译；本批到达接缝即以与 Go 客户端构建失败相同的形态收场。</p>
 */
@Service
public class TenantSandboxConfigService {

    private static final Logger log = LoggerFactory.getLogger(TenantSandboxConfigService.class);

    /** 对照 sandboxConfigCleanupTimeout：cordon 清扫的兜底时限（本批直接同步调用）。 */
    private static final java.time.Duration SANDBOX_CONFIG_CLEANUP_TIMEOUT =
            java.time.Duration.ofSeconds(20);

    private final TenantSandboxConfigMapper repo;
    private final SandboxConfigSkills skills;
    private final SandboxClientFactory clientFactory;
    private final CryptoService crypto;
    /** 部署基线；Go 容器注入，缺席时按 DefaultConfig（Go 的 nil 分支）。 */
    private final EffectiveConfig globalCfg;

    /** 对照 now func() time.Time；测试可替换（cordon 租约判定不靠墙钟，§9 波 0 教训）。 */
    private java.util.function.Supplier<OffsetDateTime> clock = OffsetDateTime::now;

    /**
     * 对照 SandboxConfigAgentRepo：agent 引用是警告，绝不是拒绝变更的理由。
     * 未翻译（agent 引擎随波 4/5）→ 缺席时 inventoryFromSummaries 跳过名单。
     */
    public interface SandboxConfigAgentRepo {
        List<String> listNamesBySandboxConfigId(long tenantId, String configId);
    }

    private final SandboxConfigAgentRepo agents;

    public TenantSandboxConfigService(TenantSandboxConfigMapper repo,
            SandboxConfigSkills skills,
            SandboxClientFactory clientFactory,
            CryptoService crypto,
            @Nullable EffectiveConfig globalCfg,
            @Nullable SandboxConfigAgentRepo agents) {
        this.repo = repo;
        this.skills = skills;
        this.clientFactory = clientFactory;
        this.crypto = crypto;
        this.globalCfg = globalCfg;
        this.agents = agents;
    }

    /** 测试注入口（生产恒为墙钟）。 */
    void setClock(java.util.function.Supplier<OffsetDateTime> clock) {
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return clock.get();
    }

    // ── 输入载荷（对照 CreateSandboxConfigInput / UpdateSandboxConfigInput） ──

    public record CreateSandboxConfigInput(String name, String description, TenantSandboxConfig config) {
    }

    public record UpdateSandboxConfigInput(String name, String description, TenantSandboxConfig config) {
    }

    /**
     * 对照 SandboxTemplateQueryInput / SandboxTemplateCatalog：设置抽屉描述的一个未保存
     * 连接。目录读取走 provider 接缝，本批到达接缝即失败分支收场（类注释）。
     */
    public record SandboxTemplateQueryInput(
            TenantSandboxConfig config, String configId, boolean ensureStandard, boolean replaceStandard) {
    }

    public record SandboxTemplateCatalog(
            List<Object> templates,
            String standardTemplateId,
            boolean provisioned) {
    }

    // ── SanitizeSandboxConfig（L335-400，校验链逐字翻译） ───────────────────

    /**
     * 对照 SanitizeSandboxConfig：持久化前解析打码密钥并校验载荷。链序 golden 依赖，
     * 不能重排：ParseType → DockerAllowed → CubeDNS → NetworkPolicy → OutboundURL →
     * AES key 缺失拒绝 → ResolveEffectiveConfig → validateSkillRollout。
     */
    public TenantSandboxConfig sanitizeSandboxConfig(
            TenantSandboxConfig incoming, TenantSandboxConfig existing) {
        if (incoming == null) {
            return null;
        }
        TenantSandboxConfig merged =
                SandboxConfigRedaction.mergeSandboxConfigForUpdate(incoming, existing);

        if (merged.getSandboxType() != null && !merged.getSandboxType().isEmpty()) {
            String parsed = SandboxTypes.parseSandboxType(merged.getSandboxType());
            SandboxBackendPolicy.ensureDockerBackendAllowed(parsed);
        }
        if (merged.getCube() != null) {
            List<String> dns;
            try {
                dns = CubeDns.normalizeCubeDNSServers(merged.getCube().getDnsServers());
            } catch (IllegalArgumentException e) {
                throw BizException.badRequest(e.getMessage());
            }
            merged.getCube().setDnsServers(dns);
        }
        // 在这里拒绝而不是沙箱创建时：provider 自己的错误几分钟才到，
        // 出现在管理员已经离开的屏幕上（Go 注释原文）
        String policyError = SandboxNetworkPolicy.validateSandboxNetworkPolicy(merged);
        if (policyError != null) {
            throw BizException.badRequest(policyError);
        }
        List<String> endpoints = sandboxConfigEndpoints(merged);
        if (endpoints != null) {
            for (String endpoint : endpoints) {
                OutboundUrlGuard.validateOutboundURLWithPolicy(endpoint,
                        new OutboundUrlGuard.OutboundURLPolicy(merged.isAllowPrivateEndpoints()));
            }
        }
        // 没有 AES key 时 Value() 钩子会把密钥明文落库。拒绝而不是悄悄降低存储安全。
        if (sandboxConfigHasSecrets(merged) && crypto.getAESKey() == null) {
            throw BizException.badRequest(
                    "SYSTEM_AES_KEY is not configured; refusing to store sandbox credentials in plaintext");
        }
        // 在这里拒绝不完整配置而不是首次分配沙箱时。resolve 是运行时做的事，
        // 两条路径由构造一致。原样上抛（不包 AppError），让 controller 的
        // sentinel→400 分类能识别（Go 注释原文）。
        EffectiveConfigResolver.resolveEffectiveConfig(merged, EffectiveConfig.defaultConfig());
        validateSkillRollout(merged.getSkillRollout());
        return merged;
    }

    /** 对照 validateSkillRollout。 */
    private static void validateSkillRollout(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()
                || SandboxConstants.SKILL_ROLLOUT_NEXT_TURN.equals(trimmed)
                || SandboxConstants.SKILL_ROLLOUT_NEW_SESSION.equals(trimmed)) {
            return;
        }
        throw BizException.badRequest("invalid skill_rollout");
    }

    /** 对照 validateNamedSandboxBackend。 */
    private void validateNamedSandboxBackend(TenantSandboxConfig cfg) {
        if (cfg == null || cfg.getSandboxType() == null || cfg.getSandboxType().trim().isEmpty()) {
            throw BizException.badRequest("sandbox backend type is required");
        }
        if (!SandboxTypes.isNamedSandboxBackendType(cfg.getSandboxType())) {
            throw new NamedSandboxBackendUnsupportedException();
        }
        SandboxBackendPolicy.ensureDockerBackendAllowed(cfg.getSandboxType());
    }

    // ── 工作区脚本开关（L441-479） ──────────────────────────────────────

    /**
     * 对照 WorkspaceScriptsDisabled：无论 agent 选择了哪个具名后端，
     * 工作区级 kill switch 是否生效。
     */
    public boolean workspaceScriptsDisabled(long tenantId) {
        List<TenantSandboxConfigEntity> list = repo.listByTenant(tenantId);
        return findWorkspacePolicyRow(list) != null;
    }

    /** 对照 SetWorkspaceScriptsDisabled：跨全部具名后端切换整个工作区的脚本执行。 */
    public void setWorkspaceScriptsDisabled(long tenantId, boolean disabled) {
        List<TenantSandboxConfigEntity> list = repo.listByTenant(tenantId);
        TenantSandboxConfigEntity existing = findWorkspacePolicyRow(list);
        if (disabled) {
            if (existing != null) {
                return;
            }
            TenantSandboxConfigEntity entity = new TenantSandboxConfigEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setTenantId(tenantId);
            entity.setName(SandboxConstants.SANDBOX_WORKSPACE_POLICY_CONFIG_NAME);
            entity.setDescription("");
            entity.setSandboxType(SandboxTypes.TYPE_DISABLED);
            TenantSandboxConfig policyConfig = new TenantSandboxConfig();
            policyConfig.setSandboxType(SandboxTypes.TYPE_DISABLED);
            entity.setConfig(policyConfig);
            OffsetDateTime now = now();
            repo.create(entity, now);
            // 对照 GORM Create 的 AutoCreateTime 回写（本批无响应消费该行，仍保持一致）
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            return;
        }
        if (existing == null) {
            return;
        }
        repo.softDelete(tenantId, existing.getId(), now());
    }

    // ── CRUD（L481-535 / L963-1077） ────────────────────────────────────

    /** 对照 Create。 */
    public TenantSandboxConfigEntity create(long tenantId, CreateSandboxConfigInput in) {
        validateNamedSandboxBackend(in.config());
        TenantSandboxConfig merged = sanitizeSandboxConfig(in.config(), null);
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty()) {
            throw new SandboxConfigNameRequiredException();
        }
        TenantSandboxConfigEntity entity = new TenantSandboxConfigEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDescription(in.description());
        entity.setConfig(merged);
        if (merged != null) {
            entity.setSandboxType(merged.getSandboxType());
        }
        OffsetDateTime now = now();
        repo.create(entity, now);
        // GORM Create 的 AutoCreateTime 回写内存对象 → 响应带真实时间
        // （§9「波 2 基础设施」第 3 条）
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    /** 对照 List：工作区的用户可见配置（策略行被排除）。 */
    public List<TenantSandboxConfigEntity> list(long tenantId) {
        List<TenantSandboxConfigEntity> list = repo.listByTenant(tenantId);
        return filterPublicSandboxConfigs(list);
    }

    /** 对照 Get：返回一份配置，缺席时 null。 */
    public TenantSandboxConfigEntity get(long tenantId, String id) {
        TenantSandboxConfigEntity entity = repo.getByID(tenantId, id);
        if (entity == null) {
            return null;
        }
        if (TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(entity)) {
            return null;
        }
        return entity;
    }

    /**
     * 对照 Update（L963-1031）。身份编辑先 cordon 再盘点，用旧客户端在写入后清扫——
     * 凭据仍拥有 provider 资源时绝不被覆写。
     *
     * @return 更新后的实体；配置不存在时 null（handler 渲染 404）
     */
    public TenantSandboxConfigEntity update(long tenantId, String id, UpdateSandboxConfigInput in) {
        TenantSandboxConfigEntity entity = repo.getByID(tenantId, id);
        if (entity == null) {
            return null;
        }
        if (TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(entity)) {
            throw BizException.badRequest("workspace policy cannot be edited here");
        }
        if (in.config() != null && in.config().getSandboxType() != null
                && !in.config().getSandboxType().trim().isEmpty()) {
            validateNamedSandboxBackend(in.config());
        }
        TenantSandboxConfig merged = sanitizeSandboxConfig(in.config(), entity.getConfig());
        validateNamedSandboxBackend(merged);
        if (skillSnapshotBlocksConnectionChange(entity.getConfig(), merged)
                || (skillRetargetWouldChange(entity.getConfig(), merged)
                        && configHasInFlightSkill(tenantId, id))) {
            throw new SkillSnapshotBlocksTemplateChangeException();
        }
        if (!sandboxIdentityChanged(entity.getConfig(), merged)) {
            return writeConfig(entity, in, merged);
        }

        OffsetDateTime now = now();
        if (repo.setCordon(tenantId, id, now,
                now.minusNanos(SandboxConstants.SANDBOX_CORDON_LEASE.toNanos())) == 0) {
            throw new SandboxConfigCordonedException();
        }
        try {
            // 旧凭据够不到 provider 时无法枚举沙箱来拒绝编辑——但拦住保存会把管理员
            // 困在他正想修好的 key 上。放行并跳过写入后清扫；看不见的沙箱可能成为
            // orphan，需要 provider 侧清理（Go L998-1008）。
            ConfigSandboxClient oldClient = null;
            try {
                oldClient = clientFor(entity.getConfig());
            } catch (RuntimeException e) {
                log.warn("[sandbox] config {}: old credentials unusable for inventory: {}; proceeding",
                        id, e.getMessage());
            }
            if (oldClient != null) {
                List<ConfigSandboxClient.RemoteSandboxSummary> summaries;
                try {
                    summaries = ConfigSandboxClient.ConfigSandboxes
                            .listConfigSandboxes(oldClient, tenantId, id);
                } catch (RuntimeException listErr) {
                    log.warn("[sandbox] config {}: cannot verify sandbox inventory with old "
                            + "credentials: {}; proceeding", id, listErr.getMessage());
                    summaries = null;
                    oldClient = null;
                }
                if (summaries != null && !summaries.isEmpty()) {
                    throw new SandboxesStillLiveException(
                            inventoryFromSummaries(summaries, tenantId, id));
                }
            }

            TenantSandboxConfigEntity updated = writeConfig(entity, in, merged);

            if (oldClient != null) {
                sweepAfterWrite(oldClient, tenantId, id);
            }
            return updated;
        } finally {
            clearCordonAfterRequest(tenantId, id);
        }
    }

    /**
     * 对照 Delete（L1033-1077）：配置仍拥有沙箱、或 ledger 上的 skill 快照无法销毁时拒绝。
     * force 覆盖两个从这里无法完成的情形：provider 联系不上、快照销毁失败。
     * 它<b>不</b>覆盖看得见的沙箱——那些仍要经它们的会话处理。
     */
    public void delete(long tenantId, String id, boolean force) {
        TenantSandboxConfigEntity entity = repo.getByID(tenantId, id);
        // 为不存在的配置报告成功会让 UI 掉一张工作区仍有的卡片，所以缺席是显式 404
        if (entity == null) {
            throw BizException.notFound("sandbox config not found");
        }
        if (TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(entity)) {
            throw BizException.badRequest("workspace policy cannot be deleted here");
        }
        List<ConfigSandboxClient.RemoteSandboxSummary> summaries;
        try {
            summaries = listSandboxes(entity.getConfig(), tenantId, id);
        } catch (RuntimeException listErr) {
            if (!force) {
                throw new SandboxInventoryUnverifiableException(
                        "cannot verify whether the sandbox config still owns sandboxes: "
                                + listErr.getMessage());
            }
            log.warn("[sandbox] force-deleting config {} without verifying its sandboxes: {}",
                    id, listErr.getMessage());
            summaries = null;
        }
        if (summaries != null && !summaries.isEmpty()) {
            throw new SandboxesStillLiveException(inventoryFromSummaries(summaries, tenantId, id));
        }
        releaseSkillSnapshots(entity, tenantId, id, force);
        repo.softDelete(tenantId, id, now());
    }

    // ── Inventory / QueryTemplates（provider 面走接缝，失败分支确定） ────────

    /**
     * 对照 Inventory（L918-940）：回答变更/删除本配置会打扰到什么。provider 联系不上以
     * Unverifiable 报告而非错误：管理页仍要渲染卡片，agent 名单来自我们自己的库。
     */
    public SandboxInventory inventory(long tenantId, String id) {
        TenantSandboxConfigEntity entity = repo.getByID(tenantId, id);
        if (entity == null) {
            throw BizException.notFound("sandbox config not found");
        }
        List<ConfigSandboxClient.RemoteSandboxSummary> summaries = null;
        boolean unverifiable = false;
        try {
            summaries = listSandboxes(entity.getConfig(), tenantId, id);
        } catch (RuntimeException err) {
            log.warn("[sandbox] inventory of config {} is unverifiable: {}", id, err.getMessage());
            unverifiable = true;
        }
        SandboxInventory inv = inventoryFromSummaries(summaries, tenantId, id);
        if (unverifiable) {
            return new SandboxInventory(inv.sandboxCount(), inv.sessionIds(),
                    inv.agentNames(), true);
        }
        return inv;
    }

    /**
     * 对照 QueryTemplates（L537-691 的前置段）：读取 provider 模板目录。目录访问需要
     * 控制面连接但还不需要 spawn 模板——私有占位符使同一份 effective-config 校验保护
     * 其他所有必填字段。本批在接缝（客户端构建）处即失败分支收场；目录读取与
     * 标准模板保障段随子批 2 的 RemoteTemplateCatalog 翻译。
     */
    public SandboxTemplateCatalog queryTemplates(long tenantId, SandboxTemplateQueryInput in) {
        if (in.replaceStandard() && (in.configId() == null || in.configId().trim().isEmpty())) {
            throw BizException.badRequest("config_id is required to rebuild the standard template");
        }
        TenantSandboxConfig existing = null;
        if (in.configId() != null && !in.configId().trim().isEmpty()) {
            TenantSandboxConfigEntity entity = repo.getByID(tenantId, in.configId());
            if (entity == null || TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(entity)) {
                throw BizException.notFound("sandbox config not found");
            }
            existing = entity.getConfig();
        }
        TenantSandboxConfig merged = SandboxConfigRedaction.mergeSandboxConfigForUpdate(
                in.config(), existing);
        if (merged == null) {
            merged = SandboxConfigRedaction.mergeSandboxConfigForUpdate(existing, null);
        }
        if (merged == null) {
            throw BizException.badRequest("sandbox config is required");
        }
        if (in.replaceStandard()) {
            refuseClusterSkillTemplateReplace(tenantId, merged);
        }

        switch (merged.getSandboxType() == null ? "" : merged.getSandboxType()) {
            case SandboxTypes.TYPE_CUBE -> {
                if (merged.getCube() == null) {
                    merged.setCube(new CubeSandboxConfig());
                }
                if (merged.getCube().getTemplateId().trim().isEmpty()) {
                    merged.getCube().setTemplateId("__catalog__");
                }
            }
            case SandboxTypes.TYPE_E2B -> {
                if (merged.getE2b() == null) {
                    merged.setE2b(new E2BSandboxConfig());
                }
                if (merged.getE2b().getTemplateId().trim().isEmpty()) {
                    merged.getE2b().setTemplateId("__catalog__");
                }
            }
            case SandboxTypes.TYPE_DOCKER -> {
                SandboxBackendPolicy.ensureDockerBackendAllowed(SandboxTypes.TYPE_DOCKER);
                // Docker 的模板是镜像，目录步是管理员挑镜像的地方。
                // 标准镜像先顶上，让下面的 effective-config 校验有东西可接受
                if (merged.getDocker() == null) {
                    merged.setDocker(new DockerSandboxConfig());
                }
                if (merged.getDocker().getImage().trim().isEmpty()) {
                    merged.getDocker().setImage(EffectiveConfig.DEFAULT_DOCKER_IMAGE);
                }
            }
            default -> throw BizException.badRequest(
                    "sandbox template catalog only supports cube, e2b and docker backends");
        }

        List<String> endpoints = sandboxConfigEndpoints(merged);
        if (endpoints != null) {
            for (String endpoint : endpoints) {
                OutboundUrlGuard.validateOutboundURLWithPolicy(endpoint,
                        new OutboundUrlGuard.OutboundURLPolicy(merged.isAllowPrivateEndpoints()));
            }
        }
        EffectiveConfig effective =
                EffectiveConfigResolver.resolveEffectiveConfig(merged, EffectiveConfig.defaultConfig());
        // ── provider 接缝（子批 2 换真客户端 + 目录段） ─────────────────────
        ConfigSandboxClient client = clientFactory.create(effective);
        throw new IllegalStateException("sandbox: provider client produced no template catalog "
                + "(unreachable with the unwired factory; client class "
                + client.getClass().getName() + ")");
    }

    /** 对照 refuseClusterSkillTemplateReplace（L717-738）。 */
    private void refuseClusterSkillTemplateReplace(long tenantId, TenantSandboxConfig merged) {
        SandboxIdentity identity = SandboxIdentity.identityOf(merged);
        List<TenantSandboxConfigEntity> list = repo.listByTenant(tenantId);
        for (TenantSandboxConfigEntity entity : list) {
            if (entity == null || TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(entity)) {
                continue;
            }
            if (!SandboxIdentity.identityOf(entity.getConfig()).equals(identity)) {
                continue;
            }
            if (configHasSkillSnapshot(entity.getConfig())
                    || configHasInFlightSkill(entity.getTenantId(), entity.getId())) {
                throw new SkillSnapshotBlocksTemplateChangeException();
            }
        }
    }

    // ── 快照/in-flight 判定（L74-137 / L740-762） ────────────────────────

    /** 对照 configHasSkillSnapshot。 */
    private static boolean configHasSkillSnapshot(TenantSandboxConfig cfg) {
        return cfg != null && cfg.getSkillImage() != null
                && cfg.getSkillImage().getSnapshotId() != null
                && !cfg.getSkillImage().getSnapshotId().trim().isEmpty();
    }

    /**
     * 对照 configHasInFlightSkill：读取本配置的 skill 行，任一处于 installing/removing
     * 即真。读失败按真处理（保守：不因读不出来而放行一次会搁浅安装的编辑）。
     */
    private boolean configHasInFlightSkill(long tenantId, String configId) {
        if (skills == null || configId == null || configId.trim().isEmpty()) {
            return false;
        }
        List<TenantSkillReadOnlyMapper.TenantSkillStatusRow> rows;
        try {
            rows = skills.listSkillsByConfig(tenantId, configId);
        } catch (RuntimeException e) {
            log.warn("[sandbox] cannot read skills of config {} while judging a retarget: {}",
                    configId, e.getMessage());
            return true;
        }
        for (TenantSkillReadOnlyMapper.TenantSkillStatusRow row : rows) {
            if (row != null
                    && ("installing".equals(row.status()) || "removing".equals(row.status()))) {
                return true;
            }
        }
        return false;
    }

    // ── 身份/retarget 判定（L50-137） ───────────────────────────────────

    /**
     * 对照 SandboxIdentityChanged：旧配置下创建的沙箱在新配置下是否不再可操作。
     * 名字、模板、TTL、HTTP 超时与 env vars 刻意不算——它们只塑造未来的沙箱。
     * newCfg 必须已与存储配置 merge 过。比较不需要部署基线：具名配置什么都不继承。
     */
    static boolean sandboxIdentityChanged(TenantSandboxConfig oldCfg, TenantSandboxConfig newCfg) {
        if (oldCfg == null) {
            return false;
        }
        if (newCfg == null) {
            return true;
        }
        return !SandboxIdentity.identityOf(oldCfg).equals(SandboxIdentity.identityOf(newCfg));
    }

    /** 对照 skillRetargetWouldChange：身份、spawn 模板或 Cube DNS 的 retarget 编辑。 */
    static boolean skillRetargetWouldChange(TenantSandboxConfig stored, TenantSandboxConfig merged) {
        if (sandboxIdentityChanged(stored, merged)) {
            return true;
        }
        if (!spawnTemplateId(stored).equals(spawnTemplateId(merged))) {
            return true;
        }
        return !sameStrings(cubeDnsServers(stored), cubeDnsServers(merged));
    }

    /**
     * 对照 spawnTemplateID：本配置在没有 skill 快照时会启动的模板/镜像。
     * Cube/E2B 存 template_id；Docker 存 image。
     */
    static String spawnTemplateId(TenantSandboxConfig cfg) {
        if (cfg == null) {
            return "";
        }
        switch (cfg.getSandboxType() == null ? "" : cfg.getSandboxType()) {
            case SandboxTypes.TYPE_CUBE -> {
                if (cfg.getCube() != null) {
                    return cfg.getCube().getTemplateId() == null
                            ? "" : cfg.getCube().getTemplateId().trim();
                }
            }
            case SandboxTypes.TYPE_E2B -> {
                if (cfg.getE2b() != null) {
                    return cfg.getE2b().getTemplateId() == null
                            ? "" : cfg.getE2b().getTemplateId().trim();
                }
            }
            case SandboxTypes.TYPE_DOCKER -> {
                if (cfg.getDocker() != null) {
                    return cfg.getDocker().getImage() == null
                            ? "" : cfg.getDocker().getImage().trim();
                }
            }
            default -> {
            }
        }
        return "";
    }

    static List<String> cubeDnsServers(TenantSandboxConfig cfg) {
        if (cfg == null || cfg.getCube() == null) {
            return null;
        }
        return cfg.getCube().getDnsServers();
    }

    private static boolean sameStrings(List<String> a, List<String> b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Objects.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** 对照 skillSnapshotBlocksConnectionChange。 */
    static boolean skillSnapshotBlocksConnectionChange(
            TenantSandboxConfig stored, TenantSandboxConfig merged) {
        return configHasSkillSnapshot(stored) && skillRetargetWouldChange(stored, merged);
    }

    // ── 内部写路径与清扫 ────────────────────────────────────────────────

    /** 对照 writeConfig（L1410-1428）。 */
    private TenantSandboxConfigEntity writeConfig(TenantSandboxConfigEntity entity,
            UpdateSandboxConfigInput in, TenantSandboxConfig merged) {
        String name = in.name() == null ? "" : in.name().trim();
        if (!name.isEmpty()) {
            entity.setName(name);
        }
        entity.setDescription(in.description());
        entity.setConfig(merged);
        if (merged != null) {
            entity.setSandboxType(merged.getSandboxType());
        }
        repo.update(entity, now());
        // GORM 的 map Updates 不回写内存对象 → 响应里的 updated_at 保持读时值（Go 一致）
        return entity;
    }

    /**
     * 对照 listSandboxes：枚举本配置当前拥有的沙箱。null 客户端意味着后端根本没有
     * 远程资源——那是一次"已核实的空"。
     */
    private List<ConfigSandboxClient.RemoteSandboxSummary> listSandboxes(
            TenantSandboxConfig cfg, long tenantId, String id) {
        ConfigSandboxClient client = clientFor(cfg);
        if (client == null) {
            return null;
        }
        return ConfigSandboxClient.ConfigSandboxes.listConfigSandboxes(client, tenantId, id);
    }

    /**
     * 对照 clientFor（L1388-1408）：基线只供给部署的执行超时；一切 provider 字段来自
     * cfg，所以 nil globalCfg 不能改变这个客户端对谈的后端。disabled 类型 → null 客户端。
     */
    private ConfigSandboxClient clientFor(TenantSandboxConfig cfg) {
        EffectiveConfig base = globalCfg != null ? globalCfg : EffectiveConfig.defaultConfig();
        EffectiveConfig effective = EffectiveConfigResolver.resolveEffectiveConfig(cfg, base);
        switch (effective.type) {
            case SandboxTypes.TYPE_CUBE, SandboxTypes.TYPE_E2B, SandboxTypes.TYPE_DOCKER:
                return clientFactory.create(effective);
            default:
                return null;
        }
    }

    /** 对照 inventoryFromSummaries（L1430-1452）。 */
    private SandboxInventory inventoryFromSummaries(
            List<ConfigSandboxClient.RemoteSandboxSummary> summaries, long tenantId, String id) {
        int count = summaries == null ? 0 : summaries.size();
        List<String> sessionIds = null;
        if (summaries != null) {
            for (ConfigSandboxClient.RemoteSandboxSummary summary : summaries) {
                String sessionId = summary.metadata() == null ? null
                        : summary.metadata()
                                .get(ConfigSandboxClient.ConfigSandboxes.metadataSessionIdKey());
                if (sessionId != null && !sessionId.isEmpty()) {
                    if (sessionIds == null) {
                        sessionIds = new ArrayList<>();
                    }
                    sessionIds.add(sessionId);
                }
            }
        }
        if (agents == null) {
            return new SandboxInventory(count, sessionIds, null, false);
        }
        List<String> names;
        try {
            names = agents.listNamesBySandboxConfigId(tenantId, id);
        } catch (RuntimeException e) {
            log.warn("[sandbox] list agents for config {}: {}", id, e.getMessage());
            return new SandboxInventory(count, sessionIds, null, false);
        }
        return new SandboxInventory(count, sessionIds, names, false);
    }

    /** 对照 clearCordonAfterRequest：Go 走 context.WithoutCancel+20s；Java 同步尽力而为。 */
    private void clearCordonAfterRequest(long tenantId, String id) {
        try {
            repo.clearCordon(tenantId, id, now());
        } catch (RuntimeException e) {
            log.warn("[sandbox] clear cordon on config {}: {}", id, e.getMessage());
        }
    }

    /** 对照 sweepAfterWrite：删除 cordon 窗口期间创建的沙箱（旧凭据仍在内存中的时刻）。 */
    private void sweepAfterWrite(ConfigSandboxClient oldClient, long tenantId, String id) {
        try {
            List<ConfigSandboxClient.RemoteSandboxSummary> summaries =
                    ConfigSandboxClient.ConfigSandboxes.listConfigSandboxes(oldClient, tenantId, id);
            int deleted = 0;
            List<String> failures = new ArrayList<>();
            for (ConfigSandboxClient.RemoteSandboxSummary summary : summaries) {
                try {
                    oldClient.delete(summary.id());
                    deleted++;
                } catch (RuntimeException e) {
                    failures.add("delete sandbox " + summary.id() + ": " + e.getMessage());
                }
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException(String.join("\n", failures));
            }
            if (deleted > 0) {
                log.info("[sandbox] swept {} sandbox(es) created during the cordon window on config {}",
                        deleted, id);
            }
        } catch (RuntimeException e) {
            log.warn("[sandbox] post-write sweep of config {} failed: {}", id, e.getMessage());
        }
    }

    // ── 列表投影辅助（L412-437） ────────────────────────────────────────

    /** 对照 filterPublicSandboxConfigs。 */
    static List<TenantSandboxConfigEntity> filterPublicSandboxConfigs(
            List<TenantSandboxConfigEntity> list) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        List<TenantSandboxConfigEntity> out = new ArrayList<>(list.size());
        for (TenantSandboxConfigEntity e : list) {
            if (TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(e)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    /** 对照 findWorkspacePolicyRow。 */
    static TenantSandboxConfigEntity findWorkspacePolicyRow(List<TenantSandboxConfigEntity> list) {
        if (list == null) {
            return null;
        }
        for (TenantSandboxConfigEntity e : list) {
            if (TenantSandboxConfigEntity.isSandboxWorkspacePolicyRow(e)) {
                return e;
            }
        }
        return null;
    }

    // ── 快照释放（Delete 路径；ledger 子批 2 接线，空行为见 SandboxConfigSkills） ──

    /**
     * 对照 releaseSkillSnapshots（L1168-1202）的骨架：ledger 读取失败且非 force →
     * 释放失败（409 固定文案）；force → 警告后继续。快照销毁/abandoned-build 解析/
     * bundle 释放的实体段随子批 2 的 ledger 一起翻译（destroyPendingSnapshots/
     * resolveAbandonedBuildIDs/releasePinnedBundles 在空 ledger 上均为无操作）。
     */
    private void releaseSkillSnapshots(TenantSandboxConfigEntity entity,
            long tenantId, String configId, boolean force) {
        if (skills == null) {
            return;
        }
        List<Object> rows;
        try {
            rows = skills.listSnapshotsByConfig(tenantId, configId);
        } catch (RuntimeException err) {
            if (!force) {
                throw new SkillSnapshotReleaseFailedException(null,
                        "list snapshots: " + err.getMessage());
            }
            log.warn("[sandbox] force-deleting config {} without listing skill snapshots: {}",
                    configId, err.getMessage());
            rows = null;
        }
        List<Object> pending = pendingSkillSnapshots(rows);
        if (pending.isEmpty()) {
            cleanupSkillMetadata(tenantId, configId);
            return;
        }
        // 空行为 store 之外不会有 pending；真实销毁段随子批 2
        cleanupSkillMetadata(tenantId, configId);
    }

    /** 对照 pendingSkillSnapshots（state != deleted 的行；本批 ledger 行未建模 → 空）。 */
    private static List<Object> pendingSkillSnapshots(List<Object> rows) {
        return rows == null ? List.of() : List.of();
    }

    /** 对照 cleanupSkillMetadata（L1287-1324）的形状；删除均为子批 2 接线。 */
    private void cleanupSkillMetadata(long tenantId, String configId) {
        List<TenantSkillReadOnlyMapper.TenantSkillStatusRow> skillRows;
        try {
            skillRows = skills.listSkillsByConfig(tenantId, configId);
        } catch (RuntimeException err) {
            log.warn("[sandbox] list skills for config {} cleanup failed: {}", configId, err.getMessage());
            skillRows = List.of();
        }
        for (TenantSkillReadOnlyMapper.TenantSkillStatusRow skill : skillRows) {
            try {
                skills.deleteSkill(tenantId, configId, skill.id());
            } catch (RuntimeException err) {
                log.warn("[sandbox] delete skill {} on config {} failed: {}",
                        skill.id(), configId, err.getMessage());
            }
        }
        try {
            skills.deleteSnapshotRowsByConfig(tenantId, configId);
        } catch (RuntimeException err) {
            log.warn("[sandbox] delete snapshot ledger for config {} failed: {}",
                    configId, err.getMessage());
        }
        try {
            skills.deleteUserEnvVarsByConfig(tenantId, configId);
        } catch (RuntimeException err) {
            log.warn("[sandbox] delete member env vars for config {} failed: {}",
                    configId, err.getMessage());
        }
    }

    // ── 端点/密钥盘点（L1488-1541） ─────────────────────────────────────

    /** 对照 sandboxConfigEndpoints：所有非空的租户自供 URL。 */
    static List<String> sandboxConfigEndpoints(TenantSandboxConfig cfg) {
        if (cfg == null) {
            return null;
        }
        List<String> endpoints = null;
        if (cfg.getCube() != null) {
            for (String raw : new String[] {cfg.getCube().getApiUrl(), cfg.getCube().getProxyUrl()}) {
                if (raw != null && !raw.isEmpty()) {
                    if (endpoints == null) {
                        endpoints = new ArrayList<>();
                    }
                    endpoints.add(raw);
                }
            }
        }
        if (cfg.getE2b() != null && cfg.getE2b().getApiUrl() != null
                && !cfg.getE2b().getApiUrl().isEmpty()) {
            if (endpoints == null) {
                endpoints = new ArrayList<>();
            }
            endpoints.add(cfg.getE2b().getApiUrl());
        }
        return endpoints;
    }

    /** 对照 sandboxConfigHasSecrets：cfg 是否携带任何必须落库加密的值。 */
    static boolean sandboxConfigHasSecrets(TenantSandboxConfig cfg) {        if (cfg == null) {
            return false;
        }
        if (cfg.getCube() != null && cfg.getCube().getApiKey() != null
                && !cfg.getCube().getApiKey().isEmpty()) {
            return true;
        }
        if (cfg.getE2b() != null && cfg.getE2b().getApiKey() != null
                && !cfg.getE2b().getApiKey().isEmpty()) {
            return true;
        }
        if (cfg.getEnvVars() != null) {
            for (String value : cfg.getEnvVars().values()) {
                if (value != null && !value.isEmpty()) {
                    return true;
                }
            }
        }
        if (cfg.getNetwork() != null) {
            if (cfg.getNetwork().getCubeRules() != null) {
                for (SandboxNetworkPolicy.CubeEgressRule rule : cfg.getNetwork().getCubeRules()) {
                    if (rule.getInject() == null) {
                        continue;
                    }
                    for (SandboxNetworkPolicy.CubeHeaderInject inject : rule.getInject()) {
                        if (inject.getSecret() != null && !inject.getSecret().isEmpty()) {
                            return true;
                        }
                    }
                }
            }
            if (cfg.getNetwork().getE2bHostRules() != null) {
                for (SandboxNetworkPolicy.E2BHostRule rule : cfg.getNetwork().getE2bHostRules()) {
                    if (rule.getHeaders() == null) {
                        continue;
                    }
                    for (String value : rule.getHeaders().values()) {
                        if (value != null && !value.isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
