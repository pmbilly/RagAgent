package com.ragagent.memory.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 冲突检测 key 与主题相似度（对照 Go internal/types/memory.go 的
 * {@code MemoryItemKey} L554-559、{@code NormalizeMemoryKey} L565-612、
 * {@code NormalizeTopicKey} L1225-1255、{@code TopicSimilarity} L1263-1302）。
 *
 * <h2>Java 侧必须逐条对齐的三处</h2>
 * <ol>
 *   <li><b>{@code strings.ToLower} → {@code toLowerCase(Locale.ROOT)}</b>：
 *       用默认 locale 会在土耳其语环境把 {@code I} 变成 {@code ı}，
 *       而 Go 的 {@code strings.ToLower} 与该 locale 无关。</li>
 *   <li><b>{@code sort.Strings} 是 UTF-8 字节序</b>，不是 Java 的 UTF-16 码元序。
 *       两者只在「BMP 的 U+E000–U+FFFF 与增补平面混排」时分叉——本项目
 *       目前全是 BMP（中文在 U+4E00–U+9FFF），但既然 §9 已经因为 map 键序踩过一次，
 *       这里就用同一套 {@link #utf8Compare}。（{@code MemoryRender} 的排序同理。）</li>
 *   <li><b>rune 计数</b>：Go 的 {@code len([]rune(s))} 是码点数；
 *       Java 的 {@code String.length()} 是 UTF-16 码元。所有截断都走
 *       {@link #runeLength}/{@link #runeSlice}，别直接用 {@code substring}。</li>
 * </ol>
 *
 * <p>{@code unicode.Is(unicode.Han, r)} 用 {@link Character.UnicodeScript#HAN} 表达：
 * 两者都是 Unicode 的 Han script 属性，覆盖范围一致。</p>
 */
public final class MemoryKeys {

    private MemoryKeys() {}

    // ── 主题归一化 ─────────────────────────────────────────────────────────

    /**
     * 承载不了主语信息的字符：从主题 key 里丢掉它们，
     * 好让"门店的排班管理"与"门店排班管理"被认成同一个主题。
     */
    private static final Set<Integer> TOPIC_NOISE_RUNES = Set.of(
            (int) '的', (int) '了', (int) '地', (int) '得', (int) '之', (int) '与', (int) '和',
            (int) '及', (int) '在', (int) '是', (int) '有', (int) '个', (int) '等', (int) '对',
            (int) '于');

    /**
     * 结尾的语气词/限定词：人与模型会 interchangeable 地给同一个主题加上它们，
     * "PostgreSQL 连接池"与"PostgreSQL 连接池问题"是一个主题、不是两个。
     *
     * <p><b>顺序有语义</b>：Go 的循环遇到第一个能去掉且结果非空的后缀就 {@code break}。
     * 注意 {@code "相关问题"} 排在 {@code "问题"} 前面——先试长的。</p>
     */
    private static final List<String> TOPIC_NOISE_WORDS = List.of(
            "相关问题", "相关", "问题", "方面", "情况", "事宜", "工作", "方向");

    private static final int TOPIC_KEY_MAX_RUNES = 120;
    private static final int MEMORY_KEY_MAX_RUNES = 200;

    /**
     * 对照 Go {@code MemoryItemKey}：一条已存记忆的冲突检测 key。
     *
     * <p>记忆的身份是它的**主题**，不是它的措辞："生产库用的是 MySQL"与
     * "生产库用的是 PostgreSQL"是同一条笔记换了个值，后者必须**取代**前者而不是并排躺着。
     * 这只有在"同一主题的两种标签给出同一个 key"时才成立，所以这里用的是主题归一化器
     * 而不是下面那个字符袋 key——主题计数需要它，也是同一个理由。</p>
     *
     * <p>content 是"陈述到达时没有主题"时的兜底：那时没有更好的东西可以 key，
     * 而词序确实不应该有影响。</p>
     */
    public static String itemKey(String topic, String content) {
        String key = normalizeTopicKey(topic);
        if (key != null && !key.isEmpty()) {
            return key;
        }
        return normalizeMemoryKey(topic, content);
    }

    /**
     * 对照 Go {@code NormalizeMemoryKey}：一条陈述的冲突检测 key。
     *
     * <p>调用方可以自带 key（抽取模型会被要求给一个）；没有时回落到
     * content 里的实词，让同一事实的两种说法仍然撞在一起。</p>
     */
    public static String normalizeMemoryKey(String key, String content) {
        String candidate = key == null ? "" : key.strip();
        if (candidate.isEmpty()) {
            candidate = content == null ? "" : content;
        }
        candidate = candidate.toLowerCase(Locale.ROOT);

        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int[] cps = candidate.codePoints().toArray();
        for (int cp : cps) {
            if (isHan(cp)) {
                // CJK 没有词分隔符，所以每个表意字自成一个 token。
                if (current.length() > 0) {
                    words.add(current.toString());
                    current.setLength(0);
                }
                words.add(new String(Character.toChars(cp)));
            } else if (Character.isLetter(cp) || Character.isDigit(cp)) {
                current.appendCodePoint(cp);
            } else {
                if (current.length() > 0) {
                    words.add(current.toString());
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) {
            words.add(current.toString());
            current.setLength(0);
        }

        // 排序 + 去重让 key 不敏感于词序，于是"偏好 数据库"与"数据库 偏好"描述同一主题。
        // ⚠️ Go 的去重保留**首次出现**，随后 sort.Strings 才排序——用 LinkedHashSet 照抄。
        Set<String> unique = new LinkedHashSet<>(words);
        String[] sorted = unique.toArray(new String[0]);
        Arrays.sort(sorted, MemoryKeys::utf8Compare);

        String result = String.join("-", sorted);
        if (runeLength(result) > MEMORY_KEY_MAX_RUNES) {
            result = runeSlice(result, MEMORY_KEY_MAX_RUNES);
        }
        return result;
    }

    /**
     * 对照 Go {@code NormalizeTopicKey}：把主题标签压成一个稳定的身份 key。
     *
     * <p>**刻意不是** {@link #normalizeMemoryKey}。后者排序去重字符——对记忆条目说得通
     * （词序不该有影响，漏掉的靠包含判定兜住），但作为主题身份两个方向都错：
     * 它会把"门店排班管理"与"门店的排班管理"当成不同主题（差一个字符），
     * 又会把两个异位词当成同一个。</p>
     *
     * <p>这里保留顺序，只去掉真正无信息的字符与结尾限定词。模型会产生变化但并非纯装饰的
     * 东西（同义词、不同说法）留给解析器——它有的手段不止字符串比较。</p>
     */
    public static String normalizeTopicKey(String topic) {
        String lowered = topic == null ? "" : topic.strip().toLowerCase(Locale.ROOT);
        if (lowered.isEmpty()) {
            return "";
        }

        StringBuilder b = new StringBuilder();
        for (int cp : lowered.codePoints().toArray()) {
            if (TOPIC_NOISE_RUNES.contains(cp)) {
                continue;
            }
            if (isHan(cp) || Character.isLetter(cp) || Character.isDigit(cp)) {
                b.appendCodePoint(cp);
            }
        }
        String key = b.toString();

        for (String suffix : TOPIC_NOISE_WORDS) {
            if (key.endsWith(suffix) && !key.equals(suffix)) {
                String trimmed = key.substring(0, key.length() - suffix.length());
                if (!trimmed.isEmpty()) {
                    key = trimmed;
                }
                break;
            }
        }

        if (runeLength(key) > TOPIC_KEY_MAX_RUNES) {
            key = runeSlice(key, TOPIC_KEY_MAX_RUNES);
        }
        return key;
    }

    // ── 主题相似度与门禁 ───────────────────────────────────────────────────

    /**
     * 对照 Go {@code TopicSimilarity}：两个主题标签在共享字符二元组上的得分。
     *
     * <p>用二元组而不是整词，因为中文没有词分隔符；用 Dice 而不是 Jaccard，
     * 因为它对一个标签比另一个长更宽容——而"模型把话说长"（"排班管理" vs
     * "门店排班管理"）正是常见情形。</p>
     */
    public static double topicSimilarity(String a, String b) {
        Set<String> left = topicBigrams(a);
        Set<String> right = topicBigrams(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int shared = 0;
        for (String gram : left) {
            if (right.contains(gram)) {
                shared++;
            }
        }
        return 2 * (double) shared / (double) (left.size() + right.size());
    }

    /** 对照 Go {@code topicBigrams}：归一化 key 的字符二元组集合。 */
    public static Set<String> topicBigrams(String topic) {
        int[] runes = normalizeTopicKey(topic).codePoints().toArray();
        Set<String> grams = new HashSet<>();
        if (runes.length == 0) {
            return grams;
        }
        if (runes.length == 1) {
            grams.add(new String(Character.toChars(runes[0])));
            return grams;
        }
        for (int i = 0; i + 1 < runes.length; i++) {
            grams.add(new String(Character.toChars(runes[i]))
                    + new String(Character.toChars(runes[i + 1])));
        }
        return grams;
    }

    /**
     * 对照 Go {@code TopicIsSpecificEnoughToMatchLoosely}：模糊匹配的门禁。
     *
     * <p>Graphiti 出于同样的理由对低熵名字跳过模糊匹配：两个字的标签上，
     * 共享一个二元组就占了分数的大半，于是模糊匹配产出的多半是错误合并。
     * 短标签落到解析器更慢、更准的那一层去。</p>
     */
    public static boolean topicIsSpecificEnoughToMatchLoosely(String topic) {
        return runeLength(normalizeTopicKey(topic)) >= 4;
    }

    /**
     * 对照 Go {@code TopicLabelIsAnImprovement}：两个主题合并时，
     * 提议的标签能否替换掉当前规范标签。
     *
     * <p>合并后活下来的标签目前只是"先到的那个"，这很随意——而它是有后果的：
     * 兴趣会作为词汇喂给查询改写器、也会展示给用户看我们以为他在乎什么。
     * 让模型提议一个更好的名字值得做，但它打开了一个棘轮：被问"这两个标签的共同点是什么"的
     * 模型每次都会往更宽的东西上靠，几次合并之后这个主题就成了一把什么都代表不了的伞。</p>
     *
     * <p>所以替换必须更**完整**，绝不更**宽泛**。这与做了重命名的实体消解系统收敛到的做法一致——
     * Graphiti 折叠重复时保留更具体的节点，现实中 LLM 驱动的合并也挑名字的最完整形式，
     * 而不是给它一个类别。</p>
     */
    public static boolean topicLabelIsAnImprovement(String canonical, String incoming, String proposed) {
        String proposedKey = normalizeTopicKey(proposed);
        String canonicalKey = normalizeTopicKey(canonical);
        if (proposedKey.isEmpty() || proposedKey.equals(canonicalKey)) {
            return false;
        }
        if (runeLength(proposed) > 80) {
            return false;
        }

        // 丢掉当前标签承载的内容就是泛化，这是唯一一个绝不允许移动的方向。
        if (canonicalKey.contains(proposedKey)) {
            return false;
        }
        if (runeLength(proposedKey) < runeLength(canonicalKey)) {
            return false;
        }

        // 提议必须同时锚定它声称要统一的两个标签；与两者都共享甚少的名字是凭空发明，不是合并。
        final double minAnchor = 0.30;
        return topicSimilarity(proposed, canonical) >= minAnchor
                && topicSimilarity(proposed, incoming) >= minAnchor;
    }

    /**
     * 对照 Go {@code TopicLooksLikeOneQuestion}：这个标签是不是在给**单个提问**命名，
     * 而不是在给一个主题命名。
     *
     * <p>主题必须复现才有意义：它被计数，只有几个会话都碰到它才变成记忆。
     * 像"v2.3版本orders接口分页参数默认值查询"这样的标签只能匹配它自己，
     * 于是被数一次、然后永远停在一。计数机制死了，而且没人说得出。
     * 长度是个粗暴的代理指标，但一个用两打字符都命名不出来的主题，
     * 承载的是某一个问题的参数。这个方法不拒绝任何东西——直接丢掉标签会失去全部信号，
     * 修法在提示词里。它存在的意义是让这个失败在日志里可见，而不是只在某个人恰好打开的 trace 里。</p>
     */
    public static boolean topicLooksLikeOneQuestion(String topic) {
        return runeLength(normalizeTopicKey(topic)) > 24;
    }

    // ── rune / 字节序工具（本模块内部共用） ────────────────────────────────

    /** 对照 Go 的 {@code len([]rune(s))}——**码点数**，不是 UTF-16 码元数。 */
    public static int runeLength(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /** 对照 Go 的 {@code string([]rune(s)[:n])}（越界不报错，取到末尾为止）。 */
    public static String runeSlice(String s, int maxRunes) {
        if (s == null || maxRunes <= 0) {
            return "";
        }
        int count = runeLength(s);
        if (count <= maxRunes) {
            return s;
        }
        int end = s.offsetByCodePoints(0, maxRunes);
        return s.substring(0, end);
    }

    /**
     * 对照 Go 的 {@code sort.Strings}——**按 UTF-8 字节**逐字节比较。
     *
     * <p>不是 {@code String.compareTo}：后者比 UTF-16 码元，仅当
     * 「BMP 的 U+E000–U+FFFF」与增补平面字符混排时才分叉（§9 的 map 键序结论同源）。</p>
     */
    public static int utf8Compare(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(left.length, right.length);
        for (int i = 0; i < n; i++) {
            int x = left[i] & 0xFF;
            int y = right[i] & 0xFF;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    /** 对照 Go {@code unicode.Is(unicode.Han, r)}。 */
    static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }
}
