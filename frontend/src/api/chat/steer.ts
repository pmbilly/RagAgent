import { del, get, post } from "../../utils/request";
import type { MentionedItem } from "../../types/mention";

export type SteerDelivery = 'inject' | 'after'

/** 队列/overlay 项：键名与 steer 端点的响应、请求体一致（camelCase）。 */
export type SteerQueueItem = {
  steerId: string
  content: string
  delivery: SteerDelivery
  mentionedItems?: MentionedItem[]
  promoting?: boolean
  awaitingIdleSend?: boolean
  // True while POST /steer is in flight. The stable client UUID is also the
  // durable server ID; disable actions until its queue entry exists.
  pending?: boolean
  failed?: boolean
  expectedAssistantMessageId?: string
  clientId?: string
}

/** 裸资源响应（§2.1，无 {data,success} 信封）。 */
export type SteerMutationResponse = {
  status: 'queued' | 'already_injected' | 'new_run' | 'gone' | 'deleted'
  steerId?: string
  delivery?: SteerDelivery
  assistantMessageId?: string
  removed?: boolean
}

/**
 * Append a message to a running agent turn.
 * - delivery 'after' (default): waits until the current run exits, then starts a follow-up turn.
 * - delivery 'inject': the running engine injects it at the next round boundary.
 * - 'new_run': no run is live; the caller should fall back to a normal send.
 * Lookup failures (503) reject rather than returning new_run — treating them
 * as idle would start a second turn on top of the one still generating.
 */
export async function steerSession(
  session_id: string,
  query: string,
  mentionedItems: MentionedItem[] = [],
  delivery: SteerDelivery = 'after',
  expectedAssistantMessageId?: string,
  steerId?: string,
): Promise<SteerMutationResponse> {
  return post(`/api/v1/sessions/${session_id}/steer`, {
    query,
    expectedAssistantMessageId,
    steerId,
    mentionedItems,
    channel: 'web',
    delivery,
  });
}

/** Flip a queued after-message to inject so the running turn reads it next. */
export async function promoteSteerSession(session_id: string, steer_id: string) {
  return post<SteerMutationResponse>(`/api/v1/sessions/${session_id}/steer/${steer_id}/inject`, {});
}

/** Pending overlay items for the live run. Empty when nothing is generating. */
export async function listSteerSession(session_id: string) {
  return get<{ assistantMessageId?: string; items: SteerQueueItem[] }>(
    `/api/v1/sessions/${session_id}/steer`,
  );
}

/** Drop a queued overlay item so it is neither injected nor sent as a follow-up. */
export async function removeSteerSession(session_id: string, steer_id: string) {
  return del<SteerMutationResponse>(`/api/v1/sessions/${session_id}/steer/${steer_id}`);
}
