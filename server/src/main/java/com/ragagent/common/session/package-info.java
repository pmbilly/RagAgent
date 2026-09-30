/**
 * 会话（session）跨域**端口与载荷**（最底层，零域依赖）：消费方注入接口、
 * 会话域实现，依赖方向是"消费域 → 端口 ← 会话域"，避免上层直连会话域的表实体。
 *
 * <ul>
 *   <li>{@code SessionMessagePort.SessionMessageView} — memory 蒸馏用的最窄消息视图
 *       （id/role/content/createdAt）；</li>
 *   <li>{@code PipelineMessageView} + {@code PipelineMessageImageView} +
 *       {@code PipelineMessageAttachmentView} + {@code PipelineUsedMemoryView} —
 *       chat 管线在端口往返与历史装载时的读取面（历史问答对 / 图片描述 / 附件渲染 / 用到记忆）。</li>
 * </ul>
 *
 * <p>载荷只带消费方真正读取的字段；需要更多字段时先改载荷，别把实体漏出去。</p>
 */
package com.ragagent.common.session;
