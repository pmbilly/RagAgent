package com.ragagent.datasource.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SyncItemError;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.domain.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 同步结果的落库与错误收口：把一次同步的计数、游标与错误样本写回 {@code SyncLog}，
 * 以及"全部条目都失败"的判定与各类错误样本的构造。
 *
 * <p>持有 {@link DataSourceService} 回引以访问其仓储与共享常/工具；本类不得独立实例化。</p>
 */
final class DataSourceSyncResultOps {

    private static final Logger log = LoggerFactory.getLogger(DataSourceSyncResultOps.class);

    private final DataSourceService service;

    DataSourceSyncResultOps(DataSourceService service) {
        this.service = service;
    }

    /**
     * 把一次运行的结果同时落到
     * sync_log 与 data_source 两侧，并按状态决定审计动作与结果。
     *
     * <p>状态机的三条分支：<b>failed</b> 时（若原本不是 paused）置 error；
     * 否则原状态是 paused 就保持 paused（手动跑完一次不该把暂停的源变成 active），
     * 其余置 active。</p>
     */
    void updateSyncRunResult(DataSource ds, SyncLog syncLog, SyncResult result,
                             com.fasterxml.jackson.databind.JsonNode resultJson,
                             String status, String errorMessage, boolean wasPaused,
                             DataSourceSupport.ActivityTask activityTask) {
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
            service.syncLogRepo.updateResult(syncLog);
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
            service.dsRepo.updateSyncState(ds);
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
        service.support.recordKbActivity(ds.getTenantId(), ds.getKnowledgeBaseId(), action,
                "data_source", ds.getId(), outcome,
                DataSourceSupport.mapOf("name", ds.getName(), "type", ds.getType(),
                        "sync_log_id", syncLog.getId(),
                        "total", result.getTotal(), "created", result.getCreated(),
                        "updated", result.getUpdated(), "deleted", result.getDeleted(),
                        "skipped", result.getSkipped(), "failed", result.getFailed()),
                activityTask, false);
    }

    /**
     * 判定"整批全失败"。
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

    static SyncItemError deletionFailedError(FetchedItem item) {
        SyncItemError e = new SyncItemError();
        e.setTitle(item.getTitle());
        e.setCode("deletion_failed");
        e.setMessage("Deletion failed; see server logs");
        return e;
    }

    /**
     * 记录错误样本，按 {@value #DataSourceService.MAX_SYNC_RESULT_ERRORS} 封顶。
     *
     * <p>理由：{@code errors} 会落 jsonb、并且出现在<b>每一次</b>
     * 同步日志列表响应里。一次失败几千份文档的同步若把错误全留下，就是多 MB 的行
     * 和多 MB 的响应体。准确的失败数在 {@code failed}（一个有界整数）。</p>
     */
    static void recordSyncError(SyncResult result, SyncItemError item) {
        List<SyncItemError> errors = result.getErrors();
        if (errors == null) {
            errors = new ArrayList<>();
            result.setErrors(errors);
        }
        if (errors.size() < DataSourceService.MAX_SYNC_RESULT_ERRORS) {
            errors.add(item);
        }
    }

    /**
     * 构造"抓取失败"错误样本。
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
}
