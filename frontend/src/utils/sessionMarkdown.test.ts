import assert from 'node:assert/strict'
import test from 'node:test'
import { buildSessionMarkdown, collectAllSessionMessages } from './sessionMarkdown'

const labels = {
  sessionId: 'Session ID',
  exportedAt: 'Exported at',
  user: 'User',
  assistant: 'Assistant',
  attachments: 'Attachments',
  references: 'References',
}

test('collectAllSessionMessages walks backwards and returns chronological unique messages', async () => {
  const pages: Record<string, any[]> = {
    '': [
      { id: '3', role: 'user', content: 'new question', createdAt: '2026-01-03T00:00:00Z' },
      { id: '4', role: 'assistant', content: 'new answer', createdAt: '2026-01-04T00:00:00Z' },
    ],
    '2026-01-03T00:00:00Z': [
      { id: '1', role: 'user', content: 'old question', createdAt: '2026-01-01T00:00:00Z' },
      { id: '2', role: 'assistant', content: 'old answer', createdAt: '2026-01-02T00:00:00Z' },
    ],
    '2026-01-01T00:00:00Z': [],
  }

  const result = await collectAllSessionMessages(async (beforeTime) => pages[beforeTime], 2)
  assert.deepEqual(result.map((message) => message.id), ['1', '2', '3', '4'])
})

test('buildSessionMarkdown exports visible conversation content without internal citation tags', () => {
  const markdown = buildSessionMarkdown({
    sessionId: 'session-1',
    title: 'Example chat',
    exportedAt: '2026-01-05T00:00:00Z',
    labels,
    messages: [
      {
        role: 'user',
        content: 'Read this file',
        attachments: [{ fileName: 'notes.md' }],
      },
      {
        role: 'assistant',
        content: 'Done. <kb doc="notes.md" chunk_id="1" />',
        knowledgeReferences: [
          { knowledgeTitle: 'Notes', knowledgeSource: 'https://example.com/notes' },
          // 历史 jsonb 行里的库内键名（过渡期兼容读取）——与上一条去重后只留一行
          { knowledge_title: 'Notes', knowledge_source: 'https://example.com/notes' },
        ],
      },
      { role: 'system', content: 'hidden system prompt' },
    ],
  })

  assert.match(markdown, /^# Example chat/)
  assert.match(markdown, /## User\n\nRead this file/)
  assert.match(markdown, /### Attachments\n\n- notes\.md/)
  assert.match(markdown, /## Assistant\n\nDone\./)
  assert.match(markdown, /- \[Notes\]\(https:\/\/example\.com\/notes\)/)
  assert.doesNotMatch(markdown, /<kb/)
  assert.doesNotMatch(markdown, /hidden system prompt/)
  assert.equal(markdown.match(/\[Notes\]/g)?.length, 1)
})
