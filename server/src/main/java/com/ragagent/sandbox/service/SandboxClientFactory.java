package com.ragagent.sandbox.service;

import com.ragagent.sandbox.runtime.ConfigSandboxClient;
import com.ragagent.sandbox.runtime.EffectiveConfig;
import org.springframework.stereotype.Component;

/**
 * <h2>⚠️ 显式接缝（波 3 子批 1）</h2>
 *
 * <p>对照 Go service 的 {@code newClient} 字段（tenant_sandbox_config.go L303：
 * {@code newClient func(*sandbox.Config) (sandbox.ConfigSandboxClient, error)}，生产实现是
 * {@code sandbox.NewRemoteClientForCheck}）。本批只定义接口 + 一个恒抛
 * {@link SandboxClientNotWiredException} 的占位实现——cube/e2b/docker 的完整客户端
 * （exec/PTY/文件/快照）属子批 2。</p>
 *
 * <p>dev A/B 由此只覆盖<b>失败分支</b>：Update 的旧凭据盘点（clientFor 失败 → warn 后
 * proceed，跳过写入后清扫，Go L1002-1008 同形）、Delete 的盘点（!force → 409 固定文案、
 * force → 警告后继续）、Inventory 的 unverifiable=true、QueryTemplates 的 500。
 * 子批 2 以真实 factory bean（@Primary 或替换注册）接线，本接口不变。</p>
 */
public interface SandboxClientFactory {

    /**
     * 对照 {@code newClient(effective)}：为解析后的配置构建 provider 检查客户端。
     *
     * @throws SandboxClientNotWiredException 占位实现恒抛（与 Go 的"provider 客户端
     *         构建失败"同形：QueryTemplates 原样上抛 → 500；Update/Delete 走各自
     *         的失败分支）
     */
    ConfigSandboxClient create(EffectiveConfig effective);

    /**
     * 对照 Go 在 newClient 赋的默认 {@code sandbox.NewRemoteClientForCheck(cfg)} 的位置。
     * 抛出的消息只进日志/500 details，不是前端契约。
     */
    @Component
    class UnwiredSandboxClientFactory implements SandboxClientFactory {
        @Override
        public ConfigSandboxClient create(EffectiveConfig effective) {
            throw new SandboxClientNotWiredException(
                    "sandbox: provider client is not wired in this build (sub-batch 2)");
        }
    }

    /** 占位 factory 的失败信号。属实现细节，不在 controller 的 sentinel→400 分类里。 */
    class SandboxClientNotWiredException extends RuntimeException {
        public SandboxClientNotWiredException(String message) {
            super(message);
        }
    }
}
