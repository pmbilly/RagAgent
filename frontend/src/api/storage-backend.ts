import { get, post, put, del } from '@/utils/request'

export interface StorageBackendConfig {
  mode?: string
  endpoint?: string
  region?: string
  accessKeyId?: string
  secretAccessKey?: string
  bucketName?: string
  pathPrefix?: string
  appId?: string
  useSsl?: boolean
  forcePathStyle?: boolean
  useTempBucket?: boolean
  tempBucketName?: string
  tempRegion?: string
}

export interface StorageBackend {
  id: string
  tenantId?: number
  name: string
  provider: string
  config: StorageBackendConfig
  source: 'user' | 'env'
  status: 'active' | 'disabled'
  legacyAlias?: boolean
  createdAt?: string
  updatedAt?: string
}

export interface StorageBackendListResponse {
  items: StorageBackend[]
  defaultStorageBackendId?: string | null
}

export const listStorageBackends = (): Promise<StorageBackendListResponse> => get('/api/v1/storage-backends')
export const listStorageBackendTypes = (): Promise<string[]> => get('/api/v1/storage-backends/types')
export const createStorageBackend = (data: Partial<StorageBackend>) => post('/api/v1/storage-backends', data)
export const updateStorageBackend = (id: string, data: Partial<StorageBackend>) => put(`/api/v1/storage-backends/${id}`, data)
export const deleteStorageBackend = (id: string) => del(`/api/v1/storage-backends/${id}`)
export const setDefaultStorageBackend = (id: string) => put(`/api/v1/storage-backends/${id}/default`, {})
export const testStorageBackend = (data: Partial<StorageBackend>) => post('/api/v1/storage-backends/test', data)
export const testStorageBackendByID = (id: string) => post(`/api/v1/storage-backends/${id}/test`, {})
