package com.ragagent.sandbox.service;

import java.util.List;

/**
 * install 管线接缝（波 4 遗留任务：runInstall 步骤链的宿主）。
 *
 * <p>对照 Go 的异步受理形状：{@code InstallSkill}/{@code InstallCatalogToConfigs}
 * （tenant_skill_catalog.go L201）在校验+落库+202 受理后，把
 * {@code go s.runInstall(tenantID, configID, skillID, bundle, instructions...)}
 * 扔进后台——本接口承载的就是那个后台调用的<b>入口形状</b>（tenant_skill_install.go
 * L259 的 runInstall：播种 → installer agent 循环（builtin-skill-installer）→ 验证
 * gate → 快照 ledger → 指针切换）。同步受理面（{@code TenantSkillService.installSkill}
 * 等）已逐行落地，调用的正是 {@code runInstallPipelineSeam}；管线本体批接管时把该
 * seam 的后半段改为委托本接口的生产实现即可。</p>
 *
 * <p>调用语义：实现在后台虚拟线程里运行（受理方不再等待），错误经 {@code failSkill}
 * 状态机落行——不向受理 HTTP 调用方传播。</p>
 */
public interface SkillInstallPipeline {

    /**
     * 一次待执行的安装（对照 {@code runInstall} 的入参面；{@code instructions}
     * 是 upload/reinstall 携带的自然语言安装指令，可空）。
     */
    record Entry(long tenantId, String configId, String skillId,
            SkillBundleParser.SkillBundle bundle, List<String> instructions) {
    }

    /** 在后台执行安装管线。 */
    void execute(Entry entry);
}
