import assert from 'node:assert/strict';
import test from 'node:test';
import { waitForKnowledgeDeletion } from './knowledgeDeletion.ts';

const noDelay = async () => {};
const row = (id: string, parseStatus: string) => ({ id, parseStatus });

test('waits through queued and hidden deleting rows until every requested ID is gone', async () => {
  const snapshots = [
    [row('a', 'completed'), row('b', 'completed')],
    [row('a', 'deleting'), row('b', 'deleting')],
    [row('b', 'deleting')],
    [],
  ];
  let calls = 0;
  const result = await waitForKnowledgeDeletion(['a', 'b'], async () => snapshots[calls++] ?? null, { delay: noDelay });
  assert.equal(result, 'completed');
  assert.equal(calls, 4);
});

test('accepts the empty Go slice encoded as null', async () => {
  assert.equal(await waitForKnowledgeDeletion(['a'], async () => null), 'completed');
});

test('does not mistake polling timeout for successful deletion', async () => {
  let calls = 0;
  assert.equal(await waitForKnowledgeDeletion(['a'], async () => {
    calls++;
    return [row('a', 'deleting')];
  }, { attempts: 3, delay: noDelay }), 'pending');
  assert.equal(calls, 3);
});

test('reports a delete that exhausts retries and becomes visible again', async () => {
  const snapshots = [[row('a', 'deleting')], [row('a', 'failed')]];
  assert.equal(await waitForKnowledgeDeletion(['a'], async () => snapshots.shift() ?? null, { delay: noDelay }), 'failed');
});

test('does not treat a previous parse or delete failure as failure of the newly queued request', async () => {
  const snapshots = [[row('a', 'failed')], [row('a', 'deleting')], []];
  assert.equal(await waitForKnowledgeDeletion(['a'], async () => snapshots.shift() ?? null, { delay: noDelay }), 'completed');
});

test('checks requested IDs even when other documents are returned', async () => {
  assert.equal(await waitForKnowledgeDeletion(['a'], async () => [row('unrelated', 'completed')],), 'completed');
});

test('畸形载荷（非数组）不能证明删除完成', async () => {
  // 新契约下空集是 [] 或 null；信封残留/对象等形态一律视为不可信。
  for (const response of [{}, { items: [] }, { success: true }]) {
    await assert.rejects(waitForKnowledgeDeletion(['a'], async () => response as any), /Invalid/);
  }
});

test('query failures propagate instead of being interpreted as missing files', async () => {
  await assert.rejects(waitForKnowledgeDeletion(['a'], async () => {
    throw new Error('network unavailable');
  }), /network unavailable/);
});

test('navigation during an in-flight query suppresses completion', async () => {
  let active = true;
  assert.equal(await waitForKnowledgeDeletion(['a'], async () => {
    active = false;
    return [];
  }, { isActive: () => active }), 'cancelled');
});

test('navigation during a polling delay stops further queries', async () => {
  let active = true;
  let calls = 0;
  assert.equal(await waitForKnowledgeDeletion(['a'], async () => {
    calls++;
    return [row('a', 'deleting')];
  }, { isActive: () => active, delay: async () => { active = false; } }), 'cancelled');
  assert.equal(calls, 1);
});

test('checks all 200 IDs in bounded queries before deciding deletion is complete', async () => {
  const ids = Array.from({ length: 200 }, (_, i) => `document-${i}`);
  const queried: string[][] = [];
  assert.equal(await waitForKnowledgeDeletion(ids, async (batch) => {
    queried.push(batch);
    return batch.includes(ids[199]) ? [row(ids[199], 'deleting')] : [];
  }, { attempts: 1 }), 'pending');
  assert.deepEqual(queried.map(batch => batch.length), [50, 50, 50, 50]);
  assert.deepEqual(queried.flat(), ids);
});

test('a later chunk query failure cannot report a partially checked batch as deleted', async () => {
  const ids = Array.from({ length: 51 }, (_, i) => `document-${i}`);
  await assert.rejects(waitForKnowledgeDeletion(ids, async (batch) => {
    if (batch.includes(ids[50])) throw new Error('query failed');
    return [];
  }), /query failed/);
});
