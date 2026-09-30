package com.ragagent.memory.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.common.settings.MemoryKeys;

/**
 * 情境召回的字面排序（对照 Go
 * {@code internal/application/service/memory/lexical.go} 全文）。
 *
 * <p><b>为什么情境召回走字面而不是向量</b>：一个主体只存几百条一行一条的记忆，
 * 扫一遍比一次 embedding 往返更便宜，而且让读路径既不碰模型调用也不碰向量库。
 * 若真实使用显示字面匹配漏掉了改写，加向量索引是一处孤立改动：
 * 会动的只有这个排序函数。</p>
 *
 * <h2>三处必须照抄的口径</h2>
 * <ol>
 *   <li>{@code tokenize} 与记忆 key 的归一化<b>用同一个字母表</b>，
 *       否则查询与已存条目不在同一个坐标系里比较。CJK 没有词分隔符，按**表意字**拆；
 *       其余按非字母数字拆。</li>
 *   <li>二元组**只由相邻的两个汉字**构成——单个汉字本身匹配得太宽
 *       （"数"出现在 数据/数量/参数 里），所以两个连续汉字额外计分且权重更高。</li>
 *   <li>身份用**对象身份**：Go 的 {@code map[*types.MemoryItem]int} 是指针键，
 *       内容相同的两个对象是两个键。Java 侧用 {@link IdentityHashMap} 照抄——
 *       {@link MemoryItem} 没有覆写 {@code equals}，但显式写出来才不会被将来的一次
 *       {@code equals} 引入悄悄改掉语义。</li>
 * </ol>
 */
public final class MemoryLexical {

    private MemoryLexical() {}

    /**
     * 相关性下限。注入一条弱相关的记忆不只是浪费上下文，
     * 更糟的是**邀请模型去用它**——一条仅仅与问题共用一个词条的陈旧笔记，
     * 比没有笔记更有害。
     */
    public static final double MIN_RECALL_SCORE = 0.15;

    /**
     * 对照 Go {@code tokenize}：与 {@code NormalizeMemoryKey} 同一套拆分。
     *
     * <p>{@code strings.ToLower} → {@code toLowerCase(Locale.ROOT)}：默认 locale 会在
     * 土耳其语环境把 {@code I} 变成 {@code ı}，而 Go 与该 locale 无关。</p>
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String lowered = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (int cp : lowered.codePoints().toArray()) {
            if (isHan(cp)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                tokens.add(new String(Character.toChars(cp)));
            } else if (Character.isLetter(cp) || Character.isDigit(cp)) {
                current.appendCodePoint(cp);
            } else {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** 对照 Go {@code bigrams}：只把相邻的两个**单汉字**配成对。 */
    public static List<String> bigrams(List<String> tokens) {
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            String a = tokens.get(i);
            String b = tokens.get(i + 1);
            if (isSingleHan(a) && isSingleHan(b)) {
                pairs.add(a + b);
            }
        }
        return pairs;
    }

    private static boolean isSingleHan(String token) {
        return token.codePointCount(0, token.length()) == 1 && isHan(token.codePointAt(0));
    }

    /**
     * 对照 Go {@code unicode.Is(unicode.Han, r)}。
     *
     * <p>与 {@code MemoryKeys} 内部那个同名方法是同一实现——那边是包私有，
     * 跨包取不到，所以这里重写一份（与 {@code McpServiceController} 的处置一致）。</p>
     */
    static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    /** 对照 Go {@code scoredItem}：一条被打分的条目。 */
    public static final class ScoredItem {
        private final MemoryItem item;
        private final double score;

        ScoredItem(MemoryItem item, double score) {
            this.item = item;
            this.score = score;
        }

        public MemoryItem item() {
            return item;
        }

        public double score() {
            return score;
        }
    }

    /**
     * 对照 Go {@code scoreItems}：把情境条目对着当前查询打分。
     *
     * <p>打分刻意简单：查询 token 与条目的重合，二元组命中权重更高，
     * 重要度只用来**破平局**。</p>
     *
     * <p>内容与主题分开索引，所以没有二元组会跨越两者的边界；归一化 key
     * {@code 刻意不用}——它是为碰撞检测造的字符袋，相邻关系没有意义。</p>
     */
    public static List<ScoredItem> scoreItems(String query, List<MemoryItem> items) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || items == null || items.isEmpty()) {
            return List.of();
        }

        Set<String> queryUnigrams = new HashSet<>();
        for (String token : queryTokens) {
            // 单个拉丁字符不承载信号、处处匹配。
            if (token.codePointCount(0, token.length()) < 2 && !isSingleHan(token)) {
                continue;
            }
            queryUnigrams.add(token);
        }
        Set<String> queryBigrams = new HashSet<>(bigrams(queryTokens));
        if (queryUnigrams.isEmpty() && queryBigrams.isEmpty()) {
            return List.of();
        }

        List<ScoredItem> scored = new ArrayList<>(items.size());
        for (MemoryItem item : items) {
            if (item == null) {
                continue;
            }
            Set<String> itemUnigrams = new HashSet<>();
            Set<String> itemBigrams = new HashSet<>();
            for (String text : List.of(item.getContent(), item.getTopic())) {
                List<String> tokens = tokenize(text);
                itemUnigrams.addAll(tokens);
                itemBigrams.addAll(bigrams(tokens));
            }
            if (itemUnigrams.isEmpty()) {
                continue;
            }

            double hits = 0;
            for (String token : queryUnigrams) {
                if (itemUnigrams.contains(token)) {
                    hits++;
                }
            }
            for (String pair : queryBigrams) {
                if (itemBigrams.contains(pair)) {
                    hits += 2;
                }
            }
            if (hits == 0) {
                continue;
            }
            // 按查询长度归一，免得长查询仅仅因为"能对上更多"而偏爱长条目。
            double denominator = queryUnigrams.size() + 2.0 * queryBigrams.size();
            if (denominator == 0) {
                continue;
            }
            scored.add(new ScoredItem(item, hits / denominator + 0.01 * item.getImportance()));
        }

        // sort.SliceStable：分数相同时按 valid_from 降序。Java 的 List.sort 同样是稳定排序。
        scored.sort((a, b) -> {
            if (a.score != b.score) {
                return Double.compare(b.score, a.score);
            }
            return b.item.getValidFrom().toInstant().compareTo(a.item.getValidFrom().toInstant());
        });
        return scored;
    }

    /**
     * 对照 Go {@code lexicalRanking}：越过字面门槛的条目的**下标**，最优在前。
     *
     * <p>用下标而不是 id，是因为一次排序只对产生它的那个切片有意义，
     * 而且条目**不保证带 id**——按 id 索引会把所有无 id 的条目悄悄塌成同一条。</p>
     */
    public static List<Integer> lexicalRanking(String query, List<MemoryItem> items) {
        if (items == null) {
            return List.of();
        }
        Map<MemoryItem, Integer> index = new IdentityHashMap<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) != null) {
                index.put(items.get(i), i);
            }
        }
        List<ScoredItem> scored = scoreItems(query, items);
        List<Integer> ranked = new ArrayList<>(scored.size());
        for (ScoredItem entry : scored) {
            if (entry.score < MIN_RECALL_SCORE) {
                break;
            }
            Integer i = index.get(entry.item);
            if (i != null) {
                ranked.add(i);
            }
        }
        return ranked;
    }

    /**
     * 对照 Go {@code takeWithinBudget}：把一次排序兑现成条目，
     * 在条数上限处停下，并**跳过**任何不再符合 rune 预算的条目。
     *
     * <p>超预算时跳过而不是停止是刻意的：一条长记忆不该把后面几条短的一起挡在门外。</p>
     */
    public static List<MemoryItem> takeWithinBudget(List<Integer> ranking, List<MemoryItem> items,
                                                    int maxItems, int runeBudget) {
        List<MemoryItem> selected = new ArrayList<>(Math.max(maxItems, 0));
        int used = 0;
        for (int index : ranking) {
            if (selected.size() >= maxItems) {
                break;
            }
            if (index < 0 || index >= items.size()) {
                continue;
            }
            MemoryItem item = items.get(index);
            if (item == null) {
                continue;
            }
            int cost = MemoryKeys.runeLength(item.getContent()) + 3;
            if (used + cost > runeBudget) {
                continue;
            }
            selected.add(item);
            used += cost;
        }
        return selected;
    }

    /** 对照 Go {@code selectRecallItems}：在两个预算内取最好的匹配。 */
    public static List<MemoryItem> selectRecallItems(String query, List<MemoryItem> items,
                                                     int maxItems, int runeBudget) {
        return takeWithinBudget(lexicalRanking(query, items), items, maxItems, runeBudget);
    }

    /** 对照 Go {@code tokenSet}：把 token 列表去重成集合（合并模块共用）。 */
    public static Set<String> tokenSet(List<String> tokens) {
        Set<String> set = new HashSet<>(Math.max(tokens.size(), 1));
        set.addAll(tokens);
        return set;
    }

    /** 对照 Go {@code jaccard}：两个 token 列表的重合度。 */
    public static double jaccard(List<String> a, List<String> b) {
        return jaccardSets(tokenSet(a), tokenSet(b));
    }

    /**
     * 对照 Go {@code jaccardSets}：两个已备好的 token 集合的重合度。
     *
     * <p>空集合回 0（不是 1）：两条都为空串的记忆"重合"没有意义。</p>
     */
    public static double jaccardSets(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        // 走小的一侧；结果两边对称。
        if (b.size() < a.size()) {
            Set<String> swap = a;
            a = b;
            b = swap;
        }
        int shared = 0;
        for (String token : a) {
            if (b.contains(token)) {
                shared++;
            }
        }
        int union = a.size() + b.size() - shared;
        if (union == 0) {
            return 0;
        }
        return (double) shared / (double) union;
    }

    /** 供 {@code mergeCandidates} 预建 token 集合用（对照 Go 的 {@code map[id]set}）。 */
    public static Map<String, Set<String>> buildTokenSets(List<MemoryItem> items) {
        Map<String, Set<String>> tokens = new HashMap<>();
        for (MemoryItem item : items) {
            if (item == null) {
                continue;
            }
            tokens.put(item.getId(), tokenSet(tokenize(item.getTopic() + " " + item.getContent())));
        }
        return tokens;
    }
}
