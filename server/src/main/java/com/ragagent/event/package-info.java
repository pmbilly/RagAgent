/**
 * 事件契约包（对照 Go {@code internal/event}，全量翻译：event.go / event_data.go /
 * adapter.go / middleware.go / global.go，另收编 agent/const.go 的 generateEventID）。
 *
 * <p><b>这是波 4 子批 4.2-4.6 所有流式件的唯一公共前置</b>。零路由、零 TestSchema 变更；
 * 依赖方向：将来由 agent 引擎 / approval 接线 / session.sse / StreamManager 桥接层
 * import 本包，本包不 import 它们中的任何一个。</p>
 *
 * <h2>JSON 是契约</h2>
 * <p>每个 payload（event_data.go 的 27 个结构体）的键名 / 键序 / omitempty 边界都与
 * Go {@code encoding/json} 逐字节一致——它们经 AgentStreamHandler
 * （{@code toolApprovalDataToMap} 即 {@code json.Marshal(payload)} → map）转发进
 * StreamManager.appendEvent，最终出现在 continue-stream 的 SSE 帧里（§9.3 四路径
 * MATCH 基线）。<b>payload 的唯一合法序列化出口是 {@link com.ragagent.event.EventJson}</b>
 * （Go 转义 + map 键序 + Go 浮点 + Go 时间），期望值全部是 /tmp 下 Go 程序实录。</p>
 *
 * <h2>24 个 emit 点（§6 纪律基线，2026-09-20 勘察实测，行号为 Go 仓现状）</h2>
 * <table border="1">
 *   <tr><th>#</th><th>Go 文件:行</th><th>事件类型</th><th>payload 类型</th><th>event id 形态</th></tr>
 *   <tr><td>1</td><td>agent/think.go:287</td><td>thought</td><td>AgentThoughtData</td><td>generateEventID("thinking")（整段思考流共用）</td></tr>
 *   <tr><td>2</td><td>agent/think.go:324</td><td>final_answer</td><td>AgentFinalAnswerData</td><td>generateEventID("answer")（整段答案流共用）</td></tr>
 *   <tr><td>3</td><td>agent/think.go:348</td><td>tool_call</td><td>AgentToolCallData</td><td>&lt;toolCallID&gt;-tool-call-pending</td></tr>
 *   <tr><td>4</td><td>agent/think.go:361</td><td>tool_call</td><td>AgentToolCallData</td><td>&lt;toolCallID&gt;-tool-call-progress</td></tr>
 *   <tr><td>5</td><td>agent/think.go:385</td><td>thought</td><td>AgentThoughtData</td><td>generateEventID("thinking-tool")（每个 thinking tool 一个）</td></tr>
 *   <tr><td>6</td><td>agent/observe.go:140</td><td>context_compacted</td><td>ContextCompactedData</td><td>generateEventID("compaction")</td></tr>
 *   <tr><td>7</td><td>agent/observe.go:385</td><td>final_answer</td><td>AgentFinalAnswerData</td><td>generateEventID("answer")（内容安全拦截的固定兜底答案）</td></tr>
 *   <tr><td>8</td><td>agent/observe.go:394</td><td>final_answer</td><td>AgentFinalAnswerData</td><td>同 7 的 answerID，Done=true 收尾</td></tr>
 *   <tr><td>9</td><td>agent/observe.go:441</td><td>final_answer</td><td>AgentFinalAnswerData</td><td>generateEventID("answer")（自然停且未直播过答案时；content 空则不发）</td></tr>
 *   <tr><td>10</td><td>agent/approval/gate.go:381</td><td>tool_approval_required</td><td>ToolApprovalRequiredData</td><td>&lt;pendingID&gt;-approval-required</td></tr>
 *   <tr><td>11</td><td>agent/approval/gate.go:399</td><td>tool_approval_resolved</td><td>ToolApprovalResolvedData</td><td>&lt;pendingID&gt;-approval-resolved</td></tr>
 *   <tr><td>12</td><td>agent/approval/gate.go:479</td><td>mcp_oauth_required</td><td>MCPOAuthRequiredData</td><td>&lt;pendingID&gt;-mcp-oauth-required</td></tr>
 *   <tr><td>13</td><td>agent/approval/gate.go:506</td><td>mcp_oauth_resolved</td><td>MCPOAuthResolvedData</td><td>&lt;pendingID&gt;-mcp-oauth-resolved</td></tr>
 *   <tr><td>14</td><td>agent/finalize.go:68</td><td>final_answer</td><td>AgentFinalAnswerData</td><td>generateEventID("answer")（finalize 合成答案流）</td></tr>
 *   <tr><td>15</td><td>agent/finalize.go:93</td><td>final_answer</td><td>AgentFinalAnswerData</td><td>同 14 的 answerID，Done 补发</td></tr>
 *   <tr><td>16</td><td>agent/finalize.go:161</td><td>agent.complete</td><td>AgentCompleteData</td><td>generateEventID("complete")</td></tr>
 *   <tr><td>17</td><td>agent/act.go:343</td><td>tool_result</td><td>AgentToolResultData</td><td>&lt;toolCallID&gt;-tool-result</td></tr>
 *   <tr><td>18</td><td>agent/act.go:359</td><td>agent.tool</td><td>AgentActionData</td><td>&lt;toolCallID&gt;-tool-exec</td></tr>
 *   <tr><td>19</td><td>agent/act.go:477</td><td>tool_call</td><td>AgentToolCallData</td><td>&lt;toolCallID&gt;-tool-hint</td></tr>
 *   <tr><td>20</td><td>agent/engine.go:362</td><td>error</td><td>ErrorData</td><td>generateEventID("error")</td></tr>
 *   <tr><td>21</td><td>agent/engine.go:449</td><td>final_answer</td><td>AgentFinalAnswerData</td><td>既有 answerID，Done=true（closeAnswerStream）</td></tr>
 *   <tr><td>22</td><td>agent/tools/shell_command_output.go:30</td><td>command_output</td><td>CommandOutputData</td><td>uuid.NewString()（每次全量新 id，累计尾量非增量分片）</td></tr>
 *   <tr><td>23</td><td>agent/tools/mcp_oauth.go:201</td><td>mcp_oauth_required</td><td>MCPOAuthRequiredData</td><td>"mcp-oauth-notice-" + serviceID（仅提示，timeout_seconds=0）</td></tr>
 *   <tr><td>24</td><td>agent/steer.go:71</td><td>user_message_injected</td><td>UserMessageInjectedData</td><td>generateEventID("injected")</td></tr>
 * </table>
 *
 * <p>分布：final_answer×7（#2/7/8/9/14/15/21）、tool_call×3（#3/4/19）、thought×2（#1/5）、
 * tool_approval×2（#10/11）、mcp_oauth_required×2（#12/23）、observe 压缩×1、complete×1、
 * tool_result×1、agent.tool×1、error×1、command_output×1、mcp_oauth_resolved×1、
 * user_message_injected×1。其余 emit 形态（旧 chat_pipeline / handler/session 的
 * session_title 等）不在此表——它们属于波 5 / 各自模块的翻译范围。</p>
 *
 * <h2>EventBus 语义（照抄 Go）</h2>
 * <ul>
 *   <li><b>同步模式（默认）</b>：按注册顺序执行；任一 handler 失败 → 立即中断链并抛
 *       {@link com.ragagent.event.EventBusException}（文案 {@code event handler failed for <type>: <原因>}）；
 *       handler 的同步异常（Go panic 等价物之一）原样冒出 Emit 调用方——Go 的同步路径不 recover。</li>
 *   <li><b>ID 自动生成是值语义</b>：Emit 在<b>浅拷贝</b>上补 UUID，调用方的 Event 对象不变；
 *       metadata map 是共享引用（Go 结构体拷贝的语义），中间件对它的写入调用方可见。</li>
 *   <li><b>异步模式</b>（{@code NewAsyncEventBus}）：每个 handler 一个虚拟线程并发执行，
 *       Emit 立即返回；panic 与错误都不外泄（Go：recover 后记日志 / 错误被 {@code _ =} 丢弃）。
 *       跨线程按项目约定<b>显式传递</b> TenantContext 值（{@link com.ragagent.event.TenantContextSnapshot}），
 *       禁共享 ThreadLocal。</li>
 *   <li><b>EmitAndWait</b>：两个模式下都并发跑全部 handler 并等齐；panic 转 error
 *       （{@code event handler panic (type=...): ...}），再包一层 {@code event handler failed for ...}。</li>
 *   <li><b>中间件链</b>：{@code Chain(first, second)} 中 first 在外层（Go 反向 apply）。</li>
 * </ul>
 *
 * <h2>Java 侧对接点预告（本包不实现、不 import）</h2>
 * <ul>
 *   <li>{@code agent.approval}：已翻译的 {@code com.ragagent.agent.approval.EventBus}
 *       最小面（{@code void emit(Event)}）将来由接线层适配到本包的 {@link com.ragagent.event.EventBus}。</li>
 *   <li>{@code session.sse.StreamEventEmitter} / {@code StreamManager.appendEvent}：
 *       AgentStreamHandler 的 Java 版（波 4.6）订阅本包事件并转发进流。</li>
 * </ul>
 */
package com.ragagent.event;
