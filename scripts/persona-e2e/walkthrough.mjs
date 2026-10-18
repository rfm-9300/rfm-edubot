// Browser walkthrough of the Persona page against a running app, checking each step.
//
// It creates its own company and users through the backoffice API, then drives Chrome through
// settings (tested unsaved), notes and PDF/Word/Markdown uploads, the test chat (handoff and a
// prompt-injection try), hand edits and the preview, history and restore, removing a source and
// rebuilding, a signed WhatsApp message that hands a chat to the team (bell + inbox), Instagram
// menus (built with keyword tokens, mapped, caught when incomplete, tried unsaved in the test chat
// and with Try it, saved, team-only; a keyword clash, the message checker, reordering, duplicating
// and discarding; a signed Instagram message the menu answers, counted in its usage), dark theme,
// a narrow window and a phone, and a member's read-only view.
//
// Run the app with OPENROUTER_BASE_URL pointing at fake-openrouter.py (its replies are what this
// expects) and a password for the backoffice, then:
//   cd scripts/persona-e2e && npm install && \
//   ADMIN_PASSWORD=… WA_APP_SECRET=… node walkthrough.mjs
// Optional: APP_URL (http://localhost:8080), CHROME (google-chrome), HEADLESS=1, OUT (./out),
// SLOW (ms between steps, 0 by default; ~800 makes it watchable), IG_APP_SECRET (when the app has
// one; Instagram webhooks are signed with it, else with WA_APP_SECRET).
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import puppeteer from 'puppeteer-core';

const APP = (process.env.APP_URL || 'http://localhost:8080').replace(/\/$/, '');
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD;
const WA_APP_SECRET = process.env.WA_APP_SECRET;
const IG_APP_SECRET = process.env.IG_APP_SECRET || WA_APP_SECRET;
const OUT = path.resolve(process.env.OUT || 'out');
const SLOW = Number(process.env.SLOW || 0);
if (!ADMIN_PASSWORD || !WA_APP_SECRET) {
  console.error('Set ADMIN_PASSWORD (backoffice password) and WA_APP_SECRET (the app\'s WhatsApp app secret).');
  process.exit(2);
}

const failures = [];
const log = [];
const note = (...a) => { const line = a.join(' '); log.push(line); console.log(line); };
const expect = (ok, what) => { note(`  ${ok ? '✓' : '✗'} ${what}`); if (!ok) failures.push(what); };
const pause = ms => new Promise(r => setTimeout(r, ms + SLOW));

async function http(method, url, token, body) {
  const res = await fetch(`${APP}${url}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body == null ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${url} → ${res.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : null;
}

/** A company with an admin and a member, in English, with a WhatsApp number of its own. */
async function createCompany() {
  const { token } = await http('POST', '/admin/auth/login', null, { password: ADMIN_PASSWORD });
  const run = Date.now().toString(36);
  const slug = `persona-e2e-${run}`;
  const phone = `persona-e2e-phone-${run}`;
  await http('POST', '/admin/api/tenants', token, {
    name: 'Clínica Sorriso', slug, locale: 'en', timezone: 'Europe/Lisbon',
    channels: [{ platform: 'WHATSAPP', externalId: phone, accessToken: 'persona-e2e' }],
  });
  const admin = { email: `admin-${run}@sorriso.test`, password: `admin-${run}-pw` };
  const member = { email: `member-${run}@sorriso.test`, password: `member-${run}-pw` };
  await http('POST', `/admin/api/tenants/${slug}/dashboard-users`, token, { ...admin, role: 'TENANT_ADMIN' });
  await http('POST', `/admin/api/tenants/${slug}/dashboard-users`, token, { ...member, role: 'TENANT_MEMBER' });
  return { slug, phone, instagram: `persona-e2e-ig-${run}`, admin, member, backoffice: token };
}

/** A .docx with one paragraph per line: a zip of stored entries, which Word and the app both read. */
function docx(lines) {
  const body = lines.map(l => `<w:p><w:r><w:t>${l}</w:t></w:r></w:p>`).join('');
  const files = {
    '[Content_Types].xml': '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>',
    'word/document.xml': `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${body}</w:body></w:document>`,
  };
  const local = [];
  const central = [];
  let offset = 0;
  for (const [name, text] of Object.entries(files)) {
    const data = Buffer.from(text);
    const nameBuf = Buffer.from(name);
    const crc = zlib.crc32(data);
    const head = Buffer.alloc(30);
    head.writeUInt32LE(0x04034b50, 0); head.writeUInt16LE(20, 4); head.writeUInt32LE(crc, 14);
    head.writeUInt32LE(data.length, 18); head.writeUInt32LE(data.length, 22); head.writeUInt16LE(nameBuf.length, 26);
    local.push(head, nameBuf, data);
    const dir = Buffer.alloc(46);
    dir.writeUInt32LE(0x02014b50, 0); dir.writeUInt16LE(20, 4); dir.writeUInt16LE(20, 6); dir.writeUInt32LE(crc, 16);
    dir.writeUInt32LE(data.length, 20); dir.writeUInt32LE(data.length, 24); dir.writeUInt16LE(nameBuf.length, 28); dir.writeUInt32LE(offset, 42);
    central.push(dir, nameBuf);
    offset += head.length + nameBuf.length + data.length;
  }
  const dirSize = central.reduce((n, b) => n + b.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(2, 8); end.writeUInt16LE(2, 10); end.writeUInt32LE(dirSize, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, ...central, end]);
}

/** A one-page PDF with a line of Helvetica text. */
function pdf(text) {
  const content = `BT /F1 12 Tf 72 720 Td (${text}) Tj ET`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let out = '%PDF-1.4\n';
  const offsets = objects.map((o, i) => { const at = out.length; out += `${i + 1} 0 obj\n${o}\nendobj\n`; return at; });
  const xref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map(o => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(out, 'latin1');
}

function fixtures(dir) {
  fs.mkdirSync(dir, { recursive: true });
  const files = {
    'prices.md': Buffer.from('# Prices\n- Routine check-up: 40 €\n- Teeth whitening: 120 €\n- Cleaning (scaling): 55 €\n'),
    'hours.docx': docx(['Opening hours: Monday to Friday, 9:00 to 18:00.', 'Saturdays by appointment, 9:00 to 13:00.', 'Address: Rua Augusta 100, Lisbon.']),
    'brochure.pdf': pdf('Clinica Sorriso - Dental implants from 900 EUR. Free first consultation for children.'),
    'notes.sh': Buffer.from('echo not a persona source\n'),
  };
  for (const [name, data] of Object.entries(files)) fs.writeFileSync(path.join(dir, name), data);
  return name => path.join(dir, name);
}

/** What Meta posts when a customer writes to the company's number, signed with the app secret. */
async function whatsappMessage(phone, text) {
  const body = JSON.stringify({
    object: 'whatsapp_business_account',
    entry: [{ id: 'waba', changes: [{ field: 'messages', value: {
      messaging_product: 'whatsapp', metadata: { display_phone_number: '351210000000', phone_number_id: phone },
      contacts: [{ profile: { name: 'Marta Silva' }, wa_id: '351912345678' }],
      messages: [{ from: '351912345678', id: `wamid.persona-e2e.${Date.now()}`, timestamp: String(Math.floor(Date.now() / 1000)), type: 'text', text: { body: text } }],
    } }] }],
  });
  const signature = crypto.createHmac('sha256', WA_APP_SECRET).update(body).digest('hex');
  const res = await fetch(`${APP}/webhook`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Hub-Signature-256': `sha256=${signature}` }, body });
  return res.status;
}

/** What Instagram posts when a customer writes to the company's account. */
async function instagramMessage(account, text) {
  const body = JSON.stringify({
    object: 'instagram',
    entry: [{ id: account, time: Math.floor(Date.now() / 1000), messaging: [{
      sender: { id: 'persona-e2e-customer' }, recipient: { id: account }, timestamp: Date.now(),
      message: { mid: `mid.persona-e2e.${Date.now()}`, text },
    }] }],
  });
  const signature = crypto.createHmac('sha256', IG_APP_SECRET).update(body).digest('hex');
  const res = await fetch(`${APP}/webhook`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Hub-Signature-256': `sha256=${signature}` }, body });
  return res.status;
}

const company = await createCompany();
const file = fixtures(path.join(OUT, 'fixtures'));
note(`Company ${company.slug}`);

// A profile left by an earlier run still holds that run's sign-in, which would open its dashboard instead of the login.
const profile = path.join(OUT, 'chrome');
fs.rmSync(profile, { recursive: true, force: true });
const browser = await puppeteer.launch({
  executablePath: process.env.CHROME || 'google-chrome',
  headless: process.env.HEADLESS === '1' ? 'new' : false,
  defaultViewport: null,
  args: ['--no-sandbox', '--no-first-run', '--no-default-browser-check', `--user-data-dir=${profile}`, '--window-position=0,0', '--window-size=1920,1160'],
});
const page = (await browser.pages())[0] || await browser.newPage();
const errors = [];
page.on('pageerror', e => errors.push(e.message));
page.on('console', m => { if (m.type() === 'error' && !(m.location()?.url || '').includes('favicon')) errors.push(m.text()); });

let shots = 0;
const shot = name => page.screenshot({ path: path.join(OUT, `${String(++shots).padStart(2, '0')}-${name}.png`) });
const text = sel => page.$eval(sel, el => el.innerText.replace(/\s+/g, ' ').trim()).catch(() => '');
const click = async (sel, wait = 900) => { await page.waitForSelector(sel, { visible: true, timeout: 15000 }); await page.click(sel); await pause(wait); };
const type = async (sel, value, delay = 15) => { await page.waitForSelector(sel, { visible: true }); await page.click(sel); await page.type(sel, value, { delay }); await pause(200); };
const toast = async () => { await pause(250); return page.$eval('#toast', el => (el.hidden ? '' : el.innerText.trim())).catch(() => ''); };
const confirm = async () => { await page.waitForSelector('#confirm:not([hidden])', { timeout: 10000 }); await pause(600); const t = `${await text('#confirm-title')} ${await text('#confirm-body')}`; await click('#confirm-ok', 300); return t; };
const rows = () => page.$$eval('.persona-studio__pane tbody tr', rs => rs.map(r => r.innerText.replace(/\s+/g, ' ').trim()));
const notices = () => page.$$eval('.notice', ns => ns.map(n => n.innerText.replace(/\s+/g, ' ').trim()).join(' / '));
const persona = () => page.evaluate(() => state.persona);
const settled = async () => { await page.waitForFunction(() => state.persona && state.persona.status !== 'COMPILING' && !state.persona.pendingSources, { timeout: 40000, polling: 300 }); await pause(1200); };
const tab = id => click(`[data-persona-tab="${id}"]`, 1200);
const openPersona = () => click('.nav__item[data-tab="persona"]', 1500);
const login = async ({ email, password }) => {
  await page.goto(`${APP}/app/`, { waitUntil: 'networkidle2' });
  await type('#email', email, 20);
  await type('#password', password, 20);
  await page.click('#login-form button[type=submit]');
  await page.waitForSelector('.nav__item[data-tab="persona"]', { timeout: 15000 });
  await pause(1000);
};
const tryChip = async key => {
  const count = () => page.$$eval('#persona-chat-log .chat__msg--bot:not(.chat__typing), #persona-chat-log .chat__note', els => els.length);
  const before = await count();
  await click(`[data-persona-try="${key}"]`, 200);
  await page.waitForFunction(b => document.querySelectorAll('#persona-chat-log .chat__msg--bot:not(.chat__typing), #persona-chat-log .chat__note').length > b, { timeout: 20000 }, before);
  await pause(1500);
  return page.$$eval('#persona-chat-log > *', els => els.slice(-2).map(e => e.innerText.trim()).join(' ⟶ '));
};

try {
  note('1. An empty persona');
  await login(company.admin);
  await openPersona();
  expect((await text('.view__stats')).includes('Not set up'), 'a new company starts "Not set up"');
  await shot('empty');

  note('2. Settings, tested before they are saved');
  await type('#pb-botName', 'Sofia', 50);
  for (const [sel, value] of [['#pb-tone', 'FRIENDLY'], ['#pb-addressForm', 'FORMAL'], ['#pb-replyLength', 'SHORT'], ['#pb-emoji', 'LIGHT']]) { await page.select(sel, value); await pause(350); }
  await type('#pb-greeting', 'Hello! Welcome to Clínica Sorriso.');
  await type('#pb-rules', 'Never discuss competitors.\nAlways offer online booking.');
  await page.evaluate(() => document.querySelector('#pb-handoff').scrollIntoView({ block: 'center' }));
  await click('#pb-handoff', 500);
  await type('#pb-handoff-message', 'I am passing you to a colleague, who will reply here shortly.', 10);
  const labels = await page.$$eval('#persona-behavior-form .lbl', ls => ls.map(l => l.innerText.split('\n')[0].trim()));
  expect(['Tone', 'Form of address', 'Reply length', 'Emoji'].every(l => labels.includes(l)), `style fields are labelled (${labels.join(', ')})`);
  expect(await text('#pb-rules-count') === '2' && await text('#pb-handoff-pill') === 'On', 'the rule count and the handoff pill follow the form');
  expect(await page.$eval('#persona-test-draft', el => !el.hidden), 'the test chat says it uses unsaved changes');
  expect((await tryChip('who')).includes("I'm Sofia"), 'the test chat answers with the unsaved name');
  await shot('draft-test');
  await click('[data-pb-save]', 300);
  expect((await toast()).startsWith('Settings saved'), 'saving the settings is confirmed');
  await pause(1000);
  expect((await text('.view__stats')).includes('Live') && (await text('.view__stats')).includes('v1'), 'the persona is live as v1');

  note('3. Knowledge from a note and three files');
  await tab('knowledge');
  await type('#persona-note', 'We are Clínica Sorriso, a dental clinic in Lisbon. A routine check-up costs 40 €.', 8);
  await click('#persona-note-form button[type=submit]', 300);
  expect((await notices()).includes('Synthesizing your sources'), 'a queued note shows as synthesizing at once');
  await settled();
  for (const name of ['prices.md', 'hours.docx', 'brochure.pdf']) {
    await (await page.$('#persona-file')).uploadFile(file(name));
    await pause(400);
    await settled();
  }
  const sources = await rows();
  expect(sources.length === 4 && sources.every(r => r.includes('In the instructions')), `four sources, all in the instructions (${sources.length})`);
  await shot('sources');
  await page.evaluate(() => [...document.querySelectorAll('.persona-studio__pane tbody tr')].find(r => r.innerText.includes('hours.docx')).querySelector('[data-view-source]').click());
  await pause(1500);
  expect((await page.$eval('#ps-text', el => el.value)).startsWith('Opening hours: Monday to Friday'), 'a Word file reads back as its text');
  await click('#drawer-body [data-close]', 600);
  await (await page.$('#persona-file')).uploadFile(file('notes.sh'));
  expect((await toast()).startsWith('Use a PDF, Word'), 'an unsupported file is refused with the accepted types');

  note('4. The test chat');
  await click('#persona-chat-clear', 400);
  expect((await tryChip('hours')).includes('Monday to Friday, 9:00 to 18:00'), 'hours come from the Word file');
  expect((await tryChip('price')).includes('Teeth whitening: 120 €'), 'prices come from the Markdown file');
  expect((await tryChip('jailbreak')).includes("can't share my internal instructions"), 'a prompt-injection try is refused');
  const handoff = await tryChip('human');
  expect(handoff.includes('I am passing you to a colleague') && handoff.includes('hands the conversation to your team'), 'asking for a person hands over with the company\'s message');
  await shot('test-chat');

  note('5. Hand edits and what the bot reads');
  await tab('instructions');
  await page.waitForFunction(() => document.querySelector('#persona-preview')?.value.startsWith('<persona>'), { timeout: 15000 });
  await page.focus('#persona-text');
  await page.keyboard.down('Control'); await page.keyboard.press('End'); await page.keyboard.up('Control');
  await page.keyboard.type('\n- Parking is available next door.', { delay: 20 });
  await click('[data-persona-preview]', 1200);
  const preview = await page.$eval('#persona-preview', el => el.value);
  expect(preview.includes('Your name is Sofia.') && preview.includes('Parking is available next door.'), 'the preview shows the settings and the unsaved edit');
  await shot('instructions-preview');
  await click('[data-pi-save]', 300);
  expect((await toast()).startsWith('Instructions saved'), 'saving the instructions is confirmed');
  await pause(1000);

  note('6. History and restore');
  await tab('history');
  await page.waitForSelector('[data-version]');
  const history = await rows();
  expect(history.length === 6 && history[0].includes('Live') && history[0].includes('Instructions edited'), `six versions, the hand edit live (${history.length})`);
  await click('[data-version="1"]', 1300);
  expect((await text('#drawer-body')).includes('Tone Friendly'), 'a version shows its settings');
  await click('#drawer-body [data-close]', 600);
  await click('[data-restore="1"]', 300);
  expect((await confirm()).startsWith('Restore version 1?'), 'restoring asks first');
  expect((await toast()) === 'Version 1 restored', 'restoring v1 is confirmed');
  await pause(1000);
  expect((await persona()).compiledInstructions === '' && (await persona()).behavior.botName === 'Sofia', 'v1 brings back its empty instructions and its settings');
  await tab('history');
  await click('[data-restore="6"]', 300);
  await confirm();
  await pause(1200);
  expect((await persona()).compiledInstructions.includes('Parking is available next door.'), 'restoring v6 brings the instructions back');
  await shot('history');

  note('7. Removing a source and rebuilding');
  await tab('knowledge');
  await page.evaluate(() => [...document.querySelectorAll('.persona-studio__pane tbody tr')].find(r => r.innerText.includes('brochure.pdf')).querySelector('[data-del-source]').click());
  expect((await confirm()).includes('stays in the instructions until you rebuild'), 'removing a synthesized source says what stays');
  await pause(1200);
  expect((await notices()).includes('still mention a source you removed'), 'the persona is flagged until a rebuild');
  await shot('stale');
  await click('.notice [data-persona-rebuild]', 300);
  expect((await confirm()).includes('edits you made by hand are dropped'), 'rebuilding warns about hand edits');
  await settled();
  const rebuilt = (await persona()).compiledInstructions;
  expect(!(await notices()).includes('still mention') && !rebuilt.includes('implants') && !rebuilt.includes('Parking'), 'the rebuild drops the removed source and the hand edit');

  note('8. A customer asks for a person on WhatsApp');
  expect(await whatsappMessage(company.phone, 'Hello, I would like to talk to a person about a refund please') === 200, 'the signed webhook is accepted');
  await pause(4000);
  await page.reload({ waitUntil: 'networkidle2' });
  await pause(2000);
  await click('#btn-notifications', 1500);
  expect((await text('#drawer-body')).includes('Marta Silva needs a person'), 'the bell tells the team');
  await click('#drawer-body [data-notification]', 2500);
  await page.waitForSelector('.thread-banner', { timeout: 15000 });
  expect((await text('.thread-banner')).includes('The bot handed this conversation to your team'), 'the chat says the bot handed it over');
  expect((await text('.thread-head__tools')).includes('AI paused'), 'the bot is paused in that chat');
  await shot('inbox-handoff');
  await click('[data-inbox-filter="needs"]', 1200);
  expect((await page.$$eval('.inbox-row__name', ns => ns.map(n => n.innerText.trim()))).includes('Marta Silva'), 'the chat waits in Needs reply');

  note('9. Menus that answer Instagram before the AI');
  await openPersona();
  await tab('menus');
  await page.waitForSelector('#persona-menus-form');
  expect((await notices()).includes('Instagram isn’t connected'), 'the tab says menus only answer Instagram');
  expect((await text('.persona-studio__pane')).includes('No menus yet'), 'a company starts without menus');
  await click('[data-menu-add]', 800);
  expect(await page.$eval('#pm-0-name', el => el.value) === 'Menu 1', 'a new menu gets a name');
  expect(await page.evaluate(() => document.activeElement?.id) === 'pm-0-keywords', 'and the cursor waits in its keywords');
  await type('#pm-0-keywords', 'Hello\nGood morning', 20);
  await type('#pm-0-0-text', 'Hello! Which service would you like to know about?', 8);
  const keywords = await page.evaluate(() => state.personaDraft.menus.flows[0].keywords);
  expect(keywords.join('|') === 'Hello|Good morning' && (await page.$$('[data-tokens="0"] .token')).length === 2, `Enter adds a keyword token, and leaving the field adds the one being typed (${keywords.join('|')})`);
  expect(await page.$eval('[data-pm-savebar]', el => el.classList.contains('is-dirty')), 'the save bar follows the screen once something is unsaved');
  await click('[data-button-add="0:0"]', 800);
  await type('#pm-0-0-b0', 'Whitening', 20);
  await page.select('#pm-0-0-n0', '__new');
  await pause(900);
  expect(await page.evaluate(() => document.activeElement?.id) === 'pm-0-1-text', '"New message…" adds a message and moves to it');
  expect((await text('.flow-map')).includes('Whitening') && (await text('[data-step-summary="0:1"]')).includes('1 · Whitening'), 'the map and the new message show the button that leads there');
  await click('[data-pm-save]', 300);
  expect((await toast()).includes('message 2: write the message'), 'an empty message is caught before saving');
  expect(await page.evaluate(() => document.activeElement?.id) === 'pm-0-1-text', 'and the cursor goes to it');
  expect((await text('[data-issue="0:1:text"]')).startsWith('Write the message'), 'the message says under it what is missing');
  await type('#pm-0-1-text', 'Whitening costs 120 €. Call us to book.', 8);
  expect(await page.$eval('[data-issue="0:1:text"]', el => el.hidden), 'and stops once it is written');
  expect(await page.$eval('#persona-test-draft', el => !el.hidden), 'the test chat says it uses the unsaved menu');
  await shot('menus-draft');
  await click('#persona-chat-clear', 300);
  await type('#persona-chat-input', 'Hello there', 20);
  await page.keyboard.press('Enter');
  await page.waitForSelector('#persona-chat-log [data-quick-reply]', { timeout: 15000 });
  await pause(500);
  const opened = await text('#persona-chat-log');
  expect(opened.includes('Which service would you like to know about?') && opened.includes('Answered by the menu “Menu 1”'), 'a keyword opens the unsaved menu in the test chat');
  await click('#persona-chat-log [data-quick-reply="0"]', 300);
  await page.waitForFunction(() => document.querySelector('#persona-chat-log').innerText.includes('Whitening costs 120'), { timeout: 15000 });
  await pause(500);
  expect(!(await page.$('#persona-chat-log [data-quick-reply]')), 'tapping a button moves the menu on, and the last message has no buttons');
  await shot('menus-test-chat');
  await click('[data-pm-save]', 300);
  expect((await toast()).startsWith('Menus saved'), 'saving the menus is confirmed');
  await pause(1000);
  expect(await page.$eval('#persona-test-draft', el => el.hidden), 'nothing is left unsaved');
  await click('[data-menu-try="0"]', 300);
  await page.waitForFunction(() => document.querySelector('#persona-chat-log').innerText.includes('Answered by the menu “Menu 1”'), { timeout: 15000 });
  expect((await page.$eval('#persona-chat-log .chat__msg--user', el => el.innerText.trim())) === 'Hello', 'Try it starts a test chat with one of the menu\'s keywords');
  await page.select('#pm-fallback', 'team');
  await pause(300);
  await click('[data-pm-save]', 1300);
  await click('#persona-chat-clear', 300);
  await type('#persona-chat-input', 'Do you open on Saturdays?', 20);
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.querySelectorAll('#persona-chat-log .chat__note').length > 0, { timeout: 20000 });
  await pause(500);
  expect((await text('#persona-chat-log')).includes('On Instagram this gets no automatic reply'), 'with the AI off on Instagram, the test chat says so');

  await click('[data-menu-add]', 800);
  await type('#pm-1-keywords', 'Hello there\n', 20);
  await pause(300);
  expect((await text('[data-kw-notes="1"]')).includes('“Hello there” opens “Menu 1” instead'), 'a keyword an earlier menu catches first is flagged');
  expect(await page.$eval('[data-tokens="1"] .token', el => el.classList.contains('token--warn')), 'and its token is marked');
  await type('#pm-probe', 'Hello there!', 20);
  expect((await text('[data-pm-probe]')).startsWith('Opens “Menu 1”, through the keyword “Hello”'), 'the message checker names the menu that answers and its keyword');
  await click('[data-menu-up="1"]', 800);
  expect(!(await text('[data-kw-notes="0"]')).includes('instead') && (await text('[data-pm-probe]')).startsWith('Opens “Menu 2”'), 'moving the menu up lets its keyword answer');
  await shot('menus-clash');
  await click('[data-menu-duplicate="1"]', 900);
  const copies = await page.evaluate(() => state.personaDraft.menus.flows.map(f => `${f.name}:${f.enabled}`));
  expect(copies.length === 3 && copies[2] === 'Menu 1 (copy):false', `a duplicate is added under the menu, off (${copies.join(', ')})`);
  await click('[data-pm-discard]', 300);
  expect((await confirm()).startsWith('Discard your changes to the menus?'), 'discarding asks first');
  await pause(800);
  expect((await page.$$('.flow-menu')).length === 1 && !(await page.evaluate(() => !!state.personaDraft.menus)), 'and goes back to the saved menu');

  // An Instagram customer writes; the menu answers before the AI and its usage counts it. Without a
  // real token Instagram refuses the reply, which the figures count apart.
  await http('POST', `/admin/api/tenants/${company.slug}/channels`, company.backoffice, { platform: 'INSTAGRAM', externalId: company.instagram, accessToken: 'persona-e2e' });
  expect(await instagramMessage(company.instagram, 'Hello!') === 200, 'the signed Instagram webhook is accepted');
  const used = await page.waitForFunction(async () => {
    const usage = (await api('/app/api/persona/menus')).usage.menus[0];
    return usage && usage.sent + usage.failed > 0 && usage;
  }, { timeout: 30000, polling: 1000 }).then(h => h.jsonValue()).catch(() => null);
  expect(!!used, `the menu's reply is in its usage (${JSON.stringify(used)})`);
  await page.reload({ waitUntil: 'networkidle2' });
  await pause(1500);
  await openPersona();
  await tab('menus');
  await page.waitForSelector('#pm-0-name');
  const kept = await page.evaluate(() => [document.querySelector('#pm-0-1-text').value, document.querySelector('#pm-fallback').value, document.querySelector('#pm-0-0-n0').selectedOptions[0].textContent]);
  expect(kept[0] === 'Whitening costs 120 €. Call us to book.' && kept[1] === 'team' && kept[2].startsWith('2 · Whitening costs'), `the saved menu reads back after a reload (${kept.join(' | ')})`);
  expect(!(await notices()).includes('Instagram isn’t connected'), 'with Instagram connected, the tab stops saying it isn’t');
  const summary = await text('[data-menu-summary="0"]');
  expect(summary.includes('Sent once to 1 customer') || summary.includes('1 refused by Instagram'), `the menu's head counts what it sent (${summary})`);
  await shot('menus-saved');

  note('10. Dark theme, a narrow window and a phone');
  await tab('behavior');
  await click('#btn-theme', 1200);
  await shot('dark');
  await tab('menus');
  await shot('menus-dark');
  await click('#btn-theme', 800);
  await tab('behavior');
  const cdp = await page.target().createCDPSession();
  const { windowId } = await cdp.send('Browser.getWindowForTarget');
  await cdp.send('Browser.setWindowBounds', { windowId, bounds: { width: 820, height: 1100 } });
  await pause(1500);
  expect(!(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)), 'nothing scrolls sideways at 820px');
  expect(await page.evaluate(() => document.querySelector('.persona-studio__test').getBoundingClientRect().top >= document.querySelector('.persona-studio__main').getBoundingClientRect().bottom - 1), 'the test chat moves below the editor');
  await shot('narrow');
  await cdp.send('Browser.setWindowBounds', { windowId, bounds: { left: 0, top: 0, width: 1920, height: 1160 } });
  await pause(1000);
  await tab('menus');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 375, height: 812, deviceScaleFactor: 2, mobile: true });
  await pause(1500);
  expect(!(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)), 'the menus scroll no sideways on a 375px phone');
  await shot('menus-phone');
  await cdp.send('Emulation.clearDeviceMetricsOverride');
  await pause(1000);

  note('11. A member');
  await click('#btn-logout', 1000);
  await login(company.member);
  await openPersona();
  expect((await notices()).includes('Only admins change the persona'), 'members are told why they can\'t edit');
  expect(await page.$eval('#pb-botName', el => el.disabled) && !(await page.$('[data-pb-save]')), 'settings are read-only');
  await tab('knowledge');
  expect(!(await page.$('#persona-note-form')) && (await page.$$('[data-del-source]')).length === 0, 'no adding or removing sources');
  await tab('history');
  await page.waitForSelector('[data-version]');
  expect((await page.$$('[data-restore]')).length === 0, 'no restoring');
  await tab('menus');
  await page.waitForSelector('#pm-0-name');
  expect(await page.$eval('#pm-0-name', el => el.disabled) && !(await page.$('[data-pm-save]')) && !(await page.$('[data-menu-add]')), 'menus are read-only');
  expect(!(await page.$('[data-menu-up="0"], [data-kw-remove]')) && await page.$eval('[data-menu-try="0"]', el => !el.disabled), 'members can\'t reorder or edit keywords, but can try a menu');
  expect((await tryChip('who')).includes("I'm Sofia"), 'members still use the test chat');
  await shot('member');
} catch (e) {
  failures.push(`stopped: ${e.message}`);
  note(`  ✗ stopped: ${e.message}`);
  await shot('stopped').catch(() => {});
}

expect(errors.length === 0, `no page errors (${errors.join(' | ') || 'none'})`);
fs.writeFileSync(path.join(OUT, 'log.txt'), log.join('\n'));
await browser.close();
note(failures.length ? `\n${failures.length} check(s) failed` : '\nAll checks passed');
process.exit(failures.length ? 1 : 0);
