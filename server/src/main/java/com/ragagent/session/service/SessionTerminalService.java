package com.ragagent.session.service;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.ragagent.common.context.TenantContext;
import com.ragagent.sandbox.domain.TenantSandboxConfigEntity;
import com.ragagent.sandbox.mapper.TenantSandboxConfigMapper;
import com.ragagent.sandbox.runtime.EffectiveConfig;
import com.ragagent.sandbox.runtime.EffectiveConfigResolver;
import com.ragagent.sandbox.runtime.SandboxBackendPolicy;
import com.ragagent.sandbox.runtime.SandboxTypes;
import com.ragagent.sandbox.service.TenantSandboxConfigService;
import com.ragagent.session.mapper.SessionMapper;

/**
 * 会话沙箱终端打开服务（对照 Go internal/application/service/sandbox_terminal_service.go
 * 全文 263 行 + session_sandbox_pin.go 的 resolveSessionManager/resolveSandboxForExecution +
 * tenant_sandbox_resolve.go 的 resolveTenantSandboxForConfig）。
 *
 * <p>只读入口：终端是"会话已绑定沙箱"的查找式入口，解析与产物收集同一来源
 * （pin 优先，绝不跟随 agent 当下的选择）。打开面板不得凭空创建或唤醒 microVM；
 * provision=1 的确认点击才走 EnsureSessionTerminal。</p>
 *
 * <h2>错误契约 → WS 协议帧（sandbox_terminal_ws.go terminalErrorFrame）</h2>
 * <ul>
 *   <li>{@link Failure#NOT_BOUND} — 会话无 pin（或 pin 为空/会话缺行）；</li>
 *   <li>{@link Failure#PAUSED} — pin 指向的沙箱暂停（provider 报告，dev 不可达）；</li>
 *   <li>{@link Failure#UNSUPPORTED} — 解析出的后端不能流 PTY（disabled 管理器）；</li>
 *   <li>{@link Failure#INTERNAL} — 其它一切（配置缺失/cordon/docker 关闭/解析失败）。</li>
 * </ul>
 *
 * <h2>已备案接缝（XDEP）</h2>
 * <p>cube/e2b/docker 具名配置的 provider 终端执行体（OpenSessionTerminal 的
 * 远程 PTY 流）随波 5：解析命中具名后端时与 Go 的"provider 不可达"分支同形落
 * {@link Failure#INTERNAL}（dev 双侧确定性行为一致；真部署需波 5 的 provider
 * terminal 客户端）。PAUSED 同属 provider 报告分支，随波 5。</p>
 */
@Service
public class SessionTerminalService {

    /** 打开失败的协议映射（对照 terminalErrorFrame 的四分支）。 */
    public enum Failure {
        NOT_BOUND("SANDBOX_NOT_BOUND", "session has no live sandbox"),
        PAUSED("SANDBOX_PAUSED", "session sandbox is paused"),
        UNSUPPORTED("TERMINAL_UNSUPPORTED", "sandbox backend does not support terminals"),
        INTERNAL("INTERNAL", "failed to open terminal");

        public final String code;
        public final String message;

        Failure(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    /** 对照 SandboxCordonLease = 2 * time.Minute。 */
    private static final Duration CORDON_LEASE = Duration.ofMinutes(2);

    /** 对照 types.SandboxConfigIDGlobalDefault = "-"。 */
    private static final String GLOBAL_DEFAULT = "-";

    private static final Logger log = LoggerFactory.getLogger(SessionTerminalService.class);

    private final SessionMapper sessionMapper;
    private final TenantSandboxConfigService sandboxConfigService;
    private final TenantSandboxConfigMapper sandboxConfigMapper;
    @Nullable
    private final EffectiveConfig globalCfg;

    public SessionTerminalService(SessionMapper sessionMapper,
            TenantSandboxConfigService sandboxConfigService,
            TenantSandboxConfigMapper sandboxConfigMapper,
            @Nullable EffectiveConfig globalCfg) {
        this.sessionMapper = sessionMapper;
        this.sandboxConfigService = sandboxConfigService;
        this.sandboxConfigMapper = sandboxConfigMapper;
        this.globalCfg = globalCfg;
    }

    /** 打开成功的结果（对照 service.SessionTerminal；dev 无 provider 执行体）。 */
    public record SessionTerminal(String backend) {}

    /** 打开结果：terminal 非 null = 成功；failure 非 null = 协议帧映射。 */
    public record OpenResult(SessionTerminal terminal, Failure failure) {}

    /**
     * 对照 OpenSessionTerminal（L75-86）：查找式打开，永不创建/唤醒。
     */
    public OpenResult openSessionTerminal(long tenantId, String sessionId) {
        return openOnResolved(resolveSessionManager(tenantId, sessionId));
    }

    /**
     * 对照 EnsureSessionTerminal（L136-187）：确认点击路径。resolveSessionManager
     * 成功 → open；失败为 NOT_BOUND 且带 configID → 按执行链解析（含 pin 写）→
     * provision → open。
     */
    public OpenResult ensureSessionTerminal(long tenantId, String sessionId, String sandboxConfigId) {
        Resolution resolved = resolveSessionManager(tenantId, sessionId);
        if (resolved.failure() == null) {
            return openOnResolved(resolved);
        }
        if (resolved.failure() != Failure.NOT_BOUND || emptyToBlank(sandboxConfigId).isEmpty()) {
            return new OpenResult(null, resolved.failure());
        }

        // 对照 resolveSandboxForExecution（L146-197）：pin 优先（本路径 pin 恒空——
        // resolveSessionManager 刚查过），否则用 agent 给的 configID 解析；具名后端
        // 成功后写 pin（并发认领：败者采纳赢者的 config）。
        String configId = emptyToBlank(sandboxConfigId);
        Resolution mgr = resolveTenantSandboxForConfig(tenantId, configId);
        if (mgr.failure() != null || mgr.type() == null) {
            return new OpenResult(null, mgr.failure());
        }
        if (!isNamedBackend(mgr.type())) {
            return provisionAndOpen(mgr, sessionId);
        }
        String pinned = trim(sessionMapper.selectSandboxConfigPin(sessionId));
        if (pinned.isEmpty()) {
            // Go 的 Pin 是条件 UPDATE 认领；此处无并发对手（终端是唯一入口）。
            // 0 行 = 已被别人钉住 → 回读现有 pin 后按其解析（Go L109-121 同形）。
            int claimed = sessionMapper.updateSandboxConfigPin(sessionId, configId,
                    OffsetDateTime.now());
            if (claimed <= 0) {
                String winnerId = trim(sessionMapper.selectSandboxConfigPin(sessionId));
                if (winnerId.isEmpty()) {
                    return new OpenResult(null, Failure.INTERNAL);
                }
                Resolution winner = resolveTenantSandboxForConfig(tenantId, winnerId);
                if (winner.failure() != null) {
                    return new OpenResult(null, winner.failure());
                }
                return provisionAndOpen(winner, sessionId);
            }
            return provisionAndOpen(mgr, sessionId);
        }
        Resolution winner = resolveTenantSandboxForConfig(tenantId, pinned);
        if (winner.failure() != null) {
            return new OpenResult(null, winner.failure());
        }
        return provisionAndOpen(winner, sessionId);
    }

    // ── 解析（对照 resolveSessionManager + resolveTenantSandboxForConfig） ────

    private record Resolution(String type, Failure failure) {}

    /**
     * 对照 resolveSessionManager（L90-115）：pin 读 → 空即 NOT_BOUND →
     * resolveTenantSandboxForConfig。
     */
    private Resolution resolveSessionManager(long tenantId, String sessionId) {
        String configId;
        try {
            configId = trim(sessionMapper.selectSandboxConfigPin(sessionId));
        } catch (RuntimeException e) {
            log.warn("[sandbox-terminal] pin read failed session={}: {}", sessionId, e.toString());
            return new Resolution(null, Failure.INTERNAL);
        }
        if (configId.isEmpty()) {
            return new Resolution(null, Failure.NOT_BOUND);
        }
        return resolveTenantSandboxForConfig(tenantId, configId);
    }

    /**
     * 对照 resolveTenantSandboxForConfig（tenant_sandbox_resolve.go L70-113）：
     * ① 工作区 kill switch（独立于 resolver 可用性）→ disabled；② 空配置/"-" →
     * disabled；③ 具名配置：加载（未找到/cordon → 错误 → INTERNAL）→ effective →
     * docker 关闭 → 错误 → INTERNAL；disabled 类型 → disabled。
     * 返回的 type 是后端类型（"disabled"=disabled 管理器，"cube"/"e2b"/"docker"=
     * provider 管理器）。
     */
    private Resolution resolveTenantSandboxForConfig(long tenantId, String configId) {
        // ① 工作区 kill switch
        try {
            if (tenantId != 0 && sandboxConfigService.workspaceScriptsDisabled(tenantId)) {
                return new Resolution(SandboxTypes.TYPE_DISABLED, null);
            }
        } catch (RuntimeException e) {
            // Go：读策略失败 Warnf 后继续（不当成失败也不当成禁用）
            log.warn("[sandbox] failed to read workspace sandbox policy for {}: {}", tenantId,
                    e.toString());
        }

        // ② 无具名配置 = 沙箱执行关闭
        if (configId.isEmpty() || configId.equals(GLOBAL_DEFAULT)) {
            return new Resolution(SandboxTypes.TYPE_DISABLED, null);
        }

        // ③ 具名配置
        if (tenantId == 0) {
            return new Resolution(null, Failure.INTERNAL);
        }
        TenantSandboxConfigEntity entity;
        try {
            entity = sandboxConfigMapper.getByID(tenantId, configId);
        } catch (RuntimeException e) {
            log.warn("[sandbox] failed to load config {} for workspace {}: {}", configId,
                    tenantId, e.toString());
            return new Resolution(null, Failure.INTERNAL);
        }
        if (entity == null) {
            // 对照 fmt.Errorf("%w: %s", ErrSandboxConfigNotFound, configID) → INTERNAL
            return new Resolution(null, Failure.INTERNAL);
        }
        if (entity.isCordoned(OffsetDateTime.now(), CORDON_LEASE)) {
            return new Resolution(null, Failure.INTERNAL);
        }
        EffectiveConfig effective;
        try {
            effective = EffectiveConfigResolver.resolveEffectiveConfig(entity.getConfig(),
                    globalCfg != null ? globalCfg : EffectiveConfig.defaultConfig());
        } catch (RuntimeException e) {
            log.warn("[sandbox] resolve effective config {} failed: {}", configId, e.toString());
            return new Resolution(null, Failure.INTERNAL);
        }
        try {
            SandboxBackendPolicy.ensureDockerBackendAllowed(effective.type);
        } catch (RuntimeException e) {
            return new Resolution(null, Failure.INTERNAL);
        }
        return switch (effective.type) {
            case SandboxTypes.TYPE_DISABLED -> new Resolution(SandboxTypes.TYPE_DISABLED, null);
            case SandboxTypes.TYPE_CUBE, SandboxTypes.TYPE_E2B, SandboxTypes.TYPE_DOCKER ->
                    new Resolution(effective.type, null);
            default -> new Resolution(SandboxTypes.TYPE_DISABLED, null);
        };
    }

    // ── 打开（对照 openOnManager + terminalManagerFromManager） ──────────────

    private OpenResult openOnResolved(Resolution resolved) {
        if (resolved.failure() != null) {
            return new OpenResult(null, resolved.failure());
        }
        if (SandboxTypes.TYPE_DISABLED.equals(resolved.type())) {
            // disabled 管理器没有终端能力（对照 terminalManagerFromManager 失败）
            log.warn("[sandbox-terminal] backend disabled does not support terminals");
            return new OpenResult(null, Failure.UNSUPPORTED);
        }
        // XDEP 接缝：provider 终端执行体随波 5。Go 在此对 provider 发起远程 PTY
        // 调用（provider 不可达 → INTERNAL）；dev 双侧都落在失败分支。
        log.warn("[sandbox-terminal] provider terminal runtime not wired (wave 5 seam) type={}",
                resolved.type());
        return new OpenResult(null, Failure.INTERNAL);
    }

    /** 对照 provisionOnManager：no-op 命令驱动的懒创建（provider 调用，XDEP 同上）。 */
    private OpenResult provisionAndOpen(Resolution resolved, String sessionId) {
        if (SandboxTypes.TYPE_DISABLED.equals(resolved.type())) {
            return new OpenResult(null, Failure.UNSUPPORTED);
        }
        log.warn("[sandbox-terminal] provision sandbox for session {} requires provider runtime "
                + "(wave 5 seam) type={}", sessionId, resolved.type());
        return new OpenResult(null, Failure.INTERNAL);
    }

    private static boolean isNamedBackend(String type) {
        return SandboxTypes.TYPE_CUBE.equals(type) || SandboxTypes.TYPE_E2B.equals(type)
                || SandboxTypes.TYPE_DOCKER.equals(type);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String emptyToBlank(String s) {
        return s == null ? "" : s.trim();
    }
}
