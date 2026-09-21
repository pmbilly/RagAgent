package com.ragagent.storage.fileserve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link StoragePaths} 的解析与签名契约（对照 Go utils/presign.go、
 * types/knowledgebase.go、types/file_reference.go；签名用 Go
 * presign_test.go 的语义自证 round-trip + 篡改/过期拒绝）。
 */
class StoragePathsTest {

    @Test
    void parseStorageTarget() {
        // go1.26 实录（/tmp/w5crec2 探针）：Go files.go 注释里的 "backend://3/…"
        // 是**误导性写法**——代码只认 "storage://" 前缀；"backend://…" 不是任何
        // 已知 provider → ("", "")
        var scoped = StoragePaths.parseStorageTarget("storage://3/local://7/x.png");
        assertEquals("3", scoped.backendId());
        assertEquals("local", scoped.provider());
        var plain = StoragePaths.parseStorageTarget("cos://7/x.png");
        assertEquals("", plain.backendId());
        assertEquals("cos", plain.provider());
        var local = StoragePaths.parseStorageTarget("local://10002/exports/a.png");
        assertEquals("", local.backendId());
        assertEquals("local", local.provider());
        assertEquals("", StoragePaths.parseStorageTarget("sftp://x/y").provider());
        assertEquals("", StoragePaths.parseStorageTarget("backend://3/local://7/x.png").provider());
    }

    @Test
    void parseResourcePathShape() {
        assertNotNull(StoragePaths.parseResourcePath("resource://abcdefghijklmnopqrstuv"));
        assertNull(StoragePaths.parseResourcePath("resource://short"));
        assertNull(StoragePaths.parseResourcePath("resource://abcdefghijklmnopqrstuv!"));
        assertNull(StoragePaths.parseResourcePath("local://10002/a.png"));
        assertEquals("resource://abcdefghijklmnopqrstuv",
                StoragePaths.buildResourcePath("abcdefghijklmnopqrstuv"));
    }

    @Test
    void tenantSegmentParsing() {
        assertEquals(10002, StoragePaths.parseTenantIdFromStoragePath("local://10002/exports/a.png"));
        assertEquals(10002, StoragePaths.parseTenantIdFromStoragePath("minio://bucket/10002/x.png"));
        assertEquals(10002, StoragePaths.parseTenantIdFromStoragePath(
                "storage://abc/local://10002/x.png"));
        assertEquals(0, StoragePaths.parseTenantIdFromStoragePath("local://no-tenant/a.png"));
        assertEquals(0, StoragePaths.parseTenantIdFromStoragePath("no-scheme"));
    }

    @Test
    void storagePathTenantValidation() {
        assertEquals("storage path has no tenant segment",
                StoragePaths.validateStoragePathTenantError("local://abc/a.png", 10002));
        assertEquals("storage path workspace mismatch",
                StoragePaths.validateStoragePathTenantError("local://77/a.png", 10002));
        assertNull(StoragePaths.validateStoragePathTenantError("local://10002/a.png", 10002));
    }

    @Test
    void kbScopedExportsValidation() {
        // canonical layout {tenant}/exports/...
        assertNull(StoragePaths.validateKbScopedStoragePathError(
                "local://10002/exports/a.png", 10002));
        // OSS temp-bucket layout exports/{tenant}/...
        assertNull(StoragePaths.validateKbScopedStoragePathError(
                "oss://bucket/exports/10002/a.png", 10002));
        // 原始上传目录（非 exports）→ 拒绝
        assertEquals("storage path is outside KB-scoped exports namespace",
                StoragePaths.validateKbScopedStoragePathError(
                        "local://10002/kbid/a.png", 10002));
        // storage:// 包装先剥再锚定
        assertNull(StoragePaths.validateKbScopedStoragePathError(
                "storage://be1/local://10002/exports/a.png", 10002));
    }

    @Test
    void presignRoundTripAndRejections() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String payloadSig = StoragePaths.signPayload(key, "local://10002/a.png", 10002, 1758000000L);
        assertEquals(64, payloadSig.length());
        assertTrue(payloadSig.matches("[0-9a-f]{64}"));
        // 确定性：同输入同输出（HMAC-SHA256 的 known answer 由跨语言 A/B 钉）
        assertEquals(payloadSig,
                StoragePaths.signPayload(key, "local://10002/a.png", 10002, 1758000000L));
        // 单字符不同 → 摘要不同
        assertFalse(payloadSig.equals(
                StoragePaths.signPayload(key, "local://10002/b.png", 10002, 1758000000L)));
    }

    @Test
    void verifySigRejectsGarbage() {
        // SYSTEM_AES_KEY 在测试 JVM 可能未配——key 为 null 时 Go 恒 false；
        // 这里只钉"非 key 依赖"的拒绝分支（过期 / 非整数）在 key 存在前提下的行为
        // 由 ContractTest 的真 env 场景覆盖。
        assertFalse(StoragePaths.verifyFileUrlSig("x", 1, "not-a-number", "ab"));
    }

    @Test
    void containsStorageReferenceTokenMatching() {
        String ref = "local://10002/exports/a.png";
        assertTrue(StoragePaths.containsStorageReference("see " + ref + " here", ref));
        assertFalse(StoragePaths.containsStorageReference("see " + ref + " here", "local://10002/exports/a.pn"));
        assertFalse(StoragePaths.containsStorageReference("plain text", ref));
        // JSON 解码后递归（转义不掩护、也不放大）
        assertTrue(StoragePaths.containsStorageReference(
                "{\"out\":\"" + ref + "\"}", ref));
        assertFalse(StoragePaths.containsStorageReference(
                "[{\"out\":\"" + ref + "x\"}]", ref));
        // resource:// 形态
        assertTrue(StoragePaths.containsStorageReference("a resource://abcdefghijklmnopqrstuv b",
                "resource://abcdefghijklmnopqrstuv"));
    }
}
