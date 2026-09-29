import { ref, watch, onMounted, computed } from 'vue'
import { knowledgeSemanticSearch } from '@/api/knowledge-base'
import { searchMessages, type MessageSearchGroupItem } from '@/api/chat-history'
import { useChatResourcesStore } from '@/stores/chatResources'
import { useMenuStore } from '@/stores/menu'

export interface CmdkKb {
  id: string
  name: string
  type?: string
}

export interface CmdkAgent {
  id: string
  name: string
  description?: string
  avatar?: string
  isBuiltin: boolean
}

export interface CmdkSessionItem {
  id: string
  title: string
}

export interface CmdkChunk {
  id: string
  chunk_index: number
  knowledge_id: string
  knowledge_base_id: string
  knowledge_title: string
  kb_name: string
  content: string
  matched_content?: string
  match_type: 'vector' | 'keyword' | string
  score: number
}

export interface CmdkFileGroup {
  knowledgeId: string
  kbId: string
  title: string
  kbName: string
  chunks: CmdkChunk[]
}

export interface CmdkMsgGroup {
  sessionId: string
  sessionTitle: string
  items: MessageSearchGroupItem[]
}

/**
 * useCmdkSearch — debounced, cancellable search that fans out to
 *   • /api/v1/knowledge-search (chunks / files)
 *   • /api/v1/messages/search  (chat history)
 * and produces three client-side groups: kbs / files / messages.
 *
 * Cancellation is implemented with a monotonically-increasing request id so
 * that stale responses are dropped (AbortController is not used to keep the
 * backend API untouched).
 *
 * `lockedKbIds` is a getter returning the current scope. When it returns a
 * non-empty array, chunk search is limited to those KBs and message search
 * is skipped. Changes to the getter's return value re-trigger the search.
 */
export function useCmdkSearch(options: {
  /** When set, scopes knowledge search to these KBs. Re-evaluated on each search. */
  lockedKbIds?: () => string[]
  /** How many knowledge chunks to keep after grouping; default no-cap. */
  chunkLimit?: number
  /** Debounce delay in ms. */
  debounceMs?: number
  /** 当前部署是否提供智能体路由；不提供时不发起预加载请求。 */
  agentsEnabled?: () => boolean
}) {
  const debounceMs = options.debounceMs ?? 350
  const query = ref('')
  const loading = ref(false)
  const hasSearched = ref(false)

  // All KBs the current user can see. Loaded lazily & cached.
  const knowledgeBases = ref<CmdkKb[]>([])
  const kbsLoaded = ref(false)
  let kbsLoadingPromise: Promise<void> | null = null

  const fileGroups = ref<CmdkFileGroup[]>([])
  const messageGroups = ref<CmdkMsgGroup[]>([])
  const totalChunks = ref(0)
  const totalMessages = ref(0)

  // Agents the current user can access (own + builtin).
  const agents = ref<CmdkAgent[]>([])
  const agentsLoaded = ref(false)
  let agentsLoadingPromise: Promise<void> | null = null

  const menuStore = useMenuStore()

  const ensureKbs = async (): Promise<void> => {
    if (kbsLoaded.value) return
    if (kbsLoadingPromise) return kbsLoadingPromise
    kbsLoadingPromise = (async () => {
      try {
        const chatResources = useChatResourcesStore()
        await chatResources.ensureKnowledgeBases()
        knowledgeBases.value = chatResources.rawKnowledgeBases.map((kb: any) => ({
          id: String(kb.id),
          name: kb.name || '',
          type: kb.type,
        }))
        kbsLoaded.value = true
      } catch (e) {
        console.error('[cmdk] failed to load knowledge bases', e)
      } finally {
        kbsLoadingPromise = null
      }
    })()
    return kbsLoadingPromise
  }

  // Derived: KB name lookup
  const getKbName = (kbId: string): string => {
    if (!kbId) return ''
    const kb = knowledgeBases.value.find(k => k.id === kbId)
    return kb?.name || ''
  }

  // KB name hits (client-side, works even before a remote search returns).
  const kbMatches = computed<CmdkKb[]>(() => {
    const q = query.value.trim().toLowerCase()
    if (!q) return []
    return knowledgeBases.value
      .filter(k => k.name.toLowerCase().includes(q))
      .slice(0, 4)
  })

  // Agents (own + builtin). Lazily loaded & cached; no backend search endpoint
  // exists so we always filter client-side.
  const ensureAgents = async (): Promise<void> => {
    if (options.agentsEnabled?.() === false) return
    if (agentsLoaded.value) return
    if (agentsLoadingPromise) return agentsLoadingPromise
    agentsLoadingPromise = (async () => {
      try {
        const chatResources = useChatResourcesStore()
        await chatResources.ensureAgents()
        agents.value = chatResources.agents.map((a: any) => ({
          id: String(a.id),
          name: a.name || '',
          description: a.description,
          avatar: a.avatar,
          isBuiltin: !!a.is_builtin,
        }))
        agentsLoaded.value = true
      } catch (e) {
        console.error('[cmdk] failed to load agents', e)
      } finally {
        agentsLoadingPromise = null
      }
    })()
    return agentsLoadingPromise
  }

  const agentMatches = computed<CmdkAgent[]>(() => {
    if (options.agentsEnabled?.() === false) return []
    const q = query.value.trim().toLowerCase()
    if (!q) return []
    return agents.value
      .filter(a => {
        const name = (a.name || '').toLowerCase()
        const desc = (a.description || '').toLowerCase()
        return name.includes(q) || desc.includes(q)
      })
      .slice(0, 5)
  })

  // Sessions: no dedicated list API; we re-use whatever menuStore already has
  // cached (the sidebar already paginates them). Good enough for "jump to a
  // session I've seen recently" — not a substitute for message-content search.
  const sessionList = computed<CmdkSessionItem[]>(() => {
    const chatMenu = (menuStore.menuArr as any[]).find((m: any) => m.path === 'creatChat')
    const children = (chatMenu?.children as any[]) || []
    return children.map((c: any) => ({
      id: String(c.id || ''),
      title: c.title || '',
    }))
  })

  const sessionMatches = computed<CmdkSessionItem[]>(() => {
    const q = query.value.trim().toLowerCase()
    if (!q) return []
    // If a session already appears in the message-content group, suppress it
    // here — showing the same session title twice (once with a message hit,
    // once by title match) is noise. The message-group entry is strictly
    // more informative (it shows which message matched), so it wins.
    const coveredBySession = new Set(messageGroups.value.map(g => g.sessionId))
    return sessionList.value
      .filter(s => s.title && s.title.toLowerCase().includes(q))
      .filter(s => !coveredBySession.has(s.id))
      .slice(0, 5)
  })

  // Monotonic id to drop stale responses.
  let requestSeq = 0
  let debounceTimer: ReturnType<typeof setTimeout> | null = null

  const clearResults = () => {
    fileGroups.value = []
    messageGroups.value = []
    totalChunks.value = 0
    totalMessages.value = 0
    hasSearched.value = false
  }

  const runSearch = async (q: string, seq: number) => {
    loading.value = true
    hasSearched.value = true

    // Determine knowledgeBaseIds for chunk search. A non-empty lockedKbIds
    // narrows the scope and also disables message search (out of scope).
    const locked = options.lockedKbIds?.() || []
    const scoped = locked.length > 0
    let kbIds: string[]
    if (scoped) {
      kbIds = locked
    } else {
      await ensureKbs()
      kbIds = knowledgeBases.value.map(k => k.id)
    }

    const knowledgePromise = kbIds.length > 0
      ? knowledgeSemanticSearch({ query: q, knowledgeBaseIds: kbIds })
          .then((res: any) => (res?.success && res.data ? res.data : []))
          .catch((e) => {
            console.error('[cmdk] knowledge search failed', e)
            return []
          })
      : Promise.resolve([])

    const messagesPromise = scoped
      ? Promise.resolve({ items: [], total: 0 })
      : searchMessages({ query: q, mode: 'hybrid', limit: 30 })
          .then((res: any) => (res?.success && res.data ? res.data : { items: [], total: 0 }))
          .catch((e) => {
            console.error('[cmdk] message search failed', e)
            return { items: [], total: 0 }
          })

    const [chunks, msgRes] = await Promise.all([knowledgePromise, messagesPromise])

    // Stale response guard.
    if (seq !== requestSeq) return

    // Group chunks by knowledge_id.
    const fmap = new Map<string, CmdkFileGroup>()
    for (const item of chunks as any[]) {
      const kid = item.knowledgeId || 'unknown'
      if (!fmap.has(kid)) {
        fmap.set(kid, {
          knowledgeId: kid,
          kbId: item.knowledgeBaseId || '',
          title: item.knowledgeTitle || item.knowledgeFilename || kid,
          kbName: getKbName(item.knowledgeBaseId),
          chunks: [],
        })
      }
      fmap.get(kid)!.chunks.push({
        id: item.id,
        chunk_index: item.chunkIndex,
        knowledge_id: kid,
        knowledge_base_id: item.knowledgeBaseId || '',
        knowledge_title: item.knowledgeTitle || '',
        kb_name: getKbName(item.knowledgeBaseId),
        content: item.content || '',
        matched_content: item.matchedContent,
        match_type: item.matchType ?? 0,
        score: item.score || 0,
      })
    }
    fileGroups.value = Array.from(fmap.values())
    totalChunks.value = (chunks as any[]).length

    // Group messages by session_id.
    const mmap = new Map<string, CmdkMsgGroup>()
    const items: MessageSearchGroupItem[] = (msgRes as any).items || []
    for (const m of items) {
      const sid = m.session_id || 'unknown'
      if (!mmap.has(sid)) {
        mmap.set(sid, {
          sessionId: sid,
          sessionTitle: m.session_title || '',
          items: [],
        })
      }
      mmap.get(sid)!.items.push(m)
    }
    messageGroups.value = Array.from(mmap.values())
    totalMessages.value = (msgRes as any).total || items.length

    loading.value = false
  }

  // Watch query changes (debounced). lockedKbIds is tracked as a derived
  // string so the search re-runs when the KB scope toggles.
  const lockedSignature = () =>
    (options.lockedKbIds?.() || []).slice().sort().join(',')

  watch([query, lockedSignature], ([val]) => {
    if (debounceTimer) clearTimeout(debounceTimer)
    const trimmed = (val as string).trim()
    if (!trimmed) {
      requestSeq++ // cancel in-flight
      loading.value = false
      clearResults()
      return
    }
    debounceTimer = setTimeout(() => {
      const seq = ++requestSeq
      runSearch(trimmed, seq)
    }, debounceMs)
  })

  onMounted(() => {
    // Preload KB and agent lists so name matches show up instantly on first
    // keystroke. Sessions come from the menuStore (already populated by the
    // sidebar) so no extra fetch needed here.
    ensureKbs()
    if (options.agentsEnabled?.() !== false) {
      ensureAgents()
    }
  })

  return {
    query,
    loading,
    hasSearched,
    knowledgeBases,
    fileGroups,
    messageGroups,
    kbMatches,
    agents,
    agentMatches,
    sessionMatches,
    totalChunks,
    totalMessages,
    clearResults,
    ensureKbs,
    ensureAgents,
  }
}
