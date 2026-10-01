import { get, put, post, del } from '@/utils/request'

// Kinds mirror internal/types/memory.go. profile and preference make up the
// block injected on every turn; fact and task are pulled in only when the
// current question matches them.
export type MemoryKind = 'profile' | 'preference' | 'fact' | 'task' | 'interest'
export type MemoryStatus = 'active' | 'superseded' | 'archived' | 'pending'
export type MemoryOrigin = 'explicit' | 'extracted' | 'manual'

// Wire format follows docs/knowledge-api-contract-v1.md: JSON field names are
// the Java field names (camelCase), success responses carry no {data, success}
// envelope, list endpoints answer {items, page, pageSize, total}.
export interface MemoryItem {
  id: string
  tenantId: number
  subjectId: string
  kind: MemoryKind
  content: string
  topic: string
  normalizedKey: string
  importance: number
  origin: MemoryOrigin
  status: MemoryStatus
  sourceSessionId: string
  sourceMessageId: string
  validFrom: string
  invalidAt: string | null
  expiresAt: string | null
  replacesId: string
  supersededBy: string
  lastUsedAt: string | null
  useCount: number
  createdAt: string
  updatedAt: string
}

// MemorySettings is already merged server-side, so the UI never has to combine
// a workspace switch with a personal one itself.
export interface MemorySettings {
  workspaceEnabled: boolean
  userEnabled: boolean
  effective: boolean
  writeMode: string
  itemCount: number
  maxItems: number
}

/** Paginated list shape shared by items / topics / documents (§2.1). */
export interface MemoryList<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
}

export interface MemoryConfig {
  enabled: boolean
  writeMode: 'explicit_only' | 'auto'
  extractModelId: string
  maxItems: number
  /** Debounce before distillation runs, in seconds. */
  extractDelaySeconds: number
  /** Floor between two distillation runs for one person, in seconds. */
  extractMinIntervalSeconds: number
  /** Workspace-specific rules appended to the distillation prompt. */
  extractInstructions: string
  /** How many conversations must touch a topic before it becomes an interest. */
  interestThreshold: number
  /** Whether memory may shape retrieval, not only the answer prompt. */
  retrievalConditioning: boolean
  /** Model used to score memory against a question. Blank = lexical matching only. */
  embeddingModelId: string
  /** Whether recall also matches on meaning, not only on wording. */
  vectorRecall: boolean
}

// ---------------------------------------------------------------------------
// Personal memory. Every endpoint operates on the caller's own memory space,
// which the server derives from the request principal, so none of these take
// an owner parameter.
// ---------------------------------------------------------------------------

export function getMemorySettings() {
  return get<MemorySettings>('/api/v1/memory/settings')
}

export function updateMemoryEnabled(enabled: boolean) {
  return put<MemorySettings>('/api/v1/memory/settings', { enabled })
}

export function listMemoryItems(params: { status?: MemoryStatus; limit?: number; offset?: number } = {}) {
  const query = new URLSearchParams()
  if (params.status) query.set('status', params.status)
  if (params.limit != null) query.set('limit', String(params.limit))
  if (params.offset != null) query.set('offset', String(params.offset))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return get<MemoryList<MemoryItem>>(`/api/v1/memory/items${suffix}`)
}

/** Accept a memory the system inferred, so it starts being used. Returns the confirmed item. */
export function confirmMemoryItem(id: string) {
  return post<MemoryItem>(`/api/v1/memory/items/${id}/confirm`, {})
}

/** Decline an inference (204). The refusal is remembered, so it is not re-proposed. */
export function rejectMemoryItem(id: string) {
  return post<void>(`/api/v1/memory/items/${id}/reject`, {})
}

/** Create a memory by hand (201 + the created item). */
export function createMemoryItem(payload: { kind: MemoryKind; content: string; importance?: number }) {
  return post<MemoryItem>('/api/v1/memory/items', payload)
}

export function updateMemoryItem(id: string, payload: { content: string; importance: number }) {
  return put<MemoryItem>(`/api/v1/memory/items/${encodeURIComponent(id)}`, payload)
}

export function deleteMemoryItem(id: string) {
  return del<void>(`/api/v1/memory/items/${encodeURIComponent(id)}`)
}

/**
 * Clear every memory (204). The count of removed rows is gone with the old
 * envelope — the store is empty afterwards, which is the only thing the UI says.
 */
export function clearMemoryItems() {
  return del<void>('/api/v1/memory/items')
}

export interface MemoryExport {
  /** `null` (not `[]`) when the export found nothing — a server-side quirk kept on purpose. */
  items: MemoryItem[] | null
  total: number
  truncated: boolean
}

export function exportMemoryItems() {
  return get<MemoryExport>('/api/v1/memory/export')
}

/** Why a review changed nothing. `null` when it did change something. */
export type MemoryConsolidationSkip =
  | 'too_few_items'
  | 'no_candidates'
  | 'model_unavailable'
  | 'model_declined'
  | 'too_soon'

export interface MemoryConsolidationResult {
  merged: number
  demoted: number
  expired: number
  reviewed: number
  candidates: number
  skipped: MemoryConsolidationSkip | null
}

/** Merge near-duplicates now, without waiting for the daily distillation pass. */
export function consolidateMemory() {
  return post<MemoryConsolidationResult>('/api/v1/memory/consolidate', {})
}

export interface MemoryTopic {
  id: string
  topic: string
  aliases: string[]
  hits: number
  threshold: number
  lastSeenAt: string
}

export function listMemoryTopics(params: { limit?: number; offset?: number } = {}) {
  const query = new URLSearchParams()
  if (params.limit != null) query.set('limit', String(params.limit))
  if (params.offset != null) query.set('offset', String(params.offset))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return get<MemoryList<MemoryTopic>>(`/api/v1/memory/topics${suffix}`)
}

/** Promote a counted topic into a long-term interest without waiting. Returns the new memory. */
export function promoteMemoryTopic(id: string) {
  return post<MemoryItem>(`/api/v1/memory/topics/${encodeURIComponent(id)}/promote`, {})
}

/** Stop tracking a topic (204). The refusal is remembered so it is not auto-promoted later. */
export function deleteMemoryTopic(id: string) {
  return del<void>(`/api/v1/memory/topics/${encodeURIComponent(id)}`)
}

export interface MemoryDoc {
  id: string
  knowledgeId: string
  knowledgeBaseId: string
  title: string
  hits: number
  lastUsedAt: string
}

export function listMemoryDocuments(params: { limit?: number; offset?: number } = {}) {
  const query = new URLSearchParams()
  if (params.limit != null) query.set('limit', String(params.limit))
  if (params.offset != null) query.set('offset', String(params.offset))
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return get<MemoryList<MemoryDoc>>(`/api/v1/memory/documents${suffix}`)
}

/** Stop using one document as a personal retrieval signal (204). */
export function deleteMemoryDocument(id: string) {
  return del<void>(`/api/v1/memory/documents/${encodeURIComponent(id)}`)
}

// ---------------------------------------------------------------------------
// Workspace configuration, stored on the tenant like the other KV configs.
// The endpoint answers the bare config object, and its keys are the stored
// jsonb names — camelCase since the M2 (stored-format) re-anchoring.
// ---------------------------------------------------------------------------

export function getTenantMemoryConfig() {
  return get<MemoryConfig>('/api/v1/tenants/kv/memory-config')
}

export function updateTenantMemoryConfig(config: MemoryConfig) {
  return put<MemoryConfig>('/api/v1/tenants/kv/memory-config', config)
}
