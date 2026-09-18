package com.ragagent.memory.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.ragagent.memory.domain.MemoryConfig;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryItemEmbedding;
import com.ragagent.memory.domain.MemoryKinds;
import com.ragagent.memory.domain.MemoryKeys;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemoryText;
import com.ragagent.memory.domain.MemoryVectorHit;
import com.ragagent.memory.domain.MemoryVectorQuery;
import com.ragagent.memory.domain.MemoryVectors;
import com.ragagent.memory.mapper.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 向量侧的全部动作（对照 Go
 * {@code internal/application/service/memory/vector.go} 全文）。
 *
 * <h2>为什么从 {@code MemoryService} 里分出来</h2>
 * <p>Go 的方法挂在同一个 {@code Service} 上。Java 侧拆成独立 bean 是因为调用方向是
 * <b>单向</b>的：召回、抽取、归并都只需要"嵌入式 + 语义查找"这两件事，
 * 而它们自身不需要被向量侧反向调用。拆开之后依赖图没有环，
 * 也让"召回里没有任何模型调用"这条性质一眼看得见——只有
 * {@code embedText} 一个出口。</p>
 *
 * <h2>三个超时口径（照抄）</h2>
 * <ul>
 *   <li>{@link #EMBED_TIMEOUT} 2s：召回排在每一个回答前面，而且在这之前它一次模型调用
 *       都没有。语义匹配值一个回合的<b>零头</b>，不值得为一个卡住的 embedding 端点
 *       把整个回合挂住——超时就静默回落到字面匹配，也就是这个功能以前的行为。</li>
 *   <li>{@link #EMBED_WRITE_TIMEOUT} 10s：写路径已经在响应路径之外，可以宽裕些。</li>
 *   <li>{@link #RRF_K} 60：倒排秩融合常数，来自原始 TREC 工作，也是多数系统用的值。
 *       Graphiti 用 1，会把列表顶部磨尖、代价是几乎忽略下面的一切。</li>
 * </ul>
 */
@Component
public class MemoryVectorService {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorService.class);

    /** 对照 Go {@code embedTimeout}。 */
    static final Duration EMBED_TIMEOUT = Duration.ofSeconds(2);
    /** 对照 Go {@code embedWriteTimeout}。 */
    static final Duration EMBED_WRITE_TIMEOUT = Duration.ofSeconds(10);
    /** 对照 Go {@code rrfK}。 */
    static final double RRF_K = 60.0;
    /**
     * 对照 Go {@code minCosine}：低于它就不算命中。
     *
     * <p>没有这条下限，每一条有向量的记忆都会进入排序（包括得分为 0 的），
     * 融合再把它们拉进提示词——这个功能会从"找不到改写过的那条记忆"
     * 直接变成"什么都召回"。</p>
     */
    static final double MIN_COSINE = 0.5;
    /** 对照 Go {@code backfillPerRun}：一次维护补多少条缺失向量。 */
    static final int BACKFILL_PER_RUN = 200;
    /** 对照 Go {@code vectorSyncPerRun}：一次维护把多少行搬进数据库的 vector 列。 */
    public static final int VECTOR_SYNC_PER_RUN = 2000;

    private static final ExecutorService EMBEDS = Executors.newVirtualThreadPerTaskExecutor();

    private final MemoryRepository repo;
    private final MemoryModelResolver modelResolver;

    public MemoryVectorService(MemoryRepository repo, MemoryModelResolver modelResolver) {
        this.repo = repo;
        this.modelResolver = modelResolver;
    }

    // ── 嵌入式解析 ─────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code embedder}：解析工作区钉死的 embedding 模型。
     *
     * <p>记忆是<b>一个工作区一个向量空间</b>。知识库各自绑定自己的 embedding 模型，
     * 所以没有"工作区的 embedding 模型"可以回落——随手抓列表里的第一个，
     * 会在模型增删过程中悄悄把不可比的空间混在一起。留空即关闭语义召回。</p>
     *
     * @return 可用的模型 id；{@code null} 表示语义召回不可用
     */
    public String embedder(MemoryConfig cfg) {
        if (cfg == null || !cfg.vectorRecallEnabled()) {
            return null;
        }
        if (cfg.getEmbeddingModelId().isEmpty()) {
            return null;
        }
        return cfg.getEmbeddingModelId();
    }

    /**
     * 对照 Go {@code embedText}：产出一个向量，有界且非致命。
     *
     * <p>任何一步失败都回 {@code null}——调用方一律按"没有向量"继续，
     * 而不是把它当错误上抛。这就是"记忆是增强而非硬依赖"的落地方式。</p>
     *
     * <p><b>超时实现</b>：Go 用 {@code context.WithTimeout} 取消 Embed；
     * Java 把阻塞调用放虚拟线程上 {@code get(timeout)}，超时即放弃（返回 null）。
     * 与 {@code MemoryRunBudget.chat} 同一处置。</p>
     */
    public float[] embedText(String modelId, String text, Duration timeout) {
        if (modelId == null || modelId.isEmpty() || text == null || text.isEmpty()) {
            return null;
        }
        Future<float[]> future = EMBEDS.submit((Callable<float[]>) () -> modelResolver.embed(modelId, text));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("memory: embed timed out after {}ms (model={})", timeout.toMillis(), modelId);
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.warn("memory: embedding model {} unavailable: {}", modelId, e.getCause().toString());
            return null;
        }
    }

    // ── 写向量 ─────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code storeItemEmbedding}：记下一条记忆的向量。尽力而为——
     * 没有向量的记忆仍然是一条记忆，只是对语义召回不可见，直到补扫捡起它。
     */
    public void storeItemEmbedding(MemoryScope scope, MemoryConfig cfg, MemoryItem item) {
        if (item == null) {
            return;
        }
        String modelId = embedder(cfg);
        if (modelId == null) {
            return;
        }
        String text = embeddableText(item, embedAliases(scope, item));
        float[] vector = embedText(modelId, text, EMBED_WRITE_TIMEOUT);
        if (vector == null || vector.length == 0) {
            return;
        }
        try {
            repo.upsertItemEmbedding(scope, newEmbedding(item, modelId, vector));
        } catch (RuntimeException e) {
            log.warn("memory: store embedding failed: {}", e.toString());
        }
    }

    /** 对照 Go 里那段重复两次的 {@code MemoryItemEmbedding{...}} 构造。 */
    private static MemoryItemEmbedding newEmbedding(MemoryItem item, String modelId, float[] vector) {
        MemoryItemEmbedding embedding = new MemoryItemEmbedding();
        embedding.setItemId(item.getId());
        embedding.setSourceContent(item.getContent());
        embedding.setSourceTopic(item.getTopic());
        embedding.setModelId(modelId);
        embedding.setDims(vector.length);
        embedding.setVector(MemoryVectors.encodeEmbedding(vector));
        return embedding;
    }

    /**
     * 对照 Go {@code embeddableText}：一条记忆嵌什么。
     *
     * <p>主题与内容合在一起，因为主题承载"这句话是关于什么的"，而陈述本身常常太简略
     * 而无法定位——"PostgreSQL 17"离开"生产数据库"几乎不意味着什么。</p>
     * <p>兴趣是从主题标签提升来的，主题与内容本来就是同一个串；拼接会嵌出 "X：X"，
     * 那不是任何一个问题会像的句子，所以相等时不拼。</p>
     * <p>{@code aliases} 是同一个人为同一主题用过的其它说法：它们拓宽问题能匹配到的东西，
     * 却<b>不拓宽</b>模型被告知的东西——只活在向量里，从不进注入块。</p>
     */
    public static String embeddableText(MemoryItem item, List<String> aliases) {
        if (item == null) {
            return "";
        }
        String topic = MemoryText.sanitizeMemoryTopic(item.getTopic());
        String content = MemoryText.sanitizeMemoryContent(item.getContent());
        String text = content;
        if (!topic.isEmpty() && !topic.equals(content)) {
            text = topic + "：" + content;
        }
        if (text.isEmpty()) {
            return "";
        }
        Set<String> seen = new LinkedHashSet<>(List.of(text, content, topic));
        StringBuilder out = new StringBuilder(text);
        if (aliases != null) {
            for (String alias : aliases) {
                alias = MemoryText.sanitizeMemoryTopic(alias);
                if (alias.isEmpty() || !seen.add(alias)) {
                    continue;
                }
                out.append("；").append(alias);
            }
        }
        return out.toString();
    }

    /**
     * 对照 Go {@code embedAliases}：这个人给某个兴趣的主题用过的其它说法。
     *
     * <p>只有兴趣：其它类型自己就带着一个句子，它的主题是一个**标题**，
     * 而不是话题追踪器在跟的主体。尽力而为——查不到只是向量窄一点。</p>
     */
    public List<String> embedAliases(MemoryScope scope, MemoryItem item) {
        if (item == null || !MemoryKinds.KIND_INTEREST.equals(item.getKind())) {
            return null;
        }
        String key = MemoryKeys.normalizeTopicKey(item.getTopic());
        if (key.isEmpty()) {
            return null;
        }
        try {
            var stat = repo.topicByKey(scope, key);
            if (stat == null) {
                return null;
            }
            return stat.getAliases();
        } catch (RuntimeException e) {
            log.warn("memory: load topic aliases failed: {}", e.toString());
            return null;
        }
    }

    // ── 语义查找 ───────────────────────────────────────────────────────────

    /** {@link #vectorSearch} 的结果：命中 + 跳过原因（对照 Go 的双返回值）。 */
    public record VectorSearchOutcome(List<MemoryVectorHit> hits, String skipReason) {
    }

    /**
     * 对照 Go {@code vectorSearch}：向存储要"离查询最近的记忆"。
     *
     * <p>查找跑在这个主体拥有的**每一个**向量上。以前它跑在一个已经选好的候选集上，
     * 于是语义召回只能重排"按重要度挑出来"的那些——一条恰好回答了问题、
     * 却落在窗口外的记忆根本够不到，而把窗口开大也修不了"挑的时候就没看问题"这件事。</p>
     *
     * <p>空结果意味着语义匹配不可用或什么都没找到；调用方应回落到字面匹配，
     * 而不是当成"没有匹配"。{@code skipReason} 说明是哪一种。</p>
     */
    public VectorSearchOutcome vectorSearch(MemoryScope scope, MemoryConfig cfg, String query,
                                            List<String> kinds, int limit) {
        String modelId = embedder(cfg);
        if (modelId == null) {
            return new VectorSearchOutcome(null, "vector_disabled");
        }
        float[] queryVector = embedText(modelId, query, EMBED_TIMEOUT);
        if (queryVector == null || queryVector.length == 0) {
            return new VectorSearchOutcome(null, "embed_failed");
        }
        List<MemoryVectorHit> hits;
        try {
            hits = repo.searchItemsByVector(scope, new MemoryVectorQuery(
                    modelId, queryVector, kinds, MIN_COSINE, limit));
        } catch (RuntimeException e) {
            log.warn("memory: vector search failed: {}", e.toString());
            return new VectorSearchOutcome(null, "vector_search_failed");
        }
        if (hits == null || hits.isEmpty()) {
            return new VectorSearchOutcome(null, "no_vector_matches");
        }
        return new VectorSearchOutcome(hits, "");
    }

    /**
     * 对照 Go {@code fuseRankings}：用倒排秩融合把两个 id 排序合成一个。
     *
     * <p>用 RRF 而不是加权求和，是因为两个信号不在同一个量纲上：余弦有界且已标定，
     * 而字面得分是一个 n-gram 袋的重合计数，绝对值毫无意义。融合**秩**直接绕开这个问题，
     * 而且两边都认可的条目会赢过只有一个信号喜欢的。</p>
     */
    public static List<Integer> fuseRankings(List<Integer> lexical, List<Integer> vector) {
        Map<Integer, Double> scores = new HashMap<>();
        List<Integer> order = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        // ⚠️ 用 Arrays.asList 而不是 List.of：Go 的 [][]int{lexical, vector} 允许 nil 切片，
        // 而 List.of(...) 遇到 null 直接抛 NPE（本测试抓到过一次）。
        for (List<Integer> list : Arrays.asList(lexical, vector)) {
            if (list == null) {
                continue;
            }
            for (int rank = 0; rank < list.size(); rank++) {
                int index = list.get(rank);
                scores.merge(index, 1.0 / (RRF_K + rank), Double::sum);
                if (seen.add(index)) {
                    order.add(index);
                }
            }
        }
        // sort.SliceStable：分数降序，相同分数保持"首次出现"的次序。
        order.sort((a, b) -> Double.compare(scores.getOrDefault(b, 0.0), scores.getOrDefault(a, 0.0)));
        return order;
    }

    /**
     * 对照 Go {@code storedVectors}：读这些记忆**已经有的**向量。
     *
     * <p>它从不嵌任何东西：一次回顾要走遍整个仓库，每遍都重新嵌入的开销远超合并本身的价值。
     * 没有向量的记忆在回顾结束的补扫追上它之前，只按措辞匹配。</p>
     */
    public Map<String, float[]> storedVectors(MemoryScope scope, MemoryConfig cfg, List<MemoryItem> items) {
        String modelId = embedder(cfg);
        if (modelId == null) {
            return null;
        }
        List<String> ids = new ArrayList<>(items.size());
        for (MemoryItem item : items) {
            if (item != null && !item.getId().isEmpty()) {
                ids.add(item.getId());
            }
        }
        try {
            return repo.itemEmbeddings(scope, ids, modelId);
        } catch (RuntimeException e) {
            log.warn("memory: load embeddings for consolidation failed: {}", e.toString());
            return null;
        }
    }

    /**
     * 对照 Go {@code backfillEmbeddings}：给"写的时候还没有 embedding 模型"的记忆补向量。
     * 每次运行有上限；每日维护会调它，所以积压是<b>几天内</b>排干而不是一次爆发。
     */
    public int backfillEmbeddings(MemoryScope scope, MemoryConfig cfg) {
        String modelId = embedder(cfg);
        if (modelId == null) {
            return 0;
        }
        List<MemoryItem> items;
        try {
            items = repo.itemsMissingEmbeddings(scope, modelId, BACKFILL_PER_RUN);
        } catch (RuntimeException e) {
            log.warn("memory: find items missing embeddings failed: {}", e.toString());
            return 0;
        }
        if (items == null) {
            return 0;
        }
        int filled = 0;
        for (MemoryItem item : items) {
            if (item == null) {
                continue;
            }
            String text = embeddableText(item, embedAliases(scope, item));
            float[] vector = embedText(modelId, text, EMBED_WRITE_TIMEOUT);
            if (vector == null || vector.length == 0) {
                // 模型刚刚失败；这一批剩下的也会失败。
                break;
            }
            try {
                repo.upsertItemEmbedding(scope, newEmbedding(item, modelId, vector));
            } catch (RuntimeException e) {
                log.warn("memory: backfill embedding failed: {}", e.toString());
                continue;
            }
            filled++;
        }
        if (filled > 0) {
            log.info("memory: backfilled {} embeddings for {}", filled, scope.subjectId());
        }
        return filled;
    }
}
