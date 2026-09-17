package com.ragagent.knowledge.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 多语言正则模式（对照 Go internal/infrastructure/chunker/patterns.go）。
 * 模式按用途分组（章节标记、编号、分隔线），优先级供 heuristic 切分器排序候选边界。
 */
public final class ChunkPatterns {

    private ChunkPatterns() {
    }

    // ---- 边界优先级（对照 patterns.go:12-20，越大越强） ----
    public static final int PRIO_FORM_FEED = 100;
    public static final int PRIO_NUMBERED_HEAD = 90;
    public static final int PRIO_CHAPTER_MARKER = 85;
    public static final int PRIO_ALL_CAPS_HEADING = 70;
    public static final int PRIO_VISUAL_SEP = 60;
    public static final int PRIO_PAGE_FOOTER = 50;
    public static final int PRIO_BLANK_BLOCK = 40;

    /** ATX Markdown 标题；捕获组：(1) # 串，(2) 标题文本（对照 patterns.go:24）。 */
    public static final Pattern MARKDOWN_HEADING =
            Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /** 换页符 \f（对照 patterns.go:28）。 */
    public static final Pattern FORM_FEED = Pattern.compile("\\f");

    /**
     * 行首数字/罗马数字编号 + 非空标题，如 "1. Intro"、"2.3 Methods"、"IV. Results"、
     * "2.2.1 用户与权限"（对照 patterns.go:34）。
     */
    public static final Pattern NUMBERED_SECTION =
            Pattern.compile("(?m)^[ \\t]*(?:\\d+(?:\\.\\d+){1,3}\\.?|(?:\\d+|[IVX]{1,5})\\.)[ \\t]+\\S.{0,200}$");

    /** 短全大写行（≥4 字母、至多 ~10 词，容忍尾部冒号；对照 patterns.go:39）。 */
    public static final Pattern ALL_CAPS_HEADING =
            Pattern.compile("(?m)^[ \\t]*([A-ZÄÖÜ][A-ZÄÖÜ \\-]{3,80}):?\\s*$");

    /** 水平分隔线（对照 patterns.go:43）。 */
    public static final Pattern VISUAL_SEPARATOR =
            Pattern.compile("(?m)^[ \\t]*(?:-{3,}|={3,}|\\*{3,}|_{3,})[ \\t]*$");

    /** 连续 ≥3 换行 = 硬分节（对照 patterns.go:47）。 */
    public static final Pattern EXCESSIVE_BLANKS = Pattern.compile("\\n{3,}");

    /** "Seite X von Y" / "Page X of Y" / "页码" 页脚行（对照 patterns.go:50）。 */
    public static final Pattern PAGE_FOOTER =
            Pattern.compile("(?mi)^[ \\t]*(?:Seite|Page|页码?)\\s+\\d+(?:\\s*(?:von|of|/)\\s*\\d+)?[ \\t]*$");

    /** 德语章节标记（对照 patterns.go:53）。 */
    public static final Pattern GERMAN_CHAPTER =
            Pattern.compile("(?m)^[ \\t]*(?:Kapitel|Abschnitt|Teil)\\s+(?:[0-9]+|[IVX]{1,5})[\\.: ].{0,200}$");

    /** 英语章节标记（对照 patterns.go:56）。 */
    public static final Pattern ENGLISH_CHAPTER =
            Pattern.compile("(?m)^[ \\t]*(?:Chapter|Section|Part)\\s+(?:[0-9]+|[IVX]{1,5})[\\.: ].{0,200}$");

    /** 中文章节标记：第一章 / 第3节 / 第 1 章（对照 patterns.go:60）。 */
    public static final Pattern CHINESE_CHAPTER =
            Pattern.compile("(?m)^[ \\t]*第[ \\t]*[一二三四五六七八九十百千零〇0-9]+[ \\t]*(?:章|节|節|部分|篇)[ \\t]?.{0,200}$");

    /** 单行 Markdown 表格行（对照 header_tracker.go:37）。 */
    public static final Pattern TABLE_ROW =
            Pattern.compile("(?m)^\\s*(?:\\|[^|\\n]*)+\\|\\s*$");

    /** 语句级分隔符，语言特化（对照 patterns.go:64 SentenceSeparators）。 */
    public static List<String> sentenceSeparators(String lang) {
        return switch (lang) {
            case Tokens.LANG_CHINESE -> List.of("。", "！", "？", "；", "\n");
            case Tokens.LANG_GERMAN, Tokens.LANG_ENGLISH -> List.of(". ", "! ", "? ", "; ", "\n");
            default -> List.of("。", "！", "？", "；", ". ", "! ", "? ", "; ", "\n");
        };
    }

    /** 语言提示对应的章节标记正则；空/未知列表返回全部（对照 patterns.go:78）。 */
    public static List<Pattern> chapterPatternsForLangs(List<String> langs) {
        if (langs == null || langs.isEmpty()) {
            return List.of(GERMAN_CHAPTER, ENGLISH_CHAPTER, CHINESE_CHAPTER);
        }
        List<Pattern> out = new ArrayList<>();
        for (String l : langs) {
            switch (l) {
                case Tokens.LANG_GERMAN -> out.add(GERMAN_CHAPTER);
                case Tokens.LANG_ENGLISH -> out.add(ENGLISH_CHAPTER);
                case Tokens.LANG_CHINESE -> out.add(CHINESE_CHAPTER);
                default -> {
                }
            }
        }
        if (out.isEmpty()) {
            return List.of(GERMAN_CHAPTER, ENGLISH_CHAPTER, CHINESE_CHAPTER);
        }
        return out;
    }
}
