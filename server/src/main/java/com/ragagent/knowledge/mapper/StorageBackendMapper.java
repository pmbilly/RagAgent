package com.ragagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.knowledge.domain.StorageBackend;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** {@code storage_backends} 表的 MyBatis-Plus mapper。 */
public interface StorageBackendMapper extends BaseMapper<StorageBackend> {
}
