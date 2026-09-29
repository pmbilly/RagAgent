/**
 * 数据访问层：MyBatis-Plus mapper + 仓储门面。ChunkRepository 是文档 chunk 行的读写
 * 契约（软删三面孔 / 全字段 UPDATE / 乐观锁 / PG-H2 方言分支，见其类 javadoc）；
 * FaqChunkRepository 承接 FAQ 条目面（重复问检测的 jsonb 方言、flags 位运算批量更新）。
 */
package com.ragagent.knowledge.mapper;
