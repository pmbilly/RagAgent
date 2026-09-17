package com.ragagent.knowledge.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 对照 Go internal/application/service/knowledge.go + knowledge_create.go
 * （阶段 3 子集：file/url/manual 创建、分页列表、get/update/delete、folders；
 *  处理管道 = pending→processing→(docreader→chunk→embed)→completed/failed，
 *  asynq 以进程内虚拟线程队列替代（响应契约一致，重试/取消语义见约定 §9））。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkMapper chunkMapper;
    private final LocalStorageService storage;
    private final KnowledgeProcessWorker worker;

    public KnowledgeService(KnowledgeMapper knowledgeMapper,
                            KnowledgeBaseMapper kbMapper,
                            ChunkMapper chunkMapper,
                            LocalStorageService storage,
                            @Lazy KnowledgeProcessWorker worker) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.storage = storage;
        this.worker = worker;
    }

    private static long tenantId() {
        Long tid = TenantContext.currentTenantId();
        return tid == null ? 0 : tid;
    }

    public KnowledgeBase requireKb(String kbId) {
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .eq(KnowledgeBase::getTenantId, tenantId())
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }
        return kb;
    }

    // ── 创建 ─────────────────────────────────────────────────────────────

    /** 对照 CreateKnowledgeFromFile（multipart 已在 controller 解析为字节） */
    public Knowledge createFromFile(String kbId, byte[] fileContent, String fileName,
                                    String displayName, JsonNode customMetadata, String channel) {
        KnowledgeBase kb = requireKb(kbId);
        long maxBytes = LocalStorageService.maxFileSizeBytes();
        if (fileContent.length > maxBytes) {
            throw new BizException(AppError.badRequest(
                    String.format("文件大小不能超过%dMB", LocalStorageService.maxFileSizeMb())));
        }
        String hash = LocalStorageService.md5Hex(fileContent);
        // 重复文件检查（对照 409 duplicate_file）
        Knowledge dup = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getFileHash, hash)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (dup != null) {
            throw new DuplicateKnowledgeException(dup, "duplicate_file", "文件已存在（相同内容）");
        }
        String title = displayName != null && !displayName.isEmpty() ? displayName : fileName;
        String fileType = fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";

        Knowledge k = newKnowledge(kb, "file", title, channel);
        k.setFileName(fileName);
        k.setFileType(fileType);
        k.setFileSize((long) fileContent.length);
        k.setFileHash(hash);
        k.setFilePath(storage.save(tenantId(), k.getId(), fileName, fileContent));
        k.setCustomMetadata(mergeCustomMetadata(customMetadata));
        knowledgeMapper.insert(k);
        worker.enqueue(k.getId());
        return k;
    }

    /** 对照 CreateKnowledgeFromURL：阶段 3 拉取 URL 内容按文件入库（SSRF 校验在 controller） */
    public Knowledge createFromUrl(String kbId, String url, String fileName, String fileType,
                                   String title, String channel) {
        KnowledgeBase kb = requireKb(kbId);
        byte[] content = fetchUrl(url);
        long maxBytes = LocalStorageService.maxFileSizeBytes();
        if (content.length > maxBytes) {
            throw new BizException(AppError.badRequest(
                    String.format("文件大小不能超过%dMB", LocalStorageService.maxFileSizeMb())));
        }
        String hash = LocalStorageService.md5Hex(content);
        Knowledge dup = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getSource, url)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (dup != null) {
            throw new DuplicateKnowledgeException(dup, "duplicate_url", "URL 已存在");
        }
        String fname = fileName != null && !fileName.isEmpty() ? fileName : extractFileNameFromUrl(url);
        Knowledge k = newKnowledge(kb, "file", title != null && !title.isEmpty() ? title : fname, channel);
        k.setSource(url);
        k.setFileName(fname);
        k.setFileType(fileType != null && !fileType.isEmpty() ? fileType
                : (fname.contains(".") ? fname.substring(fname.lastIndexOf('.') + 1).toLowerCase() : ""));
        k.setFileSize((long) content.length);
        k.setFileHash(hash);
        k.setFilePath(storage.save(tenantId(), k.getId(), fname, content));
        k.setCustomMetadata(mergeCustomMetadata(null));
        knowledgeMapper.insert(k);
        worker.enqueue(k.getId());
        return k;
    }

    /** 对照 CreateKnowledgeFromManual：status 仅 draft/publish */
    public Knowledge createManual(String kbId, String title, String content, String status,
                                  String channel) {
        KnowledgeBase kb = requireKb(kbId);
        if (!"draft".equals(status) && !"publish".equals(status)) {
            throw new BizException(AppError.validation("状态仅支持 draft 或 publish"));
        }
        Knowledge k = newKnowledge(kb, "manual", title, channel);
        k.setSource("manual");
        k.setFileName(ensureManualFileName(title));
        // Go string 零值：file_path 恒输出 ""（golden 锁定）
        k.setFilePath("");
        k.setFileType("manual");
        k.setFileSize(0L);
        k.setFileHash("");
        // 键序 = Go ManualKnowledgeMetadata struct 声明序（create 响应是内存对象，逐字节契约）；
        // 入库后经 PG jsonb 规范化（键按长度+字节序），读回路径的 canonical 化在 PgJsonTypeHandler
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("content", content == null ? "" : content);
        metadata.put("format", "markdown");
        metadata.put("status", status);
        metadata.put("version", 1);
        metadata.put("updated_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        k.setMetadata(metadata);
        k.setCustomMetadata(mergeCustomMetadata(null));
        if ("draft".equals(status)) {
            k.setParseStatus("draft");
        }
        knowledgeMapper.insert(k);
        if ("publish".equals(status)) {
            worker.enqueue(k.getId());
        }
        return k;
    }

    private Knowledge newKnowledge(KnowledgeBase kb, String type, String title, String channel) {
        Knowledge k = new Knowledge();
        k.setId(UUID.randomUUID().toString());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        k.setTenantId(tenantId());
        k.setKnowledgeBaseId(kb.getId());
        k.setType(type);
        // 对照 Go：Source 按来源各异——file 上传为零值 ""，url 记 url，manual 记 manual
        k.setTitle(title == null ? "" : title);
        k.setParseStatus(Knowledge.PARSE_PENDING);
        k.setEnableStatus("disabled"); // golden 锁定：上传后 disabled，处理完成转 enabled
        k.setEmbeddingModelId(kb.getEmbeddingModelId());
        k.setChannel(channel == null || channel.isEmpty() ? "web" : channel); // 对照 defaultChannel
        k.setFolderPath(""); // Go 零值，PG 列 NOT NULL
        return k;
    }

    private static JsonNode mergeCustomMetadata(JsonNode provided) {
        if (provided != null && provided.isObject()) {
            return provided;
        }
        return MAPPER.createObjectNode();
    }

    private static String ensureManualFileName(String title) {
        String base = title == null || title.isBlank() ? "manual" : title.trim();
        return base.endsWith(".md") ? base : base + ".md";
    }

    static String extractFileNameFromUrl(String url) {
        if (url == null) {
            return "download";
        }
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? "download" : name;
    }

    private static byte[] fetchUrl(String url) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofMinutes(2))
                    .GET()
                    .build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new BizException(AppError.badRequest("failed to fetch URL: HTTP " + resp.statusCode()));
            }
            return resp.body();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(AppError.badRequest("failed to fetch URL: " + e.getMessage()));
        }
    }

    // ── 查询 / 更新 / 删除 ────────────────────────────────────────────────

    /** 对照 ListKnowledge：真分页（page 默认 1，page_size 默认 20 上限 1000） */
    public Page<Knowledge> listKnowledge(String kbId, long page, long pageSize,
                                         String keyword, String parseStatus, String fileType,
                                         String folderPath, boolean folderPresent) {
        requireKb(kbId);
        LambdaQueryWrapper<Knowledge> qw = new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .isNull(Knowledge::getDeletedAt)
                .orderByDesc(Knowledge::getCreatedAt);
        if (keyword != null && !keyword.isEmpty()) {
            qw.like(Knowledge::getTitle, keyword);
        }
        if (parseStatus != null && !parseStatus.isEmpty()) {
            qw.eq(Knowledge::getParseStatus, parseStatus);
        }
        if (fileType != null && !fileType.isEmpty()) {
            qw.eq(Knowledge::getFileType, fileType);
        }
        if (folderPresent) {
            qw.eq(Knowledge::getFolderPath, folderPath == null ? "" : folderPath);
        }
        return knowledgeMapper.selectPage(new Page<>(page, pageSize), qw);
    }

    public Knowledge getKnowledge(String id) {
        Knowledge k = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, id)
                .eq(Knowledge::getTenantId, tenantId())
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (k == null) {
            throw new BizException(AppError.notFound("Knowledge not found"));
        }
        return k;
    }

    /** 对照 UpdateKnowledge：title/description(指针)/custom_metadata 部分更新 */
    public Knowledge updateKnowledge(String id, JsonNode body) {
        Knowledge k = getKnowledge(id);
        if (body != null) {
            if (body.hasNonNull("title")) {
                k.setTitle(body.get("title").asText());
            }
            if (body.has("description")) {
                k.setDescriptionSpecified(true);
                k.setDescription(body.get("description").isNull() ? "" : body.get("description").asText());
                // 对照 UpdateKnowledge：description 显式更新联动 summary_status
                k.setSummaryStatus(k.getDescription().isEmpty() ? "none" : "completed");
            }
            if (body.has("custom_metadata")) {
                JsonNode cm = body.get("custom_metadata");
                k.setCustomMetadata(cm != null && cm.isObject() ? cm : MAPPER.createObjectNode());
            }
        }
        k.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        knowledgeMapper.updateById(k);
        return getKnowledge(id);
    }

    /** 对照 DeleteKnowledge：异步语义（Go 入队删除）→ 阶段 3 同步软删 + 返回 task_id（响应契约一致） */
    public String deleteKnowledge(String id) {
        Knowledge k = getKnowledge(id);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", k.getId()).set("deleted_at", now));
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .eq("knowledge_id", k.getId()).set("deleted_at", now));
        storage.deleteTree(tenantId(), k.getId());
        return UUID.randomUUID().toString();
    }

    // ── folders（对照 ListKnowledgeFolders 最小实现：根目录统计 + 一层目录） ──

    /** 对照 KnowledgeFolderTree：root_document_count/total_document_count/folders */
    public JsonNode folderTree(String kbId) {
        requireKb(kbId);
        // 对照 ListKnowledgeFolderCounts：只排除 parse_status='deleting'（draft 计入）
        List<Knowledge> docs = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .isNull(Knowledge::getDeletedAt)
                .ne(Knowledge::getParseStatus, Knowledge.PARSE_DELETING));
        long root = docs.stream().filter(d -> d.getFolderPath().isEmpty()).count();
        ObjectNode tree = MAPPER.createObjectNode();
        tree.put("root_document_count", root);
        tree.put("total_document_count", docs.size());
        tree.set("folders", MAPPER.createArrayNode());
        return tree;
    }

    /** 阶段 3 内部：worker 使用的按 id 加载（无租户条件，任务可能跨请求线程） */
    public Knowledge loadById(String id) {
        return knowledgeMapper.selectById(id);
    }

    public void updateStatus(String id, String parseStatus, String errorMessage, Boolean enable) {
        UpdateWrapper<Knowledge> uw = new UpdateWrapper<Knowledge>().eq("id", id);
        uw.set("parse_status", parseStatus);
        uw.set("updated_at", OffsetDateTime.now(ZoneOffset.UTC));
        if (errorMessage != null) {
            uw.set("error_message", errorMessage);
        }
        if (enable != null) {
            uw.set("enable_status", enable ? "enabled" : "disabled");
            uw.set("processed_at", OffsetDateTime.now(ZoneOffset.UTC));
        }
        knowledgeMapper.update(null, uw);
    }

    /** 409 重复文档（对照 handler L173-178 特殊信封，不走 error_handler） */
    public static class DuplicateKnowledgeException extends RuntimeException {
        private final Knowledge existing;
        private final String code;

        public DuplicateKnowledgeException(Knowledge existing, String code, String message) {
            super(message);
            this.existing = existing;
            this.code = code;
        }

        public Knowledge existing() { return existing; }
        public String code() { return code; }
    }

    /** 占位：worker bean 由 KnowledgeProcessWorker 提供（@Lazy 避免循环依赖） */
    public interface KnowledgeProcessWorker {
        void enqueue(String knowledgeId);
    }
}
