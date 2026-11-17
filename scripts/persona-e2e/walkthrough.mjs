// Browser walkthrough of the Persona page against a running app, checking each step.
//
// It creates its own company and users through the backoffice API, then drives Chrome through the
// Overview (the answer flow and the setup list), Profile and Rules (option cards, rules as rows with
// ideas, the handoff's situations; tested unsaved, then saved together with one save bar), business
// details and questions and answers (a half-written question refused, an unsaved one answered in the
// test chat), off-topic and unknown questions and what the bot can do, notes and PDF/Word/Markdown
// uploads, the test chat (handoff and a prompt-injection try), hand edits and what the bot reads,
// history and restore, removing a document and rewriting, a signed WhatsApp message that hands a chat
// to the team (bell + inbox), Channels (the AI on and off per channel, the pointer to the Chatbot page,
// a flow left alone by a channel save; the flows themselves are scripts/chatbot-e2e's), start words (a WhatsApp gate set up and
// saved, a signed first message without one kept for the team and paused, one with a start word
// answered, the test chat's note, Resume), dark theme, a narrow window and a phone, and a member's
// read-only view.
//
// Run the app with OPENROUTER_BASE_URL pointing at fake-openrouter.py (its replies are what this
// expects) and a password for the backoffice, then:
//   cd scripts/persona-e2e && npm install && \
//   ADMIN_PASSWORD=… WA_APP_SECRET=… node walkthrough.mjs
// Optional: APP_URL (http://localhost:8080), CHROME (google-chrome), HEADLESS=1, OUT (./out),
// SLOW (ms between steps, 0 by default; ~800 makes it watchable).
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import puppeteer from 'puppeteer-core';

const APP = (process.env.APP_URL || 'http://localhost:8080').replace(/\/$/, '');
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD;
const WA_APP_SECRET = process.env.WA_APP_SECRET;
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
async function whatsappMessage(phone, text, from = '351912345678', name = 'Marta Silva') {
  const body = JSON.stringify({
    object: 'whatsapp_business_account',
    entry: [{ id: 'waba', changes: [{ field: 'messages', value: {
      messaging_product: 'whatsapp', metadata: { display_phone_number: '351210000000', phone_number_id: phone },
      contacts: [{ profile: { name }, wa_id: from }],
      messages: [{ from, id: `wamid.persona-e2e.${from}.${Date.now()}`, timestamp: String(Math.floor(Date.now() / 1000)), type: 'text', text: { body: text } }],
    } }] }],
  });
  const signature = crypto.createHmac('sha256', WA_APP_SECRET).update(body).digest('hex');
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
// An option card or box, centred first: the sticky save bar covers the bottom of the screen once something is unsaved.
const pick = async (sel, wait = 300) => {
  await page.waitForSelector(sel, { timeout: 15000 });
  await page.$eval(sel, el => el.scrollIntoView({ block: 'center' }));
  await pause(150);
  await page.click(sel);
  await pause(wait);
};
const choose = (field, value) => pick(`input[name="pb-${field}"][value="${value}"]`);
const tryChip = async key => {
  const count = () => page.$$eval('#persona-chat-log .chat__msg--bot:not(.chat__typing), #persona-chat-log .chat__note', els => els.length);
  const before = await count();
  await click(`[data-persona-try="${key}"]`, 200);
  await page.waitForFunction(b => document.querySelectorAll('#persona-chat-log .chat__msg--bot:not(.chat__typing), #persona-chat-log .chat__note').length > b, { timeout: 20000 }, before);
  await pause(1500);
  return page.$$eval('#persona-chat-log > *', els => els.slice(-2).map(e => e.innerText.trim()).join(' ⟶ '));
};

try {
  note('1. An empty persona opens on the Overview');
  await login(company.admin);
  await openPersona();
  expect((await text('.view__stats')).includes('Not set up'), 'a new company starts "Not set up"');
  expect((await page.$$('.bot-flow__step')).length === 4, 'the Overview shows the four steps a message goes through');
  expect((await text('#persona-flow')).includes('WhatsApp · every chat'), 'the first step says who gets an answer on each channel');
  expect((await text('#persona-setup')).includes('0 of 5'), 'nothing is set up yet');
  expect(!(await page.$eval('[data-persona-savebar]', el => el.classList.contains('is-dirty'))), 'the save bar rests with nothing unsaved');
  await shot('empty');

  note('2. Profile and rules, tested before they are saved');
  await click('#persona-setup [data-persona-go="profile"]', 1200);
  expect(await page.evaluate(() => document.activeElement?.id) === 'pb-botName', 'the setup list opens Profile with the cursor in the bot name');
  await type('#pb-botName', 'Sofia', 50);
  for (const [field, value] of [['tone', 'FRIENDLY'], ['addressForm', 'FORMAL'], ['replyLength', 'SHORT'], ['emoji', 'LIGHT']]) await choose(field, value);
  const groups = await page.$$eval('.choices-field > .lbl', ls => ls.map(l => l.innerText.trim()));
  expect(['Tone', 'Form of address', 'Reply length', 'Emoji'].every(l => groups.includes(l)), `style options are labelled groups (${groups.join(', ')})`);
  expect((await text('label.choice:has(input[name="pb-tone"][value="FRIENDLY"])')).includes('happy to help'), 'each option shows an example');
  await type('#pb-greeting', 'Hello! Welcome to Clínica Sorriso.');
  await tab('rules');
  await click('[data-rule-add]', 500);
  expect(await page.evaluate(() => document.activeElement?.id) === 'pr-0', 'Add rule puts the cursor in the new rule');
  await page.keyboard.type('Never discuss competitors.', { delay: 15 });
  await page.keyboard.press('Enter');
  await pause(400);
  expect(await page.evaluate(() => document.activeElement?.id) === 'pr-1', 'Enter starts the next rule');
  await page.keyboard.type('Always offer online booking.', { delay: 15 });
  await click('[data-rule-idea="discounts"]', 500);
  expect(await page.$eval('#pr-2', el => el.value) === 'Never offer discounts.' && !(await page.$('[data-rule-idea="discounts"]')), 'an idea adds its rule and leaves the list');
  await page.evaluate(() => document.querySelector('#pb-handoff').scrollIntoView({ block: 'center' }));
  await click('#pb-handoff', 700);
  const reasons = await page.$$eval('[data-pb-reason]', els => els.filter(e => e.checked).map(e => e.dataset.pbReason));
  expect(reasons.join(',') === 'ASKS_FOR_PERSON,UPSET,CANT_HELP', `turning the handoff on ticks the usual situations (${reasons.join(',')})`);
  await type('#pb-handoff-message', 'I am passing you to a colleague, who will reply here shortly.', 10);
  expect(await text('[data-pb-rules-count]') === '3' && await text('#pb-handoff-pill') === 'On', 'the rule count and the handoff pill follow the edits');
  expect((await text('[data-persona-status]')).includes('Unsaved changes: Profile, Rules'), 'the save bar names the tabs with unsaved changes');
  expect(await page.$eval('#persona-test-draft', el => !el.hidden), 'the test chat says it uses unsaved changes');
  expect((await tryChip('who')).includes("I'm Sofia"), 'the test chat answers with the unsaved name');
  await shot('draft-test');
  await click('[data-persona-save]', 300);
  expect((await toast()).startsWith('Saved'), 'one save is confirmed');
  await pause(1000);
  const v1 = await persona();
  expect(v1.version === 1 && v1.behavior.botName === 'Sofia' && v1.behavior.rules.length === 3 && v1.behavior.handoff.enabled, 'Profile and Rules went live together as v1');
  const picked = ['tone', 'addressForm', 'replyLength', 'emoji'].map(f => v1.behavior[f]).join(',');
  expect(picked === 'FRIENDLY,FORMAL,SHORT,LIGHT', `every option card picked is stored (${picked})`);
  expect((await text('.view__stats')).includes('Live') && (await text('.view__stats')).includes('On'), 'the stats say it is live and hands over');

  note('2b. Business details and questions and answers');
  await tab('knowledge');
  expect((await page.$eval('#pb-business-name', el => el.placeholder)).includes('Clínica Sorriso'), 'the business name suggests the company\'s name');
  await type('#pb-business-name', 'Clínica Sorriso', 15);
  await type('#pb-business-location', 'Rua Augusta 100, Lisbon.', 10);
  await type('#pb-business-contact', '+351 210 000 000', 10);
  await click('[data-faq-add]', 600);
  expect(await page.evaluate(() => document.activeElement?.id) === 'pf-0-q', 'Add question puts the cursor in the question');
  await page.keyboard.type('Do you have parking?', { delay: 15 });
  expect((await text('[data-persona-status]')).includes('1 to fix'), 'a question without its answer counts as one to fix');
  await click('[data-persona-save]', 300);
  expect((await toast()) === 'Question 1 has no answer yet.', 'saving a question without its answer is refused before it is sent');
  expect(await page.evaluate(() => document.activeElement?.id) === 'pf-0-a' && (await text('[data-faq-issue="0"]')).startsWith('Write the answer'), 'and the cursor goes to the answer, which says what is missing');
  await type('#pf-0-a', 'Yes, free parking behind the clinic.', 10);
  expect(await page.$eval('[data-faq-issue="0"]', el => el.hidden), 'the note goes once the answer is written');
  await click('#persona-chat-clear', 300);
  expect((await tryChip('who')).includes("I'm Sofia"), 'the test chat still works with the unsaved knowledge');
  await type('#persona-chat-input', 'Hi, do you have parking there?', 15);
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.querySelector('#persona-chat-log').innerText.includes('free parking behind the clinic'), { timeout: 15000 }).catch(() => {});
  expect((await text('#persona-chat-log')).includes('free parking behind the clinic'), 'an unsaved question is answered with its answer');
  await click('[data-persona-save]', 300);
  expect((await toast()).startsWith('Saved'), 'the knowledge is saved');
  await pause(900);
  expect((await persona()).behavior.faq.length === 1 && (await persona()).behavior.business.contact === '+351 210 000 000', 'the question and the business details are stored');

  note('2c. Off-topic questions, what the bot can do');
  await tab('rules');
  await choose('scope', 'BUSINESS');
  await choose('unknown', 'SHARE_CONTACT');
  expect(await page.$eval('[data-pb-unknown-note]', el => el.hidden), 'sharing contacts needs no note: the contacts are there');
  await choose('unknown', 'HAND_OVER');
  expect(await page.$eval('[data-pb-unknown-note]', el => el.hidden), 'handing over needs no note either: the handoff is on');
  const can = await page.$$eval('.choices--wide .choice__title', els => els.map(e => e.innerText.trim()));
  expect(can.includes('Look up catalog prices') && can.includes('Clients, quotes and invoices'), `what the bot can do lists the company's tools (${can.join(', ')})`);
  await pick('#pb-capability-records');
  await click('[data-persona-save]', 300);
  await pause(900);
  const v3 = await persona();
  expect(v3.behavior.scope === 'BUSINESS' && v3.behavior.unknown === 'HAND_OVER' && v3.behavior.capabilities.records === false, 'the off-topic and unknown answers and the switch are saved');
  await tab('overview');
  const ai = await text('#persona-flow');
  expect(ai.includes('Sofia · Friendly · Formal · Short') && ai.includes('1 question') && ai.includes('hands over what it doesn’t know') && !ai.includes('handles clients'), `the Overview sums up who the bot is, what it knows and what it can do (${ai})`);
  expect((await text('#persona-setup')).includes('5 of 5'), 'and every setup step is done, the test chat included');
  await shot('overview-set-up');

  note('3. Knowledge from a note and three files');
  await tab('knowledge');
  await type('#persona-note', 'We are Clínica Sorriso, a dental clinic in Lisbon. A routine check-up costs 40 €.', 8);
  await click('#persona-note-form button[type=submit]', 300);
  expect((await notices()).includes('Reading your documents'), 'a queued note shows the AI reading it at once');
  await settled();
  for (const name of ['prices.md', 'hours.docx', 'brochure.pdf']) {
    await (await page.$('#persona-file')).uploadFile(file(name));
    await pause(400);
    await settled();
  }
  const sources = await rows();
  expect(sources.length === 4 && sources.every(r => r.includes('Learned')), `four documents, all learned (${sources.length})`);
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
  await page.focus('#persona-text');
  await page.keyboard.down('Control'); await page.keyboard.press('End'); await page.keyboard.up('Control');
  await page.keyboard.type('\n- Payment by card or MB Way.', { delay: 20 });
  expect((await text('[data-persona-status]')).includes('Unsaved changes: Knowledge'), 'an edit to the instructions is an unsaved Knowledge change');
  await tab('overview');
  await click('[data-persona-reads]', 300);
  await page.waitForFunction(() => document.querySelector('#persona-preview')?.value.startsWith('<persona>'), { timeout: 15000 });
  const preview = await page.$eval('#persona-preview', el => el.value);
  expect(['Your name is Sofia.', 'Rules you always follow:', 'About the company', 'Q: Do you have parking?', 'Payment by card or MB Way.'].every(s => preview.includes(s)), 'what the bot reads shows every part, the unsaved edit included');
  await shot('instructions-preview');
  await click('#drawer-body [data-close]', 600);
  await click('[data-persona-save]', 300);
  expect((await toast()).startsWith('Saved'), 'saving the instructions is confirmed');
  await pause(1000);

  note('6. History and restore');
  await tab('history');
  await page.waitForSelector('[data-version]');
  const versions = await page.evaluate(async () => api('/app/api/persona/versions'));
  const handEdit = versions.find(v => v.change === 'MANUAL');
  const history = await rows();
  expect(versions.length === 8 && history[0].includes('Live') && history[0].includes('Instructions edited'), `eight versions, the hand edit live (${versions.length})`);
  expect(history.some(r => r.includes('Learned from 1 document')), 'learning from a document is named as such');
  await click('[data-version="1"]', 1300);
  expect((await text('#drawer-body')).includes('Tone Friendly'), 'a version shows its settings');
  await click('#drawer-body [data-close]', 600);
  await click('[data-restore="1"]', 300);
  expect((await confirm()).startsWith('Restore version 1?'), 'restoring asks first');
  expect((await toast()) === 'Version 1 restored', 'restoring v1 is confirmed');
  await pause(1000);
  const restored = await persona();
  expect(restored.compiledInstructions === '' && restored.behavior.botName === 'Sofia' && restored.behavior.faq.length === 0, 'v1 brings back its empty instructions and its settings, before the questions');
  await tab('history');
  await click(`[data-restore="${handEdit.version}"]`, 300);
  await confirm();
  await pause(1200);
  const back = await persona();
  expect(back.compiledInstructions.includes('Payment by card or MB Way.') && back.behavior.faq.length === 1, 'restoring the hand edit brings the instructions and the questions back');
  await shot('history');

  note('7. Removing a document and rewriting');
  await tab('knowledge');
  await page.evaluate(() => [...document.querySelectorAll('.persona-studio__pane tbody tr')].find(r => r.innerText.includes('brochure.pdf')).querySelector('[data-del-source]').click());
  expect((await confirm()).includes('stays in the instructions until you rewrite them'), 'removing a learned document says what stays');
  await pause(1200);
  expect((await notices()).includes('still mention a document you removed'), 'the persona is flagged until a rewrite');
  await shot('stale');
  await click('.notice [data-persona-rebuild]', 300);
  expect((await confirm()).includes('changes you made to them by hand are lost'), 'rewriting warns about hand edits');
  await settled();
  const rebuilt = (await persona()).compiledInstructions;
  expect(!(await notices()).includes('still mention') && !rebuilt.includes('implants') && !rebuilt.includes('MB Way'), 'the rewrite drops the removed document and the hand edit');

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

  note('9. Channels: where the AI answers, and the pointer to the Chatbot page');
  await openPersona();
  await tab('channels');
  await page.waitForSelector('#persona-channels-form');
  expect((await text('[data-channel="INSTAGRAM"]')).includes('Not connected') && (await text('[data-channel="WHATSAPP"]')).includes('Connected'), 'each channel says whether it is connected');
  expect((await text('[data-channel="WEB"]')).includes('The AI answers every visitor') && !(await page.$('#pc-ai-WEB')), 'the website always gets the AI');
  expect((await text('#pc-chatbot')).includes('No flows are on yet'), 'the tab points at the Chatbot page, where the fixed flows live');
  await click('#pc-ai-WHATSAPP', 600);
  expect((await text('[data-channel="WHATSAPP"]')).includes('Off: messages wait in Conversations'), 'turning the AI off on WhatsApp says where messages go');
  await tab('overview');
  expect((await text('#persona-flow')).includes('WhatsApp · the AI is off'), 'the Overview follows the unsaved channel change');
  expect((await text('#persona-flow')).includes('No chatbot flows'), 'and says no chatbot flow answers first yet');
  await tab('channels');
  await click('#pc-ai-WHATSAPP', 600);
  expect(!(await page.evaluate(() => !!state.personaDraft.channels)), 'turning it back on leaves nothing unsaved');
  await click('[data-open-chatbot]', 1500);
  expect(await page.evaluate(() => location.hash) === '#chatbot' && !!(await page.$('#chatbot-form')), 'Open Chatbot goes to the Chatbot page');

  // Instagram gets connected and a chatbot flow installed (through the same API the Chatbot page uses): the AI-off hint then
  // says the flows still answer, and saving the channels leaves the flow alone.
  await http('POST', `/admin/api/tenants/${company.slug}/channels`, company.backoffice, { platform: 'INSTAGRAM', externalId: company.instagram, accessToken: 'persona-e2e' });
  const flow = { id: 'hello', name: 'Hello', enabled: true, channels: ['INSTAGRAM'], keywords: ['hello'], aiReads: false, afterMisses: 'HAND_OVER', handoverText: '',
    steps: [{ id: 'menu', kind: 'MESSAGE', text: 'Hello! How can we help?', options: [], show: 'AUTO', listLabel: '', retryText: '', next: '', key: '', expect: 'TEXT', check: 'HOURS', hours: [], answerKey: '', answerIs: '', whenTrue: '', whenFalse: '', action: 'NOTIFY_TEAM', serviceId: '', failNext: '' }] };
  await page.evaluate(f => api('/app/api/chatbot/flows', { method: 'PUT', body: JSON.stringify({ flows: [f] }) }), flow);
  await page.reload({ waitUntil: 'networkidle2' });
  await pause(1500);
  await openPersona();
  await tab('channels');
  await page.waitForSelector('#persona-channels-form');
  expect((await text('[data-channel="INSTAGRAM"]')).includes('Connected') && (await text('#pc-chatbot')).includes('1 flow is on'), 'with Instagram connected and a flow on, the tab says so');
  await click('#pc-ai-INSTAGRAM', 600);
  expect((await text('[data-channel="INSTAGRAM"]')).includes('your chatbot flows still answer'), 'turning the AI off on Instagram says the chatbot flows still answer');
  await click('[data-persona-save]', 1300);
  expect((await page.evaluate(() => api('/app/api/persona/channels'))).teamOnlyChannels.join(',') === 'INSTAGRAM', 'the AI switch is saved');
  expect((await page.evaluate(() => api('/app/api/chatbot/flows'))).flows.length === 1, 'and the chatbot flow is left alone');
  await page.reload({ waitUntil: 'networkidle2' });
  await pause(1500);
  await openPersona();
  await tab('channels');
  await page.waitForSelector('#pc-ai-INSTAGRAM');
  expect(await page.$eval('#pc-ai-INSTAGRAM', el => !el.checked), 'the channel settings read back after a reload');
  await shot('channels');

  note('9b. Start words: new chats only get answers when they begin with one');
  expect((await page.$$('[data-gate]')).length === 2 && !!(await page.$('[data-channel="WHATSAPP"] [data-gate]')) && !!(await page.$('[data-channel="INSTAGRAM"] [data-gate]')), 'Instagram and WhatsApp each offer start words in their row');
  expect(!(await page.$('[data-gate-tokens]')) && !(await page.$eval('#pc-gate-WHATSAPP-on', el => el.checked)), 'both are off, so every new chat is answered');
  await click('#pc-gate-WHATSAPP-on', 700);
  expect(await page.evaluate(() => document.activeElement?.id) === 'pc-gate-WHATSAPP-words', 'switching WhatsApp on puts the cursor in its start words');
  expect(await page.$eval('[data-persona-savebar]', el => el.classList.contains('is-dirty')), 'and counts as an unsaved change');
  await click('[data-persona-save]', 300);
  expect((await toast()).includes('WhatsApp: add at least one start word'), 'a gate with no word is caught before saving');
  expect((await text('[data-gate-issue="WHATSAPP"]')).startsWith('Add at least one start word'), 'and says so under the field');
  await type('#pc-gate-WHATSAPP-words', 'quote\nBook a visit', 20);
  expect((await page.$$('[data-gate-tokens="WHATSAPP"] .token')).length === 1 && await page.$eval('#pc-gate-WHATSAPP-words', el => el.value) === 'Book a visit', 'Enter adds a start word token');
  await type('#pc-gate-WHATSAPP-words', '\nQUOTE\n', 20);
  expect((await text('[data-gate-notes="WHATSAPP"]')).includes('“quote” is already a start word') && (await page.$$('[data-gate-tokens="WHATSAPP"] .token')).length === 2, 'a repeat is refused, ignoring capitals, and names the word already there');
  await shot('start-words-draft');
  await click('[data-persona-save]', 1300);
  const gateSaved = await page.evaluate(async () => (await api('/app/api/persona/channels')).startGates);
  expect(JSON.stringify(gateSaved) === JSON.stringify([{ channel: 'WHATSAPP', enabled: true, words: ['quote', 'Book a visit'] }]), `saving keeps the words, the one being typed included (${JSON.stringify(gateSaved)})`);
  await click('#persona-chat-clear', 300);
  await type('#persona-chat-input', 'Do you open on Sundays?', 20);
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.querySelectorAll('#persona-chat-log .chat__note').length > 0, { timeout: 20000 });
  await pause(500);
  expect((await text('#persona-chat-log')).includes('No reply on WhatsApp'), 'the test chat says a new chat opening like that gets no reply on WhatsApp');

  // Two new customers write on WhatsApp: one without a start word, one with.
  expect(await whatsappMessage(company.phone, 'Hi, do you open on Saturdays?', '351913000001', 'Rui Gate') === 200, 'a new customer writes without a start word');
  expect(await whatsappMessage(company.phone, 'I would like a QUOTE please', '351913000002', 'Eva Quote') === 200, 'another writes with one');
  await pause(5000);
  await page.reload({ waitUntil: 'networkidle2' });
  await pause(1500);
  await click('.nav__item[data-tab="conversations"]', 1500);
  await click('[data-inbox-filter="all"]', 800);
  const open = async name => {
    await page.$$eval('.inbox-row', (rows, n) => rows.find(r => r.innerText.includes(n))?.click(), name);
    await pause(1500);
  };
  await open('Rui Gate');
  expect((await text('.thread-banner')).includes('This chat began without a start word'), 'the chat without a start word says why the bot kept quiet');
  expect((await text('.thread-head__tools')).includes('AI paused') && (await text('#inbox-log')).includes('do you open on Saturdays'), 'its message waits in the inbox with the AI paused');
  expect(!(await text('.thread-banner')).includes('Paused by'), 'it is not shown as paused by a person');
  await shot('inbox-start-gate');
  await open('Eva Quote');
  expect(!(await page.$('.thread-banner')) && (await text('.thread-head__tools')).includes('AI replying'), 'the chat that opened with a start word runs as usual');
  await open('Rui Gate');
  await click('[data-ai-toggle="on"]', 1500);
  expect((await text('.thread-head__tools')).includes('AI replying') && !(await page.$('.thread-banner')), 'Resume AI lets the bot answer that chat from now on');

  note('10. Dark theme, a narrow window and a phone');
  await openPersona();
  await tab('overview');
  await click('#btn-theme', 1200);
  await shot('overview-dark');
  await tab('profile');
  await shot('dark');
  await tab('rules');
  await shot('rules-dark');
  await tab('channels');
  await shot('channels-dark');
  await click('#btn-theme', 800);
  await tab('profile');
  const cdp = await page.target().createCDPSession();
  const { windowId } = await cdp.send('Browser.getWindowForTarget');
  await cdp.send('Browser.setWindowBounds', { windowId, bounds: { width: 820, height: 1100 } });
  await pause(1500);
  expect(!(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)), 'nothing scrolls sideways at 820px');
  expect(await page.evaluate(() => document.querySelector('.persona-studio__test').getBoundingClientRect().top >= document.querySelector('.persona-studio__main').getBoundingClientRect().bottom - 1), 'the test chat moves below the editor');
  await shot('narrow');
  await cdp.send('Browser.setWindowBounds', { windowId, bounds: { left: 0, top: 0, width: 1920, height: 1160 } });
  await pause(1000);
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 375, height: 812, deviceScaleFactor: 2, mobile: true });
  await pause(1500);
  for (const id of ['overview', 'profile', 'knowledge', 'rules', 'channels']) {
    await tab(id);
    expect(!(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)), `${id} scrolls no sideways on a 375px phone`);
    await shot(`${id}-phone`);
  }
  await page.$eval('#pc-channels', el => el.scrollIntoView({ block: 'start' }));
  await pause(500);
  expect((await page.$$eval('[data-gate]', els => els.map(el => Math.round(el.getBoundingClientRect().left)))).every((x, _, all) => x === all[0]), 'the channels stack in one column on a phone');
  await shot('channels-phone-rows');
  await cdp.send('Emulation.clearDeviceMetricsOverride');
  await pause(1000);

  note('11. A member');
  await click('#btn-logout', 1000);
  await login(company.member);
  await openPersona();
  expect((await notices()).includes('Only admins change the persona'), 'members are told why they can\'t edit');
  expect(!(await page.$('[data-persona-savebar]')), 'members get no save bar');
  await tab('profile');
  expect(await page.$eval('#pb-botName', el => el.disabled) && await page.$eval('input[name="pb-tone"]', el => el.disabled), 'the profile is read-only');
  await tab('rules');
  expect(await page.$eval('#pr-0', el => el.disabled) && !(await page.$('[data-rule-add], [data-rule-remove]')), 'rules are read-only');
  await tab('knowledge');
  expect(!(await page.$('#persona-note-form')) && (await page.$$('[data-del-source]')).length === 0 && !(await page.$('[data-faq-add]')), 'no adding or removing documents or questions');
  await tab('history');
  await page.waitForSelector('[data-version]');
  expect((await page.$$('[data-restore]')).length === 0, 'no restoring');
  await tab('channels');
  await page.waitForSelector('#persona-channels-form');
  expect(await page.$eval('#pc-ai-WHATSAPP', el => el.disabled) && !(await page.$('[data-gate-remove]')), 'the channel settings are read-only');
  await tryChip('who');
  expect((await text('#persona-chat-log')).includes("I'm Sofia"), 'members still use the test chat');
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
