package com.ragagent.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.TestSchema;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceException;
import com.ragagent.datasource.domain.SyncCursor;
import com.ragagent.datasource.domain.SyncResult;
import com.ragagent.datasource.mapper.DataSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 数据源仓储在 H2 上的语义（对照 Go
 * internal/application/repository/datasource_repo.go L13-151）。
 *
 * <p>重点只在真 SQL 上才暴露的行为：</p>
 * <ul>
 *   <li>GORM 的"CREATE 零值 → DDL 默认值替换并回写结构体"
 *       （{@code sync_mode}/{@code status}/{@code conflict_strategy}/
 *       {@code sync_log_retention_days}）；</li>
 *   <li>{@code sync_deletions = false} 必须真的落库为 false（Go 的三步舞的净效果）；</li>
 *   <li>{@code autoResultMap} 之外的 jsonb 列读回（TypeHandler 是否真的挂上了）；</li>
 *   <li>软删（{@code deleted_at}）把行从所有读路径里滤掉；</li>
 *   <li>{@code Updates(结构体)} 跳过零值——{@code error_message} 用 update() 清不掉，
 *       必须走 {@code updateSyncState}；</li>
 *   <li>{@code created_at} 零值才补 / {@code updated_at} 无条件刷。</li>
 * </ul>
 *
 * <p>⚠️ {@code @AutoConfigureMockMvc} 是为了与其余契约测试共用同一个 Spring 上下文缓存键。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DataSourceRepositoryTest {

    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSourceRepository repo;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    private static DataSource newDataSource(String name, String kbId) {
        DataSource ds = new DataSource();
        ds.setTenantId(TENANT);
        ds.setKnowledgeBaseId(kbId);
        ds.setName(name);
        ds.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);
        return ds;
    }

    // ── create ─────────────────────────────────────────────────────────────

    @Test
    void createFillsIdAndTimestamps() {
        DataSource ds = newDataSource("n", "kb1");
        repo.create(ds);

        assertThat(ds.getId()).hasSize(36);
        assertThat(ds.getCreatedAt()).isNotEqualTo(GoTimeSerializer.GO_ZERO_DATE_TIME);
        assertThat(ds.getUpdatedAt()).isNotEqualTo(GoTimeSerializer.GO_ZERO_DATE_TIME);
        assertThat(repo.findById(ds.getId()).getName()).isEqualTo("n");
    }

    /**
     * GORM 的"零值 → 默认值替换"（{@code callbacks/create.go} L336-341）：
     * 四个带字面量 {@code default:} tag 的字段即使为零值也会被**显式写入默认值**
     * 而不是落 DDL 默认，**并且回写内存结构体**。
     */
    @Test
    void createAppliesGormInsertDefaults() {
        DataSource ds = newDataSource("n", "kb1");
        repo.create(ds);

        DataSource stored = repo.findById(ds.getId());
        assertThat(stored.getSyncMode()).isEqualTo("incremental");
        assertThat(stored.getStatus()).isEqualTo("active");
        assertThat(stored.getConflictStrategy()).isEqualTo("overwrite");
        assertThat(stored.getSyncLogRetentionDays()).isEqualTo(30);
        // 字段被回写进内存对象（Go 的 field.Set）
        assertThat(ds.getSyncMode()).isEqualTo("incremental");
        assertThat(ds.getStatus()).isEqualTo("active");
        assertThat(ds.getConflictStrategy()).isEqualTo("overwrite");
        assertThat(ds.getSyncLogRetentionDays()).isEqualTo(30);
    }

    /**
     * ⚠️ {@code sync_deletions = false} 必须真的落库为 false。
     *
     * <p>Go 靠"Create 之后再用 UpdateColumn 写回原值"达成；Java 直接插原值。
     * 这条断言就是那个净效果的验收：如果照抄 GORM 的默认值替换而不做回写，
     * 这里会读到 {@code true}。</p>
     */
    @Test
    void createPersistsCallerSuppliedSyncDeletionsFalse() {
        DataSource ds = newDataSource("n", "kb1");
        ds.setSyncDeletions(false);
        repo.create(ds);

        assertThat(repo.findById(ds.getId()).isSyncDeletions()).isFalse();
        assertThat(jdbc.queryForObject("SELECT sync_deletions FROM data_sources WHERE id = ?",
                Boolean.class, ds.getId())).isFalse();
        // 内存对象也保持调用方的取值
        assertThat(ds.isSyncDeletions()).isFalse();
    }

    @Test
    void createPersistsSyncDeletionsTrue() {
        DataSource ds = newDataSource("n", "kb1");
        ds.setSyncDeletions(true);
        repo.create(ds);
        assertThat(repo.findById(ds.getId()).isSyncDeletions()).isTrue();
    }

    /** 三个 jsonb 列都是**可空且无 DEFAULT**：不设就落 SQL NULL（不是 '{}'）。 */
    @Test
    void createLeavesJsonbColumnsNull() {
        DataSource ds = newDataSource("n", "kb1");
        repo.create(ds);

        assertThat(jdbc.queryForObject("SELECT config FROM data_sources WHERE id = ?",
                String.class, ds.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT last_sync_cursor FROM data_sources WHERE id = ?",
                String.class, ds.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT last_sync_result FROM data_sources WHERE id = ?",
                String.class, ds.getId())).isNull();

        DataSource stored = repo.findById(ds.getId());
        assertThat(stored.getConfig()).isNull();
        assertThat(stored.parseConfig()).isNull();
    }

    /**
     * jsonb 列的**往返**：写进去再读回来必须是同一个对象树。
     *
     * <p>这条同时验证 {@code PgJsonTypeHandler} 真的挂上了——自定义 {@code @Select}
     * 的结果映射**不会**自动套实体上的 {@code @TableField(typeHandler=…)}（§9），
     * 漏了 {@code @Results} 的表现正是"库里明明有值、读出来是 null"。</p>
     */
    @Test
    void jsonbColumnsRoundTripThroughTheDatabase() {
        DataSource ds = newDataSource("n", "kb1");
        DataSourceConfig cfg = new DataSourceConfig();
        cfg.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);
        cfg.setSettings(new LinkedHashMap<>(Map.of("feed_urls", "https://example.invalid/feed")));
        ds.setConfig(cfg.toJSON());

        SyncCursor cursor = new SyncCursor();
        cursor.setLastSchemaHash("h");
        cursor.setConnectorCursor(new LinkedHashMap<>(Map.of("page", 3.0d)));
        ds.setLastSyncCursor(cursor.toJSON());

        SyncResult result = new SyncResult();
        result.setTotal(7);
        ds.setLastSyncResult(result.toJSON());

        repo.create(ds);

        DataSource stored = repo.findById(ds.getId());
        assertThat(stored.getConfig()).isNotNull();
        assertThat(stored.getLastSyncCursor()).isNotNull();
        assertThat(stored.getLastSyncResult()).isNotNull();

        assertThat(stored.parseConfig().getSettings())
                .containsEntry("feed_urls", "https://example.invalid/feed");

        // ⚠️ 数字必须活着回来（jsonb 存的是 JSON 数字，不是字符串）。
        // 但**不要**断言 Java 侧的类型：Go 的 `interface{}` 一律给 float64，Jackson 给
        // Integer/Double——这个类型差在 JSON 上不可见（两边都写 `3`，见
        // DataSourceMapSerializer），所以这里断言的是**序列化后的字节**。
        Object page = stored.parseSyncCursor().getConnectorCursor().get("page");
        assertThat(String.valueOf(page)).as("按 Go 的浮点编码器写成 3（不是 3.0）")
                .isEqualTo("3");

        assertThat(stored.parseSyncResult().getTotal()).isEqualTo(7);
    }

    /**
     * 落进 jsonb 列的数字文本必须与 Go 的 {@code json.Marshal} 一致
     * （整数值不补 {@code .0}）——否则 Go 读到的虽然是同一个值，
     * 但两边写出的字节不同，跨语言比对会一直分叉。
     */
    @Test
    void jsonbColumnStoresGoFormattedNumbers() throws Exception {
        DataSource ds = newDataSource("n", "kb1");
        SyncCursor cursor = new SyncCursor();
        Map<String, Object> connector = new LinkedHashMap<>();
        connector.put("page", 3.0d);
        connector.put("huge", 1e21d);
        cursor.setConnectorCursor(connector);
        ds.setLastSyncCursor(cursor.toJSON());
        repo.create(ds);

        String stored = jdbc.queryForObject(
                "SELECT last_sync_cursor FROM data_sources WHERE id = ?", String.class, ds.getId());
        assertThat(stored)
                .as("Go 实录：{\"connector_cursor\":{\"huge\":1e+21,\"page\":3},...}")
                .contains("\"huge\":1e+21")
                .contains("\"page\":3")
                .doesNotContain("3.0")
                .doesNotContain("1.0E21");
    }

    // ── findById / findByKnowledgeBase / findActive ────────────────────────

    @Test
    void findByIdRejectsEmptyIdAndReportsMissing() {
        assertThatThrownBy(() -> repo.findById(""))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("id is empty");
        assertThatThrownBy(() -> repo.findById(null))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("id is empty");

        assertThatThrownBy(() -> repo.findById("no-such-id"))
                .isInstanceOf(DataSourceException.NotFoundException.class)
                .hasMessage("data source not found");
    }

    @Test
    void findByKnowledgeBaseOrdersByCreatedAtDescAndIsScoped() {
        DataSource older = newDataSource("older", "kb1");
        older.setCreatedAt(OffsetDateTime.now().minusDays(2));
        repo.create(older);
        DataSource newer = newDataSource("newer", "kb1");
        repo.create(newer);
        repo.create(newDataSource("other", "kb2"));

        List<DataSource> rows = repo.findByKnowledgeBase("kb1");
        assertThat(rows).extracting(DataSource::getName).containsExactly("newer", "older");

        // 无行时是**空列表**而不是 null（GORM 的 Find 会 MakeSlice）
        assertThat(repo.findByKnowledgeBase("no-such-kb")).isNotNull().isEmpty();
    }

    @Test
    void findByKnowledgeBaseRejectsEmptyId() {
        assertThatThrownBy(() -> repo.findByKnowledgeBase(""))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("knowledge base id is empty");
    }

    /**
     * {@code FindActive} 的三条件：{@code status='active'}、
     * {@code deleted_at IS NULL}、{@code sync_schedule != ''}。
     */
    @Test
    void findActiveNeedsActiveStatusAndNonEmptySchedule() {
        DataSource scheduled = newDataSource("scheduled", "kb1");
        scheduled.setSyncSchedule("0 */6 * * *");
        repo.create(scheduled);

        DataSource manual = newDataSource("manual", "kb1");
        repo.create(manual); // sync_schedule 留空

        DataSource paused = newDataSource("paused", "kb1");
        paused.setSyncSchedule("0 */6 * * *");
        paused.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_PAUSED);
        repo.create(paused);

        assertThat(repo.findActive()).extracting(DataSource::getName).containsExactly("scheduled");
    }

    @Test
    void findActiveIsEmptyListWhenNothingMatches() {
        assertThat(repo.findActive()).isNotNull().isEmpty();
    }

    // ── update ─────────────────────────────────────────────────────────────

    @Test
    void updateSkipsZeroValues() {
        DataSource ds = newDataSource("before", "kb1");
        ds.setErrorMessage("boom");
        ds.setSyncSchedule("0 */6 * * *");
        repo.create(ds);

        // 只改 name，其余字段全零
        DataSource patch = new DataSource();
        patch.setId(ds.getId());
        patch.setName("after");
        repo.update(patch);

        DataSource stored = repo.findById(ds.getId());
        assertThat(stored.getName()).isEqualTo("after");
        assertThat(stored.getErrorMessage()).as("零值不进 SET，清不掉").isEqualTo("boom");
        assertThat(stored.getSyncSchedule()).isEqualTo("0 */6 * * *");
        assertThat(stored.getType()).as("空 type 不进 SET").isEqualTo("rss");
    }

    /** {@code updated_at} 是例外：{@code Updates(结构体)} 对它**无条件覆盖**。 */
    @Test
    void updateAlwaysRefreshesUpdatedAt() {
        DataSource ds = newDataSource("n", "kb1");
        ds.setUpdatedAt(OffsetDateTime.now().minusDays(5));
        repo.create(ds);
        OffsetDateTime before = repo.findById(ds.getId()).getUpdatedAt();

        DataSource patch = new DataSource();
        patch.setId(ds.getId());
        patch.setName("renamed");
        repo.update(patch);

        OffsetDateTime after = repo.findById(ds.getId()).getUpdatedAt();
        assertThat(after.toInstant()).isAfter(before.toInstant());
    }

    /**
     * ⚠️ {@code sync_deletions} 是**无条件**写的（Go 的第二步 UpdateColumn）：
     * 即便 patch 对象里它是零值 {@code false}，也要落到库里。
     */
    @Test
    void updateAlwaysWritesSyncDeletionsEvenWhenFalse() {
        DataSource ds = newDataSource("n", "kb1");
        ds.setSyncDeletions(true);
        repo.create(ds);
        assertThat(repo.findById(ds.getId()).isSyncDeletions()).isTrue();

        DataSource patch = new DataSource();
        patch.setId(ds.getId());
        patch.setName("n2");
        patch.setSyncDeletions(false);
        repo.update(patch);

        assertThat(repo.findById(ds.getId()).isSyncDeletions()).isFalse();
    }

    @Test
    void updateRejectsNilAndEmptyId() {
        assertThatThrownBy(() -> repo.update(null))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("data source is nil");
        assertThatThrownBy(() -> repo.update(new DataSource()))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("data source id is empty");
    }

    /** 更新 jsonb 列走 3 参 set + typeHandler——退化会报 {@code 0xACED} 魔数（§9）。 */
    @Test
    void updateWritesJsonbColumnsWithTypeHandler() {
        DataSource ds = newDataSource("n", "kb1");
        repo.create(ds);

        DataSourceConfig cfg = new DataSourceConfig();
        cfg.setType(DataSourceConstants.CONNECTOR_TYPE_FEISHU);
        cfg.setSettings(new LinkedHashMap<>(Map.of("folder_token", "ft")));

        DataSource patch = new DataSource();
        patch.setId(ds.getId());
        patch.setConfig(cfg.toJSON());
        repo.update(patch);

        assertThat(repo.findById(ds.getId()).parseConfig().getSettings())
                .containsEntry("folder_token", "ft");
    }

    // ── updateSyncState ────────────────────────────────────────────────────

    /**
     * {@code updateSyncState} 存在**唯一**的理由：{@code Updates(结构体)} 跳过零值，
     * 于是"同步成功后清掉上次的错误消息"在 {@link DataSourceRepository#update} 下
     * 根本做不到。这里用显式列集把空串写进去。
     */
    @Test
    void updateSyncStateWritesZeroValuesIncludingEmptyErrorMessage() {
        DataSource ds = newDataSource("n", "kb1");
        ds.setErrorMessage("boom");
        repo.create(ds);

        DataSource state = new DataSource();
        state.setId(ds.getId());
        state.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
        state.setErrorMessage("");
        state.setLastSyncAt(OffsetDateTime.now());

        SyncResult result = new SyncResult();
        result.setTotal(3);
        state.setLastSyncResult(result.toJSON());

        repo.updateSyncState(state);

        DataSource stored = repo.findById(ds.getId());
        assertThat(stored.getErrorMessage()).as("空串必须真的落库").isEmpty();
        assertThat(stored.getLastSyncAt()).isNotNull();
        assertThat(stored.parseSyncResult().getTotal()).isEqualTo(3);
        // 它不该碰用户可编辑的字段
        assertThat(stored.getName()).isEqualTo("n");
        assertThat(stored.getType()).isEqualTo("rss");
    }

    /** {@code updateSyncState} 的 {@code last_sync_at} 可以被写成 NULL（Go 的 map 语义）。 */
    @Test
    void updateSyncStateCanWriteNullLastSyncAt() {
        DataSource ds = newDataSource("n", "kb1");
        ds.setLastSyncAt(OffsetDateTime.now());
        repo.create(ds);

        DataSource state = new DataSource();
        state.setId(ds.getId());
        state.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
        state.setLastSyncAt(null);
        state.setErrorMessage("");
        repo.updateSyncState(state);

        assertThat(repo.findById(ds.getId()).getLastSyncAt()).isNull();
    }

    @Test
    void updateSyncStateRejectsNilAndEmptyId() {
        assertThatThrownBy(() -> repo.updateSyncState(null))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("data source is nil");
        assertThatThrownBy(() -> repo.updateSyncState(new DataSource()))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("data source id is empty");
    }

    // ── delete（软删） ─────────────────────────────────────────────────────

    @Test
    void deleteSoftDeletesAndHidesFromEveryReadPath() {
        DataSource ds = newDataSource("n", "kb1");
        ds.setSyncSchedule("0 */6 * * *");
        repo.create(ds);

        repo.delete(ds.getId());

        assertThatThrownBy(() -> repo.findById(ds.getId()))
                .isInstanceOf(DataSourceException.NotFoundException.class);
        assertThat(repo.findByKnowledgeBase("kb1")).isEmpty();
        assertThat(repo.findActive()).isEmpty();
        // 行还在，只是带上了 deleted_at
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_sources WHERE id = ? AND deleted_at IS NOT NULL",
                Integer.class, ds.getId())).isEqualTo(1);
    }

    /** 删不存在的 id 是空操作，不是错误（Go 只看 {@code .Error}）。 */
    @Test
    void deleteIsNoOpForUnknownId() {
        repo.delete("no-such-id");
    }

    @Test
    void deleteRejectsEmptyId() {
        assertThatThrownBy(() -> repo.delete(""))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("id is empty");
    }

    /** 二次删除仍是空操作（{@code deleted_at IS NULL} 让第二次 UPDATE 匹配 0 行）。 */
    @Test
    void deleteIsIdempotent() {
        DataSource ds = newDataSource("n", "kb1");
        repo.create(ds);
        repo.delete(ds.getId());
        repo.delete(ds.getId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_sources WHERE id = ? AND deleted_at IS NOT NULL",
                Integer.class, ds.getId())).isEqualTo(1);
    }

    // ── create 的校验 ──────────────────────────────────────────────────────

    @Test
    void createRejectsNil() {
        assertThatThrownBy(() -> repo.create(null))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("data source is nil");
    }
}
