/**
 * 聊天管线（库式域，无 HTTP 面）：可插拔的 {@code Plugin} 链——检索、合并、重排、实体抽取、数据分析、历史装载等，
 * 由会话域按顺序驱动。{@link com.ragagent.chatpipeline.plugin.PluginMerge} 与 {@link com.ragagent.chatpipeline.plugin.PluginSearch}
 * 是链上最重的两段。包内 Go 兼容序列化辅助服务于跨语言共享的载荷（§11 边界），勿按"Go 残留"清理。
  */
package com.ragagent.chatpipeline;
