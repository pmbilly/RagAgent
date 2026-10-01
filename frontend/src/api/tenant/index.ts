import { del, get, post, put } from '@/utils/request'
import i18n from '@/i18n'

const t = (key: string) => i18n.global.t(key)

// 空间信息接口
export interface TenantInfo {
  id: number
  name: string
  description?: string
  status?: string
  business?: string
  storage_quota?: number
  storage_used?: number
  created_at: string
  updated_at: string
}

export type APIPrincipalMode = 'tenant' | 'direct_header' | 'signed_token'

export interface APIPrincipalConfig {
  mode: APIPrincipalMode
  directHeaderName: string
  signedTokenHeaderName: string
  requireDirectHeader: boolean
  // The server never returns the plaintext secret; only its presence.
  hasHmacSecret: boolean
}

export interface UpdateAPIPrincipalConfigPayload {
  mode: APIPrincipalMode
  directHeaderName?: string
  signedTokenHeaderName?: string
  requireDirectHeader?: boolean
  hmacSecret?: string
}

export interface CreateAPIPrincipalTestTokenPayload {
  externalUserId: string
  expiresInSeconds?: number
}

export interface APIPrincipalTestToken {
  token: string
  headerName: string
  expiresInSeconds: number
  expiresAtUnix: number
  externalUserId: string
}

// Bounded per-key grants for non-full-access API keys.
//  - 'retrieve': read/search knowledge-base data within scope
//  - 'chat': run the conversation flow (sessions + agent listing + self identity)
//  - 'read_agents': list/read agents without chat or authoring
//  - 'ingest': write content into allowed knowledge bases (docs/chunks/FAQ/tags/wiki)
//  - 'manage_kbs': manage the KB lifecycle (create/copy/duplicate/update/delete + config)
//  - 'manage_agents': create/update/delete/copy agents
//  - 'message_history': search/read tenant chat-history metadata
//  - 'manage_models': manage tenant model definitions, checks, and credentials
//  - 'manage_mcp_services': manage MCP services, credentials, tool policies, and OAuth state
//  - 'manage_datasources': manage data-source connectors and sync jobs
//  - 'manage_channels': manage embed and IM channels
//  - 'manage_vector_stores': manage vector stores and parser/storage checks
//  - 'manage_web_search': manage web-search providers
//  - 'run_evaluations': run/read evaluation jobs
//  - 'manage_members': manage tenant members and invitations
//  - 'manage_spaces': manage organization/space collaboration
//  - 'manage_tenant_settings': read/update tenant integration settings (API principal mode, headers, tenant KV)
export type TenantAPIKeyCapability =
  | 'retrieve'
  | 'chat'
  | 'read_agents'
  | 'ingest'
  | 'manage_kbs'
  | 'manage_agents'
  | 'message_history'
  | 'manage_models'
  | 'manage_mcp_services'
  | 'manage_datasources'
  | 'manage_channels'
  | 'manage_vector_stores'
  | 'manage_storage_backends'
  | 'manage_web_search'
  | 'run_evaluations'
  | 'manage_members'
  | 'manage_spaces'
  | 'manage_tenant_settings'
  | 'system_tenants_read'
  | 'system_tenants_manage'
  | 'system_settings_read'
  | 'system_settings_manage'
  | 'system_runtime_read'
  | 'system_runtime_manage'
  | 'system_audit_read'

export interface TenantAPIKey {
  id: number
  scopeType?: 'tenant' | 'platform'
  name: string
  apiKey: string
  fullAccess: boolean
  knowledgeBaseIds: string[] | null
  capabilities?: TenantAPIKeyCapability[]
  lastUsedAt?: string
  expiresAt?: string
  createdAt: string
}

export interface CreatedTenantAPIKey extends TenantAPIKey {
  token?: string
}

export interface CreateTenantAPIKeyPayload {
  name: string
  fullAccess?: boolean
  knowledgeBaseIds?: string[]
  capabilities?: TenantAPIKeyCapability[]
  expiresAtUnix?: number
}

export interface UpdateTenantAPIKeyPayload {
  name: string
  fullAccess: boolean
  knowledgeBaseIds: string[]
  capabilities: TenantAPIKeyCapability[]
  expiresAtUnix?: number
}

// 搜索空间参数
export interface SearchTenantsParams {
  keyword?: string
  tenantId?: number
  page?: number
  pageSize?: number
}

// 搜索空间响应
export interface SearchTenantsResponse {
  success: boolean
  data?: {
    items: TenantInfo[]
    total: number
    page: number
    pageSize: number
  }
  message?: string
}

/**
 * 获取所有空间列表（需要跨空间访问权限）
 * @deprecated 建议使用 searchTenants 代替，支持分页和搜索
 */
export async function listAllTenants(): Promise<{ items: TenantInfo[] }> {
  try {
    const response = await get('/api/v1/tenants/all')
    return response as unknown as { items: TenantInfo[] }
  } catch (error: any) {
    throw new Error(error?.message || t('error.tenant.listFailed'))
  }
}

export async function getAPIPrincipalConfig(
  tenantId: number,
): Promise<APIPrincipalConfig> {
  try {
    const response = await get(`/api/v1/tenants/${tenantId}/api-principal-config`)
    return response as unknown as APIPrincipalConfig
  } catch (error: any) {
    throw new Error(error?.message || t('error.tenant.getApiPrincipalConfigFailed'))
  }
}

export async function updateAPIPrincipalConfig(
  tenantId: number,
  payload: UpdateAPIPrincipalConfigPayload,
): Promise<APIPrincipalConfig> {
  try {
    const response = await put(`/api/v1/tenants/${tenantId}/api-principal-config`, payload)
    return response as unknown as APIPrincipalConfig
  } catch (error: any) {
    throw new Error(error?.message || t('error.tenant.updateApiPrincipalConfigFailed'))
  }
}

export async function createAPIPrincipalTestToken(
  tenantId: number,
  payload: CreateAPIPrincipalTestTokenPayload,
): Promise<APIPrincipalTestToken> {
  try {
    const response = await post(`/api/v1/tenants/${tenantId}/api-principal-test-token`, payload)
    return response as unknown as APIPrincipalTestToken
  } catch (error: any) {
    throw new Error(error?.message || t('error.tenant.createApiPrincipalTestTokenFailed'))
  }
}

export async function listTenantAPIKeys(
  tenantId: number,
): Promise<TenantAPIKey[]> {
  try {
    const response = await get(`/api/v1/tenants/${tenantId}/api-keys`)
    return response as unknown as TenantAPIKey[]
  } catch (error: any) {
    throw new Error(error?.message || t('error.tenant.listApiKeysFailed'))
  }
}

export async function createTenantAPIKey(
  tenantId: number,
  payload: CreateTenantAPIKeyPayload,
): Promise<CreatedTenantAPIKey> {
  try {
    const response = await post(`/api/v1/tenants/${tenantId}/api-keys`, payload)
    return response as unknown as CreatedTenantAPIKey
  } catch (error: any) {
    throw new Error(error?.message || t('error.tenant.createApiKeyFailed'))
  }
}

/** 更新已创建租户 API Key 的授权范围和其他可配置属性。 */
export async function updateTenantAPIKey(
  tenantId: number,
  keyId: number,
  payload: UpdateTenantAPIKeyPayload,
): Promise<TenantAPIKey> {
  try {
    const response = await put(`/api/v1/tenants/${tenantId}/api-keys/${keyId}`, payload)
    return response as unknown as TenantAPIKey
  } catch (error: any) {
    throw new Error(error?.message || t('integrations.api.updateApiKeyScopeFailed'))
  }
}

export async function deleteTenantAPIKey(
  tenantId: number,
  keyId: number,
): Promise<void> {
  try {
    const response = await del(`/api/v1/tenants/${tenantId}/api-keys/${keyId}`)
    return
  } catch (error: any) {
    throw new Error(error?.message || t('error.tenant.deleteApiKeyFailed'))
  }
}

/**
 * 更新空间信息（目前暴露名称、描述两个字段的编辑入口）。
 * 后端 `PUT /tenants/:id` 用指针字段区分"未传"和"显式空串"，未传的列不会
 * 被改动；这里也按需选择性传 `name` / `description`，互不影响。
 * 权限：owner（与 router.go 中的 g.Owner() 守卫保持一致）。
 */
export async function updateTenant(
  tenantId: number,
  payload: { name?: string; description?: string },
): Promise<TenantInfo> {
  try {
    const response = await put(`/api/v1/tenants/${tenantId}`, payload)
    return response as unknown as TenantInfo
  } catch (error: any) {
    throw new Error(error?.message || t('error.tenant.updateFailed'))
  }
}

/**
 * 删除当前工作区。权限：owner。
 */
export async function deleteTenant(
  tenantId: number,
): Promise<void> {
  try {
    const response = await del(`/api/v1/tenants/${tenantId}`)
    return
  } catch (error: any) {
    throw new Error(error?.message || t('error.tenant.deleteFailed'))
  }
}

/**
 * 创建新工作区（任意已登录用户均可调用）。
 * 后端会自动把调用者写成新空间的 Owner，并填充默认 storage_quota
 * 等服务端字段；API Key 由用户在集成页手动创建。
 * 路由：POST /api/v1/tenants（router 上不挂 g.CrossTenant()，自助场景使用）。
 */
export async function createTenant(
  payload: { name: string; description?: string },
): Promise<TenantInfo> {
  try {
    const response = await post('/api/v1/tenants', payload)
    return response as unknown as TenantInfo
  } catch (error: any) {
    const code = error?.error?.code ?? error?.code
    if (code === 2005) {
      throw new Error(t('tenant.create.disabled'))
    }
    throw new Error(error?.message || t('error.tenant.createFailed'))
  }
}

/**
 * 搜索空间（支持分页、关键词搜索和空间ID过滤）
 */
export async function searchTenants(params: SearchTenantsParams = {}): Promise<SearchTenantsResponse> {
  try {
    const queryParams = new URLSearchParams()
    if (params.keyword) {
      queryParams.append('keyword', params.keyword)
    }
    if (params.tenantId) {
      queryParams.append('tenantId', String(params.tenantId))
    }
    if (params.page) {
      queryParams.append('page', String(params.page))
    }
    if (params.pageSize) {
      queryParams.append('pageSize', String(params.pageSize))
    }
    
    const queryString = queryParams.toString()
    const url = `/api/v1/tenants/search${queryString ? '?' + queryString : ''}`
    const response = await get(url)
    return response as unknown as SearchTenantsResponse
  } catch (error: any) {
    throw new Error(error?.message || t('error.tenant.searchFailed'))
  }
}
