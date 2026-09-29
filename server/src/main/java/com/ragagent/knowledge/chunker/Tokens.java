package com.ragagent.knowledge.chunker;

import java.util.Map;

/**
 * 语言感知的 token 数估算。
 * <p>不引入 tokenizer 依赖，按语言使用 chars/token 比率（取自常见 embedding 模型词表），
 * 数字偏保守（倾向高估），保证 chunk 留在模型限制内。</p>
 */
public final class Tokens {

    public static final String LANG_ENGLISH = "en";
    public static final String LANG_GERMAN = "de";
    public static final String LANG_CHINESE = "zh";
    public static final String LANG_MIXED = "mixed";

    private static final Map<String, Double> CHARS_PER_TOKEN = Map.of(
            LANG_ENGLISH, 4.0,
            LANG_GERMAN, 4.5,
            LANG_CHINESE, 1.7,
            LANG_MIXED, 3.0);

    private Tokens() {
    }

    public static int approxTokenCount(String s, String lang) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return approxTokenCountFromRuneLen(CodePoints.len(s), lang);
    }

    public static int approxTokenCountFromRuneLen(int runeLen, String lang) {
        if (runeLen <= 0) {
            return 0;
        }
        double ratio = CHARS_PER_TOKEN.getOrDefault(lang, CHARS_PER_TOKEN.get(LANG_MIXED));
        double approx = runeLen / ratio;
        if (approx < 1) {
            return 1;
        }
        return (int) (approx + 0.5); // 正数场景下截断即四舍五入
    }

    /**
     * 粗语言检测：数 CJK rune vs 拉丁 rune。
     * 仅供启发式分派，不是正经语言识别。
     */
    public static String detectLanguage(String s) {
        if (s == null || s.isEmpty()) {
            return LANG_MIXED;
        }
        int cjk = 0, latin = 0, umlaut = 0;
        for (int cp : CodePoints.of(s)) {
            var script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HANGUL
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) {
                cjk++;
            } else if (isGermanUmlaut(cp)) {
                umlaut++;
                latin++;
            } else if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')) {
                latin++;
            }
        }
        int total = cjk + latin;
        if (total == 0) {
            return LANG_MIXED;
        }
        double cjkRatio = (double) cjk / total;
        double latinRatio = (double) latin / total;
        // 混合：两种文字都占 ≥15%
        if (cjkRatio >= 0.15 && latinRatio >= 0.15) {
            return LANG_MIXED;
        }
        if (cjkRatio > 0.3) {
            return LANG_CHINESE;
        }
        if (umlaut > 0 || hasGermanWords(s)) {
            return LANG_GERMAN;
        }
        return LANG_ENGLISH;
    }

    private static boolean isGermanUmlaut(int cp) {
        return cp == 'ä' || cp == 'ö' || cp == 'ü' || cp == 'Ä' || cp == 'Ö' || cp == 'Ü' || cp == 'ß';
    }

    private static boolean hasGermanWords(String s) {
        final int sample = 512;
        if (s.length() > sample) {
            s = s.substring(0, sample);
        }
        for (String w : new String[]{" der ", " die ", " das ", " und ", " ist ", " nicht ", " mit ", " auf "}) {
            if (containsLower(s, w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsLower(String haystack, String needle) {
        if (haystack.length() < needle.length()) {
            return false;
        }
        for (int i = 0; i + needle.length() <= haystack.length(); i++) {
            boolean match = true;
            for (int j = 0; j < needle.length(); j++) {
                char h = haystack.charAt(i + j);
                if (h >= 'A' && h <= 'Z') {
                    h += 'a' - 'A';
                }
                if (h != needle.charAt(j)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    public static int charsForTokenLimit(int tokens, String lang) {
        if (tokens <= 0) {
            return 0;
        }
        double ratio = CHARS_PER_TOKEN.getOrDefault(lang, CHARS_PER_TOKEN.get(LANG_MIXED));
        return (int) (tokens * ratio * 0.9); // 0.9 安全系数，向零截断
    }
}
