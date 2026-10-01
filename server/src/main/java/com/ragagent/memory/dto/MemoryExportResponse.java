package com.ragagent.memory.dto;

import com.ragagent.memory.domain.MemoryItem;
import java.util.List;

/**
 * 记忆导出的响应体（{@code GET /api/v1/memory/export}）。
 *
 * <p>旧形态是 Go 的四键信封 {@code {"data":…,"success":true,"total":N,"truncated":bool}}；
 * 换锚后去掉 {@code success}、{@code data} 改名 {@code items}（§14.9k M1）。</p>
 *
 * <p>⚠️ {@code items} 在<b>空仓库时是 {@code null}</b>（不是 {@code []}）——这是 Go
 * {@code var items []*types.MemoryItem} 只在有行时才 append 的既有语义，契约没有要求
 * 改它，故原样保留；与列表端点空时输出 {@code []} 的差别是刻意的。</p>
 *
 * <p>它是"下载"：调用方还带 {@code Content-Disposition} 头（文件名固定
 * {@code weknora-memories.json}），但 Content-Type 仍是普通 JSON。</p>
 */
public record MemoryExportResponse(List<MemoryItem> items, long total, boolean truncated) {
}
