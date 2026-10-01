package com.ragagent.datasource.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.StreamHandler;
import com.ragagent.datasource.StreamingConnector;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceException;
import com.ragagent.datasource.domain.DataSourceSyncPayload;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SyncCursor;
import com.ragagent.datasource.domain.SyncItemError;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.domain.SyncResult;
import com.ragagent.knowledge.domain.KnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 同步执行器：{@code datasource:sync} 任务的真正实现——一次性抓取与流式抓取两条路径、
 * 取消/失败语义、分页检查点与游标续跑。
 *
 * <p>持有 {@link DataSourceService} 回引以访问其依赖与协作者；入口 {@link #handle} 由服务委托，
 * 本类不得独立实例化。</p>
 */
final class DataSourceSyncExecutor {

    private static final Logger log = LoggerFactory.getLogger(DataSourceSyncExecutor.class);

    private final DataSourceService service;

    DataSourceSyncExecutor(DataSourceService service) {
        this.service = service;
    }

    /**
     * 对照 Go {@code processSyncStreaming}（L1044-1122）：边抓边灌、按页落检查点。
     *
     * <p>租户与自动标签必须在<b>开始抓取之前</b>备好，因为流是"到一条灌一条"。
     * 中途抓取失败时进度已经落在 {@code ds.LastSyncCursor} 上，Asynq 的重试会从那里续跑
     * ——所以这里<b>不能</b>把 cursor 清掉。</p>
     */
    private void processSyncStreaming(StreamingConnector sc, DataSource ds, SyncLog syncLog,
                                      DataSourceConfig config, DataSourceSyncPayload payload,
                                      boolean wasPaused, DataSourceSupport.ActivityTask activityTask) {
        Tenant tenant = service.tenantService.getTenantById(ds.getTenantId());
        if (tenant == null) {
            log.error("[datasource] failed to get tenant info");
            service.resultOps.updateSyncRunResult(ds, syncLog, new SyncResult(), null,
                    DataSourceConstants.SYNC_LOG_STATUS_FAILED,
                    "Failed to get tenant info: tenant not found", wasPaused, activityTask);
            throw new DataSourceException("tenant not found");
        }

        List<String> autoTagIds = service.support.resolveAutoTagIds(ds);

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
            service.resultOps.updateSyncRunResult(ds, syncLog, new SyncResult(), null,
                    DataSourceConstants.SYNC_LOG_STATUS_FAILED,
                    "Invalid cursor: " + e.getMessage(), wasPaused, activityTask);
            throw e;
        }

        SyncResult result = new SyncResult();
        StreamSyncHandler handler = new StreamSyncHandler(service, ds, autoTagIds, result, syncLog);

        SyncCursor nextCursor = null;
        RuntimeException fetchErr = null;
        try {
            nextCursor = sc.fetchStream(config, startCursor, handler);
        } catch (RuntimeException e) {
            fetchErr = e;
        }

        if (fetchErr != null) {
            log.error("[datasource] streaming fetch failed: {}", fetchErr.getMessage());
            service.resultOps.updateSyncRunResult(ds, syncLog, result, result.toJSON(),
                    DataSourceConstants.SYNC_LOG_STATUS_FAILED,
                    "Fetch failed: " + fetchErr.getMessage(), wasPaused, activityTask);
            throw fetchErr;
        }

        com.fasterxml.jackson.databind.JsonNode resultJson = result.toJSON();
        String allFailed = DataSourceSyncResultOps.allFetchedItemsFailedError(result);
        if (allFailed != null) {
            log.error("[datasource] streaming sync failed while processing fetched items: {}", allFailed);
            service.resultOps.updateSyncRunResult(ds, syncLog, result, resultJson,
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
        service.resultOps.updateSyncRunResult(ds, syncLog, result, resultJson, status, errMsg, wasPaused, activityTask);
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
            svc.itemOps.applyFetchedItem(ds, item, tagIds, result, true);
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
    void handle(DataSourceSyncPayload payload) {
        // 对照 Go 的 payload.Initiator.Apply(ctx) + withKBActivityTask(ctx, taskID, trigger)。
        // ⚠️ asynq 的 GetTaskID 在进程内队列里拿不到 → task_id 缺席（已记入类注释）。
        DataSourceSupport.ActivityTask activityTask = new DataSourceSupport.ActivityTask("", payload.trigger());

        log.info("[datasource] processing data source sync: ds={} syncLog={}",
                payload.dataSourceId(), payload.syncLogId());

        DataSource ds;
        try {
            ds = service.getDataSource(payload.dataSourceId());
        } catch (RuntimeException e) {
            log.warn("[datasource] data source not found (likely deleted), cancelling sync: "
                    + "ds={} err={}", payload.dataSourceId(), e.getMessage());
            SyncLog syncLog = null;
            try {
                syncLog = service.syncLogRepo.findById(payload.syncLogId());
            } catch (RuntimeException ignored) {
                // 对照 Go 的 `if syncLog, slErr := ...; slErr == nil && syncLog != nil`
            }
            if (syncLog != null) {
                syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_CANCELED);
                syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
                syncLog.setErrorMessage("data source has been deleted");
                service.support.bestEffortUpdateLog(syncLog);
            }
            return;
        }

        SyncLog syncLog;
        try {
            syncLog = service.syncLogRepo.findById(payload.syncLogId());
        } catch (RuntimeException e) {
            log.error("[datasource] failed to get sync log: {}", e.getMessage());
            return;
        }

        KnowledgeBase kb = service.knowledge.findKnowledgeBase(ds.getKnowledgeBaseId());
        if (kb == null) {
            log.warn("[datasource] knowledge base not found (likely deleted), cancelling sync: "
                    + "kb={} ds={}", ds.getKnowledgeBaseId(), payload.dataSourceId());
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_CANCELED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage("knowledge base has been deleted");
            service.support.bestEffortUpdateLog(syncLog);
            return;
        }
        if (!Objects.equals(kb.getTenantId(), ds.getTenantId())) {
            // 对照 Go 的 access.WithKBTaskWrite 失败 → asynq.SkipRetry（不再重试）
            throw new DataSourceException(DataSourceService.ERR_KB_CHANGE_FORBIDDEN
                    + ": data source KB does not belong to its tenant");
        }

        boolean wasPaused = DataSourceConstants.DATA_SOURCE_STATUS_PAUSED.equals(ds.getStatus());

        Connector connector;
        try {
            connector = service.connectorRegistry.get(ds.getType());
        } catch (RuntimeException e) {
            log.error("[datasource] connector not found: type={}", ds.getType());
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage("Connector not found: " + ds.getType());
            service.support.bestEffortUpdateLog(syncLog);
            if (!wasPaused) {
                ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            }
            ds.setErrorMessage(syncLog.getErrorMessage());
            service.support.bestEffortUpdate(ds);
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
            service.support.bestEffortUpdateLog(syncLog);
            if (!wasPaused) {
                ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            }
            ds.setErrorMessage(syncLog.getErrorMessage());
            service.support.bestEffortUpdate(ds);
            return;
        }
        // 把 KB 的多模态/VLM 开关透给连接器——它据此决定要不要抽内嵌图片做 OCR。
        // **从不落库**（DataSourceConfig 上该字段是 @JsonIgnore）。
        config.setMultimodalEnabled(DataSourceSupport.isMultimodalEnabled(kb));

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
                    service.dsRepo.updateSyncState(ds);
                } catch (RuntimeException uerr) {
                    log.warn("[datasource] failed to persist sync cursor after fetch error: {}",
                            uerr.getMessage());
                }
            }
            log.error("[datasource] fetch operation failed: {}", fetchErr.getMessage());
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage("Fetch failed: " + fetchErr.getMessage());
            service.support.bestEffortUpdateLog(syncLog);
            if (!wasPaused) {
                ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            }
            ds.setErrorMessage(syncLog.getErrorMessage());
            service.support.bestEffortUpdate(ds);
            throw fetchErr;
        }

        SyncResult result = new SyncResult();
        result.setTotal(items == null ? 0 : items.size());

        Tenant tenant = service.tenantService.getTenantById(ds.getTenantId());
        if (tenant == null) {
            log.error("[datasource] failed to get tenant info");
            syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_FAILED);
            syncLog.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            syncLog.setErrorMessage("Failed to get tenant info: tenant not found");
            service.support.bestEffortUpdateLog(syncLog);
            if (!wasPaused) {
                ds.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ERROR);
            }
            ds.setErrorMessage(syncLog.getErrorMessage());
            service.support.bestEffortUpdate(ds);
            throw new DataSourceException("tenant not found");
        }
        // 对照 Go 的 ctx = WithValue(TenantInfoContextKey, tenant)：Java 侧tenant
        // 作为参数传给知识库写入路径（KnowledgeBridge 显式收租户），不放 ThreadLocal。

        List<String> autoTagIds = service.support.resolveAutoTagIds(ds);

        if (items != null) {
            for (FetchedItem item : items) {
                if (Thread.currentThread().isInterrupted()) {
                    break; // 对照 Go 的 ctx.Err() 中止
                }
                service.itemOps.applyFetchedItem(ds, item, autoTagIds, result, true);
            }
        }

        com.fasterxml.jackson.databind.JsonNode resultJson = result.toJSON();
        String allFailed = DataSourceSyncResultOps.allFetchedItemsFailedError(result);
        if (allFailed != null) {
            log.error("[datasource] data source sync failed while processing fetched items: {}", allFailed);
            service.resultOps.updateSyncRunResult(ds, syncLog, result, resultJson,
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
        service.resultOps.updateSyncRunResult(ds, syncLog, result, resultJson, syncStatus, syncErrorMessage,
                wasPaused, activityTask);

        log.info("[datasource] data source sync completed: ds={} created={} updated={} deleted={}",
                payload.dataSourceId(), syncLog.getItemsCreated(), syncLog.getItemsUpdated(),
                syncLog.getItemsDeleted());
    }
}
