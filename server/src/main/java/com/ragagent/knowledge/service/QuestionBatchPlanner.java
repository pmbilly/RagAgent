package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.ragagent.chatpipeline.ChunkTypes;
import com.ragagent.knowledge.domain.Chunk;

/**
 * 问题生成的**选块与分批**。
 * <ol>
 *   <li>只取 {@code ChunkTypeText} **且**仍可抽取散文的分块（{@code chunkHasExtractableText}——
 *       纯链接/纯图片标记的文本块被跳过：LLM 没东西可问，且会照抄 few-shot 示例）；</li>
 *   <li>按 {@code StartAt} 排序——保证逐块上下文的 prev/next 与整知识循环同序；</li>
 *   <li>按 {@link #BATCH_SIZE} 切批，<b>批间不重叠</b>；每批带窗口外的前/后邻块 id 作边界上下文。</li>
 * </ol>
 * post-process 大函数里，只能靠实录间接覆盖）。</p>
 */
public final class QuestionBatchPlanner {
    public static final int BATCH_SIZE = 20;

    private QuestionBatchPlanner() {
    }

    public static List<Chunk> selectQuestionChunks(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Chunk> out = new ArrayList<>();
        for (Chunk c : chunks) {
            if (c == null || !ChunkTypes.TEXT.equals(c.getChunkType())) {
                continue;
            }
            if (!GraphChunkSelector.chunkHasExtractableText(c.getContent())) {
                continue;
            }
            out.add(c);
        }
        out.sort(Comparator.comparingInt(Chunk::getStartAt));
        return out;
    }

    /** 一批的投递形态：批内 chunk id（保序）+ 窗口边界邻块 id。 */
    public record Batch(int index, List<String> chunkIds, String prevChunkId, String nextChunkId) {
    }

    /** = 0; start < total; start += batchSize} 与边界 id 取法。 */
    public static List<Batch> planBatches(List<Chunk> selected) {
        return planBatches(selected, BATCH_SIZE);
    }

    static List<Batch> planBatches(List<Chunk> selected, int batchSize) {
        if (selected == null || selected.isEmpty() || batchSize <= 0) {
            return List.of();
        }
        List<Batch> out = new ArrayList<>();
        int total = selected.size();
        int index = 0;
        for (int start = 0; start < total; start += batchSize) {
            int end = Math.min(start + batchSize, total);
            List<String> ids = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                ids.add(selected.get(i).getId());
            }
            String prev = start > 0 ? selected.get(start - 1).getId() : "";
            String next = end < total ? selected.get(end).getId() : "";
            out.add(new Batch(index++, ids, prev, next));
        }
        return out;
    }
    public static int batchCount(int chunkCount) {
        if (chunkCount <= 0) {
            return 0;
        }
        return (chunkCount + BATCH_SIZE - 1) / BATCH_SIZE;
    }
}
