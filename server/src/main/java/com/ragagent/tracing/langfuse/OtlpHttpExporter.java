package com.ragagent.tracing.langfuse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OTLP/HTTP 导出器（对照 Go internal/tracing/langfuse/exporter.go + otlptracehttp）：
 * POST {@code {host}/api/public/otel/v1/traces}，protobuf 体，Basic 认证，
 * {@code x-langfuse-ingestion-version: 4}（LiteFuse/Langfuse v3 直写门，
 * 缺失时服务端 400 "requires Python SDK >= 4.0.0"）+ SDK 标识头。
 *
 * <p>resource/scope 装配照 Go manager.go：resource = service.name=weknora +
 * langfuse.public.key [+environment/release]；scope = langfuse-sdk/4.0.0 +
 * 属性 public_key。Span 侧：INTERNAL kind、hex→bytes 的 id、属性全为 string、
 * RecordError → exception 事件、错误 → Status{ERROR}。</p>
 */
final class OtlpHttpExporter {

    private static final Logger log = LoggerFactory.getLogger(OtlpHttpExporter.class);

    private final LangfuseConfig cfg;
    private final HttpClient http;

    OtlpHttpExporter(LangfuseConfig cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(cfg.requestTimeoutMs(), 1000)))
                .build();
    }

    /** 对照 newExporter 的端点拼接（host 去尾斜杠）。 */
    String endpoint() {
        String host = cfg.host() == null ? "" : cfg.host();
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        return host + "/api/public/otel/v1/traces";
    }

    /** 发送一批 span；失败抛异常（由批处理层记录/丢弃，不阻塞调用方）。 */
    void export(List<RecordedSpan> spans) throws Exception {
        if (spans.isEmpty()) {
            return;
        }
        byte[] body = buildRequest(spans).toByteArray();
        String credentials = cfg.publicKey() + ":" + cfg.secretKey();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint()))
                .timeout(Duration.ofMillis(cfg.requestTimeoutMs()))
                .header("Content-Type", "application/x-protobuf")
                .header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(
                                credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("x-langfuse-ingestion-version", "4")
                .header("x-langfuse-sdk-name", "python")
                .header("x-langfuse-sdk-version", LangfuseAttributes.SCOPE_VERSION)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            if (cfg.debug()) {
                log.info("[Langfuse] exported {} spans to {}", spans.size(), endpoint());
            }
            return;
        }
        String text = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        throw new IllegalStateException("langfuse: export failed status=" + status
                + (text.isEmpty() ? "" : " body=" + text));
    }

    /** 组装 ExportTraceServiceRequest（resource + scope + spans），照 Go manager 的 resource/scope 装配。 */
    ExportTraceServiceRequest buildRequest(List<RecordedSpan> spans) {
        Resource.Builder resource = Resource.newBuilder()
                .addAttributes(stringAttr("service.name", LangfuseAttributes.SERVICE_NAME))
                .addAttributes(stringAttr(LangfuseAttributes.ATTR_LANGFUSE_PUBLIC_KEY, cfg.publicKey()));
        if (!LangfuseAttributes.isEmpty(cfg.environment())) {
            resource.addAttributes(stringAttr(LangfuseAttributes.ATTR_ENVIRONMENT, cfg.environment()));
        }
        if (!LangfuseAttributes.isEmpty(cfg.release())) {
            resource.addAttributes(stringAttr(LangfuseAttributes.ATTR_RELEASE, cfg.release()));
        }

        InstrumentationScope scope = InstrumentationScope.newBuilder()
                .setName(LangfuseAttributes.SCOPE_NAME)
                .setVersion(LangfuseAttributes.SCOPE_VERSION)
                .addAttributes(stringAttr(LangfuseAttributes.ATTR_SCOPE_PUBLIC_KEY, cfg.publicKey()))
                .build();

        ScopeSpans.Builder scopeSpans = ScopeSpans.newBuilder().setScope(scope);
        for (RecordedSpan span : spans) {
            scopeSpans.addSpans(toOtlpSpan(span));
        }

        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(resource)
                        .addScopeSpans(scopeSpans))
                .build();
    }

    private static Span toOtlpSpan(RecordedSpan recorded) {
        Span.Builder b = Span.newBuilder()
                .setTraceId(ByteString.copyFrom(
                        LangfuseAttributes.hexToBytes(recorded.traceIdHex)))
                .setSpanId(ByteString.copyFrom(
                        LangfuseAttributes.hexToBytes(recorded.spanIdHex)))
                .setName(recorded.name)
                .setKind(Span.SpanKind.SPAN_KIND_INTERNAL)
                .setStartTimeUnixNano(recorded.startNanos)
                .setEndTimeUnixNano(recorded.endNanos);

        if (!LangfuseAttributes.isEmpty(recorded.parentSpanIdHex)) {
            byte[] parent = LangfuseAttributes.hexToBytes(recorded.parentSpanIdHex);
            if (parent != null && !allZero(parent)) {
                b.setParentSpanId(ByteString.copyFrom(parent));
            }
        }

        for (Map.Entry<String, String> e : recorded.attributes.entrySet()) {
            b.addAttributes(stringAttr(e.getKey(), e.getValue()));
        }

        if (recorded.exceptionType != null || recorded.exceptionMessage != null) {
            io.opentelemetry.proto.trace.v1.Span.Event.Builder event =
                    io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                            .setName("exception")
                            .setTimeUnixNano(recorded.endNanos);
            if (recorded.exceptionType != null && !recorded.exceptionType.isEmpty()) {
                event.addAttributes(stringAttr("exception.type", recorded.exceptionType));
            }
            if (recorded.exceptionMessage != null && !recorded.exceptionMessage.isEmpty()) {
                event.addAttributes(stringAttr("exception.message", recorded.exceptionMessage));
            }
            b.addEvents(event);
        }

        if (recorded.statusMessage != null) {
            b.setStatus(Status.newBuilder()
                    .setCode(Status.StatusCode.STATUS_CODE_ERROR)
                    .setMessage(recorded.statusMessage));
        }

        return b.build();
    }

    private static KeyValue stringAttr(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value == null ? "" : value))
                .build();
    }

    private static boolean allZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
