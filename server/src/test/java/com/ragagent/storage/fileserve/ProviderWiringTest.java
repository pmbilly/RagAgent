package com.ragagent.storage.fileserve;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.storage.provider.FileService;

/**
 * A3-3 接线测试：provider 服务 → fileserve 读取面（适配器）、云 provider 分支经工厂落地、
 * 以及 {@code storageurl.StorageBackendResolver} 桥的失败分支。
 *
 * <p>用 {@code cos} 作代表：它的构造函数不触网（Go 的 cos 构造器也不用探桶），
 * 因此能在无凭据/无网络的 CI 上验证"完备配置 → 真服务"这条此前恒
 * {@code cloudUnavailable} 的路径。oss/tos 构造函数要探桶，属部署态自检。</p>
 */
class ProviderWiringTest {

    // ── 适配器 ──

    @Test
    @DisplayName("ProviderFileContentService：读/写/删/URL 四个方法一一转发，运行时异常折成 IOException")
    void adapterDelegates() throws Exception {
        StubProvider stub = new StubProvider();
        ProviderFileContentService svc = new ProviderFileContentService(stub);

        FileTransport.OpenedFile opened = svc.getFile("cos://b/r/k.png");
        assertEquals("cos://b/r/k.png", stub.lastPath);
        // W5γ5.1：读面已是流形态——长度未知（0，照 Go 的 SDK body）、不整对象入堆
        assertNull(opened.bytes());
        assertEquals(0, opened.size());
        assertArrayEquals(new byte[]{1, 2, 3}, opened.readAllBytes());

        assertEquals("https://signed", svc.getFileURL("cos://b/r/k.png"));
        assertEquals("cos://b/r/out.csv", svc.saveBytes(new byte[]{1, 2}, 7L, "out.csv", false));
        svc.deleteFile("cos://b/r/k.png");
        assertTrue(stub.deleted);

        // provider 抛运行时异常 → 端口契约的 IOException（HTTP 层折 404）
        stub.fail = true;
        assertThrows(IOException.class, () -> svc.getFile("cos://b/r/k.png"));
        assertThrows(IOException.class, () -> svc.getFileURL("cos://b/r/k.png"));
    }

    @Test
    @DisplayName("流式响应：不整对象入堆、头照 Go（none；Content-Length 只认 Options.size）、写完关流")
    void streamServeShape() throws Exception {
        StubProvider stub = new StubProvider();
        ProviderFileContentService svc = new ProviderFileContentService(stub);

        // 1) GET：Accept-Ranges: none，无 Content-Length（Options.size=0，照 Go 的非 seekable 分支）
        MockHttpServletResponse response = new MockHttpServletResponse();
        FileTransport.serve(response, new MockHttpServletRequest("GET", "/files"),
                svc.getFile("cos://b/r/k.png"),
                new FileTransport.Options("k.png", false, "", "", "private, no-store", 0));
        assertEquals(200, response.getStatus());
        assertEquals("none", response.getHeader("Accept-Ranges"));
        assertNull(response.getHeader("Content-Length"));
        assertArrayEquals(new byte[]{1, 2, 3}, response.getContentAsByteArray());
        assertTrue(stub.lastStreamClosed, "响应写完后必须关流（Go 的 defer reader.Close()）");

        // 2) HEAD + 已知 size（artifact 形态）：带 Content-Length，不写体，仍关流
        stub.lastStreamClosed = false;
        MockHttpServletResponse head = new MockHttpServletResponse();
        FileTransport.serve(head, new MockHttpServletRequest("HEAD", "/files"),
                svc.getFile("cos://b/r/k.png"),
                new FileTransport.Options("k.png", true, "", "", "", 3));
        assertEquals(200, head.getStatus());
        assertEquals("3", head.getHeader("Content-Length"));
        assertEquals(0, head.getContentAsByteArray().length);
        assertTrue(stub.lastStreamClosed, "HEAD 也要关流");
    }

    // ── StorageFileResolver 的云分支（A3-3 接线前恒 cloudUnavailable） ──

    @Test
    @DisplayName("StorageFileResolver：完备 cos 配置 → 真 provider 服务；不完备 → 照 Go 的错误文案")
    void cloudBranchBuildsRealService() {
        StorageFileResolver resolver = new StorageFileResolver(null, null);

        // 不完备：错误文案逐字（presigned-preview 的 400 body 依赖它）
        StorageFileResolver.FactoryResult missing = resolver.newFileServiceFromStorageConfig(
                "cos", null, "/tmp");
        assertEquals("incomplete cos config", missing.error());

        ObjectNode sec = completeCosConfig();
        StorageFileResolver.FactoryResult built = resolver.newFileServiceFromStorageConfig(
                "cos", sec, "/tmp");
        assertNull(built.error());
        assertInstanceOf(ProviderFileContentService.class, built.service());

        // 未知 provider 仍是明确报错（不是未实现）
        StorageFileResolver.FactoryResult unknown = resolver.newFileServiceFromStorageConfig(
                "ceph", sec, "/tmp");
        assertEquals("unsupported provider \"ceph\"", unknown.error());
    }

    private static ObjectNode completeCosConfig() {
        ObjectNode sec = new ObjectMapper().createObjectNode();
        sec.put("default_provider", "cos");
        ObjectNode cos = sec.putObject("cos");
        cos.put("secret_id", "id");
        cos.put("secret_key", "key");
        cos.put("bucket_name", "bk-125");
        cos.put("region", "ap-guangzhou");
        return sec;
    }

    // ── 桥的失败分支（成功分支要 DB 仓储，属集成态） ──

    @Test
    @DisplayName("FileserveStorageBackendResolver：无租户 id / 租户不存在 → 空解析（调用方回落）")
    void bridgeFailureBranches() {
        FileserveStorageBackendResolver bridge = new FileserveStorageBackendResolver(
                new StorageFileResolver(null, null), null);

        assertNull(bridge.resolveFileService(0, "", "cos", "/tmp").fileService());
        // tenantService 为 null 时不给 id>0 的请求（构造期已判 <=0 直接返回）
        assertNull(bridge.resolveFileService(-1, "b1", "cos", "/tmp").fileService());
    }

    // ── 桩 ──

    private static final class StubProvider implements FileService {
        String lastPath;
        boolean deleted;
        boolean fail;
        boolean lastStreamClosed;

        private void maybeFail() {
            if (fail) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void checkConnectivity() {
        }

        @Override
        public String saveFile(UploadFile file, long tenantId, String knowledgeId) {
            maybeFail();
            return "cos://b/r/saved";
        }

        @Override
        public String saveBytes(byte[] data, long tenantId, String fileName, boolean temp) {
            maybeFail();
            return "cos://b/r/" + fileName;
        }

        @Override
        public InputStream getFile(String filePath) {
            maybeFail();
            lastPath = filePath;
            return new ByteArrayInputStream(new byte[]{1, 2, 3}) {
                @Override
                public void close() throws IOException {
                    lastStreamClosed = true;
                    super.close();
                }
            };
        }

        @Override
        public void deleteFile(String filePath) {
            maybeFail();
            deleted = true;
        }

        @Override
        public String getFileURL(String filePath) {
            maybeFail();
            return "https://signed";
        }

        @Override
        public String copyFile(String srcPath, long tenantId, String knowledgeId) {
            maybeFail();
            return "cos://b/r/copied";
        }
    }
}
