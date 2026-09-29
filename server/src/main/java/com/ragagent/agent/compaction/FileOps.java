package com.ragagent.agent.compaction;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.AgentToolNames;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ToolCall;

/**
 * 沙箱文件操作在压缩中的存续。
 *
 * <p>丢掉"我写过 /workspace/output/deck.html"这段历史，模型就失去了对已产出产物的
 * 记忆，会重建磁盘上已有的文件——对大到需要分块写入的文件，重启恰恰是永不终止的
 * 那个循环。LLM 摘要本该承载这件事，但摘要是散文，路径列表是摘要器最先丢的细节。
 * 于是把路径机械地抽出来、附加到每个摘要上、再由下一个摘要继承。</p>
 *
 * <p>收集时三套集合，渲染时两个列表：写过的文件即使也被读过，只按"已修改"上报。</p>
 */
final class FileOps {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String READ_FILES_TAG = "read-files";
    private static final String MODIFIED_FILES_TAG = "modified-files";

    /**
     * 跟踪路径数上限。超出后丢最旧的条目，
     * 而不是让 prompt 无限增长。
     */
    private static final int MAX_TRACKED_FILE_PATHS = 50;

    private final List<String> read = new ArrayList<>();
    private final List<String> written = new ArrayList<>();
    private final List<String> edited = new ArrayList<>();

    /** 原地追加去重（不改传入列表）。 */
    static void appendUnique(List<String> list, String path) {
        String p = ConversationSerializer.goTrimSpace(path);
        if (p.isEmpty() || list.size() >= MAX_TRACKED_FILE_PATHS) {
            return;
        }
        for (String existing : list) {
            if (existing.equals(p)) {
                return;
            }
        }
        list.add(p);
    }

    /**
     * 收集被摘要掉的消息读写的沙箱路径。previousSummary 承载
     * 上一次压缩继承下来的块——摘要已不进入自身后继的输入，它不再属于本次被摘要的
     * 消息范围。
     */
    static FileOps extractFileOps(String previousSummary, List<List<ChatMessage>> groups) {
        FileOps ops = new FileOps();
        ops.inherit(previousSummary);
        if (groups != null) {
            for (List<ChatMessage> group : groups) {
                if (group == null) {
                    continue;
                }
                for (ChatMessage msg : group) {
                    ops.inherit(msg.getContent());
                    List<ToolCall> calls = msg.getToolCalls();
                    if (calls == null) {
                        continue;
                    }
                    for (ToolCall tc : calls) {
                        String path = toolCallPath(
                                tc.getFunction() == null ? null : tc.getFunction().getArguments());
                        if (path.isEmpty()) {
                            continue;
                        }
                        String fn = tc.getFunction().getName() == null ? "" : tc.getFunction().getName();
                        switch (fn) {
                            case AgentToolNames.TOOL_WRITE_SANDBOX_FILE -> appendUnique(ops.written, path);
                            case AgentToolNames.TOOL_EDIT_SANDBOX_FILE -> appendUnique(ops.edited, path);
                            case AgentToolNames.TOOL_READ_FILE,
                                 AgentToolNames.LEGACY_TOOL_READ_SANDBOX_FILE -> appendUnique(ops.read, path);
                            default -> {
                            }
                        }
                    }
                }
            }
        }
        return ops;
    }

    /**
     * 把已渲染的块折回收集集合。它的 modified 列表已经过 resolve()，
     * 所以按 written 归位。
     */
    void inherit(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        var inherited = parseFileOpsBlock(content);
        if (inherited.read() != null) {
            for (String p : inherited.read()) {
                appendUnique(read, p);
            }
        }
        if (inherited.modified() != null) {
            for (String p : inherited.modified()) {
                appendUnique(written, p);
            }
        }
    }

    /**
     * 三套集合收敛为展示给模型的形态：动过的都算"已修改"，
     * 只有从未写过的才列作"已读"。
     */
    Resolved resolve() {
        List<String> modified = new ArrayList<>();
        for (String p : written) {
            appendUnique(modified, p);
        }
        for (String p : edited) {
            appendUnique(modified, p);
        }
        List<String> readOnly = new ArrayList<>();
        for (String p : read) {
            if (!contains(modified, p)) {
                appendUnique(readOnly, p);
            }
        }
        return new Resolved(readOnly, modified);
    }

    record Resolved(List<String> read, List<String> modified) {
    }

    private static boolean contains(List<String> list, String want) {
        for (String p : list) {
            if (p.equals(want)) {
                return true;
            }
        }
        return false;
    }

    /** 从工具调用参数里取 {@code path}。畸形/截断的参数不出声地贡献空。 */
    static String toolCallPath(String arguments) {
        try {
            JsonNode node = MAPPER.readTree(arguments == null ? "" : arguments);
            if (node == null || !node.isObject() || !node.hasNonNull("path")) {
                return "";
            }
            String p = node.get("path").asText("");
            return p == null ? "" : ConversationSerializer.goTrimSpace(p);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 渲染附加到摘要的块；什么都没动过时为 ""。
     */
    String format() {
        Resolved r = resolve();
        if (r.read().isEmpty() && r.modified().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        writeTagged(sb, READ_FILES_TAG, r.read());
        writeTagged(sb, MODIFIED_FILES_TAG, r.modified());
        return sb.toString();
    }

    private static void writeTagged(StringBuilder sb, String tag, List<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        sb.append("\n\n<%s>\n%s\n</%s>".formatted(tag, String.join("\n", paths), tag));
    }

    record TaggedBlock(List<String> read, List<String> modified) {
    }

    /**
     * 读回 format 写出的块，连续压缩得以累积而不是
     * 每次忘掉上一次。
     */
    static TaggedBlock parseFileOpsBlock(String content) {
        return new TaggedBlock(parseTagged(content, READ_FILES_TAG), parseTagged(content, MODIFIED_FILES_TAG));
    }

    private static List<String> parseTagged(String content, String tag) {
        String open = "<" + tag + ">";
        String closeTag = "</" + tag + ">";
        int start = content.indexOf(open);
        if (start < 0) {
            return null;
        }
        start += open.length();
        int end = content.indexOf(closeTag, start);
        if (end < 0) {
            return null;
        }
        List<String> paths = new ArrayList<>();
        for (String line : content.substring(start, end).split("\n", -1)) {
            appendUnique(paths, line);
        }
        return paths;
    }
}
