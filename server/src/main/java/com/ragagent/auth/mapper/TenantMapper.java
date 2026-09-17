package com.ragagent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.auth.domain.Tenant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
