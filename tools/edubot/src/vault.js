import { randomBytes, randomInt, webcrypto } from 'node:crypto';
import { z } from 'zod';

const path = '/admin/api/vault';
const iterations = 600_000;
const fields = ['title', 'username', 'password', 'url', 'notes'];
const id = z.string().regex(/^[0-9a-f]{24}$/i);
export const vaultFields = z.strictObject({
  title: z.string().trim().min(1).optional(),
  username: z.string().trim().optional(),
  password: z.string().optional(),
  url: z.string().trim().optional(),
  notes: z.string().trim().optional(),
});

const b64 = (value) => Buffer.from(value).toString('base64');
const bytes = (value) => Buffer.from(value, 'base64');
const buffer = (value) => Buffer.from(value, 'utf8');
const aes = { name: 'AES-GCM', length: 256 };

async function passwordKey(master, kdf) {
  if (kdf?.name !== 'PBKDF2' || kdf.hash !== 'SHA-256' ||
      !Number.isInteger(kdf.iterations) || kdf.iterations < 100_000 || kdf.iterations > 10_000_000) {
    throw new Error('The vault returned unsupported key derivation settings.');
  }
  const base = await webcrypto.subtle.importKey('raw', buffer(master), 'PBKDF2', false, ['deriveKey']);
  return webcrypto.subtle.deriveKey({ ...kdf, salt: bytes(kdf.salt) }, base, aes, false, ['encrypt', 'decrypt']);
}

async function seal(key, data) {
  const iv = randomBytes(12);
  const encrypted = await webcrypto.subtle.encrypt({ name: aes.name, iv }, key, data);
  return { iv: b64(iv), data: b64(encrypted) };
}

async function opened(key, sealed) {
  return webcrypto.subtle.decrypt({ name: aes.name, iv: bytes(sealed.iv) }, key, bytes(sealed.data));
}

async function wrap(master, rawKey) {
  if (master.length < 12) throw new Error('Use a vault master password of at least 12 characters.');
  const kdf = { name: 'PBKDF2', hash: 'SHA-256', iterations, salt: b64(randomBytes(16)) };
  return { kdf, wrappedKey: await seal(await passwordKey(master, kdf), rawKey) };
}

async function session(rawKey) {
  const base = await webcrypto.subtle.importKey('raw', rawKey, 'HKDF', false, ['deriveKey', 'deriveBits']);
  const hkdf = (info) => ({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(), info: buffer(info) });
  const entryKey = await webcrypto.subtle.deriveKey(hkdf('thebots-vault-entries-v1'), base, aes, false, ['encrypt', 'decrypt']);
  const token = b64(await webcrypto.subtle.deriveBits(hkdf('thebots-vault-auth-v1'), base, 256));
  return { entryKey, token };
}

function metadata(record) {
  const { iv, data, ...meta } = record;
  return meta;
}

async function entry(key, record) {
  const data = JSON.parse(Buffer.from(await opened(key, record)).toString('utf8'));
  return Object.fromEntries(fields.map((field) => [field, String(data[field] ?? '')]));
}

export function generateVaultPassword() {
  const charset = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#$%&*+-=?@_';
  return Array.from({ length: 20 }, () => charset[randomInt(charset.length)]).join('');
}

export async function vaultOperation(api, action, { master, nextMaster, recordId, fields: changes,
  expectedUpdatedAt, signal } = {}) {
  if (!master) throw new Error('A vault master password is required.');
  if (action === 'init') {
    const rawKey = randomBytes(32);
    try {
      const body = { ...await wrap(master, rawKey), authToken: (await session(rawKey)).token };
      return await api.json(path, { method: 'POST', body, signal });
    } finally {
      rawKey.fill(0);
    }
  }

  const meta = await api.json(path, { signal });
  if (!meta.exists) throw new Error('The password vault has not been created.');
  const key = await passwordKey(master, meta.kdf);
  let rawKey;
  try {
    rawKey = new Uint8Array(await opened(key, meta.wrappedKey));
  } catch {
    throw new Error('The master password could not unlock the vault.');
  }
  try {
    const { token: vaultSession, entryKey } = await session(rawKey);
    const opts = { vaultSession, signal };
    const entries = `${path}/entries`;
    if (action === 'rotate') {
      if (!nextMaster) throw new Error('A new vault master password is required.');
      return await api.json(`${path}/key`, { ...opts, method: 'PUT', body: await wrap(nextMaster, rawKey) });
    }
    if (action === 'delete') {
      return await api.json(`${entries}/${id.parse(recordId)}`, { ...opts, method: 'DELETE' });
    }
    if (action === 'add') {
      const data = vaultFields.parse(changes);
      if (!data.title) throw new Error('A title is required.');
      const body = await seal(entryKey, buffer(JSON.stringify({ v: 1, ...data })));
      return metadata(await api.json(entries, { ...opts, method: 'POST', body }));
    }
    const records = await api.json(entries, opts);
    if (action === 'list') {
      return Promise.all(records.map(async (record) => ({ ...metadata(record), title: (await entry(entryKey, record)).title })));
    }
    const wanted = id.parse(recordId).toLowerCase();
    const record = records.find((record) => record.id === wanted);
    if (!record) throw new Error('Vault entry not found.');
    const data = await entry(entryKey, record);
    if (action === 'read') return { ...metadata(record), ...data };
    if (action !== 'update') throw new Error('Unknown vault action.');
    const updated = { ...data, ...vaultFields.parse(changes) };
    const body = {
      ...await seal(entryKey, buffer(JSON.stringify({ v: 1, ...updated }))),
      expectedUpdatedAt: expectedUpdatedAt ?? record.updatedAt,
    };
    return metadata(await api.json(`${entries}/${record.id}`, { ...opts, method: 'PUT', body }));
  } finally {
    rawKey.fill(0);
  }
}
