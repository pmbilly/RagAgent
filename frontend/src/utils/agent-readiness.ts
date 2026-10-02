import type { CustomAgentConfig } from '@/api/agent'
import type { ModelConfig } from '@/api/model'

export type AgentNotReadyReasonKey = 'summary_model' | 'rerank_model' | 'allowedTools'

/**
 * An agent is chat-ready only when it explicitly references a usable chat
 * model. Merely having some built-in/default model in the tenant must not hide
 * incomplete agent configuration.
 */
export function agentHasConfiguredChatModel(
  config: Pick<CustomAgentConfig, 'modelId'> | undefined,
  models: Pick<ModelConfig, 'id' | 'type'>[],
): boolean {
  const modelID = config?.modelId?.trim()
  if (!modelID) return false
  return models.some(model => model.type === 'KnowledgeQA' && model.id === modelID)
}

/**
 * Keep this aligned with backend agentRequiresRerankModel.
 *
 * Rerank is needed only when knowledge_search can actually run. Explicitly
 * disabling the knowledge-base scope makes KB tools ineffective, even if an
 * older agent configuration still contains knowledge_search in allowedTools.
 */
export function agentRequiresRerankModel(
  config: Pick<CustomAgentConfig, 'kbSelectionMode' | 'allowedTools'> | undefined,
): boolean {
  if (!config || config.kbSelectionMode === 'none') return false

  const allowedTools = config.allowedTools || []
  // The backend falls back to DefaultAllowedTools when the list is empty,
  // and that default includes knowledge_search.
  if (allowedTools.length === 0) return true

  return allowedTools.includes('knowledge_search')
}

export function getAgentNotReadyReasonKeys(
  config: Pick<
    CustomAgentConfig,
    'modelId' | 'rerankModelId' | 'kbSelectionMode' | 'allowedTools' | 'agentMode'
  > | undefined,
  models: Pick<ModelConfig, 'id' | 'type'>[],
  options: { isAgentMode: boolean },
): AgentNotReadyReasonKey[] {
  const reasons: AgentNotReadyReasonKey[] = []

  if (!agentHasConfiguredChatModel(config, models)) {
    reasons.push('summary_model')
  }

  if (options.isAgentMode && agentRequiresRerankModel(config)) {
    const rerankModelID = config?.rerankModelId?.trim()
    const rerankExists = !!rerankModelID
      && models.some(model => model.type === 'Rerank' && model.id === rerankModelID)
    if (!rerankExists) {
      reasons.push('rerank_model')
    }
  }

  return reasons
}

/** Map missing-config reasons to the agent editor section that fixes them. */
export function resolveAgentNotReadySection(reasons: AgentNotReadyReasonKey[]): string {
  if (reasons.includes('summary_model') || reasons.includes('rerank_model')) {
    return 'model'
  }
  if (reasons.includes('allowedTools')) {
    return 'tools'
  }
  return 'model'
}

/** First missing item — used to highlight the corresponding editor field after navigation. */
export function resolveAgentNotReadyHighlight(
  reasons: AgentNotReadyReasonKey[],
): AgentNotReadyReasonKey | undefined {
  return reasons[0]
}
