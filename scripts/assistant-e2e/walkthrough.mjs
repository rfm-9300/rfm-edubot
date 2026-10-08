/* A repeatable walkthrough of the dashboard's AI Assistant in a real browser, against a running app whose
   model is fake-openrouter.py. It makes its own company through the backoffice, seeds clients, invoices, a
   quote and a WhatsApp chat, then uses the page as an admin and as a member, checking what it sees at each
   step. Screenshots go to out/. The model is a stub: this checks plumbing and the page, not answer quality.

     python3 fake-openrouter.py 8099 &
     OPENROUTER_BASE_URL=http://127.0.0.1:8099/api/v1 ./gradlew run      # from the repo root
     npm install && node walkthrough.mjs [--headful] [--slow=60]

   Environment: APP_URL (http://127.0.0.1:8080), MODEL_URL (http://127.0.0.1:8099), ADMIN_PASSWORD (local-dev),
   WA_APP_SECRET (local-app-secret; signs the seeded WhatsApp message), CHROME (/usr/local/bin/google-chrome). */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const APP = process.env.APP_URL || 'http://127.0.0.1:8080';
const MODEL = process.env.MODEL_URL || 'http://127.0.0.1:8099';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'local-dev';
const WA_SECRET = process.env.WA_APP_SECRET || 'local-app-secret';
const CHROME = process.env.CHROME || '/usr/local/bin/google-chrome';
const HEADFUL = process.argv.includes('--headful');
const SLOW = Number((process.argv.find(a => a.startsWith('--slow=')) || '--slow=0').split('=')[1]);
const OUT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'out');
const PASSWORD = 'Walkthrough-2026!';
fs.mkdirSync(OUT, { recursive: true });

const results = [];
async function check(name, fn) {
  try {
    await fn();
    results.push({ name, ok: true });
    console.log(`  ✓ ${name}`);
  } catch (e) {
    results.push({ name, ok: false, error: e.message });
    console.log(`  ✗ ${name}\n      ${e.message}`);
  }
}
function expect(condition, message) {
  if (!condition) throw new Error(message);
}

async function api(method, url, body, token, base = APP) {
  const res = await fetch(base + url, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(`${method} ${url} → ${res.status} ${text.slice(0, 200)}`);
  return data;
}

const day = offset => {
  const d = new Date(Date.now() + offset * 86400000);
  return d.toISOString().slice(0, 10);
};

// ── A company of its own ──

const run = Date.now().toString(36);
const slug = `assistant-e2e-${run}`;
const phoneNumberId = `pn-e2e-${run}`;
const ownerEmail = `owner-${run}@assistant-e2e.test`;
const memberEmail = `member-${run}@assistant-e2e.test`;

console.log(`Company ${slug}`);
const backoffice = (await api('POST', '/admin/auth/login', { password: ADMIN_PASSWORD })).token;
const modules = (await api('GET', '/admin/api/tenants', undefined, backoffice))[0]?.availableModules;
await api('POST', '/admin/api/tenants', {
  name: 'Oficina Lopes', slug, locale: 'pt-PT', timezone: 'Europe/Lisbon', enabledModules: modules,
  channels: [{ platform: 'WHATSAPP', externalId: phoneNumberId }],
}, backoffice);
for (const [email, role] of [[ownerEmail, 'TENANT_ADMIN'], [memberEmail, 'TENANT_MEMBER']]) {
  await api('POST', `/admin/api/tenants/${slug}/dashboard-users`, { email, password: PASSWORD, role }, backoffice);
}
const login = async email => (await api('POST', '/app/auth/login', { email, password: PASSWORD })).token;
const owner = await login(ownerEmail);
const member = await login(memberEmail);

const ana = await api('POST', '/app/api/crm/clients', { name: 'Ana Martins', phone: `+351 912 ${run.slice(-3).replace(/\D/g, '1').padEnd(3, '1')} 101`, taxId: '213456789', address: 'Rua das Flores 1', city: 'Porto' }, owner);
const bruno = await api('POST', '/app/api/crm/clients', { name: 'Bruno Sousa', phone: `+351 913 ${run.slice(-3).replace(/\D/g, '2').padEnd(3, '2')} 202`, taxId: '223456789', address: 'Avenida Central 20', city: 'Braga' }, owner);
const overdue = await api('POST', '/app/api/crm/invoices', { clientId: ana.id, items: [{ description: 'Reparação do telhado', quantity: 1, unitPriceEur: 320 }], dueDate: day(-10) }, owner);
await api('POST', '/app/api/crm/invoices', { clientId: bruno.id, items: [{ description: 'Pintura', quantity: 2, unitPriceEur: 180 }], dueDate: day(20) }, owner);
const quote = await api('POST', '/app/api/crm/quotes', { clientId: bruno.id, items: [{ description: 'Isolamento', quantity: 1, unitPriceEur: 950 }], validUntil: day(30) }, owner);
for (const title of ['Cobranças de setembro', 'Fornecedores', 'Marcações da semana', 'Orçamentos de verão', 'Clientes novos']) {
  await api('POST', '/app/api/assistant/threads', { title }, owner);
}

const waId = `3519100${String(Date.now()).slice(-5)}`;
const webhook = JSON.stringify({
  object: 'whatsapp_business_account',
  entry: [{ id: 'waba-e2e', changes: [{ field: 'messages', value: {
    messaging_product: 'whatsapp',
    metadata: { display_phone_number: '351210000000', phone_number_id: phoneNumberId },
    contacts: [{ profile: { name: 'Maria Lopes' }, wa_id: waId }],
    messages: [{ from: waId, id: `wamid.e2e.${run}`, timestamp: String(Math.floor(Date.now() / 1000)), type: 'text', text: { body: 'Bom dia, já tem o orçamento da cozinha?' } }],
  } }] }],
});
await fetch(`${APP}/webhook`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Hub-Signature-256': `sha256=${crypto.createHmac('sha256', WA_SECRET).update(webhook).digest('hex')}` },
  body: webhook,
});
await api('POST', '/__reset', {}, undefined, MODEL);
console.log(`Seeded: ${ana.number}, ${bruno.number}, ${overdue.number} (overdue), ${quote.number}, a WhatsApp chat from Maria Lopes`);

// ── The browser ──

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: !HEADFUL,
  slowMo: SLOW,
  args: ['--no-sandbox', '--window-size=1400,940', '--window-position=0,0'],
  defaultViewport: HEADFUL ? null : { width: 1400, height: 900 },
});
await browser.defaultBrowserContext().overridePermissions(APP, ['clipboard-read', 'clipboard-write', 'clipboard-sanitized-write']);
const pageErrors = [];

async function open(token, theme = 'light') {
  const page = await browser.newPage();
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.evaluateOnNewDocument((t, th) => {
    localStorage.setItem('dashboardToken', t);
    localStorage.setItem('uiLocale', 'pt-PT');
    localStorage.setItem('uiTheme', th);
  }, token, theme);
  await page.goto(`${APP}/app/#ai-assistant`, { waitUntil: 'networkidle0' });
  await page.waitForSelector('.assistant');
  return page;
}

const shot = (page, name) => page.screenshot({ path: path.join(OUT, `${name}.png`) });
const text = (page, selector) => page.$eval(selector, el => el.innerText).catch(() => '');
const settle = page => page.waitForFunction(() => !document.querySelector('.chat__typing') && !document.querySelector('#assistant-input')?.disabled, { timeout: 60000 });
async function ask(page, message) {
  await page.click('#assistant-input');
  await page.type('#assistant-input', message, { delay: HEADFUL ? 12 : 0 });
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => !!document.querySelector('.chat__typing') || document.querySelectorAll('.chat__msg--user').length > 0);
  await settle(page);
}
const lastCard = page => page.$$eval('.assistant__action', cards => cards.at(-1)?.innerText || '');
const lastCardState = page => page.$$eval('.assistant__action', cards => (cards.at(-1)?.className.match(/assistant__action--(\w+)/) || [])[1] || '');
async function clickText(page, selector, label) {
  const handles = await page.$$(selector);
  for (const h of handles) {
    if ((await h.evaluate(el => el.innerText.trim())) === label) return h.click();
  }
  throw new Error(`No ${selector} labelled “${label}”`);
}
const toastSays = (page, expected) => page.waitForFunction(
  want => !document.querySelector('#toast')?.hidden && document.querySelector('#toast')?.innerText.includes(want), { timeout: 8000 }, expected,
).then(() => true, () => false);

console.log('\nAs the admin');
const page = await open(owner);

await check('an empty conversation opens on starter questions for what the company has', async () => {
  await page.waitForSelector('.assistant__welcome');
  const starters = await page.$$eval('.assistant__starters .btn', b => b.map(x => x.innerText));
  expect(starters.length >= 5, `only ${starters.length} starters`);
  expect(starters.includes('Que faturas estão em atraso?'), `starters: ${starters.join(' | ')}`);
  await shot(page, '01-welcome');
});

await check('a starter is answered from the company’s data as a table that says where it looked', async () => {
  await clickText(page, '.assistant__starters .btn', 'Que faturas estão em atraso?');
  await settle(page);
  const answer = await text(page, '.chat__msg--rich');
  expect(answer.includes(overdue.number), `the overdue invoice ${overdue.number} isn't in: ${answer}`);
  expect(await page.$('.chat__msg--rich .chat__table table'), 'no table');
  expect((await text(page, '.assistant__meta')).includes('Consultado: Faturas'), 'no “Consultado: Faturas”');
  expect((await text(page, '.assistant__title')) === 'Que faturas estão em atraso?', 'the first message didn’t name the conversation');
  await shot(page, '02-answer-table');
});

await check('an answer can be copied', async () => {
  await page.click('.assistant__copy');
  expect(await toastSays(page, 'Copiado'), 'no “Copiado” toast');
});

await check('a new client waits on a card with its details, and is made once confirmed', async () => {
  await ask(page, 'Cria um cliente chamado Rita Gomes com telefone +351 914 555 001 nif 123456789 morada Rua do Sol 5 em Lisboa');
  expect(await lastCardState(page) === 'pending', `card is ${await lastCardState(page)}`);
  const card = await lastCard(page);
  for (const part of ['Criar cliente Rita Gomes', 'NIF: 123456789', 'Morada: Rua do Sol 5', 'Localidade: Lisboa', 'A aguardar aprovação']) {
    expect(card.includes(part), `card lacks “${part}”: ${card}`);
  }
  expect((await text(page, '.assistant__thread.is-active')).includes('1'), 'the conversation doesn’t show its waiting change');
  await shot(page, '03-card-pending');
  await page.click('[data-assistant-confirm]');
  await settle(page);
  expect(await lastCardState(page) === 'confirmed', `card is ${await lastCardState(page)}`);
  await shot(page, '04-card-confirmed');
});

await check('a confirmed client opens from its card', async () => {
  await clickText(page, '[data-assistant-open]', 'Abrir cliente');
  await page.waitForFunction(() => !document.querySelector('#drawer')?.hidden && document.querySelector('#drawer-title')?.innerText === 'Rita Gomes', { timeout: 10000 });
  await shot(page, '05-client-opened');
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => document.querySelector('#drawer')?.hidden);
});

await check('a proposed quote names its client and total, and can be declined', async () => {
  await ask(page, 'Faz um orçamento para a cliente Rita Gomes com Pintura por 450€');
  const card = await lastCard(page);
  expect(card.includes('Criar um orçamento para Rita Gomes (CLT-'), `title: ${card}`);
  expect(card.includes('450,00'), `no total: ${card}`);
  await page.click('[data-assistant-cancel]');
  await settle(page);
  expect(await lastCardState(page) === 'cancelled', `card is ${await lastCardState(page)}`);
});

await check('a card left waiting expires when the conversation moves on', async () => {
  await ask(page, 'Faz um orçamento para a cliente Rita Gomes com Pintura por 300€');
  expect(await lastCardState(page) === 'pending', 'no pending card');
  await ask(page, 'Espera, afinal não.');
  const expired = await page.$$eval('.assistant__action--expired', cards => cards.map(c => c.innerText));
  expect(expired.length === 1 && expired[0].includes('nada mudou'), `expired cards: ${expired.length}`);
  expect(!(await page.$('[data-assistant-confirm]')), 'an expired card can still be confirmed');
  await shot(page, '06-card-expired');
});

await check('a quote becomes its invoice, with the invoice and its PDF a click away', async () => {
  await ask(page, `Fatura o orçamento ${quote.number} com vencimento ${day(30)}`);
  const card = await lastCard(page);
  expect(card.includes(`Faturar o orçamento ${quote.number}`) && card.includes('Bruno Sousa') && card.includes('950,00'), `card: ${card}`);
  await page.click('[data-assistant-confirm]');
  await settle(page);
  expect(await lastCardState(page) === 'confirmed', `card is ${await lastCardState(page)}`);
  const buttons = await page.$$eval('.assistant__action--confirmed', cards => [...cards.at(-1).querySelectorAll('.btn, .pdf')].map(x => x.innerText.trim()));
  expect(buttons.some(b => b === 'Abrir fatura'), `buttons: ${buttons.join(' | ')}`);
  expect(buttons.some(b => b.includes('PDF')), `no PDF button: ${buttons.join(' | ')}`);
  await shot(page, '07-quote-invoiced');
});

await check('when the model is down the turn says so and can be retried', async () => {
  await api('POST', '/__fail', { count: 3 }, undefined, MODEL);
  await ask(page, 'Quantos orçamentos tenho?');
  expect((await text(page, '.assistant__failed')).includes('não conseguiu responder'), 'no error message');
  await shot(page, '08-model-down');
  await api('POST', '/__reset', {}, undefined, MODEL);
  await page.click('[data-assistant-retry]');
  await settle(page);
  expect(!(await page.$('.assistant__failed')), 'the error stayed after retrying');
  expect((await page.$$eval('.chat__msg--rich', m => m.at(-1).innerText)).includes(quote.number), 'the retried answer lacks the quote');
});

await check('a reply to a customer is drafted on a card, and says why it failed without a real WhatsApp number', async () => {
  await ask(page, 'Responde à Maria: Bom dia Maria, o orçamento da cozinha segue hoje.');
  const card = await lastCard(page);
  expect(card.includes('Responder a Maria Lopes') && card.includes('o orçamento da cozinha segue hoje'), `card: ${card}`);
  await shot(page, '09-reply-card');
  await page.click('[data-assistant-confirm]');
  await settle(page);
  expect(['failed', 'confirmed'].includes(await lastCardState(page)), `card is ${await lastCardState(page)}`);
});

await check('conversations can be searched, renamed and deleted', async () => {
  await page.type('#assistant-search', 'cobran');
  const listed = await page.$$eval('.assistant__thread strong', t => t.map(x => x.innerText));
  expect(listed.length === 1 && listed[0] === 'Cobranças de setembro', `listed: ${listed.join(' | ')}`);
  await page.click('.assistant__thread');
  await page.waitForFunction(() => document.querySelector('.assistant__title')?.innerText === 'Cobranças de setembro');
  await page.click('#assistant-rename');
  await page.waitForSelector('#as-title');
  await page.$eval('#as-title', el => { el.value = ''; });
  await page.type('#as-title', 'Cobranças de outubro');
  await page.click('#drawer button[type=submit]');
  await page.waitForFunction(() => document.querySelector('.assistant__title')?.innerText === 'Cobranças de outubro');
  await page.click('#assistant-delete');
  await page.waitForFunction(() => !document.querySelector('#confirm')?.hidden);
  await page.click('#confirm-ok');
  await page.waitForFunction(() => ![...document.querySelectorAll('.assistant__thread strong')].some(t => t.innerText === 'Cobranças de outubro'));
  expect(await page.$eval('#assistant-search', el => el.value).catch(() => null) === 'cobran', 'the search box went away while still filtering');
  await page.click('#assistant-search', { clickCount: 3 });
  await page.keyboard.press('Backspace');
  await page.waitForFunction(() => document.querySelectorAll('.assistant__thread').length >= 4);
});

await check('an admin sets the instructions, style, language, answers-only and areas, and every turn follows them', async () => {
  await page.click('#assistant-settings');
  await page.waitForSelector('#as-instructions');
  await page.type('#as-instructions', 'As faturas vencem 30 dias depois de emitidas. Indique sempre o número do cliente.');
  await page.select('#as-style', 'CONCISE');
  await page.select('#as-language', 'en');
  await page.click('#as-changes');
  await page.click('[data-area="payments"]');
  await shot(page, '10-settings');
  await page.click('#drawer button[type=submit]');
  expect(await toastSays(page, 'guardadas'), 'no saved toast');
  await page.waitForFunction(() => [...document.querySelectorAll('.assistant__head .pill')].some(p => p.innerText === 'Só respostas'));
  await ask(page, 'Cria um cliente chamado Bloqueado com telefone +351 914 000 000 nif 223456789 morada Rua X em Faro');
  expect(!(await page.$('.assistant__action--pending')), 'a card appeared with changes off');
  const last = await api('GET', '/__last', undefined, undefined, MODEL);
  const tools = (last.tools || []).map(t => t.function.name);
  const system = last.messages.filter(m => m.role === 'system').map(m => m.content).join('\n');
  expect(!tools.includes('create_client') && !tools.some(t => t.includes('payment')), `tools offered: ${tools.join(', ')}`);
  for (const part of ['As faturas vencem 30 dias', 'Reply style: concise', 'always reply in English', 'Changes are switched off', 'Not available to you here: Payments']) {
    expect(system.includes(part), `the prompt lacks “${part}”`);
  }
  await shot(page, '11-answers-only');
  await api('PUT', '/app/api/assistant/settings', { instructions: '', replyStyle: 'BALANCED', language: null, allowChanges: true, disabledModules: [] }, owner);
});

await check('in the dark theme the page stays readable', async () => {
  const dark = await open(owner, 'dark');
  await dark.waitForSelector('.assistant__thread');
  await dark.click('.assistant__thread:nth-child(2)');
  await settle(dark);
  await shot(dark, '12-dark');
  await dark.close();
});

await check('on a phone nothing scrolls sideways', async () => {
  const phone = await browser.newPage();
  phone.on('pageerror', e => pageErrors.push(e.message));
  await phone.setViewport({ width: 390, height: 844, isMobile: true, hasTouch: true });
  await phone.evaluateOnNewDocument(t => { localStorage.setItem('dashboardToken', t); localStorage.setItem('uiLocale', 'pt-PT'); localStorage.setItem('uiTheme', 'light'); }, owner);
  await phone.goto(`${APP}/app/#ai-assistant`, { waitUntil: 'networkidle0' });
  await phone.waitForSelector('.assistant');
  const wide = await phone.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(wide <= 1, `the page is ${wide}px wider than the phone`);
  await phone.screenshot({ path: path.join(OUT, '13-phone.png'), fullPage: true });
  await phone.close();
});

console.log('\nAs a member');
await check('a member sees only their own conversations, and the settings read-only', async () => {
  const mine = await open(member);
  expect((await mine.$$('.assistant__thread')).length === 0, 'the member sees the admin’s conversations');
  await mine.click('#assistant-settings');
  await mine.waitForSelector('#as-instructions');
  expect(await mine.$eval('#as-instructions', el => el.disabled), 'the instructions can be edited');
  expect(!(await mine.$('#drawer button[type=submit]')), 'a member can save');
  expect((await text(mine, '#drawer')).includes('Só os administradores'), 'no hint for members');
  await shot(mine, '14-member-settings');
  await mine.close();
});

await check('no script errors on the page', async () => {
  expect(pageErrors.length === 0, pageErrors.join(' | '));
});

await browser.close();
const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed. Screenshots in ${OUT}`);
process.exit(failed.length ? 1 : 0);
