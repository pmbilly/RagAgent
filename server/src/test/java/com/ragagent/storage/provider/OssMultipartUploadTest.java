package com.ragagent.storage.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadResult;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.UploadPartRequest;
import com.aliyun.oss.model.UploadPartResult;

/**
 * OSS 大文件分片上传（W5γ4.20）——对照 Go {@code oss.go} 的
 * {@code multipartThreshold = 10MB} + {@code Uploader(PartSize=10MB, ParallelNum=3)}：
 * &gt;10MB 走 {@code initiate → uploadPart ×N（并发 3）→ complete}（失败 abort），
 * 小文件仍单次 {@code putObject}；错误前缀照 Go（分片带 {@code (multipart)}）。
 *
 * <p>用 Mockito 桩住 {@code OSS} 客户端（不触网）；片大小/阈值由包内构造器注入小值，
 * 以便在测试里断言片序、片大小与并发上限（Go 常量本身另有用例钉住）。</p>
 */
class OssMultipartUploadTest {

    private static final String BUCKET = "test-bucket";

    private OSS client() {
        OSS client = mock(OSS.class);
        InitiateMultipartUploadResult init = new InitiateMultipartUploadResult();
        init.setUploadId("upload-1");
        when(client.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class)))
                .thenReturn(init);
        return client;
    }

    private static FileService.UploadFile file(byte[] data, String name) {
        return new FileService.UploadFile(name, data.length, () -> new ByteArrayInputStream(data));
    }

    @Test
    @DisplayName("Go 常量钉住：阈值 10MB、片大小 10MB、并发 3")
    void goSpecConstants() {
        assertThat(OssFileService.MULTIPART_THRESHOLD).isEqualTo(10L * 1024 * 1024);
        assertThat(OssFileService.PART_SIZE).isEqualTo(10L * 1024 * 1024);
        assertThat(OssFileService.PARALLEL_NUM).isEqualTo(3);
    }

    @Test
    @DisplayName("小文件（≤ 阈值）：单次 putObject，不走分片")
    void smallFileUsesSinglePutObject() {
        OSS client = client();
        OssFileService service = new OssFileService(client, null, BUCKET, "", "pref", 64,
                10L * 1024 * 1024);
        byte[] data = new byte[1024];

        String path = service.saveFile(file(data, "a.txt"), 7L, "kb-1");

        // pathPrefix 补尾斜杠（照 Go）
        assertThat(path).startsWith("oss://" + BUCKET + "/pref/7/kb-1/").endsWith(".txt");
        verify(client, times(1)).putObject(eq(BUCKET), anyString(), any(InputStream.class),
                any(ObjectMetadata.class));
        verify(client, never()).initiateMultipartUpload(any());
    }

    @Test
    @DisplayName("大文件：initiate → 3 片（顺序/大小对）→ complete 带有序 ETag；不走 putObject")
    void largeFileUsesMultipart() throws Exception {
        OSS client = client();
        List<UploadPartRequest> parts = new CopyOnWriteArrayList<>();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        doAnswer(invocation -> {
            UploadPartRequest req = invocation.getArgument(0);
            parts.add(req);
            int now = inFlight.incrementAndGet();
            maxInFlight.updateAndGet(prev -> Math.max(prev, now));
            byte[] payload = req.getInputStream().readAllBytes();
            assertThat(payload.length).isEqualTo(req.getPartSize());
            inFlight.decrementAndGet();
            UploadPartResult result = new UploadPartResult();
            result.setPartNumber(req.getPartNumber());
            result.setETag("etag-" + req.getPartNumber());
            return result;
        }).when(client).uploadPart(any(UploadPartRequest.class));

        // 阈值 100 / 片 64 → 150 字节 = 64 + 64 + 22 三片
        OssFileService service = new OssFileService(client, null, BUCKET, "", "", 64, 100);
        byte[] data = new byte[150];
        String path = service.saveFile(file(data, "big.bin"), 9L, "kb-9");

        assertThat(path).matches("oss://" + BUCKET + "/9/kb-9/[0-9a-f-]{36}\\.bin");
        ArgumentCaptor<InitiateMultipartUploadRequest> initiate =
                ArgumentCaptor.forClass(InitiateMultipartUploadRequest.class);
        verify(client, times(1)).initiateMultipartUpload(initiate.capture());
        assertThat(initiate.getValue().getBucketName()).isEqualTo(BUCKET);
        assertThat(initiate.getValue().getKey()).contains("9/kb-9/");
        assertThat(initiate.getValue().getObjectMetadata().getContentType())
                .isEqualTo("application/octet-stream");

        // 片序与片大小（按 partNumber 排序核对）
        List<UploadPartRequest> ordered = new ArrayList<>(parts);
        ordered.sort((a, b) -> Integer.compare(a.getPartNumber(), b.getPartNumber()));
        assertThat(ordered).hasSize(3);
        assertThat(ordered).extracting(UploadPartRequest::getPartNumber)
                .containsExactly(1, 2, 3);
        assertThat(ordered).extracting(UploadPartRequest::getPartSize)
                .containsExactly(64L, 64L, 22L);
        assertThat(ordered).allSatisfy(p -> assertThat(p.getUploadId()).isEqualTo("upload-1"));
        assertThat(ordered).allSatisfy(p -> assertThat(p.getKey()).isEqualTo(
                initiate.getValue().getKey()));
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(OssFileService.PARALLEL_NUM);

        ArgumentCaptor<CompleteMultipartUploadRequest> complete =
                ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(client, times(1)).completeMultipartUpload(complete.capture());
        assertThat(complete.getValue().getUploadId()).isEqualTo("upload-1");
        assertThat(complete.getValue().getPartETags()).extracting(t -> t.getPartNumber())
                .containsExactly(1, 2, 3);
        assertThat(complete.getValue().getPartETags()).extracting(t -> t.getETag())
                .containsExactly("etag-1", "etag-2", "etag-3");
        verify(client, never()).putObject(anyString(), anyString(), any(InputStream.class),
                any(ObjectMetadata.class));
        verify(client, never()).abortMultipartUpload(any());
    }

    @Test
    @DisplayName("分片失败：abort 清理 + 错误前缀照 Go（failed to upload file to OSS (multipart)）")
    void multipartFailureAborts() {
        OSS client = client();
        doThrow(new OSSException("boom")).when(client).uploadPart(any(UploadPartRequest.class));
        OssFileService service = new OssFileService(client, null, BUCKET, "", "", 64, 100);

        assertThatThrownBy(() -> service.saveFile(file(new byte[150], "big.bin"), 1L, "kb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("failed to upload file to OSS (multipart): ")
                .hasMessageContaining("boom");

        ArgumentCaptor<AbortMultipartUploadRequest> abort =
                ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(client, times(1)).abortMultipartUpload(abort.capture());
        assertThat(abort.getValue().getUploadId()).isEqualTo("upload-1");
        assertThat(abort.getValue().getBucketName()).isEqualTo(BUCKET);
        verify(client, never()).completeMultipartUpload(any());
    }

    @Test
    @DisplayName("initiate 失败：错误前缀同样是 multipart 分支（不 abort——还没有 uploadId）")
    void initiateFailure() {
        OSS client = mock(OSS.class);
        when(client.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class)))
                .thenThrow(new OSSException("no perms"));
        OssFileService service = new OssFileService(client, null, BUCKET, "", "", 64, 100);

        assertThatThrownBy(() -> service.saveFile(file(new byte[150], "big.bin"), 1L, "kb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("failed to upload file to OSS (multipart): ")
                .hasMessageContaining("no perms");
        verify(client, never()).abortMultipartUpload(any());
    }
}
