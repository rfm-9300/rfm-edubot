import assert from 'node:assert/strict';
import { hkdfSync, createDecipheriv, pbkdf2Sync } from 'node:crypto';
import { test } from 'node:test';
import { vaultOperation } from '../src/vault.js';

test('vault entries use the browser’s encryption, keep server-side auth, and survive password rotation', async () => {
  const main = 'example master password';
  const next = 'another master password';
  const updatedAt = '2026-11-20T12:00:00Z';
  const id = '123456789012345678901234';
  const vault = '/admin/api/vault';
  let state;
  let auth;
  let row;
  let lastWrite;
  const api = {
    async json(path, options) {
      if (path === vault && options.method === 'POST') {
        const { authToken, ...metadata } = options.body;
        auth = authToken;
        state = { ...metadata, exists: true };
        return state;
      }
      if (path === vault) return state;
      assert.equal(options.vaultSession, auth);
      if (path === `${vault}/key`) {
        state = { ...state, ...options.body };
        return state;
      }
      if (options.method === 'DELETE') {
        row = undefined;
        return { deleted: true };
      }
      if (options.method === 'POST' || options.method === 'PUT') {
        lastWrite = options.body;
        const { expectedUpdatedAt, ...data } = options.body;
        row = { id, updatedAt, ...data };
        return row;
      }
      return row ? [row] : [];
    },
  };

  await vaultOperation(api, 'init', { master: main });
  await vaultOperation(api, 'add', { master: main, fields: { title: 'Fixture', password: 'secret' } });
  assert.equal(lastWrite.password, undefined);

  function open(key, sealed) {
    const ciphertext = Buffer.from(sealed.data, 'base64');
    const decoder = createDecipheriv('aes-256-gcm', key, Buffer.from(sealed.iv, 'base64'));
    decoder.setAuthTag(ciphertext.subarray(-16));
    return Buffer.concat([decoder.update(ciphertext.subarray(0, -16)), decoder.final()]);
  }
  const browserKey = pbkdf2Sync(main, Buffer.from(state.kdf.salt, 'base64'), state.kdf.iterations, 32, 'sha256');
  const raw = open(browserKey, state.wrappedKey);
  const entryKey = Buffer.from(hkdfSync('sha256', raw, '', 'thebots-vault-entries-v1', 32));
  assert.equal(JSON.parse(open(entryKey, lastWrite)).password, 'secret');
  assert.equal(Buffer.from(hkdfSync('sha256', raw, '', 'thebots-vault-auth-v1', 32)).toString('base64'), auth);

  const listed = await vaultOperation(api, 'list', { master: main });
  assert.deepEqual(listed, [{ id, updatedAt, title: 'Fixture' }]);
  await vaultOperation(api, 'update', { master: main, recordId: id, fields: { notes: 'changed' } });
  assert.equal(lastWrite.expectedUpdatedAt, updatedAt);
  await vaultOperation(api, 'rotate', { master: main, nextMaster: next });
  await assert.rejects(vaultOperation(api, 'read', { master: main, recordId: id }), /could not unlock/);
  const read = await vaultOperation(api, 'read', { master: next, recordId: id });
  assert.equal(read.notes, 'changed');
  assert.equal(read.password, 'secret');
  assert.deepEqual(await vaultOperation(api, 'delete', { master: next, recordId: id }), { deleted: true });
});
