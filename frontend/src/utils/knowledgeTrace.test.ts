import assert from 'node:assert/strict'
import test from 'node:test'

import {
  groupPostprocessGraphSpans,
  summarizePostprocessTasks,
  type KnowledgeTraceNode,
} from './knowledgeTrace.ts'

function graphChunk(index: number, overrides: Partial<KnowledgeTraceNode> = {}): KnowledgeTraceNode {
  return {
    spanId: `graph-${index}`,
    parentSpanId: 'postprocess',
    name: `postprocess.graph.chunk[${index}]`,
    kind: 'subspan',
    status: 'done',
    startedAt: `2026-07-21T08:00:0${index}.000Z`,
    finishedAt: `2026-07-21T08:00:0${index + 2}.000Z`,
    durationMs: 2000,
    ...overrides,
  }
}

test('groups graph chunks and reports their wall-clock duration', () => {
  const summary: KnowledgeTraceNode = {
    spanId: 'summary',
    name: 'postprocess.summary',
    kind: 'subspan',
    status: 'done',
  }
  const stage: KnowledgeTraceNode = {
    spanId: 'postprocess',
    name: 'postprocess',
    kind: 'stage',
    status: 'done',
    children: [summary, graphChunk(0), graphChunk(1)],
  }

  const grouped = groupPostprocessGraphSpans(stage)
  assert.equal(grouped.children?.length, 2)
  assert.equal(grouped.children?.[0], summary)

  const graph = grouped.children?.[1]
  assert.equal(graph?.name, 'postprocess.graph')
  assert.equal(graph?.status, 'done')
  assert.equal(graph?.durationMs, 3000)
  assert.equal(graph?.children?.length, 2)
  assert.deepEqual(graph?.output, {
    chunk_count: 2,
    status_counts: { done: 2 },
  })
})

test('keeps graph group live while any graph chunk is running', () => {
  const stage: KnowledgeTraceNode = {
    spanId: 'postprocess',
    name: 'postprocess',
    kind: 'stage',
    status: 'done',
    children: [
      graphChunk(0),
      graphChunk(1, { status: 'running', finishedAt: null, durationMs: undefined }),
    ],
  }

  const graph = groupPostprocessGraphSpans(stage).children?.[0]
  assert.equal(graph?.status, 'running')
  assert.equal(graph?.finishedAt, null)
  assert.equal(graph?.durationMs, undefined)
})

test('surfaces a failed graph chunk on the aggregate graph row', () => {
  const stage: KnowledgeTraceNode = {
    spanId: 'postprocess',
    name: 'postprocess',
    kind: 'stage',
    status: 'done',
    children: [graphChunk(0), graphChunk(1, { status: 'failed' })],
  }

  const graph = groupPostprocessGraphSpans(stage).children?.[0]
  assert.equal(graph?.status, 'failed')
  assert.equal(graph?.durationMs, 3000)
})

test('keeps the aggregate running until all graph chunks are terminal', () => {
  const stage: KnowledgeTraceNode = {
    spanId: 'postprocess',
    name: 'postprocess',
    kind: 'stage',
    status: 'done',
    children: [
      graphChunk(0, { status: 'failed' }),
      graphChunk(1, { status: 'running', finishedAt: null, durationMs: undefined }),
    ],
  }

  const graph = groupPostprocessGraphSpans(stage).children?.[0]
  assert.equal(graph?.status, 'running')
  assert.equal(graph?.durationMs, undefined)
})

test('leaves postprocess unchanged when it has no graph chunks', () => {
  const stage: KnowledgeTraceNode = {
    spanId: 'postprocess',
    name: 'postprocess',
    kind: 'stage',
    status: 'done',
    children: [],
  }

  assert.equal(groupPostprocessGraphSpans(stage), stage)
})

test('summarizes asynchronous postprocess leaf tasks', () => {
  const trace: KnowledgeTraceNode = {
    name: 'root',
    kind: 'root',
    status: 'done',
    children: [
      {
        name: 'postprocess',
        kind: 'stage',
        status: 'done',
        children: [
          { name: 'postprocess.summary', kind: 'subspan', status: 'done' },
          { name: 'postprocess.question', kind: 'subspan', status: 'running' },
          {
            name: 'postprocess.graph',
            kind: 'group',
            status: 'failed',
            children: [
              { name: 'postprocess.graph.chunk[0]', kind: 'subspan', status: 'failed' },
              { name: 'postprocess.graph.chunk[1]', kind: 'subspan', status: 'pending' },
            ],
          },
        ],
      },
    ],
  }

  assert.deepEqual(summarizePostprocessTasks(trace), {
    running: 2,
    failed: 1,
    completed: 1,
    other: 0,
    total: 4,
  })
})

test('returns an empty postprocess summary when no trace is available', () => {
  assert.deepEqual(summarizePostprocessTasks(undefined), {
    running: 0,
    failed: 0,
    completed: 0,
    other: 0,
    total: 0,
  })
})
