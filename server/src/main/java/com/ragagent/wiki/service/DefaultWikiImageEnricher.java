package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.searchutil.ImageInfoEnricher;
import org.springframework.stereotype.Component;

/**
 * {@link WikiImageEnricher} 的生产实现（对照 Go {@code reconstructEnrichedContent} 的
 * 富化段，wiki_ingest.go L2883-2909）：
 *
 * <ol>
 *   <li>收集文本 chunk 的 ID（调用方已按 {@code ChunkType=text} 过滤）；</li>
 *   <li>{@code CollectImageInfoByChunkIDs} + {@code MergeImageInfoJSON} 汇总图片信息；</li>
 *   <li>合并结果非空时 {@code EnrichContentWithImageInfo} 把
 *       {@code <image>/<image_ocr>/<image_caption>} 块内联进正文；否则原样返回
 *       （与 Go 的空图片信息路径一致）。</li>
 * </ol>
 *
 * <p>此前该接口无实现 bean，{@code WikiIngestService} 恒走
 * {@link WikiImageEnricher#identity}（= Go 的"空图片信息"退化路径），导致图片 /
 * 扫描件密集文档的 wiki 抽取为空。原料（{@link ImageInfoEnricher}）早已翻译并被
 * chatpipeline 与 KnowledgeService 使用，本类只补齐桥接，调用方零改动。</p>
 */
@Component
public class DefaultWikiImageEnricher implements WikiImageEnricher {

    private final ChunkRepository chunkRepository;

    public DefaultWikiImageEnricher(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Override
    public String enrich(String content, List<Chunk> textChunks, long tenantId) {
        if (textChunks == null || textChunks.isEmpty()) {
            return content;
        }
        List<String> textChunkIds = new ArrayList<>(textChunks.size());
        for (Chunk c : textChunks) {
            if (c != null && c.getId() != null && !c.getId().isEmpty()) {
                textChunkIds.add(c.getId());
            }
        }
        if (textChunkIds.isEmpty()) {
            // 对照 Go：len(textChunkIDs) == 0 → 纯文本重建结果
            return content;
        }
        Map<String, String> imageInfoMap = ImageInfoEnricher.collectImageInfoByChunkIds(
                chunkRepository::listChunksByParentIDs, tenantId, textChunkIds);
        String mergedImageInfo = ImageInfoEnricher.mergeImageInfoJson(imageInfoMap);
        if (mergedImageInfo == null || mergedImageInfo.isEmpty()) {
            // 对照 Go：mergedImageInfo == "" → 纯文本重建结果
            return content;
        }
        return ImageInfoEnricher.enrichContentWithImageInfo(content, mergedImageInfo);
    }
}
