import { get, put, post } from '@/utils/request'

// ChatHistoryConfig represents the chat history KB configuration for a tenant.
// knowledge_base_id is auto-managed by the backend; frontend only sets other fields.
export interface ChatHistoryConfig {
  enabled: boolean
  embedding_model_id: string
  knowledge_base_id?: string // read-only, auto-managed
}

// ChatHistoryKBStats represents statistics about the chat history knowledge base
// 键名＝服务端字段名（§14.9l S2 后为 camelCase）
export interface ChatHistoryKBStats {
  enabled: boolean
  embeddingModelId?: string
  knowledgeBaseId?: string
  knowledgeBaseName?: string
  indexedMessageCount: number
  hasIndexedMessages: boolean
}

// MessageSearchRequest defines search parameters for message search
export interface MessageSearchRequest {
  query: string
  mode?: 'keyword' | 'vector' | 'hybrid'
  limit?: number
  sessionIds?: string[]
}

// MessageSearchGroupItem represents a merged Q&A pair in search results
export interface MessageSearchGroupItem {
  requestId: string
  sessionId: string
  sessionTitle: string
  queryContent: string
  answerContent: string
  score: number
  matchType: string
  createdAt: string
}

// MessageSearchResult represents the full search result
export interface MessageSearchResult {
  items: MessageSearchGroupItem[]
  total: number
}

// Get tenant chat history config via KV API
export function getTenantChatHistoryConfig() {
  return get('/api/v1/tenants/kv/chat-history-config')
}

// Update tenant chat history config via KV API
export function updateTenantChatHistoryConfig(config: ChatHistoryConfig) {
  return put('/api/v1/tenants/kv/chat-history-config', config)
}

// Get chat history KB statistics
export function getChatHistoryKBStats() {
  return get('/api/v1/messages/chat-history-stats')
}

// Search messages across all sessions (keyword + vector hybrid search)
export function searchMessages(data: MessageSearchRequest) {
  return post('/api/v1/messages/search', data)
}
