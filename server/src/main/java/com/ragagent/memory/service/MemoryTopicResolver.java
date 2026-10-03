package com.ragagent.memory.service;

import com.ragagent.common.web.JsonMappers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.common.settings.MemoryKeys;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemoryText;
import com.ragagent.memory.domain.MemoryTopicStat;
import com.ragagent.memory.mapper.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把一次抽取产出的标签映射到这个人**已经有**的主体上。
 *
 * <h2>它解决的问题</h2>
 * <p>被要求给一个话题命名的模型不会两次给出同一个名字：这一次"门店排班管理"，
 * 下一次"店员班次安排"。把字符串当身份，意味着同一个主体被记在好几个 key 下、
 * 永远到不了提升阈值——功能看起来是开着的，却什么都没学到。</p>
 *
 * <p>修法是 mem0 与 Graphiti 都收敛到的那个：永不信任表层字符串，
 * 拿它对着已有的东西解析，**先试最便宜的那层**。</p>
 * <pre>
 *   tier 1  归一化相等，含此前记录过的别名
 *   tier 2  汉字二元组重合，有门禁，免得短标签被松散匹配上
 *   tier 3  对剩下的标签做一次批量模型调用
 * </pre>
 * <p>第 3 层是唯一有成本的，而且通常被跳过：抽取提示词本身就把这个人已有的主题
 * 展示给了模型、并要求它逐字复用一个标签，所以大多数运行在第 1 层就解完了。</p>
 */
@Component
public class MemoryTopicResolver {

    private static final Logger log = LoggerFactory.getLogger(MemoryTopicResolver.class);

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 光靠二元组重合就判定两个标签是同一个主体的门槛。
     *
     * <p>刻意定得高。把两个不是一回事的主题合并会污染"什么能变成记忆"的那个计数，
     * 而且发生的时候是**看不见**的。漏掉一次合并只是推迟一次提升，
     * 而下面那一层会兜住其中大多数。Graphiti 出于同样的理由把它的确定性层设在同一量级。</p>
     */
    static final double TOPIC_FUZZY_THRESHOLD = 0.80;

    /**
     * 展示给裁决模型的既有主题上限。
     * 一个人的主题列表很小，这是对病态账号的护栏，不是正常工作上限。
     */
    static final int TOPIC_CANDIDATE_LIMIT = 40;

    private final MemoryRepository repo;
    private final MemoryModelResolver modelResolver;

    public MemoryTopicResolver(MemoryRepository repo, MemoryModelResolver modelResolver) {
        this.repo = repo;
        this.modelResolver = modelResolver;
    }

    /**
     * 一个表层说法最终落到了哪里。
     *
     * <p>可变类：解析过程对 {@code canonical} / {@code tier} / {@code mergedLabel}
     * 三处就地赋值。</p>
     */
    public static final class Resolution {
        /**
         * 这个标签归属的既有主题；{@code null} 表示它确实是一个新主体。
         *
         * <p>包级可见，包内谁都能读写。</p>
         */
        MemoryTopicStat canonical;
        /**
         * 模型**实际**说的那个说法；与规范标签不同时会被记成别名。
         *
         * <p><b>它不是 final</b>：{@code collapseNewTopicsWithinRun} 会把它改写成同一次运行里
         * 更早出现的那个等价说法，
         * 而改写结果决定了最终记进别名列表的是哪一个。</p>
         */
        private String surface;
        /** 是哪条规则判定的，供日志与"断言没走到昂贵那一层"的测试使用。包级可见，同 {@link #canonical}。 */
        String tier = "";
        /**
         * 合并后的主体更好的名字（模型提议了、且过了"不许泛化"的闸门时）。
         * 空表示保持主体已有的标签。
         */
        private String mergedLabel = "";

        Resolution(String surface) {
            this.surface = surface;
        }

        public MemoryTopicStat canonical() {
            return canonical;
        }

        public String surface() {
            return surface;
        }

        public String tier() {
            return tier;
        }

        public String mergedLabel() {
            return mergedLabel;
        }

        /** 改写表层说法。 */
        void setSurface(String value) {
            this.surface = value;
        }

        /** 没判过就是 {@code "new"}。 */
        public String tierOrNew() {
            return tier.isEmpty() ? "new" : tier;
        }
    }

    /**
     * 把一次抽取运行产出的标签映射到这个人已有的主体上。
     */
    public List<Resolution> resolveTopics(MemoryScope scope, String modelId, List<String> surfaces,
                                          MemoryRunBudget budget) {
        if (surfaces == null || surfaces.isEmpty()) {
            return List.of();
        }
        List<MemoryTopicStat> existing;
        try {
            existing = repo.topTopics(scope, TOPIC_CANDIDATE_LIMIT);
        } catch (RuntimeException e) {
            log.warn("memory: load existing topics failed: {}", e.toString());
            existing = null;
        }
        if (existing == null) {
            existing = List.of();
        }

        List<Resolution> resolutions = new ArrayList<>(surfaces.size());
        List<Integer> unresolved = new ArrayList<>();

        for (String surface : surfaces) {
            Resolution resolution = new Resolution(surface);
            MemoryTopicStat exact = matchTopicExactly(surface, existing);
            if (exact != null) {
                resolution.canonical = exact;
                // 区分"抽取模型复述了一个已被跟踪的标签"与"解析器自己的归一化匹配上了"。
                // 在这里两者看起来都是精确命中，但只有前者是模型做的一个判断——
                // 把它报成最便宜、最确定的那一层，正是一次过度合并藏起来的方式。
                resolution.tier = surface.equals(exact.getTopic()) ? "reused" : "exact";
            } else {
                MemoryTopicStat loose = matchTopicLoosely(surface, existing);
                if (loose != null) {
                    resolution.canonical = loose;
                    resolution.tier = "fuzzy";
                } else {
                    unresolved.add(resolutions.size());
                }
            }
            resolutions.add(resolution);
        }

        if (!unresolved.isEmpty() && !existing.isEmpty()) {
            adjudicateTopics(budget, modelId, existing, resolutions, unresolved);
        }

        // 同一次运行里的两个标签可能是同一个新主体。没有这一步，这次运行会建出两行，
        // 之后每一次运行都得再把它们分开。
        collapseNewTopicsWithinRun(resolutions);

        return resolutions;
    }

    /**
     * 第 1 层——归一化后的标签，
     * 或者任何一个此前已经被解析到这个主题上的措辞。
     */
    static MemoryTopicStat matchTopicExactly(String surface, List<MemoryTopicStat> existing) {
        String key = MemoryKeys.normalizeTopicKey(surface);
        if (key.isEmpty()) {
            return null;
        }
        for (MemoryTopicStat stat : existing) {
            if (stat == null) {
                continue;
            }
            if (stat.getNormalizedKey().equals(key) || stat.hasAlias(surface)) {
                return stat;
            }
        }
        return null;
    }

    /**
     * 第 2 层——很高的汉字二元组重合，
     * 而且只在标签足够具体、重合度才有意义时才做。
     */
    static MemoryTopicStat matchTopicLoosely(String surface, List<MemoryTopicStat> existing) {
        if (!MemoryKeys.topicIsSpecificEnoughToMatchLoosely(surface)) {
            return null;
        }
        MemoryTopicStat best = null;
        double bestScore = 0;
        for (MemoryTopicStat stat : existing) {
            if (stat == null || !MemoryKeys.topicIsSpecificEnoughToMatchLoosely(stat.getTopic())) {
                continue;
            }
            double score = MemoryKeys.topicSimilarity(surface, stat.getTopic());
            if (score > bestScore) {
                best = stat;
                bestScore = score;
            }
        }
        if (bestScore < TOPIC_FUZZY_THRESHOLD) {
            return null;
        }
        return best;
    }

    /**
     * 把一次运行里彼此近似的新标签
     * 指到同一个表层说法上，这样它们变成一行而不是两行。
     */
    static void collapseNewTopicsWithinRun(List<Resolution> resolutions) {
        for (int i = 0; i < resolutions.size(); i++) {
            if (resolutions.get(i).canonical != null) {
                continue;
            }
            for (int j = 0; j < i; j++) {
                if (resolutions.get(j).canonical != null) {
                    continue;
                }
                if (MemoryKeys.normalizeTopicKey(resolutions.get(i).surface)
                        .equals(MemoryKeys.normalizeTopicKey(resolutions.get(j).surface))) {
                    resolutions.get(i).setSurface(resolutions.get(j).surface);
                    break;
                }
            }
        }
    }

    /**
     * 话题裁决的提示词。
     *
     * <p>这段提示词是"合并错一次就悄悄污染计数"的唯一防线，
     * 措辞的松紧会直接改变线上行为。</p>
     */
    static final String TOPIC_ADJUDICATION_PROMPT = """
            你在维护一个人的关注主题列表。下面给出「已有主题」和「新出现的说法」。

            对每个新说法，判断它和某个已有主题**说的是不是同一件事**——注意是同一件事，不是有关系。

            判为同一件事时，如果其中一个名字明显更完整、更准确，可以在 label 里给出应该保留的那个名字：
            - 「CI 流水线」和「持续集成流水线」→ label 用全称「持续集成流水线」。
            - 「PostgreSQL 连接池」和「PostgreSQL 连接池调优」→ label 用更具体的那个。
            - 两个名字差不多好，就不要填 label。
            - **绝对不要**给一个更宽泛的名字（「门店」「系统」「数据库相关」），也不要把两个名字拼起来
              （「A与B」）。名字只能变得更准确，不能变得更笼统——否则每合并一次主题就宽一点，最后变成
              一个什么都装的桶。

            算同一件事：
            - 同义、换个说法、详略不同的同一件事：「店员班次安排」和「门店排班管理」。
            - 加了个无关紧要的限定词：「PostgreSQL 连接池」和「PostgreSQL 连接池问题」。

            不算同一件事：
            - 同一领域里的不同问题：「PostgreSQL 连接池」和「PostgreSQL 备份恢复」。
            - 一个是另一个范围内的**具体查询**：已有「门店排班管理」，新说法是「三号店下周三的排班表」——
              后者确实属于前者的领域，但它是一次具体查询，不是同一个长期关注点。这类要判为不同。
            - 一个是另一个的下位概念：「数据库」和「PostgreSQL 连接池」。

            拿不准就判不同。合并错了会把两件事的计数混在一起、事后完全看不出来；没合并只是暂时多一条。

            只输出 JSON：
            {"resolutions":[{"index":<新说法的序号>,"same_as":<已有主题的序号，没有则 null>,"label":<更好的名字，没有则 null>}]}""";

    /** 话题裁决的响应 schema。 */
    static final String TOPIC_ADJUDICATION_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "resolutions": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "index": {"type": "integer"},
                      "same_as": {"type": ["integer", "null"]},
                      "label": {"type": ["string", "null"]}
                    },
                    "required": ["index", "same_as"]
                  }
                }
              },
              "required": ["resolutions"]
            }""";

    /**
     * 第 3 层——问模型，什么都没匹配上的那些标签
     * 是不是真的是新主体。
     *
     * <p>它对一次抽取运行里**全部**未解析的标签只跑一次，而不是每个标签跑一次：
     * 这里真正贵的是往返，而所有标签的判定形状是一样的。</p>
     */
    void adjudicateTopics(MemoryRunBudget budget, String modelId, List<MemoryTopicStat> existing,
                          List<Resolution> resolutions, List<Integer> unresolved) {
        if (modelId == null || modelId.isEmpty()) {
            // 没有东西可以回落。这里的每一个标签都会变成它自己的主体，这是错的答案，
            // 但是一个**可见**的错——相对于悄悄跳过这一层：那正是它直接去读配置里的
            // 抽取模型时发生的事。留空是**默认值**、意思是"用对话模型"，
            // 于是在一个默认工作区上模型层从不运行，每一种改写都待在自己那一行、
            // 命中数停在 1，永远够不到提升阈值。
            log.warn("memory: no model available to resolve {} new topics", unresolved.size());
            return;
        }
        com.ragagent.llm.LlmChatClient chatModel;
        try {
            chatModel = modelResolver.getChatModel(modelId);
        } catch (RuntimeException e) {
            log.warn("memory: topic adjudication model unavailable: {}", e.toString());
            return;
        }
        if (chatModel == null) {
            log.warn("memory: topic adjudication model unavailable");
            return;
        }

        StringBuilder b = new StringBuilder();
        b.append("已有主题：\n");
        for (int i = 0; i < existing.size(); i++) {
            b.append('[').append(i).append("] ").append(existing.get(i).getTopic()).append('\n');
        }
        b.append("\n新出现的说法：\n");
        for (int idx : unresolved) {
            b.append('[').append(idx).append("] ").append(resolutions.get(idx).surface).append('\n');
        }

        ChatOptions options = new ChatOptions();
        options.setTemperature(0);
        options.setMaxCompletionTokens(800);
        // 关思考，理由同 completeExtraction：在这里悄悄什么都拿不到，
        // 会让每一种改写都落到自己那一行。
        options.setThinking(Boolean.FALSE);
        options.setFormat(readTree(TOPIC_ADJUDICATION_SCHEMA));

        ChatResponse response;
        try {
            response = budget.chat(chatModel,
                    List.of(ChatMessage.system(TOPIC_ADJUDICATION_PROMPT), ChatMessage.user(b.toString())),
                    options);
        } catch (MemoryRunBudget.RunExpiredException e) {
            log.warn("memory: topic adjudication failed: {}", e.toString());
            return;
        } catch (RuntimeException e) {
            log.warn("memory: topic adjudication failed: {}", e.toString());
            return;
        }
        if (response == null) {
            log.warn("memory: topic adjudication failed: null response");
            return;
        }

        String content = response.getContent() == null ? "" : response.getContent().strip();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return;
        }
        List<IndexedResolution> parsed;
        try {
            JsonNode root = MAPPER.readTree(content.substring(start, end + 1));
            parsed = new ArrayList<>();
            JsonNode rows = root.get("resolutions");
            if (rows != null && rows.isArray()) {
                for (JsonNode row : rows) {
                    IndexedResolution one = new IndexedResolution();
                    one.index = row.path("index").asInt();
                    one.hasSameAs = row.hasNonNull("same_as");
                    one.sameAs = one.hasSameAs ? row.get("same_as").asInt() : 0;
                    one.label = row.path("label").isNull() ? "" : row.path("label").asText("");
                    parsed.add(one);
                }
            }
        } catch (Exception e) {
            log.warn("memory: unparsable topic adjudication: {}", e.toString());
            return;
        }

        Set<Integer> pending = new HashSet<>(unresolved);
        for (IndexedResolution decision : parsed) {
            // 只有这次调用**真的**被问到的标签才可能被重新指派。一个返回了没给过它的
            // 下标的模型，绝不能覆盖掉更早、更可靠的那一层已经做出的匹配。
            if (!pending.contains(decision.index)) {
                continue;
            }
            if (!decision.hasSameAs) {
                continue;
            }
            int target = decision.sameAs;
            if (target < 0 || target >= existing.size()) {
                continue;
            }
            resolutions.get(decision.index).canonical = existing.get(target);
            resolutions.get(decision.index).tier = "model";
            // 一次没有任何字面证据支持的合并，是最可能出错的那种，所以它连同两个标签
            // 一起记日志，而不是只表现为一个用户从没命名过的主体上被加了一的计数。
            log.info("memory: model merged topic {} into {}", resolutions.get(decision.index).surface,
                    existing.get(target).getTopic());

            String proposed = MemoryText.sanitizeMemoryTopic(decision.label);
            if (proposed.isEmpty()) {
                continue;
            }
            if (!MemoryKeys.topicLabelIsAnImprovement(existing.get(target).getTopic(),
                    resolutions.get(decision.index).surface, proposed)) {
                log.info("memory: rejected proposed label {} for {}",
                        proposed, existing.get(target).getTopic());
                continue;
            }
            resolutions.get(decision.index).mergedLabel = proposed;
        }
    }

    /** 裁决输出里的一条解析（index / sameAs / label）。 */
    private static final class IndexedResolution {
        int index;
        boolean hasSameAs;
        int sameAs;
        String label = "";
    }

    static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("unparsable embedded schema", e);
        }
    }
}
