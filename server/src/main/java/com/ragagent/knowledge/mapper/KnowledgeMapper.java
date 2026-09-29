package com.ragagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.knowledge.domain.Knowledge;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** {@code knowledges} 表的 MyBatis-Plus mapper。 */
public interface KnowledgeMapper extends BaseMapper<Knowledge> {
}
