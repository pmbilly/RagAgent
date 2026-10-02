import type { CustomAgentConfig } from '../api/agent'
import type { PromptTemplate, PromptTemplatesConfig } from '../api/system'

const systemTemplates = (config: CustomAgentConfig, templates: PromptTemplatesConfig) =>
  config.agentMode === 'smart-reasoning' ? templates.agentSystemPrompt || [] : templates.systemPrompt

function matchingTemplate(body: string | undefined, id: string | undefined, list: PromptTemplate[]) {
  // Preserve the selected identity if multiple templates have identical bodies.
  return list.find(t => t.id === id && t.content === body)
    || list.find(t => t.content === body && !!body)
}

// The textarea shows effective content. Persist untouched templates as references;
// preserve every character of actual custom text and clear any stale reference.
export function serializeAgentPrompts(config: CustomAgentConfig, templates: PromptTemplatesConfig | null): CustomAgentConfig {
  if (!templates) return { ...config }
  const result = { ...config }
  const system = matchingTemplate(config.systemPrompt, config.systemPromptId, systemTemplates(config, templates))
  const context = matchingTemplate(config.contextTemplate, config.contextTemplateId, templates.contextTemplate)
  if (system) {
    result.systemPrompt = ''
    result.systemPromptId = system.id
  } else if (config.systemPrompt) {
    delete result.systemPromptId
  }
  if (context) {
    result.contextTemplate = ''
    result.contextTemplateId = context.id
  } else if (config.contextTemplate) {
    delete result.contextTemplateId
  }
  // These fields already inherit the global defaults when empty.
  const rewrite = templates.rewrite.find(t => t.default)
  const fallback = templates.fallback.find(t => t.mode === 'model' && t.default)
    || templates.fallback.find(t => t.mode === 'model')
  const fixed = templates.fallback.find(t => t.default && t.mode !== 'model')
  for (const [field, value] of [
    ['rewritePromptSystem', rewrite?.content], ['rewritePromptUser', rewrite?.user],
    ['fallbackPrompt', fallback?.content], ['fallbackResponse', fixed?.content],
  ] as const) {
    if (value && result[field] === value) result[field] = ''
  }
  return result
}

export function hydrateAgentPromptRefs(config: CustomAgentConfig, templates: PromptTemplatesConfig | null): CustomAgentConfig {
  if (!templates) return { ...config }
  return {
    ...config,
    systemPrompt: config.systemPrompt || systemTemplates(config, templates).find(t => t.id === config.systemPromptId)?.content || '',
    contextTemplate: config.contextTemplate || templates.contextTemplate.find(t => t.id === config.contextTemplateId)?.content || '',
  }
}
