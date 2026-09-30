package com.ragagent.wiki.service.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.common.wiki.SlugUpdate;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.wiki.service.WikiImageEnricher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * wiki 摄取的文档支撑面：源知识存活性判定（墓碑快路径 + 数据库回落）、
 * reduce 期的存活过滤、以及从 chunk 重建正文（含 OCR/caption 富化）。
 */
final class WikiIngestContentSupport {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestContentSupport.class);

    private final WikiIngestService service;

    WikiIngestContentSupport(WikiIngestService service) {
        this.service = service;
    }

    /**
     * 知识文档是否已删除、或正在删除中。
     *
     * <p>先查墓碑快路径，再回落数据库。{@code getKnowledgeByIDOnly} 返回 null 同样算
     * "已消失"：仓储层查询会过滤软删行，因此软删的知识在这里表现为
     * "查不到"——正是我们要的。</p>
     */
    boolean isKnowledgeGone(String kbId, String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return true;
        }
        WikiDeletedTombstoneStore tombstones = service.tombstoneStore.getIfAvailable();
        if (tombstones != null && tombstones.exists(kbId, knowledgeId)) {
            return true;
        }
        KnowledgeMapper mapper = service.knowledgeMapper.getIfAvailable();
        if (mapper == null) {
            // 无知识仓储可查（装配裁剪）—— 无法判定，保守地当作"还在"
            return false;
        }
        Knowledge kn;
        try {
            kn = mapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getId, knowledgeId)
                    .isNull(Knowledge::getDeletedAt));
        } catch (Exception e) {
            return true;
        }
        if (kn == null) {
            return true;
        }
        return Knowledge.PARSE_DELETING.equals(kn.getParseStatus())
                || Knowledge.PARSE_CANCELLED.equals(kn.getParseStatus());
    }

    /**
     * 丢弃那些源知识在 Map 阶段结束后
     * 已被删除的新增 / 摘要更新。<b>retract 更新被保留</b>，页面因此仍能得到清理。
     * 按知识缓存判定结果，避免单个 reduce slug 携带同一文档的多个更新时反复打库。
     */
    List<SlugUpdate> filterLiveUpdates(String kbId, List<SlugUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return updates;
        }
        Map<String, Boolean> goneCache = new java.util.HashMap<>();
        List<SlugUpdate> filtered = new ArrayList<>(updates.size());
        int dropped = 0;
        for (SlugUpdate u : updates) {
            if (u.isRetractType()) {
                filtered.add(u);
                continue;
            }
            String kid = u.getKnowledgeId();
            boolean gone = false;
            if (!kid.isEmpty()) {
                gone = goneCache.computeIfAbsent(kid, k -> isKnowledgeGone(kbId, k));
            }
            if (gone) {
                dropped++;
                continue;
            }
            filtered.add(u);
        }
        if (dropped > 0) {
            log.info("wiki ingest: reduce dropped {} updates for deleted knowledge(s)", dropped);
        }
        return filtered;
    }

    /**
     * 从 chunk 重建文档正文。
     *
     * <p><b>只拼接文本类型的 chunk</b>——图片 OCR / caption 信息存在
     * {@code image_ocr} / {@code image_caption} 子 chunk 上，不在父文本 chunk 的
     * ImageInfo 字段里。需要完整富化正文（内联 OCR / caption）的调用方应改用
     * {@link #reconstructEnrichedContent}。</p>
     *
     * <p>重叠去重与排序统一交给 {@link WikiChunkMerge}（按文本匹配，
     * 兼容补写表头 / HTML 实体）。</p>
     */
    static String reconstructContent(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        List<Chunk> textChunks = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            if (c == null) {
                continue;
            }
            String type = c.getChunkType();
            if (WikiIngestService.CHUNK_TYPE_TEXT.equals(type) || type == null || type.isEmpty()) {
                textChunks.add(c);
            }
        }
        return WikiChunkMerge.mergeTextChunks(textChunks, "\n");
    }

    /**
     * 重建正文并把
     * 图片的 OCR / caption 文本内联进来。
     *
     * <p>没有图片信息时返回纯文本重建结果——与富化器缺席时的退化路径一致。</p>
     */
    String reconstructEnrichedContent(List<Chunk> chunks, long tenantId) {
        String content = reconstructContent(chunks);
        if (chunks == null || chunks.isEmpty() || content.isEmpty()) {
            return content;
        }
        List<Chunk> textChunks = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            if (c == null) {
                continue;
            }
            String type = c.getChunkType();
            if (WikiIngestService.CHUNK_TYPE_TEXT.equals(type) || type == null || type.isEmpty()) {
                textChunks.add(c);
            }
        }
        if (textChunks.isEmpty()) {
            return content;
        }
        WikiImageEnricher enricher = service.imageEnricher.getIfAvailable(WikiImageEnricher::identity);
        return enricher.enrich(content, textChunks, tenantId);
    }
}
