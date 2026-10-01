import assert from 'node:assert/strict'
import test from 'node:test'
import { expandSteerForksInHistory, forkAfterInjectedUser } from './steerStreamFork.ts'
import { readFileSync } from 'node:fs'
import {
  collectSessionArtifacts,
  formatArtifactDateTime,
  formatArtifactSize,
} from './sessionArtifacts.ts'

test('collectSessionArtifacts keeps per-message download indexes', () => {
  const items = collectSessionArtifacts([
    { role: 'user', id: 'u1', content: 'hi' },
    {
      role: 'assistant',
      id: 'a1',
      artifacts: [{ fileName: 'a.csv', fileSize: 12 }],
    },
    {
      role: 'assistant',
      id: 'a2',
      artifacts: [
        { index: 0, fileName: 'chart.html' },
        { index: 1, fileName: 'plot.png' },
      ],
    },
  ])
  assert.deepEqual(
    items.map((item) => ({ messageId: item.messageId, index: item.index, fileName: item.fileName })),
    [
      { messageId: 'a1', index: 0, fileName: 'a.csv' },
      { messageId: 'a2', index: 0, fileName: 'chart.html' },
      { messageId: 'a2', index: 1, fileName: 'plot.png' },
    ],
  )
})

test('collectSessionArtifacts falls back to requestId before the row is persisted', () => {
  const items = collectSessionArtifacts([
    { requestId: 'req-9', artifacts: [{ fileName: 'out.md' }] },
  ])
  assert.equal(items[0]?.messageId, 'req-9')
  assert.equal(items[0]?.index, 0)
})

test('collectSessionArtifacts skips empty or unidentifiable rows', () => {
  assert.deepEqual(
    collectSessionArtifacts([
      { id: 'a1', artifacts: [] },
      { artifacts: [{ fileName: 'ghost.txt' }] },
      null,
      'nope',
    ]),
    [],
  )
})

test('formatArtifactSize matches the former drawer copy', () => {
  assert.equal(formatArtifactSize(0), '0 B')
  assert.equal(formatArtifactSize(512), '512 B')
  assert.equal(formatArtifactSize(2048), '2.0 KB')
})

test('formatArtifactDateTime renders a stable local timestamp', () => {
  assert.equal(formatArtifactDateTime(''), '—')
  assert.equal(formatArtifactDateTime('not-a-date'), 'not-a-date')
  assert.match(formatArtifactDateTime('2026-09-08T04:05:00Z'), /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
})

test('artifacts after a live inject use the persisted assistant ID regardless of filename', () => {
  const assistant = { id: 'assistant', requestId: 'request', role: 'assistant' }
  const list = [assistant]
  const continuation = forkAfterInjectedUser(list, assistant, { id: 'user', role: 'user' }, 'steer')
  continuation.artifacts = [{ fileName: 'WeKnora-示例文档.docx', index: 3 }]
  const items = collectSessionArtifacts(list)
  assert.equal(items[0]?.messageId, 'assistant')
  assert.equal(items[0]?.index, 3)
  assert.equal(items[0]?.fileName, 'WeKnora-示例文档.docx')
})

test('history-split artifacts keep their persisted download address after refresh', () => {
  const list = expandSteerForksInHistory([
    { id: 'assistant', role: 'assistant', requestId: 'request', completed: true,
      artifacts: [{ fileName: '示例.docx' }, { fileName: 'example.docx' }] },
    { id: 'user', role: 'user', requestId: 'request', createdAt: '2026-09-09T12:00:00Z' },
  ])
  const items = collectSessionArtifacts(list)
  assert.deepEqual(items.map(item => [item.messageId, item.index]), [['assistant', 0], ['assistant', 1]])
})

test('both answer renderers resolve the persisted artifact owner for inline previews and folder entry', () => {
  for (const file of ['botmsg.vue', 'AgentStreamDisplay.vue']) {
    const source = readFileSync(new URL(`../views/chat/components/${file}`, import.meta.url), 'utf8')
    const start = source.indexOf('const messageIdForArtifacts = computed(')
    const end = source.indexOf('// Set when the drawer', start)
    assert.ok(start >= 0 && end > start)
    assert.match(source.slice(start, end), /persistedAssistantId\(props\.session\)/)
  }
})
