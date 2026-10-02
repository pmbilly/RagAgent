package com.ragagent.datasource.connector.feishu.core;

import java.time.OffsetDateTime;
import com.ragagent.common.deployment.AppEnvLookup;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.datasource.connector.feishu.core.DocxBlocks.BlockTokenRef;
import com.ragagent.datasource.connector.feishu.core.DocxBlocks.DocxBlock;
import com.ragagent.datasource.connector.feishu.core.DocxMarkdown.MarkdownResult;
import com.ragagent.datasource.connector.feishu.core.DocxMarkdown.PendingAttachment;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.SubtreeChildIds;

/**
 * docx 抓取（对照 Go {@code core/shared.go} L287-508 的 {@code DocxFetchInput} /
 * {@code FetchDocxWithBlocks} / {@code exportDocxFallback}）。
 *
 * <h2>两条解析路径与为什么要选</h2>
 * <p>{@code FEISHU_DOCX_PARSE_MODE} 选择 docx 的解析路径：</p>
 * <ul>
 *   <li><b>blocks</b>：把 image 块渲染成空占位 {@code ![图片]()}，并把图片<b>扇出</b>成
 *       独立知识条目——这会打断检索/wiki/agent 里"图片↔文档"的关联；</li>
 *   <li><b>export</b>（默认）：导出的 .docx 交给 docreader 内联解析，图片经
 *       parent_chunk_id 绑在父文档上（与普通 docx 上传一致）。</li>
 * </ul>
 * <p>Go 的注释明确写了这是<b>临时方案</b>：以后有更好的解析方案时这个环境变量会被移除。
 * 默认（未设 / {@code "export"}）走 export。</p>
 *
 * <h2>Java 侧对 env 的处置（任务书约束第 3 条）</h2>
 * <p>{@code System.getenv} 在进程内改不了，所以取值抽成可覆盖的
 * {@link #parseMode}（默认实现读 env）。测试把它换成 {@code () -> "blocks"} 就能覆盖
 * <b>两条</b>分支，不必依赖环境变量；与 §9「{@code Mode.defaultMode()} 读 System.getenv，
 * Java 侧没测」是同一处置、但这次留了缝。</p>
 *
 * <h2>为什么 {@link DocxFetchInput} 用 public 字段</h2>
 * <p>它是 Go 的<b>入参结构体</b>（{@code DocxFetchInput}）：9 个字段、只在同模块内部
 * 按名传参，从不序列化。Go 侧就是"结构体字面量 + 具名字段"，Java 侧用 public 字段
 * 是最贴近的翻译，也免去 9 对无意义 getter/setter。这是<b>刻意的例外</b>，
 * 仅限本纯入参类型（会落 jsonb / 作响应体的类型仍然必须 getter/setter + 契约治理）。</p>
 */
public final class DocxFetcher {

    private static final Logger log = LoggerFactory.getLogger(DocxFetcher.class);

    /** {@code FEISHU_DOCX_PARSE_MODE} 的默认值（对照 Go 的 {@code "export"}）。 */
    public static final String PARSE_MODE_EXPORT = "export";

    /** 另一条路径的取值。 */
    public static final String PARSE_MODE_BLOCKS = "blocks";

    /**
     * {@code FEISHU_DOCX_PARSE_MODE} 的取值来源。
     *
     * <p>默认实现读 {@code System.getenv}（对照 Go 的 {@code os.Getenv}）；
     * 测试可替换为固定值以覆盖两条分支。**可变静态字段**是刻意留的注入缝。</p>
     */
    public static volatile Supplier<String> parseMode = () -> AppEnvLookup.get("FEISHU_DOCX_PARSE_MODE");

    private DocxFetcher() {
    }

    /** 当前生效的解析模式：trim 后为空则回落 {@code "export"}（对照 Go）。 */
    public static String currentParseMode() {
        String mode = parseMode.get();
        mode = mode == null ? "" : mode.trim();
        if (mode.isEmpty()) {
            mode = PARSE_MODE_EXPORT;
        }
        return mode;
    }

    /**
     * 对照 Go {@code DocxFetchInput}：一次 docx 抓取需要的全部输入
     * （wiki 节点与云盘文件统一成这个形状）。
     */
    public static final class DocxFetchInput {

        /** WeKnora external_id：wiki 用 node.NodeToken，drive 用 file.Token。 */
        public String docToken = "";

        /** 飞书 docx 文档 token。 */
        public String objToken = "";

        public String title = "";

        public String url = "";

        public String resourceId = "";

        public OffsetDateTime editTime = FeishuSupport.orGoZero(null);

        /** 飞书侧的文档创建时间；未知时是 Go 零值。 */
        public OffsetDateTime createTime = FeishuSupport.orGoZero(null);

        /** 基础 metadata（各连接器自己拼，含 obj_token/obj_type/channel…）。 */
        public Map<String, String> baseMeta = new LinkedHashMap<>();

        public boolean multimodalEnabled;
    }

    /**
     * 对照 Go {@code FetchDocxWithBlocks}：经 blocks API 取一篇 docx、转成 Markdown，
     * 返回主条目 + 可解析的附件/图片子条目。
     *
     * <p>blocks API 报错或渲染为空时<b>回落</b>到导出 API。wiki 与 drive 共用。</p>
     *
     * <p>回落路径<b>不设</b> {@code ReplacesSubtree}：一次瞬时的 blocks 失败绝不能把
     * 上一次 blocks 路径同步出来的好附件子项扫掉、又没有东西替上（wiki 的历史教训）。</p>
     */
    public static List<FetchedItem> fetchDocxWithBlocks(FeishuClient client, DocxFetchInput in) {
        if (PARSE_MODE_EXPORT.equalsIgnoreCase(currentParseMode())) {
            return List.of(exportDocxFallback(client, in));
        }

        List<DocxBlock> blocks;
        try {
            blocks = client.listDocumentBlocks(in.objToken);
        } catch (RuntimeException e) {
            log.warn("[Feishu] blocks API failed for {} ({}), falling back to export: {}",
                    in.title, in.objToken, e.getMessage());
            return List.of(exportDocxFallback(client, in));
        }

        MarkdownResult md;
        try {
            md = DocxMarkdown.blocksToMarkdown(client, blocks);
        } catch (RuntimeException e) {
            throw new com.ragagent.datasource.ConnectorException(
                    "convert blocks " + in.title + ": " + e.getMessage(), e);
        }

        if (md.text().trim().isEmpty()) {
            log.info("[Feishu] doc {} ({}): blocks rendered empty Markdown, falling back to export",
                    in.title, in.objToken);
            return List.of(exportDocxFallback(client, in));
        }

        FetchedItem main = new FetchedItem();
        main.setExternalId(in.docToken);
        main.setTitle(in.title);
        main.setContent(md.markdown());
        main.setContentType("text/markdown");
        main.setFileName(FeishuSupport.sanitizeFileName(in.title) + ".md");
        main.setUrl(in.url);
        main.setUpdatedAt(in.editTime);
        main.setCreatedAt(in.createTime);
        main.setSourceResourceId(in.resourceId);
        main.setMetadata(in.baseMeta);
        main.setReplacesSubtree(true); // 重同步时清扫过期的附件子项

        List<FetchedItem> items = new ArrayList<>();
        items.add(main);

        // keep 一开始就是非 nil 的空切片（对照 Go 的 make([]string, 0, len(atts))）：
        // SubtreeKeep 的契约是"空 = 什么都不保留"，nil 与空等价，但这里保持空切片形态。
        List<String> keep = new ArrayList<>();

        for (PendingAttachment a : md.attachments()) {
            String childId = SubtreeChildIds.subtreeChildId(in.docToken, "file", a.fileToken());
            keep.add(childId); // 文档里还在 → 绝不能当成过期清扫掉
            String ext = FeishuSupport.fileExt(a.name()).toLowerCase(Locale.ROOT);
            if (ext.isEmpty()) {
                log.warn("[Feishu] doc {}: skipping attachment with no usable filename (token={} name=\"{}\")",
                        in.objToken, a.fileToken(), a.name());
                continue;
            }
            if (!FeishuSupport.PARSEABLE_ATTACHMENT_EXTS.containsKey(ext)) {
                continue;
            }
            byte[] data;
            try {
                data = client.downloadMediaFile(a.fileToken());
            } catch (RuntimeException derr) {
                log.warn("[Feishu] doc {}: attachment \"{}\" (token={}) download failed: {}",
                        in.objToken, a.name(), a.fileToken(), derr.getMessage());
                FetchedItem errItem = new FetchedItem();
                errItem.setExternalId(childId);
                errItem.setTitle(a.name());
                errItem.setSourceResourceId(in.resourceId);
                errItem.setMetadata(FeishuErrors.feishuErrorItemMeta(derr, childMeta(in)));
                items.add(errItem);
                continue;
            }
            if (data.length < FeishuSupport.MIN_ATTACHMENT_BYTES) {
                log.info("[Feishu] doc {}: skipping tiny attachment \"{}\" (token={}, {} bytes < {})",
                        in.objToken, a.name(), a.fileToken(), data.length,
                        FeishuSupport.MIN_ATTACHMENT_BYTES);
                continue;
            }
            FetchedItem att = new FetchedItem();
            att.setExternalId(childId);
            att.setTitle(a.name());
            att.setContent(data);
            att.setContentType("application/octet-stream");
            att.setFileName(FeishuSupport.sanitizeFileName(a.name()));
            att.setUrl(in.url);
            att.setUpdatedAt(in.editTime);
            att.setCreatedAt(in.createTime);
            att.setSourceResourceId(in.resourceId);
            att.setMetadata(childMeta(in));
            items.add(att);
        }

        for (DocxBlock b : blocks) {
            if (b.getBlockType() != DocxBlocks.BLOCK_TYPE_IMAGE || b.getImage() == null) {
                continue;
            }
            BlockTokenRef img = b.getImage();
            if (img.token() == null || img.token().isEmpty()) {
                continue;
            }
            String childId = SubtreeChildIds.subtreeChildId(in.docToken, "image", img.token());
            keep.add(childId); // 文档里还在 → 绝不能当成过期清扫掉
            if (!in.multimodalEnabled) {
                continue; // 知识库不会 OCR 图片；只保留内联占位
            }
            byte[] data;
            try {
                data = client.downloadMediaFile(img.token());
            } catch (RuntimeException derr) {
                log.warn("[Feishu] doc {}: image (token={}) download failed: {}",
                        in.objToken, img.token(), derr.getMessage());
                FetchedItem errItem = new FetchedItem();
                errItem.setExternalId(childId);
                errItem.setTitle(in.title + "（内嵌图片）");
                errItem.setSourceResourceId(in.resourceId);
                errItem.setMetadata(FeishuErrors.feishuErrorItemMeta(derr, imgMeta(in)));
                items.add(errItem);
                continue;
            }
            if (data.length < FeishuSupport.MIN_ATTACHMENT_BYTES) {
                continue; // 装饰性微图（图标/占位符）
            }
            FeishuSupport.ImageExt sniffed = FeishuSupport.supportedImageExt(data);
            if (!sniffed.ok()) {
                log.warn("[Feishu] doc {}: skipping image (token={}) of unsupported type \"{}\"",
                        in.objToken, img.token(), sniffed.contentType());
                continue;
            }
            FetchedItem imgItem = new FetchedItem();
            imgItem.setExternalId(childId);
            imgItem.setTitle(in.title + "（内嵌图片）");
            imgItem.setContent(data);
            imgItem.setContentType(sniffed.contentType());
            imgItem.setFileName("image-" + img.token() + sniffed.ext());
            imgItem.setUrl(in.url);
            imgItem.setUpdatedAt(in.editTime);
            imgItem.setCreatedAt(in.createTime);
            imgItem.setSourceResourceId(in.resourceId);
            imgItem.setMetadata(imgMeta(in));
            items.add(imgItem);
        }

        main.setSubtreeKeep(keep);
        return items;
    }

    /** 附件的子条目 metadata：父节点 metadata 的克隆 + 两个标记。 */
    private static Map<String, String> childMeta(DocxFetchInput in) {
        Map<String, String> m = new LinkedHashMap<>(in.baseMeta);
        m.put("parent_node_token", in.docToken);
        m.put("attachment", "true");
        return m;
    }

    /** 内嵌图片的子条目 metadata。 */
    private static Map<String, String> imgMeta(DocxFetchInput in) {
        Map<String, String> m = new LinkedHashMap<>(in.baseMeta);
        m.put("parent_node_token", in.docToken);
        m.put("embedded_image", "true");
        return m;
    }

    /**
     * 对照 Go {@code exportDocxFallback}：经异步导出 API 导出一篇 docx，
     * 返回<b>单个</b>承载导出 .docx 二进制的 FetchedItem。
     */
    static FetchedItem exportDocxFallback(FeishuClient client, DocxFetchInput in) {
        FeishuClient.ExportDownload exported;
        try {
            exported = client.exportAndDownload(in.objToken, "docx");
        } catch (RuntimeException e) {
            throw new com.ragagent.datasource.ConnectorException(
                    "export " + in.title + " (docx): " + e.getMessage(), e);
        }

        String ext = FeishuConfig.EXPORT_FILE_EXT_TO_SUFFIX.get(
                FeishuConfig.OBJ_TYPE_TO_EXPORT_FILE_EXTENSION.get("docx"));
        String fileName = exported.fileName();
        if (fileName == null || fileName.isEmpty()) {
            fileName = FeishuSupport.sanitizeFileName(in.title) + ext;
        } else if (!fileName.toLowerCase(Locale.ROOT).endsWith(ext)) {
            // 飞书常返回不带扩展名的文档标题——补上
            fileName = FeishuSupport.sanitizeFileName(fileName) + ext;
        }

        FetchedItem item = new FetchedItem();
        item.setExternalId(in.docToken);
        item.setTitle(in.title);
        item.setContent(exported.data());
        item.setContentType("application/octet-stream");
        item.setFileName(fileName);
        item.setUrl(in.url);
        item.setUpdatedAt(in.editTime);
        item.setCreatedAt(in.createTime);
        item.setSourceResourceId(in.resourceId);
        item.setMetadata(in.baseMeta);
        return item;
    }
}
