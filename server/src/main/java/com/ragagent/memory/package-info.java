/**
 * 记忆域：用户长期记忆的抽取（{@link com.ragagent.memory.service.MemoryExtractionService}）、去重与合并、
 * 检索与生命周期（租约、墓碑、容量；核心 {@link com.ragagent.memory.service.MemoryService}）。落库走 jsonb，
 * 读路径统一接 {@code JsonMappers} 工厂。
  */
package com.ragagent.memory;
