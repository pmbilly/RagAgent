package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.chatpipeline.ChunkTypes;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.wiki.service.WikiImageMarkup;

/**
 * 图抽取的分块筛选（对照 Go {@code selectGraphChunks} + {@code chunkHasExtractableText}，
 * knowledge_post_process.go L696-733）。
 *
 * <p>规则（逐条照抄）：</p>
 * <ol>
 *   <li>先记下所有<b>文本块</b>（{@code text}）；</li>
 *   <li>{@code image_caption} 一律跳过（caption 是图片的描述，不是实体的来源）；</li>
 *   <li>{@code image_ocr}：内容剥掉图片标记后没有真实文本 → 跳过；若有<B>可抽取文本的
 *       父文本块</b> → 也跳过（父块已覆盖，避免重复抽取）；</li>
 *   <li>{@code text}：内容剥掉图片标记后有真实文本 → 入选；</li>
 *   <li>其它类型（faq 等）不入图。</li>
 * </ol>
 *
 * <p>返回顺序 = 传入顺序（Go 同），因此 {@code chunk_index} 与文本序一致。</p>
 */
public final class GraphChunkSelector {

    private GraphChunkSelector() {
    }

    /** 对照 {@code chunkHasExtractableText}：剥掉图片标记后是否还有散文。 */
    public static boolean chunkHasExtractableText(String content) {
        return !WikiImageMarkup.extractRealText(content).isEmpty();
    }

    /** 对照 {@code selectGraphChunks}。 */
    public static List<Chunk> selectGraphChunks(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Chunk> textById = new LinkedHashMap<>();
        for (Chunk c : chunks) {
            if (c != null && ChunkTypes.TEXT.equals(c.getChunkType())) {
                textById.put(c.getId(), c);
            }
        }
        List<Chunk> out = new ArrayList<>();
        for (Chunk c : chunks) {
            if (c == null) {
                continue;
            }
            String type = c.getChunkType();
            if (ChunkTypes.IMAGE_CAPTION.equals(type)) {
                continue;
            }
            if (ChunkTypes.IMAGE_OCR.equals(type)) {
                if (!chunkHasExtractableText(c.getContent())) {
                    continue;
                }
                Chunk parent = textById.get(c.getParentChunkId());
                if (parent != null && chunkHasExtractableText(parent.getContent())) {
                    continue;
                }
                out.add(c);
                continue;
            }
            if (ChunkTypes.TEXT.equals(type)) {
                if (chunkHasExtractableText(c.getContent())) {
                    out.add(c);
                }
            }
        }
        return out;
    }
}
