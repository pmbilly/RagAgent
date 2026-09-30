/**
 * 共享审批机制：审批门（Gate）、待审请求/决议消息、Redis pubsub 适配与工具策略。载荷键名与 mcp 侧消费方成对。
 *
 * <p>原在 {@code common.approval}；因 mcp 的工具审批也要用它（mcp 曾反向依赖 agent 的唯一来源）而搬入
 * common——共享机制放最底层，消费方（agent/session/mcp）各自注入，依赖方向是"消费域 → common"。</p>
 */
package com.ragagent.common.approval;
