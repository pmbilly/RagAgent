package com.ragagent.agent.modelcontext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ToolCall;

/**
 * model-context registry 的持久资源半边：为存储资源引用分配请求局部 res://NNNN 句柄，
 * 并在应用代码消费模型输出之前还原它们。
 */
final class ResourceRegistry {

    /**
     * storedRefRE 同时识别遗留的物理引用。新写入持久 resource:// 句柄，但旧 chunk
     * 与消息历史仍可能带 provider URL。给两种形态同一个请求局部句柄让滚动升级
     * 安全。最后一个分支处理 wiki summary 页 slug（summary/<uuid>）：模型经常增删
     * hex 位导致死链，别名到低熵 res:// token 从源头消除改写机会。entity slug
     * （entity/<readable-title>）低熵且语义明确，刻意不动。
     */
    private static final Pattern STORED_REF = Pattern.compile(
            "resource://[0-9A-Za-z_-]{22}|"
                    + "(?:storage://[0-9A-Za-z_-]+/)?"
                    + "(?:local|minio|cos|tos|s3|oss|ks3|obs)://[^\\t\\n\\u000c\\r )\\]>\"']+|"
                    + "summary/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** EncodeText 产出的句柄语法（res://digits）：识别 registry 无法映射回的 token。 */
    static final Pattern RESOURCE_HANDLE_SHAPE = Pattern.compile("res://\\d+");

    private final HandleStore<Object> table;

    ResourceRegistry() {
        // URL 形状的句柄（scheme://digits）保持低熵又足够像链接，
        // 模型会在 Markdown 图/链语法里原样复用它
        this.table = new HandleStore<>("res://", 4, 1);
    }

    /** 用紧凑、稳定句柄替换存储引用。 */
    String encodeText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return SourceRegistry.replaceAllFunc(STORED_REF, value, ref -> table.register(ref, ref, null, null));
    }

    /**
     * 还原 registry 当前已知的每个句柄。按句柄最长优先替换、
     * 不做词边界检查——普通子串行为是承重设计：Markdown 里紧邻标点的句柄。
     */
    String decodeText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        List<HandleStore.Pair<Object>> pairs = table.pairs();
        pairs.sort(Comparator.comparingInt((HandleStore.Pair<Object> p) -> p.handle.length()).reversed());
        for (HandleStore.Pair<Object> item : pairs) {
            value = value.replace(item.handle, item.value);
        }
        return value;
    }

    /**
     * 已知句柄全部还原后移除 handle 形状 token。
     * 只用于模型输出；工具参数必须保留未知句柄，好让 modelcontext 拒绝调用。
     */
    String stripOrphanHandles(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return RESOURCE_HANDLE_SHAPE.matcher(value).replaceAll("");
    }

    /** 文本引用压缩过的消息副本。二进制/图片内容刻意不动。 */
    List<ChatMessage> encodeMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> encoded = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            ChatMessage c = new ChatMessage();
            c.setRole(m.getRole());
            c.setContent(encodeText(m.getContent()));
            c.setReasoningContent(encodeText(m.getReasoningContent()));
            c.setName(m.getName());
            c.setToolCallId(m.getToolCallId());
            c.setImages(m.getImages());
            c.setKind(m.getKind());
            if (m.getMultiContent() != null && !m.getMultiContent().isEmpty()) {
                List<com.ragagent.llm.domain.MessageContentPart> parts = new ArrayList<>(m.getMultiContent());
                c.setMultiContent(parts);
                for (int j = 0; j < parts.size(); j++) {
                    parts.get(j).setText(encodeText(parts.get(j).getText()));
                }
            }
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                List<com.ragagent.llm.domain.ToolCall> calls = new ArrayList<>(m.getToolCalls());
                c.setToolCalls(calls);
                for (int j = 0; j < calls.size(); j++) {
                    calls.get(j).getFunction().setArguments(encodeText(calls.get(j).getFunction().getArguments()));
                }
            }
            encoded.add(c);
        }
        return encoded;
    }

    /** 工具调用 JSON 参数里的句柄还原。 */
    void decodeToolCalls(List<ToolCall> toolCalls) {
        for (ToolCall call : toolCalls) {
            call.getFunction().setArguments(decodeText(call.getFunction().getArguments()));
        }
    }

    /**
     * 已解码字符串里 registry 无法解析的 distinct handle 形状 token。
     * 非空结果 = 模型虚构了引用或用户文本撞了句柄语法。
     */
    List<String> orphanHandles(String decoded) {
        if (decoded == null || decoded.isEmpty()) {
            return null;
        }
        List<String> orphans = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = RESOURCE_HANDLE_SHAPE.matcher(decoded);
        while (m.find()) {
            String match = m.group();
            if (table.has(match)) {
                continue;
            }
            if (!seen.add(match)) {
                continue;
            }
            orphans.add(match);
        }
        return orphans;
    }

    /** 已分配句柄清单（流式 holdLen 用）。 */
    List<String> handles() {
        List<HandleStore.Pair<Object>> pairs = table.pairs();
        List<String> handles = new ArrayList<>(pairs.size());
        for (HandleStore.Pair<Object> item : pairs) {
            handles.add(item.handle);
        }
        return handles;
    }

    HandleStore<Object> table() {
        return table;
    }
}
