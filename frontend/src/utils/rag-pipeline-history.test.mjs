import assert from 'node:assert/strict'
import test from 'node:test'
import {
  ensureRagPipelineHistoryStream,
  hasRagPipelineToolEvents,
  RAG_TIMELINE_TOOL_NAMES,
  synthesizeRagPipelineToolEvents,
} from './rag-pipeline-history.ts'

test('synthesizeRagPipelineToolEvents builds completed retrieval steps', () => {
  const events = synthesizeRagPipelineToolEvents({
    knowledgeReferences: [
      { knowledgeId: 'a' },
      { knowledgeId: 'a' },
      { knowledgeId: 'b' },
    ],
  })

  assert.equal(events.length, 2)
  assert.equal(events[0].tool_name, 'query_understand')
  assert.equal(events[1].tool_name, 'knowledge_search')
  assert.equal(events[1].tool_data.count, 3)
  assert.equal(events[1].tool_data.search_source, 'knowledge')
})

test('synthesizeRagPipelineToolEvents marks web-only references as web search', () => {
  const events = synthesizeRagPipelineToolEvents({
    knowledgeReferences: [
      { chunkType: 'web_search', knowledgeTitle: 'page-1' },
      { chunkType: 'web_search', knowledgeTitle: 'page-2' },
    ],
  })

  assert.equal(events[1].tool_data.search_source, 'web')
  assert.equal(events[1].tool_data.web_count, 2)
  assert.equal(events[1].tool_data.doc_count, 0)
})

test('synthesizeRagPipelineToolEvents skips retrieval when there are no references', () => {
  const events = synthesizeRagPipelineToolEvents({
    knowledgeReferences: [],
  })

  assert.equal(events.length, 0)
})

test('ensureRagPipelineHistoryStream does not invent retrieval for attachment-only turns', () => {
  const item = {
    completed: true,
    content: 'answer from attachment only',
    knowledgeReferences: [],
    agentEventStream: [
      {
        type: 'tool_call',
        tool_name: 'attachment_parsing',
        tool_call_id: 'attach-1',
        pending: false,
        success: true,
        tool_data: { parsed_count: 1, skipped_count: 0 },
      },
    ],
  }

  ensureRagPipelineHistoryStream(item)

  assert.equal(item.isAgentMode, true)
  assert.equal(item.hideContent, true)
  assert.equal(hasRagPipelineToolEvents(item.agentEventStream), false)
  assert.equal(
    item.agentEventStream.some((event) => event.tool_name === 'knowledge_search'),
    false,
  )
  assert.equal(
    item.agentEventStream.some((event) => event.tool_name === 'query_understand'),
    false,
  )
  assert.equal(
    item.agentEventStream.some((event) => event.tool_name === 'attachment_parsing'),
    true,
  )
  assert.equal(
    item.agentEventStream.some((event) => event.type === 'answer'),
    true,
  )
})

test('ensureRagPipelineHistoryStream restores quick-answer history after reload', () => {
  const item = {
    completed: true,
    content: 'final answer',
    knowledgeReferences: [{ knowledgeId: 'doc-1' }],
    agentEventStream: [],
  }

  ensureRagPipelineHistoryStream(item)

  assert.equal(item.isAgentMode, true)
  assert.equal(item.hideContent, true)
  assert.equal(hasRagPipelineToolEvents(item.agentEventStream), true)
  assert.equal(
    item.agentEventStream.some((event) => event.type === 'answer'),
    true,
  )
})

test('ensureRagPipelineHistoryStream keeps existing pipeline events', () => {
  const existing = {
    type: 'tool_call',
    tool_name: 'knowledge_search',
    tool_call_id: 'live-1',
    pending: false,
  }
  const item = {
    completed: true,
    content: 'answer',
    agentEventStream: [existing],
  }

  ensureRagPipelineHistoryStream(item)

  assert.equal(item.agentEventStream.length, 1)
  assert.equal(item.agentEventStream[0].tool_call_id, 'live-1')
})

test('RAG_TIMELINE_TOOL_NAMES includes attachment prep tools', () => {
  assert.equal(RAG_TIMELINE_TOOL_NAMES.has('attachment_parsing'), true)
  assert.equal(RAG_TIMELINE_TOOL_NAMES.has('image_analysis'), true)
  assert.equal(RAG_TIMELINE_TOOL_NAMES.has('knowledge_search'), true)
})
