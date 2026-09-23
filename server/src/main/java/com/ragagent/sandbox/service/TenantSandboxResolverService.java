package com.ragagent.sandbox.service;

import com.ragagent.sandbox.domain.SandboxConstants;
import com.ragagent.sandbox.mapper.TenantSandboxConfigMapper;
import com.ragagent.sandbox.runtime.DisabledSandboxManager;
import com.ragagent.sandbox.runtime.DockerSandboxClient;
import com.ragagent.sandbox.runtime.EffectiveConfig;
import com.ragagent.sandbox.runtime.EffectiveConfigResolver;
import com.ragagent.sandbox.runtime.RemoteSessionLifecycle;
import com.ragagent.sandbox.runtime.SandboxBackendPolicy;
import com.ragagent.sandbox.runtime.SandboxException;
import com.ragagent.sandbox.runtime.SandboxManager;
import com.ragagent.sandbox.runtime.SandboxTypes;
import com.ragagent.sandbox.runtime.SessionBoundManager;
import com.ragagent.sandbox.runtime.SessionSandboxBindingStore;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 按配置解析沙箱管理器（对照 Go {@code sandbox.TenantSandboxResolver} /
 * {@code tenantSandboxResolver.Resolve}，internal/sandbox/tenant_resolver.go
 * L150-196 全文）。
 *
 * <p><b>每请求构建、刻意不缓存</b>（Go 文件头注释原文）：lifecycle 的
 * create/recover 串行化完全活在共享绑定存储里（Redis SET NX / 内存锁），
 * manager 不跨请求持有句柄，Health 探测被 SkipHealthProbe 省略——无缓存即
 * 无失效管道，配置变更下一请求生效。Java 同构：这里每次 new。</p>
 *
 * <p><b>错误契约</b>（消息逐字对照 Go）：
 * <ul>
 *   <li>配置不存在 → {@code sandbox: config not found: <id>}（ErrSandboxConfigNotFound，
 *       调用方必须透出而非回落默认后端——把 agent 的脚本跑在它没指过的后端上是
 *       安全相关的静默替换）；</li>
 *   <li>cordon 租约内 → {@code sandbox: config is being updated: <id>}
 *       （ErrSandboxConfigCordoned，瞬时，调用方可重试）；</li>
 *   <li>docker 后端总闸未开 → {@code sandbox: docker backend is disabled; ...}
 *       （DockerSandboxClient.forConfig 内建 EnsureDockerBackendAllowed）。</li>
 * </ul></p>
 *
 * <p><b>已知差异（provider-XDEP 备案，随波次收口）</b>：cube/e2b 的会话执行
 * 客户端（envd exec/文件面/快照）尚未翻译——引用这两种类型的配置在此抛
 * {@code sandbox: provider "cube" session execution is not available in this
 * deployment}，而非静默回落。docker 是当前唯一可用的会话执行后端（Go
 * docker_remote_client.go 全文已翻译，DockerSandboxClient 覆盖 install 管线
 * 全部 provider 环节：Create/Exec/文件播种/ContainerCommit 快照/Destroy/失效）。</p>
 */
@Service
public class TenantSandboxResolverService {

    private static final Logger log = LoggerFactory.getLogger(TenantSandboxResolverService.class);

    private final TenantSandboxConfigMapper repo;
    private final SessionSandboxBindingStore bindingStore;
    private final RemoteSessionLifecycle.SessionExistenceChecker sessionChecker;

    public TenantSandboxResolverService(
            TenantSandboxConfigMapper repo,
            SessionSandboxBindingStore bindingStore,
            RemoteSessionLifecycle.SessionExistenceChecker sessionChecker) {
        this.repo = repo;
        this.bindingStore = bindingStore;
        this.sessionChecker = sessionChecker;
    }

    /**
     * 对照 {@code Resolve}：为 (tenant, config) 构建管理器。空 configID 选择
     * 部署级默认管理器（Go 语义 = Disabled——保留无配置部署的既有行为）。
     */
    public SandboxManager resolve(long tenantId, String configId) {
        if (configId == null || configId.isBlank()
                || configId.equals(SandboxConstants.SANDBOX_CONFIG_ID_GLOBAL_DEFAULT)) {
            return new DisabledSandboxManager();
        }

        var row = repo.getByID(tenantId, configId);
        if (row == null) {
            throw new SandboxException(SandboxException.Kind.INTERNAL,
                    "sandbox: config not found: " + configId);
        }
        if (row.isCordoned(OffsetDateTime.now(), SandboxConstants.SANDBOX_CORDON_LEASE)) {
            throw new SandboxException(SandboxException.Kind.INTERNAL,
                    "sandbox: config is being updated: " + configId);
        }

        EffectiveConfig effective = EffectiveConfigResolver.resolveEffectiveConfig(
                row.getConfig(), EffectiveConfig.defaultConfig());
        // 对照 EnsureDockerBackendAllowed：docker 总闸（DB > env > false）；
        // 非 docker 类型在函数内部直接放行
        SandboxBackendPolicy.ensureDockerBackendAllowed(effective.type);

        switch (effective.type) {
            case SandboxTypes.TYPE_DISABLED:
                return new DisabledSandboxManager();
            case SandboxTypes.TYPE_DOCKER: {
                DockerSandboxClient client = DockerSandboxClient.forConfig(effective);
                // SkipHealthProbe=true：每请求构建省一次构造期探测（Go 同款）
                return new SessionBoundManager(
                        effective, client, bindingStore, sessionChecker, configId, true);
            }
            case SandboxTypes.TYPE_CUBE, SandboxTypes.TYPE_E2B:
                // provider-XDEP：会话执行面未翻译（见类 javadoc）；控制面
                // （配置 CRUD/盘点/探测）不受影响，仍由 RemoteConfigSandboxClient 服务
                log.warn("[sandbox] provider {} has no session execution client in this "
                        + "deployment (config {})", effective.type, configId);
                throw new SandboxException(SandboxException.Kind.INTERNAL,
                        "sandbox: provider \"" + effective.type
                                + "\" session execution is not available in this deployment");
            default:
                return new DisabledSandboxManager();
        }
    }
}
