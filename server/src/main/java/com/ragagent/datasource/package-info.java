/**
 * 数据源连接器域：外部知识源（Notion/Feishu/Yuque/RSS/IMA/GitLab 等，见 {@code connector/}）的抓取、游标推进、
 * 同步编排（{@link com.ragagent.datasource.service.DataSourceService}）与数据源配置面。
 * 口径：连接器只负责"取回并翻译成仓内文档"，进入知识库后的处理属 {@code knowledge} 域。
  */
package com.ragagent.datasource;
