package com.ragagent.session.support;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.ragagent.agent.domain.AgentStep;
import com.ragagent.agent.domain.ToolCall;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.storage.support.Rewriter;

/**
 * 消息历史响应的存储引用重写（对照 Go {@code Rewriter.RewriteMessages*}）。
 *
 * <p>2026-09-30 从 {@link Rewriter} 搬来：这段代码操作的是**会话实体**（{@code Message}/
 * {@code MessageImage}/{@code AgentStep}），留在存储域会让 {@code storage} 反向依赖 {@code session}
 * （即 {@code session ⇄ storage} 环）。URL 重写内核仍在 {@link Rewriter}，本类只做"消息 → 字段级重写"的编排：
 * 因此依赖方向是"会话 → 存储"，单向。</p>
 *
 * <p>生产调用方：{@code MessageController} 的消息列表端点（{@code rewriter.rewriteMessagesResponse}）。
 * 行为逐行移植自 Go 的 {@code RewriteMessages*}，有 {@code MessageReferenceRewriterTest} 覆盖。</p>
 */
public class MessageReferenceRewriter {

    private final Rewriter rewriter;

    public MessageReferenceRewriter(Rewriter rewriter) {
        this.rewriter = rewriter;
    }


    /**
     * {@code cloneMessages} 用的 mapper——**与 HTTP 那个不是同一个**，也刻意不是。
     *
     * <p>Go 的 {@code cloneMessages} 走的是 {@code json.Marshal/Unmarshal} 往返，
     * 本处的目的是复刻那次往返（深拷贝嵌套切片与 agent steps，且与源切片解耦），
     * 不是产出响应字节。所以只需要能把 {@code Message} 完整读回来即可，
     * 时间格式无所谓——但 {@code JavaTimeModule} 不能少，
     * 否则 {@code created_at} 这类字段直接抛 {@code InvalidDefinitionException}。</p>
     */
    private static final ObjectMapper CLONE_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /**
     * 对照 Go {@code Rewriter.RewriteMessagesResponse}：返回重写后的消息**副本**，
     * 调用方因此能保住原对象（例如来自 service 缓存的那些）。禁用时原样返回。
     */
    public List<Message> rewriteMessagesResponse(List<Message> messages) {
        if (!rewriter.enabled() || messages == null || messages.isEmpty()) {
            return messages;
        }
        List<Message> out = cloneMessages(messages);
        rewriteMessages(out);
        return out;
    }

    /**
     * 对照 Go {@code cloneMessages}：经 JSON 深拷贝，
     * 使嵌套切片与 agent steps 与源切片互不共享。
     *
     * <p>与 Go 一致，这次往返会**丢掉 {@code json:"-"} 的字段**
     * （{@code rendered_content} / {@code execution_context}）——
     * 它们本来就不出响应，所以不可见；但这是刻意的行为，别"顺手修好"。</p>
     */
    private static List<Message> cloneMessages(List<Message> messages) {
        try {
            String json = CLONE_MAPPER.writeValueAsString(messages);
            return CLONE_MAPPER.readValue(json, new TypeReference<List<Message>>() {});
        } catch (Exception e) {
            // 对照 Go：往返失败就退回原切片
            return messages;
        }
    }

    /**
     * 对照 Go {@code Rewriter.RewriteMessages}：把消息历史响应里的存储引用换成
     * 客户端能直接加载的图片 URL。<b>就地改写</b>。
     */
    public void rewriteMessages(List<Message> messages) {
        if (!rewriter.enabled() || messages == null) {
            return;
        }
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            message.setContent(rewriter.rewrite(message.getContent()));
            if (message.getImages() != null) {
                for (MessageImage image : message.getImages()) {
                    image.setUrl(rewriter.rewriteRef(image.getUrl()));
                    image.setCaption(rewriter.rewrite(image.getCaption()));
                }
            }
            message.setKnowledgeReferences(rewriter.copyReferences(message.getKnowledgeReferences()));
            rewriteAgentSteps(message.getAgentSteps());
        }
    }

    /**
     * 对照 Go {@code Rewriter.rewriteAgentSteps}：覆盖推理轨迹——
     * 工具输出里嵌着检索到的图与生成的图表的 Markdown 图片。
     *
     * <p>只改这四个字段（{@code thought} / {@code reasoning_content} /
     * {@code tool_calls[].reflection} / {@code tool_calls[].result.output}），
     * {@code result.data} 之类**不动**——照抄 Go 的范围。</p>
     */
    private void rewriteAgentSteps(List<AgentStep> steps) {
        if (steps == null) {
            return;
        }
        for (AgentStep step : steps) {
            if (step == null) {
                continue;
            }
            step.setThought(rewriter.rewrite(step.getThought()));
            step.setReasoningContent(rewriter.rewrite(step.getReasoningContent()));
            if (step.getToolCalls() == null) {
                continue;
            }
            for (ToolCall call : step.getToolCalls()) {
                if (call == null) {
                    continue;
                }
                call.setReflection(rewriter.rewrite(call.getReflection()));
                if (call.getResult() != null) {
                    call.getResult().setOutput(rewriter.rewrite(call.getResult().getOutput()));
                }
            }
        }
    }
}
