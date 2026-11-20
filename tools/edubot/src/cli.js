#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { stdin, stdout, stderr } from 'node:process';
import { parseArgs } from 'node:util';
import { ZodError } from 'zod';
import { createApi, jsonResponse, REQUEST_METHODS } from './api.js';
import { browserLink, browserWorkflows, openBrowser } from './browser.js';
import { getCatalogOperation, listCatalog, operationPath, pathParams, queryParams, scopes, validateOperationBody } from './catalog.js';
import { operations, runOperation } from './operations.js';
import { cliMultipart, saveResponse } from './transfer.js';
import { generateVaultPassword, vaultOperation } from './vault.js';

const commands = new Map(Object.entries(operations).map(([name, value]) => [name.replaceAll('_', '-'), value]));
const help = `Usage:
  edubot <command> [--args <JSON or ->] [--confirm]
  edubot login --email <email> < password-file
  edubot login --backoffice < password-file
  edubot login [--backoffice] --google < firebase-id-token-file
  edubot login [--backoffice] --session < dashboard-or-backoffice-token-file
  edubot switch-company <company-id>
  edubot impersonate <company-slug>
  edubot catalog [--scope <scope>] [--method <method>] [--search <text>] [--offset <n>] [--limit <n>]
  edubot call '<METHOD> <path-template>' [--params <JSON>] [--query <JSON>] [request options]
  edubot request <METHOD> <path> [request options]
  edubot open <workflow> [--print]
  edubot vault generate
  edubot vault <init|list|read|add|update|delete|rotate> [--id <id>] [--entry <JSON-file>] [--next <password-file>] [--confirm]
  edubot help [command]

Request options are --args <JSON or ->, --field <name=value>, --file <name=path>, --output <file>, and --confirm.
Use --field and --file for multipart requests. Write operations require --confirm.
Set EDUBOT_URL to your dashboard origin and EDUBOT_TOKEN or EDUBOT_ADMIN_TOKEN to the matching session token.

Commands:
${[...commands].map(([name, command]) => `  ${name.padEnd(29)} ${command.description}`).join('\n')}
`;

async function readStdin() {
  if (stdin.isTTY) throw new Error('Pipe input into stdin or redirect it from a file.');
  stdin.setEncoding('utf8');
  let input = '';
  for await (const chunk of stdin) input += chunk;
  return input;
}

async function parseJson(value, name) {
  if (value === undefined) return undefined;
  const input = value === '-' ? await readStdin() : value;
  try {
    return JSON.parse(input);
  } catch {
    throw new Error(`Expected valid JSON for --${name}.`);
  }
}

function printJson(value) {
  stdout.write(`${JSON.stringify(value, null, 2)}\n`);
}

function printToken(value) {
  if (typeof value?.token !== 'string' || !/^[a-zA-Z0-9._~-]+$/.test(value.token)) {
    throw new Error('The edubot API did not return a session token.');
  }
  stdout.write(`${value.token}\n`);
}

async function login(args) {
  const { values } = parseArgs({
    args,
    options: {
      email: { type: 'string' }, google: { type: 'boolean' }, backoffice: { type: 'boolean' }, session: { type: 'boolean' },
    },
  });
  if (Number(Boolean(values.email)) + Number(Boolean(values.google)) + Number(Boolean(values.session)) > 1 ||
      (values.backoffice && values.email) || (!values.backoffice && !values.email && !values.google && !values.session)) {
    throw new Error('See edubot help for dashboard and backoffice sign-in options.');
  }
  const credential = (await readStdin()).replace(/\r?\n$/, '');
  if (!credential) throw new Error('Pass your credential on stdin.');
  if (values.session) {
    const api = createApi({ token: values.backoffice ? '' : credential, adminToken: values.backoffice ? credential : '' });
    await api.json(values.backoffice ? '/admin/api/admins' : '/app/api/me');
    printToken({ token: credential });
    return;
  }
  const api = createApi({ token: '', adminToken: '', vaultToken: '' });
  const prefix = values.backoffice ? '/admin/auth' : '/app/auth';
  const body = values.google ? { idToken: credential } :
    values.backoffice ? { password: credential } : { email: values.email, password: credential };
  printToken(await api.json(`${prefix}/${values.google ? 'google' : 'login'}`, { method: 'POST', body }));
}

const requestOptions = {
  args: { type: 'string' }, output: { type: 'string' }, confirm: { type: 'boolean' },
  field: { type: 'string', multiple: true }, file: { type: 'string', multiple: true },
};

async function request(args, catalog = false) {
  const { values, positionals } = parseArgs({
    args,
    allowPositionals: true,
    options: catalog ? { ...requestOptions, params: { type: 'string' }, query: { type: 'string' } } : requestOptions,
  });
  if (positionals.length !== (catalog ? 1 : 2)) throw new Error(catalog
    ? "Use call '<METHOD> <path-template>'." : 'Use request <METHOD> <path>.');
  const operation = catalog ? getCatalogOperation(positionals[0]) : undefined;
  const method = operation?.method ?? positionals[0];
  if (!REQUEST_METHODS.includes(method)) throw new Error(`Use one of ${REQUEST_METHODS.join(', ')}.`);
  if (method !== 'GET' && !values.confirm) throw new Error('Use --confirm to run a write command.');
  const multipart = values.field !== undefined || values.file !== undefined;
  if (method === 'GET' && (values.args !== undefined || multipart)) throw new Error('Use query parameters for GET requests.');
  if (multipart && values.args !== undefined) throw new Error('Use multipart fields or a JSON body for a request.');
  const body = multipart ? await cliMultipart(values.field, values.file) : await parseJson(values.args, 'args');
  if (operation) validateOperationBody(operation, body);
  const params = pathParams.parse(await parseJson(values.params, 'params') ?? {});
  const query = queryParams.parse(await parseJson(values.query, 'query') ?? {});
  const path = operation ? operationPath(operation, params) : positionals[1];
  const response = await createApi().request(path, { method, body, query, accept: values.output ? '*/*' : 'application/json' });
  if (values.output) await saveResponse(response, values.output);
  else printJson(await jsonResponse(response));
}

function catalog(args) {
  const { values } = parseArgs({
    args,
    options: Object.fromEntries(['scope', 'method', 'search', 'offset', 'limit'].map((key) => [key, { type: 'string' }])),
  });
  if (values.scope && !scopes.includes(values.scope)) throw new Error(`Use one of ${scopes.join(', ')} for --scope.`);
  if (values.method && !REQUEST_METHODS.includes(values.method)) throw new Error(`Use one of ${REQUEST_METHODS.join(', ')} for --method.`);
  const offset = Number(values.offset ?? '0');
  const limit = Number(values.limit ?? '50');
  if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 200) {
    throw new Error('Use a nonnegative offset and a limit from 1 to 200.');
  }
  printJson(listCatalog({ ...values, offset, limit }));
}

async function browser(args) {
  const { positionals, values } = parseArgs({ args, allowPositionals: true, options: { print: { type: 'boolean' } } });
  if (positionals.length !== 1 || !Object.hasOwn(browserWorkflows, positionals[0])) {
    throw new Error(`Use one of: ${Object.keys(browserWorkflows).join(', ')}.`);
  }
  const url = browserLink(createApi().origin, positionals[0]);
  stdout.write(`${url}\n`);
  if (!values.print) await openBrowser(url);
}

async function vault(args) {
  const { values, positionals } = parseArgs({
    args, allowPositionals: true,
    options: {
      id: { type: 'string' }, entry: { type: 'string' }, next: { type: 'string' },
      'expected-updated-at': { type: 'string' }, confirm: { type: 'boolean' },
    },
  });
  const [action] = positionals;
  if (positionals.length !== 1 || !['generate', 'init', 'list', 'read', 'add', 'update', 'delete', 'rotate'].includes(action)) {
    throw new Error('See edubot help for password vault commands.');
  }
  if (action === 'generate') {
    stdout.write(`${generateVaultPassword()}\n`);
    return;
  }
  if (!['list', 'read'].includes(action) && !values.confirm) throw new Error('Use --confirm to run a write command.');
  const master = (await readStdin()).replace(/\r?\n$/, '');
  const fields = values.entry ? await parseJson(await readFile(values.entry, 'utf8'), 'entry') : undefined;
  const nextMaster = values.next ? (await readFile(values.next, 'utf8')).replace(/\r?\n$/, '') : undefined;
  printJson(await vaultOperation(createApi(), action, {
    master, nextMaster, fields, recordId: values.id, expectedUpdatedAt: values['expected-updated-at'],
  }));
}

async function main([name, ...args]) {
  if (!name || name === '-h' || name === '--help' || name === 'help') {
    if (name === 'help' && args.length) {
      const command = commands.get(args[0]);
      if (!command || args.length !== 1) throw new Error('Use edubot help to see the available commands.');
      stdout.write(`${command.description}\n`);
      printJson(command.schema.toJSONSchema());
    } else {
      stdout.write(help);
    }
    return;
  }

  if (name === 'login') return login(args);
  if (name === 'request' || name === 'call') return request(args, name === 'call');
  if (name === 'catalog') return catalog(args);
  if (name === 'open') return browser(args);
  if (name === 'vault') return vault(args);
  if (name === 'switch-company') {
    if (args.length !== 1 || !/^[0-9a-f]{24}$/i.test(args[0])) {
      throw new Error('Use switch-company <company-id>. Get the company ID from whoami.');
    }
    printToken(await createApi().json(`/app/api/companies/${args[0]}/switch`, { method: 'POST' }));
    return;
  }
  if (name === 'impersonate') {
    if (args.length !== 1 || !/^[a-z0-9-]+$/i.test(args[0])) throw new Error('Use impersonate <company-slug>.');
    printToken(await createApi().json(`/admin/api/tenants/${args[0]}/impersonate`, { method: 'POST' }));
    return;
  }

  const command = commands.get(name);
  if (!command) throw new Error('Unknown command. Use edubot help to see the available commands.');
  const { values } = parseArgs({ args, options: { args: { type: 'string' }, confirm: { type: 'boolean' } } });
  const payload = await parseJson(values.args, 'args');
  printJson(await runOperation(createApi(), command, payload === undefined ? {} : payload, { allowWrites: values.confirm }));
}

main(process.argv.slice(2)).catch((error) => {
  const message = error instanceof ZodError
    ? error.issues.map(({ path, message }) => `${path.join('.') || 'arguments'}: ${message}`).join('\n')
    : error.message;
  stderr.write(`edubot: ${message}\n`);
  process.exitCode = 1;
});
