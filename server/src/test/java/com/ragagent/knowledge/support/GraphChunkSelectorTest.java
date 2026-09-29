package com.ragagent.knowledge.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.chatpipeline.ChunkTypes;
import com.ragagent.common.context.TracingContext;
import com.ragagent.knowledge.domain.Chunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.ragagent.knowledge.domain.ExtractChunkPayload;

/**
 * 图抽取分块筛选测试（对照 Go {@code selectGraphChunks} / {@code chunkHasExtractableText}）
 * + 任务载荷的 JSON 形态（omitempty 与平铺 {@code lf_*} 键）。
 */
class GraphChunkSelectorTest {

    private static Chunk chunk(String id, String type, String content) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setChunkType(type);
        c.setContent(content);
        return c;
    }

    @Test
    @DisplayName("chunkHasExtractableText：剥掉图片标记后仍有散文才算可抽取")
    void extractableText() {
        assertTrue(GraphChunkSelector.chunkHasExtractableText("张三在腾讯工作"));
        assertFalse(GraphChunkSelector.chunkHasExtractableText("![img](http://x/y.png)"));
        assertFalse(GraphChunkSelector.chunkHasExtractableText("<image_original>![a](b.png)</image_original>"));
        assertFalse(GraphChunkSelector.chunkHasExtractableText("   "));
    }

    @Test
    @DisplayName("筛选规则：caption 全跳、OCR 有可抽取父文本则跳、纯图片文本跳、序不变")
    void selectionRules() {
        Chunk t1 = chunk("t1", ChunkTypes.TEXT, "张三在腾讯工作");
        Chunk t2 = chunk("t2", ChunkTypes.TEXT, "![only-image](http://x/y.png)");   // 无散文 → 跳
        Chunk cap = chunk("c1", ChunkTypes.IMAGE_CAPTION, "一张流程图");
        Chunk ocrOrphan = chunk("o1", ChunkTypes.IMAGE_OCR, "OCR：发票编号 123");
        ocrOrphan.setParentChunkId("t2");   // 父块无散文 → 不拦（入选）
        Chunk ocrCovered = chunk("o2", ChunkTypes.IMAGE_OCR, "OCR：合同编号 9");
        ocrCovered.setParentChunkId("t1");  // 父块有散文 → 跳过
        Chunk ocrEmpty = chunk("o3", ChunkTypes.IMAGE_OCR, "![img](x.png)");
        Chunk faq = chunk("f1", ChunkTypes.FAQ, "问：你好 答：你好");

        List<Chunk> out = GraphChunkSelector.selectGraphChunks(
                List.of(t1, t2, cap, ocrOrphan, ocrCovered, ocrEmpty, faq));

        assertEquals(List.of("t1", "o1"), out.stream().map(Chunk::getId).toList());
    }

    @Test
    @DisplayName("空输入与 null 项安全")
    void nullSafety() {
        assertEquals(List.of(), GraphChunkSelector.selectGraphChunks(null));
        assertEquals(List.of(), GraphChunkSelector.selectGraphChunks(List.of()));
        assertEquals(1, GraphChunkSelector.selectGraphChunks(
                java.util.Arrays.asList(null, chunk("t", ChunkTypes.TEXT, "有文本"))).size());
    }

    @Test
    @DisplayName("载荷 JSON：业务键 + 平铺 lf_* 键；omitempty 键缺省时不输出")
    void payloadJsonShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        ExtractChunkPayload bare = new ExtractChunkPayload(7L, "ck-1", "model-1", "", 0, 0);
        assertEquals("{\"tenantId\":7,\"chunkId\":\"ck-1\",\"modelId\":\"model-1\"}",
                mapper.writeValueAsString(bare));

        ExtractChunkPayload withTracing = ExtractChunkPayload.withTracing(7L, "ck-1", "model-1",
                "kn-9", 2, 3, new TracingContext("tr", "", "00-tr-sp-01", "u", "r"));
        String json = mapper.writeValueAsString(withTracing);
        assertTrue(json.contains("\"knowledgeId\":\"kn-9\""));
        assertTrue(json.contains("\"attempt\":2"));
        assertTrue(json.contains("\"chunkIndex\":3"));
        assertTrue(json.contains("\"lfTraceparent\":\"00-tr-sp-01\""));
        assertTrue(json.contains("\"lfUserId\":\"u\""));

        ExtractChunkPayload parsed = ExtractChunkPayload.fromJson(json + ",\"unknown\":1}");
        assertEquals("kn-9", parsed.knowledgeId());
        assertEquals(2, parsed.attempt());
        assertEquals(3, parsed.chunkIndex());
        assertEquals("00-tr-sp-01", parsed.tracing().traceparent());

        // toJson/fromJson 往返（列队真实形态）
        assertEquals("ck-1", ExtractChunkPayload.fromJson(withTracing.toJson()).chunkId());
    }
}
