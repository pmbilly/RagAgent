package com.ragagent.knowledge.mapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.common.CleanInvalidUtf8;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.ChunkRevision;
import org.springframework.stereotype.Component;

/**
 * chunk 仓储（对照 Go internal/application/repository/chunk.go），方法式门面：
 * GORM 隐式行为在 JVM 内显式复刻，service/controller 层不得再猜。
 *
 * <h2>GORM 隐式行为清单（约定 §3，写代码前逐条对照）</h2>
 * <ol>
 *   <li><b>Save 全字段更新但 Omit SeqID</b>（Go L330）：{@code Save} 对<b>有主键</b>的行
 *       是 {@code Select("*")} 全字段 UPDATE——零值也写（string ""、bool false、int 0、
 *       nil JSON → SQL NULL）；{@code seq_id} 被排除。另外 GORM 给名为 UpdatedAt 的字段
 *       自动刷时间（autoUpdateTime 回调）——Save 把 {@code updated_at} 覆盖为 now 并
 *       <b>回写进调用方的 struct</b>，Java 在 {@link #updateChunk} 里显式复刻这两步。
 *       （已知差异：GORM Save 在影响行数为 0 时回退 INSERT，HTTP 面不可达，未复刻。）</li>
 *   <li><b>SaveChunkRevision 的乐观锁 UPDATE 用 map</b>（Go L342-353）：{@code Updates(map)}
 *       不走"结构体零值跳过"——8 个键无条件写入（含 {@code content} 的空串）；
 *       map 里显式带 {@code updated_at} 键，所以 autoUpdateTime 不会覆盖它。</li>
 *   <li><b>软删除的三张面孔</b>：{@code gorm.DeletedAt} 不只过滤 SELECT——QueryClauses、
 *       UpdateClauses、DeleteClauses 分别给查询加 {@code deleted_at IS NULL}、
 *       给 UPDATE 加同款 WHERE（gorm.io/gorm soft_delete.go 的
 *       SoftDeleteQueryClause/SoftDeleteUpdateClause）、把 DELETE 改写成
 *       {@code SET deleted_at = now WHERE ... AND deleted_at IS NULL}。三个删除方法都是
 *       <b>软删</b>（Go 源没有 Unscoped），重复删除第二次是 no-op（不会刷新时间戳）。</li>
 *   <li><b>Find 对切片初始化为非 nil</b>：GORM {@code Find(&slice)} 查不到也返回空切片
 *       （不是 nil）→ 响应是 {@code "data":[]}。Java 全部列表方法返回空 List，永不返回
 *       null——service 层直接透传即可得到 {@code []}。</li>
 *   <li><b>ListPaged 的 IN 与常量</b>：{@code chunk_type IN (...)} + {@code status IN (2,0)}
 *       （ChunkStatusIndexed/ChunkStatusDefault，注意 Stored=1 <b>不</b>在内）；
 *       排序二选一：FAQ 按 {@code updated_at}（默认 DESC）、文档按 {@code chunk_index}
 *       （默认 ASC）。page/size 在 repo 层直接收<b>已钳位</b>的 offset/limit
 *       （Go 的 {@code Pagination.Offset()/Limit()} 语义，钳位归 handler/service）。</li>
 *   <li><b>First 找不到返回错误</b>：GetChunkByID/GetChunkByIDOnly 把
 *       {@code gorm.ErrRecordNotFound} 翻成 {@code ErrChunkNotFound}
 *       （→ {@link ChunkNotFoundException}）；GetChunkRevision 的 First 错误<b>原样上抛</b>
 *       （Go service 继续透传），Java 按项目惯例返回 null，由 service 层决定 404 文案。</li>
 * </ol>
 *
 * <h2>刻意未翻译（属于后续模块，翻译时再补到这里）</h2>
 * <p>FAQ 专用方法子集已在波 2 第四批补齐（GetChunkBySeqID / ListChunksBySeqID /
 * ListAllFAQChunksByKnowledgeID / ListAllFAQChunksWithMetadataByKnowledgeBaseID /
 * FindFAQChunkWithDuplicateQuestion / ListAllFAQChunksForExport / UpdateChunkFlagsBatch /
 * UpdateChunkFieldsByTagID / UpdateChunks / SaveChunks / DeleteUnindexedChunks）。
 * 仍未翻译：FAQChunkDiff / ListFAQChunkStatusByIDs / ListRecommendedFAQChunks /
 * ListRecentDocumentChunksWithQuestions / CreateChunks（CreateChunks 的 MP insert 等价物在
 * FaqService.createChunks 内联）/ MoveChunksByKnowledgeID / CountChunksByKnowledgeBaseID /
 * ListChunksByIDOnly / ListChunksByKnowledgeID(AndTypes) / ListChunksByParentIDs /
 * DeleteChunksByTagID / ListImageInfoByKnowledgeIDs / ListAllChunksByKnowledgeID。</p>
 *
 * <h2>方言探测</h2>
 * <p>{@code ListPagedChunksByKnowledgeID} 的 FAQ 关键词搜索按数据库产品名切 PG/非 PG 分支
 * （Go 的 {@code db.Dialector.Name() == "postgres"}），探测方式与
 * {@code VectorStoreService}/{@code MessageRepository} 同款（构造期问一次
 * {@code DatabaseProductName}）。H2 走非 PG 分支；非 PG 分支的 SQL 是 MySQL 语法，
 * <b>H2 跑不了 FAQ 关键词搜索</b>（Go 的非 PG 分支本来也只服务 MySQL/SQLite）——
 * FAQ 关键词条留待真 PG 的 e2e 验证。</p>
 */
@Component
public class ChunkRepository {

    /** 三条 json 列共用的类型处理器（3 参 set 的 mapping 串）。 */
    private static final String PG_JSON = "com.ragagent.common.web.PgJsonTypeHandler";

    /** Go types/chunk.go 的 ChunkStatus / KnowledgeType 常量。 */
    private static final int STATUS_DEFAULT = 0;
    private static final int STATUS_INDEXED = 2;
    private static final String KNOWLEDGE_TYPE_FAQ = "faq";

    /** Go DeleteChunks 的占位符分批上限（MySQL Error 1390 防御，照抄）。 */
    private static final int DELETE_BATCH_SIZE = 5000;

    private final ChunkMapper chunkMapper;
    private final ChunkRevisionMapper revisionMapper;
    private final ChunkTxTemplate tx;
    /** 方言探测（构造期问一次）：ListPaged 的 FAQ 关键词搜索分支。 */
    private final boolean postgres;

    public ChunkRepository(ChunkMapper chunkMapper, ChunkRevisionMapper revisionMapper,
                           ChunkTxTemplate tx, javax.sql.DataSource dataSource) {
        this.chunkMapper = chunkMapper;
        this.revisionMapper = revisionMapper;
        this.tx = tx;
        this.postgres = detectPostgres(dataSource);
    }

    private static boolean detectPostgres(javax.sql.DataSource dataSource) {
        try (java.sql.Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(java.util.Locale.ROOT).contains("postgres");
        } catch (java.sql.SQLException e) {
            return false;
        }
    }

    /** {@code ListPagedChunksByKnowledgeID} 的返回：GORM 语义里它同时返回行与总数。 */
    public record ChunkPage(List<Chunk> items, long total) {
        public ChunkPage {
            items = items == null ? List.of() : items;
        }
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code GetChunkByID}（L73-82）：tenant + id，软删行不可见；
     * 找不到抛 {@link ChunkNotFoundException}（Go 的 ErrChunkNotFound）。
     */
    public Chunk getChunkById(long tenantId, String id) {
        Chunk chunk = chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getId, id)
                .isNull(Chunk::getDeletedAt));
        if (chunk == null) {
            throw new ChunkNotFoundException();
        }
        return chunk;
    }

    /**
     * 对照 Go {@code GetChunkByIDOnly}（L85-94）：**无租户过滤**（权限解析用）；
     * 软删行同样不可见，找不到抛 {@link ChunkNotFoundException}。
     */
    public Chunk getChunkByIdOnly(String id) {
        Chunk chunk = chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getId, id)
                .isNull(Chunk::getDeletedAt));
        if (chunk == null) {
            throw new ChunkNotFoundException();
        }
        return chunk;
    }

    /**
     * 对照 Go {@code ListChunksByID}（L109-119）：tenant + id IN，软删行不可见。
     * <p>Go 的空切片会展开成 {@code IN (NULL)}（匹配零行）；MyBatis-Plus 的空集合
     * {@code IN ()} 是 SQL 语法错误，所以显式短路为空列表——净效果相同。</p>
     */
    public List<Chunk> listChunksById(long tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .in(Chunk::getId, ids)
                .isNull(Chunk::getDeletedAt));
    }

    /**
     * 对照 Go {@code ListPagedChunksByKnowledgeID}（L184-294）。
     *
     * @param offset/limit <b>已钳位</b>的偏移与页大小（Go 的 {@code Pagination.Offset()/Limit()}
     *                     是调用方算好的值，本层不做钳位）
     * @param chunkTypes   chunk_type IN 的白名单（Go handler 传的类型列表；空列表在 Go
     *                     展开成 {@code IN (NULL)} 匹配零行，这里同样短路）
     * @param tagIds       非空时追加 {@code tag_id IN}
     * @param isEnabled    非空时追加 {@code is_enabled =}
     * @param keyword      先 TrimSpace（Go 的 unicode.IsSpace 全集，Java 的 {@code strip()}
     *                     差 U+00A0/U+0085，显式复刻）；空串不加搜索条件。knowledgeType
     *                     ≠ "faq" 只搜 {@code content LIKE}；"faq" 按 searchField 切四条
     *                     JSON 路径（PG 用 {@code ->> + ILIKE}，非 PG 分支是 MySQL 语法）
     * @param sortOrder    仅接受 "asc"/"desc" 字面量，其余值用各自默认方向（照抄 Go）
     * @param knowledgeType "faq" 决定排序键与搜索面
     */
    public ChunkPage listPagedChunksByKnowledgeId(
            long tenantId, String knowledgeId, int offset, int limit,
            List<String> chunkTypes, List<String> tagIds,
            String keyword, String searchField, String sortOrder,
            String knowledgeType, Boolean isEnabled) {
        String kw = goTrimSpace(keyword);

        // Go 先 Count（baseFilter 的独立一份），再分页查数据（又一份 baseFilter）
        long total = chunkMapper.selectCount(pagedFilter(
                tenantId, knowledgeId, chunkTypes, tagIds, kw, searchField, knowledgeType, isEnabled));

        QueryWrapper<Chunk> data = pagedFilter(
                tenantId, knowledgeId, chunkTypes, tagIds, kw, searchField, knowledgeType, isEnabled);

        // 排序二选一：FAQ 按 updated_at（默认 DESC）、文档按 chunk_index（默认 ASC）；
        // sortOrder 只认 "asc"/"desc" 字面量，其余值走默认方向（照抄 Go）
        if (KNOWLEDGE_TYPE_FAQ.equals(knowledgeType)) {
            if ("asc".equals(sortOrder)) {
                data.orderByAsc("updated_at");
            } else {
                data.orderByDesc("updated_at");
            }
        } else {
            if ("desc".equals(sortOrder)) {
                data.orderByDesc("chunk_index");
            } else {
                data.orderByAsc("chunk_index");
            }
        }
        data.last("LIMIT " + limit + " OFFSET " + offset);
        return new ChunkPage(chunkMapper.selectList(data), total);
    }

    /**
     * 对照 Go {@code ListChunkByParentID}（L296-308）：tenant + parent_chunk_id，
     * 软删行不可见。空结果是空列表（GORM Find 非 nil 语义）。
     */
    public List<Chunk> listChunkByParentId(long tenantId, String parentId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getParentChunkId, parentId)
                .isNull(Chunk::getDeletedAt));
    }

    /**
     * 对照 Go {@code ListChunksByParentIDs}（L310-325）：tenant + parent_chunk_id IN，
     * 软删行不可见；空 parentIDs 返回空列表（Go 的 nil 展开语义）。
     */
    public List<Chunk> listChunksByParentIDs(long tenantId, List<String> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return List.of();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .in(Chunk::getParentChunkId, parentIds)
                .isNull(Chunk::getDeletedAt));
    }

    /**
     * 对照 Go {@code ListChunksByKnowledgeID}（L149-161）：**text-only**（chunk_type='text'）
     * + chunk_index ASC，软删行不可见。摘要/索引管线取「文档正文」都走这里；
     * summary / parent_text / image 类子块走 {@link #listChunksByKnowledgeIDAndTypes}。
     */
    public List<Chunk> listChunksByKnowledgeID(long tenantId, String knowledgeId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getKnowledgeId, knowledgeId)
                .eq(Chunk::getChunkType, "text")
                .isNull(Chunk::getDeletedAt)
                .orderByAsc(Chunk::getChunkIndex));
    }

    /**
     * 对照 Go {@code ListChunksByKnowledgeIDAndTypes}（L163-181）：chunk_type IN + ASC；
     * 空 chunkTypes 返回空列表（Go 的 nil 语义）。
     */
    public List<Chunk> listChunksByKnowledgeIDAndTypes(
            long tenantId, String knowledgeId, List<String> chunkTypes) {
        if (chunkTypes == null || chunkTypes.isEmpty()) {
            return List.of();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getKnowledgeId, knowledgeId)
                .in(Chunk::getChunkType, chunkTypes)
                .isNull(Chunk::getDeletedAt)
                .orderByAsc(Chunk::getChunkIndex));
    }

    // ── 写 ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code UpdateChunk}（L330）：{@code Omit("SeqID").Save(chunk)} =
     * 全字段 UPDATE（零值也写）+ updated_at 刷成 now 并回写实体；软删行不可见
     * （GORM UpdateClauses 给 UPDATE 也加 {@code deleted_at IS NULL}）。
     *
     * <p>已知差异：GORM Save 影响行数为 0 时回退 INSERT——service 层总是先查后存，
     * HTTP 面不可达，未复刻（见类 Javadoc）。</p>
     */
    public void updateChunk(Chunk chunk) {
        // GORM autoUpdateTime：Save 把 updated_at 覆盖为 now 且回写 struct
        chunk.setUpdatedAt(java.time.OffsetDateTime.now());
        chunkMapper.updateAllFieldsExceptSeqId(chunk);
    }

    /** 对照 Go {@code CreateChunkRevision}（L334-336）：纯 INSERT，ID/时间由调用方赋值。 */
    public void createChunkRevision(ChunkRevision revision) {
        revisionMapper.insert(revision);
    }

    /**
     * 对照 Go {@code SaveChunkRevision}（L338-362）：事务内先做
     * {@code content_revision = expectedRevision} 乐观锁 UPDATE（map 语义——8 列无条件写，
     * content/source_content 过 {@link CleanInvalidUtf8#clean}），影响行数 != 1 抛
     * {@link ChunkRevisionConflictException}（事务回滚，revision 不落库）；否则插入 revision 快照。
     *
     * <p>WHERE 带 {@code deleted_at IS NULL}（GORM UpdateClauses，与 Go 同款）。
     * metadata 是 json 列——wrapper 的 {@code set()} 不带 typeHandler，
     * 必须用 3 参形式显式挂（约定 §9）。</p>
     */
    public void saveChunkRevision(Chunk chunk, ChunkRevision revision, int expectedRevision) {
        tx.inTransaction(() -> {
            UpdateWrapper<Chunk> w = new UpdateWrapper<Chunk>()
                    .eq("id", chunk.getId())
                    .eq("tenant_id", chunk.getTenantId())
                    .eq("content_revision", expectedRevision)
                    .isNull("deleted_at")
                    .set("content", CleanInvalidUtf8.clean(chunk.getContent()))
                    // Go 的 SourceContent 是非指针 string，零值 ""；Java null 等价归一
                    .set("source_content", CleanInvalidUtf8.clean(
                            chunk.getSourceContent() == null ? "" : chunk.getSourceContent()))
                    .set("content_revision", chunk.getContentRevision())
                    .set("is_enabled", chunk.isIsEnabled())
                    .set("metadata", chunk.getMetadata(), "typeHandler=" + PG_JSON)
                    .set("index_status", chunk.getIndexStatus())
                    .set("last_editor_id", chunk.getLastEditorId())
                    .set("updated_at", chunk.getUpdatedAt());
            int rows = chunkMapper.update(null, w);
            if (rows != 1) {
                // 对应 Go 的 return ErrChunkRevisionConflict——事务回滚
                throw new ChunkRevisionConflictException();
            }
            revisionMapper.insert(revision);
            return null;
        });
    }

    // ── 修订历史 ────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code ListChunkRevisions}（L364-372）：{@code revision DESC}；
     * chunk_revisions 无软删列。空结果是空列表（GORM Find 非 nil 语义）。
     */
    public List<ChunkRevision> listChunkRevisions(long tenantId, String chunkId) {
        return revisionMapper.selectList(new LambdaQueryWrapper<ChunkRevision>()
                .eq(ChunkRevision::getTenantId, tenantId)
                .eq(ChunkRevision::getChunkId, chunkId)
                .orderByDesc(ChunkRevision::getRevision));
    }

    /**
     * 对照 Go {@code GetChunkRevision}（L374-382）：First 找不到时 Go 把
     * {@code gorm.ErrRecordNotFound} 原样上抛（service 层继续透传成 404 "record not found"）。
     * Java 侧返回 <b>null</b>，404 文案与映射由 service/controller 层决定（约定见
     * MessageRepository.getMessageByRequestId 的同款先例）。
     */
    public ChunkRevision getChunkRevision(long tenantId, String chunkId, int revision) {
        return revisionMapper.selectOne(new LambdaQueryWrapper<ChunkRevision>()
                .eq(ChunkRevision::getTenantId, tenantId)
                .eq(ChunkRevision::getChunkId, chunkId)
                .eq(ChunkRevision::getRevision, revision)
                .last("LIMIT 1"));
    }

    // ── 删除（全部是软删，见类 Javadoc 第 3 条）────────────────────────────

    /** 对照 Go {@code DeleteChunk}（L523-525）：tenant + id 软删；不存在时静默 no-op。 */
    public void deleteChunk(long tenantId, String id) {
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .eq("tenant_id", tenantId)
                .eq("id", id)
                .isNull("deleted_at")
                .set("deleted_at", java.time.OffsetDateTime.now()));
    }

    /**
     * 对照 Go {@code DeleteChunks}（L529-544）：tenant + id IN，按 5000 一批
     * （MySQL Error 1390 防御，照抄）；空列表短路（Go 同款）。
     */
    public void deleteChunks(long tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (int i = 0; i < ids.size(); i += DELETE_BATCH_SIZE) {
            int end = Math.min(i + DELETE_BATCH_SIZE, ids.size());
            chunkMapper.update(null, new UpdateWrapper<Chunk>()
                    .eq("tenant_id", tenantId)
                    .in("id", ids.subList(i, end))
                    .isNull("deleted_at")
                    .set("deleted_at", java.time.OffsetDateTime.now()));
        }
    }

    /**
     * 对照 Go {@code DeleteChunksByTagID}（chunk.go L583-628，W5a 标签 CRUD 用）：
     * pluck 该 tag 下全部 chunk id → 排除 excluded → 按 1000 一批软删。
     * 返回"计划删除"的 id 清单（对照 Go 的索引清理入参，含删除失败时的已删前缀）。
     */
    public List<String> deleteChunksByTagId(long tenantId, String kbId, String tagId, List<String> excludeIds) {
        List<String> allIds = chunkMapper.selectIdsByTag(tenantId, kbId, tagId);
        java.util.Set<String> excludeSet = new java.util.HashSet<>(excludeIds == null ? List.of() : excludeIds);
        List<String> toDelete = new java.util.ArrayList<>(allIds.size());
        for (String id : allIds) {
            if (!excludeSet.contains(id)) {
                toDelete.add(id);
            }
        }
        if (toDelete.isEmpty()) {
            return List.of();
        }
        final int batchSize = 1000;
        for (int i = 0; i < toDelete.size(); i += batchSize) {
            int end = Math.min(i + batchSize, toDelete.size());
            chunkMapper.update(null, new UpdateWrapper<Chunk>()
                    .eq("tenant_id", tenantId)
                    .in("id", toDelete.subList(i, end))
                    .isNull("deleted_at")
                    .set("deleted_at", java.time.OffsetDateTime.now()));
        }
        return toDelete;
    }

    /** 对照 Go {@code DeleteChunksByKnowledgeID}（L547-551）：tenant + knowledge 软删。 */
    public void deleteChunksByKnowledgeId(long tenantId, String knowledgeId) {
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .eq("tenant_id", tenantId)
                .eq("knowledge_id", knowledgeId)
                .isNull("deleted_at")
                .set("deleted_at", java.time.OffsetDateTime.now()));
    }

    /**
     * 对照 Go {@code DeleteByKnowledgeList}（L568-572）：tenant + knowledge IN 软删。
     * Go 没有判空——空列表展开成 {@code IN (NULL)} 删不到任何行；MyBatis-Plus 的
     * {@code IN ()} 是语法错误，显式短路（净效果相同）。
     */
    public void deleteByKnowledgeList(long tenantId, List<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return;
        }
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .eq("tenant_id", tenantId)
                .in("knowledge_id", knowledgeIds)
                .isNull("deleted_at")
                .set("deleted_at", java.time.OffsetDateTime.now()));
    }

    // ── FAQ 专用（波 2 第四批，对照 Go chunk.go 的 FAQ 方法子集） ─────────────

    /**
     * 对照 Go {@code GetChunkBySeqID}（L97-106）：tenant + seq_id，软删行不可见。
     * Go 的 ErrChunkNotFound 在 FAQ service 层统一翻成 404 "FAQ条目不存在"，
     * 这里返回 null 由调用方决定文案（ChunkRepository 的既有先例）。
     */
    public Chunk getChunkBySeqId(long tenantId, long seqId) {
        return chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getSeqId, seqId)
                .isNull(Chunk::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 对照 Go {@code ListChunksBySeqID}（L134-147）：tenant + seq_id IN；空入参 → 空列表。 */
    public List<Chunk> listChunksBySeqId(long tenantId, List<Long> seqIds) {
        if (seqIds == null || seqIds.isEmpty()) {
            return List.of();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .in(Chunk::getSeqId, seqIds)
                .isNull(Chunk::getDeletedAt));
    }

    /**
     * 对照 Go {@code ListAllFAQChunksByKnowledgeID}（L665-701）：只取
     * {@code id, content_hash} 投影（replace 模式 hash 比对用），chunk_type='faq'。
     * Go 按 1000 一批 offset 分页；Java 同款循环。
     */
    public List<Chunk> listAllFAQChunksByKnowledgeId(long tenantId, String knowledgeId) {
        List<Chunk> all = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<Chunk> batch = chunkMapper.selectList(new QueryWrapper<Chunk>()
                    .select("id", "content_hash")
                    .eq("tenant_id", tenantId)
                    .eq("knowledge_id", knowledgeId)
                    .eq("chunk_type", "faq")
                    .last("LIMIT 1000 OFFSET " + offset));
            if (batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < 1000) {
                break;
            }
            offset += 1000;
        }
        return all;
    }

    /**
     * 对照 Go {@code ListAllFAQChunksWithMetadataByKnowledgeBaseID}（L706-743）：
     * {@code id, metadata} 投影 + chunk_type='faq' + <b>status=2（indexed）</b>
     * （append 校验/合并只看已索引行）。
     */
    public List<Chunk> listAllFAQChunksWithMetadataByKnowledgeBaseId(long tenantId, String kbId) {
        List<Chunk> all = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<Chunk> batch = chunkMapper.selectList(new QueryWrapper<Chunk>()
                    .select("id", "metadata")
                    .eq("tenant_id", tenantId)
                    .eq("knowledge_base_id", kbId)
                    .eq("chunk_type", "faq")
                    .eq("status", STATUS_INDEXED)
                    .last("LIMIT 1000 OFFSET " + offset));
            if (batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < 1000) {
                break;
            }
            offset += 1000;
        }
        return all;
    }

    /**
     * 对照 Go {@code FindFAQChunkWithDuplicateQuestion}（L748-813）：找单个
     * standard_question 或 similar_questions 与给定问题集重叠的 FAQ chunk
     * （status ∈ {0,1,2} 全算——stored 的兄弟请求也算，软删行不可见）。
     *
     * <p>PG 走 Go 的原版 SQL；非 PG（H2 测试）无 {@code ->>} / json_each，
     * 退化为「取候选行后在 JVM 内按同一集合语义过滤」——功能等价、数据量是
     * 测试级；PG（真 e2e/A-B）不受影响。LIMIT 1 无 ORDER BY（照抄 Go，
     * 多行重叠时取哪一行本就不确定）。</p>
     */
    public Chunk findFAQChunkWithDuplicateQuestion(
            long tenantId, String kbId, String excludeChunkId, List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            return null;
        }
        if (postgres) {
            return chunkMapper.findFaqDuplicateChunk(tenantId, kbId, excludeChunkId, questions);
        }
        // 非 PG：同 WHERE 的 JVM 版（questions 的集合语义逐条对照）
        List<Chunk> candidates = chunkMapper.selectList(new QueryWrapper<Chunk>()
                .select("id", "metadata")
                .eq("tenant_id", tenantId)
                .eq("knowledge_base_id", kbId)
                .eq("chunk_type", "faq")
                .in("status", STATUS_DEFAULT, 1, STATUS_INDEXED)
                .ne("id", excludeChunkId));
        java.util.Set<String> wanted = new java.util.HashSet<>(questions);
        for (Chunk c : candidates) {
            FaqChunkMetadata meta = parseFaqMetadata(c.getMetadata());
            if (meta == null) {
                continue;
            }
            if (meta.standardQuestion != null && wanted.contains(meta.standardQuestion)) {
                return c;
            }
            if (meta.similarQuestions != null) {
                for (String q : meta.similarQuestions) {
                    if (wanted.contains(q)) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 对照 Go {@code ListAllFAQChunksForExport}（L817-855）：
     * {@code id, metadata, tag_id, is_enabled, flags} + status=2 + {@code created_at ASC}。
     * 注意投影不含 seq_id——导出面 FAQExportEntry 的 {@code id} 因此恒 0（Go 实录）。
     */
    public List<Chunk> listAllFAQChunksForExport(long tenantId, String knowledgeId) {
        List<Chunk> all = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            List<Chunk> batch = chunkMapper.selectList(new QueryWrapper<Chunk>()
                    .select("id", "metadata", "tag_id", "is_enabled", "flags")
                    .eq("tenant_id", tenantId)
                    .eq("knowledge_id", knowledgeId)
                    .eq("chunk_type", "faq")
                    .eq("status", STATUS_INDEXED)
                    .orderByAsc("created_at")
                    .last("LIMIT 1000 OFFSET " + offset));
            if (batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < 1000) {
                break;
            }
            offset += 1000;
        }
        return all;
    }

    /**
     * 对照 Go {@code UpdateChunkFlagsBatch}（L861-940）：
     * {@code flags = (flags | set) & ~clear} + updated_at=NOW()，单条 UPDATE
     * （所有行共享同一时刻，与列表页排序有关——别改成循环）。
     */
    public void updateChunkFlagsBatch(long tenantId, String kbId,
                                      Map<String, Integer> setFlags, Map<String, Integer> clearFlags) {
        if ((setFlags == null || setFlags.isEmpty()) && (clearFlags == null || clearFlags.isEmpty())) {
            return;
        }
        LinkedHashSet<String> allIds = new LinkedHashSet<>();
        if (setFlags != null) {
            allIds.addAll(setFlags.keySet());
        }
        if (clearFlags != null) {
            allIds.addAll(clearFlags.keySet());
        }
        String setExpr = buildFlagCase(setFlags);
        String clearExpr = buildFlagCase(clearFlags);
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .setSql("flags = " + flagsBitExpr("(flags | (" + setExpr + ")) & ~(" + clearExpr + ")",
                        "BITAND(BITOR(flags, " + setExpr + "), BITNOT(" + clearExpr + "))"))
                .setSql("updated_at = NOW()")
                .eq("tenant_id", tenantId)
                .eq("knowledge_base_id", kbId)
                .isNull("deleted_at")
                .in("id", allIds));
    }

    /**
     * FAQ metadata 列（json 投影）→ {@link FaqChunkMetadata}；解析失败/空 → null
     * （Go 的 FAQMetadata() 在 len==0 时返回 (nil,nil)，解析错误上层各按文案处理）。
     */
    public static FaqChunkMetadata parseFaqMetadata(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isEmpty()) {
            return null;
        }
        return FaqChunkMetadata.fromJson(node);
    }

    /** 位运算表达式的方言切换：PG 用 | &~，H2 2.x 无位运算符 → BITOR/BITAND/BITNOT（语义等价）。 */
    private String flagsBitExpr(String postgresExpr, String h2Expr) {
        return postgres ? postgresExpr : h2Expr;
    }

    private static String buildFlagCase(Map<String, Integer> flags) {
        if (flags == null || flags.isEmpty()) {
            return "0";
        }
        StringBuilder sb = new StringBuilder("CASE");
        for (Map.Entry<String, Integer> e : flags.entrySet()) {
            sb.append(" WHEN id = '").append(e.getKey().replace("'", "''"))
                    .append("' THEN ").append(e.getValue());
        }
        sb.append(" ELSE 0 END");
        return sb.toString();
    }

    /**
     * 对照 Go {@code UpdateChunkFieldsByTagID}（L945-1017）：把某 tag 下（可排除若干 id）
     * 的全部 FAQ chunk 更新 is_enabled / flags / tag_id，返回受影响 id（先 Pluck 后更新）。
     * flags 的位运算用 SQL 原样表达式（Go 的 gorm Raw 同款）。
     */
    public List<String> updateChunkFieldsByTagId(long tenantId, String kbId, String tagId,
                                                 Boolean isEnabled, int setFlags, int clearFlags,
                                                 String newTagId, List<String> excludeIds) {
        if (isEnabled == null && setFlags == 0 && clearFlags == 0 && newTagId == null) {
            return List.of();
        }
        QueryWrapper<Chunk> selection = new QueryWrapper<Chunk>()
                .select("id")
                .eq("tenant_id", tenantId)
                .eq("knowledge_base_id", kbId)
                .eq("chunk_type", "faq");
        if (tagId != null && !tagId.isEmpty()) {
            selection.eq("tag_id", tagId);
        }
        if (excludeIds != null && !excludeIds.isEmpty()) {
            selection.notIn("id", excludeIds);
        }
        List<String> affectedIds = chunkMapper.selectList(selection)
                .stream().map(Chunk::getId).toList();

        UpdateWrapper<Chunk> update = new UpdateWrapper<Chunk>()
                .eq("tenant_id", tenantId)
                .eq("knowledge_base_id", kbId)
                .eq("chunk_type", "faq");
        if (tagId != null && !tagId.isEmpty()) {
            update.eq("tag_id", tagId);
        }
        if (excludeIds != null && !excludeIds.isEmpty()) {
            update.notIn("id", excludeIds);
        }
        update.set("updated_at", java.time.OffsetDateTime.now());
        if (isEnabled != null) {
            update.set("is_enabled", isEnabled);
        }
        if (newTagId != null) {
            update.set("tag_id", newTagId);
        }
        if (setFlags != 0 || clearFlags != 0) {
            String expr = "flags";
            if (setFlags != 0) {
                expr = postgres ? expr + " | " + setFlags
                        : "BITOR(" + expr + ", " + setFlags + ")";
            }
            if (clearFlags != 0) {
                expr = postgres ? expr + " & ~" + clearFlags
                        : "BITAND(" + expr + ", BITNOT(" + clearFlags + "))";
            }
            update.setSql("flags = " + expr);
        }
        chunkMapper.update(null, update);
        return affectedIds;
    }

    /**
     * 对照 Go {@code UpdateChunks}（L417-520）：CASE 批量更新 content / is_enabled /
     * tag_id / flags / status + updated_at=NOW()（一条语句一个时刻，列表排序敏感）。
     * metadata / content_hash 不在此更新（Go 注释明确，需要时用单条 Save）。
     */
    public void updateChunks(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        chunkMapper.updateChunksCase(chunks);
    }

    /**
     * 对照 Go {@code SaveChunks}（L385-397）：事务内逐条全字段 UPDATE
     * （GORM Save = updateAllFieldsExceptSeqId，metadata/content_hash 持久化）。
     */
    public void saveChunks(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        tx.inTransaction(() -> {
            for (Chunk c : chunks) {
                chunkMapper.updateAllFieldsExceptSeqId(c);
            }
            return null;
        });
    }

    /**
     * 对照 Go {@code DeleteUnindexedChunks}（L642-661）：先查后删
     * knowledge 下 status=stored(1) 的 chunk（软删），返回被删行（索引清理用）。
     */
    public List<Chunk> deleteUnindexedChunks(long tenantId, String knowledgeId) {
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getKnowledgeId, knowledgeId)
                .eq(Chunk::getStatus, 1)
                .isNull(Chunk::getDeletedAt));
        if (!chunks.isEmpty()) {
            chunkMapper.update(null, new UpdateWrapper<Chunk>()
                    .eq("tenant_id", tenantId)
                    .eq("knowledge_id", knowledgeId)
                    .eq("status", 1)
                    .isNull("deleted_at")
                    .set("deleted_at", java.time.OffsetDateTime.now()));
        }
        return chunks;
    }

    // ── 私有 ────────────────────────────────────────────────────────────────

    /**
     * ListPaged 的公共过滤（Go 的 baseFilter 闭包）。每条 SQL（count/data）各自调一次、
     * 生成独立 wrapper——与 Go 两份 {@code baseFilter(...)} 调用同形。
     */
    private QueryWrapper<Chunk> pagedFilter(long tenantId, String knowledgeId, List<String> chunkTypes,
            List<String> tagIds, String keyword, String searchField, String knowledgeType, Boolean isEnabled) {
        QueryWrapper<Chunk> w = new QueryWrapper<Chunk>()
                .eq("tenant_id", tenantId)
                .eq("knowledge_id", knowledgeId)
                .isNull("deleted_at");
        if (chunkTypes == null || chunkTypes.isEmpty()) {
            // Go：空切片 → IN (NULL) → 匹配零行
            w.apply("1 = 0");
        } else {
            w.in("chunk_type", chunkTypes);
        }
        w.in("status", STATUS_INDEXED, STATUS_DEFAULT);
        if (tagIds != null && !tagIds.isEmpty()) {
            w.in("tag_id", tagIds);
        }
        if (isEnabled != null) {
            w.eq("is_enabled", isEnabled);
        }
        if (!keyword.isEmpty()) {
            String like = "%" + keyword + "%";
            if (!KNOWLEDGE_TYPE_FAQ.equals(knowledgeType)) {
                // 文档：只搜 content
                w.apply("content LIKE {0}", like);
                return w;
            }
            // FAQ：按 searchField 切 JSON 路径（PG ILIKE / 非 PG 是 MySQL 语法）
            switch (searchField == null ? "" : searchField) {
                case "standard_question" -> w.apply(postgres
                        ? "metadata->>'standard_question' ILIKE {0}"
                        : "metadata->>'$.standard_question' LIKE {0}", like);
                case "similar_questions" -> w.apply(postgres
                        ? "(metadata->'similar_questions')::text ILIKE {0}"
                        : "JSON_EXTRACT(metadata, '$.similar_questions') LIKE {0}", like);
                case "answers" -> w.apply(postgres
                        ? "(metadata->'answers')::text ILIKE {0}"
                        : "JSON_EXTRACT(metadata, '$.answers') LIKE {0}", like);
                default -> w.apply(postgres
                        ? "(content ILIKE {0} OR metadata::text ILIKE {0})"
                        : "(content LIKE {0} OR CAST(metadata AS CHAR) LIKE {0})", like);
            }
        }
        return w;
    }

    /**
     * Go 的 {@code strings.TrimSpace}（unicode.IsSpace 全集）。Java 的 {@code strip()}
     * 不含 U+0085/U+00A0（非断行空格），与 Go 的 White_Space 集不同，故显式复刻。
     * （public：knowledge.service 的摘要管线（getSummary/sampleLongContent）同样需要
     * Go 精确裁空语义，跨包复用同一实现。）
     */
    public static String goTrimSpace(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isGoSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /** Go unicode.IsSpace 的 BMP 全集（White_Space property）。 */
    private static boolean isGoSpace(char c) {
        switch (c) {
            case '\t': case '\n': case '\u000B': case '\f': case '\r':
            case ' ': case '\u0085': case '\u00A0': case '\u1680':
            case '\u2028': case '\u2029': case '\u202F': case '\u205F': case '\u3000':
                return true;
            default:
                return c >= '\u2000' && c <= '\u200A';
        }
    }
}
