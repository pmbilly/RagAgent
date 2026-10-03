import { get, post, put, del } from '@/utils/request'

export interface MCPService {
  id: string
  tenantId?: number
  name: string
  description: string
  usageInstructions?: string
  enabled: boolean
  transportType: 'sse' | 'http-streamable' | 'stdio'
  url?: string // Optional: required for SSE/HTTP Streamable
  headers?: Record<string, string>
  authConfig?: {
    // Authentication strategy — wire values are the backend enum's lowercase
    // values (contract §1.7): "" (none) / "api_key" / "bearer" / "oauth",
    // NOT camelCase. "oauth" enables the per-user OAuth2 authorization-code
    // flow (zero-config: discovery + dynamic client registration).
    // 2026-10-03 缺陷实录：这里曾写成 'apiKey'（camel）→ 后端 fromValue 不匹配
    // → 策略被静默写成 null，用户"配了 API Key 却不生效"。
    authType?: '' | 'api_key' | 'bearer' | 'oauth'
    // Secret fields (api_key, token) are NEVER returned by the server in
    // this shape — they live behind the /credentials subresource. The
    // optional-property typing remains so create-mode payloads can still
    // carry them in the initial POST body.
    apiKey?: string
    // Header name carrying api_key when auth_type is "api_key". Non-secret;
    // empty defaults to "X-API-Key". Lets services expecting the key in a
    // different header (e.g. raw token in "Authorization") work.
    apiKeyHeader?: string
    token?: string
    customHeaders?: Record<string, string>
    // OAuth-only, non-secret configuration.
    scopes?: string[]
    authServerMetadataUrl?: string
  }
  advancedConfig?: {
    timeout?: number
    retryCount?: number
    retryDelay?: number
  }
  stdioConfig?: {
    command: 'uvx' | 'npx' // Command: uvx or npx
    args: string[] // Command arguments array
  }
  envVars?: Record<string, string> // Environment variables for stdio transport
  isBuiltin?: boolean // Whether this is a builtin MCP service
  // Per-field "configured?" map embedded on the main response (server-side
  // dto.MCPServiceResponse.Credentials). Drives the CredentialResource card
  // without a follow-up GET. Absent for builtin services.
  credentials?: Record<McpCredentialField, CredentialFieldMetadata>
  createdAt?: string
  updatedAt?: string
  catalog?: {
    toolCount: number
    stale: boolean
    syncedAt: string
  }
}

export interface MCPTool {
  name: string
  description: string
  inputSchema: Record<string, any>
  require_approval?: boolean
  enabled?: boolean
}

export interface MCPToolApprovalRow {
  id: string
  tenantId?: number
  serviceId: string
  toolName: string
  requireApproval: boolean
  enabled: boolean
}

export interface MCPResource {
  uri: string
  name: string
  description?: string
  mimeType?: string
}

export interface MCPTestResult {
  success: boolean
  message?: string
  description?: string
  // Set when the server requires OAuth (RFC 9728) but the service was not
  // configured for it — the UI guides the user to switch to OAuth 2.0.
  oauthRequired?: boolean
  tools?: MCPTool[]
  resources?: MCPResource[]
}

// List all MCP services
export async function listMCPServices(): Promise<MCPService[]> {
  // 裸数组（§14.9n M1：无 {data,success} 信封）
  const response: any = await get('/api/v1/mcp-services')
  return Array.isArray(response) ? response : []
}

// Get a single MCP service by ID
export async function getMCPService(id: string): Promise<MCPService> {
  return get<MCPService>(`/api/v1/mcp-services/${id}`)
}

// Create a new MCP service
export async function createMCPService(data: Partial<MCPService>): Promise<MCPService> {
  // 201 + 裸资源（§1.15）
  return post<MCPService>('/api/v1/mcp-services', data)
}

// Update an existing MCP service
export async function updateMCPService(id: string, data: Partial<MCPService>): Promise<MCPService> {
  return put<MCPService>(`/api/v1/mcp-services/${id}`, data)
}

// Delete an MCP service
export async function deleteMCPService(id: string): Promise<void> {
  await del(`/api/v1/mcp-services/${id}`)
}

// Test MCP service connection
export async function testMCPService(id: string): Promise<MCPTestResult> {
  // 裸 McpTestResult（success 是**业务结论**，不是信封）
  return post<MCPTestResult>(`/api/v1/mcp-services/${id}/test`, {})
}

// Get tools from an MCP service
export async function getMCPServiceTools(id: string): Promise<MCPTool[]> {
  const response: any = await get(`/api/v1/mcp-services/${id}/tools`)
  return Array.isArray(response) ? response : []
}

// Get resources from an MCP service
export async function getMCPServiceResources(id: string): Promise<MCPResource[]> {
  const response: any = await get(`/api/v1/mcp-services/${id}/resources`)
  return Array.isArray(response) ? response : []
}

/** Persisted per-tool human-approval flags (issue #1173) */
export async function getMCPToolApprovals(serviceId: string): Promise<MCPToolApprovalRow[]> {
  // 裸数组（§14.9n M4）
  const response: any = await get(`/api/v1/mcp-services/${serviceId}/tool-approvals`)
  return Array.isArray(response) ? response : []
}

export async function setMCPToolApproval(serviceId: string, toolName: string, requireApproval: boolean): Promise<void> {
  await put(`/api/v1/mcp-services/${serviceId}/tool-approvals/${encodeURIComponent(toolName)}`, {
    requireApproval
  })
}

export async function setMCPToolEnabled(serviceId: string, toolName: string, enabled: boolean): Promise<void> {
  await put(`/api/v1/mcp-services/${serviceId}/tool-approvals/${encodeURIComponent(toolName)}`, { enabled })
}

// ----------------------------------------------------------------------------
// Credential subresource (issue #988 follow-up).
//
// Secrets travel through a dedicated /credentials endpoint instead of the
// main MCP PUT body. "Is this configured?" metadata is embedded on the main
// MCPService response (MCPService.credentials), so there is no GET on this
// endpoint — only PUT (write) and DELETE (clear). Both trigger an MCP
// client reconnect server-side.
// ----------------------------------------------------------------------------

export type McpCredentialField = 'apiKey' | 'token'

export interface CredentialFieldMetadata {
  configured: boolean
}

export interface McpCredentialsResponse {
  fields: Record<McpCredentialField, CredentialFieldMetadata>
}

export async function putMCPCredentials(
  serviceId: string,
  body: Partial<Record<McpCredentialField, string>>
): Promise<McpCredentialsResponse> {
  // 裸 {fields:{...}}（§14.9n M1）
  return put<McpCredentialsResponse>(`/api/v1/mcp-services/${serviceId}/credentials`, body)
}

export async function deleteMCPCredentialField(
  serviceId: string,
  field: McpCredentialField
): Promise<void> {
  await del(`/api/v1/mcp-services/${serviceId}/credentials/${field}`)
}

// ----------------------------------------------------------------------------
// Per-user OAuth2 authorization-code flow.
//
// The user authorizes a service once; the backend stores their access/refresh
// token (per tenant + user + service) and refreshes it transparently. The
// callback is a public backend route that the third-party authorization
// server redirects to.
// ----------------------------------------------------------------------------

// Path of the public backend OAuth callback (registered outside /mcp-services
// to avoid a route conflict, and allow-listed for no-auth in the backend).
export const MCP_OAUTH_CALLBACK_PATH = '/api/v1/mcp-oauth/callback'

export interface MCPOAuthAuthorization {
  authorizationUrl: string
  authorizationAttempt: string
}

export type MCPOAuthTokenState = 'authorized' | 'refreshable' | 'reauth_required' | 'pending'

export interface MCPOAuthStatus {
  authorized: boolean
  state: MCPOAuthTokenState
  refreshAvailable: boolean
  /** null = 不过期（§1.6：键恒在，不再是 omitempty 式的"键消失"） */
  expiresAt: string | null
}

// Begin authorization for the current user. The attempt id binds polling to
// this popup, so an older stored token cannot be mistaken for fresh consent.
export async function getMCPOAuthAuthorizeURL(
  serviceId: string,
  body: { redirectUri: string; frontendRedirect?: string }
): Promise<MCPOAuthAuthorization> {
  // 裸对象（§14.9n M4）
  const data: any = await post(`/api/v1/mcp-services/${serviceId}/oauth/authorize-url`, body)
  return {
    authorizationUrl: data?.authorizationUrl ?? '',
    authorizationAttempt: data?.authorizationAttempt ?? '',
  }
}

// Whether the current user has authorized this service.
export async function getMCPOAuthStatus(
  serviceId: string,
  authorizationAttempt?: string,
): Promise<boolean> {
  const query = authorizationAttempt
    ? `?authorization_attempt=${encodeURIComponent(authorizationAttempt)}`
    : ''
  const response: any = await get(`/api/v1/mcp-services/${serviceId}/oauth/status${query}`)
  return Boolean(response?.authorized)
}

// Full lifecycle status for management surfaces. Expired access tokens with a
// refresh token are "refreshable", not falsely presented as already usable.
export async function getMCPOAuthAuthorizationStatus(serviceId: string): Promise<MCPOAuthStatus> {
  const data: any = await get(`/api/v1/mcp-services/${serviceId}/oauth/status`)
  return {
    authorized: Boolean(data?.authorized),
    state: data?.state ?? 'reauth_required',
    refreshAvailable: Boolean(data?.refreshAvailable),
    expiresAt: data?.expiresAt ?? null,
  }
}

// Revoke the current user's token (forces re-authorization).
export async function revokeMCPOAuthToken(serviceId: string): Promise<void> {
  await del(`/api/v1/mcp-services/${serviceId}/oauth/token`)
}

export async function resolveToolApproval(
  pendingId: string,
  body: { decision: 'approve' | 'reject'; modifiedArgs?: Record<string, unknown>; reason?: string }
): Promise<void> {
  await post(`/api/v1/agent/tool-approvals/${encodeURIComponent(pendingId)}`, body)
}

// Resume an agent run that paused on an in-conversation MCP OAuth prompt.
// Call after the per-user authorization popup completes; the backend verifies
// the token exists before unblocking the paused tool call.
export async function resolveMCPOAuth(
  pendingId: string,
  body: { serviceId: string; decision?: 'authorize' | 'cancel' }
): Promise<void> {
  await post(`/api/v1/agent/mcp-oauth-resolutions/${encodeURIComponent(pendingId)}`, body)
}

export async function cancelMCPOAuth(pendingId: string): Promise<void> {
  await post(`/api/v1/agent/mcp-oauth-resolutions/${encodeURIComponent(pendingId)}/cancel`, {})
}

// Persisted directory: GET never opens an upstream MCP connection.
export interface MCPMetadata {
  serviceId: string
  tools: MCPTool[]
  instructions: string
  serverName: string
  serverVersion: string
  serverDescription: string
  syncedAt: string
  stale: boolean
}

export async function getMCPMetadata(id: string): Promise<MCPMetadata | null> {
  // 从未同步 → 裸 JSON null（§2.1）
  const response: any = await get(`/api/v1/mcp-services/${id}/metadata`)
  return response ?? null
}

export async function refreshMCPMetadata(id: string): Promise<MCPMetadata> {
  return post<MCPMetadata>(`/api/v1/mcp-services/${id}/metadata/refresh`, {})
}

export async function generateMCPUsageInstructions(id: string, language: string): Promise<string> {
  const response: any = await post(`/api/v1/mcp-services/${id}/usage-instructions/generate`, { language }, { timeout: 65000 })
  return response.usageInstructions
}
