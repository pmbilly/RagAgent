package com.ragagent.session.domain;

import java.util.List;

/**
 * 会话列表的一页（数据 + 总数 + 分页回显）。
 *
 * <p>原先是 {@code SessionRepository} 的嵌套类型（{@code PagedItems}）——控制器要命名它
 * 才能接服务返回值，于是形成了"controller → mapper"的直连。提成领域类型后，仓储/服务/
 * 控制器三方都只认它。</p>
 */
public record SessionPage(List<SessionListItem> items, long total, int page, int pageSize) {
}
