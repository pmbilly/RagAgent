import assert from 'node:assert/strict';
import test from 'node:test';
import {
  resolveAgentWebSearchProviderId,
  isAgentWebSearchReady,
  isTenantWebSearchReady,
} from './agentWebSearch.ts';

const providers = [
  { id: 'p1', name: 'Keenable', isDefault: false },
  { id: 'p2', name: 'Default', isDefault: true },
];

test('resolveAgentWebSearchProviderId uses explicit agent provider', () => {
  assert.equal(
    resolveAgentWebSearchProviderId({ webSearchProviderId: 'p1' }, providers),
    'p1',
  );
});

test('resolveAgentWebSearchProviderId falls back to tenant default', () => {
  assert.equal(
    resolveAgentWebSearchProviderId({ webSearchProviderId: '' }, providers),
    'p2',
  );
});

test('resolveAgentWebSearchProviderId returns null when default missing', () => {
  const noDefault = [{ id: 'p1', name: 'Keenable', isDefault: false }];
  assert.equal(
    resolveAgentWebSearchProviderId({ webSearchProviderId: '' }, noDefault),
    null,
  );
});

test('isAgentWebSearchReady requires enabled flag and resolvable provider', () => {
  assert.equal(
    isAgentWebSearchReady({ webSearchEnabled: true }, providers),
    true,
  );
  assert.equal(
    isAgentWebSearchReady({ webSearchEnabled: true, webSearchProviderId: '' }, [
      { id: 'p1', name: 'Keenable', isDefault: false },
    ]),
    false,
  );
  assert.equal(
    isAgentWebSearchReady({ webSearchEnabled: false }, providers),
    false,
  );
});

test('isAgentWebSearchReady trusts source workspace readiness for a shared agent', () => {
  assert.equal(
    isAgentWebSearchReady(
      { webSearchEnabled: true, webSearchProviderId: 'source-provider' },
      [],
      true,
    ),
    true,
  );
  assert.equal(
    isAgentWebSearchReady({ webSearchEnabled: true }, providers, false),
    false,
  );
});

test('isTenantWebSearchReady checks default provider only', () => {
  assert.equal(isTenantWebSearchReady(providers), true);
  assert.equal(
    isTenantWebSearchReady([{ id: 'p1', name: 'Keenable', isDefault: false }]),
    false,
  );
});
