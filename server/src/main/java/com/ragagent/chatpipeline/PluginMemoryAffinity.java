package com.ragagent.chatpipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.retrieval.domain.SearchResult;

/**
 * CHUNK_RERANK 附加插件（对照 Go chat_pipeline/memory_affinity.go）：
 * "这个人反复引用哪些文档" 的亲和加权（×1.15 封顶、对数曲线饱和、最少命中 2 次、
 * 最多查 200 个知识 ID），加权后稳定重排。
 */
public final class PluginMemoryAffinity implements Plugin {

    private static final double AFFINITY_MAX_BOOST = 1.15;
    private static final double AFFINITY_FULL_HITS = 8.0;
    private static final int AFFINITY_MIN_HITS = 2; // 对照 types.MemoryDocAffinityMinHits
    private static final int AFFINITY_MAX_LOOKUP = 200;

    private final PipelinePorts.MemoryService memoryService;

    public PluginMemoryAffinity(PipelinePorts.MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.CHUNK_RERANK};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        PluginError err = next.next();
        if (err != null) {
            return err;
        }
        List<SearchResult> rerankResult = chatManage.getRerankResult();
        if (memoryService == null || rerankResult == null || rerankResult.isEmpty()) {
            return null;
        }

        List<String> ids = new java.util.ArrayList<>(rerankResult.size());
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (SearchResult r : rerankResult) {
            String id = r.getKnowledgeId();
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (seen.containsKey(id)) {
                continue;
            }
            seen.put(id, Boolean.TRUE);
            ids.add(id);
            if (ids.size() >= AFFINITY_MAX_LOOKUP) {
                break;
            }
        }
        if (ids.isEmpty()) {
            return null;
        }

        Map<String, Integer> affinity = memoryService.documentAffinity(ids);
        if (affinity == null || affinity.isEmpty()) {
            return null;
        }

        int boosted = 0;
        for (SearchResult r : rerankResult) {
            Integer hits = affinity.get(r.getKnowledgeId());
            int h = hits == null ? 0 : hits;
            if (h < AFFINITY_MIN_HITS) {
                continue;
            }
            r.setScore(r.getScore() * affinityFactor(h));
            boosted++;
        }
        if (boosted == 0) {
            return null;
        }

        rerankResult.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return null;
    }

    /** 对照 affinityFactor：log1p 曲线，8 次命中饱和到 ×1.15。 */
    static double affinityFactor(int hits) {
        if (hits < AFFINITY_MIN_HITS) {
            return 1;
        }
        double ratio = Math.log1p(hits) / Math.log1p(AFFINITY_FULL_HITS);
        if (ratio > 1) {
            ratio = 1;
        }
        return 1 + (AFFINITY_MAX_BOOST - 1) * ratio;
    }
}
