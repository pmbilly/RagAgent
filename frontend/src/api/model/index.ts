import { get, post, postUpload, put, del } from '../../utils/request';
import i18n from '@/i18n'
import { ModelInUseError, modelInUseErrorFromRequest } from './modelUsage'

export * from './modelUsage'

const t = (key: string) => i18n.global.t(key)

// 模型类型定义（键名 = 后端 JSON 字段名，camelCase）
export interface ModelConfig {
  id?: string;
  tenantId?: number;
  name: string;
  displayName?: string;
  type: 'KnowledgeQA' | 'Embedding' | 'Rerank' | 'VLLM' | 'ASR';
  source: 'local' | 'remote';
  description?: string;
  parameters: {
    baseUrl?: string;
    apiKey?: string;
    provider?: string; // Provider identifier: openai, aliyun, zhipu, generic
    embeddingParameters?: {
      dimension?: number;
      truncatePromptTokens?: number;
      supportsDimensionOverride?: boolean;
    };
    interfaceType?: 'ollama' | 'openai'; // VLLM专用
    parameterSize?: string; // Ollama模型参数大小 (e.g., "7B", "13B", "70B")
    extraConfig?: Record<string, string>; // Provider-specific configuration
    // 自定义 HTTP 请求头（类似 Python OpenAI SDK 的 extra_headers），
    // 会在调用远程模型 API 时附加到每个请求上。Authorization、Content-Type 等保留头会被忽略。
    customHeaders?: Record<string, string>;
    supportsVision?: boolean; // Whether the model accepts image/multimodal input
    // 对话/VLM 的上下文窗口（token）。0 或不填表示使用后端默认 200000。
    contextWindow?: number;
    maxOutputTokens?: number;
    // 后台任务（入库/富化）对该模型的并发上限，按模型 ID 全副本共享。
    // 0 或不填表示沿用全局默认（model.max_concurrency）；仅对 chat/embedding/vllm 生效。
    maxConcurrency?: number;
    appId?: string;
    // Secret fields (apiKey, appSecret) are never returned by the server in
    // this shape — they live behind the /credentials subresource. They are
    // kept on the type so create-mode payloads can still carry them in the
    // initial POST body.
    appSecret?: string;
  };
  isDefault?: boolean;
  isBuiltin?: boolean;
  status?: string;
  // Per-field configured? metadata from the main response. For builtin
  // models it is returned only to system administrators.
  credentials?: Record<ModelCredentialField, { configured: boolean }>;
  createdAt?: string;
  updatedAt?: string;
}

// 创建模型
export function createModel(data: ModelConfig): Promise<ModelConfig> {
  return new Promise((resolve, reject) => {
    post('/api/v1/models', data)
      .then((response: any) => {
        if (response && response.id) {
          resolve(response);
        } else {
          reject(new Error(response?.message || t('error.model.createFailed')));
        }
      })
      .catch((error: any) => {
        console.error('Failed to create model:', error);
        reject(error);
      });
  });
}

// 获取模型列表
export function listModels(type?: string): Promise<ModelConfig[]> {
  return new Promise((resolve, reject) => {
    const url = `/api/v1/models`;
    get(url)
      .then((response: any) => {
        const items: ModelConfig[] = Array.isArray(response) ? response : [];
        resolve(type ? items.filter((item: ModelConfig) => item.type === type) : items);
      })
      .catch((error: any) => {
        console.error('Failed to list models:', error);
        // 抛出而非吞掉：调用方（含缓存层）才能区分「真失败」与「成功但无模型」，
        // 避免把一次瞬时失败的空结果缓存下来。各 UI 调用点均已 try/catch 兜底。
        reject(error);
      });
  });
}

// 获取单个模型
export function getModel(id: string): Promise<ModelConfig> {
  return new Promise((resolve, reject) => {
    get(`/api/v1/models/${id}`)
      .then((response: any) => {
        if (response && response.id) {
          resolve(response);
        } else {
          reject(new Error(response?.message || t('error.model.getFailed')));
        }
      })
      .catch((error: any) => {
        console.error('Failed to get model:', error);
        reject(error);
      });
  });
}

// 更新模型
export function updateModel(id: string, data: Partial<ModelConfig>): Promise<ModelConfig> {
  return new Promise((resolve, reject) => {
    put(`/api/v1/models/${id}`, data)
      .then((response: any) => {
        if (response && response.id) {
          resolve(response);
        } else {
          reject(new Error(response?.message || t('error.model.updateFailed')));
        }
      })
      .catch((error: any) => {
        console.error('Failed to update model:', error);
        reject(error);
      });
  });
}

// 删除模型（204 无响应体：成功即无异常）
export function deleteModel(id: string): Promise<void> {
  return new Promise((resolve, reject) => {
    del(`/api/v1/models/${id}`)
      .then(() => resolve())
      .catch((error: any) => {
        console.error('Failed to delete model:', error);
        if (error instanceof ModelInUseError) {
          reject(error)
          return
        }
        const conflict = modelInUseErrorFromRequest(error)
        if (conflict) {
          reject(conflict)
          return
        }
        reject(error);
      });
  });
}

export interface ModelDebugOptions {
  systemPrompt?: string
  temperature?: number
  topP?: number
  maxTokens?: number
  thinking?: boolean
}

export interface ModelDebugResult {
  ok: boolean
  elapsedMs: number
  error: string | null
  request: Record<string, unknown>
  rawResponse: unknown
  observations: Record<string, unknown>
}

export async function debugModel(
  id: string,
  data: {
    input?: string
    documents?: string[]
    options?: ModelDebugOptions
    file?: File | null
  },
): Promise<ModelDebugResult> {
  const form = new FormData()
  form.append('input', data.input || '')
  form.append('documents', JSON.stringify(data.documents || []))
  form.append('options', JSON.stringify(data.options || {}))
  if (data.file) form.append('file', data.file)
  const response: any = await postUpload(
    `/api/v1/models/${id}/debug`,
    form,
    undefined,
    { timeout: 300000 },
  )
  // 调试结果恒 200 且为裸对象（运行时错误在 result.ok/error 里）
  if (response && typeof response.ok === 'boolean') return response
  throw new Error(response?.error?.message || response?.message || t('error.model.getFailed'))
}

// ----------------------------------------------------------------------------
// Model credential subresource. See mcp-service.ts for the matching MCP API
// shape and the design notes in internal/handler/dto/mcp.go.
// ----------------------------------------------------------------------------

export type ModelCredentialField = 'api_key' | 'app_secret'

export interface ModelCredentialsResponse {
  fields: Record<ModelCredentialField, { configured: boolean }>
}

export async function putModelCredentials(
  id: string,
  body: { apiKey?: string; appSecret?: string },
): Promise<ModelCredentialsResponse> {
  const response: any = await put(`/api/v1/models/${id}/credentials`, body)
  return response as ModelCredentialsResponse
}

export async function deleteModelCredentialField(
  id: string,
  field: ModelCredentialField,
): Promise<void> {
  await del(`/api/v1/models/${id}/credentials/${field}`)
}

export interface InitializeWeKnoraCloudRequest {
  appId: string
  appSecret: string
}

// 仅保存 WeKnoraCloud 凭证，不自动创建模型（成功 = 204 无响应体）
export function saveWeKnoraCloudCredentials(data: InitializeWeKnoraCloudRequest): Promise<void> {
  return post('/api/v1/weknoracloud/credentials', data)
}

export interface WeKnoraCloudStatusResult {
  hasModels: boolean
  needsReinit: boolean
  reason: string | null
}

export function getWeKnoraCloudStatus(): Promise<WeKnoraCloudStatusResult> {
  return new Promise((resolve) => {
    get('/api/v1/models/weknoracloud/status')
      .then((response: any) => {
        if (response && typeof response.hasModels === 'boolean') {
          resolve(response)
        } else {
          resolve({ hasModels: false, needsReinit: false, reason: null })
        }
      })
      .catch(() => {
        resolve({ hasModels: false, needsReinit: false, reason: null })
      })
  })
}
