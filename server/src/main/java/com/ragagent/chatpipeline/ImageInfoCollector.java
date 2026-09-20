package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.retrieval.domain.ImageInfo;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.searchutil.ImageInfoMatchUtil;

/**
 * 按命中 chunk 批量聚合子块 image_info（对照 Go searchutil/imageinfo.go 的
 * CollectImageInfoByChunkIDs + EnrichSearchResultsImageInfo，search_entity/merge 消费）。
 *
 * <p>聚合规则：以 image_ocr/image_caption 子块挂在的<b>文本父块 ID</b> 为聚合键，
 * URL（空则 OriginalURL）去重、后写覆盖 OCR/Caption 非空字段；嵌套 text 子块的
 * 孙辈图片折算到顶层文本 ID。产出 map 值是 Go json.Marshal 形态的数组 JSON
 * （复用 ImageInfoMatchUtil.marshalImageInfos 的既有对齐）。</p>
 */
public final class ImageInfoCollector {

    private ImageInfoCollector() {}

    /** 对照 CollectImageInfoByChunkIDs（无命中返回 null）。 */
    public static Map<String, String> collect(PipelinePorts.ChunkRepository chunkRepo,
                                              long tenantId, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return null;
        }

        List<Chunk> children;
        try {
            children = chunkRepo.listChunksByParentIds(tenantId, chunkIds);
        } catch (RuntimeException e) {
            return null;
        }
        if (children == null || children.isEmpty()) {
            return null;
        }

        Map<String, Map<String, ImageInfo>> aggMap = new LinkedHashMap<>();
        List<String> textChildIds = new ArrayList<>();
        Map<String, String> textToParent = new LinkedHashMap<>();

        for (Chunk child : children) {
            if (!child.isIsEnabled()) {
                continue;
            }
            switch (child.getChunkType()) {
                case ChunkTypes.IMAGE_OCR, ChunkTypes.IMAGE_CAPTION ->
                        addInfo(aggMap, child.getParentChunkId(), child);
                case ChunkTypes.TEXT -> {
                    textChildIds.add(child.getId());
                    textToParent.put(child.getId(), child.getParentChunkId());
                }
                default -> {}
            }
        }

        if (!textChildIds.isEmpty()) {
            List<Chunk> grandChildren;
            try {
                grandChildren = chunkRepo.listChunksByParentIds(tenantId, textChildIds);
            } catch (RuntimeException e) {
                grandChildren = null;
            }
            if (grandChildren != null) {
                for (Chunk gc : grandChildren) {
                    if (!gc.isIsEnabled()) {
                        continue;
                    }
                    if (!ChunkTypes.IMAGE_OCR.equals(gc.getChunkType())
                            && !ChunkTypes.IMAGE_CAPTION.equals(gc.getChunkType())) {
                        continue;
                    }
                    String parentTextID = textToParent.get(gc.getParentChunkId());
                    if (parentTextID != null) {
                        addInfo(aggMap, parentTextID, gc);
                    }
                }
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ImageInfo>> e : aggMap.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            out.put(e.getKey(), ImageInfoMatchUtil.marshalImageInfos(new ArrayList<>(e.getValue().values())));
        }
        return out;
    }

    /** 对照 addInfo：URL 去重 + 非空字段覆盖。 */
    private static void addInfo(Map<String, Map<String, ImageInfo>> aggMap, String targetID, Chunk child) {
        if (child.getImageInfo() == null || child.getImageInfo().isEmpty()) {
            return;
        }
        List<ImageInfo> infos = ImageInfoMatchUtil.parseInfos(child.getImageInfo());
        if (infos == null || infos.isEmpty()) {
            return;
        }
        Map<String, ImageInfo> agg = aggMap.computeIfAbsent(targetID, k -> new LinkedHashMap<>());
        for (ImageInfo info : infos) {
            String key = info.getUrl();
            if (key.isEmpty()) {
                key = info.getOriginalUrl();
            }
            if (key.isEmpty()) {
                continue;
            }
            ImageInfo existing = agg.get(key);
            if (existing == null) {
                agg.put(key, info);
            } else {
                if (!info.getOcrText().isEmpty()) {
                    existing.setOcrText(info.getOcrText());
                }
                if (!info.getCaption().isEmpty()) {
                    existing.setCaption(info.getCaption());
                }
            }
        }
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
