package com.ragagent.sandbox.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.ragagent.common.error.BizException;
import com.ragagent.sandbox.domain.SkillEnvVar;
import com.ragagent.sandbox.domain.SkillEnvVars;
import com.ragagent.sandbox.domain.SkillImageConfig;
import com.ragagent.sandbox.domain.SkillStatus;
import com.ragagent.sandbox.domain.TenantSandboxConfig;
import com.ragagent.sandbox.domain.TenantSandboxConfigEntity;
import com.ragagent.sandbox.domain.TenantSkillCatalogEntity;
import com.ragagent.sandbox.domain.TenantSkillEntity;
import com.ragagent.sandbox.mapper.TenantSkillMapper;
import com.ragagent.sandbox.runtime.ConfigSandboxClient;
import com.ragagent.sandbox.runtime.EffectiveConfig;
import com.ragagent.sandbox.runtime.EffectiveConfigResolver;
import com.ragagent.sandbox.runtime.RemoteConfigSandboxClient;
import com.ragagent.sandbox.runtime.SandboxTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 对照 Go {@code service.TenantSkillService} 的子批 3 子集
 * （tenant_skill_admin.go / tenant_skill_progress.go / tenant_skill_bundle.go /
 * tenant_skill_files.go 的读面 + tenant_skill_install.go / tenant_skill_remove.go /
 * tenant_skill_stop.go / tenant_skill_steer.go / tenant_skill_transcript.go 的
 * <b>入口与首个 provider 调用失败传播</b>）。
 *
 * <h2>波 4 接缝（刻意不翻的管线后续）</h2>
 * <ul>
 *   <li><b>install</b>：同步入口逐行对照（配置 404 → bundle 解析 → 同名换行 →
 *       catalog 落库 → 202）；后台管线只保留状态机的两端——
 *       {@code installStillOwnsTheRow}（纯 DB）之后直接是<b>首个 provider 调用</b>
 *       （启动维护沙箱，dev 恒拒连），失败 → {@link #failSkill} 置 failed。
 *       播种、installer agent、验证 gate、快照、指针切换随波 4；</li>
 *   <li><b>remove</b>：同步入口逐行对照（404 → 翻 removing → 202）；后台管线在
 *       existing/status 复核后即到<b>首个 provider 调用</b>接缝，失败 →
 *       {@link #restoreSkillAfterFailedRemoval}（对照 Go defer 的补偿写）。
 *       无快照配置的 DB-only 完成分支（clearImagePointer/finishRemoval）随波 4；</li>
 *   <li><b>进度实时通道</b>：{@link SkillProgressStore} 的订阅侧随管线一起接线；</li>
 *   <li><b>markConfigSandboxesStale</b>（指向沙箱绑定失效）：session 绑定属波 4，恒 no-op；</li>
 *   <li><b>转写产生方</b>（installTranscript 的 append）：随管线；读侧
 *       （/transcript 端点）本批全文翻译。</li>
 * </ul>
 *
 * <p>错误分类：{@link SkillBundleParser.BundleInvalidException}（bundle 校验整类）与
 * {@link SkillSourceInvalidException}（registry/git/URL source 校验）由 handler 升为 400；
 * 其余按 Go 形态抛（404/409 → AppError 信封，管线失败 → 500 plain）。</p>
 */
@Service
public class TenantSkillService {

    private static final Logger log = LoggerFactory.getLogger(TenantSkillService.class);

    /** 对照 {@code MaxEnvValueBytes}（tenant_skill_env_declare.go）：PEM key 也够用的宽裕上限。 */
    public static final int MAX_ENV_VALUE_BYTES = 8 * 1024;

    /** 对照 {@code installCommandTimeout} 等的常量面只保留被本批消费的两个。 */
    static final long SKILL_INSTALL_IN_FLIGHT_SKIP_MS = 3 * 60 * 1000;

    /** 对照 {@code errSkillInstallStopped}。 */
    private static final String SKILL_INSTALL_STOPPED_MESSAGE = "安装已停止";

    private final TenantSkillMapper skills;
    private final TenantSandboxConfigMapperHolder configsHolder;
    private final SkillBundleStore bundleStore;
    private final SkillProgressStore progress;
    private final SandboxClientFactory clientFactory;
    /** 配置读面（listCatalog 的 ListByTenant；子批 4 加入）。 */
    private final com.ragagent.sandbox.mapper.TenantSandboxConfigMapper configMapper;
    @Nullable
    private final com.ragagent.stream.StreamManager streams;

    /** 对照 {@code now func() time.Time}；测试可替换。 */
    private java.util.function.Supplier<OffsetDateTime> clock = OffsetDateTime::now;

    /** 对照 {@code runCancels}：进程内 run 的取消句柄（重启即无；StopSkill 只改写行）。 */
    private final Map<String, Thread> runCancels = new ConcurrentHashMap<>();

    /** 对照 {@code keyedMutex}：无 Redis 时进程内串行（多副本需 Redis，波 4 接 redislock）。 */
    private final Map<String, ReentrantLock> keyedLocks = new ConcurrentHashMap<>();

    public TenantSkillService(TenantSkillMapper skills,
            TenantSandboxConfigMapperHolder configsHolder,
            SkillBundleStore bundleStore,
            SkillProgressStore progress,
            SandboxClientFactory clientFactory,
            com.ragagent.sandbox.mapper.TenantSandboxConfigMapper configMapper,
            @Nullable com.ragagent.stream.StreamManager streams) {
        this.skills = skills;
        this.configsHolder = configsHolder;
        this.bundleStore = bundleStore;
        this.progress = progress;
        this.clientFactory = clientFactory;
        this.configMapper = configMapper;
        this.streams = streams;
    }

    /** 测试注入口（生产恒为墙钟）。 */
    void setClock(java.util.function.Supplier<OffsetDateTime> clock) {
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return clock.get();
    }

    /**
     * 配置仓储的窄接口：install/remove 入口只读配置。直接复用子批 1 的
     * {@link com.ragagent.sandbox.mapper.TenantSandboxConfigMapper} 会把整个仓储面
     * 拖进构造器，这里收窄成两个方法（实现委托同一个 mapper bean）。
     */
    public interface TenantSandboxConfigMapperHolder {
        TenantSandboxConfigEntity getByID(long tenantId, String id);

        void update(TenantSandboxConfigEntity entity, OffsetDateTime now);
    }

    /** 管理请求可改的一切（对照 {@code SkillAdminUpdate}）。 */
    public record SkillAdminUpdate(Boolean enabled, Map<String, String> envValues) {
    }

    // ── 读面（tenant_skill_admin.go） ───────────────────────────────────

    /**
     * 对照 {@code ListSkills}：先读配置——不属于本工作区的配置报"配置缺失"
     * 而不是"无 skill"。
     */
    public List<TenantSkillEntity> listSkills(long tenantId, String configId) {
        TenantSandboxConfigEntity cfg = configsHolder.getByID(tenantId, configId);
        if (cfg == null) {
            throw BizException.notFound("sandbox config not found");
        }
        return skills.listSkillsByConfig(tenantId, configId);
    }

    /** 对照 {@code GetSkill}：跨工作区的 skill ID 与不存在不可分辨（仓储按工作区+配置划界）。 */
    public TenantSkillEntity getSkill(long tenantId, String configId, String skillId) {
        return skills.getSkill(tenantId, configId, skillId);
    }

    // ── 管理写面（UpdateSkillAdmin：单次 read-modify-write，请求要么全中要么不落） ──

    /**
     * 对照 {@code UpdateSkillAdmin}。值只写已声明的名字；声明外的名字忽略而非拒绝
     * （过期的设置页不得拖垮有效的保存）；空串清值、保留声明。可见性只是元数据。
     * skill 不可达时返回 null（handler 渲染 404）。
     */
    public TenantSkillEntity updateSkillAdmin(long tenantId, String configId, String skillId,
            SkillAdminUpdate update) {
        TenantSkillEntity skill = skills.getSkill(tenantId, configId, skillId);
        if (skill == null) {
            return null;
        }
        Map<String, String> envValues =
                update.envValues() == null ? Map.of() : update.envValues();

        // 任何一部分应用之前先校验全部值：被拒的条目不能把声明（或开关）写一半
        for (Map.Entry<String, String> e : envValues.entrySet()) {
            if (skill.getEnvs() == null || !skill.getEnvs().declares(e.getKey())) {
                continue;
            }
            if (e.getValue() != null
                    && e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            > MAX_ENV_VALUE_BYTES) {
                throw BizException.badRequest("value of " + e.getKey()
                        + " cannot exceed " + MAX_ENV_VALUE_BYTES + " bytes");
            }
        }

        boolean changed = false;
        if (update.enabled() != null && skill.isEnabled() != update.enabled()) {
            skill.setEnabled(update.enabled());
            changed = true;
        }
        if (skill.getEnvs() != null) {
            for (SkillEnvVar entry : skill.getEnvs()) {
                if (!envValues.containsKey(entry.getName())
                        || entry.getValue().equals(envValues.get(entry.getName()))) {
                    continue;
                }
                entry.setValue(envValues.get(entry.getName()));
                changed = true;
            }
        }
        if (!changed) {
            return skill;
        }
        // 只写两列管理拥有的列：行可能在安装中，installer 写在那里的状态与此请求无关
        skills.updateSkillAdminState(tenantId, configId, skillId, skill.isEnabled(),
                skill.getEnvs(), now());
        return skill;
    }

    // ── 进度（tenant_skill_progress.go 面） ─────────────────────────────

    void publishProgress(long tenantId, String configId, String skillId, SkillProgress p) {
        progress.publish(tenantId, configId, skillId, p);
    }

    /** 对照 {@code LastProgress}。 */
    public SkillProgressStore.LastProgress lastProgress(long tenantId, String configId,
            String skillId) {
        return progress.last(tenantId, configId, skillId);
    }

    /** 对照 {@code SubscribeProgress}。 */
    public SkillProgressStore.Subscription subscribeProgress(long tenantId, String configId,
            String skillId) {
        return progress.subscribe(tenantId, configId, skillId);
    }

    // ── 校验门（tenant_skill_verify.go 面，波 5 W5β） ─────────────────────

    /**
     * 对照 {@code verifySkill}：安装的最后一道门（逐 pass 见 {@link TenantSkillVerifier}）。
     * 执行体/读文件体以 seam 显式传入（Go 从 sandbox.Manager 取能力；Java 的会话
     * Manager 随 provider 执行体批落地）。install 管线体（播种/agent/快照/切换）接入
     * 前由契约测试直接驱动本入口。
     */
    TenantSkillVerifier.VerifyResult verifySkill(
            com.ragagent.agent.tools.SandboxInstallCommandExecutor executor,
            TenantSkillVerifier.SessionFileReader reader,
            String sessionID, String skillDir, SkillBundleParser.SkillBundle bundle) {
        return TenantSkillVerifier.verifySkill(executor, reader, sessionID, skillDir, bundle);
    }

    // ── install 入口（tenant_skill_install.go L61-201） ──────────────────

    /** 对照 {@code InstallSkill}：校验、记录、后台启动；返回 skill ID 供 202。 */
    public String installSkill(long tenantId, String configId, byte[] archive) {
        return installSkillArchive(tenantId, configId, archive);
    }

    /** 对照 {@code installFromSource} 的 service 入口：source 校验在抓取前失败。 */
    public String installSkillFromSource(long tenantId, String configId, String source) {
        // 波 4 接缝：parseSkillSource/fetch 的 930 行校验与抓取管线随 registry 安装面翻译；
        // 本批入口即以 source 校验失败收场（分类与 bundle 同族 → 400）
        throw new SkillSourceInvalidException("skill source is invalid: " + source);
    }

    private String installSkillArchive(long tenantId, String configId, byte[] archive,
            String... instructions) {
        TenantSandboxConfigEntity cfg = configsHolder.getByID(tenantId, configId);
        if (cfg == null) {
            throw BizException.notFound("sandbox config not found");
        }
        SkillBundleParser.SkillBundle bundle = SkillBundleParser.parseSkillBundle(archive);
        return installParsedSkill(tenantId, configId, bundle, archive, instructions);
    }

    private String installParsedSkill(long tenantId, String configId,
            SkillBundleParser.SkillBundle bundle, byte[] archive, String... instructions) {
        String guidance = String.join("\n", instructions).trim();
        if (guidance.codePointCount(0, guidance.length()) > 10000) {
            throw BizException.badRequest("install instructions exceed 10000 characters");
        }

        // 同名重传是升级，不是第二行：(config, name) 唯一索引与镜像目录名都按 name 走
        TenantSkillEntity existing = skills.getSkillByName(tenantId, configId, bundle.name);
        if (!guidance.isEmpty() && existing != null
                && (SkillStatus.INSTALLING.equals(existing.getStatus())
                        || SkillStatus.REMOVING.equals(existing.getStatus()))) {
            throw BizException.conflict(
                    "skill is busy; send guidance to the active install or wait for it to finish");
        }
        if (guidance.isEmpty() && canSkipInstall(existing, bundle)) {
            TenantSkillCatalogEntity catalog =
                    upsertCatalogFromBundle(tenantId, bundle, archive, true);
            pointInstallAtCatalog(existing, catalog);
            return existing.getId();
        }

        String skillId = UUID.randomUUID().toString();
        OffsetDateTime now = now();
        if (existing != null) {
            skillId = existing.getId();
            takeSkillRowForInstall(existing, bundle, now);
            skills.updateSkill(existing, now);
        } else {
            TenantSkillEntity row = new TenantSkillEntity();
            row.setId(skillId);
            row.setTenantId(tenantId);
            row.setSandboxConfigId(configId);
            row.setName(bundle.name);
            row.setVersion(bundle.version);
            row.setDescription(bundle.description);
            row.setInstructions(bundle.instructions);
            row.setBundleSha256(bundle.sha256);
            row.setEnabled(true);
            row.setStatus(SkillStatus.INSTALLING);
            row.setInstallingSince(now);
            try {
                skills.createSkill(row, now);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 同名首次上传与唯一索引赛跑：赢家的行接手，而不是把 500 暴给用户
                TenantSkillEntity winner = skills.getSkillByName(tenantId, configId, bundle.name);
                if (winner == null) {
                    throw e;
                }
                skillId = winner.getId();
                takeSkillRowForInstall(winner, bundle, now);
                skills.updateSkill(winner, now);
            }
        }

        // zip 归 catalog 所有，不归这份沙箱：从最后一份配置卸载不得带走定义的文件
        TenantSkillCatalogEntity catalog;
        try {
            catalog = upsertCatalogFromBundle(tenantId, bundle, archive, true);
            pointInstallAtCatalog(existingOrRow(tenantId, configId, skillId), catalog);
        } catch (RuntimeException err) {
            log.error("[skill] store bundle failed tenant={} config={} skill={} name={}: {}",
                    tenantId, configId, skillId, bundle.name, err.getMessage());
            failSkill(tenantId, configId, skillId, err.getMessage());
            throw new IllegalStateException("store bundle for skill " + skillId + ": "
                    + err.getMessage(), err);
        }

        publishProgress(tenantId, configId, skillId,
                new SkillProgress(10, "accepted", "", SkillStatus.INSTALLING));

        // 安装活得比 HTTP 请求久，不得继承它的取消；跨重启也不持久——StopSkill 改写行，
        // 卡死 run 的 reaper 是兜底。波 4 接缝：runInstall 的管线体（播种/agent/快照）不翻。
        final String bgSkillId = skillId;
        final SkillBundleParser.SkillBundle bgBundle = bundle;
        Thread.ofVirtual().start(() -> {
            Thread current = Thread.currentThread();
            runCancels.put(runKey(tenantId, configId, bgSkillId), current);
            ReentrantLock lock = keyedLocks.computeIfAbsent(
                    skillImageLockKey(tenantId, configId), k -> new ReentrantLock());
            lock.lock();
            try {
                runInstallPipelineSeam(tenantId, configId, bgSkillId, bgBundle);
            } catch (RuntimeException err) {
                log.error("[skill] install {} failed: {}", bgSkillId, err.getMessage());
            } finally {
                runCancels.remove(runKey(tenantId, configId, bgSkillId), current);
                lock.unlock();
            }
        });
        return skillId;
    }

    private TenantSkillEntity existingOrRow(long tenantId, String configId, String skillId) {
        TenantSkillEntity row = new TenantSkillEntity();
        row.setId(skillId);
        row.setTenantId(tenantId);
        row.setSandboxConfigId(configId);
        return row;
    }

    /**
     * 对照 {@code pointInstallAtCatalog}：把沙箱行挂到持有 zip 的定义上。安装行不复制
     * BundleRef——读取方跟 CatalogID，沙箱卸载不得能删掉定义对象（Go 注释原文）。
     */
    private void pointInstallAtCatalog(TenantSkillEntity skill,
            TenantSkillCatalogEntity catalog) {
        if (skill == null) {
            return;
        }
        if (catalog == null || catalog.getBundleRef().trim().isEmpty()) {
            throw new IllegalStateException("catalog archive is missing");
        }
        TenantSkillEntity row = skills.getSkill(skill.getTenantId(), skill.getSandboxConfigId(),
                skill.getId());
        if (row == null) {
            throw new IllegalStateException("skill " + skill.getId() + " not found");
        }
        String superseded = row.getBundleRef().trim();
        row.setCatalogId(catalog.getId());
        row.setBundleRef("");
        skills.updateSkill(row, now());
        // 本安装现在读定义的副本：之前指向的（前 catalog 时代的对象，或更早替换钉住的
        // 归档）少了一个读者
        if (!superseded.isEmpty() && !superseded.equals(catalog.getBundleRef().trim())) {
            releaseInstallBundle(skill.getTenantId(), superseded);
        }
    }

    /**
     * 对照 {@code releaseInstallBundle}：一行不再命中的归档，除非还有别人命名它才保留。
     * 两张表都查过才删；表读不出来就保留——泄漏一份归档的代价是存储，误删的代价是
     * 一份安装的文件（Go 注释原文）。
     */
    private void releaseInstallBundle(long tenantId, String bundleRef) {
        String ref = bundleRef == null ? "" : bundleRef.trim();
        if (ref.isEmpty()) {
            return;
        }
        try {
            for (TenantSkillCatalogEntity cat : skills.listCatalogsByTenant(tenantId)) {
                if (cat != null && cat.getBundleRef().trim().equals(ref)) {
                    return;
                }
            }
            for (TenantSkillEntity row : skills.listSkillsByTenant(tenantId)) {
                if (row != null && row.getBundleRef().trim().equals(ref)) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.warn("[skill] check holders before releasing bundle {} failed: {}", ref,
                    e.getMessage());
            return;
        }
        deleteBundleBestEffort(tenantId, ref);
    }

    /** 对照 {@code takeSkillRowForInstall}：转写定位符与 error 一并清掉。 */
    private static void takeSkillRowForInstall(TenantSkillEntity row,
            SkillBundleParser.SkillBundle bundle, OffsetDateTime now) {
        row.setVersion(bundle.version);
        row.setDescription(bundle.description);
        row.setInstructions(bundle.instructions);
        row.setBundleSha256(bundle.sha256);
        row.setStatus(SkillStatus.INSTALLING);
        row.setError("");
        row.setInstallingSince(now);
        row.setInstallSessionId("");
        row.setInstallMessageId("");
    }

    /**
     * 波 4 接缝：install 管线体。本批只保留状态机两端——行所有权复核（纯 DB）与
     * 首个 provider 调用（启动维护沙箱）；后者失败时按 Go 的 defer 形态把行置 failed。
     */
    private void runInstallPipelineSeam(long tenantId, String configId, String skillId,
            SkillBundleParser.SkillBundle bundle) {
        try {
            if (!installStillOwnsTheRow(tenantId, configId, skillId, bundle)) {
                return;
            }
            bootMaintenanceSandbox(tenantId, configId, "install");
            // dev 永远到不了这里；后续管线（播种/agent/快照/指针切换）随波 4
        } catch (RuntimeException cause) {
            failSkill(tenantId, configId, skillId, cause.getMessage());
        }
    }

    /**
     * 首个 provider 调用（对照 Go startMaintenanceSession 起的维护沙箱）：
     * dev 无 provider，传输层即失败（RemoteError，UNAVAILABLE 分类）。
     */
    private void bootMaintenanceSandbox(long tenantId, String configId, String operation) {
        TenantSandboxConfigEntity entity = configsHolder.getByID(tenantId, configId);
        if (entity == null) {
            throw new IllegalStateException("sandbox config " + configId + " not found");
        }
        EffectiveConfig base = EffectiveConfig.defaultConfig();
        EffectiveConfig effective =
                EffectiveConfigResolver.resolveEffectiveConfig(entity.getConfig(), base);
        switch (effective.type) {
            case SandboxTypes.TYPE_CUBE, SandboxTypes.TYPE_E2B, SandboxTypes.TYPE_DOCKER -> {
                ConfigSandboxClient client = clientFactory.create(effective);
                if (client instanceof RemoteConfigSandboxClient remote) {
                    remote.createProbeSandbox();
                } else {
                    throw new IllegalStateException(
                            "sandbox execution is disabled for this workspace");
                }
            }
            default -> throw new IllegalStateException(
                    "sandbox execution is disabled for this workspace");
        }
    }

    /**
     * 对照 {@code installStillOwnsTheRow}：remove 赢了锁、StopSkill 翻了行、更新的上传
     * 换了 SHA——任何一种都意味着本 run 不得再快照。ready 行的在图判定走 provider 接缝
     * （不可达 = 不在图 = 继续，与 Go 的 {@code ok == false} 分支一致）。
     */
    private boolean installStillOwnsTheRow(long tenantId, String configId, String skillId,
            SkillBundleParser.SkillBundle bundle) {
        TenantSkillEntity current = skills.getSkill(tenantId, configId, skillId);
        if (current == null) {
            return false;
        }
        if (SkillStatus.REMOVING.equals(current.getStatus())
                || SkillStatus.FAILED.equals(current.getStatus())) {
            return false;
        }
        if (bundle != null && !current.getBundleSha256().isEmpty()
                && !current.getBundleSha256().equals(bundle.sha256)) {
            return false;
        }
        if (SkillStatus.READY.equals(current.getStatus())) {
            // 波 4 接缝：skillFilesInLiveImage 需要 exec 到活沙箱；不可达即“不在图”
            boolean ok = false;
            if (ok) {
                return false;
            }
        }
        return true;
    }

    /** 对照 {@code canSkipInstall}：ready 且仍在图（接缝：不可达 → 不在图）或安装中的心跳。 */
    private boolean canSkipInstall(TenantSkillEntity existing,
            SkillBundleParser.SkillBundle bundle) {
        if (existing == null || bundle == null) {
            return false;
        }
        if (existing.getBundleSha256().isEmpty()
                || !existing.getBundleSha256().equals(bundle.sha256)) {
            return false;
        }
        switch (existing.getStatus()) {
            case SkillStatus.INSTALLING -> {
                return installIsInFlight(existing);
            }
            case SkillStatus.READY -> {
                // 波 4 接缝：在图判定需要活沙箱 exec；本批恒“不在图”
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** 对照 {@code installIsInFlight}：心跳静默超过 3 分钟 = 进程已死。 */
    private boolean installIsInFlight(TenantSkillEntity existing) {
        if (existing == null || existing.getInstallingSince() == null) {
            return false;
        }
        return !existing.getInstallingSince().toInstant()
                .isBefore(now().toInstant().minusMillis(SKILL_INSTALL_IN_FLIGHT_SKIP_MS));
    }

    /** 对照 {@code failSkill}：仍拥有行才写；进度发布 failed。 */
    private void failSkill(long tenantId, String configId, String skillId, String cause) {
        if (!installStillOwnsTheRow(tenantId, configId, skillId, null)) {
            return;
        }
        TenantSkillEntity skill = skills.getSkill(tenantId, configId, skillId);
        if (skill == null) {
            return;
        }
        skill.setStatus(SkillStatus.FAILED);
        skill.setError(cause == null ? "" : cause);
        skill.setInstallingSince(null);
        skills.updateSkill(skill, now());
        publishProgress(tenantId, configId, skillId,
                new SkillProgress(100, "failed", cause == null ? "" : cause, SkillStatus.FAILED));
    }

    // ── catalog upsert（tenant_skill_catalog.go L278-404） ───────────────

    private TenantSkillCatalogEntity upsertCatalogFromBundle(long tenantId,
            SkillBundleParser.SkillBundle bundle, byte[] archive, boolean requireStore) {
        TenantSkillCatalogEntity existing = skills.getCatalogByName(tenantId, bundle.name);
        OffsetDateTime now = now();
        if (existing != null) {
            StoreResult stored = storeCatalogBundle(tenantId, existing, archive);
            if (!stored.stored()) {
                return existing;
            }
            boolean keepOld = pinReplacedCatalogBundle(tenantId, existing, stored.replacement());
            existing.setVersion(bundle.version);
            existing.setDescription(bundle.description);
            existing.setInstructions(bundle.instructions);
            existing.setBundleSha256(bundle.sha256);
            existing.setUpdatedAt(now);
            skills.updateCatalog(existing, now);
            if (!stored.replacement().oldRef().isEmpty() && !stored.replacement().sameDigest()
                    && !keepOld) {
                deleteBundleBestEffort(tenantId, stored.replacement().oldRef());
            }
            return existing;
        }

        TenantSkillCatalogEntity row = new TenantSkillCatalogEntity();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantId);
        row.setName(bundle.name);
        row.setVersion(bundle.version);
        row.setDescription(bundle.description);
        row.setInstructions(bundle.instructions);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        StoreResult stored = storeCatalogBundle(tenantId, row, archive);
        if (stored.stored()) {
            row.setBundleSha256(bundle.sha256);
        }
        try {
            skills.createCatalog(row, now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            TenantSkillCatalogEntity winner = skills.getCatalogByName(tenantId, bundle.name);
            if (winner == null) {
                throw e;
            }
            return upsertCatalogFromBundle(tenantId, bundle, archive, requireStore);
        }
        return row;
    }

    private record StoreResult(boolean stored, CatalogBundleReplacement replacement) {
    }

    private record CatalogBundleReplacement(String oldRef, String oldSHA, boolean sameDigest) {
    }

    private static final StoreResult NOT_STORED = new StoreResult(false,
            new CatalogBundleReplacement("", "", false));

    /**
     * 对照 {@code storeCatalogBundle}：写归档并报告内存中的定义现在是否指向它；
     * 调用方必须先 pin 替换再提交行。
     */
    private StoreResult storeCatalogBundle(long tenantId, TenantSkillCatalogEntity catalog,
            byte[] archive) {
        if (catalog == null || archive == null || archive.length == 0) {
            return NOT_STORED;
        }
        String digest = SkillBundleParser.skillArchiveSHA256(archive);
        String oldRef = catalog.getBundleRef() == null ? "" : catalog.getBundleRef().trim();
        String oldSHA = catalog.getBundleSha256() == null ? "" : catalog.getBundleSha256().trim();
        if (!oldRef.isEmpty() && !oldSHA.isEmpty() && oldSHA.equals(digest)
                && catalogBundleStillHeld(tenantId, catalog)) {
            return new StoreResult(true, new CatalogBundleReplacement("", "", false));
        }
        String ref = bundleStore.save(tenantId,
                "tenant-skills/catalog/" + catalog.getId() + ".zip", archive);
        catalog.setBundleRef(ref);
        if (oldRef.isEmpty() || oldRef.equals(ref)) {
            return new StoreResult(true, new CatalogBundleReplacement("", "", false));
        }
        return new StoreResult(true,
                new CatalogBundleReplacement(oldRef, oldSHA, oldSHA.equals(digest)));
    }

    /** 对照 {@code catalogBundleStillHeld}。 */
    private boolean catalogBundleStillHeld(long tenantId, TenantSkillCatalogEntity catalog) {
        if (catalog == null || catalog.getBundleRef().trim().isEmpty()) {
            return false;
        }
        String want = catalog.getBundleSha256() == null ? "" : catalog.getBundleSha256().trim();
        TenantSkillEntity probe = new TenantSkillEntity();
        probe.setName(catalog.getName());
        probe.setBundleRef(catalog.getBundleRef());
        probe.setBundleSha256(catalog.getBundleSha256());
        byte[] archive = trySkillBundle(tenantId, probe);
        boolean ok = archive != null && archive.length > 0
                && (want.isEmpty() || SkillBundleParser.archiveMatchesSHA(archive, want));
        return ok;
    }

    /** 对照 {@code pinReplacedCatalogBundle}：把旧归档钉到仍在服役它的安装上。 */
    private boolean pinReplacedCatalogBundle(long tenantId, TenantSkillCatalogEntity catalog,
            CatalogBundleReplacement replaced) {
        if (replaced.oldRef().isEmpty() || replaced.sameDigest()) {
            return false;
        }
        if (catalog != null && replaced.oldRef().equals(catalog.getBundleRef().trim())) {
            return false;
        }
        return pinInstallsToReplacedBundle(tenantId, catalog, replaced.oldRef(), replaced.oldSHA());
    }

    private boolean pinInstallsToReplacedBundle(long tenantId, TenantSkillCatalogEntity catalog,
            String oldRef, String oldSHA) {
        if (catalog == null || oldRef.trim().isEmpty()) {
            return false;
        }
        List<TenantSkillEntity> installs = skills.listSkillsByCatalog(tenantId, catalog.getId());
        boolean keep = false;
        for (TenantSkillEntity row : installs) {
            if (row == null || !row.getBundleRef().trim().isEmpty()) {
                continue;
            }
            if (oldSHA.isEmpty()) {
                keep = true;
                continue;
            }
            if (!row.getBundleSha256().trim().equals(oldSHA)) {
                continue;
            }
            row.setBundleRef(oldRef);
            skills.updateSkill(row, now());
            keep = true;
        }
        return keep;
    }

    private void deleteBundleBestEffort(long tenantId, String ref) {
        try {
            bundleStore.delete(tenantId, ref);
        } catch (RuntimeException e) {
            log.warn("[skill] delete bundle {} failed: {}", ref, e.getMessage());
        }
    }

    // ── reinstall（tenant_skill_install.go L234-257） ────────────────────

    /** 对照 {@code ReinstallSkill}：从已存的归档再跑安装，不要求重新上传。 */
    public String reinstallSkill(long tenantId, String configId, String skillId,
            String... instructions) {
        TenantSkillEntity skill = skills.getSkill(tenantId, configId, skillId);
        if (skill == null) {
            throw BizException.notFound("skill not found");
        }
        byte[] archive = skillBundleArchive(tenantId, configId, skillId);
        if (archive == null || archive.length == 0) {
            throw BizException.badRequest(
                    "the archive of this skill is no longer stored; install it again from the original bundle");
        }
        return installSkillArchive(tenantId, configId, archive, instructions);
    }

    // ── stop（tenant_skill_stop.go L102-134） ────────────────────────────

    /**
     * 对照 {@code StopSkill}：中止在途安装让操作员重试或卸载。进程重启后没有协程可取消，
     * 行仍被改写——这就是解锁 UI 的机制。移除不在此列。
     */
    public TenantSkillEntity stopSkill(long tenantId, String configId, String skillId) {
        TenantSkillEntity skill = skills.getSkill(tenantId, configId, skillId);
        if (skill == null) {
            throw BizException.notFound("skill not found");
        }
        switch (skill.getStatus()) {
            case SkillStatus.FAILED -> {
                return skill;
            }
            case SkillStatus.INSTALLING -> {
                // Go 的 switch 空分支：往下走
            }
            default -> throw BizException.badRequest("skill is not installing");
        }

        cancelSkillRun(tenantId, configId, skillId);
        failSkill(tenantId, configId, skillId, SKILL_INSTALL_STOPPED_MESSAGE);

        TenantSkillEntity updated = skills.getSkill(tenantId, configId, skillId);
        if (updated == null) {
            throw BizException.notFound("skill not found");
        }
        return updated;
    }

    private void cancelSkillRun(long tenantId, String configId, String skillId) {
        Thread handle = runCancels.get(runKey(tenantId, configId, skillId));
        if (handle != null) {
            handle.interrupt();
        }
    }

    private static String runKey(long tenantId, String configId, String skillId) {
        return Long.toUnsignedString(tenantId) + ":" + configId + ":" + skillId;
    }

    private static String skillImageLockKey(long tenantId, String configId) {
        return "weknora-skill-image-lock:" + Long.toUnsignedString(tenantId) + ":" + configId;
    }

    // ── remove 入口（tenant_skill_remove.go L23-57） ─────────────────────

    /**
     * 对照 {@code RemoveSkill}：不是纯 DB 操作——文件真的会离开镜像（目录删除后的
     * 新快照）。正常返回即已受理（handler 202）。
     */
    public void removeSkill(long tenantId, String configId, String skillId) {
        TenantSkillEntity skill = skills.getSkill(tenantId, configId, skillId);
        if (skill == null) {
            throw BizException.notFound("skill not found");
        }
        OffsetDateTime now = now();
        skill.setStatus(SkillStatus.REMOVING);
        skill.setError("");
        skill.setInstallingSince(now);
        skills.updateSkill(skill, now);
        publishProgress(tenantId, configId, skillId,
                new SkillProgress(5, "accepted", "", SkillStatus.REMOVING));

        // 与 install 同理：活得比请求久、不继承取消。波 4 接缝：runRemove 的镜像面不翻。
        Thread.ofVirtual().start(() -> {
            Thread current = Thread.currentThread();
            runCancels.put(runKey(tenantId, configId, skillId), current);
            ReentrantLock lock = keyedLocks.computeIfAbsent(
                    skillImageLockKey(tenantId, configId), k -> new ReentrantLock());
            lock.lock();
            try {
                runRemovePipelineSeam(tenantId, configId, skillId);
            } catch (RuntimeException err) {
                log.error("[skill] remove {} failed: {}", skillId, err.getMessage());
            } finally {
                runCancels.remove(runKey(tenantId, configId, skillId), current);
                lock.unlock();
            }
        });
    }

    /**
     * 波 4 接缝：remove 管线体。existing/status 复核（Go 对重复移除的早退）之后
     * 即到首个 provider 调用；失败 → restoreSkillAfterFailedRemoval
     * （Go defer 的补偿写：半移除比保留更糟）。
     */
    private void runRemovePipelineSeam(long tenantId, String configId, String skillId) {
        TenantSkillEntity existing = skills.getSkill(tenantId, configId, skillId);
        if (existing == null) {
            return;
        }
        if (!SkillStatus.REMOVING.equals(existing.getStatus())) {
            return;
        }
        try {
            bootMaintenanceSandbox(tenantId, configId, "remove");
            // dev 永远到不了这里；镜像面（rm 目录/清单/快照/指针/finishRemoval）随波 4
        } catch (RuntimeException cause) {
            restoreSkillAfterFailedRemoval(tenantId, configId, skillId, cause.getMessage());
        }
    }

    /** 对照 {@code restoreSkillAfterFailedRemoval}：把借来的行放回去。 */
    private void restoreSkillAfterFailedRemoval(long tenantId, String configId, String skillId,
            String cause) {
        TenantSkillEntity current = skills.getSkill(tenantId, configId, skillId);
        if (current == null || !SkillStatus.REMOVING.equals(current.getStatus())) {
            return;
        }
        String status = SkillStatus.FAILED;
        // 到过镜像的 skill 仍在镜像里 → 回 ready 可重试；没到过的回 ready 会把 agent
        // 指向不存在的文件（Go 注释原文）
        if (!current.getInstalledSnapshotId().isEmpty()) {
            status = SkillStatus.READY;
        }
        current.setStatus(status);
        current.setError(cause == null ? "" : cause);
        current.setInstallingSince(null);
        skills.updateSkill(current, now());
        publishProgress(tenantId, configId, skillId,
                new SkillProgress(100, "failed", cause == null ? "" : cause, status));
    }

    // ── 文件浏览（tenant_skill_files.go） ────────────────────────────────

    /** 对照 {@code ListSkillFiles}：文件来自上传的 bundle 而非活镜像（不启沙箱）。 */
    public List<SkillBundleParser.SkillFileEntry> listSkillFiles(long tenantId, String configId,
            String skillId) {
        byte[] archive = skillBundleArchive(tenantId, configId, skillId);
        return SkillBundleParser.listSkillZipFiles(archive);
    }

    /** 对照 {@code ReadSkillFile}。 */
    public SkillBundleParser.SkillFileContent readSkillFile(long tenantId, String configId,
            String skillId, String relativePath) {
        String clean;
        try {
            clean = SkillBundleParser.safeSkillFilePath(relativePath);
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest(e.getMessage());
        }
        byte[] archive = skillBundleArchive(tenantId, configId, skillId);
        byte[] body;
        try {
            body = SkillBundleParser.readSkillZipFile(archive, clean);
        } catch (SkillBundleParser.SkillFileNotFoundException e) {
            throw BizException.notFound("skill file not found");
        }
        return SkillBundleParser.projectSkillFileContent(clean, body);
    }

    /**
     * 对照 {@code skillBundleArchive}：行自指的对象优先（无摘要检查）；否则定义持有 zip，
     * 且只在摘要一致时对本安装有效；都不可用 → 404 "skill files are not available"。
     */
    private byte[] skillBundleArchive(long tenantId, String configId, String skillId) {
        TenantSkillEntity skill = skills.getSkill(tenantId, configId, skillId);
        if (skill == null) {
            throw BizException.notFound("skill not found");
        }
        byte[] direct = trySkillBundle(tenantId, skill);
        if (direct != null) {
            return direct;
        }
        byte[] sameDigest = sameDigestCatalogArchive(tenantId, skill);
        if (sameDigest != null && sameDigest.length > 0) {
            return sameDigest;
        }
        if (skill.getBundleSha256().trim().isEmpty()) {
            byte[] any = anyCatalogArchiveFor(tenantId, skill);
            if (any != null && any.length > 0) {
                return any;
            }
        }
        throw BizException.notFound("skill files are not available");
    }

    private byte[] anyCatalogArchiveFor(long tenantId, TenantSkillEntity skill) {
        String cid = skill.getCatalogId().trim();
        if (!cid.isEmpty()) {
            byte[] archive = loadCatalogArchive(tenantId, cid);
            if (archive != null && archive.length > 0) {
                return archive;
            }
        }
        return loadCatalogArchive(tenantId, skill.getId());
    }

    private byte[] sameDigestCatalogArchive(long tenantId, TenantSkillEntity skill) {
        if (skill.getBundleSha256().trim().isEmpty()) {
            return null;
        }
        String cid = skill.getCatalogId().trim();
        if (cid.isEmpty()) {
            cid = skill.getId();
        }
        return loadCatalogArchive(tenantId, cid);
    }

    /** 对照 {@code trySkillBundle}：不可用时返回 null（对照 Go 的 ok=false）。 */
    private byte[] trySkillBundle(long tenantId, TenantSkillEntity skill) {
        if (skill == null || skill.getBundleRef().trim().isEmpty()) {
            return null;
        }
        try {
            byte[] archive = bundleStore.load(tenantId, skill.getBundleRef().trim());
            if (archive == null || archive.length == 0) {
                return null;
            }
            return archive;
        } catch (RuntimeException e) {
            log.debug("[skill] bundle {} unavailable: {}", skill.getBundleRef(), e.getMessage());
            return null;
        }
    }

    private byte[] loadCatalogArchive(long tenantId, String catalogId) {
        TenantSkillCatalogEntity catalog = skills.getCatalogByID(tenantId, catalogId);
        if (catalog == null) {
            return null;
        }
        return trySkillBundle(tenantId, catalogToProbe(catalog));
    }

    private static TenantSkillEntity catalogToProbe(TenantSkillCatalogEntity catalog) {
        TenantSkillEntity probe = new TenantSkillEntity();
        probe.setName(catalog.getName());
        probe.setBundleRef(catalog.getBundleRef());
        probe.setBundleSha256(catalog.getBundleSha256());
        return probe;
    }

    // ── guidance / steer（tenant_skill_steer.go L42-152） ────────────────

    /** 对照 {@code installSteerSession}：独立命名空间，普通聊天的 steering 到不了维护 shell。 */
    static String installSteerSession(String sessionId) {
        return "skill-install:" + sessionId;
    }

    /** 对照 {@code SkillInstallGuidance}。 */
    public record SkillInstallGuidance(
            @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
            @com.fasterxml.jackson.annotation.JsonProperty("content") String content,
            @com.fasterxml.jackson.annotation.JsonProperty("status") String status) {
    }

    /** 对照 {@code SkillInstallGuidanceState}（messages 恒输出：空时 []）。 */
    public record SkillInstallGuidanceState(
            @com.fasterxml.jackson.annotation.JsonProperty("accepting") boolean accepting,
            @com.fasterxml.jackson.annotation.JsonProperty("messages")
            List<SkillInstallGuidance> messages) {
    }

    /** 对照 {@code InstallGuidance}：只暴露本租户/配置/skill 当前 run 的指引。 */
    public SkillInstallGuidanceState installGuidance(long tenantId, String configId,
            String skillId) {
        TenantSkillEntity skill = skills.getSkill(tenantId, configId, skillId);
        if (skill == null) {
            throw BizException.notFound("skill not found");
        }
        List<SkillInstallGuidance> messages = new ArrayList<>();
        SkillInstallGuidanceState result = new SkillInstallGuidanceState(false, messages);
        if (streams == null || skill.getInstallSessionId().isEmpty()
                || skill.getInstallMessageId().isEmpty()) {
            return result;
        }
        synchronized (steerLock(skill.getInstallSessionId())) {
            var live = streams.getLiveRun(installSteerSession(skill.getInstallSessionId()));
            boolean liveMatches = live != null
                    && skill.getInstallMessageId().equals(live.assistantMessageId());
            boolean accepting = SkillStatus.INSTALLING.equals(skill.getStatus()) && liveMatches;
            var batch = streams.getSteerEvents(skill.getInstallSessionId(),
                    skill.getInstallMessageId(), 0);
            for (com.ragagent.stream.StreamEvent evt : batch.events()) {
                String status = "pending";
                boolean consumed = evt.getData() != null
                        && Boolean.TRUE.equals(evt.getData().get("consumed"));
                if (consumed) {
                    status = "injected";
                } else if (!accepting) {
                    status = "unprocessed";
                }
                messages.add(new SkillInstallGuidance(evt.getId(), evt.getContent(), status));
            }
            return new SkillInstallGuidanceState(accepting, messages);
        }
    }

    /** 对照 {@code SteerInstall}：为精确期望的安装 run 排队一条管理员指令。 */
    public void steerInstall(long tenantId, String configId, String skillId,
            String expectedMessageId, String steerId, String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()
                || trimmed.codePointCount(0, trimmed.length()) > 10000) {
            throw BizException.badRequest("guidance must contain 1 to 10000 characters");
        }
        if (!isValidUUID(steerId) || expectedMessageId == null || expectedMessageId.isEmpty()) {
            throw BizException.badRequest("steer_id and expected_message_id are required");
        }
        TenantSkillEntity skill = skills.getSkill(tenantId, configId, skillId);
        if (skill == null) {
            throw BizException.notFound("skill not found");
        }
        if (streams == null) {
            throw BizException.serviceUnavailable("install guidance is unavailable");
        }
        if (skill.getInstallSessionId().isEmpty()
                || !skill.getInstallMessageId().equals(expectedMessageId)) {
            throw BizException.conflict(
                    "the install run changed; refresh before sending guidance");
        }
        synchronized (steerLock(skill.getInstallSessionId())) {
            // 发送/关闭锁内重读，含 durable 终态
            TenantSkillEntity current = skills.getSkill(tenantId, configId, skillId);
            if (current == null || !current.getInstallMessageId().equals(expectedMessageId)) {
                throw BizException.conflict("the install run changed");
            }
            var batch = streams.getSteerEvents(skill.getInstallSessionId(), expectedMessageId, 0);
            int pending = 0;
            for (com.ragagent.stream.StreamEvent evt : batch.events()) {
                if (evt.getId().equals(steerId)) {
                    if (!evt.getContent().equals(trimmed)) {
                        throw BizException.conflict("steer_id already belongs to another message");
                    }
                    return; // HTTP 重试不得追加两次
                }
                boolean consumed = evt.getData() != null
                        && Boolean.TRUE.equals(evt.getData().get("consumed"));
                if (!consumed) {
                    pending++;
                }
            }
            var live = streams.getLiveRun(installSteerSession(skill.getInstallSessionId()));
            boolean liveMatches = live != null
                    && expectedMessageId.equals(live.assistantMessageId());
            if (!SkillStatus.INSTALLING.equals(current.getStatus()) || !liveMatches) {
                throw BizException.conflict(
                        "installer is not accepting input; wait for completion and reinstall with instructions");
            }
            if (pending >= 10 || batch.events().size() >= 100) {
                throw BizException.badRequest("too many install guidance messages");
            }
            Map<String, Object> data = new HashMap<>();
            data.put("delivery", "inject");
            com.ragagent.stream.StreamEvent evt = new com.ragagent.stream.StreamEvent();
            evt.setId(steerId);
            evt.setContent(trimmed);
            evt.setTimestamp(OffsetDateTime.now());
            evt.setData(data);
            streams.appendSteerEvents(skill.getInstallSessionId(), expectedMessageId,
                    List.of(evt));
        }
    }

    private final Map<String, Object> steerLocks = new ConcurrentHashMap<>();

    private Object steerLock(String sessionId) {
        return steerLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    /**
     * 对照 google/uuid 的 {@code Parse}：规范 36 字符、32 hex、urn:、花括号形态都收。
     */
    static boolean isValidUUID(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.startsWith("{") && t.endsWith("}") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        if (t.startsWith("urn:uuid:")) {
            t = t.substring("urn:uuid:".length());
        }
        if (t.length() == 36) {
            try {
                UUID.fromString(t);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (t.length() == 32 && t.chars().allMatch(c -> Character.isDigit(c)
                || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            return true;
        }
        return false;
    }

    // ── 源 sentinel（对照 ErrSkillSourceInvalid） ────────────────────────

    /** 对照 {@code ErrSkillSourceInvalid}：registry/git/URL source 校验一切拒绝的标记。 */
    public static final String SENTINEL_SKILL_SOURCE_INVALID = "skill source is invalid";

    /** registry/git/URL source 校验的一切拒绝（handler 升 400）。 */
    public static class SkillSourceInvalidException extends RuntimeException {
        public SkillSourceInvalidException(String message) {
            super(message);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 波 3 子批 4：/skills 家族（skill_handler.go + skill_catalog.go +
    // tenant_skill_catalog.go L42-165 / L492-642 + tenant_skill_effective.go）
    // ══════════════════════════════════════════════════════════════════════

    // ── usable skills（skill_handler.go 的 ListUsableSkills 面） ──────────

    /**
     * 对照 {@code ListUsableSkills} → {@code effectiveTenantSkills}：一次聊天真正能
     * 调用的已安装技能（ready、启用、且快照是会话真正启动的镜像）。任何失败都返回空
     * ——@ 提及不得因 skill 查询失败而 500（Go 注释原文）。
     */
    public List<TenantSkillEntity> listUsableSkills(long tenantId, String configId) {
        if (configId == null || configId.isEmpty() || tenantId == 0) {
            return List.of();
        }
        TenantSandboxConfigEntity cfgEntity = configsHolder.getByID(tenantId, configId);
        if (cfgEntity == null || cfgEntity.getConfig() == null) {
            return List.of();
        }
        if (!skillImageActive(cfgEntity.getConfig())) {
            return List.of();
        }
        List<TenantSkillEntity> rows;
        try {
            rows = skills.listSkillsByConfig(tenantId, configId);
        } catch (RuntimeException e) {
            log.warn("[skill] list skills of sandbox config {} for skill injection failed: {}",
                    configId, e.getMessage());
            return List.of();
        }
        List<TenantSkillEntity> usable = new ArrayList<>(rows.size());
        for (TenantSkillEntity row : rows) {
            if (row == null || !row.isEnabled() || !SkillStatus.READY.equals(row.getStatus())) {
                continue;
            }
            usable.add(row);
        }
        return usable;
    }

    /** 对照 {@code sandbox.SkillImageActive}（skill_image.go L33-65 的直译）。 */
    static boolean skillImageActive(com.ragagent.sandbox.domain.TenantSandboxConfig cfg) {
        if (cfg == null) {
            return false;
        }
        return switch (cfg.getSandboxType() == null ? "" : cfg.getSandboxType()) {
            case SandboxTypes.TYPE_CUBE -> cfg.getCube() != null
                    && !com.ragagent.sandbox.runtime.SkillImageSupport.skillImageTemplateOverride(cfg.getSkillImage(),
                            "cube", cfg.getCube().getApiKey(), cfg.getCube().getApiUrl()).isEmpty();
            case SandboxTypes.TYPE_E2B -> cfg.getE2b() != null
                    && !com.ragagent.sandbox.runtime.SkillImageSupport.skillImageTemplateOverride(cfg.getSkillImage(),
                            "e2b", cfg.getE2b().getApiKey(), cfg.getE2b().getApiUrl()).isEmpty();
            case SandboxTypes.TYPE_DOCKER ->
                    !com.ragagent.sandbox.runtime.SkillImageSupport.dockerSkillImageOverride(cfg).isEmpty();
            default -> false;
        };
    }

    // ── catalog 列表（tenant_skill_catalog.go L42-165） ──────────────────

    /**
     * 对照 {@code ListCatalog}：工作区的每份 skill 定义 + 它在各沙箱上的安装。
     * catalog 行在前（created_at ASC）；孤儿安装（catalog 行已删/从未存在）与
     * catalog 之前的直装行并成合成定义补在后面。
     */
    public List<SkillCatalogView> listCatalog(long tenantId) {
        List<TenantSkillCatalogEntity> catalogs = skills.listCatalogsByTenant(tenantId);
        List<TenantSkillEntity> installs = skills.listSkillsByTenant(tenantId);
        List<TenantSandboxConfigEntity> configs = configMapper.listByTenant(tenantId);
        Map<String, TenantSandboxConfigEntity> configByID =
                new HashMap<>(configs.size() * 2);
        for (TenantSandboxConfigEntity cfg : configs) {
            if (cfg != null) {
                configByID.put(cfg.getId(), cfg);
            }
        }

        // LinkedHashMap：Go 的 map 遍历序随机，Java 侧固定为 tenant 行序（单条目下等价）
        Map<String, List<TenantSkillEntity>> byCatalog = new LinkedHashMap<>();
        List<TenantSkillEntity> unattached = new ArrayList<>();
        for (TenantSkillEntity row : installs) {
            if (row == null) {
                continue;
            }
            if (!row.getCatalogId().isEmpty()) {
                byCatalog.computeIfAbsent(row.getCatalogId(), k -> new ArrayList<>()).add(row);
                continue;
            }
            unattached.add(row);
        }

        List<SkillCatalogView> out =
                new ArrayList<>(catalogs.size() + unattached.size());
        Set<String> seenName = new HashSet<>();
        Set<String> seenCatalog = new HashSet<>();
        for (TenantSkillCatalogEntity cat : catalogs) {
            if (cat == null) {
                continue;
            }
            seenName.add(cat.getName());
            seenCatalog.add(cat.getId());
            out.add(SkillCatalogView.catalogView(cat,
                    byCatalog.getOrDefault(cat.getId(), List.of()), configByID));
        }
        // catalog 行被删（或从未存在）的安装否则会消失：它们既非 unattached
        // （catalog_id 有值）也不在任何活定义名下渲染（Go 注释原文）
        for (Map.Entry<String, List<TenantSkillEntity>> e : byCatalog.entrySet()) {
            if (seenCatalog.contains(e.getKey())) {
                continue;
            }
            unattached.addAll(e.getValue());
        }
        // catalog_id 之前时代的行（或测试直插的安装）仍要作为定义出现，
        // 设置页才完整（Go 注释原文）
        for (TenantSkillEntity row : unattached) {
            if (seenName.contains(row.getName())) {
                for (SkillCatalogView view : out) {
                    if (view.name().equals(row.getName())) {
                        view.installations()
                                .add(SkillCatalogView.installView(row, configByID));
                    }
                }
                continue;
            }
            seenName.add(row.getName());
            TenantSkillCatalogEntity synthetic = new TenantSkillCatalogEntity();
            synthetic.setId(row.getId());
            synthetic.setTenantId(row.getTenantId());
            synthetic.setName(row.getName());
            synthetic.setVersion(row.getVersion());
            synthetic.setDescription(row.getDescription());
            synthetic.setBundleSha256(row.getBundleSha256());
            synthetic.setCreatedAt(row.getCreatedAt());
            synthetic.setUpdatedAt(row.getUpdatedAt());
            out.add(SkillCatalogView.catalogView(synthetic, List.of(row), configByID));
        }
        return out;
    }

    // ── catalog 注册（L167-188 + upsertCatalogFromBundle 复用子批 3） ────

    /** 对照 {@code RegisterCatalogFromArchive}：只记定义不安装；同名重传更新存量。 */
    public TenantSkillCatalogEntity registerCatalogFromArchive(long tenantId, byte[] archive) {
        SkillBundleParser.SkillBundle bundle = SkillBundleParser.parseSkillBundle(archive);
        return upsertCatalogFromBundle(tenantId, bundle, archive, true);
    }

    /**
     * 对照 {@code RegisterCatalogFromSource}：抓取公开 skill 并记入 catalog。
     * 本批只翻译到 SSRF 校验层——校验通过后的真实抓取随 registry 安装面（波 4 接缝），
     * dev 的 fake-ip DNS 让一切公网域名在校验层即被拒（golden slk-catalog-register-src）。
     */
    public TenantSkillCatalogEntity registerCatalogFromSource(long tenantId, String source) {
        SkillSource.Parsed parsed = SkillSource.parse(source);
        byte[] archive = fetchSkillArchive(parsed.directURL());
        SkillBundleParser.SkillBundle bundle = SkillBundleParser.parseSkillBundle(archive);
        return upsertCatalogFromBundle(tenantId, bundle, archive, true);
    }

    /**
     * 对照 {@code getSkillURL} 的前半段：出站前先过 SSRF 校验；拒绝消息 =
     * {@code skill source is invalid: <FormatSSRFError>}（Go 原文形态）。
     * 波 4 接缝：校验通过后的 HTTP GET（handoff/重定向/限额）不翻——该分支要求
     * 白名单放行的出站 URL，dev 无网等价不可达。
     */
    private byte[] fetchSkillArchive(String rawURL) {
        com.ragagent.common.security.SsrfGuard guard =
                new com.ragagent.common.security.SsrfGuard();
        try {
            guard.validateURLForSSRF(rawURL);
        } catch (com.ragagent.common.security.SsrfGuard.SsrfException e) {
            throw new SkillSourceInvalidException(SENTINEL_SKILL_SOURCE_INVALID + ": "
                    + guard.formatSSRFError("skill source", rawURL, e));
        }
        throw new SkillSourceInvalidException(SENTINEL_SKILL_SOURCE_INVALID
                + ": download failed: skill source fetch is not available in this deployment");
    }

    // ── catalog 安装（L190-243） ─────────────────────────────────────────

    /** 对照 {@code CatalogInstallResult}：部分成功也是 202（errors 里逐配置报错）。 */
    public record CatalogInstallResult(Map<String, String> installs, Map<String, String> errors) {
    }

    /**
     * 对照 {@code InstallCatalogToConfigs}：把 catalog skill 用既有镜像安装管线
     * 装到每个具名沙箱。缺失的配置逐个报错跳过；部分成功照常返回，调用方才能
     * 显示每配置状态（Go 注释原文）。
     */
    public CatalogInstallResult installCatalogToConfigs(long tenantId, String catalogId,
            List<String> configIDs) {
        TenantSkillCatalogEntity catalog = resolveCatalog(tenantId, catalogId);
        byte[] archive = catalogBundleArchive(tenantId, catalog);

        List<String> ids = uniqueNonEmptyStrings(configIDs);
        if (ids.isEmpty()) {
            throw BizException.badRequest("at least one sandbox is required");
        }

        // Go 的 json.Marshal 对 map 恒按键字母序输出——TreeMap 对齐多配置请求
        Map<String, String> resultInstalls = new TreeMap<>();
        Map<String, String> errors = new TreeMap<>();
        RuntimeException firstErr = null;
        for (String configId : ids) {
            try {
                String skillId = installSkill(tenantId, configId, archive);
                resultInstalls.put(configId, skillId);
            } catch (RuntimeException installErr) {
                log.warn("[skill] install catalog {} onto config {} failed: {}",
                        catalogId, configId, installErr.getMessage());
                errors.put(configId, skillUserErrorMessage(installErr));
                if (firstErr == null) {
                    firstErr = installErr;
                }
            }
        }
        if (resultInstalls.isEmpty()) {
            throw firstErr;
        }
        return new CatalogInstallResult(resultInstalls, errors);
    }

    /** 对照 {@code skillUserErrorMessage}：AppError 取消息，其余取 toString。 */
    private static String skillUserErrorMessage(RuntimeException err) {
        if (err instanceof BizException biz && !biz.appError().message().isEmpty()) {
            return biz.appError().message();
        }
        String msg = err.getMessage();
        return msg == null ? err.toString() : msg;
    }

    // ── catalog 删除（L245-276） ─────────────────────────────────────────

    /**
     * 对照 {@code DeleteCatalog}：只删没有剩余安装的定义并丢弃存量 zip。
     * 沙箱卸载永不删这份 zip（Go 注释原文）。
     */
    public void deleteCatalog(long tenantId, String catalogId) {
        TenantSkillCatalogEntity catalog = skills.getCatalogByID(tenantId, catalogId);
        if (catalog == null) {
            throw BizException.notFound("skill not found");
        }
        List<TenantSkillEntity> installs = skills.listSkillsByCatalog(tenantId, catalogId);
        for (TenantSkillEntity row : installs) {
            if (row == null) {
                continue;
            }
            throw BizException.conflict(
                    "remove this skill from every sandbox before deleting it from the catalog");
        }
        String ref = catalog.getBundleRef() == null ? "" : catalog.getBundleRef().trim();
        skills.deleteCatalog(tenantId, catalogId, now());
        if (!ref.isEmpty()) {
            deleteBundleBestEffort(tenantId, ref);
        }
    }

    // ── catalog 文件浏览（L544-642） ─────────────────────────────────────

    /** 对照 {@code ListCatalogFiles}：文件属 skill 定义，不属于某次沙箱安装。 */
    public List<SkillBundleParser.SkillFileEntry> listCatalogFiles(long tenantId,
            String catalogId) {
        byte[] archive = loadCatalogDefinitionArchive(tenantId, catalogId);
        return SkillBundleParser.listSkillZipFiles(archive);
    }

    /** 对照 {@code ReadCatalogFile}。 */
    public SkillBundleParser.SkillFileContent readCatalogFile(long tenantId, String catalogId,
            String relativePath) {
        String clean;
        try {
            clean = SkillBundleParser.safeSkillFilePath(relativePath);
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest(e.getMessage());
        }
        byte[] archive = loadCatalogDefinitionArchive(tenantId, catalogId);
        byte[] body;
        try {
            body = SkillBundleParser.readSkillZipFile(archive, clean);
        } catch (SkillBundleParser.SkillFileNotFoundException e) {
            throw BizException.notFound("skill file not found");
        }
        return SkillBundleParser.projectSkillFileContent(clean, body);
    }

    /** 对照 {@code loadCatalogArchive}（catalog 入口；安装行的同名读取面见上方）。 */
    private byte[] loadCatalogDefinitionArchive(long tenantId, String catalogId) {
        TenantSkillCatalogEntity catalog = resolveCatalog(tenantId, catalogId);
        return catalogBundleArchive(tenantId, catalog);
    }

    /**
     * 对照 {@code catalogBundleArchive}：定义自有对象优先；其次该 catalog 的安装行；
     * 再次全租户里 ID 或同名行（都过摘要校验）。全不可用 → 400（Go 措辞照抄）。
     */
    private byte[] catalogBundleArchive(long tenantId, TenantSkillCatalogEntity catalog) {
        if (catalog == null) {
            throw BizException.notFound("skill not found");
        }
        String wantSHA = catalog.getBundleSha256() == null ? "" : catalog.getBundleSha256().trim();

        if (!catalog.getBundleRef().trim().isEmpty()) {
            byte[] archive = tryCatalogRow(tenantId, catalogToProbe(catalog), wantSHA);
            if (archive != null) {
                return archive;
            }
        }
        for (TenantSkillEntity row : skills.listSkillsByCatalog(tenantId, catalog.getId())) {
            byte[] archive = tryCatalogRow(tenantId, row, wantSHA);
            if (archive != null) {
                return archive;
            }
        }
        for (TenantSkillEntity row : skills.listSkillsByTenant(tenantId)) {
            if (row == null || (!row.getId().equals(catalog.getId())
                    && !row.getName().equals(catalog.getName()))) {
                continue;
            }
            byte[] archive = tryCatalogRow(tenantId, row, wantSHA);
            if (archive != null) {
                return archive;
            }
        }
        throw BizException.badRequest(
                "the archive of this skill is no longer stored; add it again from the original bundle");
    }

    /** tryRow 闭包的直译：读得到且摘要一致才算数。 */
    private byte[] tryCatalogRow(long tenantId, TenantSkillEntity row, String wantSHA) {
        if (row == null || row.getBundleRef().trim().isEmpty()) {
            return null;
        }
        byte[] archive = trySkillBundle(tenantId, row);
        if (archive == null || archive.length == 0) {
            return null;
        }
        if (!wantSHA.isEmpty() && !SkillBundleParser.archiveMatchesSHA(archive, wantSHA)) {
            return null;
        }
        return archive;
    }

    // ── catalog 解析（L492-542） ─────────────────────────────────────────

    /**
     * 对照 {@code resolveCatalog}：catalog 行优先；ID 落在安装行（行 ID 或其
     * catalog_id）上时投影成定义——同一定义的两个名字都能被解析。
     */
    private TenantSkillCatalogEntity resolveCatalog(long tenantId, String id) {
        String trimmed = id == null ? "" : id.trim();
        if (trimmed.isEmpty()) {
            throw BizException.notFound("skill not found");
        }
        TenantSkillCatalogEntity cat = skills.getCatalogByID(tenantId, trimmed);
        if (cat != null) {
            return cat;
        }
        TenantSkillEntity match = null;
        for (TenantSkillEntity row : skills.listSkillsByTenant(tenantId)) {
            if (row != null && (row.getId().equals(trimmed)
                    || row.getCatalogId().equals(trimmed))) {
                match = row;
                break;
            }
        }
        if (match == null) {
            throw BizException.notFound("skill not found");
        }
        String cid = match.getCatalogId().trim();
        if (!cid.isEmpty() && !cid.equals(trimmed)) {
            TenantSkillCatalogEntity byInstall = skills.getCatalogByID(tenantId, cid);
            if (byInstall != null) {
                return byInstall;
            }
        }
        return catalogProjectionFromSkill(match);
    }

    /** 对照 {@code catalogProjectionFromSkill}。 */
    private static TenantSkillCatalogEntity catalogProjectionFromSkill(TenantSkillEntity row) {
        TenantSkillCatalogEntity projection = new TenantSkillCatalogEntity();
        projection.setId(row.getId());
        projection.setTenantId(row.getTenantId());
        projection.setName(row.getName());
        projection.setVersion(row.getVersion());
        projection.setDescription(row.getDescription());
        projection.setInstructions(row.getInstructions());
        projection.setBundleRef(row.getBundleRef());
        projection.setBundleSha256(row.getBundleSha256());
        projection.setCreatedAt(row.getCreatedAt());
        projection.setUpdatedAt(row.getUpdatedAt());
        return projection;
    }

    // ── 本批的小工具 ─────────────────────────────────────────────────────

    /** 对照 {@code uniqueNonEmptyStrings}（保序去重）。 */
    private static List<String> uniqueNonEmptyStrings(List<String> in) {
        if (in == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }
}
