package com.ragagent.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ragagent.agent.tools.RemoteDirEntry;
import com.ragagent.session.domain.MessageArtifact;

/**
 * 产物排水器的行为验收（对照 Go artifact_collector.go 的 Collect 契约面 +
 * artifact_collector_test.go 的内存 stub 法）：降级链、accept 过滤、已知集去重、
 * 上传命名、信封字段、notify 时机。
 */
class ArtifactCollectorTest {

    /** 沙箱文件面 stub。 */
    static final class FakeSource implements ArtifactCollector.SandboxArtifactSource {
        final Map<String, List<RemoteDirEntry>> listings = new HashMap<>();
        final Map<String, byte[]> files = new HashMap<>();

        @Override
        public List<RemoteDirEntry> listSessionFiles(String sessionId, String path) {
            return listings.getOrDefault(path, List.of());
        }

        @Override
        public byte[] readSessionFile(String sessionId, String path) {
            byte[] data = files.get(path);
            if (data == null) {
                throw new IllegalStateException("file does not exist");
            }
            return data;
        }
    }

    static final class FakeStore implements ArtifactCollector.SessionArtifactStore {
        final Map<String, List<MessageArtifact>> bySession = new HashMap<>();

        @Override
        public List<MessageArtifact> knownArtifacts(String sessionId) {
            return bySession.getOrDefault(sessionId, List.of());
        }
    }

    static final class FakeFileStore implements ArtifactCollector.ArtifactFileStore {
        final Map<String, byte[]> saved = new LinkedHashMap<>();
        int count;

        @Override
        public String saveBytes(byte[] data, long tenantId, String storageName) {
            saved.put(storageName, data.clone());
            return "local://art/" + storageName;
        }
    }

    @Test
    void degradationChainReturnsNull() {
        // 无绑定沙箱（source 无该目录列表）→ null
        FakeSource empty = new FakeSource();
        ArtifactCollector c = new ArtifactCollector(empty, new FakeFileStore(),
                new FakeStore(), null);
        assertNull(c.collect("s1", "m1", 10002L, "/workspace/output"));
        // 空 sessionID → null
        assertNull(c.collect("", "m1", 10002L, "/workspace/output"));
        // 空 outputDir → null
        assertNull(c.collect("s1", "m1", 10002L, ""));
    }

    @Test
    void collectPersistsNewFilesWithEnvelopeFields() throws Exception {
        FakeSource source = new FakeSource();
        Instant mod = Instant.parse("2026-09-22T00:00:00Z");
        source.listings.put("/workspace/output", List.of(
                new RemoteDirEntry("报告.html", "/workspace/output/报告.html",
                        RemoteDirEntry.TYPE_FILE, 10, mod),
                new RemoteDirEntry("sub", "/workspace/output/sub",
                        RemoteDirEntry.TYPE_DIR, 0, mod),
                new RemoteDirEntry("", "/workspace/output/noname",
                        RemoteDirEntry.TYPE_FILE, 5, mod)));
        source.files.put("/workspace/output/报告.html", "0123456789".getBytes());

        FakeFileStore fileStore = new FakeFileStore();
        ArtifactCollector c = new ArtifactCollector(source, fileStore, new FakeStore(), null);

        List<MessageArtifact> arts = c.collect("s1", "m1", 10002L, "/workspace/output");
        assertEquals(1, arts.size(), "目录与非命名文件被 accept 过滤");
        MessageArtifact art = arts.get(0);
        assertEquals("报告.html", art.getFileName());
        assertEquals(".html", art.getFileType(), "扩展名小写");
        assertEquals(10, art.getFileSize());
        assertEquals("/workspace/output/报告.html", art.getSourcePath());
        assertTrue(art.getUrl().endsWith("_报告.html"), "存储名保留原文件名");
        assertEquals(OffsetDateTime.parse("2026-09-22T00:00:00Z"), art.getModTime());
        assertEquals("0123456789", new String(fileStore.saved.values().iterator().next()));
    }

    @Test
    void oversizeAndKnownFilesSkipped() throws Exception {
        FakeSource source = new FakeSource();
        Instant mod = Instant.parse("2026-09-22T00:00:00Z");
        source.listings.put("/workspace/output", List.of(
                new RemoteDirEntry("big.bin", "/workspace/output/big.bin",
                        RemoteDirEntry.TYPE_FILE, 1_000_000, mod),
                new RemoteDirEntry("ok.txt", "/workspace/output/ok.txt",
                        RemoteDirEntry.TYPE_FILE, 3, mod)));
        source.files.put("/workspace/output/ok.txt", "abc".getBytes());
        source.files.put("/workspace/output/big.bin", new byte[0]);

        ArtifactCollector c = new ArtifactCollector(source, new FakeFileStore(),
                new FakeStore(), null, 100);

        List<MessageArtifact> arts = c.collect("s1", "m1", 10002L, "/workspace/output");
        assertEquals(1, arts.size(), "超大文件被过滤");
        assertEquals("ok.txt", arts.get(0).getFileName());
    }

    @Test
    void knownSetPreventsDoubleAttachAndNotifyFiresOnlyWhenPending() throws Exception {
        FakeSource source = new FakeSource();
        Instant mod = Instant.parse("2026-09-22T00:00:00Z");
        source.listings.put("/workspace/output", List.of(
                new RemoteDirEntry("a.txt", "/workspace/output/a.txt",
                        RemoteDirEntry.TYPE_FILE, 1, mod)));
        source.files.put("/workspace/output/a.txt", "x".getBytes());

        FakeStore store = new FakeStore();
        ArtifactCollector c = new ArtifactCollector(source, new FakeFileStore(), store, null);

        List<Integer> notifyCounts = new ArrayList<>();
        List<MessageArtifact> first = c.collect("s1", "m1", 10002L, "/workspace/output",
                notifyCounts::add);
        assertEquals(1, first.size());
        assertEquals(1, notifyCounts.size(), "有 pending 时 notify 一次");
        store.bySession.put("s1", new ArrayList<>(first));

        List<Integer> notifyCounts2 = new ArrayList<>();
        List<MessageArtifact> second = c.collect("s1", "m2", 10002L, "/workspace/output",
                notifyCounts2::add);
        assertTrue(second.isEmpty(), "全部已知 → 空（无新产物）；空列表非 null，同 Go 空切片");
        assertTrue(notifyCounts2.isEmpty(), "零 pending 不触发 notify");
    }

    @Test
    void referencedHistoryBindsOnlyMentionedRefs() throws Exception {
        FakeStore store = new FakeStore();
        MessageArtifact kept = new MessageArtifact();
        kept.setUrl("resource://aaaaaaaaaaaaaaaaaaaaaa"); // 恰 22 字符 handle
        kept.setSourcePath("/workspace/output/a.html");
        MessageArtifact dropped = new MessageArtifact();
        dropped.setUrl("resource://bbbbbbbbbbbbbbbbbbbbbbbb");
        store.bySession.put("s1", List.of(kept, dropped));

        List<String> binds = new ArrayList<>();
        ArtifactCollector c = new ArtifactCollector(new FakeSource(), new FakeFileStore(),
                store, (ref, ownerType, ownerId, relation) -> binds.add(ref + "|" + ownerId));

        List<MessageArtifact> result = c.referencedHistory("s1", "m2",
                "看 resource://aaaaaaaaaaaaaaaaaaaaaa 这个");
        assertEquals(1, result.size());
        assertEquals("resource://aaaaaaaaaaaaaaaaaaaaaa", result.get(0).getUrl());
        assertEquals(1, binds.size());
        assertEquals("resource://aaaaaaaaaaaaaaaaaaaaaa|m2", binds.get(0));

        // 无引用内容 → null
        assertNull(c.referencedHistory("s1", "m3", "没有引用"));
    }

    @Test
    void artifactKeyNormalizesTimezone() {
        OffsetDateTime utc = OffsetDateTime.parse("2026-09-22T00:00:00Z");
        OffsetDateTime same = OffsetDateTime.parse("2026-09-22T08:00:00+08:00");
        assertEquals(ArtifactCollector.artifactKey("/a", utc),
                ArtifactCollector.artifactKey("/a", same), "同瞬时不同时区 → 同键");
        assertEquals("/a\0", ArtifactCollector.artifactKey("/a", null), "零值时间 → path+NUL");
    }

    @Test
    void safeFileNameReplacesSlashes() {
        assertEquals("a_b_c", ArtifactCollector.safeFileName("a/b\\c"));
        assertEquals("unnamed", ArtifactCollector.safeFileName(""));
        assertEquals("unnamed", ArtifactCollector.safeFileName(null));
    }
}
