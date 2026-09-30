package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.TemporaryDocument;
import com.ragagent.session.mapper.TemporaryDocumentRepository;

/**
 * {@code TemporaryDocumentService} 的**提示词渲染切片**（§14 步骤 2 拆分）：把 ready 的
 * 临时附件按 token 预算选内容，产出提示词附件列表 + 给 vision 模型的图片 URL。
 *
 * <p>与门面同包、只持 {@code repo} 一个引用（同 {@code SessionQaResolution}/
 * {@code SessionQaFallback} 先例：门面保留公开入口并薄委托）。对外 API 的返回/异常类型
 * （{@link TemporaryDocumentService.PromptResult}/
 * {@link TemporaryDocumentService.AttachmentResolveException}）留在门面，本类限定引用；
 * 双用助手（格式判定 {@code isImageFormat}、jsonb 数组读取 {@code readJsonArray}）
 * 也留在门面（存储与删除路径同样在调），本类按类名调用。</p>
 */
final class TemporaryDocumentPromptResolver {

    /** 对照 Go {@code temporaryDocumentPromptBudget}。 */
    static final int PROMPT_BUDGET_TOKENS = 12_000;

    /** 对照 Go {@code temporaryDocumentInlineTokens}：低于它直接给全文。 */
    static final int PROMPT_INLINE_TOKENS = 12_000;

    /** 对照 Go {@code temporaryDocumentMaxPromptParts}。 */
    static final int MAX_PROMPT_PARTS = 16;

    /** 对照 Go ResolveForPrompt 的硬编码上限 {@code len(result.ImageURLs) < 4}。 */
    static final int MAX_IMAGE_URLS = 4;

    /** 对照 Go {@code isVisualDocumentQuery} 的标记词表。 */
    private static final List<String> VISUAL_QUERY_MARKERS =
            List.of("图", "表格", "截图", "页面", "排版", "chart", "figure", "diagram", "image", "layout");

    private final TemporaryDocumentRepository repo;

    TemporaryDocumentPromptResolver(TemporaryDocumentRepository repo) {
        this.repo = repo;
    }

    /**
     * 对照 Go {@code ResolveForPrompt}：把 ready 的临时附件按预算选内容，产出
     * 提示词附件列表 + 给 vision 模型的图片 URL（≤ {@value #MAX_IMAGE_URLS} 个）。
     *
     * <p>错误语义照 Go：文档缺失 / failed / 未 ready 都是 error（调用方记 warn 并
     * 放弃本轮附件注入，不让回合失败）。</p>
     */
    TemporaryDocumentService.PromptResult resolveForPrompt(long tenantId, String sessionId,
            List<String> documentIds, String query) {
        List<MessageAttachment> attachments = new ArrayList<>();
        List<String> imageUrls = new ArrayList<>();
        if (documentIds == null || documentIds.isEmpty()) {
            return new TemporaryDocumentService.PromptResult(attachments, imageUrls);
        }
        if (documentIds.size() > TemporaryDocumentService.MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new TemporaryDocumentService.AttachmentResolveException("a message can use at most "
                    + TemporaryDocumentService.MAX_ATTACHMENTS_PER_MESSAGE + " attachments");
        }
        int perDocumentBudget = PROMPT_BUDGET_TOKENS / documentIds.size();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (String documentId : documentIds) {
            if (!seen.add(documentId)) {
                continue;
            }
            TemporaryDocument document = repo.getScoped(tenantId, sessionId, documentId);
            if (document == null) {
                throw new TemporaryDocumentService.AttachmentResolveException(
                        "attachment " + documentId + " was not found in this session");
            }
            if (!TemporaryDocument.STATUS_READY.equals(document.getStatus())) {
                if (TemporaryDocument.STATUS_FAILED.equals(document.getStatus())) {
                    throw new TemporaryDocumentService.AttachmentResolveException(
                            "attachment " + document.getFileName()
                                    + " failed to parse: " + document.getErrorMessage());
                }
                throw new TemporaryDocumentService.AttachmentResolveException(
                        "attachment " + document.getFileName()
                                + " is still being processed");
            }
            ContentSelection selection = selectContent(document, parseChunks(document.getChunks()),
                    query, perDocumentBudget);
            MessageAttachment att = new MessageAttachment();
            att.setId(document.getId());
            att.setUrl(document.getResourceRef());
            att.setFileName(document.getFileName());
            att.setFileType(document.getFileType());
            att.setFileSize(document.getFileSize());
            att.setContent(selection.content());
            att.setContentMode(selection.selected() == selection.total()
                    ? "full" : "selected_chunks");
            att.setTokenCount(document.getTokenCount());
            att.setSelectedChunks(selection.selected());
            att.setTotalChunks(selection.total());
            attachments.add(att);
            // 图片型附件恒暴露原图给 vision 模型；文本文档只在问题带视觉意图时附带
            // 抽取图（对照 Go 注释：避免无谓的多模态时延）。
            if (TemporaryDocumentService.isImageFormat(document.getFileType())
                    || isVisualDocumentQuery(query)) {
                for (String url : imageUrlsOf(document.getImageRefs())) {
                    if (imageUrls.size() >= MAX_IMAGE_URLS) {
                        break;
                    }
                    imageUrls.add(url);
                }
            }
        }
        return new TemporaryDocumentService.PromptResult(attachments, imageUrls);
    }

    /** selectContent 的三元返回（对照 Go 的 (content, selected, total)）。 */
    record ContentSelection(String content, int selected, int total) {
    }

    /** 对照 Go TemporaryDocumentChunk（jsonb 元素形态）。 */
    record DocumentChunk(int seq, String content, String contextHeader, int tokenCount) {
    }

    static List<DocumentChunk> parseChunks(String chunksJson) {
        List<DocumentChunk> out = new ArrayList<>();
        for (Map<?, ?> raw : TemporaryDocumentService.readJsonArray(chunksJson)) {
            out.add(new DocumentChunk(
                    intOf(raw.get("seq")),
                    strOf(raw.get("content")),
                    strOf(raw.get("context_header")),
                    intOf(raw.get("token_count"))));
        }
        return out;
    }

    /** 对照 Go temporaryDocumentImageRefs：image_refs jsonb → 非空 URL 列表。 */
    static List<String> imageUrlsOf(String imageRefsJson) {
        List<String> out = new ArrayList<>();
        for (Map<?, ?> ref : TemporaryDocumentService.readJsonArray(imageRefsJson)) {
            Object url = ref.get("url");
            if (url instanceof String s && !s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 对照 Go {@code selectTemporaryDocumentContentWithBudget}（L650-705）：
     * 全文（chunks 空或 token 数不超阈值/预算），否则按查询词打分选块
     * （score = Σ 出现次数 × (1 + 词长/2)，降序稳定；按预算与 16 块上限装填，
     * 最后按 seq 升序用 {@code \n\n---\n\n} 拼装）。
     */
    static ContentSelection selectContent(TemporaryDocument document, List<DocumentChunk> chunks,
            String query, int budget) {
        if (budget <= 0) {
            budget = PROMPT_BUDGET_TOKENS;
        }
        if (chunks.isEmpty()
                || (document.getTokenCount() <= PROMPT_INLINE_TOKENS
                        && document.getTokenCount() <= budget)) {
            return new ContentSelection(document.getContent(), chunks.size(), chunks.size());
        }
        List<String> terms = queryTerms(query);
        List<Integer> order = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            order.add(i);
        }
        List<Integer> scores = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            String text = (strOf(chunk.contextHeader()) + "\n" + strOf(chunk.content()))
                    .toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : terms) {
                score += countOccurrences(text, term)
                        * (1 + term.codePointCount(0, term.length()) / 2);
            }
            scores.add(score);
        }
        order.sort((a, b) -> {
            int cmp = Integer.compare(scores.get(b), scores.get(a));
            return cmp != 0 ? cmp : Integer.compare(chunks.get(a).seq(), chunks.get(b).seq());
        });
        List<DocumentChunk> selected = new ArrayList<>();
        int tokens = 0;
        for (int idx : order) {
            if (selected.size() >= MAX_PROMPT_PARTS) {
                break;
            }
            DocumentChunk candidate = chunks.get(idx);
            if (tokens > 0 && tokens + candidate.tokenCount() > budget) {
                continue;
            }
            selected.add(candidate);
            tokens += candidate.tokenCount();
        }
        selected.sort((a, b) -> Integer.compare(a.seq(), b.seq()));
        StringBuilder builder = new StringBuilder();
        for (DocumentChunk part : selected) {
            if (builder.length() > 0) {
                builder.append("\n\n---\n\n");
            }
            if (part.contextHeader() != null && !part.contextHeader().isEmpty()) {
                builder.append(part.contextHeader()).append("\n\n");
            }
            builder.append(part.content() == null ? "" : part.content().trim());
        }
        return new ContentSelection(builder.toString(), selected.size(), chunks.size());
    }

    /** 对照 Go temporaryDocumentQueryTerms（L715-739）：词元 + 相邻汉字二元组。 */
    static List<String> queryTerms(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (String field : q.split("[\\s\\p{P}]+")) {
            if (field.codePointCount(0, field.length()) < 2) {
                continue;
            }
            if (seen.add(field)) {
                terms.add(field);
            }
        }
        int[] cps = q.codePoints().toArray();
        for (int i = 0; i + 1 < cps.length; i++) {
            if (isHan(cps[i]) && isHan(cps[i + 1])) {
                String term = new String(cps, i, 2);
                if (seen.add(term)) {
                    terms.add(term);
                }
            }
        }
        return terms;
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    /** 对照 Go isVisualDocumentQuery（L741-749）。 */
    static boolean isVisualDocumentQuery(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (String marker : VISUAL_QUERY_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 对照 Go strings.Count（非重叠子串计数）。 */
    static int countOccurrences(String text, String term) {
        if (text == null || term == null || term.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = text.indexOf(term); i >= 0; i = text.indexOf(term, i + term.length())) {
            count++;
        }
        return count;
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String strOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
