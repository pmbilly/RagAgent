package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ragagent.agent.tools.RemoteDirEntry;
import com.ragagent.sandbox.runtime.DisabledSandboxManager;
import com.ragagent.sandbox.runtime.SandboxSessionClient;
import com.ragagent.sandbox.runtime.SessionBoundManager;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.mapper.TemporaryDocumentRepository;
import com.ragagent.storage.fileserve.FileContentService;
import com.ragagent.storage.fileserve.FileTransport;

/**
 * 附件 staging 的行为验收（对照 Go session_attachment_staging.go 全文 + 同名
 * _test.go 的内存 stub 法）：路径派生、去重、URL 回填、existing/desired 对账、
 * stale 清理、提示词 XML、降级链。
 */
class SessionAttachmentStagingServiceTest {

    /** 文件服务 stub（对照 Go 测试的 fake fileService.GetFile）。 */
    static final class FakeFiles implements FileContentService {
        final Map<String, byte[]> byUrl = new LinkedHashMap<>();

        @Override
        public FileTransport.OpenedFile getFile(String filePath) throws IOException {
            byte[] data = byUrl.get(filePath);
            if (data == null) {
                throw new IOException("file does not exist");
            }
            return FileTransport.OpenedFile.ofBytes(data);
        }

        @Override
        public String getFileURL(String filePath) {
            return filePath;
        }
    }

    /** 会话输入面 stub（对照 Go 测试的 fake SessionFileStore）。 */
    static final class FakeStore implements SessionAttachmentStagingService.SessionInputStore {
        final List<RemoteDirEntry> listing = new ArrayList<>();
        final Map<String, byte[]> written = new LinkedHashMap<>();
        final List<String> removed = new ArrayList<>();

        @Override
        public List<RemoteDirEntry> listSessionFiles(String sessionId, String dir) {
            return listing;
        }

        @Override
        public void writeSessionInputFile(String sessionId, String remotePath, byte[] content) {
            written.put(remotePath, content.clone());
        }

        @Override
        public void removeSessionInputPath(String sessionId, String targetPath) {
            removed.add(targetPath);
        }
    }

    private static MessageAttachment attachment(String id, String name, String type,
            String url, long size) {
        MessageAttachment a = new MessageAttachment();
        a.setId(id);
        a.setFileName(name);
        a.setFileType(type);
        a.setUrl(url);
        a.setFileSize(size);
        return a;
    }

    private static SessionAttachmentStagingService service(TemporaryDocumentRepository repo,
            FileContentService files) {
        return new SessionAttachmentStagingService(mock(SessionSandboxExecutionService.class),
                repo, files);
    }

    // ── sandboxAttachmentPath / safeFileName ─────────────────────────────────

    @Test
    void attachmentPathUsesSha256PrefixAndSafeName() {
        // sha256("hello") = 2cf24dba5fb0...，前 6 字节 hex = "2cf24dba5fb0"
        MessageAttachment a = attachment("", "report.pdf", ".pdf", "hello", 0);
        String path = SessionAttachmentStagingService.sandboxAttachmentPath(a);
        assertThat(path).isEqualTo("/workspace/input/2cf24dba5fb0/report.pdf");
    }

    @Test
    void attachmentPathRejectsBlankUrl() {
        MessageAttachment a = attachment("", "report.pdf", ".pdf", "  ", 0);
        assertThatThrownBy(() -> SessionAttachmentStagingService.sandboxAttachmentPath(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no durable storage URL")
                .hasMessageContaining("\"report.pdf\"");
    }

    @Test
    void safeFileNameFollowsGoSemantics() {
        // 普通名原样；目录部分被 Base 剥离（Go filepath.Base 的实际行为）
        assertThat(SessionAttachmentStagingService.safeFileName("a.txt")).isEqualTo("a.txt");
        assertThat(SessionAttachmentStagingService.safeFileName("dir/b.txt")).isEqualTo("b.txt");
        assertThat(SessionAttachmentStagingService.safeFileName("../etc/passwd"))
                .isEqualTo("passwd");
        // 空 / .. / 含 ".." 的名字 → error（Go 同文）
        assertThatThrownBy(() -> SessionAttachmentStagingService.safeFileName(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fileName cannot be empty");
        assertThatThrownBy(() -> SessionAttachmentStagingService.safeFileName(".."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid fileName: path traversal or empty name");
        assertThatThrownBy(() -> SessionAttachmentStagingService.safeFileName("a..b.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid fileName: contains path traversal");
        assertThatThrownBy(() -> SessionAttachmentStagingService.safeFileName("x".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fileName too long");
        // unsafe 文件名折进 staging 路径错误
        MessageAttachment a = attachment("", "a..b.txt", ".txt", "u", 0);
        assertThatThrownBy(() -> SessionAttachmentStagingService.sandboxAttachmentPath(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsafe attachment filename");
    }

    // ── deduplicateSessionAttachments ────────────────────────────────────────

    @Test
    void dedupeDropsBlankUrlsAndDuplicates() {
        List<MessageAttachment> in = List.of(
                attachment("1", "a.txt", ".txt", "u1", 1),
                attachment("2", "b.txt", ".txt", " u1 ", 2),
                attachment("3", "c.txt", ".txt", "", 3),
                attachment("4", "d.txt", ".txt", "u2", 4));
        List<MessageAttachment> out = SessionAttachmentStagingService
                .deduplicateSessionAttachments(in);
        assertThat(out).extracting(MessageAttachment::getFileName)
                .containsExactly("a.txt", "d.txt");
    }

    // ── resolveSessionAttachmentURLs ─────────────────────────────────────────

    @Test
    void resolvePassesThroughUrlBearingAndIdlessAttachments() {
        TemporaryDocumentRepository repo = mock(TemporaryDocumentRepository.class);
        SessionAttachmentStagingService s = service(repo, new FakeFiles());
        List<MessageAttachment> in = List.of(
                attachment("", "a.txt", ".txt", "u1", 1),
                attachment("", "b.txt", ".txt", "", 2));
        assertThat(s.resolveSessionAttachmentURLs(10002L, "s1", in))
                .containsExactly(in.get(0), in.get(1));
        verifyNoInteractions(repo);
    }

    @Test
    void resolveFillsUrlFromTemporaryDocument() {
        TemporaryDocumentRepository repo = mock(TemporaryDocumentRepository.class);
        TemporaryDocument doc = new TemporaryDocument();
        doc.setResourceRef("local://docs/ref");
        when(repo.getScoped(10002L, "s1", "doc-1")).thenReturn(doc);
        SessionAttachmentStagingService s = service(repo, new FakeFiles());

        MessageAttachment a = attachment("doc-1", "a.txt", ".txt", "", 1);
        List<MessageAttachment> out = s.resolveSessionAttachmentURLs(10002L, "s1", List.of(a));
        assertThat(out).hasSize(1);
        assertThat(a.getUrl()).isEqualTo("local://docs/ref");
    }

    @Test
    void resolveDropsExpiredDocumentInsteadOfFailing() {
        TemporaryDocumentRepository repo = mock(TemporaryDocumentRepository.class);
        when(repo.getScoped(10002L, "s1", "doc-1")).thenReturn(null);
        SessionAttachmentStagingService s = service(repo, new FakeFiles());

        MessageAttachment a = attachment("doc-1", "a.txt", ".txt", "", 1);
        List<MessageAttachment> out = s.resolveSessionAttachmentURLs(10002L, "s1", List.of(a));
        assertThat(out).isEmpty();
    }

    @Test
    void resolveRepoFailureIsWrapped() {
        TemporaryDocumentRepository repo = mock(TemporaryDocumentRepository.class);
        when(repo.getScoped(10002L, "s1", "doc-1")).thenThrow(new RuntimeException("db down"));
        SessionAttachmentStagingService s = service(repo, new FakeFiles());

        MessageAttachment a = attachment("doc-1", "a.txt", ".txt", "", 1);
        assertThatThrownBy(() -> s.resolveSessionAttachmentURLs(10002L, "s1", List.of(a)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolve attachment \"a.txt\" storage reference");
    }

    @Test
    void resolveWithoutRepositoryIsPassThroughSeam() {
        // 接缝：仓储缺席（Go 的 s.db == nil）→ URL 回填整体跳过
        SessionAttachmentStagingService s = service(null, new FakeFiles());
        List<MessageAttachment> in = List.of(attachment("doc-1", "a.txt", ".txt", "", 1));
        assertThat(s.resolveSessionAttachmentURLs(10002L, "s1", in)).isSameAs(in);
    }

    // ── stageSessionAttachments 核心循环 ─────────────────────────────────────

    @Test
    void missingAttachmentIsWrittenAndStaged() {
        FakeFiles files = new FakeFiles();
        files.byUrl.put("local://x", "hello".getBytes());
        FakeStore store = new FakeStore();
        SessionAttachmentStagingService s = service(null, files);

        List<SessionAttachmentStagingService.StagedSessionAttachment> staged =
                s.stageSessionAttachments(10002L, "s1", store,
                        List.of(attachment("", "a.txt", ".txt", "local://x", 0)));

        assertThat(staged).hasSize(1);
        assertThat(staged.get(0).name()).isEqualTo("a.txt");
        assertThat(staged.get(0).fileType()).isEqualTo(".txt");
        assertThat(staged.get(0).size()).isEqualTo(5);
        assertThat(staged.get(0).path()).isEqualTo("/workspace/input/735498b775d7/a.txt");
        assertThat(store.written).containsKey("/workspace/input/735498b775d7/a.txt");
    }

    @Test
    void matchingEntryIsNotRewritten() {
        FakeFiles files = new FakeFiles();
        files.byUrl.put("local://x", "hello".getBytes());
        FakeStore store = new FakeStore();
        store.listing.add(new RemoteDirEntry("a.txt", "/workspace/input/735498b775d7/a.txt",
                RemoteDirEntry.TYPE_FILE, 5, null));
        SessionAttachmentStagingService s = service(null, files);

        s.stageSessionAttachments(10002L, "s1", store,
                List.of(attachment("", "a.txt", ".txt", "local://x", 5)));
        assertThat(store.written).isEmpty();
    }

    @Test
    void sizeMismatchTriggersRewrite() {
        FakeFiles files = new FakeFiles();
        files.byUrl.put("local://x", "hello!".getBytes());
        FakeStore store = new FakeStore();
        store.listing.add(new RemoteDirEntry("a.txt", "/workspace/input/735498b775d7/a.txt",
                RemoteDirEntry.TYPE_FILE, 7, null));
        SessionAttachmentStagingService s = service(null, files);

        List<SessionAttachmentStagingService.StagedSessionAttachment> staged =
                s.stageSessionAttachments(10002L, "s1", store,
                        List.of(attachment("", "a.txt", ".txt", "local://x", 5)));
        assertThat(store.written).containsKey("/workspace/input/735498b775d7/a.txt");
        assertThat(staged.get(0).size()).isEqualTo(6);
    }

    @Test
    void staleInputsAreRemoved() {
        FakeFiles files = new FakeFiles();
        files.byUrl.put("local://x", "hi".getBytes());
        FakeStore store = new FakeStore();
        store.listing.add(new RemoteDirEntry("old.txt", "/workspace/input/deadbeef01/old.txt",
                RemoteDirEntry.TYPE_FILE, 2, null));
        SessionAttachmentStagingService s = service(null, files);

        s.stageSessionAttachments(10002L, "s1", store,
                List.of(attachment("", "a.txt", ".txt", "local://x", 2)));
        assertThat(store.removed).containsExactly("/workspace/input/deadbeef01/old.txt");
    }

    @Test
    void unreadableAttachmentIsRejectedWithGoWording() {
        FakeFiles files = new FakeFiles(); // 无 local://missing
        FakeStore store = new FakeStore();
        SessionAttachmentStagingService s = service(null, files);

        assertThatThrownBy(() -> s.stageSessionAttachments(10002L, "s1", store,
                List.of(attachment("", "gone.txt", ".txt", "local://missing", 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("open attachment \"gone.txt\"");
    }

    @Test
    void nullFileServiceWithAttachmentsIsRejected() {
        FakeStore store = new FakeStore();
        SessionAttachmentStagingService s = service(null, null);
        assertThatThrownBy(() -> s.stageSessionAttachments(10002L, "s1", store,
                List.of(attachment("", "a.txt", ".txt", "local://x", 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("file service is unavailable for session input staging");
    }

    @Test
    void emptyAttachmentListStillReconcilesStaleInputs() {
        FakeStore store = new FakeStore();
        store.listing.add(new RemoteDirEntry("old.txt", "/workspace/input/aa/old.txt",
                RemoteDirEntry.TYPE_FILE, 2, null));
        SessionAttachmentStagingService s = service(null, null);
        assertThat(s.stageSessionAttachments(10002L, "s1", store, List.of())).isEmpty();
        assertThat(store.removed).containsExactly("/workspace/input/aa/old.txt");
    }

    @Test
    void stagedOutputIsSortedByPath() {
        FakeFiles files = new FakeFiles();
        files.byUrl.put("u1", "a".getBytes());
        files.byUrl.put("u2", "b".getBytes());
        FakeStore store = new FakeStore();
        SessionAttachmentStagingService s = service(null, files);

        List<SessionAttachmentStagingService.StagedSessionAttachment> staged =
                s.stageSessionAttachments(10002L, "s1", store, List.of(
                        attachment("", "z.txt", ".txt", "u1", 1),
                        attachment("", "a.txt", ".txt", "u2", 1)));
        assertThat(staged).isSortedAccordingTo(
                java.util.Comparator.comparing(
                        SessionAttachmentStagingService.StagedSessionAttachment::path));
    }

    // ── sessionSandboxInputStore 的门控 ──────────────────────────────────────

    @Test
    void inputStoreRequiresSessionBoundManager() {
        SessionSandboxExecutionService exec = mock(SessionSandboxExecutionService.class);
        when(exec.resolveForExecution(10002L, "s1", "cfg-1"))
                .thenReturn(new SessionSandboxExecutionService.Resolution(
                        mock(SessionBoundManager.class), "cfg-1"));
        when(exec.resolveForExecution(10002L, "s2", "cfg-1"))
                .thenReturn(new SessionSandboxExecutionService.Resolution(
                        new DisabledSandboxManager(), "cfg-1"));
        SessionAttachmentStagingService s = new SessionAttachmentStagingService(exec, null, null);

        assertThat(s.sessionSandboxInputStore(10002L, "s1", "cfg-1")).isNotNull();
        assertThat(s.sessionSandboxInputStore(10002L, "s2", "cfg-1")).isNull();
    }

    @Test
    void boundInputStoreProjectsDirEntries() throws Exception {
        SessionBoundManager bound = mock(SessionBoundManager.class);
        when(bound.listSessionFiles(10002L, "s1", "/workspace/input")).thenReturn(List.of(
                new SandboxSessionClient.DirEntry("a.txt", "/workspace/input/ab/a.txt",
                        SandboxSessionClient.DirEntryType.FILE, 3,
                        java.time.OffsetDateTime.parse("2026-09-23T00:00:00Z"))));
        SessionSandboxExecutionService exec = mock(SessionSandboxExecutionService.class);
        when(exec.resolveForExecution(10002L, "s1", "cfg-1"))
                .thenReturn(new SessionSandboxExecutionService.Resolution(bound, "cfg-1"));
        SessionAttachmentStagingService s = new SessionAttachmentStagingService(exec, null, null);

        SessionAttachmentStagingService.SessionInputStore store =
                s.sessionSandboxInputStore(10002L, "s1", "cfg-1");
        List<RemoteDirEntry> entries = store.listSessionFiles("s1", "/workspace/input");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).type()).isEqualTo("file");
        assertThat(entries.get(0).path()).isEqualTo("/workspace/input/ab/a.txt");
        assertThat(entries.get(0).size()).isEqualTo(3);
    }

    @Test
    void resolveFailureInInputStoreIsWrapped() {
        SessionSandboxExecutionService exec = mock(SessionSandboxExecutionService.class);
        when(exec.resolveForExecution(10002L, "s1", "cfg-1"))
                .thenThrow(new RuntimeException("endpoint down"));
        SessionAttachmentStagingService s = new SessionAttachmentStagingService(exec, null, null);

        assertThatThrownBy(() -> s.sessionSandboxInputStore(10002L, "s1", "cfg-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolve sandbox config for session s1");
    }

    // ── buildSandboxAttachmentsPrompt ────────────────────────────────────────

    @Test
    void promptIsEmptyWithoutAttachments() {
        assertThat(SessionAttachmentStagingService.buildSandboxAttachmentsPrompt(List.of()))
                .isEmpty();
        assertThat(SessionAttachmentStagingService.buildSandboxAttachmentsPrompt(null))
                .isEmpty();
    }

    @Test
    void promptMatchesGoXmlShape() {
        String expected = "\n\n<sandbox_attachments root=\"/workspace/input\">\n"
                + "  <file name=\"report.pdf\" type=\".pdf\" size_bytes=\"5\" "
                + "path=\"/workspace/input/2cf24dba5fb0/report.pdf\" />\n"
                + "  <instruction>These are the user's files: read them at the absolute paths above "
                + "and do not write into /workspace/input. Inspect them with read_file, "
                + "or with shell_exec (ls/find) when a shell is available. "
                + "Create generated files with write_sandbox_file "
                + "and patch existing ones with edit_sandbox_file. $WEKNORA_SKILL_OUTPUT_DIR (/workspace/output) "
                + "is the only directory collected for download, so put finished deliverables there "
                + "and keep drafts and intermediate files in any other directory under /workspace.</instruction>\n"
                + "</sandbox_attachments>";
        List<SessionAttachmentStagingService.StagedSessionAttachment> staged = List.of(
                new SessionAttachmentStagingService.StagedSessionAttachment(
                        "report.pdf", ".pdf", 5, "/workspace/input/2cf24dba5fb0/report.pdf"));
        assertThat(SessionAttachmentStagingService.buildSandboxAttachmentsPrompt(staged))
                .isEqualTo(expected);
    }

    @Test
    void promptEscapesXmlAttributeDelimiters() {
        List<SessionAttachmentStagingService.StagedSessionAttachment> staged = List.of(
                new SessionAttachmentStagingService.StagedSessionAttachment(
                        "a<b>&\"x\"'.txt", ".txt", 1, "/workspace/input/ab/a.txt"));
        String prompt = SessionAttachmentStagingService.buildSandboxAttachmentsPrompt(staged);
        assertThat(prompt).contains(
                "name=\"a&lt;b&gt;&amp;&quot;x&quot;&apos;.txt\"");
    }
}
