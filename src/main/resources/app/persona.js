/* Persona (/app → Persona): how the bot answers customers. On the left, tabs in the order a message meets
   them: Overview (the steps a message goes through, and what is set up), Profile (who it is, how it talks),
   Knowledge (business details, questions and answers, documents and the instructions), Rules (rules, off-topic
   and unknown questions, what it can do, handing over), Channels (who it answers where, and its start words:
   channels.js, ChannelsUI) and History. The fixed chat flows that answer before the AI are the Chatbot page's. On the right, a test chat that uses the unsaved edits (state.personaDraft),
   so a change can be tried before customers get it. One save bar saves every tab: the settings and the
   instructions as one version (PUT /app/api/persona), then the channel settings (ChannelsUI.save). */

let personaChatBusy = false;
let personaSaving = false;
const PS = I18N.section('app.personaStudio');
const ps = (key, params) => I18N.t(`app.personaStudio.${key}`, params);
const PERSONA_TABS = ['overview', 'profile', 'knowledge', 'rules', 'channels', 'history'];
// The tabs this page had before, so a tab remembered from then still opens its content.
const PERSONA_TAB_ALIASES = { behavior: 'profile', instructions: 'knowledge', menus: 'channels' };
const PERSONA_CHOICES = {
  disclosure: ['WHEN_ASKED', 'FIRST_REPLY'],
  tone: ['FRIENDLY', 'PROFESSIONAL', 'FORMAL', 'CASUAL', 'EMPATHETIC'],
  addressForm: ['INFORMAL', 'FORMAL'],
  replyLength: ['SHORT', 'MEDIUM', 'DETAILED'],
  emoji: ['NONE', 'LIGHT', 'EXPRESSIVE'],
  scope: ['BUSINESS', 'ANYTHING'],
  unknown: ['OFFER_CHECK', 'SHARE_CONTACT', 'HAND_OVER'],
};
const PERSONA_REASONS = ['ASKS_FOR_PERSON', 'UPSET', 'CANT_HELP', 'PAYMENTS', 'URGENT'];
const PERSONA_CAPABILITIES = ['catalog', 'bookings', 'records'];
const PERSONA_BUSINESS = ['name', 'about', 'hours', 'location', 'contact'];
const PERSONA_RULE_IDEAS = ['discounts', 'competitors', 'booking', 'name'];
const PERSONA_TRIES = ['who', 'hours', 'price', 'human', 'jailbreak', 'language'];
// Which tab holds each field a refused save names, so the page can go there and put the cursor in it.
const PERSONA_PROFILE_FIELDS = ['botName', 'disclosure', 'language', 'languageStrict', 'tone', 'addressForm', 'replyLength', 'emoji', 'greeting'];
const PERSONA_KNOWLEDGE_FIELDS = ['business', 'faq'];
const PERSONA_RULES_FIELDS = ['rules', 'scope', 'unknown', 'capabilities', 'handoff'];
// Extensions the upload accepts as other names for a listed type, left out of the hint.
const PERSONA_TYPE_ALIASES = ['markdown', 'text'];
const fmtCount = n => new Intl.NumberFormat(uiLocale()).format(Number(n || 0));
const fmtMegabytes = bytes => new Intl.NumberFormat(uiLocale(), { style: 'unit', unit: 'megabyte', maximumFractionDigits: 0 }).format(Number(bytes || 0) / (1024 * 1024));
const personaStatusLabel = s => ps(`status.${s || 'EMPTY'}`);
const personaAuthor = a => (a === 'operator' ? PS.byOperator : a);
const personaLanguageLabel = code => ps(`lang.${code}`);
const personaChannelLabel = channel => ps(`channel.${channel}`);
// Not part of the draft: whether a save was refused (problems then show under their fields).
const personaUi = { checked: false };

function personaErrorText(code, err = {}) {
  const field = err.field ? ps(`fields.${err.field.replace('.', '_')}`) : '';
  const key = `app.personaStudio.errors.${code}`;
  const index = err.index ?? err.body?.index;
  const params = { field, limit: err.limit != null ? fmtCount(err.limit) : '', size: err.limit != null ? fmtMegabytes(err.limit) : '', n: index != null ? fmtCount(index + 1) : '' };
  const text = code ? I18N.t(key, params) : key;
  return text === key ? PS.errorGeneric : text;
}
const personaFail = err => { if (err?.message !== 'unauthorized') toast(personaErrorText(err?.code, err)); };

// ── The draft ──────────────────────────────────────────────────────────────────────────────────────

const personaText = v => (typeof v === 'string' ? v.trim() : '') || null;
const personaSavedBehavior = () => state.persona?.behavior || {};
/** The settings as they stand: the unsaved draft, else the saved ones. */
const personaBehavior = () => state.personaDraft?.behavior || personaSavedBehavior();
const personaInstructions = () => state.personaDraft?.compiledInstructions ?? state.persona?.compiledInstructions ?? '';

/**
 * Settings as the server keeps them (trimmed, blanks unset, situations in their usual order), so an edit
 * undone by hand doesn't count as unsaved. A rule or question row added and still empty does count.
 */
function normalizedBehavior(b = {}) {
  const t = personaText;
  const caps = b.capabilities || {};
  const h = b.handoff || {};
  return {
    botName: t(b.botName), disclosure: t(b.disclosure), language: t(b.language), languageStrict: !!(t(b.language) && b.languageStrict),
    tone: t(b.tone), addressForm: t(b.addressForm), replyLength: t(b.replyLength), emoji: t(b.emoji),
    greeting: t(b.greeting), scope: t(b.scope), unknown: t(b.unknown),
    rules: (b.rules || []).map(r => (r || '').trim()),
    business: Object.fromEntries(PERSONA_BUSINESS.map(k => [k, t(b.business?.[k])])),
    faq: (b.faq || []).map(f => ({ question: (f.question || '').trim(), answer: (f.answer || '').trim() })),
    capabilities: Object.fromEntries(PERSONA_CAPABILITIES.map(k => [k, caps[k] !== false])),
    handoff: { enabled: !!h.enabled, reasons: PERSONA_REASONS.filter(r => (h.reasons || []).includes(r)), triggers: t(h.triggers), message: t(h.message) },
  };
}
const sameBehavior = (a, b) => JSON.stringify(normalizedBehavior(a)) === JSON.stringify(normalizedBehavior(b));
/** What is sent: rules without blanks. Questions go as they are, so a refusal's position matches the row on screen. */
function behaviorPayload(b) {
  const n = normalizedBehavior(b);
  return { ...n, rules: n.rules.filter(Boolean) };
}

/** The settings to change, started as a copy of the saved ones. */
function editableBehavior() {
  if (!state.personaDraft.behavior) state.personaDraft.behavior = JSON.parse(JSON.stringify(personaSavedBehavior()));
  const b = state.personaDraft.behavior;
  b.rules = b.rules || [];
  b.faq = b.faq || [];
  b.business = b.business || {};
  b.capabilities = b.capabilities || {};
  b.handoff = b.handoff || {};
  b.handoff.reasons = b.handoff.reasons || [];
  return b;
}
/** Drops the draft once it matches the saved settings again. */
function settleBehavior() {
  const draft = state.personaDraft.behavior;
  if (draft && sameBehavior(draft, personaSavedBehavior())) delete state.personaDraft.behavior;
}
function setPersonaPath(obj, path, value) {
  const keys = path.split('.');
  const last = keys.pop();
  const target = keys.reduce((o, k) => (o[k] = o[k] || {}), obj);
  target[last] = value;
}

/** The unsaved edits the test chat and the preview should use; null when nothing is unsaved. */
function personaDraftPayload() {
  const d = state.personaDraft || {};
  const out = {};
  if (d.behavior) out.behavior = behaviorPayload(d.behavior);
  if (d.compiledInstructions != null) out.compiledInstructions = d.compiledInstructions;
  return Object.keys(out).length ? out : null;
}

/** The tabs with unsaved changes, in tab order. */
function personaDirtyAreas() {
  const areas = new Set();
  const draft = state.personaDraft || {};
  if (draft.behavior) {
    const a = normalizedBehavior(draft.behavior);
    const b = normalizedBehavior(personaSavedBehavior());
    const differs = keys => keys.some(k => JSON.stringify(a[k]) !== JSON.stringify(b[k]));
    if (differs(PERSONA_PROFILE_FIELDS)) areas.add('profile');
    if (differs(PERSONA_KNOWLEDGE_FIELDS)) areas.add('knowledge');
    if (differs(PERSONA_RULES_FIELDS)) areas.add('rules');
  }
  if (draft.compiledInstructions != null) areas.add('knowledge');
  if (window.ChannelsUI?.dirty()) areas.add('channels');
  return PERSONA_TABS.filter(t => areas.has(t));
}
const personaPageDirty = () => personaDirtyAreas().length > 0;

/** A question with only one side, which the server would refuse: its row and which side is missing. */
function personaFaqIssues(b = personaBehavior()) {
  return (b.faq || []).map((f, i) => {
    const q = (f.question || '').trim();
    const a = (f.answer || '').trim();
    if (q && !a) return { index: i, code: 'answer_required', field: 'faq.answer' };
    if (a && !q) return { index: i, code: 'question_required', field: 'faq.question' };
    return null;
  }).filter(Boolean);
}
const personaFixCount = () => personaFaqIssues().length + (window.ChannelsUI?.problemCount() || 0);

/** Leaving the dashboard drops the draft, so the browser asks first. Wired up once by app.js's init(). */
function wirePersonaUnload() {
  window.addEventListener('beforeunload', e => {
    if (!state.persona?.canEdit || !personaPageDirty()) return;
    e.preventDefault();
    e.returnValue = '';
  });
}

// ── Loading ────────────────────────────────────────────────────────────────────────────────────────

let personaPollTimer;
async function refreshPersona() {
  const before = state.persona;
  state.persona = await api('/app/api/persona');
  if (before?.status === 'COMPILING' && state.persona.status !== 'COMPILING') {
    state.personaVersions = null;
    state.personaPreview = null;
    if (state.persona.status === 'ERROR') toast(personaErrorText(state.persona.lastError));
    else if (state.persona.version !== before.version) toast(ps('synthesized', { n: state.persona.version }));
  }
  if (state.active === 'persona') renderKeepingFocus();
  clearTimeout(personaPollTimer);
  if (state.persona.status === 'COMPILING') personaPollTimer = setTimeout(() => refreshPersona().catch(() => {}), 3000);
}

/** Redraws the page without dropping the field someone is typing in (polls redraw while they work). */
function renderKeepingFocus() {
  const el = document.activeElement;
  const id = el?.id;
  const range = el && typeof el.selectionStart === 'number' ? [el.selectionStart, el.selectionEnd] : null;
  render();
  const again = id && document.getElementById(id);
  if (!again) return;
  again.focus();
  if (range && again.setSelectionRange) { try { again.setSelectionRange(range[0], range[1]); } catch { /* not a text field */ } }
}

async function loadPersonaVersions() {
  state.personaVersions = await api('/app/api/persona/versions');
}

function personaTabNow() {
  const tab = PERSONA_TAB_ALIASES[state.personaTab] || state.personaTab;
  return PERSONA_TABS.includes(tab) ? tab : 'overview';
}

/**
 * Opens [tab]. From the tab row nothing moves; from elsewhere ([jump], the Overview's buttons) the page goes to
 * [focusId] and puts the cursor there, or back to the top, where the tabs are.
 */
async function openPersonaTab(tab, focusId, jump = false) {
  state.personaTab = tab;
  if (tab === 'history' && !state.personaVersions) {
    render();
    await loadPersonaVersions().catch(err => { personaFail(err); state.personaVersions = []; });
  }
  if (tab === 'channels' && (!state.personaChannels || state.personaChannels.failed)) {
    state.personaChannels = null;
    render();
    await window.ChannelsUI.load().catch(err => { personaFail(err); state.personaChannels = { failed: true }; });
  }
  render();
  if (!jump) return;
  const el = focusId && document.getElementById(focusId);
  if (el) {
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    el.focus({ preventScroll: true });
  } else {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
}

// ── The page ───────────────────────────────────────────────────────────────────────────────────────

/** The channels where the AI answers customers now: connected, and not left to the team. */
function personaAnswersOn(p) {
  const off = state.personaChannels?.teamOnlyChannels || [];
  const on = (p.channels || []).filter(c => c.connected && !off.includes(c.channel)).map(c => personaChannelLabel(c.channel));
  return on.length ? on.join(', ') : PS.answersNone;
}

function renderPersona(root) {
  const p = state.persona || {};
  const tab = personaTabNow();
  const live = personaSavedBehavior();
  const stats = statCards([
    { label: PS.statStatus, value: personaStatusLabel(p.status) },
    { label: PS.statAnswers, value: personaAnswersOn(p) },
    { label: PS.statHandoff, value: live.handoff?.enabled ? PS.handoffOn : PS.handoffOff },
    { label: PS.statUpdated, value: p.updatedAt && p.version ? relTime(p.updatedAt) : '—' },
  ]);
  const tabs = `<div class="settings-tabs" role="tablist" aria-label="${escapeHTML(PS.tabsAria)}">${PERSONA_TABS.map(id => `<button type="button" role="tab" class="chip ${tab === id ? 'is-on' : ''}" aria-selected="${tab === id}" data-persona-tab="${id}">${escapeHTML(ps(`tab.${id}`))}${id === 'knowledge' && p.pendingSources ? `<span class="chip__count">${fmtCount(p.pendingSources)}</span>` : ''}</button>`).join('')}</div>`;
  root.innerHTML = `${hero(labels.persona, STR.personaDesc, stats)}
    ${personaNotices(p)}
    <div class="persona-studio">
      <div class="persona-studio__main">
        ${tabs}
        <div class="persona-studio__pane" role="tabpanel">${personaPane(tab, p)}</div>
        ${p.canEdit ? personaSavebarHtml() : ''}
      </div>
      <aside class="persona-studio__test" aria-label="${escapeHTML(PS.testTitle)}">${personaTestPanel()}</aside>
    </div>`;
  wirePersona(root, tab, p);
}

function personaNotice(tone, title, desc, actions = '') {
  return `<div class="notice${tone ? ` notice--${tone}` : ''}" role="status"><div class="notice__text"><strong>${escapeHTML(title)}</strong><span>${escapeHTML(desc)}</span></div>${actions}</div>`;
}

function personaNotices(p) {
  const act = (attr, label) => (p.canEdit ? `<div class="notice__actions"><button type="button" class="btn btn--sm" ${attr}>${escapeHTML(label)}</button></div>` : '');
  const out = [];
  if (p.status === 'COMPILING') out.push(personaNotice('info', PS.compilingTitle, PS.compilingDesc));
  else if (p.status === 'ERROR') out.push(personaNotice('warn', PS.errorTitle, `${personaErrorText(p.lastError)} ${PS.errorKeeps}`, act('data-persona-compile', PS.retry)));
  else if (p.pendingSources) out.push(personaNotice('info', ps('pendingTitle', { n: p.pendingSources }), PS.pendingDesc, act('data-persona-compile', PS.synthesizeNow)));
  if (p.stale && p.status !== 'COMPILING') out.push(personaNotice('warn', PS.staleTitle, PS.staleDesc, act('data-persona-rebuild', PS.rebuild)));
  if (p.canEdit === false) out.push(personaNotice('', PS.readOnlyTitle, PS.readOnlyDesc));
  return out.join('');
}

function personaPane(tab, p) {
  if (tab === 'profile') return personaProfilePane(p);
  if (tab === 'knowledge') return personaKnowledgePane(p);
  if (tab === 'rules') return personaRulesPane(p);
  if (tab === 'channels') return window.ChannelsUI.render(p);
  if (tab === 'history') return personaHistoryPane(p);
  return personaOverviewPane(p);
}

function personaPanel(title, body, { id = '', tools = '', meta = '' } = {}) {
  return `<section class="panel"${id ? ` id="${id}"` : ''}><header class="panel__head"><h2 class="panel__title">${title}</h2>${meta}${tools}</header>
    <div class="panel__body form">${body}</div></section>`;
}

// ── Overview: how a message is answered, and what is set up ───────────────────────────────────────

function personaOverviewPane(p) {
  const b = personaBehavior();
  const step = (n, title, body, actions, off = false) => `<li class="bot-flow__step${off ? ' is-off' : ''}">
      <span class="bot-flow__num" aria-hidden="true">${n}</span>
      <div><h3 class="bot-flow__title">${escapeHTML(title)}</h3>${body}</div>
      <div class="bot-flow__actions">${actions}</div>
    </li>`;
  const go = (tab, label = ps(`tab.${tab}`), focus = '') => `<button type="button" class="btn btn--sm btn--ghost" data-persona-go="${tab}"${focus ? ` data-persona-focus="${focus}"` : ''}>${escapeHTML(label)}</button>`;
  const list = items => `<ul class="bot-flow__list">${items.map(i => `<li${i.muted ? ' class="is-muted"' : ''}>${i.label ? `<strong>${escapeHTML(i.label)}</strong> · ` : ''}${escapeHTML(i.text)}</li>`).join('')}</ul>`;

  const channels = personaChannelLines(p);
  const anyConnected = (p.channels || []).some(c => c.connected);
  const step1 = anyConnected
    ? list(channels)
    : `<p class="bot-flow__text">${escapeHTML(PS.flowNoChannel)}</p>`;
  const settings = `<button type="button" class="btn btn--sm btn--ghost" data-persona-settings>${escapeHTML(PS.openSettings)}</button>`;

  const chatbot = personaChatbotLine();
  const ai = personaAiLines(p, b);
  const handoff = b.handoff || {};
  const reasons = (handoff.reasons || []).map(r => ps(`reasons.${r}`));
  if (personaText(handoff.triggers)) reasons.push(handoff.triggers.trim());
  const team = handoff.enabled
    ? `<p class="bot-flow__text">${escapeHTML(ps('flowTeamOn', { when: (reasons.length ? reasons : PERSONA_REASONS.slice(0, 3).map(r => ps(`reasons.${r}`))).join(' · ') }))}</p>`
    : `<p class="bot-flow__text">${escapeHTML(PS.flowTeamOff)}</p>`;

  const flow = personaPanel(escapeHTML(PS.flowTitle), `<p class="hint">${escapeHTML(PS.flowHint)}</p>
    <ol class="bot-flow">
      ${step(1, PS.flowChannelsTitle, step1, `${go('channels')}${anyConnected ? '' : settings}`, !anyConnected)}
      ${step(2, PS.flowMenusTitle, `<p class="bot-flow__text">${escapeHTML(chatbot.text)}</p>`, `<button type="button" class="btn btn--sm btn--ghost" data-persona-chatbot>${escapeHTML(PS.flowMenusOpen)}</button>`, chatbot.off)}
      ${step(3, PS.flowAiTitle, list(ai), `${go('profile')}${go('knowledge')}${go('rules')}`)}
      ${step(4, PS.flowTeamTitle, team, go('rules', ps('tab.rules'), 'pb-handoff'), !handoff.enabled)}
    </ol>`, { id: 'persona-flow' });

  const setup = personaSetupItems(p, b);
  const done = setup.filter(s => s.done).length;
  const checklist = personaPanel(`${escapeHTML(PS.setupTitle)} <span class="tag">${escapeHTML(ps('setupCount', { n: fmtCount(done), total: fmtCount(setup.length) }))}</span>`,
    `<ul class="setup-steps">${setup.map(s => `<li class="setup-steps__item${s.done ? ' is-done' : ''}">
        <span class="setup-steps__mark" aria-hidden="true">✓</span>
        <span class="setup-steps__text"><span>${escapeHTML(ps(`setup.${s.key}`))}<span class="visually-hidden"> · ${escapeHTML(s.done ? PS.setupDone : PS.setupTodo)}</span></span><small>${escapeHTML(ps(`setup.${s.key}Desc`))}</small></span>
        ${s.tab ? go(s.tab, s.done ? PS.setupReview : PS.setupStart, s.focus) : `<button type="button" class="btn btn--sm btn--ghost" data-persona-try-focus>${escapeHTML(PS.setupTry)}</button>`}
      </li>`).join('')}</ul>`, { id: 'persona-setup' });

  const reads = personaPanel(escapeHTML(PS.previewTitle), `<p class="hint">${escapeHTML(ps('readsHint', { n: fmtCount(p.tokenEstimate || 0) }))}</p>
    <div class="actions"><button type="button" class="btn btn--sm" data-persona-reads>${escapeHTML(PS.readsShow)}</button></div>`);
  return flow + checklist + reads;
}

/** Each channel, and whom the AI answers there (from the draft when the channels have unsaved changes). */
function personaChannelLines(p) {
  const m = window.ChannelsUI?.current() || {};
  return (p.channels || []).map(c => {
    const label = personaChannelLabel(c.channel);
    if (!c.connected) return { label, text: PS.chNotConnected, muted: true };
    if (c.channel === 'WEB') return { label, text: PS.chWeb };
    if ((m.teamOnlyChannels || []).includes(c.channel)) return { label, text: PS.chOff };
    const gate = (m.startGates || []).find(g => g.channel === c.channel && g.enabled && (g.words || []).length);
    return { label, text: gate ? ps('chGate', { words: gate.words.slice(0, 3).join(', ') }) : PS.chAll };
  });
}

/** The Chatbot page's flows that answer before the AI: how many are on, from the saved ones. */
function personaChatbotLine() {
  const on = state.personaChannels?.flowsOn || 0;
  return on ? { text: ps('flowMenusOn', { n: fmtCount(on) }), off: false } : { text: PS.flowMenusNone, off: true };
}

/** Who the AI is, what it knows and what it keeps to, a line each. */
function personaAiLines(p, b) {
  const choice = field => (b[field] ? ps(`options.${field}.${b[field]}`) : null);
  const profile = [personaText(b.botName), b.language ? personaLanguageLabel(b.language) : null, choice('tone'), choice('addressForm'), choice('replyLength')].filter(Boolean);
  const facts = PERSONA_BUSINESS.filter(k => personaText(b.business?.[k])).length;
  const questions = (b.faq || []).filter(f => personaText(f.question) && personaText(f.answer)).length;
  const docs = (p.sources || []).length;
  const knows = [
    facts ? ps('nFacts', { n: facts }) : null,
    questions ? ps('nQuestions', { n: questions }) : null,
    docs ? ps('nDocuments', { n: docs }) : null,
    !docs && personaText(personaInstructions()) ? PS.hasInstructions : null,
  ].filter(Boolean);
  const rules = (b.rules || []).filter(r => personaText(r)).length;
  const can = (p.capabilities || []).filter(k => b.capabilities?.[k] !== false).map(k => ps(`capabilityShort.${k}`));
  const keeps = [
    rules ? ps('nRules', { n: rules }) : null,
    b.scope ? ps(`flowScope.${b.scope}`) : null,
    b.unknown ? ps(`flowUnknown.${b.unknown}`) : null,
    ...can,
  ].filter(Boolean);
  return [
    { label: ps('tab.profile'), text: profile.length ? profile.join(' · ') : PS.flowProfileAuto },
    { label: ps('tab.knowledge'), text: knows.length ? knows.join(' · ') : PS.flowKnowsNothing, muted: !knows.length },
    { label: ps('tab.rules'), text: keeps.length ? keeps.join(' · ') : PS.flowRulesNone, muted: !keeps.length },
  ];
}

function personaSetupItems(p, b) {
  return [
    { key: 'profile', done: !!(personaText(b.botName) || b.tone || b.language || b.replyLength), tab: 'profile', focus: 'pb-botName' },
    { key: 'business', done: PERSONA_BUSINESS.some(k => personaText(b.business?.[k])), tab: 'knowledge', focus: 'pb-business-name' },
    { key: 'faq', done: (b.faq || []).some(f => personaText(f.question) && personaText(f.answer)) || (p.sources || []).length > 0 || !!personaText(personaInstructions()), tab: 'knowledge', focus: 'persona-faq' },
    { key: 'handoff', done: !!b.handoff?.enabled, tab: 'rules', focus: 'pb-handoff' },
    { key: 'test', done: (state.personaChat || []).some(m => m.role === 'assistant'), tab: null },
  ];
}

// ── Profile: who the bot is and how it talks ──────────────────────────────────────────────────────

/** A radio group of option cards; "Automatic" (nothing set) comes first and leaves it to the instructions. */
function personaChoices(field, b, edit, labelText) {
  const id = `pb-${field}`;
  const value = b[field] || '';
  const card = (v, title, eg) => `<label class="choice"><input type="radio" name="${id}" value="${v}" data-pb="${field}"${value === v ? ' checked' : ''}${edit ? '' : ' disabled'} /><span class="choice__title">${escapeHTML(title)}</span>${eg ? `<span class="choice__eg">${escapeHTML(eg)}</span>` : ''}</label>`;
  return `<div class="choices-field"><span class="lbl" id="${id}-lbl">${escapeHTML(labelText)}</span>
    <div class="choices" role="radiogroup" aria-labelledby="${id}-lbl">
      ${card('', PS.optionAuto, ps(`auto.${field}`))}
      ${PERSONA_CHOICES[field].map(v => card(v, ps(`options.${field}.${v}`), ps(`examples.${field}.${v}`))).join('')}
    </div></div>`;
}

function personaProfilePane(p) {
  const b = personaBehavior();
  const limits = p.limits || {};
  const edit = !!p.canEdit;
  const off = edit ? '' : ' disabled';
  const languages = (limits.languages || []).map(code => `<option value="${escapeHTML(code)}"${b.language === code ? ' selected' : ''}>${escapeHTML(personaLanguageLabel(code))}</option>`).join('');
  const identity = personaPanel(escapeHTML(PS.identityTitle), `<p class="hint">${escapeHTML(PS.identityHint)}</p>
      <div class="form__row"><label class="lbl" for="pb-botName">${escapeHTML(PS.botName)}</label>
        <input class="inp" id="pb-botName" data-pb="botName" maxlength="${limits.botNameChars || 60}" autocomplete="off" placeholder="${escapeHTML(PS.botNamePh)}" value="${escapeHTML(b.botName || '')}"${off} /></div>
      ${personaChoices('disclosure', b, edit, PS.disclosure)}`);
  const language = personaPanel(escapeHTML(PS.languageTitle), `<div class="form__grid">
        <div class="form__row"><label class="lbl" for="pb-language">${escapeHTML(PS.language)}</label>
          <select class="sel" id="pb-language" data-pb="language"${off}><option value="">${escapeHTML(PS.languageAuto)}</option>${languages}</select></div>
        <div class="form__row form__row--full"><label class="form__check"><input type="checkbox" id="pb-languageStrict" data-pb="languageStrict"${b.languageStrict ? ' checked' : ''}${b.language && edit ? '' : ' disabled'} /> ${escapeHTML(PS.languageStrict)}</label></div>
      </div>`);
  const style = personaPanel(escapeHTML(PS.styleTitle), `<p class="hint">${escapeHTML(PS.styleHint)}</p>
      ${personaChoices('tone', b, edit, PS.tone)}
      ${personaChoices('addressForm', b, edit, PS.addressForm)}
      ${personaChoices('replyLength', b, edit, PS.replyLength)}
      ${personaChoices('emoji', b, edit, PS.emoji)}`);
  const greeting = personaPanel(escapeHTML(PS.greetingTitle), `<label class="visually-hidden" for="pb-greeting">${escapeHTML(PS.greeting)}</label>
      <textarea class="txt" id="pb-greeting" data-pb="greeting" rows="2" maxlength="${limits.greetingChars || 400}" placeholder="${escapeHTML(PS.greetingPh)}"${off}>${escapeHTML(b.greeting || '')}</textarea>
      <p class="hint">${escapeHTML(PS.greetingHint)}</p>`);
  const agents = `<div class="persona-agents" id="persona-agents" hidden></div>`;
  return identity + language + style + greeting + agents;
}

// ── Knowledge: business details, questions and answers, documents, instructions ──────────────────

function personaKnowledgePane(p) {
  return personaBusinessPanel(p) + personaFaqPanel(p) + personaDocumentsPanels(p) + personaInstructionsPanel(p);
}

function personaBusinessPanel(p) {
  const b = personaBehavior();
  const biz = b.business || {};
  const limits = p.limits || {};
  const off = p.canEdit ? '' : ' disabled';
  const field = (key, rows, full = false) => {
    const max = key === 'name' ? limits.businessNameChars : key === 'about' ? limits.businessAboutChars : limits.businessFieldChars;
    const ph = key === 'name' ? ps('businessPh.name', { company: p.companyName || '' }) : ps(`businessPh.${key}`);
    const id = `pb-business-${key}`;
    const control = rows
      ? `<textarea class="txt" id="${id}" data-pb="business.${key}" rows="${rows}" maxlength="${max || 500}" placeholder="${escapeHTML(ph)}"${off}>${escapeHTML(biz[key] || '')}</textarea>`
      : `<input class="inp" id="${id}" data-pb="business.${key}" maxlength="${max || 100}" autocomplete="off" placeholder="${escapeHTML(ph)}" value="${escapeHTML(biz[key] || '')}"${off} />`;
    return `<div class="form__row${full ? ' form__row--full' : ''}"><label class="lbl" for="${id}">${escapeHTML(ps(`business.${key}`))}</label>${control}</div>`;
  };
  return personaPanel(escapeHTML(PS.businessTitle), `<p class="hint">${escapeHTML(PS.businessHint)}</p>
      <div class="form__grid">${field('name', 0, true)}${field('about', 3, true)}${field('hours', 3)}${field('location', 3)}${field('contact', 2, true)}</div>`,
  { id: 'persona-business' });
}

function personaFaqPanel(p) {
  const b = personaBehavior();
  const faq = b.faq || [];
  const limits = p.limits || {};
  const max = limits.faq || 30;
  const edit = !!p.canEdit;
  const off = edit ? '' : ' disabled';
  const filled = faq.filter(f => personaText(f.question) && personaText(f.answer)).length;
  const items = faq.map((f, i) => `<div class="faq-item" id="pf-${i}">
      <span class="faq-item__num" aria-hidden="true">${i + 1}</span>
      <div class="faq-item__fields">
        <label class="visually-hidden" for="pf-${i}-q">${escapeHTML(ps('faqQuestionN', { n: i + 1 }))}</label>
        <input class="inp" id="pf-${i}-q" data-faq="${i}" data-faq-field="question" maxlength="${limits.faqQuestionChars || 200}" autocomplete="off" placeholder="${escapeHTML(PS.faqQuestionPh)}" value="${escapeHTML(f.question || '')}"${off} />
        <label class="visually-hidden" for="pf-${i}-a">${escapeHTML(ps('faqAnswerN', { n: i + 1 }))}</label>
        <textarea class="txt" id="pf-${i}-a" data-faq="${i}" data-faq-field="answer" rows="2" maxlength="${limits.faqAnswerChars || 800}" placeholder="${escapeHTML(PS.faqAnswerPh)}"${off}>${escapeHTML(f.answer || '')}</textarea>
        <p class="hint hint--bad" data-faq-issue="${i}" role="alert" hidden></p>
      </div>
      ${edit ? `<div class="row-tools"><button type="button" class="iconbtn iconbtn--danger" data-faq-remove="${i}" aria-label="${escapeHTML(ps('faqRemove', { n: i + 1 }))}" title="${escapeHTML(ps('faqRemove', { n: i + 1 }))}">×</button></div>` : ''}
    </div>`).join('');
  const add = !edit ? ''
    : faq.length < max ? `<div class="actions"><button type="button" class="btn btn--sm btn--ghost" id="persona-faq" data-faq-add><span class="btn__plus">+</span> ${escapeHTML(PS.faqAdd)}</button></div>`
      : `<p class="hint">${escapeHTML(ps('faqFull', { max: fmtCount(max) }))}</p>`;
  return personaPanel(`${escapeHTML(PS.faqTitle)} <span class="tag" data-pb-faq-count>${fmtCount(filled)}</span>`, `<p class="hint">${escapeHTML(PS.faqHint)}</p>
      ${faq.length ? `<div class="faq-list">${items}</div>` : `<p class="hint">${escapeHTML(PS.faqEmpty)}</p>`}
      ${add}
      <p class="hint">${escapeHTML(ps('faqLimit', { max: fmtCount(max) }))}</p>`, { id: 'persona-faq-panel' });
}

function personaDocumentsPanels(p) {
  const limits = p.limits || {};
  const sources = p.sources || [];
  const used = sources.reduce((n, s) => n + (s.chars || 0), 0);
  const note = state.personaDraft?.note || '';
  const types = limits.fileTypes || [];
  const add = p.canEdit ? personaPanel(escapeHTML(PS.addTitle), `<p class="hint">${escapeHTML(PS.addHint)}</p>
        <form class="form" id="persona-note-form">
          <div class="form__row form__row--full"><label class="lbl" for="persona-note">${escapeHTML(PS.noteLabel)}</label>
            <textarea class="txt" id="persona-note" rows="4" maxlength="${limits.noteChars || 20000}" placeholder="${escapeHTML(PS.notePh)}">${escapeHTML(note)}</textarea>
            <p class="hint" id="persona-note-count">${escapeHTML(ps('charCount', { n: fmtCount(note.length), max: fmtCount(limits.noteChars || 20000) }))}</p></div>
          <div class="actions"><button class="btn btn--sm" type="submit">${escapeHTML(PS.addNote)}</button></div>
        </form>
        <div class="form__row form__row--full"><label class="lbl" for="persona-file">${escapeHTML(PS.fileLabel)}</label>
          <input class="inp" id="persona-file" type="file" accept="${escapeHTML(types.map(t => `.${t}`).join(','))}" />
          <p class="hint">${escapeHTML(ps('fileHint', { types: types.filter(t => !PERSONA_TYPE_ALIASES.includes(t)).map(t => t.toUpperCase()).join(', '), size: fmtMegabytes(limits.uploadBytes) }))}</p></div>`) : '';
  const rows = sources.map(s => `<tr>
      <td class="name long">${escapeHTML(s.label)}<div class="sub">${escapeHTML([ps(`kind.${s.kind}`), ps('chars', { n: fmtCount(s.chars) }), s.addedBy ? personaAuthor(s.addedBy) : ''].filter(Boolean).join(' · '))}</div>${s.truncated ? `<p class="hint hint--warn">${escapeHTML(PS.truncated)}</p>` : ''}</td>
      <td data-label="${escapeHTML(PS.thState)}">${s.compiled ? `<span class="pill pill--ok">${escapeHTML(PS.sourceSynced)}</span>` : `<span class="pill pill--warn">${escapeHTML(PS.sourcePending)}</span>`}</td>
      <td class="nowrap" data-label="${escapeHTML(PS.thAdded)}">${escapeHTML(fmtDate(s.createdAt))}</td>
      <td class="right"><div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-view-source="${escapeHTML(s.id)}">${escapeHTML(PS.view)}</button>${p.canEdit ? `<button class="btn btn--sm btn--ghost" type="button" data-del-source="${escapeHTML(s.id)}">${escapeHTML(PS.remove)}</button>` : ''}</div></td>
    </tr>`).join('');
  const empty = `<tr><td colspan="4"><div class="empty"><p class="empty__title">${escapeHTML(PS.noSourcesTitle)}</p><p class="empty__desc">${escapeHTML(PS.noSourcesDesc)}</p></div></td></tr>`;
  const tools = p.canEdit && sources.length ? `<div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-persona-rebuild${p.status === 'COMPILING' ? ' disabled' : ''}>${escapeHTML(PS.rebuild)}</button></div>` : '';
  return `${add}
    <section class="panel" id="persona-sources"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.sourcesTitle)} <span class="tag">${fmtCount(sources.length)}</span></h2>${tools}</header>
      <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${escapeHTML(PS.thSource)}</th><th>${escapeHTML(PS.thState)}</th><th>${escapeHTML(PS.thAdded)}</th><th class="right">${escapeHTML(STR.thActions)}</th></tr></thead><tbody>${rows || empty}</tbody></table></div>
      <div class="panel__body"><p class="hint">${escapeHTML(ps('sourcesUsage', { n: fmtCount(sources.length), max: fmtCount(limits.sources || 100), chars: fmtCount(used), maxChars: fmtCount(limits.totalSourceChars || 600000) }))}</p></div>
    </section>`;
}

function personaCountHint(n, max, withTokens) {
  const tone = n > max ? ' hint--bad' : n > max * 0.8 ? ' hint--warn' : '';
  const text = withTokens
    ? ps('charCountTokens', { n: fmtCount(n), max: fmtCount(max), tokens: fmtCount(Math.ceil(n / 4)) })
    : ps('charCount', { n: fmtCount(n), max: fmtCount(max) });
  return { cls: `hint${tone}`, text };
}

function personaInstructionsPanel(p) {
  const max = p.limits?.instructionsChars || 12000;
  const text = personaInstructions();
  const count = personaCountHint(text.length, max, true);
  return personaPanel(escapeHTML(PS.instructionsTitle), `<p class="hint">${escapeHTML(PS.instructionsHint)}</p>
      <label class="visually-hidden" for="persona-text">${escapeHTML(PS.instructionsTitle)}</label>
      <textarea class="txt" id="persona-text" rows="14" placeholder="${escapeHTML(PS.instructionsPh)}"${p.canEdit ? '' : ' readonly'}>${escapeHTML(text)}</textarea>
      <p class="${count.cls}" id="persona-text-count">${escapeHTML(count.text)}</p>`,
  { id: 'persona-instructions', meta: p.version ? `<span class="panel__meta">${escapeHTML(ps('versionShort', { n: p.version }))}</span>` : '' });
}

// ── Rules: rules, off-topic and unknown questions, what it can do, handing over ───────────────────

function personaRulesPane(p) {
  return personaRulesPanel(p) + personaTopicsPanel(p) + personaCanDoPanel(p) + personaHandoffPanel(p);
}

function personaRulesPanel(p) {
  const b = personaBehavior();
  const rules = b.rules || [];
  const limits = p.limits || {};
  const max = limits.rules || 25;
  const edit = !!p.canEdit;
  const rows = rules.map((r, i) => `<div class="rule-row"><span class="rule-row__num" aria-hidden="true">${i + 1}</span>
      <input class="inp" id="pr-${i}" data-rule="${i}" maxlength="${limits.ruleChars || 300}" autocomplete="off" placeholder="${escapeHTML(PS.rulePh)}" aria-label="${escapeHTML(ps('ruleN', { n: i + 1 }))}" value="${escapeHTML(r || '')}"${edit ? '' : ' disabled'} />
      ${edit ? `<div class="row-tools"><button type="button" class="iconbtn iconbtn--danger" data-rule-remove="${i}" aria-label="${escapeHTML(ps('ruleRemove', { n: i + 1 }))}" title="${escapeHTML(ps('ruleRemove', { n: i + 1 }))}">×</button></div>` : ''}
    </div>`).join('');
  const have = new Set(rules.map(r => (r || '').trim().toLowerCase()));
  const ideas = edit && rules.length < max ? PERSONA_RULE_IDEAS.filter(k => !have.has(ps(`ruleIdeas.${k}`).toLowerCase())) : [];
  const ideaChips = ideas.length ? `<div class="rule-ideas"><span class="hint" id="pb-rule-ideas">${escapeHTML(PS.ruleIdeasLabel)}</span>
      <div class="chip-picks" role="group" aria-labelledby="pb-rule-ideas">${ideas.map(k => `<button type="button" class="chip" data-rule-idea="${k}"><span class="btn__plus" aria-hidden="true">+</span> ${escapeHTML(ps(`ruleIdeas.${k}`))}</button>`).join('')}</div></div>` : '';
  const count = rules.filter(r => personaText(r)).length;
  return personaPanel(`${escapeHTML(PS.rulesTitle)} <span class="tag" data-pb-rules-count>${fmtCount(count)}</span>`, `<p class="hint">${escapeHTML(PS.rulesHint)}</p>
      ${rules.length ? `<div class="rule-list">${rows}</div>` : `<p class="hint">${escapeHTML(PS.rulesEmpty)}</p>`}
      ${edit && rules.length < max ? `<div class="actions"><button type="button" class="btn btn--sm btn--ghost" id="persona-rule-add" data-rule-add><span class="btn__plus">+</span> ${escapeHTML(PS.ruleAdd)}</button></div>` : ''}
      ${ideaChips}
      <p class="hint">${escapeHTML(ps('rulesLimit', { max: fmtCount(max), chars: fmtCount(limits.ruleChars || 300) }))}</p>`, { id: 'persona-rules' });
}

function personaTopicsPanel(p) {
  const b = personaBehavior();
  const edit = !!p.canEdit;
  return personaPanel(escapeHTML(PS.topicsTitle), `<p class="hint">${escapeHTML(PS.topicsHint)}</p>
      ${personaChoices('scope', b, edit, PS.scope)}
      ${personaChoices('unknown', b, edit, PS.unknown)}
      <p class="hint hint--warn" data-pb-unknown-note hidden></p>`, { id: 'persona-topics' });
}

function personaCanDoPanel(p) {
  const b = personaBehavior();
  const edit = !!p.canEdit;
  const available = p.capabilities || [];
  const card = (key, checked, disabled) => `<label class="choice"><input type="checkbox"${key !== 'answer' ? ` id="pb-capability-${key}" data-pb="capabilities.${key}"` : ''}${checked ? ' checked' : ''}${disabled ? ' disabled' : ''} /><span class="choice__title">${escapeHTML(ps(`capability.${key}`))}</span><span class="choice__eg">${escapeHTML(ps(`capabilityDesc.${key}`))}</span></label>`;
  const cards = [card('answer', true, true), ...available.map(k => card(k, b.capabilities?.[k] !== false, !edit))].join('');
  return personaPanel(escapeHTML(PS.canDoTitle), `<p class="hint">${escapeHTML(PS.canDoHint)}</p>
      <div class="choices choices--wide" role="group" aria-label="${escapeHTML(PS.canDoTitle)}">${cards}</div>
      ${available.length ? '' : `<p class="hint">${escapeHTML(PS.canDoNone)}</p>`}`, { id: 'persona-can-do' });
}

function personaHandoffPanel(p) {
  const b = personaBehavior();
  const h = b.handoff || {};
  const limits = p.limits || {};
  const edit = !!p.canEdit;
  const off = edit ? '' : ' disabled';
  const reasons = PERSONA_REASONS.map(r => `<label class="form__check"><input type="checkbox" data-pb-reason="${r}"${(h.reasons || []).includes(r) ? ' checked' : ''}${off} /> ${escapeHTML(ps(`reasons.${r}`))}</label>`).join('');
  const details = !h.enabled ? '' : `<div class="form__row form__row--full"><span class="lbl" id="pb-handoff-when">${escapeHTML(PS.handoffWhen)}</span>
        <div class="form__checks form__checks--wide" role="group" aria-labelledby="pb-handoff-when">${reasons}</div>
        <p class="hint hint--warn" data-pb-reasons-note hidden></p></div>
      <div class="form__row form__row--full"><label class="lbl" for="pb-handoff-triggers">${escapeHTML(PS.handoffAlso)} <span class="opt">${escapeHTML(PS.optional)}</span></label>
        <textarea class="txt" id="pb-handoff-triggers" data-pb="handoff.triggers" rows="2" maxlength="${limits.handoffChars || 500}" placeholder="${escapeHTML(PS.handoffAlsoPh)}"${off}>${escapeHTML(h.triggers || '')}</textarea></div>
      <div class="form__row form__row--full"><label class="lbl" for="pb-handoff-message">${escapeHTML(PS.handoffMessage)} <span class="opt">${escapeHTML(PS.optional)}</span></label>
        <textarea class="txt" id="pb-handoff-message" data-pb="handoff.message" rows="2" maxlength="${limits.handoffChars || 500}" placeholder="${escapeHTML(PS.handoffMessagePh)}"${off}>${escapeHTML(h.message || '')}</textarea></div>`;
  return personaPanel(escapeHTML(PS.handoffTitle), `<label class="form__check"><input type="checkbox" id="pb-handoff" data-pb="handoff.enabled"${h.enabled ? ' checked' : ''}${off} /> ${escapeHTML(PS.handoffEnable)}</label>
      ${details}
      <p class="hint">${escapeHTML(PS.handoffHint)}</p>`,
  { id: 'persona-handoff', meta: `<span class="pill${h.enabled ? ' pill--ok' : ''}" id="pb-handoff-pill">${escapeHTML(h.enabled ? PS.handoffOn : PS.handoffOff)}</span>` });
}

// ── History ────────────────────────────────────────────────────────────────────────────────────────

const personaChangeText = v => ps(`change.${v.change}`, { n: v.sourceCount, from: v.restoredFrom });

function personaHistoryPane(p) {
  const versions = state.personaVersions;
  if (!versions) return `<section class="panel"><div class="panel__body"><p class="hint">${escapeHTML(PS.historyLoading)}</p></div></section>`;
  const rows = versions.map(v => `<tr${v.live ? ' class="is-current"' : ''}>
      <td class="name nowrap">${escapeHTML(ps('versionShort', { n: v.version }))}${v.live ? ` <span class="pill pill--ok">${escapeHTML(PS.live)}</span>` : ''}</td>
      <td class="long" data-label="${escapeHTML(PS.thChange)}">${escapeHTML(personaChangeText(v))}${v.trimmed ? `<div class="sub">${escapeHTML(PS.trimmed)}</div>` : ''}</td>
      <td data-label="${escapeHTML(PS.thBy)}">${escapeHTML(v.author ? personaAuthor(v.author) : PS.byAutomatic)}</td>
      <td class="nowrap" data-label="${escapeHTML(PS.thWhen)}">${escapeHTML(fmtDate(v.createdAt))}</td>
      <td class="right"><div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-version="${v.version}">${escapeHTML(PS.view)}</button>${p.canEdit && !v.live ? `<button class="btn btn--sm" type="button" data-restore="${v.version}">${escapeHTML(PS.restore)}</button>` : ''}</div></td>
    </tr>`).join('');
  const empty = `<tr><td colspan="5"><div class="empty"><p class="empty__title">${escapeHTML(PS.historyEmptyTitle)}</p><p class="empty__desc">${escapeHTML(PS.historyEmptyDesc)}</p></div></td></tr>`;
  return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.historyTitle)} <span class="tag">${fmtCount(versions.length)}</span></h2></header>
      <div class="panel__body"><p class="hint">${escapeHTML(ps('historyHint', { n: fmtCount(versions.length) }))} ${p.version ? escapeHTML(ps('historyTokens', { n: fmtCount(p.tokenEstimate || 0) })) : ''}</p></div>
      <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${escapeHTML(PS.thVersion)}</th><th>${escapeHTML(PS.thChange)}</th><th>${escapeHTML(PS.thBy)}</th><th>${escapeHTML(PS.thWhen)}</th><th class="right">${escapeHTML(STR.thActions)}</th></tr></thead><tbody>${rows || empty}</tbody></table></div>
    </section>`;
}

// Persona → Channels lives in channels.js (ChannelsUI); the test chat beside it says where the AI would stay quiet.
const pc = (key, params) => I18N.t(`app.personaStudio.channels.${key}`, params);

function personaBehaviorFacts(b = {}) {
  const choice = field => (b[field] ? ps(`options.${field}.${b[field]}`) : null);
  const row = (label, value) => (value ? [label, escapeHTML(String(value))] : null);
  const h = b.handoff || {};
  const facts = PERSONA_BUSINESS.map(k => personaText(b.business?.[k]) && `${ps(`business.${k}`)}: ${b.business[k].trim()}`).filter(Boolean);
  const can = PERSONA_CAPABILITIES.filter(k => b.capabilities?.[k] === false).map(k => ps(`capability.${k}`));
  return recordFieldsHtml([
    row(PS.botName, b.botName || PS.optionAuto),
    row(PS.disclosure, choice('disclosure') || PS.optionAuto),
    row(PS.language, b.language ? `${personaLanguageLabel(b.language)}${b.languageStrict ? ` · ${PS.languageStrictShort}` : ''}` : PS.languageAuto),
    row(PS.tone, choice('tone') || PS.optionAuto),
    row(PS.addressForm, choice('addressForm') || PS.optionAuto),
    row(PS.replyLength, choice('replyLength') || PS.optionAuto),
    row(PS.emoji, choice('emoji') || PS.optionAuto),
    b.greeting ? row(PS.greeting, b.greeting) : null,
    facts.length ? row(PS.businessTitle, facts.join(' · ')) : null,
    (b.faq || []).length ? row(PS.faqTitle, ps('nQuestions', { n: b.faq.length })) : null,
    (b.rules || []).length ? row(PS.rulesTitle, b.rules.join(' · ')) : null,
    row(PS.scope, choice('scope') || PS.optionAuto),
    row(PS.unknown, choice('unknown') || PS.optionAuto),
    can.length ? row(PS.canDoOff, can.join(' · ')) : null,
    row(PS.handoffTitle, h.enabled ? [PS.handoffOn, ...(h.reasons || []).map(r => ps(`reasons.${r}`)), h.triggers, h.message].filter(Boolean).join(' · ') : PS.handoffOff),
  ]);
}

async function openPersonaVersion(n) {
  let v;
  try { v = await api(`/app/api/persona/versions/${encodeURIComponent(n)}`); } catch (err) { return personaFail(err); }
  const canRestore = state.persona?.canEdit && !v.live;
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `${recordFieldsHtml([[PS.thChange, escapeHTML(personaChangeText(v))], [PS.thBy, escapeHTML(v.author ? personaAuthor(v.author) : PS.byAutomatic)], [PS.thWhen, escapeHTML(fmtDate(v.createdAt))]])}
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.versionSettings)}</h2></header><div class="panel__body">${personaBehaviorFacts(v.behavior)}</div></section>
    <div class="form__row form__row--full"><label class="lbl" for="pv-text">${escapeHTML(PS.instructionsTitle)}</label>
      <textarea class="txt" id="pv-text" rows="14" readonly>${escapeHTML(v.compiledInstructions || '')}</textarea></div>
    ${docFootHtml(`<button class="btn btn--sm btn--ghost" type="button" data-close>${escapeHTML(PS.close)}</button>${canRestore ? `<button class="btn btn--sm btn--primary" type="button" data-restore-version>${escapeHTML(PS.restoreThis)}</button>` : ''}`)}`;
  openDrawer(ps('versionTitle', { n: v.version }), body, true, { eyebrow: labels.persona });
  $('[data-restore-version]', body)?.addEventListener('click', () => restorePersonaVersion(v.version));
}

async function restorePersonaVersion(n) {
  const ok = await confirmDialog({ title: ps('restoreTitle', { n }), body: PS.restoreBody, okLabel: PS.restore, danger: false });
  if (!ok) return;
  try {
    state.persona = await api(`/app/api/persona/versions/${encodeURIComponent(n)}/restore`, { method: 'POST', body: '{}' });
  } catch (err) { return personaFail(err); }
  delete state.personaDraft.behavior;
  delete state.personaDraft.compiledInstructions;
  state.personaPreview = null;
  personaUi.checked = false;
  closeDrawer({ dismissed: true });
  toast(ps('restored', { n }));
  await loadPersonaVersions().catch(() => { state.personaVersions = null; });
  render();
}

// ── What the bot reads ─────────────────────────────────────────────────────────────────────────────

function personaPreviewMeta(preview) {
  if (!preview) return PS.previewLoading;
  if (!preview.block) return PS.previewEmpty;
  return ps(preview.draft ? 'previewTokensDraft' : 'previewTokens', { n: fmtCount(preview.tokenEstimate) });
}

async function loadPersonaPreview() {
  const draft = personaDraftPayload();
  try {
    const res = await api('/app/api/persona/preview', { method: 'POST', body: JSON.stringify(draft ? { draft } : {}) });
    state.personaPreview = { ...res, draft: !!draft };
  } catch (err) {
    state.personaPreview = null;
    return personaFail(err);
  }
  const box = $('#persona-preview');
  if (box) box.value = state.personaPreview.block || '';
  const meta = $('#persona-preview-meta');
  if (meta) meta.textContent = personaPreviewMeta(state.personaPreview);
}

function openPersonaReads() {
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `<p class="hint">${escapeHTML(PS.previewHint)}</p>
    <label class="visually-hidden" for="persona-preview">${escapeHTML(PS.previewTitle)}</label>
    <textarea class="txt" id="persona-preview" rows="20" readonly></textarea>
    <p class="hint" id="persona-preview-meta">${escapeHTML(PS.previewLoading)}</p>
    ${docFootHtml(`<button class="btn btn--sm btn--ghost" type="button" data-close>${escapeHTML(PS.close)}</button>`)}`;
  openDrawer(PS.previewTitle, body, true, { eyebrow: labels.persona });
  state.personaPreview = null;
  loadPersonaPreview();
}

// ── Documents ──────────────────────────────────────────────────────────────────────────────────────

async function openPersonaSource(id) {
  let s;
  try { s = await api(`/app/api/persona/sources/${encodeURIComponent(id)}`); } catch (err) { return personaFail(err); }
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `${recordFieldsHtml([
      [PS.thState, escapeHTML(s.compiled ? PS.sourceSynced : PS.sourcePending)],
      [PS.thAdded, escapeHTML(fmtDate(s.createdAt))],
      s.addedBy ? [PS.thBy, escapeHTML(personaAuthor(s.addedBy))] : null,
      [PS.size, escapeHTML(ps('chars', { n: fmtCount(s.chars) }))],
    ])}
    ${s.truncated ? `<p class="hint hint--warn">${escapeHTML(PS.truncated)}</p>` : ''}
    <div class="form__row form__row--full"><label class="lbl" for="ps-text">${escapeHTML(PS.sourceText)}</label>
      <textarea class="txt" id="ps-text" rows="18" readonly>${escapeHTML(s.content)}</textarea></div>
    ${docFootHtml(`<button class="btn btn--sm btn--ghost" type="button" data-close>${escapeHTML(PS.close)}</button>`)}`;
  openDrawer(s.label, body, true, { eyebrow: ps(`kind.${s.kind}`) });
}

async function removePersonaSource(source) {
  const ok = await confirmDialog({ title: ps('removeTitle', { name: source.label }), body: source.compiled ? PS.removeCompiledBody : PS.removePendingBody, okLabel: PS.remove });
  if (!ok) return;
  try {
    state.persona = await api(`/app/api/persona/sources/${encodeURIComponent(source.id)}`, { method: 'DELETE' });
  } catch (err) { return personaFail(err); }
  toast(PS.sourceRemoved);
  renderKeepingFocus();
}

async function startPersonaSynthesis(rebuild) {
  if (rebuild && !(await confirmDialog({ title: PS.rebuildTitle, body: PS.rebuildBody, okLabel: PS.rebuild, danger: false }))) return;
  try {
    state.persona = await api(`/app/api/persona/${rebuild ? 'rebuild' : 'compile'}`, { method: 'POST', body: '{}' });
  } catch (err) { return personaFail(err); }
  toast(rebuild ? PS.rebuildStarted : PS.synthesisStarted);
  renderKeepingFocus();
  refreshPersona().catch(() => {});
}

async function uploadPersonaFile(file) {
  const form = new FormData();
  form.append('file', file);
  const res = await fetch('/app/api/persona/sources/file', { method: 'POST', headers: token ? { Authorization: `Bearer ${token}` } : {}, body: form });
  if (res.status === 401) { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); throw new Error('unauthorized'); }
  if (!res.ok) throw await apiError(res);
  return res.json();
}

// ── Saving ─────────────────────────────────────────────────────────────────────────────────────────

function personaSavebarHtml() {
  return `<div class="savebar" data-persona-savebar>
    <p class="savebar__status" data-persona-status aria-live="polite"></p>
    <div class="actions"><button class="btn btn--ghost" type="button" data-persona-discard disabled>${escapeHTML(PS.discard)}</button><button class="btn btn--primary" type="button" data-persona-save disabled>${escapeHTML(PS.save)}</button></div>
  </div>`;
}

function paintPersonaSavebar() {
  const bar = $('[data-persona-savebar]');
  if (!bar) return;
  const areas = personaDirtyAreas();
  const dirty = areas.length > 0;
  bar.classList.toggle('is-dirty', dirty);
  const names = areas.map(a => ps(`tab.${a}`)).join(', ');
  const fix = dirty ? personaFixCount() : 0;
  const status = !dirty ? escapeHTML(PS.allSaved)
    : `<span class="savebar__dot" aria-hidden="true"></span>${escapeHTML(fix ? ps('unsavedFix', { areas: names, n: fmtCount(fix) }) : ps('unsavedIn', { areas: names }))}`;
  const el = $('[data-persona-status]', bar);
  if (el.innerHTML !== status) el.innerHTML = status;
  const save = $('[data-persona-save]', bar);
  save.disabled = !dirty || personaSaving;
  save.textContent = personaSaving ? PS.saving : PS.save;
  $('[data-persona-discard]', bar).disabled = !dirty || personaSaving;
}

/** Goes to the field a refusal names (a tab of its own when needed) and puts the cursor in it. */
function focusPersonaField(field, index) {
  const base = (field || '').split('.')[0];
  const tab = field === 'compiledInstructions' ? 'knowledge'
    : PERSONA_KNOWLEDGE_FIELDS.includes(base) ? 'knowledge'
      : PERSONA_RULES_FIELDS.includes(base) ? 'rules'
        : PERSONA_PROFILE_FIELDS.includes(base) ? 'profile' : null;
  if (tab && personaTabNow() !== tab) { state.personaTab = tab; render(); }
  const id = {
    compiledInstructions: 'persona-text', botName: 'pb-botName', greeting: 'pb-greeting', language: 'pb-language',
    'handoff.triggers': 'pb-handoff-triggers', 'handoff.message': 'pb-handoff-message', 'handoff.reasons': 'pb-handoff',
    'faq.question': `pf-${index}-q`, 'faq.answer': `pf-${index}-a`, faq: 'persona-faq-panel', rules: 'pr-0',
  }[field] || (base === 'business' ? `pb-business-${field.split('.')[1]}` : null);
  const el = (id && document.getElementById(id)) || $(`input[name="pb-${field}"]:checked`) || $(`input[name="pb-${field}"]`);
  if (!el) return;
  el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  el.focus?.({ preventScroll: true });
}

async function savePersonaPage() {
  if (personaSaving || !state.persona?.canEdit) return;
  // A start word still in its field is part of what is saved.
  window.ChannelsUI?.commitPending();
  const areas = personaDirtyAreas();
  if (!areas.length) return;
  const issue = personaFaqIssues()[0];
  if (issue) {
    personaUi.checked = true;
    toast(personaErrorText(issue.code, issue));
    paintPersonaDraft();
    return focusPersonaField(issue.field, issue.index);
  }
  const channelsDirty = areas.includes('channels');
  if (channelsDirty) {
    const problem = window.ChannelsUI.problem();
    if (problem) {
      if (personaTabNow() !== 'channels') { state.personaTab = 'channels'; render(); }
      return window.ChannelsUI.refuse(problem);
    }
  }
  personaSaving = true;
  paintPersonaSavebar();
  let saved = false;
  try {
    const draft = state.personaDraft;
    const body = {};
    if (draft.behavior) body.behavior = behaviorPayload(draft.behavior);
    if (draft.compiledInstructions != null) body.compiledInstructions = draft.compiledInstructions;
    if (Object.keys(body).length) {
      const sentBehavior = body.behavior ? JSON.stringify(body.behavior) : null;
      const sentText = body.compiledInstructions;
      try {
        state.persona = await api('/app/api/persona', { method: 'PUT', body: JSON.stringify(body) });
      } catch (err) {
        if (err?.message === 'unauthorized') return;
        personaUi.checked = true;
        toast(personaErrorText(err?.code, err));
        return focusPersonaField(err?.field, err?.body?.index);
      }
      // Typing goes on while the request is out; only what was sent is saved, the rest stays unsaved.
      if (sentBehavior && state.personaDraft.behavior && JSON.stringify(behaviorPayload(state.personaDraft.behavior)) === sentBehavior) delete state.personaDraft.behavior;
      else settleBehavior();
      if (sentText != null && state.personaDraft.compiledInstructions === sentText) delete state.personaDraft.compiledInstructions;
      state.personaVersions = null;
      state.personaPreview = null;
    }
    if (channelsDirty && !(await window.ChannelsUI.save())) return;
    personaUi.checked = false;
    saved = true;
  } finally {
    personaSaving = false;
    renderKeepingFocus();
  }
  if (saved) toast(PS.saved);
}

async function discardPersonaPage() {
  if (!(await confirmDialog({ title: PS.discardTitle, body: PS.discardBody, okLabel: PS.discard, danger: false }))) return;
  delete state.personaDraft.behavior;
  delete state.personaDraft.compiledInstructions;
  window.ChannelsUI?.discard();
  personaUi.checked = false;
  state.personaPreview = null;
  render();
}

// ── The test chat ──────────────────────────────────────────────────────────────────────────────────

/** Whether test messages go with unsaved changes: settings or instructions. */
const personaTestDirty = () => !!personaDraftPayload();

function personaTestPanel() {
  const dirty = personaTestDirty();
  return `<section class="panel">
    <header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.testTitle)}</h2>
      <div class="panel__tools"><span class="pill pill--warn" id="persona-test-draft"${dirty ? '' : ' hidden'}>${escapeHTML(PS.testDraft)}</span><button class="btn btn--sm btn--ghost" type="button" id="persona-chat-clear">${escapeHTML(STR.clear)}</button></div></header>
    <div class="panel__body form">
      <p class="hint">${escapeHTML(PS.testHint)}</p>
      <div class="chip-picks" role="group" aria-label="${escapeHTML(PS.triesAria)}">${PERSONA_TRIES.map(k => `<button type="button" class="chip" data-persona-try="${k}">${escapeHTML(ps(`try.${k}`))}</button>`).join('')}</div>
      <div class="chat__log" id="persona-chat-log" role="log" aria-live="polite"></div>
      <form class="chat__form" id="persona-chat-form">
        <input class="inp chat__input" id="persona-chat-input" placeholder="${escapeHTML(STR.chatPlaceholder)}" aria-label="${escapeHTML(STR.chatPlaceholder)}" autocomplete="off" />
        <button class="btn btn--primary" type="submit" id="persona-chat-send"${personaChatBusy ? ' disabled' : ''}>${escapeHTML(STR.send)}</button>
      </form>
    </div>
  </section>`;
}

function renderPersonaChatLog(busy = false) {
  const log = $('#persona-chat-log');
  if (!log) return;
  const msgs = state.personaChat || [];
  if (!msgs.length && !busy) {
    log.innerHTML = `<div class="chat__empty">${escapeHTML(STR.chatEmpty)}</div>`;
    return;
  }
  const names = channels => channels.map(c => personaChannelLabel(c)).join(' / ');
  log.innerHTML = msgs.map(m => {
    if (m.role === 'note') return `<div class="chat__note">${escapeHTML(m.content)}</div>`;
    const bubble = `<div class="chat__msg chat__msg--${m.role === 'user' ? 'user' : 'bot'}">${renderChatText(m.content)}</div>`;
    const notes = [
      m.handoff && (m.reason ? ps('handoffNoteReason', { reason: m.reason }) : PS.handoffNote),
      m.aiOffChannels?.length && pc('testAiOff', { channels: names(m.aiOffChannels) }),
      m.gatedChannels?.length && pc('testGated', { channels: names(m.gatedChannels) }),
    ].filter(Boolean);
    return bubble + notes.map(n => `<div class="chat__note">${escapeHTML(n)}</div>`).join('');
  }).join('') + (busy ? `<div class="chat__msg chat__msg--bot chat__typing">${escapeHTML(STR.typing)}</div>` : '');
  log.scrollTop = log.scrollHeight;
}

/** Sends a test message to the AI, with the unsaved settings and instructions. */
async function sendPersonaTest(text) {
  const content = (text || '').trim();
  if (personaChatBusy || !content) return;
  state.personaChat.push({ role: 'user', content });
  personaChatBusy = true;
  const send = $('#persona-chat-send');
  if (send) send.disabled = true;
  renderPersonaChatLog(true);
  const draft = personaDraftPayload();
  const tested = (state.personaChat || []).some(m => m.role === 'assistant');
  try {
    const messages = state.personaChat.filter(m => m.role === 'user' || m.role === 'assistant').map(({ role, content: c }) => ({ role, content: c }));
    const body = { messages, ...(draft && { draft }) };
    const res = await api('/app/api/persona/test', { method: 'POST', body: JSON.stringify(body) });
    state.personaChat.push({ role: 'assistant', content: res.reply, handoff: res.handoff, reason: res.handoffReason, aiOffChannels: res.aiOffChannels || [], gatedChannels: res.gatedChannels || [] });
  } catch (err) {
    if (err?.message !== 'unauthorized') {
      state.personaChat.push({ role: 'note', content: personaErrorText(err?.code, err) });
    }
  } finally {
    personaChatBusy = false;
    renderPersonaChatLog();
    const again = $('#persona-chat-send');
    if (again) again.disabled = false;
    $('#persona-chat-input')?.focus();
    // The Overview's "Try it" line ticks once the bot has answered for the first time.
    const answered = (state.personaChat || []).some(m => m.role === 'assistant');
    if (!tested && answered && personaTabNow() === 'overview' && state.active === 'persona') renderKeepingFocus();
  }
}

/**
 * Keeps what follows the draft in step without a redraw: the test chat's "Unsaved changes" pill, the save
 * bar, counts, the strict-language box and notes that depend on other settings. ChannelsUI calls it too.
 */
function paintPersonaDraft() {
  const pill = $('#persona-test-draft');
  if (pill) pill.hidden = !personaTestDirty();
  paintPersonaSavebar();
  const b = personaBehavior();
  const strict = $('#pb-languageStrict');
  if (strict) {
    strict.disabled = !b.language || !state.persona?.canEdit;
    if (!b.language) strict.checked = false;
  }
  const rules = $('[data-pb-rules-count]');
  if (rules) rules.textContent = fmtCount((b.rules || []).filter(r => personaText(r)).length);
  const faq = $('[data-pb-faq-count]');
  if (faq) faq.textContent = fmtCount((b.faq || []).filter(f => personaText(f.question) && personaText(f.answer)).length);
  const handoffPill = $('#pb-handoff-pill');
  if (handoffPill) {
    handoffPill.textContent = b.handoff?.enabled ? PS.handoffOn : PS.handoffOff;
    handoffPill.classList.toggle('pill--ok', !!b.handoff?.enabled);
  }
  const unknownNote = $('[data-pb-unknown-note]');
  if (unknownNote) {
    const text = b.unknown === 'HAND_OVER' && !b.handoff?.enabled ? PS.unknownNeedsHandoff
      : b.unknown === 'SHARE_CONTACT' && !personaText(b.business?.contact) ? PS.unknownNeedsContact : '';
    unknownNote.textContent = text;
    unknownNote.hidden = !text;
  }
  const reasonsNote = $('[data-pb-reasons-note]');
  if (reasonsNote) {
    const none = b.handoff?.enabled && !(b.handoff.reasons || []).length && !personaText(b.handoff.triggers);
    reasonsNote.textContent = none ? PS.handoffNoReason : '';
    reasonsNote.hidden = !none;
  }
  const issues = personaUi.checked ? personaFaqIssues(b) : [];
  $$('[data-faq-issue]').forEach(el => {
    const i = Number(el.dataset.faqIssue);
    const issue = issues.find(x => x.index === i);
    el.textContent = issue ? ps(issue.code === 'answer_required' ? 'faqNeedsAnswer' : 'faqNeedsQuestion') : '';
    el.hidden = !issue;
    $(`#pf-${i}-q`)?.toggleAttribute('aria-invalid', issue?.code === 'question_required');
    $(`#pf-${i}-a`)?.toggleAttribute('aria-invalid', issue?.code === 'answer_required');
  });
}

// ── Wiring ─────────────────────────────────────────────────────────────────────────────────────────

/** A change in a settings field ([data-pb] names its path in the settings). */
function onPersonaField(el) {
  const path = el.dataset.pb;
  let value;
  if (el.type === 'checkbox') value = el.checked;
  else if (el.type === 'radio') { if (!el.checked) return false; value = el.value || null; }
  else value = el.value;
  const b = editableBehavior();
  setPersonaPath(b, path, value);
  if (path === 'language' && !value) b.languageStrict = false;
  if (path === 'handoff.enabled' && value && !b.handoff.reasons.length && !personaText(b.handoff.triggers)) b.handoff.reasons = PERSONA_REASONS.slice(0, 3);
  settleBehavior();
  return path === 'handoff.enabled';
}

/** Adds a rule after row [after] (or last) and puts the cursor in it. */
function addPersonaRule(text = '', after = null) {
  const b = editableBehavior();
  const max = state.persona?.limits?.rules || 25;
  if (b.rules.length >= max) return toast(ps('rulesLimit', { max: fmtCount(max), chars: fmtCount(state.persona?.limits?.ruleChars || 300) }));
  const at = after == null ? b.rules.length : after + 1;
  b.rules.splice(at, 0, text);
  settleBehavior();
  renderKeepingFocus();
  const input = document.getElementById(`pr-${at}`);
  input?.focus();
  input?.scrollIntoView({ block: 'nearest' });
}

function wirePersona(root, tab, p) {
  $$('[data-persona-tab]', root).forEach(b => b.addEventListener('click', () => openPersonaTab(b.dataset.personaTab)));
  $$('[data-persona-go]', root).forEach(b => b.addEventListener('click', () => openPersonaTab(b.dataset.personaGo, b.dataset.personaFocus, true)));
  $$('[data-persona-settings]', root).forEach(b => b.addEventListener('click', async () => {
    state.settingsSection = 'channels';
    await setActive('settings');
  }));
  $('[data-persona-reads]', root)?.addEventListener('click', openPersonaReads);
  $('[data-persona-try-focus]', root)?.addEventListener('click', () => {
    const input = $('#persona-chat-input');
    input?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    input?.focus({ preventScroll: true });
  });
  if (tab === 'channels') window.ChannelsUI.wire(root);
  $$('[data-persona-chatbot]', root).forEach(b => b.addEventListener('click', () => setActive('chatbot')));
  $$('[data-persona-compile]', root).forEach(b => b.addEventListener('click', () => startPersonaSynthesis(false)));
  $$('[data-persona-rebuild]', root).forEach(b => b.addEventListener('click', () => startPersonaSynthesis(true)));

  const main = $('.persona-studio__main', root);
  // Ctrl+S saves every tab, wherever the cursor is in the editor.
  main.addEventListener('keydown', e => {
    if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 's') {
      e.preventDefault();
      if (p.canEdit) savePersonaPage();
    }
  });
  $('[data-persona-save]', root)?.addEventListener('click', savePersonaPage);
  $('[data-persona-discard]', root)?.addEventListener('click', discardPersonaPage);

  const pane = $('.persona-studio__pane', root);
  if (p.canEdit && tab !== 'channels') {
    const onEdit = e => {
      const el = e.target;
      if (el.dataset.pb != null) {
        const redraw = onPersonaField(el);
        return redraw ? renderKeepingFocus() : paintPersonaDraft();
      }
      if (el.dataset.pbReason != null) {
        const h = editableBehavior().handoff;
        h.reasons = el.checked ? PERSONA_REASONS.filter(r => r === el.dataset.pbReason || h.reasons.includes(r)) : h.reasons.filter(r => r !== el.dataset.pbReason);
        settleBehavior();
        return paintPersonaDraft();
      }
      if (el.dataset.rule != null) {
        editableBehavior().rules[Number(el.dataset.rule)] = el.value;
        settleBehavior();
        return paintPersonaDraft();
      }
      if (el.dataset.faq != null) {
        const row = editableBehavior().faq[Number(el.dataset.faq)];
        if (row) row[el.dataset.faqField] = el.value;
        settleBehavior();
        return paintPersonaDraft();
      }
      return undefined;
    };
    // Text follows each key; boxes, radios and lists their change (which also sends an input event).
    const picked = el => el.type === 'radio' || el.type === 'checkbox' || el.tagName === 'SELECT';
    pane.addEventListener('input', e => { if (!picked(e.target)) onEdit(e); });
    pane.addEventListener('change', e => { if (picked(e.target)) onEdit(e); });
    pane.addEventListener('keydown', e => {
      const el = e.target;
      // Enter in a rule starts the next one; Backspace in an empty one removes it.
      if (el.dataset.rule == null) return;
      const i = Number(el.dataset.rule);
      if (e.key === 'Enter') { e.preventDefault(); addPersonaRule('', i); }
      if (e.key === 'Backspace' && !el.value && editableBehavior().rules.length) {
        e.preventDefault();
        editableBehavior().rules.splice(i, 1);
        settleBehavior();
        renderKeepingFocus();
        (document.getElementById(`pr-${Math.max(0, i - 1)}`) || $('#persona-rule-add'))?.focus();
      }
    });
    pane.addEventListener('click', e => {
      const b = e.target.closest('button');
      if (!b || !pane.contains(b)) return;
      const ds = b.dataset;
      if (ds.ruleAdd != null) return addPersonaRule();
      if (ds.ruleIdea != null) return addPersonaRule(ps(`ruleIdeas.${ds.ruleIdea}`));
      if (ds.ruleRemove != null) {
        const i = Number(ds.ruleRemove);
        editableBehavior().rules.splice(i, 1);
        settleBehavior();
        renderKeepingFocus();
        return (document.getElementById(`pr-${Math.min(i, editableBehavior().rules.length - 1)}`) || $('#persona-rule-add'))?.focus();
      }
      if (ds.faqAdd != null) {
        const faq = editableBehavior().faq;
        faq.push({ question: '', answer: '' });
        settleBehavior();
        renderKeepingFocus();
        const input = document.getElementById(`pf-${faq.length - 1}-q`);
        input?.scrollIntoView({ block: 'center', behavior: 'smooth' });
        return input?.focus({ preventScroll: true });
      }
      if (ds.faqRemove != null) {
        const i = Number(ds.faqRemove);
        const faq = editableBehavior().faq;
        faq.splice(i, 1);
        settleBehavior();
        renderKeepingFocus();
        return (document.getElementById(`pf-${Math.min(i, faq.length - 1)}-q`) || $('#persona-faq'))?.focus();
      }
      return undefined;
    });
  }

  if (tab === 'profile') {
    const agents = $('#persona-agents', root);
    if (agents && hasModule('agents') && window.AgentsUI) {
      window.AgentsUI.mountPersonaAgents(agents, p).then(() => { agents.hidden = !agents.childElementCount; });
    }
  }

  const noteForm = $('#persona-note-form', root);
  if (noteForm) {
    const note = $('#persona-note', noteForm);
    const max = p.limits?.noteChars || 20000;
    note.addEventListener('input', () => {
      state.personaDraft.note = note.value;
      const count = personaCountHint(note.value.length, max, false);
      const hint = $('#persona-note-count', noteForm);
      hint.className = count.cls;
      hint.textContent = count.text;
    });
    noteForm.addEventListener('submit', async e => {
      e.preventDefault();
      const content = note.value.trim();
      if (!content) return toast(personaErrorText('content_required', { field: 'content' }));
      const submit = $('button[type=submit]', noteForm);
      submit.disabled = true;
      try {
        state.persona = await api('/app/api/persona/sources', { method: 'POST', body: JSON.stringify({ content }) });
      } catch (err) {
        submit.disabled = false;
        return personaFail(err);
      }
      delete state.personaDraft.note;
      toast(PS.noteAdded);
      renderKeepingFocus();
      refreshPersona().catch(() => {});
    });
  }
  $('#persona-file', root)?.addEventListener('change', async e => {
    const input = e.target;
    const file = input.files[0];
    if (!file) return;
    const limits = p.limits || {};
    const ext = file.name.includes('.') ? file.name.split('.').pop().toLowerCase() : '';
    if (limits.fileTypes && !limits.fileTypes.includes(ext)) { input.value = ''; return toast(personaErrorText('unsupported_file')); }
    if (limits.uploadBytes && file.size > limits.uploadBytes) { input.value = ''; return toast(personaErrorText('file_too_large', { limit: limits.uploadBytes, field: 'file' })); }
    input.disabled = true;
    try {
      state.persona = await uploadPersonaFile(file);
    } catch (err) {
      input.disabled = false;
      input.value = '';
      return personaFail(err);
    }
    toast(PS.fileUploaded);
    renderKeepingFocus();
    refreshPersona().catch(() => {});
  });
  $$('[data-view-source]', root).forEach(b => b.addEventListener('click', () => openPersonaSource(b.dataset.viewSource)));
  $$('[data-del-source]', root).forEach(b => b.addEventListener('click', () => {
    const source = (p.sources || []).find(s => s.id === b.dataset.delSource);
    if (source) removePersonaSource(source);
  }));

  const area = $('#persona-text', root);
  if (area && p.canEdit) {
    const max = p.limits?.instructionsChars || 12000;
    area.addEventListener('input', () => {
      state.personaDraft.compiledInstructions = area.value === (p.compiledInstructions || '') ? undefined : area.value;
      if (state.personaDraft.compiledInstructions === undefined) delete state.personaDraft.compiledInstructions;
      const count = personaCountHint(area.value.length, max, true);
      const hint = $('#persona-text-count', root);
      hint.className = count.cls;
      hint.textContent = count.text;
      paintPersonaDraft();
    });
  }

  $$('[data-version]', root).forEach(b => b.addEventListener('click', () => openPersonaVersion(b.dataset.version)));
  $$('[data-restore]', root).forEach(b => b.addEventListener('click', () => restorePersonaVersion(b.dataset.restore)));

  renderPersonaChatLog(personaChatBusy);
  $('#persona-chat-clear', root).addEventListener('click', () => { state.personaChat = []; renderPersonaChatLog(); });
  $$('[data-persona-try]', root).forEach(b => b.addEventListener('click', () => sendPersonaTest(ps(`try.${b.dataset.personaTry}`))));
  $('#persona-chat-form', root).addEventListener('submit', e => {
    e.preventDefault();
    const input = $('#persona-chat-input');
    const text = input.value;
    input.value = '';
    sendPersonaTest(text);
  });
  paintPersonaDraft();
}
