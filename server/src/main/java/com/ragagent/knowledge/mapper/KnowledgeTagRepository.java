package com.ragagent.knowledge.mapper;

import java.util.List;
import java.util.UUID;

import com.ragagent.knowledge.domain.KnowledgeTag;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;

/**
 * knowledge_tags 的写入侧仓储（对照 Go repository/tag.go + tagService.CreateTag 的
 * 落库净效果），读路径在 {@link KnowledgeTagMapper}。
 *
 * <p><b>seq_id 的方言分裂</b>：Go 的 KnowledgeTag 带 {@code autoIncrement} tag——
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
        this.postgres = detectPostgres(dataSource);
    }

    private static boolean detectPostgres(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(java.util.Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 对照 tagService.CreateTag 的净效果（L162-172）：name 已 TrimSpace、
     * "未分类" sort_order=-1 的决策在 service 层；这里只负责 uuid、now、seq_id 分配。
     *
     * <p><b>seq_id 回填（W5a golden 纠正了本类旧注释）</b>：Go 的 GORM 对带
     * {@code autoIncrement} 的列在 PG 上走 RETURNING 回填——CreateTag 的 HTTP
     * 响应 {@code seq_id} 是**真值**（w5a-tag-create golden 钉住），不是 0。
     * PG 侧插入后按 id 回读序列值；H2 走 max+1（同 Go 的 sqlite BeforeCreate）。</p>
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

    // ── W5a：KB 标签 CRUD 的读/改/删（对照 Go repository/tag.go） ────────────

    /** 对照 {@code GetByID}（tag.go L33-42）。缺失返回 null（调用方区分 404/500 文案）。 */
    public KnowledgeTag getById(long tenantId, String id) {
        return tagMapper.selectByTenantAndId(tenantId, id);
    }

    /** 对照 {@code GetBySeqID}（tag.go L58-67）。 */
    public KnowledgeTag getBySeqId(long tenantId, long seqId) {
        return tagMapper.selectByTenantAndSeqId(tenantId, seqId);
    }

    /** 对照 {@code GetByName}（tag.go L83-92）。 */
    public KnowledgeTag getByName(long tenantId, String kbId, String name) {
        return tagMapper.selectByTenantKbAndName(tenantId, kbId, name);
    }

    /** 对照 {@code Update} = GORM Save：整行覆写。 */
    public void update(KnowledgeTag tag) {
        tagMapper.updateTag(tag);
    }

    /** 对照 {@code Delete}（tag.go L139-143）：硬删（struct 无 DeletedAt）。 */
    public void delete(long tenantId, String id) {
        tagMapper.deleteByTenantAndId(tenantId, id);
    }

    /** 分页参数的 Go 归一结果 + 行集 + 总数（对照 ListByKB 的返回三元组）。 */
    public record TagPage(List<KnowledgeTag> items, long total, int page, int pageSize) {}

    /**
     * 对照 {@code ListByKB}（tag.go L94-137）：keyword 转义（Go escapeLikeKeyword：
     * \\ → \\\\、% → \%、_ → \_）+ 前后 % 包裹；分页在 Java 侧先按 Go
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

    /** 对照 {@code CountReferences}（tag.go L146-173）：{knowledgeCount, chunkCount}。 */
    public long[] countReferences(long tenantId, String kbId, String tagId) {
        return new long[]{
                tagMapper.countKnowledgeRefs(tenantId, kbId, tagId),
                tagMapper.countChunkRefs(tenantId, kbId, tagId)
        };
    }

    /**
     * 对照 {@code BatchCountReferences}（tag.go L175-227）：两条分组 SQL；
     * 未命中的 tagID 保留零值条目（Go 先给全量初始化零值）。
     */
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
