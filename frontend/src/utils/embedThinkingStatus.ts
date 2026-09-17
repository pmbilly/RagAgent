export interface EmbedThinkingMessage {
  thinking?: boolean
  showThink?: boolean
  isAgentMode?: boolean
  is_completed?: boolean
  content?: string
  agentEventStream?: Array<{ type?: string; thinking?: boolean; done?: boolean }>
}

/**
 * Whether a thinking process is currently in progress for a message.
 * Quick-answer (RAG) mode mirrors the `thinking` flag parsed from <think> tags
 * by useChatStreamHandler. Agent mode scans the event stream for unfinished
 * thinking events; agents without thinking enabled emit none even while
 * working, so the whole pre-answer phase (message not completed and no answer
 * content yet, e.g. tool/knowledge lookups) also counts as in progress.
 */
export function isThinkingInProgress(message?: EmbedThinkingMessage | null): boolean {
  if (!message) return false
  if (message.isAgentMode) {
    const hasActiveThinkingEvent = (message.agentEventStream ?? []).some(
      (e) => e?.type === 'thinking' && e.thinking === true && e.done !== true,
    )
    if (hasActiveThinkingEvent) return true
    const hasAnswerContent = Boolean(String(message.content ?? '').trim())
    return message.is_completed === false && !hasAnswerContent
  }
  return message.thinking === true
}
