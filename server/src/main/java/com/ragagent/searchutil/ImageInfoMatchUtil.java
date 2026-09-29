package com.ragagent.searchutil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.ImageInfo;

/**
 * 检索窗口与 image_info 的互相裁剪（对照 Go {@code internal/searchutil/imageinfo_match.go}
 * 的<b>波 2 未翻部分</b>；{@code ImageURLsInContent / ImageURLsFromInfo} 已在
 * {@code knowledge.service.ChunkSearchUtil} 落地，本类<b>委托复用</b>不重复实现）。
 *
 * <p>已知差异（保留，与 ChunkSearchUtil 同族）：Go 的 RE2 {@code \s} 不含
 * {@code 0x0B}，Java 默认含——URL 中出现垂直制表符不可能，见 ChunkSearchUtil 注释。</p>
 */
public final class ImageInfoMatchUtil {

    private ImageInfoMatchUtil() {
    }

    /**
     * 对照 SliceContentByDocumentRange：取 content 中文档级 rune 偏移落在
     * [rangeStart, rangeEnd) 的子串。contentStartAt 是 content 起点的文档偏移。
     */
    public static String sliceContentByDocumentRange(String content, int contentStartAt,
                                                     int rangeStart, int rangeEnd) {
        if (content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }
        int relStart = rangeStart - contentStartAt;
        int relEnd = rangeEnd - contentStartAt;
        int[] runes = content.codePoints().toArray();
        if (relStart < 0) {
            relStart = 0;
        }
        if (relEnd > runes.length) {
            relEnd = runes.length;
        }
        if (relStart >= relEnd) {
            return "";
        }
        StringBuilder b = new StringBuilder(relEnd - relStart);
        for (int i = relStart; i < relEnd; i++) {
            b.appendCodePoint(runes[i]);
        }
        return b.toString();
    }

    /**
     * 对照 FilterImageInfoByContentURLs：只保留 URL 或 OriginalURL 被 content 引用
     * （Markdown 链接或 HTML img 标签）的条目；无匹配或 JSON 非法返回 ""。
     */
    public static String filterImageInfoByContentUrls(String content, String imageInfoJson) {
        if (imageInfoJson == null || imageInfoJson.isEmpty()) {
            return "";
        }
        List<ImageInfo> infos = parseInfos(imageInfoJson);
        if (infos == null || infos.isEmpty()) {
            return "";
        }
        Set<String> urls = ChunkSearchUtil.imageURLsInContent(content);
        if (urls.isEmpty()) {
            return "";
        }
        List<ImageInfo> filtered = new ArrayList<>();
        for (ImageInfo info : infos) {
            if (urls.contains(info.getUrl()) || urls.contains(info.getOriginalUrl())) {
                filtered.add(info);
            }
        }
        return marshalImageInfos(filtered);
    }

    /**
     * 对照 PruneMarkdownImagesByImageInfo：只保留 chunk 级 image 元数据引用的
     * Markdown 图片（按耐久的图片 URL 匹配，文本编辑后仍稳定）。
     */
    public static String pruneMarkdownImagesByImageInfo(String content, String imageInfoJson) {
        // Go：allowed 只在 imageInfoJSON 非空且 Unmarshal 成功时填充——与
        // imageURLsFromInfo 的语义（空串/非法 → 空集）逐分支重合，直接复用。
        Set<String> allowed = ChunkSearchUtil.imageURLsFromInfo(
                imageInfoJson == null ? "" : imageInfoJson);
        String src = content == null ? "" : content;
        java.util.regex.Matcher m =
                ChunkSearchUtil.MARKDOWN_IMAGE_REGEX.matcher(src);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String image = m.group();
            out.append(src, last, m.start());
            last = m.end();
            if (m.groupCount() >= 2 && m.group(2) != null && allowed.contains(m.group(2))) {
                out.append(image);
            }
        }
        out.append(src, last, src.length());
        return collapseBlankLines(out.toString());
    }

    /**
     * 对照 FilterImageInfoByMatchRange：父内容为扩上下文而展开时，只保留图片引用
     * 落在文档 rune 区间 [matchStart, matchEnd) 内的 image_info 条目。
     */
    public static String filterImageInfoByMatchRange(String parentContent, int parentStartAt,
                                                     int matchStart, int matchEnd,
                                                     String imageInfoJson) {
        if (imageInfoJson == null || imageInfoJson.isEmpty()) {
            return "";
        }
        List<ImageInfo> infos = parseInfos(imageInfoJson);
        if (infos == null || infos.isEmpty()) {
            return "";
        }
        String window = sliceContentByDocumentRange(parentContent, parentStartAt, matchStart, matchEnd);
        Set<String> urls = ChunkSearchUtil.imageURLsInContent(window);
        if (urls.isEmpty()) {
            return "";
        }
        List<ImageInfo> filtered = new ArrayList<>();
        for (ImageInfo info : infos) {
            if (urls.contains(info.getUrl()) || urls.contains(info.getOriginalUrl())) {
                filtered.add(info);
            }
        }
        return marshalImageInfos(filtered);
    }

    /**
     * 对照 PruneMarkdownImagesOutsideRange：删除文档级 rune 偏移落在
     * [matchStart, matchEnd) 之外的 Markdown 图片；非图片文本全保留
     * （父子展开仍提供完整文字上下文，只丢无关页缩略图）。
     */
    public static String pruneMarkdownImagesOutsideRange(String content, int contentStartAt,
                                                         int matchStart, int matchEnd) {
        if (content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }
        record Loc(int start, int end) {
        }
        List<Loc> locs = new ArrayList<>();
        java.util.regex.Matcher m = ChunkSearchUtil.MARKDOWN_IMAGE_REGEX.matcher(content);
        while (m.find()) {
            locs.add(new Loc(m.start(), m.end()));
        }
        if (locs.isEmpty()) {
            return content;
        }
        StringBuilder b = new StringBuilder();
        int last = 0;
        for (Loc loc : locs) {
            if (loc.start() < last || loc.end() > content.length()) {
                continue;
            }
            int docStart = contentStartAt + content.codePointCount(0, loc.start());
            int docEnd = contentStartAt + content.codePointCount(0, loc.end());
            boolean inRange = docStart < matchEnd && docEnd > matchStart;
            if (inRange) {
                b.append(content, last, loc.end());
            } else {
                b.append(content, last, loc.start());
            }
            last = loc.end();
        }
        b.append(content, last, content.length());
        return collapseBlankLines(b.toString());
    }

    /** 对照 marshalImageInfos：空数组 → ""（Go 的 len==0 分支）。 */
    public static String marshalImageInfos(List<ImageInfo> infos) {
        if (infos == null || infos.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < infos.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(marshalOne(infos.get(i)));
        }
        return b.append(']').toString();
    }

    /**
     * Go 的 json.Marshal(ImageInfo)（struct 序 + 恒输出；HTML/控制字符转义按
     * encoding/json）。ImageInfo 六键恒输出且为简单值，手工拼装与 marshal 逐字节等价。
     */
    static String marshalOne(ImageInfo info) {
        var node = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
        node.put("url", info.getUrl());
        node.put("original_url", info.getOriginalUrl());
        node.put("start_pos", info.getStartPos());
        node.put("end_pos", info.getEndPos());
        node.put("caption", info.getCaption());
        node.put("ocr_text", info.getOcrText());
        return new String(GoJsonUtil.marshal(node), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 对照 collapseBlankLines：把 3+ 连续换行折叠成 2，再整体 trim。
     * （Go 循环 ReplaceAll 直到不再含 "\n\n\n"。）
     */
    static String collapseBlankLines(String s) {
        while (s.contains("\n\n\n")) {
            s = s.replace("\n\n\n", "\n\n");
        }
        return s.trim();
    }

    /** 对照 json.Unmarshal(imageInfoJSON, &[]types.ImageInfo)：失败/非数组 → null。 */
    public static List<ImageInfo> parseInfos(String imageInfoJson) {
        JsonNode arr = GoJsonUtil.parse(imageInfoJson);
        if (arr == null || !arr.isArray()) {
            return null;
        }
        List<ImageInfo> out = new ArrayList<>();
        for (JsonNode item : arr) {
            ImageInfo info = new ImageInfo();
            info.setUrl(item.path("url").asText(""));
            info.setOriginalUrl(item.path("original_url").asText(""));
            info.setStartPos(item.path("start_pos").asInt());
            info.setEndPos(item.path("end_pos").asInt());
            info.setCaption(item.path("caption").asText(""));
            info.setOcrText(item.path("ocr_text").asText(""));
            out.add(info);
        }
        return out;
    }

    /** 信息聚合用（Go 的 map 遍历无序；LinkedHashMap 仅为确定性）。 */
    public static <K, V> Map<K, V> orderedMap() {
        return new LinkedHashMap<>();
    }
}
