package com.ragagent.tracing.langfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OTLP 载荷结构测试（对照 Go exporter.go 的端点/头与 manager.go 的 resource/scope
 * 装配；wire 形态用生成的 opentelemetry.proto 类断言）。
 */
class OtlpHttpExporterTest {

    private static LangfuseConfig config() {
        return new LangfuseConfig(true, "https://lf.example.com/", "pk-1", "sk-1", 15, 3000,
                2048, 10_000, "v0.8.0", "prod", 1.0, false);
    }

    private static String attrOf(ResourceSpans rs, String key) {
        for (KeyValue kv : rs.getResource().getAttributesList()) {
            if (kv.getKey().equals(key)) {
                return kv.getValue().getStringValue();
            }
        }
        return null;
    }

    @Test
    @DisplayName("端点：host 去尾斜杠 + /api/public/otel/v1/traces")
    void endpointMatchesGo() {
        assertEquals("https://lf.example.com/api/public/otel/v1/traces",
                new OtlpHttpExporter(config()).endpoint());
    }

    @Test
    @DisplayName("resource/scope/span 装配：service.name 与 public key、langfuse-sdk 作用域、id 与状态")
    void buildRequestShape() {
        OtlpHttpExporter exporter = new OtlpHttpExporter(config());

        RecordedSpan recorded = new RecordedSpan(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", null, "rerank", 1000L);
        recorded.endNanos = 2000L;
        recorded.putAttribute(LangfuseAttributes.ATTR_OBS_TYPE, LangfuseAttributes.OBS_TYPE_SPAN);
        recorded.putAttribute(LangfuseAttributes.ATTR_OBS_INPUT, "{\"query\":\"q\"}");
        recorded.recordError("java.lang.IllegalStateException", "boom");
        recorded.setErrorStatus("boom");

        ExportTraceServiceRequest request = exporter.buildRequest(List.of(recorded));
        assertEquals(1, request.getResourceSpansCount());

        ResourceSpans rs = request.getResourceSpans(0);
        assertEquals("weknora", attrOf(rs, "service.name"));
        assertEquals("pk-1", attrOf(rs, "langfuse.public.key"));
        assertEquals("prod", attrOf(rs, "langfuse.environment"));
        assertEquals("v0.8.0", attrOf(rs, "langfuse.release"));

        ScopeSpans scopeSpans = rs.getScopeSpans(0);
        assertEquals("langfuse-sdk", scopeSpans.getScope().getName());
        assertEquals("4.0.0", scopeSpans.getScope().getVersion());
        assertEquals("pk-1", scopeSpans.getScope().getAttributes(0).getValue().getStringValue());
        assertEquals("public_key", scopeSpans.getScope().getAttributes(0).getKey());

        assertEquals(1, scopeSpans.getSpansCount());
        Span span = scopeSpans.getSpans(0);
        assertEquals("rerank", span.getName());
        assertEquals(Span.SpanKind.SPAN_KIND_INTERNAL, span.getKind());
        assertEquals(1000L, span.getStartTimeUnixNano());
        assertEquals(2000L, span.getEndTimeUnixNano());
        assertEquals(ByteString.copyFrom(LangfuseAttributes.hexToBytes(
                        "0af7651916cd43dd8448eb211c80319c")),
                span.getTraceId());
        assertEquals(ByteString.copyFrom(LangfuseAttributes.hexToBytes("b7ad6b7169203331")),
                span.getSpanId());
        // 无父（根）span 的 parent_span_id 为空
        assertTrue(span.getParentSpanId().isEmpty());

        assertEquals("langfuse.observation.type", span.getAttributes(0).getKey());
        assertEquals("span", span.getAttributes(0).getValue().getStringValue());
        assertEquals("langfuse.observation.input", span.getAttributes(1).getKey());
        assertEquals("{\"query\":\"q\"}", span.getAttributes(1).getValue().getStringValue());

        assertEquals(Status.StatusCode.STATUS_CODE_ERROR, span.getStatus().getCode());
        assertEquals("boom", span.getStatus().getMessage());

        assertEquals(1, span.getEventsCount());
        assertEquals("exception", span.getEvents(0).getName());
        assertEquals("java.lang.IllegalStateException",
                span.getEvents(0).getAttributes(0).getValue().getStringValue());
        assertEquals("boom", span.getEvents(0).getAttributes(1).getValue().getStringValue());

        // 序列化不抛（protobuf 可编码）
        assertTrue(request.toByteArray().length > 0);
    }

    @Test
    @DisplayName("父 span id：合法则写入，全零则省略（对照 OTLP 语义）")
    void parentSpanIdHandling() {
        OtlpHttpExporter exporter = new OtlpHttpExporter(config());
        String traceId = "0af7651916cd43dd8448eb211c80319c";

        RecordedSpan withParent = new RecordedSpan(traceId, "b7ad6b7169203331",
                "a1b2c3d4e5f60718", "child", 1L);
        withParent.endNanos = 2L;
        Span otlp = exporter.buildRequest(List.of(withParent))
                .getResourceSpans(0).getScopeSpans(0).getSpans(0);
        assertEquals(ByteString.copyFrom(LangfuseAttributes.hexToBytes("a1b2c3d4e5f60718")),
                otlp.getParentSpanId());

        RecordedSpan zeroParent = new RecordedSpan(traceId, "b7ad6b7169203331",
                "0000000000000000", "child", 1L);
        zeroParent.endNanos = 2L;
        Span otlpZero = exporter.buildRequest(List.of(zeroParent))
                .getResourceSpans(0).getScopeSpans(0).getSpans(0);
        assertTrue(otlpZero.getParentSpanId().isEmpty());
    }

    @Test
    @DisplayName("hex 工具：生成 32/16 位、非法输入回 null")
    void hexHelpers() {
        assertEquals(32, LangfuseAttributes.randomTraceIdHex().length());
        assertEquals(16, LangfuseAttributes.randomSpanIdHex().length());
        assertEquals(null, LangfuseAttributes.hexToBytes("zz"));
        assertEquals(null, LangfuseAttributes.hexToBytes("abc"));
        assertEquals(8, LangfuseAttributes.hexToBytes("a1b2c3d4e5f60718").length);
        assertEquals("a1b2c3d4e5f60718",
                LangfuseAttributes.toHex(LangfuseAttributes.hexToBytes("a1b2c3d4e5f60718")));
        assertEquals("测试", new String("测试".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }
}
