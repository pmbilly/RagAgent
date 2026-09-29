interface KnowledgeDeletionRow {
  id: string;
  parseStatus?: string;
}

/** 批量取文档的响应体：新契约下是**裸数组**（无 `{success,data}` 信封），
 *  且空结果可能是 `[]` 或 `null`（两种写法都表示"已无这些文档"）。 */
type KnowledgeDeletionRows = KnowledgeDeletionRow[] | null | undefined;

type DeletionResult = 'completed' | 'pending' | 'failed' | 'cancelled';

/** Query exact IDs, including deleting rows, instead of the filtered document list. */
export async function waitForKnowledgeDeletion(
  ids: string[],
  fetchRows: (ids: string[]) => Promise<KnowledgeDeletionRows>,
  options: {
    isActive?: () => boolean;
    attempts?: number;
    delay?: () => Promise<void>;
  } = {},
): Promise<DeletionResult> {
  const isActive = options.isActive ?? (() => true);
  const delay = options.delay ?? (() => new Promise<void>(resolve => setTimeout(resolve, 1000)));
  const requested = new Set(ids);
  const deleting = new Set<string>();
  const attempts = options.attempts ?? 30;
  for (let i = 0; i < attempts; i++) {
    if (!isActive()) return 'cancelled';
    const rows: KnowledgeDeletionRow[] = [];
    // A supported 200-document delete exceeds common proxy request-line
    // limits if all UUIDs are put into a single GET query string.
    for (let start = 0; start < ids.length; start += 50) {
      if (!isActive()) return 'cancelled';
      const response = await fetchRows(ids.slice(start, start + 50));
      if (!isActive()) return 'cancelled';
      // 载荷不合法时不能当作"已删除"的证据：空集可能是 [] 或 null，
      // 其余非数组形态（信封残留、字段改名后的旧形状）一律视为不可信。
      if (response != null && !Array.isArray(response)) {
        throw new Error('Invalid knowledge deletion status response');
      }
      rows.push(...(response ?? []));
    }
    const remaining = rows.filter(row => requested.has(row.id));
    if (remaining.length === 0) return 'completed';
    for (const row of remaining) {
      if (row.parseStatus === 'deleting') deleting.add(row.id);
      // A document may already have a failed parse/delete before this request
      // starts. Only a failure after observing deletion belongs to this run.
      if (deleting.has(row.id) && row.parseStatus === 'failed') return 'failed';
    }
    if (i + 1 < attempts) await delay();
  }
  return isActive() ? 'pending' : 'cancelled';
}
