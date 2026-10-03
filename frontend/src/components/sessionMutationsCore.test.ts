import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createSessionMutations,
  type SessionMutationApi,
  type SessionMutationDetail,
} from './sessionMutationsCore.ts'

/** 默认桩：删除/清空按契约回 204（HTTP 层解出空字符串），改名/置顶回 200 裸对象。 */
function wiring(overrides: Partial<SessionMutationApi> = {}) {
  const calls: string[] = []
  const api: SessionMutationApi = {
    updateSession: async () => {
      calls.push('updateSession')
      return { title: '服务器标题' }
    },
    pinSession: async () => {
      calls.push('pinSession')
      return { pinned: true }
    },
    unpinSession: async () => {
      calls.push('unpinSession')
      return { pinned: false }
    },
    clearSessionMessages: async () => {
      calls.push('clearSessionMessages')
      return ''
    },
    delSession: async () => {
      calls.push('delSession')
      return ''
    },
    ...overrides,
  }
  const details: SessionMutationDetail[] = []
  const mutations = createSessionMutations(api, (d) => details.push(d))
  return { mutations, details, calls }
}

test('removeSession：204 空响应体算成功，并发出 removed 事件', async () => {
  const { mutations, details, calls } = wiring()

  await assert.doesNotReject(() => mutations.removeSession('s1'))

  assert.deepEqual(calls, ['delSession'])
  assert.deepEqual(details, [{ sessionId: 's1', removed: true }])
})

test('clearSession：204 空响应体算成功，并发出 messagesCleared 事件', async () => {
  const { mutations, details } = wiring()

  await assert.doesNotReject(() => mutations.clearSession('s1'))

  assert.deepEqual(details, [{ sessionId: 's1', messagesCleared: true }])
})

test('removeSession：HTTP 层失败（非 2xx）仍向上抛，且不发事件', async () => {
  const { mutations, details } = wiring({
    delSession: async () => {
      throw new Error('404 not found')
    },
  })

  await assert.rejects(() => mutations.removeSession('s1'), /404 not found/)
  assert.deepEqual(details, [])
})

test('renameSession：200 裸对象取 title 并发出 patch', async () => {
  const { mutations, details } = wiring()

  const response = await mutations.renameSession('s1', '本地标题', '描述')

  assert.equal(response.title, '服务器标题')
  assert.deepEqual(details, [{ sessionId: 's1', patch: { title: '服务器标题' } }])
})

test('renameSession：拿不到对象视为失败（对象校验保留，不回退成静默成功）', async () => {
  const { mutations, details } = wiring({ updateSession: async () => '' })

  await assert.rejects(() => mutations.renameSession('s1', '标题'), /session mutation failed/)
  assert.deepEqual(details, [])
})

test('setSessionPinned：置顶/取消置顶各自发出 pinned 与 pinnedAt', async () => {
  const { mutations, details, calls } = wiring()

  await mutations.setSessionPinned('s1', true)
  await mutations.setSessionPinned('s1', false)

  assert.deepEqual(calls, ['pinSession', 'unpinSession'])
  assert.equal(details[0].patch?.pinned, true)
  assert.equal(typeof details[0].patch?.pinnedAt, 'string')
  assert.deepEqual(details[1].patch, { pinned: false, pinnedAt: null })
})
