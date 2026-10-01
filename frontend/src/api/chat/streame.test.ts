import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer, type ViteDevServer } from 'vite'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'

let server: ViteDevServer
let useStream: typeof import('./streame').useStream
let transport: { requests: Array<{ url: string; options: { body: string } }> }
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
before(async () => {
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: (key: string) => key === 'weknora_token' ? 'test-token' : null,
  } })
  const mocks: Record<string, string> = {
    '@microsoft/fetch-event-source': `export const requests = []; export async function fetchEventSource(url, options) { requests.push({url, options}); }`,
    '@/utils/index': `export const generateRandomString = () => 'test-request';`,
    '@/i18n': `export default { global: { t: key => key, locale: { value: 'zh-CN' } } };`,
  }
  server = await createServer({
    configFile: false,
    plugins: [{ name: 'stream-request-test', enforce: 'pre',
      resolveId(id) { if (id.startsWith('\0mock:')) return id },
      load(id) { if (id.startsWith('\0mock:')) return mocks[id.slice(6)] },
    }],
    optimizeDeps: { noDiscovery: true, entries: [] },
    resolve: { alias: [
      ...Object.keys(mocks).map(find => ({ find, replacement: '\0mock:' + find })),
      { find: '@', replacement: fileURLToPath(new URL('../../', import.meta.url)) },
    ] },
    server: { middlewareMode: true, hmr: false }, appType: 'custom',
  })
  ;({ useStream } = await server.ssrLoadModule('/src/api/chat/streame.ts'))
  transport = await server.ssrLoadModule('\0mock:@microsoft/fetch-event-source') as typeof transport
})
after(async () => {
  await server?.close()
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
  else Reflect.deleteProperty(globalThis, 'localStorage')
})

test('SSE HTTP body preserves web search selection alongside other tools', async () => {
  let stream!: ReturnType<typeof useStream>
  await renderToString(createSSRApp({ setup() { stream = useStream(); return () => null } }))
  try {
    await stream.startStream({
      session_id: 'new-session', query: '查一下腾讯股价', method: 'POST', url: '/api/v1/agent-chat',
      agentEnabled: true, webSearchEnabled: true,
      mcpServiceIds: ['mcp-1'], skillNames: ['report'],
    })
    assert.equal(stream.error.value, null)
    const request = transport.requests.at(-1)!
    assert.equal(request.url, '/api/v1/agent-chat/new-session')
    const body = JSON.parse(request.options.body)
    assert.equal(Object.hasOwn(body, 'local_browser_enabled'), false)
    // 请求体键＝服务端字段名（§14.9l S4），旧 snake 键不再出现
    assert.equal(body.webSearchEnabled, true)
    assert.equal(Object.hasOwn(body, 'web_search_enabled'), false)
    assert.deepEqual(body.mcpServiceIds, ['mcp-1'])
    assert.deepEqual(body.skillNames, ['report'])
    assert.equal(stream.lastStreamRequest.value?.body?.webSearchEnabled, true)
  } finally { stream.stopStream() }
})
