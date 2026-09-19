package com.ragagent.sandbox.service;

import com.ragagent.sandbox.runtime.ConfigSandboxClient;
import com.ragagent.sandbox.runtime.EffectiveConfig;

/**
 * provider 客户端工厂（对照 Go service 的 {@code newClient} 字段，
 * tenant_sandbox_config.go L303：{@code newClient func(*sandbox.Config)
 * (sandbox.ConfigSandboxClient, error)}，生产实现是
 * {@code sandbox.NewRemoteClientForCheck}）。
 *
 * <p>子批 1 的 Unwired 占位已由 {@link RemoteSandboxClientFactory} 替换：
 * 客户端方法在控制面不可达时抛 {@code RemoteError}（失败分类与 Go 同形），
 * Update 的旧凭据盘点（失败 → warn 后 proceed）、Delete 的盘点（!force → 409
 * 固定文案）、Inventory 的 unverifiable=true、QueryTemplates 的 500 各失败
 * 分支由此驱动。</p>
 */
public interface SandboxClientFactory {

    /**
     * 对照 {@code newClient(effective)}：为解析后的配置构建 provider 客户端。
     *
     * @throws com.ragagent.sandbox.runtime.DockerBackendDisabledException
     *         docker 后端未启用（对照 NewRemoteClientForCheck 直传
     *         ErrDockerBackendDisabled 原文）
     * @throws com.ragagent.sandbox.runtime.RemoteError 传输/分类失败
     */
    ConfigSandboxClient create(EffectiveConfig effective);
}
