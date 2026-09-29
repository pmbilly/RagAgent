package com.ragagent.knowledge.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.web.NonNullBody;
import com.ragagent.knowledge.dto.ChunkerDtos;
import com.ragagent.knowledge.dto.ChunkerDtos.PreviewPayload;
import com.ragagent.knowledge.dto.ChunkerDtos.PreviewRequest;
import com.ragagent.knowledge.chunker.Chunker;
import com.ragagent.knowledge.chunker.DocumentProfiler;
import com.ragagent.knowledge.chunker.ParsedChunk;
import com.ragagent.knowledge.chunker.SplitterConfig;
import com.ragagent.knowledge.chunker.TextNormalizer;
import com.ragagent.knowledge.chunker.Tokens;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * chunker 只读预览端点。无状态：不落库、不生成 embedding、不打日志正文。
 * <h2>响应形态：struct 声明序 + map 字母序的混合（契约样例 cprev-*.json 全钉）</h2>
 * <p>顶层 {@code gin.H} 字母序 {@code {"data":…,"success":true}}；data 是
 * PreviewChunkingResponse struct——按<b>声明序</b>输出
 * {@code selected_tier, tier_chain, rejected, profile, chunks, stats}。
 * {@code chunks} 用 make 初始化恒 {@code []}。</p>
 * <h2>profile 里的两类编码陷阱</h2>
 * <ul>
 *   <li>double 字段（avg_line_len/std_line_len/code_ratio）走标准 Jackson 输出（整数值带 {@code .0}）</li>
 *   <li>空表恒 {@code {}}（profiler 恒 make）——Java 用按键排序的 LinkedHashMap</li>
 * </ul>
 * <h2>策略解析的实测契约（契约样例锁定）</h2>
 * <p>strategy 空串/legacy/recursive → 链 {@code [legacy]}（<b>不是</b> auto！），
 * diag.profile 为 null 由 handler 调 ProfileDocument 物化；未知 strategy 落
 * default 分支走 auto 画像。tier_chain 在响应里恒非 null（文本非空时）。</p>
 * <h2>超时与截断</h2>
 * <p>文本上限 64k rune（超限 413）、分块上限 500（stats 按全集算，
 * truncated_to 记原始数量，omitempty）、5s 超时 504。切分是 CPU 密集且不接受
 * context——Java 用虚拟线程 + Future.get(5s) 仿真，<b>超时不 cancel</b>
 * 。</p>
 */
@RestController
public class ChunkerDebugController {

    /** 64k rune 上限（防 goroutine 堆积的主缓解）。 */
    static final int PREVIEW_MAX_CHARS = 64 * 1024;

    /** 响应截断上限（stats 不受影响）。 */
    static final int PREVIEW_MAX_CHUNKS = 500;

    /**  */
    static final long PREVIEW_TIMEOUT_SECONDS = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 虚拟线程池：切分 CPU 密集，超时后让线程自然跑完。 */
    private static final ExecutorService CHUNKER_POOL = Executors.newVirtualThreadPerTaskExecutor();

    // ── 响应体 ───

    static final class PreviewResponse {
        @JsonProperty("selected_tier")
        String selectedTier;
        @JsonProperty("tier_chain")
        List<String> tierChain;
        @JsonProperty("rejected")
        List<TierRejectionDto> rejected;
        @JsonProperty("profile")
        ProfileDto profile;
        @JsonProperty("chunks")
        List<ChunkDto> chunks = new ArrayList<>();
        @JsonProperty("stats")
        StatsDto stats;
    }

    static final class TierRejectionDto {
        @JsonProperty("tier")
        String tier;
        @JsonProperty("reason")
        String reason;

        TierRejectionDto(String tier, String reason) {
            this.tier = tier;
            this.reason = reason;
        }
    }

    static final class ProfileDto {
        @JsonProperty("total_chars")
        int totalChars;
        @JsonProperty("total_lines")
        int totalLines;
        @JsonProperty("avg_line_len")
        double avgLineLen;
        @JsonProperty("std_line_len")
        double stdLineLen;
        @JsonProperty("md_heading_counts")
        Map<Integer, Integer> mdHeadingCounts = new LinkedHashMap<>();
        @JsonProperty("md_heading_total")
        int mdHeadingTotal;
        @JsonProperty("numbered_section_count")
        int numberedSectionCount;
        @JsonProperty("all_caps_short_line_count")
        int allCapsShortLineCount;
        @JsonProperty("blank_paragraph_breaks")
        int blankParagraphBreaks;
        @JsonProperty("form_feed_count")
        int formFeedCount;
        @JsonProperty("visual_sep_count")
        int visualSepCount;
        @JsonProperty("german_chapter_count")
        int germanChapterCount;
        @JsonProperty("english_chapter_count")
        int englishChapterCount;
        @JsonProperty("chinese_chapter_count")
        int chineseChapterCount;
        @JsonProperty("repeated_footer_count")
        int repeatedFooterCount;
        @JsonProperty("has_tables")
        boolean hasTables;
        @JsonProperty("has_code")
        boolean hasCode;
        @JsonProperty("code_ratio")
        double codeRatio;
        @JsonProperty("detected_langs")
        List<String> detectedLangs = new ArrayList<>();
    }

    static final class ChunkDto {
        @JsonProperty("seq")
        int seq;
        @JsonProperty("start")
        int start;
        @JsonProperty("end")
        int end;
        @JsonProperty("size_chars")
        int sizeChars;
        @JsonProperty("size_tokens_approx")
        int sizeTokensApprox;
        /** JSON 空值省略：空串省略。 */
        @JsonProperty("context_header")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        String contextHeader;
        @JsonProperty("content")
        String content;
    }

    static final class StatsDto {
        @JsonProperty("count")
        int count;
        @JsonProperty("avg_chars")
        int avgChars;
        @JsonProperty("min_chars")
        int minChars;
        @JsonProperty("max_chars")
        int maxChars;
        @JsonProperty("stddev_chars")
        int stddevChars;
        /** JSON 空值省略：0 省略。 */
        @JsonProperty("truncated_to")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int truncatedTo;
    }

    /** 切分结果 + 诊断（parent-child 时 chunks = children）。 */
    private record SplitOutcome(List<ParsedChunk> chunks, Chunker.Diagnostics diag) {
    }

    /**
     * 绑定失败 → 400 裸错误体 {@code {"error":"invalid request body: …","success":false}}
     * ——该调试端点专属形态（非标准信封），契约样例 cprev-bad-body 锁定。
     */
    @org.springframework.web.bind.annotation.ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            com.ragagent.common.error.BizException.class})
    public ResponseEntity<Map<String, Object>> handleBind(Exception ex) {
        String detail = ex instanceof com.ragagent.common.error.BizException
                ? "请求体不能为空" : "请求体格式不正确";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid request body: " + detail);
        body.put("success", false);
        return ResponseEntity.status(400).body(body);
    }

    @PostMapping("/api/v1/chunker/preview")
    public ResponseEntity<Map<String, Object>> previewChunking(
            @NonNullBody @RequestBody PreviewRequest req) {
        String text = req.text() == null ? "" : req.text();

        if (text.strip().isEmpty()) {
            return ResponseEntity.status(400).body(errorBody(
                    "text is empty — paste a sample to preview chunking"));
        }
        // rune 计数（非 char 计）
        if (text.codePointCount(0, text.length()) > PREVIEW_MAX_CHARS) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "text exceeds preview limit");
            body.put("limit", PREVIEW_MAX_CHARS);
            body.put("success", false);
            return ResponseEntity.status(413).body(body);
        }
        String normalized = TextNormalizer.normalizeLineEndings(text);

        SplitterConfig cfg = new SplitterConfig();
        PreviewPayload payload = req.chunkingConfig() == null ? new PreviewPayload(
                null, null, null, null, null, null, null, null, null) : req.chunkingConfig();
        cfg.setChunkSize(orZero(payload.chunkSize()));
        cfg.setChunkOverlap(orZero(payload.chunkOverlap()));
        if (payload.separators() != null) {
            cfg.setSeparators(payload.separators());
        }
        cfg.setStrategy(payload.strategy() == null ? "" : payload.strategy());
        cfg.setTokenLimit(orZero(payload.tokenLimit()));
        if (payload.languages() != null) {
            cfg.setLanguages(payload.languages());
        }
        cfg = Chunker.normalizeSplitterConfig(cfg);
        final SplitterConfig effectiveCfg = cfg;

        // 切分在独立虚拟线程上跑；
        // 超时不 cancel——切分会自然跑完，只是调用方先走。
        boolean parentChild = Boolean.TRUE.equals(payload.enableParentChild());
        Future<SplitOutcome> future = CHUNKER_POOL.submit(
                () -> runSplit(normalized, effectiveCfg, payload, parentChild));
        SplitOutcome outcome;
        try {
            outcome = future.get(PREVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ResponseEntity.status(504).body(errorBody("chunker preview timed out"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body(errorBody("chunker preview interrupted"));
        } catch (ExecutionException e) {
            return ResponseEntity.status(500).body(errorBody("chunker preview failed"));
        }

        List<ParsedChunk> chunks = outcome.chunks();
        DocumentProfiler.DocProfile profile = outcome.diag().profile();
        if (profile == null) {
            // 显式非 auto 策略不经画像——handler 物化一份给 UI（避免二次切分）
            profile = DocumentProfiler.profileDocument(normalized);
        }

        String lang = Tokens.LANG_MIXED;
        if (!profile.detectedLangs.isEmpty()) {
            lang = profile.detectedLangs.get(0);
        }

        // 每个 chunk 的 rune 长度算一次，stats 与结果负载共用
        List<Integer> runeLens = new ArrayList<>(chunks.size());
        for (ParsedChunk ch : chunks) {
            runeLens.add(ch.getContent().codePointCount(0, ch.getContent().length()));
        }

        // stats 先按全集算，再截断响应（指标保持代表性）
        int totalCount = chunks.size();
        StatsDto stats = computeChunkSizeStats(runeLens);
        if (totalCount > PREVIEW_MAX_CHUNKS) {
            stats.truncatedTo = totalCount;
            chunks = chunks.subList(0, PREVIEW_MAX_CHUNKS);
        }

        PreviewResponse resp = new PreviewResponse();
        resp.selectedTier = tierToGo(outcome.diag().selectedTier());
        resp.tierChain = new ArrayList<>();
        if (outcome.diag().tierChain() != null) {
            for (DocumentProfiler.StrategyTier t : outcome.diag().tierChain()) {
                resp.tierChain.add(tierToGo(t));
            }
        }
        resp.rejected = null;
        if (outcome.diag().rejected() != null) {
            resp.rejected = new ArrayList<>();
            for (Chunker.TierRejection r : outcome.diag().rejected()) {
                resp.rejected.add(new TierRejectionDto(tierToGo(r.tier()), r.reason()));
            }
        }
        resp.profile = toProfileDto(profile);
        for (int i = 0; i < chunks.size(); i++) {
            ParsedChunk ch = chunks.get(i);
            ChunkDto dto = new ChunkDto();
            dto.seq = ch.getSeq();
            dto.start = ch.getStart();
            dto.end = ch.getEnd();
            dto.sizeChars = runeLens.get(i);
            dto.sizeTokensApprox = Tokens.approxTokenCountFromRuneLen(runeLens.get(i), lang);
            dto.contextHeader = ch.getContextHeader();
            dto.content = ch.getContent();
            resp.chunks.add(dto);
        }
        resp.stats = stats;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", resp);
        body.put("success", true);
        return ResponseEntity.status(200).body(body);
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private SplitOutcome runSplit(String text, SplitterConfig cfg, PreviewPayload payload,
            boolean parentChild) {
        if (parentChild) {
            Chunker.ParentChildConfigs configs = Chunker.deriveParentChildConfigs(cfg,
                    orZero(payload.parentChunkSize()), orZero(payload.childChunkSize()));
            Chunker.ParentChildDiagnostics result =
                    Chunker.splitParentChildWithDiagnostics(text, configs.parent(), configs.child());
            return new SplitOutcome(result.result().children(), result.diagnostics());
        }
        Chunker.SplitResult sr = Chunker.splitWithDiagnostics(text, cfg);
        return new SplitOutcome(sr.chunks(), sr.diagnostics());
    }


    /** 均值/方差走 float64 再截断。 */
    private static StatsDto computeChunkSizeStats(List<Integer> runeLens) {
        StatsDto stats = new StatsDto();
        stats.count = runeLens.size();
        if (runeLens.isEmpty()) {
            return stats;
        }
        double sum = 0;
        double sumSq = 0;
        int minLen = Integer.MAX_VALUE;
        int maxLen = 0;
        for (int l : runeLens) {
            sum += l;
            sumSq += (double) l * l;
            if (l < minLen) {
                minLen = l;
            }
            if (l > maxLen) {
                maxLen = l;
            }
        }
        double avg = sum / runeLens.size();
        double variance = sumSq / runeLens.size() - avg * avg;
        if (variance < 0) {
            // 近均匀输入上浮点精度可把方差推成极小负数——钳到 0 防 NaN
            variance = 0;
        }
        stats.avgChars = (int) (avg + 0.5);
        stats.minChars = minLen;
        stats.maxChars = maxLen;
        stats.stddevChars = (int) (Math.sqrt(variance) + 0.5);
        return stats;
    }

    private static ProfileDto toProfileDto(DocumentProfiler.DocProfile p) {
        ProfileDto dto = new ProfileDto();
        dto.totalChars = p.totalChars;
        dto.totalLines = p.totalLines;
        dto.avgLineLen = p.avgLineLen;
        dto.stdLineLen = p.stdLineLen;
        List<Integer> levels = new ArrayList<>(p.mdHeadingCounts.keySet());
        levels.sort(Integer::compareTo);
        for (int level : levels) {
            dto.mdHeadingCounts.put(level, p.mdHeadingCounts.get(level));
        }
        dto.mdHeadingTotal = p.mdHeadingTotal;
        dto.numberedSectionCount = p.numberedSectionCount;
        dto.allCapsShortLineCount = p.allCapsShortLineCount;
        dto.blankParagraphBreaks = p.blankParagraphBreaks;
        dto.formFeedCount = p.formFeedCount;
        dto.visualSepCount = p.visualSepCount;
        dto.germanChapterCount = p.germanChapterCount;
        dto.englishChapterCount = p.englishChapterCount;
        dto.chineseChapterCount = p.chineseChapterCount;
        dto.repeatedFooterCount = p.repeatedFooterCount;
        dto.hasTables = p.hasTables;
        dto.hasCode = p.hasCode;
        dto.codeRatio = p.codeRatio;
        dto.detectedLangs = new ArrayList<>(p.detectedLangs);
        return dto;
    }

    private static String tierToGo(DocumentProfiler.StrategyTier tier) {
        return switch (tier) {
            case HEADING -> "heading";
            case HEURISTIC -> "heuristic";
            case LEGACY -> "legacy";
        };
    }

    /** preview 的错误体是裸 gin.H（非 AppError 信封）：键字母序。 */
    private static Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("success", false);
        return body;
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }
}
