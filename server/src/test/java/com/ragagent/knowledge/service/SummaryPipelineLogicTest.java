package com.ragagent.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.retrieval.domain.ImageInfo;
import com.ragagent.searchutil.ImageInfoEnricher;
import org.junit.jupiter.api.Test;
import com.ragagent.searchutil.ChunkSearchUtil;
import com.ragagent.knowledge.support.KnowledgeIndexContent;

/**
 * 摘要管线与向量索引的确定性纯逻辑（2026-09-22 走查批补全 regenerate-summary 时新增）。
 *
 * <p>覆盖：{@link Chunk#embeddingContent()}（Go Chunk.EmbeddingContent）、
 * {@link KnowledgeIndexContent#build}（Go buildKnowledgeIndexContent）、
 * {@link ImageInfoEnricher#collectImageInfoByChunkIds}（Go CollectImageInfoByChunkIDs
 * 的两级解析/去重/容错）、{@link ChunkSearchUtil#generatedQuestionSourceId}
 * （Go GeneratedQuestionSourceID 的 64 字节折叠）。</p>
 *
 * <p>不依赖 Spring 上下文：LLM 主链路（doRegenerate 状态机 / summary chunk 维护 /
 * 向量重建）已在真 PG 环境实测验证（走查文档 regenerate-summary 200 + completed +
 * summary chunk 复用 + source_id 形态对齐），此处只钉纯函数。</p>
 */
class SummaryPipelineLogicTest {

    // ── Chunk.embeddingContent ─────────────────────────────────────────────

    @Test
    void embeddingContentPrependsContextHeaderAndTrimsBody() {
        Chunk c = new Chunk();
        c.setContent("  body text  ");
        c.setContextHeader("一级标题 > 二级标题");
        assertThat(c.embeddingContent()).isEqualTo("一级标题 > 二级标题\n\nbody text");
    }

    @Test
    void embeddingContentTrimsBodyWhenNoHeader() {
        Chunk c = new Chunk();
        c.setContent("\n\nplain body\n");
        assertThat(c.embeddingContent()).isEqualTo("plain body");
    }

    // ── KnowledgeIndexContent.build ────────────────────────────────────────

    @Test
    void indexContentPrependsTrimmedTitle() {
        Knowledge k = new Knowledge();
        k.setTitle(" 需求总览 ");
        assertThat(KnowledgeIndexContent.build(k, "正文")).isEqualTo("需求总览\n正文");
    }

    @Test
    void indexContentKeepsContentWhenTitleBlankOrKnowledgeNull() {
        Knowledge k = new Knowledge();
        k.setTitle("   ");
        assertThat(KnowledgeIndexContent.build(k, "正文")).isEqualTo("正文");
        assertThat(KnowledgeIndexContent.build(null, "正文")).isEqualTo("正文");
    }

    // ── ImageInfoEnricher.collectImageInfoByChunkIds ───────────────────────

    private static Chunk chunk(String id, String parentId, String type,
                               boolean enabled, String imageInfoJson) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setParentChunkId(parentId);
        c.setChunkType(type);
        c.setIsEnabled(enabled);
        c.setImageInfo(imageInfoJson);
        return c;
    }

    private static String imageJson(String url, String caption, String ocr) {
        ImageInfo info = new ImageInfo();
        info.setUrl(url);
        info.setCaption(caption);
        info.setOcrText(ocr);
        return com.ragagent.searchutil.ImageInfoMatchUtil.marshalImageInfos(List.of(info));
    }

    @Test
    void collectResolvesDirectChildrenAndGrandChildren() {
        // 第一级：chunkA 的图片子块；parentText 的文本子块（其下还有图片孙辈）
        List<Chunk> firstLevel = List.of(
                chunk("img-1", "chunkA", "image_ocr", true, imageJson("u1", "", "ocr-1")),
                chunk("text-child", "parentText", "text", true, ""));
        List<Chunk> secondLevel = List.of(
                chunk("img-2", "text-child", "image_caption", true, imageJson("u2", "cap-2", "")));

        BiFunction<Long, List<String>, List<Chunk>> lister = (tenantId, ids) ->
                ids.contains("text-child") ? secondLevel : firstLevel;

        Map<String, String> out = ImageInfoEnricher.collectImageInfoByChunkIds(
                lister, 7L, List.of("chunkA", "parentText"));

        assertThat(out).containsOnlyKeys("chunkA", "parentText");
        assertThat(out.get("chunkA")).contains("u1").contains("ocr-1");
        // 孙辈图片折算到顶层文本父块 ID（两级解析契约）
        assertThat(out.get("parentText")).contains("u2").contains("cap-2");
    }

    @Test
    void collectSkipsDisabledChildren() {
        List<Chunk> children = List.of(
                chunk("img-1", "chunkA", "image_ocr", false, imageJson("u1", "", "ocr-1")));
        Map<String, String> out = ImageInfoEnricher.collectImageInfoByChunkIds(
                (t, ids) -> children, 7L, List.of("chunkA"));
        assertThat(out).isEmpty();
    }

    @Test
    void collectMergesByUrlKeepingNonEmptyFields() {
        // 同一 URL 的 OCR 与 caption 来自两个子块 → 合并为一条（后写覆盖非空字段）
        List<Chunk> children = List.of(
                chunk("img-1", "chunkA", "image_ocr", true, imageJson("same", "", "ocr-x")),
                chunk("img-2", "chunkA", "image_caption", true, imageJson("same", "cap-x", "")));
        Map<String, String> out = ImageInfoEnricher.collectImageInfoByChunkIds(
                (t, ids) -> children, 7L, List.of("chunkA"));
        String merged = out.get("chunkA");
        assertThat(merged).contains("ocr-x").contains("cap-x");
        // 合并为一条（url 键只出现一次，同一 URL 不重复成两行）
        assertThat(merged.split("\"url\"", -1).length - 1).isEqualTo(1);
    }

    @Test
    void collectReturnsNullWhenNoIdsNoChildrenOrRepoError() {
        assertThat(ImageInfoEnricher.collectImageInfoByChunkIds((t, ids) -> List.of(), 7L, List.of()))
                .isNull();
        assertThat(ImageInfoEnricher.collectImageInfoByChunkIds((t, ids) -> List.of(), 7L,
                List.of("chunkA"))).isNull();
        assertThat(ImageInfoEnricher.collectImageInfoByChunkIds((t, ids) -> {
            throw new RuntimeException("db down");
        }, 7L, List.of("chunkA"))).isNull();
    }

    // ── generatedQuestionSourceId（Go faq.go L41-50） ──────────────────────

    @Test
    void questionSourceIdConcatenatesWhenShort() {
        assertThat(ChunkSearchUtil.generatedQuestionSourceId("chunk-1", "q-1"))
                .isEqualTo("chunk-1-q-1");
    }

    @Test
    void questionSourceIdHashesWhenOver64Bytes() {
        String chunkId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"; // 36
        String questionId = "11111111-2222-3333-4444-555555555555"; // 36 → 73 > 64
        String out = ChunkSearchUtil.generatedQuestionSourceId(chunkId, questionId);
        assertThat(out).startsWith(chunkId + "-q");
        assertThat(out).hasSize(chunkId.length() + 2 + 24); // "-q" + sha256 前 12 字节 hex
        assertThat(out.substring(chunkId.length() + 2)).matches("[0-9a-f]{24}");
        // 同样的 questionID 恒得同一折叠值（Go 语义：历史索引行仍可寻址）
        assertThat(ChunkSearchUtil.generatedQuestionSourceId(chunkId, questionId)).isEqualTo(out);
    }

    @Test
    void questionSourceIdHashIsDeterministicForDifferentChunks() {
        String q = "11111111-2222-3333-4444-555555555555";
        String a = ChunkSearchUtil.generatedQuestionSourceId(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", q);
        String b = ChunkSearchUtil.generatedQuestionSourceId(
                "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee", q);
        // 后缀（questionID 的摘要）相同、前缀（chunkID）不同
        assertThat(a.substring(a.length() - 24)).isEqualTo(b.substring(b.length() - 24));
    }

    // 空列表防御（对照 Go len==0 分支在调用方的行为）
    @Test
    void collectAcceptsNullIds() {
        assertThat(ImageInfoEnricher.collectImageInfoByChunkIds(
                (t, ids) -> new ArrayList<>(), 7L, null)).isNull();
    }
}
