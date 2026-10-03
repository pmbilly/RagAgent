package com.ragagent.agent;

import java.util.List;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.MessageContentPart;
import com.ragagent.llm.domain.ToolCall;

/**
 * token 估算器。
 *
 * <p>token 数的权威来源是模型 API 返回的 Usage；本类只服务两个场景：</p>
 * <ol>
 *   <li><b>增量估算</b>——LLM 调用之间新增消息（assistant 回复 + 工具结果）的
 *       token 成本，供引擎决定是否需要压缩，省一次 API 往返；</li>
 *   <li><b>首轮兜底</b>——会话首轮没有 Usage 可用，用估算值代替。</li>
 * </ol>
 *
 * <p><b>编码器</b>：jtokkit 的 {@link EncodingType#CL100K_BASE}——同一份 OpenAI 词表 +
 * 同一 BPE 合并算法，token 数与基线语料逐字节一致（36 条语料 + 消息/工具样例钉住）。
 * 用 {@code encodeOrdinary}：特殊 token（{@code <|endoftext|>} 等）按普通文本切，
 * 从不查 special token 表。</p>
 *
 * <p><b>reasoning_content 必须计数</b>：思考类模型（DeepSeek V3.2/V4、MiMo）要求
 * 历轮 reasoning_content 原样回传，它通常数倍于可见回复。漏记曾把 130k 上下文
 * 量成 26k——压缩于是切错位置、几乎白切、下轮再来一遍。因此它与正文、工具调用
 * 一起计数。</p>
 */
public final class TokenEstimator {

    private static final int PER_MESSAGE_OVERHEAD = 3;
    private static final int PER_CONVERSATION_TAIL = 3;
    private static final int PER_TOOL_CALL_OVERHEAD = 4;
    private static final int PER_TOOL_DEF_OVERHEAD = 8;

    /**
     * 一张图的假定成本。供应商按 tile 计费，
     * URL 与 base64 都不可作依据：短 https:// 链接和一兆字节的 data URI 可能同价。
     * 1200 token ≈ 4800 字符（按 4 字符/token 的经验值）。
     */
    private static final int ESTIMATED_IMAGE_TOKENS = 1200;

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();

    private final Encoding codec = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    public TokenEstimator() {
    }

    /** 一批消息的估算总 token。全量上下文优先用 API Usage。 */
    public int estimateMessages(List<ChatMessage> messages) {
        int total = 0;
        if (messages != null) {
            for (ChatMessage m : messages) {
                total += estimateMessage(m);
            }
        }
        return total + PER_CONVERSATION_TAIL;
    }

    /** 单字符串的 BPE token 数。 */
    public int estimateString(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            IntArrayList ids = codec.encodeOrdinary(s);
            return ids.size();
        } catch (RuntimeException e) {
            return (utf8Length(s) + 3) / 4;
        }
    }

    /** 单条消息的估算 token。每一个上线字段都要算进去。 */
    public int estimateMessage(ChatMessage msg) {
        if (msg == null) {
            return PER_MESSAGE_OVERHEAD;
        }
        int tokens = PER_MESSAGE_OVERHEAD;
        tokens += estimateString(msg.getRole());
        tokens += estimateString(msg.getContent());
        tokens += estimateString(msg.getName());
        tokens += estimateString(msg.getToolCallId());
        tokens += estimateString(msg.getReasoningContent());
        tokens += estimateImageParts(msg);

        List<ToolCall> calls = msg.getToolCalls();
        if (calls != null) {
            for (ToolCall tc : calls) {
                tokens += estimateString(tc.getFunction().getName());
                tokens += estimateString(tc.getFunction().getArguments());
                tokens += PER_TOOL_CALL_OVERHEAD;
            }
        }
        return tokens;
    }

    /**
     * 多模态内容计数。MultiContent 是实际发给供应商的
     * 组装形态，所以它在场时裸 Images 列表是同一批图被数第二遍——不计。
     */
    private int estimateImageParts(ChatMessage msg) {
        List<MessageContentPart> multi = msg.getMultiContent();
        if (multi == null || multi.isEmpty()) {
            List<String> images = msg.getImages();
            return (images == null ? 0 : images.size()) * ESTIMATED_IMAGE_TOKENS;
        }
        int tokens = 0;
        for (MessageContentPart part : multi) {
            if (part.getImageUrl() != null || "image_url".equals(part.getType())) {
                tokens += ESTIMATED_IMAGE_TOKENS;
                continue;
            }
            tokens += estimateString(part.getText());
        }
        return tokens;
    }

    /**
     * 随每个请求发送的工具 schema 的 token 成本。它们是供应商
     * 计费的 prompt 的一部分，但<b>不是</b>压缩触发条件（那只看消息）；用于诊断与
     * 请求预算钳制，勿用于 shouldCompact。
     */
    public int estimateTools(List<ChatTool> tools) {
        int total = 0;
        if (tools == null) {
            return 0;
        }
        for (ChatTool tool : tools) {
            total += estimateString(tool.getFunction().getName());
            total += estimateString(tool.getFunction().getDescription());
            // FunctionDef.parameters 是 JsonNode，序列化回紧凑 JSON 计数。
            total += estimateString(ParametersJson.write(tool.getFunction().getParameters()));
            total += PER_TOOL_DEF_OVERHEAD;
        }
        return total;
    }

    /** JsonNode → 紧凑 JSON 文本（null 安全）。 */
    private static final class ParametersJson {
        private static String write(com.fasterxml.jackson.databind.JsonNode node) {
            if (node == null || node.isNull() || node.isMissingNode()) {
                return "";
            }
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(node);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return "";
            }
        }
    }

    /** 返回 UTF-8 字节长度（String.length() 是 UTF-16 长度，别混用）。 */
    private static int utf8Length(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
