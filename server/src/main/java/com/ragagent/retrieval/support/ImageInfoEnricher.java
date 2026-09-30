package com.ragagent.retrieval.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.retrieval.domain.ImageInfo;

/**
 * 图片信息与正文的互相富化（对照 Go {@code internal/searchutil/imageinfo.go} 的
 * 纯函数部分——波 2 未翻部分；依赖仓储的 {@code CollectImageInfoByChunkIDs /
 * EnrichSearchResultsImageInfo} 待检索引擎波次随端口一起落，见报告）。
 */
public final class ImageInfoEnricher {

    private ImageInfoEnricher() {
    }

    /**
     * 对照 MergeImageInfoJSON：把多 chunk 的 image_info JSON 合并成一个数组，
     * 按 URL（空则 OriginalURL）去重；无有效内容返回 ""。
     */
    public static String mergeImageInfoJson(Map<String, String> perChunk) {
        if (perChunk == null || perChunk.isEmpty()) {
            return "";
        }
        // Go 用 map[string]bool 去重 + 依赖 map 遍历序（无序）；Java 用插入序保证确定性，
        // 集合内容一致（顺序契约在 Go 侧本就不存在）。
        Set0 seen = new Set0();
        List<ImageInfo> all = new ArrayList<>();
        for (String raw : perChunk.values()) {
            List<ImageInfo> infos = ImageInfoMatchUtil.parseInfos(raw);
            if (infos == null) {
                continue; // Go：unmarshal 失败 → continue
            }
            for (ImageInfo info : infos) {
                String key = info.getUrl().isEmpty() ? info.getOriginalUrl() : info.getUrl();
                if (!key.isEmpty() && !seen.contains(key)) {
                    seen.add(key);
                    all.add(info);
                }
            }
        }
        if (all.isEmpty()) {
            return "";
        }
        return ImageInfoMatchUtil.marshalImageInfos(all);
    }

    /** 简单 set（避免与本类其它 Map 语义混淆的小别名）。 */
    private static final class Set0 {
        private final Map<String, Boolean> backing = new LinkedHashMap<>();

        boolean contains(String k) {
            return backing.containsKey(k);
        }

        void add(String k) {
            backing.put(k, Boolean.TRUE);
        }
    }

    /**
     * 对照 Go {@code searchutil.CollectImageInfoByChunkIDs}：按 chunk 聚合子块
     * image_info（两级解析——文本块的直接子块是图片块；parent_text 块的孙辈图片
     * 折算到顶层文本 ID），禁用子块跳过；返回 chunkID → 合并后数组 JSON。
     *
     * <p>仓储以 {@code lister} 回调注入（tenantId + parentIDs → 子块列表），
     * chatpipeline 端口与 knowledge 包的具体仓储都走这里，避免两份实现漂移。</p>
     */
    public static Map<String, String> collectImageInfoByChunkIds(
            java.util.function.BiFunction<Long, List<String>, List<com.ragagent.knowledge.domain.Chunk>> lister,
            long tenantId, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return null;
        }
        List<com.ragagent.knowledge.domain.Chunk> children;
        try {
            children = lister.apply(tenantId, chunkIds);
        } catch (RuntimeException e) {
            return null;
        }
        if (children == null || children.isEmpty()) {
            return null;
        }

        Map<String, Map<String, ImageInfo>> aggMap = new LinkedHashMap<>();
        List<String> textChildIds = new ArrayList<>();
        Map<String, String> textToParent = new LinkedHashMap<>();
        for (com.ragagent.knowledge.domain.Chunk child : children) {
            if (!child.isIsEnabled()) {
                continue;
            }
            switch (child.getChunkType()) {
                case "image_ocr", "image_caption" -> addChildInfo(aggMap, child.getParentChunkId(), child);
                case "text" -> {
                    textChildIds.add(child.getId());
                    textToParent.put(child.getId(), child.getParentChunkId());
                }
                default -> {
                }
            }
        }
        if (!textChildIds.isEmpty()) {
            List<com.ragagent.knowledge.domain.Chunk> grandChildren;
            try {
                grandChildren = lister.apply(tenantId, textChildIds);
            } catch (RuntimeException e) {
                grandChildren = null;
            }
            if (grandChildren != null) {
                for (com.ragagent.knowledge.domain.Chunk gc : grandChildren) {
                    if (!gc.isIsEnabled()) {
                        continue;
                    }
                    if (!"image_ocr".equals(gc.getChunkType()) && !"image_caption".equals(gc.getChunkType())) {
                        continue;
                    }
                    String parentTextID = textToParent.get(gc.getParentChunkId());
                    if (parentTextID != null) {
                        addChildInfo(aggMap, parentTextID, gc);
                    }
                }
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ImageInfo>> e : aggMap.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            out.put(e.getKey(), ImageInfoMatchUtil.marshalImageInfos(new ArrayList<>(e.getValue().values())));
        }
        return out;
    }

    /** 对照 addInfo：URL（空则 OriginalURL）去重 + 非空 OCR/Caption 字段覆盖。 */
    private static void addChildInfo(Map<String, Map<String, ImageInfo>> aggMap,
                                     String targetID,
                                     com.ragagent.knowledge.domain.Chunk child) {
        if (child.getImageInfo() == null || child.getImageInfo().isEmpty()) {
            return;
        }
        List<ImageInfo> infos = ImageInfoMatchUtil.parseInfos(child.getImageInfo());
        if (infos == null || infos.isEmpty()) {
            return;
        }
        Map<String, ImageInfo> agg = aggMap.computeIfAbsent(targetID, k -> new LinkedHashMap<>());
        for (ImageInfo info : infos) {
            String key = info.getUrl().isEmpty() ? info.getOriginalUrl() : info.getUrl();
            if (key.isEmpty()) {
                continue;
            }
            ImageInfo existing = agg.get(key);
            if (existing == null) {
                agg.put(key, info);
            } else {
                if (!info.getOcrText().isEmpty()) {
                    existing.setOcrText(info.getOcrText());
                }
                if (!info.getCaption().isEmpty()) {
                    existing.setCaption(info.getCaption());
                }
            }
        }
    }

    /**
     * 对照 ClearImageInfoTextMatchingBody：从 image_info 中移除与 recognized 完全
     * 相等的 OCR/caption 字段（merge 把该正文挂回 Content，富化不再重复注入）。
     */
    public static String clearImageInfoTextMatchingBody(String imageInfoJson,
                                                        String recognized, String chunkType) {
        if (imageInfoJson == null || imageInfoJson.isEmpty()
                || recognized == null || recognized.isEmpty()) {
            return imageInfoJson == null ? "" : imageInfoJson;
        }
        List<ImageInfo> infos = ImageInfoMatchUtil.parseInfos(imageInfoJson);
        if (infos == null || infos.isEmpty()) {
            return imageInfoJson;
        }
        boolean changed = false;
        for (ImageInfo info : infos) {
            switch (chunkType == null ? "" : chunkType) {
                case "image_ocr" -> {
                    if (info.getOcrText().equals(recognized)) {
                        info.setOcrText("");
                        changed = true;
                    }
                }
                case "image_caption" -> {
                    if (info.getCaption().equals(recognized)) {
                        info.setCaption("");
                        changed = true;
                    }
                }
                default -> {
                    // 其它 chunk 类型不动
                }
            }
        }
        if (!changed) {
            return imageInfoJson;
        }
        return ImageInfoMatchUtil.marshalImageInfos(infos);
    }

    /**
     * 对照 EnrichContentWithImageInfo：内联 Markdown 图片包成 &lt;image&gt; XML
     * （含 &lt;image_original&gt; 原文与 caption/ocr），content 里找不到的图片以
     * &lt;image&gt; 块追加。
     */
    public static String enrichContentWithImageInfo(String content, String imageInfoJson) {
        List<ImageInfo> infos = ImageInfoMatchUtil.parseInfos(imageInfoJson);
        if (infos == null || infos.isEmpty()) {
            return content;
        }
        Map<String, ImageInfo> infoMap = buildInfoMap(infos);

        record Match(String whole, String url) {
        }
        List<Match> matches = new ArrayList<>();
        var md = ChunkSearchUtil.MARKDOWN_IMAGE_REGEX.matcher(content);
        while (md.find()) {
            matches.add(new Match(md.group(), md.group(2)));
        }

        Map<String, Boolean> processedUrls = new LinkedHashMap<>();
        for (Match match : matches) {
            processedUrls.put(match.url(), Boolean.TRUE);
            ImageInfo imgInfo = infoMap.get(match.url());
            StringBuilder b = new StringBuilder();
            b.append("<image url=\"").append(match.url()).append("\">\n");
            b.append("<image_original>").append(match.whole()).append("</image_original>\n");
            if (imgInfo != null) {
                b.append(buildImageInfoXml(imgInfo));
            }
            b.append("</image>");
            content = replaceFirst(content, match.whole(), b.toString());
        }

        List<String> extras = new ArrayList<>();
        for (ImageInfo imgInfo : infos) {
            if (processedUrls.containsKey(imgInfo.getUrl())
                    || processedUrls.containsKey(imgInfo.getOriginalUrl())) {
                continue;
            }
            String url = imgInfo.getUrl().isEmpty() ? imgInfo.getOriginalUrl() : imgInfo.getUrl();
            String block = buildImageInfoXmlWithUrl(url, imgInfo);
            if (!block.isEmpty()) {
                extras.add(block);
            }
        }
        if (!extras.isEmpty()) {
            if (!content.isEmpty()) {
                content += "\n";
            }
            content += String.join("\n", extras);
        }
        return content;
    }

    /** Go strings.Replace(s, old, new, 1)：只替换第一次出现。 */
    private static String replaceFirst(String s, String oldStr, String newStr) {
        int idx = s.indexOf(oldStr);
        if (idx < 0) {
            return s;
        }
        return s.substring(0, idx) + newStr + s.substring(idx + oldStr.length());
    }

    /**
     * 对照 EnrichContentWithImageInfoForChat：保持图片本身是 Markdown，把
     * caption/OCR 以引用块形式注入其后（答案可复制渲染）。只富化有 image_info
     * 匹配的图片；HTML img 的 src 值先 trim（Markdown 目标按原文精确匹配）。
     * 两种语法都对着<b>原始</b> content 定位，按位置倒序一次性拼接。
     */
    public static String enrichContentWithImageInfoForChat(String content, String imageInfoJson) {
        List<ImageInfo> infos = ImageInfoMatchUtil.parseInfos(imageInfoJson);
        if (infos == null || infos.isEmpty()) {
            return content;
        }
        Map<String, ImageInfo> infoMap = buildInfoMap(infos);

        List<Injection> injections = new ArrayList<>();

        // Markdown：分组 2 是 URL；HTML：src 分组且 trim。注入点都在整个匹配的末尾。
        var md = ChunkSearchUtil.MARKDOWN_IMAGE_REGEX.matcher(content);
        while (md.find()) {
            appendInjection(injections, infoMap, content, md.end(), md.start(2), md.end(2), false);
        }
        var html = ChunkSearchUtil.HTML_IMAGE_SRC_REGEX.matcher(content);
        while (html.find()) {
            appendInjection(injections, infoMap, content, html.end(),
                    html.start(HTML_IMAGE_SRC_URL_GROUP), html.end(HTML_IMAGE_SRC_URL_GROUP), true);
        }
        injections.sort(Comparator.comparingInt(Injection::at).reversed());
        for (Injection inj : injections) {
            content = content.substring(0, inj.at()) + inj.text() + content.substring(inj.at());
        }
        return content;
    }

    /** HTMLImageSrcURLGroup = 2（桥内再导出一份，避免 Pattern 名重复）。 */
    /** 对照 Go HTMLImageSrcURLGroup = 2。 */
    static final int HTML_IMAGE_SRC_URL_GROUP = 2;

    private static void appendInjection(List<Injection> injections,
                                        Map<String, ImageInfo> infoMap, String content,
                                        int matchEnd, int keyStart, int keyEnd, boolean trim) {
        if (keyStart < 0) {
            return;
        }
        String key = content.substring(keyStart, keyEnd);
        if (trim) {
            key = goTrimSpace(key);
        }
        ImageInfo imgInfo = infoMap.get(key);
        if (imgInfo == null) {
            return;
        }
        String metadata = buildImageInfoMarkdownMetadata(imgInfo);
        if (metadata.isEmpty()) {
            return;
        }
        // 注入点 = 整个匹配的末尾（对照 Go 的 loc[1]）
        injections.add(new Injection(matchEnd, "\n\n" + metadata));
    }

    /** 注入点（位置 + 文本），对照 Go 的局部 injection struct。 */
    private record Injection(int at, String text) {
    }

    /** 对照 buildImageInfoMarkdownMetadata：引用块承载 caption/OCR（保多行缩进）。 */
    static String buildImageInfoMarkdownMetadata(ImageInfo img) {
        if (img == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        String caption = goTrimSpace(img.getCaption());
        if (!caption.isEmpty()) {
            lines.add("**Image caption:** " + caption);
        }
        String ocr = goTrimSpace(img.getOcrText());
        if (!ocr.isEmpty()) {
            lines.add("**Image text (OCR):** " + ocr);
        }
        if (lines.isEmpty()) {
            return "";
        }
        String joined = String.join("\n\n", lines);
        return "> " + joined.replace("\n", "\n> ");
    }

    /**
     * 对照 BuildImageInfoMarkdownWithURL：answer-ready Markdown（URL 原样保留）。
     * alt 取 caption 的空白折叠并转义 {@code \ [ ]}；空 alt 回落 "image"。
     */
    public static String buildImageInfoMarkdownWithUrl(String url, ImageInfo img) {
        if (img == null) {
            return "";
        }
        url = goTrimSpace(url == null ? "" : url);
        String metadata = buildImageInfoMarkdownMetadata(img);
        if (url.isEmpty()) {
            return metadata;
        }
        String alt = String.join(" ", foldFields(img.getCaption()));
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

    /** 对照 BuildImageInfoXML：caption / ocr 两行（无行省略）。 */
    public static String buildImageInfoXml(ImageInfo img) {
        StringBuilder b = new StringBuilder();
        if (img != null && !img.getCaption().isEmpty()) {
            b.append("<image_caption>").append(img.getCaption()).append("</image_caption>\n");
        }
        if (img != null && !img.getOcrText().isEmpty()) {
            b.append("<image_ocr>").append(img.getOcrText()).append("</image_ocr>\n");
        }
        return b.toString();
    }

    /** 对照 BuildImageInfoXMLWithURL：包一层 &lt;image url&gt;；内层空 → ""。 */
    public static String buildImageInfoXmlWithUrl(String url, ImageInfo img) {
        String inner = buildImageInfoXml(img);
        if (inner.isEmpty()) {
            return "";
        }
        return "<image url=\"" + url + "\">\n" + inner + "</image>";
    }

    /**
     * 对照 EnrichContentCaptionOnly：只注入 caption（摘要用，OCR 噪声大）。
     * 内联图片在原文后接一行 caption；找不到的图片追加 caption 块。
     */
    public static String enrichContentCaptionOnly(String content, String imageInfoJson) {
        List<ImageInfo> infos = ImageInfoMatchUtil.parseInfos(imageInfoJson);
        if (infos == null || infos.isEmpty()) {
            return content;
        }
        Map<String, ImageInfo> infoMap = buildInfoMap(infos);

        record Match(String whole, String url) {
        }
        List<Match> matches = new ArrayList<>();
        var md = ChunkSearchUtil.MARKDOWN_IMAGE_REGEX.matcher(content);
        while (md.find()) {
            matches.add(new Match(md.group(), md.group(2)));
        }

        Map<String, Boolean> processedUrls = new LinkedHashMap<>();
        for (Match match : matches) {
            processedUrls.put(match.url(), Boolean.TRUE);
            ImageInfo imgInfo = infoMap.get(match.url());
            if (imgInfo != null && !imgInfo.getCaption().isEmpty()) {
                String replacement = match.whole() + "\n"
                        + "<image_caption>" + imgInfo.getCaption() + "</image_caption>";
                content = replaceFirst(content, match.whole(), replacement);
            }
        }
        List<String> extras = new ArrayList<>();
        for (ImageInfo imgInfo : infos) {
            if (processedUrls.containsKey(imgInfo.getUrl())
                    || processedUrls.containsKey(imgInfo.getOriginalUrl())) {
                continue;
            }
            if (!imgInfo.getCaption().isEmpty()) {
                extras.add("<image_caption>" + imgInfo.getCaption() + "</image_caption>");
            }
        }
        if (!extras.isEmpty()) {
            if (!content.isEmpty()) {
                content += "\n";
            }
            content += String.join("\n", extras);
        }
        return content;
    }

    /**
     * 对照 EnrichContentCaptionAndOCR：caption 之外也注入 OCR；刻意不带 URL 与
     * &lt;image_original&gt; 包装（摘要 LLM 只要可读文本）。
     */
    public static String enrichContentCaptionAndOcr(String content, String imageInfoJson) {
        List<ImageInfo> infos = ImageInfoMatchUtil.parseInfos(imageInfoJson);
        if (infos == null || infos.isEmpty()) {
            return content;
        }
        Map<String, ImageInfo> infoMap = buildInfoMap(infos);

        record Match(String whole, String url) {
        }
        List<Match> matches = new ArrayList<>();
        var md = ChunkSearchUtil.MARKDOWN_IMAGE_REGEX.matcher(content);
        while (md.find()) {
            matches.add(new Match(md.group(), md.group(2)));
        }

        Map<String, Boolean> processedUrls = new LinkedHashMap<>();
        for (Match match : matches) {
            processedUrls.put(match.url(), Boolean.TRUE);
            ImageInfo imgInfo = infoMap.get(match.url());
            if (imgInfo == null) {
                continue;
            }
            String appended = buildCaptionOcrBlock(imgInfo);
            if (appended.isEmpty()) {
                continue;
            }
            content = replaceFirst(content, match.whole(), match.whole() + "\n" + appended);
        }
        List<String> extras = new ArrayList<>();
        for (ImageInfo imgInfo : infos) {
            if (processedUrls.containsKey(imgInfo.getUrl())
                    || processedUrls.containsKey(imgInfo.getOriginalUrl())) {
                continue;
            }
            String block = buildCaptionOcrBlock(imgInfo);
            if (!block.isEmpty()) {
                extras.add(block);
            }
        }
        if (!extras.isEmpty()) {
            if (!content.isEmpty()) {
                content += "\n";
            }
            content += String.join("\n", extras);
        }
        return content;
    }

    /** 对照 buildCaptionOCRBlock：无 URL 包装的 caption + OCR 行块。 */
    static String buildCaptionOcrBlock(ImageInfo img) {
        List<String> parts = new ArrayList<>();
        if (!img.getCaption().isEmpty()) {
            parts.add("<image_caption>" + img.getCaption() + "</image_caption>");
        }
        if (!img.getOcrText().isEmpty()) {
            parts.add("<image_ocr>" + img.getOcrText() + "</image_ocr>");
        }
        return String.join("\n", parts);
    }

    /** url 与 original_url 都进 map（非空才放；同键后者覆盖前者，对照 Go）。 */
    private static Map<String, ImageInfo> buildInfoMap(List<ImageInfo> infos) {
        Map<String, ImageInfo> map = new LinkedHashMap<>();
        for (ImageInfo info : infos) {
            if (!info.getUrl().isEmpty()) {
                map.put(info.getUrl(), info);
            }
            if (!info.getOriginalUrl().isEmpty()) {
                map.put(info.getOriginalUrl(), info);
            }
        }
        return map;
    }

    /** Go strings.Fields：按空白切（含 unicode 空白），空串列表为空。 */
    static List<String> foldFields(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        for (String f : s.split("\\p{IsWhite_Space}+")) {
            if (!f.isEmpty()) {
                out.add(f);
            }
        }
        return out;
    }

    /** Go strings.TrimSpace（unicode.IsSpace 全集，与 ChunkSearchUtil.goTrimSpace 同表）。 */
    static String goTrimSpace(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isGoSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isGoSpace(char c) {
        switch (c) {
            case '\t': case '\n': case '\u000B': case '\f': case '\r':
            case ' ': case '\u0085': case '\u00A0': case '\u1680':
            case '\u2028': case '\u2029': case '\u202F': case '\u205F': case '\u3000':
                return true;
            default:
                return c >= '\u2000' && c <= '\u200A';
        }
    }

}
