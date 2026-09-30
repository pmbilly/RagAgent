/**
 * wiki **页面**面：页面服务（接口门面 + 实现）、文件夹/视图支撑、链接与 lint
 * （{@link com.ragagent.wiki.service.page.WikiLinkify}、{@code WikiCrossLinker}/{@code WikiDeadLinks}）、
 * 别名（slug）锁与匹配、编辑上下文。
 *
 * <p>摄取管线在 {@link com.ragagent.wiki.service.ingest}，跨切面端口与 LLM 适配留在
 * {@code com.ragagent.wiki.service} 根包。</p>
 */
package com.ragagent.wiki.service.page;
