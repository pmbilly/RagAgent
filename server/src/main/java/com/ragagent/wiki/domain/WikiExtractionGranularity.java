package com.ragagent.wiki.domain;

/**
 * 候选 slug 抽取（Pass 0）的激进程度（对照 Go types.WikiExtractionGranularity，
 * internal/types/wiki_page.go L477-518）。
 *
 * <p>粒度越高 = 抽出的 slug 越多；越低 = 越聚焦文档主题。</p>
 *
 * <p><b>为什么是 enum + 静态方法而不是「字段用 enum」</b>：Go 的这个类型是
 * {@code type WikiExtractionGranularity string}，其零值是 {@code ""}（历史行 / 未设置），
 * 且 {@code Normalize()} 会把空值与未知值映射为 standard。JSON 往返必须<b>保留 ""</b>
 * （omitempty 会把空串整键省略），所以 {@link WikiConfig} 里字段类型仍是 {@code String}，
 * 本枚举只提供取值 / 校验 / 归一化。</p>
 */
public enum WikiExtractionGranularity {

    /**
     * 只保留文档主要对象（简历 → 人 + 其项目）。最激进的 slug 剪枝，
     * 避免偶发技术名与泛化概念撑爆索引。
     */
    FOCUSED("focused"),

    /**
     * 默认档：主要对象 + 有实质讨论（独立段落或多条要点）的实体/概念。
     * 跳过一次性提及与通用词。
     */
    STANDARD("standard"),

    /**
     * 抽取每一个命名实体与可辨识概念，包括顺带提到的技术栈/库。
     * 等价于引入粒度之前的旧行为，适合把知识库当词汇表而非精选 wiki 使用。
     */
    EXHAUSTIVE("exhaustive");

    private final String value;

    WikiExtractionGranularity(String value) {
        this.value = value;
    }

    /** 线格式值（= Go 常量字面量） */
    public String value() {
        return value;
    }

    /** 对照 Go IsValid（L502-508）：严格区分大小写，非三个值之一即非法。 */
    public static boolean isValid(String raw) {
        for (WikiExtractionGranularity g : values()) {
            if (g.value.equals(raw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对照 Go Normalize（L513-518）：合法则原样返回，否则回落 standard。
     * 调用方拿配置前都过一遍，避免历史行里的空值/未知值惊吓抽取 prompt。
     */
    public static String normalize(String raw) {
        return isValid(raw) ? raw : STANDARD.value;
    }

    @Override
    public String toString() {
        return value;
    }
}
