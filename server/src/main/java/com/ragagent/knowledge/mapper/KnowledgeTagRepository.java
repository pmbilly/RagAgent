package com.ragagent.knowledge.mapper;

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
     * seq_id 写回实体（Go 的 DB 序列不回填内存对象——Go 的 tag.SeqID 落库后仍是 0！
     * 但 FindOrCreateTagByName 之后 FAQ 只用 tag.ID，seq_id 不可见，无 HTTP 差异）。
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
        } else {
            tag.setSeqId(tagMapper.maxSeqId() + 1);
            tagMapper.insertTag(tag);
        }
        return tag;
    }
}
