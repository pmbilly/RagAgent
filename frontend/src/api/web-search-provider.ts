import { get, post, put, del } from '@/utils/request'

// WebSearchProviderEntity represents a configured web search provider instance
export interface WebSearchProviderEntity {
  id?: string
  tenantId?: number
  name: string
  provider: 'brave' | 'bing' | 'google' | 'duckduckgo' | 'tavily' | 'ollama' | 'baidu' | 'searxng' | 'keenable' | 'zhipu' | 'metaso' | 'exa' | 'bocha'
  description?: string
  parameters: {
    // apiKey is never returned by the server in this shape; it lives behind
    // the /credentials subresource. Kept on the type so the initial create
    // POST can still include it.
    apiKey?: string
    engineId?: string
    baseUrl?: string
    proxyUrl?: string
    extraConfig?: Record<string, string>
  }
  isDefault?: boolean
  // Per-field configured? metadata from the main response.
  credentials?: Record<WebSearchCredentialField, { configured: boolean }>
  createdAt?: string
  updatedAt?: string
}

// WebSearchProviderTypeInfo describes metadata for a provider type
export interface WebSearchProviderTypeInfo {
  id: string
  name: string
  requiresApiKey: boolean
  // Keyless-by-default providers that still accept an optional key (e.g. Keenable).
  supportsOptionalApiKey?: boolean
  requiresEngineId?: boolean
  requiresBaseUrl?: boolean
  supportsProxy?: boolean
  description?: string
  docsUrl?: string
  configFields?: WebSearchProviderConfigField[]
}

export interface WebSearchProviderConfigField {
  key: string
  label: string
  label_key?: string
  type: 'select'
  required?: boolean
  default?: string
  description?: string
  description_key?: string
  options?: Array<{ label: string; label_key?: string; value: string }>
}

// Create a new web search provider
export function createWebSearchProvider(data: Partial<WebSearchProviderEntity>) {
  return post('/api/v1/web-search-providers', data)
}

// List all web search providers for the current tenant
export async function listWebSearchProviders(): Promise<{ data: WebSearchProviderEntity[] }> {
  // 后端 200 裸数组（§2.1）；适配成消费端既有的 { data } 契约
  const resp = (await get('/api/v1/web-search-providers')) as unknown as WebSearchProviderEntity[]
  return { data: Array.isArray(resp) ? resp : [] }
}

// Get a single web search provider by ID
export function getWebSearchProvider(id: string) {
  return get(`/api/v1/web-search-providers/${id}`)
}

// Update an existing web search provider
export function updateWebSearchProvider(id: string, data: Partial<WebSearchProviderEntity>) {
  return put(`/api/v1/web-search-providers/${id}`, data)
}

// Delete a web search provider
export function deleteWebSearchProvider(id: string) {
  return del(`/api/v1/web-search-providers/${id}`)
}

// Get available provider types (for dynamic form rendering)
export function listWebSearchProviderTypes(): Promise<WebSearchProviderTypeInfo[]> {
  return get('/api/v1/web-search-providers/types')
}

// ----------------------------------------------------------------------------
// Web search provider credential subresource.
// ----------------------------------------------------------------------------

export type WebSearchCredentialField = 'apiKey'

export interface WebSearchCredentialsResponse {
  fields: Record<WebSearchCredentialField, { configured: boolean }>
}

export async function putWebSearchProviderCredentials(
  id: string,
  body: Partial<Record<WebSearchCredentialField, string>>,
): Promise<WebSearchCredentialsResponse> {
  const response: any = await put(`/api/v1/web-search-providers/${id}/credentials`, body)
  return (response.data ?? response) as WebSearchCredentialsResponse
}

export async function deleteWebSearchProviderCredentialField(
  id: string,
  field: WebSearchCredentialField,
): Promise<void> {
  await del(`/api/v1/web-search-providers/${id}/credentials/${field}`)
}

// Test a web search provider connection.
// If id is provided, tests the existing saved provider.
// If data is provided, tests with raw credentials (no persistence).
export function testWebSearchProvider(id?: string, data?: { provider: string; parameters: any }): Promise<any> {
  if (id) {
    return post(`/api/v1/web-search-providers/${id}/test`, {})
  }
  return post('/api/v1/web-search-providers/test', data || {})
}
