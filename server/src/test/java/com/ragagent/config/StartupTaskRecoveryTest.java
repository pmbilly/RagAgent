package com.ragagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ragagent.TestSchema;

/**
 * 启动恢复（对照 Go {@code container/reset_pending_tasks.go}）的 H2 钉子：
 * Lite 模式下卡在处理态的知识/摘要行复位为 failed + 重启文案 + 子任务计数清零、
 * wiki 独槽的 finalizing 行排除（持久化 wiki op 可重建触发器收尾）、
 * 同步日志按模式复位（Lite 全量 / 分布式只判 30 分钟陈旧窗）。
 * 复位行不再产生孤儿 running span（SERVER_RESTART 取消）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StartupTaskRecoveryTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StartupTaskRecovery recovery;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    private String knowledge(String parseStatus, int pendingSubtasks) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "source, channel, parse_status, pending_subtasks_count, summary_status, "
                + "created_at, updated_at) "
                + "VALUES (?, 10002, 'kb-1', 'file', 't', 'file', '', ?, ?, 'none', "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, parseStatus, pendingSubtasks);
        return id;
    }

    @Test
    void liteModeResetsStuckKnowledgeAndSummary() {
        String pending = knowledge("pending", 0);
        String processing = knowledge("processing", 2);
        String finalizingNoOp = knowledge("finalizing", 1);
        String deleting = knowledge("deleting", 0);
        String completed = knowledge("completed", 0);
        String wikiOnlySlot = knowledge("finalizing", 1);
        jdbc.update("INSERT INTO task_pending_ops (tenant_id, task_type, scope, scope_id, op, "
                + "dedup_key) VALUES (10002, 'wiki:ingest', 'knowledge_base', 'kb-1', 'ingest', ?)",
                wikiOnlySlot);

        recovery.resetPendingTasks(false);

        assertReset(pending);
        assertReset(processing);
        assertReset(finalizingNoOp);
        assertReset(deleting);
        assertThat(parseStatus(completed)).isEqualTo("completed");
        // wiki 独槽的 finalizing 行保持存活（启动后重建触发器收尾）
        assertThat(parseStatus(wikiOnlySlot)).isEqualTo("finalizing");
    }

    @Test
    void liteModeResetsStuckSummary() {
        String id = knowledge("completed", 0);
        jdbc.update("UPDATE knowledges SET summary_status = 'processing' WHERE id = ?", id);

        recovery.resetPendingTasks(false);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT summary_status FROM knowledges WHERE id = ?", id);
        assertThat(row.get("SUMMARY_STATUS")).isEqualTo("failed");
    }

    @Test
    void distributedModeSkipsKnowledgeResets() {
        String processing = knowledge("processing", 0);

        recovery.resetPendingTasks(true);

        assertThat(parseStatus(processing)).isEqualTo("processing").as("分布式模式不复位知识行");
    }

    @Test
    void syncLogsResetByMode() {
        String liteId = seedSyncLog();
        recovery.resetPendingTasks(false);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, error_message, finished_at FROM sync_logs WHERE id = ?", liteId);
        assertThat(row.get("STATUS")).isEqualTo("failed");
        assertThat(row.get("ERROR_MESSAGE"))
                .isEqualTo("Sync interrupted due to application restart");
        assertThat(row.get("FINISHED_AT")).isNotNull();

        // 分布式：30 分钟陈旧窗内的 running 行不动
        String fresh = seedSyncLog();
        String stale = seedSyncLog();
        jdbc.update("UPDATE sync_logs SET started_at = CURRENT_TIMESTAMP - 60 MINUTE WHERE id = ?",
                stale);
        recovery.resetPendingTasks(true);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM sync_logs WHERE id = ?", String.class, fresh))
                .isEqualTo("running").as("陈旧窗内不动");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM sync_logs WHERE id = ?", String.class, stale))
                .isEqualTo("failed").as("过窗即判死");
    }

    private String seedSyncLog() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sync_logs (id, data_source_id, tenant_id, status, started_at) "
                + "VALUES (?, 'ds-1', 10002, 'running', CURRENT_TIMESTAMP)", id);
        return id;
    }

    private String parseStatus(String id) {
        return jdbc.queryForObject(
                "SELECT parse_status FROM knowledges WHERE id = ?", String.class, id);
    }

    private void assertReset(String id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT parse_status, error_message, pending_subtasks_count FROM knowledges "
                        + "WHERE id = ?", id);
        assertThat(row.get("PARSE_STATUS")).isEqualTo("failed");
        assertThat(row.get("ERROR_MESSAGE"))
                .isEqualTo("Task interrupted due to application restart");
        assertThat(((Number) row.get("PENDING_SUBTASKS_COUNT")).intValue()).isEqualTo(0);
    }
}
