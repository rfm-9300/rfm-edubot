// Browser walkthrough of the Briefings page against a running app, checking each step.
//
// It creates its own company (Agents, Employees and the time clock on, a WhatsApp number without a Business
// Account, so nothing ever reaches Meta) and an admin through the backoffice API, then employees, today's planned
// shifts, a "no-briefing" tag and a daily shift briefing that asks first. In Chrome it checks who gets what today
// and why the others don't, one person's message in its drawer, "Send now" leaving the messages waiting for
// approval (it never approves: an approved message would go to WhatsApp), the "New briefing" choices and the
// Agents drawers that open over the page, then the dark theme and a phone.
//
// Run the app with a password for the backoffice (its Mongo and OpenRouter can be fakes), then:
//   cd scripts/briefings-e2e && npm install && ADMIN_PASSWORD=… node walkthrough.mjs
// Optional: APP_URL (http://localhost:8080), CHROME (google-chrome), HEADLESS=1, OUT (./out),
// SLOW (ms between steps, 0 by default).
import fs from 'node:fs';
import path from 'node:path';
import puppeteer from 'puppeteer-core';

const APP = (process.env.APP_URL || 'http://localhost:8080').replace(/\/$/, '');
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD;
const OUT = path.resolve(process.env.OUT || 'out');
const SLOW = Number(process.env.SLOW || 0);
if (!ADMIN_PASSWORD) {
  console.error('Set ADMIN_PASSWORD (the backoffice password).');
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

// The company's clock: Lisbon.
const lisbon = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Lisbon', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const parts = Object.fromEntries(lisbon.formatToParts(new Date()).map(p => [p.type, p.value]));
const today = `${parts.year}-${parts.month}-${parts.day}`;
// An hour from now (or the day's last minute), so today's fire is still ahead and the rows read "Scheduled".
const minutes = Math.min(Number(parts.hour) * 60 + Number(parts.minute) + 60, 23 * 60 + 59);
const sendAt = `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;

/** A company in English with Agents, Employees and the time clock, and an admin. */
async function createCompany() {
  const { token } = await http('POST', '/admin/auth/login', null, { password: ADMIN_PASSWORD });
  const run = Date.now().toString(36);
  const slug = `briefings-e2e-${run}`;
  await http('POST', '/admin/api/tenants', token, {
    name: 'Clean Team', slug, locale: 'en', timezone: 'Europe/Lisbon',
    enabledModules: ['agents', 'employees', 'timesheets', 'clients', 'services', 'conversations', 'settings'],
    channels: [{ platform: 'WHATSAPP', externalId: `briefings-e2e-phone-${run}`, accessToken: 'briefings-e2e' }],
  });
  const admin = { email: `admin-${run}@clean.test`, password: `admin-${run}-pw` };
  await http('POST', `/admin/api/tenants/${slug}/dashboard-users`, token, { ...admin, role: 'TENANT_ADMIN' });
  return { slug, admin };
}

const company = await createCompany();
note(`Company ${company.slug}, briefing at ${sendAt} on ${today}`);
const { token } = await http('POST', '/app/auth/login', null, company.admin);
const api = (method, url, body) => http(method, url, token, body);

// The team: someone working today, someone off, someone working twice, someone who opted out.
const employee = (name, phone, role) => api('POST', '/app/api/crm/employees', { name, phone, role });
const ana = await employee('Ana Costa', '+351 912 000 101', 'Cleaner');
const rui = await employee('Rui Lopes', '+351 912 000 102', 'Cleaner');
const eva = await employee('Eva Dias', '+351 912 000 103', 'Driver');
const bea = await employee('Bea Nunes', '+351 912 000 104', 'Team lead');
await api('PUT', `/app/api/crm/employees/${eva.id}/tags`, { tags: ['no-briefing'] });
const plan = (who, start, end) => api('POST', '/app/api/timesheets/schedule', { employeeId: who.id, days: [today], start, end });
await plan(ana, '09:00', '17:00');
await plan(eva, '09:00', '13:00');
await plan(bea, '08:00', '12:00');
await plan(bea, '14:00', '18:00');

// The daily shift briefing, asking first, with its template picked.
const draft = await api('POST', '/app/api/agents', { templateKey: 'daily_shift_briefing', params: { time: sendAt, autonomy: 'APPROVE' } });
const definition = { ...draft.definition, steps: draft.definition.steps.map(s => ({ ...s, input: { ...s.input, template: 'shift_briefing', templateLanguage: 'en' } })) };
await api('PUT', `/app/api/agents/${draft.id}`, { definition });
await api('POST', `/app/api/agents/${draft.id}/activate`);

fs.mkdirSync(OUT, { recursive: true });
const profile = path.join(OUT, 'chrome');
fs.rmSync(profile, { recursive: true, force: true });
const browser = await puppeteer.launch({
  executablePath: process.env.CHROME || 'google-chrome',
  headless: process.env.HEADLESS === '1' ? 'new' : false,
  defaultViewport: { width: 1440, height: 960 },
  args: ['--no-sandbox', '--no-first-run', '--no-default-browser-check', `--user-data-dir=${profile}`],
});
const page = (await browser.pages())[0] || await browser.newPage();
const errors = [];
page.on('pageerror', e => errors.push(e.message));
page.on('console', m => { if (m.type() === 'error' && !(m.location()?.url || '').includes('favicon')) errors.push(m.text()); });

let shots = 0;
const shot = name => page.screenshot({ path: path.join(OUT, `${String(++shots).padStart(2, '0')}-${name}.png`), fullPage: true });
const click = async (sel, wait = 700) => { await page.waitForSelector(sel, { visible: true, timeout: 15000 }); await page.$eval(sel, el => el.scrollIntoView({ block: 'center' })); await pause(100); await page.click(sel); await pause(wait); };
const type = async (sel, value) => { await page.waitForSelector(sel, { visible: true }); await page.click(sel); await page.type(sel, value, { delay: 15 }); };
const toast = async () => { await pause(250); return page.$eval('#toast', el => (el.hidden ? '' : el.innerText.trim())).catch(() => ''); };
/** Each person's row: name, the state pill, why, and the message. */
const rows = () => page.$$eval('tr[data-briefing-person]', trs => trs.map(tr => ({
  name: tr.querySelector('.tbl__open')?.innerText.trim(),
  state: tr.querySelector('td:nth-child(2) .pill')?.innerText.trim(),
  why: tr.querySelector('td:nth-child(2) .sub')?.innerText.trim() || '',
  message: tr.querySelector('td:nth-child(3)')?.innerText.trim(),
})));
const row = async name => (await rows()).find(r => r.name === name) || {};

note('Sign in and open Briefings');
await page.goto(`${APP}/app/`, { waitUntil: 'networkidle2' });
await type('#email', company.admin.email);
await type('#password', company.admin.password);
await page.click('#login-form button[type=submit]');
await page.waitForSelector('.nav__item[data-tab="briefings"]', { timeout: 15000 });
expect(true, 'the Briefings page is in the menu (Agents and Employees are on)');
await click('.nav__item[data-tab="briefings"]', 1500);
await page.waitForSelector('tr[data-briefing-person]', { timeout: 15000 });
await shot('today');

note('Who gets what today');
expect((await rows()).length === 4, 'one row per member of the team');
expect(['Scheduled', 'Not sent'].includes((await row('Ana Costa')).state), `Ana's message is waiting for the schedule (${(await row('Ana Costa')).state})`);
expect((await row('Ana Costa')).message.includes('09:00-17:00'), 'her message has her shift');
expect((await row('Bea Nunes')).message.includes('08:00-12:00; 14:00-18:00'), 'two shifts read on one line');
expect((await row('Rui Lopes')).state === 'Nothing today', 'someone without a shift gets nothing');
expect((await row('Eva Dias')).state === 'Left out', 'the "no-briefing" tag keeps someone out');
expect((await page.$eval('.panel__head .hint', el => el.innerText)).includes(sendAt), 'the panel says when it goes');
expect(!(await page.$$eval('tr[data-briefing-person]', trs => trs.some(tr => tr.innerText.includes('Rui Lopes') && tr.querySelector('[data-briefing-row="again"]')))), 'nothing to send again to someone who got nothing');

note("Ana's drawer");
await page.evaluate(() => [...document.querySelectorAll('tr[data-briefing-person] .tbl__open')].find(b => b.innerText.trim() === 'Ana Costa').click());
await page.waitForSelector('#drawer:not([hidden]) .wa-preview', { timeout: 10000 });
await pause(500);
expect((await page.$eval('#drawer .bubble__text', el => el.innerText)).includes('Ana'), 'the drawer shows her message in a WhatsApp bubble');
expect(!!(await page.$('#drawer .notice--warn')), 'and warns that the template is missing on this number');
await shot('drawer');
await page.keyboard.press('Escape');
await pause(400);

note('Send now: the messages wait for approval');
await click('[data-briefing-act="send"]', 300);
await page.waitForSelector('#confirm:not([hidden])', { timeout: 10000 });
await pause(300);
await click('#confirm-ok', 300);
const sentToast = await toast();
expect(sentToast.includes('approval') && sentToast.includes('2'), `the toast says two messages now wait for approval (${sentToast})`);
await page.waitForFunction(() => [...document.querySelectorAll('tr[data-briefing-person] td:nth-child(2) .pill')].some(p => p.innerText.includes('approval')), { timeout: 15000 }).catch(() => {});
await pause(800);
await page.evaluate(() => window.scrollTo(0, 0));
expect((await row('Ana Costa')).state === 'Waiting for approval', 'Ana\'s message waits for approval');
expect((await row('Rui Lopes')).state === 'Nothing today', 'Rui still gets nothing');
expect(!!(await page.$('[data-briefing-act="approve"]')), 'Approve all is offered');
expect(!(await page.$('[data-briefing-act="send"]')), 'nobody is left to send to');
await shot('approval');

note('New briefing');
await click('#btn-new', 1200);
await page.waitForSelector('#drawer:not([hidden]) [data-briefing-template]', { timeout: 10000 });
const choices = await page.$$eval('#drawer [data-briefing-template]', els => els.map(e => ({ key: e.dataset.briefingTemplate, off: e.classList.contains('is-unavailable') })));
expect(choices.length === 3, 'three kinds: shifts, services and open issues');
expect(choices.find(c => c.key === 'open_issues_briefing')?.off === true, 'open issues needs Building, which is off');
await shot('new');
await click('#drawer [data-briefing-template="daily_services_briefing"]', 1200);
expect(!!(await page.$('#drawer:not([hidden]) #ag-tpl-name')), "Agents' setup drawer opens over the page");
await page.keyboard.press('Escape');
await pause(400);

note('Edit opens the agent');
await click('.panel__tools [data-briefing-act="edit"]', 1500);
expect(!!(await page.$('#drawer:not([hidden]) .record')), 'the agent record opens over the page');
await page.keyboard.press('Escape');
await pause(400);

note('Dark theme and a phone');
await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'));
await pause(300);
await shot('dark');
await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
await page.reload({ waitUntil: 'networkidle2' });
await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'light'));
await page.waitForSelector('tr[data-briefing-person]', { timeout: 15000 });
await pause(600);
const sideways = await page.evaluate(() => document.scrollingElement.scrollWidth - window.innerWidth);
expect(sideways <= 1, `nothing scrolls sideways on a phone (${sideways}px)`);
await shot('phone');

expect(errors.length === 0, `no script errors${errors.length ? `: ${errors.slice(0, 3).join(' | ')}` : ''}`);
await browser.close();
note(failures.length ? `\n${failures.length} check(s) failed` : '\nAll checks passed');
process.exit(failures.length ? 1 : 0);
