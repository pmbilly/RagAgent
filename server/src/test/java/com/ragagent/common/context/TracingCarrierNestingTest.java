package com.ragagent.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.web.JsonMappers;
import com.ragagent.datasource.domain.DataSourceSyncPayload;
import com.ragagent.datasource.domain.TaskInitiator;
import com.ragagent.knowledge.domain.ExtractChunkPayload;
import com.ragagent.knowledge.domain.QuestionBatchPayload;
import com.ragagent.memory.service.MemoryExtractPayload;
import com.ragagent.wiki.service.ingest.WikiIngestPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 追踪载体形状：四个域的队列载荷统一为**嵌套 {@code tracing} 键**，
 * 空载体整键省略（未启用追踪时字节与平铺期一致）。
 *
 * <p>载荷形状没有契约金片（任务载荷只在进程内队列流动、不出响应），
 * 全靠这里钉住——回环（非空载体原样往返）+ 字节稳定（空载体不新增任何键）。</p>
 */
class TracingCarrierNestingTest {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private static final TracingContext FULL =
            new TracingContext("t-1", "obs-2", "00-t-1-span-3-01", "u-4", "s-5");

    /** 五个载荷：同一个载体、同一个形状约定（四域 + 知识域两个载荷）。 */
    private static Map<String, Object> samples(TracingContext carrier) {
        return Map.of(
                "extractChunk", ExtractChunkPayload.withTracing(7L, "c-1", "m-1", "k-1", 1, 2, carrier),
                "questionBatch", QuestionBatchPayload.withTracing(7L, "kb-1", "k-1", 3, "zh-CN", 1,
                        List.of("c-1"), 0, "", "", carrier),
                "memoryExtract", MemoryExtractPayload.withTracing(7L, "subj", "sess", "msg", "m-1",
                        "zh-CN", carrier),
                "dataSourceSync", DataSourceSyncPayload.withTracing(new TaskInitiator("u-4", "admin"),
                        "manual", "ds-1", 7L, "log-1", false, 10, carrier),
                "wikiIngest", WikiIngestPayload.withTracing(7L, "kb-1", "zh-CN", carrier));
    }

    @Test
    @DisplayName("非空载体：JSON 里是嵌套 tracing 对象（顶层不再有 lf_* 键），且可原样回环")
    void populatedCarrierIsNestedAndRoundTrips() throws Exception {
        for (Map.Entry<String, Object> e : samples(FULL).entrySet()) {
            String json = MAPPER.writeValueAsString(e.getValue());
            Map<?, ?> top = MAPPER.readValue(json, Map.class);

            assertThat(top.keySet()).as("%s：不应再有顶层 lf_* 键", e.getKey())
                    .noneMatch(k -> String.valueOf(k).startsWith("lf_"));
            assertThat(top.get("tracing")).as("%s：tracing 应是嵌套对象", e.getKey())
                    .isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) top.get("tracing");
            assertThat(nested).as("%s：嵌套对象承载五个 lf_* 组件", e.getKey())
                    .containsEntry("lf_trace_id", "t-1")
                    .containsEntry("lf_parent_obs_id", "obs-2")
                    .containsEntry("lf_traceparent", "00-t-1-span-3-01")
                    .containsEntry("lf_user_id", "u-4")
                    .containsEntry("lf_session_id", "s-5");

            Object back = MAPPER.readValue(json, e.getValue().getClass());
            assertThat(tracingOf(back)).as("%s：回环后载体一致", e.getKey()).isEqualTo(FULL);
        }
    }

    @Test
    @DisplayName("空载体：wiki/memory/datasource 整键省略；知识域按模块约定输出空对象")
    void emptyCarrierIsHandled() throws Exception {
        // 知识域约定「字段一律显式输出」→ tracing:{}；其余三域为字节稳定整键省略
        List<String> explicitOutput = List.of("extractChunk", "questionBatch");

        for (Map.Entry<String, Object> e : samples(TracingContext.EMPTY).entrySet()) {
            String json = MAPPER.writeValueAsString(e.getValue());
            boolean alwaysOutput = explicitOutput.contains(e.getKey());
            assertThat(json.contains("\"tracing\"")).as("%s：tracing 键存在与否应符合本域约定", e.getKey())
                    .isEqualTo(alwaysOutput);
            if (alwaysOutput) {
                assertThat(json).as("%s：知识域输出空对象", e.getKey()).contains("\"tracing\":{}");
            }
            assertThat(json).as("%s：空载体不应泄露 lf_ 组件到顶层", e.getKey())
                    .doesNotContain("\"lf_trace_id\":\"t\"");

            Object back = MAPPER.readValue(json, e.getValue().getClass());
            assertThat(tracingOf(back).isEmpty()).as("%s：回环后仍是空载体", e.getKey()).isTrue();
        }
    }

    /** 各载荷的 {@code tracing()} 结构视图（入参为已回环的对象，故用反射取同一方法）。 */
    private static TracingContext tracingOf(Object payload) throws Exception {
        return (TracingContext) payload.getClass().getMethod("tracing").invoke(payload);
    }
}
