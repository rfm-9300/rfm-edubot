import { constants, openAsBlob } from 'node:fs';
import { open, realpath, unlink } from 'node:fs/promises';
import { basename, extname, isAbsolute, join } from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { isJson, jsonResponse } from './api.js';

const mimeTypes = {
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp',
  '.svg': 'image/svg+xml', '.pdf': 'application/pdf', '.txt': 'text/plain', '.md': 'text/markdown',
};
const inlineByteLimit = 1024 * 1024;

export function fileType(name) {
  return mimeTypes[extname(name).toLowerCase()] ?? 'application/octet-stream';
}

export function namedValue(value) {
  const i = value.indexOf('=');
  if (i <= 0) throw new Error('Use NAME=VALUE for multipart fields and files.');
  return [value.slice(0, i), value.slice(i + 1)];
}

export function appendFile(form, field, path, file) {
  form.append(field, file, basename(path));
}

export async function cliMultipart(fields = [], files = []) {
  const form = new FormData();
  for (const field of fields) form.append(...namedValue(field));
  for (const file of files) {
    const [name, path] = namedValue(file);
    appendFile(form, name, path, await openAsBlob(path, { type: fileType(path) }));
  }
  return form;
}

export async function saveResponse(response, path) {
  let file;
  try {
    file = await open(path, 'wx', 0o600);
    if (response.body) await pipeline(Readable.fromWeb(response.body), file.createWriteStream());
    else await file.close();
  } catch (error) {
    if (!response.body?.locked) await response.body?.cancel().catch(() => {});
    if (file) {
      await file.close().catch(() => {});
      await unlink(path).catch(() => {});
    }
    throw error;
  }
}

function attachmentName(name) {
  if (!/^[^/\\\u0000-\u001f\u007f]{1,180}$/.test(name) || /^[. ]+$/.test(name) || /[:. ]$/.test(name) || name.includes(':')) {
    throw new Error('Use a plain filename in the attachment directory.');
  }
  return name;
}

export async function attachments(path = process.env.EDUBOT_MCP_FILES_DIR) {
  if (!path) return null;
  if (!isAbsolute(path)) throw new Error('EDUBOT_MCP_FILES_DIR must be an absolute path.');
  const root = await realpath(path);
  return {
    async read(name, type) {
      const target = join(root, attachmentName(name));
      if (await realpath(target) !== target) throw new Error('Use a regular file in the attachment directory.');
      const handle = await open(target, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) | (constants.O_NONBLOCK ?? 0));
      try {
        if (!(await handle.stat()).isFile()) throw new Error('Use a regular file in the attachment directory.');
        return new Blob([await handle.readFile()], { type: type || fileType(name) });
      } finally {
        await handle.close();
      }
    },
    async save(response, name) {
      let filename;
      try {
        filename = attachmentName(name);
      } catch (error) {
        await response.body?.cancel().catch(() => {});
        throw error;
      }
      await saveResponse(response, join(root, filename));
      return filename;
    },
  };
}

export async function mcpMultipart({ fields, files }, directory) {
  const form = new FormData();
  for (const { name, value } of fields ?? []) form.append(name, value);
  for (const { name, filename, base64, staged, type } of files ?? []) {
    if ((base64 === undefined) === (staged === undefined)) throw new Error('Provide exactly one of base64 or staged per file.');
    if (staged !== undefined && !directory) throw new Error('Set EDUBOT_MCP_FILES_DIR to use staged files.');
    let blob;
    if (staged !== undefined) {
      blob = await directory.read(staged, type);
    } else {
      if (!/^[a-zA-Z0-9+/]*={0,2}$/.test(base64) || base64.length % 4 !== 0) throw new Error('File data must use standard base64.');
      blob = new Blob([Buffer.from(base64, 'base64')], { type: type || fileType(filename) });
    }
    form.append(name, blob, attachmentName(filename));
  }
  return form;
}

export async function mcpResponse(response, { directory, output } = {}) {
  if (response.status === 204 || response.status === 205) return null;
  const contentType = response.headers.get('content-type') || 'application/octet-stream';
  if (output !== undefined) {
    if (!directory) throw new Error('Set EDUBOT_MCP_FILES_DIR to save downloads.');
    return { contentType, file: await directory.save(response, output) };
  }
  if (isJson(response)) return jsonResponse(response);
  const chunks = [];
  let size = 0;
  if (response.body) {
    for await (const chunk of response.body) {
      size += chunk.length;
      if (size > inlineByteLimit) throw new Error('This download exceeds 1 MiB. Set EDUBOT_MCP_FILES_DIR and give an output filename.');
      chunks.push(chunk);
    }
  }
  return { contentType, base64: Buffer.concat(chunks, size).toString('base64') };
}
