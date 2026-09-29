import { reactive, watch } from 'vue'
import { defineStore } from 'pinia'
import { getMessageList } from '@/api/chat/index'
import { useAuthStore } from './auth'
import { createSessionActivityState, type SessionActivity } from './sessionActivityState'

export const useSessionActivityStore = defineStore('sessionActivity', () => {
  const entries = reactive<Record<string, SessionActivity>>({})
  const activity = createSessionActivityState(entries, async sessionId => {
    const result: any = await getMessageList({ session_id: sessionId, limit: 20, created_at: '' })
    // 裸数组：响应体即消息列表
    return Array.isArray(result) ? result : []
  })
  const auth = useAuthStore()
  watch(() => [auth.user?.id, auth.effectiveTenantId], activity.clear, { flush: 'sync' })
  return { entries, ...activity }
})
