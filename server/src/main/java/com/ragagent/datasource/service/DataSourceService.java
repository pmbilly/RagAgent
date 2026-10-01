package com.ragagent.datasource.service;

import com.ragagent.common.web.JsonMappers;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.auth.service.TenantService;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorRegistry;
import com.ragagent.datasource.DataSourceSyncHandler;
import com.ragagent.datasource.DataSourceSyncTaskQueue;
import com.ragagent.datasource.Scheduler;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceException;
import com.ragagent.datasource.domain.DataSourceSyncPayload;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.mapper.DataSourceRepository;
import com.ragagent.datasource.mapper.SyncLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 数据源的应用服务（对照 Go {@code internal/application/service/datasource_service.go}
 * 全文，1488 行）。
 *
 * <h2>职责边界</h2>
 * <ol>
 *   <li><b>管理面</b>：17 条 HTTP 路由的全部业务语义（CRUD、凭据子资源、连接校验、
 *       资源枚举、同步控制、同步日志）；</li>
 *   <li><b>数据面</b>：{@link DataSourceSyncHandler}——把队列里的
 *       {@code datasource:sync} 任务真正跑起来（抓取 → 灌入 → 检查点 → 落结果）。</li>
 * </ol>
 *
 * <h2>ctx 去哪了（约定 §5）</h2>
 * <p>Go 的这些方法第一个参数都是 {@code context.Context}，它同时承载租户、主体与取消。
 * Java 侧：租户/主体走 {@code TenantContext}（ThreadLocal），取消走<b>线程中断</b>
 * （{@link #handle} 跑在队列的虚拟线程上，超时由 {@code Future.cancel(true)} 打断）。
 * 因此凡是 Go "把 ctx 往下传"的地方，Java 都换成了显式参数——尤其
 * {@link KnowledgeBridge}：它把租户当参数，因为同步线程上没有请求上下文。</p>
 *
 * <h2>与 Go 的逐条对应（方法级）</h2>
 * <table>
 *   <tr><td>CreateDataSource</td><td>{@link #createDataSource}</td></tr>
 *   <tr><td>GetDataSource</td><td>{@link #getDataSource}</td></tr>
 *   <tr><td>ListDataSources</td><td>{@link #listDataSources}</td></tr>
 *   <tr><td>UpdateDataSource</td><td>{@link #updateDataSource}</td></tr>
 *   <tr><td>UpdateDataSourceCredentials</td><td>{@link #updateDataSourceCredentials}</td></tr>
 *   <tr><td>ClearDataSourceCredentials</td><td>{@link #clearDataSourceCredentials}</td></tr>
 *   <tr><td>DeleteDataSource</td><td>{@link #deleteDataSource}</td></tr>
 *   <tr><td>ValidateConnection</td><td>{@link #validateConnection}</td></tr>
 *   <tr><td>ValidateCredentials</td><td>{@link #validateCredentials}</td></tr>
 *   <tr><td>ListAvailableResources</td><td>{@link #listAvailableResources}</td></tr>
 *   <tr><td>ResolveResourceAncestors</td><td>{@link #resolveResourceAncestors}</td></tr>
 *   <tr><td>ManualSync</td><td>{@link #manualSync}</td></tr>
 *   <tr><td>PauseDataSource</td><td>{@link #pauseDataSource}</td></tr>
 *   <tr><td>ResumeDataSource</td><td>{@link #resumeDataSource}</td></tr>
 *   <tr><td>GetSyncLogs</td><td>{@link #getSyncLogs}</td></tr>
 *   <tr><td>GetSyncLog</td><td>{@link #getSyncLog}</td></tr>
 *   <tr><td>ProcessSync</td><td>{@link #handle}</td></tr>
 * </table>
 *
 * <h2>错误语义：Go 的哨兵错误 → Java 的异常类型</h2>
 * <ul>
 *   <li>{@code datasource.ErrDataSourceInvalid} → {@link DataSourceException}
 *       且 message 逐字为 {@value #ERR_DATA_SOURCE_INVALID}（handler 直接把
 *       {@code err.Error()} 当响应体输出）；</li>
 *   <li>{@code datasource.ErrKnowledgeBaseNotFound} → {@value #ERR_KNOWLEDGE_BASE_NOT_FOUND}；</li>
 *   <li>{@code datasource.ErrDataSourceNotActive} → {@value #ERR_DATA_SOURCE_NOT_ACTIVE}；</li>
 *   <li>{@code datasource.ErrInvalidConfig} → {@link ConnectorException.InvalidConfig}
 *       （{@value #ERR_INVALID_CONFIG}）；</li>
 *   <li>注册表未命中 → {@link ConnectorException.NotFound}
 *       （{@code "connector type not found in registry"}）。</li>
 * </ul>
 * <p>仓储的 {@code FindByID} 未命中抛 {@link DataSourceException.NotFoundException}
 * ——service 原样上抛，由 handler 映射成 404（Go 里也是 handler 无条件映射）。</p>
 *
 * <h2>已知差异（逐条都有理由，见各方法注释）</h2>
 * <ol>
 *   <li><b>asynq → 进程内队列</b>：拿不到 {@code asynq.GetRetryCount}，
 *       所以 {@code streamStartCursor} 的 attempt 恒为 0（首次尝试）；
 *       拿不到 {@code asynq.GetTaskID}，所以同步审计的 details 少
 *       {@code task_id} 一个键（{@code trigger} 与 {@code processing_status} 照常）。</li>
 *   <li><b>langfuse 追踪未接线</b>：{@code langfuse.InjectTracing} 是 no-op
 *       （§9 阶段 4.0 已知差异 1）。</li>
 *   <li><b>知识库写入是"最小闭环"</b>，见 {@link KnowledgeBridge} 的类注释。</li>
 * </ol>
 */
@Service
public class DataSourceService implements DataSourceSyncHandler {

    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);

    static final ObjectMapper MAPPER = JsonMappers.lenient();

    /** 对照 Go {@code datasource.ErrDataSourceInvalid}。 */
    public static final String ERR_DATA_SOURCE_INVALID = "data source configuration is invalid";
    /** 对照 Go {@code datasource.ErrKnowledgeBaseNotFound}。 */
    public static final String ERR_KNOWLEDGE_BASE_NOT_FOUND = "knowledge base not found";
    /** 对照 Go {@code datasource.ErrDataSourceNotActive}。 */
    public static final String ERR_DATA_SOURCE_NOT_ACTIVE = "data source is not active";
    /** 对照 Go {@code datasource.ErrInvalidConfig}。 */
    public static final String ERR_INVALID_CONFIG = "invalid configuration";
    /** 对照 Go 的 {@code fmt.Errorf("changing knowledge base is not allowed")}。 */
    public static final String ERR_KB_CHANGE_FORBIDDEN = "changing knowledge base is not allowed";

    /** 对照 Go {@code maxSyncResultErrors}：per-item 错误样本的上限。 */
    static final int MAX_SYNC_RESULT_ERRORS = 100;

    /** 对照 Go {@code datasource.Scheduler.MAX_RETRY} / {@code TASK_TIMEOUT}。 */
    static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    final DataSourceRepository dsRepo;
    final SyncLogRepository syncLogRepo;
    final KnowledgeBridge knowledge;
    final DataSourceSyncTaskQueue taskQueue;
    final ConnectorRegistry connectorRegistry;
    final Scheduler scheduler;
    final TenantService tenantService;
    final AuditLogService audit;
    final AutoTagProvider autoTagProvider;

    /** 结果落库协作者（构造期装配）。 */
    final DataSourceSyncResultOps resultOps;

    /** 支撑件协作者（构造期装配）。 */
    final DataSourceSupport support;

    /** 条目灌入协作者（构造期装配）。 */
    final DataSourceItemOps itemOps;

    /** 同步执行协作者（构造期装配）。 */
    final DataSourceSyncExecutor executor;

    public DataSourceService(DataSourceRepository dsRepo,
                             SyncLogRepository syncLogRepo,
                             KnowledgeBridge knowledge,
                             DataSourceSyncTaskQueue taskQueue,
                             ConnectorRegistry connectorRegistry,
                             Scheduler scheduler,
                             TenantService tenantService,
                             AuditLogService audit,
                             AutoTagProvider autoTagProvider) {
        this.dsRepo = dsRepo;
        this.syncLogRepo = syncLogRepo;
        this.knowledge = knowledge;
        this.taskQueue = taskQueue;
        this.connectorRegistry = connectorRegistry;
        this.scheduler = scheduler;
        this.tenantService = tenantService;
        this.audit = audit;
        this.autoTagProvider = autoTagProvider;
        this.resultOps = new DataSourceSyncResultOps(this);
        this.support = new DataSourceSupport(this);
        this.itemOps = new DataSourceItemOps(this);
        this.executor = new DataSourceSyncExecutor(this);
    }

    // ══════════════════════════ 管理面 ══════════════════════════

    /**
     * 对照 Go {@code CreateDataSource}（L68-117）。
     *
     * <p>顺序有语义：<b>先</b>查知识库（不存在 → {@code knowledge base not found}）、
     * <b>再</b>检查连接器类型（未登记 → {@code connector type not found in registry}）、
     * <b>再</b>剥非密钥凭据并落 {@code ds.Config}、<b>最后</b>跑一次真实连接校验。
     * 前两步的判定对象不同（一个是另一个租户的库、一个是没实现的连接器），
     * 换顺序会让错误文案变。</p>
     */
    public DataSource createDataSource(DataSource ds) {
        if (ds == null) {
            throw new DataSourceException(ERR_DATA_SOURCE_INVALID);
        }
        support.requireOwnedKnowledgeBase(ds.getKnowledgeBaseId(), ds.getTenantId());
        connectorRegistry.get(ds.getType());

        DataSourceConfig cfg = ds.parseConfig();
        if (cfg != null) {
            cfg.stripNonSecretCredentials(ds.getType());
            ds.setConfig(cfg.toJSON());
        }
        support.validateDataSourceConfig(ds);

        dsRepo.create(ds);

        if (ds.getSyncSchedule() != null && !ds.getSyncSchedule().isEmpty()
                && DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE.equals(ds.getStatus())) {
            try {
                scheduler.addOrUpdate(ds);
            } catch (RuntimeException e) {
                log.warn("[datasource] failed to register cron for ds={}: {}",
                        ds.getId(), e.getMessage());
            }
        }

        log.info("[datasource] data source created: id={} type={} kb={}",
                ds.getId(), ds.getType(), ds.getKnowledgeBaseId());
        support.recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_CREATED, "data_source", ds.getId(),
                AuditOutcome.SUCCESS, DataSourceSupport.mapOf("name", ds.getName(), "type", ds.getType()),
                null, false);
        return ds;
    }

    /** 对照 Go {@code GetDataSource}（L120-126）：仓储未命中抛 NotFound，原样上抛。 */
    public DataSource getDataSource(String id) {
        return dsRepo.findById(id);
    }

    /**
     * 对照 Go {@code ListDataSources}（L129-145）：给每个数据源补上"最近一次同步日志"。
     *
     * <p>{@code FindLatest} 失败被忽略（Go 的 {@code log, _ := …}）；它查不到时回
     * {@code nil} 而不是错误，所以 {@code latest_sync_log} 的缺省形态是不输出该键。
     * 注意 Go 这一路<b>不</b>回填 {@code total_items_synced}（那个字段恒为 0）。</p>
     */
    public List<DataSource> listDataSources(String kbId) {
        List<DataSource> dataSources;
        try {
            dataSources = dsRepo.findByKnowledgeBase(kbId);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to list data sources: {}", e.getMessage());
            throw e;
        }
        for (DataSource ds : dataSources) {
            SyncLog latest = null;
            try {
                latest = syncLogRepo.findLatest(ds.getId());
            } catch (RuntimeException ignored) {
                // 对照 Go 的 `log, _ := s.syncLogRepo.FindLatest(...)`
            }
            if (latest != null) {
                ds.setLatestSyncLog(latest);
            }
        }
        return dataSources;
    }

    /**
     * 对照 Go {@code UpdateDataSource}（L148-233）。
     *
     * <h2>凭据永不从这条端点流入</h2>
     * <p>Go 的注释把话写死了：凭据住在 {@code /credentials} 子资源后面，PUT 主体里
     * 就算带了也要被<b>整块换回库里的旧值</b>。Config 的其余部分
     * （Type / ResourceIDs / Settings）照常流过去。带了凭据只记一条 warn——
     * 那是"还有老客户端在用"的信号，不是错误。</p>
     *
     * <h2>什么时候才跑连接校验</h2>
     * <p>只有"库里有可用凭据"<b>且</b>（类型变了<b>或</b>解析后的配置真的变了）才校验。
     * 理由（Go 注释）：还没存过凭据时校验必然失败（没 token 可调），
     * 而结构完全相同的重复提交没必要再打一次外部 API。</p>
     *
     * <p>⚠️ {@code configActuallyChanged} 用 {@code reflect.DeepEqual} 判"整块配置是否
     * 逐字段相同"。Java 侧用<b>规范化 JSON 树相等</b>表达（字段集与 Go 结构体一致：
     * type / credentials / resource_ids / settings），见 {@link #configDeepEquals}。</p>
     */
    public DataSource updateDataSource(DataSource ds) {
        if (ds == null || ds.getId() == null || ds.getId().isEmpty()) {
            throw new DataSourceException(ERR_DATA_SOURCE_INVALID);
        }
        DataSource existing = dsRepo.findById(ds.getId());

        if (ds.getKnowledgeBaseId() == null || ds.getKnowledgeBaseId().isEmpty()) {
            ds.setKnowledgeBaseId(existing.getKnowledgeBaseId());
        }
        if (!Objects.equals(ds.getKnowledgeBaseId(), existing.getKnowledgeBaseId())) {
            throw new DataSourceException(ERR_KB_CHANGE_FORBIDDEN);
        }

        if (ds.getTenantId() == null || ds.getTenantId() == 0L) {
            ds.setTenantId(existing.getTenantId());
        }
        if (!Objects.equals(ds.getTenantId(), existing.getTenantId())) {
            throw new DataSourceException(ERR_DATA_SOURCE_INVALID);
        }

        DataSourceConfig mergedCfg = null;
        DataSourceConfig existingParsedCfg = null;
        if (ds.getConfig() != null) {
            DataSourceConfig incomingCfg = ds.parseConfig();
            DataSourceConfig existingCfg = existing.parseConfig();
            if (incomingCfg != null) {
                if (incomingCfg.hasCredentials()) {
                    log.warn("[datasource] deprecated: credentials in PUT /datasource/{} body are "
                            + "ignored; use PUT /credentials instead", ds.getId());
                }
                DataSourceConfig merged = new DataSourceConfig();
                merged.setType(incomingCfg.getType());
                merged.setResourceIds(incomingCfg.getResourceIds());
                merged.setSettings(incomingCfg.getSettings());
                merged.setCredentials(existingCfg == null ? null : existingCfg.getCredentials());
                merged.stripNonSecretCredentials(ds.getType());
                ds.setConfig(merged.toJSON());
                mergedCfg = merged;
                existingParsedCfg = existingCfg;
            }
        }

        boolean configActuallyChanged = true;
        if (mergedCfg != null && existingParsedCfg != null) {
            configActuallyChanged = !DataSourceSupport.configDeepEquals(mergedCfg, existingParsedCfg);
        }
        boolean hasCreds = mergedCfg != null
                && mergedCfg.hasConfiguredCredentials(ds.getType());
        if (hasCreds && (!Objects.equals(ds.getType(), existing.getType()) || configActuallyChanged)) {
            support.validateDataSourceConfig(ds);
        }

        try {
            dsRepo.update(ds);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to update data source: {}", e.getMessage());
            throw e;
        }

        try {
            scheduler.addOrUpdate(ds);
        } catch (RuntimeException e) {
            log.warn("[datasource] failed to update cron for ds={}: {}", ds.getId(), e.getMessage());
        }

        log.info("[datasource] data source updated: id={}", ds.getId());
        support.recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_UPDATED, "data_source", ds.getId(),
                AuditOutcome.SUCCESS,
                DataSourceSupport.mapOf("name", ds.getName(), "type", ds.getType(),
                        "changed_fields", List.of("settings")),
                null, false);
        return ds;
    }

    /**
     * 对照 Go {@code UpdateDataSourceCredentials}（L240-279）：整张 map 原子替换。
     *
     * <p>不能按 key 打补丁——"配了一半的凭据"根本认证不了，所以旧的一律丢弃。
     * 写库<b>之后</b>立刻跑一次真实连接校验，让用户当场知道新 token 对不对，
     * 而不是等下一次定时同步才发现。</p>
     */
    public DataSource updateDataSourceCredentials(String id, Map<String, Object> credentials) {
        if (id == null || id.isEmpty()) {
            throw new DataSourceException(ERR_DATA_SOURCE_INVALID);
        }
        DataSource existing = dsRepo.findById(id);
        DataSourceConfig parsed = existing.parseConfig();
        if (parsed == null) {
            parsed = new DataSourceConfig();
            parsed.setType(existing.getType());
        }
        parsed.setCredentials(credentials);
        parsed.stripNonSecretCredentials(existing.getType());
        existing.setConfig(parsed.toJSON());

        support.validateDataSourceConfig(existing);
        dsRepo.update(existing);
        log.info("[datasource] DataSource credentials updated: id={}", id);
        support.recordKbActivity(existing.getTenantId(), existing.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_UPDATED, "data_source", existing.getId(),
                AuditOutcome.SUCCESS,
                DataSourceSupport.mapOf("name", existing.getName(), "type", existing.getType(),
                        "changed_fields", List.of("credentials")),
                null, false);
        return existing;
    }

    /**
     * 对照 Go {@code ClearDataSourceCredentials}（L283-321）：清空凭据、幂等。
     *
     * <p>已经是空的时候走的是另一条分支：只把"剥掉非密钥项之后"的配置写回去，
     * <b>不</b>记审计（Go 在那条分支上直接 return）——因为这次调用什么都没改。</p>
     */
    public void clearDataSourceCredentials(String id) {
        if (id == null || id.isEmpty()) {
            throw new DataSourceException(ERR_DATA_SOURCE_INVALID);
        }
        DataSource existing = dsRepo.findById(id);
        DataSourceConfig parsed = existing.parseConfig();
        if (parsed == null) {
            return;
        }
        parsed.stripNonSecretCredentials(existing.getType());
        if (!parsed.hasConfiguredCredentials(existing.getType())) {
            existing.setConfig(parsed.toJSON());
            dsRepo.update(existing);
            return;
        }
        parsed.setCredentials(null);
        existing.setConfig(parsed.toJSON());
        dsRepo.update(existing);
        log.info("[datasource] DataSource credentials cleared by user: id={}", id);
        support.recordKbActivity(existing.getTenantId(), existing.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_UPDATED, "data_source", existing.getId(),
                AuditOutcome.SUCCESS,
                DataSourceSupport.mapOf("name", existing.getName(), "type", existing.getType(),
                        "changed_fields", List.of("credentials")),
                null, false);
    }

    /**
     * 对照 Go {@code DeleteDataSource}（L324-349）：软删 + 摘定时任务 + 作废在途同步日志。
     *
     * <p>第三步让"已经排队但还没跑的 asynq 任务"不再重试——它们醒来时会发现数据源
     * 已删（{@link #handle} 的第一段），把同步日志置成 canceled 后安静返回。</p>
     */
    public void deleteDataSource(String id) {
        DataSource existing = dsRepo.findById(id);

        try {
            dsRepo.delete(id);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to delete data source: {}", e.getMessage());
            throw e;
        }

        scheduler.remove(id);

        try {
            syncLogRepo.cancelPendingByDataSource(id);
        } catch (RuntimeException e) {
            log.warn("[datasource] failed to cancel pending sync logs for ds={}: {}", id, e.getMessage());
        }

        log.info("[datasource] data source deleted: id={}", id);
        support.recordKbActivity(existing.getTenantId(), existing.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_DELETED, "data_source", existing.getId(),
                AuditOutcome.SUCCESS,
                DataSourceSupport.mapOf("name", existing.getName(), "type", existing.getType()),
                null, false);
    }

    /**
     * 对照 Go {@code ValidateConnection}（L352-387）。
     *
     * <p>校验失败<b>不是</b>只回个错误就完事：它把数据源置为 {@code error} 并落库，
     * 好让列表页立刻显示"这个源连不上"。反向也对称——原本是 error 的源校验通过后
     * 会回到 active 并清掉错误消息（这个清空必须走 {@code update}，
     * Go 那边也一样）。</p>
     */
    public void validateConnection(String dsId) {
        DataSource ds = getDataSource(dsId);
        Connector connector = connectorRegistry.get(ds.getType());
        DataSourceConfig config = DataSourceSupport.parseConfigOrInvalid(ds);
        try {
            connector.validate(config);
        } catch (RuntimeException e) {
            ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            ds.setErrorMessage(e.getMessage());
            support.bestEffortUpdate(ds);
            throw e;
        }
        if (DataSourceConstants.DATA_SOURCE_STATUS_ERROR.equals(ds.getStatus())) {
            ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
            ds.setErrorMessage("");
            support.bestEffortUpdate(ds);
        }
    }

    /**
     * 对照 Go {@code ValidateCredentials}（L1203-1217）：用裸凭据试连，<b>什么都不落库</b>
     * ——前端"测试连接"按钮走的就是这条，用户还没保存就该能试。
     */
    public void validateCredentials(String connectorType, Map<String, Object> credentials) {
        Connector connector = connectorRegistry.get(connectorType);
        DataSourceConfig config = new DataSourceConfig();
        config.setType(connectorType);
        config.setCredentials(credentials);
        connector.validate(config);
    }

    /** 对照 Go {@code ListAvailableResources}（L392-420）。 */
    public List<Resource> listAvailableResources(String dsId, String parentId) {
        DataSource ds = getDataSource(dsId);
        Connector connector = connectorRegistry.get(ds.getType());
        DataSourceConfig config = DataSourceSupport.parseConfigOrInvalid(ds);
        List<Resource> resources;
        try {
            resources = connector.listResources(config, parentId);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to list resources: {}", e.getMessage());
            throw e;
        }
        return resources;
    }

    /**
     * 对照 Go {@code ResolveResourceAncestors}（L424-453）。
     *
     * <p>⚠️ <b>空入参直接短路</b>：Go 在 {@code len(resourceIDs) == 0} 时就 return 空切片，
     * 连数据源都不查——所以"给一个不存在的数据源 + 空 resource_ids"回的是
     * <b>200 + {"ancestors":[]}</b>，不是 404。这条实测行为很容易在重构时被"顺手修正"。</p>
     */
    public List<String> resolveResourceAncestors(String dsId, List<String> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return new ArrayList<>();
        }
        DataSource ds = getDataSource(dsId);
        Connector connector = connectorRegistry.get(ds.getType());
        DataSourceConfig config = DataSourceSupport.parseConfigOrInvalid(ds);
        List<String> ancestors;
        try {
            ancestors = connector.resolveResourceAncestors(config, resourceIds);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to resolve resource ancestors: {}", e.getMessage());
            throw e;
        }
        return ancestors;
    }

    // ══════════════════════════ 同步控制 ══════════════════════════

    /**
     * 对照 Go {@code ManualSync}（L456-522）。
     *
     * <p>三态才允许手动同步：active / error / paused（paused 也允许——"暂停"停的是
     * 定时排期，不是手动触发）。</p>
     *
     * <p><b>投递失败会把两侧都写成失败</b>：sync_log 落 failed + 完成时间 + 错误原文，
     * data_source 落 error + {@code "Failed to enqueue sync: <原因>"}（paused 的源不改
     * 状态——它本来就是因为在暂停才没排期）。</p>
     *
     * <h2>已知差异：TaskID</h2>
     * <p>Go 的 {@code asynq.NewTask} 没给 TaskID，由 asynq 生成一个随机 ID；
     * Java 侧显式生成 UUID 当 TaskID（同语义：不与任何东西去重）。
     * 审计里的 {@code task_id} 因此两边格式不同——它是不透明串，前端只回显。</p>
     */
    public SyncLog manualSync(String dsId) {
        DataSource ds = getDataSource(dsId);

        String status = ds.getStatus();
        if (!DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE.equals(status)
                && !DataSourceConstants.DATA_SOURCE_STATUS_ERROR.equals(status)
                && !DataSourceConstants.DATA_SOURCE_STATUS_PAUSED.equals(status)) {
            throw new DataSourceException(ERR_DATA_SOURCE_NOT_ACTIVE);
        }

        SyncLog syncLog = new SyncLog();
        syncLog.setDataSourceId(dsId);
        syncLog.setTenantId(ds.getTenantId());
        syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_RUNNING);
        syncLog.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        try {
            syncLogRepo.create(syncLog);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to create sync log: {}", e.getMessage());
            throw e;
        }

        String taskId = UUID.randomUUID().toString();
        // 入队侧注入（对照 Go 的 langfuse.InjectTracing(ctx, payload)）：把请求的
        // traceparent 打进载荷（平铺 lf_* 键），worker 侧续接同一棵树
        DataSourceSyncPayload payload = DataSourceSyncPayload.withTracing(
                DataSourceSupport.taskInitiatorFromContext(), "manual", dsId, ds.getTenantId(),
                syncLog.getId(), false, 0,
                com.ragagent.tracing.langfuse.LangfuseTracing.inject());

        DataSourceSyncTaskQueue.Outcome outcome;
        try {
            outcome = taskQueue.enqueue(payload, taskId, Scheduler.MAX_RETRY, Scheduler.TASK_TIMEOUT);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to enqueue sync task: {}", e.getMessage());
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage(e.getMessage());
            support.bestEffortUpdateLog(syncLog);
            if (!DataSourceConstants.DATA_SOURCE_STATUS_PAUSED.equals(ds.getStatus())) {
                ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            }
            ds.setErrorMessage("Failed to enqueue sync: " + e.getMessage());
            support.bestEffortUpdate(ds);
            support.recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                    AuditAction.DATASOURCE_SYNC_FAILED, "data_source", ds.getId(),
                    AuditOutcome.FAILED,
                    DataSourceSupport.mapOf("name", ds.getName(), "type", ds.getType(),
                            "sync_log_id", syncLog.getId(), "trigger", "manual"),
                    null, false);
            throw e;
        }

        log.info("[datasource] sync task enqueued: ds={} syncLog={}", dsId, syncLog.getId());
        support.recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_SYNC_STARTED, "data_source", ds.getId(),
                AuditOutcome.ACCEPTED,
                DataSourceSupport.mapOf("name", ds.getName(), "type", ds.getType(),
                        "sync_log_id", syncLog.getId(), "task_id", taskId,
                        "trigger", "manual", "processing_status", "pending"),
                null, false);
        return syncLog;
    }

    /** 对照 Go {@code PauseDataSource}（L525-544）：置 paused + 摘掉 cron 排期。 */
    public void pauseDataSource(String id) {
        DataSource ds = getDataSource(id);
        ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_PAUSED);
        try {
            dsRepo.update(ds);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to pause data source: {}", e.getMessage());
            throw e;
        }
        scheduler.remove(id);
        log.info("[datasource] data source paused: id={}", id);
        support.recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_PAUSED, "data_source", ds.getId(),
                AuditOutcome.SUCCESS,
                DataSourceSupport.mapOf("name", ds.getName(), "type", ds.getType()), null, false);
    }

    /** 对照 Go {@code ResumeDataSource}（L547-568）：置 active 并重新注册 cron。 */
    public void resumeDataSource(String id) {
        DataSource ds = getDataSource(id);
        ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
        try {
            dsRepo.update(ds);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to resume data source: {}", e.getMessage());
            throw e;
        }
        try {
            scheduler.addOrUpdate(ds);
        } catch (RuntimeException e) {
            log.warn("[datasource] failed to re-register cron for ds={}: {}", ds.getId(), e.getMessage());
        }
        log.info("[datasource] data source resumed: id={}", id);
        support.recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_RESUMED, "data_source", ds.getId(),
                AuditOutcome.SUCCESS,
                DataSourceSupport.mapOf("name", ds.getName(), "type", ds.getType()), null, false);
    }

    /** 对照 Go {@code GetSyncLogs}（L571-578）。 */
    public List<SyncLog> getSyncLogs(String dsId, int limit, int offset) {
        try {
            return syncLogRepo.findByDataSource(dsId, limit, offset);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to get sync logs: {}", e.getMessage());
            throw e;
        }
    }

    /** 对照 Go {@code GetSyncLog}（L581-587）：未命中抛 NotFound（handler 映射成 404）。 */
    public SyncLog getSyncLog(String syncLogId) {
        return syncLogRepo.findById(syncLogId);
    }

    // ── 同步执行：实现随协作者（DataSourceSyncExecutor） ──

    /** 同步任务入口（asynq handler 的等价物）：实现见 {@link DataSourceSyncExecutor}。 */
    @Override
    public void handle(DataSourceSyncPayload payload) {
        executor.handle(payload);
    }
}
