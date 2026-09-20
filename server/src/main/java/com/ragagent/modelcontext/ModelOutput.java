package com.ragagent.modelcontext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * 给 LLM 的紧凑、以 source 为中心的工具结果渲染（对照 Go internal/modelcontext
 * model_output.go，全文移植）。规范的 ToolResult.Output 保持不动，供 UI/日志/存储。
 */
final class ModelOutput {

    static final int MODEL_WEB_SEARCH_EVIDENCE_MAX_RUNES = 1500;
    static final int MODEL_WEB_FETCH_SUMMARY_MAX_RUNES = 4000;
    static final int MODEL_WEB_FETCH_CONTENT_MAX_RUNES = 8000;
    static final int MODEL_WEB_FETCH_TOTAL_MAX_RUNES = 16000;

    private ModelOutput() {
    }

    /** 工具结果的模型面渲染入口（对照 ModelOutput）。 */
    static String modelOutput(SourceRegistry r, ToolResult result) {
        if (result == null) {
            return "";
        }
        // 当前源工具的遗留格式输出同样是证据。仅回放注册绝不授予该资格
        if (result.isSuccess()) {
            r.registerLegacyToolReferences(result.getOutput(), true);
            ToolResult copy = copyOf(result);
            copy.setOutput(r.compactPublicCitations(result.getOutput(), true));
            result = copy;
        }
        String displayType = stringValue(result.getData(), "display_type");
        if ("web_fetch_results".equals(displayType)) {
            return modelWebFetchOutput(r, mapsValue(result.getData().get("results")), result.getOutput());
        }
        if (!result.isSuccess()) {
            return failedToolModelText(result.getOutput(), result.getError());
        }
        switch (displayType) {
            case "grep_results":
                return modelKnowledgeOutput(r, "keyword", mapsValue(result.getData().get("chunk_results")), result.getOutput());
            case "search_results":
                return modelKnowledgeOutput(r, "semantic", mapsValue(result.getData().get("results")), result.getOutput());
            case "knowledge_chunks_list":
                return modelKnowledgeChunksOutput(r, result.getData(), result.getOutput());
            case "document_info":
                return modelDocumentInfoOutput(r, mapsValue(result.getData().get("documents")), result.getOutput());
            case "graph_query_results":
                return modelKnowledgeOutput(r, "graph", mapsValue(result.getData().get("results")), result.getOutput());
            case "web_search_results":
                return modelWebSearchOutput(r, mapsValue(result.getData().get("results")), result.getOutput());
            case "database_query":
                return modelDatabaseQueryOutput(r, mapsValue(result.getData().get("rows")), result.getOutput());
            default:
                r.registerLabeledReferences(result.getOutput());
                registerStructuredReferences(r, result.getOutput());
                return r.compactKnownText(result.getOutput());
        }
    }

    private static ToolResult copyOf(ToolResult result) {
        ToolResult copy = new ToolResult();
        copy.setOutputFiles(result.getOutputFiles());
        copy.setSuccess(result.isSuccess());
        copy.setOutput(result.getOutput());
        copy.setData(result.getData());
        copy.setError(result.getError());
        copy.setImages(result.getImages());
        return copy;
    }

    /** 从 JSON 工具结果里显式标记的键下登记持久 ID（对照 registerStructuredReferences）。 */
    static void registerStructuredReferences(SourceRegistry r, String raw) {
        JsonNode value = GoJsonValues.parse(raw);
        if (value == null) {
            return;
        }
        walkStructured(r, "", value);
    }

    private static void walkStructured(SourceRegistry r, String key, JsonNode value) {
        if (value.isTextual()) {
            r.registerSourceIDByKey(key, value.asText(), true);
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                walkStructured(r, key, item);
            }
        } else if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                walkStructured(r, e.getKey(), e.getValue());
            }
        }
    }

    private static String modelDatabaseQueryOutput(SourceRegistry r, List<Map<String, JsonNode>> rows, String fallback) {
        for (Map<String, JsonNode> row : rows) {
            for (Map.Entry<String, JsonNode> e : row.entrySet()) {
                if (e.getValue().isTextual()) {
                    r.registerSourceIDByKey(e.getKey(), e.getValue().asText(), true);
                }
            }
        }
        return r.compactKnownText(fallback);
    }

    private static String modelDocumentInfoOutput(SourceRegistry r, List<Map<String, JsonNode>> rows, String fallback) {
        if (rows.isEmpty()) {
            return r.compactKnownText(fallback);
        }
        StringBuilder b = new StringBuilder();
        b.append("<documents>\n");
        int count = 0;
        for (Map<String, JsonNode> row : rows) {
            String knowledgeId = stringValue(row, "knowledge_id");
            String docHandle = r.registerDocument(knowledgeId);
            if (boolValue(row, "is_faq")) {
                String chunkId = stringValue(row, "faq_id");
                if (chunkId.isEmpty()) {
                    continue;
                }
                String title = SourceRegistry.firstNonEmpty(stringValue(row, "faq_question"), stringValue(row, "title"));
                SourceRegistry.ChunkReference ref = new SourceRegistry.ChunkReference();
                ref.chunkId = chunkId;
                ref.knowledgeId = knowledgeId;
                ref.documentTitle = title;
                ref.chunkType = "faq";
                String chunkHandle = r.registerChunk(ref);
                b.append("  <document id=\"").append(SourceRegistry.escapeAttr(docHandle)).append("\" type=\"faq\">\n");
                b.append("    <chunk id=\"").append(SourceRegistry.escapeAttr(chunkHandle)).append("\" type=\"faq\">\n");
                if (!title.isEmpty()) {
                    b.append("      <question>").append(SourceRegistry.escapeText(title)).append("</question>\n");
                }
                for (String answer : stringSliceValue(row.get("faq_answers"))) {
                    b.append("      <answer>").append(SourceRegistry.escapeText(answer)).append("</answer>\n");
                }
                b.append("    </chunk>\n  </document>\n");
                count++;
                continue;
            }

            if (docHandle.isEmpty()) {
                continue;
            }
            b.append("  <document id=\"").append(SourceRegistry.escapeAttr(docHandle)).append("\"");
            String title = stringValue(row, "title");
            if (!title.isEmpty()) {
                b.append(" title=\"").append(SourceRegistry.escapeAttr(title)).append("\"");
            }
            String docType = stringValue(row, "type");
            if (!docType.isEmpty()) {
                b.append(" type=\"").append(SourceRegistry.escapeAttr(docType)).append("\"");
            }
            String fileType = stringValue(row, "file_type");
            if (!fileType.isEmpty()) {
                b.append(" file_type=\"").append(SourceRegistry.escapeAttr(fileType)).append("\"");
            }
            b.append(" chunk_count=\"").append(intValue(row, "chunk_count")).append("\">\n");
            String description = stringValue(row, "description");
            if (!description.isEmpty()) {
                b.append("    <description>").append(SourceRegistry.escapeText(description)).append("</description>\n");
            }
            b.append("  </document>\n");
            count++;
        }
        b.append("</documents>");
        if (count == 0) {
            return r.compactKnownText(fallback);
        }
        return b.toString();
    }

    /** 一个待渲染 chunk 的中间态（Go modelChunk）。 */
    private static final class ModelChunk {
        String handle;
        String docHandle;
        String kbHandle;
        String title;
        String metadata;
        String chunkType;
        int index;
        String view;
        String match;
        String content;
        String question;
        List<String> answers = List.of();
        List<Map<String, JsonNode>> images = List.of();
        int inputOrder;
    }

    private static String modelKnowledgeOutput(SourceRegistry r, String mode, List<Map<String, JsonNode>> rows, String fallback) {
        List<ModelChunk> chunks = new ArrayList<>(rows.size());
        for (int idx = 0; idx < rows.size(); idx++) {
            Map<String, JsonNode> row = rows.get(idx);
            String chunkId = SourceRegistry.firstNonEmpty(
                    stringValue(row, "chunk_id"), stringValue(row, "faq_id"), stringValue(row, "id"));
            String knowledgeId = stringValue(row, "knowledge_id");
            String kbId = SourceRegistry.firstNonEmpty(stringValue(row, "knowledge_base_id"), stringValue(row, "knowledge_base"));
            String title = SourceRegistry.firstNonEmpty(stringValue(row, "knowledge_title"), stringValue(row, "title"));
            if (chunkId.isEmpty()) {
                continue;
            }
            String chunkType = stringValue(row, "chunk_type");
            if (!stringValue(row, "faq_id").isEmpty() && chunkType.isEmpty()) {
                chunkType = "faq";
            }
            int chunkIndex = intValue(row, "chunk_index");
            if (chunkIndex == 0) {
                chunkIndex = intValue(row, "index");
            }
            SourceRegistry.ChunkReference ref = new SourceRegistry.ChunkReference();
            ref.chunkId = chunkId;
            ref.knowledgeId = knowledgeId;
            ref.knowledgeBaseId = kbId;
            ref.documentTitle = title;
            ref.chunkIndex = chunkIndex;
            ref.chunkType = chunkType;
            String chunkHandle = r.registerChunk(ref);
            ModelChunk mc = new ModelChunk();
            mc.handle = chunkHandle;
            mc.docHandle = r.registerDocument(knowledgeId);
            mc.kbHandle = r.registerKnowledgeBase(kbId);
            mc.title = title;
            mc.metadata = stringValue(row, "knowledge_metadata");
            mc.chunkType = chunkType;
            mc.index = chunkIndex;
            mc.view = viewForRow(row, mode);
            mc.match = SourceRegistry.firstNonEmpty(stringValue(row, "match_snippet"), stringValue(row, "matched_content"));
            mc.content = stringValue(row, "content");
            mc.question = SourceRegistry.firstNonEmpty(stringValue(row, "faq_question"), stringValue(row, "faq_standard_question"));
            mc.answers = stringSliceValue(row.get("faq_answers"));
            List<Map<String, JsonNode>> images = mapsValue(row.get("images"));
            mc.images = images == null ? List.of() : images;
            mc.inputOrder = idx;
            chunks.add(mc);
        }
        if (chunks.isEmpty()) {
            return r.compactKnownText(fallback);
        }
        return renderKnowledgeChunks(mode, chunks);
    }

    private static String viewForRow(Map<String, JsonNode> row, String mode) {
        if (!stringValue(row, "content").isEmpty()) {
            return "full";
        }
        if ("deep_read".equals(mode)) {
            return "full";
        }
        return "match";
    }

    private static String modelKnowledgeChunksOutput(SourceRegistry r, Map<String, Object> data, String fallback) {
        List<Map<String, JsonNode>> rows = mapsValue(data.get("chunks"));
        String title = stringValue(data, "knowledge_title");
        String knowledgeId = stringValue(data, "knowledge_id");
        for (Map<String, JsonNode> row : rows) {
            if (stringValue(row, "knowledge_id").isEmpty()) {
                row.put("knowledge_id", TextNode.valueOf(knowledgeId));
            }
            if (stringValue(row, "knowledge_title").isEmpty()) {
                row.put("knowledge_title", TextNode.valueOf(title));
            }
        }
        String output = modelKnowledgeOutput(r, "deep_read", rows, fallback);
        if (rows.isEmpty()) {
            return output;
        }
        int remaining = intValue(data, "total_chunks") - intValue(data, "fetched_chunks");
        if (remaining > 0) {
            if (output.endsWith("</retrieval>")) {
                output = output.substring(0, output.length() - "</retrieval>".length());
            }
            output += "  <pagination remaining=\"" + remaining + "\" page=\"" + intValue(data, "page")
                    + "\" page_size=\"" + intValue(data, "page_size") + "\" />\n</retrieval>";
        }
        return output;
    }

    private static String renderKnowledgeChunks(String mode, List<ModelChunk> chunks) {
        // 可变 doc 分组（Go 的局部 docGroup struct）
        final class DocGroup {
            final String handle;
            final String kbHandle;
            final String title;
            String metadata;
            final int order;
            final List<ModelChunk> groupChunks = new ArrayList<>();

            DocGroup(String handle, String kbHandle, String title, String metadata, int order) {
                this.handle = handle;
                this.kbHandle = kbHandle;
                this.title = title;
                this.metadata = metadata;
                this.order = order;
            }
        }
        Map<String, DocGroup> groupsByKey = new HashMap<>();
        List<DocGroup> groups = new ArrayList<>();
        for (ModelChunk chunk : chunks) {
            String key = chunk.docHandle.isEmpty() ? "chunk:" + chunk.handle : chunk.docHandle;
            DocGroup group = groupsByKey.get(key);
            if (group == null) {
                group = new DocGroup(chunk.docHandle, chunk.kbHandle, chunk.title, chunk.metadata, chunk.inputOrder);
                groupsByKey.put(key, group);
                groups.add(group);
            } else if (group.metadata.isEmpty()) {
                group.metadata = chunk.metadata;
            }
            group.groupChunks.add(chunk);
        }
        groups.sort(Comparator.comparingInt(g -> g.order));

        StringBuilder b = new StringBuilder();
        b.append("<retrieval type=\"knowledge\" mode=\"").append(SourceRegistry.escapeAttr(mode)).append("\">\n");
        for (DocGroup group : groups) {
            b.append("  <document");
            if (!group.handle.isEmpty()) {
                b.append(" id=\"").append(SourceRegistry.escapeAttr(group.handle)).append("\"");
            }
            if (!group.kbHandle.isEmpty()) {
                b.append(" kb=\"").append(SourceRegistry.escapeAttr(group.kbHandle)).append("\"");
            }
            if (!group.title.isEmpty()) {
                b.append(" title=\"").append(SourceRegistry.escapeAttr(group.title)).append("\"");
            }
            b.append(">\n");
            if (!group.metadata.isEmpty()) {
                b.append("    <metadata>").append(SourceRegistry.escapeText(group.metadata)).append("</metadata>\n");
            }
            for (ModelChunk chunk : group.groupChunks) {
                b.append("    <chunk id=\"").append(chunk.handle).append("\" index=\"").append(chunk.index)
                        .append("\" view=\"").append(chunk.view).append("\"");
                if (!chunk.chunkType.isEmpty()) {
                    b.append(" type=\"").append(SourceRegistry.escapeAttr(chunk.chunkType)).append("\"");
                }
                b.append(">\n");
                if (!chunk.question.isEmpty()) {
                    b.append("      <question>").append(SourceRegistry.escapeText(chunk.question)).append("</question>\n");
                }
                if (!chunk.match.isEmpty()) {
                    b.append("      <match>").append(SourceRegistry.escapeText(chunk.match)).append("</match>\n");
                }
                if (!chunk.content.isEmpty()) {
                    b.append("      <content>").append(SourceRegistry.escapeText(chunk.content)).append("</content>\n");
                }
                for (String answer : chunk.answers) {
                    b.append("      <answer>").append(SourceRegistry.escapeText(answer)).append("</answer>\n");
                }
                for (Map<String, JsonNode> image : chunk.images) {
                    String imageURL = stringValue(image, "url");
                    if (imageURL.isEmpty()) {
                        continue;
                    }
                    String caption = stringValue(image, "caption");
                    b.append("      ![").append(caption).append("](").append(imageURL).append(")\n");
                }
                b.append("    </chunk>\n");
            }
            b.append("  </document>\n");
        }
        b.append("</retrieval>");
        return b.toString();
    }

    private static String modelWebSearchOutput(SourceRegistry r, List<Map<String, JsonNode>> rows, String fallback) {
        if (rows.isEmpty()) {
            return r.compactKnownText(fallback);
        }
        StringBuilder b = new StringBuilder();
        b.append("<retrieval type=\"web\" mode=\"search\" trust=\"untrusted\">\n");
        int evidenceFields = 2;
        for (Map<String, JsonNode> row : rows) {
            if (boolValue(row, "page_verified")) {
                evidenceFields = 3;
                break;
            }
        }
        int perEvidence = Math.min(MODEL_WEB_SEARCH_EVIDENCE_MAX_RUNES, 16000 / Math.max(1, rows.size() * evidenceFields));
        int count = 0;
        for (Map<String, JsonNode> row : rows) {
            String rawURL = stringValue(row, "url");
            if (rawURL.isEmpty()) {
                continue;
            }
            String handle = r.registerWeb(rawURL, stringValue(row, "title"));
            b.append("  <page id=\"").append(handle).append("\" title=\"")
                    .append(SourceRegistry.escapeAttr(stringValue(row, "title"))).append("\">\n");
            b.append("    <evidence type=\"search_summary\" verified=\"false\" />\n");
            b.append("    <domain>").append(SourceRegistry.escapeText(urlHostname(rawURL))).append("</domain>\n");
            String snippet = stringValue(row, "snippet");
            if (!snippet.isEmpty()) {
                writeLimitedWebEvidence(b, "match", snippet, perEvidence, null);
            }
            String content = stringValue(row, "content");
            if (!content.isEmpty() && !content.equals(stringValue(row, "snippet"))) {
                writeLimitedWebEvidence(b, "content", content, perEvidence, null);
            }
            String age = stringValue(row, "age");
            if (!age.isEmpty()) {
                b.append("    <age>").append(SourceRegistry.escapeText(age)).append("</age>\n");
            }
            if (boolValue(row, "page_verified")) {
                writeLimitedWebEvidence(b, "fetched_content", stringValue(row, "page_content"), perEvidence, null);
                b.append("    <page_fetch status=\"success\" verified=\"true\" />\n");
                writeWebPageFileHint(b, row);
                if (stringValue(row, "full_output_path").isEmpty()) {
                    b.append("    <continue url=\"").append(handle)
                            .append("\" next_offset=\"0\">Read with web_fetch for more page content.</continue>\n");
                }
            } else if ("failed".equals(stringValue(row, "page_status"))) {
                b.append("    <page_fetch status=\"failed\">").append(SourceRegistry.escapeText(stringValue(row, "page_error")))
                        .append("</page_fetch>\n");
            }
            String published = stringValue(row, "published_at");
            if (!published.isEmpty()) {
                b.append("    <published>").append(SourceRegistry.escapeText(published)).append("</published>\n");
            }
            b.append("  </page>\n");
            count++;
        }
        b.append("</retrieval>");
        if (count == 0) {
            return r.compactKnownText(fallback);
        }
        return b.toString();
    }

    private static String modelWebFetchOutput(SourceRegistry r, List<Map<String, JsonNode>> rows, String fallback) {
        if (rows.isEmpty()) {
            return r.compactKnownText(fallback);
        }
        StringBuilder b = new StringBuilder();
        b.append("<retrieval type=\"web\" mode=\"fetch\" trust=\"untrusted\">\n");
        int count = 0;
        int successCount = 0;
        int failedCount = 0;
        // 给每个成功页分配份额，包括遗留存储结果
        int successPages = 0;
        for (Map<String, JsonNode> row : rows) {
            String status = stringValue(row, "status");
            if ("success".equals(status) || status.isEmpty()) {
                successPages++;
            }
        }
        int perPage = MODEL_WEB_FETCH_TOTAL_MAX_RUNES / Math.max(1, successPages);
        for (Map<String, JsonNode> row : rows) {
            String rawURL = stringValue(row, "url");
            if (rawURL.isEmpty()) {
                continue;
            }
            String title = stringValue(row, "title");
            String handle = r.registerWeb(rawURL, title);
            String status = stringValue(row, "status");
            if (status.isEmpty()) {
                status = "success";
            }
            b.append("  <page id=\"").append(handle).append("\" status=\"").append(SourceRegistry.escapeAttr(status)).append("\"");
            if (!title.isEmpty()) {
                b.append(" title=\"").append(SourceRegistry.escapeAttr(title)).append("\"");
            }
            if ("success".equals(status)) {
                b.append(" view=\"excerpt\">\n");
                writeWebPageFileHint(b, row);
                int[] remainingEvidence = {perPage};
                successCount++;
                String summary = stringValue(row, "summary");
                if (!summary.isEmpty()) {
                    writeLimitedWebEvidence(b, "summary", summary, MODEL_WEB_FETCH_SUMMARY_MAX_RUNES, remainingEvidence);
                }
                String summaryStatus = stringValue(row, "summary_status");
                if ("failed".equals(summaryStatus)) {
                    b.append("    <summary_error code=\"").append(SourceRegistry.escapeAttr(stringValue(row, "summary_error_code")))
                            .append("\">").append(SourceRegistry.escapeText(stringValue(row, "summary_error_message")))
                            .append("</summary_error>\n");
                }
                String content = stringValue(row, "raw_content");
                if (!content.isEmpty()) {
                    int limit = Math.min(MODEL_WEB_FETCH_CONTENT_MAX_RUNES, remainingEvidence[0]);
                    writeLimitedWebEvidence(b, "content", content, limit, remainingEvidence);
                    int runeCount = content.codePointCount(0, content.length());
                    int shown = Math.min(runeCount, limit);
                    int offset = intValue(row, "offset");
                    int total = intValue(row, "content_length");
                    if (total == 0) {
                        total = offset + runeCount;
                    }
                    b.append("    <range offset=\"").append(offset).append("\" returned_chars=\"").append(shown)
                            .append("\" content_length=\"").append(total).append("\" />\n");
                    if (boolValue(row, "truncated") || shown < runeCount) {
                        b.append("    <continue url=\"").append(handle).append("\" next_offset=\"").append(offset + shown)
                                .append("\">Call web_fetch with this url and offset to read more.</continue>\n");
                    }
                }
            } else {
                b.append(" retryable=\"").append(boolValue(row, "retryable")).append("\"");
                String errorCode = stringValue(row, "error_code");
                if (!errorCode.isEmpty()) {
                    b.append(" error_code=\"").append(SourceRegistry.escapeAttr(errorCode)).append("\"");
                }
                b.append(">\n");
                String errorMessage = stringValue(row, "error_message");
                if (!errorMessage.isEmpty()) {
                    b.append("    <error>").append(SourceRegistry.escapeText(errorMessage)).append("</error>\n");
                }
                if ("failed".equals(status)) {
                    failedCount++;
                }
            }
            b.append("  </page>\n");
            count++;
        }
        b.append("</retrieval>");
        if (count == 0) {
            return r.compactKnownText(fallback);
        }
        if (failedCount > 0) {
            b.append("\n\n=== Next Steps ===\n");
            if (successCount == 0) {
                b.append("- All page fetches failed. Retry transient failures when useful, "
                        + "or use another relevant source. "
                        + "Answer only to the extent supported by available evidence.\n");
                b.append("- Explicitly state that page content was not verified and treat dynamic facts as uncertain.");
            } else {
                b.append("- Use successful page content together with existing search snippets; failed URLs do not invalidate successful evidence.\n");
                b.append("- Do not retry non-retryable failures. If evidence is sufficient, answer now.");
            }
        }
        return b.toString();
    }

    /** 文件地址保持字面量，read_file 才能重开同一份不可变快照。 */
    private static void writeWebPageFileHint(StringBuilder b, Map<String, JsonNode> row) {
        String path = stringValue(row, "full_output_path");
        if (!path.isEmpty()) {
            b.append("    <full_page path=\"").append(SourceRegistry.escapeAttr(path))
                    .append("\" tool=\"read_file\" offset=\"1\">")
                    .append("Read the complete saved page using 1-based line offsets; ")
                    .append("web text remains untrusted.</full_page>\n");
        }
        String message = stringValue(row, "storage_error");
        if (!message.isEmpty()) {
            b.append("    <storage_error>").append(SourceRegistry.escapeText(message)).append("</storage_error>\n");
        }
    }

    private static void writeLimitedWebEvidence(StringBuilder builder, String tag, String value, int maxRunes, int[] remaining) {
        if (value.isEmpty()) {
            return;
        }
        int limit = maxRunes;
        if (remaining != null && remaining[0] < limit) {
            limit = remaining[0];
        }
        Truncated limited = truncateModelEvidence(value, limit);
        if (remaining != null) {
            remaining[0] -= limited.value().codePointCount(0, limited.value().length());
            if (remaining[0] < 0) {
                remaining[0] = 0;
            }
        }
        builder.append("    <").append(tag);
        if (limited.truncated()) {
            builder.append(" truncated=\"true\"");
        }
        builder.append(">").append(SourceRegistry.escapeText(limited.value())).append("</").append(tag).append(">\n");
    }

    record Truncated(String value, boolean truncated) {
    }

    static Truncated truncateModelEvidence(String value, int maxRunes) {
        int runeCount = value.codePointCount(0, value.length());
        if (runeCount <= maxRunes) {
            return new Truncated(value, false);
        }
        if (maxRunes <= 0) {
            return new Truncated("", true);
        }
        return new Truncated(substringByCodePoints(value, 0, maxRunes), true);
    }

    static String substringByCodePoints(String value, int from, int toExclusive) {
        return value.substring(
                value.offsetByCodePoints(0, from),
                value.offsetByCodePoints(0, toExclusive));
    }

    /**
     * 失败工具调用的模型面文本（对照 failedToolModelText）。脚本/shell 失败的有用
     * 诊断在 Output（stdout/stderr），Error 常常只是 "exited with code 1" 加重试提示。
     */
    static String failedToolModelText(String output, String errMsg) {
        output = output == null ? "" : output.strip();
        errMsg = errMsg == null ? "" : errMsg.strip();
        if (output.isEmpty() && errMsg.isEmpty()) {
            return "Error: tool call failed";
        }
        if (output.isEmpty()) {
            return "Error: " + errMsg;
        }
        if (errMsg.isEmpty() || output.contains(errMsg)) {
            return output;
        }
        return output + "\n\nError: " + errMsg;
    }

    // ---- map 取值助手（Go mapsValue/stringValue/intValue/boolValue/stringSliceValue）----

    private static JsonNode toNode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode n) {
            return n;
        }
        return GoJsonValues.MAPPER.valueToTree(value);
    }

    /**
     * Go mapsValue：marshal → unmarshal 进 []map[string]interface{}；非数组或元素
     * 非对象 → nil。行用可变 LinkedHashMap（knowledge_chunks_list 会就地补键）。
     */
    static List<Map<String, JsonNode>> mapsValue(Object value) {
        JsonNode tree = toNode(value);
        if (tree == null || tree.isNull() || !tree.isArray()) {
            return null;
        }
        List<Map<String, JsonNode>> rows = new ArrayList<>(tree.size());
        for (JsonNode item : tree) {
            if (!item.isObject()) {
                return null;
            }
            Map<String, JsonNode> row = new LinkedHashMap<>();
            var fields = item.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                row.put(e.getKey(), e.getValue());
            }
            rows.add(row);
        }
        return rows;
    }

    static String stringValue(Map<String, ?> values, String key) {
        if (values == null) {
            return "";
        }
        Object value = values.get(key);
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof JsonNode n && n.isTextual()) {
            return n.asText();
        }
        return "";
    }

    static int intValue(Map<String, ?> values, String key) {
        if (values == null) {
            return 0;
        }
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof JsonNode n && n.isNumber()) {
            return n.intValue();
        }
        return 0;
    }

    static boolean boolValue(Map<String, ?> values, String key) {
        if (values == null) {
            return false;
        }
        Object value = values.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof JsonNode n && n.isBoolean()) {
            return n.asBoolean();
        }
        return false;
    }

    /** Go stringSliceValue：marshal → unmarshal 进 []string；失败 → nil（Java 空列表 = 零次循环）。 */
    static List<String> stringSliceValue(JsonNode value) {
        if (value == null || value.isNull() || !value.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                return List.of();
            }
            values.add(item.asText());
        }
        return values;
    }

    /** Go：u, err := url.Parse(rawURL) 后取 Hostname（本包只用于 domain 行）。 */
    static String urlHostname(String rawURL) {
        String rest = rawURL;
        int schemeEnd = rest.indexOf("://");
        if (schemeEnd >= 0) {
            rest = rest.substring(schemeEnd + 3);
        }
        int pathStart = rest.indexOf('/');
        String authority = pathStart < 0 ? rest : rest.substring(0, pathStart);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int hash = authority.indexOf('#');
        if (hash >= 0) {
            authority = authority.substring(0, hash);
        }
        int colon = authority.lastIndexOf(':');
        // 多冒号视为 IPv6 字面量，不做端口切分
        if (colon >= 0 && authority.indexOf(':') == colon) {
            authority = authority.substring(0, colon);
        }
        return authority;
    }
}
