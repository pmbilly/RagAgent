import assert from 'node:assert/strict'
import test from 'node:test'

import { shouldRefreshWikiStatusAfterKnowledgePoll } from './wikiStatusRefresh.ts'

test('refreshes wiki status when a polled document leaves an in-flight state', () => {
  assert.equal(
    shouldRefreshWikiStatusAfterKnowledgePoll(
      { parseStatus: 'finalizing', summaryStatus: 'processing' },
      { parseStatus: 'completed', summaryStatus: 'completed' },
    ),
    true,
  )
})

test('does not refresh wiki status for ordinary in-flight polling updates', () => {
  assert.equal(
    shouldRefreshWikiStatusAfterKnowledgePoll(
      { parseStatus: 'pending' },
      { parseStatus: 'processing' },
    ),
    false,
  )
})
