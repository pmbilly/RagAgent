package com.ragagent.wiki.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.wiki.domain.WikiPageLite;

/**
 * 实体/概念去重与身份收敛的<b>可插拔端口</b>（对照 Go
 * internal/application/service/wiki_ingest_dedup.go，732 行）。
 *
 * <h2>为什么是端口</h2>
 * <p>Go 的 {@code deduplicateExtractedBatch}（<b>在</b>本任务范围，wiki_ingest.go
 * L2306-2502）在关键路径上调用五个定义在 {@code wiki_ingest_dedup.go}
 * （<b>不在</b>本任务范围）的函数：</p>
 * <ul>
 *   <li>{@code stabilizeExtractedIdentities} —— 身份收敛，防止同类型同标题被建出多页；</li>
 *   <li>{@code attachExactIdentityPages} —— 把精确同名的既有页塞进候选集；</li>
 *   <li>{@code collectExactIdentityTargets} —— 确定性的精确同名合并目标；</li>
 *   <li>{@code dedupMergeRejectReason} —— 模型无关的合并合法性校验；</li>
 *   <li>{@code dedupCandidateTopK}（常量 5）—— 逐项 trigram 相似候选的 top-K。</li>
 * </ul>
 * <p>Java 侧把这五个抽成端口，让 ingest 主入口可以独立编译、独立测试；
 * dedup 的翻译任务实现本接口并注册为 bean 即可全部生效，
 * <b>无需改动本任务产出的任何文件</b>。</p>
 *
 * <h2>⚠️ 未接线时的退化行为（必须知道）</h2>
 * <p>{@code deduplicateExtractedBatch} 在<b>没有</b>本端口实现时会：</p>
 * <ol>
 *   <li>跳过整个 LLM 去重调用（省一次模型调用，但也就没有了跨次抽取的合并）；</li>
 *   <li>把 {@code stabilizeExtractedIdentities} 当作恒等函数——<b>直接返回原列表</b>。
 *       这意味着 Go 的"同类型同标题必须收敛到同一 slug"这一保证<b>暂时失效</b>，
 *       并发批次或同一批次内的同标题项可能落到不同 slug 上。</li>
 * </ol>
 * <p>这是刻意的、可见的降级（与 {@code WikiCrossLinker.Noop} 同一模式）：
 * 宁可让主入口完整可用并留下明确的接线点，也不在半成品状态假装去重已经工作。
 * <b>接线前不要把本模块当作"去重已实现"。</b></p>
 */
public interface WikiDedupSupport {

    /** 对照 Go {@code dedupCandidateTopK}（wiki_ingest_dedup.go L37） */
    int DEDUP_CANDIDATE_TOP_K = 5;

    /**
     * 对照 Go {@code stabilizeExtractedIdentities}（dedup L396-444）：应用合并目标、
     * 认领身份 slug、按 slug 折叠重复项（同名项走 {@code mergeExtractedIdentity} 合并）。
     *
     * @param mergeTargets 模型判定的"合并到哪个既有 slug"（新 slug → 既有 slug）
     * @param exactTargets 确定性判定的精确同名目标（新 slug → 既有 slug）
     */
    List<ExtractedItem> stabilizeExtractedIdentities(
            String kbId,
            String pageType,
            List<ExtractedItem> items,
            Map<String, String> mergeTargets,
            Map<String, String> exactTargets,
            WikiBatchContext batchCtx);

    /**
     * 对照 Go {@code attachExactIdentityPages}（dedup L502-566）：按归一化标题做一次
     * 批量精确查找，把命中的既有页补进 {@code candidatePages} 与
     * {@code itemCandidates}，让精确同名的情况无论如何都进入候选集。
     */
    void attachExactIdentityPages(
            String kbId,
            String pageType,
            List<ExtractedItem> items,
            Map<String, WikiPageLite> candidatePages,
            Map<String, Set<String>> itemCandidates,
            WikiBatchContext batchCtx);

    /**
     * 对照 Go {@code collectExactIdentityTargets}（dedup L568-576）：为每个条目确定
     * 精确同名的既有页目标。纯函数，但依赖 dedup 侧的 {@code exactIdentityTarget}，
     * 因此仍由本端口提供。
     */
    void collectExactIdentityTargets(
            List<ExtractedItem> items,
            String pageType,
            Map<String, Set<String>> itemCandidates,
            Map<String, WikiPageLite> candidatePages,
            Map<String, String> exactTargets);

    /**
     * 对照 Go {@code dedupMergeRejectReason}（dedup L194-222）：校验模型提议的一次合并。
     * <b>返回空串表示允许</b>，否则返回简短的人类可读拒绝原因（会写进日志）。
     *
     * <p>逐项作用域检查是这里的关键护栏：去重 prompt 给模型看的是所有新条目的候选并集，
     * 弱模型会把某个条目与<b>只为另一个条目</b>召回的页面配成对。要求目标必须属于
     * <b>本条目自己</b>的候选集，就能整类拒绝这种幻觉。</p>
     */
    String dedupMergeRejectReason(String srcSlug, String dstSlug, Set<String> srcCandidates);
}
