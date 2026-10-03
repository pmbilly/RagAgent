package com.ragagent.chatpipeline.plugin;

import java.util.List;

import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelineEventType;
import com.ragagent.chatpipeline.PipelinePorts;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.common.pipeline.ChunkTypes;

/**
 * CHUNK_RERANK 附加插件：rerank 链之后的
 * wiki_page 分数加成（×1.3），确认至少一个检索目标确实是 wiki KB 才生效。
 */
public final class PluginWikiBoost implements Plugin {

    private static final double WIKI_BOOST_FACTOR = 1.3;

    private final PipelinePorts.KnowledgeBaseService kbService;

    public PluginWikiBoost(PipelinePorts.KnowledgeBaseService kbService) {
        this.kbService = kbService;
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
        if (rerankResult == null || rerankResult.isEmpty()) {
            return null;
        }

        // 快路径：结果集没有 wiki_page 块就不查 KB
        boolean hasWikiChunk = false;
        for (SearchResult r : rerankResult) {
            if (ChunkTypes.WIKI_PAGE.equals(r.getChunkType())) {
                hasWikiChunk = true;
                break;
            }
        }
        if (!hasWikiChunk) {
            return null;
        }

        // 确认至少一个目标是 wiki KB
        boolean hasWikiKB = false;
        for (SearchTarget target : chatManage.getSearchTargets()) {
            if (target == null || target.knowledgeBaseId().isEmpty()) {
                continue;
            }
            KnowledgeBase kb;
            try {
                kb = kbService.getKnowledgeBaseByIdOnly(target.knowledgeBaseId());
            } catch (RuntimeException e) {
                kb = null;
            }
            if (kb != null && kb.getIndexingStrategy().isWikiEnabled()) {
                hasWikiKB = true;
                break;
            }
        }
        if (!hasWikiKB) {
            return null;
        }

        int boostedCount = 0;
        for (SearchResult r : rerankResult) {
            if (ChunkTypes.WIKI_PAGE.equals(r.getChunkType())) {
                r.setScore(r.getScore() * WIKI_BOOST_FACTOR);
                boostedCount++;
            }
        }

        if (boostedCount > 0) {
            rerankResult.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        }
        return null;
    }
}
