package com.ragagent.memory.domain;

import java.util.List;

/**
 * 记忆列表的一页（数据 + 总数；对照 Go 的 {@code ([]T, int64, error)}）。
 *
 * <p>原先是 {@code MemoryRepository} 的嵌套类型（{@code Page<T>}）——控制器要命名它才能接
 * 服务返回值，于是形成了"controller → mapper"的直连。提成领域类型后，仓储/服务/控制器
 * 三方都只认它。</p>
 */
public record MemoryPage<T>(List<T> items, long total) {
}
