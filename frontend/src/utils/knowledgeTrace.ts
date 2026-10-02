/** Whether GET /knowledge/:id/spans returned a real trace (not legacy placeholder-only). */
export function knowledgeSpansPayloadHasTrace(
  data: { trace?: { spanId?: string }; currentAttempt?: number } | null | undefined,
): boolean {
  if (!data?.trace) return false
  return !!(data.trace.spanId || (data.currentAttempt ?? 0) > 0)
}

export interface KnowledgeTraceNode {
  spanId?: string
  parentSpanId?: string
  name: string
  kind: string
  status: string
  startedAt?: string | null
  finishedAt?: string | null
  durationMs?: number
  errorCode?: string
  errorMessage?: string
  input?: unknown
  output?: unknown
  metadata?: unknown
  children?: KnowledgeTraceNode[]
}

export interface PostprocessTaskSummary {
  running: number
  failed: number
  completed: number
  other: number
  total: number
}

const graphChunkName = /^postprocess\.graph\.chunk\[(\d+)\]$/

function timestamp(value?: string | null): number | null {
  if (!value) return null
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? null : parsed
}

function nodeEnd(node: KnowledgeTraceNode): number | null {
  const finished = timestamp(node.finishedAt)
  if (finished !== null) return finished
  const started = timestamp(node.startedAt)
  if (started !== null && typeof node.durationMs === 'number' && node.durationMs >= 0) {
    return started + node.durationMs
  }
  return null
}

function aggregateStatus(nodes: KnowledgeTraceNode[]): string {
  if (nodes.some(node => node.status === 'running' || node.status === 'pending')) return 'running'
  if (nodes.some(node => node.status === 'failed')) return 'failed'
  if (nodes.every(node => node.status === 'skipped')) return 'skipped'
  if (nodes.some(node => node.status === 'cancelled')) return 'cancelled'
  return 'done'
}

/**
 * Groups persisted postprocess.graph.chunk[i] spans into one derived graph
 * node. The derived duration is wall-clock time from the first graph worker
 * start to the final graph worker finish; children retain per-chunk detail.
 */
export function groupPostprocessGraphSpans(
  stage: KnowledgeTraceNode,
): KnowledgeTraceNode {
  const children = stage.children || []
  const graphChildren = children.filter(child => graphChunkName.test(child.name))
  if (graphChildren.length === 0) return stage

  const starts = graphChildren
    .map(child => timestamp(child.startedAt))
    .filter((value): value is number => value !== null)
  const ends = graphChildren
    .map(nodeEnd)
    .filter((value): value is number => value !== null)
  const status = aggregateStatus(graphChildren)
  const start = starts.length > 0 ? Math.min(...starts) : null
  const terminal = status !== 'running'
  const end = terminal && ends.length > 0 ? Math.max(...ends) : null
  const counts = graphChildren.reduce<Record<string, number>>((result, child) => {
    result[child.status] = (result[child.status] || 0) + 1
    return result
  }, {})

  const group: KnowledgeTraceNode = {
    spanId: `virtual:postprocess.graph:${stage.spanId || 'stage'}`,
    parentSpanId: stage.spanId,
    name: 'postprocess.graph',
    kind: 'group',
    status,
    startedAt: start === null ? null : new Date(start).toISOString(),
    finishedAt: end === null ? null : new Date(end).toISOString(),
    durationMs: start !== null && end !== null ? Math.max(0, end - start) : undefined,
    input: { chunk_count: graphChildren.length },
    output: { chunk_count: graphChildren.length, status_counts: counts },
    children: graphChildren,
  }

  let inserted = false
  const groupedChildren: KnowledgeTraceNode[] = []
  for (const child of children) {
    if (graphChunkName.test(child.name)) {
      if (!inserted) {
        groupedChildren.push(group)
        inserted = true
      }
      continue
    }
    groupedChildren.push(child)
  }

  return { ...stage, children: groupedChildren }
}

/**
 * Counts leaf postprocess spans so the UI can distinguish the five main
 * pipeline stages from asynchronous enrichment work.
 */
export function summarizePostprocessTasks(
  trace?: KnowledgeTraceNode,
): PostprocessTaskSummary {
  const summary: PostprocessTaskSummary = {
    running: 0,
    failed: 0,
    completed: 0,
    other: 0,
    total: 0,
  }
  if (!trace) return summary

  const postprocess = trace.name === 'postprocess'
    ? trace
    : (trace.children || []).find(child => child.name === 'postprocess')
  if (!postprocess) return summary

  const countLeaves = (node: KnowledgeTraceNode) => {
    const children = node.children || []
    if (children.length > 0) {
      children.forEach(countLeaves)
      return
    }

    summary.total++
    switch (node.status) {
      case 'running':
      case 'pending':
      case 'processing':
      case 'finalizing':
        summary.running++
        break
      case 'failed':
        summary.failed++
        break
      case 'done':
      case 'completed':
        summary.completed++
        break
      default:
        summary.other++
    }
  }

  ;(postprocess.children || []).forEach(countLeaves)
  return summary
}
