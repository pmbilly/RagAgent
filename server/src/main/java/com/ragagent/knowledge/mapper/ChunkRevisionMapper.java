package com.ragagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.knowledge.domain.ChunkRevision;
import org.apache.ibatis.annotations.Mapper;

/**
 * chunk_revisions 表（迁移 000078）的 MyBatis-Plus 接口，对照 Go 仓储的
 * {@code CreateChunkRevision} / {@code ListChunkRevisions} / {@code GetChunkRevision}。
 *
 * <p>读/写统一走 {@link ChunkRepository} 门面（GORM 隐式行为在那里收口）；
 * 本接口只承载 BaseMapper 的通用能力。无软删列、无自动时间戳——全部列由调用方显式赋值
 * （对照 {@code ChunkRevision} 实体 Javadoc 的 GORM 清单）。</p>
 */
@Mapper
public interface ChunkRevisionMapper extends BaseMapper<ChunkRevision> {
}
