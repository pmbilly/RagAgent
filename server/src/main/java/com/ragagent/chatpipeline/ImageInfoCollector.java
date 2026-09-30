package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.retrieval.support.ImageInfoEnricher;

/**
 * 按命中 chunk 批量聚合子块 image_info（对照 Go searchutil/imageinfo.go 的
 * CollectImageInfoByChunkIDs + EnrichSearchResultsImageInfo，search_entity/merge 消费）。
 *
 * <p>聚合规则本体在 {@link ImageInfoEnricher#collectImageInfoByChunkIds}（knowledge 摘要
 * 管线共用同一实现）；本类只做端口 → 回调的适配。</p>
 */
public final class ImageInfoCollector {

    private ImageInfoCollector() {}

    /** 对照 CollectImageInfoByChunkIDs（无命中返回 null）。 */
    public static Map<String, String> collect(PipelinePorts.ChunkRepository chunkRepo,
                                              long tenantId, List<String> chunkIds) {
        return ImageInfoEnricher.collectImageInfoByChunkIds(
                chunkRepo::listChunksByParentIds, tenantId, chunkIds);
    }

    /** 对照 EnrichSearchResultsImageInfo：给无 image_info 的结果补齐。 */
    public static void enrichSearchResultsImageInfo(PipelinePorts.ChunkRepository chunkRepo,
                                                    long tenantId, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        List<String> chunkIDs = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (SearchResult r : results) {
            if (!r.getImageInfo().isEmpty()) {
                continue;
            }
            if (!seen.containsKey(r.getId())) {
                seen.put(r.getId(), Boolean.TRUE);
                chunkIDs.add(r.getId());
            }
        }
        if (chunkIDs.isEmpty()) {
            return;
        }
        Map<String, String> infoMap = collect(chunkRepo, tenantId, chunkIDs);
        if (infoMap == null || infoMap.isEmpty()) {
            return;
        }
        for (SearchResult r : results) {
            if (!r.getImageInfo().isEmpty()) {
                continue;
            }
            String merged = infoMap.get(r.getId());
            if (merged != null) {
                r.setImageInfo(merged);
            }
        }
    }
}
