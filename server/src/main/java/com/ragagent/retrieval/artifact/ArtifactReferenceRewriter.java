package com.ragagent.retrieval.artifact;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 产物引用改写（对照 Go internal/handler/session/artifact_reference.go 全文，
 * 检索/收尾批 2026-09-22 翻译）：把模型最终答案里指向沙箱产物的 Markdown
 * 链接/图片目标归一成能存活的引用形态——有资源目录时用 resource:// handle，
 * 否则用聊天可读的 {@code sandbox:<name>}。字节契约：contracts/w5g3c-artifacts.tsv。
 */
public final class ArtifactReferenceRewriter {

    private ArtifactReferenceRewriter() {
    }

    /** 代码段切分：围栏代码与行内代码里的目标永不改写。 */
    private static final Pattern FENCED_OR_INLINE_CODE =
            Pattern.compile("(?s)(```.*?```|~~~.*?~~~|`[^`\\n]*`)");
    /** 已带 scheme 的目标（http://、resource://、data:…）。 */
    private static final Pattern SCHEME_RE = Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*:");
    /** 可选标题后缀（`file.png "caption"`）。 */
    private static final Pattern TITLE_SUFFIX_RE =
            Pattern.compile("(?s)^(.*?)(\\s+(?:\"[^\"]*\"|'[^']*'))$");

    /** MessageArtifact 的消费面（types.MessageArtifact 的子集）。 */
    public record Artifact(String fileName, String url) {
    }

    /** 对照 types.ParseResourcePath + BuildResourcePath 的退化实现：catalog 句柄
     *  形如 {@code resource://<handle>} 原样返回；其余 null。 */
    public interface ResourcePathResolver {
        /** URL → 规范 resource:// 引用；不是资源路径返回 null。 */
        String parseResourcePath(String url);
    }

    /** 默认实现：识别 {@code resource://<22 字符 handle>}（Go ResourceHandleLength）。 */
    public static final ResourcePathResolver DEFAULT_RESOLVER = url -> {
        if (url == null || !url.startsWith("resource://")) {
            return null;
        }
        String handle = url.substring("resource://".length());
        // Go ParseResourcePath 要求 handle 恰好 22 字符
        return handle.length() == 22 ? "resource://" + handle : null;
    };

    /**
     * 对照 rewriteArtifactReferences（artifact_reference.go L56-79）：先按代码段
     * 切分，非代码段逐链接改写。
     */
    public static String rewriteArtifactReferences(String content, List<Artifact> artifacts,
            ResourcePathResolver resolver) {
        if (content == null || content.isEmpty() || artifacts == null || artifacts.isEmpty()) {
            return content;
        }
        ResourcePathResolver res = resolver != null ? resolver : DEFAULT_RESOLVER;
        Map<String, String> byName = artifactRefByName(artifacts, res);
        if (byName.isEmpty()) {
            return content;
        }

        List<String> parts = new ArrayList<>();
        Matcher codeMatcher = FENCED_OR_INLINE_CODE.matcher(content);
        List<String> code = new ArrayList<>();
        int last = 0;
        while (codeMatcher.find()) {
            parts.add(content.substring(last, codeMatcher.start()));
            code.add(codeMatcher.group());
            last = codeMatcher.end();
        }
        parts.add(content.substring(last));

        if (parts.size() == 1) {
            return rewriteSegment(content, byName, res);
        }
        StringBuilder out = new StringBuilder(content.length());
        for (int i = 0; i < parts.size(); i++) {
            out.append(rewriteSegment(parts.get(i), byName, res));
            if (i < code.size()) {
                out.append(code.get(i));
            }
        }
        return out.toString();
    }

    /**
     * 对照 rewriteArtifactReferencesInSegment（L88-127）：目标按括号配对扫描
     * （文件名常含空格与括号，正则会截断），`](` 前须有同行 `[`。
     */
    static String rewriteSegment(String segment, Map<String, String> byName,
            ResourcePathResolver res) {
        if (segment.isEmpty() || !segment.contains("](")) {
            return segment;
        }
        StringBuilder out = new StringBuilder(segment.length());
        int cursor = 0;
        while (cursor < segment.length()) {
            // Java indexOf(str, from) 已是绝对下标（Go 的 Index(segment[cursor:]) 才需加 cursor）
            int closeBracket = segment.indexOf("](", cursor);
            if (closeBracket < 0) {
                break;
            }
            int open = closeBracket + 1;

            int[] dest = scanLinkDestination(segment, open);
            boolean ok = dest != null && hasLinkLabelBefore(segment, closeBracket);
            if (!ok) {
                out.append(segment, cursor, open + 1);
                cursor = open + 1;
                continue;
            }
            String inner = segment.substring(open + 1, dest[0]);
            String[] dt = splitDestinationTitle(inner);
            String ref = lookupArtifactRef(dt[0], byName,
                    markdownImageBefore(segment, closeBracket), res);
            if (ref == null) {
                out.append(segment, cursor, dest[0] + 1);
                cursor = dest[0] + 1;
                continue;
            }
            out.append(segment, cursor, open + 1);
            out.append(ref);
            out.append(dt[1]);
            out.append(')');
            cursor = dest[0] + 1;
        }
        out.append(segment.substring(cursor));
        return out.toString();
    }

    /**
     * 对照 scanLinkDestination（L132-148）：返回 {闭括号下标}；换行即失败；
     * 支持嵌套一层括号计数。
     */
    private static int[] scanLinkDestination(String text, int openIndex) {
        int depth = 1;
        for (int i = openIndex + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                return null;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return new int[] {i};
                }
            }
        }
        return null;
    }

    /** 对照 hasLinkLabelBefore：`](` 前须有同行 `[`。 */
    private static boolean hasLinkLabelBefore(String text, int closeBracketIndex) {
        for (int i = closeBracketIndex - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return false;
            }
            if (c == '[') {
                return true;
            }
        }
        return false;
    }

    /** 对照 splitDestinationTitle：dest 与标题分离，标题带前导空白原样回填。 */
    private static String[] splitDestinationTitle(String inner) {
        Matcher m = TITLE_SUFFIX_RE.matcher(inner);
        if (m.matches()) {
            return new String[] {m.group(1).strip(), m.group(2)};
        }
        return new String[] {inner.strip(), ""};
    }

    /** 对照 markdownImageBefore：`]` 前最近的 `[` 前是否是 `!`（即图片语法）。 */
    private static boolean markdownImageBefore(String text, int closeBracketIndex) {
        for (int i = closeBracketIndex - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return false;
            }
            if (c == '[') {
                return i > 0 && text.charAt(i - 1) == '!';
            }
        }
        return false;
    }

    /** 对照 looksLikeSandboxOutputPath：./、/ 前缀剥离后是否指向 output 路径。 */
    static boolean looksLikeSandboxOutputPath(String candidate) {
        String c = candidate.strip();
        if (c.startsWith("./")) {
            c = c.substring(2);
        }
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        return c.startsWith("workspace/output/") || c.startsWith("output/");
    }

    /**
     * 对照 lookupArtifactRef（L192-237）：sandbox://、sandbox: 前缀剥离；已带
     * scheme 的真 URL 不动；百分号解码；裸名仅图片（或 output 路径形态）改写；
     * 目录前缀取 path.Base 后查表。
     */
    static String lookupArtifactRef(String destination, Map<String, String> byName,
            boolean image, ResourcePathResolver res) {
        String candidate = destination == null ? "" : destination.strip();
        if (candidate.isEmpty()) {
            return null;
        }
        if (candidate.endsWith(">")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.startsWith("<")) {
            candidate = candidate.substring(1);
        }

        boolean hadSandboxPrefix = false;
        for (String prefix : List.of("sandbox://", "sandbox:")) {
            if (candidate.length() >= prefix.length()
                    && candidate.regionMatches(true, 0, prefix, 0, prefix.length())) {
                candidate = candidate.substring(prefix.length());
                hadSandboxPrefix = true;
                break;
            }
        }
        if (!hadSandboxPrefix && SCHEME_RE.matcher(candidate).find()) {
            return null;
        }
        candidate = pathUnescape(candidate);
        candidate = candidate.strip();
        if (!hadSandboxPrefix && !image && !looksLikeSandboxOutputPath(candidate)) {
            return null;
        }
        candidate = pathBase(candidate);
        if (candidate.isEmpty() || candidate.equals(".") || candidate.equals("/")) {
            return null;
        }
        return byName.get(candidate);
    }

    /** 对照 url.PathUnescape：只解 %XX，'+' 保持原义；坏转义原样返回（Go err → 不变）。 */
    private static String pathUnescape(String s) {
        if (!s.contains("%")) {
            return s;
        }
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** 对照 path.Base（GoPath.clean 已有；这里只处理正斜杠路径的末段）。 */
    private static String pathBase(String p) {
        if (p.isEmpty()) {
            return ".";
        }
        String s = p;
        while (s.endsWith("/") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        int slash = s.lastIndexOf('/');
        String base = slash >= 0 ? s.substring(slash + 1) : s;
        return base.isEmpty() ? "/" : base;
    }

    /**
     * 对照 artifactRefByName：文件名 → 引用；重名保留首个（后出现的文件不抢）。
     */
    static Map<String, String> artifactRefByName(List<Artifact> artifacts,
            ResourcePathResolver res) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (Artifact a : artifacts) {
            String name = a.fileName() == null ? "" : a.fileName().strip();
            if (name.isEmpty() || byName.containsKey(name)) {
                continue;
            }
            byName.put(name, artifactReference(a, res));
        }
        return byName;
    }

    /** 对照 artifactReference：catalog handle 优先，否则 chat-only sandbox: 形态。 */
    static String artifactReference(Artifact artifact, ResourcePathResolver res) {
        String handle = res.parseResourcePath(artifact.url());
        if (handle != null) {
            return handle;
        }
        return "sandbox:" + (artifact.fileName() == null ? "" : artifact.fileName().strip());
    }
}
