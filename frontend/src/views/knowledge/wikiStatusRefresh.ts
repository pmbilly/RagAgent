export type KnowledgePollStatus = {
  parseStatus?: string
  summaryStatus?: string
}

export function isKnowledgeParseInFlight(status?: string): boolean {
  return status === 'pending' || status === 'processing' || status === 'finalizing'
}

export function knowledgeNeedsStatusPolling(item: KnowledgePollStatus): boolean {
  if (isKnowledgeParseInFlight(item.parseStatus)) return true
  return item.parseStatus === 'completed' &&
    (item.summaryStatus === 'pending' || item.summaryStatus === 'processing')
}

export function shouldRefreshWikiStatusAfterKnowledgePoll(
  before: KnowledgePollStatus,
  after: KnowledgePollStatus,
): boolean {
  return knowledgeNeedsStatusPolling(before) && !knowledgeNeedsStatusPolling(after)
}
