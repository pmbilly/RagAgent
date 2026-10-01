import { get, post, put, del } from '@/utils/request'

// ===== Types =====

export interface VectorStoreEntity {
  id?: string
  name: string
  engineType: string
  connectionConfig: Record<string, any>
  indexConfig: Record<string, any>
  source: 'env' | 'user'
  readOnly: boolean
  tenantId?: number
  createdAt?: string
  updatedAt?: string
}

export interface VectorStoreTypeInfo {
  type: string
  displayName: string
  connectionFields: FieldSchema[]
  indexFields: FieldSchema[]
}

export interface FieldSchema {
  name: string
  type: 'string' | 'number' | 'boolean'
  required: boolean
  sensitive?: boolean
  description?: string
  default?: any
  // Inclusive bounds for number fields (omitempty on the backend). When
  // absent the UI falls back to per-field heuristics (isReplicaField).
  min?: number
  max?: number
  // Closed value set for string fields (e.g. knnEngine ∈ lucene|faiss).
  // When non-empty the UI renders a select instead of a free-text input.
  enum?: string[]
  // Marks a field that cannot change after store creation. Informational
  // for now (edit mode is fully read-only); kept for forward use.
  immutable?: boolean
}

// ===== API Functions =====

export function listVectorStoreTypes(): Promise<VectorStoreTypeInfo[]> {
  return get('/api/v1/vector-stores/types')
}

export function listVectorStores(): Promise<VectorStoreEntity[]> {
  return get('/api/v1/vector-stores')
}

export function createVectorStore(data: Partial<VectorStoreEntity>) {
  return post('/api/v1/vector-stores', data)
}

export function updateVectorStore(id: string, data: Partial<VectorStoreEntity>) {
  return put(`/api/v1/vector-stores/${id}`, data)
}

export function deleteVectorStore(id: string) {
  return del(`/api/v1/vector-stores/${id}`)
}

export function testVectorStoreRaw(data: { engineType: string; connectionConfig: any }): Promise<any> {
  return post('/api/v1/vector-stores/test', data)
}

export function testVectorStoreById(id: string): Promise<any> {
  return post(`/api/v1/vector-stores/${id}/test`, {})
}
