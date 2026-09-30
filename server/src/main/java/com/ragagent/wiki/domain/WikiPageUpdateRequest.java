package com.ragagent.wiki.domain;

import java.util.List;


/**
 * {@code PUT /wiki/pages/*slug} 的部分更新载荷。JSON 键为 snake（§11 登记边界，前端按此解析）。
 *
 * <p>所有内容字段都是可选的——{@code null} = 字段缺席、保留库中值，于是客户端可以只改正文，
 * 而不必重发（也就不会误覆盖）title / status / aliases。
 * 注意 {@code aliases} 是 {@code List} 而非元素可空——只有整体缺席/整体提供两种状态。</p>
 *
 * @param version 乐观锁护栏：&gt; 0 时若库中版本不同则以冲突拒绝本次更新
 *                （客户端加载后有人改过）；0 跳过校验（兼容旧客户端）
 */
public record WikiPageUpdateRequest( String title, String content, String summary, String pageType, String status, List<String> aliases, int version) {
}
