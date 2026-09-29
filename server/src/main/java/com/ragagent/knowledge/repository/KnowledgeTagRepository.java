package com.ragagent.knowledge.repository;

import java.util.List;
import java.util.UUID;

import com.ragagent.knowledge.domain.KnowledgeTag;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import com.ragagent.common.jdbc.DatabaseDialects;
import com.ragagent.knowledge.mapper.KnowledgeTagMapper;

/**
 * knowledge_tags 的写入侧仓储，读路径在 {@link KnowledgeTagMapper}。
 * PG/MySQL 由 DB 序列供给，SQLite 走 BeforeCreate 的 {@code MAX(seq_id)+1}。
 * Java 侧 PG 用 {@code NEXTVAL}（insertTagPg）、H2 测试库用 max+1（构造期问一次
 * DatabaseProductName，与 {@link ChunkRepository} 的方言探测同款）。</p>
 */
@Component
public class KnowledgeTagRepository {

    private final KnowledgeTagMapper tagMapper;
    private final boolean postgres;

    public KnowledgeTagRepository(KnowledgeTagMapper tagMapper, DataSource dataSource) {
        this.tagMapper = tagMapper;
        this.postgres = DatabaseDialects.isPostgres(dataSource);
    }

    /**
     * "未分类" sort_order=-1 的决策在 service 层；这里只负责 uuid、now、seq_id 分配。
     * {@code autoIncrement} 的列在 PG 上走 RETURNING 回填——CreateTag 的 HTTP
     * 响应 {@code seq_id} 是**真值**（w5a-tag-create 契约样例锁定），不是 0。
     */
    public KnowledgeTag createTag(long tenantId, String kbId, String name, String color, int sortOrder) {
        KnowledgeTag tag = new KnowledgeTag();
        tag.setId(UUID.randomUUID().toString());
        tag.setTenantId(tenantId);
        tag.setKnowledgeBaseId(kbId);
        tag.setName(name);
        tag.setColor(color);
        tag.setSortOrder(sortOrder);
        OffsetDateTime now = OffsetDateTime.now();
        tag.setCreatedAt(now);
        tag.setUpdatedAt(now);
        if (postgres) {
            tagMapper.insertTagPg(tag);
            tag.setSeqId(tagMapper.selectSeqIdById(tag.getId()));
        } else {
            tag.setSeqId(tagMapper.maxSeqId() + 1);
            tagMapper.insertTag(tag);
        }
        return tag;
    }

    // ── KB 标签 CRUD 的读/改/删 ────────────────

    public KnowledgeTag getById(long tenantId, String id) {
        return tagMapper.selectByTenantAndId(tenantId, id);
    }

    public KnowledgeTag getBySeqId(long tenantId, long seqId) {
        return tagMapper.selectByTenantAndSeqId(tenantId, seqId);
    }

    public KnowledgeTag getByName(long tenantId, String kbId, String name) {
        return tagMapper.selectByTenantKbAndName(tenantId, kbId, name);
    }

    public void update(KnowledgeTag tag) {
        tagMapper.updateTag(tag);
    }

    public void delete(long tenantId, String id) {
        tagMapper.deleteByTenantAndId(tenantId, id);
    }

    /**
     * （knowledge / repository/knowledge）：
     * 携带任一指定标签的文档 id（DISTINCT）。标签删除时用它列出待清理的文档。
     */
    public List<String> listKnowledgeIdsByTagIds(long tenantId, String kbId, List<String> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        return tagMapper.selectKnowledgeIdsByTagIds(tenantId, kbId, tagIds);
    }

    /** 标签分页结果（仓储内使用；对外形状见 {@code dto.TagPageResult}）。 */
    public record TagPage(List<KnowledgeTag> items, long total, int page, int pageSize) {}

    /**
     * Pagination.GetPage/GetPageSize 归一（page&lt;1→1、size&lt;1→20、&gt;1000→1000）。
     */
    public TagPage listByKb(long tenantId, String kbId, Integer page, Integer pageSize, String keyword) {
        int pageNo = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 1000);
        String escaped = keyword == null ? "" : keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        long total = tagMapper.countByKB(tenantId, kbId, escaped);
        int offset = (pageNo - 1) * size;
        List<KnowledgeTag> items = escaped.isEmpty()
                ? tagMapper.listByKB(tenantId, kbId, size, offset)
                : tagMapper.listByKBKeyword(tenantId, kbId, escaped, size, offset);
        return new TagPage(items, total, pageNo, size);
    }

    public long[] countReferences(long tenantId, String kbId, String tagId) {
        return new long[]{
                tagMapper.countKnowledgeRefs(tenantId, kbId, tagId),
                tagMapper.countChunkRefs(tenantId, kbId, tagId)
        };
    }

    public java.util.Map<String, long[]> batchCountReferences(long tenantId, String kbId, List<String> tagIds) {
        java.util.Map<String, long[]> result = new java.util.HashMap<>();
        for (String id : tagIds) {
            result.put(id, new long[]{0, 0});
        }
        for (KnowledgeTagMapper.TagCountRow row : tagMapper.batchCountKnowledgeRefs(tenantId, kbId, tagIds)) {
            result.get(row.getTagId())[0] = row.getCnt();
        }
        for (KnowledgeTagMapper.TagCountRow row : tagMapper.batchCountChunkRefs(tenantId, kbId, tagIds)) {
            result.get(row.getTagId())[1] = row.getCnt();
        }
        return result;
    }
}
