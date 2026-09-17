package com.ragagent.wiki.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.ragagent.wiki.domain.WikiExtractionGranularity;
import com.ragagent.wiki.domain.WikiPageLite;

/**
 * Map 与 Reduce 阶段共享的数据（对照 Go {@code WikiBatchContext}，
 * wiki_ingest.go L1300-1351）。
 *
 * <h2>为什么是"懒加载 + 逐批缓存"而不是物化全量页面</h2>
 * <p>（照搬 Go 注释）历史上这里携带一份完全物化的 {@code AllPages}，外加预建的
 * SlugTitleMap / SummaryContentByKnowledgeID 查找表。在 4 万文档规模下，这意味着
 * 每个批次干的第一件事就是把 10 万+ 行 wiki_pages（含 content TEXT）读进内存
 * ——然后为了 cleanDeadLinks / injectCrossLinks / getExistingPageSlugsForKnowledge
 * 再遍历好几遍。</p>
 * <p>现在改为通过 fetcher 懒加载，背后是轻量投影
 * （{@code ListBySlugs} / {@code ListSummariesByKnowledgeIDs}）。每个 fetcher 按入参
 * 缓存结果，因此批次内重复查询是免费的；缓存是<b>逐批次</b>的。</p>
 *
 * <h2>Java 侧的并发注意</h2>
 * <p>Go 的 {@code identityClaims} / {@code identityPages} 是 {@code sync.Map}，
 * 但 fetcher 的缓存注释说"goroutine-local-via-mutex"。Java 侧的 map 阶段用虚拟线程
 * 扇出，<b>必须</b>用并发容器：这里统一用 {@link ConcurrentHashMap}。
 * 注意 {@code identityPages} 的缓存值允许是<b>空列表</b>（表示"确认查无此页"），
 * 因此不能用 {@code computeIfAbsent} 的"缺失即计算"语义去区分——取用时要显式
 * 用 {@code containsKey} 判定，与 Go 的 {@code sync.Map.Load 的 ok} 语义一致。</p>
 */
public class WikiBatchContext {

    /**
     * 对照 Go {@code SlugTitle}：把 slug 解析成它当前的标题（缺失返回 ""）。
     * 背后是 ListBySlugs；缓存随调用填充，因此只为真正看过的 slug 付费。
     */
    private Function<String, String> slugTitle = slug -> "";

    /**
     * 对照 Go {@code SlugTitleMany}：把一个 slug 集合批量成单次 ListBySlugs 查询，
     * 返回解析出的标题表。调用方已经握有完整 slug 列表时很方便；结果同样进缓存。
     */
    private Function<List<String>, Map<String, String>> slugTitleMany = slugs -> Map.of();

    /**
     * 对照 Go {@code SummaryContentByKnowledgeID}：返回给定 knowledge id 存活的
     * 摘要页正文（没有摘要页 / 已归档则 ""）。背后是
     * {@code ListSummariesByKnowledgeIDs}，缓存同样懒填充。
     */
    private Function<String, String> summaryContentByKnowledgeId = kid -> "";

    /**
     * 对照 Go {@code ExtractionGranularity}：驱动 Pass 0（候选 slug 抽取）的激进度。
     * 每批次从 KB 的 WikiConfig <b>解析一次</b>，因此批内每篇文档看到同一套范围规则。
     * <b>已经 Normalize 过</b>——消费方可以假定它是三个合法值之一。
     */
    private WikiExtractionGranularity extractionGranularity;

    /**
     * 对照 Go {@code ContentInstructions}：KB 级业务指引。稳定的引用、合并、taxonomy
     * 与 JSON 规则仍留在系统模板里，<b>不可</b>被本字段替换。
     */
    private String contentInstructions = "";

    /** 对照 Go {@code ExtractionInstructions}：同上，作用于抽取阶段。 */
    private String extractionInstructions = "";

    /**
     * 对照 Go {@code PlannedFolderID}：批次 taxonomy 规划（planBatchTaxonomy +
     * 目录解析）给每个页面 slug 分配的 {@code wiki_folders.id}。
     *
     * <p>Reduce 只把它应用到<b>尚未归档</b>（{@code FolderID == ""}）的页面，
     * 因此整批落在同一棵连贯的树上，又不会打乱用户策展的归属。目录本身在 reduce
     * <b>之前</b>就已顺序创建好，所以并行的 reduce 阶段只做"写入已解析好的 id"，
     * 从不在目录创建上竞争。reduce 期间<b>只读</b>。</p>
     */
    private Map<String, String> plannedFolderId = Map.of();

    /**
     * 对照 Go {@code identityClaims}：身份预留的 Lite 模式 / Redis 报错兜底。
     * 一个批次里的 map worker 即使 Lite 模式串行批次也会并发跑，所以它们仍需在
     * Reduce 按 slug 聚合更新<b>之前</b>收敛。该 map 是逐批次的，随批次消失。
     */
    private final ConcurrentHashMap<String, String> identityClaims = new ConcurrentHashMap<>();

    /**
     * 对照 Go {@code identityPages}：为本批次记忆精确标题查询，让并发 map worker
     * 探查同一个 (页面类型, 归一化标题) 时共享一次 DB 往返。
     * 值是 {@code List<WikiPageLite>}，<b>包含空列表</b>表示"已确认查无"。
     */
    private final ConcurrentHashMap<String, List<WikiPageLite>> identityPages = new ConcurrentHashMap<>();

    public WikiBatchContext() {}

    // ── fetcher ──

    public Function<String, String> getSlugTitle() { return slugTitle; }
    public void setSlugTitle(Function<String, String> v) {
        slugTitle = v == null ? slug -> "" : v;
    }

    /** 对照 Go {@code SlugTitle(ctx, slug)} 的调用形态 */
    public String slugTitle(String slug) {
        return slugTitle.apply(slug);
    }

    public Function<List<String>, Map<String, String>> getSlugTitleMany() { return slugTitleMany; }
    public void setSlugTitleMany(Function<List<String>, Map<String, String>> v) {
        slugTitleMany = v == null ? slugs -> Map.of() : v;
    }

    /** 对照 Go {@code SlugTitleMany(ctx, slugs)} 的调用形态 */
    public Map<String, String> slugTitleMany(List<String> slugs) {
        return slugTitleMany.apply(slugs);
    }

    public Function<String, String> getSummaryContentByKnowledgeId() {
        return summaryContentByKnowledgeId;
    }

    public void setSummaryContentByKnowledgeId(Function<String, String> v) {
        summaryContentByKnowledgeId = v == null ? kid -> "" : v;
    }

    public String summaryContentByKnowledgeId(String kid) {
        return summaryContentByKnowledgeId.apply(kid);
    }

    // ── 业务字段 ──

    public WikiExtractionGranularity getExtractionGranularity() { return extractionGranularity; }
    public void setExtractionGranularity(WikiExtractionGranularity v) { extractionGranularity = v; }

    public String getContentInstructions() { return contentInstructions; }
    public void setContentInstructions(String v) { contentInstructions = v == null ? "" : v; }

    public String getExtractionInstructions() { return extractionInstructions; }
    public void setExtractionInstructions(String v) { extractionInstructions = v == null ? "" : v; }

    public Map<String, String> getPlannedFolderId() { return plannedFolderId; }
    public void setPlannedFolderId(Map<String, String> v) {
        plannedFolderId = v == null ? Map.of() : v;
    }

    // ── 身份认领 / 精确标题缓存（对照 Go 的 sync.Map 字段） ──

    public ConcurrentHashMap<String, String> identityClaims() { return identityClaims; }

    public ConcurrentHashMap<String, List<WikiPageLite>> identityPages() { return identityPages; }

    /**
     * 缓存键分隔符。
     *
     * <p>Go 用 {@code "\x00"}（NUL）作分隔符；Java 侧改用 {@code ':'}——
     * <b>可注入性等价</b>：{@code pageType} 只会是 {@code "entity"} / {@code "concept"}
     * 等固定小写词（不含 {@code ':'}），因此 {@code pageType + ':' + identity} 仍是
     * 单射，不会出现 {@code ("a", "b:c")} 与 {@code ("a:b", "c")} 撞键
     * （Go 选 NUL 是为了在<b>不假设</b> pageType 形态时也安全）。</p>
     *
     * <p>改动的动机是工程性的：源码里放裸 NUL 字节会让 grep / diff 等工具把文件
     * 判定成二进制而静默跳过。该键只在批次的进程内 map 里使用，从不落库、
     * 从不跨语言传递，因此格式变化没有任何可观测影响。</p>
     */
    public static final String CACHE_KEY_SEPARATOR = ":";

    /** 对照 Go {@code identityPageCacheKey}（dedup 侧）：{@code pageType + SEP + identity}。 */
    public static String identityPageCacheKey(String pageType, String identity) {
        return pageType + CACHE_KEY_SEPARATOR + identity;
    }

    /** 对照 Go 的 {@code batchCtx == nil} 容忍：fetcher 缺失时返回空结果而不是 NPE。 */
    public static <T, R> R applyOr(Function<T, R> fn, T input, R fallback) {
        if (fn == null) {
            return fallback;
        }
        return fn.apply(input);
    }

    /** 供需要 {@code BiFunction}（ctx + 参数）形态的调用点使用 */
    public static <T, U, R> R applyOr(BiFunction<T, U, R> fn, T a, U b, R fallback) {
        if (fn == null) {
            return fallback;
        }
        return fn.apply(a, b);
    }
}
