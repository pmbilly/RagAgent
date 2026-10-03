package com.ragagent.chatpipeline.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.ragagent.chatpipeline.support.QueryTokenizer;

/**
 * 查询关键词/分词静态工具（自 {@link PluginSearch} 拆出，全静态）：中英停用词、疑问词剥离、
 * 引号短语/分隔符切分与中英混合 tokenize。
 */
final class QueryTextOps {

    // ----- 中英停用词 -----

    static final Set<String> STOPWORDS = Set.of(
            "的", "是", "在", "了", "和", "与", "或",
            "a", "an", "the", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had",
            "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "can",
            "to", "of", "in", "for", "on", "with", "at",
            "by", "from", "as", "into", "through", "about",
            "what", "how", "why", "when", "where", "which",
            "who", "whom", "whose");

    /** 中文疑问词前缀（锚定开头）。 */
    static final Pattern QUESTION_WORDS =
            Pattern.compile("^(什么是|什么|如何|怎么|怎样|为什么|为何|哪个|哪些|谁|何时|何地|请问|请告诉我|帮我|我想知道|我想了解)");

    public static List<String> extractKeywords(String text) {
        List<String> words = tokenize(text);
        List<String> keywords = new ArrayList<>(words.size());
        for (String w : words) {
            String lower = w.toLowerCase(java.util.Locale.ROOT);
            if (!STOPWORDS.contains(lower) && runeCount(w) > 1) {
                keywords.add(w);
            }
        }
        return keywords;
    }

    public static List<String> extractPhrases(String text) {
        List<String> phrases = new ArrayList<>();
        java.util.regex.Matcher m = QUOTED_PHRASE.matcher(text);
        while (m.find()) {
            // 按 UTF-8 字节数 > 2（CJK 短语两字 = 6 字节仍入选）
            if (m.groupCount() >= 1
                    && m.group(1).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2) {
                phrases.add(m.group(1));
            }
        }
        return phrases;
    }

    /** 引号对（直双引号、直单引号、「」『』）。 */
    static final Pattern QUOTED_PHRASE =
            Pattern.compile("[\"'\u300c\u300d\u300e\u300f]([^\"'\u300c\u300d\u300e\u300f]+)[\"'\u300c\u300d\u300e\u300f]");

    static final Pattern DELIMITERS = Pattern.compile("[,，;；、。！？!?\\s]+");

    public static List<String> splitByDelimiters(String text) {
        String[] parts = DELIMITERS.split(text);
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String v = p.trim();
            if (!v.isEmpty()) {
                result.add(v);
            }
        }
        return result;
    }

    public static String removeQuestionWords(String text) {
        return QUESTION_WORDS.matcher(text).replaceAll("").trim();
    }

    /**
     * Han 连续段走 jieba CutForSearch（{@link QueryTokenizer} 分词 seam，
     * 已知降级：默认实现为二字滑窗，可注入真实分词器恢复）；字母数字段整段成词。
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean[] currentIsHan = new boolean[] {false};

        Runnable flush = () -> {
            if (current.length() == 0) {
                return;
            }
            if (currentIsHan[0]) {
                for (String word : QueryTokenizer.cutForSearch(current.toString())) {
                    String w = word.trim();
                    if (!w.isEmpty()) {
                        tokens.add(w);
                    }
                }
            } else {
                tokens.add(current.toString());
            }
            current.setLength(0);
            currentIsHan[0] = false;
        };

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isHan(cp)) {
                if (current.length() > 0 && !currentIsHan[0]) {
                    flush.run();
                }
                currentIsHan[0] = true;
                current.appendCodePoint(cp);
            } else if (Character.isLetter(cp) || Character.isDigit(cp)) {
                if (current.length() > 0 && currentIsHan[0]) {
                    flush.run();
                }
                currentIsHan[0] = false;
                current.appendCodePoint(cp);
            } else {
                flush.run();
            }
            i += Character.charCount(cp);
        }
        flush.run();
        return tokens;
    }

    /** Unicode Han 判定。 */
    static boolean isHan(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN;
    }

    static int runeCount(String s) {
        return s.codePointCount(0, s.length());
    }
}
