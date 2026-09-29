// Types for the /api/v1/chunker/preview endpoint. Mirrors the JSON shape
// produced by internal/handler/chunker_debug.go. Used by the KB editor's
// chunking debug panel to render tier-info / chunk-cards / size stats.

export type StrategyTier = 'heading' | 'heuristic' | 'recursive' | 'legacy'

export interface TierRejection {
  tier: StrategyTier
  reason: string
}

export interface DocProfile {
  totalChars: number
  totalLines: number
  avgLineLen: number
  stdLineLen: number
  mdHeadingCounts: Record<string, number>
  mdHeadingTotal: number
  numberedSectionCount: number
  allCapsShortLineCount: number
  blankParagraphBreaks: number
  formFeedCount: number
  visualSepCount: number
  germanChapterCount: number
  englishChapterCount: number
  chineseChapterCount: number
  repeatedFooterCount: number
  hasTables: boolean
  hasCode: boolean
  codeRatio: number
  detectedLangs: string[]
}

export interface PreviewChunk {
  seq: number
  start: number
  end: number
  sizeChars: number
  sizeTokensApprox: number
  contextHeader?: string
  content: string
}

export interface PreviewChunkingStats {
  count: number
  avgChars: number
  minChars: number
  maxChars: number
  stddevChars: number
  truncatedTo?: number
}

export interface PreviewChunkingResponse {
  selectedTier: StrategyTier
  tierChain: StrategyTier[]
  rejected: TierRejection[]
  profile: DocProfile
  chunks: PreviewChunk[]
  stats: PreviewChunkingStats
}

export interface PreviewChunkingRequest {
  text: string
  chunkingConfig: {
    chunkSize: number
    chunkOverlap: number
    separators: string[]
    enableParentChild?: boolean
    parentChunkSize?: number
    childChunkSize?: number
    strategy?: string
    tokenLimit?: number
    languages?: string[]
  }
}
