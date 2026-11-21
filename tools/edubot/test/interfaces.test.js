import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const cliPath = fileURLToPath(new URL('../src/cli.js', import.meta.url));
const mcpPath = fileURLToPath(new URL('../src/mcp.js', import.meta.url));
const recordId = '123456789012345678901234';
const companyId = '123456789012345678901235';
const requests = [];
const fixture = createServer(async (request, response) => {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString();
  const multipart = request.headers['content-type']?.startsWith('multipart/form-data');
  const entry = { method: request.method, url: request.url, auth: request.headers.authorization,
    body: raw && !multipart ? JSON.parse(raw) : undefined };
  if (multipart) entry.multipart = raw;
  if (request.headers['x-vault-auth']) entry.vault = request.headers['x-vault-auth'];
  requests.push(entry);

  const path = new URL(request.url, 'http://localhost').pathname;
  function json(status, data) {
    response.writeHead(status, { 'content-type': 'application/json' });
    response.end(JSON.stringify(data));
  }
  if (path === '/health') return json(200, { status: 'ok' });
  if (path.startsWith('/app/auth/')) return json(200, { token: 'signed.token' });
  if (path.startsWith('/admin/auth/')) return json(200, { token: 'admin.token' });
  if (path.startsWith('/app/share/')) {
    return request.headers.authorization ? json(400, { error: 'unexpected_token' }) : json(200, { decision: 'APPROVED' });
  }
  const backoffice = path.startsWith('/admin/api/');
  const bearer = request.headers.authorization;
  if (backoffice ? bearer !== 'Bearer admin.token' : !['Bearer test.token', 'Bearer switched.token', 'Bearer impersonated.token'].includes(bearer)) {
    return json(401, { error: 'expired_session' });
  }

  switch (path) {
    case '/app/api/me':
      return json(200, { tenant: { id: bearer === 'Bearer test.token' ? companyId : recordId }, modules: ['overview', 'clients'] });
    case '/admin/api/admins':
      return json(200, { admins: [] });
    case '/admin/api/tenants/sample/impersonate':
      return json(200, { token: 'impersonated.token' });
    case '/admin/api/vault/entries':
      return entry.vault === 'dmF1bHQ=' ? json(200, []) : json(403, { error: 'vault_locked' });
    case '/app/api/settings/document-template/logo':
      return json(200, { hasLogo: true });
    case `/app/api/crm/invoices/${recordId}/paid`:
      return json(200, { status: 'PAID' });
    case '/app/api/crm/clients':
      return request.method === 'GET' ? json(200, [{ id: recordId }]) : json(201, { id: recordId });
    case '/app/api/crm/clients/fields':
      return json(200, { standard: [{ key: 'taxId', required: true }], custom: [] });
    case '/app/api/overview':
      return json(200, { attention: [] });
    case '/app/api/building/projects':
      return json(403, { error: 'module_disabled' });
    case `/app/api/companies/${companyId}/switch`:
      return json(200, { token: 'switched.token' });
    case `/app/api/crm/quotes/${recordId}/pdf`:
      response.writeHead(200, { 'content-type': 'application/pdf' });
      return response.end('%PDF-fixture');
    case `/app/api/conversations/${recordId}/messages`:
      return json(201, { id: recordId });
    case `/app/api/conversations/${recordId}/auto-reply`:
      return json(200, { autoReplyEnabled: false });
    case `/app/api/notifications/${recordId}/read`:
      response.writeHead(204);
      return response.end();
    case '/app/api/redirect':
      response.writeHead(302, { location: '/unexpected-redirect' });
      return response.end();
    default:
      return json(404, {});
  }
});

let url;
before(async () => {
  await new Promise((resolve, reject) => {
    fixture.once('error', reject);
    fixture.listen(0, '127.0.0.1', resolve);
  });
  url = `http://127.0.0.1:${fixture.address().port}`;
});

after(async () => {
  await new Promise((resolve) => fixture.close(resolve));
});

function cli(args, { input = '', token = 'test.token', adminToken = '', vaultToken = '' } = {}) {
  const env = { ...process.env, EDUBOT_URL: url, EDUBOT_TOKEN: token, EDUBOT_ADMIN_TOKEN: adminToken, EDUBOT_VAULT_TOKEN: vaultToken };
  const child = spawn(process.execPath, [cliPath, ...args], { env });
  return new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (data) => { stdout += data; });
    child.stderr.on('data', (data) => { stderr += data; });
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr }));
    child.stdin.end(input);
  });
}

async function connectMcp(testContext, allowWrites = '0', env = {}) {
  const client = new Client({ name: 'edubot-test', version: '1.0.0' });
  testContext.after(() => client.close());
  await client.connect(new StdioClientTransport({
    command: process.execPath,
    args: [mcpPath],
    env: { EDUBOT_URL: url, EDUBOT_TOKEN: 'test.token', EDUBOT_MCP_ALLOW_WRITES: allowWrites, ...env },
  }));
  return client;
}

test('CLI public health and password and Google login use no bearer token', async () => {
  const health = await cli(['health'], { token: '' });
  assert.equal(health.code, 0);
  assert.deepEqual(JSON.parse(health.stdout), { status: 'ok' });
  assert.equal(requests.at(-1).auth, undefined);

  const password = await cli(['login', '--email', 'test@example.test'], { input: 'test password\n' });
  assert.deepEqual(password, { code: 0, stdout: 'signed.token\n', stderr: '' });
  assert.deepEqual(requests.at(-1), {
    method: 'POST', url: '/app/auth/login', auth: undefined, body: { email: 'test@example.test', password: 'test password' },
  });

  const google = await cli(['login', '--google'], { input: 'test.firebase.token\n' });
  assert.equal(google.stdout, 'signed.token\n');
  assert.deepEqual(requests.at(-1), {
    method: 'POST', url: '/app/auth/google', auth: undefined, body: { idToken: 'test.firebase.token' },
  });

  const switched = await cli(['switch-company', companyId]);
  assert.equal(switched.stdout, 'switched.token\n');
  assert.equal(requests.at(-1).auth, 'Bearer test.token');
});

test('CLI serializes filters and reports the server’s auth and module failures', async () => {
  const list = await cli(['list-clients', '--args', '-'], { input: '{"q":"A&B?","archived":true}' });
  assert.equal(list.code, 0);
  assert.deepEqual(JSON.parse(list.stdout), [{ id: recordId }]);
  assert.equal(requests.at(-1).url, '/app/api/crm/clients?q=A%26B%3F&archived=1');

  const denied = await cli(['list-projects']);
  assert.equal(denied.code, 1);
  assert.equal(denied.stdout, '');
  assert.match(denied.stderr, /HTTP 403: module_disabled/);

  const expired = await cli(['whoami'], { token: 'expired.token' });
  assert.equal(expired.code, 1);
  assert.match(expired.stderr, /HTTP 401: expired_session/);
});

test('CLI checks named and direct writes before dispatch', async () => {
  const input = '{"name":"Example","phone":"+15555550100"}';
  const count = requests.length;
  const blocked = await cli(['create-client', '--args', input]);
  assert.equal(blocked.code, 1);
  assert.match(blocked.stderr, /--confirm/);
  const direct = await cli(['request', 'POST', '/app/api/crm/clients', '--args', input]);
  assert.equal(direct.code, 1);
  assert.equal(requests.length, count);

  const created = await cli(['create-client', '--args', input, '--confirm']);
  assert.equal(created.code, 0);
  assert.deepEqual(requests.at(-1), {
    method: 'POST', url: '/app/api/crm/clients', auth: 'Bearer test.token', body: JSON.parse(input),
  });
});

test('CLI refuses unsafe paths, does not follow redirects, and can export a PDF', async (t) => {
  const count = requests.length;
  for (const path of ['https://example.test/app/api/me', '/app/api/%2e%2e/admin', '/app/api/%252fadmin', '/app/api/..%5cadmin']) {
    const result = await cli(['request', 'GET', path]);
    assert.equal(result.code, 1);
    assert.match(result.stderr, /edubot API or share path/);
  }
  assert.equal(requests.length, count);

  const redirect = await cli(['request', 'GET', '/app/api/redirect']);
  assert.equal(redirect.code, 1);
  assert.match(redirect.stderr, /HTTP 302/);
  assert.equal(requests.length, count + 1);

  const folder = await mkdtemp(join(tmpdir(), 'edubot-test-'));
  t.after(() => rm(folder, { recursive: true }));
  const output = join(folder, 'quote.pdf');
  const args = ['request', 'GET', `/app/api/crm/quotes/${recordId}/pdf`, '--output', output];
  assert.equal((await cli(args)).code, 0);
  assert.equal(await readFile(output, 'utf8'), '%PDF-fixture');
  assert.match((await cli(args)).stderr, /EEXIST/);
});

test('MCP discovers and calls tools, resources and prompts over stdio', async (t) => {
  const client = await connectMcp(t);
  const { tools } = await client.listTools();
  assert.ok(tools.some(({ name, annotations }) => name === 'list_clients' && annotations.readOnlyHint));
  assert.ok(!tools.some(({ name }) => name === 'create_client'));
  assert.ok(tools.some(({ name }) => name === 'read_operation'));
  assert.ok(!tools.some(({ name }) => name === 'write_operation'));

  const result = await client.callTool({ name: 'list_clients', arguments: { q: 'A&B', archived: true } });
  assert.deepEqual(result.structuredContent, { data: [{ id: recordId }] });
  assert.equal(requests.at(-1).url, '/app/api/crm/clients?q=A%26B&archived=1');
  assert.equal(requests.at(-1).auth, 'Bearer test.token');


  const fields = await client.callTool({ name: 'get_client_fields' });
  assert.deepEqual(fields.structuredContent.data.standard, [{ key: 'taxId', required: true }]);

  const count = requests.length;
  const invalid = await client.callTool({ name: 'get_client', arguments: { id: '../bad' } });
  assert.equal(invalid.isError, true);
  assert.equal(requests.length, count);

  const denied = await client.callTool({ name: 'list_projects' });
  assert.equal(denied.isError, true);
  assert.match(denied.content[0].text, /HTTP 403: module_disabled/);

  assert.deepEqual((await client.listResources()).resources.map(({ uri }) => uri),
    ['edubot://dashboard/session', 'edubot://dashboard/overview']);
  const session = await client.readResource({ uri: 'edubot://dashboard/session' });
  assert.equal(JSON.parse(session.contents[0].text).tenant.id, companyId);
  assert.equal((await client.listPrompts()).prompts[0].name, 'company-brief');
  assert.match((await client.getPrompt({ name: 'company-brief' })).messages[0].content.text, /list_notifications/);
});

test('MCP exposes writes only when enabled and handles empty success responses', async (t) => {
  const client = await connectMcp(t, '1');
  const { tools } = await client.listTools();
  assert.ok(tools.some(({ name, annotations }) => name === 'create_client' && !annotations.readOnlyHint));
  const created = await client.callTool({
    name: 'create_client',
    arguments: { name: 'Example', phone: '+15555550100', customFields: { preferred: true } },
  });
  assert.deepEqual(created.structuredContent, { data: { id: recordId } });
  assert.deepEqual(requests.at(-1).body, { name: 'Example', phone: '+15555550100', customFields: { preferred: true } });
  const sent = await client.callTool({ name: 'send_conversation_reply', arguments: { id: recordId, text: 'Hello' } });
  assert.equal(sent.isError, undefined);
  assert.deepEqual(requests.at(-1), {
    method: 'POST', url: `/app/api/conversations/${recordId}/messages`, auth: 'Bearer test.token', body: { text: 'Hello' },
  });

  const paused = await client.callTool({ name: 'set_conversation_auto_reply', arguments: { id: recordId, enabled: false } });
  assert.deepEqual(paused.structuredContent, { data: { autoReplyEnabled: false } });
  assert.deepEqual(requests.at(-1).body, { enabled: false });
  assert.equal(requests.at(-1).method, 'PATCH');

  const marked = await client.callTool({ name: 'mark_notification_read', arguments: { id: recordId } });
  assert.deepEqual(marked.structuredContent, { data: null });
  assert.equal(requests.at(-1).url, `/app/api/notifications/${recordId}/read`);
});

test('CLI discovers all API scopes and uses each route’s matching credential', async () => {
  const catalog = await cli(['catalog', '--scope', 'backoffice', '--search', 'impersonate']);
  assert.equal(catalog.code, 0);
  assert.equal(JSON.parse(catalog.stdout).operations[0].operation, 'POST /admin/api/tenants/{slug}/impersonate');

  const signed = await cli(['login', '--backoffice'], { input: 'example password' });
  assert.equal(signed.stdout, 'admin.token\n');
  assert.deepEqual(requests.at(-1), {
    method: 'POST', url: '/admin/auth/login', auth: undefined, body: { password: 'example password' },
  });
  const reused = await cli(['login', '--backoffice', '--session'], { input: 'admin.token\n' });
  assert.equal(reused.stdout, 'admin.token\n');
  assert.equal(requests.at(-1).auth, 'Bearer admin.token');
  assert.match((await cli(['request', 'GET', '/admin/api/admins'])).stderr, /EDUBOT_ADMIN_TOKEN/);

  const admin = await cli(['call', 'GET /admin/api/admins', '--query', '{"group":["a","b"]}'], { adminToken: 'admin.token' });
  assert.equal(admin.code, 0);
  assert.equal(requests.at(-1).url, '/admin/api/admins?group=a&group=b');
  assert.equal(requests.at(-1).auth, 'Bearer admin.token');

  const vault = await cli(['call', 'GET /admin/api/vault/entries'], { adminToken: 'admin.token', vaultToken: 'dmF1bHQ=' });
  assert.equal(vault.code, 0);
  assert.equal(requests.at(-1).vault, 'dmF1bHQ=');
  const share = await cli(['call', 'GET /app/share/change/{token}/data', '--params', '{"token":"share-code"}']);
  assert.equal(share.code, 0);
  assert.equal(requests.at(-1).auth, undefined);
  const workflow = await cli(['open', 'time-clock', '--print']);
  assert.equal(workflow.stdout, `${url}/app/#my-hours\n`);
});

test('CLI and MCP can upload multipart data and stage authenticated downloads', async (t) => {
  const folder = await mkdtemp(join(tmpdir(), 'edubot-transfer-'));
  t.after(() => rm(folder, { recursive: true }));
  const sample = join(folder, 'sample.png');
  await writeFile(sample, 'fixture data');
  const command = ['call', 'POST /app/api/settings/document-template/logo', '--confirm', '--file', `file=${sample}`, '--field', 'caption=A&B'];
  assert.equal((await cli(command)).code, 0);
  assert.match(requests.at(-1).multipart, /filename="sample.png"/);
  assert.match(requests.at(-1).multipart, /image\/png/);
  assert.match(requests.at(-1).multipart, /A&B/);

  const mcp = await connectMcp(t, '1', { EDUBOT_MCP_FILES_DIR: folder });
  const uploaded = await mcp.callTool({ name: 'write_operation', arguments: {
    operation: command[1], confirm: true,
    multipart: { files: [{ name: 'file', filename: 'sample.png', staged: 'sample.png' }] },
  } });
  assert.deepEqual(uploaded.structuredContent, { data: { hasLogo: true } });
  assert.match(requests.at(-1).multipart, /fixture data/);

  await symlink(sample, join(folder, 'alias.png'));
  const count = requests.length;
  const blocked = await mcp.callTool({ name: 'write_operation', arguments: {
    operation: command[1], confirm: true,
    multipart: { files: [{ name: 'file', filename: 'sample.png', staged: 'alias.png' }] },
  } });
  assert.equal(blocked.isError, true);
  assert.equal(requests.length, count);

  const download = { operation: 'GET /app/api/crm/quotes/{id}/pdf', params: { id: recordId }, output: 'quote.pdf' };
  const downloaded = await mcp.callTool({ name: 'read_operation', arguments: download });
  assert.deepEqual(downloaded.structuredContent, { data: { contentType: 'application/pdf', file: 'quote.pdf' } });
  assert.equal(await readFile(join(folder, 'quote.pdf'), 'utf8'), '%PDF-fixture');
  assert.equal((await mcp.callTool({ name: 'read_operation', arguments: download })).isError, true);
  assert.equal((await mcp.callTool({ name: 'read_operation', arguments: { ...download, output: '../quote.pdf' } })).isError, true);
});

test('MCP discovers and executes API operations without exposing session credentials', async (t) => {
  const readOnly = await connectMcp(t);
  const listing = await readOnly.callTool({ name: 'list_operations' });
  assert.ok(listing.structuredContent.data.total > 90);
  assert.ok(listing.structuredContent.data.operations.every(({ method, scope }) => method === 'GET' && scope !== 'backoffice'));
  const failed = await readOnly.callTool({ name: 'read_operation', arguments: { operation: 'GET /admin/api/admins' } });
  assert.match(failed.content[0].text, /EDUBOT_ADMIN_TOKEN/);

  const mcp = await connectMcp(t, '1', { EDUBOT_ADMIN_TOKEN: 'admin.token' });
  const count = requests.length;
  const denied = await mcp.callTool({ name: 'write_operation', arguments: {
    operation: 'PATCH /app/api/crm/invoices/{id}/paid', params: { id: recordId }, confirm: false,
  } });
  assert.equal(denied.isError, true);
  assert.equal(requests.length, count);
  const paid = await mcp.callTool({ name: 'write_operation', arguments: {
    operation: 'PATCH /app/api/crm/invoices/{id}/paid', params: { id: recordId }, confirm: true,
  } });
  assert.deepEqual(paid.structuredContent, { data: { status: 'PAID' } });

  const credential = await mcp.callTool({ name: 'write_operation', arguments: {
    operation: 'POST /admin/api/tenants/{slug}/impersonate', params: { slug: 'sample' }, confirm: true,
  } });
  assert.equal(credential.isError, true);
  assert.match(credential.content[0].text, /select_dashboard_session/);
  const company = await mcp.callTool({ name: 'select_dashboard_session', arguments: { tenantSlug: 'sample' } });
  assert.equal(company.structuredContent.data.tenant.id, recordId);
  assert.ok(!JSON.stringify(company).includes('impersonated.token'));
  await mcp.callTool({ name: 'whoami' });
  assert.equal(requests.at(-1).auth, 'Bearer impersonated.token');
  const restored = await mcp.callTool({ name: 'select_dashboard_session', arguments: { restore: true } });
  assert.equal(restored.structuredContent.data.tenant.id, companyId);
  assert.equal(requests.at(-1).auth, 'Bearer test.token');
  const link = await mcp.callTool({ name: 'browser_workflow', arguments: { workflow: 'password-vault' } });
  assert.equal(link.structuredContent.data.url, `${url}/backoffice/#vault`);
});
