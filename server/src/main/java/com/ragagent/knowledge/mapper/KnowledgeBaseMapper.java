package com.ragagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.knowledge.domain.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** {@code knowledge_bases} 表的 MyBatis-Plus mapper。 */
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
}
