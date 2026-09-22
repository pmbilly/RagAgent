package com.ragagent.datasource.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorRegistry;
import com.ragagent.datasource.DataSourceSyncHandler;
import com.ragagent.datasource.DataSourceSyncTaskQueue;
import com.ragagent.datasource.Scheduler;
import com.ragagent.datasource.StreamHandler;
import com.ragagent.datasource.StreamingConnector;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceException;
import com.ragagent.datasource.domain.DataSourceSyncPayload;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SubtreeChildIds;
import com.ragagent.datasource.domain.SyncCursor;
import com.ragagent.datasource.domain.SyncItemError;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.domain.SyncResult;
import com.ragagent.datasource.domain.TaskInitiator;
import com.ragagent.datasource.mapper.DataSourceRepository;
import com.ragagent.datasource.mapper.SyncLogRepository;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.service.KnowledgeService;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private final DataSourceRepository dsRepo;
    private final SyncLogRepository syncLogRepo;
    private final KnowledgeBridge knowledge;
    private final DataSourceSyncTaskQueue taskQueue;
    private final ConnectorRegistry connectorRegistry;
    private final Scheduler scheduler;
    private final TenantService tenantService;
    private final AuditLogService audit;
    private final AutoTagProvider autoTagProvider;

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
        requireOwnedKnowledgeBase(ds.getKnowledgeBaseId(), ds.getTenantId());
        connectorRegistry.get(ds.getType());

        DataSourceConfig cfg = ds.parseConfig();
        if (cfg != null) {
            cfg.stripNonSecretCredentials(ds.getType());
            ds.setConfig(cfg.toJSON());
        }
        validateDataSourceConfig(ds);

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
        recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_CREATED, "data_source", ds.getId(),
                AuditOutcome.SUCCESS, mapOf("name", ds.getName(), "type", ds.getType()),
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
            configActuallyChanged = !configDeepEquals(mergedCfg, existingParsedCfg);
        }
        boolean hasCreds = mergedCfg != null
                && mergedCfg.hasConfiguredCredentials(ds.getType());
        if (hasCreds && (!Objects.equals(ds.getType(), existing.getType()) || configActuallyChanged)) {
            validateDataSourceConfig(ds);
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
        recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_UPDATED, "data_source", ds.getId(),
                AuditOutcome.SUCCESS,
                mapOf("name", ds.getName(), "type", ds.getType(),
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

        validateDataSourceConfig(existing);
        dsRepo.update(existing);
        log.info("[datasource] DataSource credentials updated: id={}", id);
        recordKbActivity(existing.getTenantId(), existing.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_UPDATED, "data_source", existing.getId(),
                AuditOutcome.SUCCESS,
                mapOf("name", existing.getName(), "type", existing.getType(),
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
        recordKbActivity(existing.getTenantId(), existing.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_UPDATED, "data_source", existing.getId(),
                AuditOutcome.SUCCESS,
                mapOf("name", existing.getName(), "type", existing.getType(),
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
        recordKbActivity(existing.getTenantId(), existing.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_DELETED, "data_source", existing.getId(),
                AuditOutcome.SUCCESS,
                mapOf("name", existing.getName(), "type", existing.getType()),
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
        DataSourceConfig config = parseConfigOrInvalid(ds);
        try {
            connector.validate(config);
        } catch (RuntimeException e) {
            ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            ds.setErrorMessage(e.getMessage());
            bestEffortUpdate(ds);
            throw e;
        }
        if (DataSourceConstants.DATA_SOURCE_STATUS_ERROR.equals(ds.getStatus())) {
            ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
            ds.setErrorMessage("");
            bestEffortUpdate(ds);
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
        DataSourceConfig config = parseConfigOrInvalid(ds);
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
        DataSourceConfig config = parseConfigOrInvalid(ds);
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
        DataSourceSyncPayload payload = new DataSourceSyncPayload(
                taskInitiatorFromContext(), "manual", dsId, ds.getTenantId(),
                syncLog.getId(), false, 0);
        // 对照 Go 的 langfuse.InjectTracing(ctx, payload)：追踪未实现（no-op），
        // 载荷里的 lf_* 五个字段因此缺席——等价于 Go 未启用追踪时的形状。

        DataSourceSyncTaskQueue.Outcome outcome;
        try {
            outcome = taskQueue.enqueue(payload, taskId, Scheduler.MAX_RETRY, Scheduler.TASK_TIMEOUT);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to enqueue sync task: {}", e.getMessage());
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage(e.getMessage());
            bestEffortUpdateLog(syncLog);
            if (!DataSourceConstants.DATA_SOURCE_STATUS_PAUSED.equals(ds.getStatus())) {
                ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            }
            ds.setErrorMessage("Failed to enqueue sync: " + e.getMessage());
            bestEffortUpdate(ds);
            recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                    AuditAction.DATASOURCE_SYNC_FAILED, "data_source", ds.getId(),
                    AuditOutcome.FAILED,
                    mapOf("name", ds.getName(), "type", ds.getType(),
                            "sync_log_id", syncLog.getId(), "trigger", "manual"),
                    null, false);
            throw e;
        }

        log.info("[datasource] sync task enqueued: ds={} syncLog={}", dsId, syncLog.getId());
        recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_SYNC_STARTED, "data_source", ds.getId(),
                AuditOutcome.ACCEPTED,
                mapOf("name", ds.getName(), "type", ds.getType(),
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
        recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_PAUSED, "data_source", ds.getId(),
                AuditOutcome.SUCCESS,
                mapOf("name", ds.getName(), "type", ds.getType()), null, false);
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
        recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(),
                AuditAction.DATASOURCE_RESUMED, "data_source", ds.getId(),
                AuditOutcome.SUCCESS,
                mapOf("name", ds.getName(), "type", ds.getType()), null, false);
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

    // ══════════════════════════ 同步执行（asynq handler 的等价物） ══════════════════════════

    /**
     * 对照 Go {@code ProcessSync}（L590-804）：队列里那个 {@code datasource:sync} 任务的
     * 真正实现。
     *
     * <h2>取消语义</h2>
     * <p>Go 靠 {@code ctx} 取消让连接器停止抓取。Java 侧靠<b>线程中断</b>——
     * 队列在超时时 {@code Future.cancel(true)}，于是这里在循环与 {@link #applyFetchedItem}
     * 的边界上检查 {@link Thread#isInterrupted()}（对照 Go 的 {@code ctx.Err()}）。</p>
     *
     * <h2>失败路径全部只记日志、不上抛</h2>
     * <p>除了"抓取失败"与"全部条目都失败"这两条 Go 显式 {@code return err} 的路径，
     * 其余（数据源被删、同步日志缺失、知识库被删、租户查不到）都是<b>把日志标成
     * canceled/failed 后正常返回</b>——重试没有意义。这与 Go 逐条对应。</p>
     */
    @Override
    public void handle(DataSourceSyncPayload payload) {
        // 对照 Go 的 payload.Initiator.Apply(ctx) + withKBActivityTask(ctx, taskID, trigger)。
        // ⚠️ asynq 的 GetTaskID 在进程内队列里拿不到 → task_id 缺席（已记入类注释）。
        ActivityTask activityTask = new ActivityTask("", payload.trigger());

        log.info("[datasource] processing data source sync: ds={} syncLog={}",
                payload.dataSourceId(), payload.syncLogId());

        DataSource ds;
        try {
            ds = getDataSource(payload.dataSourceId());
        } catch (RuntimeException e) {
            log.warn("[datasource] data source not found (likely deleted), cancelling sync: "
                    + "ds={} err={}", payload.dataSourceId(), e.getMessage());
            SyncLog syncLog = null;
            try {
                syncLog = syncLogRepo.findById(payload.syncLogId());
            } catch (RuntimeException ignored) {
                // 对照 Go 的 `if syncLog, slErr := ...; slErr == nil && syncLog != nil`
            }
            if (syncLog != null) {
                syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_CANCELED);
                syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
                syncLog.setErrorMessage("data source has been deleted");
                bestEffortUpdateLog(syncLog);
            }
            return;
        }

        SyncLog syncLog;
        try {
            syncLog = syncLogRepo.findById(payload.syncLogId());
        } catch (RuntimeException e) {
            log.error("[datasource] failed to get sync log: {}", e.getMessage());
            return;
        }

        KnowledgeBase kb = knowledge.findKnowledgeBase(ds.getKnowledgeBaseId());
        if (kb == null) {
            log.warn("[datasource] knowledge base not found (likely deleted), cancelling sync: "
                    + "kb={} ds={}", ds.getKnowledgeBaseId(), payload.dataSourceId());
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_CANCELED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage("knowledge base has been deleted");
            bestEffortUpdateLog(syncLog);
            return;
        }
        if (!Objects.equals(kb.getTenantId(), ds.getTenantId())) {
            // 对照 Go 的 access.WithKBTaskWrite 失败 → asynq.SkipRetry（不再重试）
            throw new DataSourceException(ERR_KB_CHANGE_FORBIDDEN
                    + ": data source KB does not belong to its tenant");
        }

        boolean wasPaused = DataSourceConstants.DATA_SOURCE_STATUS_PAUSED.equals(ds.getStatus());

        Connector connector;
        try {
            connector = connectorRegistry.get(ds.getType());
        } catch (RuntimeException e) {
            log.error("[datasource] connector not found: type={}", ds.getType());
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage("Connector not found: " + ds.getType());
            bestEffortUpdateLog(syncLog);
            if (!wasPaused) {
                ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            }
            ds.setErrorMessage(syncLog.getErrorMessage());
            bestEffortUpdate(ds);
            throw e;
        }

        DataSourceConfig config;
        try {
            config = ds.parseConfig();
        } catch (RuntimeException e) {
            config = null;
        }
        if (config == null) {
            // ⚠️ 已知差异（路径实际不可达）：Go 在这一行之后**无条件**执行
            // `config.MultimodalEnabled = kb.IsMultimodalEnabled()`，config 为 nil 时它
            // 会 panic（被 asynq 的 Recover 接住 → 任务失败重试）。Java 侧把同一个
            // 情形折叠成普通失败分支，避免在虚拟线程里抛 NPE。
            // 该路径经 HTTP 不可达：CreateDataSource 会先把空配置交给连接器校验，
            // 而各连接器的 Validate 都拒绝 nil 配置（错误文案正是 "invalid configuration"）。
            log.error("[datasource] failed to parse config: config is empty");
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage("Invalid configuration: invalid configuration");
            bestEffortUpdateLog(syncLog);
            if (!wasPaused) {
                ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            }
            ds.setErrorMessage(syncLog.getErrorMessage());
            bestEffortUpdate(ds);
            return;
        }
        // 把 KB 的多模态/VLM 开关透给连接器——它据此决定要不要抽内嵌图片做 OCR。
        // **从不落库**（DataSourceConfig 上该字段是 @JsonIgnore）。
        config.setMultimodalEnabled(isMultimodalEnabled(kb));

        if (connector instanceof StreamingConnector sc) {
            processSyncStreaming(sc, ds, syncLog, config, payload, wasPaused, activityTask);
            return;
        }

        // ── 批量路径 ────────────────────────────────────────────────────────
        //
        // ⚠️ 与 Go 的一处结构性差异：Go 的抓取返回三元组 (items, cursor, err)，
        // 部分失败时三者同时有效，调用方先取结果再看错误类型。Java 的异常会中断返回，
        // 所以连接器把"仍然有效的结果"挂在异常上（RSS 的接缝：PartialFetchException
        // 实现 RssFetchState）。这里必须先 **catch (PartialFetch)**、从 state 里取回
        // items/cursor，再把 details 记成"部分同步"——顺序反了就会把一部分成功的
        // 运行记成整体失败，而 Go 那边是 partial。
        FetchOutcome fetched;
        if (payload.forceFull() || DataSourceConstants.SYNC_MODE_FULL.equals(ds.getSyncMode())) {
            fetched = fetch(connector, config, safeParseCursor(ds), true);
            log.info("[datasource] full sync fetched {} items",
                    fetched.items == null ? 0 : fetched.items.size());
        } else {
            fetched = fetch(connector, config, safeParseCursor(ds), false);
            log.info("[datasource] incremental sync fetched {} items",
                    fetched.items == null ? 0 : fetched.items.size());
        }

        List<FetchedItem> items = fetched.items;
        SyncCursor nextCursor = fetched.nextCursor;
        RuntimeException fetchErr = fetched.error;
        List<String> fetchWarnings = fetched.warnings;

        if (fetchErr != null) {
            // 抓取失败也要把 cursor 落下来：RSS 这类源短暂宕机后不该被迫全量重灌
            if (nextCursor != null) {
                ds.setLastSyncCursor(nextCursor.toJSON());
                try {
                    dsRepo.updateSyncState(ds);
                } catch (RuntimeException uerr) {
                    log.warn("[datasource] failed to persist sync cursor after fetch error: {}",
                            uerr.getMessage());
                }
            }
            log.error("[datasource] fetch operation failed: {}", fetchErr.getMessage());
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage("Fetch failed: " + fetchErr.getMessage());
            bestEffortUpdateLog(syncLog);
            if (!wasPaused) {
                ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            }
            ds.setErrorMessage(syncLog.getErrorMessage());
            bestEffortUpdate(ds);
            throw fetchErr;
        }

        SyncResult result = new SyncResult();
        result.setTotal(items == null ? 0 : items.size());

        Tenant tenant = tenantService.getTenantById(ds.getTenantId());
        if (tenant == null) {
            log.error("[datasource] failed to get tenant info");
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage("Failed to get tenant info: tenant not found");
            bestEffortUpdateLog(syncLog);
            if (!wasPaused) {
                ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            }
            ds.setErrorMessage(syncLog.getErrorMessage());
            bestEffortUpdate(ds);
            throw new DataSourceException("tenant not found");
        }
        // 对照 Go 的 ctx = WithValue(TenantInfoContextKey, tenant)：Java 侧tenant
        // 作为参数传给知识库写入路径（KnowledgeBridge 显式收租户），不放 ThreadLocal。

        List<String> autoTagIds = resolveAutoTagIds(ds);

        if (items != null) {
            for (FetchedItem item : items) {
                if (Thread.currentThread().isInterrupted()) {
                    break; // 对照 Go 的 ctx.Err() 中止
                }
                applyFetchedItem(ds, item, autoTagIds, result, true);
            }
        }

        com.fasterxml.jackson.databind.JsonNode resultJson = result.toJSON();
        String allFailed = allFetchedItemsFailedError(result);
        if (allFailed != null) {
            log.error("[datasource] data source sync failed while processing fetched items: {}", allFailed);
            updateSyncRunResult(ds, syncLog, result, resultJson,
                    DataSourceConstants.SYNC_LOG_STATUS_FAILED, allFailed, wasPaused, activityTask);
            throw new DataSourceException(allFailed);
        }

        if (nextCursor != null) {
            ds.setLastSyncCursor(nextCursor.toJSON());
        }
        ds.setLastSyncAt(OffsetDateTime.now(ZoneOffset.UTC));

        String syncStatus = DataSourceConstants.SYNC_LOG_STATUS_SUCCESS;
        String syncErrorMessage = "";
        if (!fetchWarnings.isEmpty()) {
            syncStatus = DataSourceConstants.SYNC_LOG_STATUS_PARTIAL;
            syncErrorMessage = "Some feeds failed: " + String.join("; ", fetchWarnings);
            List<SyncItemError> errors = result.getErrors() == null
                    ? new ArrayList<>() : new ArrayList<>(result.getErrors());
            for (String w : fetchWarnings) {
                SyncItemError e = new SyncItemError();
                e.setMessage(w);
                errors.add(e);
            }
            result.setErrors(errors);
            resultJson = result.toJSON();
        }
        if (result.getFailed() > 0) {
            syncStatus = DataSourceConstants.SYNC_LOG_STATUS_PARTIAL;
            if (!syncErrorMessage.isEmpty()) {
                syncErrorMessage += "; ";
            }
            syncErrorMessage += result.getFailed() + " document(s) failed to sync";
            if (result.getDeletionFailed() > 0) {
                syncErrorMessage += "; " + result.getDeletionFailed()
                        + " deletion failure(s) will only retry on the next full sync";
            }
        }
        updateSyncRunResult(ds, syncLog, result, resultJson, syncStatus, syncErrorMessage,
                wasPaused, activityTask);

        log.info("[datasource] data source sync completed: ds={} created={} updated={} deleted={}",
                payload.dataSourceId(), syncLog.getItemsCreated(), syncLog.getItemsUpdated(),
                syncLog.getItemsDeleted());
    }

    // ══════════════════════════ 同步执行：流式路径 ══════════════════════════

    /**
     * 对照 Go {@code processSyncStreaming}（L1044-1122）：边抓边灌、按页落检查点。
     *
     * <p>租户与自动标签必须在<b>开始抓取之前</b>备好，因为流是"到一条灌一条"。
     * 中途抓取失败时进度已经落在 {@code ds.LastSyncCursor} 上，Asynq 的重试会从那里续跑
     * ——所以这里<b>不能</b>把 cursor 清掉。</p>
     */
    private void processSyncStreaming(StreamingConnector sc, DataSource ds, SyncLog syncLog,
                                      DataSourceConfig config, DataSourceSyncPayload payload,
                                      boolean wasPaused, ActivityTask activityTask) {
        Tenant tenant = tenantService.getTenantById(ds.getTenantId());
        if (tenant == null) {
            log.error("[datasource] failed to get tenant info");
            updateSyncRunResult(ds, syncLog, new SyncResult(), null,
                    DataSourceConstants.SYNC_LOG_STATUS_FAILED,
                    "Failed to get tenant info: tenant not found", wasPaused, activityTask);
            throw new DataSourceException("tenant not found");
        }

        List<String> autoTagIds = resolveAutoTagIds(ds);

        boolean forceFull = payload.forceFull()
                || DataSourceConstants.SYNC_MODE_FULL.equals(ds.getSyncMode());
        // ⚠️ 已知差异：进程内队列拿不到 asynq.GetRetryCount → attempt 恒 0
        // （等价于"用户手动全量同步的第一次尝试"：丢掉 cursor、重抓全部）。
        int attempt = 0;
        SyncCursor startCursor;
        try {
            startCursor = streamStartCursor(ds, forceFull, attempt);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to parse sync cursor: {}", e.getMessage());
            updateSyncRunResult(ds, syncLog, new SyncResult(), null,
                    DataSourceConstants.SYNC_LOG_STATUS_FAILED,
                    "Invalid cursor: " + e.getMessage(), wasPaused, activityTask);
            throw e;
        }

        SyncResult result = new SyncResult();
        StreamSyncHandler handler = new StreamSyncHandler(this, ds, autoTagIds, result, syncLog);

        SyncCursor nextCursor = null;
        RuntimeException fetchErr = null;
        try {
            nextCursor = sc.fetchStream(config, startCursor, handler);
        } catch (RuntimeException e) {
            fetchErr = e;
        }

        if (fetchErr != null) {
            log.error("[datasource] streaming fetch failed: {}", fetchErr.getMessage());
            updateSyncRunResult(ds, syncLog, result, result.toJSON(),
                    DataSourceConstants.SYNC_LOG_STATUS_FAILED,
                    "Fetch failed: " + fetchErr.getMessage(), wasPaused, activityTask);
            throw fetchErr;
        }

        com.fasterxml.jackson.databind.JsonNode resultJson = result.toJSON();
        String allFailed = allFetchedItemsFailedError(result);
        if (allFailed != null) {
            log.error("[datasource] streaming sync failed while processing fetched items: {}", allFailed);
            updateSyncRunResult(ds, syncLog, result, resultJson,
                    DataSourceConstants.SYNC_LOG_STATUS_FAILED, allFailed, wasPaused, activityTask);
            throw new DataSourceException(allFailed);
        }

        if (nextCursor != null) {
            ds.setLastSyncCursor(nextCursor.toJSON());
        }
        ds.setLastSyncAt(OffsetDateTime.now(ZoneOffset.UTC));

        String status = DataSourceConstants.SYNC_LOG_STATUS_SUCCESS;
        String errMsg = "";
        if (result.getFailed() > 0) {
            status = DataSourceConstants.SYNC_LOG_STATUS_PARTIAL;
            errMsg = result.getFailed() + " document(s) failed to sync";
            if (result.getDeletionFailed() > 0) {
                errMsg += "; " + result.getDeletionFailed()
                        + " deletion failure(s) will only retry on the next full sync";
            }
        }
        updateSyncRunResult(ds, syncLog, result, resultJson, status, errMsg, wasPaused, activityTask);
        log.info("[datasource] streaming sync completed: ds={} created={} updated={} deleted={} "
                        + "skipped={} failed={}", payload.dataSourceId(), result.getCreated(),
                result.getUpdated(), result.getDeleted(), result.getSkipped(), result.getFailed());
    }

    /** 一次抓取的三样产出（对照 Go 的三返回值 {@code (items, cursor, err)}）。 */
    private record FetchOutcome(List<FetchedItem> items, SyncCursor nextCursor,
                                RuntimeException error, List<String> warnings) {
    }

    /**
     * 批量抓取，并把 Go 的三返回值语义在 Java 的异常模型里还原。
     *
     * <p>判定顺序<b>照抄 Go 调用点的两条 {@code catch}</b>：先
     * {@link ConnectorException.PartialFetch}（部分成功——结果在异常对象上，
     * 由 {@code RssFetchState} 带出来），再把 {@code RssFetchState} 当兜底
     * （全部 feed 失败——items 恒空，但 cursor 仍然值得落库）。
     * 剩下的一律算抓取失败。</p>
     */
    private static FetchOutcome fetch(Connector connector, DataSourceConfig config,
                                      SyncCursor cursor, boolean full) {
        List<String> warnings = new ArrayList<>();
        try {
            if (full) {
                List<FetchedItem> items = connector.fetchAll(config, config.getResourceIds());
                return new FetchOutcome(items, null, null, warnings);
            }
            Connector.FetchIncrementalResult r = connector.fetchIncremental(config, cursor);
            List<FetchedItem> items = r == null ? null : r.items();
            SyncCursor next = r == null ? null : r.cursor();
            return new FetchOutcome(items, next, null, warnings);
        } catch (ConnectorException.PartialFetch partial) {
            // 部分成功：Go 在这一支上把 fetchErr 清成 nil、details 记成 warning
            warnings.addAll(partial.getDetails());
            if (partial instanceof com.ragagent.datasource.connector.rss.RssFetchState state) {
                return new FetchOutcome(state.items(), state.cursor(), null, warnings);
            }
            return new FetchOutcome(null, null, null, warnings);
        } catch (RuntimeException e) {
            // 「全部失败」也是 RssFetchState（items 恒 null、cursor 可能有值）——
            // Go 在 err != nil 分支上照样先落 cursor 再记失败。
            SyncCursor next = e instanceof com.ragagent.datasource.connector.rss.RssFetchState state
                    ? state.cursor() : null;
            return new FetchOutcome(null, next, e, warnings);
        }
    }

    /**
     * 对照 Go {@code streamStartCursor}（L981-986）：决定流式抓取从哪个 cursor 续。
     *
     * <p>用户手动触发的全量同步在<b>第一次尝试</b>时丢掉 cursor（每条都重抓）；
     * 重试的全量同步与所有增量同步都从上次落下的检查点续跑——这样一次超时的运行
     * 会收敛，而不是每次重试都从头再来。</p>
     */
    static SyncCursor streamStartCursor(DataSource ds, boolean forceFull, int attempt) {
        if (forceFull && attempt == 0) {
            return null;
        }
        return safeParseCursor(ds);
    }

    /** 对照 Go 的 {@code cursor, _ := ds.ParseSyncCursor()}：解析失败当作"没有 cursor"。 */
    private static SyncCursor safeParseCursor(DataSource ds) {
        try {
            return ds.parseSyncCursor();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 对照 Go {@code streamSyncHandler}（L991-1039）：把流式抓取接到知识库灌入上。
     *
     * <p>{@code emit} 一条就灌一条（内存被限制在单条），{@code checkpoint} 在页边界
     * 把 cursor 与"到目前为止的计数"落库——UI 因此能看到一个长同步的中途进度，
     * 而不是从 0 直接跳到完成。</p>
     */
    private static final class StreamSyncHandler implements StreamHandler {

        private final DataSourceService svc;
        private final DataSource ds;
        private final List<String> tagIds;
        private final SyncResult result;
        private final SyncLog syncLog;

        StreamSyncHandler(DataSourceService svc, DataSource ds, List<String> tagIds,
                          SyncResult result, SyncLog syncLog) {
            this.svc = svc;
            this.ds = ds;
            this.tagIds = tagIds;
            this.result = result;
            this.syncLog = syncLog;
        }

        /** 对照 Go {@code Emit}：被中断即中止整条流；单条灌入失败<b>不</b>中止。 */
        @Override
        public void emit(FetchedItem item) {
            if (Thread.currentThread().isInterrupted()) {
                throw new ConnectorException("sync canceled");
            }
            result.setTotal(result.getTotal() + 1);
            svc.applyFetchedItem(ds, item, tagIds, result, true);
        }

        /** 对照 Go {@code Checkpoint}：cursor 落库 + 进度镜像进 sync_log（尽力而为）。 */
        @Override
        public void checkpoint(SyncCursor cursor) {
            if (cursor == null) {
                return;
            }
            ds.setLastSyncCursor(cursor.toJSON());
            svc.dsRepo.updateSyncState(ds);

            syncLog.setItemsTotal(result.getTotal());
            syncLog.setItemsCreated(result.getCreated());
            syncLog.setItemsUpdated(result.getUpdated());
            syncLog.setItemsDeleted(result.getDeleted());
            syncLog.setItemsSkipped(result.getSkipped());
            syncLog.setItemsFailed(result.getFailed());
            try {
                svc.syncLogRepo.updateResult(syncLog);
            } catch (RuntimeException e) {
                log.warn("[datasource] failed to persist sync log progress at checkpoint: {}",
                        e.getMessage());
            }
        }
    }

    // ══════════════════════════ 条目灌入 ══════════════════════════

    /**
     * 对照 Go {@code applyFetchedItem}（L860-974）：单条抓取结果的分类与灌入。
     *
     * <p>批量循环与流式处理器共用它，所以"删除 / 空内容 / 灌入结果"三类的判定
     * 在两条抓取路径上必然一致。</p>
     *
     * @param suppressed 对照 Go 的 {@code withKBActivitySuppressed(ctx)}——
     *                   同步期间的单条变更<b>不</b>进审计（一次同步能灌几千条，
     *                   只有本次运行的汇总事件该出现）。本实现里审计本就只在
     *                   汇总点发生，所以参数只用于表达调用意图。
     */
    void applyFetchedItem(DataSource ds, FetchedItem item, List<String> tagIDs,
                          SyncResult result, boolean suppressed) {
        if (item.isDeleted()) {
            if (!ds.isSyncDeletions()) {
                return; // 关闭了删除同步：既不计也不删
            }
            if (item.getExternalId() == null || item.getExternalId().isEmpty()) {
                log.warn("[datasource] skipping deletion for item \"{}\": empty external_id",
                        item.getTitle());
                result.setSkipped(result.getSkipped() + 1);
                return;
            }
            applyDeletion(ds, item, result);
            return;
        }

        if ((item.getContent() == null || item.getContent().length == 0)
                && (item.getUrl() == null || item.getUrl().isEmpty())) {
            Map<String, String> meta = item.getMetadata() == null ? Map.of() : item.getMetadata();
            String errMsg = meta.get("error");
            if (errMsg != null) {
                log.warn("[datasource] item \"{}\" (external_id={}) fetch failed: {}",
                        item.getTitle(), item.getExternalId(), errMsg);
                result.setFailed(result.getFailed() + 1);
                recordSyncError(result, fetchFailureSyncError(item, errMsg));
            } else {
                log.info("[datasource] skipping item \"{}\" (external_id={}): no content or URL",
                        item.getTitle(), item.getExternalId());
                result.setSkipped(result.getSkipped() + 1);
            }
            return;
        }

        boolean isUpdate;
        try {
            isUpdate = ingestItem(ds, item, tagIDs);
        } catch (KnowledgeService.DuplicateKnowledgeException dup) {
            // 重复的文件/URL 不算失败——计入 skipped
            log.info("[datasource] item \"{}\" (external_id={}) already exists, skipping",
                    item.getTitle(), item.getExternalId());
            result.setSkipped(result.getSkipped() + 1);
            return;
        } catch (RuntimeException err) {
            String embeddedImage = item.getMetadata() == null
                    ? null : item.getMetadata().get("embedded_image");
            if ("true".equals(embeddedImage)) {
                // 从文档里抽出来做 OCR 的图片是"尽力而为"的增强，不是文档本身：
                // 知识库灌不进去（没配 VLM/对象存储）时跳过即可，别让整次同步失败。
                log.info("[datasource] skipping embedded image \"{}\" (external_id={}), "
                                + "not ingested: {}", item.getTitle(), item.getExternalId(),
                        err.getMessage());
                result.setSkipped(result.getSkipped() + 1);
                return;
            }
            log.warn("[datasource] failed to ingest item \"{}\" (external_id={}): {}",
                    item.getTitle(), item.getExternalId(), err.getMessage());
            result.setFailed(result.getFailed() + 1);
            SyncItemError e = new SyncItemError();
            e.setTitle(item.getTitle());
            e.setCode("ingest_failed");
            e.setMessage("Ingest failed; see server logs");
            recordSyncError(result, e);
            return;
        }
        if (isUpdate) {
            result.setUpdated(result.getUpdated() + 1);
        } else {
            result.setCreated(result.getCreated() + 1);
        }
    }

    /** 对照 Go {@code applyFetchedItem} 的删除分支（L864-927）。 */
    private void applyDeletion(DataSource ds, FetchedItem item, SyncResult result) {
        Knowledge existing;
        try {
            existing = knowledge.findByDataSourceExternalId(
                    ds.getTenantId(), ds.getKnowledgeBaseId(), ds.getId(), item.getExternalId());
        } catch (RuntimeException e) {
            log.error("[datasource] failed to find deleted knowledge for external_id={} "
                            + "(ds={}, kb={}): {}", item.getExternalId(), ds.getId(),
                    ds.getKnowledgeBaseId(), e.getMessage());
            result.setFailed(result.getFailed() + 1);
            result.setDeletionFailed(result.getDeletionFailed() + 1);
            SyncItemError err = new SyncItemError();
            err.setTitle(item.getTitle());
            err.setCode("deletion_lookup_failed");
            err.setMessage("Failed to look up the item before deletion; see server logs");
            recordSyncError(result, err);
            return;
        }
        if (existing == null) {
            // 删除是幂等的：源端条目可能已经被手工删掉或上一轮同步删过了
            result.setSkipped(result.getSkipped() + 1);
            return;
        }
        try {
            knowledge.softDelete(ds.getTenantId(), existing.getId());
        } catch (RuntimeException deleteErr) {
            result.setFailed(result.getFailed() + 1);
            result.setDeletionFailed(result.getDeletionFailed() + 1);
            log.error("[datasource] failed to delete knowledge {} for external_id={} (ds={}): {}",
                    existing.getId(), item.getExternalId(), ds.getId(), deleteErr.getMessage());
            recordSyncError(result, deletionFailedError(item));
            return;
        }
        try {
            knowledge.hardDelete(ds.getTenantId(), existing.getId());
        } catch (RuntimeException herr) {
            result.setFailed(result.getFailed() + 1);
            result.setDeletionFailed(result.getDeletionFailed() + 1);
            log.error("[datasource] failed to hard-delete knowledge {} for external_id={} (ds={}): {}",
                    existing.getId(), item.getExternalId(), ds.getId(), herr.getMessage());
            recordSyncError(result, deletionFailedError(item));
            return;
        }
        result.setDeleted(result.getDeleted() + 1);
    }

    private static SyncItemError deletionFailedError(FetchedItem item) {
        SyncItemError e = new SyncItemError();
        e.setTitle(item.getTitle());
        e.setCode("deletion_failed");
        e.setMessage("Deletion failed; see server logs");
        return e;
    }

    /**
     * 对照 Go {@code recordSyncError}（L830-834）：错误样本按 {@value #MAX_SYNC_RESULT_ERRORS}
     * 封顶。
     *
     * <p>理由写在 Go 的注释里：{@code result.Errors} 会落 jsonb、并且出现在<b>每一次</b>
     * 同步日志列表响应里。一次失败几千份文档的同步若把错误全留下，就是多 MB 的行
     * 和多 MB 的响应体。准确的失败数在 {@code result.Failed}（一个有界整数）。</p>
     */
    static void recordSyncError(SyncResult result, SyncItemError item) {
        List<SyncItemError> errors = result.getErrors();
        if (errors == null) {
            errors = new ArrayList<>();
            result.setErrors(errors);
        }
        if (errors.size() < MAX_SYNC_RESULT_ERRORS) {
            errors.add(item);
        }
    }

    /**
     * 对照 Go {@code fetchFailureSyncError}（L842-854）。
     *
     * <p>会分类错误的连接器（飞书）经 metadata 给出稳定的 i18n 码 + 参数，
     * 让前端能本地化；<b>原始状态码/响应体/log_id 永远不出服务端日志</b>。
     * 不提供码的连接器保留原文当 fallback。</p>
     */
    static SyncItemError fetchFailureSyncError(FetchedItem item, String rawMsg) {
        SyncItemError e = new SyncItemError();
        e.setTitle(item.getTitle());
        Map<String, String> meta = item.getMetadata() == null ? Map.of() : item.getMetadata();
        String code = meta.get("error_reason_code");
        if (code != null && !code.isEmpty()) {
            e.setCode(code);
            String v = meta.get("error_reason_code_value");
            if (v != null && !v.isEmpty()) {
                e.setParams(Map.of("code", v));
            }
            e.setMessage(meta.get("error_reason"));
        } else {
            e.setMessage(rawMsg);
        }
        return e;
    }

    /**
     * 对照 Go {@code ingestItem}（L1243-1370）：把一条 {@link FetchedItem} 写进知识库。
     *
     * <p>有 external_id 时先删后建（update = delete + re-create）；有内容字节走
     * {@code createFromFile}，只有 URL 走 {@code createFromUrl} 并在<b>新建</b>分支上
     * 补 datasource metadata（重复分支复用已有行，不该被重新打标）。</p>
     *
     * @return true = 替换了一条已有条目
     */
    boolean ingestItem(DataSource ds, FetchedItem item, List<String> tagIDs) {
        // channel 决定界面上显示的来源标签：连接器给的 metadata.channel 优先
        // （飞书云盘把它设成 "feishu"，好和 wiki 共用"飞书"标签），否则回落到 ds.Type。
        String channel = ds.getType();
        Map<String, String> itemMeta = item.getMetadata() == null ? Map.of() : item.getMetadata();
        String mc = itemMeta.get("channel");
        if (mc != null && !mc.isEmpty()) {
            channel = mc;
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("external_id", item.getExternalId());
        metadata.put("source_resource_id", item.getSourceResourceId());
        metadata.put("datasource_id", ds.getId());
        // 源系统自己的最后修改时间：knowledge 行的 updated_at 每次重解析都会动，
        // 所以这是"这份文档本身有多旧"的唯一记录。
        if (!isGoZeroTime(item.getUpdatedAt())) {
            metadata.put("source_updated_at", item.getUpdatedAt().toInstant()
                    .atOffset(ZoneOffset.UTC).format(RFC3339));
        }
        if (!isGoZeroTime(item.getCreatedAt())) {
            metadata.put("source_created_at", item.getCreatedAt().toInstant()
                    .atOffset(ZoneOffset.UTC).format(RFC3339));
        }
        metadata.putAll(itemMeta);

        boolean isUpdate = false;
        if (item.getExternalId() != null && !item.getExternalId().isEmpty()) {
            Knowledge existing;
            try {
                existing = knowledge.findByDataSourceExternalId(
                        ds.getTenantId(), ds.getKnowledgeBaseId(), ds.getId(), item.getExternalId());
            } catch (RuntimeException e) {
                log.warn("[datasource] failed to check existing knowledge for external_id={}: {}",
                        item.getExternalId(), e.getMessage());
                existing = null; // 非致命：继续新建（可能产生重复）
            }
            if (existing != null) {
                log.info("[datasource] found existing knowledge {} for external_id={}, "
                        + "deleting for update", existing.getId(), item.getExternalId());
                try {
                    knowledge.softDelete(ds.getTenantId(), existing.getId());
                    try {
                        knowledge.hardDelete(ds.getTenantId(), existing.getId());
                    } catch (RuntimeException herr) {
                        log.warn("[datasource] failed to hard-delete replaced knowledge {}: {}",
                                existing.getId(), herr.getMessage());
                    }
                    isUpdate = true;
                } catch (RuntimeException e) {
                    log.warn("[datasource] failed to delete existing knowledge {}: {}",
                            existing.getId(), e.getMessage());
                }
            }
        }

        if (item.getContent() != null && item.getContent().length > 0) {
            try {
                knowledge.createFromFile(ds.getTenantId(), ds.getKnowledgeBaseId(),
                        item.getContent(), item.getFileName(), metadata, tagIDs, channel);
            } catch (KnowledgeService.DuplicateKnowledgeException dup) {
                if (dupIsSameNode(dup, item)) {
                    sweepStaleSubtree(ds, item);
                }
                throw dup;
            }
            sweepStaleSubtree(ds, item);
            return isUpdate;
        }

        if (item.getUrl() != null && !item.getUrl().isEmpty()) {
            Knowledge created;
            try {
                created = knowledge.createFromUrl(ds.getTenantId(), ds.getKnowledgeBaseId(),
                        item.getUrl(), item.getFileName(), item.getTitle(), tagIDs, channel);
            } catch (KnowledgeService.DuplicateKnowledgeException dup) {
                if (dupIsSameNode(dup, item)) {
                    sweepStaleSubtree(ds, item);
                }
                throw dup;
            }
            // URL 建出来的行没有 metadata，之后的删除就永远找不到它。
            // 只在**新建**分支上补（重复分支复用已有行）。
            if (created != null) {
                knowledge.attachMetadata(created, metadata);
            }
            sweepStaleSubtree(ds, item);
            return isUpdate;
        }

        throw new DataSourceException("item has neither content nor URL");
    }

    /**
     * 对照 Go {@code dupIsSameNode}（L1382-1385）：重复内容命中的是不是<b>这个节点自己</b>
     * 的行（external_id 相同）。
     *
     * <p>文件去重只看 file_hash + file_type，所以"某节点重建后的正文恰好与<b>另一条</b>
     * 知识哈希相同"是可能的——那种情况下这条节点的父行刚被删、还没重建，
     * 若照旧清扫子树，就会把子项删掉却没有父行来替换。</p>
     */
    static boolean dupIsSameNode(KnowledgeService.DuplicateKnowledgeException dup, FetchedItem item) {
        if (dup == null || dup.existing() == null) {
            return false;
        }
        String externalId = readMetadataValue(dup.existing(), "external_id");
        return Objects.equals(externalId, item.getExternalId());
    }

    /**
     * 对照 Go {@code sweepStaleSubtree}（L1399-1444）：删掉源端已经消失的子项。
     *
     * <p>只在父项<b>确实存在于知识库之后</b>才跑（刚重建成功、或经重复哈希确认还在），
     * 这样一次真正失败的父写入绝不会毁掉已有的子项。仍在源端的子项由
     * {@code SubtreeKeep} 保住——即便这一轮没能重新灌入（例如附件下载瞬时失败），
     * 它此前同步好的副本也不会丢。</p>
     */
    void sweepStaleSubtree(DataSource ds, FetchedItem item) {
        if (!item.isReplacesSubtree() || item.getExternalId() == null || item.getExternalId().isEmpty()) {
            return;
        }
        List<Knowledge> children;
        try {
            children = knowledge.findByMetadataKeyPrefix(ds.getTenantId(), ds.getKnowledgeBaseId(),
                    "external_id", SubtreeChildIds.subtreeChildPrefix(item.getExternalId()));
        } catch (RuntimeException e) {
            log.warn("[datasource] failed to list subtree of external_id={}: {}",
                    item.getExternalId(), e.getMessage());
            return;
        }
        if (children == null || children.isEmpty()) {
            return;
        }
        List<String> subtreeKeep = item.getSubtreeKeep() == null ? List.of() : item.getSubtreeKeep();
        List<String> ids = new ArrayList<>();
        for (Knowledge child : children) {
            // 限定本次数据源：同一个知识库里另一个连接器的同前缀 external_id 不该被清扫
            if (!Objects.equals(readMetadataValue(child, "datasource_id"), ds.getId())) {
                continue;
            }
            if (subtreeKeep.contains(readMetadataValue(child, "external_id"))) {
                continue;
            }
            ids.add(child.getId());
        }
        if (ids.isEmpty()) {
            return;
        }
        // 批量删：一个节点的附件集从 N 缩到 M 时只付一轮删除扇出，而不是 N 轮
        try {
            knowledge.softDeleteList(ds.getTenantId(), ids);
        } catch (RuntimeException derr) {
            log.warn("[datasource] failed to delete {} stale sub-item(s) of external_id={}: {}",
                    ids.size(), item.getExternalId(), derr.getMessage());
            return;
        }
        try {
            knowledge.hardDeleteList(ds.getTenantId(), ids);
        } catch (RuntimeException herr) {
            log.warn("[datasource] failed to hard-delete {} stale sub-item(s) of external_id={}: {}",
                    ids.size(), item.getExternalId(), herr.getMessage());
        }
    }

    // ══════════════════════════ 结果落库 ══════════════════════════

    /**
     * 对照 Go {@code updateSyncRunResult}（L1124-1177）：把一次运行的结果同时落到
     * sync_log 与 data_source 两侧，并按状态决定审计动作与结果。
     *
     * <p>状态机的三条分支逐条照抄：<b>failed</b> 时（若原本不是 paused）置 error；
     * 否则原状态是 paused 就保持 paused（手动跑完一次不该把暂停的源变成 active），
     * 其余置 active。</p>
     */
    void updateSyncRunResult(DataSource ds, SyncLog syncLog, SyncResult result,
                             com.fasterxml.jackson.databind.JsonNode resultJson,
                             String status, String errorMessage, boolean wasPaused,
                             ActivityTask activityTask) {
        syncLog.setItemsTotal(result.getTotal());
        syncLog.setItemsCreated(result.getCreated());
        syncLog.setItemsUpdated(result.getUpdated());
        syncLog.setItemsDeleted(result.getDeleted());
        syncLog.setItemsSkipped(result.getSkipped());
        syncLog.setItemsFailed(result.getFailed());
        syncLog.setStatus(status);
        syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        syncLog.setErrorMessage(errorMessage);
        syncLog.setResult(resultJson);
        try {
            syncLogRepo.updateResult(syncLog);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to update sync log: {}", e.getMessage());
        }

        if (DataSourceConstants.SYNC_LOG_STATUS_FAILED.equals(status)) {
            if (!wasPaused) {
                ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            }
        } else if (wasPaused) {
            ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_PAUSED);
        } else {
            ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
        }
        ds.setErrorMessage(errorMessage);
        ds.setLastSyncResult(resultJson);
        try {
            dsRepo.updateSyncState(ds);
        } catch (RuntimeException e) {
            log.error("[datasource] failed to update data source: {}", e.getMessage());
        }

        String action = AuditAction.DATASOURCE_SYNC_COMPLETED;
        String outcome = AuditOutcome.SUCCESS;
        if (DataSourceConstants.SYNC_LOG_STATUS_FAILED.equals(status)) {
            action = AuditAction.DATASOURCE_SYNC_FAILED;
            outcome = AuditOutcome.FAILED;
        } else if (DataSourceConstants.SYNC_LOG_STATUS_PARTIAL.equals(status)) {
            outcome = AuditOutcome.PARTIAL;
        }
        recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(), action,
                "data_source", ds.getId(), outcome,
                mapOf("name", ds.getName(), "type", ds.getType(),
                        "sync_log_id", syncLog.getId(),
                        "total", result.getTotal(), "created", result.getCreated(),
                        "updated", result.getUpdated(), "deleted", result.getDeleted(),
                        "skipped", result.getSkipped(), "failed", result.getFailed()),
                activityTask, false);
    }

    /**
     * 对照 Go {@code allFetchedItemsFailedError}（L1179-1200）。
     *
     * <p>只有"抓到了东西、而且<b>每一件</b>都失败、且没有任何成功计数"才算整体失败
     * ——这样一个"源里全是被删的条目"的运行不会被误判成故障。
     * 详情取第一条错误样本，超过 500 字节截断。</p>
     *
     * @return 非 null = 整体失败的文案
     */
    static String allFetchedItemsFailedError(SyncResult result) {
        if (result == null || result.getTotal() == 0) {
            return null;
        }
        if (result.getFailed() != result.getTotal() || result.getCreated() != 0
                || result.getUpdated() != 0 || result.getDeleted() != 0 || result.getSkipped() != 0) {
            return null;
        }
        String detail = "";
        List<SyncItemError> errors = result.getErrors();
        if (errors != null && !errors.isEmpty()) {
            detail = errors.get(0).display();
            if (detail == null) {
                detail = "";
            }
            if (detail.length() > 500) {
                detail = detail.substring(0, 500) + "...";
            }
        }
        if (detail.isEmpty()) {
            return "all fetched items failed during sync ("
                    + result.getFailed() + "/" + result.getTotal() + ")";
        }
        return "all fetched items failed during sync ("
                + result.getFailed() + "/" + result.getTotal() + "): " + detail;
    }

    // ══════════════════════════ 内部工具 ══════════════════════════

    /**
     * 对照 Go {@code resolveAutoTagIDs}（L809-818）：找/建本次数据源的自动标签。
     *
     * <p><b>标签失败不致命</b>：同步照常进行、条目只是没有标签。</p>
     */
    List<String> resolveAutoTagIds(DataSource ds) {
        List<String> autoTagIds = new ArrayList<>();
        try {
            String tagId = autoTagProvider.findOrCreateTagId(ds.getKnowledgeBaseId(), ds.getName());
            if (tagId != null) {
                autoTagIds.add(tagId);
                log.info("[datasource] using auto-tag \"{}\" (id={}) for data source sync",
                        ds.getName(), tagId);
            }
        } catch (RuntimeException e) {
            log.warn("[datasource] failed to find/create auto-tag \"{}\": {} "
                    + "(proceeding without tag)", ds.getName(), e.getMessage());
        }
        return autoTagIds;
    }

    /**
     * 对照 Go {@code validateDataSourceConfig}（L1221-1233）：解析配置后交给连接器真连一次。
     *
     * <p>{@code ParseConfig} 失败一律折叠成 {@code ErrInvalidConfig}
     * （{@value #ERR_INVALID_CONFIG}）——把 JSON 解析器的原文漏给用户是没有意义的。</p>
     */
    private void validateDataSourceConfig(DataSource ds) {
        Connector connector = connectorRegistry.get(ds.getType());
        DataSourceConfig config = parseConfigOrInvalid(ds);
        // ⚠️ 这里**允许** config 为 null 并原样传给连接器——Go 的
        // `config, err := ds.ParseConfig(); if err != nil {...}; return connector.Validate(ctx, config)`
        // 对"空 config"（ParseConfig 回 nil, nil）是把 nil 递下去的，各连接器自己拒绝。
        // 把 null 提前折叠成 InvalidConfig 会改变**哪个**错误被暴露出来。
        connector.validate(config);
    }

    /**
     * 对照 Go 的 {@code config, err := ds.ParseConfig(); if err != nil { return ErrInvalidConfig }}：
     * <b>只有解析抛错</b>才折叠成 {@code invalid configuration}；空 config 回 {@code null}
     * 并继续往下传（调用方自己决定怎么处理 null）。
     */
    private static DataSourceConfig parseConfigOrInvalid(DataSource ds) {
        try {
            return ds.parseConfig();
        } catch (RuntimeException e) {
            throw new ConnectorException.InvalidConfig();
        }
    }

    /**
     * 对照 Go {@code CreateDataSource} 里的两步知识库校验（L74-80）：
     * 找不到 → {@code knowledge base not found}；租户不符 → <b>同一个</b>错误
     * （不泄漏"这个 id 确实存在，只是不属于你"）。
     */
    private KnowledgeBase requireOwnedKnowledgeBase(String kbId, Long tenantId) {
        KnowledgeBase kb = knowledge.findKnowledgeBase(kbId);
        if (kb == null) {
            throw new DataSourceException(ERR_KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (!Objects.equals(kb.getTenantId(), tenantId)) {
            throw new DataSourceException(ERR_KNOWLEDGE_BASE_NOT_FOUND);
        }
        return kb;
    }

    /**
     * 对照 Go 的 {@code reflect.DeepEqual(*mergedCfg, *existingParsedCfg)}：
     * 把两个配置折成规范化 JSON 树再比。
     *
     * <p>字段集与 Go 的结构体逐字对应：{@code type} / {@code credentials} /
     * {@code resource_ids} / {@code settings}。<b>不含</b> {@code multimodal_enabled}
     * ——它在本方法被调用时两侧都还是零值（{@code @JsonIgnore}、从不落库、
     * 只在同步抓取前临时填）。</p>
     */
    private static boolean configDeepEquals(DataSourceConfig a, DataSourceConfig b) {
        return Objects.equals(configTree(a), configTree(b));
    }

    private static com.fasterxml.jackson.databind.JsonNode configTree(DataSourceConfig cfg) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", cfg.getType());
        node.set("credentials", MAPPER.valueToTree(cfg.getCredentials()));
        node.set("resource_ids", MAPPER.valueToTree(cfg.getResourceIds()));
        node.set("settings", MAPPER.valueToTree(cfg.getSettings()));
        return node;
    }

    /** 对照 Go 的 {@code kb.IsMultimodalEnabled()}：缺失时等价于 false。 */
    private static boolean isMultimodalEnabled(KnowledgeBase kb) {
        try {
            java.lang.reflect.Method m = kb.getClass().getMethod("isMultimodalEnabled");
            Object v = m.invoke(kb);
            return v instanceof Boolean b && b;
        } catch (ReflectiveOperationException e) {
            // KB 的 VLM 配置在阶段 3 未落地 → 等价于"没开多模态"（连接器因此不抽图片）
            return false;
        }
    }

    /** 对照 Go {@code TaskInitiatorFromContext}：合成用户（API-Key 主体）刻意留空。 */
    private static TaskInitiator taskInitiatorFromContext() {
        String userId = TenantContext.currentUserId();
        if (userId == null || userId.isEmpty() || isSyntheticUserId(userId)) {
            return TaskInitiator.empty();
        }
        String role = TenantContext.currentRole();
        return new TaskInitiator(userId, role == null ? "" : role);
    }

    /** 对照 Go {@code IsSyntheticUserID}：{@code "system-"} + 全数字。 */
    static boolean isSyntheticUserId(String id) {
        String prefix = "system-";
        if (id == null || id.length() <= prefix.length() || !id.startsWith(prefix)) {
            return false;
        }
        for (int i = prefix.length(); i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Go 的 {@code t.IsZero()}（本模块只用来判"连接器有没有给时间"）。 */
    private static boolean isGoZeroTime(OffsetDateTime t) {
        return t == null || com.ragagent.common.web.GoTimeSerializer.isGoZero(t);
    }

    private static String readMetadataValue(Knowledge k, String key) {
        com.fasterxml.jackson.databind.JsonNode md = k.getMetadata();
        if (md == null || !md.isObject()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode v = md.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private void bestEffortUpdate(DataSource ds) {
        bestEffort(() -> dsRepo.update(ds));
    }

    /** 对照 Go 的 {@code _ = s.syncLogRepo.Update(ctx, syncLog)}。 */
    private void bestEffortUpdateLog(SyncLog syncLog) {
        bestEffort(() -> syncLogRepo.update(syncLog));
    }

    private static void bestEffort(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 对照 Go 的 `_ = s.xxxRepo.Update(...)`：写日志失败不改变主流程
        }
    }

    /** 构造一个"按字母序"的 details（Go 的 map → encoding/json 恒排键）。 */
    private static Map<String, Object> mapOf(Object... kv) {
        TreeMap<String, Object> m = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 一次同步运行在审计详情里的关联字段（对照 Go 的 {@code withKBActivityTask}）。 */
    record ActivityTask(String taskId, String trigger) {
    }

    /**
     * 对照 Go {@code kb_activity.go} 的 {@code recordKBActivity}。
     *
     * <p>本模块只用到它的"汇总事件"形态——同步期间的单条变更一律被
     * {@code withKBActivitySuppressed} 压掉，所以这里没有 suppressed 参数的用武之地，
     * 由调用点自己保证只发汇总。</p>
     *
     * <p>details 的键序：Go 是 {@code map[string]any} → {@code encoding/json} 按字母序输出，
     * 所以这里用 {@link TreeMap} 构造（与 {@code WikiActivityAuditRecorder} 同款处置）。</p>
     */
    void recordKbActivity(long tenantId, String kbId, String action, String targetType,
                          String targetId, String outcome, Map<String, Object> details,
                          ActivityTask task, boolean suppressed) {
        if (suppressed) {
            return;
        }
        if (kbId == null || kbId.isEmpty() || action == null || action.isEmpty()) {
            return;
        }
        long tid = tenantId;
        if (tid == 0) {
            Long ctx = TenantContext.currentTenantId();
            tid = ctx == null ? 0L : ctx;
        }
        if (tid == 0) {
            return;
        }
        String effOutcome = outcome == null || outcome.isEmpty() ? AuditOutcome.SUCCESS : outcome;

        Map<String, Object> activityDetails = new TreeMap<>();
        if (details != null) {
            activityDetails.putAll(details);
        }
        if (task != null) {
            if (task.taskId() != null && !task.taskId().isEmpty()
                    && !activityDetails.containsKey("task_id")) {
                activityDetails.put("task_id", task.taskId());
            }
            if (task.trigger() != null && !task.trigger().isEmpty()
                    && !activityDetails.containsKey("trigger")) {
                activityDetails.put("trigger", task.trigger());
            }
            if (!activityDetails.containsKey("processing_status")) {
                switch (effOutcome) {
                    case AuditOutcome.ACCEPTED -> activityDetails.put("processing_status", "pending");
                    case AuditOutcome.SUCCESS -> activityDetails.put("processing_status", "completed");
                    case AuditOutcome.PARTIAL -> activityDetails.put("processing_status", "partial");
                    case AuditOutcome.FAILED, AuditOutcome.DENIED ->
                            activityDetails.put("processing_status", "failed");
                    case AuditOutcome.CANCELED ->
                            activityDetails.put("processing_status", "canceled");
                    default -> { }
                }
            }
        }

        com.fasterxml.jackson.databind.JsonNode detailsNode =
                activityDetails.isEmpty() ? null : MAPPER.valueToTree(activityDetails);

        String actorId = nullToEmpty(TenantContext.currentUserId());
        String actorRole = actorId.isEmpty() ? "" : nullToEmpty(TenantContext.currentRole());

        AuditLog entry = new AuditLog();
        entry.setTenantId(tid);
        entry.setActorUserId(actorId);
        entry.setActorRole(actorRole);
        entry.setAction(action);
        entry.setScopeType("knowledge_base");
        entry.setScopeId(kbId);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setOutcome(effOutcome);
        entry.setDetails(detailsNode);
        audit.logBestEffort(entry);
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
