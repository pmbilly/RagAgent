package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ragagent.knowledge.service.DocReaderClient;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.mapper.TemporaryDocumentRepository;

/**
 * ResolveForPrompt + 图片落地（对照 Go temporary_document.go L597-705 与
 * image_resolver.go 的 docreader 分支）的行为验收。
 *
 * <p>用真 {@link AttachmentFileStore}（临时目录）验图片落盘与 URL 形态，
 * 仓库/docreader 用 Mockito stub（无 DB 依赖）。</p>
 */
class TemporaryDocumentResolveForPromptTest {

    private static final long TENANT = 1L;
    private static final String SESSION = "s1";

    @TempDir
    Path tempDir;

    private final TemporaryDocumentRepository repo = mock(TemporaryDocumentRepository.class);
    private final DocReaderClient docReader = mock(DocReaderClient.class);
    private AttachmentFileStore fileStore;
    private TemporaryDocumentService service;

    private TemporaryDocumentService service() {
        if (service == null) {
            fileStore = new AttachmentFileStore(tempDir.toString());
            service = new TemporaryDocumentService(repo, fileStore, docReader);
        }
        return service;
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static TemporaryDocument document(String id, String name, String type, String status,
            String content, String chunks, int tokenCount, String imageRefs) {
        TemporaryDocument d = new TemporaryDocument();
        d.setId(id);
        d.setTenantId(TENANT);
        d.setSessionId(SESSION);
        d.setResourceRef("local://1/exports/" + id);
        d.setFileName(name);
        d.setFileType(type);
        d.setMimeType("");
        d.setFileSize(10L);
        d.setStatus(status);
        d.setContent(content);
        d.setChunks(chunks);
        d.setImageRefs(imageRefs);
        d.setMetadata("{}");
        d.setProcessingOptions("{}");
        d.setTokenCount(tokenCount);
        d.setChunkCount(0);
        d.setErrorMessage("");
        d.setExpiresAt(OffsetDateTime.now().plusHours(1));
        return d;
    }

    private static String chunk(int seq, String content, String contextHeader, int tokenCount) {
        StringBuilder sb = new StringBuilder("{\"seq\":").append(seq)
                .append(",\"content\":\"").append(content).append('"');
        if (contextHeader != null) {
            sb.append(",\"context_header\":\"").append(contextHeader).append('"');
        }
        return sb.append(",\"start\":0,\"end\":0,\"token_count\":").append(tokenCount).append('}')
                .toString();
    }

    private static String chunksJson(String... items) {
        return "[" + String.join(",", items) + "]";
    }

    // ── ResolveForPrompt：内容选择 ────────────────────────────────────────

    @Test
    void smallDocumentReturnsFullContent() {
        TemporaryDocument doc = document("d1", "notes.txt", ".txt", TemporaryDocument.STATUS_READY,
                "hello world", chunksJson(chunk(0, "hello", null, 3), chunk(1, "world", null, 3)),
                6, "[]");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(doc);

        TemporaryDocumentService.PromptResult result =
                service().resolveForPrompt(TENANT, SESSION, List.of("d1"), "hello");

        assertThat(result.attachments()).hasSize(1);
        MessageAttachment att = result.attachments().get(0);
        assertThat(att.getId()).isEqualTo("d1");
        assertThat(att.getFileName()).isEqualTo("notes.txt");
        assertThat(att.getContent()).isEqualTo("hello world");
        assertThat(att.getContentMode()).isEqualTo("full");
        assertThat(att.getSelectedChunks()).isEqualTo(2);
        assertThat(att.getTotalChunks()).isEqualTo(2);
        assertThat(result.imageUrls()).isEmpty();
    }

    @Test
    void chunksWithEmptyListReturnFullContent() {
        TemporaryDocument doc = document("d1", "a.txt", ".txt", TemporaryDocument.STATUS_READY,
                "body", "[]", 99, "[]");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(doc);

        MessageAttachment att = service().resolveForPrompt(TENANT, SESSION, List.of("d1"), "q")
                .attachments().get(0);
        assertThat(att.getContent()).isEqualTo("body");
        assertThat(att.getContentMode()).isEqualTo("full");
    }

    @Test
    void oversizedDocumentSelectsChunksByQueryTerms() {
        // tokenCount > 阈值 → 走选块：命中查询词的块先入选（6000+6000 总重超预算 12000，
        // 未命中的尾块被预算挤掉），装填后按 seq 升序用 \n\n---\n\n 拼接
        TemporaryDocument doc = document("d1", "big.md", ".md", TemporaryDocument.STATUS_READY,
                "FULL-CONTENT", chunksJson(
                        chunk(0, "filler one", null, 6000),
                        chunk(1, "deployment guide", "Chapter A", 40),
                        chunk(2, "filler two", null, 6000)),
                20_000, "[]");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(doc);

        TemporaryDocumentService.PromptResult result =
                service().resolveForPrompt(TENANT, SESSION, List.of("d1"), "deployment guide");

        MessageAttachment att = result.attachments().get(0);
        assertThat(att.getContent())
                .isEqualTo("filler one\n\n---\n\nChapter A\n\ndeployment guide");
        assertThat(att.getContentMode()).isEqualTo("selected_chunks");
        assertThat(att.getSelectedChunks()).isEqualTo(2);
        assertThat(att.getTotalChunks()).isEqualTo(3);
    }

    @Test
    void perDocumentBudgetSplitsAcrossAttachments() {
        // 两个附件 → 每份预算 6000；命中词的首块（4000 token）入选，第二块（4000）超预算被跳过
        TemporaryDocument d1 = document("d1", "a.md", ".md", TemporaryDocument.STATUS_READY,
                "A", chunksJson(chunk(0, "target alpha", null, 4000), chunk(1, "target beta", null, 4000)),
                20_000, "[]");
        TemporaryDocument d2 = document("d2", "b.md", ".md", TemporaryDocument.STATUS_READY,
                "B", chunksJson(chunk(0, "target gamma", null, 4000), chunk(1, "target delta", null, 4000)),
                20_000, "[]");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(d1);
        when(repo.getScoped(TENANT, SESSION, "d2")).thenReturn(d2);

        TemporaryDocumentService.PromptResult result =
                service().resolveForPrompt(TENANT, SESSION, List.of("d1", "d2"), "target");

        assertThat(result.attachments()).hasSize(2);
        assertThat(result.attachments().get(0).getSelectedChunks()).isEqualTo(1);
        assertThat(result.attachments().get(1).getSelectedChunks()).isEqualTo(1);
    }

    @Test
    void duplicateIdsResolveOnce() {
        TemporaryDocument doc = document("d1", "a.txt", ".txt", TemporaryDocument.STATUS_READY,
                "body", "[]", 1, "[]");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(doc);

        TemporaryDocumentService.PromptResult result =
                service().resolveForPrompt(TENANT, SESSION, List.of("d1", "d1"), "q");
        assertThat(result.attachments()).hasSize(1);
    }

    // ── ResolveForPrompt：图片 URL ────────────────────────────────────────

    @Test
    void imageAttachmentExposesImageUrls() {
        TemporaryDocument doc = document("d1", "gac.png", ".png", TemporaryDocument.STATUS_READY,
                "![gac.png](local://1/exports/gac.png)", "[]", 7,
                "[{\"original_ref\":\"images/gac.png\",\"url\":\"local://1/exports/gac.png\","
                        + "\"mime_type\":\"image/png\"}]");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(doc);

        TemporaryDocumentService.PromptResult result =
                service().resolveForPrompt(TENANT, SESSION, List.of("d1"), "这是什么");

        assertThat(result.imageUrls()).containsExactly("local://1/exports/gac.png");
    }

    @Test
    void visualQueryPullsImageUrlsFromTextDocument() {
        TemporaryDocument doc = document("d1", "report.pdf", ".pdf", TemporaryDocument.STATUS_READY,
                "text", "[]", 3,
                "[{\"url\":\"local://1/exports/a.png\"},{\"url\":\"\"},{\"url\":\"local://1/exports/b.png\"}]");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(doc);

        // 非图片格式 + 视觉意图关键词（"表格"）→ 带上抽取图
        TemporaryDocumentService.PromptResult visual =
                service().resolveForPrompt(TENANT, SESSION, List.of("d1"), "看一下表格");
        assertThat(visual.imageUrls())
                .containsExactly("local://1/exports/a.png", "local://1/exports/b.png");

        // 无视觉意图 → 不带
        TemporaryDocumentService.PromptResult plain =
                service().resolveForPrompt(TENANT, SESSION, List.of("d1"), "总结一下");
        assertThat(plain.imageUrls()).isEmpty();
    }

    @Test
    void legacyImageRowWithoutRefsFallsBackToSourceFile() {
        // 收口前的历史行：image_refs 恒 "[]" —— 图片型附件回退到源文件（原图即源文件）
        TemporaryDocument doc = document("d1", "legacy.png", ".png", TemporaryDocument.STATUS_READY,
                "![legacy.png](images/legacy.png)", "[]", 7, "[]");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(doc);

        assertThat(service().resolveForPrompt(TENANT, SESSION, List.of("d1"), "这是什么").imageUrls())
                .containsExactly("local://1/exports/d1");
    }

    @Test
    void legacyTextRowDoesNotFallBackToSourceFile() {
        // 文本文档的历史行没有可回退的原图（源文件是 PDF 不是图片）→ 不产出 ImageURL
        TemporaryDocument doc = document("d1", "legacy.pdf", ".pdf", TemporaryDocument.STATUS_READY,
                "text", "[]", 7, "[]");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(doc);

        assertThat(service().resolveForPrompt(TENANT, SESSION, List.of("d1"), "看一下表格").imageUrls())
                .isEmpty();
    }

    @Test
    void imageUrlsAreCappedAtFour() {
        TemporaryDocument doc = document("d1", "gac.png", ".png", TemporaryDocument.STATUS_READY,
                "x", "[]", 1,
                "[{\"url\":\"u1\"},{\"url\":\"u2\"},{\"url\":\"u3\"},{\"url\":\"u4\"},{\"url\":\"u5\"},{\"url\":\"u6\"}]");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(doc);

        assertThat(service().resolveForPrompt(TENANT, SESSION, List.of("d1"), "看图").imageUrls())
                .containsExactly("u1", "u2", "u3", "u4");
    }

    // ── ResolveForPrompt：错误语义 ────────────────────────────────────────

    @Test
    void missingDocumentFails() {
        when(repo.getScoped(TENANT, SESSION, "d9")).thenReturn(null);

        assertThatThrownBy(() ->
                service().resolveForPrompt(TENANT, SESSION, List.of("d9"), "q"))
                .isInstanceOf(TemporaryDocumentService.AttachmentResolveException.class)
                .hasMessage("attachment d9 was not found in this session");
    }

    @Test
    void failedDocumentReportsParseError() {
        TemporaryDocument doc = document("d1", "bad.pdf", ".pdf", TemporaryDocument.STATUS_FAILED,
                "", "[]", 0, "[]");
        doc.setErrorMessage("parse document: boom");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(doc);

        assertThatThrownBy(() ->
                service().resolveForPrompt(TENANT, SESSION, List.of("d1"), "q"))
                .isInstanceOf(TemporaryDocumentService.AttachmentResolveException.class)
                .hasMessage("attachment bad.pdf failed to parse: parse document: boom");
    }

    @Test
    void processingDocumentIsRejected() {
        TemporaryDocument doc = document("d1", "slow.pdf", ".pdf", TemporaryDocument.STATUS_PROCESSING,
                "", "[]", 0, "[]");
        when(repo.getScoped(TENANT, SESSION, "d1")).thenReturn(doc);

        assertThatThrownBy(() ->
                service().resolveForPrompt(TENANT, SESSION, List.of("d1"), "q"))
                .isInstanceOf(TemporaryDocumentService.AttachmentResolveException.class)
                .hasMessage("attachment slow.pdf is still being processed");
    }

    @Test
    void moreThanFiveAttachmentsRejected() {
        List<String> ids = Arrays.asList("a", "b", "c", "d", "e", "f");

        assertThatThrownBy(() -> service().resolveForPrompt(TENANT, SESSION, ids, "q"))
                .isInstanceOf(TemporaryDocumentService.AttachmentResolveException.class)
                .hasMessage("a message can use at most 5 attachments");
    }

    // ── 图片落地（storeDocumentImages） ───────────────────────────────────

    @Test
    void storesInlineImageAndRewritesMarkdown() {
        byte[] png = pngBytes(200, 150);
        TemporaryDocument doc = document("d1", "gac.png", ".png", TemporaryDocument.STATUS_READY,
                "", "[]", 0, "[]");
        DocReaderClient.ImageRef ref = new DocReaderClient.ImageRef(
                "gac.png", "images/gac.png", "image/png", png);

        TemporaryDocumentService.StoredImages stored = service()
                .storeDocumentImages(TENANT, doc, List.of(ref), "![gac.png](images/gac.png)");

        assertThat(stored.markdown()).startsWith("![gac.png](local://1/exports/")
                .endsWith(".png)");
        assertThat(stored.imageRefsJson())
                .contains("\"original_ref\":\"images/gac.png\"")
                .contains("\"mime_type\":\"image/png\"");
        // 落盘字节可读回（真文件存储）
        String url = TemporaryDocumentService.imageUrlsOf(stored.imageRefsJson()).get(0);
        assertThat(stored.markdown()).isEqualTo("![gac.png](" + url + ")");
        assertThat(fileStore.getFile(url)).isEqualTo(png);
    }

    @Test
    void tileImageIsFilteredForNonImageDocument() {
        // 非图片来源文档 + 48x48 小图 → 图标过滤（对照 Go isIconImage）
        byte[] icon = pngBytes(48, 48);
        TemporaryDocument doc = document("d1", "report.pdf", ".pdf", TemporaryDocument.STATUS_READY,
                "", "[]", 0, "[]");
        DocReaderClient.ImageRef ref = new DocReaderClient.ImageRef(
                "icon.png", "images/icon.png", "image/png", icon);

        TemporaryDocumentService.StoredImages stored = service()
                .storeDocumentImages(TENANT, doc, List.of(ref), "![icon](images/icon.png)");

        assertThat(stored.imageRefsJson()).isEqualTo("[]");
        assertThat(stored.markdown()).isEqualTo("![icon](images/icon.png)");
    }

    @Test
    void smallImageOfImageDocumentIsKept() {
        // 图片型附件不过滤（对照 Go SimpleFormatReader 的 IsOriginal 语义）
        byte[] small = pngBytes(32, 32);
        TemporaryDocument doc = document("d1", "tiny.png", ".png", TemporaryDocument.STATUS_READY,
                "", "[]", 0, "[]");
        DocReaderClient.ImageRef ref = new DocReaderClient.ImageRef(
                "tiny.png", "images/tiny.png", "image/png", small);

        TemporaryDocumentService.StoredImages stored = service()
                .storeDocumentImages(TENANT, doc, List.of(ref), "![tiny](images/tiny.png)");

        assertThat(TemporaryDocumentService.imageUrlsOf(stored.imageRefsJson())).hasSize(1);
    }

    @Test
    void localUrlResolvesToDataUriForVisionModels() {
        // 最后一公里：LLM 发送前把 local:// 手柄读成字节并转 base64 data URI
        // （对照 Go resolveImageURLForLLM；装配路径见 ChatLocalImageResolverWiring）
        service();
        byte[] png = pngBytes(64, 64);
        String url = fileStore.saveBytes(png, TENANT, "probe.png");
        com.ragagent.llm.chat.ImageResolver.setLocalImageResolver(fileStore::getFile);
        try {
            String resolved = com.ragagent.llm.chat.ImageResolver.resolveImageUrlForLlm(url);
            assertThat(resolved).startsWith("data:image/png;base64,");
        } finally {
            com.ragagent.llm.chat.ImageResolver.setLocalImageResolver(null);
        }
    }

    @Test
    void referenceWithoutInlineBytesIsKeptAsIs() {
        TemporaryDocument doc = document("d1", "a.pdf", ".pdf", TemporaryDocument.STATUS_READY,
                "", "[]", 0, "[]");
        DocReaderClient.ImageRef ref =
                new DocReaderClient.ImageRef("a.png", "images/a.png", "image/png", new byte[0]);

        TemporaryDocumentService.StoredImages stored = service()
                .storeDocumentImages(TENANT, doc, List.of(ref), "![a](images/a.png)");

        assertThat(stored.imageRefsJson()).isEqualTo("[]");
        assertThat(stored.markdown()).isEqualTo("![a](images/a.png)");
    }

    @Test
    void titledImageTargetIsRewritten() {
        byte[] png = pngBytes(120, 120);
        TemporaryDocument doc = document("d1", "chart.png", ".png", TemporaryDocument.STATUS_READY,
                "", "[]", 0, "[]");
        DocReaderClient.ImageRef ref =
                new DocReaderClient.ImageRef("chart.png", "images/chart.png", "image/png", png);

        TemporaryDocumentService.StoredImages stored = service().storeDocumentImages(
                TENANT, doc, List.of(ref), "![图片](images/chart.png \"标题\")");

        String url = TemporaryDocumentService.imageUrlsOf(stored.imageRefsJson()).get(0);
        // title 与右括号必须原样保留（换成路径本体之外的整段不动）
        assertThat(stored.markdown()).isEqualTo("![图片](" + url + " \"标题\")");
    }

    // ── 纯函数族 ─────────────────────────────────────────────────────────

    @Test
    void queryTermsSplitWordsAndHanBigrams() {
        // 空白/标点切出的整段（连续汉字不切）+ 相邻汉字二元组（对照 Go temporaryDocumentQueryTerms）
        assertThat(TemporaryDocumentService.queryTerms("deployment 图表的说明"))
                .containsExactly("deployment", "图表的说明", "图表", "表的", "的说", "说明");
        assertThat(TemporaryDocumentService.queryTerms("a 图")).isEmpty(); // 单字词与单字汉字不成词
    }

    @Test
    void visualDocumentQueryMarkers() {
        assertThat(TemporaryDocumentService.isVisualDocumentQuery("这张图是什么")).isTrue();
        assertThat(TemporaryDocumentService.isVisualDocumentQuery("show me the LAYOUT")).isTrue();
        assertThat(TemporaryDocumentService.isVisualDocumentQuery("你好")).isFalse();
    }

    @Test
    void imageFormatAndExtFromMime() {
        assertThat(TemporaryDocumentService.isImageFormat(".PNG")).isTrue();
        assertThat(TemporaryDocumentService.isImageFormat("webp")).isTrue();
        assertThat(TemporaryDocumentService.isImageFormat(".pdf")).isFalse();
        assertThat(TemporaryDocumentService.extFromMime("image/jpeg")).isEqualTo(".jpg");
        assertThat(TemporaryDocumentService.extFromMime("image/tiff")).isEmpty();
    }

    @Test
    void countOccurrencesIsNonOverlapping() {
        assertThat(TemporaryDocumentService.countOccurrences("aaa", "aa")).isEqualTo(1);
        assertThat(TemporaryDocumentService.countOccurrences("ababab", "ab")).isEqualTo(3);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** 生成可解码的 PNG（图标过滤需要真实尺寸）。 */
    private static byte[] pngBytes(int width, int height) {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
