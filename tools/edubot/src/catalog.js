import catalog from './catalog.json' with { type: 'json' };
import { apiPath } from './api.js';
import { z } from 'zod';

export const pathParams = z.record(z.string(), z.string());
const scalar = z.union([z.string(), z.number(), z.boolean()]);
export const queryParams = z.record(z.string(), z.union([scalar, z.array(scalar)]));
export const scopes = ['dashboard', 'backoffice', 'share', 'public'];

const multipartPaths = new Set([
  '/app/api/building/projects/{id}/photos',
  '/app/api/persona/sources/file',
  '/app/api/portal/sites/{id}/photos',
  '/app/api/settings/document-template/logo',
]);
const sessionOperations = new Set([
  'POST /app/api/companies/{id}/switch',
  'POST /admin/api/tenants/{slug}/impersonate',
]);

function describe(key) {
  const [method, template] = key.split(' ');
  const scope = template.startsWith('/admin/api/') ? 'backoffice' :
    template.startsWith('/app/api/') ? 'dashboard' :
    template.startsWith('/app/share/') ? 'share' : 'public';
  const params = [...template.matchAll(/\{([^}]+)\}/g)].map((match) => match[1]);
  return {
    operation: key,
    method,
    template,
    scope,
    params,
    input: method === 'GET' ? 'query' : multipartPaths.has(template) ? 'multipart' : 'json',
    session: sessionOperations.has(key),
  };
}

const operations = new Map(catalog.map((key) => [key, describe(key)]));

export function listCatalog({ scope, method, search, offset = 0, limit = 50,
  allowWrites = true, allowBackoffice = true } = {}) {
  const matches = [...operations.values()].filter((op) =>
    (!scope || scope === op.scope) && (!method || method === op.method) &&
    (!search || op.operation.toLowerCase().includes(search.toLowerCase())) &&
    (allowWrites || op.method === 'GET') && (allowBackoffice || op.scope !== 'backoffice'));
  return { total: matches.length, offset, operations: matches.slice(offset, offset + limit) };
}

export function getCatalogOperation(key) {
  const operation = operations.get(key);
  if (!operation) throw new Error('Unknown operation. Use the operation catalog to find the method and path template.');
  return operation;
}

export function operationPath(operation, params = {}) {
  if (Object.keys(params).length !== operation.params.length ||
      operation.params.some((key) => !Object.hasOwn(params, key) || !params[key])) {
    throw new Error(`Expected path parameters: ${operation.params.join(', ') || 'none'}.`);
  }

  const path = operation.template.replace(/\{([^}]+)\}/g, (_, key) => encodeURIComponent(params[key]));
  if (operation.scope !== 'public') apiPath(path);
  return path;
}

export function validateOperationBody(operation, body) {
  if (operation.method === 'GET' && body !== undefined) throw new Error('Pass query parameters for GET operations.');
  if (operation.input === 'multipart' && !(body instanceof FormData)) throw new Error('This operation expects multipart form data.');
}
