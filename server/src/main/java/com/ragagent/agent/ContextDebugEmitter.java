package com.ragagent.agent;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ragagent.agent.compaction.CompactionSettings;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.TokenUsage;

/**
 * 上下文诊断日志协作者：每轮的请求成本预估分解（prediction）与用量漂移（drift）。
 *
 * <p>持有 {@link AgentEngine} 回引以访问引擎字段；本类不得独立实例化。</p>
 */
final class ContextDebugEmitter {

    private static final Logger log = LoggerFactory.getLogger(ContextDebugEmitter.class);

    private final AgentEngine engine;

    ContextDebugEmitter(AgentEngine engine) {
        this.engine = engine;
    }

    void logContextPrediction(int round, List<ChatMessage> messages, List<ChatTool> tools,
            int predicted) {
        CompactionSettings settings = engine.observe.activeCompactionSettings();
        ContextDiagnostics.ContextBreakdown b = ContextDiagnostics.breakdownContext(messages, tools,
                engine.tokenEstimator);

        int messagesEst = b.getTotal() - b.getToolSchemas();
        log.debug("[Agent][Round-{}][ctx] predicted={} (baseline_usage={} + delta) | messages={} tool_schemas={} request={} | threshold={} window={} keep_recent={}",
                round, predicted, AgentEngine.contextTokensFromUsage(engine.lastUsage), messagesEst, b.getToolSchemas(),
                b.getTotal(), settings.threshold(), settings.maxContextTokens(),
                settings.keepRecentTokens());
        log.debug("[Agent][Round-{}][ctx] breakdown: {}", round, b);

        // 压缩的预估是 messages-only（或 usage+delta）口径；独立校验用同一口径：
        // 有 usage 基线时 provider 已把工具计费，对比全请求；没有就只对比消息。
        int baseline = AgentEngine.contextTokensFromUsage(engine.lastUsage);
        int compare = messagesEst;
        if (baseline > 0) {
            compare = b.getTotal();
        }
        if (predicted > 0 && compare > 0) {
            double ratio = (double) predicted / (double) compare;
            if (ratio > 1.5 || ratio < 0.67) {
                log.warn("[Agent][Round-{}][ctx] usage baseline and direct estimate disagree by {}x (predicted={}, estimated={}) — one of them is missing content the other counts",
                        round, String.format(java.util.Locale.ROOT, "%.1f", ratio), predicted, compare);
            }
        }
    }

    /** 预测 vs provider 实际计费（对照 logContextDrift——估计器完整性的 ground truth）。 */
    void logContextDrift(int round, int predicted, TokenUsage usage) {
        int actual = usage.getPromptTokens();
        if (actual <= 0 || predicted <= 0) {
            return;
        }
        int drift = predicted - actual;
        double pct = (double) drift / (double) actual * 100;

        log.debug("[Agent][Round-{}][ctx] drift: predicted={} actual_prompt={} diff={} ({}%) completion={}",
                round, predicted, actual, String.format(java.util.Locale.ROOT, "%+d", drift),
                String.format(java.util.Locale.ROOT, "%+.1f", pct), usage.getCompletionTokens());

        // 低估是危险方向：会发出被 provider 以体积拒绝的请求。
        if (pct < -25) {
            log.warn("[Agent][Round-{}][ctx] estimate is {}% below the provider's count (predicted={} actual={}) — compaction will fire late and cut too little",
                    round, String.format(java.util.Locale.ROOT, "%.0f", -pct), predicted, actual);
        } else if (pct > 50) {
            log.warn("[Agent][Round-{}][ctx] estimate is {}% above the provider's count (predicted={} actual={}) — compaction will fire on a context that fits",
                    round, String.format(java.util.Locale.ROOT, "%.0f", pct), predicted, actual);
        }
    }
}
