package com.ragagent.knowledge.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.common.CleanInvalidUtf8;
import com.ragagent.knowledge.domain.Chunk;
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
 * <p>FAQ 专用查询（ListAllFAQChunks* / FindFAQChunkWithDuplicateQuestion /
 * UpdateChunkFlagsBatch / FAQChunkDiff / ListFAQChunkStatusByIDs /
 * ListRecommendedFAQChunks / ListRecentDocumentChunksWithQuestions）、CreateChunks、
 * MoveChunksByKnowledgeID、CountChunksByKnowledgeBaseID、DeleteUnindexedChunks、
 * GetChunkBySeqID、ListChunksByIDOnly、ListChunksBySeqID、ListChunksByKnowledgeID(AndTypes)、
 * ListChunksByParentIDs、SaveChunks、UpdateChunks、DeleteChunksByTagID、
 * ListImageInfoByKnowledgeIDs、ListAllChunksByKnowledgeID。</p>
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
     */
    private static String goTrimSpace(String s) {
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
