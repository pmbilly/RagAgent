import { del, get, getDown, postUpload } from '@/utils/request';

export type TemporaryAttachmentStatus = 'uploaded' | 'processing' | 'ready' | 'failed';

/** 键名＝服务端字段名（§14.9l S3 后为 camelCase）。 */
export interface TemporaryAttachment {
  id: string;
  sessionId: string;
  fileName: string;
  fileType: string;
  fileSize: number;
  mimeType?: string;
  status: TemporaryAttachmentStatus;
  tokenCount: number;
  chunkCount: number;
  imageRefs?: Array<{ originalRef?: string; url: string; mimeType?: string }>;
  errorMessage?: string;
  expiresAt: string;
  startedAt?: string | null;
  readyAt?: string | null;
  updatedAt?: string;
}

export function uploadTemporaryAttachment(
  sessionId: string,
  file: File,
  agentId?: string,
  parserEngine?: string,
  onProgress?: (percent: number) => void,
): Promise<TemporaryAttachment> {
  const form = new FormData();
  form.append('file', file);
  if (agentId) form.append('agentId', agentId);
  if (parserEngine) form.append('parserEngine', parserEngine);
  return postUpload(
    `/api/v1/sessions/${sessionId}/attachments`,
    form,
    (event) => {
      if (event.total) onProgress?.(Math.round((event.loaded * 100) / event.total));
    },
  );
}

export function getTemporaryAttachment(sessionId: string, attachmentId: string): Promise<TemporaryAttachment> {
  return get(`/api/v1/sessions/${sessionId}/attachments/${attachmentId}`);
}

export function previewTemporaryAttachment(sessionId: string, attachmentId: string): Promise<Blob> {
  return getDown(`/api/v1/sessions/${sessionId}/attachments/${attachmentId}/preview`);
}

export function deleteTemporaryAttachment(sessionId: string, attachmentId: string): Promise<void> {
  return del(`/api/v1/sessions/${sessionId}/attachments/${attachmentId}`);
}
