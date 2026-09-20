package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.DocChunkSupport.ChunkPage;
import com.ragagent.agent.tools.DocChunkSupport.KnowledgeInfoReader;
import com.ragagent.agent.tools.DocChunkSupport.KnowledgeInfoView;
import com.ragagent.agent.tools.DocChunkSupport.PagedChunks;
import com.ragagent.agent.tools.FaqSnippet.SimilarQuestionsDisplay;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.FaqChunkMetadata;

/**
 * get_document_info 工具（对照 Go {@code get_document_info.go}，逐字移植）。
 * Go 的 goroutine 并发在 Java 顺序执行（输出按入参序组装，结果等价；
 * metadata 多键的 Go map 随机序列入已知差异——探针只用单键）。
 */
public class GetDocumentInfoTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "knowledge_ids": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "Short dN document IDs for regular documents"
                },
                "faq_ids": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "Short cN FAQ chunk IDs from retrieval results. Use instead of knowledge_ids for a single FAQ Q&A."
                }
              }
            }""";

    private static final String DESCRIPTION =
            "Retrieve detailed metadata information about documents.\n"
                    + "\n"
                    + "## When to Use\n"
                    + "\n"
                    + "Use this tool when:\n"
                    + "- Need to understand document basic information (title, type, size, etc.)\n"
                    + "- Check if document exists and is available\n"
                    + "- Batch query metadata for multiple documents\n"
                    + "- Understand document processing status\n"
                    + "\n"
                    + "Do not use when:\n"
                    + "- Need document content (use knowledge_search)\n"
                    + "- Need specific text chunks (search results already contain full content)\n"
                    + "\n"
                    + "\n"
                    + "## Returned Information\n"
                    + "\n"
                    + "- Basic info: title, description, source type\n"
                    + "- File info: filename, type, size\n"
                    + "- Processing status: whether processed, chunk count\n"
                    + "- Metadata: custom tags and properties\n"
                    + "\n"
                    + "\n"
                    + "## Notes\n"
                    + "\n"
                    + "- Concurrent query for multiple documents provides better performance\n"
                    + "- Returns complete document metadata, not just title\n"
                    + "- Can check document processing status (parse_status)\n"
                    + "\n"
                    + "## IDs\n"
                    + "- knowledge_ids: regular documents, using the short dN IDs from retrieval results\n"
                    + "- faq_ids: individual FAQ entries, using the short cN chunk IDs. Returns the standard question and answers, not the container title.";

    private final KnowledgeInfoReader knowledgeReader;
    private final Function<String, Chunk> chunkById;
    private final PagedChunks pagedChunks;
    private final SearchTarget.SearchTargets searchTargets;

    public GetDocumentInfoTool(KnowledgeInfoReader knowledgeReader, Function<String, Chunk> chunkById,
            PagedChunks pagedChunks, SearchTarget.SearchTargets searchTargets) {
        super(ToolDefinitions.TOOL_GET_DOCUMENT_INFO, DESCRIPTION, SCHEMA_JSON);
        this.knowledgeReader = knowledgeReader;
        this.chunkById = chunkById;
        this.pagedChunks = pagedChunks;
        this.searchTargets = searchTargets;
    }

    private static final class DocInfo {
        KnowledgeInfoView knowledge;
        Chunk chunk;
        FaqChunkMetadata faqMeta;
        int chunkCount;
        String err;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();

        List<String> knowledgeIDs = jsonStringList(args.get("knowledge_ids"));
        List<String> faqIDs = jsonStringList(args.get("faq_ids"));
        if (knowledgeIDs.isEmpty() && faqIDs.isEmpty()) {
            return failure("knowledge_ids or faq_ids is required (non-empty array)");
        }

        Map<String, DocInfo> results = new HashMap<>();

        for (String faqIDRaw : faqIDs) {
            String faqID = faqIDRaw == null ? "" : faqIDRaw.trim();
            if (faqID.isEmpty()) {
                continue;
            }
            DocInfo info = new DocInfo();
            Chunk chunk;
            try {
                chunk = SearchAuth.authorizeDomainChunkInSearchTargets(searchTargets, faqID,
                        chunkById, DocChunkSupport.asScopeReader(knowledgeReader));
            } catch (RuntimeException e) {
                info.err = "FAQ entry is not accessible: " + e.getMessage();
                results.put("faq:" + faqID, info);
                continue;
            }
            FaqChunkMetadata meta = null;
            if (chunk != null && "faq".equals(chunk.getChunkType())) {
                meta = FaqSnippet.faqMetadata(chunk);
            }
            info.chunk = chunk;
            info.faqMeta = meta;
            info.chunkCount = 1;
            results.put("faq:" + faqID, info);
        }

        for (String knowledgeID : knowledgeIDs) {
            DocInfo info = new DocInfo();
            KnowledgeInfoView knowledge;
            try {
                SearchAuth.authorizeKnowledgeInSearchTargets(searchTargets, knowledgeID,
                        DocChunkSupport.asScopeReader(knowledgeReader));
                knowledge = knowledgeReader == null ? null : knowledgeReader.byIdOnly(knowledgeID);
            } catch (RuntimeException e) {
                info.err = "failed to get document info: " + e.getMessage();
                results.put(knowledgeID, info);
                continue;
            }
            if (knowledge == null) {
                info.err = "failed to get document info: document " + knowledgeID + " not found: empty result";
                results.put(knowledgeID, info);
                continue;
            }
            long total;
            try {
                ChunkPage page = pagedChunks == null
                        ? null
                        : pagedChunks.listPaged(knowledge.tenantId(), knowledgeID, 1, 1);
                total = page == null ? 0 : page.total();
            } catch (RuntimeException e) {
                info.err = "failed to get document info: " + e.getMessage();
                results.put(knowledgeID, info);
                continue;
            }
            info.knowledge = knowledge;
            info.chunkCount = (int) total;
            results.put(knowledgeID, info);
        }

        int requested = knowledgeIDs.size() + faqIDs.size();
        List<DocInfo> successDocs = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String knowledgeID : knowledgeIDs) {
            DocInfo result = results.get(knowledgeID);
            if (result == null) {
                errors.add(knowledgeID + ": not found");
                continue;
            }
            if (result.err != null) {
                errors.add(knowledgeID + ": " + result.err);
            } else if (result.knowledge != null) {
                successDocs.add(result);
            }
        }
        for (String faqIDRaw : faqIDs) {
            String faqID = faqIDRaw == null ? "" : faqIDRaw.trim();
            if (faqID.isEmpty()) {
                continue;
            }
            DocInfo result = results.get("faq:" + faqID);
            if (result == null) {
                errors.add("faq:" + faqID + ": not found");
                continue;
            }
            if (result.err != null) {
                errors.add("faq:" + faqID + ": " + result.err);
            } else if (result.chunk != null) {
                successDocs.add(result);
            }
        }

        if (successDocs.isEmpty()) {
            return failure("Failed to retrieve any document info. Errors: " + goSliceString(errors));
        }

        StringBuilder output = new StringBuilder();
        output.append("=== Document Info ===\n\n");
        output.append(String.format("Successfully retrieved %d / %d entries\n\n",
                successDocs.size(), requested));

        if (!errors.isEmpty()) {
            output.append("=== Partial Failures ===\n");
            for (String errMsg : errors) {
                output.append("  - ").append(errMsg).append('\n');
            }
            output.append('\n');
        }

        List<Map<String, Object>> formattedDocs = new ArrayList<>();
        for (int i = 0; i < successDocs.size(); i++) {
            DocInfo doc = successDocs.get(i);
            output.append(String.format("[Entry #%d]\n", i + 1));

            if (doc.chunk != null) {
                Map<String, Object> formatted = formatFAQEntryInfo(output, doc.chunk, doc.faqMeta);
                formattedDocs.add(formatted);
                continue;
            }

            KnowledgeInfoView k = doc.knowledge;
            output.append(String.format("  ID:           %s\n", k.id()));
            output.append(String.format("  Title:        %s\n", k.title()));

            if (k.description() != null && !k.description().isEmpty()) {
                output.append(String.format("  Description:  %s\n", k.description()));
            }

            output.append(String.format("  Source:       %s\n", formatSource(k.type(), k.source())));

            if (k.fileName() != null && !k.fileName().isEmpty()) {
                output.append(String.format("  File Name:    %s\n", k.fileName()));
                output.append(String.format("  File Type:    %s\n", k.fileType()));
                output.append(String.format("  File Size:    %s\n", formatFileSize(k.fileSize())));
            }

            output.append(String.format("  Parse Status: %s\n", formatParseStatus(k.parseStatus())));
            output.append(String.format("  Chunk Count:  %d\n", doc.chunkCount));

            if (k.metadata() != null && !k.metadata().isEmpty()) {
                output.append("  Metadata:\n");
                for (Map.Entry<String, Object> e : k.metadata().entrySet()) {
                    output.append(String.format("    - %s: %s\n", e.getKey(),
                            DocChunkSupport.goFmtV(e.getValue())));
                }
            }

            output.append('\n');

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("knowledge_id", k.id());
            formatted.put("title", k.title());
            formatted.put("description", k.description());
            formatted.put("type", k.type());
            formatted.put("source", k.source());
            formatted.put("file_name", k.fileName());
            formatted.put("file_type", k.fileType());
            formatted.put("file_size", k.fileSize());
            formatted.put("parse_status", k.parseStatus());
            formatted.put("chunk_count", doc.chunkCount);
            formatted.put("metadata", DocChunkSupport.knowledgeMetadataMap(k.metadata()));
            formatted.put("is_faq", false);
            formattedDocs.add(formatted);
        }

        String firstTitle = "";
        if (!formattedDocs.isEmpty()) {
            Object t = formattedDocs.get(0).get("title");
            if (t instanceof String s) {
                firstTitle = s;
            }
        }

        ToolResult toolResult = new ToolResult();
        toolResult.setSuccess(true);
        toolResult.setOutput(output.toString());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documents", formattedDocs);
        data.put("total_docs", successDocs.size());
        data.put("requested", requested);
        data.put("errors", errors.isEmpty() ? null : errors);
        data.put("display_type", "document_info");
        data.put("title", firstTitle);
        toolResult.setData(data);
        return toolResult;
    }

    private static Map<String, Object> formatFAQEntryInfo(StringBuilder output, Chunk chunk,
            FaqChunkMetadata meta) {
        String title = FaqSnippet.faqStandardQuestion(chunk);
        if ((title == null || title.isEmpty()) && meta != null) {
            title = meta.standardQuestion == null ? "" : meta.standardQuestion.trim();
        }
        if (title == null || title.isEmpty()) {
            title = "FAQ Entry";
        }

        output.append(String.format("  FAQ ID:       %s\n", chunk.getId()));
        output.append(String.format("  Question:     %s\n", title));
        if (chunk.getKnowledgeId() != null && !chunk.getKnowledgeId().isEmpty()) {
            output.append(String.format("  Container ID: %s\n", chunk.getKnowledgeId()));
        }
        if (meta != null && meta.answers != null && !meta.answers.isEmpty()) {
            output.append("  Answers:\n");
            for (String ans : meta.answers) {
                output.append(String.format("    - %s\n", ans));
            }
        }
        if (meta != null && meta.similarQuestions != null && !meta.similarQuestions.isEmpty()) {
            SimilarQuestionsDisplay display =
                    FaqSnippet.truncateSimilarQuestionsForDisplay(meta.similarQuestions);
            output.append("  Similar Questions:\n");
            for (String sq : display.display()) {
                output.append(String.format("    - %s\n", sq));
            }
            if (display.omitted() > 0) {
                output.append(String.format("    ... and %d more omitted\n", display.omitted()));
            }
        }
        output.append('\n');

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("faq_id", chunk.getId());
        entry.put("knowledge_id", chunk.getKnowledgeId());
        entry.put("title", title);
        entry.put("faq_question", title);
        entry.put("type", "faq");
        entry.put("is_faq", true);
        entry.put("chunk_count", 1);
        if (meta != null) {
            if (meta.answers != null && !meta.answers.isEmpty()) {
                entry.put("faq_answers", meta.answers);
            }
            FaqSnippet.appendSimilarQuestionsToChunkData(entry, meta.similarQuestions);
        }
        return entry;
    }

    static String formatSource(String knowledgeType, String source) {
        if ("file".equals(knowledgeType)) {
            return "File Upload";
        }
        if ("url".equals(knowledgeType)) {
            return "URL: " + source;
        }
        if ("passage".equals(knowledgeType)) {
            return "Text Input";
        }
        return knowledgeType;
    }

    static String formatFileSize(long size) {
        if (size == 0) {
            return "Unknown";
        }
        final long unit = 1024;
        if (size < unit) {
            return size + " B";
        }
        long div = unit;
        int exp = 0;
        for (long n = size / unit; n >= unit; n /= unit) {
            div *= unit;
            exp++;
        }
        return String.format(Locale.US, "%.1f %cB", (double) size / div, "KMGTPE".charAt(exp));
    }

    static String formatParseStatus(String status) {
        switch (status == null ? "" : status) {
            case "pending":
                return "Pending";
            case "processing":
                return "Processing";
            case "completed":
            case "success":
                return "Completed";
            case "failed":
                return "Failed";
            default:
                return status;
        }
    }

    private static List<String> jsonStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item.isTextual()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    /** Go 的 %v 打印 []string：" [a b c]"。 */
    private static String goSliceString(List<String> items) {
        return "[" + String.join(" ", items) + "]";
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
