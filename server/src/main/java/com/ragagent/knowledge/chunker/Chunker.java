package com.ragagent.knowledge.chunker;

import java.util.ArrayList;
import java.util.List;

/**
 * 自适应分块入口（对照 Go internal/infrastructure/chunker/strategy.go）。
 *
 * <p>调用方使用 {@link #split} / {@link #splitParentChild} 而非 legacy 的
 * {@code SplitText} / {@code SplitTextParentChild}；策略解析器依据文档画像与
 * {@code SplitterConfig.strategy} 提示选择层级。auto 策略链：
 * ProfileDocument → heading → heuristic → legacy，每层输出经 ValidateChunks 校验，
 * 失败则逐级回退，legacy 永远兜底。</p>
 */
public final class Chunker {

    private static final System.Logger LOG = System.getLogger("com.ragagent.knowledge.chunker");

    // 对照 Go strategy.go:18-24
    public static final String STRATEGY_AUTO = "auto";
    public static final String STRATEGY_HEADING = "heading";
    public static final String STRATEGY_HEURISTIC = "heuristic";
    public static final String STRATEGY_RECURSIVE = "recursive";
    public static final String STRATEGY_LEGACY = "legacy";

    /** 两级分块输出（对照 Go ParentChildResult，splitter.go:844）。 */
    public record ParentChildResult(List<ParsedChunk> parents, List<ParsedChunk> children) {
    }

    /** parent/child 配置对（对照 Go DeriveParentChildConfigs 多返回值，strategy.go:220）。 */
    public record ParentChildConfigs(SplitterConfig parent, SplitterConfig child) {
    }

    /** 某一层被校验器拒绝的记录（对照 Go TierRejection，strategy.go:63）。 */
    public record TierRejection(DocumentProfiler.StrategyTier tier, String reason) {
    }

    /**
     * 策略链诊断轨迹（对照 Go Diagnostics，strategy.go:75）。JSON 形状是
     * preview 端点公开 API 的一部分——{@code rejected} 为 Go nil slice，
     * 无拒绝时序列化成 {@code null}（不是 {@code []}）；{@code profile} 在
     * 显式非 auto 策略下为 null（不经画像）。
     */
    public record Diagnostics(DocumentProfiler.StrategyTier selectedTier,
            List<DocumentProfiler.StrategyTier> tierChain,
            List<TierRejection> rejected,
            DocumentProfiler.DocProfile profile) {
    }

    /** splitWithDiagnostics 的双返回值（对照 Go 的 (chunks, diag)）。 */
    public record SplitResult(List<ParsedChunk> chunks, Diagnostics diagnostics) {
    }

    /** splitParentChildWithDiagnostics 的双返回值。 */
    public record ParentChildDiagnostics(ParentChildResult result, Diagnostics diagnostics) {
    }

    private record ParentChildSplit(ParentChildResult result, Diagnostics diagnostics) {
    }

    private Chunker() {
    }

    /**
     * 对照 Go Split（strategy.go:34）：按 cfg.strategy 分块；空/auto 时由文档画像选层。
     * 总是返回非 null 结果：层级失败时链式回退到 legacy（原 Tier 3 实现）。
     */
    public static List<ParsedChunk> split(String text, SplitterConfig cfg) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        cfg = ensureDefaults(cfg);
        ChainResolution res = resolveChainWithProfile(text, cfg);
        int totalChars = Runes.len(text);

        List<ParsedChunk> lastOut = null;
        for (int i = 0; i < res.chain().size(); i++) {
            DocumentProfiler.StrategyTier tier = res.chain().get(i);
            List<ParsedChunk> out = runTier(tier, text, cfg, res.profile());
            ChunkValidator.ValidationResult v = ChunkValidator.validate(out, totalChars, cfg.getChunkSize());
            if (v.ok()) {
                return out;
            }
            LOG.log(System.Logger.Level.DEBUG, "chunker: tier " + tier + " rejected: " + v.reason());
            if (tier == DocumentProfiler.StrategyTier.LEGACY && i == res.chain().size() - 1) {
                lastOut = out;
            }
        }
        if (lastOut != null) {
            return lastOut;
        }
        return LegacySplitter.splitText(text, cfg);
    }

    /**
     * 对照 Go SplitWithDiagnostics（strategy.go:88）：与 {@link #split} 相同的切分，
     * 附带诊断轨迹（选中层、完整链、逐层拒绝原因、auto 时的画像）。
     * selectedTier 默认 LEGACY——空 diag 不携带零串（debug UI 不出空白标签）。
     */
    public static SplitResult splitWithDiagnostics(String text, SplitterConfig cfg) {
        Diagnostics diag = new Diagnostics(DocumentProfiler.StrategyTier.LEGACY, null, null, null);
        if (text == null || text.isEmpty()) {
            return new SplitResult(List.of(), diag);
        }
        cfg = ensureDefaults(cfg);
        ChainResolution res = resolveChainWithProfile(text, cfg);
        diag = new Diagnostics(diag.selectedTier(), res.chain(), diag.rejected(), res.profile());
        int totalChars = Runes.len(text);

        List<ParsedChunk> lastOut = null;
        DocumentProfiler.StrategyTier lastTier = DocumentProfiler.StrategyTier.LEGACY;
        for (int i = 0; i < res.chain().size(); i++) {
            DocumentProfiler.StrategyTier tier = res.chain().get(i);
            List<ParsedChunk> out = runTier(tier, text, cfg, res.profile());
            ChunkValidator.ValidationResult v = ChunkValidator.validate(out, totalChars, cfg.getChunkSize());
            if (v.ok()) {
                diag = new Diagnostics(tier, diag.tierChain(), diag.rejected(), diag.profile());
                return new SplitResult(out, diag);
            }
            List<TierRejection> rejected = new ArrayList<>(
                    diag.rejected() == null ? List.of() : diag.rejected());
            rejected.add(new TierRejection(tier, v.reason()));
            diag = new Diagnostics(diag.selectedTier(), diag.tierChain(), rejected, diag.profile());
            LOG.log(System.Logger.Level.DEBUG, "chunker: tier " + tier + " rejected: " + v.reason());
            if (tier == DocumentProfiler.StrategyTier.LEGACY && i == res.chain().size() - 1) {
                lastOut = out;
                lastTier = tier;
            }
        }
        if (lastOut != null) {
            diag = new Diagnostics(lastTier, diag.tierChain(), diag.rejected(), diag.profile());
            return new SplitResult(lastOut, diag);
        }
        // 防御性最后一跳（对照 Go 的 SplitText fallback）
        return new SplitResult(LegacySplitter.splitText(text, cfg), diag);
    }

    /**
     * 对照 Go SplitParentChild（strategy.go:135）：parent 分块走策略链，
     * 再按 childCfg 把每个 parent 细分为 child。child 分块遵守 childCfg.strategy。
     */
    public static ParentChildResult splitParentChild(String text, SplitterConfig parentCfg,
            SplitterConfig childCfg) {
        return splitParentChildInternal(text, parentCfg, childCfg, false).result();
    }

    /**
     * 对照 Go SplitParentChildWithDiagnostics（strategy.go:144）：结果与
     * splitParentChild 完全一致，diagnostics 描述<b>整篇文档</b> parent 切分所选策略。
     */
    public static ParentChildDiagnostics splitParentChildWithDiagnostics(String text,
            SplitterConfig parentCfg, SplitterConfig childCfg) {
        ParentChildSplit split = splitParentChildInternal(text, parentCfg, childCfg, true);
        return new ParentChildDiagnostics(split.result(), split.diagnostics());
    }

    private static ParentChildSplit splitParentChildInternal(String text, SplitterConfig parentCfg,
            SplitterConfig childCfg, boolean withDiagnostics) {
        parentCfg = ensureDefaults(parentCfg);
        childCfg = ensureDefaults(childCfg);

        List<ParsedChunk> parents;
        Diagnostics diag = null;
        if (withDiagnostics) {
            SplitResult sr = splitWithDiagnostics(text, parentCfg);
            parents = sr.chunks();
            diag = sr.diagnostics();
        } else {
            parents = split(text, parentCfg);
        }
        if (parents.isEmpty()) {
            return new ParentChildSplit(new ParentChildResult(List.of(), List.of()), diag);
        }

        List<ParsedChunk> newParents = new ArrayList<>();
        List<ParsedChunk> children = new ArrayList<>();
        int childSeq = 0;
        for (ParsedChunk parent : parents) {
            List<ParsedChunk> subs = split(parent.getContent(), childCfg);

            int parentIndex = -1;
            if (subs.size() > 1
                    || (subs.size() == 1 && !subs.get(0).getContent().equals(parent.getContent()))) {
                parentIndex = newParents.size();
                newParents.add(parent);
            }
            for (ParsedChunk sub : subs) {
                // 偏移换算：子位置相对 parent content，平移到文档级偏移（加性平移，
                // 使前置上下文表头的 chunk 位置仍正确）
                sub.setSeq(childSeq);
                sub.setStart(sub.getStart() + parent.getStart());
                sub.setEnd(sub.getEnd() + parent.getStart());
                sub.setContextHeader(mergeBreadcrumbs(parent.getContextHeader(), sub.getContextHeader()));
                sub.setParentIndex(parentIndex);
                children.add(sub);
                childSeq++;
            }
        }
        return new ParentChildSplit(new ParentChildResult(newParents, children), diag);
    }

    /** 对照 Go NormalizeSplitterConfig（strategy.go:190）：ingestion 使用的基础默认值。 */
    public static SplitterConfig normalizeSplitterConfig(SplitterConfig cfg) {
        if (cfg.getChunkSize() <= 0) {
            cfg.setChunkSize(SplitterConfig.DEFAULT_CHUNK_SIZE);
        }
        if (cfg.getChunkOverlap() <= 0) {
            cfg.setChunkOverlap(SplitterConfig.DEFAULT_CHUNK_OVERLAP);
        }
        if (cfg.getSeparators().isEmpty()) {
            cfg.setSeparators(SplitterConfig.DEFAULT_SEPARATORS);
        }
        return cfg;
    }

    /**
     * 对照 Go DeriveParentChildConfigs（strategy.go:220）：知识库 ingestion 实际使用的
     * parent/child 配置。Languages 复制到两层；TokenLimit 只复制到 child（parent 保留上下文窗口）。
     */
    public static ParentChildConfigs deriveParentChildConfigs(SplitterConfig base,
            int parentSize, int childSize) {
        if (parentSize <= 0) {
            parentSize = 4096;
        }
        if (childSize <= 0) {
            childSize = 384;
        }
        SplitterConfig parent = new SplitterConfig();
        parent.setChunkSize(parentSize);
        parent.setChunkOverlap(base.getChunkOverlap());
        parent.setSeparators(base.getSeparators());
        parent.setStrategy(base.getStrategy());
        parent.setLanguages(base.getLanguages());

        SplitterConfig child = new SplitterConfig();
        child.setChunkSize(childSize);
        child.setChunkOverlap(childSize / 5);
        child.setSeparators(base.getSeparators());
        child.setStrategy(base.getStrategy());
        child.setTokenLimit(base.getTokenLimit());
        child.setLanguages(base.getLanguages());
        return new ParentChildConfigs(parent, child);
    }

    /**
     * 对照 Go mergeBreadcrumbs（strategy.go:250）：合并 parent/child 标题面包屑；
     * child 重跑标题检测时首行通常与 parent 最后一行重复，去掉该重复行。
     */
    static String mergeBreadcrumbs(String parent, String child) {
        if (parent.isEmpty()) {
            return child;
        }
        if (child.isEmpty()) {
            return parent;
        }
        String[] parentLines = parent.split("\n", -1);
        String[] childLines = child.split("\n", -1);
        if (parentLines.length > 0 && childLines.length > 0
                && parentLines[parentLines.length - 1].strip().equals(childLines[0].strip())) {
            String[] trimmed = new String[childLines.length - 1];
            System.arraycopy(childLines, 1, trimmed, 0, trimmed.length);
            childLines = trimmed;
        }
        if (childLines.length == 0) {
            return parent;
        }
        StringBuilder sb = new StringBuilder(parent);
        for (String line : childLines) {
            sb.append('\n').append(line);
        }
        return sb.toString();
    }

    private record ChainResolution(List<DocumentProfiler.StrategyTier> chain,
            DocumentProfiler.DocProfile profile) {
    }

    /**
     * 对照 Go resolveChainWithProfile（strategy.go:273）：auto 时返回画像，
     * 显式策略返回 null profile（不为用不到的画像付费）。
     */
    static ChainResolution resolveChainWithProfile(String text, SplitterConfig cfg) {
        return switch (cfg.getStrategy()) {
            case STRATEGY_HEADING -> new ChainResolution(List.of(
                    DocumentProfiler.StrategyTier.HEADING, DocumentProfiler.StrategyTier.LEGACY), null);
            case STRATEGY_HEURISTIC -> new ChainResolution(List.of(
                    DocumentProfiler.StrategyTier.HEURISTIC, DocumentProfiler.StrategyTier.LEGACY), null);
            // "recursive" 是 "legacy" 的公开 API 别名（兼容存量配置）
            case STRATEGY_RECURSIVE -> new ChainResolution(
                    List.of(DocumentProfiler.StrategyTier.LEGACY), null);
            // 空 = legacy，兼容早于 Strategy 字段的存量配置行
            case STRATEGY_LEGACY, "" -> new ChainResolution(
                    List.of(DocumentProfiler.StrategyTier.LEGACY), null);
            default -> {
                DocumentProfiler.DocProfile profile = DocumentProfiler.profileDocument(text);
                yield new ChainResolution(DocumentProfiler.selectStrategy(profile), profile);
            }
        };
    }

    /** 对照 Go runTier（strategy.go:304）。profile 可为 null（按需计算）。 */
    static List<ParsedChunk> runTier(DocumentProfiler.StrategyTier tier, String text,
            SplitterConfig cfg, DocumentProfiler.DocProfile profile) {
        return switch (tier) {
            case HEADING -> HeadingSplitter.splitByHeadings(text, cfg, profile);
            case HEURISTIC -> HeuristicSplitter.splitByHeuristics(text, cfg, profile);
            case LEGACY -> LegacySplitter.splitText(text, cfg);
        };
    }

    /**
     * 对照 Go ensureDefaults（strategy.go:323）：零值字段填充默认值。
     * TokenLimit 生效时把 ChunkSize 压到该 token 上限内的字符预算（10% 安全因子）。
     * overlap 超过 ChunkSize/2 时截断为 ChunkSize/2（病理配置防护）。
     */
    static SplitterConfig ensureDefaults(SplitterConfig cfg) {
        if (cfg.getChunkSize() <= 0) {
            cfg.setChunkSize(SplitterConfig.DEFAULT_CHUNK_SIZE);
        }
        if (cfg.getChunkOverlap() <= 0) {
            cfg.setChunkOverlap(SplitterConfig.DEFAULT_CHUNK_OVERLAP);
        }
        if (cfg.getSeparators().isEmpty()) {
            cfg.setSeparators(SplitterConfig.DEFAULT_SEPARATORS);
        }
        if (cfg.getTokenLimit() > 0) {
            String lang = Tokens.LANG_MIXED;
            if (!cfg.getLanguages().isEmpty()) {
                lang = cfg.getLanguages().get(0);
            }
            int charBudget = Tokens.charsForTokenLimit(cfg.getTokenLimit(), lang);
            if (charBudget > 0 && charBudget < cfg.getChunkSize()) {
                cfg.setChunkSize(charBudget);
            }
        }
        if (cfg.getChunkOverlap() > cfg.getChunkSize() / 2 && cfg.getChunkSize() > 0) {
            cfg.setChunkOverlap(cfg.getChunkSize() / 2);
        }
        return cfg;
    }
}
