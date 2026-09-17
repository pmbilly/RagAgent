package com.ragagent.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.audit.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * audit_logs 的 MyBatis-Plus 基础仓储（对照 Go
 * internal/application/repository/audit_log.go 的 {@code auditLogRepository} L16-137）。
 *
 * <p>之所以只用 {@link BaseMapper} 而不写自定义 SQL：Go 侧的全部查询都是等值 /
 * 范围 / 排序 / LIMIT 的组合，MyBatis-Plus 的 {@code LambdaQueryWrapper} 能 1:1 表达，
 * 且 {@code @TableName(autoResultMap = true)} 会让 {@code details} 这个 jsonb 列
 * 自动走 {@code PgJsonTypeHandler}（写 setObject(OTHER) / 读规范化键序）。
 * 查询构造全部收口在 {@link AuditLogRepository}，语义注释也在那里。</p>
 *
 * <p>本表<b>没有</b>软删除列、没有 Update 语句——只追加，无 @TableLogic。</p>
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
