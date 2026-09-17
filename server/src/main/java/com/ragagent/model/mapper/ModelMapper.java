package com.ragagent.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.model.domain.Model;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelMapper extends BaseMapper<Model> {
}
