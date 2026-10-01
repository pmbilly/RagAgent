package com.ragagent.datasource;

import java.util.List;


/**
 * 一个可用连接器的元数据（对照 Go {@code datasource.ConnectorMetadata}，
 * internal/datasource/connector.go L138-147）。
 *
 * <h2>它是响应体</h2>
 * <p>{@code GET /api/v1/datasource/types} 的 handler 直接
 * {@code c.JSON(200, connectors)}——一个 {@code []ConnectorMetadata} 裸数组，
 * 没有 {@code data}/{@code success} 信封。所以下面的键序与 omitempty 是
 * <b>线上契约</b>（对照 {@code Resource} 的同款处置）。</p>
 *
 * <h2>Go 实录（键序 = struct 声明序）</h2>
 * <pre>
 *   {"type":"...","name":"...","description":"...","priority":0,
 *    "auth_type":"oauth2","capabilities":["incremental","deletion_sync"]}
 * </pre>
 * <p>两个要点：</p>
 * <ol>
 *   <li><b>{@code icon} 带 omitempty</b>：全部 17 个内置连接器的 {@code Icon}
 *       都是空串，所以线上<b>一个 icon 键都不会出现</b>。Java 侧用
 *       {@code NON_EMPTY}（Go 对 string 的 omitempty 判 {@code len==0}，
 *       与 NON_EMPTY 等价）。</li>
 *   <li><b>{@code capabilities} 没有 omitempty</b>：nil 时输出 {@code null}。
 *       WebCrawler / IMAP 显式给了空切片 {@code []string{}} → 输出 {@code []}
 *       ——两者形态不同，照抄。</li>
 * </ol>
 */
public record ConnectorMetadata(
        String type,
        String name,
        String description,
        String icon,
        int priority,
        String authType,
        List<String> capabilities) {

    public ConnectorMetadata {
        if (type == null) {
            type = "";
        }
        if (name == null) {
            name = "";
        }
        if (description == null) {
            description = "";
        }
        if (icon == null) {
            icon = "";
        }
        if (authType == null) {
            authType = "";
        }
        // capabilities 刻意不归一化：Go 的 nil（→ JSON null）与 []string{}（→ JSON []）
        // 是两种不同形态，构造器把 null 变成 List.of() 就再也拿不回 null 了。
        // 见类注释第 2 条与 memory 模块 Export 的同款踩坑（§9）。
    }

    /** 便利构造：无图标（全部内置连接器都是这个形态）。 */
    public ConnectorMetadata(String type, String name, String description, int priority,
                             String authType, List<String> capabilities) {
        this(type, name, description, "", priority, authType, capabilities);
    }
}
