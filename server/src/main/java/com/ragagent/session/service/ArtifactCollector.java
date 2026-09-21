package com.ragagent.session.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.agent.tools.RemoteDirEntry;
import com.ragagent.session.domain.MessageArtifact;

/**
 * 沙箱产物排水器（对照 Go internal/application/service/artifact_collector.go
 * 全文，收尾批 2026-09-22 逐行翻译）：回合结束时扫描会话沙箱的 output 目录，
 * 把新增/新修改的文件持久化到租户文件服务，产出 MessageArtifact 列表。
 *
 * <p><b>降级契约</b>：无沙箱绑定、source 缺失（skill 后端关闭）、空 sessionID、
 * 空 outputDir、列目录失败、零条目——全部返回 null（调用方按"没有可附加产物"
 * 处理，不设置 message.Artifacts）。Collect 抛出的错误只限内部不变量被破坏；
 * 单文件失败（不可读/超限/上传失败）记日志跳过，绝不打断回合。</p>
 */
public class ArtifactCollector {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCollector.class);

    /** 对照 defaultMaxArtifactFileBytes：单文件 50 MiB 上限（防 OOM）。 */
    public static final long DEFAULT_MAX_ARTIFACT_FILE_BYTES = 50L * 1024 * 1024;

    /** 沙箱文件面（对照 SandboxArtifactSource：list + read）。 */
    public interface SandboxArtifactSource {
        List<RemoteDirEntry> listSessionFiles(String sessionId, String path) throws Exception;

        byte[] readSessionFile(String sessionId, String path) throws Exception;
    }

    /** 已记录产物读取面（对照 SessionArtifactStore；生产由 message 仓储支撑）。 */
    public interface SessionArtifactStore {
        List<MessageArtifact> knownArtifacts(String sessionId) throws Exception;
    }

    /** 字节落盘面（对照 interfaces.FileService.SaveBytes）。 */
    public interface ArtifactFileStore {
        String saveBytes(byte[] data, long tenantId, String storageName) throws Exception;
    }

    /** 资源绑定面（对照 interfaces.ResourceCatalog.Bind；可选）。 */
    public interface ResourceCatalogBinder {
        void bind(String ref, String ownerType, String ownerId, String relation) throws Exception;
    }

    private final SandboxArtifactSource source;
    private final ArtifactFileStore fileService;
    private final SessionArtifactStore store;
    private final ResourceCatalogBinder catalog;
    private final long maxFileBytes;

    public ArtifactCollector(SandboxArtifactSource source, ArtifactFileStore fileService,
            SessionArtifactStore store, ResourceCatalogBinder catalog) {
        this(source, fileService, store, catalog, DEFAULT_MAX_ARTIFACT_FILE_BYTES);
    }

    public ArtifactCollector(SandboxArtifactSource source, ArtifactFileStore fileService,
            SessionArtifactStore store, ResourceCatalogBinder catalog, long maxFileBytes) {
        this.source = source;
        this.fileService = fileService;
        this.store = store;
        this.catalog = catalog;
        this.maxFileBytes = maxFileBytes <= 0 ? DEFAULT_MAX_ARTIFACT_FILE_BYTES : maxFileBytes;
    }

    /** 对照 Collect（artifact_collector.go L226-234）。 */
    public List<MessageArtifact> collect(String sessionId, String messageId, long tenantId,
            String outputDir) {
        return collect(sessionId, messageId, tenantId, outputDir, null);
    }

    /**
     * 对照 CollectWithNotify：列表过滤后、读文件前回调 notify(待持久数)——
     * 前端用它显示工具条占位。没有待持久文件时跳过 notify。
     */
    public List<MessageArtifact> collect(String sessionId, String messageId, long tenantId,
            String outputDir, java.util.function.IntConsumer notify) {
        if (fileService == null) {
            log.info("[ArtifactCollector] skipped: collector or dependencies nil (session={})",
                    sessionId);
            return null;
        }
        if (source == null) {
            log.info("[ArtifactCollector] skipped: sandbox backend has no session filesystem (session={})",
                    sessionId);
            return null;
        }
        if (sessionId == null || sessionId.isEmpty()) {
            log.info("[ArtifactCollector] skipped: empty sessionID");
            return null;
        }
        if (outputDir == null || outputDir.isEmpty()) {
            log.info("[ArtifactCollector] skipped: empty outputDir (session={})", sessionId);
            return null;
        }

        List<RemoteDirEntry> entries;
        try {
            entries = source.listSessionFiles(sessionId, outputDir);
        } catch (Exception e) {
            log.warn("[ArtifactCollector] list sandbox files failed: session={} dir={} err={}",
                    sessionId, outputDir, e.getMessage());
            return null;
        }
        if (entries == null || entries.isEmpty()) {
            // "下载按钮永远不出现"的最常见原因正是这个分支：沙箱已被回收，或 skill
            // 写去了别的目录。记下精确的 (session, dir) 便于 30 秒定位。
            log.info("[ArtifactCollector] no entries under {} (session={}) — sandbox reaped or skill wrote elsewhere",
                    outputDir, sessionId);
            return null;
        }
        log.info("[ArtifactCollector] listed {} entries under {} (session={})",
                entries.size(), outputDir, sessionId);

        // "已记录"集合：多回合共享沙箱时防止重复附加。出错降级为空集
        // （重复附加是软失败，中止不是）。
        Set<String> known = loadKnownSet(sessionId);

        int pending = 0;
        for (RemoteDirEntry entry : entries) {
            if (acceptEntry(entry, known)) {
                pending++;
            }
        }
        if (pending > 0 && notify != null) {
            notify.accept(pending);
        }

        List<MessageArtifact> artifacts = new ArrayList<>(pending);
        for (RemoteDirEntry entry : entries) {
            MessageArtifact art = maybePersist(sessionId, messageId, tenantId, entry, known);
            if (art == null) {
                continue;
            }
            artifacts.add(art);
            // 循环内更新 known 集：防 ListSessionFiles 返回重复路径的病态情形。
            known.add(artifactKey(art.getSourcePath(), art.getModTime()));
        }
        log.info("[ArtifactCollector] done session={} listed={} attached={}",
                sessionId, entries.size(), artifacts.size());
        return artifacts;
    }

    /** 对照 loadKnownSet：出错返回空集，调用方可继续。 */
    private Set<String> loadKnownSet(String sessionId) {
        Set<String> set = new HashSet<>();
        if (store == null) {
            return set;
        }
        List<MessageArtifact> prev;
        try {
            prev = store.knownArtifacts(sessionId);
        } catch (Exception e) {
            log.warn("[ArtifactCollector] load previous artifacts failed: session={} err={}",
                    sessionId, e.getMessage());
            return set;
        }
        for (MessageArtifact p : prev) {
            set.add(artifactKey(p.getSourcePath(), p.getModTime()));
        }
        return set;
    }

    /** 对照 acceptEntry：文件类型、路径/名字非空、尺寸上限、未记录过。 */
    private boolean acceptEntry(RemoteDirEntry entry, Set<String> known) {
        if (!entry.isFile()) {
            return false;
        }
        if (entry.path() == null || entry.path().isEmpty()
                || entry.name() == null || entry.name().isEmpty()) {
            return false;
        }
        if (entry.size() > maxFileBytes) {
            return false;
        }
        return !known.contains(artifactKey(entry.path(), toOffset(entry.modTime())));
    }

    /**
     * 对照 maybePersist：过滤 → 下载 → 二次尺寸守卫 → UUID 命名上传 → 资源绑定 →
     * 构建元数据。跳过返回 null。
     */
    private MessageArtifact maybePersist(String sessionId, String messageId, long tenantId,
            RemoteDirEntry entry, Set<String> known) {
        if (!acceptEntry(entry, known)) {
            if (entry.isFile() && entry.size() > maxFileBytes) {
                log.warn("[ArtifactCollector] skip oversize artifact: session={} path={} size={} limit={}",
                        sessionId, entry.path(), entry.size(), maxFileBytes);
            }
            return null;
        }
        byte[] data;
        try {
            data = source.readSessionFile(sessionId, entry.path());
        } catch (Exception e) {
            log.warn("[ArtifactCollector] read artifact failed: session={} path={} err={}",
                    sessionId, entry.path(), e.getMessage());
            return null;
        }
        // 二次守卫：envd 报告的可能是重写中的过期尺寸；按实际字节数再卡一次。
        if (data.length > maxFileBytes) {
            log.warn("[ArtifactCollector] skip oversize artifact after read: session={} path={} size={} limit={}",
                    sessionId, entry.path(), data.length, maxFileBytes);
            return null;
        }

        // UUID 命名空间化的存储名：并发回合不冲突，存储键外部不可猜。
        String storageName = "artifact_" + UUID.randomUUID() + "_"
                + safeFileName(entry.name());
        String storagePath;
        try {
            storagePath = fileService.saveBytes(data, tenantId, storageName);
        } catch (Exception e) {
            log.warn("[ArtifactCollector] upload artifact failed: session={} path={} err={}",
                    sessionId, entry.path(), e.getMessage());
            return null;
        }

        bindArtifactResource(storagePath, messageId);

        MessageArtifact art = new MessageArtifact();
        art.setUrl(storagePath);
        art.setFileName(entry.name());
        art.setFileType(fileExtensionLower(entry.name()));
        art.setFileSize(data.length);
        art.setSourcePath(entry.path());
        art.setModTime(toOffset(entry.modTime()));
        art.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return art;
    }

    /**
     * 对照 bindArtifactResource：新产物资源由其 assistant 消息拥有。尽力而为：
     * 绑定失败不丢弃产物（文件已存，/artifacts 端点照常可下载）。仅当 catalog
     * 在位、有 messageID、且 SaveBytes 返回的是 resource:// 引用时才绑定。
     */
    private void bindArtifactResource(String ref, String messageId) {
        if (catalog == null || messageId == null || messageId.isEmpty()) {
            return;
        }
        if (!ref.startsWith("resource://")) {
            return;
        }
        try {
            catalog.bind(ref, "message", messageId, "artifact");
        } catch (Exception e) {
            log.warn("[ArtifactCollector] bind artifact resource failed: message={} ref={} err={}",
                    messageId, ref, e.getMessage());
        }
    }

    /**
     * 对照 ReferencedHistory：仅返回本会话在答案中被显式引用的产物，并把它们的
     * 不可变版本绑定到新消息（删除原消息不能使后续引用失效）。
     */
    public List<MessageArtifact> referencedHistory(String sessionId, String messageId,
            String content) {
        if (store == null) {
            return null;
        }
        Set<String> refs = new HashSet<>();
        for (String ref : ResourceReferences.scan(content)) {
            refs.add(ref);
        }
        if (refs.isEmpty()) {
            return null;
        }
        List<MessageArtifact> previous;
        try {
            previous = store.knownArtifacts(sessionId);
        } catch (Exception e) {
            log.warn("Read referenced artifact history failed: {}", e.getMessage());
            return null;
        }
        List<MessageArtifact> result = new ArrayList<>();
        for (MessageArtifact artifact : previous) {
            if (refs.contains(artifact.getUrl())) {
                result.add(artifact);
                bindArtifactResource(artifact.getUrl(), messageId);
                refs.remove(artifact.getUrl());
            }
        }
        return result;
    }

    /**
     * 对照 artifactKey：(source_path, mtime) 的字符串形态。mtime 归一到 UTC
     * RFC3339 纳秒，跨时区/精度差异保持相等；零值时间 → path + NUL。
     */
    static String artifactKey(String path, OffsetDateTime modTime) {
        if (modTime == null || modTime.toInstant().toEpochMilli() == 0
                || modTime.getYear() <= 1) {
            return path + "\0";
        }
        return path + "\0" + modTime.withOffsetSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.nX"));
    }

    /** 对照 safeFileName：斜杠/反斜杠换下划线；空名兜底 unnamed。 */
    static String safeFileName(String name) {
        String n = name == null ? "" : name.replace("/", "_").replace("\\", "_");
        return n.isEmpty() ? "unnamed" : n;
    }

    private static String fileExtensionLower(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase();
    }

    private static OffsetDateTime toOffset(Instant t) {
        return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    /** 对照 types.ScanResourceReferences：抽答案里全部 resource://<handle> 引用。 */
    static final class ResourceReferences {
        private static final java.util.regex.Pattern RESOURCE_REF =
                java.util.regex.Pattern.compile("resource://[A-Za-z0-9]{22}");

        static List<String> scan(String content) {
            List<String> out = new ArrayList<>();
            if (content == null || content.isEmpty()) {
                return out;
            }
            var m = RESOURCE_REF.matcher(content);
            while (m.find()) {
                out.add(m.group());
            }
            return out;
        }
    }
}
