package com.ragagent.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ragagent.TestSchema;
import com.ragagent.sandbox.domain.SkillStatus;
import com.ragagent.sandbox.domain.TenantSkillSnapshotEntity;
import com.ragagent.sandbox.mapper.TenantSkillMapper;
import com.ragagent.sandbox.service.TenantSkillReaper.LiveImageProbe;

/**
 * reaper 状态机 + 快照台账（对照 Go tenant_skill_reaper.go 的 DB 面）。
 * provider 面 seam 恒 unknown（dev 分支）：installing → failed、removing → 跳过。
 */
@SpringBootTest
class TenantSkillReaperTest {

    private static final String KB = "w5reap00-0000-0000-0000-00000000000";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TenantSkillMapper mapper;

    @Test
    void reapStuckRunsStateMachine() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime staleHeartbeat = now.minusMinutes(60);
        OffsetDateTime freshHeartbeat = now.minusMinutes(1);

        // tenant/config 行（installing_since 外键无，但保持形状）
        seedSkill("aaaaaaaa-bbbb-cccc-dddd-000000000001", "installing", staleHeartbeat);
        seedSkill("aaaaaaaa-bbbb-cccc-dddd-000000000002", "removing", staleHeartbeat);
        seedSkill("aaaaaaaa-bbbb-cccc-dddd-000000000003", "installing", freshHeartbeat);

        // removing 且已知不在图 → 软删（Go removing 尾段）
        TenantSkillReaper reaper = new TenantSkillReaper(mapper,
                (tenantId, configId, skillId) ->
                        new LiveImageProbe.ProbeResult("", false, false),
                () -> OffsetDateTime.now());
        int reaped = reaper.reapStuckRuns();

        assertEquals(1, reaped, "provider 不可判定（known=false）：installing 治愈回 ready 计数；removing 跳过不计");
        // known=false（provider 不可判定）→ installing 治愈回 ready（Go：serving || !known）
        assertEquals(SkillStatus.READY, jdbc.queryForObject(
                "SELECT status FROM tenant_skills WHERE id=? AND tenant_id=10002", String.class,
                "aaaaaaaa-bbbb-cccc-dddd-000000000001"));
        assertEquals("", jdbc.queryForObject(
                "SELECT error FROM tenant_skills WHERE id=? AND tenant_id=10002", String.class,
                "aaaaaaaa-bbbb-cccc-dddd-000000000001"));
        assertNull(jdbc.queryForObject(
                "SELECT installed_snapshot_id FROM tenant_skills WHERE id=? AND tenant_id=10002",
                String.class, "aaaaaaaa-bbbb-cccc-dddd-000000000001"), "空 snapshotId 不写");
        assertEquals(SkillStatus.REMOVING, jdbc.queryForObject(
                "SELECT status FROM tenant_skills WHERE id=? AND tenant_id=10002", String.class,
                "aaaaaaaa-bbbb-cccc-dddd-000000000002"), "provider 不可判定 → removing 留待下轮");
        assertEquals(SkillStatus.INSTALLING, jdbc.queryForObject(
                "SELECT status FROM tenant_skills WHERE id=? AND tenant_id=10002", String.class,
                "aaaaaaaa-bbbb-cccc-dddd-000000000003"), "心跳新鲜的不动");
    }

    @Test
    void snapshotLedgerCrud() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        OffsetDateTime now = OffsetDateTime.now();
        com.ragagent.sandbox.domain.TenantSkillSnapshotEntity e =
                new com.ragagent.sandbox.domain.TenantSkillSnapshotEntity();
        e.setId("aaaaaaaa-bbbb-cccc-dddd-000000000010");
        e.setTenantId(10002L);
        e.setSandboxConfigId("cfg-1");
        e.setSkillId("aaaaaaaa-bbbb-cccc-dddd-000000000001");
        e.setGeneration(1);
        e.setTrigger("install");
        e.setState("creating");
        assertEquals(1, mapper.createSnapshotRow(e, now));

        assertEquals(1, mapper.markSnapshotState(10002L, e.getId(), "ready", "snap-1", now));
        var row = mapper.listSnapshotsByConfig(10002L, "cfg-1");
        assertEquals(1, row.size());
        assertEquals("ready", row.get(0).getState());
        assertEquals("snap-1", row.get(0).getSnapshotId());

        assertEquals(1, mapper.markSnapshotState(10002L, e.getId(), "superseded", null, now));
        var superseded = mapper.listSnapshotsByConfig(10002L, "cfg-1");
        assertNotNull(superseded.get(0).getSupersededAt(), "superseded 附时间戳");
        assertEquals("snap-1", superseded.get(0).getSnapshotId(), "空 snapshotId 不覆盖既有值");

        assertEquals(1, mapper.deleteSnapshotRowsByConfig(10002L, "cfg-1"));
        assertEquals(0, mapper.listSnapshotsByConfig(10002L, "cfg-1").size());
    }

    private void seedSkill(String id, String status, OffsetDateTime installingSince) {
        jdbc.update("INSERT INTO tenant_skills (id, tenant_id, sandbox_config_id, name, "
                        + "enabled, bundle_ref, bundle_sha256, status, error, installing_since) "
                        + "VALUES (?, 10002, 'cfg-1', 'reap-test', TRUE, 'ref', 'sha', ?, '', ?)",
                id, status, installingSince);
    }
}
