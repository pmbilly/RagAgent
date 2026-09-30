/**
 * wiki **摄取（ingest）管线**：批量/单篇入库的门面与协作者
 * （{@link com.ragagent.wiki.service.ingest.WikiIngestService}、
 * {@link com.ragagent.wiki.service.ingest.WikiIngestBatchHandler}，map/reduce/finalize/index 四阶段），
 * 连同它的任务队列、锁、幂等凭据与去重/清理支撑
 * （{@code InProcessWiki*}/{@code RedisWiki*}、{@link com.ragagent.wiki.service.ingest.SingleFlight} 等）。
 *
 * <p>页面读写/链接/别名在 {@link com.ragagent.wiki.service.page}，跨切面端口与 LLM 适配留在
 * {@code com.ragagent.wiki.service} 根包。</p>
 */
package com.ragagent.wiki.service.ingest;
