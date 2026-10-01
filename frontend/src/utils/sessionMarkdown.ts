import { KB_WEB_TAG_RE } from './citationMarkdown'

export interface SessionExportAttachment {
  fileName?: string
}

/**
 * 引用元素是检索域的 `SearchResult`：线上是 camelCase；历史 jsonb 行里可能仍是
 * Go 时代的库内键名，故两种拼写都收（camelCase 优先），见 `referenceSources.ts` 的同类说明。
 */
export interface SessionExportReference {
  knowledgeTitle?: string
  knowledgeFilename?: string
  knowledgeSource?: string
  chunkType?: string
  metadata?: Record<string, string>
  knowledge_title?: string
  knowledge_filename?: string
  knowledge_source?: string
}

export interface SessionExportMessage {
  id?: string
  role?: string
  content?: string
  createdAt?: string
  attachments?: SessionExportAttachment[]
  knowledgeReferences?: SessionExportReference[]
}

export interface SessionMarkdownLabels {
  sessionId: string
  exportedAt: string
  user: string
  assistant: string
  attachments: string
  references: string
}

export interface SessionMessagePageFetcher {
  (beforeTime: string, limit: number): Promise<SessionExportMessage[]>
}

const roleOrder = (role?: string): number => {
  if (role === 'user') return 0
  if (role === 'assistant') return 1
  return 2
}

export async function collectAllSessionMessages(
  fetchPage: SessionMessagePageFetcher,
  pageSize = 100,
  maxPages = 500,
): Promise<SessionExportMessage[]> {
  const pages: SessionExportMessage[][] = []
  let beforeTime = ''

  for (let pageIndex = 0; pageIndex < maxPages; pageIndex += 1) {
    const page = await fetchPage(beforeTime, pageSize)
    if (!Array.isArray(page) || page.length === 0) break
    pages.unshift(page)

    const oldestTime = page[0]?.createdAt || ''
    if (page.length < pageSize || !oldestTime || oldestTime === beforeTime) break
    beforeTime = oldestTime
  }

  const seen = new Set<string>()
  return pages
    .flat()
    .filter((message) => {
      const key = message.id
        || `${message.role || ''}:${message.createdAt || ''}:${message.content || ''}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    .sort((a, b) => {
      const timeCompare = String(a.createdAt || '').localeCompare(String(b.createdAt || ''))
      return timeCompare || roleOrder(a.role) - roleOrder(b.role)
    })
}

function cleanMessageContent(content?: string): string {
  return String(content || '')
    .replace(KB_WEB_TAG_RE, '')
    .replace(/[ \t]+\n/g, '\n')
    .trim()
}

function markdownListText(value: string): string {
  return value.replace(/\s+/g, ' ').replace(/([\\`*_[\]<>])/g, '\\$1').trim()
}

function isHttpUrl(value: string): boolean {
  return /^https?:\/\//i.test(value)
}

function referenceLine(reference: SessionExportReference): string {
  const title = reference.knowledgeTitle
    || reference.knowledge_title
    || reference.knowledgeFilename
    || reference.knowledge_filename
    || reference.metadata?.title
    || reference.knowledgeSource
    || reference.knowledge_source
    || ''
  if (!title) return ''

  const source = reference.metadata?.url
    || reference.knowledgeSource
    || reference.knowledge_source
    || ''
  const safeTitle = markdownListText(title)
  return isHttpUrl(source) ? `- [${safeTitle}](${source})` : `- ${safeTitle}`
}

export function buildSessionMarkdown(options: {
  sessionId: string
  title: string
  messages: SessionExportMessage[]
  labels: SessionMarkdownLabels
  exportedAt?: string
}): string {
  const { sessionId, labels } = options
  const title = options.title.replace(/\s+/g, ' ').trim() || sessionId
  const exportedAt = options.exportedAt || new Date().toISOString()
  const blocks = [
    `# ${title.replace(/^#+\s*/, '')}`,
    `> ${labels.sessionId}: ${sessionId}  `,
    `> ${labels.exportedAt}: ${exportedAt}`,
  ]

  for (const message of options.messages) {
    if (message.role !== 'user' && message.role !== 'assistant') continue
    const content = cleanMessageContent(message.content)
    const attachments = (message.attachments || [])
      .map((attachment) => attachment.fileName?.trim() || '')
      .filter(Boolean)
    const references = [...new Set(
      (message.knowledgeReferences || []).map(referenceLine).filter(Boolean),
    )]
    if (!content && attachments.length === 0 && references.length === 0) continue

    blocks.push(`## ${message.role === 'user' ? labels.user : labels.assistant}`)
    if (content) blocks.push(content)
    if (attachments.length > 0) {
      blocks.push(`### ${labels.attachments}`, ...attachments.map((name) => `- ${markdownListText(name)}`))
    }
    if (references.length > 0) {
      blocks.push(`### ${labels.references}`, ...references)
    }
  }

  return `${blocks.join('\n\n')}\n`
}
