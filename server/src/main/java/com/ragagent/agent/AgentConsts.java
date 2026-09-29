package com.ragagent.agent;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.ragagent.agent.compaction.CompactionSettings;

/**
 * agent 常量与判定。
 *
 * <p>差异说明：{@code generateEventID} 已收编到
 * {@link com.ragagent.event.EventIds#generateEventID}，此处不重复。
 * LLM 停顿超时 / 本轮补全预算 / 历史保留窗口 / 预算裁剪这四个读引擎配置的
 * 计算，纯计算部分拆到 {@link #llmStallTimeout(Integer)}、{@link AgentBudgets}、
 * {@link #contextReserveTokens(int)}、{@link #clampCompletionBudgetToContext}。</p>
 */
public final class AgentConsts {

    /** agent 默认温度。 */
    public static final double DEFAULT_AGENT_TEMPERATURE = 0.7;
    /** 默认最大迭代数。 */
    public static final int DEFAULT_AGENT_MAX_ITERATIONS = 20;
    /** 默认不使用自定义系统提示词。 */
    public static final boolean DEFAULT_USE_CUSTOM_SYSTEM_PROMPT = false;

    /**
     * 单条 LLM 流允许无输出的时长。这是<b>停顿</b>预算
     * 不是总预算：一个在 write_sandbox_file 调用里持续吐整个文件体的 round 能连续
     * 输出地跑几分钟，按已用时间杀它等于扔掉组装了一半的调用。总上限属于供应商
     * 传输层（WEKNORA_LLM_STREAM_TIMEOUT_SECONDS）。可经 AgentConfig.LLMCallTimeout 覆盖。
     */
    public static final Duration DEFAULT_LLM_STALL_TIMEOUT = Duration.ofSeconds(120);

    /** 单次工具执行的默认上限。 */
    public static final Duration DEFAULT_TOOL_EXEC_TIMEOUT = Duration.ofSeconds(60);

    /** 瞬态 LLM 错误的最大重试数。 */
    public static final int MAX_LLM_RETRIES = 2;
    /** 空回复（自然停止、无工具调用）的最大重试数。 */
    public static final int MAX_EMPTY_RESPONSE_RETRIES = 2;
    /** 连续相同回复（无工具调用）的最大轮数。 */
    public static final int MAX_REPEATED_RESPONSE_ROUNDS = 2;

    /**
     * 估算成本与供应商实记账之间的余量。token 估算近似、
     * 供应商还有自己的脚手架；没有这块余量，"按算术刚好装下"的那个请求恰是会被
     * 拒绝的那个。
     */
    public static final int CONTEXT_SAFETY_TOKENS = 4096;

    /**
     * 瞬态（可重试）错误的特征串。
     * 断流/静默的流值得再试一次：这一轮没有产出可用的 turn，否则对话就结束在
     * 半个响应上。
     */
    private static final List<String> TRANSIENT_ERROR_MARKERS = List.of(
            "429", "rate limit",
            "500", "502", "503", "504",
            "overloaded", "timeout", "timed out",
            "connection", "server error", "temporarily unavailable",
            "deadline exceeded", "stalled");

    private AgentConsts() {
    }

    /**
     * 判定错误是否可能瞬态、值得重试。大小写不敏感的
     * 子串匹配。
     */
    public static boolean isTransientError(Throwable err) {
        return err != null && isTransientError(err.toString());
    }

    /** 同上，接受错误文本。 */
    public static boolean isTransientError(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String errStr = errorMessage.toLowerCase(Locale.ROOT);
        for (String marker : TRANSIENT_ERROR_MARKERS) {
            if (errStr.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单次工具执行的超时。一律默认 60 秒
     * （shell_exec 的专用超时随沙箱裁剪退役）。
     *
     * @param arguments 兼容变长签名，只消费第一个（JSON）
     */
    public static Duration toolExecutionTimeout(String toolName, String... arguments) {
        return DEFAULT_TOOL_EXEC_TIMEOUT;
    }

    /**
     * LLM 流的停顿超时：
     * 配置秒数（&gt;0）或默认 120 秒。
     */
    public static Duration llmStallTimeout(Integer configuredSeconds) {
        if (configuredSeconds != null && configuredSeconds > 0) {
            return Duration.ofSeconds(configuredSeconds);
        }
        return DEFAULT_LLM_STALL_TIMEOUT;
    }

    /**
     * 历史不得占据的窗口部分。
     * 按本轮自己的补全预算取值：允许吐 24576 token 的 agent 至少需要这么多空位，
     * 否则请求被接受而回复被截断。
     */
    public static int contextReserveTokens(int completionBudget) {
        return Math.max(completionBudget + CONTEXT_SAFETY_TOKENS,
                CompactionSettings.DEFAULT_RESERVE_TOKENS);
    }

    /**
     * 把本轮补全预算缩到窗口实际剩余。要的输出比窗口装得下的还多，是会被供应商直接拒绝的请求——在 agent
     * 眼里就是一次无缘无故的失败。
     */
    public static int clampCompletionBudgetToContext(int maxContextTokens, int currentTokens,
            int budget) {
        if (maxContextTokens <= 0) {
            return budget;
        }
        int available = maxContextTokens - currentTokens - CONTEXT_SAFETY_TOKENS;
        return Math.max(Math.min(budget, available), 1);
    }
}
