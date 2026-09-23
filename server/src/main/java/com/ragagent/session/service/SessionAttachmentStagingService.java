package com.ragagent.session.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import com.ragagent.agent.tools.RemoteDirEntry;
import com.ragagent.sandbox.runtime.SessionBoundManager;
import com.ragagent.sandbox.runtime.SessionSandboxPaths;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.mapper.TemporaryDocumentRepository;
import com.ragagent.storage.fileserve.FileContentService;
import com.ragagent.storage.fileserve.FileTransport;

/**
 * 会话附件 staging（对照 Go internal/application/service/session_attachment_staging.go
 * 全文）：把持久附件对账进会话沙箱的 /workspace/input，产出 staged 附件清单
 * （供 sandbox_attachments 提示词使用）。
 *
 * <p><b>门控</b>：仅在沙箱管理器支持会话文件面（SessionBoundManager）时执行；
 * 其他后端沿用提示词抽取的附件内容，永远收不到宿主文件路径（Go 注释原文）。</p>
 *
 * <p><b>接缝（主会话接线）</b>：</p>
 * <ul>
 *   <li>{@code fileService}——Go 的 {@code agentService.fileService}（GetFile 读面）。
 *       Java 侧用 W5c 的 {@link FileContentService}；当前容器无 bean（local 一支由
 *       {@code StorageFileResolver} 工厂构造），故 {@code @Autowired(required=false)}
 *       允许缺席：null 且有附件时报 "file service is unavailable for session input
 *       staging"（Go 同文）。</li>
 *   <li>{@code temporaryDocuments}——Go 经 {@code repository.NewTemporaryDocumentRepository(s.db)}
 *       构造，{@code s.db == nil} 时跳过 URL 回填；Java 为 null 时同形跳过（接缝备案）。</li>
 *   <li>接线到 QA 管线（agentService 的 sessionAttachmentStager 接口）由主会话完成。</li>
 * </ul>
 */
public class SessionAttachmentStagingService {

    private final SessionSandboxExecutionService sandboxExecution;
    private final TemporaryDocumentRepository temporaryDocuments;
    private final FileContentService fileService;

    /**
     * 文件服务缺省（生产 = FileProxyService 的进程级默认，经
     * {@code SessionAttachmentStagingBeans} 装配；测试直喂替身）。
     * null 且有附件时按 Go 原文报 "file service is unavailable"。
     */
    public SessionAttachmentStagingService(SessionSandboxExecutionService sandboxExecution,
            TemporaryDocumentRepository temporaryDocuments,
            FileContentService fileService) {
        this.sandboxExecution = sandboxExecution;
        this.temporaryDocuments = temporaryDocuments;
        this.fileService = fileService;
    }

    /** staged 附件（对照 stagedSessionAttachment；path 是沙箱内绝对路径）。 */
    public record StagedSessionAttachment(String name, String fileType, long size, String path) {
    }

    /**
     * 会话输入文件面（对照 sandbox.SessionFileStore 中 staging 用到的三个方法；
     * 生产实现 {@link BoundSessionInputStore}，测试用内存 fake）。
     */
    public interface SessionInputStore {

        List<RemoteDirEntry> listSessionFiles(String sessionId, String dir) throws Exception;

        void writeSessionInputFile(String sessionId, String remotePath, byte[] content)
                throws Exception;

        void removeSessionInputPath(String sessionId, String targetPath);
    }

    /** SessionBoundManager 的会话文件面 → staging 门面（生产装配）。 */
    static final class BoundSessionInputStore implements SessionInputStore {

        private final SessionBoundManager bound;
        private final long tenantId;

        BoundSessionInputStore(SessionBoundManager bound, long tenantId) {
            this.bound = bound;
            this.tenantId = tenantId;
        }

        @Override
        public List<RemoteDirEntry> listSessionFiles(String sessionId, String dir) {
            List<RemoteDirEntry> out = new ArrayList<>();
            for (com.ragagent.sandbox.runtime.SandboxSessionClient.DirEntry e
                    : bound.listSessionFiles(tenantId, sessionId, dir)) {
                out.add(new RemoteDirEntry(e.name(), e.path(),
                        e.type() == null ? RemoteDirEntry.TYPE_OTHER
                                : e.type().name().toLowerCase(java.util.Locale.ROOT),
                        e.size(), e.modTime() == null ? null : e.modTime().toInstant()));
            }
            return out;
        }

        @Override
        public void writeSessionInputFile(String sessionId, String remotePath, byte[] content) {
            bound.writeSessionInputFile(tenantId, sessionId, remotePath, content);
        }

        @Override
        public void removeSessionInputPath(String sessionId, String targetPath) {
            bound.removeSessionInputPath(tenantId, sessionId, targetPath);
        }
    }

    // ── sessionSandboxInputStore（Go L71-95）─────────────────────────────────

    /**
     * 解析本会话沙箱实际运行所在后端的会话文件面。调用方必须以它（而非进程级
     * 管理器）作 staging 门控：不同工作区配置暴露不同能力（Go 注释原文）。
     * 后端不宣告会话文件面 → null（staging 完全跳过）。
     */
    public SessionInputStore sessionSandboxInputStore(long tenantId, String sessionId,
            String agentConfigId) {
        SessionSandboxExecutionService.Resolution r;
        try {
            r = sandboxExecution.resolveForExecution(tenantId, sessionId, agentConfigId);
        } catch (RuntimeException e) {
            throw new IllegalStateException("resolve sandbox config for session "
                    + sessionId + ": " + e.getMessage(), e);
        }
        if (r.manager() instanceof SessionBoundManager bound) {
            return new BoundSessionInputStore(bound, tenantId);
        }
        return null;
    }

    // ── stageSessionAttachments（Go L97-188）─────────────────────────────────

    /**
     * 把 /workspace/input 与持久附件清单对账。门控在沙箱管理器宣告会话文件面
     * 之上；其他后端保留提示词抽取的附件内容，绝不接收宿主文件路径。
     */
    public List<StagedSessionAttachment> stageSessionAttachments(long tenantId,
            String sessionId, String agentConfigId, List<MessageAttachment> attachments) {
        SessionInputStore store = sessionSandboxInputStore(tenantId, sessionId, agentConfigId);
        if (store == null) {
            return List.of();
        }
        return stageSessionAttachments(tenantId, sessionId, store, attachments);
    }

    /** store 已解析的核心循环（包可见，测试直接喂内存 fake）。 */
    List<StagedSessionAttachment> stageSessionAttachments(long tenantId, String sessionId,
            SessionInputStore store, List<MessageAttachment> attachments) {
        if (attachments == null) {
            attachments = List.of();
        }
        if (fileService == null && !attachments.isEmpty()) {
            throw new IllegalStateException("file service is unavailable for session input staging");
        }

        List<MessageAttachment> resolved = resolveSessionAttachmentURLs(tenantId, sessionId,
                attachments);
        List<MessageAttachment> deduped = deduplicateSessionAttachments(resolved);

        List<RemoteDirEntry> existingEntries;
        try {
            existingEntries = store.listSessionFiles(sessionId,
                    SessionSandboxPaths.SESSION_INPUT_ROOT);
        } catch (Exception e) {
            throw new IllegalStateException("list staged session inputs: " + e.getMessage(), e);
        }
        Map<String, RemoteDirEntry> existing = new HashMap<>(existingEntries.size());
        for (RemoteDirEntry entry : existingEntries) {
            existing.put(SessionSandboxPaths.clean(entry.path()), entry);
        }

        Map<String, Boolean> desired = new LinkedHashMap<>(deduped.size());
        List<StagedSessionAttachment> staged = new ArrayList<>(deduped.size());
        for (MessageAttachment attachment : deduped) {
            String remotePath = sandboxAttachmentPath(attachment);
            desired.put(remotePath, Boolean.TRUE);

            RemoteDirEntry entry = existing.get(remotePath);
            if (entry == null || attachment.getFileSize() <= 0
                    || entry.size() != attachment.getFileSize()) {
                byte[] content = readAttachment(attachment);
                try {
                    store.writeSessionInputFile(sessionId, remotePath, content);
                } catch (Exception e) {
                    throw new IllegalStateException("stage attachment " + q(attachment.getFileName())
                            + ": " + e.getMessage(), e);
                }
                attachment.setFileSize(content.length);
            }

            staged.add(new StagedSessionAttachment(attachment.getFileName(),
                    attachment.getFileType(), attachment.getFileSize(), remotePath));
        }

        // 持久消息附件已不存在的输入：删除（Go L177-184）。
        for (String filePath : existing.keySet()) {
            if (!desired.containsKey(filePath)) {
                try {
                    store.removeSessionInputPath(sessionId, filePath);
                } catch (Exception e) {
                    throw new IllegalStateException("remove stale session input " + filePath
                            + ": " + e.getMessage(), e);
                }
            }
        }

        staged.sort(Comparator.comparing(StagedSessionAttachment::path));
        return staged;
    }

    /**
     * 对照 Go 的 GetFile + io.ReadAll(io.LimitReader(reader, maxBytes+1))：Java 的
     * {@link FileContentService#getFile} 一次性物化字节（OpenedFile 的 bytes/seekable
     * 二态），LimitReader 的超限判定退化为读后长度比较——语义相同。
     */
    private byte[] readAttachment(MessageAttachment attachment) {
        FileTransport.OpenedFile opened;
        try {
            opened = fileService.getFile(attachment.getUrl());
        } catch (Exception e) {
            throw new IllegalStateException("open attachment " + q(attachment.getFileName())
                    + ": " + e.getMessage(), e);
        }
        byte[] content;
        try {
            content = opened.bytes() != null
                    ? opened.bytes()
                    : java.nio.file.Files.readAllBytes(opened.seekable());
        } catch (Exception e) {
            throw new IllegalStateException("read attachment " + q(attachment.getFileName())
                    + ": " + e.getMessage(), e);
        }
        long maxBytes = maxFileSizeBytes();
        if (content.length > maxBytes) {
            throw new IllegalStateException(String.format(
                    "attachment %s exceeds sandbox staging limit of %d bytes",
                    q(attachment.getFileName()), maxBytes));
        }
        return content;
    }

    // ── resolveSessionAttachmentURLs（Go L190-227）───────────────────────────

    /**
     * 为消息行上未持久化 URL 的附件补齐存储句柄。MessageAttachment.URL 刻意不进
     * DB（json:"-"），跨会话可下载引用不可外泄；以 attachment.ID 为键的
     * temporary-document 行是存储引用的权威来源。已带 URL 或没有临时文档 ID 的
     * 附件原样通过，让其他附件来源的调用方继续工作（Go 注释原文）。
     */
    List<MessageAttachment> resolveSessionAttachmentURLs(long tenantId, String sessionId,
            List<MessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty() || temporaryDocuments == null) {
            // 接缝：Go 的 s.db == nil 分支——仓储缺席时 URL 回填整体跳过。
            return attachments;
        }
        List<MessageAttachment> out = new ArrayList<>(attachments.size());
        for (MessageAttachment attachment : attachments) {
            if (nonBlank(attachment.getUrl()) || blank(attachment.getId())) {
                out.add(attachment);
                continue;
            }
            TemporaryDocument document;
            try {
                document = temporaryDocuments.getScoped(tenantId, sessionId, attachment.getId());
            } catch (RuntimeException e) {
                throw new IllegalStateException("resolve attachment " + q(attachment.getFileName())
                        + " storage reference: " + e.getMessage(), e);
            }
            if (document == null || blank(document.getResourceRef())) {
                // 过期或已删除的临时文档：URL 留空，deduplicateSessionAttachments
                // 会跳过它，而不是让整个 staging 失败（Go 注释原文）。
                continue;
            }
            attachment.setUrl(document.getResourceRef());
            out.add(attachment);
        }
        return out;
    }

    // ── deduplicateSessionAttachments（Go L229-244）──────────────────────────

    static List<MessageAttachment> deduplicateSessionAttachments(List<MessageAttachment> attachments) {
        if (attachments == null) {
            return List.of();
        }
        Map<String, Boolean> seen = new LinkedHashMap<>(attachments.size());
        List<MessageAttachment> out = new ArrayList<>(attachments.size());
        for (MessageAttachment attachment : attachments) {
            String url = attachment.getUrl() == null ? "" : attachment.getUrl().trim();
            if (url.isEmpty() || seen.containsKey(url)) {
                continue;
            }
            seen.put(url, Boolean.TRUE);
            out.add(attachment);
        }
        return out;
    }

    // ── sandboxAttachmentPath（Go L246-257）──────────────────────────────────

    /**
     * 附件 → 沙箱内路径：/workspace/input/&lt;sha256(url) 前 6 字节 hex&gt;/&lt;安全文件名&gt;。
     * URL 哈希子目录防同名附件互踩与路径猜测。
     */
    static String sandboxAttachmentPath(MessageAttachment attachment) {
        String url = attachment.getUrl() == null ? "" : attachment.getUrl().trim();
        if (url.isEmpty()) {
            throw new IllegalStateException("attachment " + q(attachment.getFileName())
                    + " has no durable storage URL");
        }
        String fileName;
        try {
            fileName = safeFileName(attachment.getFileName());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("unsafe attachment filename "
                    + q(attachment.getFileName()) + ": " + e.getMessage());
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] sum = digest.digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder sub = new StringBuilder(12);
        for (int i = 0; i < 6; i++) {
            sub.append(String.format("%02x", sum[i]));
        }
        return SessionSandboxPaths.join(SessionSandboxPaths.SESSION_INPUT_ROOT,
                sub.toString(), fileName);
    }

    // ── buildSandboxAttachmentsPrompt（Go L259-295）──────────────────────────

    /** staged 附件 → sandbox_attachments 提示词段（XML 形态逐字对照 Go）。 */
    static String buildSandboxAttachmentsPrompt(List<StagedSessionAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        b.append("\n\n<sandbox_attachments root=\"/workspace/input\">\n");
        for (StagedSessionAttachment attachment : attachments) {
            b.append(String.format(
                    "  <file name=\"%s\" type=\"%s\" size_bytes=\"%d\" path=\"%s\" />\n",
                    escapeAttachmentXML(attachment.name()),
                    escapeAttachmentXML(attachment.fileType()),
                    attachment.size(),
                    escapeAttachmentXML(attachment.path())));
        }
        b.append("  <instruction>These are the user's files: read them at the absolute paths above "
                + "and do not write into /workspace/input. Inspect them with read_file, "
                + "or with shell_exec (ls/find) when a shell is available. "
                + "Create generated files with write_sandbox_file "
                + "and patch existing ones with edit_sandbox_file. $WEKNORA_SKILL_OUTPUT_DIR (/workspace/output) "
                + "is the only directory collected for download, so put finished deliverables there "
                + "and keep drafts and intermediate files in any other directory under /workspace.</instruction>\n");
        b.append("</sandbox_attachments>");
        return b.toString();
    }

    static String escapeAttachmentXML(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // ── secutils.SafeFileName / GetMaxFileSizeMB 等价 ────────────────────────

    /**
     * 对照 secutils.SafeFileName（internal/utils/security.go L150-165）：
     * filepath.Base(filepath.Clean) 白名单化 + 路径遍历拒绝 + 255 字节上限。
     * 非法名抛 IllegalArgumentException（Go 返回 error）。
     */
    static String safeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName cannot be empty");
        }
        String base = SessionSandboxPaths.base(SessionSandboxPaths.clean(fileName));
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            throw new IllegalArgumentException("invalid fileName: path traversal or empty name");
        }
        if (base.contains("..")) {
            throw new IllegalArgumentException("invalid fileName: contains path traversal");
        }
        if (base.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("fileName too long");
        }
        return base;
    }

    /**
     * 对照 GetMaxFileSizeMB() * 1024 * 1024（internal/utils/filesize.go）：
     * MAX_FILE_SIZE_MB env，默认 50，非正值回落默认。
     */
    static long maxFileSizeBytes() {
        return maxFileSizeMb() * 1024L * 1024L;
    }

    static long maxFileSizeMb() {
        String sizeStr = System.getenv("MAX_FILE_SIZE_MB");
        if (sizeStr != null && !sizeStr.isEmpty()) {
            try {
                long size = Long.parseLong(sizeStr.trim());
                if (size > 0) {
                    return size;
                }
            } catch (NumberFormatException ignored) {
                // Go envSizeMB：解析失败回落默认
            }
        }
        return 50;
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static boolean nonBlank(String s) {
        return !blank(s);
    }

    /** Go %q 的近似（错误文案用）：双引号包裹 + 反斜杠/双引号转义。 */
    static String q(String s) {
        if (s == null) {
            return "<null>";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
