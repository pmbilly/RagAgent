package com.ragagent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.auth.domain.TenantMember;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMemberMapper extends BaseMapper<TenantMember> {
}
