package com.ragagent.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.system.domain.SystemSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * system_settings 表 Mapper（对照 Go internal/application/repository/system_setting.go）。
 *
 * Go 的 Upsert = `clause.OnConflict{Columns: key, UpdateAll: true}`——
 * Java 侧在 service 层先 selectByKey 再 insert/updateById（先读后写的两步等价，
 * 单实例 SystemAdmin 操作无并发窗口）。
 */
@Mapper
public interface SystemSettingMapper extends BaseMapper<SystemSetting> {
}
