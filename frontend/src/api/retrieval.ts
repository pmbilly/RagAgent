import { get, put } from '@/utils/request'

// RetrievalConfig represents the global retrieval/search configuration for a tenant.
// Shared by knowledge search and message search.
export interface RetrievalConfig {
  embedding_top_k: number
  vector_threshold: number
  keyword_threshold: number
  rerank_top_k: number
  rerank_threshold: number
  rerank_model_id: string
}

// Get tenant retrieval config via KV API
export async function getTenantRetrievalConfig(): Promise<{ data: RetrievalConfig }> {
  // 后端 200 裸配置对象（§2.1）；适配成消费端既有的 { data } 契约
  const resp = (await get('/api/v1/tenants/kv/retrieval-config')) as unknown as RetrievalConfig
  return { data: resp }
}

// Update tenant retrieval config via KV API
export async function updateTenantRetrievalConfig(config: RetrievalConfig): Promise<{ data: RetrievalConfig }> {
  // 后端 200 裸配置对象（§2.1）；适配成消费端既有的 { data } 契约
  const resp = (await put('/api/v1/tenants/kv/retrieval-config', config)) as unknown as RetrievalConfig
  return { data: resp }
}
