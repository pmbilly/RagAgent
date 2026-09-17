import assert from 'node:assert/strict'
import test from 'node:test'

import { isThinkingInProgress } from './embedThinkingStatus.ts'

test('isThinkingInProgress: quick-answer mode tracks thinking flag', () => {
  assert.equal(isThinkingInProgress({ thinking: true }), true)
  assert.equal(isThinkingInProgress({ thinking: false }), false)
  assert.equal(isThinkingInProgress({ showThink: true }), false)
  assert.equal(isThinkingInProgress(null), false)
  assert.equal(isThinkingInProgress(undefined), false)
})

test('isThinkingInProgress: agent mode scans thinking events', () => {
  assert.equal(
    isThinkingInProgress({
      isAgentMode: true,
      is_completed: false,
      agentEventStream: [{ type: 'thinking', thinking: true, done: false }],
    }),
    true,
  )
  assert.equal(
    isThinkingInProgress({
      isAgentMode: true,
      is_completed: true,
      agentEventStream: [{ type: 'thinking', thinking: false, done: true }],
    }),
    false,
  )
  // Tool-only phase of an in-flight message counts as thinking (no thinking
  // events are emitted, but the answer has not started yet).
  assert.equal(
    isThinkingInProgress({
      isAgentMode: true,
      is_completed: false,
      agentEventStream: [{ type: 'tool_call', thinking: false }],
    }),
    true,
  )
  assert.equal(isThinkingInProgress({ isAgentMode: true }), false)
  assert.equal(isThinkingInProgress({ isAgentMode: true, is_completed: true }), false)
  // Answer already streaming: dots must give way to the answer text.
  assert.equal(
    isThinkingInProgress({ isAgentMode: true, is_completed: false, content: '2' }),
    false,
  )
})
