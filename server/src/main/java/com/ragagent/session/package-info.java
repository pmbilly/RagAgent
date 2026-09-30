/**
 * 会话域：会话与消息的读写（{@link com.ragagent.session.service.SessionService} /
 * {@link com.ragagent.session.service.MessageService}）、两条问答入口
 * （{@code SessionKnowledgeQaService} 知识库问答、{@code SessionAgentQaService} Agent 问答）、
 * SSE 流式桥（{@code sse/}）与 {@code QaWiring} 的管线装配（chat 管线、Agent 工具的端口实现）。
 *
 * <p>对外只暴露 {@code controller/} 的 HTTP 面与 {@code common.session} 的载荷端口
 * （memory 蒸馏、chat 管线往返都用载荷而非本域实体）；反向依赖：本域调用
 * {@code chatpipeline} 执行问答管线，属 {@code L3 → L2} 合法方向。</p>
 */
package com.ragagent.session;
