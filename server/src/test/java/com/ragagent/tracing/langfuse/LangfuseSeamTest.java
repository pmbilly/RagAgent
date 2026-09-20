package com.ragagent.tracing.langfuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * langfuse seam 的形状测试（波 4.6a）：GetManager 单例、no-op span 非空、
 * 调用点三参 finish 形状与 Go 一致（engine.go L303/L391 + act.go L513/L105）。
 */
class LangfuseSeamTest {

    @Test
    void getManagerReturnsSingletonNoop() {
        LangfuseManager manager = LangfuseManager.get();
        assertThat(manager).isSameAs(LangfuseManager.get());
        assertThat(manager.enabled()).isFalse();
    }

    @Test
    void startSpanToleratesOptionsAndFinishShape() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("session_id", "sess-1");
        metadata.put("max_iterations", 25);
        metadata.put("knowledge_base_ids", new String[] {"kb-1"});
        Span span = LangfuseManager.get().startSpan(
                new LangfuseManager.SpanOptions("agent.execute",
                        Map.of("query", "q", "query_len", 1), metadata));
        assertThat(span).isNotNull();
        assertThat(span.getId()).isEmpty();
        // finishAgentSpan / finishToolSpan 的三参形状（output, metadata, err）
        assertThatCode(() -> span.finish(
                Map.of("rounds", 2, "steps", 3, "complete", true, "final_answer", "答案"),
                Map.of("rounds", 2, "steps", 3, "tool_calls", 4),
                (String) null))
                .doesNotThrowAnyException();
        assertThatCode(() -> span.finish(
                Map.of("success", false, "duration_ms", 12L, "error", "boom"),
                Map.of("iteration", 1, "round", 0),
                "boom"))
                .doesNotThrowAnyException();
    }

    @Test
    void spanOptionsMirrorGoFields() {
        LangfuseManager.SpanOptions options = new LangfuseManager.SpanOptions("agent.tool.web_search",
                Map.of("resolved_arguments", "{}"), Map.of("round", 0));
        assertThat(options.name()).isEqualTo("agent.tool.web_search");
        assertThat(options.input()).isNotNull();
        assertThat(options.metadata()).containsKey("round");
    }
}
