// Browser walkthrough of the Chatbot page against a running app, checking each step.
//
// It creates its own company and users through the backoffice API, then drives Chrome through the page's
// empty state and builds a flow on the board by hand (keywords as tokens, a message with options that lead to
// new steps, a question that keeps its answer, a client-creating action and a hand-over), opening cards beside
// the board, linking them by dragging a card's dot onto another, moving cards with undo and redo, dropping a line
// on the empty board for a new step and unlinking a line. It tries the flow unsaved in the test chat on WhatsApp
// and on the website (taps versus numbered lines) while the board lights up the way the customer went, saves it
// with its cards' places, checks a refused save, a reload, Tidy up and the Persona → Channels pointer. It then
// installs a richer flow through the API (opening hours, a known client, questions with checks, a client, a
// booking request and a team notice) and runs it with signed WhatsApp webhooks, reading back what the bot stored,
// the client it created, the notice in the bell and a hand-over that pauses the chat. Last: dark theme, a phone,
// and a member's read-only view.
//
// Run the app with a password for the backoffice (its Mongo and OpenRouter can be fakes: no flow here calls
// the model), then:
//   cd scripts/chatbot-e2e && npm install && \
//   ADMIN_PASSWORD=… WA_APP_SECRET=… node walkthrough.mjs
// Optional: APP_URL (http://localhost:8080), CHROME (google-chrome), HEADLESS=1, OUT (./out),
// SLOW (ms between steps, 0 by default; ~800 makes it watchable).
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
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
const note = (...a) => console.log(a.join(' '));
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
  const slug = `chatbot-e2e-${run}`;
  const phone = `chatbot-e2e-phone-${run}`;
  await http('POST', '/admin/api/tenants', token, {
    name: 'Clínica Sorriso', slug, locale: 'en', timezone: 'Europe/Lisbon',
    channels: [{ platform: 'WHATSAPP', externalId: phone, accessToken: 'chatbot-e2e' }],
  });
  const admin = { email: `admin-${run}@sorriso.test`, password: `admin-${run}-pw` };
  const member = { email: `member-${run}@sorriso.test`, password: `member-${run}-pw` };
  await http('POST', `/admin/api/tenants/${slug}/dashboard-users`, token, { ...admin, role: 'TENANT_ADMIN' });
  await http('POST', `/admin/api/tenants/${slug}/dashboard-users`, token, { ...member, role: 'TENANT_MEMBER' });
  return { slug, phone, admin, member, backoffice: token };
}

/** What Meta posts when a customer writes to the company's number, signed with the app secret. */
async function whatsappMessage(phone, from, name, text) {
  const body = JSON.stringify({
    object: 'whatsapp_business_account',
    entry: [{ id: 'waba', changes: [{ field: 'messages', value: {
      messaging_product: 'whatsapp', metadata: { display_phone_number: '351210000000', phone_number_id: phone },
      contacts: [{ profile: { name }, wa_id: from }],
      messages: [{ from, id: `wamid.chatbot-e2e.${from}.${Date.now()}.${Math.random().toString(36).slice(2, 6)}`, timestamp: String(Math.floor(Date.now() / 1000)), type: 'text', text: { body: text } }],
    } }] }],
  });
  const signature = crypto.createHmac('sha256', WA_APP_SECRET).update(body).digest('hex');
  const res = await fetch(`${APP}/webhook`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Hub-Signature-256': `sha256=${signature}` }, body });
  return res.status;
}

const step = (id, o = {}) => ({
  id, kind: 'MESSAGE', text: '', options: [], show: 'AUTO', listLabel: '', retryText: '', next: '', key: '', expect: 'TEXT', check: 'HOURS', hours: [],
  answerKey: '', answerIs: '', whenTrue: '', whenFalse: '', action: 'NOTIFY_TEAM', serviceId: '', failNext: '', ...o,
});

/** The richer flow the webhook part runs: hours, a known client, two questions, a client, a notice and a hand-over. */
function richFlow() {
  const weekdays = [1, 2, 3, 4, 5, 6, 7].map(day => ({ day, from: '00:00', to: '23:59' }));
  return {
    id: 'rich', name: 'Reception', enabled: true, channels: ['WHATSAPP'], keywords: ['reception'], aiReads: false, afterMisses: 'HAND_OVER', handoverText: 'A colleague will reply shortly.',
    steps: [
      step('open', { kind: 'CONDITION', check: 'HOURS', hours: weekdays, whenTrue: 'known', whenFalse: 'closed' }),
      step('closed', { text: 'We are closed right now.' }),
      step('known', { kind: 'CONDITION', check: 'KNOWN_CLIENT', whenTrue: 'back', whenFalse: 'menu' }),
      step('back', { text: 'Welcome back!', next: 'menu' }),
      step('menu', { text: 'Reply 1 to leave your details, or 2 to talk to a person.', show: 'HIDDEN', retryText: 'Please reply 1 or 2.',
        options: [{ title: 'Leave my details', next: 'name', words: ['details'] }, { title: 'Talk to a person', next: 'person', words: ['person', 'human'] }] }),
      step('name', { kind: 'ASK', text: 'What is your name?', key: 'name', expect: 'NAME', next: 'service' }),
      step('service', { kind: 'ASK', text: 'Which service, {name}?', key: 'service', next: 'save' }),
      step('save', { kind: 'ACTION', action: 'CREATE_CLIENT', next: 'tell', failNext: 'sorry' }),
      step('tell', { kind: 'ACTION', action: 'NOTIFY_TEAM', text: 'Thanks {name}! We have your details for {service}.' }),
      step('sorry', { text: 'We could not save your details.' }),
      step('person', { kind: 'HANDOFF', text: 'Passing you to a colleague.' }),
    ],
  };
}

/** A campaign flow in Portuguese: choices kept under keys, a branch per pain point, a lead with explicit consent. */
function campaignFlow() {
  const pick = (title, next) => ({ title, next, words: [] });
  const choice = (id, text, key, options, next) => step(id, { text, key, options: options.map(o => pick(o, next)), listLabel: 'Escolher' });
  const reply = (id, text) => step(id, { text, next: 'qualify' });
  return {
    id: 'bbc', name: 'BBC × The Bots Lab', enabled: true, channels: ['WHATSAPP'], keywords: ['BBC'], aiReads: false, retries: 1, afterMisses: 'HAND_OVER', handoverText: 'Já lhe respondemos.',
    steps: [
      step('start', { text: 'Olá! Bem-vindo à experiência BBC × The Bots Lab! Preparado?', options: [pick('Vamos lá!', 'area'), pick('Agora não', 'bye')] }),
      step('bye', { text: 'Sem problema! Quando quiseres, escreve BBC.' }),
      choice('area', 'Primeiro, conta-me: em que área trabalhas?', 'business_type', ['Estética', 'Cabelo / Barbearia', 'Unhas e pestanas', 'Outro de beleza'], 'pain'),
      step('pain', { text: 'Qual destas situações te dá mais vontade de marcar uma massagem relaxante?', key: 'pain_point', listLabel: 'Escolher', options: [
        pick('Sem resposta', 'r1'), pick('Marcações', 'r2'), pick('Pedidos de preço', 'r3'), pick('Falta de tempo', 'r4')] }),
      reply('r1', 'Os teus clientes não gostam de esperar! Imagina ter uma IA a responder 24/7.'),
      reply('r2', 'A tua agenda parece um Tetris? Um assistente pode organizar as marcações.'),
      reply('r3', 'O clássico "quanto custa?" seguido de desaparecimento. Um bot acompanha potenciais clientes.'),
      reply('r4', 'És especialista em beleza, não em responder 47 vezes à mesma pergunta!'),
      choice('qualify', 'Se pudesses delegar uma tarefa à IA hoje, qual seria?', 'automation_interest', ['Responder', 'Gerir marcações', 'Follow-up', 'Ainda não sei'], 'lead'),
      step('lead', { text: 'Gostavas que a equipa da The Bots Lab entrasse em contacto contigo?', options: [pick('Sim, quero!', 'name'), pick('Agora não', 'thanks')] }),
      step('name', { kind: 'ASK', text: 'Qual é o teu nome?', key: 'name', expect: 'NAME', next: 'email' }),
      step('email', { kind: 'ASK', text: 'E o teu email?', key: 'email', expect: 'EMAIL', next: 'consent' }),
      step('consent', { kind: 'ASK', text: 'Aceitas ser contactado(a) para fins comerciais? Política de privacidade: https://thebotslab.pt/privacidade (sim ou não)', key: 'consent', expect: 'YES_NO', next: 'agreed' }),
      step('agreed', { kind: 'CONDITION', check: 'ANSWER', answerKey: 'consent', answerIs: 'yes', whenTrue: 'save', whenFalse: 'thanks' }),
      step('save', { kind: 'ACTION', action: 'CREATE_CLIENT', next: 'tell', failNext: 'end' }),
      step('tell', { kind: 'ACTION', action: 'NOTIFY_TEAM', next: 'end' }),
      step('thanks', { text: 'Obrigado pela visita! Não insistimos.' }),
      step('end', { text: 'Experiência concluída! Tu cuidas da beleza dos teus clientes. Nós ajudamos a cuidar do resto.' }),
    ],
  };
}

const company = await createCompany();
note(`Company ${company.slug}`);
const { token } = await http('POST', '/app/auth/login', null, company.admin);
const api = (method, url, body) => http(method, url, token, body);

const profile = path.join(OUT, 'chrome');
fs.rmSync(profile, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });
const browser = await puppeteer.launch({
  executablePath: process.env.CHROME || 'google-chrome',
  headless: process.env.HEADLESS === '1' ? 'new' : false,
  defaultViewport: null,
  args: ['--no-sandbox', '--no-first-run', '--no-default-browser-check', `--user-data-dir=${profile}`, '--window-position=0,0', '--window-size=1600,1100'],
});
const page = (await browser.pages())[0] || await browser.newPage();
const errors = [];
page.on('pageerror', e => errors.push(e.message));
// A 400 is the refused save this walkthrough provokes on purpose.
page.on('console', m => { if (m.type() === 'error' && !(m.location()?.url || '').includes('favicon') && !m.text().includes('400 (Bad Request)')) errors.push(m.text()); });

let shots = 0;
const shot = name => page.screenshot({ path: path.join(OUT, `${String(++shots).padStart(2, '0')}-${name}.png`) });
const text = sel => page.$eval(sel, el => el.innerText.replace(/\s+/g, ' ').trim()).catch(() => '');
const click = async (sel, wait = 700) => { await page.waitForSelector(sel, { visible: true, timeout: 15000 }); await page.$eval(sel, el => el.scrollIntoView({ block: 'center' })); await pause(100); await page.click(sel); await pause(wait); };
const type = async (sel, value, delay = 12) => { await page.waitForSelector(sel, { visible: true }); await page.$eval(sel, el => el.scrollIntoView({ block: 'center' })); await page.click(sel); await page.type(sel, value, { delay }); await pause(150); };
const toast = async () => { await pause(250); return page.$eval('#toast', el => (el.hidden ? '' : el.innerText.trim())).catch(() => ''); };
const confirm = async () => { await page.waitForSelector('#confirm:not([hidden])', { timeout: 10000 }); await pause(500); await click('#confirm-ok', 300); };
/** Chooses the option of a <select> whose text contains [label]. */
const choose = async (sel, label) => {
  await page.waitForSelector(sel, { timeout: 15000 });
  await page.$eval(sel, el => el.scrollIntoView({ block: 'center' }));
  const value = await page.$eval(sel, (el, l) => [...el.options].find(o => o.textContent.includes(l))?.value, label);
  if (value == null) throw new Error(`${sel} has no option with “${label}”`);
  await page.select(sel, value);
  await pause(500);
};
const bubbles = () => page.$$eval('#chatbot-chat-log .chat__msg, #chatbot-chat-log .chat__note', els => els.map(e => e.innerText.replace(/\s+/g, ' ').trim()));
const chat = async (msg, wait = 1500) => {
  const before = (await bubbles()).length;
  await type('#chatbot-chat-input', msg);
  await page.keyboard.press('Enter');
  await page.waitForFunction(n => document.querySelectorAll('#chatbot-chat-log .chat__msg:not(.chat__typing), #chatbot-chat-log .chat__note').length > n + 1, { timeout: 20000 }, before).catch(() => {});
  await pause(wait);
  return bubbles();
};
const openChatbot = () => click('.nav__item[data-tab="chatbot"]', 1500);
const login = async ({ email, password }) => {
  await page.goto(`${APP}/app/`, { waitUntil: 'networkidle2' });
  await type('#email', email, 20);
  await type('#password', password, 20);
  await page.click('#login-form button[type=submit]');
  await page.waitForSelector('.nav__item[data-tab="chatbot"]', { timeout: 15000 });
  await pause(1000);
};

// The board: step j of the first flow is card `#cb-node-0-j` (the start card `#cb-node-0-start`); a line is
// `[data-edge="<step id>|<output>"]`, an output being `o:<n>` (an option), next, yes, no or fail.
const card = (j, flow = 0) => `#cb-node-${flow}-${j}`;
const stepId = j => page.$eval(card(j), el => el.dataset.node);
/** Opens a card in the side panel, as a click on it does. */
const openCard = async (sel, wait = 500) => { await page.waitForSelector(`${sel} .flow-node__head`, { timeout: 15000 }); await page.$eval(`${sel} .flow-node__head`, el => el.click()); await pause(wait); };
const openSettings = () => openCard(card('start'));
const sideTab = tab => click(`.chatbot-side__tabs [data-side-go="${tab}"]`, 500);
const fitBoard = async () => { await page.$eval('[data-board-fit]', el => el.click()); await pause(300); };
const centerOf = sel => page.$eval(sel, el => { const r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; });
const edges = () => page.$$eval('[data-edge]', gs => gs.map(g => g.dataset.edge));
const unsaved = () => page.$eval('[data-chatbot-savebar]', el => el.classList.contains('is-dirty'));
const placeOf = sel => page.$eval(sel, el => el.style.transform);
/** Presses at [from], moves to [to] in small steps and lets go: a real drag, as a mouse makes it. */
const drag = async (from, to, steps = 14) => {
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  for (let i = 1; i <= steps; i++) await page.mouse.move(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps);
  await page.mouse.up();
  await pause(500);
};
/** A point of the board with nothing on it, clear of its toolbars. */
const emptySpot = () => page.$eval('[data-board]', canvas => {
  const r = canvas.getBoundingClientRect();
  for (let y = r.bottom - 90; y > r.top + 70; y -= 24) {
    for (let x = r.right - 60; x > r.left + 60; x -= 24) {
      const under = document.elementFromPoint(x, y);
      if (under === canvas) return { x, y };
    }
  }
  return null;
});
/** The middle of a line, on screen. */
const lineMiddle = id => page.$eval(`[data-edge="${id}"] .flow-edge__line`, p => {
  const at = p.getPointAtLength(p.getTotalLength() / 2);
  const m = p.getScreenCTM();
  return { x: at.x * m.a + m.e, y: at.y * m.d + m.f };
});
const lit = () => page.evaluate(() => ({
  current: [...document.querySelectorAll('.flow-node.is-current')].map(e => e.id),
  trail: [...document.querySelectorAll('.flow-edge.is-trail')].map(e => e.dataset.edge),
}));

try {
  note('1. The Chatbot page, empty');
  await login(company.admin);
  expect((await text('.nav__group:has(.nav__item[data-tab="chatbot"]) .nav__group-label')) === 'Automation', 'the Chatbot page sits in the Automation group of the nav');
  await openChatbot();
  expect((await text('.view__title, h1')).includes('Chatbot'), 'the page is called Chatbot');
  expect((await text('.empty__title')) === 'No flows yet', 'a new company has no flows');
  expect((await text('.chatbot-side')).includes('Anything no flow answers goes to the AI'), 'the side panel says how the chatbot works and what answers what is left');
  expect(await page.$eval('[data-chatbot-savebar]', el => !el.classList.contains('is-dirty')), 'the save bar rests with nothing unsaved');
  await shot('empty');

  note('2. Building a flow on the board');
  await click('[data-flow-add]', 800);
  expect(await page.evaluate(() => document.activeElement?.id) === 'cb-0-name', 'Add flow opens the flow’s settings with the cursor in its name');
  expect((await page.$$('[data-node]')).length === 2, 'a new flow is a start card and one message on the board');
  expect((await text(card('start'))).includes('No keywords yet') && (await text(card('start'))).includes('2 to fix'), 'the start card says what the flow still needs');
  await type('#cb-0-name', 'Appointments');
  await type('#cb-0-keywords', 'book');
  await page.keyboard.press('Enter');
  await pause(300);
  await type('#cb-0-keywords', 'agendar');
  await page.keyboard.press('Enter');
  await pause(300);
  expect((await page.$$eval('[data-tokens="0"] .token__text', ts => ts.map(t => t.innerText.trim()))).join(',') === 'book,agendar', 'keywords become tokens');
  expect((await page.$$eval(`${card('start')} .flow-node__chip`, cs => cs.map(c => c.innerText.trim()))).join(',') === 'book,agendar', 'and show on the start card');
  await type('#cb-0-keywords', 'BOOK!');
  await page.keyboard.press('Enter');
  await pause(300);
  expect((await text('[data-kw-notes="0"]')).includes('already a keyword'), 'a repeated keyword is refused with a note');
  await click('#cb-0-enabled', 300);
  expect(await page.$eval('[data-flow-pills="0"]', el => el.classList.contains('is-on')), 'turning the flow on shows on its tab at once');
  expect((await text('[data-chatbot-status]')).includes('Unsaved changes'), 'the save bar says there are unsaved changes');
  expect(await page.$eval('#cb-0-retries', el => el.value) === '1' && await page.$eval('#cb-0-after', el => el.value) === 'HAND_OVER', 'a new flow asks once more, then hands over');

  await openCard(card(0));
  expect(await page.$eval('.chatbot-inspector', el => el.dataset.step) === '0:0', 'clicking a card opens its step beside the board');
  await type('#cb-0-0-text', 'Hi! What do you need?');
  expect((await text(`${card(0)} .flow-node__text`)) === 'Hi! What do you need?', 'the card shows the text as it is typed');
  await click('[data-option-add="0:0"]', 400);
  await type('#cb-0-0-o0', 'Nails');
  await choose('#cb-0-0 [data-option="0:0:0"] select', 'New step');
  expect(await page.evaluate(() => document.activeElement?.id) === 'cb-0-1-text', 'choosing “New step…” adds a step, opens it and puts the cursor in its text');
  const [first, second] = [await stepId(0), await stepId(1)];
  expect((await edges()).includes(`${first}|o:0`), 'and the board draws the line from the option to it');
  await choose('#cb-0-1-kind', 'Question');
  await type('#cb-0-1-text', 'What is your name?');
  await type('#cb-0-1-key', 'name');
  await page.keyboard.press('Tab');
  await pause(600);
  expect(await page.$eval('#cb-0-1-expect', el => el.value) === 'NAME', 'a question named “name” expects a name');
  await openCard(card(0));
  await click('[data-option-add="0:0"]', 400);
  await type('#cb-0-0-o1', 'Talk to a person');
  await choose('#cb-0-0 [data-option="0:0:1"] select', 'New step');
  await choose('#cb-0-2-kind', 'Hand over');
  await type('#cb-0-2-text', 'A colleague will reply shortly.');
  await click('[data-board-add]', 400);
  expect((await page.$$eval('[data-board-menu] [data-menu-kind]', ms => ms.map(m => m.dataset.menuKind))).join(',') === 'MESSAGE,ASK,CONDITION,ACTION,HANDOFF', 'Add a step offers every kind of step');
  await click('[data-board-menu] [data-menu-kind="ACTION"]', 700);
  expect(await page.$eval('.chatbot-inspector', el => el.dataset.step) === '0:3', 'and opens the new one');
  await choose('#cb-0-3-action', 'Create a client');
  await type('#cb-0-3-text', 'Thanks {name}, you are on our list!');
  const action = await stepId(3);
  await fitBoard();
  await drag(await centerOf(`${card(1)} [data-port="next"]`), await centerOf(`${card(3)} .flow-node__head`));
  expect((await edges()).includes(`${second}|next`), 'dragging the question’s dot onto the action’s card links them');
  await openCard(card(1));
  expect((await page.$eval('#cb-0-1-next', el => el.selectedOptions[0]?.textContent || '')).startsWith('4 · Thanks'), 'and the question’s “Then go to” says so');
  await openSettings();
  expect((await text('[data-flow-summary="0"]')).includes('4 steps') && (await text('[data-flow-summary="0"]')).includes('WhatsApp'), 'the flow’s settings sum it up: keywords, steps, channels');
  await shot('built');

  note('2b. Arranging the board');
  await fitBoard();
  const handover = card(2);
  const before = await placeOf(handover);
  const head = await centerOf(`${handover} .flow-node__head`);
  await drag(head, { x: head.x + 40, y: head.y + 90 });
  expect((await placeOf(handover)) !== before, 'dragging a card moves it');
  await click('[data-board-undo]', 500);
  expect((await placeOf(handover)) === before, 'undo puts it back');
  await click('[data-board-redo]', 500);
  const moved = await placeOf(handover);
  expect(moved !== before, 'redo moves it again');
  await fitBoard();
  const spot = await emptySpot();
  await drag(await centerOf(`${card(3)} [data-port="fail"]`), spot);
  expect(await page.$eval('[data-board-menu]', m => !m.hidden && m.textContent.includes('Lead to a new step')), 'a line dropped on the empty board asks for a new step');
  await shot('drop-menu');
  await click('[data-board-menu] [data-menu-kind="MESSAGE"]', 700);
  const sorry = await stepId(4);
  expect((await edges()).includes(`${action}|fail`) && await page.$eval(`[data-edge="${action}|fail"]`, (g, id) => g.ownerDocument.querySelector(`[data-node="${id}"]`) != null, sorry), 'and the new step is linked from the dot the line came from');
  expect(await page.$eval('.chatbot-inspector', el => el.dataset.step) === '0:4', 'the new step opens beside the board');
  await type('#cb-0-4-text', 'Sorry, we could not save your details.');
  await fitBoard();
  const failLine = `${action}|fail`;
  const middle = await lineMiddle(failLine);
  await page.mouse.click(middle.x, middle.y);
  await pause(400);
  expect(await page.$eval(`[data-edge="${failLine}"]`, g => g.classList.contains('is-selected')) && await page.$eval('[data-edge-remove]', b => !b.hidden), 'clicking a line picks it and offers to unlink it');
  await click('[data-edge-remove]', 500);
  expect(!(await edges()).includes(failLine), 'unlinking removes the line');
  expect(await page.$eval(`${card(3)} [data-port="fail"]`, p => !p.classList.contains('is-linked')), 'and empties its dot');
  await page.$eval('[data-board]', el => el.focus());
  await page.keyboard.down('Control');
  await page.keyboard.press('z');
  await page.keyboard.up('Control');
  await pause(500);
  expect((await edges()).includes(failLine), 'Ctrl+Z on the board brings the line back');
  await shot('arranged');

  note('3. Trying it unsaved on WhatsApp and on the website, while the board follows');
  await sideTab('try');
  expect(await page.$eval('#chatbot-test-draft', el => !el.hidden), 'the test chat says it uses unsaved changes');
  let log = await chat('I want to book');
  expect(log.includes('Hi! What do you need?'), 'a keyword starts the flow in the test chat');
  let way = await lit();
  expect(way.current.join() === 'cb-node-0-0' && way.trail.includes('__start|start'), 'the board lights the start and the message the customer waits at');
  expect((await text(card(0))).includes('The customer is here'), 'and says so on its card');
  expect((await page.$$eval('#chatbot-chat-log .chip', cs => cs.map(c => c.innerText.trim()))).join(',') === 'Nails,Talk to a person', 'WhatsApp gets its two options as taps');
  await click('#chatbot-chat-log .chip', 1500);
  log = await bubbles();
  expect(log.includes('Nails') && log.includes('What is your name?'), 'tapping an option moves the flow on');
  way = await lit();
  expect(way.current.join() === 'cb-node-0-1' && way.trail.includes(`${first}|o:0`), 'and the light follows the option taken');
  log = await chat('Maria Costa', 2200);
  expect(log.some(l => l.includes('Thanks Maria Costa, you are on our list!')), 'the answer is filled into the next message');
  expect(log.some(l => l.startsWith('Would create a client: Maria Costa')), 'the test chat says what the action would do, without doing it');
  expect(log.includes('The flow ended.'), 'and that the flow ended');
  expect((await lit()).trail.includes(`${second}|next`), 'the board shows the whole way, to the action');
  expect((await api('GET', '/app/api/crm/clients')).length === 0, 'trying a flow created no client');
  await shot('test-chat-whatsapp');
  await click('#chatbot-chat-clear', 300);
  way = await lit();
  expect(!way.current.length && !way.trail.length, 'Start over clears the way lit on the board');
  await sideTab('edit');
  await openSettings();
  await click('#cb-0 [data-channel="WEB"]', 300);
  await sideTab('try');
  await page.select('#chatbot-channel', 'WEB');
  await pause(300);
  log = await chat('book');
  expect(log.some(l => l.includes('1. Nails') && l.includes('2. Talk to a person')), 'on the website the options are numbered lines');
  expect((await page.$$('#chatbot-chat-log .chip')).length === 0, 'with no taps');
  log = await chat('2');
  expect(log.some(l => l.includes('A colleague will reply shortly.')) && log.some(l => l.startsWith('Handed to your team')), 'typing the number chooses the option, and the hand-over is reported');
  await page.select('#chatbot-channel', 'WHATSAPP');
  await click('#chatbot-chat-clear', 300);
  await chat('book');
  log = await chat('hmm banana');
  expect(log.filter(l => l.includes('Hi! What do you need?')).length === 2, 'a reply nothing can read gets the options again');
  log = await chat('still no idea');
  expect(log.some(l => l.startsWith('Handed to your team')), 'and the next one hands the chat to the team');
  await click('#chatbot-chat-clear', 300);

  note('4. Saving, and a save the server refuses');
  await sideTab('edit');
  await openSettings();
  await click('[data-kw-remove="0:1"]', 300);
  await click('[data-kw-remove="0:0"]', 300);
  await click('[data-chatbot-save]', 900);
  expect((await toast()).includes('add at least one keyword'), `a flow with no keyword is refused, saying so (“${await toast()}”)`);
  expect(await page.evaluate(() => document.activeElement?.id) === 'cb-0-keywords', 'and the cursor goes to the flow’s keywords');
  await type('#cb-0-keywords', 'book');
  await page.keyboard.press('Enter');
  await pause(300);
  await click('[data-chatbot-save]', 1200);
  expect((await toast()) === 'Saved', 'saving works once it is fixed');
  expect(await page.$eval('[data-chatbot-savebar]', el => !el.classList.contains('is-dirty')), 'and the save bar rests again');
  const saved = await api('GET', '/app/api/chatbot/flows');
  expect(saved.flows.length === 1 && saved.flows[0].steps.length === 5 && saved.flows[0].keywords.join(',') === 'book', 'the flow is stored with its five steps');
  expect(Number.isFinite(saved.flows[0].startX) && saved.flows[0].steps.every(s => Number.isFinite(s.x) && Number.isFinite(s.y)), 'with where each card sits on the board');
  await page.reload({ waitUntil: 'networkidle2' });
  await page.waitForSelector('.nav__item[data-tab="chatbot"]');
  await pause(1500);
  expect((await text('[data-flow-title="0"]')) === 'Appointments' && await page.$eval('[data-flow-pills="0"]', el => el.classList.contains('is-on')), 'after a reload the page is on Chatbot and shows the saved flow');
  expect((await placeOf(handover)) === moved, 'with its cards where they were put');
  await shot('saved');

  note('4b. A refused save opens the step it names; Tidy up');
  await openCard(card(1));
  await page.$eval('#cb-0-1-text', el => { el.value = ''; el.dispatchEvent(new Event('input', { bubbles: true })); });
  await pause(300);
  expect((await text(`${card(1)} .flow-node__flag`)) === '1 to fix' && (await text('[data-step-issues]')).includes('Write the message.'), 'the board flags a step that can’t be saved before trying');
  await openSettings();
  await click('[data-chatbot-save]', 1000);
  expect((await toast()).includes('step 2'), `the refused save names step 2 (“${await toast()}”)`);
  expect(await page.evaluate(() => document.activeElement?.closest('#cb-0-1') != null), 'and opens that step with the cursor in it');
  await click('[data-chatbot-discard]', 300);
  await confirm();
  await openCard(card(1));
  expect(!(await unsaved()) && (await page.$eval('#cb-0-1-text', el => el.value)) === 'What is your name?', 'discarding brings the text back');
  await click('[data-board-tidy]', 600);
  expect(await unsaved() && (await placeOf(handover)) !== moved, 'Tidy up lays the cards out again, a change to save');
  await click('[data-board-undo]', 500);
  expect(!(await unsaved()) && (await placeOf(handover)) === moved, 'and undo puts them back');

  note('5. Persona → Channels points at the chatbot and leaves the flows alone');
  await click('.nav__item[data-tab="persona"]', 1500);
  await click('[data-persona-tab="channels"]', 1500);
  expect((await text('#pc-chatbot')).includes('1 flow is on'), 'the Channels tab says one flow is on');
  await click('#pc-ai-WHATSAPP', 400);
  await click('[data-persona-save]', 1200);
  expect((await api('GET', '/app/api/chatbot/flows')).flows.length === 1, 'turning the AI off on WhatsApp keeps the flow');
  expect((await api('GET', '/app/api/persona/channels')).teamOnlyChannels.join(',') === 'WHATSAPP', 'and is saved');
  await click('#pc-ai-WHATSAPP', 400);
  await click('[data-persona-save]', 1200);
  await shot('channels');

  note('6. A richer flow, run by real (signed) WhatsApp messages');
  const rich = richFlow();
  await api('PUT', '/app/api/chatbot/flows', { flows: [...saved.flows, rich] });
  const customer = '351911111111';
  const status = await whatsappMessage(company.phone, customer, 'Rita Lopes', 'reception please');
  expect(status === 200, 'the webhook accepts the message');
  const messagesOf = async (wa = customer) => {
    const list = await api('GET', '/app/api/conversations');
    const convo = (list.conversations || list).find(c => c.waId === wa);
    if (!convo) return { convo: null, messages: [] };
    const m = await api('GET', `/app/api/conversations/${convo.id}/messages`);
    return { convo, messages: (m.messages || m).map(x => ({ role: x.role, text: x.text || '', author: x.author, status: x.status })) };
  };
  const waitFor = async (what, predicate, wa = customer) => {
    for (let i = 0; i < 30; i++) { const r = await messagesOf(wa); if (predicate(r)) return r; await pause(400); }
    return messagesOf(wa);
  };
  let r = await waitFor('first reply', x => x.messages.some(m => m.text.includes('Reply 1 to leave your details')));
  expect(r.messages.some(m => m.text.includes('Reply 1 to leave your details') && m.author === 'automation'), 'the keyword runs the hours check and the known-client check, then sends the menu as the flow’s own message');
  expect(!r.messages.some(m => m.text.includes('Welcome back')), 'a customer who isn’t a client yet isn’t welcomed back');
  await whatsappMessage(company.phone, customer, 'Rita Lopes', 'banana');
  r = await waitFor('retry', x => x.messages.some(m => m.text.includes('Please reply 1 or 2')));
  expect(r.messages.some(m => m.text.includes('Please reply 1 or 2') && m.text.includes('Reply 1 to leave')), 'a reply nothing can read gets the retry words, then the menu again');
  await whatsappMessage(company.phone, customer, 'Rita Lopes', '1');
  r = await waitFor('name', x => x.messages.some(m => m.text.includes('What is your name?')));
  expect(r.messages.some(m => m.text === 'What is your name?'), 'typing 1 chooses the first option and asks the question');
  await whatsappMessage(company.phone, customer, 'Rita Lopes', 'My name is Rita Lopes');
  await waitFor('service', x => x.messages.some(m => m.text.includes('Which service, Rita Lopes?')));
  await whatsappMessage(company.phone, customer, 'Rita Lopes', 'Whitening');
  r = await waitFor('thanks', x => x.messages.some(m => m.text.includes('We have your details for Whitening')));
  expect(r.messages.some(m => m.text === 'Thanks Rita Lopes! We have your details for Whitening.'), 'the answers are filled into the closing message');
  const clients = await api('GET', '/app/api/crm/clients');
  expect(clients.some(c => c.name === 'Rita Lopes' && c.phone.includes(customer)), 'the client was created from the answers, with the customer’s own number');
  const bell = await api('GET', '/app/api/notifications');
  expect(JSON.stringify(bell).includes('chatbot_answers') && JSON.stringify(bell).includes('Whitening'), 'the team was told, with the answers');
  await whatsappMessage(company.phone, customer, 'Rita Lopes', 'reception');
  r = await waitFor('welcome back', x => x.messages.some(m => m.text.includes('Welcome back')));
  expect(r.messages.some(m => m.text === 'Welcome back!'), 'the next time she is welcomed back: she is a client now');

  const other = '351922222222';
  await whatsappMessage(company.phone, other, 'Tiago Reis', 'reception');
  await whatsappMessage(company.phone, other, 'Tiago Reis', 'human');
  const handed = await waitFor('hand-over', x => x.convo?.autoReplyPausedBy === 'bot:handoff', other);
  expect(handed.messages.some(m => m.text === 'Passing you to a colleague.'), 'the hand-over step sends its words');
  expect(handed.convo && handed.convo.autoReplyPausedBy === 'bot:handoff', 'and pauses the chat for the team');
  await openChatbot();
  await pause(800);
  await click('[data-flow-pick="1"]', 800);
  expect((await text('[data-flow-summary="1"]')).includes('Sent'), 'the flow’s settings count what it sent in the last 30 days');
  expect((await text('[data-node="menu"] .flow-node__stat')).startsWith('Sent'), 'and each card what its step sent');
  expect((await page.$$('[data-node]')).length === 12, 'a flow made through the API, with no places, is laid out by the board: its start and its eleven steps');
  await shot('rich-flow');

  note('6b. A campaign flow: choices kept as answers');
  const flowsNow = await api('GET', '/app/api/chatbot/flows');
  await api('PUT', '/app/api/chatbot/flows', { flows: [...flowsNow.flows, campaignFlow()] });
  const lead = '351933333333';
  const says = async (msg, waitText) => { await whatsappMessage(company.phone, lead, 'Joana Reis', msg); return waitFor(msg, x => x.messages.some(m => m.text.includes(waitText)), lead); };
  let c = await says('bbc', 'Bem-vindo à experiência');
  expect(c.messages.some(m => m.text.includes('Bem-vindo') && m.author === 'automation'), 'the BBC keyword (any case) starts the campaign');
  c = await says('1', 'em que área trabalhas');
  c = await says('2', 'Qual destas situações');
  c = await says('2', 'Tetris');
  expect(c.messages.some(m => m.text.includes('Tetris')) && !c.messages.some(m => m.text.includes('47 vezes')), 'each pain point gets its own reply');
  expect((await messagesOf(lead)).messages.some(m => m.text.includes('Se pudesses delegar')), 'the branches meet again at the qualification');
  c = await says('3', 'equipa da The Bots Lab');
  c = await says('1', 'Qual é o teu nome');
  c = await says('Joana Reis', 'E o teu email');
  c = await says('joana@salao.test', 'Aceitas ser contactado');
  c = await says('sim', 'Experiência concluída');
  const lists = await api('GET', '/app/api/crm/clients');
  expect(lists.some(x => x.name === 'Joana Reis' && (x.email || '') === 'joana@salao.test'), 'with consent, the lead became a client');
  const bell2 = JSON.stringify(await api('GET', '/app/api/notifications'));
  expect(['business_type: Cabelo / Barbearia', 'pain_point: Marcações', 'automation_interest: Follow-up', 'consent: yes'].every(t => bell2.includes(t)), 'the team notice carries every kept choice');
  const refuser = '351944444444';
  const refuses = async (msg, waitText) => { await whatsappMessage(company.phone, refuser, 'Nuno Reis', msg); return waitFor(msg, x => x.messages.some(m => m.text.includes(waitText)), refuser); };
  await refuses('BBC', 'Bem-vindo'); await refuses('1', 'em que área'); await refuses('4', 'Qual destas'); await refuses('4', 'Se pudesses');
  await refuses('1', 'equipa da The Bots Lab');
  await refuses('Agora não', 'Não insistimos');
  expect(!(await api('GET', '/app/api/crm/clients')).some(x => x.name === 'Nuno Reis'), 'a visitor who says not now is thanked and no client is created');

  note('7. Dark theme and a phone');
  await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'));
  await pause(500);
  await shot('dark');
  await page.evaluate(() => document.documentElement.removeAttribute('data-theme'));
  await page.setViewport({ width: 375, height: 800, isMobile: true, hasTouch: true });
  await page.reload({ waitUntil: 'networkidle2' });
  await pause(1500);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), 'on a phone the page doesn’t scroll sideways');
  await shot('phone-board');
  await openCard(card(0), 700);
  const shows = sel => page.$eval(sel, el => getComputedStyle(el).display !== 'none');
  expect(!(await shows('.chatbot-board')) && await shows('.chatbot-side'), 'a card tapped on a phone opens over the board');
  await shot('phone');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), 'and fits too');
  await click('[data-side-close]', 700);
  expect(await shows('.chatbot-board') && !(await shows('.chatbot-side')), '“Board” goes back to the board');
  await page.setViewport({ width: 1600, height: 1100, isMobile: false, hasTouch: false });

  note('8. A member only reads');
  await page.evaluate(() => { localStorage.removeItem('dashboardToken'); });
  await login(company.member);
  await openChatbot();
  expect(!(await page.$('[data-chatbot-savebar]')) && !(await page.$('[data-flow-add]')) && !(await page.$('[data-board-add]')), 'a member sees no save bar, no Add flow and no Add a step');
  await fitBoard();
  const where = await placeOf(card(0));
  const grip = await centerOf(`${card(0)} .flow-node__head`);
  await drag(grip, { x: grip.x + 60, y: grip.y + 60 });
  expect((await placeOf(card(0))) === where, 'cards stay where they are when a member drags them (the board pans instead)');
  await openCard(card(0));
  expect(await page.$$eval('.chatbot-inspector input:not([type=checkbox]), .chatbot-inspector textarea, .chatbot-inspector select', els => els.length > 0 && els.every(e => e.disabled)), 'a step opens read-only');
  await shot('member');
  expect(errors.length === 0, `no page errors (${errors.join(' | ').slice(0, 300)})`);
} catch (err) {
  failures.push(`stopped by an error: ${err.message}`);
  note(`  ✗ stopped by an error: ${err.stack}`);
  await shot('error').catch(() => {});
} finally {
  await browser.close();
}
note(failures.length ? `\n${failures.length} check(s) failed` : '\nAll checks passed');
process.exit(failures.length ? 1 : 0);
