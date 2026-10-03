package com.ragagent.knowledge.repository;

import java.util.List;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.common.CleanInvalidUtf8;
import com.ragagent.common.knowledge.ChunkFacts;
import com.ragagent.common.knowledge.ChunkSearchGateway;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.ChunkRevision;
import org.springframework.stereotype.Component;
import com.ragagent.common.jdbc.DatabaseDialects;
import com.ragagent.knowledge.domain.ChunkNotFoundException;
import com.ragagent.knowledge.domain.ChunkRevisionConflictException;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.ChunkRevisionMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;

/**
 * chunk 仓储（文档与 FAQ 的 chunk 行读写，方法式门面）。数据访问契约如下，
 * 调用方（service/controller）依赖这些语义，不得在其上重新包装猜测：
 * <h2>写路径</h2>
 * <ul>
 *   <li><b>updateChunk = 全字段更新（seq_id 除外）</b>：零值也写（空串/false/0/null JSON
 *       → SQL NULL），updated_at 刷新为 now 并<b>回写到传入实体</b>；影响行数 0 时不回退
 *       插入（调用方总是先查后存）；</li>
 *   <li><b>saveChunkRevision = 乐观锁 UPDATE</b>：8 个键无条件写入（含 content 空串），
 *       updated_at 显式在 SET 中，不会被自动刷新覆盖；</li>
 *   <li><b>删除均为软删</b>（SET deleted_at = now），且 WHERE 一律带
 *       {@code deleted_at IS NULL}——重复删除第二次是 no-op，不刷新时间戳。</li>
 * </ul>
 * <h2>读路径</h2>
 * <ul>
 *   <li><b>软删行不可见</b>：全部 SELECT/UPDATE 附加 {@code deleted_at IS NULL}；</li>
 *   <li><b>列表查询永不返回 null</b>：查不到返回空列表（响应序列化为 {@code []}）；</li>
 *   <li><b>单行查询</b>：getChunkById/getChunkByIdOnly 查不到抛
 *       {@link ChunkNotFoundException}；getChunkRevision 查不到返回 null，由 service 层
 *       决定 404 文案；</li>
 *   <li><b>listPagedChunksByKnowledgeId</b>：{@code chunk_type IN (...)} +
 *       {@code status IN (2,0)}（stored=1 不在内）；排序 FAQ 按 updated_at（默认 DESC）、
 *       文档按 chunk_index（默认 ASC）；offset/limit 由调用方钳位后传入；</li>
 *   <li><b>FAQ 关键词搜索</b>：按 searchField 切四条 JSON 路径——PG 用
 *       {@code ->> + ILIKE}，非 PG 分支是 MySQL 语法（H2 跑不了，留待真 PG e2e）。</li>
 * </ul>
 * <h2>方言</h2>
 * <p>构造期按 {@code DatabaseProductName} 探测一次 PG 与否（FAQ 关键词搜索与 flags
 * 位运算的分支依据），与 VectorStoreService/MessageRepository 同款。</p>
 */
@Component
public class ChunkRepository implements ChunkSearchGateway {

    /** 三条 json 列共用的类型处理器（3 参 set 的 mapping 串）。 */
    private static final String PG_JSON = "com.ragagent.common.web.PgJsonTypeHandler";

    private static final int STATUS_DEFAULT = 0;
    private static final int STATUS_INDEXED = 2;
    private static final String KNOWLEDGE_TYPE_FAQ = "faq";

    private static final int DELETE_BATCH_SIZE = 5000;

    private final ChunkMapper chunkMapper;
    private final ChunkRevisionMapper revisionMapper;
    private final ChunkTxTemplate tx;
    /** 方言探测（构造期问一次）：ListPaged 的 FAQ 关键词搜索分支。 */
    private final boolean postgres;

    public ChunkRepository(ChunkMapper chunkMapper, ChunkRevisionMapper revisionMapper,
                           ChunkTxTemplate tx, DataSource dataSource) {
        this.chunkMapper = chunkMapper;
        this.revisionMapper = revisionMapper;
        this.tx = tx;
        this.postgres = DatabaseDialects.isPostgres(dataSource);
    }

    /** 分页结果：行与总数一并返回（调用方一次拿到两份）。 */
    public record ChunkPage(List<Chunk> items, long total) {
        public ChunkPage {
            items = items == null ? List.of() : items;
        }
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /**
     * tenant + id，软删行不可见；
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
     * **无租户过滤**（权限解析用）；
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
     * tenant + id IN，软删行不可见。
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
     * 跨域只读端口的实现（{@link ChunkSearchGateway}）：检索编排按 id 批量取 chunk 事实，
     * 语义与 {@link #listChunksById} 一致（租户内、软删不可见）。
     */
    @Override
    public List<ChunkFacts> findChunks(long tenantId, List<String> chunkIds) {
        List<ChunkFacts> out = new ArrayList<>();
        for (Chunk c : listChunksById(tenantId, chunkIds)) {
            out.add(new ChunkFacts(c.getId(), c.getKnowledgeId(), c.getContent(), c.getChunkType(),
                    c.getIndexStatus(), c.isIsEnabled(), c.getChunkIndex(), c.getStartAt(),
                    c.getEndAt(), c.getContentRevision(), c.getParentChunkId(), c.getPreChunkId(),
                    c.getNextChunkId(), c.getRelationChunks(), c.getMetadata()));
        }
        return out;
    }

    /**
     * 分页列出知识下的 chunk 并统计总数。offset/limit
     * 是调用方算好的值，本层不做钳位；chunkTypes 为空集时短路成零行条件
     * （不展开成 {@code IN (NULL)}）。
     * @param tagIds       非空时追加 {@code tag_id IN}
     * @param isEnabled    非空时追加 {@code is_enabled = ?}
     * @param keyword      先 trimSpace（空白集比 Java 默认多收 U+00A0/U+0085）；空串不加搜索条件。
     *                     knowledgeType
     *                     ≠ "faq" 只搜 {@code content LIKE}；"faq" 按 searchField 切四条
     *                     JSON 路径（PG 用 {@code ->> + ILIKE}，非 PG 分支是 MySQL 语法）
     * @param knowledgeType "faq" 决定排序键与搜索面
     */
    public ChunkPage listPagedChunksByKnowledgeId(
            long tenantId, String knowledgeId, int offset, int limit,
            List<String> chunkTypes, List<String> tagIds,
            String keyword, String searchField, String sortOrder,
            String knowledgeType, Boolean isEnabled) {
        String kw = trimSpace(keyword);

        long total = chunkMapper.selectCount(pagedFilter(
                tenantId, knowledgeId, chunkTypes, tagIds, kw, searchField, knowledgeType, isEnabled));

        QueryWrapper<Chunk> data = pagedFilter(
                tenantId, knowledgeId, chunkTypes, tagIds, kw, searchField, knowledgeType, isEnabled);

        // 排序二选一：FAQ 按 updated_at（默认 DESC）、文档按 chunk_index（默认 ASC）；
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
     * tenant + parent_chunk_id，
     * 软删行不可见。空结果是空列表。
     */
    public List<Chunk> listChunkByParentId(long tenantId, String parentId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getParentChunkId, parentId)
                .isNull(Chunk::getDeletedAt));
    }

    /**
     * tenant + parent_chunk_id IN，
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
     * **text-only**（chunk_type='text'）
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
     * chunk_type IN + ASC；
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
     * 全字段 UPDATE（seq_id 除外，零值也写）+ updated_at 刷成 now 并回写实体；
     * 软删行不可见。
     * <p>影响行数为 0 时不回退插入——调用方总是先查后存，HTTP 面不可达该分支。</p>
     */
    public void updateChunk(Chunk chunk) {
        // updated_at 覆盖为 now 且回写传入实体（updateChunk 契约）
        chunk.setUpdatedAt(OffsetDateTime.now());
        // FAQ 创建后 status 更新的全列 UPDATE 写 NULL → 违反 NOT NULL 约束。
        if (chunk.getSourceContent() == null) {
            chunk.setSourceContent("");
        }
        if (chunk.getContextHeader() == null) {
            chunk.setContextHeader("");
        }
        // getLastEditorId() 是 null→"" 归一的 getter：经 setter 无条件回写字段
        chunk.setLastEditorId(chunk.getLastEditorId());
        chunkMapper.updateAllFieldsExceptSeqId(chunk);
    }

    /** 纯 INSERT，ID/时间由调用方赋值。 */
    public void createChunkRevision(ChunkRevision revision) {
        revisionMapper.insert(revision);
    }

    /**
     * 事务内先做
     * {@code content_revision = expectedRevision} 乐观锁 UPDATE（map 语义——8 列无条件写，
     * content/source_content 过 {@link CleanInvalidUtf8#clean}），影响行数 != 1 抛
     * {@link ChunkRevisionConflictException}（事务回滚，revision 不落库）；否则插入 revision 快照。
     * <p>WHERE 带 {@code deleted_at IS NULL}。
     * metadata 是 json 列——wrapper 的 {@code set()} 不带 typeHandler，
     * 必须用 3 参形式显式挂（本仓约定）。</p>
     */
    public void saveChunkRevision(Chunk chunk, ChunkRevision revision, int expectedRevision) {
        tx.inTransaction(() -> {
            UpdateWrapper<Chunk> w = new UpdateWrapper<Chunk>()
                    .eq("id", chunk.getId())
                    .eq("tenant_id", chunk.getTenantId())
                    .eq("content_revision", expectedRevision)
                    .isNull("deleted_at")
                    .set("content", CleanInvalidUtf8.clean(chunk.getContent()))
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
                throw new ChunkRevisionConflictException();
            }
            revisionMapper.insert(revision);
            return null;
        });
    }

    // ── 修订历史 ────────────────────────────────────────────────────────────

    /**
     * {@code revision DESC}；
     * chunk_revisions 无软删列。空结果是空列表。
     */
    public List<ChunkRevision> listChunkRevisions(long tenantId, String chunkId) {
        return revisionMapper.selectList(new LambdaQueryWrapper<ChunkRevision>()
                .eq(ChunkRevision::getTenantId, tenantId)
                .eq(ChunkRevision::getChunkId, chunkId)
                .orderByDesc(ChunkRevision::getRevision));
    }

    /**
     * 查不到行时由 service 层透传成 404 "record not found"。
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

    /** tenant + id 软删；不存在时静默 no-op。 */
    public void deleteChunk(long tenantId, String id) {
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .eq("tenant_id", tenantId)
                .eq("id", id)
                .isNull("deleted_at")
                .set("deleted_at", OffsetDateTime.now()));
    }

    /**
     * tenant + id IN，按 5000 一批
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
                    .set("deleted_at", OffsetDateTime.now()));
        }
    }

    /**
     * * pluck 该 tag 下全部 chunk id → 排除 excluded → 按 1000 一批软删。
     * 返回"计划删除"的 id 清单。
     */
    public List<String> deleteChunksByTagId(long tenantId, String kbId, String tagId, List<String> excludeIds) {
        List<String> allIds = chunkMapper.selectIdsByTag(tenantId, kbId, tagId);
        Set<String> excludeSet = new HashSet<>(excludeIds == null ? List.of() : excludeIds);
        List<String> toDelete = new ArrayList<>(allIds.size());
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
                    .set("deleted_at", OffsetDateTime.now()));
        }
        return toDelete;
    }

    /** tenant + knowledge 软删。 */
    public void deleteChunksByKnowledgeId(long tenantId, String knowledgeId) {
        chunkMapper.update(null, new UpdateWrapper<Chunk>()
                .eq("tenant_id", tenantId)
                .eq("knowledge_id", knowledgeId)
                .isNull("deleted_at")
                .set("deleted_at", OffsetDateTime.now()));
    }

    /**
     * tenant + knowledge IN 软删。
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
                .set("deleted_at", OffsetDateTime.now()));
    }

    // ── 私有 ────────────────────────────────────────────────────────────────

    private QueryWrapper<Chunk> pagedFilter(long tenantId, String knowledgeId, List<String> chunkTypes,
            List<String> tagIds, String keyword, String searchField, String knowledgeType, Boolean isEnabled) {
        QueryWrapper<Chunk> w = new QueryWrapper<Chunk>()
                .eq("tenant_id", tenantId)
                .eq("knowledge_id", knowledgeId)
                .isNull("deleted_at");
        if (chunkTypes == null || chunkTypes.isEmpty()) {
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
     * （public：knowledge.service 的摘要管线（getSummary/sampleLongContent）同样需要
     */
    public static String trimSpace(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isWhitespace(s.charAt(start))) {
            start++;
        }
        while (end > start && isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isWhitespace(char c) {
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
