package com.ragagent.wiki.domain;

import java.util.List;

/**
 * Wiki 领域常量。收敛到一个类按用途分段；常量值即 JSON / DB 出口字面量，
 * 前端与查询过滤均按此解析。
 */
public final class WikiConstants {

    private WikiConstants() {}

    // ── 目录深度 ──
    /**
     * category_path 允许保留的目录层数硬上限。ingest prompt 只要求模型给 2 层，
     * 存储侧多留一层做防御。service / repository / taxonomy 三层共用同一份，
     * 保证"写入的路径"和"查询过滤的路径"清洗规则完全一致。
     */
    public static final int CATEGORY_MAX_DEPTH = 3;

    // ── 页面类型 ──
    public static final String PAGE_TYPE_SUMMARY = "summary";
    public static final String PAGE_TYPE_ENTITY = "entity";
    public static final String PAGE_TYPE_CONCEPT = "concept";
    public static final String PAGE_TYPE_INDEX = "index";
    /** 合成/分析页：不由 ingest 自动创建，只能由 agent 的 wiki_write_page 工具写入 */
    public static final String PAGE_TYPE_SYNTHESIS = "synthesis";
    /** 对比页：同上，只能由 agent 创建 */
    public static final String PAGE_TYPE_COMPARISON = "comparison";

    /** 全部合法页面类型（校验与过滤共用，顺序即展示/校验顺序） */
    public static final List<String> PAGE_TYPES = List.of(
            PAGE_TYPE_SUMMARY, PAGE_TYPE_ENTITY, PAGE_TYPE_CONCEPT,
            PAGE_TYPE_INDEX, PAGE_TYPE_SYNTHESIS, PAGE_TYPE_COMPARISON);

    // ── 页面状态 ──
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_ARCHIVED = "archived";

    /** 全部合法状态（校验与过滤共用） */
    public static final List<String> STATUSES = List.of(STATUS_DRAFT, STATUS_PUBLISHED, STATUS_ARCHIVED);

    // ── 编辑来源 ──
    /** wiki ingest 管道写入（同时是空来源的历史行的回落值） */
    public static final String EDIT_SOURCE_PIPELINE = "pipeline";
    /** 经 agent wiki 工具写入（wiki_write_page / wiki_replace_text / ...） */
    public static final String EDIT_SOURCE_AGENT = "agent";
    /** 人工经 wiki 编辑器 UI / REST API 写入 */
    public static final String EDIT_SOURCE_USER = "user";
    /** 回滚到历史修订产生的版本 */
    public static final String EDIT_SOURCE_REVERT = "revert";

    // ── 修订保留 ──
    /** 机器作者快照的软上限 */
    public static final int MAX_REVISIONS_PER_PAGE = 50;
    /** 每页绝对上限，无视作者 */
    public static final int MAX_REVISIONS_HARD_CAP = 200;
    /**
     * 可被软上限丢弃的编辑来源。注意含 {@code ""}——历史行没有来源，
     * 语义同 pipeline。
     */
    public static final List<String> PRUNABLE_EDIT_SOURCES = List.of("", EDIT_SOURCE_PIPELINE);

    // ── 文件夹根哨兵 ──
    /** 表示"wiki 根"的 parent/folder id（顶层页面/文件夹，没有父目录） */
    public static final String FOLDER_ROOT_ID = "";

    // ── 图谱模式 ──
    /** 返回 top-N 最连通的页面作为知识库概览（图谱首次打开） */
    public static final String GRAPH_MODE_OVERVIEW = "overview";
    /** 返回以中心页为起点、限制深度的邻域（下钻交互） */
    public static final String GRAPH_MODE_EGO = "ego";

    // ── 校验 / 归一化 ──

    /** 未知类型会在类型过滤列表里静默消失，故写路径必须拒绝。 */
    public static boolean isValidPageType(String pageType) {
        return pageType != null && PAGE_TYPES.contains(pageType);
    }

    /** status 是否为合法状态。 */
    public static boolean isValidPageStatus(String status) {
        return status != null && STATUSES.contains(status);
    }

    /**
     * 未知 / 空值一律映射为 {@link #EDIT_SOURCE_PIPELINE}，
     * 让历史行与漏改的调用点退化成历史行为（"机器写的"）。
     */
    public static String normalizeEditSource(String source) {
        if (EDIT_SOURCE_AGENT.equals(source) || EDIT_SOURCE_USER.equals(source)
                || EDIT_SOURCE_REVERT.equals(source) || EDIT_SOURCE_PIPELINE.equals(source)) {
            return source;
        }
        return EDIT_SOURCE_PIPELINE;
    }
}
