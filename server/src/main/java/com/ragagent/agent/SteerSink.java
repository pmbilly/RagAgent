package com.ragagent.agent;

import java.util.List;
import java.util.Map;

/**
 * 运行中轮次的 steer 注入通道——引擎侧的半边；HTTP 面已由
 * SteerController 落地，实现方（session 侧）提供 bean。
 *
 * <p>本接口刻意声明在引擎与实现方都够得着、又互不依赖的位置：
 * 引擎与 session 侧谁也不 import 谁。</p>
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>{@link #pollSteer} 返回待注入事件 {@code {id, content, mentioned_items,
 *       channel}} map 列表。lastOffset 未用：消费标记（不是数值 offset）才是跳过
 *       已处理事件的依据。空/缺队列是空结果，不是错误。</li>
 *   <li>{@link #persistSteerMessage} 把接受的消息存成运行请求 ID 下的 user 角色行，
 *       返回新行 ID。<b>空 ID = 持久化失败</b>：调用方不得把文本追加进消息，
 *       下一次 drain 可重试。mentions 只入历史——运行中轮次的范围不因此变宽。</li>
 *   <li>channel 是本次 steer 的来源（"web"/"api"/"im"）；空存为 "web"。</li>
 * </ul>
 *
 * <p><b>错误通道</b>：
 * pollSteer 出错时抛异常（引擎 catch 后记日志并跳过本轮注入），成功时返回事件列表。</p>
 *
 * <p><b>mentionedItems 是不透明透传</b>（与既有 {@code List<Object>} 透传先例同族）：
 * 引擎只把它从事件 map 原样搬到持久化调用，不理解其内容；实现方用
 * {@code session.domain.MentionedItem.fromRawMap} 族还原类型。</p>
 */
public interface SteerSink {

    /**
     * 拉取待注入的 steer 事件。
     *
     * @return 事件 map 列表（可能为空）；每个 map 至少含 {@code id}/{@code content}
     */
    List<Map<String, Object>> pollSteer(String sessionId, String messageId, int lastOffset);

    /**
     * 持久化接受的 steer 消息。
     *
     * @return 新 user 行的 ID；空串 = 持久化失败（事件保持待处理，下次 drain 重试）
     */
    String persistSteerMessage(String sessionId, String messageId, String steerId,
            String content, Object mentionedItems, String channel);
}
