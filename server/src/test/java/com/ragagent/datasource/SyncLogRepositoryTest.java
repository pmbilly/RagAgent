package com.ragagent.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.ragagent.TestSchema;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceException;
import com.ragagent.datasource.domain.SyncItemError;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.domain.SyncResult;
import com.ragagent.datasource.mapper.DataSourceRepository;
import com.ragagent.datasource.mapper.SyncLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 同步日志仓储在 H2 上的语义。
 *
 * <p>重点：</p>
 * <ul>
 *   <li>创建时自动补 {@code id} / {@code started_at}（UTC now）；</li>
 *   <li>{@link SyncLogRepository#findLatest} 未命中回 {@code null}
 *       ——与同文件 {@link SyncLogRepository#findById} 上抛**不同**；</li>
 *   <li>分页钳制（{@code limit <= 0 → 10}、{@code offset < 0 → 0}）；</li>
 *   <li>{@code update}（结构体，跳零值）vs {@code updateResult}（显式 map，
 *       <b>能写空串</b>）——这是两个方法存在的理由；</li>
 *   <li>{@code cancelPendingByDataSource} 的四列（含自动补上的 {@code updated_at}）；</li>
 *   <li>{@code cleanupOldLogs} 是**物理删**且全局，保留天数钳制。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SyncLogRepositoryTest {

    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SyncLogRepository repo;
    @Autowired
    private DataSourceRepository dataSourceRepo;

    /** 每条 sync_log 都要有父数据源（真库里有外键；测试库不建，但语义上要有）。 */
    private String dataSourceId;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        DataSource ds = new DataSource();
        ds.setTenantId(TENANT);
        ds.setKnowledgeBaseId("kb1");
        ds.setName("ds");
        ds.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);
        dataSourceRepo.create(ds);
        dataSourceId = ds.getId();
    }

    private SyncLog newLog(String status) {
        SyncLog log = new SyncLog();
        log.setDataSourceId(dataSourceId);
        log.setTenantId(TENANT);
        log.setStatus(status);
        return log;
    }

    // ── create ─────────────────────────────────────────────────────────────

    /** 创建时：id 为空就生成 UUID、{@code started_at} 零值补 **UTC** now。 */
    @Test
    void createFillsIdAndStartedAt() {
        SyncLog log = newLog(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        repo.create(log);

        assertThat(log.getId()).hasSize(36);
        assertThat(log.getStartedAt()).isNotEqualTo(GoTimeSerializer.GO_ZERO_DATE_TIME);
        assertThat(log.getCreatedAt()).isNotEqualTo(GoTimeSerializer.GO_ZERO_DATE_TIME);
        assertThat(log.getUpdatedAt()).isNotEqualTo(GoTimeSerializer.GO_ZERO_DATE_TIME);
        assertThat(repo.findById(log.getId()).getStatus())
                .isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
    }

    /** 调用方已给的 {@code started_at} 不能被覆盖（仅在零值时补 now）。 */
    @Test
    void createKeepsCallerSuppliedStartedAt() {
        OffsetDateTime when = OffsetDateTime.now(ZoneOffset.UTC).minusHours(3);
        SyncLog log = newLog(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        log.setStartedAt(when);
        repo.create(log);

        assertThat(repo.findById(log.getId()).getStartedAt().toInstant())
                .isEqualTo(when.toInstant());
    }

    /** {@code result} 列可空且无 DEFAULT：不设就落 SQL NULL。 */
    @Test
    void createLeavesResultNull() {
        SyncLog log = newLog(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        repo.create(log);

        assertThat(jdbc.queryForObject("SELECT result FROM sync_logs WHERE id = ?",
                String.class, log.getId())).isNull();
        assertThat(repo.findById(log.getId()).getResult()).isNull();
        assertThat(repo.findById(log.getId()).parseResult()).isNull();
    }

    /** {@code result} 的 jsonb 往返——同时验证自定义 {@code @Select} 上的 {@code @Results} 挂对了。 */
    @Test
    void resultRoundTripsThroughTheDatabase() {
        SyncLog log = newLog(DataSourceConstants.SYNC_LOG_STATUS_PARTIAL);
        SyncResult result = new SyncResult();
        result.setTotal(9);
        result.setDeletionFailed(2);
        SyncItemError err = new SyncItemError();
        err.setCode("feishu_rate_limited");
        err.setMessage("boom");
        result.setErrors(new java.util.ArrayList<>(List.of(err)));
        log.setResult(result.toJSON());
        repo.create(log);

        SyncLog stored = repo.findById(log.getId());
        assertThat(stored.getResult()).isNotNull();
        SyncResult parsed = stored.parseResult();
        assertThat(parsed.getTotal()).isEqualTo(9);
        assertThat(parsed.getDeletionFailed()).isEqualTo(2);
        assertThat(parsed.getErrors()).hasSize(1);
        assertThat(parsed.getErrors().get(0).getCode()).isEqualTo("feishu_rate_limited");
    }

    @Test
    void createRejectsNil() {
        assertThatThrownBy(() -> repo.create(null))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("sync log is nil");
    }

    // ── 读 ─────────────────────────────────────────────────────────────────

    @Test
    void findByIdRejectsEmptyIdAndReportsMissing() {
        assertThatThrownBy(() -> repo.findById(""))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("id is empty");
        assertThatThrownBy(() -> repo.findById("no-such-id"))
                .isInstanceOf(DataSourceException.NotFoundException.class)
                .hasMessage("sync log not found");
    }

    /**
     * ⚠️ {@code findLatest} 未命中回 {@code null}——**不是**错误。
     * 它与 {@link #findByIdRejectsEmptyIdAndReportsMissing} 里的
     * {@code "sync log not found"} 是同一文件的两种处置，别统一。
     */
    @Test
    void findLatestReturnsNullWhenAbsent() {
        assertThat(repo.findLatest(dataSourceId)).isNull();
    }

    @Test
    void findByDataSourceOrdersByStartedAtDescAndPaginates() {
        for (int i = 1; i <= 4; i++) {
            SyncLog log = newLog(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
            log.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(i));
            repo.create(log);
        }

        List<SyncLog> all = repo.findByDataSource(dataSourceId, 0, 0);
        assertThat(all).as("默认页大小 10，四行全回来").hasSize(4);
        // started_at DESC：最近的在最前（上面 i 越大越早）
        assertThat(all.get(0).getStartedAt().toInstant())
                .isAfter(all.get(3).getStartedAt().toInstant());

        assertThat(repo.findByDataSource(dataSourceId, 2, 0)).hasSize(2);
        assertThat(repo.findByDataSource(dataSourceId, 2, 3)).hasSize(1);
        // offset 为负按 0 处理
        assertThat(repo.findByDataSource(dataSourceId, 2, -5)).hasSize(2);
    }

    @Test
    void findByDataSourceIsEmptyListWhenNothingMatches() {
        assertThat(repo.findByDataSource(dataSourceId, 10, 0)).isNotNull().isEmpty();
        assertThat(repo.findByDataSource("no-such-ds", 10, 0)).isNotNull().isEmpty();
    }

    @Test
    void findByDataSourceRejectsEmptyId() {
        assertThatThrownBy(() -> repo.findByDataSource("", 10, 0))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("data source id is empty");
    }

    /** {@code started_at DESC, id ASC}——同一次同步的两条日志靠主键破平局。 */
    @Test
    void findLatestBreaksTiesOnId() {
        OffsetDateTime same = OffsetDateTime.now(ZoneOffset.UTC);
        SyncLog a = newLog(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        a.setId("aaaaaaaa-0000-0000-0000-000000000000");
        a.setStartedAt(same);
        repo.create(a);
        SyncLog b = newLog(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        b.setId("bbbbbbbb-0000-0000-0000-000000000000");
        b.setStartedAt(same);
        repo.create(b);

        assertThat(repo.findLatest(dataSourceId).getId()).isEqualTo(a.getId());
    }

    @Test
    void hasRunningSyncOnlyCountsRunning() {
        assertThat(repo.hasRunningSync(dataSourceId)).isFalse();

        repo.create(newLog(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS));
        assertThat(repo.hasRunningSync(dataSourceId)).isFalse();

        repo.create(newLog(DataSourceConstants.SYNC_LOG_STATUS_RUNNING));
        assertThat(repo.hasRunningSync(dataSourceId)).isTrue();
    }

    @Test
    void hasRunningSyncRejectsEmptyId() {
        assertThatThrownBy(() -> repo.hasRunningSync(""))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("data source id is empty");
        assertThatThrownBy(() -> repo.findLatest(""))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("data source id is empty");
    }

    // ── update（结构体） vs updateResult（显式 map） ────────────────────────

    /** {@code update} 跳过零值：用它清空 {@code error_message} 是做不到的。 */
    @Test
    void updateSkipsZeroValues() {
        SyncLog log = newLog(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
        log.setErrorMessage("boom");
        log.setItemsFailed(3);
        repo.create(log);

        SyncLog patch = new SyncLog();
        patch.setId(log.getId());
        patch.setStatus(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        repo.update(patch);

        SyncLog stored = repo.findById(log.getId());
        assertThat(stored.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        assertThat(stored.getErrorMessage()).as("零值不进 SET").isEqualTo("boom");
        assertThat(stored.getItemsFailed()).isEqualTo(3);
    }

    /** 但 {@code updated_at} 例外——{@code update} 对它无条件覆盖。 */
    @Test
    void updateAlwaysRefreshesUpdatedAt() {
        SyncLog log = newLog(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        log.setUpdatedAt(OffsetDateTime.now().minusDays(3));
        repo.create(log);
        OffsetDateTime before = repo.findById(log.getId()).getUpdatedAt();

        SyncLog patch = new SyncLog();
        patch.setId(log.getId());
        patch.setStatus(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        repo.update(patch);

        assertThat(repo.findById(log.getId()).getUpdatedAt().toInstant())
                .isAfter(before.toInstant());
    }

    /**
     * {@code updateResult} 用显式列集，**能**把 {@code error_message} 写成空串
     * ——这正是它和 {@code update} 并存的理由。
     */
    @Test
    void updateResultWritesZeroValuesIncludingEmptyErrorMessage() {
        SyncLog log = newLog(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
        log.setErrorMessage("boom");
        log.setFinishedAt(OffsetDateTime.now().minusMinutes(1));
        repo.create(log);

        SyncLog done = new SyncLog();
        done.setId(log.getId());
        done.setStatus(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        done.setItemsTotal(5);
        done.setItemsCreated(1);
        done.setItemsUpdated(2);
        done.setItemsDeleted(0);
        done.setItemsSkipped(0);
        done.setItemsFailed(0);
        done.setErrorMessage("");
        SyncResult result = new SyncResult();
        result.setTotal(5);
        done.setResult(result.toJSON());

        repo.updateResult(done);

        SyncLog stored = repo.findById(log.getId());
        assertThat(stored.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        assertThat(stored.getErrorMessage()).as("空串必须真的落库").isEmpty();
        assertThat(stored.getItemsTotal()).isEqualTo(5);
        assertThat(stored.getItemsCreated()).isEqualTo(1);
        assertThat(stored.parseResult().getTotal()).isEqualTo(5);
        // 它只写同步产出的列，不动 started_at / data_source_id
        assertThat(stored.getDataSourceId()).isEqualTo(dataSourceId);
    }

    @Test
    void updateAndUpdateResultRejectNilAndEmptyId() {
        assertThatThrownBy(() -> repo.update(null))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("sync log is nil");
        assertThatThrownBy(() -> repo.update(new SyncLog()))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("sync log id is empty");
        assertThatThrownBy(() -> repo.updateResult(null))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("sync log is nil");
        assertThatThrownBy(() -> repo.updateResult(new SyncLog()))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("sync log id is empty");
    }

    // ── cancelPendingByDataSource ──────────────────────────────────────────

    /**
     * 取消操作写三列业务值，{@code updated_at} 会被自动补上（§9）——共四列。
     * 这里验证三列的净效果。
     */
    @Test
    void cancelPendingMarksRunningAndPendingAsCanceled() {
        SyncLog running = newLog(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        repo.create(running);
        SyncLog terminal = newLog(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        repo.create(terminal);

        // "pending" 没有对应常量，这里直接造一行
        SyncLog pending = newLog("pending");
        repo.create(pending);

        repo.cancelPendingByDataSource(dataSourceId);

        SyncLog canceled = repo.findById(running.getId());
        assertThat(canceled.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_CANCELED);
        assertThat(canceled.getFinishedAt()).as("finished_at 必须写上").isNotNull();
        assertThat(canceled.getErrorMessage()).isEqualTo("data source deleted");

        assertThat(repo.findById(pending.getId()).getStatus())
                .isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_CANCELED);
        // 终态行不受影响
        assertThat(repo.findById(terminal.getId()).getStatus())
                .isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        assertThat(repo.findById(terminal.getId()).getFinishedAt()).isNull();
    }

    /**
     * {@code finished_at} 与 {@code updated_at} 用的是**同一个 {@code now}**。
     *
     * <p>断言的是两个值的**相等**，不涉及"时间已经过去多久"——两列都由同一个
     * {@code OffsetDateTime} 写出，所以不受机器负载影响（不写靠墙钟的用例，§9）。</p>
     */
    @Test
    void cancelPendingUsesOneTimestampForBothColumns() {
        SyncLog running = newLog(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        repo.create(running);
        repo.cancelPendingByDataSource(dataSourceId);

        SyncLog stored = repo.findById(running.getId());
        assertThat(stored.getUpdatedAt().toInstant())
                .as("updated_at 由 GORM 补、finished_at 由 Go 显式给，两者同源")
                .isEqualTo(stored.getFinishedAt().toInstant());
    }

    @Test
    void cancelPendingRejectsEmptyId() {
        assertThatThrownBy(() -> repo.cancelPendingByDataSource(""))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("data source id is empty");
    }

    // ── cleanupOldLogs（物理删、全局、保留天数钳制） ───────────────────────

    @Test
    void cleanupOldLogsDeletesOnlyRowsOlderThanRetention() {
        SyncLog old = newLog(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        old.setStartedAt(OffsetDateTime.now().minusDays(40));
        repo.create(old);
        SyncLog fresh = newLog(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        repo.create(fresh);

        repo.cleanupOldLogs(30);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_logs WHERE id = ?",
                Integer.class, old.getId())).as("物理删").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_logs WHERE id = ?",
                Integer.class, fresh.getId())).isEqualTo(1);
    }

    /** {@code retentionDays <= 0} 回落到 30。 */
    @Test
    void cleanupOldLogsFallsBackToThirtyDays() {
        SyncLog fortyDays = newLog(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        fortyDays.setStartedAt(OffsetDateTime.now().minusDays(40));
        repo.create(fortyDays);
        SyncLog twentyDays = newLog(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        twentyDays.setStartedAt(OffsetDateTime.now().minusDays(20));
        repo.create(twentyDays);

        repo.cleanupOldLogs(0);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_logs WHERE id = ?",
                Integer.class, fortyDays.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_logs WHERE id = ?",
                Integer.class, twentyDays.getId())).isEqualTo(1);
    }

    /** 负的天数与 0 同义（否则 {@code minusDays(-1)} 会把界推到未来，删光整表）。 */
    @Test
    void cleanupOldLogsClampsNegativeRetentionDays() {
        SyncLog fresh = newLog(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        repo.create(fresh);

        repo.cleanupOldLogs(-7);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_logs", Integer.class))
                .isEqualTo(1);
    }

    /** 清理是**全局**的：别的数据源的旧日志同样被删（不带租户条件）。 */
    @Test
    void cleanupOldLogsIsGlobal() {
        DataSource other = new DataSource();
        other.setTenantId(99999L);
        other.setKnowledgeBaseId("kb2");
        other.setName("other");
        other.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);
        dataSourceRepo.create(other);

        SyncLog otherOld = new SyncLog();
        otherOld.setDataSourceId(other.getId());
        otherOld.setTenantId(99999L);
        otherOld.setStatus(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        otherOld.setStartedAt(OffsetDateTime.now().minusDays(90));
        repo.create(otherOld);

        repo.cleanupOldLogs(30);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_logs WHERE id = ?",
                Integer.class, otherOld.getId())).isZero();
    }

    /** 保证测试用的 UUID 是合法形态。 */
    @Test
    void generatedIdsAreUuidShaped() {
        SyncLog log = newLog(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        repo.create(log);
        assertThat(UUID.fromString(log.getId())).isNotNull();
    }
}
