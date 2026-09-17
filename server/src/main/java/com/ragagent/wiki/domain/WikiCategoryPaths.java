package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wiki 目录路径 / 候选类型的纯函数工具（逐条对照 Go internal/types/wiki_page.go
 * L21-133 + L823-834）。
 *
 * <p>这些函数是 service、repository、taxonomy 三层共用的<b>同一份</b>归一化规则：
 * 页面写入时存的路径与列表/过滤查询匹配的路径必须经过完全相同的清洗，
 * 否则目录过滤会静默漂移。</p>
 */
public final class WikiCategoryPaths {

    private WikiCategoryPaths() {}

    /** 全角 / 竖线分隔符统一成 "/"（对照 Go wikiCategorySeparatorReplacer L21） */
    private static String replaceSeparators(String s) {
        return s.replace('／', '/').replace('｜', '/').replace('|', '/');
    }

    /**
     * 对照 Go CleanWikiCategoryPart（L26-44）。
     *
     * <p>把单个原始分类标签归一化：可能自带分隔符、包裹引号/括号、或页面类型噪声，
     * 返回拆开后的干净子标签（"entity"/"实体" 之类的类型标签被丢弃）。</p>
     */
    public static List<String> cleanCategoryPart(String part) {
        if (part == null) {
            return List.of();
        }
        part = part.trim();
        if (part.isEmpty()) {
            return List.of();
        }
        part = replaceSeparators(part);
        String[] rawParts = part.split("/", -1);
        List<String> cleaned = new ArrayList<>(rawParts.length);
        for (String raw : rawParts) {
            String label = trimWrappingQuotes(raw.trim());
            label = label.trim();
            if (label.isEmpty() || isTypeCategoryLabel(label)) {
                continue;
            }
            cleaned.add(label);
        }
        return cleaned;
    }

    /**
     * 对照 Go CleanWikiCategoryPath（L50-64）：清洗 + 去重 + 按
     * {@link WikiConstants#CATEGORY_MAX_DEPTH} 截断。
     */
    public static List<String> cleanCategoryPath(List<String> parts) {
        if (parts == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(parts.size());
        for (String part : parts) {
            for (String label : cleanCategoryPart(part)) {
                if (cleaned.contains(label)) {
                    continue;
                }
                cleaned.add(label);
                if (cleaned.size() >= WikiConstants.CATEGORY_MAX_DEPTH) {
                    return cleaned;
                }
            }
        }
        return cleaned;
    }

    /**
     * 对照 Go TrimWikiFolderSegments（L71-79）。
     *
     * <p>文件夹路径段<b>原样保留</b>，只丢空段。文件夹名在创建时已校验（无分隔符），
     * 且文件夹树才是页面归属的唯一真相来源，故与 {@link #cleanCategoryPath} 不同：
     * 不做类型过滤、不去重、不限深度——用户合法地可以把文件夹命名为"概念"或"Concepts"。</p>
     */
    public static List<String> trimFolderSegments(List<String> parts) {
        if (parts == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(parts.size());
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * 对照 Go WikiFolderPathSegments（L83-88）：把物化的文件夹路径（"AI/RAG"）
     * 拆成字面段。空/纯空白路径返回空列表（即 wiki 根）。
     */
    public static List<String> folderPathSegments(String path) {
        if (path == null || path.trim().isEmpty()) {
            return List.of();
        }
        return trimFolderSegments(List.of(path.split("/", -1)));
    }

    /**
     * 对照 Go SplitWikiPageTypes（L95-113）：解析可能带逗号分隔多类型的 page_type
     * （如 "entity,concept"），去重后返回；空/纯空白输入返回空列表（= 不过滤）。
     * handler（查询解析）与 repository（List 过滤）共用，保证两层切分方式一致。
     */
    public static List<String> splitPageTypes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            seen.add(part);
        }
        return new ArrayList<>(seen);
    }

    /**
     * 对照 Go WikiSourceKnowledgeID（L825-834）：从 source_refs 条目里取出 knowledge id，
     * 存储形式为 "uuid" 或 "uuid|title"。
     */
    public static String sourceKnowledgeID(String ref) {
        if (ref == null) {
            return "";
        }
        ref = ref.trim();
        if (ref.isEmpty()) {
            return "";
        }
        int i = ref.indexOf('|');
        if (i > 0) {
            return ref.substring(0, i).trim();
        }
        return ref;
    }

    /**
     * 对照 Go isWikiTypeCategoryLabel（L115-124）：类型标签（entity/实体/…）会被
     * {@link #cleanCategoryPart} 丢弃。注意 Go 会先 lower 再去掉一个尾随 "s"。
     */
    public static boolean isTypeCategoryLabel(String label) {
        String normalized = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("s")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        switch (normalized) {
            case "entity", "实体", "實體", "concept", "概念", "summary", "摘要", "wiki", "页面", "頁面":
                return true;
            default:
                return false;
        }
    }

    /**
     * 对照 Go strings.Trim(label, `"'“”‘’[]（）()`)
     * ——从两端反复裁掉集合内的字符（Trim 的 cutset 语义，非前缀剥离）。
     */
    private static String trimWrappingQuotes(String label) {
        int start = 0;
        int end = label.length();
        while (start < end && isWrappingChar(label.charAt(start))) {
            start++;
        }
        while (end > start && isWrappingChar(label.charAt(end - 1))) {
            end--;
        }
        return label.substring(start, end);
    }

    private static boolean isWrappingChar(char c) {
        return c == '"' || c == '\'' || c == '“' || c == '”' || c == '‘' || c == '’'
                || c == '[' || c == ']' || c == '（' || c == '）' || c == '(' || c == ')';
    }
}
