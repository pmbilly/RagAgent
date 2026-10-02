import assert from 'node:assert/strict'
import test from 'node:test'
import {
  agentHasConfiguredChatModel,
  agentRequiresRerankModel,
  getAgentNotReadyReasonKeys,
  resolveAgentNotReadySection,
  resolveAgentNotReadyHighlight,
} from './agent-readiness.ts'

test('does not treat an unrelated built-in chat model as agent configuration', () => {
  assert.equal(agentHasConfiguredChatModel(
    {},
    [{ id: 'builtin-chat', type: 'KnowledgeQA' }],
  ), false)
})

test('accepts the chat model explicitly configured on the agent', () => {
  assert.equal(agentHasConfiguredChatModel(
    { modelId: 'builtin-chat' },
    [{ id: 'builtin-chat', type: 'KnowledgeQA' }],
  ), true)
})

test('rejects a configured chat model that no longer exists', () => {
  assert.equal(agentHasConfiguredChatModel(
    { modelId: 'deleted-chat' },
    [{ id: 'builtin-chat', type: 'KnowledgeQA' }],
  ), false)
})

test('requires rerank when knowledge_search has a knowledge-base scope', () => {
  assert.equal(agentRequiresRerankModel({
    kbSelectionMode: 'all',
    allowedTools: ['knowledge_search'],
  }), true)
})

test('does not require rerank when knowledge bases are disabled', () => {
  assert.equal(agentRequiresRerankModel({
    kbSelectionMode: 'none',
    allowedTools: ['knowledge_search'],
  }), false)
})

test('does not require rerank for wiki-only tools', () => {
  assert.equal(agentRequiresRerankModel({
    kbSelectionMode: 'all',
    allowedTools: ['wiki_search', 'wiki_read_page'],
  }), false)
})

test('matches backend default-tool behavior', () => {
  assert.equal(agentRequiresRerankModel({
    kbSelectionMode: 'all',
    allowedTools: [],
  }), true)
  assert.equal(agentRequiresRerankModel({
    kbSelectionMode: 'none',
    allowedTools: [],
  }), false)
})

test('resolveAgentNotReadySection opens model config for model issues', () => {
  assert.equal(resolveAgentNotReadySection(['summary_model']), 'model')
  assert.equal(resolveAgentNotReadySection(['rerank_model']), 'model')
  assert.equal(resolveAgentNotReadySection(['summary_model', 'allowedTools']), 'model')
})

test('resolveAgentNotReadySection opens tools config when only tools are missing', () => {
  assert.equal(resolveAgentNotReadySection(['allowedTools']), 'tools')
})

test('resolveAgentNotReadyHighlight returns the first missing item', () => {
  assert.equal(resolveAgentNotReadyHighlight(['summary_model', 'rerank_model']), 'summary_model')
  assert.equal(resolveAgentNotReadyHighlight(['allowedTools']), 'allowedTools')
  assert.equal(resolveAgentNotReadyHighlight([]), undefined)
})

test('getAgentNotReadyReasonKeys flags missing chat model', () => {
  assert.deepEqual(getAgentNotReadyReasonKeys(
    {},
    [{ id: 'chat-1', type: 'KnowledgeQA' }],
    { isAgentMode: false },
  ), ['summary_model'])
})

test('getAgentNotReadyReasonKeys requires rerank only in agent mode with KB search', () => {
  assert.deepEqual(getAgentNotReadyReasonKeys(
    { kbSelectionMode: 'all', allowedTools: ['knowledge_search'] },
    [{ id: 'chat-1', type: 'KnowledgeQA' }, { id: 'rerank-1', type: 'Rerank' }],
    { isAgentMode: true },
  ), ['summary_model', 'rerank_model'])
})

test('getAgentNotReadyReasonKeys rejects a chat model that no longer exists', () => {
  assert.deepEqual(getAgentNotReadyReasonKeys(
    { modelId: 'deleted-chat' },
    [{ id: 'chat-1', type: 'KnowledgeQA' }],
    { isAgentMode: false },
  ), ['summary_model'])
})

test('getAgentNotReadyReasonKeys treats empty allowedTools as ready via backend defaults', () => {
  assert.deepEqual(getAgentNotReadyReasonKeys(
    {
      modelId: 'chat-1',
      rerankModelId: 'rerank-1',
      kbSelectionMode: 'all',
      allowedTools: [],
    },
    [{ id: 'chat-1', type: 'KnowledgeQA' }, { id: 'rerank-1', type: 'Rerank' }],
    { isAgentMode: true },
  ), [])
})
