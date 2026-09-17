package com.ragagent.llm.provider;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.Provider 接口（provider.go）：
 *
 * <pre>
 * type Provider interface {
 *     Info() ProviderInfo
 *     ValidateConfig(config *Config) error
 * }
 * </pre>
 *
 * Go 的 error 返回值 → Java 抛 {@link BizException}（约定 §1）。当前 Go 仓内
 * ValidateConfig 尚无生产调用点（仅测试），阶段 4 起的运行时客户端接入时复用。
 */
public interface Provider {

    /** Info 返回服务商的元数据（对照 Go Info()） */
    ProviderInfo info();

    /**
     * 校验服务商配置（对照 Go ValidateConfig）。
     *
     * @throws BizException 校验失败，message 与 Go 的 fmt.Errorf 文案逐字一致
     */
    void validateConfig(Config config);
}
