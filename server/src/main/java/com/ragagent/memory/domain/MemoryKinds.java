package com.ragagent.memory.domain;

import java.util.List;

/**
 * memory 模块的取值常量与预算（对照 Go internal/types/memory.go 的三段 const 块
 * L24-115 与零散常量）。
 *
 * <p><b>为什么这些放得进一个类</b>：Go 里它们是包级常量，Java 没有包级作用域，
 * 全部落在一个 final 类里最贴近原样，也便于后续模块按名字检索。</p>
 *
 * <p>{@code write_mode} 的两个取值刻意**不在这里**——它们已经落在
 * {@link MemoryConfig#WRITE_MODE_EXPLICIT_ONLY} / {@link MemoryConfig#WRITE_MODE_AUTO}，
 * 重复定义会让两处漂移。同理 {@code interest_threshold} 与
 * {@code MEMORY_DOC_AFFINITY_MIN_HITS} 也在 {@link MemoryConfig} 上。</p>
 */
public final class MemoryKinds {

    private MemoryKinds() {}

    // ── 记忆种类（Go MemoryKind*，L24-34） ──────────────────────────────────

    /**
     * 稳定特质：构成每轮都注入的常驻块。
     *
     * <p>profile 与 preference 是稳定特质，fact 与 task 是情境性的
     * （只在当前提问匹配上时才被拉进来）。</p>
     */
    public static final String KIND_PROFILE = "profile";
    public static final String KIND_PREFERENCE = "preference";
    public static final String KIND_FACT = "fact";
    public static final String KIND_TASK = "task";

    /**
     * 这个人一直在问的东西。它来自**复现**而不是某一次陈述，
     * 用途是**给检索加条件**而不是被引用给用户看：知道某人在做医疗影像，
     * 才能把"分割怎么调"变成一条找得到正确文档的查询。
     */
    public static final String KIND_INTEREST = "interest";

    /** 对照 Go {@code MemoryKinds}：全部合法种类，**按常驻块的渲染顺序**。 */
    public static final List<String> ALL = List.of(
            KIND_PROFILE, KIND_PREFERENCE, KIND_FACT, KIND_TASK, KIND_INTEREST);

    /**
     * 对照 Go {@code ResidentMemoryKinds}：构成"总是注入"那块内容的稳定特质。
     *
     * <p>interest **属于**这里，尽管它是推导出来的而不是被陈述的：它是一个人的固有属性，
     * 而且是"我在忙什么"这类**关于本人**的问题的答案——那类问题与兴趣本身的文本
     * 一个词都不重合，所以永远不可能被查询匹配命中。</p>
     */
    public static final List<String> RESIDENT = List.of(
            KIND_PROFILE, KIND_PREFERENCE, KIND_INTEREST);

    /** 对照 Go {@code IsResidentMemoryKind}。 */
    public static boolean isResident(String kind) {
        return kind != null && RESIDENT.contains(kind);
    }

    /** 对照 Go {@code IsValidMemoryKind}：校验来自 LLM 响应或 API 的 kind。 */
    public static boolean isValid(String kind) {
        return kind != null && ALL.contains(kind);
    }

    // ── 来源（Go MemoryOrigin*，L38-42） ───────────────────────────────────

    /** 用户在对话里明确要求的。 */
    public static final String ORIGIN_EXPLICIT = "explicit";
    /** 后台抽取任务蒸馏出来的。 */
    public static final String ORIGIN_EXTRACTED = "extracted";
    /** 在记忆管理器里创建或编辑的。 */
    public static final String ORIGIN_MANUAL = "manual";

    // ── 状态（Go MemoryStatus*，L46-56） ───────────────────────────────────

    public static final String STATUS_ACTIVE = "active";
    /** 被矛盾陈述取代。**取代而非删除**，这样记忆管理器仍能解释"改了什么、什么时候改的"。 */
    public static final String STATUS_SUPERSEDED = "superseded";
    public static final String STATUS_ARCHIVED = "archived";

    /**
     * 系统**推断**出来、而不是被告知的记忆：在记忆管理器里可见、等待用户确认，
     * **永不注入提示词**。从提问里猜一个人的角色很有价值且往往正确，
     * 但把猜错的东西悄悄当成事实，是记忆功能彻底失去信任的方式。
     */
    public static final String STATUS_PENDING = "pending";

    // ── 预算（Go L71-106） ────────────────────────────────────────────────

    /** 常驻块的 rune 预算。 */
    public static final int BLOCK_RUNE_BUDGET = 900;
    /** 情境召回（每轮）的 rune 预算。 */
    public static final int RECALL_RUNE_BUDGET = 600;
    /** 一轮最多拉进多少条情境条目，与 rune 预算彼此独立。 */
    public static final int RECALL_MAX_ITEMS = 5;
    /** 一次按需查找的上限。比召回宽松得多，因为两者付费方式不同（见 Go 注释）。 */
    public static final int SEARCH_MAX_ITEMS = 20;
    /** 一次按需查找的 rune 预算。 */
    public static final int SEARCH_RUNE_BUDGET = 2000;
    /** 调用方没写 limit 时给的条数。 */
    public static final int SEARCH_DEFAULT_ITEMS = 10;
    /**
     * 常驻块最多带几条兴趣。
     *
     * <p>兴趣**不**按相关性过滤——"我在做什么"这个问题与兴趣自身的文本不共享任何词，
     * 相关性会恰好把能回答它的记忆丢掉。但长期用户会攒下几十条，而常驻块不是
     * 罗列它们的地方，所以上限存在、由相关性决定谁留下。</p>
     */
    public static final int RESIDENT_INTEREST_MAX_ITEMS = 5;
    /** 单条记忆的 rune 上限。记忆应当是一句话，更长的属于聊天历史知识库。 */
    public static final int CONTENT_MAX_RUNES = 300;
    /** 单个主体可持有的活跃条目上限；超出后按排名归档——这是系统里唯一的自动遗忘。 */
    public static final int DEFAULT_MAX_ITEMS = 200;

    // ── 其它零散常量 ───────────────────────────────────────────────────────

    /** 对照 Go {@code MaxMemoryPendingSessions}：**一批处理**的上限，不是持久队列的上限。 */
    public static final int MAX_PENDING_SESSIONS = 32;

    /** 对照 Go {@code MaxMemoryTombstones}：一个主体累积的拒绝条数上限，超出丢最旧的。 */
    public static final int MAX_TOMBSTONES = 500;

    /** 对照 Go {@code RedactedMemoryPlaceholder}：**刻意可见**，用户该看得出有东西被丢掉了。 */
    public static final String REDACTED_PLACEHOLDER = "【已隐藏】";

    /** 对照 Go {@code MaxMemoryExtractInstructionsRunes}。 */
    public static final int MAX_EXTRACT_INSTRUCTIONS_RUNES = 1000;
}
