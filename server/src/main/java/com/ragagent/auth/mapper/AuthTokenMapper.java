package com.ragagent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.auth.domain.AuthToken;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthTokenMapper extends BaseMapper<AuthToken> {
}
