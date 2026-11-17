// Browser walkthrough of the Chatbot page against a running app, checking each step.
//
// It creates its own company and users through the backoffice API, then drives Chrome through the page's
// empty state, building a flow by hand (keywords as tokens, a message with options that lead to new steps, a
// question that keeps its answer, a client-creating action and a hand-over), trying it unsaved in the test
// chat on WhatsApp and on the website (taps versus numbered lines), saving it, a refused save, a reload, and
// the Persona → Channels pointer. It then installs a richer flow through the API (opening hours, a known
// client, questions with checks, a client, a booking request and a team notice) and runs it with signed
// WhatsApp webhooks, reading back what the bot stored, the client it created, the notice in the bell and a
// hand-over that pauses the chat. Last: dark theme, a phone, and a member's read-only view.
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

try {
  note('1. The Chatbot page, empty');
  await login(company.admin);
  expect((await text('.nav__group:has(.nav__item[data-tab="chatbot"]) .nav__group-label')) === 'Automation', 'the Chatbot page sits in the Automation group of the nav');
  await openChatbot();
  expect((await text('.view__title, h1')).includes('Chatbot'), 'the page is called Chatbot');
  expect((await text('.empty__title')) === 'No flows yet', 'a new company has no flows');
  expect((await text('.persona-studio__main')).includes('Anything no flow answers goes to the AI'), 'the intro says what answers what is left');
  expect(await page.$eval('[data-chatbot-savebar]', el => !el.classList.contains('is-dirty')), 'the save bar rests with nothing unsaved');
  await shot('empty');

  note('2. Building a flow by hand');
  await click('[data-flow-add]', 800);
  expect(await page.evaluate(() => document.activeElement?.id) === 'cb-0-name', 'Add flow opens the flow with the cursor in its name');
  await type('#cb-0-name', 'Appointments');
  await type('#cb-0-keywords', 'book');
  await page.keyboard.press('Enter');
  await pause(300);
  await type('#cb-0-keywords', 'agendar');
  await page.keyboard.press('Enter');
  await pause(300);
  expect((await page.$$eval('[data-tokens="0"] .token__text', ts => ts.map(t => t.innerText.trim()))).join(',') === 'book,agendar', 'keywords become tokens');
  await type('#cb-0-keywords', 'BOOK!');
  await page.keyboard.press('Enter');
  await pause(300);
  expect((await text('[data-kw-notes="0"]')).includes('already a keyword'), 'a repeated keyword is refused with a note');
  await click('#cb-0-enabled', 300);
  expect((await text('[data-flow-pills="0"]')) === 'On', 'turning the flow on shows in its head at once');
  expect((await text('[data-chatbot-status]')).includes('Unsaved changes'), 'the save bar says there are unsaved changes');
  expect(await page.$eval('#cb-0-retries', el => el.value) === '1' && await page.$eval('#cb-0-after', el => el.value) === 'HAND_OVER', 'a new flow asks once more, then hands over');

  await type('#cb-0-0-text', 'Hi! What do you need?');
  await click('[data-option-add="0:0"]', 400);
  await type('#cb-0-0-o0', 'Nails');
  await choose('#cb-0-0 [data-option="0:0:0"] select', 'New step');
  expect(await page.evaluate(() => document.activeElement?.id) === 'cb-0-1-text', 'choosing “New step…” adds a step and puts the cursor in its text');
  await choose('#cb-0-1-kind', 'Question');
  await type('#cb-0-1-text', 'What is your name?');
  await type('#cb-0-1-key', 'name');
  await page.keyboard.press('Tab');
  await pause(600);
  expect(await page.$eval('#cb-0-1-expect', el => el.value) === 'NAME', 'a question named “name” expects a name');
  await click('[data-option-add="0:0"]', 400);
  await type('#cb-0-0-o1', 'Talk to a person');
  await choose('#cb-0-0 [data-option="0:0:1"] select', 'New step');
  await choose('#cb-0-2-kind', 'Hand over');
  await type('#cb-0-2-text', 'A colleague will reply shortly.');
  await choose('[data-step-add="0"]', 'Action');
  await choose('#cb-0-3-action', 'Create a client');
  await type('#cb-0-3-text', 'Thanks {name}, you are on our list!');
  await choose('#cb-0-1-next', '4 · Thanks');
  expect((await text('[data-flow-summary="0"]')).includes('4 steps') && (await text('[data-flow-summary="0"]')).includes('WhatsApp'), 'the head sums the flow up: keywords, steps, channels');
  await page.$eval('#cb-0-ai', el => el.scrollIntoView({ block: 'center' }));
  await pause(300);
  await shot('flow-settings');
  await shot('built');

  note('3. Trying it unsaved on WhatsApp and on the website');
  expect(await page.$eval('#chatbot-test-draft', el => !el.hidden), 'the test chat says it uses unsaved changes');
  let log = await chat('I want to book');
  expect(log.includes('Hi! What do you need?'), 'a keyword starts the flow in the test chat');
  expect((await page.$$eval('#chatbot-chat-log .chip', cs => cs.map(c => c.innerText.trim()))).join(',') === 'Nails,Talk to a person', 'WhatsApp gets its two options as taps');
  await click('#chatbot-chat-log .chip', 1500);
  log = await bubbles();
  expect(log.includes('Nails') && log.includes('What is your name?'), 'tapping an option moves the flow on');
  log = await chat('Maria Costa', 2200);
  expect(log.some(l => l.includes('Thanks Maria Costa, you are on our list!')), 'the answer is filled into the next message');
  expect(log.some(l => l.startsWith('Would create a client: Maria Costa')), 'the test chat says what the action would do, without doing it');
  expect(log.includes('The flow ended.'), 'and that the flow ended');
  expect((await api('GET', '/app/api/crm/clients')).length === 0, 'trying a flow created no client');
  await shot('test-chat-whatsapp');
  await click('#chatbot-chat-clear', 300);
  await click('#cb-0 [data-channel="WEB"]', 300);
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
  await click('[data-kw-remove="0:1"]', 300);
  await click('[data-kw-remove="0:0"]', 300);
  await click('[data-chatbot-save]', 900);
  expect((await toast()).includes('add at least one keyword'), `a flow with no keyword is refused, saying so (“${await toast()}”)`);
  expect(await page.evaluate(() => document.activeElement?.closest('.flow-menu') != null), 'and the cursor goes to the flow');
  await type('#cb-0-keywords', 'book');
  await page.keyboard.press('Enter');
  await pause(300);
  await click('[data-chatbot-save]', 1200);
  expect((await toast()) === 'Saved', 'saving works once it is fixed');
  expect(await page.$eval('[data-chatbot-savebar]', el => !el.classList.contains('is-dirty')), 'and the save bar rests again');
  const saved = await api('GET', '/app/api/chatbot/flows');
  expect(saved.flows.length === 1 && saved.flows[0].steps.length === 4 && saved.flows[0].keywords.join(',') === 'book', 'the flow is stored with its four steps');
  await page.reload({ waitUntil: 'networkidle2' });
  await page.waitForSelector('.nav__item[data-tab="chatbot"]');
  await pause(1500);
  expect((await text('[data-flow-title="0"]')) === 'Appointments' && (await text('[data-flow-pills="0"]')) === 'On', 'after a reload the page is on Chatbot and shows the saved flow');
  await shot('saved');

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
  expect((await text('[data-flow-summary="1"]')).includes('Sent'), 'the flow’s head counts what it sent in the last 30 days');
  await click('[data-flow-toggle="1"]', 600);
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
  await click('[data-flow-toggle="0"]', 600);
  await shot('phone');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), 'an open flow fits too');
  await page.setViewport({ width: 1600, height: 1100, isMobile: false, hasTouch: false });

  note('8. A member only reads');
  await page.evaluate(() => { localStorage.removeItem('dashboardToken'); });
  await login(company.member);
  await openChatbot();
  expect(!(await page.$('[data-chatbot-savebar]')) && !(await page.$('[data-flow-add]')), 'a member sees no save bar and no Add flow');
  await click('[data-flow-toggle="0"]', 600);
  expect(await page.$$eval('.flow-menu__body input:not([type=checkbox]), .flow-menu__body textarea, .flow-menu__body select', els => els.length > 0 && els.every(e => e.disabled)), 'every field in an open flow is read-only');
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
