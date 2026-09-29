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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.common.web.NonNullBody;
import com.ragagent.knowledge.dto.PreviewPayload;
import com.ragagent.knowledge.dto.PreviewRequest;
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
 * chunker 只读预览端点（分块配置预览：文本进、分块结果出）。无状态：不落库、不生成 embedding、不打日志正文。
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
public class ChunkerPreviewController {

    /** 64k rune 上限（防 goroutine 堆积的主缓解）。 */
    static final int PREVIEW_MAX_CHARS = 64 * 1024;

    /** 响应截断上限（stats 不受影响）。 */
    static final int PREVIEW_MAX_CHUNKS = 500;

    static final long PREVIEW_TIMEOUT_SECONDS = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 虚拟线程池：切分 CPU 密集，超时后让线程自然跑完。 */
    private static final ExecutorService CHUNKER_POOL = Executors.newVirtualThreadPerTaskExecutor();

    // ── 响应体 ───

    static final class PreviewResponse {
        public String selectedTier;
        public List<String> tierChain;
        public List<TierRejectionDto> rejected;
        public ProfileDto profile;
        public List<ChunkDto> chunks = new ArrayList<>();
        public StatsDto stats;
    }

    static final class TierRejectionDto {
        public String tier;
        public String reason;

        TierRejectionDto(String tier, String reason) {
            this.tier = tier;
            this.reason = reason;
        }
    }

    static final class ProfileDto {
        public int totalChars;
        public int totalLines;
        public double avgLineLen;
        public double stdLineLen;
        public Map<Integer, Integer> mdHeadingCounts = new LinkedHashMap<>();
        public int mdHeadingTotal;
        public int numberedSectionCount;
        public int allCapsShortLineCount;
        public int blankParagraphBreaks;
        public int formFeedCount;
        public int visualSepCount;
        public int germanChapterCount;
        public int englishChapterCount;
        public int chineseChapterCount;
        public int repeatedFooterCount;
        public boolean hasTables;
        public boolean hasCode;
        public double codeRatio;
        public List<String> detectedLangs = new ArrayList<>();
    }

    static final class ChunkDto {
        public int seq;
        public int start;
        public int end;
        public int sizeChars;
        public int sizeTokensApprox;
        /** JSON 空值省略：空串省略。 */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public String contextHeader;
        public String content;
    }

    static final class StatsDto {
        public int count;
        public int avgChars;
        public int minChars;
        public int maxChars;
        public int stddevChars;
        /** JSON 空值省略：0 省略。 */
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        public int truncatedTo;
    }

    /** 切分结果 + 诊断（parent-child 时 chunks = children）。 */
    private record SplitOutcome(List<ParsedChunk> chunks, Chunker.Diagnostics diag) {
    }

    /**
     * 绑定失败 → 400 裸错误体 {@code {"error":"invalid request body: …","success":false}}
     * ——该调试端点专属形态（非标准信封），契约样例 cprev-bad-body 锁定。
     */

    @PostMapping("/api/v1/chunker/preview")
    public ResponseEntity<PreviewResponse> previewChunking(
            @NonNullBody @RequestBody PreviewRequest req) {
        String text = req.text() == null ? "" : req.text();

        if (text.strip().isEmpty()) {
            throw new BizException(AppError.badRequest(
                    "text is empty — paste a sample to preview chunking"));
        }
        // rune 计数（非 char 计）
        if (text.codePointCount(0, text.length()) > PREVIEW_MAX_CHARS) {
            throw new BizException(new AppError(ErrorCode.KNOWLEDGE_PREVIEW_TOO_LARGE.value(),
                    "text exceeds preview limit", "limit: " + PREVIEW_MAX_CHARS, 413));
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
            throw new BizException(new AppError(ErrorCode.KNOWLEDGE_PREVIEW_TIMEOUT.value(),
                    "chunker preview timed out", null, 504));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(new AppError(ErrorCode.INTERNAL_SERVER.value(),
                    "chunker preview interrupted", null, 500));
        } catch (ExecutionException e) {
            throw new BizException(new AppError(ErrorCode.INTERNAL_SERVER.value(),
                    "chunker preview failed", null, 500));
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

        return ResponseEntity.ok(resp);
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

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }
}
