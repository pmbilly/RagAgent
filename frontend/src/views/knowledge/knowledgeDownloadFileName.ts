export interface KnowledgeDownloadItem {
  id: string;
  original_file_name?: string;
  fileName?: string;
  title?: string;
  type?: string;
}

export function resolveKnowledgeDownloadFileName(item: KnowledgeDownloadItem): string {
  const baseName = item.original_file_name || item.fileName || item.title || item.id;
  if (item.type === 'manual' && !baseName.toLowerCase().endsWith('.md')) {
    return `${baseName}.md`;
  }
  return baseName;
}
