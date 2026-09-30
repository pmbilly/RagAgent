package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.dto.faq.FaqEntryPayload;
import com.ragagent.knowledge.dto.faq.FaqFailedEntry;
import com.ragagent.knowledge.dto.faq.FaqImportProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FaqImportService 的校验簇：append/replace 两种模式的逐条校验（重复检测、
 * question 冲突、tag 校验）与基础 payload 校验、失败条目工厂。
 */
final class FaqValidateOps {

    private static final Logger log = LoggerFactory.getLogger(FaqValidateOps.class);

    private final FaqImportService service;

    FaqValidateOps(FaqImportService service) {
        this.service = service;
    }

    List<Integer> validateAppendMode(long tenantId, String kbId,
                                             List<FaqEntryPayload> entries,
                                             FaqImportProgress progress) {
        List<Chunk> existingChunks = service.faqChunkRepository
                .listAllFAQChunksWithMetadataByKnowledgeBaseId(tenantId, kbId);

        Map<String, Chunk> existingStdQToChunk = new LinkedHashMap<>();
        Map<String, String> existingQuestionToChunkID = new LinkedHashMap<>();
        Map<String, Set<String>> existingChunkQuestions = new LinkedHashMap<>();
        Map<String, String> existingChunkIDToStdQ = new LinkedHashMap<>();
        for (Chunk chunk : existingChunks) {
            FaqChunkMetadata meta = service.faqChunkCodec.sanitizedFaqMetadata(chunk);
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
            FaqChunkMetadata existingMeta = service.faqChunkCodec.sanitizedFaqMetadata(mergeChunk);
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
        progress = FaqImportService.withValidationResults(progress, failedEntries, failedCount, partialFailedCount,
                mergeIndices, null);
        service.taskStore.saveProgress(progress);
        log.info("Append mode validation completed: total={}, valid={}, merge_candidates={}, failed={}, partial_failed={}",
                entries.size(), validIndicesAfterStdQ.size(), mergeCount, progress.failedCount(),
                progress.partialFailedCount());
        return validIndicesAfterStdQ;
    }


    List<Integer> validateReplaceMode(List<FaqEntryPayload> entries,
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
        progress = FaqImportService.withValidationResults(progress, failedEntries, failedCount, partialFailedCount,
                null, null);
        service.taskStore.saveProgress(progress);
        return validIndicesAfterStdQ;
    }


    static String validateEntryPayloadBasic(FaqEntryPayload entry) {
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


    static List<String> unionStrings(List<String> a, List<String> b) {
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


    static FaqFailedEntry failedEntry(int idx, String reason, FaqEntryPayload entry,
                                              String failureType) {
        boolean answerAll = FaqChunkMetadata.ANSWER_STRATEGY_ALL.equals(entry.answerStrategy());
        boolean isDisabled = entry.enabled() != null && !entry.enabled();
        return new FaqFailedEntry(idx, reason, failureType, false,
                entry.tagName(), FaqChunkMetadata.trimSpace(entry.standardQuestion()),
                entry.similarQuestions(), entry.negativeQuestions(), entry.answers(),
                answerAll, isDisabled, null, null);
    }


    static FaqFailedEntry partialFailedEntry(int idx, FaqEntryPayload entry,
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


}
