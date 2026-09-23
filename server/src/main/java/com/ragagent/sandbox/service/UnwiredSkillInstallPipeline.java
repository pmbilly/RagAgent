package com.ragagent.sandbox.service;

import org.springframework.stereotype.Component;

/**
 * {@link SkillInstallPipeline} 的占位实现（对照既有 {@code ConfigSandboxClient}
 * Unwired 占位先例）：部署里没有可用的 install 管线——本形态只保留状态机的两端，
 * 管线本体（播种/installer agent/快照/指针切换）随后续批次接管。
 *
 * <p>抛 {@link IllegalStateException} 而非 BizException：走 sandbox 包 controller
 * 的 plain-500 分支（Go 对非 AppError 的全局 ErrorHandler 形态），且后台管线路径
 * 由调用方 catch 后 {@code failSkill} 落行，与 Go 的 error 通道一致。</p>
 */
@Component
public class UnwiredSkillInstallPipeline implements SkillInstallPipeline {

    public static final String MESSAGE = "install pipeline is not available in this deployment";

    @Override
    public void execute(Entry entry) {
        throw new IllegalStateException(MESSAGE);
    }
}
