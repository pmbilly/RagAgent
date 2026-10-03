package com.ragagent.knowledge.client;

import java.time.Duration;
import com.ragagent.knowledge.config.DocReaderProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
import com.google.protobuf.ByteString;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * DocReader gRPC 客户端。
 * 契约要点（依 proto 线格式）：
 * - 首选 ReadStream（首帧必须 meta，随后每帧一张图）；UNIMPLEMENTED 回退 unary Read
 * - 业务错误走响应 error 字段而非 gRPC status
 * - ReadConfig 3 号字段 reserved
 * - 单次调用超时 30 分钟
 * 本仓只消费 meta/markdown 两帧（图片帧忽略并记录）。
 */
@Service
public class DocReaderClient {

    private static final Logger log = LoggerFactory.getLogger(DocReaderClient.class);
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(30);

    /** blocking/asyncStub 可被 {@link #reconnect} 原子换绑（volatile 保可见性）。 */
    private volatile DocReaderGrpc.DocReaderBlockingStub blocking;
    private volatile DocReaderGrpc.DocReaderStub asyncStub;

    /** 远端可达性。 */
    private volatile boolean connected;
    private final Object reconnectLock = new Object();

    /**
     * 地址/传输走 {@link DocReaderProperties} 绑定（env 名与语义未变）。
     */
    public DocReaderClient(DocReaderProperties properties) {
        String addr = properties.addrOrDefault();
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
        // DOCREADER_ADDR 缺省时构造用 localhost:50051 兜底——但连接判据是
        // 「有没有显式配置」（dev/e2e 都显式配置，行为一致），不是真实探活。
        this.connected = properties.addrConfigured();
    }

    /**
     * 解析结果：markdown + 图片数 + 图片引用（含内联字节）。
     * <p>图片字节来自 docreader 的 inline 模式（docreader main.py 的 {@code _resolve_images}
     */
    public record ParseResult(String markdown, int imageCount, List<ImageRef> imageRefs) {
        public ParseResult(String markdown, int imageCount) {
            this(markdown, imageCount, List.of());
        }
    }

    /** docreader 返回的图片引用：文件名、原始引用串、MIME 与内联字节。 */
    public record ImageRef(String filename, String originalRef, String mimeType, byte[] imageData) {
    }

    private static ImageRef toImageRef(Docreader.ImageRef ref) {
        return new ImageRef(ref.getFilename(), ref.getOriginalRef(), ref.getMimeType(),
                ref.getImageData().toByteArray());
    }

    /**
     * 解析文件。
     * @param parserEngine 引擎名（空 = 服务端默认路由）
     */
    public ParseResult read(byte[] fileContent, String fileName, String fileType,
                            String title, String parserEngine) throws Exception {
        Docreader.ReadConfig.Builder config = Docreader.ReadConfig.newBuilder();
        if (parserEngine != null && !parserEngine.isBlank()) {
            config.setParserEngine(parserEngine);
        }
        Docreader.ReadRequest request = Docreader.ReadRequest.newBuilder()
                .setFileContent(ByteString.copyFrom(fileContent))
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
        CountDownLatch latch = new CountDownLatch(1);
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

    // ── 系统管理端（收官批）附加能力 ─────────────────────────────────

    /** 远端引擎信息。 */
    public record RemoteEngine(String name, String description, List<String> fileTypes,
                               boolean available, String unavailableReason) {}

    /**
     * conn != nil。Java 的 ManagedChannel 惰性连接，
     * "配置了地址即连接对象存在"的语义一致。
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 失败抛
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
                newBlocking.withDeadlineAfter(5, TimeUnit.SECONDS)
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
     * gRPC ListEngines RPC → 引擎列表。
     * RPC 失败抛 RuntimeException（调用方 fetchRemoteEngines 记 WARN 后回落静态表）。
     */
    public List<RemoteEngine> listEngines(Map<String, String> overrides) {
        Docreader.ListEnginesRequest.Builder req = Docreader.ListEnginesRequest.newBuilder();
        if (overrides != null) {
            req.putAllConfigOverrides(overrides);
        }
        Docreader.ListEnginesResponse resp = blocking.withDeadlineAfter(30, TimeUnit.SECONDS)
                .listEngines(req.build());
        List<RemoteEngine> result = new ArrayList<>();
        for (Docreader.ParserEngineInfo e : resp.getEnginesList()) {
            result.add(new RemoteEngine(e.getName(), e.getDescription(),
                    e.getFileTypesList(), e.getAvailable(), e.getUnavailableReason()));
        }
        return result;
    }
}
