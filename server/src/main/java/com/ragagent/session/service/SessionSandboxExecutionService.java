package com.ragagent.session.service;

import com.ragagent.agent.skills.Manager;
import com.ragagent.agent.skills.TenantSkillSource;
import com.ragagent.agent.tools.EditSandboxFileTool;
import com.ragagent.agent.tools.ListSandboxFilesTool;
import com.ragagent.agent.tools.ReadFileTool;
import com.ragagent.agent.tools.RemoteDirEntry;
import com.ragagent.agent.tools.RemoteStatEntry;
import com.ragagent.agent.tools.SandboxCommandExecutor;
import com.ragagent.agent.tools.SandboxExecuteResult;
import com.ragagent.agent.tools.SandboxFileEditor;
import com.ragagent.agent.tools.SandboxFileSink;
import com.ragagent.agent.tools.SandboxFileSource;
import com.ragagent.agent.tools.ShellExecTool;
import com.ragagent.agent.tools.SkillEnvironment;
import com.ragagent.agent.tools.ToolDefinitions;
import com.ragagent.agent.tools.ToolRegistry;
import com.ragagent.agent.tools.WriteSandboxFileTool;
import com.ragagent.sandbox.domain.TenantSkillEntity;
import com.ragagent.sandbox.runtime.DisabledSandboxManager;
import com.ragagent.sandbox.runtime.SandboxManager;
import com.ragagent.sandbox.runtime.SandboxSessionClient;
import com.ragagent.sandbox.runtime.SandboxTypes;
import com.ragagent.sandbox.runtime.SessionBoundManager;
import com.ragagent.sandbox.service.TenantSandboxConfigService;
import com.ragagent.sandbox.service.TenantSandboxResolverService;
import com.ragagent.sandbox.service.TenantSkillService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话沙箱执行装配（对照 Go agent_service.go 的 registerSandboxShellIfAllowed /
 * registerSandboxFileTools / initializeSkillsManager / tenantSkillSource +
 * session_sandbox_pin.go 的 resolveSandboxForExecution + tenant_skill_effective.go
 * 的 skillsForRun）。
 *
 * <p>一次聊天回合的沙箱装配全在这里：解析会话绑定的工作区配置 → 注册
 * shell/文件工具 → 构建 skills 管理器并绑定到工具。解析失败降级为"无沙箱回合"
 * （工具不注册，聊天不因沙箱不可达失败）；配置级错误（config not found /
 * cordoned）按 Go 原文透出。</p>
 */
@Service
public class SessionSandboxExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SessionSandboxExecutionService.class);

    private final TenantSandboxResolverService resolver;
    private final TenantSandboxConfigService configService;
    private final TenantSkillService skillService;
    private final SessionSandboxPinnerService pinner;
    private final com.ragagent.sandbox.service.UserEnvService userEnvService;

    public SessionSandboxExecutionService(TenantSandboxResolverService resolver,
            TenantSandboxConfigService configService, TenantSkillService skillService,
            SessionSandboxPinnerService pinner,
            com.ragagent.sandbox.service.UserEnvService userEnvService) {
        this.resolver = resolver;
        this.configService = configService;
        this.skillService = skillService;
        this.pinner = pinner;
        this.userEnvService = userEnvService;
    }

    /** 解析结果：管理器 + 实际生效的配置 ID（pin 竞速后可能与 agent 选择不同）。 */
    public record Resolution(SandboxManager manager, String configId) {
    }

    /**
     * 对照 {@code resolveSandboxForExecution}（session_sandbox_pin.go L146-193）：
     * 会话已 pin → 以 pin 为准；否则用 agent 的配置；具名后端解析成功后 Pin 认领
     * （CAS，败者采纳赢者的配置并按它重新解析——产物收集与销毁都以 pin 为准）。
     */
    public Resolution resolveForExecution(long tenantId, String sessionId, String agentConfigId) {
        if (tenantId != 0 && sessionId != null && !sessionId.isBlank()) {
            String pinned = pinner.read(sessionId);
            if (!pinned.isBlank()) {
                return new Resolution(resolveTenant(tenantId, pinned), pinned);
            }
        }
        String configId = agentConfigId == null ? "" : agentConfigId.strip();
        SandboxManager mgr = resolveTenant(tenantId, configId);
        if (mgr == null) {
            return new Resolution(null, configId);
        }
        // Named backends keep a session-scoped sandbox; artifact collection and
        // teardown resolve that sandbox from this pin (Go L176-181 注释原文).
        if (!SandboxTypes.TYPE_DOCKER.equals(mgr.getType())) {
            return new Resolution(mgr, configId);
        }
        if (sessionId == null || sessionId.isBlank()) {
            return new Resolution(mgr, configId);
        }
        String winner = pinner.pin(sessionId, configId);
        if (winner.equals(configId)) {
            return new Resolution(mgr, winner);
        }
        return new Resolution(resolveTenant(tenantId, winner), winner);
    }

    /** ①工作区 kill switch ②空配置 ③具名配置不静默回落（Go tenant_sandbox_resolve.go L70-110）。 */
    private SandboxManager resolveTenant(long tenantId, String configId) {
        if (tenantId != 0) {
            try {
                if (configService.workspaceScriptsDisabled(tenantId)) {
                    return new DisabledSandboxManager();
                }
            } catch (RuntimeException e) {
                log.warn("[sandbox] failed to read workspace sandbox policy for {}: {}",
                        tenantId, e.getMessage());
            }
        }
        if (configId == null || configId.isEmpty()
                || configId.equals(com.ragagent.sandbox.domain.SandboxConstants
                        .SANDBOX_CONFIG_ID_GLOBAL_DEFAULT)) {
            return new DisabledSandboxManager();
        }
        if (tenantId == 0) {
            throw new IllegalStateException(
                    "sandbox: resolve config \"" + configId + "\": missing workspace context");
        }
        return resolver.resolve(tenantId, configId);
    }

    /**
     * 对照 {@code skillsForRun}（tenant_skill_effective.go L35-55）：先看会话已
     * pin 的配置（与沙箱解析同路径），技能集来自 {@code listUsableSkills}
     * （ready + enabled + 快照即启动镜像）。
     */
    public RunSkills skillsForRun(long tenantId, String sessionId, String agentConfigId) {
        String configId = agentConfigId == null ? "" : agentConfigId;
        try {
            String pinned = sessionId == null || sessionId.isBlank()
                    ? "" : pinner.read(sessionId);
            if (!pinned.isBlank()) {
                configId = pinned;
            }
        } catch (RuntimeException e) {
            log.warn("[skill] read the pinned sandbox config of session {} failed: {}",
                    sessionId, e.getMessage());
            return new RunSkills("", List.of());
        }
        return new RunSkills(configId, skillService.listUsableSkills(tenantId, configId));
    }

    public record RunSkills(String configId, List<TenantSkillEntity> rows) {
    }

    /**
     * 对照 {@code tenantSkillSource}（agent_service.go L633-656）：本轮沙箱镜像里
     * 已安装技能的 source；行来自调用者工作区，bundle 下载按行自身记的租户。
     */
    public TenantSkillSource tenantSkillSource(List<TenantSkillEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        long ownerTenantId = rows.get(0).getTenantId();
        String configId = rows.get(0).getSandboxConfigId();
        return new TenantSkillSource(rows, row -> {
            if (row == null) {
                throw new IllegalStateException("skill is required");
            }
            return skillService.loadInstalledSkillBundle(ownerTenantId, configId, row.getId());
        });
    }

    // ── 工具注册（Go registerSandboxShellIfAllowed / registerSandboxFileTools） ──

    /**
     * shell_exec：SkillsEnabled 或安装模式的运行才有 shell（Go L506-520 +
     * registerSandboxShellTool L808-834）。不要求已存在 ready 技能——新沙箱也要有
     * 探查环境的途径（Go 注释原文）。
     */
    public void registerSandboxShellIfAllowed(ToolRegistry toolRegistry, long tenantId,
            String sessionId, QaAgentConfig config) {
        if (config == null || (!config.isSkillsEnabled() && !config.isSkillInstallMode())) {
            return;
        }
        SessionBoundManager bound = resolveBound(tenantId, sessionId, config, "shell_exec");
        if (bound == null) {
            return;
        }
        long execTenantId = tenantId;
        SandboxCommandExecutor executor = (sid, command, workDir, timeout, env, output) -> {
            SandboxManager.ExecuteResult r = bound.execShellCommand(
                    execTenantId, sid, command, workDir, timeout, env);
            return toToolResult(r);
        };
        toolRegistry.registerTool(new ShellExecTool(executor, userEnvResolver(tenantId, config)));
        log.info("Registered shell_exec tool");
    }

    /**
     * 对照 {@code userEnvResolver}（agent_service.go L707-740）：本回合的凭据
     * 解析器。无配置或无行集租户 → null（无注入，Go 同形）；行集来源作用域
     * 优先于上下文。
     */
    private SkillEnvironment.SkillEnvResolver userEnvResolver(long tenantId,
            QaAgentConfig config) {
        if (config == null || config.getSandboxConfigId() == null
                || config.getSandboxConfigId().isEmpty()) {
            return null;
        }
        List<TenantSkillEntity> rows = config.getTenantSkills();
        long ownerTenantId = (rows != null && !rows.isEmpty())
                ? rows.get(0).getTenantId() : tenantId;
        if (ownerTenantId == 0) {
            return null;
        }
        return new com.ragagent.sandbox.service.UserEnvResolver(rows, userEnvService,
                ownerTenantId, config.getSandboxConfigId());
    }

    /**
     * 对照 sessionSandboxFileStore 的能力断言：把管理器的会话文件面适配成
     * skills 网关；无会话文件系统的后端返回 null（Go 同形）。
     */
    private Manager.SandboxGateway sandboxGateway(SandboxManager mgr, long tenantId) {
        if (!(mgr instanceof SessionBoundManager bound)) {
            return null;
        }
        return new Manager.SandboxGateway() {
            @Override
            public Manager.SessionFileStore sessionFileStore() {
                return new BoundFileStore(bound, tenantId);
            }

            @Override
            public void cleanup() {
                bound.cleanup();
            }
        };
        // BoundFileStore 同时实现工具层三窄接口与 skills 网关切片
    }

    /**
     * 沙箱文件工具：list/read/write/edit（Go registerSandboxFileTools L415-450）。
     * 安装模式跳过会话文件工具（write/edit_skill_file 随批 D install 管线接线）。
     */
    public void registerSandboxFileTools(ToolRegistry toolRegistry, long tenantId,
            String sessionId, QaAgentConfig config) {
        if (config != null && config.isSkillInstallMode()) {
            log.info("Skipping session file tools in skill install mode");
            return;
        }
        SessionBoundManager bound = resolveBound(tenantId, sessionId, config, "file tools");
        if (bound == null) {
            return;
        }
        BoundFileStore store = new BoundFileStore(bound, tenantId);
        if (!hasTool(toolRegistry, ToolDefinitions.TOOL_SHELL_EXEC)) {
            toolRegistry.registerTool(new ListSandboxFilesTool(store));
        }
        toolRegistry.registerTool(new ReadFileTool(store));
        toolRegistry.registerTool(new WriteSandboxFileTool(store, maxCompletionTokens(config)));
        toolRegistry.registerTool(new EditSandboxFileTool(store));
        log.info("Registered sandbox file primitives (listing is a no-shell fallback)");
    }

    /**
     * 对照 {@code initializeSkillsManager}（agent_service.go L563-616）：构建 skills
     * 管理器（会话文件面 + agent 选择），挂上镜像技能 source，绑定到 shell_exec 与
     * read_file。技能工具跟 SkillsEnabled 而非沙箱可用性：安装 agent 必须有
     * shell_exec 而没有 execute_skill_script（Go 注释原文）。
     */
    public Manager initializeSkillsManager(long tenantId, String sessionId,
            QaAgentConfig config, ToolRegistry toolRegistry) {
        Resolution r = resolveForExecution(tenantId, sessionId, config.getSandboxConfigId());
        SandboxManager sandboxMgr = r.manager() != null ? r.manager() : new DisabledSandboxManager();
        log.info("Workspace sandbox in use: config={} type={}", r.configId(), sandboxMgr.getType());

        Manager skillsManager = new Manager(
                new Manager.ManagerConfig(config.getSkillDirs(), config.getAllowedSkills(),
                        config.isSkillsEnabled()),
                sandboxGateway(sandboxMgr, tenantId));
        TenantSkillSource source = tenantSkillSource(config.getTenantSkills());
        if (source != null) {
            skillsManager.withTenantSource(source);
        }
        try {
            skillsManager.initialize();
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize skills: " + e.getMessage(), e);
        }

        // shell_exec 挂 skill 环境（技能凭据 + SKILL.md 读取面）
        if (hasTool(toolRegistry, ToolDefinitions.TOOL_SHELL_EXEC)) {
            try {
                var tool = toolRegistry.getTool(ToolDefinitions.TOOL_SHELL_EXEC);
                if (tool instanceof ShellExecTool shell) {
                    shell.withSkillEnvironment(skillsManager.asSkillEnvironment());
                }
            } catch (ToolRegistry.ToolNotFoundException ignored) {
                // 并发取消注册的极端窗口；与 Go 的 err!=nil 分支同形
            }
        }
        if (config.isSkillsEnabled()) {
            ReadFileTool reader = null;
            if (hasTool(toolRegistry, ToolDefinitions.TOOL_READ_FILE)) {
                try {
                    var tool = toolRegistry.getTool(ToolDefinitions.TOOL_READ_FILE);
                    if (tool instanceof ReadFileTool rft) {
                        reader = rft;
                    }
                } catch (ToolRegistry.ToolNotFoundException ignored) {
                    // 落到下方补建
                }
            }
            if (reader == null) {
                // Instructions remain readable without a session filesystem.
                reader = new ReadFileTool(null);
                toolRegistry.registerTool(reader);
            }
            reader.withSkills(skillsManager.asSkillEnvironment(),
                    hasTool(toolRegistry, ToolDefinitions.TOOL_SHELL_EXEC));
            log.info("Attached skill resources to read_file");
        }
        return skillsManager;
    }

    // ── 回合租约（对照 Go session.go holdSandboxTurn L939-993） ────────────

    /**
     * 回合租约（对照 Go holdSandboxTurn 返回的 closer {@code func()}）。
     * 回合开始时开、结束时 close（调用方必须调用）；失败仅记 WARN，绝不打断回合。
     */
    public static final class TurnLease implements AutoCloseable {

        private static final TurnLease EMPTY = new TurnLease(null);

        private final Runnable closer;

        private TurnLease(Runnable closer) {
            this.closer = closer;
        }

        /** 空租约：close 是 no-op（对照 Go 的 {@code func(){}}）。 */
        public static TurnLease empty() {
            return EMPTY;
        }

        /** 持有真实 closer 的租约（WARN 语义由 closer 自带）。 */
        public static TurnLease of(Runnable closer) {
            return new TurnLease(closer);
        }

        @Override
        public void close() {
            if (closer != null) {
                closer.run();
            }
        }
    }

    /**
     * 对照 {@code holdSandboxTurn}（session.go L939-993）：回合开始时对会话沙箱开
     * chat-turn 租约，防技能镜像变更在回合中途重建 VM。首个 resolve 可能仍读到
     * 上一回合的 stale 标记。
     *
     * <p>降级链与 Go 同形：空 sessionID / 解析失败（WARN）/ 非
     * SessionBoundManager（disabled 或无会话面后端）/ begin 失败（WARN）→
     * 空租约。Go 先试进程级 sandboxMgr 再按配置解析；Java 的运行期管理器
     * 一律按 (tenantId, configId) 解析（{@link #resolveTenant} 即
     * resolveTenantSandboxForConfig），单路径等价。</p>
     */
    public TurnLease holdSandboxTurn(long tenantId, String sessionId, String configId) {
        if (sessionId == null || sessionId.isBlank()) {
            return TurnLease.empty();
        }
        SandboxManager mgr;
        try {
            mgr = resolveTenant(tenantId, configId);
        } catch (RuntimeException e) {
            log.warn("[sandbox] resolve config {} to begin turn of session {} failed: {}",
                    configId, sessionId, e.getMessage());
            return TurnLease.empty();
        }
        if (!(mgr instanceof SessionBoundManager bound)) {
            return TurnLease.empty();
        }
        try {
            bound.beginSessionTurn(tenantId, sessionId);
        } catch (RuntimeException e) {
            log.warn("[sandbox] begin turn for session {} failed: {}", sessionId, e.getMessage());
            return TurnLease.empty();
        }
        return TurnLease.of(() -> {
            try {
                bound.endSessionTurn(tenantId, sessionId);
            } catch (RuntimeException e) {
                log.warn("[sandbox] end turn for session {} failed: {}", sessionId, e.getMessage());
            }
        });
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private SessionBoundManager resolveBound(long tenantId, String sessionId,
            QaAgentConfig config, String what) {
        SandboxManager mgr;
        try {
            Resolution r = resolveForExecution(tenantId, sessionId,
                    config == null ? "" : config.getSandboxConfigId());
            mgr = r.manager();
        } catch (RuntimeException e) {
            log.warn("Failed to resolve sandbox for {}: {}", what, e.getMessage());
            return null;
        }
        if (mgr == null || SandboxTypes.TYPE_DISABLED.equals(mgr.getType())) {
            return null;
        }
        if (!(mgr instanceof SessionBoundManager bound)) {
            log.info("Sandbox backend {} does not advertise session capability; {} not registered",
                    mgr.getType(), what);
            return null;
        }
        return bound;
    }

    private static SandboxExecuteResult toToolResult(SandboxManager.ExecuteResult r) {
        return new SandboxExecuteResult(r.stdout, r.stderr, r.exitCode, r.duration,
                r.killed, r.error);
    }

    private static int maxCompletionTokens(QaAgentConfig config) {
        // 每轮完成预算（非固定字节数）才是一次写入的真正上限；工具按它推导限额
        return config.getMaxCompletionTokens() > 0 ? config.getMaxCompletionTokens() : 4096;
    }

    private static boolean hasTool(ToolRegistry registry, String name) {
        try {
            registry.getTool(name);
            return true;
        } catch (ToolRegistry.ToolNotFoundException e) {
            return false;
        }
    }

    /** 把 SessionBoundManager 的会话文件面适配成工具层三个窄接口 + skills 网关切片。 */
    static final class BoundFileStore implements SandboxFileSource, SandboxFileSink,
            SandboxFileEditor, Manager.SessionFileStore {
        private final SessionBoundManager bound;
        private final long tenantId;

        BoundFileStore(SessionBoundManager bound, long tenantId) {
            this.bound = bound;
            this.tenantId = tenantId;
        }

        @Override
        public List<RemoteDirEntry> listSessionFiles(String sessionId, String dir)
                throws Exception {
            List<RemoteDirEntry> out = new ArrayList<>();
            // 照 Go：无绑定沙箱 → nil（不报错）；null 按空集处理（见 SessionBoundManager 契约）
            List<SandboxSessionClient.DirEntry> entries =
                    bound.listSessionFiles(tenantId, sessionId, dir);
            if (entries == null) {
                return out;
            }
            for (SandboxSessionClient.DirEntry e : entries) {
                out.add(new RemoteDirEntry(e.name(), e.path(), e.type().name().toLowerCase(),
                        e.size(), e.modTime() == null ? null : e.modTime().toInstant()));
            }
            return out;
        }

        @Override
        public RemoteStatEntry statSessionFile(String sessionId, String path) throws Exception {
            SandboxSessionClient.StatEntry s = bound.statSessionFile(tenantId, sessionId, path);
            return s == null ? null : toStat(s);
        }

        @Override
        public byte[] readSessionFile(String sessionId, String path) throws Exception {
            return bound.readSessionFile(tenantId, sessionId, path);
        }

        @Override
        public void writeSessionWorkspaceFile(String sessionId, String filePath, byte[] content)
                throws Exception {
            bound.writeSessionWorkspaceFile(tenantId, sessionId, filePath, content);
        }

        /** 对照 WriteSessionWorkspaceFiles：布局准备一次后批量写。 */
        @Override
        public void writeSessionWorkspaceFiles(String sessionId,
                List<Manager.StagedFile> files) throws Exception {
            List<SessionBoundManager.SessionWorkspaceFile> staged = new ArrayList<>(files.size());
            for (Manager.StagedFile f : files) {
                staged.add(new SessionBoundManager.SessionWorkspaceFile(f.path(), f.content()));
            }
            bound.writeSessionWorkspaceFiles(tenantId, sessionId, staged);
        }

        private static RemoteStatEntry toStat(SandboxSessionClient.StatEntry s) {
            Instant mod = s.modTime() == null ? null : s.modTime().toInstant();
            String type = s.type() == null ? "file"
                    : s.type().name().toLowerCase();
            return new RemoteStatEntry(s.path(), type, s.size(), mod);
        }
    }
}
