import { get, post } from '@/utils/request'

export interface MessageSuggestionItem {
  id: string
  text: string
  category?: 'clarify' | 'deepen' | 'action'
  source: 'model' | 'faq' | 'document' | 'wiki' | string
  knowledgeBaseIds?: string[]
}

export interface MessageSuggestionSet {
  id: string
  sessionId: string
  assistantMessageId: string
  status: 'generating' | 'ready' | 'suppressed' | 'failed'
  allowRegenerate: boolean
  suppressionReason?: string
  questions: MessageSuggestionItem[]
  generatedAt?: string
}

/** 裸资源：响应体即建议集（§2.1，无 {data,success} 信封）。 */
export function ensureMessageSuggestions(sessionId: string, messageId: string, regenerate = false) {
  return post<MessageSuggestionSet>(
    `/api/v1/sessions/${sessionId}/messages/${messageId}/suggestions`,
    { regenerate },
  )
}

export function getMessageSuggestions(sessionId: string, messageId: string) {
  return get<MessageSuggestionSet>(
    `/api/v1/sessions/${sessionId}/messages/${messageId}/suggestions`,
  )
}

export function recordMessageSuggestionEvent(
  sessionId: string,
  suggestionSetId: string,
  eventType: 'impression' | 'click' | 'dismiss',
  questionId = '',
) {
  return post(
    `/api/v1/sessions/${sessionId}/suggestion-events`,
    { suggestionSetId, questionId, eventType },
  )
}
