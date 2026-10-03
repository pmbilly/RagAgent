/**
 * 会话写操作的纯逻辑核心（不依赖 `@/` 与浏览器环境，便于直接用 `tsx --test` 单测）。
 *
 * <p>两类端点的返回值口径不同（§1.13 契约）：
 * <ul>
 *   <li><b>改名 / 置顶</b> → 200 + 裸对象：拿不到对象即视为失败（校验保留）；</li>
 *   <li><b>删除会话 / 清空消息</b> → <b>204 无响应体</b>：成功与否由 HTTP 状态保证
 *       （非 2xx 已在 HTTP 层 reject），返回值一律不检查。</li>
 * </ul>
 *
 * <p>历史缺陷（2026-10-03 点检实锤）：删除会话曾套用"对象校验"，204 空体被抛错
 * ⇒ 服务端已删除、界面却提示"删除失败"且不从列表移除，用户重试再撞 404。
 * 回归测试见 {@code sessionMutationsCore.test.ts}。</p>
 */

export interface SessionMutationPatch {
  title?: string
  pinned?: boolean
  pinnedAt?: string | null
}

export interface SessionMutationDetail {
  sessionId: string
  patch?: SessionMutationPatch
  messagesCleared?: boolean
  removed?: boolean
}

export interface SessionRenamePayload {
  title: string
  description: string
}

/** 写端点最小面（真实接线见 `sessionMutations.ts`；测试传桩实现）。 */
export interface SessionMutationApi {
  updateSession(sessionId: string, data: SessionRenamePayload): Promise<unknown>
  pinSession(sessionId: string): Promise<unknown>
  unpinSession(sessionId: string): Promise<unknown>
  clearSessionMessages(sessionId: string): Promise<unknown>
  delSession(sessionId: string): Promise<unknown>
}

export type SessionMutationEmitter = (detail: SessionMutationDetail) => void

/** 200 + 裸对象类端点：拿不到对象即失败（无信封契约）。 */
function requireObject(response: unknown): any {
  if (!response || typeof response !== 'object') {
    throw new Error((response as any)?.message || 'session mutation failed')
  }
  return response
}

export function createSessionMutations(api: SessionMutationApi, emit: SessionMutationEmitter) {
  async function renameSession(
    sessionId: string,
    title: string,
    description = '',
  ): Promise<any> {
    const response = requireObject(await api.updateSession(sessionId, { title, description }))
    const nextTitle = response.title || title
    emit({ sessionId, patch: { title: nextTitle } })
    return response
  }

  async function setSessionPinned(sessionId: string, pinned: boolean): Promise<void> {
    requireObject(pinned ? await api.pinSession(sessionId) : await api.unpinSession(sessionId))
    emit({
      sessionId,
      patch: {
        pinned: pinned,
        pinnedAt: pinned ? new Date().toISOString() : null,
      },
    })
  }

  async function clearSession(sessionId: string): Promise<void> {
    // 204 无体：非 2xx 已由 HTTP 层 reject，不检查返回值（见文件头注释）。
    await api.clearSessionMessages(sessionId)
    emit({ sessionId, messagesCleared: true })
  }

  async function removeSession(sessionId: string): Promise<void> {
    // 204 无体：同上。
    await api.delSession(sessionId)
    emit({ sessionId, removed: true })
  }

  return { renameSession, setSessionPinned, clearSession, removeSession }
}
