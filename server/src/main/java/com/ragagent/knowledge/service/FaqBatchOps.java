package com.ragagent.knowledge.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.KnowledgeTag;
import com.ragagent.knowledge.dto.faq.FaqEntryPayload;
import com.ragagent.knowledge.dto.faq.FaqSuccessEntry;
import com.ragagent.knowledge.dto.faq.FaqFailedEntry;
import com.ragagent.knowledge.dto.faq.FaqImportProgress;
import com.ragagent.knowledge.dto.faq.FaqImportResult;
import com.ragagent.knowledge.dto.faq.FaqSuccessEntry;
import com.ragagent.knowledge.service.FaqImportService.ImportJob;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.model.domain.Model;
import com.ragagent.knowledge.storage.TenantFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FaqImportService 的批量导入簇：分批写入+进度回报、CSV 生成、结果落库与消息组装。
 */
final class FaqBatchOps {

    private static final Logger log = LoggerFactory.getLogger(FaqBatchOps.class);

    static final int FAQ_IMPORT_BATCH_SIZE = 50;

    private final FaqImportService service;

    FaqBatchOps(FaqImportService service) {
        this.service = service;
    }

    /**
     * 导入执行循环——
     * 2026-09-22 走查批接线（此前是「embedding runtime is not available」占位）：
     * 按 faqImportBatchSize(50) 分批 → 逐条 sanitize/resolveTagID/建 chunk → CreateChunks
     * → service.faqIndexWriter.indexFAQChunks(adjustStorage=true) → status=2 → 收集成功条目 → 进度落库；
     * 末尾 finalizeImport（completed 终态 + 结果落库 + replace 清未引用标签）。
     * 事务性回滚（失败直落 failed 终态，残留行由重导/replace 清理）——与 processImport
     * 的既有取舍同款。</p>
     */
    void executeImportBatches(ImportJob job, KnowledgeBase kb, Knowledge faqKnowledge,
                                      Model embeddingModel, FaqImportProgress progress) {
        List<Integer> valid = progress.validEntryIndices();
        int totalEntries = progress.total();
        int skippedCount = progress.skippedCount();
        int actualProcessed = skippedCount + progress.mergedCount();
        String indexMode = service.faqChunkCodec.faqIndexMode(kb);
        List<FaqSuccessEntry> successEntries = progress.successEntries() == null
                ? new ArrayList<>() : new ArrayList<>(progress.successEntries());

        for (int i = 0; i < valid.size(); i += FaqBatchOps.FAQ_IMPORT_BATCH_SIZE) {
            int end = Math.min(i + FaqBatchOps.FAQ_IMPORT_BATCH_SIZE, valid.size());
            List<Chunk> chunks = new ArrayList<>(end - i);
            for (int k = i; k < end; k++) {
                int entryIdx = valid.get(k); // dry-run 校验给出的原始条目下标
                FaqEntryPayload entry = job.entries().get(entryIdx);
                FaqChunkMetadata meta;
                try {
                    meta = service.faqGuard.sanitizeFAQEntryPayload(entry);
                } catch (RuntimeException e) {
                    service.markImportFailed(job, progress,
                            "FAQ import failed: failed to sanitize entry at index " + entryIdx
                                    + ": " + e.getMessage());
                    return;
                }
                String tagID;
                try {
                    tagID = service.faqGuard.resolveTagID(job.kbId(), entry);
                } catch (RuntimeException e) {
                    service.markImportFailed(job, progress,
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
                chunk.setContent(service.faqChunkCodec.buildFAQChunkContent(meta, indexMode));
                chunk.setIsEnabled(isEnabled);
                chunk.setChunkType("faq");
                chunk.setTagId(tagID);
                chunk.setStatus(1); // stored
                if (entry.id() != null && entry.id() > 0) {
                    chunk.setSeqId(entry.id());
                }
                service.faqChunkCodec.setFaqMetadata(chunk, meta);
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
                service.faqIndexWriter.createChunks(chunks);
            } catch (RuntimeException e) {
                service.markImportFailed(job, progress,
                        "FAQ import failed: failed to create chunks: " + e.getMessage());
                return;
            }
            try {
                service.faqIndexWriter.indexFAQChunks(kb, faqKnowledge, chunks, embeddingModel, true);
            } catch (RuntimeException e) {
                service.markImportFailed(job, progress,
                        "FAQ import failed: failed to index chunks: " + e.getMessage());
                return;
            }
            for (Chunk chunk : chunks) {
                chunk.setStatus(2); // indexed
            }
            try {
                service.faqChunkRepository.updateChunks(chunks);
            } catch (RuntimeException e) {
                service.markImportFailed(job, progress,
                        "FAQ import failed: failed to update chunks status: " + e.getMessage());
                return;
            }

            // 收集成功条目
            for (int k = 0; k < chunks.size(); k++) {
                Chunk chunk = chunks.get(k);
                FaqChunkMetadata meta = service.faqChunkCodec.sanitizedFaqMetadata(chunk);
                String standardQ = meta == null || meta.standardQuestion == null
                        ? "" : meta.standardQuestion;
                long tagID = 0;
                String tagName = "";
                if (chunk.getTagId() != null && !chunk.getTagId().isEmpty()) {
                    KnowledgeTag tag = service.tagMapper
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
            progress = FaqImportService.withStatus(progress, "processing", prog, actualProcessed);
            progress = FaqImportService.withMessage(progress,
                    "正在处理第 " + actualProcessed + "/" + totalEntries + " 条");
            progress = FaqImportService.withSuccessEntries(progress, successEntries);
            service.taskStore.saveProgress(progress);
        }

        progress = FaqImportService.withSuccessEntries(progress, successEntries);
        service.taskStore.saveProgress(progress);
        log.info("FAQ import task {}: all batches completed, processed: {}", job.taskId(), actualProcessed);
        service.finalizeImport(job, progress, totalEntries);
    }


    /** ：BOM + 8 列，
     *  落 {base}/{tenant}/exports/{name}_{UnixNano}.csv，返回 local:// URL。 */
    String generateFailedEntriesCsv(long tenantId, String taskId, List<FaqFailedEntry> failedEntries) {
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
        String base = service.storage.baseDir().toString();
        Path dir = Path.of(base, String.valueOf(tenantId), "exports");
        String unique = "faq_dryrun_failed_" + taskId + "_" + System.nanoTime() + ".csv";
        byte[] csv = buf.toString().getBytes(StandardCharsets.UTF_8);
        if (service.fileStorage != null) {
            // fileSvc.SaveBytes(..., temp=true) + GetFileURL → 云上落临时桶、回预签名 URL
            TenantFileStorage.Exported exported =
                    service.fileStorage.saveExportedBytesToUrl(tenantId, unique, csv, true);
            if (exported.handled()) {
                if (exported.url() == null) {
                    log.warn("FAQ import task {}: failed to generate failed entries CSV", taskId);
                }
                return exported.url();
            }
        }
        try {
            // 本地租户：既有落盘 + local:// 引用（契约样例 形态）
            Files.createDirectories(dir);
            Path target = dir.resolve(unique);
            Files.write(target, csv);
            return "local://" + tenantId + "/exports/" + unique;
        } catch (IOException e) {
            log.warn("FAQ import task {}: failed to generate failed entries CSV: {}", taskId, e.getMessage());
            return null;
        }
    }



    static String csvEscape(String s) {
        if (s == null) {
            s = "";
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }


    void saveImportResultToDatabase(ImportJob job, FaqImportProgress progress, int originalTotalEntries) {
        Knowledge knowledge = service.knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
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
        service.knowledgeMapper.updateById(knowledge);
        log.info("Saved FAQ import result to database: knowledge_id={}, task={}, total={}, success={}, failed={}",
                job.knowledgeId(), job.taskId(), originalTotalEntries, progress.successCount(),
                progress.failedCount());
    }


    static String buildImportResultMessage(String prefix, FaqImportProgress p) {
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


}
