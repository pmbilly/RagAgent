package com.ragagent.datasource;

import java.util.List;


/**
 * 一个可用连接器的元数据。
 *
 * <h2>它是响应体</h2>
 * <p>{@code GET /api/v1/datasource/types} 的响应是一个 {@code []ConnectorMetadata} 裸数组，
 * 没有 {@code data}/{@code success} 信封。所以下面的键序与 omitempty 是
 * <b>线上契约</b>（与 {@code Resource} 的同款处置）。</p>
 *
 * <h2>JSON 形状（键序 = 声明序）</h2>
 * <pre>
 *   {"type":"...","name":"...","description":"...","priority":0,
 *    "auth_type":"oauth2","capabilities":["incremental","deletion_sync"]}
 * </pre>
 * <p>两个要点：</p>
 * <ol>
 *   <li><b>{@code icon} 为空省略</b>：全部 17 个内置连接器的 {@code icon}
 *       都是空串，所以线上<b>一个 icon 键都不会出现</b>。Java 侧用
 *       {@code NON_EMPTY} 表达。</li>
 *   <li><b>{@code capabilities} 恒输出</b>：{@code null} 时输出 {@code null}。
 *       WebCrawler / IMAP 给的是空列表 → 输出 {@code []}
 *       ——两种形态并存，保持原样。</li>
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
        // capabilities 刻意不归一化：null（→ JSON null）与空列表（→ JSON []）
        // 是两种不同形态，构造器把 null 变成 List.of() 就再也拿不回 null 了。
        // 见类注释第 2 条。
    }

    /** 便利构造：无图标（全部内置连接器都是这个形态）。 */
    public ConnectorMetadata(String type, String name, String description, int priority,
                             String authType, List<String> capabilities) {
        this(type, name, description, "", priority, authType, capabilities);
    }
}
