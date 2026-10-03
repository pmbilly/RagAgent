package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.knowledge.domain.Chunk;

/**
 * wiki_read_source_doc / get_document_info / list_knowledge_chunks 三工具共享的
 * 接缝与图片富化（chunk 内容富化、图片 markdown 组装、批量图片信息收集的消费侧）。
 *
 * <p>seam：</p>
 * <ul>
 *   <li>{@link KnowledgeInfoReader}：按 ID 取知识 + 取知识标签。</li>
 *   <li>{@link PagedChunks}：按知识分页取 chunk
 *       （text/faq 类型 + enabled 过滤已在服务端语义内；收窄为整页返回）。</li>
 *   <li>{@link ImageInfoCollector}：批量收集图片信息
 *       （返回 parent chunk ID → 合并后 ImageInfo JSON 串；null 收集器 = 跳过富化）。
 *       其内部合并实现属基础设施侧。</li>
 * </ul>
 */
public final class DocChunkSupport {

    private DocChunkSupport() {
    }

    /** knowledge 文档富视图（跨模块被用字段）。 */
    public record KnowledgeInfoView(String id, long tenantId, String knowledgeBaseId, String title,
            String description, String type, String source, String fileName, String fileType,
            long fileSize, String parseStatus, Map<String, Object> metadata) {
    }

    /**
     * 知识读取接缝（按 ID 取知识 / 取标签）。
     * 返回 null = empty result（记入失败文案）。
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

    /** 一页 chunk 结果。 */
    public record ChunkPage(List<Chunk> chunks, long total) {
    }

    /** 分页取 chunk 接缝（为 null = 服务不可用）。 */
    public interface PagedChunks {
        ChunkPage listPaged(long tenantId, String knowledgeId, int page, int pageSize);
    }

    /** 批量收集图片信息接缝。 */
    public interface ImageInfoCollector {
        Map<String, String> collect(long tenantId, List<String> chunkIds);
    }

    // ==================== ImageInfo ====================

    /** 图片信息三字段：URL/Caption/OCRText。 */
    public record ImageInfoView(String url, String caption, String ocrText) {
    }

    /** 独立 ObjectMapper（主源码不可依赖测试侧的 RecordingSupport）。 */
    private static final class ImageJson {
        static final com.fasterxml.jackson.databind.ObjectMapper PLAIN =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private ImageJson() {
        }
    }

    /** 解析 ImageInfo JSON 数组：失败或空 → null。 */
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

    /** caption/OCR 的 blockquote 组装；空 → ""。 */
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
     * 图片 markdown 组装：URL 原样保留；alt = caption 按空白折叠，
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

    /** 空白折叠为单空格。 */
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

    /** 内容富化：content + 每个非空图片 markdown（各前置 "\n"）。 */
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
     * 给 ImageInfo 为空的父 chunk 补图（已有非空的跳过）。
     * collector 为 null → 直接返回（服务不可用语义）。
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

    /** metadata 视图：空 metadata → 空 map；非字符串值按标量形态转文本；解析失败 → null。 */
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

    /** 常见标量的输出形态（metadata 值用；嵌套容器的序不保证）。 */
    public static String goFmtV(Object v) {
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
            return Double.toString(d);
        }
        if (v instanceof Integer || v instanceof Long) {
            return v.toString();
        }
        return v.toString();
    }
}
