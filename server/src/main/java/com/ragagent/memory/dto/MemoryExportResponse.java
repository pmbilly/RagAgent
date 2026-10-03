package com.ragagent.memory.dto;

import com.ragagent.memory.domain.MemoryItem;
import java.util.List;

/**
 * 记忆导出的响应体（{@code GET /api/v1/memory/export}）。
 *
 * <p>响应是裸的 {@code {items, total, truncated}}：旧四键信封
 * {@code {"data":…,"success":true,...}} 里的 {@code success} 已去掉、{@code data} 改名 {@code items}。</p>
 *
 * <p>⚠️ {@code items} 在<b>空仓库时是 {@code null}</b>（不是 {@code []}）——
 * 只在真的有行时才建列表的既有语义，契约没有要求
 * 改它，故原样保留；与列表端点空时输出 {@code []} 的差别是刻意的。</p>
 *
 * <p>它是"下载"：调用方还带 {@code Content-Disposition} 头（文件名固定
 * {@code weknora-memories.json}），但 Content-Type 仍是普通 JSON。</p>
 */
public record MemoryExportResponse(List<MemoryItem> items, long total, boolean truncated) {
}
