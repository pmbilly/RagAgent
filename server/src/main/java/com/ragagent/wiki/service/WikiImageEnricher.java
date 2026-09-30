package com.ragagent.wiki.service;

import java.util.List;

import com.ragagent.knowledge.domain.Chunk;

/**
 * 把图片的 OCR / caption 文本内联进文档正文的可插拔端口。
 *
 * <h2>不富化会怎样</h2>
 * <p>没有这一步，图片密集的文档（扫描 PDF、单独的 .jpg）到达 LLM 时
 * 只有一堆裸 Markdown 图片链接，导致抽取 / 摘要产出空结果或
 * 「no textual content was extractable」。</p>
 *
 * <h2>富化三步</h2>
 * <ol>
 *   <li>{@code content = reconstructContent(chunks)} —— 纯文本重建（见
 *       {@link WikiIngestService#reconstructContent}）；</li>
 *   <li>收集文本 chunk 的 ID，按 chunk 汇总图片信息；</li>
 *   <li>把 {@code <image>/<image_ocr>/<image_caption>} 块内联进正文。</li>
 * </ol>
 *
 * <p><b>未接线时的退化行为</b>：{@code wikiService} 在 enrich 服务缺席时返回
 * {@link #identity}（第 1 步的结果）。这与"无需富化"的两种既有情形一致：
 * 没有文本 chunk、或合并后图片信息为空——两种情形同样返回纯文本重建结果。
 * 也就是说：<b>退化路径就是空图片信息路径</b>，不是新引入的行为。</p>
 *
 * <p>生产实现见 {@link DefaultWikiImageEnricher}；本接口保留 {@link #identity} 作为
 * 显式退化形态供测试/裁剪装配使用。</p>
 */
public interface WikiImageEnricher {

    /**
     * 富化正文。
     *
     * @param content      已经过 {@code reconstructContent} 的纯文本正文
     * @param textChunks   参与重建的文本 chunk（调用方已按 ChunkType 过滤）
     * @param tenantId     租户 id（chunk 查询的租户隔离）
     * @return 富化后的正文；无图片信息时应当原样返回 {@code content}
     */
    String enrich(String content, List<Chunk> textChunks, long tenantId);

    /** 未接线时的等价实现：等价于"空图片信息"分支。 */
    static WikiImageEnricher identity() {
        return (content, textChunks, tenantId) -> content;
    }
}
