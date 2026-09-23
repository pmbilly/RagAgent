package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.DocChunkSupport.ChunkPage;
import com.ragagent.agent.tools.DocChunkSupport.ImageInfoCollector;
import com.ragagent.agent.tools.DocChunkSupport.ImageInfoView;
import com.ragagent.agent.tools.DocChunkSupport.KnowledgeInfoReader;
import com.ragagent.agent.tools.DocChunkSupport.KnowledgeInfoView;
import com.ragagent.agent.tools.DocChunkSupport.PagedChunks;
import com.ragagent.knowledge.domain.Chunk;

/**
 * list_knowledge_chunks 工具（对照 Go {@code list_knowledge_chunks.go}，逐字移植）。
 * faq_id/chunk_id 单块路径经 {@code chunkById} seam（对照 GetChunkByIDOnly）；
 * 图片富化经 {@link ImageInfoCollector}（对照 CollectImageInfoByChunkIDs）。
 */
public class ListKnowledgeChunksTool extends BaseTool {

    /** 键序对照 Go GenerateSchema 输出（字母序：properties < type；属性名与字段键均字母序）。 */
    private static final String SCHEMA_JSON = """
            {
              "properties": {
                "chunk_id": {
                  "description": "Short cN ID for one non-FAQ chunk",
                  "type": "string"
                },
                "faq_id": {
                  "description": "Short cN FAQ chunk ID. Use for FAQ hits instead of the parent dN document ID.",
                  "type": "string"
                },
                "knowledge_id": {
                  "description": "Short dN document ID to list all chunks",
                  "type": "string"
                },
                "limit": {
                  "default": 20,
                  "description": "Chunks per page when using knowledge_id (default 20, max 100)",
                  "maximum": 100,
                  "minimum": 1,
                  "type": "integer"
                },
                "offset": {
                  "default": 0,
                  "description": "Start position when using knowledge_id (default 0)",
                  "minimum": 0,
                  "type": "integer"
                }
              },
              "type": "object"
            }""";

    private static final String DESCRIPTION =
            "Retrieve full chunk content for a document or a single FAQ entry.\n"
                    + "\n"
                    + "## Use After grep_chunks or knowledge_search:\n"
                    + "- **FAQ hit** (type faq): list_knowledge_chunks(faq_id=\"cN\") — reads that one FAQ chunk with answers from metadata.\n"
                    + "- **Document hit**: list_knowledge_chunks(knowledge_id=\"dN\") — pages through all chunks.\n"
                    + "\n"
                    + "## Parameters (provide exactly one id target):\n"
                    + "- faq_id (optional): Short cN ID for an FAQ chunk from grep_chunks / knowledge_search.\n"
                    + "- chunk_id (optional): Short cN ID for a single non-FAQ chunk.\n"
                    + "- knowledge_id (optional): Short dN document ID to page through all chunks.\n"
                    + "- limit / offset: Only for knowledge_id paging (default limit 20, max 100).\n"
                    + "\n"
                    + "## Output:\n"
                    + "Full chunk content. FAQ entries include <faq> with <answer> from metadata.";

    private final KnowledgeInfoReader knowledgeReader;
    private final Function<String, Chunk> chunkById;
    private final PagedChunks pagedChunks;
    private final ImageInfoCollector imageCollector;
    private final SearchTarget.SearchTargets searchTargets;

    public ListKnowledgeChunksTool(KnowledgeInfoReader knowledgeReader,
            Function<String, Chunk> chunkById, PagedChunks pagedChunks,
            ImageInfoCollector imageCollector, SearchTarget.SearchTargets searchTargets) {
        super(ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS, DESCRIPTION, SCHEMA_JSON);
        this.knowledgeReader = knowledgeReader;
        this.chunkById = chunkById;
        this.pagedChunks = pagedChunks;
        this.imageCollector = imageCollector;
        this.searchTargets = searchTargets;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();

        String chunkID = args.path("faq_id").asText("").trim();
        if (chunkID.isEmpty()) {
            chunkID = args.path("chunk_id").asText("").trim();
        }
        if (!chunkID.isEmpty()) {
            return executeByChunkID(chunkID);
        }

        String knowledgeID = args.path("knowledge_id").asText("").trim();
        if (knowledgeID.isEmpty()) {
            return failure("one of faq_id, chunk_id, or knowledge_id is required");
        }

        KnowledgeInfoView knowledge;
        try {
            SearchAuth.authorizeKnowledgeInSearchTargets(searchTargets, knowledgeID,
                    DocChunkSupport.asScopeReader(knowledgeReader));
            knowledge = knowledgeReader == null ? null : knowledgeReader.byIdOnly(knowledgeID);
        } catch (RuntimeException e) {
            return failure("Knowledge is not accessible: " + e.getMessage());
        }
        if (knowledge == null) {
            return failure("Knowledge is not accessible: document " + knowledgeID + " not found: empty result");
        }

        long effectiveTenantID = knowledge.tenantId();

        int chunkLimit = 20;
        if (args.path("limit").asInt(0) > 0) {
            chunkLimit = args.path("limit").asInt(0);
        }
        int offset = 0;
        if (args.path("offset").asInt(0) > 0) {
            offset = args.path("offset").asInt(0);
        }
        if (offset < 0) {
            offset = 0;
        }

        int page = offset / chunkLimit + 1;
        int pageSize = chunkLimit;

        ChunkPage result;
        try {
            result = pagedChunks == null ? null
                    : pagedChunks.listPaged(effectiveTenantID, knowledgeID, page, pageSize);
        } catch (RuntimeException e) {
            return failure("failed to list chunks: " + e.getMessage());
        }
        if (result == null || result.chunks() == null) {
            return failure("chunk query returned no data");
        }
        List<Chunk> chunks = result.chunks();
        long totalChunks = result.total();
        int fetched = chunks.size();

        // 越界指引
        if (fetched == 0 && totalChunks > 0 && offset >= totalChunks) {
            long suggestedOffset = totalChunks - chunkLimit;
            if (suggestedOffset < 0) {
                suggestedOffset = 0;
            }
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(String.format(
                    "offset %d is out of range: document has only %d chunks (valid offset range: 0..%d)."
                            + " Retry with offset=%d (or any value < %d).",
                    offset, totalChunks, totalChunks - 1, suggestedOffset, totalChunks));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("knowledge_id", knowledgeID);
            data.put("total_chunks", totalChunks);
            data.put("requested_offset", offset);
            data.put("requested_limit", chunkLimit);
            data.put("suggested_offset", suggestedOffset);
            r.setData(data);
            return r;
        }

        // 图片富化（惰性）
        if (fetched > 0) {
            DocChunkSupport.enrichChunkImageInfo(imageCollector, effectiveTenantID, chunks);
        }

        String knowledgeTitle = lookupKnowledgeTitle(knowledgeID);
        String output = buildOutput(knowledgeID, knowledgeTitle, totalChunks, fetched, chunks);

        List<Map<String, Object>> formattedChunks = new ArrayList<>();
        for (int idx = 0; idx < chunks.size(); idx++) {
            Chunk c = chunks.get(idx);
            Map<String, Object> chunkData = new LinkedHashMap<>();
            chunkData.put("seq", idx + 1);
            chunkData.put("chunk_id", c.getId());
            chunkData.put("chunk_index", c.getChunkIndex());
            chunkData.put("content", nz(c.getContent()));
            chunkData.put("chunk_type", c.getChunkType());
            chunkData.put("knowledge_id", nz(c.getKnowledgeId()));
            chunkData.put("knowledge_base", nz(c.getKnowledgeBaseId()));
            chunkData.put("start_at", c.getStartAt());
            chunkData.put("end_at", c.getEndAt());
            chunkData.put("parent_chunk_id", nz(c.getParentChunkId()));

            FaqSnippet.appendFaqChunkData(chunkData, c);
            FaqSnippet.normalizeFaqChunkDataMap(chunkData, c);

            String imageInfo = c.getImageInfo();
            if (imageInfo != null && !imageInfo.isEmpty()) {
                List<ImageInfoView> imageInfos = DocChunkSupport.parseImageInfoList(imageInfo);
                if (imageInfos != null && !imageInfos.isEmpty()) {
                    List<Map<String, String>> imageList = new ArrayList<>();
                    for (ImageInfoView img : imageInfos) {
                        Map<String, String> imgData = new LinkedHashMap<>();
                        if (img.url() != null && !img.url().isEmpty()) {
                            imgData.put("url", img.url());
                        }
                        if (img.caption() != null && !img.caption().isEmpty()) {
                            imgData.put("caption", img.caption());
                        }
                        if (img.ocrText() != null && !img.ocrText().isEmpty()) {
                            imgData.put("ocr_text", img.ocrText());
                        }
                        if (!imgData.isEmpty()) {
                            imageList.add(imgData);
                        }
                    }
                    if (!imageList.isEmpty()) {
                        chunkData.put("images", imageList);
                    }
                }
            }

            formattedChunks.add(chunkData);
        }

        ToolResult toolResult = new ToolResult();
        toolResult.setSuccess(true);
        toolResult.setOutput(output);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("display_type", "knowledge_chunks_list");
        data.put("knowledge_id", knowledgeID);
        data.put("knowledge_title", knowledgeTitle);
        data.put("total_chunks", totalChunks);
        data.put("fetched_chunks", fetched);
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("chunks", formattedChunks);
        toolResult.setData(data);
        return toolResult;
    }

    /** 对照 executeByChunkID：faq_id / chunk_id 单块路径。 */
    private ToolResult executeByChunkID(String chunkID) {
        Chunk chunk;
        try {
            chunk = SearchAuth.authorizeDomainChunkInSearchTargets(searchTargets, chunkID,
                    chunkById, DocChunkSupport.asScopeReader(knowledgeReader));
        } catch (RuntimeException e) {
            return failure("chunk is not accessible: " + e.getMessage());
        }
        if (chunk == null) {
            return failure("chunk is not accessible: chunk " + chunkID + " not found: empty result");
        }

        List<Chunk> chunks = List.of(chunk);
        String imageInfo = chunk.getImageInfo();
        if (imageInfo == null || imageInfo.isEmpty()) {
            long effectiveTenantID = searchTargets == null
                    ? 0
                    : searchTargets.getTenantIdForKb(chunk.getKnowledgeBaseId());
            if (effectiveTenantID > 0 && imageCollector != null) {
                Map<String, String> infoMap = imageCollector.collect(effectiveTenantID,
                        List.of(chunk.getId()));
                if (infoMap != null) {
                    String merged = infoMap.get(chunk.getId());
                    if (merged != null && !merged.isEmpty()) {
                        chunk.setImageInfo(merged);
                    }
                }
            }        }

        String knowledgeTitle = lookupKnowledgeTitle(chunk.getKnowledgeId());
        String output = buildOutput(chunk.getKnowledgeId(), knowledgeTitle, 1, 1, chunks);

        Map<String, Object> chunkData = new LinkedHashMap<>();
        chunkData.put("seq", 1);
        chunkData.put("chunk_id", chunk.getId());
        chunkData.put("chunk_index", chunk.getChunkIndex());
        chunkData.put("content", nz(chunk.getContent()));
        chunkData.put("chunk_type", chunk.getChunkType());
        chunkData.put("knowledge_id", nz(chunk.getKnowledgeId()));
        chunkData.put("knowledge_base", nz(chunk.getKnowledgeBaseId()));
        FaqSnippet.appendFaqChunkData(chunkData, chunk);
        FaqSnippet.normalizeFaqChunkDataMap(chunkData, chunk);
        List<Map<String, Object>> formattedChunks = List.of(chunkData);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("display_type", "knowledge_chunks_list");
        data.put("knowledge_id", chunk.getKnowledgeId());
        data.put("knowledge_title", knowledgeTitle);
        data.put("total_chunks", 1L);
        data.put("fetched_chunks", 1);
        data.put("page", 1);
        data.put("page_size", 1);
        data.put("chunks", formattedChunks);
        data.put("faq_id", chunk.getId());
        data.put("single_chunk", true);
        String q = FaqSnippet.faqStandardQuestion(chunk);
        if (q != null && !q.isEmpty()) {
            data.put("faq_question", q);
        }

        ToolResult toolResult = new ToolResult();
        toolResult.setSuccess(true);
        toolResult.setOutput(output);
        toolResult.setData(data);
        return toolResult;
    }

    /** 对照 lookupKnowledgeTitle：trim 后返回（失败/无服务 → ""）。 */
    private String lookupKnowledgeTitle(String knowledgeID) {
        if (knowledgeReader == null) {
            return "";
        }
        KnowledgeInfoView knowledge;
        try {
            knowledge = knowledgeReader.byIdOnly(knowledgeID);
        } catch (RuntimeException e) {
            return "";
        }
        if (knowledge == null || knowledge.title() == null) {
            return "";
        }
        return knowledge.title().trim();
    }

    /** 对照 buildOutput：XML 输出。 */
    private String buildOutput(String knowledgeID, String knowledgeTitle, long total,
            int fetched, List<Chunk> chunks) {
        StringBuilder b = new StringBuilder();

        String titleAttr = "";
        if (knowledgeTitle != null && !knowledgeTitle.isEmpty()) {
            titleAttr = String.format(" title=\"%s\"", knowledgeTitle);
        }
        b.append(String.format("<knowledge_chunks knowledge_id=\"%s\"%s total=\"%d\" fetched=\"%d\">\n",
                knowledgeID, titleAttr, total, fetched));

        if (fetched == 0) {
            b.append("</knowledge_chunks>");
            return b.toString();
        }

        for (Chunk c : chunks) {
            if ("faq".equals(c.getChunkType())) {
                FaqSnippet.writeFaqEntryXml(b, c);
                writeChunkImagesMarkdown(b, c);
                continue;
            }

            String q = FaqSnippet.faqStandardQuestion(c);
            if (q != null && !q.isEmpty()) {
                b.append(String.format("<chunk chunk_id=\"%s\" chunk_index=\"%d\" type=\"%s\" question=\"%s\">\n",
                        c.getId(), c.getChunkIndex(), c.getChunkType(), FaqSnippet.xmlEscape(q)));
            } else {
                b.append(String.format("<chunk chunk_id=\"%s\" chunk_index=\"%d\" type=\"%s\">\n",
                        c.getId(), c.getChunkIndex(), c.getChunkType()));
            }
            b.append(String.format("<content>%s</content>\n", summarizeContent(c.getContent())));
            writeChunkImagesMarkdown(b, c);
            b.append("</chunk>\n");
        }

        if (fetched < total) {
            b.append(String.format("<pagination remaining=\"%d\" />\n", total - fetched));
        }

        b.append("</knowledge_chunks>");
        return b.toString();
    }

    /** 对照 writeChunkImagesMarkdown：逐图 markdown + "\n"。 */
    private static void writeChunkImagesMarkdown(StringBuilder b, Chunk c) {
        if (c == null) {
            return;
        }
        String imageInfo = c.getImageInfo();
        if (imageInfo == null || imageInfo.isEmpty()) {
            return;
        }
        List<ImageInfoView> imageInfos = DocChunkSupport.parseImageInfoList(imageInfo);
        if (imageInfos == null || imageInfos.isEmpty()) {
            return;
        }
        for (ImageInfoView img : imageInfos) {
            String md = DocChunkSupport.buildImageInfoMarkdownWithURL(img.url(), img);
            if (!md.isEmpty()) {
                b.append(md).append('\n');
            }
        }
    }

    /** Go 字符串零值规范化（chunk 字段未 set 时 Java 为 null，Go 为 ""）。 */
    static String nz(String v) {
        return v == null ? "" : v;
    }

    /** 对照 summarizeContent。 */
    static String summarizeContent(String content) {
        String cleaned = content == null ? "" : content.trim();
        if (cleaned.isEmpty()) {
            return "(empty)";
        }
        return cleaned.trim();
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }
}
