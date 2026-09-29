package com.ragagent.storage.fileserve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.storage.LocalStorageService;

/**
 * ③ local 双实现去重的语义验证（W5γ5.1）：单一份实现在 {@link StoragePathGuard}，
 * 两支各自委托它——{@code LocalFileContentService}（IOException 通道 → 404）与
 * {@code LocalStorageService}（BizException 信封通道）。本测试钉 Go 语义本身，
 * 并验证**两支的接受/拒绝集合一致**（等价性）。
 */
class StoragePathGuardTest {

    @Test
    @DisplayName("cleanPath：照 Go filepath.Clean（unix 规则）")
    void cleanPathMatchesGo() {
        assertEquals(".", StoragePathGuard.cleanPath(""));
        assertEquals("/", StoragePathGuard.cleanPath("/"));
        assertEquals("a/b", StoragePathGuard.cleanPath("a//b"));
        assertEquals("a/b", StoragePathGuard.cleanPath("a/./b"));
        assertEquals("b", StoragePathGuard.cleanPath("a/../b"));
        assertEquals("../a", StoragePathGuard.cleanPath("../a"));
        assertEquals("/b", StoragePathGuard.cleanPath("/a/../../b"));
        assertEquals("a", StoragePathGuard.cleanPath("a/"));
    }

    @Test
    @DisplayName("stripKnownScheme：resource:// 与 local:// 剥壳；裸路径/绝对路径/null 原样")
    void stripKnownScheme() {
        assertEquals("t/k/a.txt", StoragePathGuard.stripKnownScheme("resource://t/k/a.txt"));
        assertEquals("t/exports/a.csv", StoragePathGuard.stripKnownScheme("local://t/exports/a.csv"));
        assertEquals("a/b.txt", StoragePathGuard.stripKnownScheme("a/b.txt"));
        assertEquals("/abs/a.txt", StoragePathGuard.stripKnownScheme("/abs/a.txt"));
        assertEquals("", StoragePathGuard.stripKnownScheme(null));
    }

    @Test
    @DisplayName("safePathUnderBase：base 内/自身可过；穿越、同名兄弟前缀、空参拒（文案照 Go）")
    void safePathUnderBaseGuard(@TempDir Path base) throws Exception {
        String b = base.toString();
        assertEquals(b + "/x/a.txt", StoragePathGuard.safePathUnderBase(b, b + "/x/a.txt"));
        assertEquals(b, StoragePathGuard.safePathUnderBase(b, b));

        IOException traversal = assertThrows(IOException.class,
                () -> StoragePathGuard.safePathUnderBase(b, b + "/../other/x"));
        assertEquals("invalid file path: path traversal denied: path is outside base directory",
                traversal.getMessage());
        // 同名兄弟目录（/base 与 /base-sibling）——经典前缀误判，必须拒
        assertThrows(IOException.class, () -> StoragePathGuard.safePathUnderBase(b, b + "-sibling/x"));
        assertThrows(IOException.class, () -> StoragePathGuard.safePathUnderBase(b, ""));
        assertThrows(IOException.class, () -> StoragePathGuard.safePathUnderBase("", b + "/x"));
    }

    @Test
    @DisplayName("③ 等价性：知识支（BizException 通道）与守卫（IOException 通道）判定一致")
    void knowledgeBranchAgreesWithGuard(@TempDir Path base) {
        LocalStorageService knowledge = new LocalStorageService(base.toString());
        String b = base.toString();

        // 守卫直接接受/拒绝的输入（已 join 的绝对路径；scheme 形态由知识支先剥壳，见下）
        List<String> accepted = List.of(b + "/t/k/a.txt", b);
        List<String> rejected = List.of(b + "-sibling/x", b + "/../other/x");

        for (String path : accepted) {
            assertTrue(!guardRejects(b, path), "守卫应接受：" + path);
            assertTrue(!knowledgeRejectsTraversal(knowledge, path),
                    "知识支应走到读文件（不是穿越拒绝）：" + path);
        }
        for (String path : rejected) {
            assertTrue(guardRejects(b, path), "守卫应拒绝：" + path);
            assertTrue(knowledgeRejectsTraversal(knowledge, path),
                    "知识支应以穿越守卫拒绝：" + path);
        }

        // scheme 形态：知识支先 stripKnownScheme 再守卫 → 同样不判穿越（文件不存在 → 读失败）
        assertTrue(!knowledgeRejectsTraversal(knowledge, "resource://t/k/a.txt"));
        assertTrue(!knowledgeRejectsTraversal(knowledge, "local://t/k/a.txt"));
    }

    private static boolean guardRejects(String base, String path) {
        try {
            StoragePathGuard.safePathUnderBase(base, path);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean knowledgeRejectsTraversal(LocalStorageService service, String path) {
        try {
            service.readChecked(path);
            return false;
        } catch (BizException e) {
            Object details = e.appError().details();
            return details != null && String.valueOf(details).contains("path traversal denied");
        }
    }
}
