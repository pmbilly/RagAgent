package com.ragagent.memory.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.ZeroTimeSerializer;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.common.settings.MemoryConfig;
import com.ragagent.memory.domain.MemoryConsolidationResult;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySubject;
import com.ragagent.memory.domain.MemoryText;
import com.ragagent.memory.domain.MemoryVectors;
import com.ragagent.memory.mapper.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 整仓回顾：把同一个主体近重复的记忆折成一条，并把搁置太久的任务降级。
 *
 * <h2>为什么是独立的一趟</h2>
 * <p>蒸馏只看得到最新的那段对话，这对单轮是正确的范围，对"注意到三周里五轮
 * 把同一个偏好记成了五种略有不同的说法"或"上个季度的任务现在只是噪声"是错误的范围。
 * Generative Agents 的 reflection 步骤与 MemoryOS 的分段存储都出于同样的理由
 * 把这一趟离线处理当成独立阶段：任何按轮调用的东西都看不到它。</p>
 *
 * <p>它<b>从不</b>跑在请求路径上（除了 {@code ConsolidateNow} 这条用户主动按按钮的路，
 * 那也是他自己在等）。</p>
 */
@Component
public class MemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);

    /**
     * 两次整仓回顾之间的最小间隔。
     * 这是维护，不是用户在等的功能，而且每次运行都花一次模型调用，所以刻意低频。
     */
    static final Duration CONSOLIDATE_INTERVAL = Duration.ofHours(24);

    /**
     * 用户主动要求的两次回顾之间的地板。
     * 短到真正的重试（修好模型、再按一次）不被挡住，长到按钮无法被脚本化成
     * 一串模型调用。
     */
    static final Duration FORCED_CONSOLIDATE_INTERVAL = Duration.ofMinutes(1);

    /**
     * 低于这个仓库存量就没有值得回顾的东西
     * ——一把记忆不可能已经漂移出矛盾。
     */
    static final int CONSOLIDATE_MIN_ITEMS = 6;

    /** 一次回顾的模型调用上限。 */
    static final int CONSOLIDATE_MAX_CLUSTERS = 3;
    /** 用户主动要求的回顾的模型调用上限（比每日那趟宽）。 */
    static final int FORCED_MAX_CLUSTERS = 8;

    /**
     * 两条记忆要共享多少措辞，
     * 每日那一趟才肯花一次模型调用去比较它们。
     */
    static final double CONSOLIDATE_MIN_OVERLAP = 0.55;

    /**
     * 用户主动要求的回顾用的同一类措辞重合门槛。
     *
     * <p><b>刻意低</b>。候选选取是**召回**，不是判断——每一组都会被交给模型，
     * 而模型在发现这些记录其实是不同的事时会回一句空话。严门槛只会把成对的东西
     * 藏起来、不让唯一能分辨它们的那个部件看到：{@code "我叫wizard，我是一个画家"}
     * 与 {@code "职业：我叫wizardchen，我是一个作家"} 的 token 重合是 0.50，
     * 因此永远到不了模型面前——而解决那个矛盾正是有人按下这个按钮的原因。</p>
     */
    static final double FORCED_MIN_OVERLAP = 0.3;

    /** 余弦相似度门槛：每日 / 用户主动。 */
    static final double CONSOLIDATE_MIN_COSINE = 0.86;
    static final double FORCED_MIN_COSINE = 0.75;

    /**
     * 一个任务可以被无视多久之后不再争抢空间。
     * "我这周在重构支付"这一周值得召回，三个月后就是误导。
     */
    static final Duration STALE_TASK_AGE = Duration.ofDays(45);

    /** 整理任务的系统提示词（内容是固定契约）。 */
    static final String CONSOLIDATION_SYSTEM_PROMPT = """
            你在整理一个人的长期记忆。下面几条记录说的是同一件事，请合并成一条。

            规则：
            - 只用这些记录里已有的信息，不要补充、不要推测。
            - 如果它们互相矛盾，以日期最新的一条为准。
            - 保留最具体的细节（具体的名称、数字、版本），丢掉重复的说法。
            - 用记录本身的语言，一句话，不超过 60 字。
            - 只输出 JSON：{"statement":"合并后的一句话"}
            - 如果这些记录其实不是同一件事，输出 {"statement":""}。""";

    /** 整理任务的响应 schema。 */
    static final String CONSOLIDATION_SCHEMA = """
            {
              "type": "object",
              "properties": {"statement": {"type": "string"}},
              "required": ["statement"]
            }""";

    private final MemoryRepository repo;
    private final MemoryService memoryService;
    private final MemoryVectorService vectorService;
    private final MemoryModelResolver modelResolver;

    public MemoryConsolidationService(MemoryRepository repo,
                                      MemoryService memoryService,
                                      MemoryVectorService vectorService,
                                      MemoryModelResolver modelResolver) {
        this.repo = repo;
        this.memoryService = memoryService;
        this.vectorService = vectorService;
        this.modelResolver = modelResolver;
    }

    /**
     * 最多一天一次地回顾一个主体的整个仓库。
     * 它**从不**跑在请求路径上。
     */
    void consolidateIfDue(MemoryScope scope, MemoryConfig cfg, String modelId, MemoryRunBudget budget) {
        reviewStore(scope, cfg, modelId, false, budget);
    }

    /**
     * 立刻回顾调用者的仓库，
     * 不必等搭在蒸馏上的每日维护那一趟。
     */
    public MemoryConsolidationResult consolidateNow() {
        MemoryService.ScopeState state = memoryService.enabledScope();
        if (!state.ok()) {
            throw new MemoryScopeExceptions.Disabled();
        }
        MemoryScope scope = state.scope();
        repo.ensureSubject(scope);
        String modelId = memoryService.extractionModelId(state.cfg(), MemoryExtractPayload.empty());
        return reviewStore(scope, state.cfg(), modelId, true, MemoryRunBudget.UNBOUNDED);
    }

    /**
     * 回顾一个主体的整个仓库。
     *
     * @param force  {@code true} = 用户主动按的按钮（用自己的节流钟、更宽的预算）
     * @param budget 整次运行还剩下的时间；{@code Handle} 传的是带 9 分钟上限的那个
     */
    MemoryConsolidationResult reviewStore(MemoryScope scope, MemoryConfig cfg, String modelId,
                                          boolean force, MemoryRunBudget budget) {
        MemoryConsolidationResult result = new MemoryConsolidationResult();
        MemorySubject subject;
        try {
            subject = repo.getSubject(scope);
        } catch (RuntimeException e) {
            log.warn("memory: load subject for consolidation failed: {}", e.toString());
            return result;
        }
        if (subject == null) {
            return result;
        }
        // 两只钟：两个调用方被限流的理由不同，不能互相压住对方。每日那一趟是没人要求的
        // 维护，所以等一天。按按钮要的那次只等到按钮按不住为止：那条端点是 Viewer 级的，
        // 而每次按下值得最多 forcedMaxClusters 次模型调用，一个模型一直拒绝的仓库
        // 会让它无限重复下去。
        OffsetDateTime last = force ? subject.getForcedConsolidatedAt() : subject.getConsolidatedAt();
        Duration interval = force ? FORCED_CONSOLIDATE_INTERVAL : CONSOLIDATE_INTERVAL;
        if (last != null && !ZeroTimeSerializer.isZeroValue(last)
                && Duration.between(last, OffsetDateTime.now()).compareTo(interval) < 0) {
            if (force) {
                result.setSkipped(MemoryConsolidationResult.SKIP_TOO_SOON);
            }
            return result;
        }
        if (force) {
            try {
                repo.markForcedConsolidated(scope);
            } catch (RuntimeException e) {
                log.warn("memory: mark forced consolidation failed: {}", e.toString());
            }
        }

        // 先过期：一条已经过期的任务不该成为合并候选。
        try {
            long archived = repo.expireOverdue(scope);
            result.setExpired((int) archived);
            if (archived > 0) {
                log.info("memory: archived {} expired memories for {}", archived, scope.subjectId());
            }
        } catch (RuntimeException e) {
            log.warn("memory: expire overdue failed: {}", e.toString());
        }

        // 整个仓库，不是一页。以前用一个固定的 200 封顶，意味着两条都旧的重复
        // 永远见不到面：那一趟只看得到最新的行，而归并恰恰是唯一负责发现
        // "几周里逐渐漂开的东西"的阶段。
        //
        // 多读不花模型调用。候选选取是本地的——token 重合加上召回已经存下的向量——
        // 而真正交给模型的组数由 maxClusters 单独封顶。
        List<MemoryItem> items;
        try {
            items = repo.listItems(scope, MemoryKinds.STATUS_ACTIVE, cfg.effectiveMaxItems(), 0).items();
        } catch (RuntimeException e) {
            log.warn("memory: consolidation list failed: {}", e.toString());
            return result;
        }
        if (items == null) {
            items = List.of();
        }

        result.setReviewed(items.size());
        result.setDemoted(demoteStaleTasks(scope, items));
        if (force || items.size() >= CONSOLIDATE_MIN_ITEMS) {
            MergeOutcome outcome = mergeRedundant(scope, cfg, modelId, items, force, budget);
            result.setMerged(outcome.merged());
            result.setCandidates(outcome.candidates());
            result.setSkipped(outcome.skipped());
        } else {
            result.setSkipped(MemoryConsolidationResult.SKIP_TOO_FEW_ITEMS);
        }

        // 给"在 embedding 模型存在之前"或"它不可达时"写下的东西补向量。每次运行有上限，
        // 所以一大堆积压是几天内排干，而不是把一次维护卡住。
        vectorService.backfillEmbeddings(scope, cfg);

        // 存在、但早于"数据库能对它们排序"的那些向量。便宜——不调用模型，数字已经存着——
        // 而在它跑之前，那些记忆对 SQL 排名是隐形的。
        try {
            int moved = repo.syncVectorColumn(scope, MemoryVectorService.VECTOR_SYNC_PER_RUN);
            if (moved > 0) {
                log.info("memory: moved {} vectors into the search column for {}",
                        moved, scope.subjectId());
            }
        } catch (RuntimeException e) {
            log.warn("memory: sync vector column failed: {}", e.toString());
        }

        try {
            repo.markConsolidated(scope);
        } catch (RuntimeException e) {
            log.warn("memory: mark consolidated failed: {}", e.toString());
        }
        if (result.getMerged() > 0 || result.getDemoted() > 0 || result.getExpired() > 0) {
            memoryService.rebuildBlock(scope);
        }
        // 一次什么都没做的回顾是常态，而在它说明原因之前，它与坏掉的回顾无从分辨。
        if (force || result.getMerged() > 0 || result.getDemoted() > 0 || result.getExpired() > 0) {
            log.info("memory: consolidation reviewed {}, candidates {}, merged {}, demoted {}, "
                            + "expired {}, skipped=\"{}\" for {}",
                    result.getReviewed(), result.getCandidates(), result.getMerged(),
                    result.getDemoted(), result.getExpired(), result.getSkipped(), scope.subjectId());
        }
        return result;
    }

    /**
     * 把几个月没人提过的任务降低重要度。
     *
     * <p>删掉它们是错的——用户从没说过做完了，而我们不删被告知的东西。
     * 降低重要度就够了：它们掉出常驻块，在仓库触顶时最先被拿走，
     * 同时在记忆管理器里仍然可见、可解释。</p>
     */
    private int demoteStaleTasks(MemoryScope scope, List<MemoryItem> items) {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(STALE_TASK_AGE);
        int demoted = 0;
        for (MemoryItem item : items) {
            if (item == null || !MemoryKinds.KIND_TASK.equals(item.getKind()) || item.getImportance() <= 1) {
                continue;
            }
            OffsetDateTime last = item.getValidFrom();
            if (item.getLastUsedAt() != null && !ZeroTimeSerializer.isZeroValue(item.getLastUsedAt())
                    && item.getLastUsedAt().isAfter(last)) {
                last = item.getLastUsedAt();
            }
            if (last.isAfter(cutoff)) {
                continue;
            }
            try {
                repo.updateItemContent(scope, item.getId(), item.getContent(),
                        item.getNormalizedKey(), 1);
            } catch (RuntimeException e) {
                log.warn("memory: demote stale task failed: {}", e.toString());
                continue;
            }
            demoted++;
        }
        return demoted;
    }

    /** {@link #mergeRedundant} 的三返回值。 */
    record MergeOutcome(int merged, int candidates, String skipped) {
    }

    /**
     * 把一组组近重复的记忆折成一条陈述。
     *
     * <p>{@code candidates} 是找到的组数，不是封顶之后的 {@code len(clusters)}——
     * 它是用来告诉调用方"空结果意味着没有任何东西看起来相似"还是"模型说了不"的。</p>
     */
    private MergeOutcome mergeRedundant(MemoryScope scope, MemoryConfig cfg, String modelId,
                                        List<MemoryItem> items, boolean force, MemoryRunBudget budget) {
        double minOverlap = force ? FORCED_MIN_OVERLAP : CONSOLIDATE_MIN_OVERLAP;
        double minCosine = force ? FORCED_MIN_COSINE : CONSOLIDATE_MIN_COSINE;
        int maxClusters = force ? FORCED_MAX_CLUSTERS : CONSOLIDATE_MAX_CLUSTERS;

        List<List<MemoryItem>> clusters = mergeCandidates(scope, cfg, items, minOverlap, minCosine);
        int candidates = clusters.size();
        if (candidates == 0) {
            return new MergeOutcome(0, 0, MemoryConsolidationResult.SKIP_NO_CANDIDATES);
        }
        if (clusters.size() > maxClusters) {
            clusters = clusters.subList(0, maxClusters);
        }

        int merged = 0;
        int declined = 0;
        for (List<MemoryItem> cluster : clusters) {
            ConsolidationCall call = callConsolidationModel(modelId, cluster, budget);
            if (call.unavailable()) {
                // 只有模型可以判定两条记忆说的是同一件事。光凭 token 重合就合并，
                // 会用一条从来不是用来做判断的启发式去取代用户给我们的措辞，
                // 所以回顾在这里停下并说明原因。
                return new MergeOutcome(merged, candidates,
                        MemoryConsolidationResult.SKIP_MODEL_UNAVAILABLE);
            }
            String statement = MemoryText.sanitizeMemoryContent(call.statement());
            if (statement.isEmpty()) {
                declined++;
                continue;
            }
            MemoryItem primary = cluster.get(0);
            MemoryItem replacement = null;
            try {
                MemoryItem incoming = new MemoryItem();
                incoming.setKind(primary.getKind());
                incoming.setTopic(primary.getTopic());
                incoming.setContent(statement);
                incoming.setImportance(primary.getImportance());
                incoming.setOrigin(primary.getOrigin());
                incoming.setSourceSessionId(primary.getSourceSessionId());
                incoming.setSourceMessageId(primary.getSourceMessageId());
                replacement = memoryService.write(scope, cfg, incoming);
            } catch (RuntimeException e) {
                log.warn("memory: consolidation write failed: {}", e.toString());
            }
            if (replacement == null) {
                continue;
            }
            // 取代而不是删除：旧措辞保留它的日期，所以记忆管理器仍然能解释
            // 这条陈述以前是什么、什么时候变的。
            for (MemoryItem item : cluster) {
                if (item.getId().equals(replacement.getId())) {
                    continue;
                }
                try {
                    repo.supersedeItem(scope, item.getId(), replacement.getId());
                } catch (RuntimeException e) {
                    log.warn("memory: supersede during consolidation failed: {}", e.toString());
                }
            }
            merged++;
        }
        if (merged == 0 && declined > 0) {
            return new MergeOutcome(0, candidates, MemoryConsolidationResult.SKIP_MODEL_DECLINED);
        }
        return new MergeOutcome(merged, candidates, null);
    }

    /**
     * 把可能说的是同一件事的记忆分组。
     *
     * <p>两个信号，任一个够就成立。措辞重合抓同一句话的复述；召回已经存下的向量上的
     * 余弦抓"同一件事换个说法"，那是数 token 数多少遍都抓不到的。
     * 两者都不做决定——每一组都会被交给模型。</p>
     *
     * <p>预建 token 集合是刻意的：聚类要比较每一对幸存者，所以一个处在 2000 条上限的仓库
     * 会把那个谓词跑上几百万次，而每次调用里重建两边的 token 集合，正是让
     * "回顾整个仓库"慢到不能放在一次请求里的原因。</p>
     */
    private List<List<MemoryItem>> mergeCandidates(MemoryScope scope, MemoryConfig cfg,
                                                   List<MemoryItem> items,
                                                   double minOverlap, double minCosine) {
        Map<String, Set<String>> tokens = MemoryLexical.buildTokenSets(items);
        Map<String, float[]> vectors = vectorService.storedVectors(scope, cfg, items);

        return clusterBy(items, (a, b) -> {
            if (MemoryLexical.jaccardSets(tokens.get(a.getId()), tokens.get(b.getId())) >= minOverlap) {
                return true;
            }
            float[] va = vectors == null ? null : vectors.get(a.getId());
            float[] vb = vectors == null ? null : vectors.get(b.getId());
            if (va == null || vb == null || va.length == 0 || vb.length == 0) {
                return false;
            }
            return MemoryVectors.cosineSimilarity(va, vb) >= minCosine;
        });
    }

    /**
     * 同 kind 且措辞几乎一样的记忆分组，
     * 用无人值守那一趟的门槛。
     */
    static List<List<MemoryItem>> clusterSimilar(List<MemoryItem> items) {
        return clusterBy(items, (a, b) -> MemoryLexical.jaccard(
                MemoryLexical.tokenize(a.getTopic() + " " + a.getContent()),
                MemoryLexical.tokenize(b.getTopic() + " " + b.getContent())) >= CONSOLIDATE_MIN_OVERLAP);
    }

    /**
     * 把同一个 kind 且 {@code same} 判为一回事的记忆分组。
     * 只有一条的组不会被返回。
     */
    static List<List<MemoryItem>> clusterBy(List<MemoryItem> items,
                                            BiPredicate<MemoryItem, MemoryItem> same) {
        List<List<MemoryItem>> clusters = new ArrayList<>();
        Set<String> taken = new HashSet<>();

        for (int i = 0; i < items.size(); i++) {
            MemoryItem item = items.get(i);
            if (item == null || taken.contains(item.getId())) {
                continue;
            }
            List<MemoryItem> group = new ArrayList<>();
            group.add(item);
            for (int j = i + 1; j < items.size(); j++) {
                MemoryItem other = items.get(j);
                if (other == null || taken.contains(other.getId())
                        || !other.getKind().equals(item.getKind())) {
                    continue;
                }
                if (!same.test(item, other)) {
                    continue;
                }
                group.add(other);
                taken.add(other.getId());
            }
            if (group.size() < 2) {
                continue;
            }
            taken.add(item.getId());
            clusters.add(group);
        }
        return clusters;
    }

    /** {@link #callConsolidationModel} 的双返回值。 */
    record ConsolidationCall(String statement, boolean unavailable) {
    }

    /**
     * 请模型合并一组。
     *
     * <p>关思考，理由同 {@code completeExtraction}：一个推理模型会把这份预算全花在
     * 自己的斟酌上、什么都不返回，而在这里那会悄悄跳过每一次合并。</p>
     */
    private ConsolidationCall callConsolidationModel(String modelId, List<MemoryItem> cluster,
                                                     MemoryRunBudget budget) {
        if (modelId == null || modelId.isEmpty()) {
            return new ConsolidationCall("", true);
        }
        com.ragagent.llm.LlmChatClient chatModel;
        try {
            chatModel = modelResolver.getChatModel(modelId);
        } catch (RuntimeException e) {
            log.warn("memory: consolidation model unavailable: {}", e.toString());
            return new ConsolidationCall("", true);
        }
        if (chatModel == null) {
            log.warn("memory: consolidation model unavailable");
            return new ConsolidationCall("", true);
        }

        StringBuilder b = new StringBuilder();
        for (MemoryItem item : cluster) {
            b.append("- (").append(item.getValidFrom().toLocalDate()).append(") ")
                    .append(MemoryText.sanitizeMemoryContent(item.getContent())).append('\n');
        }

        ChatOptions options = new ChatOptions();
        options.setTemperature(0);
        options.setMaxCompletionTokens(600);
        options.setThinking(Boolean.FALSE);
        options.setFormat(MemoryTopicResolver.readTree(CONSOLIDATION_SCHEMA));

        ChatResponse response;
        try {
            response = budget.chat(chatModel,
                    List.of(ChatMessage.system(CONSOLIDATION_SYSTEM_PROMPT),
                            ChatMessage.user(b.toString())),
                    options);
        } catch (RuntimeException e) {
            // 含 RunExpiredException：预算耗尽也落进同一条 warn 分支。
            log.warn("memory: consolidation call failed: {}", e.toString());
            return new ConsolidationCall("", true);
        }
        if (response == null) {
            log.warn("memory: consolidation call failed: null response");
            return new ConsolidationCall("", true);
        }

        String content = response.getContent() == null ? "" : response.getContent().strip();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new ConsolidationCall("", false);
        }
        try {
            JsonNode parsed = MemoryTopicResolver.readTree(content.substring(start, end + 1));
            JsonNode statement = parsed.get("statement");
            return new ConsolidationCall(statement == null ? "" : statement.asText(""), false);
        } catch (Exception e) {
            return new ConsolidationCall("", false);
        }
    }
}
