package com.ragagent.datasource.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import com.ragagent.TestSchema;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.service.TenantService;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorRegistry;
import com.ragagent.datasource.DataSourceSyncTaskQueue;
import com.ragagent.datasource.Scheduler;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceException;
import com.ragagent.datasource.domain.DataSourceSyncPayload;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;
import com.ragagent.datasource.domain.SyncItemError;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.domain.SyncResult;
import com.ragagent.datasource.mapper.DataSourceRepository;
import com.ragagent.datasource.mapper.SyncLogRepository;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * {@link DataSourceService} 的语义测试（对照 Go
 * {@code internal/application/service/datasource_service.go}）。
 *
 * <h2>为什么用真仓储 + H2 而不是 fake</h2>
 * <p>与 {@code SchedulerTest} 同一处置：{@code DataSourceRepository} /
 * {@code SyncLogRepository} 是具体类，而 TestSchema 里已经有这两张表。
 * 走真仓储才能覆盖到"两处 {@code Updates} 的零值跳过差异"
 * （{@code update} 跳零值、{@code updateSyncState} 不跳）——那正是本模块最容易
 * 写错的地方（§9 的 session/message 同款坑）。</p>
 *
 * <h2>知识库一侧用假实现</h2>
 * <p>{@link KnowledgeBridge} 是端口（见其类注释）：真实的
 * {@link MapperKnowledgeBridge} 要跑完整的知识解析管线，与"同步状态机"无关。
 * 这里注入一个可编程的假实现，从而能精确构造
 * "抓到了 3 条、全失败" / "重复内容" / "删除时找不到行" 这些分支。</p>
 *
 * <h2>不靠墙钟</h2>
 * <p>没有任何断言依赖"时间过去了"——{@code started_at}/{@code finished_at} 只判非空。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DataSourceServiceTest {

    private static final long TENANT = 10002L;
    private static final String KB_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSourceRepository dsRepo;
    @Autowired
    private SyncLogRepository syncLogRepo;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private AuditLogService auditLogService;
    @Autowired
    private KnowledgeBaseMapper kbMapper;
    @Autowired
    private TenantMapper tenantMapper;

    /** 可编程的知识库侧替身。 */
    private static final class FakeBridge implements KnowledgeBridge {
        KnowledgeBase kb;
        final List<Knowledge> created = new ArrayList<>();
        final List<String> softDeleted = new ArrayList<>();
        final List<String> hardDeleted = new ArrayList<>();
        final List<String> hardDeletedLists = new ArrayList<>();
        Knowledge existingByExternalId;
        Knowledge existingByPrefix;
        /**
         * 逐次消费的"灌入结果"队列：{@code null} = 这一次成功，非 null = 抛它。
         * 队列空之后一律成功——这样"第 N 条失败、其余成功"的分支可以精确构造。
         */
        final List<RuntimeException> createOutcomes = new ArrayList<>();
        int createOutcomeCursor;
        RuntimeException lookupFailure;

        @Override
        public KnowledgeBase findKnowledgeBase(String kbId) {
            return kb;
        }

        @Override
        public Knowledge findByDataSourceExternalId(long tenantId, String kbId, String dataSourceId,
                                                    String externalId) {
            if (lookupFailure != null) {
                throw lookupFailure;
            }
            return existingByExternalId;
        }

        @Override
        public List<Knowledge> findByMetadataKeyPrefix(long tenantId, String kbId, String key,
                                                       String prefix) {
            return existingByPrefix == null ? List.of() : List.of(existingByPrefix);
        }

        @Override
        public Knowledge createFromFile(long tenantId, String kbId, byte[] content, String fileName,
                                        Map<String, String> metadata, List<String> tagIds,
                                        String channel) {
            maybeFailCreate();
            Knowledge k = newKnowledge("file", fileName);
            k.setMetadata(toNode(metadata));
            created.add(k);
            return k;
        }

        @Override
        public Knowledge createFromUrl(long tenantId, String kbId, String url, String fileName,
                                       String title, List<String> tagIds, String channel) {
            maybeFailCreate();
            Knowledge k = newKnowledge("url", title);
            k.setSource(url);
            created.add(k);
            return k;
        }

        @Override
        public void attachMetadata(Knowledge knowledge, Map<String, String> metadata) {
            knowledge.setMetadata(toNode(metadata));
        }

        @Override
        public void softDelete(long tenantId, String knowledgeId) {
            softDeleted.add(knowledgeId);
        }

        @Override
        public void softDeleteList(long tenantId, List<String> knowledgeIds) {
            softDeleted.addAll(knowledgeIds);
        }

        @Override
        public void hardDelete(long tenantId, String knowledgeId) {
            hardDeleted.add(knowledgeId);
        }

        @Override
        public void hardDeleteList(long tenantId, List<String> knowledgeIds) {
            hardDeletedLists.addAll(knowledgeIds);
        }

        private void maybeFailCreate() {
            if (createOutcomeCursor < createOutcomes.size()) {
                RuntimeException e = createOutcomes.get(createOutcomeCursor++);
                if (e != null) {
                    throw e;
                }
            }
        }

        private static Knowledge newKnowledge(String type, String title) {
            Knowledge k = new Knowledge();
            k.setId(UUID.randomUUID().toString());
            k.setType(type);
            k.setTitle(title);
            return k;
        }

        private static com.fasterxml.jackson.databind.JsonNode toNode(Map<String, String> m) {
            com.fasterxml.jackson.databind.node.ObjectNode n =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            if (m != null) {
                m.forEach(n::put);
            }
            return n;
        }
    }

    /** 可编程的连接器替身。 */
    private static final class FakeConnector implements Connector {
        String type = DataSourceConstants.CONNECTOR_TYPE_RSS;
        List<FetchedItem> allItems = List.of();
        List<FetchedItem> incrementalItems = List.of();
        SyncCursor nextCursor;
        RuntimeException fetchFailure;
        RuntimeException validateFailure;
        List<Resource> resources = List.of();
        List<String> ancestors = List.of();
        int validateCalls;

        @Override
        public String type() {
            return type;
        }

        @Override
        public void validate(DataSourceConfig config) {
            validateCalls++;
            if (validateFailure != null) {
                throw validateFailure;
            }
        }

        @Override
        public List<Resource> listResources(DataSourceConfig config, String parentId) {
            return resources;
        }

        @Override
        public List<String> resolveResourceAncestors(DataSourceConfig config, List<String> resourceIds) {
            return ancestors;
        }

        @Override
        public List<FetchedItem> fetchAll(DataSourceConfig config, List<String> resourceIds) {
            if (fetchFailure != null) {
                throw fetchFailure;
            }
            return allItems;
        }

        @Override
        public FetchIncrementalResult fetchIncremental(DataSourceConfig config, SyncCursor cursor) {
            if (fetchFailure != null) {
                throw fetchFailure;
            }
            return new FetchIncrementalResult(incrementalItems, nextCursor);
        }
    }

    private FakeBridge bridge;
    private FakeConnector connector;
    private FakeConnector notion;
    private final List<DataSourceSyncPayload> enqueued = new ArrayList<>();
    private RuntimeException enqueueFailure;
    private DataSourceService service;

    @BeforeEach
    void setUp() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.update("DELETE FROM sync_logs");
        jdbc.update("DELETE FROM data_sources");

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("datasource-svc-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        bridge = new FakeBridge();
        bridge.kb = newKnowledgeBase();
        bridge.createOutcomes.clear();
        bridge.createOutcomeCursor = 0;

        connector = new FakeConnector();
        ConnectorRegistry registry = new ConnectorRegistry();
        registry.register(connector);
        // 第二个类型：凭据测试要一个"凭据不会被 StripNonSecretCredentials 清掉"的连接器
        notion = new FakeConnector();
        notion.type = DataSourceConstants.CONNECTOR_TYPE_NOTION;
        registry.register(notion);

        enqueued.clear();
        enqueueFailure = null;
        DataSourceSyncTaskQueue queue = (payload, taskId, maxRetry, timeout) -> {
            if (enqueueFailure != null) {
                throw enqueueFailure;
            }
            assertThat(maxRetry).isEqualTo(Scheduler.MAX_RETRY);
            assertThat(timeout).isEqualTo(Scheduler.TASK_TIMEOUT);
            enqueued.add(payload);
            return DataSourceSyncTaskQueue.Outcome.ENQUEUED;
        };

        TaskScheduler cron = mock(TaskScheduler.class);
        when(cron.schedule(any(Runnable.class), any(Trigger.class)))
                .thenReturn(mock(ScheduledFuture.class));
        Scheduler scheduler = new Scheduler(dsRepo, syncLogRepo, queue, cron);

        service = new DataSourceService(dsRepo, syncLogRepo, bridge, queue, registry, scheduler,
                tenantService, auditLogService, (kbId, name) -> null);
    }

    private KnowledgeBase newKnowledgeBase() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setTenantId(TENANT);
        kb.setName("ds-kb");
        return kb;
    }

    private DataSource newDataSource(String type, String status, String syncMode) {
        DataSource ds = new DataSource();
        ds.setTenantId(TENANT);
        ds.setKnowledgeBaseId(KB_ID);
        ds.setName("src");
        ds.setType(type);
        ds.setSyncMode(syncMode == null ? DataSourceConstants.SYNC_MODE_INCREMENTAL : syncMode);
        // 真实行总有 config（CreateDataSource 会先让连接器校验它），
        // 而 handle() 对"空 config"走的是 Go 里那条 panic 等价分支——测试要覆盖的是
        // 正常路径，所以这里补一份最小配置。
        DataSourceConfig cfg = new DataSourceConfig();
        cfg.setType(type);
        ds.setConfig(cfg.toJSON());
        dsRepo.create(ds);
        if (status != null) {
            jdbc.update("UPDATE data_sources SET status = ? WHERE id = ?", status, ds.getId());
            ds.setStatus(status);
        }
        return ds;
    }

    private static FetchedItem item(String externalId, String title, byte[] content, String url) {
        FetchedItem it = new FetchedItem();
        it.setExternalId(externalId);
        it.setTitle(title);
        it.setContent(content);
        it.setUrl(url);
        it.setFileName(title + ".md");
        return it;
    }

    private static FetchedItem deletedItem(String externalId) {
        FetchedItem it = new FetchedItem();
        it.setExternalId(externalId);
        it.setTitle("gone");
        it.setDeleted(true);
        return it;
    }

    private DataSourceSyncPayload payload(String dsId, String syncLogId, boolean forceFull) {
        return new DataSourceSyncPayload(null, "manual", dsId, TENANT, syncLogId, forceFull, 0);
    }

    private SyncLog runningLog(String dsId) {
        SyncLog log = new SyncLog();
        log.setDataSourceId(dsId);
        log.setTenantId(TENANT);
        log.setStatus(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        log.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        syncLogRepo.create(log);
        return log;
    }

    // ══════════════════════════ createDataSource ══════════════════════════

    @Test
    void createRejectsNullDataSource() {
        assertThatThrownBy(() -> service.createDataSource(null))
                .isInstanceOf(DataSourceException.class)
                .hasMessage("data source configuration is invalid");
    }

    @Test
    void createRejectsMissingKnowledgeBase() {
        bridge.kb = null;
        DataSource ds = new DataSource();
        ds.setTenantId(TENANT);
        ds.setKnowledgeBaseId(KB_ID);
        ds.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);

        assertThatThrownBy(() -> service.createDataSource(ds))
                .hasMessage("knowledge base not found");
    }

    /** 跨租户的知识库回的是<b>同一句</b> 404 文案（不泄漏"这个 id 存在"）。 */
    @Test
    void createRejectsCrossTenantKnowledgeBase() {
        bridge.kb.setTenantId(TENANT + 1);
        DataSource ds = new DataSource();
        ds.setTenantId(TENANT);
        ds.setKnowledgeBaseId(KB_ID);
        ds.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);

        assertThatThrownBy(() -> service.createDataSource(ds))
                .hasMessage("knowledge base not found");
    }

    @Test
    void createRejectsUnknownConnectorType() {
        DataSource ds = new DataSource();
        ds.setTenantId(TENANT);
        ds.setKnowledgeBaseId(KB_ID);
        ds.setType("no-such-connector");

        assertThatThrownBy(() -> service.createDataSource(ds))
                .isInstanceOf(ConnectorException.NotFound.class)
                .hasMessage("connector type not found in registry");
    }

    /** CREATE 时 GORM 的默认值替换：sync_mode / status / conflict_strategy / retention_days。 */
    @Test
    void createAppliesGormInsertDefaults() {
        DataSource ds = new DataSource();
        ds.setTenantId(TENANT);
        ds.setKnowledgeBaseId(KB_ID);
        ds.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);
        ds.setName("rss-src");

        DataSource created = service.createDataSource(ds);

        assertThat(created.getId()).isNotEmpty();
        assertThat(created.getSyncMode()).isEqualTo("incremental");
        assertThat(created.getStatus()).isEqualTo("active");
        assertThat(created.getConflictStrategy()).isEqualTo("overwrite");
        assertThat(created.getSyncLogRetentionDays()).isEqualTo(30);
        assertThat(connector.validateCalls).isEqualTo(1);
    }

    // ══════════════════════════ updateDataSource ══════════════════════════

    @Test
    void updateRejectsEmptyId() {
        assertThatThrownBy(() -> service.updateDataSource(new DataSource()))
                .hasMessage("data source configuration is invalid");
    }

    @Test
    void updateRejectsKnowledgeBaseChange() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);

        DataSource req = new DataSource();
        req.setId(ds.getId());
        req.setKnowledgeBaseId("22222222-2222-2222-2222-222222222222");

        assertThatThrownBy(() -> service.updateDataSource(req))
                .hasMessage("changing knowledge base is not allowed");
    }

    /** 请求里空白的 knowledge_base_id 会被库里的值补上（不是"改成空"）。 */
    @Test
    void updateFillsBlankKnowledgeBaseIdFromStoredRow() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);

        DataSource req = new DataSource();
        req.setId(ds.getId());
        req.setName("renamed");

        DataSource updated = service.updateDataSource(req);

        assertThat(updated.getKnowledgeBaseId()).isEqualTo(KB_ID);
        assertThat(updated.getTenantId()).isEqualTo(TENANT);
        assertThat(updated.getName()).isEqualTo("renamed");
    }

    /**
     * ⚠️ 凭据永不从 PUT 流入：请求体里带 credentials，落库的仍是原来的那张 map。
     *
     * <p>这条是 Go 注释里"Credentials NEVER flow through this endpoint"的实测版。</p>
     */
    @Test
    void updateForcePreservesStoredCredentials() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("feed_urls", "https://example.test/feed.xml");
        DataSourceConfig cfg = new DataSourceConfig();
        cfg.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);
        cfg.setCredentials(stored);
        ds.setConfig(cfg.toJSON());
        dsRepo.update(ds);

        DataSource req = new DataSource();
        req.setId(ds.getId());
        req.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);
        com.fasterxml.jackson.databind.node.ObjectNode incoming =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        incoming.put("type", DataSourceConstants.CONNECTOR_TYPE_RSS);
        incoming.putObject("credentials").put("feed_urls", "https://attacker.test/feed.xml");
        req.setConfig(incoming);

        DataSource updated = service.updateDataSource(req);

        DataSourceConfig parsed = updated.parseConfig();
        assertThat(parsed).isNotNull();
        // RSS 的 feed_urls 属于非密钥配置，会被 StripNonSecretCredentials 从 credentials 里剔掉；
        // 关键是"攻击者提交的那个 URL 没有留下"。
        assertThat(parsed.getCredentials()).isNullOrEmpty();
    }

    // ══════════════════════════ 凭据子资源 ══════════════════════════

    @Test
    void updateCredentialsReplacesWholeMap() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_NOTION, null, null);

        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("api_token", "secret-token");
        DataSource updated = service.updateDataSourceCredentials(ds.getId(), creds);

        DataSourceConfig parsed = updated.parseConfig();
        assertThat(parsed.getCredentials()).containsEntry("api_token", "secret-token");
        assertThat(parsed.hasConfiguredCredentials(updated.getType())).isTrue();
        // 写库后立刻做一次真实连接校验（对照 Go 的"当场告诉用户新 token 对不对"）
        assertThat(notion.validateCalls).isEqualTo(1);
    }

    @Test
    void updateCredentialsRejectsEmptyId() {
        assertThatThrownBy(() -> service.updateDataSourceCredentials("", Map.of()))
                .hasMessage("data source configuration is invalid");
    }

    /** 清空是幂等的：已经是空的时候只写回配置、不记审计、不报错。 */
    @Test
    void clearCredentialsIsIdempotent() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_NOTION, null, null);

        service.clearDataSourceCredentials(ds.getId());
        service.clearDataSourceCredentials(ds.getId());

        DataSourceConfig parsed = dsRepo.findById(ds.getId()).parseConfig();
        assertThat(parsed.getCredentials()).isNull();
    }

    @Test
    void clearCredentialsWipesConfiguredMap() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_NOTION, null, null);
        service.updateDataSourceCredentials(ds.getId(), Map.of("api_token", "t"));

        service.clearDataSourceCredentials(ds.getId());

        DataSourceConfig parsed = dsRepo.findById(ds.getId()).parseConfig();
        assertThat(parsed.getCredentials()).isNull();
        assertThat(parsed.hasConfiguredCredentials(DataSourceConstants.CONNECTOR_TYPE_NOTION)).isFalse();
    }

    // ══════════════════════════ 连接校验 ══════════════════════════

    /** 校验失败：数据源置 error + 落库错误消息，异常继续上抛。 */
    @Test
    void validateConnectionMarksErrorState() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        connector.validateFailure = new ConnectorException.InvalidCredentials("bad token");

        assertThatThrownBy(() -> service.validateConnection(ds.getId()))
                .hasMessage("invalid credentials: bad token");

        DataSource after = dsRepo.findById(ds.getId());
        assertThat(after.getStatus()).isEqualTo(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
        assertThat(after.getErrorMessage()).isEqualTo("invalid credentials: bad token");
    }

    /**
     * 反向对称：原本是 error 的源校验通过后回到 active。
     *
     * <p>⚠️ 但 <b>error_message 不会从库里清掉</b>——这是 GORM
     * {@code Updates(结构体)} 跳过零值字段的净效果（§9 同款），Java 侧照抄。
     * Go 的源码读起来是"置空并落库"，实际上那条 SET 里根本没有 error_message；
     * 真正的清空要走 {@code UpdateSyncState}（同步结果路径用的是它）。
     * 这条断言就是为了钉住这个反直觉的事实，别"顺手修好"。</p>
     */
    @Test
    void validateConnectionRestoresActiveButKeepsStaleErrorMessage() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS,
                DataSourceConstants.DATA_SOURCE_STATUS_ERROR, null);
        jdbc.update("UPDATE data_sources SET error_message = ? WHERE id = ?", "old failure", ds.getId());

        service.validateConnection(ds.getId());

        DataSource after = dsRepo.findById(ds.getId());
        assertThat(after.getStatus()).isEqualTo(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
        assertThat(after.getErrorMessage()).isEqualTo("old failure");
    }

    @Test
    void validateCredentialsUsesRawMapWithoutPersisting() {
        Map<String, Object> creds = Map.of("feed_urls", "https://example.test/f.xml");

        service.validateCredentials(DataSourceConstants.CONNECTOR_TYPE_RSS, creds);

        assertThat(connector.validateCalls).isEqualTo(1);
        assertThat(dsRepo.findByKnowledgeBase(KB_ID)).isEmpty();
    }

    // ══════════════════════════ 资源枚举 ══════════════════════════

    /** ⚠️ 空 resource_ids 直接短路：连数据源都不查（不存在的数据源也回 200 + []）。 */
    @Test
    void resolveAncestorsShortCircuitsOnEmptyInput() {
        List<String> out = service.resolveResourceAncestors("does-not-exist", List.of());
        assertThat(out).isEmpty();

        assertThat(service.resolveResourceAncestors("does-not-exist", null)).isEmpty();
    }

    @Test
    void resolveAncestorsDelegatesToConnector() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        connector.ancestors = List.of("a", "b");

        assertThat(service.resolveResourceAncestors(ds.getId(), List.of("r1"))).containsExactly("a", "b");
    }

    // ══════════════════════════ ManualSync ══════════════════════════

    @Test
    void manualSyncRejectsNotActiveStatus() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, "deleted", null);

        assertThatThrownBy(() -> service.manualSync(ds.getId()))
                .hasMessage("data source is not active");
    }

    @Test
    void manualSyncAcceptsActiveErrorAndPaused() {
        for (String status : List.of(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE,
                DataSourceConstants.DATA_SOURCE_STATUS_ERROR,
                DataSourceConstants.DATA_SOURCE_STATUS_PAUSED)) {
            DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, status, null);
            SyncLog log = service.manualSync(ds.getId());
            assertThat(log.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
            assertThat(log.getStartedAt()).isNotNull();
        }
    }

    @Test
    void manualSyncEnqueuesWithManualTrigger() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);

        SyncLog log = service.manualSync(ds.getId());

        assertThat(enqueued).hasSize(1);
        assertThat(enqueued.get(0).trigger()).isEqualTo("manual");
        assertThat(enqueued.get(0).dataSourceId()).isEqualTo(ds.getId());
        assertThat(enqueued.get(0).syncLogId()).isEqualTo(log.getId());
        assertThat(enqueued.get(0).forceFull()).isFalse();
    }

    /** 投递失败：两侧都落失败（数据源转 error + Go 的原文案）。 */
    @Test
    void manualSyncMarksBothSidesFailedOnEnqueueError() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        enqueueFailure = new com.ragagent.datasource.DataSourceSyncEnqueueException("queue down");

        assertThatThrownBy(() -> service.manualSync(ds.getId())).hasMessage("queue down");

        List<SyncLog> logs = syncLogRepo.findByDataSource(ds.getId(), 10, 0);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
        assertThat(logs.get(0).getErrorMessage()).isEqualTo("queue down");
        assertThat(logs.get(0).getFinishedAt()).isNotNull();

        DataSource after = dsRepo.findById(ds.getId());
        assertThat(after.getStatus()).isEqualTo(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
        assertThat(after.getErrorMessage()).isEqualTo("Failed to enqueue sync: queue down");
    }

    /** paused 的源投递失败时<b>保持</b> paused（它本来就是因为在暂停才没排期）。 */
    @Test
    void manualSyncKeepsPausedStatusOnEnqueueError() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS,
                DataSourceConstants.DATA_SOURCE_STATUS_PAUSED, null);
        enqueueFailure = new com.ragagent.datasource.DataSourceSyncEnqueueException("queue down");

        assertThatThrownBy(() -> service.manualSync(ds.getId()));

        DataSource after = dsRepo.findById(ds.getId());
        assertThat(after.getStatus()).isEqualTo(DataSourceConstants.DATA_SOURCE_STATUS_PAUSED);
    }

    // ══════════════════════════ pause / resume / delete ══════════════════════════

    @Test
    void pauseAndResumeFlipStatus() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);

        service.pauseDataSource(ds.getId());
        assertThat(dsRepo.findById(ds.getId()).getStatus())
                .isEqualTo(DataSourceConstants.DATA_SOURCE_STATUS_PAUSED);

        service.resumeDataSource(ds.getId());
        assertThat(dsRepo.findById(ds.getId()).getStatus())
                .isEqualTo(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
    }

    /** 删除：软删数据源 + 把它在途的同步日志标成 canceled。 */
    @Test
    void deleteSoftDeletesAndCancelsPendingLogs() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        SyncLog running = runningLog(ds.getId());

        service.deleteDataSource(ds.getId());

        assertThatThrownBy(() -> dsRepo.findById(ds.getId()))
                .isInstanceOf(DataSourceException.NotFoundException.class);
        assertThat(syncLogRepo.findById(running.getId()).getStatus())
                .isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_CANCELED);
    }

    // ══════════════════════════ ProcessSync（handle） ══════════════════════════

    @Test
    void handleCancelsWhenDataSourceIsGone() {
        SyncLog log = runningLog("ghost-ds");

        service.handle(payload("ghost-ds", log.getId(), false));

        SyncLog after = syncLogRepo.findById(log.getId());
        assertThat(after.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_CANCELED);
        assertThat(after.getErrorMessage()).isEqualTo("data source has been deleted");
    }

    @Test
    void handleCancelsWhenKnowledgeBaseIsGone() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        SyncLog log = runningLog(ds.getId());
        bridge.kb = null;

        service.handle(payload(ds.getId(), log.getId(), false));

        SyncLog after = syncLogRepo.findById(log.getId());
        assertThat(after.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_CANCELED);
        assertThat(after.getErrorMessage()).isEqualTo("knowledge base has been deleted");
    }

    @Test
    void handleFailsWhenConnectorIsNotRegistered() {
        DataSource ds = newDataSource("no-such-connector", null, null);
        SyncLog log = runningLog(ds.getId());

        assertThatThrownBy(() -> service.handle(payload(ds.getId(), log.getId(), false)))
                .isInstanceOf(ConnectorException.NotFound.class);

        SyncLog after = syncLogRepo.findById(log.getId());
        assertThat(after.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
        assertThat(after.getErrorMessage()).isEqualTo("Connector not found: no-such-connector");
        assertThat(dsRepo.findById(ds.getId()).getStatus())
                .isEqualTo(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
    }

    /** 抓取失败：sync_log 与 data_source 都落 failed，异常上抛（让队列按 maxRetry 重试）。 */
    @Test
    void handleFailsOnFetchError() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        SyncLog log = runningLog(ds.getId());
        connector.fetchFailure = new ConnectorException.FetchFailed("boom");

        assertThatThrownBy(() -> service.handle(payload(ds.getId(), log.getId(), false)))
                .hasMessageContaining("boom");

        SyncLog after = syncLogRepo.findById(log.getId());
        assertThat(after.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
        assertThat(after.getErrorMessage()).isEqualTo(
                "Fetch failed: failed to fetch items from source: boom");
        assertThat(dsRepo.findById(ds.getId()).getErrorMessage())
                .isEqualTo("Fetch failed: failed to fetch items from source: boom");
    }

    /** 成功路径：计数落 sync_log + data_source，状态回 active，last_sync_at 被填。 */
    @Test
    void handleSucceedsAndPersistsCounters() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        SyncLog log = runningLog(ds.getId());
        connector.incrementalItems = List.of(
                item("e1", "a", "hello".getBytes(), ""),
                item("e2", "b", "world".getBytes(), ""));
        connector.nextCursor = new SyncCursor();
        connector.nextCursor.setLastSyncTime(OffsetDateTime.now(ZoneOffset.UTC));

        service.handle(payload(ds.getId(), log.getId(), false));

        SyncLog after = syncLogRepo.findById(log.getId());
        assertThat(after.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        assertThat(after.getItemsTotal()).isEqualTo(2);
        assertThat(after.getItemsCreated()).isEqualTo(2);
        assertThat(after.getFinishedAt()).isNotNull();

        DataSource dsAfter = dsRepo.findById(ds.getId());
        assertThat(dsAfter.getStatus()).isEqualTo(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
        assertThat(dsAfter.getLastSyncAt()).isNotNull();
        assertThat(dsAfter.getLastSyncCursor()).isNotNull();
        assertThat(dsAfter.getLastSyncResult()).isNotNull();
        assertThat(bridge.created).hasSize(2);
    }

    /** 全部条目都失败 → 整次运行落 failed，且异常上抛。 */
    @Test
    void handleFailsWhenEveryItemFailed() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        SyncLog log = runningLog(ds.getId());
        bridge.createOutcomes.add(new ConnectorException("ingest blew up"));
        connector.incrementalItems = List.of(item("e1", "a", "x".getBytes(), ""));

        assertThatThrownBy(() -> service.handle(payload(ds.getId(), log.getId(), false)))
                .hasMessageContaining("all fetched items failed during sync (1/1)");

        SyncLog after = syncLogRepo.findById(log.getId());
        assertThat(after.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
        assertThat(after.getItemsFailed()).isEqualTo(1);
        // 详情取第一条错误样本的 Display()（"<title>: <message>"），超过 500 字节才截断
        assertThat(after.getErrorMessage()).isEqualTo(
                "all fetched items failed during sync (1/1): a: Ingest failed; see server logs");
    }

    /**
     * 部分失败（有成功、也有失败）→ <b>partial</b> 且错误文本带文档数，
     * <b>不</b>上抛异常、状态也不转 error。
     */
    @Test
    void handleMarksPartialWhenSomeItemsFailed() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        SyncLog log = runningLog(ds.getId());
        // 第一条灌入失败，第二条成功
        bridge.createOutcomes.add(new ConnectorException("first one blew up"));
        bridge.createOutcomes.add(null);
        connector.incrementalItems = List.of(
                item("e1", "bad", "x".getBytes(), ""),
                item("e2", "ok", "body".getBytes(), ""));

        service.handle(payload(ds.getId(), log.getId(), false));

        SyncLog after = syncLogRepo.findById(log.getId());
        assertThat(after.getStatus()).isEqualTo(DataSourceConstants.SYNC_LOG_STATUS_PARTIAL);
        assertThat(after.getItemsFailed()).isEqualTo(1);
        assertThat(after.getItemsCreated()).isEqualTo(1);
        assertThat(after.getErrorMessage()).isEqualTo("1 document(s) failed to sync");
        // partial 不是 failed：数据源回到 active
        assertThat(dsRepo.findById(ds.getId()).getStatus())
                .isEqualTo(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
    }

    /** 删除同步：命中旧行 → 软删 + 硬删各一次，计数进 deleted。 */
    @Test
    void handleDeletesItemsAndCountsThem() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        jdbc.update("UPDATE data_sources SET sync_deletions = TRUE WHERE id = ?", ds.getId());
        ds.setSyncDeletions(true);
        SyncLog log = runningLog(ds.getId());

        Knowledge existing = new Knowledge();
        existing.setId("k-1");
        bridge.existingByExternalId = existing;
        connector.incrementalItems = List.of(deletedItem("e1"));

        service.handle(payload(ds.getId(), log.getId(), false));

        assertThat(bridge.softDeleted).containsExactly("k-1");
        assertThat(bridge.hardDeleted).containsExactly("k-1");
        assertThat(syncLogRepo.findById(log.getId()).getItemsDeleted()).isEqualTo(1);
    }

    /** 关闭删除同步：既不计也不删。 */
    @Test
    void handleSkipsDeletionsWhenDisabled() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        jdbc.update("UPDATE data_sources SET sync_deletions = FALSE WHERE id = ?", ds.getId());
        ds.setSyncDeletions(false);
        SyncLog log = runningLog(ds.getId());
        bridge.existingByExternalId = new Knowledge();
        connector.incrementalItems = List.of(deletedItem("e1"));

        service.handle(payload(ds.getId(), log.getId(), false));

        assertThat(bridge.softDeleted).isEmpty();
        SyncLog after = syncLogRepo.findById(log.getId());
        assertThat(after.getItemsDeleted()).isZero();
        assertThat(after.getItemsSkipped()).isZero();
    }

    /** 删除时源端条目本来就不在库里 → 计入 skipped（幂等）。 */
    @Test
    void handleSkipsDeletionWhenRowIsAlreadyGone() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        jdbc.update("UPDATE data_sources SET sync_deletions = TRUE WHERE id = ?", ds.getId());
        ds.setSyncDeletions(true);
        SyncLog log = runningLog(ds.getId());
        bridge.existingByExternalId = null;
        connector.incrementalItems = List.of(deletedItem("e1"));

        service.handle(payload(ds.getId(), log.getId(), false));

        assertThat(syncLogRepo.findById(log.getId()).getItemsSkipped()).isEqualTo(1);
    }

    /** 空 external_id 的删除项跳过并计入 skipped（对照 Go 的 warn 分支）。 */
    @Test
    void handleSkipsDeletionWithEmptyExternalId() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        jdbc.update("UPDATE data_sources SET sync_deletions = TRUE WHERE id = ?", ds.getId());
        ds.setSyncDeletions(true);
        SyncLog log = runningLog(ds.getId());
        connector.incrementalItems = List.of(deletedItem(""));

        service.handle(payload(ds.getId(), log.getId(), false));

        assertThat(syncLogRepo.findById(log.getId()).getItemsSkipped()).isEqualTo(1);
    }

    /** 连接器给的是"抓取错误的空条目" → 计 failed 并带结构化错误样本。 */
    @Test
    void handleCountsConnectorErrorItemsAsFailed() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        SyncLog log = runningLog(ds.getId());
        FetchedItem errItem = new FetchedItem();
        errItem.setExternalId("e1");
        errItem.setTitle("t");
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("error", "connection refused");
        errItem.setMetadata(meta);
        connector.incrementalItems = List.of(errItem);

        assertThatThrownBy(() -> service.handle(payload(ds.getId(), log.getId(), false)));

        SyncLog after = syncLogRepo.findById(log.getId());
        assertThat(after.getItemsFailed()).isEqualTo(1);
        // 全失败 → 整体失败文案带第一条样本的 Display()
        assertThat(after.getErrorMessage()).contains("connection refused");
    }

    // ══════════════════════════ 纯函数 ══════════════════════════

    @Test
    void recordSyncErrorCapsSamplesAt100() {
        SyncResult result = new SyncResult();
        for (int i = 0; i < 150; i++) {
            SyncItemError e = new SyncItemError();
            e.setMessage("e" + i);
            DataSourceService.recordSyncError(result, e);
        }
        assertThat(result.getErrors()).hasSize(100);
        assertThat(result.getErrors().get(0).getMessage()).isEqualTo("e0");
    }

    @Test
    void allFetchedItemsFailedOnlyWhenEveryItemFailed() {
        assertThat(DataSourceService.allFetchedItemsFailedError(null)).isNull();

        SyncResult empty = new SyncResult();
        assertThat(DataSourceService.allFetchedItemsFailedError(empty)).isNull();

        SyncResult partial = new SyncResult();
        partial.setTotal(2);
        partial.setFailed(1);
        assertThat(DataSourceService.allFetchedItemsFailedError(partial)).isNull();

        SyncResult withSkips = new SyncResult();
        withSkips.setTotal(2);
        withSkips.setFailed(2);
        withSkips.setSkipped(1);
        assertThat(DataSourceService.allFetchedItemsFailedError(withSkips)).isNull();

        SyncResult allFailed = new SyncResult();
        allFailed.setTotal(3);
        allFailed.setFailed(3);
        assertThat(DataSourceService.allFetchedItemsFailedError(allFailed))
                .isEqualTo("all fetched items failed during sync (3/3)");
    }

    /** 详情超过 500 字节要截断（对照 Go 的 {@code maxDetailLen}）。 */
    @Test
    void allFetchedItemsFailedTruncatesLongDetail() {
        SyncResult result = new SyncResult();
        result.setTotal(1);
        result.setFailed(1);
        SyncItemError e = new SyncItemError();
        e.setMessage("x".repeat(600));
        result.setErrors(List.of(e));

        String message = DataSourceService.allFetchedItemsFailedError(result);
        assertThat(message).endsWith("...");
        assertThat(message.length()).isLessThan(600 + 80);
    }

    @Test
    void fetchFailureSyncErrorPrefersStructuredCode() {
        FetchedItem it = new FetchedItem();
        it.setTitle("doc");
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("error_reason_code", "FEISHU_TOKEN_EXPIRED");
        meta.put("error_reason_code_value", "99991663");
        meta.put("error_reason", "token expired");
        it.setMetadata(meta);

        SyncItemError e = DataSourceService.fetchFailureSyncError(it, "raw message");

        assertThat(e.getCode()).isEqualTo("FEISHU_TOKEN_EXPIRED");
        assertThat(e.getParams()).containsEntry("code", "99991663");
        assertThat(e.getMessage()).isEqualTo("token expired");
    }

    @Test
    void fetchFailureSyncErrorFallsBackToRawMessage() {
        FetchedItem it = new FetchedItem();
        it.setTitle("doc");

        SyncItemError e = DataSourceService.fetchFailureSyncError(it, "raw message");

        assertThat(e.getCode()).isEmpty();
        assertThat(e.getMessage()).isEqualTo("raw message");
    }

    @Test
    void streamStartCursorDropsCursorOnlyOnFirstFullAttempt() {
        DataSource ds = new DataSource();
        SyncCursor stored = new SyncCursor();
        stored.setLastSyncTime(OffsetDateTime.now(ZoneOffset.UTC));
        ds.setLastSyncCursor(stored.toJSON());

        assertThat(DataSourceService.streamStartCursor(ds, true, 0)).isNull();
        assertThat(DataSourceService.streamStartCursor(ds, true, 1)).isNotNull();
        assertThat(DataSourceService.streamStartCursor(ds, false, 0)).isNotNull();
    }

    @Test
    void syntheticUserIdMatchesGoRule() {
        assertThat(DataSourceService.isSyntheticUserId("system-42")).isTrue();
        assertThat(DataSourceService.isSyntheticUserId("system-")).isFalse();
        assertThat(DataSourceService.isSyntheticUserId("system-4a2")).isFalse();
        assertThat(DataSourceService.isSyntheticUserId("user-1")).isFalse();
        assertThat(DataSourceService.isSyntheticUserId("")).isFalse();
        assertThat(DataSourceService.isSyntheticUserId(null)).isFalse();
    }

    /** paused 的源跑完一次成功的同步后仍然 paused（手动同步不改它的排期状态）。 */
    @Test
    void pausedDataSourceStaysPausedAfterSuccessfulRun() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS,
                DataSourceConstants.DATA_SOURCE_STATUS_PAUSED, null);
        SyncLog log = runningLog(ds.getId());
        connector.incrementalItems = List.of(item("e1", "a", "x".getBytes(), ""));

        service.handle(payload(ds.getId(), log.getId(), false));

        assertThat(dsRepo.findById(ds.getId()).getStatus())
                .isEqualTo(DataSourceConstants.DATA_SOURCE_STATUS_PAUSED);
    }

    /** 全量同步走 fetchAll；增量走 fetchIncremental。 */
    @Test
    void fullSyncModeUsesFetchAll() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS,
                null, DataSourceConstants.SYNC_MODE_FULL);
        SyncLog log = runningLog(ds.getId());
        connector.allItems = List.of(item("e1", "a", "x".getBytes(), ""));
        connector.incrementalItems = List.of(item("e2", "b", "y".getBytes(), ""));

        service.handle(payload(ds.getId(), log.getId(), false));

        assertThat(syncLogRepo.findById(log.getId()).getItemsCreated()).isEqualTo(1);
        assertThat(bridge.created).hasSize(1);
        // 标题就是文件名（Go 的 CreateKnowledgeFromFile 用 fileName 当 Title）
        assertThat(bridge.created.get(0).getTitle()).isEqualTo("a.md");
    }

    /** 分页透传给仓储：limit/offset 生效。 */
    @Test
    void getSyncLogsHonoursPaging() {
        DataSource ds = newDataSource(DataSourceConstants.CONNECTOR_TYPE_RSS, null, null);
        for (int i = 0; i < 3; i++) {
            runningLog(ds.getId());
        }
        assertThat(service.getSyncLogs(ds.getId(), 2, 0)).hasSize(2);
        assertThat(service.getSyncLogs(ds.getId(), 2, 2)).hasSize(1);
    }

    /** 未知 id 的日志：仓储抛 NotFound（handler 映射成 404 sync log not found）。 */
    @Test
    void getSyncLogThrowsNotFoundForUnknownId() {
        assertThatThrownBy(() -> service.getSyncLog("nope"))
                .isInstanceOf(DataSourceException.NotFoundException.class);
    }

    /** 数据源带着 cron 表达式创建时会注册排期（不抛即可）。 */
    @Test
    void createWithScheduleRegistersCron() {
        DataSource ds = new DataSource();
        ds.setTenantId(TENANT);
        ds.setKnowledgeBaseId(KB_ID);
        ds.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);
        ds.setName("scheduled");
        ds.setSyncSchedule("0 0 * * * *");

        DataSource created = service.createDataSource(ds);
        assertThat(created.getStatus()).isEqualTo(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
    }

    /** 时长常量与调度器共享（对照 Go 的 asynq.MaxRetry(5) / Timeout(2h)）。 */
    @Test
    void taskBudgetMatchesGoAsynqOptions() {
        assertThat(Scheduler.MAX_RETRY).isEqualTo(5);
        assertThat(Scheduler.TASK_TIMEOUT).isEqualTo(Duration.ofHours(2));
    }
}
