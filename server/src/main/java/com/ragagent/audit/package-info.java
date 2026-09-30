/**
 * 审计日志域：审计条目的写入面（{@link com.ragagent.audit.service.AuditLogService}）、查询面与保留期清理
 * （{@link com.ragagent.audit.service.AuditLogRetentionRunner}）。审计行只追加不修改。
  */
package com.ragagent.audit;
