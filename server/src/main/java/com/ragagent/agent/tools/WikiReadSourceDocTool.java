package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.DocChunkSupport.ChunkPage;
import com.ragagent.agent.tools.DocChunkSupport.ImageInfoCollector;
import com.ragagent.agent.tools.DocChunkSupport.KnowledgeInfoReader;
import com.ragagent.agent.tools.DocChunkSupport.KnowledgeInfoView;
import com.ragagent.agent.tools.DocChunkSupport.PagedChunks;
import com.ragagent.knowledge.domain.Chunk;

/**
 * wiki_read_source_doc 工具。
 *
 * <p>{@code searchTargets != null} 即启用 scope 强制（空 scope 失败关闭）。
 * 正则用 {@code (?i)+query} 编译；正则方言在交集语法外行为不同
 * （编译失败文案差异列入报告已知差异）。</p>
 */
public class WikiReadSourceDocTool extends BaseTool {

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "knowledge_id": {
                  "type": "string",
                  "description": "The short dN source document ID from the <sources> block"
                },
                "query": {
                  "type": "string",
                  "description": "Optional: A regex query to filter the document chunks. Use this to find specific quotes or details efficiently. Remember to double-escape backslashes for JSON: write \\"C\\\\\\\\+\\\\\\\\+\\" (NOT \\"C\\\\+\\\\+\\") and \\"\\\\\\\\d+\\" (NOT \\"\\\\d+\\")."
                },
                "start_chunk_index": {
                  "type": "integer",
                  "description": "Optional: The starting chunk index (1-based) to read a specific range."
                },
                "end_chunk_index": {
                  "type": "integer",
                  "description": "Optional: The ending chunk index (1-based) to read a specific range. Must be >= start_chunk_index."
                }
              },
              "required": ["knowledge_id"]
            }""";

    private static final String DESCRIPTION =
            "Read or search within a specific source document to drill down for details omitted from the wiki.\n"
                    + "Provide the knowledge_id from the <sources> block.\n"
                    + "You can EITHER search using a regex query OR fetch a specific contiguous range of chunks using start_chunk_index and end_chunk_index (useful for expanding context around a known chunk).\n"
                    + "If neither query nor range is provided, it returns the beginning of the document.";

    private final KnowledgeInfoReader knowledgeReader;
    private final PagedChunks pagedChunks;
    private final ImageInfoCollector imageCollector;
    private final com.ragagent.agent.tools.SearchTarget.SearchTargets searchTargets;

    public WikiReadSourceDocTool(KnowledgeInfoReader knowledgeReader, PagedChunks pagedChunks,
            ImageInfoCollector imageCollector,
            com.ragagent.agent.tools.SearchTarget.SearchTargets searchTargets) {
        super(ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC, DESCRIPTION, SCHEMA_JSON);
        this.knowledgeReader = knowledgeReader;
        this.pagedChunks = pagedChunks;
        this.imageCollector = imageCollector;
        this.searchTargets = searchTargets;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();

        String knowledgeID = args.path("knowledge_id").asText("").trim();
        if (knowledgeID.isEmpty()) {
            return failure("knowledge_id is required");
        }
        String query = args.path("query").asText("");
        int startChunkIndex = args.path("start_chunk_index").asInt(0);
        int endChunkIndex = args.path("end_chunk_index").asInt(0);

        KnowledgeInfoView knowledge;
        if (searchTargets != null) {
            try {
                SearchAuth.authorizeKnowledgeInSearchTargets(
                        searchTargets, knowledgeID, DocChunkSupport.asScopeReader(knowledgeReader));
            } catch (RuntimeException e) {
                return failure("Document not found: " + e.getMessage());
            }
            // 授权用适配视图丢了富字段，重新取一次（seam 无状态，结果一致）
            knowledge = knowledgeReader == null ? null : knowledgeReader.byIdOnly(knowledgeID);
        } else {
            try {
                knowledge = knowledgeReader == null ? null : knowledgeReader.byIdOnly(knowledgeID);
            } catch (RuntimeException e) {
                return failure("Document not found: " + e.getMessage());
            }
        }
        if (knowledge == null) {
            return failure("Document not found: knowledge service returned an empty result");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<source_document>\n<metadata>\n");
        sb.append("<title>").append(knowledge.title()).append("</title>\n");
        sb.append("<knowledge_id>").append(knowledgeID).append("</knowledge_id>\n");

        boolean hasRange = startChunkIndex > 0;
        Pattern re = null;

        if (hasRange) {
            if (endChunkIndex < startChunkIndex) {
                endChunkIndex = startChunkIndex + 10; // 默认窗口
            }
            if (endChunkIndex - startChunkIndex > 50) {
                endChunkIndex = startChunkIndex + 50; // 最多 50 块
            }
            sb.append("<chunk_range start=\"").append(startChunkIndex).append("\" end=\"")
                    .append(endChunkIndex).append("\"/>\n");
        } else if (!query.isEmpty()) {
            try {
                re = Pattern.compile("(?i)" + query);
            } catch (java.util.regex.PatternSyntaxException e) {
                return failure("Invalid regex query '" + query + "': " + e.getMessage());
            }
            sb.append("<query>").append(query).append("</query>\n");
        }

        int matchCount = 0;
        int pageSize = 100;
        int page = 1;
        if (hasRange) {
            page = (startChunkIndex - 1) / pageSize + 1;
        }

        StringBuilder chunksOutput = new StringBuilder();
        List<Map<String, Object>> formattedChunks = new ArrayList<>();
        long totalChunks = 0;
        boolean reachedMax = false;

        Chunk prevChunk = null;
        boolean forceOutputNext = false;
        Set<Integer> outputtedIndices = new HashSet<>();

        final String knowledgeTitle = knowledge.title();
        final String knowledgeBaseId = knowledge.knowledgeBaseId();
        final long tenantId = knowledge.tenantId();

        java.util.function.BiConsumer<Chunk, String> appendFormattedChunk = (chunk, content) -> {
            if (chunk == null) {
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunk_id", chunk.getId());
            m.put("chunk_index", chunk.getChunkIndex());
            m.put("chunk_type", chunk.getChunkType());
            m.put("content", content);
            m.put("knowledge_id", knowledgeID);
            m.put("knowledge_base", knowledgeBaseId);
            m.put("knowledge_title", knowledgeTitle);
            formattedChunks.add(m);
        };

        final Pattern reFinal = re;
        final int startFinal = startChunkIndex;
        final int endFinal = endChunkIndex;
        final boolean hasRangeFinal = hasRange;

        outer:
        while (true) {
            ChunkPage result;
            try {
                result = pagedChunks.listPaged(tenantId, knowledgeID, page, pageSize);
            } catch (RuntimeException e) {
                return failure("Failed to list chunks: " + e.getMessage());
            }
            List<Chunk> chunks = result == null ? null : result.chunks();
            long total = result == null ? 0 : result.total();

            if (page == 1) {
                totalChunks = total;
            }

            if (chunks == null || chunks.isEmpty()) {
                break;
            }

            DocChunkSupport.enrichChunkImageInfo(imageCollector, tenantId, chunks);

            for (Chunk c : chunks) {
                int chunkNum = c.getChunkIndex() + 1;
                String chunkContent = DocChunkSupport.enrichChunkContent(c);

                if (hasRangeFinal) {
                    if (chunkNum < startFinal) {
                        continue;
                    }
                    if (chunkNum > endFinal) {
                        reachedMax = true;
                        break outer;
                    }
                    chunksOutput.append("<chunk index=\"").append(chunkNum)
                            .append("\" type=\"range\">\n").append(chunkContent).append("\n</chunk>\n");
                    appendFormattedChunk.accept(c, chunkContent);
                    matchCount++;
                    continue;
                }

                boolean isMatch = reFinal == null || reFinal.matcher(chunkContent).find();

                if (isMatch) {
                    matchCount++;

                    if (reFinal != null && prevChunk != null
                            && !outputtedIndices.contains(prevChunk.getChunkIndex())) {
                        String prevContent = DocChunkSupport.enrichChunkContent(prevChunk);
                        chunksOutput.append("<chunk index=\"").append(prevChunk.getChunkIndex() + 1)
                                .append("\" type=\"context_before\">\n").append(prevContent)
                                .append("\n</chunk>\n");
                        appendFormattedChunk.accept(prevChunk, prevContent);
                        outputtedIndices.add(prevChunk.getChunkIndex());
                    }

                    if (!outputtedIndices.contains(c.getChunkIndex())) {
                        String matchAttr = reFinal != null ? " type=\"match\"" : "";
                        chunksOutput.append("<chunk index=\"").append(c.getChunkIndex() + 1)
                                .append("\"").append(matchAttr).append(">\n")
                                .append(chunkContent).append("\n</chunk>\n");
                        appendFormattedChunk.accept(c, chunkContent);
                        outputtedIndices.add(c.getChunkIndex());
                    }

                    if (reFinal != null) {
                        forceOutputNext = true;
                    }
                } else if (forceOutputNext) {
                    if (!outputtedIndices.contains(c.getChunkIndex())) {
                        chunksOutput.append("<chunk index=\"").append(c.getChunkIndex() + 1)
                                .append("\" type=\"context_after\">\n").append(chunkContent)
                                .append("\n</chunk>\n");
                        appendFormattedChunk.accept(c, chunkContent);
                        outputtedIndices.add(c.getChunkIndex());
                    }
                    forceOutputNext = false;
                }

                prevChunk = c;

                if (reFinal == null && matchCount >= 10) {
                    break;
                }
                if (reFinal != null && matchCount >= 20) {
                    break;
                }
            }

            if (hasRangeFinal && reachedMax) {
                break;
            }

            if (!hasRangeFinal) {
                if (reFinal == null && matchCount >= 10) {
                    break;
                }
                if (reFinal != null && matchCount >= 20) {
                    reachedMax = true;
                    break;
                }
            }

            if ((long) page * pageSize >= total) {
                break;
            }
            page++;
        }

        sb.append("<total_chunks>").append(totalChunks).append("</total_chunks>\n</metadata>\n");

        if (matchCount > 0) {
            sb.append("<chunks count=\"").append(matchCount).append("\">\n");
            sb.append(chunksOutput);
            sb.append("</chunks>\n");
        } else {
            sb.append("<chunks count=\"0\" />\n");
        }

        if (reachedMax) {
            sb.append("<message>Reached maximum limit for fetching chunks in a single call. "
                    + "Please refine your query or range if needed.</message>\n");
        } else if (matchCount == 0) {
            if (hasRangeFinal) {
                sb.append("<message>No chunks found in the specified range.</message>\n");
            } else if (reFinal != null) {
                sb.append("<message>No chunks matched your query in this document.</message>\n");
            } else {
                sb.append("<message>Document has no text chunks available.</message>\n");
            }
        } else if (!hasRangeFinal && reFinal == null) {
            sb.append("<message>No query or range provided. Showing the first 10 chunks as a preview.</message>\n");
        }

        sb.append("</source_document>");

        ToolResult toolResult = new ToolResult();
        toolResult.setSuccess(true);
        toolResult.setOutput(sb.toString());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("display_type", "knowledge_chunks_list");
        data.put("knowledge_id", knowledgeID);
        data.put("knowledge_title", knowledgeTitle);
        data.put("total_chunks", totalChunks);
        data.put("fetched_chunks", formattedChunks.size());
        data.put("chunks", formattedChunks);
        toolResult.setData(data);
        return toolResult;
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
