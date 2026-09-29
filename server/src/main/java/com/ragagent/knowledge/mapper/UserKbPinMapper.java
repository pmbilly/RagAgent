package com.ragagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.knowledge.domain.UserKbPin;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** {@code user_kb_pins} 表的 MyBatis-Plus mapper。 */
public interface UserKbPinMapper extends BaseMapper<UserKbPin> {
}
