import {
  clearSessionMessages,
  delSession,
  pinSession,
  unpinSession,
  updateSession,
} from '@/api/chat'

import {
  createSessionMutations,
  type SessionMutationApi,
  type SessionMutationDetail,
} from './sessionMutationsCore'

export const SESSION_MUTATION_EVENT = 'weknora:session-mutation'

export type {
  SessionMutationPatch,
  SessionMutationDetail,
} from './sessionMutationsCore'

export function notifySessionMutation(detail: SessionMutationDetail): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent<SessionMutationDetail>(SESSION_MUTATION_EVENT, { detail }))
}

/** 真实写端点接线；返回值口径（200 裸对象 vs 204 无体）见 sessionMutationsCore。 */
const api: SessionMutationApi = {
  updateSession,
  pinSession,
  unpinSession,
  clearSessionMessages,
  delSession,
}

const mutations = createSessionMutations(api, notifySessionMutation)

export const renameSession = mutations.renameSession
export const setSessionPinned = mutations.setSessionPinned
export const clearSession = mutations.clearSession
export const removeSession = mutations.removeSession
