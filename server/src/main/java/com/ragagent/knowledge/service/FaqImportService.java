package com.ragagent.knowledge.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.faq.FaqFailedEntry;
import com.ragagent.knowledge.dto.faq.FaqImportProgress;
import com.ragagent.knowledge.dto.faq.FaqImportResult;
import com.ragagent.knowledge.dto.faq.FaqSuccessEntry;
import com.ragagent.model.domain.Model;
import com.ragagent.knowledge.repository.FaqChunkRepository;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.mapper.KnowledgeTagMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ragagent.knowledge.task.FaqImportTaskStore;
import com.ragagent.knowledge.task.KnowledgeTaskExecutor;
import com.ragagent.knowledge.task.KnowledgeTaskIdCodec;
import com.ragagent.knowledge.storage.LocalStorageService;
import com.ragagent.knowledge.storage.TenantFileStorage;
import com.ragagent.knowledge.security.FaqGuard;
import com.ragagent.knowledge.dto.faq.FaqEntryPayload;
import com.ragagent.knowledge.dto.faq.FaqBatchUpsertPayload;
import com.ragagent.knowledge.dto.faq.FaqEntryFieldsBatchUpdate;
import com.ragagent.knowledge.dto.faq.FaqEntryFieldsUpdate;
import com.fasterxml.jackson.core.JacksonException;

/**
 * FAQ 条目批量导入（upsert）与进度面：append/replace 两种模式的 dry-run 校验、
 * 分批执行与向量索引、失败明细 CSV、导入结果落库与展示状态更新。
 * <p>执行模型：受理即返回 taskId，导入在虚拟线程内异步推进，进度经
 * {@link FaqImportTaskStore} 查询；失败即终态（不重试）。dry-run 只校验不落库。</p>
 */
@Service
public class FaqImportService {

    // 规模例外（>800 行）：导入是单一状态机（append/replace 校验 → 批次执行 → 终态落库），
    // 方法间共享导入进度对象的密集读写，再拆会制造 progress 参数的跨类传递。

    private static final Logger log = LoggerFactory.getLogger(FaqImportService.class);

    final FaqChunkRepository faqChunkRepository;
    final FaqValidateOps validateOps;
    final FaqBatchOps batchOps;
    final KnowledgeMapper knowledgeMapper;
    final KnowledgeTagMapper tagMapper;
    final FaqImportTaskStore taskStore;
    final LocalStorageService storage;
    final TenantFileStorage fileStorage;
    final FaqGuard faqGuard;
    final FaqChunkCodec faqChunkCodec;
    final FaqIndexWriter faqIndexWriter;
    /** 后台任务执行器（统一命名与关停）。 */
    private final KnowledgeTaskExecutor taskExecutor;


    public FaqImportService(KnowledgeTaskExecutor taskExecutor,
                            KnowledgeMapper knowledgeMapper,
                            KnowledgeTagMapper tagMapper,
                            FaqImportTaskStore taskStore,
                            LocalStorageService storage,
                            TenantFileStorage fileStorage,
                            FaqGuard faqGuard,
                            FaqChunkCodec faqChunkCodec,
                            FaqIndexWriter faqIndexWriter,
                            FaqChunkRepository faqChunkRepository) {

        this.taskExecutor = taskExecutor;
        this.validateOps = new FaqValidateOps(this);
        this.batchOps = new FaqBatchOps(this);
        this.faqChunkRepository = faqChunkRepository;
        this.knowledgeMapper = knowledgeMapper;
        this.tagMapper = tagMapper;
        this.taskStore = taskStore;
        this.storage = storage;
        this.fileStorage = fileStorage;
        this.faqGuard = faqGuard;
        this.faqChunkCodec = faqChunkCodec;
        this.faqIndexWriter = faqIndexWriter;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    // ══════════════════ 导入（Upsert）与进度 ═══════════════════════════

    /**
     * binding 校验
     * （entries required / mode oneof）在 controller；这里的判定顺序：
     * 空条目 → writable → tag scope → task_id 合法性 → running 锁 → 容器 →
     * 进度初始化 → 入队。
     */
    public String upsertEntries(String kbId, FaqBatchUpsertPayload payload) {
        if (payload == null || payload.entries() == null || payload.entries().isEmpty()) {
            throw new BizException(AppError.badRequest("FAQ 条目不能为空"));
        }
        final String mode = payload.mode() == null || payload.mode().isEmpty()
                ? "append" : payload.mode();
        if (!"append".equals(mode) && !"replace".equals(mode)) {
            throw new BizException(AppError.badRequest("模式仅支持 append 或 replace"));
        }

        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        validateFAQImportTags(kb, payload.entries());
        long tid = tenantId();

        String taskId = payload.taskId() == null ? "" : payload.taskId().trim();
        final String effectiveTaskId;
        if (taskId.isEmpty()) {
            effectiveTaskId = KnowledgeTaskIdCodec.generateTaskId("faq_import", tid, kbId);
        } else if (!validateTaskId(taskId)) {
            throw new BizException(AppError.badRequest("task_id 格式不合法"));
        } else {
            effectiveTaskId = taskId;
        }

        String runningTaskId = taskStore.getRunningTaskId(kbId);
        if (runningTaskId != null && !runningTaskId.isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "该知识库已有导入任务正在进行中（任务ID: " + runningTaskId + "），请等待完成后再试"));
        }

        Knowledge faqKnowledge = faqIndexWriter.ensureFAQKnowledge(tid, kb);
        if (faqKnowledge == null) {
            throw new IllegalStateException("failed to ensure FAQ knowledge: knowledge not found");
        }

        long enqueuedAt = Instant.now().getEpochSecond();
        String instanceId = UUID.randomUUID().toString();
        taskStore.setRunningInfo(kbId, new FaqImportTaskStore.RunningInfo(effectiveTaskId, enqueuedAt, instanceId));

        FaqImportProgress progress = new FaqImportProgress(
                effectiveTaskId, kbId, faqKnowledge.getId(), "pending", 0,
                payload.entries().size(), 0, 0, 0, 0, 0,
                new ArrayList<>(), null, null, null, null, 0, 0, null,
                "任务已创建，等待处理", "", Instant.now().getEpochSecond(),
                Instant.now().getEpochSecond(), payload.dryRun(),
                null, null, null, 0);
        taskStore.saveProgress(progress);

        log.info("FAQ import task initialized: {}, kb={}, total={}, dry_run={}",
                taskId, kbId, payload.entries().size(), payload.dryRun());

        // 受理后在虚拟线程内执行；entries 复制成可变列表（校验阶段会就地改写）
        List<FaqEntryPayload> entries = new ArrayList<>(payload.entries());
        taskExecutor.submit("faq-import", () -> processImport(new ImportJob(
                tid, effectiveTaskId, kbId, faqKnowledge.getId(), mode, payload.dryRun(),
                enqueuedAt, instanceId, entries)));

        if (!payload.dryRun()) {
            log.info("FAQ import started: task={}, kb={}, mode={}, total={}",
                    effectiveTaskId, kbId, mode, entries.size());
        }
        return effectiveTaskId;
    }

    /**
     * 异步语义：
     * 无 retry/backoff 中间态——任何失败直接落 failed 终态（搜索与移动/复制批同款取舍）。
     * dry_run 只做验证（无 embedding 依赖，确定性）；导入模式在
     */
    void processImport(ImportJob job) {
        TenantContext.set(job.tenantId(), null, null, false, null, false);
        try {
            processImportInner(job);
        } catch (RuntimeException e) {
            // panic → 任务失败（直落终态，无重试）
            log.error("FAQ import task {} crashed: {}", job.taskId(), e.getMessage(), e);
        }
    }

    private void processImportInner(ImportJob job) {
        TenantContext.set(job.tenantId(), null, null, false, null, false);
        try {
            KnowledgeBase kb;
            try {
                kb = faqGuard.validateFAQKnowledgeBase(job.kbId());
            } catch (BizException e) {
                log.warn("FAQ import task {} aborted: KB invalid: {}", job.taskId(), e.getMessage());
                return;
            }
            Knowledge knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getId, job.knowledgeId())
                    .eq(Knowledge::getTenantId, job.tenantId())
                    .isNull(Knowledge::getDeletedAt)
                    .last("LIMIT 1"));
            if (knowledge == null || knowledge.getTenantId() == null
                    || knowledge.getTenantId() != job.tenantId()
                    || !job.kbId().equals(knowledge.getKnowledgeBaseId())
                    || !"faq".equals(knowledge.getType())) {
                log.warn("FAQ import task {} aborted: document does not belong to its KB", job.taskId());
                return;
            }

            FaqImportProgress progress = new FaqImportProgress(
                    job.taskId(), job.kbId(), job.knowledgeId(), "processing", 0,
                    job.entries().size(), 0, 0, 0, 0, 0,
                    new ArrayList<>(), null, new ArrayList<>(), null, null, 0, 0, null,
                    "正在验证条目...", "", Instant.now().getEpochSecond(),
                    Instant.now().getEpochSecond(), job.dryRun(),
                    null, null, null, 0);
            try {
                validateFAQImportTags(kb, job.entries());
            } catch (BizException e) {
                markImportFailed(job, progress, e.getMessage());
                return;
            }

            int originalTotalEntries = job.entries().size();
            progress = executeFAQDryRunValidation(job, progress);

            if (job.dryRun()) {
                finalizeImport(job, progress, originalTotalEntries);
                return;
            }
            if (progress.validEntryIndices() == null || progress.validEntryIndices().isEmpty()) {
                finalizeImport(job, progress, originalTotalEntries);
                return;
            }

            progress = withMessage(progress,
                    "验证完成，开始导入 " + progress.validEntryIndices().size() + " 条有效数据...");

            Model embeddingModel;
            try {
                embeddingModel = faqIndexWriter.requireEmbeddingModel(kb);
            } catch (IllegalStateException e) {
                markImportFailed(job, progress, e.getMessage());
                return;
            }
            batchOps.executeImportBatches(job, kb, knowledge, embeddingModel, progress);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * * append 走四阶段校验（含合并候选与后校验）、replace 走三阶段校验。
     * 进度对象不可变（record）——返回更新后的实例并落库。
     */
    private FaqImportProgress executeFAQDryRunValidation(ImportJob job, FaqImportProgress progress) {
        List<Integer> valid = "append".equals(job.mode())
                ? validateAppendMode(job.tenantId(), job.kbId(), job.entries(), progress)
                : validateReplaceMode(job.entries(), progress);
        // 校验内部的 withValidationResults 是不可变 record 的局部副本——
        // 以存储里的最新进度为基底重建（无进程内共享可变状态）
        progress = withValidEntryIndices(taskStore.getProgress(job.taskId()), valid);
        taskStore.saveProgress(progress);
        return progress;
    }
    /**
     * 失败条目 CSV
     * （进度里 failed_entries 清空、failed_entries_url 接管、message 追加 CSV 提示）、
     * 计数归一、结果落库（非 dry）、replace 清理未引用标签、终态 completed。
     */
    private List<Integer> validateAppendMode(long tenantId, String kbId,
            List<FaqEntryPayload> entries, FaqImportProgress progress) {
        return validateOps.validateAppendMode(tenantId, kbId, entries, progress);
    }

    private List<Integer> validateReplaceMode(List<FaqEntryPayload> entries,
            FaqImportProgress progress) {
        return validateOps.validateReplaceMode(entries, progress);
    }

    void finalizeImport(ImportJob job, FaqImportProgress progress, int originalTotalEntries) {
        List<FaqFailedEntry> failedEntries = progress.failedEntries() == null
                ? List.of() : progress.failedEntries();
        String failedEntriesUrl = progress.failedEntriesUrl();
        String message = progress.message();
        if (!failedEntries.isEmpty()) {
            String csvUrl = batchOps.generateFailedEntriesCsv(job.tenantId(), job.taskId(), failedEntries);
            if (csvUrl != null && !csvUrl.isEmpty()) {
                failedEntriesUrl = csvUrl;
                message = message + " (失败记录已导出为CSV)";
            }
        }
        progress = withValidationResults(progress,
                failedEntriesUrl == null || failedEntriesUrl.isEmpty() ? failedEntries : List.of(),
                progress.failedCount(), progress.partialFailedCount(), null, failedEntriesUrl);
        progress = withMessage(progress, message);

        progress = withStatus(progress, "completed", 100, originalTotalEntries);
        int successCount;
        if (progress.validEntryIndices() != null && !progress.validEntryIndices().isEmpty()) {
            successCount = progress.validEntryIndices().size() - progress.partialFailedCount();
        } else if (progress.successEntries() != null && !progress.successEntries().isEmpty()) {
            successCount = progress.successEntries().size() - progress.partialFailedCount();
        } else {
            successCount = originalTotalEntries - progress.failedCount() - progress.partialFailedCount();
        }
        if (successCount < 0) {
            successCount = 0;
        }
        int skippedCount = originalTotalEntries - successCount - progress.partialFailedCount() - progress.failedCount();
        if (skippedCount < 0) {
            skippedCount = 0;
        }
        int addedCount = progress.addedCount();
        if (addedCount == 0 && progress.mergedCount() > 0) {
            addedCount = Math.max(successCount - progress.mergedCount(), 0);
        } else if (addedCount == 0) {
            addedCount = successCount;
        }
        progress = withCounts(progress, successCount, skippedCount, addedCount);
        progress = withMessage(progress, buildImportResultMessage(
                job.dryRun() ? "验证完成" : "导入完成", progress));
        progress = withError(progress, "");
        taskStore.saveProgress(progress);

        if (!job.dryRun()) {
            batchOps.saveImportResultToDatabase(job, progress, originalTotalEntries);
            if ("replace".equals(job.mode())) {
                int deleted = tagMapper.deleteUnusedTags(job.tenantId(), job.kbId());
                if (deleted > 0) {
                    log.info("FAQ import task {}: cleaned up {} unused tags after replace import",
                            job.taskId(), deleted);
                }
            }
        }
        // updateFAQImportProgressStatus(completed)：终态覆写 + 清 running key
        progress = withStatus(progress, "completed", 100, originalTotalEntries);
        progress = withUpdatedNow(progress);
        progress = withError(progress, "");
        taskStore.saveProgress(progress);
        taskStore.clearRunningInfoIfMatches(job.kbId(), job.taskId(), job.instanceId(), job.enqueuedAt());
        log.info("FAQ task completed: {}, dry_run={}, success: {}, added: {}, merged: {}, failed: {}, partial_failed: {}",
                job.taskId(), job.dryRun(), progress.successCount(), progress.addedCount(),
                progress.mergedCount(), progress.failedCount(), progress.partialFailedCount());
    }
    String buildImportResultMessage(String prefix, FaqImportProgress p) {
        return batchOps.buildImportResultMessage(prefix, p);
    }


    void markImportFailed(ImportJob job, FaqImportProgress progress, String error) {
        progress = withStatus(progress, "failed", 0, progress.total());
        progress = withMessage(progress, "导入失败");
        progress = withError(progress, error);
        progress = withUpdatedNow(progress);
        taskStore.saveProgress(progress);
        taskStore.clearRunningInfoIfMatches(job.kbId(), job.taskId(), job.instanceId(), job.enqueuedAt());
        log.warn("FAQ import task {} failed: {}", job.taskId(), error);
    }

    // ── 不可变 record 的局部更新辅助 ─────────────────────────────────────

    static FaqImportProgress withMessage(FaqImportProgress p, String message) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), message, p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    static FaqImportProgress withError(FaqImportProgress p, String error) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), error, p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    static FaqImportProgress withStatus(FaqImportProgress p, String status, int prog, int processed) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), status, prog,
                p.total(), processed, p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    /** 批次成功后累积 success_entries。 */
    static FaqImportProgress withSuccessEntries(FaqImportProgress p,
                                                        List<FaqSuccessEntry> successEntries) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(),
                successEntries == null ? List.of() : new ArrayList<>(successEntries),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    static FaqImportProgress withUpdatedNow(FaqImportProgress p) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(),
                Instant.now().getEpochSecond(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    static FaqImportProgress withCounts(FaqImportProgress p, int successCount, int skippedCount,
                                                int addedCount) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), successCount, p.failedCount(), p.partialFailedCount(),
                skippedCount, p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), addedCount,
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    static FaqImportProgress withValidEntryIndices(FaqImportProgress p, List<Integer> valid) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                valid, p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    /** 校验结果落进度（failedEntries/failedCount/partialFailedCount/mergeIndices/failedUrl 的部分覆写）。 */
    static FaqImportProgress withValidationResults(FaqImportProgress p,
                                                           List<FaqFailedEntry> failedEntries,
                                                           Integer failedCount,
                                                           Integer partialFailedCount,
                                                           List<Integer> mergeEntryIndices,
                                                           String failedEntriesUrl) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(),
                p.successCount(),
                failedCount == null ? p.failedCount() : failedCount,
                partialFailedCount == null ? p.partialFailedCount() : partialFailedCount,
                p.skippedCount(),
                failedEntries == null ? p.failedEntries() : failedEntries,
                failedEntriesUrl == null ? p.failedEntriesUrl() : failedEntriesUrl,
                p.successEntries(),
                p.validEntryIndices(),
                mergeEntryIndices == null ? p.mergeEntryIndices() : mergeEntryIndices,
                p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    /** completed 时用
     *  knowledges.last_faq_import_result 覆盖统计字段。 */
    public FaqImportProgress getImportProgress(String taskId) {
        FaqImportProgress progress = taskStore.getProgress(taskId);
        if (progress == null) {
            throw new BizException(AppError.notFound("FAQ import task not found"));
        }
        if ("completed".equals(progress.status()) && progress.knowledgeId() != null
                && !progress.knowledgeId().isEmpty()) {
            Knowledge knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getId, progress.knowledgeId())
                    .eq(Knowledge::getTenantId, tenantId())
                    .isNull(Knowledge::getDeletedAt)
                    .last("LIMIT 1"));
            if (knowledge != null) {
                FaqImportResult result = parseImportResult(knowledge.getLastFaqImportResult());
                if (result != null) {
                    progress = new FaqImportProgress(
                            progress.taskId(), progress.kbId(), progress.knowledgeId(),
                            progress.status(), progress.progress(), progress.total(),
                            progress.processed(),
                            result.successCount(), result.failedCount(),
                            result.partialFailedCount(), result.skippedCount(),
                            progress.failedEntries(),
                            result.failedEntriesUrl() == null || result.failedEntriesUrl().isEmpty()
                                    ? progress.failedEntriesUrl() : result.failedEntriesUrl(),
                            progress.successEntries(),
                            progress.validEntryIndices(), progress.mergeEntryIndices(),
                            result.mergedCount(), result.addedCount(), progress.mergeDetails(),
                            progress.message(), progress.error(),
                            progress.createdAt(), progress.updatedAt(), progress.dryRun(),
                            result.importMode(), result.importedAt(),
                            result.displayStatus(), result.processingTime());
                }
            }
        }
        return progress;
    }
    public void updateLastImportResultDisplayStatus(String kbId, String displayStatus) {
        if (!"open".equals(displayStatus) && !"close".equals(displayStatus)) {
            throw new BizException(AppError.badRequest("invalid display status, must be 'open' or 'close'"));
        }
        KnowledgeBase kb = faqGuard.writableFAQKnowledgeBase(kbId);
        long tid = kb.getTenantId() == null ? tenantId() : kb.getTenantId();

        List<Knowledge> knowledgeList = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getTenantId, tid)
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .isNull(Knowledge::getDeletedAt));
        Knowledge faqKnowledge = null;
        for (Knowledge k : knowledgeList) {
            if ("faq".equals(k.getType())) {
                faqKnowledge = k;
                break;
            }
        }
        if (faqKnowledge == null) {
            throw new BizException(AppError.notFound("FAQ knowledge not found in this knowledge base"));
        }
        FaqImportResult result = parseImportResult(faqKnowledge.getLastFaqImportResult());
        if (result == null) {
            throw new BizException(AppError.notFound("no FAQ import result found"));
        }
        FaqImportResult updated = new FaqImportResult(result.totalEntries(), result.successCount(),
                result.failedCount(), result.partialFailedCount(), result.skippedCount(),
                result.mergedCount(), result.addedCount(), result.importMode(), result.importedAt(),
                result.taskId(), result.failedEntriesUrl(), displayStatus, result.processingTime());
        faqKnowledge.setLastFaqImportResult(FaqChunkMetadata.JSON.valueToTree(updated));
        knowledgeMapper.updateById(faqKnowledge);
    }
    private void validateFAQImportTags(KnowledgeBase kb, List<FaqEntryPayload> entries) {
        Map<Long, FaqEntryFieldsUpdate> byTag = new LinkedHashMap<>();
        for (FaqEntryPayload entry : entries) {
            if (entry.tagId() != 0) {
                byTag.putIfAbsent(entry.tagId(), new FaqEntryFieldsUpdate(null, null, null));
            }
        }
        faqGuard.planFAQFields(kb, new FaqEntryFieldsBatchUpdate(null, byTag, null));
    }

    /** ：≤128 且仅 [A-Za-z0-9_-]。 */
    private static boolean validateTaskId(String taskId) {
        if (taskId == null || taskId.isEmpty() || taskId.length() > 128) {
            return false;
        }
        for (int i = 0; i < taskId.length(); i++) {
            char c = taskId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private FaqImportResult parseImportResult(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isEmpty()) {
            return null;
        }
        try {
            return FaqChunkMetadata.JSON.treeToValue(node, FaqImportResult.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    // ══════════════════ 导入任务（进程内） ═════════════════════════════

    /** 进程内导入任务的参数。 */
    record ImportJob(long tenantId, String taskId, String kbId, String knowledgeId,
                     String mode, boolean dryRun, long enqueuedAt, String instanceId,
                     List<FaqEntryPayload> entries) {
    }

    private void executeImportBatches(ImportJob job, KnowledgeBase kb, Knowledge faqKnowledge,
            Model embeddingModel, FaqImportProgress progress) {
        batchOps.executeImportBatches(job, kb, faqKnowledge, embeddingModel, progress);
    }

    private String generateFailedEntriesCsv(long tenantId, String taskId, List<FaqFailedEntry> failedEntries) {
        return batchOps.generateFailedEntriesCsv(tenantId, taskId, failedEntries);
    }

    private void saveImportResultToDatabase(ImportJob job, FaqImportProgress progress, int originalTotalEntries) {
        batchOps.saveImportResultToDatabase(job, progress, originalTotalEntries);
    }

}
