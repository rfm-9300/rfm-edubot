#!/usr/bin/env node
import { writeFile } from 'node:fs/promises';
import { stdin, stdout, stderr } from 'node:process';
import { parseArgs } from 'node:util';
import { ZodError } from 'zod';
import { createApi, dashboardPath, jsonResponse, REQUEST_METHODS } from './api.js';
import { operations, runOperation } from './operations.js';

const commands = new Map(Object.entries(operations).map(([name, value]) => [name.replaceAll('_', '-'), value]));
const help = `Usage:
  edubot <command> [--args <JSON or ->] [--confirm]
  edubot login --email <email> < password-file
  edubot login --google < firebase-id-token-file
  edubot switch-company <company-id>
  edubot request <METHOD> </app/api/path> [--args <JSON or ->] [--output <file>] [--confirm]
  edubot help [command]

Set EDUBOT_URL to your dashboard origin and EDUBOT_TOKEN to a dashboard session token.
The login and switch-company commands print a token for use in EDUBOT_TOKEN.
Pass --args - to read JSON from stdin. Write operations require --confirm.

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

async function parseJson(value) {
  if (value === undefined) return undefined;
  const input = value === '-' ? await readStdin() : value;
  try {
    return JSON.parse(input);
  } catch {
    throw new Error('Expected valid JSON for --args.');
  }
}

function printJson(value) {
  stdout.write(`${JSON.stringify(value, null, 2)}\n`);
}

function printToken(value) {
  if (typeof value?.token !== 'string' || !/^[a-zA-Z0-9._~-]+$/.test(value.token)) {
    throw new Error('The edubot API did not return a dashboard token.');
  }
  stdout.write(`${value.token}\n`);
}

async function login(args) {
  const { values } = parseArgs({ args, options: { email: { type: 'string' }, google: { type: 'boolean' } } });
  if (Number(Boolean(values.email)) + Number(Boolean(values.google)) !== 1) {
    throw new Error('Use login --email <email> for password sign-in or login --google for a Firebase ID token.');
  }
  const credential = (await readStdin()).replace(/\r?\n$/, '');
  if (!credential) throw new Error('Pass your password or Firebase ID token on stdin.');
  const api = createApi({ token: '' });
  const body = values.google ? { idToken: credential } : { email: values.email, password: credential };
  printToken(await api.json(values.google ? '/app/auth/google' : '/app/auth/login', { method: 'POST', body }));
}

async function request(args) {
  const { values, positionals } = parseArgs({
    args,
    allowPositionals: true,
    options: { args: { type: 'string' }, output: { type: 'string' }, confirm: { type: 'boolean' } },
  });
  if (positionals.length !== 2) throw new Error('Use request <METHOD> </app/api/path>.');
  const [method, path] = positionals;
  if (!REQUEST_METHODS.includes(method)) throw new Error(`Use one of ${REQUEST_METHODS.join(', ')}.`);
  if (method !== 'GET' && !values.confirm) throw new Error('Use --confirm to run a write command.');
  if (method === 'GET' && values.args !== undefined) throw new Error('Use URL query parameters for GET requests.');
  const response = await createApi().request(dashboardPath(path), {
    method,
    body: await parseJson(values.args),
    accept: values.output ? '*/*' : 'application/json',
  });
  if (values.output) {
    await writeFile(values.output, Buffer.from(await response.arrayBuffer()), { flag: 'wx', mode: 0o600 });
  } else {
    printJson(await jsonResponse(response));
  }
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
  if (name === 'request') return request(args);
  if (name === 'switch-company') {
    if (args.length !== 1 || !/^[0-9a-f]{24}$/i.test(args[0])) {
      throw new Error('Use switch-company <company-id>. Get the company ID from whoami.');
    }
    printToken(await createApi().json(`/app/api/companies/${args[0]}/switch`, { method: 'POST' }));
    return;
  }

  const command = commands.get(name);
  if (!command) throw new Error('Unknown command. Use edubot help to see the available commands.');
  const { values } = parseArgs({ args, options: { args: { type: 'string' }, confirm: { type: 'boolean' } } });
  const payload = await parseJson(values.args);
  printJson(await runOperation(createApi(), command, payload === undefined ? {} : payload, { allowWrites: values.confirm }));
}

main(process.argv.slice(2)).catch((error) => {
  const message = error instanceof ZodError
    ? error.issues.map(({ path, message }) => `${path.join('.') || 'arguments'}: ${message}`).join('\n')
    : error.message;
  stderr.write(`edubot: ${message}\n`);
  process.exitCode = 1;
});
