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

    private final DocReaderGrpc.DocReaderBlockingStub blocking;
    private final DocReaderGrpc.DocReaderStub asyncStub;

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
    }

    /** 解析结果：markdown + 图片数（阶段 3 忽略图片内容） */
    public record ParseResult(String markdown, int imageCount) {}

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
        int imageCount = meta.getImageCount();
        return new ParseResult(meta.getMarkdownContent(), imageCount);
    }

    private ParseResult readUnary(Docreader.ReadRequest request) {
        Docreader.ReadResponse resp = blocking.withDeadlineAfter(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .read(request);
        if (!resp.getError().isEmpty()) {
            throw new IllegalStateException("docreader parse error: " + resp.getError());
        }
        return new ParseResult(resp.getMarkdownContent(), resp.getImageRefsCount());
    }
}
