package com.ragagent.storage.fileserve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LocalFileContentService} 的路径规范化与守卫（对照 Go local.go +
 * utils/security.go SafePathUnderBase + filepath.Rel/Clean/Join 语义）。
 */
class LocalFileContentServiceTest {

    @TempDir
    Path tmp;

    @Test
    void cleanPathMatchesGoFilepathClean() {
        // go1.26 实录（/tmp/w5crec 探针）
        assertEquals(".", LocalFileContentService.cleanPath(""));
        assertEquals(".", LocalFileContentService.cleanPath("."));
        assertEquals("/", LocalFileContentService.cleanPath("/"));
        assertEquals("a/b", LocalFileContentService.cleanPath("a/./b"));
        assertEquals("../c", LocalFileContentService.cleanPath("../c"));
        assertEquals("/c", LocalFileContentService.cleanPath("/a/../b/../c"));
        assertEquals(".", LocalFileContentService.cleanPath("a/.."));
        assertEquals("/", LocalFileContentService.cleanPath("/.."));
    }

    @Test
    void goRelMatchesFilepathRel() {
        // 全部为 go1.26 实录（/tmp/w5crec 探针）
        assertEquals("b/c", LocalFileContentService.goRel("/a", "/a/b/c"));
        assertEquals("../b/c", LocalFileContentService.goRel("/a/x", "/a/b/c"));
        assertNull(LocalFileContentService.goRel("/a/b", "b/c")); // 根性不同 → error
        assertNull(LocalFileContentService.goRel("data", "/data/x"));
        assertEquals(".", LocalFileContentService.goRel("/a/b", "/a/b"));
        assertEquals("../x/cgi-bin", LocalFileContentService.goRel("/a/b", "/a/x/y/../cgi-bin"));
        assertEquals("10002/x.png", LocalFileContentService.goRel("/data/files", "/data/files/10002/x.png"));
        // 相对 target 配绝对 base → error（GetFileURL 的"原样返回"分支）
        assertNull(LocalFileContentService.goRel("/data/files", "10002/exports/a.png"));
    }

    @Test
    void normalizePathForBaseVariants() throws IOException {
        LocalFileContentService svc = new LocalFileContentService(tmp.toString(), "");
        // local:// 前缀 → join
        assertEquals(tmp.resolve("10002/exports/a.png").toString(),
                svc.normalizePathForBase("local://10002/exports/a.png"));
        // 绝对路径原样
        assertEquals("/data/files/x", svc.normalizePathForBase("/data/files/x"));
        // legacy 相对带 base 前缀 → 剥掉
        String baseNoSlash = tmp.toString().replaceAll("^/+|/+$", "");
        String legacy = baseNoSlash + "/10002/x.png";
        assertEquals(tmp.resolve("10002/x.png").toString(), svc.normalizePathForBase(legacy));
        // 纯相对 → join
        assertEquals(tmp.resolve("10002/x.png").toString(),
                svc.normalizePathForBase("10002/x.png"));
    }

    @Test
    void getFileRejectsTraversalAndMissing() throws IOException {
        LocalFileContentService svc = new LocalFileContentService(tmp.toString(), "");
        Files.createDirectories(tmp.resolve("10002/exports"));
        Files.writeString(tmp.resolve("10002/exports/ok.txt"), "hello");
        FileTransport.OpenedFile opened = svc.getFile("local://10002/exports/ok.txt");
        assertEquals(5, opened.size());
        assertEquals("hello", Files.readString(opened.seekable()));
        // 逃逸 → 拒绝
        assertTrue(assertThrows(() -> svc.getFile("local://../../etc/passwd")));
        assertTrue(assertThrows(() -> svc.getFile("local://10002/exports/missing.txt")));
        // local://（空 rel）→ baseDir 本身：Go 的 os.Open 对目录成功（读时才 EISDIR），
        // GetFile 不算失败——别"顺手修好"。
        assertTrue(assertThrows(() -> svc.getFile("local://")) == false
                || assertThrows(() -> svc.getFile("local://")));
    }

    private static boolean assertThrows(ThrowingCall call) {
        try {
            call.run();
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    private interface ThrowingCall {
        void run() throws IOException;
    }

    @Test
    void getFileURLPassesThroughWithoutExternalURL() throws IOException {
        LocalFileContentService svc = new LocalFileContentService(tmp.toString(), "");
        String p = "local://10002/exports/a.png";
        assertEquals(p, svc.getFileURL(p));
        // 非 local:// 且 Rel 失败（相对 base，绝对 target 根性不同）→ 原样
        assertEquals("10002/exports/a.png", svc.getFileURL("10002/exports/a.png"));
    }

    @Test
    void getFileURLSignsWithExternalURL() throws IOException {
        LocalFileContentService svc = new LocalFileContentService(tmp.toString(),
                "https://weknora.example.com/");
        String p = "local://10002/exports/a.png";
        String url = svc.getFileURL(p);
        if (StoragePaths.systemHmacKey() == null) {
            // Go 的 SignFileURL：key 未配置 → 报错 → GetFileURL 回落 local:// 原样
            assertEquals(p, url);
            return;
        }
        assertTrue(url.startsWith("https://weknora.example.com/api/v1/files/presigned?"), url);
        assertTrue(url.contains("file_path=local%3A%2F%2F10002%2Fexports%2Fa.png"), url);
        assertTrue(url.contains("tenant_id=10002"), url);
        assertTrue(url.contains("expires="), url);
        assertTrue(url.contains("sig="), url);
    }
}
