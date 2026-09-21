package com.ragagent.sandbox.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.sandbox.domain.SkillStatus;
import com.ragagent.sandbox.domain.TenantSkillEntity;
import com.ragagent.sandbox.mapper.TenantSkillMapper;

/**
 * 卡死安装/移除的状态机（对照 Go tenant_skill_reaper.go 的 ReapStuckRuns
 * L90-195，W5 收尾批翻译）。
 *
 * <p><b>provider 面声明</b>：skillFilesInLiveImage（在图判定）需要 exec 到活沙箱
 * ——provider-XDEP。Go 在 provider 不可达时 known=false，状态机因此走
 * "installing → failed / removing → skip" 的保守分支；Java 同构，`inLiveImage`
 * seam 返回 known=false 即该分支。heal 分支（known=true）随 provider 批联调。</p>
 */
public final class TenantSkillReaper {

    private static final Logger log = LoggerFactory.getLogger(TenantSkillReaper.class);

    /** 对照 skillInstallStuckTTL：心跳静默超过该时长视为卡死。 */
    public static final java.time.Duration SKILL_INSTALL_STUCK_TTL = java.time.Duration.ofMinutes(30);

    /** 对照 skillInstallInterruptedMessage。 */
    public static final String SKILL_INSTALL_INTERRUPTED_MESSAGE = "安装中断，请重试";

    /** 对照 skillFilesInLiveImage 的三元返回（provider 面 seam）。 */
    public interface LiveImageProbe {
        /** known=false = provider 不可达（dev 恒此分支）。 */
        record ProbeResult(String snapshotId, boolean serving, boolean known) {
            static ProbeResult unknown() {
                return new ProbeResult("", false, false);
            }
        }

        ProbeResult probe(long tenantId, String configId, String skillId);
    }

    private final TenantSkillMapper skills;
    private final LiveImageProbe probe;
    private final java.util.function.Supplier<OffsetDateTime> clock;

    public TenantSkillReaper(TenantSkillMapper skills, LiveImageProbe probe,
            java.util.function.Supplier<OffsetDateTime> clock) {
        this.skills = skills;
        this.probe = probe;
        this.clock = clock;
    }

    /**
     * 对照 ReapStuckRuns：扫 installing/removing 且心跳超时的行——
     * <ul>
     *   <li>installing：在图（或无法判定）→ 治愈回 ready；确认不在图 → failed；</li>
     *   <li>removing：仍 known 且不在图 → 治愈回 ready；不在图/不可判定 → 跳过
     *       （宁可留着 removing 行，也不误删活镜像里的 skill）。</li>
     * </ul>
     *
     * @return 处理的行数
     */
    public int reapStuckRuns() {
        OffsetDateTime cutoff = clock.get().minus(SKILL_INSTALL_STUCK_TTL);
        List<TenantSkillEntity> stale = skills.listStaleInstalling(cutoff);
        int reaped = 0;
        for (TenantSkillEntity row : stale) {
            if (row == null || row.getInstallingSince() == null
                    || !row.getInstallingSince().isBefore(cutoff)) {
                continue;
            }
            var probeResult = probe.probe(row.getTenantId(), row.getSandboxConfigId(),
                    row.getId());
            String snapshotId = probeResult.snapshotId();
            boolean serving = probeResult.serving();
            boolean known = probeResult.known();

            switch (row.getStatus()) {
                case SkillStatus.INSTALLING -> {
                    if (serving || !known) {
                        // 进程在写入快照前后死掉但镜像已就绪/无法判定 → 治愈回 ready
                        if (skills.updateReapState(row.getTenantId(), row.getSandboxConfigId(),
                                row.getId(), SkillStatus.READY, "", null,
                                snapshotId.isEmpty() ? null : snapshotId,
                                OffsetDateTime.now()) > 0) {
                            reaped++;
                        } else {
                            log.warn("[skill] heal abandoned install {} back to ready failed",
                                    row.getId());
                        }
                        continue;
                    }
                    if (skills.updateReapState(row.getTenantId(), row.getSandboxConfigId(),
                            row.getId(), SkillStatus.FAILED, SKILL_INSTALL_INTERRUPTED_MESSAGE,
                            null, null, OffsetDateTime.now()) > 0) {
                        reaped++;
                    } else {
                        log.warn("[skill] reap abandoned install {} failed", row.getId());
                    }
                }
                case SkillStatus.REMOVING -> {
                    if (!known) {
                        continue;
                    }
                    if (serving) {
                        if (skills.updateReapState(row.getTenantId(), row.getSandboxConfigId(),
                                row.getId(), SkillStatus.READY, "", null, null,
                                OffsetDateTime.now()) > 0) {
                            reaped++;
                        }
                        continue;
                    }
                    // 已不在图：移除实际已完成 → 软删行
                    if (skills.softDeleteSkill(row.getTenantId(), row.getSandboxConfigId(),
                            row.getId(), OffsetDateTime.now()) > 0) {
                        reaped++;
                    }
                }
                default -> { /* 非 installing/removing 不可能出现在 stale 集 */ }
            }
        }
        return reaped;
    }
}
