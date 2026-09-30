package com.ragagent.common.pipeline;

/**
 * Chunk 类型常量（对照 Go {@code types.ChunkType} string 别名族，internal/types/chunk.go:12-39）。
 * Java 侧已有零散字面量，这里集中为常量表（值逐字符一致）。
 */
public final class ChunkTypes {

    private ChunkTypes() {}

    /** 普通文本 Chunk。 */
    public static final String TEXT = "text";
    /** 父子分块策略中的父文本 Chunk（仅用于上下文，不参与向量索引）。 */
    public static final String PARENT_TEXT = "parent_text";
    /** 图片 OCR 文本 Chunk。 */
    public static final String IMAGE_OCR = "image_ocr";
    /** 图片描述 Chunk。 */
    public static final String IMAGE_CAPTION = "image_caption";
    /** 摘要 Chunk。 */
    public static final String SUMMARY = "summary";
    /** 实体 Chunk。 */
    public static final String ENTITY = "entity";
    /** 关系 Chunk。 */
    public static final String RELATIONSHIP = "relationship";
    /** FAQ 条目 Chunk。 */
    public static final String FAQ = "faq";
    /** Web 搜索结果 Chunk。 */
    public static final String WEB_SEARCH = "web_search";
    /** 数据表摘要 Chunk。 */
    public static final String TABLE_SUMMARY = "table_summary";
    /** 数据表列描述 Chunk。 */
    public static final String TABLE_COLUMN = "table_column";
    /** Wiki 页面同步 Chunk。 */
    public static final String WIKI_PAGE = "wiki_page";
}
