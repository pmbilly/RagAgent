import assert from 'node:assert/strict'
import { test } from 'node:test'
import { hydrateAgentPromptRefs, serializeAgentPrompts } from './agentPromptTemplates'
import type { CustomAgentConfig } from '../api/agent'
import type { PromptTemplatesConfig } from '../api/system'

const template = (id: string, content: string, extra = {}) => ({ id, name: id, description: '', content, ...extra })
const templates: PromptTemplatesConfig = {
  agentSystemPrompt: [template('agent', 'Agent default'), template('wiki', 'Wiki workflow')],
  systemPrompt: [template('normal', 'QA default')],
  contextTemplate: [template('context', '{{contexts}}')],
  rewrite: [template('rewrite', 'Rewrite default', { default: true, user: '{{query}}' })],
  fallback: [template('fallback', 'Fallback {{query}}', { default: true, mode: 'model' })],
}

test('untouched templates round trip as references and follow updates in both modes', () => {
  for (const agentMode of ['smart-reasoning', 'quick-answer'] as const) {
    const input: CustomAgentConfig = {
      agentMode, systemPrompt: agentMode === 'smart-reasoning' ? 'Wiki workflow' : 'QA default',
      contextTemplate: '{{contexts}}', rewritePromptSystem: 'Rewrite default', rewritePromptUser: '{{query}}',
      fallbackPrompt: 'Fallback {{query}}',
    }
    const saved = serializeAgentPrompts(input, templates)
    assert.equal(saved.systemPrompt, '')
    assert.equal(saved.contextTemplate, '')
    assert.equal(saved.rewritePromptSystem, '')
    assert.equal(saved.rewritePromptUser, '')
    assert.equal(saved.fallbackPrompt, '')
    assert.equal(hydrateAgentPromptRefs(saved, templates).systemPrompt, input.systemPrompt)
    const updated = structuredClone(templates)
    const list = agentMode === 'smart-reasoning' ? updated.agentSystemPrompt! : updated.systemPrompt
    list.find(t => t.id === saved.systemPromptId)!.content = 'Updated template'
    assert.equal(hydrateAgentPromptRefs(saved, updated).systemPrompt, 'Updated template')
    assert.notEqual(input.systemPrompt, '', 'serialization must not alter the visible textarea')
  }
})

test('custom and legacy text survives unchanged and stale IDs are removed', () => {
  const input: CustomAgentConfig = {
    agentMode: 'smart-reasoning', systemPromptId: 'agent', systemPrompt: ' Legacy default with edits\n',
    contextTemplateId: 'context', contextTemplate: 'Custom {{contexts}}',
    rewritePromptSystem: 'Custom rewrite', fallbackPrompt: 'Custom {{query}}',
  }
  const saved = serializeAgentPrompts(input, templates)
  assert.equal(saved.systemPrompt, input.systemPrompt)
  assert.equal(saved.contextTemplate, input.contextTemplate)
  assert.equal(saved.systemPromptId, undefined)
  assert.equal(saved.contextTemplateId, undefined)
  assert.equal(hydrateAgentPromptRefs(saved, templates).systemPrompt, input.systemPrompt)
  assert.equal(saved.rewritePromptSystem, input.rewritePromptSystem)
  assert.equal(saved.fallbackPrompt, input.fallbackPrompt)
  assert.deepEqual(serializeAgentPrompts(input, null), input)
})

test('template refs cannot resolve from another mode or field', () => {
  assert.equal(hydrateAgentPromptRefs({ agentMode: 'quick-answer', systemPromptId: 'wiki' }, templates).systemPrompt, '')
  assert.equal(hydrateAgentPromptRefs({ agentMode: 'smart-reasoning', systemPromptId: 'context' }, templates).systemPrompt, '')
})
