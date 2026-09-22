package com.ragagent.llm.chat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 沙箱 write/edit 工具的实时行数进度（对照 Go internal/models/chat/sandbox_file_progress.go
 * 全文逐字移植，2026-09-23 随「阶段 7 前置接线」批落地）。
 *
 * <p>模型还在吐工具调用 JSON 时的活进度：path 一出现就上报，之后随 content 落地持续
 * {@code +N / -M}。SSE payload 只有统计量加一小段预览——<b>绝不携带文件正文</b>
 * （测试 {@code TestSandboxFileProgressWriteStreamsLineCount} 钉死 payload 无 {@code content} 键）。</p>
 *
 * <p>接线点在 {@link RemoteApiChat#processToolCallsDelta}（对照 Go openai_stream.go:561-571）：
 * 命中沙箱变更工具且本 delta 带 arguments 时喂给 {@link #feed}，产出的 payload 作为
 * tool_call 事件的 {@code arguments} 附加字段下发（首个"名字稳定"标记事件搭车一次，
 * 之后独立成事件）。payload 是 {@code Map}，经 {@code StreamResponse.data} 上的
 * {@code GoMapSerializer} 按 Go 的 map 键序（字典序、递归）输出，与 Go 的
 * {@code json.Marshal(map[string]any)} 线格式一致。</p>
 *
 * <p><b>字节语义</b>：Go 的 {@code len(string)} 是 UTF-8 字节数，Java 的 {@code String.length()}
 * 是 UTF-16 单元数——所有进 {@code bytes} / 节流判定的长度一律走 {@link #utf8Len}。</p>
 */
final class SandboxFileProgress {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 预览最多保留 10 行（对照 sandboxFilePreviewMaxLines；测试钉 15 行内容 → 预览恰 10 行）。 */
    private static final int SANDBOX_FILE_PREVIEW_MAX_LINES = 10;

    /** 对照 writeSandboxFileName；与 agent 侧 AgentToolNames.TOOL_WRITE_SANDBOX_FILE 同名（llm 包不反向依赖 agent 包，故本地声明）。 */
    static final String WRITE_SANDBOX_FILE = "write_sandbox_file";
    /** 对照 editSandboxFileName。 */
    static final String EDIT_SANDBOX_FILE = "edit_sandbox_file";

    /**
     * 仅字节数变化（一条长行迟迟不出换行）时的最小发射间隔；行数变化恒发射。
     *
     * <p>Go 是包级 var（{@code TestSandboxFileProgressEditPartialJSON} 置 0 再复原），
     * Java 用包内可写的静态字段保持同一测试手法——非 final、volatile，仅测试改写。</p>
     */
    static volatile Duration sandboxProgressMinInterval = Duration.ofMillis(120);

    private final String toolName;

    /** 流式字段抽取器（复用 thinking 工具那套，见 JsonFieldExtractor）。 */
    private JsonFieldExtractor pathEx;
    private JsonFieldExtractor contentEx;

    /** write：已积累的（反转义后）path 与 content。 */
    private String path = "";
    private String content = "";

    /** edit：原始 arguments 缓冲与它的 UTF-8 字节长（对照 len(p.buf)，增量维护避免每 delta 全量拷贝）。 */
    private String buf = "";
    private int bufBytes;

    /** 本轮统计（对照 added/removed/bytes/preview）。 */
    private int added;
    private int removed;
    private int bytes;
    private String preview = "";

    /** 上次发射时的快照与节流锚点（对照 last* 字段）。 */
    private String lastPath = "";
    private int lastAdded;
    private int lastRemoved;
    private int lastBytes;
    private String lastPreview = "";
    private Instant lastEmit = Instant.EPOCH;
    private boolean emitted;

    /** edit 解析节流（对照 lastParseAt/lastParseLen：增长不足 512 字节且不到间隔就跳过整段解析）。 */
    private Instant lastParseAt = Instant.EPOCH;
    private int lastParseLen;

    SandboxFileProgress(String toolName) {
        this.toolName = toolName;
    }

    /** 对照 isSandboxMutationTool：仅这两个沙箱写类工具出进度。 */
    static boolean isSandboxMutationTool(String name) {
        return WRITE_SANDBOX_FILE.equals(name) || EDIT_SANDBOX_FILE.equals(name);
    }

    /**
     * 消费一条 arguments 增量。返回非 null 表示 UI 应收到一份更新后的统计 payload
     * （对照 Go 的 {@code (map[string]any, bool)}：null 即 ok=false）。
     */
    Map<String, Object> feed(String delta) {
        if (delta == null || delta.isEmpty()) {
            return null;
        }
        switch (toolName) {
            case WRITE_SANDBOX_FILE -> feedWrite(delta);
            case EDIT_SANDBOX_FILE -> feedEdit(delta);
            default -> {
                return null;
            }
        }
        if (!shouldEmit()) {
            return null;
        }
        markEmitted();
        return payload();
    }

    /** 对照 feedWrite：path/content 双抽取器增量走，统计每条 delta 全量重算。 */
    private void feedWrite(String delta) {
        if (pathEx == null) {
            pathEx = new JsonFieldExtractor("path");
        }
        if (contentEx == null) {
            contentEx = new JsonFieldExtractor("content");
        }
        String chunk = pathEx.feed(delta);
        if (!chunk.isEmpty()) {
            path += chunk;
        }
        chunk = contentEx.feed(delta);
        if (!chunk.isEmpty()) {
            content += chunk;
        }
        added = countContentLines(content);
        removed = 0;
        bytes = utf8Len(content);
        preview = sandboxContentPreview(content);
    }

    /** 对照 feedEdit：不做流式 content 抽取，攒够 512 字节或过了间隔才整段解析部分 JSON。 */
    private void feedEdit(String delta) {
        buf += delta;
        bufBytes += utf8Len(delta);
        if (pathEx == null) {
            pathEx = new JsonFieldExtractor("path");
        }
        String chunk = pathEx.feed(delta);
        if (!chunk.isEmpty()) {
            path += chunk;
        }

        Instant now = Instant.now();
        if (lastParseLen > 0
                && bufBytes - lastParseLen < 512
                && Duration.between(lastParseAt, now).compareTo(sandboxProgressMinInterval) < 0) {
            return;
        }
        lastParseAt = now;
        lastParseLen = bufBytes;

        Map<String, Object> obj = parsePartialJsonObject(buf);
        if (obj == null) {
            return;
        }
        // path 优先信流式抽取器；抽取器没出（比如字段还没闭合）才退回解析结果
        if (obj.get("path") instanceof String parsedPath && !parsedPath.isEmpty() && path.isEmpty()) {
            path = parsedPath;
        }
        int[] stats = editArgsLineStats(obj);
        added = stats[0];
        removed = stats[1];
        bytes = bufBytes;
        preview = "";
    }

    /** 对照 shouldEmit：没东西不发；路径/行数变化恒发；纯字节/预览变化按最小间隔节流。 */
    private boolean shouldEmit() {
        if (path.isEmpty() && added == 0 && removed == 0 && bytes == 0) {
            return false;
        }
        boolean pathChanged = !path.equals(lastPath);
        boolean linesChanged = added != lastAdded || removed != lastRemoved;
        if (!emitted || pathChanged || linesChanged) {
            return true;
        }
        if (bytes == lastBytes && preview.equals(lastPreview)) {
            return false;
        }
        return Duration.between(lastEmit, Instant.now()).compareTo(sandboxProgressMinInterval) >= 0;
    }

    /** 对照 markEmitted：记录快照作为下一次"是否有变化"的基准。 */
    private void markEmitted() {
        emitted = true;
        lastPath = path;
        lastAdded = added;
        lastRemoved = removed;
        lastBytes = bytes;
        lastPreview = preview;
        lastEmit = Instant.now();
    }

    /**
     * 对照 payload：三个统计键恒在（0 也输出——Go 的 map 无 omitempty 语义），
     * path / preview 空则整键省略。
     */
    private Map<String, Object> payload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added_lines", added);
        out.put("removed_lines", removed);
        out.put("bytes", bytes);
        if (!path.isEmpty()) {
            out.put("path", path);
        }
        if (!preview.isEmpty()) {
            out.put("preview", preview);
        }
        return out;
    }

    /**
     * 对照 countContentLines：按 {@code \n} 计数，末尾无换行的残行也算一行。
     * 实录（Go 测试表）："" → 0；"hello" → 1；"hello\n" → 1；"hello\nworld" → 2；
     * "hello\nworld\n" → 2；"a\n\nb" → 3（空行也是一行）。
     */
    static int countContentLines(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        int n = 0;
        int idx = 0;
        while ((idx = s.indexOf('\n', idx)) >= 0) {
            n++;
            idx++;
        }
        if (s.charAt(s.length() - 1) != '\n') {
            n++;
        }
        return n;
    }

    /**
     * 对照 sandboxContentPreview：取前 10 行；整体以换行结尾时先去掉末尾空元素。
     * {@code split("\n", -1)} 才与 Go 的 {@code strings.Split} 一致（保留尾随空串）。
     */
    static String sandboxContentPreview(String s) {
        if (s.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>(List.of(s.split("\n", -1)));
        int end = lines.size();
        if (end > 0 && lines.get(end - 1).isEmpty()) {
            end--;
        }
        lines = lines.subList(0, end);
        if (lines.size() > SANDBOX_FILE_PREVIEW_MAX_LINES) {
            lines = lines.subList(0, SANDBOX_FILE_PREVIEW_MAX_LINES);
        }
        return String.join("\n", lines);
    }

    /**
     * 对照 parsePartialJSONObject：先原样解析；失败则闭合后再试；闭合等于原串（说明
     * 不是"缺尾巴"而是真畸形）→ null。输入整体是 {@code null} 字面量时 Jackson 返回
     * null map——与 Go 的 nil map 同路（Go feedEdit 里 {@code obj == nil} 照样早退）。
     */
    private static Map<String, Object> parsePartialJsonObject(String s) {
        Map<String, Object> m = tryUnmarshalObject(s);
        if (m != null) {
            return m;
        }
        // 输入是 JSON null 字面量时 Go 也是"成功但得 nil map"，feedEdit 对 nil map
        // 与解析失败的处理相同（早退），故这里两种 null 合一是等价的。
        String closed = closePartialJson(s);
        if (closed.equals(s)) {
            return null;
        }
        return tryUnmarshalObject(closed);
    }

    /** 解析失败（含 JSON null 字面量 → null 返回值）一律得 null。 */
    private static Map<String, Object> tryUnmarshalObject(String s) {
        try {
            return MAPPER.readValue(s, MAPPER.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 对照 closePartialJSON：闭合未终结的字符串与缺失的括号，让流式前缀可解析。
     * 结构字符都是 ASCII，按 UTF-8 字节扫描（多字节序列的高位字节不会撞上任何分支）。
     */
    static String closePartialJson(String s) {
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        boolean inString = false;
        boolean escaped = false;
        byte[] stack = new byte[in.length];
        int depth = 0;
        for (byte c : in) {
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> stack[depth++] = '}';
                case '[' -> stack[depth++] = ']';
                case '}', ']' -> {
                    if (depth > 0 && stack[depth - 1] == c) {
                        depth--;
                    }
                }
                default -> {
                }
            }
        }
        if (!inString && !escaped && depth == 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(in.length + depth + 2);
        b.append(s);
        if (escaped) {
            b.append('\\');
        }
        if (inString || escaped) {
            b.append('"');
        }
        for (int i = depth - 1; i >= 0; i--) {
            b.append((char) stack[i]);
        }
        return b.toString();
    }

    /**
     * 对照 editArgsLineStats：优先累计 {@code edits[]} 里每条 old/new 的行数；
     * 一次都没累计到（旧字段形态：顶层 old_string/new_string 或 oldText/newText）才退回顶层。
     * 返回 {@code [added, removed]}。
     */
    private static int[] editArgsLineStats(Map<String, Object> obj) {
        int added = 0;
        int removed = 0;
        if (obj.get("edits") instanceof List<?> edits) {
            for (Object raw : edits) {
                if (!(raw instanceof Map<?, ?> m)) {
                    continue;
                }
                String oldS = stringFromMap(m, "old_string", "oldText");
                String newS = stringFromMap(m, "new_string", "newText");
                if (oldS.isEmpty() && newS.isEmpty()) {
                    continue;
                }
                removed += countContentLines(oldS);
                added += countContentLines(newS);
            }
        }
        if (added == 0 && removed == 0) {
            String oldS = stringFromMap(obj, "old_string", "oldText");
            String newS = stringFromMap(obj, "new_string", "newText");
            removed = countContentLines(oldS);
            added = countContentLines(newS);
        }
        return new int[] { added, removed };
    }

    /** 对照 stringFromMap：按顺序找第一个存在的字符串型键。 */
    private static String stringFromMap(Map<?, ?> m, String... keys) {
        for (String key : keys) {
            if (m.get(key) instanceof String s) {
                return s;
            }
        }
        return "";
    }

    /** Go {@code len(string)} 的等价物：UTF-8 字节长。 */
    private static int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
