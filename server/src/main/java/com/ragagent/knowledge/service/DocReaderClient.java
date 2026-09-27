package com.ragagent.knowledge.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.ragagent.model.domain.Model;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import docreader.DocReaderGrpc;
import docreader.Docreader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * DocReader gRPC 客户端（对照 Go internal/infrastructure/docparser/grpc_parser.go）。
 *
 * 契约（proto 实录）：
 * - 首选 ReadStream（首帧必须 meta，随后每帧一张图）；UNIMPLEMENTED 回退 unary Read
 * - 业务错误走响应 error 字段而非 gRPC status
 * - ReadConfig 3 号字段 reserved（image_storage 已移除）
 * - 单次调用超时 30 分钟（对照 doc_reader_call_timeout）
 *
 * 阶段 3 仅消费 meta/markdown（图片帧忽略并记录）。
 */
@Service
public class DocReaderClient {

    private static final Logger log = LoggerFactory.getLogger(DocReaderClient.class);
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(30);

    /** blocking/asyncStub 可被 {@link #reconnect} 原子换绑（volatile 保可见性）。 */
    private volatile DocReaderGrpc.DocReaderBlockingStub blocking;
    private volatile DocReaderGrpc.DocReaderStub asyncStub;

    /** 远端可达性（对照 GRPCDocumentReader.IsConnected 的 conn != nil 语义）。 */
    private volatile boolean connected;
    private final Object reconnectLock = new Object();

    public DocReaderClient() {
        String addr = System.getenv("DOCREADER_ADDR");
        if (addr == null || addr.isBlank()) {
            addr = "localhost:50051";
        }
        String host = addr;
        int port = 50051;
        int idx = addr.lastIndexOf(':');
        if (idx > 0) {
            host = addr.substring(0, idx);
            try {
                port = Integer.parseInt(addr.substring(idx + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(64 * 1024 * 1024)
                .build();
        this.blocking = DocReaderGrpc.newBlockingStub(channel);
        this.asyncStub = DocReaderGrpc.newStub(channel);
        // DOCREADER_ADDR 缺省时 DocReaderClient 构造用 localhost:50051 兜底——
        // 与 Go 的差异：Go 的 addr=="" 时启动"未连接"状态。Java 侧以 env 显式配置
        // 为连接判据（dev/e2e 都显式配置，行为一致）。
        String configured = System.getenv("DOCREADER_ADDR");
        this.connected = configured != null && !configured.isBlank();
    }

    /**
     * 解析结果：markdown + 图片数 + 图片引用（含内联字节）。
     *
     * <p>图片字节来自 docreader 的 inline 模式（docreader main.py 的 {@code _resolve_images}
     * 恒填 {@code ImageRef.image_data}、不用 storage_key），与 Go 侧
     * {@code grpc_parser.go:184-189} 收下的两字段同源；落盘责任在调用方
     * （Go 注释原文：image persistence is now handled entirely by the App）。</p>
     */
    public record ParseResult(String markdown, int imageCount, List<ImageRef> imageRefs) {
        public ParseResult(String markdown, int imageCount) {
            this(markdown, imageCount, List.of());
        }
    }

    /** 对照 proto {@code docreader.ImageRef} 的应用侧视图（只留落盘需要的四字段）。 */
    public record ImageRef(String filename, String originalRef, String mimeType, byte[] imageData) {
    }

    private static ImageRef toImageRef(Docreader.ImageRef ref) {
        return new ImageRef(ref.getFilename(), ref.getOriginalRef(), ref.getMimeType(),
                ref.getImageData().toByteArray());
    }

    /**
     * 解析文件（对照 GRPCDocumentReader.Read → ReadStream 优先，UNIMPLEMENTED 回退 unary）。
     * @param parserEngine 引擎名（空 = 服务端默认路由）
     */
    public ParseResult read(byte[] fileContent, String fileName, String fileType,
                            String title, String parserEngine) throws Exception {
        Docreader.ReadConfig.Builder config = Docreader.ReadConfig.newBuilder();
        if (parserEngine != null && !parserEngine.isBlank()) {
            config.setParserEngine(parserEngine);
        }
        Docreader.ReadRequest request = Docreader.ReadRequest.newBuilder()
                .setFileContent(com.google.protobuf.ByteString.copyFrom(fileContent))
                .setFileName(fileName == null ? "" : fileName)
                .setFileType(fileType == null ? "" : fileType)
                .setTitle(title == null ? "" : title)
                .setConfig(config)
                .build();
        try {
            return readStream(request);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNIMPLEMENTED) {
                log.info("ReadStream unimplemented; falling back to unary Read");
                return readUnary(request);
            }
            throw e;
        }
    }

    private ParseResult readStream(Docreader.ReadRequest request) throws Exception {
        List<Docreader.ReadStreamResponse> frames = new ArrayList<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        StreamObserver<Docreader.ReadStreamResponse> observer =
                new StreamObserver<>() {
                    @Override
                    public void onNext(Docreader.ReadStreamResponse value) {
                        frames.add(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        latch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        latch.countDown();
                    }
                };
        asyncStub.withDeadlineAfter(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .readStream(request, observer);
        latch.await(CALL_TIMEOUT.toMillis() + 10_000, TimeUnit.MILLISECONDS);

        if (frames.isEmpty() || !frames.get(0).hasMeta()) {
            throw new IllegalStateException("docreader ReadStream: first frame missing meta");
        }
        Docreader.ReadStreamMeta meta = frames.get(0).getMeta();
        if (!meta.getError().isEmpty()) {
            throw new IllegalStateException("docreader parse error: " + meta.getError());
        }
        // 图片帧（首帧之后每帧一张图，对照 proto ReadStreamResponse 的 oneof payload）
        List<ImageRef> images = new ArrayList<>();
        for (int i = 1; i < frames.size(); i++) {
            Docreader.ReadStreamResponse frame = frames.get(i);
            if (frame.hasImage()) {
                images.add(toImageRef(frame.getImage()));
            }
        }
        return new ParseResult(meta.getMarkdownContent(), meta.getImageCount(), images);
    }

    private ParseResult readUnary(Docreader.ReadRequest request) {
        Docreader.ReadResponse resp = blocking.withDeadlineAfter(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .read(request);
        if (!resp.getError().isEmpty()) {
            throw new IllegalStateException("docreader parse error: " + resp.getError());
        }
        List<ImageRef> images = new ArrayList<>(resp.getImageRefsCount());
        for (Docreader.ImageRef ref : resp.getImageRefsList()) {
            images.add(toImageRef(ref));
        }
        return new ParseResult(resp.getMarkdownContent(), resp.getImageRefsCount(), images);
    }

    // ── 系统管理端（波 2 收官批）附加能力 ─────────────────────────────────

    /** 远端引擎信息（对照 Go types.ParserEngineInfo——无 json tag，响应键是 Go 字段名）。 */
    public record RemoteEngine(String name, String description, java.util.List<String> fileTypes,
                               boolean available, String unavailableReason) {}

    /**
     * 对照 GRPCDocumentReader.IsConnected：conn != nil。Java 的 ManagedChannel 惰性连接，
     * 这里以"启动时配置了 DOCREADER_ADDR 或已 reconnect 成功"为准——与 Go 在
     * "配置了地址即连接对象存在"的语义一致。
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 对照 GRPCDocumentReader.Reconnect：关旧通道、按新地址重建。失败抛
     * RuntimeException（handler 落 200 + code:1 "连接失败: %v"，与 Go 相同形态）。
     */
    public void reconnect(String addr) {
        synchronized (reconnectLock) {
            try {
                String host = addr;
                int port = 50051;
                int idx = addr.lastIndexOf(':');
                if (idx > 0) {
                    host = addr.substring(0, idx);
                    port = Integer.parseInt(addr.substring(idx + 1));
                }
                ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                        .usePlaintext()
                        .maxInboundMessageSize(64 * 1024 * 1024)
                        .build();
                DocReaderGrpc.DocReaderBlockingStub newBlocking = DocReaderGrpc.newBlockingStub(channel);
                // 探活：真连一次 ListEngines（空 overrides），失败即判定重连失败
                newBlocking.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                        .listEngines(Docreader.ListEnginesRequest.newBuilder().build());
                this.blocking = newBlocking;
                this.asyncStub = DocReaderGrpc.newStub(channel);
                this.connected = true;
            } catch (RuntimeException e) {
                throw new IllegalStateException("gRPC connect failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 对照 GRPCDocumentReader.ListEngines：gRPC ListEngines RPC → 引擎列表。
     * RPC 失败抛 RuntimeException（调用方 fetchRemoteEngines 记 WARN 后回落静态表）。
     */
    public java.util.List<RemoteEngine> listEngines(java.util.Map<String, String> overrides) {
        Docreader.ListEnginesRequest.Builder req = Docreader.ListEnginesRequest.newBuilder();
        if (overrides != null) {
            req.putAllConfigOverrides(overrides);
        }
        Docreader.ListEnginesResponse resp = blocking.withDeadlineAfter(30, TimeUnit.SECONDS)
                .listEngines(req.build());
        java.util.List<RemoteEngine> result = new ArrayList<>();
        for (Docreader.ParserEngineInfo e : resp.getEnginesList()) {
            result.add(new RemoteEngine(e.getName(), e.getDescription(),
                    e.getFileTypesList(), e.getAvailable(), e.getUnavailableReason()));
        }
        return result;
    }
}
