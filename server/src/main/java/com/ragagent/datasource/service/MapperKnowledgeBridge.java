package com.ragagent.datasource.service;

import com.ragagent.common.web.JsonMappers;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.knowledge.storage.LocalStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.ragagent.common.error.ErrorCode;

/**
 * {@link KnowledgeBridge} 的生产实现：直接用 knowledge 模块的
 * {@link KnowledgeMapper} / {@link KnowledgeBaseMapper} / {@link LocalStorageService}
 * 与进程内处理队列。
 *
 * <h2>为什么不复用 {@code KnowledgeService}</h2>
 * <p>它的每个读写方法都从 {@code TenantContext} 取租户（{@code getKnowledge} 还会顺手做
 * API-Key 的 KB 白名单校验），而同步跑在后台线程上——那里<b>没有</b>请求上下文，
 * 也没有"给一次调用指定租户"的重载，所以这里按同样的 SQL 语义实现了一遍
 * <b>显式传租户</b>的版本。</p>
 *
 * <h2>方言分叉：jsonb 上的 {@code ->>}</h2>
 * <p>两条查询都要在 SQL 里做 {@code metadata->>'key' = ?}（这么写才能吃到 PG 的
 * 表达式索引）。</p>
 * <ul>
 *   <li><b>PG</b>：原样下推到 SQL。</li>
 *   <li><b>H2</b>：项目测试库把 jsonb 一律承载成 {@code VARCHAR}（{@code TestSchema}
 *       的既有约定），没有 {@code ->>} 运算符 → 退化成"取本租户本知识库的未删行，
 *       在 Java 里解析 metadata 精确匹配"。分支的开关方式与
 *       {@code session.mapper.SessionRepository} 的 {@code ILIKE}/{@code NULLS LAST}
 *       一致（探测 JDBC 产品名，探测失败按 H2 走）。</li>
 * </ul>
 *
 * <h2>落库语义清单</h2>
 * <ol>
 *   <li><b>插入钩子</b>：知识行的 UUID 与
 *       {@code custom_metadata='{}'}——这里显式赋值（与 {@code KnowledgeService} 一致）。</li>
 *   <li><b>关联预加载</b>：无（{@code tags} 非表字段）。</li>
 *   <li><b>软删除</b>：所有查询显式带 {@code deleted_at IS NULL}；
 *       {@code hardDelete*} 是物理 DELETE。</li>
 *   <li><b>默认排序</b>：本类不引入排序（查询契约也没有排序）。</li>
 *   <li><b>唯一索引/外键</b>：无。</li>
 *   <li><b>自动时间戳</b>：CREATE 时显式写 {@code created_at}/{@code updated_at}。</li>
 * </ol>
 */
@Component
public class MapperKnowledgeBridge implements KnowledgeBridge {

    private static final Logger log = LoggerFactory.getLogger(MapperKnowledgeBridge.class);

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ChunkMapper chunkMapper;
    private final LocalStorageService storage;
    /** A3-3 尾批：租户感知文件存储（本地契约不变；云 provider 租户落对象存储）。 */
    private final com.ragagent.knowledge.storage.TenantFileStorage fileStorage;
    private final KnowledgeService.KnowledgeProcessWorker worker;
    private final boolean postgres;

    public MapperKnowledgeBridge(KnowledgeMapper knowledgeMapper,
                                 KnowledgeBaseMapper kbMapper,
                                 ChunkMapper chunkMapper,
                                 LocalStorageService storage,
                                 com.ragagent.knowledge.storage.TenantFileStorage fileStorage,
                                 KnowledgeService.KnowledgeProcessWorker worker,
                                 DataSource dataSource) {
        this.knowledgeMapper = knowledgeMapper;
        this.kbMapper = kbMapper;
        this.chunkMapper = chunkMapper;
        this.storage = storage;
        this.fileStorage = fileStorage;
        this.worker = worker;
        this.postgres = detectPostgres(dataSource);
    }

    // ── 知识库 ───────────────────────────────────────────────────────────────

    /**
     * 只按 id 查 + {@code deleted_at IS NULL}，
     * <b>没有</b>租户条件——租户归属由调用方比对。
     */
    @Override
    public KnowledgeBase findKnowledgeBase(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            return null;
        }
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────

    @Override
    public Knowledge findByDataSourceExternalId(long tenantId, String kbId, String dataSourceId,
                                                String externalId) {
        if (postgres) {
            List<Knowledge> rows = knowledgeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Knowledge>()
                            .eq("tenant_id", tenantId)
                            .eq("knowledge_base_id", kbId)
                            .apply("deleted_at IS NULL")
                            .apply("metadata->>'datasource_id' = {0}", dataSourceId)
                            .apply("metadata->>'external_id' = {0}", externalId)
                            .last("LIMIT 1"));
            return rows == null || rows.isEmpty() ? null : rows.get(0);
        }
        for (Knowledge k : listLive(tenantId, kbId)) {
            Map<String, String> md = readMetadata(k);
            if (dataSourceId.equals(md.get("datasource_id")) && externalId.equals(md.get("external_id"))) {
                return k;
            }
        }
        return null;
    }

    @Override
    public List<Knowledge> findByMetadataKeyPrefix(long tenantId, String kbId, String key, String prefix) {
        List<Knowledge> out = new ArrayList<>();
        if (key == null || key.isEmpty() || prefix == null || prefix.isEmpty()) {
            return out;
        }
        if (postgres) {
            List<Knowledge> rows = knowledgeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Knowledge>()
                            .eq("tenant_id", tenantId)
                            .eq("knowledge_base_id", kbId)
                            .apply("deleted_at IS NULL")
                            .apply("metadata->>'" + key.replace("'", "''") + "' LIKE {0} ESCAPE '\\\\'",
                                    escapeLike(prefix) + "%"));
            return rows == null ? out : rows;
        }
        for (Knowledge k : listLive(tenantId, kbId)) {
            String value = readMetadata(k).get(key);
            if (value != null && value.startsWith(prefix)) {
                out.add(k);
            }
        }
        return out;
    }

    // ── 写入 ─────────────────────────────────────────────────────────────────

    /**
     * 文件写入的<b>最小闭环</b>：重复内容检测 →
     * 落 Storage → 落 knowledges 行 → 交给处理队列。
     *
     * <p>见 {@link KnowledgeBridge} 的"已知差异"：文件名安全校验、多模态/问题生成配置、
     * 标签关系、按 KB 选存储引擎、处理任务载荷形态都不在这里。</p>
     */
    @Override
    public Knowledge createFromFile(long tenantId, String kbId, byte[] content, String fileName,
                                    Map<String, String> metadata, List<String> tagIds, String channel) {
        KnowledgeBase kb = findKnowledgeBase(kbId);
        if (kb == null) {
            throw new com.ragagent.common.error.BizException(
                    com.ragagent.common.error.AppError.notFound("knowledge base not found"));
        }
        String safeName = fileName == null || fileName.isEmpty() ? "untitled" : fileName;
        String fileType = fileTypeOf(safeName);
        String hash = LocalStorageService.md5Hex(content);

        // 重复检测：同库同哈希同类型即重复
        Knowledge dup = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .eq(Knowledge::getFileHash, hash)
                .eq(Knowledge::getFileType, fileType)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
        if (dup != null) {
            throw new KnowledgeService.DuplicateKnowledgeException(
                    dup, ErrorCode.KNOWLEDGE_DUPLICATE_FILE,
                    "文件已存在（相同内容）");
        }

        Knowledge k = newKnowledge(tenantId, kb, safeName, fileType, channel);
        k.setFileSize((long) content.length);
        k.setFileHash(hash);
        k.setFilePath(fileStorage.save(tenantId, k.getId(), safeName, content));
        k.setMetadata(metadataNode(metadata));
        knowledgeMapper.insert(k);
        worker.enqueue(k.getId());
        return k;
    }

    /**
     * URL 写入的<b>最小闭环</b>：让知识库自己下载。
     *
     * <p>⚠️ <b>返回的行没有 datasource 的 metadata</b>——调用方在"新建"
     * 分支上再补一次（重复分支复用已有行，不该被重新打标）。</p>
     */
    @Override
    public Knowledge createFromUrl(long tenantId, String kbId, String url, String fileName,
                                   String title, List<String> tagIds, String channel) {
        KnowledgeBase kb = findKnowledgeBase(kbId);
        if (kb == null) {
            throw new com.ragagent.common.error.BizException(
                    com.ragagent.common.error.AppError.notFound("knowledge base not found"));
        }
        String name = fileName != null && !fileName.isEmpty()
                ? fileName
                : extractFileNameFromUrl(url);
        Knowledge k = newKnowledge(tenantId, kb, title != null && !title.isEmpty() ? title : name,
                fileTypeOf(name), channel);
        k.setSource(url);
        k.setFileName(name);
        // URL 型文档先不落 Storage：真正的下载/解析由处理队列做（Go 交给 asynq 的 docreader）
        k.setFilePath("");
        knowledgeMapper.insert(k);
        worker.enqueue(k.getId());
        return k;
    }

    @Override
    public void attachMetadata(Knowledge knowledge, Map<String, String> metadata) {
        UpdateWrapper<Knowledge> uw = new UpdateWrapper<Knowledge>()
                .eq("id", knowledge.getId())
                .eq("tenant_id", knowledge.getTenantId())
                .set("metadata", metadataNode(metadata), "typeHandler="
                        + com.ragagent.common.web.PgJsonTypeHandler.class.getName())
                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC));
        knowledgeMapper.update(null, uw);
        knowledge.setMetadata(metadataNode(metadata));
    }

    // ── 删除 ─────────────────────────────────────────────────────────────────

    /** 软删知识行 + 软删分片 + 清文件。 */
    @Override
    public void softDelete(long tenantId, String knowledgeId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        knowledgeMapper.update(null, new UpdateWrapper<Knowledge>()
                .eq("id", knowledgeId).eq("tenant_id", tenantId).set("deleted_at", now));
        chunkMapper.update(null, new UpdateWrapper<com.ragagent.knowledge.domain.Chunk>()
                .eq("knowledge_id", knowledgeId).set("deleted_at", now));
        // A3-3 尾批：本地目录树恒清 + 若是 provider 引用则额外删对象（best-effort）
        Knowledge row = knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, tenantId)
                .last("LIMIT 1"));
        fileStorage.delete(tenantId, knowledgeId, row == null ? null : row.getFilePath());
    }

    @Override
    public void softDeleteList(long tenantId, List<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return;
        }
        for (String id : knowledgeIds) {
            softDelete(tenantId, id);
        }
    }

    /** 物理删单条。 */
    @Override
    public void hardDelete(long tenantId, String knowledgeId) {
        knowledgeMapper.delete(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .eq(Knowledge::getTenantId, tenantId));
    }

    @Override
    public void hardDeleteList(long tenantId, List<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return;
        }
        knowledgeMapper.delete(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getTenantId, tenantId)
                .in(Knowledge::getId, knowledgeIds));
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /** 非 PG 路径的候选集：本租户 + 本知识库 + 未软删。 */
    private List<Knowledge> listLive(long tenantId, String kbId) {
        List<Knowledge> rows = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getTenantId, tenantId)
                .eq(Knowledge::getKnowledgeBaseId, kbId)
                .isNull(Knowledge::getDeletedAt));
        return rows == null ? new ArrayList<>() : rows;
    }

    /** 从 {@code metadata} 列读出字符串 map。 */
    private static Map<String, String> readMetadata(Knowledge k) {
        Map<String, String> out = new LinkedHashMap<>();
        com.fasterxml.jackson.databind.JsonNode md = k.getMetadata();
        if (md == null || !md.isObject()) {
            return out;
        }
        md.fields().forEachRemaining(e -> {
            if (e.getValue() != null && e.getValue().isTextual()) {
                out.put(e.getKey(), e.getValue().asText());
            }
        });
        return out;
    }

    /** 把字符串 map 序列化成 {@code metadata} 列的 JSON（键按给定序写出、不排序）。 */
    private static com.fasterxml.jackson.databind.JsonNode metadataNode(Map<String, String> metadata) {
        ObjectNode node = MAPPER.createObjectNode();
        if (metadata != null) {
            for (Map.Entry<String, String> e : metadata.entrySet()) {
                node.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        return node;
    }

    /**
     * 构造一条新知识行（字段集与 {@code KnowledgeService.newKnowledge} 相同，但<b>显式传租户</b>）。
     */
    private static Knowledge newKnowledge(long tenantId, KnowledgeBase kb, String title,
                                          String fileType, String channel) {
        Knowledge k = new Knowledge();
        k.setId(UUID.randomUUID().toString());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        k.setTenantId(tenantId);
        k.setKnowledgeBaseId(kb.getId());
        k.setType("file");
        k.setTitle(title == null ? "" : title);
        k.setFileName(title);
        k.setFileType(fileType);
        k.setParseStatus(Knowledge.PARSE_PENDING);
        k.setEnableStatus("disabled");
        k.setEmbeddingModelId(kb.getEmbeddingModelId());
        k.setChannel(channel);
        k.setFolderPath("");
        k.setCustomMetadata(MAPPER.createObjectNode());
        return k;
    }

    /**
     * 从 URL 提取文件名（去 query 串、取最后一段；空则 {@code "download"}）。
     */
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

    /** 取最后一个点之后的小写扩展名（无点则空串）。 */
    static String fileTypeOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** LIKE 转义：{@code \}、{@code %}、{@code _}。 */
    static String escapeLike(String v) {
        return v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 与 {@code SessionRepository.detectPostgres} 同一处置：探测失败按非 postgres 走。 */
    private static boolean detectPostgres(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            log.warn("[datasource] cannot detect database product, assuming non-postgres: {}",
                    e.getMessage());
            return false;
        }
    }
}
