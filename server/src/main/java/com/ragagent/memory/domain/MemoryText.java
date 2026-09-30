package com.ragagent.memory.domain;

import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.common.settings.MemoryKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 记忆文本的清洗、脱敏、指纹与显式指令识别
 * （对照 Go internal/types/memory.go L614-698、L842-880、L1137-1170）。
 *
 * <h2>三处刻意的对齐（都是 §9 记过的 Java/Go 差异）</h2>
 * <ol>
 *   <li><b>rune 计数</b>：{@code MemoryContentMaxRunes} 是**码点**数，
 *       不是 {@code String.length()}（UTF-16 码元）。中文里后者会数对，
 *       但 emoji/增补平面就会算成两倍，所以统一走 {@link MemoryKeys#runeLength}。</li>
 *   <li><b>{@code strings.Fields} 的空白定义</b>：Go 的 {@code unicode.IsSpace}
 *       是 Unicode 的 White_Space 属性，等价于 Java 的
 *       {@code Character.isSpaceChar(c) || c ∈ {09,0A,0B,0C,0D,85}}。
 *       <b>不能</b>用 {@code Character.isWhitespace}：它把不换行空格
 *       （U+00A0/U+2007/U+202F）排除在外，与 Go 相反。</li>
 *   <li><b>{@code strings.ToLower} 与 locale 无关</b>：一律 {@code Locale.ROOT}。</li>
 * </ol>
 *
 * <h2>⚠️ 正则逐条对照的注意点</h2>
 * <p>{@code \b} 两边都当 ASCII 词边界（Go 的 RE2 本就是 ASCII-only；
 * Java 默认 {@code \w} 也是 {@code [A-Za-z0-9_]}），所以 {@code \b密码} 在两边的
 * **都**匹配不上——Go 的注释正是这么说的，CJK 那条规则刻意不写 {@code \b}，别顺手补。</p>
 * <p>{@code \s} 两边有一处已知差异（约定 §9）：Java 的 {@code \s} 含 {@code \x0B}
 * 而 Go 不含。出现垂直制表符的概率可忽略，故保留差异、不改成显式字符类。</p>
 */
public final class MemoryText {

    private MemoryText() {}

    private static final int TOPIC_MAX_RUNES = 80;

    // ── 清洗 ───────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code SanitizeMemoryContent}：把一条陈述压成一行、并卡进长度预算。
     *
     * <p>记忆会被注入系统提示词，所以换行与控制字符要被压掉，
     * 免得一条记忆自己伪造出提示词结构。</p>
     */
    public static String sanitizeMemoryContent(String content) {
        if (content == null) {
            return "";
        }
        StringBuilder mapped = new StringBuilder(content.length());
        for (int cp : content.codePoints().toArray()) {
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                mapped.append(' ');
            } else if (!Character.isISOControl(cp)) {
                mapped.appendCodePoint(cp);
            }
            // 其余控制字符：丢弃（Go 的 unicode.IsControl → 返回 -1）
        }
        String joined = joinGoFields(mapped.toString());
        if (MemoryKeys.runeLength(joined) > MemoryKinds.CONTENT_MAX_RUNES) {
            joined = MemoryKeys.runeSlice(joined, MemoryKinds.CONTENT_MAX_RUNES).strip();
        }
        return joined;
    }

    /** 对照 Go {@code SanitizeMemoryTopic}：把可读主题压成一行短文本（80 码点）。 */
    public static String sanitizeMemoryTopic(String topic) {
        String sanitized = sanitizeMemoryContent(topic);
        if (MemoryKeys.runeLength(sanitized) > TOPIC_MAX_RUNES) {
            sanitized = MemoryKeys.runeSlice(sanitized, TOPIC_MAX_RUNES).strip();
        }
        return sanitized;
    }

    /**
     * 对照 Go {@code strings.Fields} + {@code strings.Join(…, " ")}。
     *
     * <p>Go 的 {@code unicode.IsSpace} = Unicode White_Space，等价于
     * {@code isSpaceChar} 加上六个 ASCII 制表/换行类字符（含 U+0085 NEL）。</p>
     */
    private static String joinGoFields(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        boolean wroteAny = false;
        for (int cp : s.codePoints().toArray()) {
            if (isGoSpace(cp)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && wroteAny) {
                out.append(' ');
            }
            pendingSpace = false;
            out.appendCodePoint(cp);
            wroteAny = true;
        }
        return out.toString();
    }

    /** 对照 Go {@code unicode.IsSpace}。 */
    static boolean isGoSpace(int cp) {
        if (Character.isSpaceChar(cp)) {
            return true;
        }
        return cp == 0x09 || cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D || cp == 0x85;
    }

    /**
     * 对照 Go {@code ClampMemoryImportance}：把重要度夹在 1..5。
     */
    public static int clampImportance(int importance) {
        if (importance < 1) {
            return 1;
        }
        if (importance > 5) {
            return 5;
        }
        return importance;
    }

    /**
     * 对照 Go {@code NormalizeMemoryForMatch}：把一条陈述压成可比较的形式
     * ——没有大小写、没有空白、没有标点。既用于包含判定的去重，
     * 也用于"被忘掉的东西不许复活"的那个指纹。
     */
    public static String normalizeMemoryForMatch(String content) {
        StringBuilder builder = new StringBuilder();
        String lowered = sanitizeMemoryContent(content).toLowerCase(Locale.ROOT);
        for (int cp : lowered.codePoints().toArray()) {
            if (Character.isLetter(cp) || Character.isDigit(cp)) {
                builder.appendCodePoint(cp);
            }
        }
        return builder.toString();
    }

    /**
     * 对照 Go {@code MemoryFingerprint}：把归一化后的陈述做哈希。
     *
     * <p>墓碑**只留这个哈希，从不留原文**——要求忘掉某件事的用户，
     * 不该让它在另一张表里换个名字继续躺着。</p>
     *
     * @return 64 位小写十六进制；归一化后为空时返回 {@code ""}
     */
    public static String fingerprint(String content) {
        String normalized = normalizeMemoryForMatch(content);
        if (normalized.isEmpty()) {
            return "";
        }
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，不可达；保留抛出而不是静默降级。
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── 敏感信息脱敏 ───────────────────────────────────────────────────────

    /**
     * 绝不允许变成长期笔记的内容的模式。记忆会被注入之后每一轮的系统提示词，
     * 所以落进来的凭据不只是被留存，而是被**反复发给模型**。
     *
     * <p>这份清单刻意"具体"而不是"聪明"。上一版这个功能匹配得太松，
     * 一边把普通的长订单号搅烂、一边又把身份证号的尾巴留在原地——
     * 那是两种坏结果里最糟的一种：用户丢了正确的记忆，留下了敏感的那条。</p>
     */
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            // 各家 provider 的 token，按其文档化的前缀匹配。
            Pattern.compile("\\bsk-[A-Za-z0-9_\\-]{16,}"),
            Pattern.compile("\\bsk_(live|test)_[A-Za-z0-9]{16,}"),
            Pattern.compile("\\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9\\-]{10,}"),
            Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{35}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            // 赋值给"自称是秘密"的东西的值。值的范围止于空白或中文标点：
            // 中文没有空格，贪心的 \S+ 会把句子剩下的部分一起吞掉，
            // 于是连同秘密一起把一整条合法记忆也涂掉。
            Pattern.compile("(?i)\\b(password|passwd|pwd|secret|token|api[_\\- ]?key|access[_\\- ]?key)\\b"
                    + "\\s*[:=＝：]\\s*[^\\s，。、；：！？,;]+"),
            // 这里**不写** \b：Go 的词边界是 ASCII-only 的，中文前永远匹配不上，
            // 加了只会把这条规则静默废掉。
            Pattern.compile("(密码|口令|密钥|秘钥)\\s*[:=＝：是为]?\\s*[^\\s，。、；：！？,;]+"),
            // 中国大陆身份证：锚在一个像样的出生日期上，免得把长订单号和别的 18 位串抓进来。
            Pattern.compile("\\b[1-9]\\d{5}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b"),
            // 银行卡号，可以带空格或横线分组。
            Pattern.compile("\\b\\d{4}[ \\-]?\\d{4}[ \\-]?\\d{4}[ \\-]?\\d{2,7}\\b"),
            // 中国大陆手机号。
            Pattern.compile("\\b1[3-9]\\d{9}\\b"),
            // 不透明的高熵长串：一个认不出来的 token 长什么样。
            Pattern.compile("\\b[A-Za-z0-9_\\-]{40,}\\b"));

    /**
     * 对照 Go {@code RedactSensitive}：从一条陈述里去掉凭据与身份号码。
     *
     * <p>模式**按声明顺序**依次 replaceAll（Go 也是顺序作用于同一个串），
     * 所以某一轮替换出的占位符还会被后续模式看到——它不含数字与字母，
     * 实际不会再被匹配。</p>
     *
     * @return 二元组：脱敏后的文本 + "有没有动过"
     */
    public static Redaction redactSensitive(String content) {
        String redacted = content == null ? "" : content;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll(MemoryKinds.REDACTED_PLACEHOLDER);
        }
        return new Redaction(redacted, !redacted.equals(content));
    }

    /** {@link #redactSensitive} 的返回值，对照 Go 的二元 {@code (string, bool)}。 */
    public record Redaction(String content, boolean changed) {
    }

    /**
     * 对照 Go {@code IsMostlyRedacted}：这条陈述丢得太多，
     * 留下来也只是存一个占位符而不是一条记忆。
     */
    public static boolean isMostlyRedacted(String content) {
        String stripped = content == null ? "" : content.replace(MemoryKinds.REDACTED_PLACEHOLDER, "");
        return MemoryKeys.runeLength(stripped.strip()) < 6;
    }

    // ── 显式记忆指令 ───────────────────────────────────────────────────────

    /**
     * 显式的记忆指令。识别一组固定的前缀让显式写入路径保持确定性：
     * 默认的 {@code explicit_only} 模式下，用户没有字面要求保存的东西**永不**入库，
     * 请求与落库之间也不站一次模型调用。
     *
     * <p><b>顺序有语义</b>：Go 在第一个命中的前缀处就返回。</p>
     */
    private static final List<String> EXPLICIT_MEMORY_PREFIXES = List.of(
            "记住：", "记住:", "记住，", "记住,", "记住 ", "记住",
            "请记住：", "请记住:", "请记住，", "请记住,", "请记住 ", "请记住",
            "帮我记住：", "帮我记住:", "帮我记住，", "帮我记住,", "帮我记住 ", "帮我记住",
            "remember that ", "remember: ", "remember, ", "please remember that ",
            "please remember: ", "note that ", "keep in mind that ");

    /** 对照 Go 的 {@code strings.TrimLeft(statement, "：:，, ")} 字符集。 */
    private static final String TRIM_LEFT_CUTSET = "：:，, ";

    /**
     * 对照 Go {@code DetectExplicitMemory}：从一条"记住…"指令里取出陈述。
     *
     * <p>别的一切都回 {@code ok=false}，**包括没有陈述的裸指令**。</p>
     *
     * <p><b>切片位置用字符数而不是字节数</b>：Go 写的是
     * {@code trimmed[len(prefix):]}，那是**字节**下标；对这里的前缀
     * （全 BMP，中文 3 字节 / Java 1 码元）两者数值不同，但**切点相同**
     * ——都是"跳过前缀那几个字符"，所以 Java 用 {@code substring(prefix.length())}
     * 得到的是同一个结果。别照抄字节数。</p>
     */
    public static Detected detectExplicitMemory(String query) {
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.isEmpty()) {
            return new Detected("", false);
        }
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        for (String prefix : EXPLICIT_MEMORY_PREFIXES) {
            if (!lowered.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String statement = sanitizeMemoryContent(trimmed.substring(prefix.length()).strip());
            statement = trimLeft(statement, TRIM_LEFT_CUTSET);
            if (MemoryKeys.runeLength(statement) < 2) {
                return new Detected("", false);
            }
            return new Detected(statement, true);
        }
        return new Detected("", false);
    }

    /** {@link #detectExplicitMemory} 的返回值，对照 Go 的二元 {@code (string, bool)}。 */
    public record Detected(String statement, boolean detected) {
    }

    private static String trimLeft(String s, String cutset) {
        int i = 0;
        while (i < s.length() && cutset.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return i == 0 ? s : s.substring(i);
    }

    // ── 展示列表合并 ───────────────────────────────────────────────────────

    /**
     * 对照 Go {@code MergeUsedMemories}：合并两份"展示给用户的记忆"，
     * 每个 id 只保留**第一次**出现。
     *
     * <p>一条记忆可以影响一轮两次——一次塑造检索、一次被引在答案里——
     * 而用户应该只看到它列一次。</p>
     *
     * <p>两个照抄的细节：{@code additional} 为空时**原样返回 existing**
     * （同一个列表对象，不是副本）；id 为空的条目**不参与去重**、一律追加。</p>
     */
    public static <T> List<T> mergeUsedMemories(List<T> existing, List<T> additional,
                                                java.util.function.Function<T, String> idOf) {
        if (additional == null || additional.isEmpty()) {
            return existing;
        }
        List<T> base = existing == null ? new ArrayList<>() : existing;
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<T> merged = new ArrayList<>(base.size() + additional.size());
        for (List<T> list : List.of(base, additional)) {
            for (T item : list) {
                String id = idOf.apply(item);
                if (id != null && !id.isEmpty()) {
                    if (!seen.add(id)) {
                        continue;
                    }
                }
                merged.add(item);
            }
        }
        return merged;
    }
}
