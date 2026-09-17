package com.ragagent.wiki.service;

import java.util.List;

import com.ragagent.knowledge.domain.Chunk;

/**
 * 把图片的 OCR / caption 文本内联进文档正文的可插拔端口（对照 Go
 * {@code reconstructEnrichedContent} 的富化段，wiki_ingest.go L2877-2909）。
 *
 * <h2>不富化会怎样</h2>
 * <p>（照搬 Go 注释）没有这一步，图片密集的文档（扫描 PDF、单独的 .jpg）到达 LLM 时
 * 只有一堆裸 Markdown 图片链接，导致抽取 / 摘要产出空结果或
 * 「no textual content was extractable」。</p>
 *
 * <h2>Go 的三步</h2>
 * <ol>
 *   <li>{@code content = reconstructContent(chunks)} —— 纯文本重建（已实现，见
 *       {@link WikiIngestService#reconstructContent}）；</li>
 *   <li>收集文本 chunk 的 ID，{@code searchutil.CollectImageInfoByChunkIDs} +
 *       {@code MergeImageInfoJSON} 汇总图片信息；</li>
 *   <li>{@code searchutil.EnrichContentWithImageInfo(content, merged)} 把
 *       {@code <image>/<image_ocr>/<image_caption>} 块内联进正文。</li>
 * </ol>
 *
 * <p><b>未接线时的退化行为</b>：{@code wikiService} 在 enrich 服务缺席时返回
 * {@link #identity}（第 1 步的结果）。这精确对应 Go 里两种"无需富化"的情形：
 * 没有文本 chunk（{@code len(textChunkIDs) == 0}）与合并后图片信息为空
 * （{@code mergedImageInfo == ""}）——此时 Go 也是返回纯文本重建结果。
 * 也就是说：<b>退化路径就是 Go 的空图片信息路径</b>，不是新引入的行为。</p>
 *
 * <p>searchutil 的 imageinfo 模块随知识库富化链路翻译；届时实现本接口并注册为
 * bean 即可生效。</p>
 */
public interface WikiImageEnricher {

    /**
     * 对照 Go {@code reconstructEnrichedContent} 的富化段。
     *
     * @param content      已经过 {@code reconstructContent} 的纯文本正文
     * @param textChunks   参与重建的文本 chunk（与 Go 传入的 {@code chunks} 一致，
     *                     调用方已按 ChunkType 过滤）
     * @param tenantId     租户 id（Go 用于 chunk 查询的租户隔离）
     * @return 富化后的正文；无图片信息时应当原样返回 {@code content}
     */
    String enrich(String content, List<Chunk> textChunks, long tenantId);

    /** 未接线时的等价实现：对应 Go 的"空图片信息"分支。 */
    static WikiImageEnricher identity() {
        return (content, textChunks, tenantId) -> content;
    }
}
