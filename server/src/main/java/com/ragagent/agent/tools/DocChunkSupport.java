package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.knowledge.domain.Chunk;

/**
 * wiki_read_source_doc / get_document_info / list_knowledge_chunks 三工具共享的
 * 接缝与图片富化（对照 Go {@code wiki_read_source_doc.go} 的 enrichChunkImageInfo /
 * enrichChunkContent、{@code searchutil/imageinfo.go} 的
 * BuildImageInfoMarkdownWithURL / buildImageInfoMarkdownMetadata / CollectImageInfoByChunkIDs
 * 消费侧，逐字移植）。
 *
 * <p>seam（接线交 4.5c）：</p>
 * <ul>
 *   <li>{@link KnowledgeInfoReader}：对照 interfaces.KnowledgeService 被用子集
 *       （GetKnowledgeByIDOnly + GetKnowledgeTags）。</li>
 *   <li>{@link PagedChunks}：对照 ChunkRepository.ListPagedChunksByKnowledgeID
 *       （text/faq 类型 + enabled 过滤已在服务端语义内；Java 收窄为整页返回）。</li>
 *   <li>{@link ImageInfoCollector}：对照 searchutil.CollectImageInfoByChunkIDs
 *       （返回 parent chunk ID → 合并后 ImageInfo JSON 串；null 收集器 = Go chunkRepo nil 跳过）。
 *       其内部合并实现（含 map 迭代序）属 4.5c。</li>
 * </ul>
 */
public final class DocChunkSupport {

    private DocChunkSupport() {
    }

    /** knowledge 文档富视图（对照 types.Knowledge 被用字段；tenantId 对照 uint64）。 */
    public record KnowledgeInfoView(String id, long tenantId, String knowledgeBaseId, String title,
            String description, String type, String source, String fileName, String fileType,
            long fileSize, String parseStatus, Map<String, Object> metadata) {
    }

    /**
     * 对照 interfaces.KnowledgeService 被用子集（GetKnowledgeByIDOnly/GetKnowledgeTags）。
     * 返回 null = empty result（对照 Go err==nil 且 knowledge==nil）。
     * 不继承 {@link SearchAuth.KnowledgeScopeReader}（record 无子类型关系），
     * 传给 SearchAuth 时用 {@link #asScopeReader} 适配。
     */
    public interface KnowledgeInfoReader {
        KnowledgeInfoView byIdOnly(String knowledgeId);

        Map<String, List<SearchAuth.TagView>> fetchTags(List<String> knowledgeIds);
    }

    /** KnowledgeInfoReader → SearchAuth.KnowledgeScopeReader 适配（授权路径用）。 */
    public static SearchAuth.KnowledgeScopeReader asScopeReader(KnowledgeInfoReader reader) {
        return new SearchAuth.KnowledgeScopeReader() {
            @Override
            public SearchAuth.KnowledgeView byIdOnly(String knowledgeId) {
                KnowledgeInfoView k = reader == null ? null : reader.byIdOnly(knowledgeId);
                if (k == null) {
                    return null;
                }
                return new SearchAuth.KnowledgeView(k.id(), k.knowledgeBaseId(), k.title(), k.fileName());
            }

            @Override
            public Map<String, List<SearchAuth.TagView>> fetchTags(List<String> knowledgeIds) {
                return reader == null ? null : reader.fetchTags(knowledgeIds);
            }
        };
    }

    /** 对照 ListPagedChunksByKnowledgeID 的一页结果。 */
    public record ChunkPage(List<Chunk> chunks, long total) {
    }

    /** 对照 ChunkRepository.ListPagedChunksByKnowledgeID（chunkRepo 为 null = 服务不可用）。 */
    public interface PagedChunks {
        ChunkPage listPaged(long tenantId, String knowledgeId, int page, int pageSize);
    }

    /** 对照 searchutil.CollectImageInfoByChunkIDs 的位置。 */
    public interface ImageInfoCollector {
        Map<String, String> collect(long tenantId, List<String> chunkIds);
    }

    // ==================== ImageInfo（对照 types.ImageInfo 被用字段） ====================

    /** 对照 types.ImageInfo 的 URL/Caption/OCRText 三字段。 */
    public record ImageInfoView(String url, String caption, String ocrText) {
    }

    /** 独立 ObjectMapper（主源码不可依赖测试侧的 RecordingSupport）。 */
    private static final class ImageJson {
        static final com.fasterxml.jackson.databind.ObjectMapper PLAIN =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private ImageJson() {
        }
    }

    /** 对照 json.Unmarshal(chunk.ImageInfo, &[]types.ImageInfo)：失败或空 → null。 */
    public static List<ImageInfoView> parseImageInfoList(String imageInfoJson) {
        if (imageInfoJson == null || imageInfoJson.isEmpty()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = ImageJson.PLAIN.readTree(imageInfoJson);
            if (!node.isArray() || node.isEmpty()) {
                return null;
            }
            List<ImageInfoView> out = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                String url = item.path("url").asText("");
                String originalUrl = item.path("original_url").asText("");
                if (url.isEmpty()) {
                    url = originalUrl;
                }
                out.add(new ImageInfoView(url,
                        item.path("caption").asText(""), item.path("ocr_text").asText("")));
            }
            return out;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** 对照 buildImageInfoMarkdownMetadata：caption/OCR 的 blockquote 组装；空 → ""。 */
    public static String buildImageInfoMarkdownMetadata(ImageInfoView img) {
        if (img == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        String caption = img.caption() == null ? "" : img.caption().trim();
        if (!caption.isEmpty()) {
            lines.add("**Image caption:** " + caption);
        }
        String ocr = img.ocrText() == null ? "" : img.ocrText().trim();
        if (!ocr.isEmpty()) {
            lines.add("**Image text (OCR):** " + ocr);
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "> " + String.join("\n\n", lines).replace("\n", "\n> ");
    }

    /**
     * 对照 BuildImageInfoMarkdownWithURL：URL 原样保留；alt = caption 按空白折叠，
     * 空 → "image"，反斜杠与方括号转义。
     */
    public static String buildImageInfoMarkdownWithURL(String url, ImageInfoView img) {
        if (img == null) {
            return "";
        }
        url = url == null ? "" : url.trim();
        String metadata = buildImageInfoMarkdownMetadata(img);
        if (url.isEmpty()) {
            return metadata;
        }
        String alt = collapseWhitespace(img.caption());
        if (alt.isEmpty()) {
            alt = "image";
        }
        alt = alt.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
        String image = "![" + alt + "](" + url + ")";
        if (metadata.isEmpty()) {
            return image;
        }
        return image + "\n\n" + metadata;
    }

    /** 对照 strings.Join(strings.Fields(s), " ")：空白折叠为单空格。 */
    static String collapseWhitespace(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean inWs = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c)) {
                inWs = true;
            } else {
                if (inWs) {
                    sb.append(' ');
                    inWs = false;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 对照 enrichChunkContent：content + 每个非空图片 markdown（各前置 "\n"）。 */
    public static String enrichChunkContent(Chunk c) {
        String content = c.getContent() == null ? "" : c.getContent();
        String imageInfo = c.getImageInfo();
        if (imageInfo != null && !imageInfo.isEmpty()) {
            List<ImageInfoView> infos = parseImageInfoList(imageInfo);
            if (infos != null && !infos.isEmpty()) {
                StringBuilder imgBuilder = new StringBuilder();
                for (ImageInfoView img : infos) {
                    String md = buildImageInfoMarkdownWithURL(img.url(), img);
                    if (!md.isEmpty()) {
                        imgBuilder.append('\n').append(md);
                    }
                }
                content += imgBuilder.toString();
            }
        }
        return content;
    }

    /**
     * 对照 enrichChunkImageInfo：给 ImageInfo 为空的父 chunk 补图（已有非空的跳过）。
     * collector 为 null → 直接返回（Go chunkRepo nil 语义）。
     */
    public static void enrichChunkImageInfo(ImageInfoCollector collector, long tenantId, List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty() || collector == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Chunk c : chunks) {
            String info = c.getImageInfo();
            if ((info == null || info.isEmpty()) && c.getId() != null && !c.getId().isEmpty()) {
                ids.add(c.getId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<String, String> infoMap = collector.collect(tenantId, ids);
        if (infoMap == null || infoMap.isEmpty()) {
            return;
        }
        for (Chunk c : chunks) {
            String info = c.getImageInfo();
            if (info != null && !info.isEmpty()) {
                continue;
            }
            String merged = infoMap.get(c.getId());
            if (merged != null && !merged.isEmpty()) {
                c.setImageInfo(merged);
            }
        }
    }

    /** 对照 GetMetadata：空 metadata → 空 map（Go 非 nil）；解析失败 → null。 */
    public static Map<String, String> knowledgeMetadataMap(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : metadata.entrySet()) {
            out.put(e.getKey(), goFmtV(e.getValue()));
        }
        return out;
    }

    /** Go fmt %v 的常见标量形态（metadata 值用；嵌套容器的随机序不进探针）。 */
    static String goFmtV(Object v) {
        if (v == null) {
            return "<nil>";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Double d) {
            return com.ragagent.common.web.GoDoubleSerializer.format(d);
        }
        if (v instanceof Integer || v instanceof Long) {
            return v.toString();
        }
        return v.toString();
    }
}
