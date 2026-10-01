import { get, post, put, del } from '../../utils/request'

// --- Types ---

// 键名＝服务端字段名（§14.9q D1 换锚后的 camelCase）
export interface DataSource {
  id: string
  tenantId: number
  knowledgeBaseId: string
  name: string
  type: string
  config: DataSourceConfig | null
  syncSchedule: string
  syncMode: 'incremental' | 'full'
  status: 'active' | 'paused' | 'error'
  conflictStrategy: 'overwrite' | 'skip'
  syncDeletions: boolean
  lastSyncAt: string | null
  lastSyncCursor?: unknown
  lastSyncResult: any
  errorMessage: string
  syncLogRetentionDays: number
  totalItemsSynced: number
  // Single-field "credentials" map from the main response — DataSource
  // credentials are a per-connector atomic set.
  credentials?: { credentials: { configured: boolean } }
  createdAt: string
  updatedAt: string
  deletedAt?: string | null
  latestSyncLog?: SyncLog
}

/** jsonb `config` 的外层包装（内层 settings/credentials 是各 connector 的字段名，原样）。 */
export interface DataSourceConfig {
  type: string
  resourceIds: string[] | null
  settings?: Record<string, unknown> | null
  credentials?: Record<string, unknown> | null
}

/**
 * One user-facing failure sample. The backend sends a stable i18n `code`
 * (+ interpolation `params`) so the UI localises it to the viewer's language;
 * `message` is a fallback when no i18n key exists (old logs decode into it).
 */
export interface SyncItemError {
  title: string
  code: string
  params: Record<string, string> | null
  message: string
}

export interface SyncResultDetail {
  total: number
  created: number
  updated: number
  deleted: number
  skipped: number
  failed: number
  deletionFailed: number
  /** Per-item failure samples (capped; null when none); localised in the sync-log drawer. */
  errors: SyncItemError[] | null
  nextCursor?: unknown
}

export interface SyncLog {
  id: string
  dataSourceId: string
  tenantId: number
  status: 'running' | 'success' | 'partial' | 'failed' | 'canceled'
  startedAt: string
  finishedAt: string | null
  itemsTotal: number
  itemsCreated: number
  itemsUpdated: number
  itemsDeleted: number
  itemsSkipped: number
  itemsFailed: number
  errorMessage: string
  result: SyncResultDetail | null
  createdAt: string
  updatedAt: string
}

export interface ConnectorMeta {
  type: string
  name: string
  description: string
  icon: string
  priority: number
  authType: string
  capabilities: string[]
}

export interface Resource {
  externalId: string
  name: string
  type: string
  description: string
  url: string
  modifiedAt?: string
  parentId?: string
  hasChildren?: boolean
  metadata?: Record<string, unknown> | null
}

// --- API calls ---

export function getConnectorTypes() {
  return get('/api/v1/datasource/types')
}

export function listDataSources(kbId: string) {
  return get(`/api/v1/datasource?kbId=${encodeURIComponent(kbId)}`)
}

export function getDataSource(id: string) {
  return get(`/api/v1/datasource/${id}`)
}

export function createDataSource(data: Partial<DataSource>) {
  return post('/api/v1/datasource', data)
}

export function updateDataSource(id: string, data: Partial<DataSource>) {
  return put(`/api/v1/datasource/${id}`, data)
}

export function deleteDataSource(id: string) {
  return del(`/api/v1/datasource/${id}`)
}

export function validateConnection(id: string) {
  return post(`/api/v1/datasource/${id}/validate`, {})
}

// Validate credentials without persisting (for "Test Connection" during creation)
export function validateCredentials(type: string, credentials: Record<string, any>) {
  return post('/api/v1/datasource/validate-credentials', { type, credentials })
}

// listResources lists selectable resources for a data source. Pass parentId to
// lazily load the direct children of a resource (e.g. expanding a Feishu wiki
// space/node), which avoids traversing the whole tree up front.
export function listResources(id: string, parentId?: string) {
  const query = parentId ? `?parentId=${encodeURIComponent(parentId)}` : ''
  return get(`/api/v1/datasource/${id}/resources${query}`, { timeout: 120000 })
}

// resolveResourceAncestors returns the ExternalIDs of every parent that must be
// expanded to reveal the given (possibly deeply nested) selections in a lazily
// loaded picker. Used when editing a data source to restore an existing selection.
export function resolveResourceAncestors(id: string, resourceIds: string[]) {
  return post(`/api/v1/datasource/${id}/resource-ancestors`, { resourceIds }, { timeout: 120000 })
}

export function triggerSync(id: string) {
  return post(`/api/v1/datasource/${id}/sync`, {})
}

export function pauseDataSource(id: string) {
  return post(`/api/v1/datasource/${id}/pause`, {})
}

export function resumeDataSource(id: string) {
  return post(`/api/v1/datasource/${id}/resume`, {})
}

export function getSyncLogs(id: string, limit = 20, offset = 0) {
  return get(`/api/v1/datasource/${id}/logs?limit=${limit}&offset=${offset}`)
}

// ----------------------------------------------------------------------------
// Data source credential subresource. Unlike the other three resources,
// DataSource exposes a single logical field "credentials" because connector
// auth is a per-connector atomic map. See internal/handler/dto/datasource.go.
// ----------------------------------------------------------------------------

export interface DataSourceCredentialsResponse {
  fields: {
    credentials: { configured: boolean }
  }
}

export async function putDataSourceCredentials(
  id: string,
  credentials: Record<string, unknown>,
): Promise<DataSourceCredentialsResponse> {
  // 裸对象（§2.1；§14.9q D1 去掉 data/success 信封）
  return (await put(`/api/v1/datasource/${id}/credentials`, { credentials })) as DataSourceCredentialsResponse
}

export async function deleteDataSourceCredentials(id: string): Promise<void> {
  await del(`/api/v1/datasource/${id}/credentials/credentials`)
}
