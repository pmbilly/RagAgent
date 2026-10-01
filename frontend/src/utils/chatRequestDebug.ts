/** Sanitize stream POST body for display (strip large base64 payloads). */
export function sanitizeStreamRequestBody(body: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = { ...body };
  if (Array.isArray(out.images)) {
    out.images = out.images.map((img: { data?: string }, i: number) => ({
      _placeholder: `image[${i}]`,
      bytes: typeof img?.data === 'string' ? img.data.length : 0,
    }));
  }
  // 键名＝QA 请求体（§14.9l S4 后为 camelCase）：改错了这里，调试面板会把整段 base64 原样打印
  if (Array.isArray(out.attachmentUploads)) {
    out.attachmentUploads = out.attachmentUploads.map(
      (att: { fileName?: string; fileSize?: number; data?: string }, i: number) => ({
        fileName: att.fileName,
        fileSize: att.fileSize,
        _placeholder: `attachment[${i}]`,
        bytes: typeof att?.data === 'string' ? att.data.length : 0,
      }),
    );
  }
  if (typeof out.query === 'string' && out.query.length > 500) {
    out.query = `${out.query.slice(0, 500)}… (${out.query.length} chars)`;
  }
  return out;
}

export interface StreamRequestMeta {
  requestId: string;
  url: string;
  method: string;
  body: Record<string, unknown> | null;
  sentAt: number;
}

export interface ChatRequestDebugInfo {
  requestId?: string;
  messageId?: string;
  sessionId?: string;
  url?: string;
  method?: string;
  body?: Record<string, unknown> | null;
  sentAt?: number;
}

export function buildChatRequestDebugPayload(info: ChatRequestDebugInfo): string {
  return JSON.stringify(info, null, 2);
}
