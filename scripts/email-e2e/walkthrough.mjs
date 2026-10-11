/* A repeatable walkthrough of the Email page in a real browser, against a running app whose model and Gmail are
   fake-services.py. It makes its own company through the backoffice (with the `email` module), seeds clients, an
   invoice and a quote, connects a Gmail account by writing it to Mongo with tokens sealed by the app's key, emails
   the quote from the dashboard, and drops five emails in the fake inbox for the app's real inbox sync to pick up.
   Then it uses the page as an admin and as a member, checking what it sees at each step. Screenshots go to out/.

     python3 fake-services.py 8099 &
     OPENROUTER_BASE_URL=http://127.0.0.1:8099/api/v1 GMAIL_API_URL=http://127.0.0.1:8099/gmail/v1 \
       GMAIL_INBOX_ENABLED=true GMAIL_SYNC_SECONDS=15 GOOGLE_OAUTH_CLIENT_ID=e2e GOOGLE_OAUTH_CLIENT_SECRET=e2e \
       GOOGLE_OAUTH_REDIRECT=http://127.0.0.1:8080/integrations/google/callback INTEGRATIONS_ENCRYPTION_KEY=<base64 32 bytes> ./gradlew run
     npm install && INTEGRATIONS_ENCRYPTION_KEY=<the same> node walkthrough.mjs [--headful] [--slow=60]

   Environment: APP_URL (http://127.0.0.1:8080), FAKE_URL (http://127.0.0.1:8099), MONGO_URI (mongodb://127.0.0.1:27017),
   MONGO_DB (wabot), ADMIN_PASSWORD (local-dev), CHROME (/usr/local/bin/google-chrome). */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { MongoClient, ObjectId } from 'mongodb';
import puppeteer from 'puppeteer-core';

const APP = process.env.APP_URL || 'http://127.0.0.1:8080';
const FAKE = process.env.FAKE_URL || 'http://127.0.0.1:8099';
const MONGO_URI = process.env.MONGO_URI || 'mongodb://127.0.0.1:27017';
const MONGO_DB = process.env.MONGO_DB || 'wabot';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'local-dev';
const KEY = process.env.INTEGRATIONS_ENCRYPTION_KEY || '';
const CHROME = process.env.CHROME || '/usr/local/bin/google-chrome';
const HEADFUL = process.argv.includes('--headful');
const SLOW = Number((process.argv.find(a => a.startsWith('--slow=')) || '--slow=0').split('=')[1]);
const OUT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'out');
const PASSWORD = 'Walkthrough-2026!';
fs.mkdirSync(OUT, { recursive: true });
if (!KEY) throw new Error('Set INTEGRATIONS_ENCRYPTION_KEY to the key the app runs with');

const results = [];
const linger = ms => (HEADFUL ? new Promise(resolve => setTimeout(resolve, ms)) : Promise.resolve());
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function check(name, fn) {
  try {
    await fn();
    await linger(2500);
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

/** The app's TokenCipher: `v1:<keyId>:<base64(iv + ciphertext + tag)>`, the header as associated data. */
function seal(plain) {
  const key = Buffer.from(KEY.split(',')[0].trim(), 'base64');
  const keyId = crypto.createHash('sha256').update(key).digest().subarray(0, 4).toString('hex');
  const header = `v1:${keyId}`;
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(header));
  const body = Buffer.concat([iv, cipher.update(plain, 'utf8'), cipher.final(), cipher.getAuthTag()]);
  return `${header}:${body.toString('base64')}`;
}

const day = offset => new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10);

// ── A company of its own ──

const run = Date.now().toString(36);
const slug = `email-e2e-${run}`;
const account = `obras-${run}@obras-lopes.test`;
const ownerEmail = `owner-${run}@email-e2e.test`;
const memberEmail = `member-${run}@email-e2e.test`;

console.log(`Company ${slug}`);
const backoffice = (await api('POST', '/admin/auth/login', { password: ADMIN_PASSWORD })).token;
const modules = ['clients', 'quotes', 'invoices', 'payments', 'suppliers', 'agents', 'settings', 'conversations', 'contacts', 'email'];
await api('POST', '/admin/api/tenants', { name: 'Obras Lopes', slug, locale: 'pt-PT', timezone: 'Europe/Lisbon', enabledModules: modules, channels: [] }, backoffice);
for (const [email, role] of [[ownerEmail, 'TENANT_ADMIN'], [memberEmail, 'TENANT_MEMBER']]) {
  await api('POST', `/admin/api/tenants/${slug}/dashboard-users`, { email, password: PASSWORD, role }, backoffice);
}
const login = async email => (await api('POST', '/app/auth/login', { email, password: PASSWORD })).token;
const owner = await login(ownerEmail);
const member = await login(memberEmail);
const me = await api('GET', '/app/api/me', undefined, owner);

const digits = run.replace(/\D/g, '7').padEnd(3, '7').slice(-3);
const joao = await api('POST', '/app/api/crm/clients', { name: 'João Costa', phone: `+351 913 ${digits} 101`, email: `joao-${run}@costa.test`, taxId: '213456789', address: 'Rua do Sol 5' }, owner);
const ana = await api('POST', '/app/api/crm/clients', { name: 'Ana Lima', phone: `+351 914 ${digits} 202`, email: `ana-${run}@lima.test`, taxId: '223456780', address: 'Avenida Central 20' }, owner);
const invoice = await api('POST', '/app/api/crm/invoices', { clientId: joao.id, items: [{ description: 'Pintura exterior', quantity: 1, unitPriceEur: 615 }], dueDate: day(5) }, owner);
const quote = await api('POST', '/app/api/crm/quotes', { clientId: ana.id, items: [{ description: 'Remodelação da cozinha', quantity: 1, unitPriceEur: 4200 }], validUntil: day(30) }, owner);

const mongo = new MongoClient(MONGO_URI);
await mongo.connect();
const db = mongo.db(MONGO_DB);
const now = new Date();
await db.collection('integration_connections').insertOne({
  tenantId: new ObjectId(me.tenant.id), provider: 'google', accountEmail: account,
  scopes: ['openid', 'email', 'https://www.googleapis.com/auth/gmail.send', 'https://www.googleapis.com/auth/gmail.readonly', 'https://www.googleapis.com/auth/gmail.modify'],
  status: 'ACTIVE', accessToken: seal('access-e2e'), refreshToken: seal('refresh-e2e'), accessTokenExpiresAt: new Date(Date.now() + 365 * 86400000),
  connectedByUserId: me.user.id, connectedByEmail: ownerEmail, isDefault: true,
  settings: { signature: 'Obras Lopes · 210 000 000', inboxSync: true },
  inbox: { enabledAt: new Date(Date.now() - 3 * 3600000) }, createdAt: now, updatedAt: now,
});
await api('POST', '/__gmail/reset', { emailAddress: account }, undefined, FAKE);

const sentQuote = await api('POST', '/app/api/email/send', {
  type: 'quote', id: quote.id, to: ana.email, subject: `Orçamento ${quote.number} — Obras Lopes`,
  text: 'Olá Ana,\n\nSegue o orçamento da remodelação da cozinha.\n\nCumprimentos', requestId: `e2e-quote-${run}`,
}, owner);
const quoteThread = (await api('GET', '/__gmail/sent', undefined, undefined, FAKE)).at(-1).threadId;
const mail = [
  { from: 'Loja das Tintas <news@loja-tintas.test>', subject: 'Promoções de outono', text: 'Até 30% em tintas de interior esta semana.', minutesAgo: 70, labels: ['INBOX', 'UNREAD', 'CATEGORY_UPDATES'], headers: { 'List-Unsubscribe': '<mailto:sair@loja-tintas.test>' } },
  { from: 'Tintas & Cores <faturacao@tintas-cores.test>', subject: 'Fatura FT 2026/123', text: 'Bom dia,\n\nSegue em anexo a fatura FT 2026/123 no valor de 230,00 €, com vencimento a 30/10/2026.\n\nTintas & Cores, Lda\nTel. 213 456 789\nNIF 501964843', minutesAgo: 50, attachments: ['FT-2026-123.pdf'] },
  { from: `João Costa <${joao.email}>`, subject: `Pagamento da fatura ${invoice.number}`, text: `Olá,\n\nJá fiz a transferência da fatura ${invoice.number}, 615,00 €. Segue o comprovativo.\n\nJoão Costa`, minutesAgo: 40, attachments: ['comprovativo.pdf'] },
  { from: 'Maria Silva <maria.silva@correio.test>', subject: 'Pedido de orçamento — pintura', text: 'Bom dia,\n\nGostaria de pedir um orçamento para pintar a sala e o corredor, cerca de 40 m2. Podem passar cá para ver na próxima semana?\n\nObrigada,\nMaria Silva\nTelemóvel: 912 345 678', minutesAgo: 5 },
  // Her answer to the quote the dashboard just emailed, so it comes after it.
  { from: `Ana Lima <${ana.email}>`, subject: `Re: Orçamento ${quote.number} — Obras Lopes`, text: `Aceito o orçamento ${quote.number}. Quando podem começar?\n\nAna`, minutesAgo: 0, threadId: quoteThread },
];
for (const m of mail) await api('POST', '/__gmail/receive', m, undefined, FAKE);
console.log(`Seeded ${joao.number}, ${ana.number}, ${invoice.number}, ${quote.number} (emailed: ${sentQuote.messageId}), ${mail.length} emails in the inbox`);

let threads = [];
for (let i = 0; i < 40 && threads.length < 5; i++) {
  threads = await api('GET', '/app/api/email/inbox/threads', undefined, owner);
  if (threads.length < 5) await sleep(3000);
}
console.log(`The inbox sync kept ${threads.length} threads`);
const unreadAtStart = (await api('GET', '/app/api/email/inbox', undefined, owner)).unread;

// ── The browser ──

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: !HEADFUL,
  slowMo: SLOW,
  args: ['--no-sandbox', HEADFUL ? '--start-maximized' : '--window-size=1440,940', '--window-position=0,0'],
  defaultViewport: HEADFUL ? null : { width: 1440, height: 900 },
});
const pageErrors = [];

async function open(token, { theme = 'light', locale = 'pt-PT', viewport } = {}) {
  const page = await browser.newPage();
  if (viewport) await page.setViewport(viewport);
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.evaluateOnNewDocument((t, th, loc) => {
    localStorage.setItem('dashboardToken', t);
    localStorage.setItem('uiLocale', loc);
    localStorage.setItem('uiTheme', th);
  }, token, theme, locale);
  await page.goto(`${APP}/app/#email`, { waitUntil: 'networkidle0' });
  await page.waitForSelector('.email-inbox');
  return page;
}
async function shot(page, name) {
  await page.waitForFunction(() => document.getAnimations().every(a => a.playState !== 'running' || a.effect?.getTiming().iterations === Infinity), { timeout: 3000 }).catch(() => {});
  await page.screenshot({ path: path.join(OUT, `${name}.png`) });
  await linger(1800);
}
const text = (page, selector) => page.$eval(selector, el => el.innerText).catch(() => '');
// The drawer slides in: clicks land where its fields end up only once it settled.
const drawerOpen = page => page.waitForSelector('#drawer:not([hidden]) form', { visible: true }).then(() => sleep(400));
const drawerClosed = page => page.waitForFunction(() => document.querySelector('#drawer')?.hidden !== false, { timeout: 10000 });
const toastSays = (page, expected) => page.waitForFunction(want => !document.querySelector('#toast')?.hidden && document.querySelector('#toast')?.innerText.includes(want), { timeout: 8000 }, expected).then(() => true, () => false);
async function openRow(page, name) {
  const rows = await page.$$('[data-em-thread]');
  for (const row of rows) {
    if ((await row.$eval('.inbox-row__name', el => el.innerText.trim())) === name) {
      await row.click();
      await page.waitForFunction(n => document.querySelector('#em-thread .thread-head__sub')?.innerText.includes(n) || document.querySelector('[data-em-thread].is-active .inbox-row__name')?.innerText === n, { timeout: 10000 }, name);
      await page.waitForSelector('#em-log');
      return;
    }
  }
  throw new Error(`No row named “${name}”`);
}
const ready = page => page.waitForFunction(() => !document.querySelector('.email-actions__reading'), { timeout: 30000 });
const actionTitles = page => page.$$eval('.email-action__title', els => els.map(e => e.innerText));
async function field(page, selector, value) {
  await page.waitForSelector(selector, { visible: true });
  await page.$eval(selector, el => {
    el.focus();
    el.value = '';
    el.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await page.keyboard.type(value, { delay: HEADFUL ? 25 : 0 });
}

console.log('\nAs the admin');
const page = await open(owner);

await check('Email sits in the Inbox group, with the unread threads counted', async () => {
  expect(unreadAtStart === 4, `four people's threads wait to be read, the newsletter doesn't count: ${unreadAtStart}`);
  const nav = await page.$eval('.nav__item[data-tab="email"]', el => el.innerText);
  expect(nav.includes('Email'), `nav item: ${nav}`);
  expect(/\b[34]\b/.test(nav), `the count (the newest thread opens, and is read, on a wide screen): ${nav}`);
  const group = await page.$eval('.nav__item[data-tab="email"]', el => el.closest('.nav__group').querySelector('.nav__group-label')?.innerText);
  expect(group === 'Inbox', `group: ${group}`);
});

await check('the list shows one row per thread, the quote thread with its reply, the newsletter marked automatic', async () => {
  const names = await page.$$eval('[data-em-thread] .inbox-row__name', els => els.map(e => e.innerText));
  expect(names.length === 5, `rows: ${names.join(', ')}`);
  expect(names.join(', ') === 'Ana Lima, Maria Silva, João Costa, Tintas & Cores, Loja das Tintas', `newest first: ${names.join(', ')}`);
  expect(names.includes('Ana Lima') && names.includes('João Costa'), `client names: ${names.join(', ')}`);
  expect(await page.$('[data-em-thread].is-automated'), 'no automatic row');
  await shot(page, '01-list');
});

await check('a stranger asking for a quote: AI reads it and the page offers to add her and prepare the quote', async () => {
  await openRow(page, 'Maria Silva');
  await page.waitForSelector('.email-actions__summary', { timeout: 30000 });
  await ready(page);
  const summary = await text(page, '.email-actions__summary');
  expect(summary.includes('Maria Silva'), `summary: ${summary}`);
  expect((await text(page, '.email-actions__head')).includes('Pedido de orçamento'), 'no intent pill');
  const titles = await actionTitles(page);
  expect(titles[0] === 'Adicionar Maria Silva como cliente', `first action: ${titles.join(' | ')}`);
  expect(titles.some(t => t.startsWith('Preparar um orçamento')), `actions: ${titles.join(' | ')}`);
  expect((await text(page, '.email-action')).includes('912 345 678'), 'the phone from her signature is offered');
  await shot(page, '02-stranger-quote-request');
});

await check('adding her as a client opens the client form filled from the email, and files her mail under her', async () => {
  await page.click('[data-em-act="client.create"]');
  await drawerOpen(page);
  expect((await page.$eval('#cf-name', el => el.value)) === 'Maria Silva', 'name not filled');
  expect((await page.$eval('#cf-phone', el => el.value)).includes('912 345 678'), 'phone not filled');
  expect((await page.$eval('#cf-email', el => el.value)) === 'maria.silva@correio.test', 'email not filled');
  // The company asks for a NIF and an address, which her email doesn't give: the person types them.
  await field(page, '#cf-tax', '123456789');
  await field(page, '#cf-address', 'Rua das Flores 12, Lisboa');
  await shot(page, '03-client-form');
  await page.click('#drawer-body button[type=submit]');
  await drawerClosed(page);
  await page.waitForFunction(() => [...document.querySelectorAll('.email-action.is-done .email-action__title')].some(e => e.innerText.startsWith('Cliente adicionado')), { timeout: 10000 });
  expect((await text(page, '.thread-head__tools')).includes('Maria Silva'), 'the thread doesn’t show its client');
});

await check('the quote form starts with her client and the lines she asked for', async () => {
  await page.click('[data-em-act="quote.create"]');
  await drawerOpen(page);
  const client = await page.$eval('#f-client', el => el.selectedOptions[0]?.innerText);
  expect(client === 'Maria Silva', `client: ${client}`);
  const line = await page.$eval('.line [data-k="description"]', el => el.value);
  expect(line.toLowerCase().includes('pintar a sala'), `line: ${line}`);
  expect((await page.$eval('.line [data-k="quantity"]', el => el.value)) === '40', 'quantity not filled');
  await field(page, '.line [data-k="unitPriceEur"]', '9.5');
  await shot(page, '04-quote-form');
  await page.click('#drawer-body button[type=submit]');
  await drawerClosed(page);
  await page.waitForFunction(() => [...document.querySelectorAll('.email-action.is-done .email-action__title')].some(e => e.innerText.includes('ORC-002')), { timeout: 10000 });
  await shot(page, '05-actions-done');
});

await check('a client who paid: the invoice they name can be marked paid from the email', async () => {
  await openRow(page, 'João Costa');
  await page.waitForSelector('.email-actions__summary', { timeout: 30000 });
  await ready(page);
  const titles = await actionTitles(page);
  expect(titles[0] === `Marcar a fatura ${invoice.number} como paga`, `first action: ${titles.join(' | ')}`);
  expect((await text(page, '.email-actions__docs')).includes(invoice.number), 'the invoice isn’t listed as mentioned');
  await page.click('[data-em-act="invoice.paid"]');
  await page.waitForSelector('#confirm:not([hidden]) #confirm-ok', { visible: true });
  await page.click('#confirm-ok');
  await page.waitForFunction(() => document.querySelector('.email-action.is-done'), { timeout: 10000 });
  const paid = await api('GET', `/app/api/crm/invoices/${invoice.id}`, undefined, owner);
  expect(paid.status === 'PAID', `invoice is ${paid.status}`);
});

await check('a supplier’s bill: the supplier and the payment are filled from the email', async () => {
  await openRow(page, 'Tintas & Cores');
  await page.waitForSelector('.email-actions__summary', { timeout: 30000 });
  await ready(page);
  const titles = await actionTitles(page);
  expect(titles[0].startsWith('Registar a fatura de Tintas & Cores'), `first action: ${titles.join(' | ')}`);
  expect(!titles.some(t => t.startsWith('Adicionar')), 'a supplier isn’t offered as a client');
  await page.click('[data-em-act="bill.create"]');
  await drawerOpen(page);
  expect((await page.$eval('#sf-name', el => el.value)).startsWith('Tintas & Cores'), 'supplier name not filled');
  expect((await page.$eval('#sf-phone', el => el.value)).includes('213 456 789'), 'supplier phone not filled');
  await page.click('#drawer-body button[type=submit]');
  await page.waitForSelector('#drawer:not([hidden]) #p-due', { visible: true });
  expect((await page.$eval('#p-due', el => el.value)) === '2026-10-30', 'due date not filled');
  expect((await page.$eval('.line [data-k="unitPriceEur"]', el => el.value)) === '230', 'amount not filled');
  await shot(page, '06-bill-form');
  await page.click('#drawer-body button[type=submit]');
  await drawerClosed(page);
  await page.waitForFunction(() => document.querySelector('.email-action.is-done'), { timeout: 10000 });
  const payments = await api('GET', '/app/api/crm/payments', undefined, owner);
  expect(payments.some(p => Math.round(p.totalEur) === 230), 'no 230 € payment');
});

await check('a reply accepting a quote: the quote is marked accepted, and the suggested answer goes out in the thread', async () => {
  await openRow(page, 'Ana Lima');
  await page.waitForSelector('.email-actions__summary', { timeout: 30000 });
  await ready(page);
  expect((await page.$$('.email-msg')).length === 2, 'the thread shows the quote email and her reply');
  await page.click('[data-em-act="quote.accept"]');
  await page.waitForSelector('#confirm:not([hidden]) #confirm-ok', { visible: true });
  await page.click('#confirm-ok');
  await page.waitForFunction(() => document.querySelector('.email-action.is-done'), { timeout: 10000 });
  const accepted = await api('GET', `/app/api/crm/quotes/${quote.id}`, undefined, owner);
  expect(accepted.status === 'ACEITO', `quote is ${accepted.status}`);
  await page.click('[data-em-draft]');
  expect((await page.$eval('#em-reply', el => el.value)).includes('Olá Ana'), 'the suggested reply wasn’t added');
  await shot(page, '07-reply-draft');
  await page.click('#em-send');
  expect(await toastSays(page, 'Resposta enviada'), 'no “Resposta enviada” toast');
  await page.waitForFunction(() => document.querySelectorAll('.email-msg').length === 3, { timeout: 10000 });
  const sent = (await api('GET', '/__gmail/sent', undefined, undefined, FAKE)).at(-1);
  expect(sent.threadId === quoteThread, `sent in thread ${sent.threadId}, not ${quoteThread}`);
  expect(sent.to.includes(ana.email) && sent.subject.startsWith('Re: '), `sent to ${sent.to}: ${sent.subject}`);
  expect(sent.inReplyTo.includes('@mail.example.test'), `In-Reply-To: ${sent.inReplyTo}`);
  await shot(page, '08-replied');
});

await check('the newsletter gets no person-to-person actions', async () => {
  await openRow(page, 'Loja das Tintas');
  expect((await text(page, '.email-msg')).includes('Automático'), 'not marked automatic');
  expect(!(await page.$('[data-em-act]')), 'actions offered on a newsletter');
});

await check('reading everything empties the unread count, and a thread can be marked unread again', async () => {
  await page.waitForFunction(() => !document.querySelector('.nav__item[data-tab="email"] .nav__count'), { timeout: 10000 });
  await openRow(page, 'Maria Silva');
  await page.click('[data-em-unread]');
  expect(await toastSays(page, 'Marcado como não lido'), 'no toast');
  await page.waitForFunction(() => document.querySelector('.nav__item[data-tab="email"] .nav__count')?.innerText === '1', { timeout: 10000 });
});

await check('filters narrow the list: clients, new senders, unread', async () => {
  // The chips are redrawn with the list, so they're clicked where they are now rather than where they were.
  // A chip turns on once its list has arrived and is drawn.
  const filter = async (name, count) => {
    await page.$eval(`[data-em-filter="${name}"]`, el => el.click());
    await page.waitForFunction((f, n) => document.querySelector(`[data-em-filter="${f}"]`)?.classList.contains('is-on')
      && document.querySelectorAll('[data-em-thread]').length === n, { timeout: 10000 }, name, count);
  };
  await filter('clients', 3);
  await filter('new', 1);
  expect((await text(page, '[data-em-thread] .inbox-row__name')).includes('Tintas'), 'the supplier is the only sender not on file');
  await shot(page, '09-filter-new-senders');
  await filter('unread', 1);
  expect((await text(page, '[data-em-thread] .inbox-row__name')) === 'Maria Silva', 'Maria’s thread was marked unread');
  await filter('all', 5);
});

await check('dark theme, in English', async () => {
  const dark = await open(owner, { theme: 'dark', locale: 'en' });
  await openRow(dark, 'João Costa');
  await dark.waitForSelector('.email-action.is-done');
  expect((await text(dark, '.email-actions__title')) === 'What to do', 'not in English');
  await shot(dark, '10-dark-en');
  await dark.close();
});

await check('on a phone the list and the thread take the screen in turn, with nothing scrolling sideways', async () => {
  const phone = await open(owner, { viewport: { width: 375, height: 812, isMobile: true, hasTouch: true } });
  await phone.waitForSelector('[data-em-thread]');
  await shot(phone, '11-phone-list');
  await openRow(phone, 'Maria Silva');
  await phone.waitForSelector('#em-log');
  const wide = await phone.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(wide <= 0, `the page is ${wide}px wider than the screen`);
  await shot(phone, '12-phone-thread');
  await phone.close();
});

console.log('\nAs a member');
await check('a member reads and acts on email but isn’t offered to connect accounts', async () => {
  const view = await open(member);
  const names = await view.$$eval('[data-em-thread] .inbox-row__name', els => els.map(e => e.innerText));
  expect(names.length === 5, `member sees ${names.length} rows`);
  expect(!(await view.$('[data-em-setup]')), 'a member is offered a setup button');
  await view.close();
});

await check('no page errors', async () => {
  expect(!pageErrors.length, pageErrors.join(' | '));
});

await browser.close();
await mongo.close();
const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed. Screenshots in ${OUT}`);
process.exit(failed.length ? 1 : 0);
