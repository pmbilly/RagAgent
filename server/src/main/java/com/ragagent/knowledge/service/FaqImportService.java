package com.ragagent.knowledge.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.dto.FaqFailedEntry;
import com.ragagent.knowledge.dto.FaqImportProgress;
import com.ragagent.knowledge.dto.FaqImportResult;
import com.ragagent.knowledge.dto.FaqSuccessEntry;
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
import com.ragagent.knowledge.dto.FaqEntryPayload;
import com.ragagent.knowledge.dto.FaqBatchUpsertPayload;
import com.ragagent.knowledge.dto.FaqEntryFieldsBatchUpdate;
import com.ragagent.knowledge.dto.FaqEntryFieldsUpdate;

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

    private final FaqChunkRepository faqChunkRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeTagMapper tagMapper;
    private final FaqImportTaskStore taskStore;
    private final LocalStorageService storage;
    private final TenantFileStorage fileStorage;
    private final FaqGuard faqGuard;
    private final FaqChunkCodec faqChunkCodec;
    private final FaqIndexWriter faqIndexWriter;
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
            executeImportBatches(job, kb, knowledge, embeddingModel, progress);
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
    private List<Integer> validateAppendMode(long tenantId, String kbId,
                                             List<FaqEntryPayload> entries,
                                             FaqImportProgress progress) {
        List<Chunk> existingChunks = faqChunkRepository
                .listAllFAQChunksWithMetadataByKnowledgeBaseId(tenantId, kbId);

        Map<String, Chunk> existingStdQToChunk = new LinkedHashMap<>();
        Map<String, String> existingQuestionToChunkID = new LinkedHashMap<>();
        Map<String, Set<String>> existingChunkQuestions = new LinkedHashMap<>();
        Map<String, String> existingChunkIDToStdQ = new LinkedHashMap<>();
        for (Chunk chunk : existingChunks) {
            FaqChunkMetadata meta = faqChunkCodec.sanitizedFaqMetadata(chunk);
            if (meta == null) {
                continue;
            }
            Set<String> qs = new LinkedHashSet<>();
            if (!meta.standardQuestion.isEmpty()) {
                existingStdQToChunk.put(meta.standardQuestion, chunk);
                existingQuestionToChunkID.put(meta.standardQuestion, chunk.getId());
                qs.add(meta.standardQuestion);
            }
            if (meta.similarQuestions != null) {
                for (String q : meta.similarQuestions) {
                    if (!q.isEmpty()) {
                        existingQuestionToChunkID.put(q, chunk.getId());
                        qs.add(q);
                    }
                }
            }
            existingChunkQuestions.put(chunk.getId(), qs);
            existingChunkIDToStdQ.put(chunk.getId(), meta.standardQuestion);
        }

        Map<Integer, Chunk> mergeChunkMap = new LinkedHashMap<>();

        // 第一次迭代：基本格式验证 + 文件内标准问去重 + 合并候选识别
        Map<String, Integer> batchStandardQuestions = new LinkedHashMap<>();
        List<Integer> validIndicesAfterStdQ = new ArrayList<>();
        List<FaqFailedEntry> failedEntries = new ArrayList<>(progress.failedEntries() == null
                ? List.of() : progress.failedEntries());
        int failedCount = progress.failedCount();
        for (int i = 0; i < entries.size(); i++) {
            FaqEntryPayload entry = entries.get(i);
            String basicError = validateEntryPayloadBasic(entry);
            if (basicError != null) {
                failedCount++;
                failedEntries.add(failedEntry(i, basicError, entry, "pre_validation"));
                continue;
            }
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            Integer firstIdx = batchStandardQuestions.get(standardQ);
            if (firstIdx != null) {
                failedCount++;
                failedEntries.add(failedEntry(i,
                        "标准问冲突：与批次内第 " + (firstIdx + 1) + " 条标准问重复", entry, "pre_validation"));
                continue;
            }
            Chunk mergeChunk = existingStdQToChunk.get(standardQ);
            if (mergeChunk != null) {
                mergeChunkMap.put(i, mergeChunk);
            } else if (existingQuestionToChunkID.containsKey(standardQ)) {
                String conflictChunkId = existingQuestionToChunkID.get(standardQ);
                String conflictStdQ = existingChunkIDToStdQ.getOrDefault(conflictChunkId, "");
                failedCount++;
                failedEntries.add(failedEntry(i,
                        "标准问冲突：与知识库中标准问“" + conflictStdQ + "”的相似问“"
                                + standardQ + "”重复", entry, "pre_validation"));
                continue;
            }
            batchStandardQuestions.put(standardQ, i);
            validIndicesAfterStdQ.add(i);
        }

        // 第二次迭代：相似问冲突检测
        Map<String, Integer> batchAllQuestions = new LinkedHashMap<>();
        for (int i : validIndicesAfterStdQ) {
            FaqEntryPayload entry = entries.get(i);
            batchAllQuestions.putIfAbsent(FaqChunkMetadata.trimSpace(entry.standardQuestion()), i);
            if (entry.similarQuestions() != null) {
                for (String q : entry.similarQuestions()) {
                    String t = FaqChunkMetadata.trimSpace(q);
                    if (!t.isEmpty()) {
                        batchAllQuestions.putIfAbsent(t, i);
                    }
                }
            }
        }

        Map<Integer, List<String>> removedSimilarMap = new LinkedHashMap<>();
        Map<Integer, List<String>> removedNegativeMap = new LinkedHashMap<>();

        for (int idx = 0; idx < validIndicesAfterStdQ.size(); idx++) {
            int i = validIndicesAfterStdQ.get(idx);
            FaqEntryPayload entry = entries.get(i);
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            Set<String> ownChunkQuestions = mergeChunkMap.containsKey(i)
                    ? existingChunkQuestions.get(mergeChunkMap.get(i).getId()) : null;

            List<String> validSimilar = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            if (entry.similarQuestions() != null) {
                for (String qRaw : entry.similarQuestions()) {
                    String q = FaqChunkMetadata.trimSpace(qRaw);
                    if (q.isEmpty()) {
                        continue;
                    }
                    if (q.equals(standardQ)) {
                        removed.add("「相似问冲突」：“" + q + "”与本条“标准问”冲突");
                        continue;
                    }
                    if (existingQuestionToChunkID.containsKey(q)) {
                        if (ownChunkQuestions != null && ownChunkQuestions.contains(q)) {
                            validSimilar.add(q);
                            continue;
                        }
                        removed.add("「相似问冲突」：“" + q + "”与知识库已有“标准问/相似问”冲突");
                        continue;
                    }
                    Integer firstIdx2 = batchAllQuestions.get(q);
                    if (firstIdx2 != null && firstIdx2 != i) {
                        removed.add("「相似问冲突」：“" + q + "”与第 " + (firstIdx2 + 1)
                                + " 行“标准问/相似问”冲突");
                        continue;
                    }
                    validSimilar.add(q);
                }
            }
            if (entry.similarQuestions() != null) {
                entries.get(i).similarQuestions().clear();
                entries.get(i).similarQuestions().addAll(validSimilar);
            } else if (!validSimilar.isEmpty()) {
                entries.set(i, new FaqEntryPayload(entry.id(), entry.standardQuestion(),
                        validSimilar, entry.negativeQuestions(), entry.answers(), entry.answerStrategy(),
                        entry.tagId(), entry.tagName(), entry.enabled(), entry.recommended()));
            }
            if (!removed.isEmpty()) {
                removedSimilarMap.put(i, removed);
            }
        }

        // 第三次迭代：反例冲突检测（预校验，仅检查新条目自身数据）
        for (int idx = 0; idx < validIndicesAfterStdQ.size(); idx++) {
            int i = validIndicesAfterStdQ.get(idx);
            FaqEntryPayload entry = entries.get(i);
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            Set<String> currentQAQuestions = new LinkedHashSet<>();
            currentQAQuestions.add(standardQ);
            if (entry.similarQuestions() != null) {
                currentQAQuestions.addAll(entry.similarQuestions());
            }
            List<String> validNegative = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            if (entry.negativeQuestions() != null) {
                for (String qRaw : entry.negativeQuestions()) {
                    String q = FaqChunkMetadata.trimSpace(qRaw);
                    if (q.isEmpty()) {
                        continue;
                    }
                    if (currentQAQuestions.contains(q)) {
                        removed.add("「反例冲突」：“" + q + "”与本条“标准问/相似问”冲突");
                        continue;
                    }
                    validNegative.add(q);
                }
            }
            if (entry.negativeQuestions() != null) {
                entries.get(i).negativeQuestions().clear();
                entries.get(i).negativeQuestions().addAll(validNegative);
            }
            if (!removed.isEmpty()) {
                removedNegativeMap.put(i, removed);
            }
        }

        // 第四次迭代：后校验（仅合并候选）
        Set<Integer> postValidationFailed = new LinkedHashSet<>();
        int mergeCount = 0;
        for (int i : validIndicesAfterStdQ) {
            Chunk mergeChunk = mergeChunkMap.get(i);
            if (mergeChunk == null) {
                continue;
            }
            FaqChunkMetadata existingMeta = faqChunkCodec.sanitizedFaqMetadata(mergeChunk);
            if (existingMeta == null) {
                continue;
            }
            FaqEntryPayload entry = entries.get(i);
            List<String> mergedSimilar = unionStrings(existingMeta.similarQuestions, entry.similarQuestions());
            List<String> mergedNegative = unionStrings(existingMeta.negativeQuestions, entry.negativeQuestions());
            Set<String> mergedPositiveSet = new LinkedHashSet<>();
            mergedPositiveSet.add(existingMeta.standardQuestion);
            mergedPositiveSet.addAll(mergedSimilar);
            List<String> conflictingNegatives = new ArrayList<>();
            for (String q : mergedNegative) {
                if (mergedPositiveSet.contains(q)) {
                    conflictingNegatives.add(q);
                }
            }
            if (!conflictingNegatives.isEmpty()) {
                postValidationFailed.add(i);
                mergeChunkMap.remove(i);
                failedCount++;
                failedEntries.add(failedEntry(i,
                        "后校验失败：合并后反例「" + String.join("、", conflictingNegatives) + "」与相似问冲突",
                        entry, "post_validation"));
            } else {
                mergeCount++;
            }
        }
        if (!postValidationFailed.isEmpty()) {
            validIndicesAfterStdQ.removeIf(postValidationFailed::contains);
        }

        int partialFailedCount = progress.partialFailedCount();
        for (int i : validIndicesAfterStdQ) {
            List<String> removedSimilar = removedSimilarMap.get(i);
            List<String> removedNegative = removedNegativeMap.get(i);
            if ((removedSimilar != null && !removedSimilar.isEmpty())
                    || (removedNegative != null && !removedNegative.isEmpty())) {
                failedEntries.add(partialFailedEntry(i, entries.get(i),
                        removedSimilar == null ? List.of() : removedSimilar,
                        removedNegative == null ? List.of() : removedNegative));
                partialFailedCount++;
            }
        }
        List<Integer> mergeIndices = new ArrayList<>();
        for (int i : validIndicesAfterStdQ) {
            if (mergeChunkMap.containsKey(i)) {
                mergeIndices.add(i);
            }
        }
        progress = withValidationResults(progress, failedEntries, failedCount, partialFailedCount,
                mergeIndices, null);
        taskStore.saveProgress(progress);
        log.info("Append mode validation completed: total={}, valid={}, merge_candidates={}, failed={}, partial_failed={}",
                entries.size(), validIndicesAfterStdQ.size(), mergeCount, progress.failedCount(),
                progress.partialFailedCount());
        return validIndicesAfterStdQ;
    }
    private List<Integer> validateReplaceMode(List<FaqEntryPayload> entries,
                                              FaqImportProgress progress) {
        Map<String, Integer> batchStandardQuestions = new LinkedHashMap<>();
        List<Integer> validIndicesAfterStdQ = new ArrayList<>();
        List<FaqFailedEntry> failedEntries = new ArrayList<>(progress.failedEntries() == null
                ? List.of() : progress.failedEntries());
        int failedCount = progress.failedCount();
        for (int i = 0; i < entries.size(); i++) {
            FaqEntryPayload entry = entries.get(i);
            String basicError = validateEntryPayloadBasic(entry);
            if (basicError != null) {
                failedCount++;
                failedEntries.add(failedEntry(i, basicError, entry, null));
                continue;
            }
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            Integer firstIdx = batchStandardQuestions.get(standardQ);
            if (firstIdx != null) {
                failedCount++;
                failedEntries.add(failedEntry(i,
                        "标准问冲突：与批次内第 " + (firstIdx + 1) + " 条标准问重复", entry, null));
                continue;
            }
            batchStandardQuestions.put(standardQ, i);
            validIndicesAfterStdQ.add(i);
        }

        Map<String, Integer> batchAllQuestions = new LinkedHashMap<>();
        for (int i : validIndicesAfterStdQ) {
            FaqEntryPayload entry = entries.get(i);
            batchAllQuestions.putIfAbsent(FaqChunkMetadata.trimSpace(entry.standardQuestion()), i);
            if (entry.similarQuestions() != null) {
                for (String q : entry.similarQuestions()) {
                    String t = FaqChunkMetadata.trimSpace(q);
                    if (!t.isEmpty()) {
                        batchAllQuestions.putIfAbsent(t, i);
                    }
                }
            }
        }

        Map<Integer, List<String>> removedSimilarMap = new LinkedHashMap<>();
        Map<Integer, List<String>> removedNegativeMap = new LinkedHashMap<>();

        for (int idx = 0; idx < validIndicesAfterStdQ.size(); idx++) {
            int i = validIndicesAfterStdQ.get(idx);
            FaqEntryPayload entry = entries.get(i);
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            List<String> validSimilar = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            if (entry.similarQuestions() != null) {
                for (String qRaw : entry.similarQuestions()) {
                    String q = FaqChunkMetadata.trimSpace(qRaw);
                    if (q.isEmpty()) {
                        continue;
                    }
                    Integer firstIdx2 = batchAllQuestions.get(q);
                    if (firstIdx2 != null && firstIdx2 != i) {
                        removed.add("「相似问冲突」：“" + q + "”与第 " + (firstIdx2 + 1)
                                + " 行“标准问/相似问”冲突");
                        continue;
                    }
                    if (q.equals(standardQ)) {
                        removed.add("「相似问冲突」：“" + q + "”与本条“标准问”冲突");
                        continue;
                    }
                    validSimilar.add(q);
                }
            }
            if (entry.similarQuestions() != null) {
                entries.get(i).similarQuestions().clear();
                entries.get(i).similarQuestions().addAll(validSimilar);
            }
            if (!removed.isEmpty()) {
                removedSimilarMap.put(i, removed);
            }
        }

        for (int idx = 0; idx < validIndicesAfterStdQ.size(); idx++) {
            int i = validIndicesAfterStdQ.get(idx);
            FaqEntryPayload entry = entries.get(i);
            String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
            Set<String> currentQAQuestions = new LinkedHashSet<>();
            currentQAQuestions.add(standardQ);
            if (entry.similarQuestions() != null) {
                currentQAQuestions.addAll(entry.similarQuestions());
            }
            List<String> validNegative = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            if (entry.negativeQuestions() != null) {
                for (String qRaw : entry.negativeQuestions()) {
                    String q = FaqChunkMetadata.trimSpace(qRaw);
                    if (q.isEmpty()) {
                        continue;
                    }
                    if (currentQAQuestions.contains(q)) {
                        removed.add("「反例冲突」：“" + q + "”与本条“标准问/相似问”冲突");
                        continue;
                    }
                    validNegative.add(q);
                }
            }
            if (entry.negativeQuestions() != null) {
                entries.get(i).negativeQuestions().clear();
                entries.get(i).negativeQuestions().addAll(validNegative);
            }
            if (!removed.isEmpty()) {
                removedNegativeMap.put(i, removed);
            }
        }

        int partialFailedCount = progress.partialFailedCount();
        for (int i : validIndicesAfterStdQ) {
            List<String> removedSimilar = removedSimilarMap.get(i);
            List<String> removedNegative = removedNegativeMap.get(i);
            if ((removedSimilar != null && !removedSimilar.isEmpty())
                    || (removedNegative != null && !removedNegative.isEmpty())) {
                failedEntries.add(partialFailedEntry(i, entries.get(i),
                        removedSimilar == null ? List.of() : removedSimilar,
                        removedNegative == null ? List.of() : removedNegative));
                partialFailedCount++;
            }
        }
        progress = withValidationResults(progress, failedEntries, failedCount, partialFailedCount,
                null, null);
        taskStore.saveProgress(progress);
        return validIndicesAfterStdQ;
    }
    private static String validateEntryPayloadBasic(FaqEntryPayload entry) {
        if (entry == null) {
            return "条目不能为空";
        }
        String standardQ = FaqChunkMetadata.trimSpace(entry.standardQuestion());
        if (standardQ.isEmpty()) {
            return "标准问不能为空";
        }
        if (entry.answers() == null || entry.answers().isEmpty()) {
            return "答案不能为空";
        }
        boolean hasValidAnswer = false;
        for (String a : entry.answers()) {
            if (!FaqChunkMetadata.trimSpace(a).isEmpty()) {
                hasValidAnswer = true;
                break;
            }
        }
        if (!hasValidAnswer) {
            return "答案不能全为空";
        }
        return null;
    }
    private static List<String> unionStrings(List<String> a, List<String> b) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String s0 : a == null ? new String[0] : a.toArray(new String[0])) {
            String t = FaqChunkMetadata.trimSpace(s0);
            if (!t.isEmpty() && seen.add(t)) {
                result.add(t);
            }
        }
        for (String s0 : b == null ? new String[0] : b.toArray(new String[0])) {
            String t = FaqChunkMetadata.trimSpace(s0);
            if (!t.isEmpty() && seen.add(t)) {
                result.add(t);
            }
        }
        return result;
    }
    private static FaqFailedEntry failedEntry(int idx, String reason, FaqEntryPayload entry,
                                              String failureType) {
        boolean answerAll = FaqChunkMetadata.ANSWER_STRATEGY_ALL.equals(entry.answerStrategy());
        boolean isDisabled = entry.enabled() != null && !entry.enabled();
        return new FaqFailedEntry(idx, reason, failureType, false,
                entry.tagName(), FaqChunkMetadata.trimSpace(entry.standardQuestion()),
                entry.similarQuestions(), entry.negativeQuestions(), entry.answers(),
                answerAll, isDisabled, null, null);
    }
    private static FaqFailedEntry partialFailedEntry(int idx, FaqEntryPayload entry,
                                                     List<String> removedSimilar, List<String> removedNegative) {
        boolean answerAll = FaqChunkMetadata.ANSWER_STRATEGY_ALL.equals(entry.answerStrategy());
        boolean isDisabled = entry.enabled() != null && !entry.enabled();
        List<String> summary = new ArrayList<>();
        if (!removedSimilar.isEmpty()) {
            summary.add(removedSimilar.size() + "条相似问被移除");
        }
        if (!removedNegative.isEmpty()) {
            summary.add(removedNegative.size() + "条反例被移除");
        }
        List<String> reasonParts = new ArrayList<>();
        reasonParts.add("部分成功：" + String.join("，", summary));
        if (!removedSimilar.isEmpty()) {
            reasonParts.add(String.join("; ", removedSimilar));
        }
        if (!removedNegative.isEmpty()) {
            reasonParts.add(String.join("; ", removedNegative));
        }
        return new FaqFailedEntry(idx, String.join(" | ", reasonParts), null, true,
                entry.tagName(), FaqChunkMetadata.trimSpace(entry.standardQuestion()),
                entry.similarQuestions(), entry.negativeQuestions(), entry.answers(),
                answerAll, isDisabled, removedSimilar, removedNegative);
    }

    /**
     * 失败条目 CSV
     * （进度里 failed_entries 清空、failed_entries_url 接管、message 追加 CSV 提示）、
     * 计数归一、结果落库（非 dry）、replace 清理未引用标签、终态 completed。
     */
    private void finalizeImport(ImportJob job, FaqImportProgress progress, int originalTotalEntries) {
        List<FaqFailedEntry> failedEntries = progress.failedEntries() == null
                ? List.of() : progress.failedEntries();
        String failedEntriesUrl = progress.failedEntriesUrl();
        String message = progress.message();
        if (!failedEntries.isEmpty()) {
            String csvUrl = generateFailedEntriesCsv(job.tenantId(), job.taskId(), failedEntries);
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
            saveImportResultToDatabase(job, progress, originalTotalEntries);
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
    private void markImportFailed(ImportJob job, FaqImportProgress progress, String error) {
        progress = withStatus(progress, "failed", 0, progress.total());
        progress = withMessage(progress, "导入失败");
        progress = withError(progress, error);
        progress = withUpdatedNow(progress);
        taskStore.saveProgress(progress);
        taskStore.clearRunningInfoIfMatches(job.kbId(), job.taskId(), job.instanceId(), job.enqueuedAt());
        log.warn("FAQ import task {} failed: {}", job.taskId(), error);
    }
    private static final int FAQ_IMPORT_BATCH_SIZE = 50;

    /**
     * 导入执行循环——
     * 2026-09-22 走查批接线（此前是「embedding runtime is not available」占位）：
     * 按 faqImportBatchSize(50) 分批 → 逐条 sanitize/resolveTagID/建 chunk → CreateChunks
     * → faqIndexWriter.indexFAQChunks(adjustStorage=true) → status=2 → 收集成功条目 → 进度落库；
     * 末尾 finalizeImport（completed 终态 + 结果落库 + replace 清未引用标签）。
     * 事务性回滚（失败直落 failed 终态，残留行由重导/replace 清理）——与 processImport
     * 的既有取舍同款。</p>
     */
    private void executeImportBatches(ImportJob job, KnowledgeBase kb, Knowledge faqKnowledge,
                                      Model embeddingModel, FaqImportProgress progress) {
        List<Integer> valid = progress.validEntryIndices();
        int totalEntries = progress.total();
        int skippedCount = progress.skippedCount();
        int actualProcessed = skippedCount + progress.mergedCount();
        String indexMode = faqChunkCodec.faqIndexMode(kb);
        List<FaqSuccessEntry> successEntries = progress.successEntries() == null
                ? new ArrayList<>() : new ArrayList<>(progress.successEntries());

        for (int i = 0; i < valid.size(); i += FAQ_IMPORT_BATCH_SIZE) {
            int end = Math.min(i + FAQ_IMPORT_BATCH_SIZE, valid.size());
            List<Chunk> chunks = new ArrayList<>(end - i);
            for (int k = i; k < end; k++) {
                int entryIdx = valid.get(k); // dry-run 校验给出的原始条目下标
                FaqEntryPayload entry = job.entries().get(entryIdx);
                FaqChunkMetadata meta;
                try {
                    meta = faqGuard.sanitizeFAQEntryPayload(entry);
                } catch (RuntimeException e) {
                    markImportFailed(job, progress,
                            "FAQ import failed: failed to sanitize entry at index " + entryIdx
                                    + ": " + e.getMessage());
                    return;
                }
                String tagID;
                try {
                    tagID = faqGuard.resolveTagID(job.kbId(), entry);
                } catch (RuntimeException e) {
                    markImportFailed(job, progress,
                            "FAQ import failed: failed to resolve tag for entry at index " + entryIdx
                                    + ": " + e.getMessage());
                    return;
                }
                boolean isEnabled = entry.enabled() == null || entry.enabled();
                Chunk chunk = new Chunk();
                chunk.setId(UUID.randomUUID().toString());
                chunk.setTenantId(job.tenantId());
                chunk.setKnowledgeId(faqKnowledge.getId());
                chunk.setKnowledgeBaseId(kb.getId());
                chunk.setContent(faqChunkCodec.buildFAQChunkContent(meta, indexMode));
                chunk.setIsEnabled(isEnabled);
                chunk.setChunkType("faq");
                chunk.setTagId(tagID);
                chunk.setStatus(1); // stored
                if (entry.id() != null && entry.id() > 0) {
                    chunk.setSeqId(entry.id());
                }
                faqChunkCodec.setFaqMetadata(chunk, meta);
                // 导入建的 chunk 不设 Flags（推荐位零值）
                chunk.setCreatedAt(OffsetDateTime.now());
                chunk.setUpdatedAt(chunk.getCreatedAt());
                chunks.add(chunk);
            }
            List<String> chunkIds = new ArrayList<>(chunks.size());
            for (Chunk chunk : chunks) {
                chunkIds.add(chunk.getId());
            }
            try {
                faqIndexWriter.createChunks(chunks);
            } catch (RuntimeException e) {
                markImportFailed(job, progress,
                        "FAQ import failed: failed to create chunks: " + e.getMessage());
                return;
            }
            try {
                faqIndexWriter.indexFAQChunks(kb, faqKnowledge, chunks, embeddingModel, true);
            } catch (RuntimeException e) {
                markImportFailed(job, progress,
                        "FAQ import failed: failed to index chunks: " + e.getMessage());
                return;
            }
            for (Chunk chunk : chunks) {
                chunk.setStatus(2); // indexed
            }
            try {
                faqChunkRepository.updateChunks(chunks);
            } catch (RuntimeException e) {
                markImportFailed(job, progress,
                        "FAQ import failed: failed to update chunks status: " + e.getMessage());
                return;
            }

            // 收集成功条目
            for (int k = 0; k < chunks.size(); k++) {
                Chunk chunk = chunks.get(k);
                FaqChunkMetadata meta = faqChunkCodec.sanitizedFaqMetadata(chunk);
                String standardQ = meta == null || meta.standardQuestion == null
                        ? "" : meta.standardQuestion;
                long tagID = 0;
                String tagName = "";
                if (chunk.getTagId() != null && !chunk.getTagId().isEmpty()) {
                    KnowledgeTag tag = tagMapper
                            .selectByTenantAndIds(job.tenantId(), List.of(chunk.getTagId()))
                            .stream().findFirst().orElse(null);
                    if (tag != null) {
                        tagID = tag.getSeqId();
                        tagName = tag.getName();
                    }
                }
                successEntries.add(new FaqSuccessEntry(valid.get(k),
                        chunk.getSeqId() == null ? 0 : chunk.getSeqId(), tagID, tagName, standardQ));
            }

            actualProcessed += end - i;
            int prog = totalEntries == 0 ? 0 : (int) ((double) actualProcessed / totalEntries * 100);
            progress = withStatus(progress, "processing", prog, actualProcessed);
            progress = withMessage(progress,
                    "正在处理第 " + actualProcessed + "/" + totalEntries + " 条");
            progress = withSuccessEntries(progress, successEntries);
            taskStore.saveProgress(progress);
        }

        progress = withSuccessEntries(progress, successEntries);
        taskStore.saveProgress(progress);
        log.info("FAQ import task {}: all batches completed, processed: {}", job.taskId(), actualProcessed);
        finalizeImport(job, progress, totalEntries);
    }

    /** ：BOM + 8 列，
     *  落 {base}/{tenant}/exports/{name}_{UnixNano}.csv，返回 local:// URL。 */
    private String generateFailedEntriesCsv(long tenantId, String taskId, List<FaqFailedEntry> failedEntries) {
        StringBuilder buf = new StringBuilder();
        buf.append('\uFEFF');
        buf.append("错误原因,分类(必填),问题(必填),相似问题(选填-多个用##分隔),反例问题(选填-多个用##分隔),")
                .append("机器人回答(必填-多个用##分隔),是否全部回复(选填-默认FALSE),是否停用(选填-默认FALSE)")
                .append('\n');
        for (FaqFailedEntry entry : failedEntries) {
            String answerAll = entry.answerAll() ? "true" : "false";
            String isDisabled = entry.disabled() ? "true" : "false";
            buf.append(csvEscape(entry.reason())).append(',')
                    .append(csvEscape(entry.tagName())).append(',')
                    .append(csvEscape(entry.standardQuestion())).append(',')
                    .append(csvEscape(entry.similarQuestions() == null ? "" : String.join("##", entry.similarQuestions())))
                    .append(',')
                    .append(csvEscape(entry.negativeQuestions() == null ? "" : String.join("##", entry.negativeQuestions())))
                    .append(',')
                    .append(csvEscape(entry.answers() == null ? "" : String.join("##", entry.answers())))
                    .append(',')
                    .append(answerAll).append(',')
                    .append(isDisabled).append('\n');
        }
        String base = storage.baseDir().toString();
        java.nio.file.Path dir = java.nio.file.Path.of(base, String.valueOf(tenantId), "exports");
        String unique = "faq_dryrun_failed_" + taskId + "_" + System.nanoTime() + ".csv";
        byte[] csv = buf.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (fileStorage != null) {
            // fileSvc.SaveBytes(..., temp=true) + GetFileURL → 云上落临时桶、回预签名 URL
            TenantFileStorage.Exported exported =
                    fileStorage.saveExportedBytesToUrl(tenantId, unique, csv, true);
            if (exported.handled()) {
                if (exported.url() == null) {
                    log.warn("FAQ import task {}: failed to generate failed entries CSV", taskId);
                }
                return exported.url();
            }
        }
        try {
            // 本地租户：既有落盘 + local:// 引用（契约样例 形态）
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path target = dir.resolve(unique);
            java.nio.file.Files.write(target, csv);
            return "local://" + tenantId + "/exports/" + unique;
        } catch (java.io.IOException e) {
            log.warn("FAQ import task {}: failed to generate failed entries CSV: {}", taskId, e.getMessage());
            return null;
        }
    }

    private static String csvEscape(String s) {
        if (s == null) {
            s = "";
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
    private void saveImportResultToDatabase(ImportJob job, FaqImportProgress progress, int originalTotalEntries) {
        Knowledge knowledge = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, job.knowledgeId())
                .eq(Knowledge::getTenantId, job.tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (knowledge == null) {
            log.warn("FAQ import task {}: knowledge not found for result save", job.taskId());
            return;
        }
        int skippedCount = originalTotalEntries - progress.successCount()
                - progress.partialFailedCount() - progress.failedCount();
        if (skippedCount < 0) {
            skippedCount = 0;
        }
        long processingTime = Instant.now().getEpochSecond() - progress.createdAt();
        FaqImportResult result = new FaqImportResult(originalTotalEntries, progress.successCount(),
                progress.failedCount(), progress.partialFailedCount(), skippedCount,
                progress.mergedCount(), progress.addedCount(), job.mode(),
                OffsetDateTime.now(), job.taskId(),
                progress.failedEntriesUrl() == null || progress.failedEntriesUrl().isEmpty()
                        ? null : progress.failedEntriesUrl(),
                "open", processingTime);
        knowledge.setLastFaqImportResult(FaqChunkMetadata.JSON.valueToTree(result));
        knowledge.setUpdatedAt(OffsetDateTime.now());
        knowledgeMapper.updateById(knowledge);
        log.info("Saved FAQ import result to database: knowledge_id={}, task={}, total={}, success={}, failed={}",
                job.knowledgeId(), job.taskId(), originalTotalEntries, progress.successCount(),
                progress.failedCount());
    }
    private static String buildImportResultMessage(String prefix, FaqImportProgress p) {
        List<String> parts = new ArrayList<>();
        parts.add(prefix);
        parts.add("上传 " + p.total() + " 条");
        if (p.mergedCount() > 0) {
            parts.add("新增 " + p.addedCount() + " 条");
            parts.add("合并更新 " + p.mergedCount() + " 条");
        } else {
            parts.add("成功 " + p.successCount() + " 条");
        }
        if (p.failedCount() > 0) {
            parts.add("失败 " + p.failedCount() + " 条");
        }
        if (p.partialFailedCount() > 0) {
            parts.add("部分失败 " + p.partialFailedCount() + " 条");
        }
        return String.join(" / ", parts);
    }

    // ── 不可变 record 的局部更新辅助 ─────────────────────────────────────

    private static FaqImportProgress withMessage(FaqImportProgress p, String message) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), message, p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    private static FaqImportProgress withError(FaqImportProgress p, String error) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), error, p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    private static FaqImportProgress withStatus(FaqImportProgress p, String status, int prog, int processed) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), status, prog,
                p.total(), processed, p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    /** 批次成功后累积 success_entries。 */
    private static FaqImportProgress withSuccessEntries(FaqImportProgress p,
                                                        List<FaqSuccessEntry> successEntries) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(),
                successEntries == null ? List.of() : new ArrayList<>(successEntries),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    private static FaqImportProgress withUpdatedNow(FaqImportProgress p) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(),
                Instant.now().getEpochSecond(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    private static FaqImportProgress withCounts(FaqImportProgress p, int successCount, int skippedCount,
                                                int addedCount) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), successCount, p.failedCount(), p.partialFailedCount(),
                skippedCount, p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                p.validEntryIndices(), p.mergeEntryIndices(), p.mergedCount(), addedCount,
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    private static FaqImportProgress withValidEntryIndices(FaqImportProgress p, List<Integer> valid) {
        return new FaqImportProgress(p.taskId(), p.kbId(), p.knowledgeId(), p.status(), p.progress(),
                p.total(), p.processed(), p.successCount(), p.failedCount(), p.partialFailedCount(),
                p.skippedCount(), p.failedEntries(), p.failedEntriesUrl(), p.successEntries(),
                valid, p.mergeEntryIndices(), p.mergedCount(), p.addedCount(),
                p.mergeDetails(), p.message(), p.error(), p.createdAt(), p.updatedAt(), p.dryRun(),
                p.importMode(), p.importedAt(), p.displayStatus(), p.processingTime());
    }

    /** 校验结果落进度（failedEntries/failedCount/partialFailedCount/mergeIndices/failedUrl 的部分覆写）。 */
    private static FaqImportProgress withValidationResults(FaqImportProgress p,
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
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
    }

    // ══════════════════ 导入任务（进程内） ═════════════════════════════

    /** 进程内导入任务的参数。 */
    record ImportJob(long tenantId, String taskId, String kbId, String knowledgeId,
                     String mode, boolean dryRun, long enqueuedAt, String instanceId,
                     List<FaqEntryPayload> entries) {
    }
}
