import { resolveFilePreviewExt, isKnownPreviewableFile } from './filePreview'

export type ChatAttachmentLike = {
  id?: string
  fileName?: string
  fileType?: string
}

export function resolveAttachmentFileType(fileName?: string, fileType?: string): string {
  return resolveFilePreviewExt(fileName, fileType)
}

export function isPreviewableAttachment(attachment: ChatAttachmentLike | null | undefined): boolean {
  if (!attachment?.id) return false
  return isKnownPreviewableFile(attachment.fileName, attachment.fileType)
}
