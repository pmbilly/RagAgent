package com.ragagent.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ragagent.chatpipeline.ChunkTypes;
import com.ragagent.common.context.TracingContext;
import com.ragagent.knowledge.domain.Chunk;

/**
 * 导入后问题生成的**选块与分批**契约（对照 Go {@code knowledge_post_process.go:209-238}
 * 与 {@code enqueueQuestionGenerationTasks}:604-690）。
 *
 * <p>2026-09-25 走查：本仓此前只有手动 {@code regenerate} 路径，导入后的自动生成备案为"未翻"
 * ⇒ 刚导入的 KB 推荐问题恒为空（用户报障的第二半）。本测试钉住扇出侧的三条规则：</p>
 * <ol>
 *   <li>只取 <b>text</b> 且仍可抽取散文的分块（纯图片标记的块必须被剔除）；</li>
 *   <li>按 {@code StartAt} 排序（保证 prev/next 与整知识顺序一致）；</li>
 *   <li>按 20 切批 + 每批带窗口外的前/后邻块 id 作边界上下文。</li>
 * </ol>
 */
class QuestionBatchPlannerTest {

    private static Chunk chunk(String id, String type, String content, int startAt) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setChunkType(type);
        c.setContent(content);
        c.setStartAt(startAt);
        return c;
    }

    @Test
    void selectsOnlyTextChunksWithExtractableContentInStartAtOrder() {
        List<Chunk> in = List.of(
                chunk("c3", ChunkTypes.TEXT, "第三段", 300),
                chunk("img", ChunkTypes.IMAGE_CAPTION, "图注", 0),
                chunk("c1", ChunkTypes.TEXT, "第一段", 100),
                chunk("blank", ChunkTypes.TEXT, "![image](https://example.com/x.png)", 50),
                chunk("c2", ChunkTypes.TEXT, "第二段", 200));

        List<Chunk> out = QuestionBatchPlanner.selectQuestionChunks(in);

        assertThat(out).extracting(Chunk::getId).containsExactly("c1", "c2", "c3");
    }

    @Test
    void nonTextAndBlankContentAreExcluded() {
        List<Chunk> in = List.of(
                chunk("a", ChunkTypes.IMAGE_OCR, "OCR 文本", 10),
                chunk("b", ChunkTypes.PARENT_TEXT, "父块", 20),
                chunk("c", ChunkTypes.TEXT, "   ", 30),
                chunk("d", ChunkTypes.TEXT, "", 40));

        assertThat(QuestionBatchPlanner.selectQuestionChunks(in)).isEmpty();
    }

    @Test
    void batchesByTwentyWithBoundaryNeighborIds() {
        List<Chunk> in = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            in.add(chunk("c" + i, ChunkTypes.TEXT, "第 " + i + " 段", i * 10));
        }
        List<Chunk> selected = QuestionBatchPlanner.selectQuestionChunks(in);

        List<QuestionBatchPlanner.Batch> batches = QuestionBatchPlanner.planBatches(selected);

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0).chunkIds()).hasSize(20);
        assertThat(batches.get(1).chunkIds()).hasSize(20);
        assertThat(batches.get(2).chunkIds()).hasSize(5);
        // 批序与批内保序
        assertThat(batches.get(0).index()).isZero();
        assertThat(batches.get(0).chunkIds().get(0)).isEqualTo("c0");
        assertThat(batches.get(1).chunkIds().get(0)).isEqualTo("c20");
        assertThat(batches.get(2).chunkIds().get(0)).isEqualTo("c40");
        // 边界邻块：首批无 prev，末批无 next，中间批两侧都有（照 Go 的 payload 取法）
        assertThat(batches.get(0).prevChunkId()).isEmpty();
        assertThat(batches.get(0).nextChunkId()).isEqualTo("c20");
        assertThat(batches.get(1).prevChunkId()).isEqualTo("c19");
        assertThat(batches.get(1).nextChunkId()).isEqualTo("c40");
        assertThat(batches.get(2).prevChunkId()).isEqualTo("c39");
        assertThat(batches.get(2).nextChunkId()).isEmpty();
    }

    @Test
    void batchCountRoundsUp() {
        assertThat(QuestionBatchPlanner.batchCount(0)).isZero();
        assertThat(QuestionBatchPlanner.batchCount(1)).isEqualTo(1);
        assertThat(QuestionBatchPlanner.batchCount(20)).isEqualTo(1);
        assertThat(QuestionBatchPlanner.batchCount(21)).isEqualTo(2);
        assertThat(QuestionBatchPlanner.batchCount(45)).isEqualTo(3);
    }

    @Test
    void payloadRoundTripsThroughJsonWithTracingKeys() {
        QuestionBatchPayload p = QuestionBatchPayload.withTracing(10002, "kb-1", "kn-1", 3, "zh-CN", 2,
                List.of("c1", "c2"), 1, "c0", "c3",
                new TracingContext("tid", "obs", "tp", "uid", "sid"));

        String json = p.toJson();

        assertThat(json).contains("\"chunk_ids\":[\"c1\",\"c2\"]");
        assertThat(json).contains("\"batch_index\":1");
        assertThat(json).contains("\"prev_chunk_id\":\"c0\"");
        assertThat(json).contains("\"lf_trace_id\":\"tid\"");
        QuestionBatchPayload back = QuestionBatchPayload.fromJson(json);
        assertThat(back.tenantId()).isEqualTo(10002);
        assertThat(back.knowledgeBaseId()).isEqualTo("kb-1");
        assertThat(back.knowledgeId()).isEqualTo("kn-1");
        assertThat(back.questionCount()).isEqualTo(3);
        assertThat(back.chunkIds()).containsExactly("c1", "c2");
    }
}
