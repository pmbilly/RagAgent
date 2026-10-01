import { fetchEventSource } from '@microsoft/fetch-event-source';
import { ref, onUnmounted } from 'vue';
import { generateRandomString } from '@/utils/index';
import type { MentionedItem } from '@/types/mention';
import i18n from '@/i18n';
import { getApiBaseUrl } from '@/utils/api-base';
import {
  sanitizeStreamRequestBody,
  type StreamRequestMeta,
} from '@/utils/chatRequestDebug';
import {
  StreamAuthError,
  isStreamAuthError,
  refreshAccessTokenShared,
  runStreamWithAuthRetry,
} from '@/utils/authRefresh';

interface StreamOptions {
  // 请求方法 (默认POST)
  method?: 'GET' | 'POST'
  // 请求头
  headers?: Record<string, string>
  // 请求体自动序列化
  body?: Record<string, any>
  // 流式渲染间隔 (ms)
  chunkInterval?: number
}

export function useStream() {
  // 响应式状态
  const output = ref('')              // 显示内容
  const isStreaming = ref(false)      // 流状态
  const isLoading = ref(false)        // 初始加载
  const error = ref<string | null>(null)// 错误信息
  const lastStreamRequest = ref<StreamRequestMeta | null>(null)
  let controller = new AbortController()
  let streamGeneration = 0

  // 流式渲染缓冲
  let buffer: string[] = []
  let renderTimer: number | null = null

  // 启动流式请求
  // QA 请求体键＝服务端字段名（§14.9l S4）：全部 camelCase，含提及项元素与
  // SuggestionAttribution 的内部键（S3 已收口）。session_id/url 是前端路由参数，不属请求体。
  const startStream = async (params: { session_id: any; query: any; knowledgeBaseIds?: string[]; knowledgeIds?: string[]; tagIds?: string[]; agentEnabled?: boolean; agentId?: string; agentSourceTenantId?: string | number; webSearchEnabled?: boolean; summaryModelId?: string; mcpServiceIds?: string[]; skillNames?: string[]; mentionedItems?: MentionedItem[]; images?: Array<{data: string}>; attachmentUploads?: Array<{data: string; fileName: string; fileSize: number}>; attachmentIds?: string[]; suggestionAttribution?: { suggestionSetId: string; questionId: string }; method: string; url: string; embed_token?: string; embed_session_sig?: string; embed_visitor_id?: string }) => {
    const myGeneration = ++streamGeneration
    const streamAbort = controller
    // 重置状态
    output.value = '';
    error.value = null;
    isStreaming.value = true;
    isLoading.value = true;

    // 获取API配置
    const apiUrl = getApiBaseUrl();
    
    const embedToken = params.embed_token;
    const token = embedToken || localStorage.getItem('weknora_token');
    if (!token) {
      error.value = i18n.global.t('error.tokenNotFound');
      stopStream();
      return;
    }

    // 跨空间访问请求头：只要 setSelectedTenant 写过激活空间，就附
    // X-Tenant-ID。早期版本会 short-circuit "selectedTenantId ===
    // defaultTenantId 时不附" 来减少 header 体积，但任何把 weknora_tenant
    // 写成激活空间的代码（OIDC 同步 / UserMenu loadUserInfo / router
    // hydrate）都会让两者相等，使得后续流式请求悄悄丢 header、落到
    // home 空间上，导致 SSE 接口返回 404。直接附即可——后端
    // IsTenantAccessible 也允许 header 指向自家空间。
    const selectedTenantId = localStorage.getItem('weknora_selected_tenant_id');
    const tenantIdHeader: string | null = selectedTenantId || null;

    // TTFB instrumentation: record the moment we kick off the request so
    // we can compare it with the first answer chunk we receive from the
    // server. This makes it possible to correlate the frontend-observed
    // latency with the backend "TTFB:first_answer_chunk" log line by
    // matching on X-Request-ID.
    const sentAt = performance.now();
    const requestID = generateRandomString(12);
    let firstAnswerLogged = false;

    try {
      let url =
        params.method == "POST"
          ? `${apiUrl}${params.url}/${params.session_id}`
          : `${apiUrl}${params.url}/${params.session_id}?message_id=${params.query}`;
      console.log(`[TTFB] request:start request_id=${requestID} url=${url} sent_at=${Date.now()}`);
      
      // Prepare POST body with required fields for agent-chat
      // knowledgeBaseIds array and agentEnabled can update Session's SessionAgentConfig
      const postBody: any = { 
        query: params.query,
        agentEnabled: params.agentEnabled !== undefined ? params.agentEnabled : true
      };
      // Always include knowledgeBaseIds for agent-chat (already validated above)
      if (params.knowledgeBaseIds !== undefined && params.knowledgeBaseIds.length > 0) {
        postBody.knowledgeBaseIds = params.knowledgeBaseIds;
      }
      // Include knowledgeIds if provided
      if (params.knowledgeIds !== undefined && params.knowledgeIds.length > 0) {
        postBody.knowledgeIds = params.knowledgeIds;
      }
      // Include agentId if provided (backend resolves shared agent and tenant from share relation)
      if (params.agentId) {
        postBody.agentId = params.agentId;
      }
      if (params.agentSourceTenantId) {
        postBody.agentSourceTenantId = Number(params.agentSourceTenantId);
      }
      // Include webSearchEnabled if provided
      if (params.webSearchEnabled !== undefined) {
        postBody.webSearchEnabled = params.webSearchEnabled;
      }
      // Include summaryModelId if provided (for non-Agent mode)
      if (params.summaryModelId) {
        postBody.summaryModelId = params.summaryModelId;
      }
      // Include mcpServiceIds if provided (for Agent mode)
      if (params.mcpServiceIds !== undefined && params.mcpServiceIds.length > 0) {
        postBody.mcpServiceIds = params.mcpServiceIds;
      }
      if (params.skillNames !== undefined && params.skillNames.length > 0) {
        postBody.skillNames = params.skillNames;
      }
      if (params.tagIds !== undefined && params.tagIds.length > 0) {
        postBody.tagIds = params.tagIds;
      }
      // Include mentionedItems if provided (for displaying @mentions in chat)
      if (params.mentionedItems !== undefined && params.mentionedItems.length > 0) {
        postBody.mentionedItems = params.mentionedItems;
      }
      // Include images if provided (base64 data URIs for multimodal chat)
      if (params.images !== undefined && params.images.length > 0) {
        postBody.images = params.images;
      }
      // Include attachmentUploads if provided (documents, audio, etc.)
      if (params.attachmentUploads !== undefined && params.attachmentUploads.length > 0) {
        postBody.attachmentUploads = params.attachmentUploads;
      }
	  if (params.attachmentIds !== undefined && params.attachmentIds.length > 0) {
		postBody.attachmentIds = params.attachmentIds;
	  }
      if (params.suggestionAttribution) {
        postBody.suggestionAttribution = params.suggestionAttribution;
      }
      postBody.channel = embedToken ? "embed" : "web";

      lastStreamRequest.value = {
        requestId: requestID,
        url,
        method: params.method,
        body: params.method === 'POST' ? sanitizeStreamRequestBody(postBody) : null,
        sentAt: Date.now(),
      };
      
      // Wrapped so an expired access token can be refreshed and the request
      // replayed once. Nothing has been streamed to the UI yet when the
      // handshake 401s, so the replay is invisible to the user.
      const runStream = (authToken: string) => fetchEventSource(url, {
        method: params.method,
        headers: {
          "Content-Type": "application/json",
          "Authorization": embedToken ? `Embed ${embedToken}` : `Bearer ${authToken}`,
          "Accept-Language": i18n.global.locale?.value || localStorage.getItem('locale') || 'zh-CN',
          "X-Request-ID": requestID,
          ...(!embedToken && tenantIdHeader ? { "X-Tenant-ID": tenantIdHeader } : {}),
          ...(params.embed_session_sig ? { "X-Embed-Session": params.embed_session_sig } : {}),
          ...(params.embed_visitor_id ? { "X-Embed-Visitor": params.embed_visitor_id } : {}),
        },
        body:
          params.method == "POST"
            ? JSON.stringify(postBody)
            : null,
        signal: streamAbort.signal,
        openWhenHidden: true,

        onopen: async (res) => {
          // 401 is recoverable (refresh + replay); everything else is not.
          if (res.status === 401) throw new StreamAuthError(res.status);
          if (!res.ok) throw new Error(`HTTP ${res.status}`);
          console.log(`[TTFB] response:headers request_id=${requestID} elapsed_ms=${(performance.now() - sentAt).toFixed(1)}`);
          isLoading.value = false;
        },

        onmessage: (ev) => {
          if (myGeneration !== streamGeneration) return
          const parsed = JSON.parse(ev.data);
          // Log first answer chunk for end-to-end TTFB measurement.
          // Filter by event type so non-answer events (references, tool
          // calls, etc.) don't count as the "first token" arrival.
          if (!firstAnswerLogged && (parsed?.response_type === 'answer' || parsed?.type === 'answer')) {
            firstAnswerLogged = true;
            console.log(`[TTFB] response:first_answer request_id=${requestID} elapsed_ms=${(performance.now() - sentAt).toFixed(1)}`);
          }
          buffer.push(parsed); // 数据存入缓冲
          // 执行自定义处理
          if (chunkHandler) {
            chunkHandler(parsed);
          }
        },

        onerror: (err) => {
          if (isStreamAuthError(err)) throw err;
          throw new Error(`${i18n.global.t('error.streamFailed')}: ${err}`);
        },

        onclose: () => {
          stopStream();
        },
      });

      await runStreamWithAuthRetry({
        run: runStream,
        initialToken: token,
        isEmbed: Boolean(embedToken),
        isCurrent: () => myGeneration === streamGeneration && !streamAbort.signal.aborted,
        refreshAccessToken: () => refreshAccessTokenShared({
          messages: {
            pleaseRelogin: i18n.global.t('error.pleaseRelogin'),
            tokenRefreshFailed: i18n.global.t('error.tokenRefreshFailed'),
          },
        }),
        reloginMessage: i18n.global.t('error.pleaseRelogin'),
      });
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      stopStream()
    }
  }

  let chunkHandler: ((data: any) => void) | null = null
  // 注册块处理器
  const onChunk = (handler: (data: any) => void) => {
    chunkHandler = handler
  }


  // 停止流
  const stopStream = () => {
    streamGeneration++
    controller.abort();
    controller = new AbortController(); // 重置控制器（如需重新发起）
    isStreaming.value = false;
    isLoading.value = false;
  }

  // 组件卸载时自动清理
  onUnmounted(stopStream)

  return {
    output,          // 显示内容
    isStreaming,     // 是否在流式传输中
    isLoading,       // 初始连接状态
    error,
    lastStreamRequest,
    onChunk,
    startStream,     // 启动流
    stopStream       // 手动停止
  }
}
