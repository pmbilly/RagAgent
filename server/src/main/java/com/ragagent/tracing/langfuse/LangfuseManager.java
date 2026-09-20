package com.ragagent.tracing.langfuse;

import java.util.Map;

/**
 * langfuse 观测 seam（对照 Go internal/tracing/langfuse 的 Manager/Span/SpanOptions
 * 公共形状，internal/agent/{engine,act}.go 的三个调用点形状逐字对应）。
 *
 * <p><b>波 4.6a 范围声明：这是接缝不是完整翻译。</b>Go 引擎只用三个调用点：</p>
 * <ol>
 *   <li>{@code langfuse.GetManager().StartSpan(ctx, langfuse.SpanOptions{Name, Input, Metadata})}
 *       返回 span；</li>
 *   <li>{@code finishAgentSpan(span, state, err)} → {@code span.Finish(output, metadata, err)}；</li>
 *   <li>{@code finishToolSpan(span, tc, execErr, durationMs)} → 同上。</li>
 * </ol>
 * <p>OTLP 导出与 internal/tracing/langfuse 的其余 ~2.3k 行（Trace/Generation/config/
 * exporter/middleware 等，部署观测面，不进 HTTP 契约）不翻——对照 chromedp/
 * BrowserRenderer 的降级先例。GetManager 恒返回 no-op 单例；将来接真导出时换
 * Init 的实现即可，调用点不用动。</p>
 *
 * <p>Span 的 name/input/metadata 字段结构照 Go 抄（tracer.go L79-84），finish 的
 * (output, metadata, err) 三参形状同 Go L251。</p>
 */
public interface LangfuseManager {

    /** GetManager：安装的单例；未 Init 时 Go 返回 nil、调用方容忍 nil——这里恒返回 no-op 单例。 */
    static LangfuseManager get() {
        return NoopLangfuseManager.INSTANCE;
    }

    /** 对照 Manager.Enabled()。 */
    boolean enabled();

    /**
     * 开一个 SPAN 观测（对照 Manager.StartSpan；Go 额外返回携带父 span 上下文的
     * ctx——Java 侧租户/上下文走显式传参，无 ctx 返回值）。
     */
    Span startSpan(SpanOptions options);

    /** SPAN 观测配置（对照 tracer.go 的 SpanOptions；字段名与 Go 一致）。 */
    record SpanOptions(String name, Object input, Map<String, Object> metadata) {
        public static SpanOptions of(String name) {
            return new SpanOptions(name, null, null);
        }
    }
}
