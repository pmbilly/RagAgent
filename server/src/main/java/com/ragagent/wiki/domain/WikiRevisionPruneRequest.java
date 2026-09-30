package com.ragagent.wiki.domain;

import java.util.List;

/**
 * 单个页面的快照历史两级保留策略。
 *
 * <p>版本阈值 &lt;= 0 表示该级不生效。</p>
 *
 * @param pageID              目标页面 id（空则整套剪枝是 no-op）
 * @param keepFromVersion     低于该版本的快照被丢弃，<b>但仅当其编辑来源在
 *                            {@code prunableSources} 里</b>
 * @param prunableSources     可被软上限丢弃的编辑来源（见
 *                            {@link WikiConstants#PRUNABLE_EDIT_SOURCES}）
 * @param hardKeepFromVersion 低于该版本的快照<b>无条件</b>丢弃，无视作者
 */
public record WikiRevisionPruneRequest(
        String pageID,
        int keepFromVersion,
        List<String> prunableSources,
        int hardKeepFromVersion) {

    /** 全不生效的空请求：pageID 空、两级阈值 0、来源空列表 */
    public static WikiRevisionPruneRequest none() {
        return new WikiRevisionPruneRequest("", 0, List.of(), 0);
    }
}
