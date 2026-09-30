package com.ragagent.knowledge.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ragagent.common.pipeline.ChunkTypes;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.common.wiki.WikiImageMarkup;

/**
 * 图抽取的分块筛选。
 * <p>规则（逐条照抄）：</p>
 * <ol>
 *   <li>先记下所有<b>文本块</b>（{@code text}）；</li>
 *   <li>{@code image_caption} 一律跳过（caption 是图片的描述，不是实体的来源）；</li>
 *   <li>{@code image_ocr}：内容剥掉图片标记后没有真实文本 → 跳过；若有<B>可抽取文本的
 *       父文本块</b> → 也跳过（父块已覆盖，避免重复抽取）；</li>
 *   <li>{@code text}：内容剥掉图片标记后有真实文本 → 入选；</li>
 *   <li>其它类型（faq 等）不入图。</li>
 * </ol>
 */
public final class GraphChunkSelector {

    private GraphChunkSelector() {
    }

    /** 剥掉图片标记后是否还有散文。 */
    public static boolean chunkHasExtractableText(String content) {
        return !WikiImageMarkup.extractRealText(content).isEmpty();
    }

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
