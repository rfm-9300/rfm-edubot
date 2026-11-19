/* Chatbot (/app → Chatbot): the company's fixed chat flows, which answer customers before the AI. A flow starts when
   a message holds one of its keywords and walks through steps: a message with options (buttons the customer taps, or
   numbers and words they type), a question whose answer is kept, a condition (opening hours, a client we know, an
   earlier answer), an action (create a client, create a booking, tell the team) or a hand-over to a person. A flow can
   let the AI read an answer nothing else could; the AI only says what it means, never what to reply.
   The page is a board, one flow at a time (chatbot-board.js draws it): a start card with the keywords, then the steps
   as cards joined by lines, which people move around, link by dragging from an output dot to a card, and open in the
   side panel to edit. The side panel also runs the test chat (POST /app/api/chatbot/test) with the unsaved edits,
   writing nothing, while the board lights up the way the customer went. app.js mounts it with init() and passes its
   helpers; the server checks every rule again when the flows are saved (PUT /app/api/chatbot/flows). Unsaved edits
   live in `draft`, a copy of the saved flows taken on the first edit; undo and redo step through earlier copies. */
(function () {
  let d = null;
  const T = I18N.section('app.chatbot');
  const B = I18N.section('app.chatbot.board');
  const PS = I18N.section('app.personaStudio');
  const tr = (key, params) => I18N.t(`app.chatbot.${key}`, params);
  const esc = s => d.escapeHTML(s == null ? '' : String(s));
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const count = n => d.fmtCount(n);
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  const KINDS = ['MESSAGE', 'ASK', 'CONDITION', 'ACTION', 'HANDOFF'];
  const EXPECTS = ['TEXT', 'NAME', 'PHONE', 'EMAIL', 'NUMBER', 'DATE', 'TIME', 'YES_NO'];
  const CHECKS = ['HOURS', 'KNOWN_CLIENT', 'ANSWER'];
  const ACTIONS = ['CREATE_CLIENT', 'CREATE_BOOKING', 'NOTIFY_TEAM'];
  const SHOWS = ['AUTO', 'TAPS_AND_NUMBERS', 'NUMBERED', 'HIDDEN'];
  const CHANNELS = ['WHATSAPP', 'INSTAGRAM', 'WEB'];
  /** Answers other steps read by name; picking one also sets what the question expects. */
  const KEYS = { name: 'NAME', phone: 'PHONE', email: 'EMAIL', service: 'TEXT', date: 'DATE', time: 'TIME', note: 'TEXT' };
  const DAYS = [1, 2, 3, 4, 5, 6, 7];
  const VAR = /^[a-z][a-z0-9_]{0,29}$/;
  /** The board's start card: the flow's keywords, linked to its first step. */
  const START = '__start';
  /** A flow and a step as the server keeps them, in its order, so a draft compares with what was saved. */
  const FLOW_FIELDS = ['id', 'name', 'enabled', 'channels', 'keywords', 'steps', 'aiReads', 'afterMisses', 'handoverText', 'retries'];
  const STEP_FIELDS = ['id', 'kind', 'text', 'options', 'show', 'listLabel', 'retryText', 'next', 'key', 'expect', 'check', 'hours', 'answerKey', 'answerIs', 'whenTrue', 'whenFalse', 'action', 'serviceId', 'failNext'];
  /** What the server leaves out of a flow when it holds its default (kotlinx doesn't encode defaults). */
  const FLOW_DEFAULTS = { aiReads: false, afterMisses: 'HAND_OVER', handoverText: '', retries: 1 };
  /** Room left between cards, and a new card's size until it is measured. */
  const GAP = 88;
  const CARD = { w: 272, h: 120 };

  const icon = body => `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">${body}</svg>`;
  const ICON = {
    MESSAGE: icon('<path d="M4 5.5h16v10.5H9.5L5 19.5V16H4z"/>'),
    ASK: icon('<circle cx="12" cy="12" r="8.5"/><path d="M9.7 9.6a2.4 2.4 0 1 1 3.3 2.2c-.6.3-1 .8-1 1.4v.4"/><path d="M12 16.6v.1"/>'),
    CONDITION: icon('<path d="M12 3.5 20.5 12 12 20.5 3.5 12z"/>'),
    ACTION: icon('<path d="M13 3 5.5 13.5H11L10 21l7.5-10.5H12z"/>'),
    HANDOFF: icon('<circle cx="12" cy="8" r="3.5"/><path d="M5 20c.9-3.6 3.6-5.5 7-5.5s6.1 1.9 7 5.5"/>'),
    START: icon('<path d="M8 5v14l11-7z"/>'),
    undo: icon('<path d="M9 14 4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"/>'),
    redo: icon('<path d="m15 14 5-5-5-5"/><path d="M20 9H9.5a5.5 5.5 0 0 0 0 11H13"/>'),
    tidy: icon('<rect x="3.5" y="4" width="7" height="6" rx="1.5"/><rect x="13.5" y="4" width="7" height="6" rx="1.5"/><rect x="13.5" y="14" width="7" height="6" rx="1.5"/><path d="M10.5 7h3M17 10v4"/>'),
    fit: icon('<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>'),
    plus: icon('<path d="M12 5v14M5 12h14"/>'),
    minus: icon('<path d="M5 12h14"/>'),
    unlink: icon('<path d="M7 7l10 10M17 7 7 17"/>'),
    back: icon('<path d="M15 5l-7 7 7 7"/>'),
    board: icon('<rect x="3.5" y="4.5" width="7.5" height="5.5" rx="1.5"/><rect x="13" y="14" width="7.5" height="5.5" rx="1.5"/><path d="M11 7.2h2.2a2 2 0 0 1 2 2V14"/>'),
    list: icon('<path d="M9 6.5h11M9 12h11M9 17.5h11"/><path d="M4.5 6.5h.01M4.5 12h.01M4.5 17.5h.01"/>'),
  };
  const MARKERS = `<defs>${['', '-on', '-trail'].map(v => `<marker id="flow-arrow${v}" class="flow-arrow${v ? ` flow-arrow--${v.slice(1)}` : ''}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="9" markerHeight="9" markerUnits="userSpaceOnUse" orient="auto"><path d="M1,1.5 L9,5 L1,8.5 z"/></marker>`).join('')}</defs>`;

  /** Board or List, as this browser last showed the flows: a convenience, so it may be forgotten. */
  const VIEW_KEY = 'chatbot.view';
  function storedView() {
    try { return localStorage.getItem(VIEW_KEY) === 'list' ? 'list' : 'board'; } catch { return 'board'; }
  }

  // The unsaved flows (null: nothing changed yet) and what the page shows: the board or the list, the flow shown, the
  // step open in the side panel (null: the flow's own settings), the line picked, each flow's pan and zoom, the
  // add-a-step menu, the test chat and the way it went. None of it is saved.
  let draft = null;
  let guard = false;
  let board = null;
  const history = { past: [], future: [] };
  const ui = {
    view: storedView(), flowId: null, sel: null, edge: null, side: 'edit', sideOpen: false, views: {}, menu: null, typing: null, nudge: null, trail: null,
    saving: false, pending: {}, note: null, chat: [], chatState: null, busy: false, channel: 'WHATSAPP', at: '', known: false,
  };

  const utf8Bytes = text => new TextEncoder().encode(text || '').length;
  const words = text => (text || '').toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  const newId = prefix => `${prefix}-${Math.random().toString(36).slice(2, 8)}`;
  const clone = value => JSON.parse(JSON.stringify(value));
  const firstLine = (text, max = 40) => {
    const line = (text || '').split('\n').map(t => t.trim()).find(Boolean) || '';
    return line.length > max ? `${line.slice(0, max - 1).trimEnd()}…` : line;
  };
  const narrow = () => window.matchMedia('(max-width: 920px)').matches;

  const saved = () => d.state.chatbot;
  const ready = () => saved() && !saved().failed;
  const limitsOf = () => saved()?.limits || {};
  const canEdit = () => !!saved()?.canEdit;
  const flows = () => draft || saved()?.flows || [];
  const isDirty = () => !!draft && JSON.stringify(payload(draft)) !== JSON.stringify(payload(saved()?.flows || []));
  const channelName = channel => PS.channel?.[channel] || channel;
  const flowName = (f, i) => (f?.name || '').trim() || tr('defaultName', { n: i + 1 });
  const flowIndex = () => Math.max(0, flows().findIndex(f => f.id === ui.flowId));
  const current = () => flows()[flowIndex()];

  /** The page's data, with the flow fields the server left out at their defaults. */
  function adopt(res) {
    res.flows = (res.flows || []).map(f => ({ ...FLOW_DEFAULTS, ...f, steps: (f.steps || []).map(s => ({ ...s, options: s.options || [], hours: s.hours || [] })) }));
    return res;
  }

  async function load() {
    d.state.chatbot = adopt(await d.api('/app/api/chatbot/flows'));
  }

  function failed(err) {
    d.state.chatbot = { failed: true };
    if (err?.message !== 'unauthorized') d.toast(T.loadFailed);
  }

  /** The draft to change, started as a copy of the saved flows. */
  function editable() {
    if (!draft) draft = clone(saved()?.flows || []);
    return draft;
  }

  /** The flows as the server keeps them: trimmed, with keywords and option words free of blanks and repeats. */
  function payload(list) {
    const clean = (items = []) => {
      const seen = new Set();
      return items.map(k => (k || '').trim()).filter(k => { const w = words(k); if (!w || seen.has(w)) return false; seen.add(w); return true; });
    };
    const pick = (from, fields) => Object.fromEntries(fields.map(k => [k, from[k]]));
    const placed = (out, x, y, kx, ky) => {
      if (Number.isFinite(x) && Number.isFinite(y)) { out[kx] = Math.round(x); out[ky] = Math.round(y); }
      return out;
    };
    return {
      flows: list.map(f => placed({
        ...pick(f, FLOW_FIELDS),
        name: (f.name || '').trim(),
        keywords: clean(f.keywords),
        handoverText: (f.handoverText || '').trim(),
        steps: (f.steps || []).map(s => placed({
          ...pick(s, STEP_FIELDS),
          text: (s.text || '').trim(),
          key: s.kind === 'MESSAGE' && !(s.options || []).length ? '' : (s.key || '').trim(),
          options: (s.options || []).map(o => ({ title: (o.title || '').trim(), next: o.next || '', words: clean(o.words) })),
        }, s.x, s.y, 'x', 'y')),
      }, f.startX, f.startY, 'startX', 'startY')),
    };
  }

  /** Drops the draft once it matches the saved flows again, so an edit undone by hand isn't left "unsaved". */
  function settle() {
    if (draft && !isDirty()) draft = null;
  }

  // ── Undo and redo ──────────────────────────────────────────────────────────────────────────────

  const snapshot = () => JSON.stringify({ draft, flowId: ui.flowId, sel: ui.sel });

  /** Remembers the flows as they are, before an edit, for undo. */
  function checkpoint(nudging = null) {
    if (!canEdit()) return;
    ui.nudge = nudging;
    history.past.push(snapshot());
    if (history.past.length > 100) history.past.shift();
    history.future = [];
  }

  function restore(snap) {
    const s = JSON.parse(snap);
    draft = s.draft;
    ui.flowId = s.flowId;
    ui.sel = s.sel;
    ui.edge = null;
    ui.typing = null;
    ui.nudge = null;
    closeMenu();
    settle();
    d.render();
  }

  function undo() {
    if (!history.past.length) return;
    history.future.push(snapshot());
    restore(history.past.pop());
  }

  function redo() {
    if (!history.future.length) return;
    history.past.push(snapshot());
    restore(history.future.pop());
  }

  // ── What needs fixing ──────────────────────────────────────────────────────────────────────────

  /** What keeps a step from being saved or from working, as the server would see it (it checks again on save). */
  function stepIssues(f, s) {
    const ids = new Set((f.steps || []).map(x => x.id));
    const blank = text => !(text || '').trim();
    const out = [];
    if (utf8Bytes(s.text) > (limitsOf().textBytes || 1000)) out.push('size');
    if (s.kind === 'MESSAGE') {
      if (blank(s.text)) out.push('text');
      if ((s.options || []).some(o => blank(o.title))) out.push('title');
      if ((s.options || []).some(o => !ids.has(o.next))) out.push('option');
      if ((s.options || []).length && !blank(s.key) && !VAR.test(s.key.trim())) out.push('key');
    } else if (s.kind === 'ASK') {
      if (blank(s.text)) out.push('text');
      if (!VAR.test((s.key || '').trim())) out.push('key');
    } else if (s.kind === 'CONDITION') {
      if (s.check === 'HOURS' && (!(s.hours || []).length || s.hours.some(h => !(h.from && h.to && h.from < h.to)))) out.push('hours');
      if (s.check === 'ANSWER' && (!VAR.test((s.answerKey || '').trim()) || blank(s.answerIs))) out.push('answer');
      if (!ids.has(s.whenTrue) || !ids.has(s.whenFalse)) out.push('branch');
    } else if (s.kind === 'ACTION') {
      if (s.action === 'CREATE_BOOKING' && !s.serviceId && blank(s.key)) out.push('service');
      if ((s.action === 'CREATE_CLIENT' && !saved().clientsModule) || (s.action === 'CREATE_BOOKING' && !saved().bookingsModule)) out.push(`module:${s.action}`);
    }
    return out;
  }

  function flowIssues(f) {
    const out = [];
    if (!(f.name || '').trim()) out.push('name');
    if (!(f.keywords || []).some(k => words(k))) out.push('keywords');
    if (!(f.channels || []).length) out.push('channels');
    return out;
  }

  const flowHasIssues = f => flowIssues(f).length > 0 || (f.steps || []).some(s => stepIssues(f, s).length > 0);

  function issueText(code) {
    if (code.startsWith('module:')) return tr('actionNoModule', { module: code.endsWith('CREATE_CLIENT') ? labels.clients : labels.bookings });
    return tr(`board.issue.${code}`, { max: count(limitsOf().textBytes || 1000) });
  }

  // ── The page ───────────────────────────────────────────────────────────────────────────────────

  function render(root) {
    if (!saved()) { root.innerHTML = `<section class="panel"><div class="panel__body"><p class="hint">${esc(T.loading)}</p></div></section>`; return; }
    if (saved().failed) {
      root.innerHTML = `<section class="panel"><div class="panel__body"><div class="empty"><p class="empty__title">${esc(T.loadFailed)}</p>
        <div class="empty__actions"><button type="button" class="btn btn--sm" data-chatbot-retry>${esc(PS.retry)}</button></div></div></div></section>`;
      $('[data-chatbot-retry]', root).addEventListener('click', async () => {
        d.state.chatbot = null;
        d.render();
        await load().catch(failed);
        d.render();
      });
      return;
    }
    seed();
    board = null;
    const keep = sel => { const el = $(sel, root); return el ? { key: el.dataset.sideScroll || el.dataset.listScroll, top: el.scrollTop } : null; };
    const scroll = keep('[data-side-scroll]');
    const listScroll = keep('[data-list-scroll]');
    const list = flows();
    const edit = canEdit();
    const i = flowIndex();
    const f = list[i];
    const listing = ui.view === 'list' && !!f;
    const on = list.filter(x => x.enabled).length;
    const used = saved().usage?.flows || [];
    const stats = statCards([
      { label: T.statOn, value: tr('statOnValue', { n: count(on), total: count(list.length) }) },
      { label: tr('statSent', { days: saved().usage?.days || 30 }), value: count(used.reduce((n, u) => n + u.sent, 0)) },
      { label: T.statCustomers, value: count(used.reduce((n, u) => n + u.customers, 0)) },
    ]);
    root.innerHTML = `<div class="chatbot-page" data-chatbot>${hero(labels.chatbot, T.desc, stats)}
      <div class="chatbot-studio${ui.sideOpen ? ' is-side-open' : ''}">
        <section class="panel chatbot-board${listing ? ' is-list' : ''}" aria-label="${esc(listing ? B.listAria : B.boardAria)}">${barHtml(list, i, edit)}${!f ? emptyState(edit) : listing ? listHtml(f, i, edit) : canvasHtml(f, i, edit)}</section>
        <aside class="chatbot-side" aria-label="${esc(listing ? T.testTitle : B.sideAria)}">${sideHtml(f, i, edit)}</aside>
      </div>
      ${edit ? savebarHtml() : ''}</div>`;
    wire(root);
    mountBoard(root);
    const side = $('[data-side-scroll]', root);
    if (side && scroll && side.dataset.sideScroll === scroll.key) side.scrollTop = scroll.top;
    const steps = $('[data-list-scroll]', root);
    if (steps && listScroll && steps.dataset.listScroll === listScroll.key) steps.scrollTop = listScroll.top;
  }

  /** Keeps what the page shows pointing at flows and steps that exist. */
  function seed() {
    const list = flows();
    if (!list.some(f => f.id === ui.flowId)) { ui.flowId = list[0]?.id || null; ui.sel = null; ui.edge = null; }
    const f = list.find(x => x.id === ui.flowId);
    if (ui.sel && !(f?.steps || []).some(s => s.id === ui.sel)) ui.sel = null;
    if (ui.edge && !(f && edgesOf(f).some(e => e.id === ui.edge))) ui.edge = null;
  }

  const EMPTY_ART = `<svg class="empty__art" viewBox="0 0 128 84" aria-hidden="true" focusable="false"><rect x="10" y="10" width="68" height="22" rx="8" fill="none" stroke="currentColor" stroke-width="2"/><rect x="50" y="42" width="68" height="22" rx="8" fill="none" stroke="currentColor" stroke-width="2"/><path d="M44 32v10h6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>`;

  function emptyState(edit) {
    return `<div class="chatbot-board__empty"><div class="empty empty--art">${EMPTY_ART}<p class="empty__title">${esc(T.emptyTitle)}</p>
      <p class="empty__desc">${esc(T.emptyDesc)}</p>
      ${edit ? `<div class="empty__actions"><button type="button" class="btn btn--primary" data-flow-add><span class="btn__plus">+</span> ${esc(T.add)}</button></div>` : ''}</div></div>`;
  }

  const usageOf = id => (saved().usage?.flows || []).find(u => u.id === id);
  const isSaved = id => (saved().flows || []).some(f => f.id === id);

  function flowSummary(f) {
    const keywords = (f.keywords || []).filter(k => words(k));
    const shown = keywords.slice(0, 3).join(' · ');
    const parts = [
      keywords.length ? (keywords.length > 3 ? tr('keywordsMore', { list: shown, n: count(keywords.length - 3) }) : shown) : T.noKeywords,
      tr('stepsCount', { n: (f.steps || []).length }),
      (f.channels || []).map(channelName).join(', ') || T.noChannels,
    ];
    const used = usageOf(f.id);
    const days = saved().usage?.days || 30;
    if (used?.sent) parts.push(tr('sentSummary', { n: used.sent, customers: used.customers, days }));
    else if (isSaved(f.id)) parts.push(tr('sentNone', { days }));
    return esc(parts.join(' · ')) + (used?.failed ? ` · <span class="hint--warn">${esc(tr('refused', { n: used.failed }))}</span>` : '');
  }

  /** The flows as tabs over the board, the order they are checked in. */
  function barHtml(list, i, edit) {
    const room = list.length < (limitsOf().flows || 10);
    const tabs = list.map((f, n) => `<button type="button" class="flow-tab${n === i ? ' is-on' : ''}" role="tab" aria-selected="${n === i}" tabindex="${n === i ? 0 : -1}" id="cb-tab-${n}" data-flow-pick="${n}">
        <span class="flow__index">${n + 1}</span><span class="flow-tab__name" data-flow-title="${n}">${esc(flowName(f, n))}</span>
        <span class="flow-tab__state${f.enabled ? ' is-on' : ''}" data-flow-pills="${n}" title="${esc(f.enabled ? T.on : T.off)}"><span class="visually-hidden">${esc(f.enabled ? T.on : T.off)}</span></span>
        <span class="flow-tab__warn" data-flow-warn="${n}"${flowHasIssues(f) ? '' : ' hidden'} title="${esc(B.needsWork)}"><span class="visually-hidden">${esc(B.needsWork)}</span></span>
      </button>`).join('');
    const add = !edit ? '' : room
      ? `<button type="button" class="btn btn--sm btn--ghost" data-flow-add aria-label="${esc(T.add)}" title="${esc(T.add)}"><span class="btn__plus">+</span><span class="chatbot-board__add">${esc(T.add)}</span></button>`
      : `<span class="hint">${esc(tr('flowsFull', { max: count(limitsOf().flows || 10) }))}</span>`;
    // The list has no toolbar of its own, so its undo and redo sit here.
    const tools = edit && ui.view === 'list' ? `<span class="chatbot-board__tools">
        <button type="button" class="iconbtn" data-board-undo aria-label="${esc(B.undo)}" title="${esc(B.undoTitle)}"${history.past.length ? '' : ' disabled'}>${ICON.undo}</button>
        <button type="button" class="iconbtn" data-board-redo aria-label="${esc(B.redo)}" title="${esc(B.redoTitle)}"${history.future.length ? '' : ' disabled'}>${ICON.redo}</button>
      </span>` : '';
    const views = `<div class="chatbot-view" role="group" aria-label="${esc(B.viewAria)}">${['board', 'list'].map(v => `<button type="button" class="chatbot-view__btn${ui.view === v ? ' is-on' : ''}" data-view="${v}" aria-pressed="${ui.view === v}" title="${esc(tr(`board.viewHint.${v}`))}">${ICON[v]}<span class="chatbot-view__label">${esc(tr(`board.view.${v}`))}</span></button>`).join('')}</div>`;
    return `<div class="chatbot-board__bar">
        ${list.length ? `<div class="flow-tabs" role="tablist" aria-label="${esc(B.flowsAria)}">${tabs}</div>${add}${tools}${views}` : ''}
        <button type="button" class="btn btn--sm chatbot-board__try" data-side-go="try">${esc(B.tryIt)}</button>
      </div>`;
  }

  // ── The list: the same flow as forms, one under the other ─────────────────────────────────────

  /** The flow's settings, then every step's fields in their order, then buttons that add a step of each kind. */
  function listHtml(f, i, edit) {
    const steps = f.steps || [];
    const max = limitsOf().steps || 60;
    const room = steps.length < max;
    const add = edit ? `<div class="chatbot-list__add">
        <span class="lbl">${esc(T.addStep)}</span>
        <div class="chatbot-list__kinds">${KINDS.map(k => `<button type="button" class="btn btn--sm" data-list-add="${k}"${room ? '' : ' disabled'}><span class="flow-node__icon flow-node__icon--${k.toLowerCase()}" aria-hidden="true">${ICON[k]}</span>${esc(tr(`board.kind.${k}`))}</button>`).join('')}</div>
        ${room ? '' : `<p class="hint">${esc(tr('stepsFull', { max: count(max) }))}</p>`}
      </div>` : '';
    return `<div class="chatbot-list" data-list-scroll="${esc(f.id)}" role="region" aria-label="${esc(tr('board.aria', { name: flowName(f, i) }))}">
      <form class="form chatbot-list__form" id="chatbot-form" novalidate>
        ${flowPanel(f, i, edit, true)}
        ${steps.map((s, j) => stepPanel(f, i, edit, j, true)).join('')}
        ${add}
      </form>
    </div>`;
  }

  const listCard = id => $(`[data-list-step="${CSS.escape(id)}"]`);

  /** The list's cards follow the test chat as the board's do: the steps gone through, and the one the customer is at. */
  function paintListTrail(f) {
    const t = trailOf(f);
    $$('[data-list-step]').forEach(card => {
      const id = card.dataset.listStep;
      card.classList.toggle('is-current', t?.current === id);
      card.classList.toggle('is-visited', !!t && t.current !== id && t.seen.has(id));
      $('[data-list-here]', card)?.toggleAttribute('hidden', t?.current !== id);
    });
  }

  function setView(view) {
    if (view === ui.view) return;
    ui.view = view;
    try { localStorage.setItem(VIEW_KEY, view); } catch { /* not remembered, then */ }
    ui.sideOpen = false;
    ui.edge = null;
    closeMenu();
    d.render();
    $(`[data-view="${view}"]`)?.focus({ preventScroll: true });
    // The step open on the board is where the list starts, and the other way round.
    if (view === 'list' && ui.sel) listCard(ui.sel)?.scrollIntoView({ block: 'start' });
  }

  // ── The board ──────────────────────────────────────────────────────────────────────────────────

  function canvasHtml(f, i, edit) {
    const v = ui.views[f.id];
    return `<div class="flow-canvas${edit ? ' is-editable' : ''}" data-board tabindex="0" role="group" aria-roledescription="${esc(B.roleDesc)}" aria-label="${esc(tr('board.aria', { name: flowName(f, i) }))}" aria-describedby="cb-board-help">
      <div class="flow-canvas__world" data-world${v ? ` style="transform: translate(${v.x}px, ${v.y}px) scale(${v.k})"` : ''}>
        <svg class="flow-canvas__edges" data-edges aria-hidden="true" focusable="false">${MARKERS}<g data-edge-layer></g><path class="flow-edge__temp" data-temp-edge></path></svg>
        <div class="${startClass(f)}" data-node="${START}" id="cb-node-${i}-start">${startInner(f, i)}</div>
        ${(f.steps || []).map((s, j) => `<div class="${nodeClass(f, s)}" data-node="${esc(s.id)}" id="cb-node-${i}-${j}">${nodeInner(f, i, s, j)}</div>`).join('')}
      </div>
      ${toolsHtml(edit)}
      <button type="button" class="iconbtn flow-canvas__unlink" data-board-ui data-edge-remove hidden aria-label="${esc(B.unlink)}" title="${esc(B.unlink)}">${ICON.unlink}</button>
      <div class="board-menu" data-board-ui data-board-menu role="menu" aria-label="${esc(T.addStep)}" hidden></div>
      <p class="flow-canvas__help" id="cb-board-help">${esc(edit ? B.help : B.helpRead)}</p>
    </div>`;
  }

  function toolsHtml(edit) {
    const zoom = `<div class="flow-canvas__tools flow-canvas__tools--zoom" data-board-ui>
        <button type="button" class="iconbtn" data-board-zoom="out" aria-label="${esc(B.zoomOut)}" title="${esc(B.zoomOut)}">${ICON.minus}</button>
        <button type="button" class="flow-canvas__zoom" data-board-zoom="reset" aria-label="${esc(B.zoomReset)}" title="${esc(B.zoomReset)}" data-board-zoom-label>100%</button>
        <button type="button" class="iconbtn" data-board-zoom="in" aria-label="${esc(B.zoomIn)}" title="${esc(B.zoomIn)}">${ICON.plus}</button>
        <button type="button" class="iconbtn" data-board-fit aria-label="${esc(B.fit)}" title="${esc(B.fit)}">${ICON.fit}</button>
      </div>`;
    if (!edit) return zoom;
    return `<div class="flow-canvas__tools flow-canvas__tools--edit" data-board-ui>
        <button type="button" class="btn btn--sm btn--primary" data-board-add aria-haspopup="menu" aria-expanded="false"><span class="btn__plus">+</span> ${esc(T.addStep)}</button>
        <span class="flow-canvas__sep" aria-hidden="true"></span>
        <button type="button" class="iconbtn" data-board-undo aria-label="${esc(B.undo)}" title="${esc(B.undoTitle)}"${history.past.length ? '' : ' disabled'}>${ICON.undo}</button>
        <button type="button" class="iconbtn" data-board-redo aria-label="${esc(B.redo)}" title="${esc(B.redoTitle)}"${history.future.length ? '' : ' disabled'}>${ICON.redo}</button>
        <button type="button" class="btn btn--sm btn--ghost" data-board-tidy title="${esc(B.tidyTitle)}">${ICON.tidy}<span>${esc(B.tidy)}</span></button>
      </div>${zoom}`;
  }

  /** Where a step leads: its options, or what comes next, with the step each one goes to (empty: nowhere yet). */
  function outsOf(s) {
    switch (s.kind) {
      case 'MESSAGE': return (s.options || []).length
        ? s.options.map((o, k) => ({ key: `o:${k}`, label: (o.title || '').trim(), blank: tr('board.option', { n: k + 1 }), target: o.next, required: true }))
        : [{ key: 'next', label: B.then, target: s.next }];
      case 'ASK': return [{ key: 'next', label: B.then, target: s.next }];
      case 'CONDITION': return [
        { key: 'yes', label: B.yes, target: s.whenTrue, required: true },
        { key: 'no', label: B.no, target: s.whenFalse, required: true, alt: true },
      ];
      case 'ACTION': return s.action === 'NOTIFY_TEAM'
        ? [{ key: 'next', label: B.then, target: s.next }]
        : [{ key: 'next', label: B.worked, target: s.next }, { key: 'fail', label: B.failed, target: s.failNext, alt: true, empty: B.toTeam }];
      default: return [];
    }
  }

  /** Every line on the board: the start card to the first step, then each step's outputs to where they lead. */
  function edgesOf(f) {
    const steps = f.steps || [];
    const ids = new Set(steps.map(s => s.id));
    const list = steps.length ? [{ id: `${START}|start`, from: START, port: 'start', to: steps[0].id }] : [];
    for (const s of steps) {
      for (const o of outsOf(s)) if (o.target && ids.has(o.target)) list.push({ id: `${s.id}|${o.key}`, from: s.id, port: o.key, to: o.target, alt: !!o.alt });
    }
    return list;
  }

  /** The steps a customer can reach from the first one. */
  function reachOf(f) {
    const byId = new Map((f.steps || []).map(s => [s.id, s]));
    const seen = new Set();
    const queue = f.steps?.length ? [f.steps[0].id] : [];
    while (queue.length) {
      const id = queue.shift();
      if (seen.has(id) || !byId.has(id)) continue;
      seen.add(id);
      outsOf(byId.get(id)).forEach(o => o.target && queue.push(o.target));
    }
    return seen;
  }

  const trailOf = f => (ui.trail && f && ui.trail.flowId === f.id ? ui.trail : null);
  function trailClass(f, id) {
    const t = trailOf(f);
    return !t ? '' : t.current === id ? ' is-current' : t.seen.has(id) ? ' is-visited' : '';
  }

  /** The card whose settings the side panel shows (the start card for the flow's own), while it shows them. */
  const isOpen = id => ui.side === 'edit' && (ui.sideOpen || !narrow()) && (ui.sel || START) === id;
  const startClass = f => `flow-node flow-node--start${isOpen(START) ? ' is-selected' : ''}${flowIssues(f).length ? ' has-issues' : ''}${trailOf(f)?.links.has(`${START}|start`) ? ' is-visited' : ''}`;

  function startInner(f, i) {
    const keywords = (f.keywords || []).filter(k => words(k));
    const issues = flowIssues(f);
    const chips = keywords.length
      ? `${keywords.slice(0, 6).map(k => `<span class="flow-node__chip">${esc(k)}</span>`).join('')}${keywords.length > 6 ? `<span class="flow-node__more">+${count(keywords.length - 6)}</span>` : ''}`
      : `<span class="flow-node__chip is-empty">${esc(T.noKeywords)}</span>`;
    const label = tr('board.startAria', { name: flowName(f, i), keywords: keywords.join(', ') || T.noKeywords });
    return `<button type="button" class="flow-node__head" data-node-pick="${START}" aria-pressed="${isOpen(START)}" aria-label="${esc(label)}">
        <span class="flow-node__icon" aria-hidden="true">${ICON.START}</span><span class="flow-node__kind">${esc(B.start)}</span>
        <span class="pill${f.enabled ? ' pill--ok' : ''}">${esc(f.enabled ? T.on : T.off)}</span>
      </button>
      <div class="flow-node__body">
        <p class="flow-node__meta">${esc(B.whenSays)}</p>
        <p class="flow-node__chips">${chips}</p>
        <p class="flow-node__meta">${esc((f.channels || []).map(channelName).join(' · ') || T.noChannels)}</p>
      </div>
      ${(f.steps || []).length ? `<ul class="flow-node__outs"><li class="flow-node__out is-linked"><span class="flow-node__label">${esc(B.firstStep)}</span><span class="flow-port is-linked" data-port="start" title="${esc(B.startPortTitle)}"></span></li></ul>` : ''}
      ${issues.length ? `<div class="flow-node__foot"><span class="flow-node__flag" title="${esc(issues.map(issueText).join(' '))}">${esc(tr('board.toFix', { n: issues.length }))}</span></div>` : ''}`;
  }

  function nodeClass(f, s) {
    return `flow-node flow-node--${s.kind.toLowerCase()}${isOpen(s.id) ? ' is-selected' : ''}${stepIssues(f, s).length ? ' has-issues' : ''}${reachOf(f).has(s.id) ? '' : ' is-unreached'}${trailClass(f, s.id)}`;
  }

  function nodeInner(f, i, s, j) {
    const issues = stepIssues(f, s);
    const used = usageOf(f.id)?.steps?.[s.id];
    const label = tr('board.nodeAria', { n: j + 1, kind: tr(`kind.${s.kind}`), text: firstLine(s.text, 60) });
    const foot = [
      trailOf(f)?.current === s.id ? `<span class="flow-node__here">${esc(B.here)}</span>` : '',
      issues.length ? `<span class="flow-node__flag" title="${esc(issues.map(issueText).join(' '))}">${esc(tr('board.toFix', { n: issues.length }))}</span>` : '',
      reachOf(f).has(s.id) ? '' : `<span class="flow-node__note">${esc(B.unreached)}</span>`,
      used ? `<span class="flow-node__stat">${esc(tr('stepSent', { n: used }))}</span>` : '',
    ].filter(Boolean);
    return `<span class="flow-node__in" data-in aria-hidden="true"></span>
      <button type="button" class="flow-node__head" data-node-pick="${esc(s.id)}" aria-pressed="${isOpen(s.id)}" aria-label="${esc(label)}">
        <span class="flow-node__icon" aria-hidden="true">${ICON[s.kind]}</span><span class="flow-node__kind">${esc(tr(`board.kind.${s.kind}`))}</span><span class="flow-node__num">${j + 1}</span>
      </button>
      <div class="flow-node__body">${nodeBody(s)}</div>
      ${outsHtml(f, s)}
      ${foot.length ? `<div class="flow-node__foot">${foot.join('')}</div>` : ''}`;
  }

  const keyChip = key => `<code class="flow-node__key">{${esc(key)}}</code>`;

  function nodeBody(s) {
    const text = (s.text || '').trim();
    const said = text ? `<p class="flow-node__text">${esc(text)}</p>` : '';
    const empty = `<p class="flow-node__text is-empty">${esc(B.noText)}</p>`;
    switch (s.kind) {
      case 'ASK':
        return `${said || empty}<p class="flow-node__meta">${(s.key || '').trim() ? `${esc(B.keeps)} ${keyChip(s.key.trim())}` : `<span class="flow-node__missing">${esc(B.noKey)}</span>`} · ${esc(tr(`expect.${s.expect}`))}</p>`;
      case 'CONDITION': {
        const detail = s.check === 'HOURS' ? `<p class="flow-node__meta">${esc(hoursSummary(s.hours) || T.noHours)}</p>`
          : s.check === 'ANSWER' ? `<p class="flow-node__meta">${keyChip((s.answerKey || '').trim() || '…')} = ${esc(s.answerIs || '…')}</p>` : '';
        return `<p class="flow-node__lead">${esc(tr(`check.${s.check}`))}</p>${detail}`;
      }
      case 'ACTION': {
        const service = (saved().services || []).find(x => x.id === s.serviceId);
        const booking = s.action !== 'CREATE_BOOKING' ? ''
          : `<p class="flow-node__meta">${service ? esc(service.name) : (s.key || '').trim() ? `${esc(B.serviceFrom)} ${keyChip(s.key.trim())}` : `<span class="flow-node__missing">${esc(B.noService)}</span>`}</p>`;
        return `<p class="flow-node__lead">${esc(tr(`action.${s.action}`))}</p>${booking}${said}`;
      }
      case 'HANDOFF':
        return `${said}<p class="flow-node__meta">${esc(B.teamTakesOver)}</p>`;
      default:
        return `${said || empty}${(s.options || []).length && (s.key || '').trim() ? `<p class="flow-node__meta">${esc(B.keepsChoice)} ${keyChip(s.key.trim())}</p>` : ''}`;
    }
  }

  function outsHtml(f, s) {
    const outs = outsOf(s);
    if (!outs.length) return '';
    const ids = new Set((f.steps || []).map(x => x.id));
    return `<ul class="flow-node__outs">${outs.map(o => {
      const linked = !!o.target && ids.has(o.target);
      const state = linked ? ' is-linked' : o.required ? ' is-missing' : '';
      const end = linked ? '' : `<span class="flow-node__end">${esc(o.required ? B.needsStep : o.empty || B.ends)}</span>`;
      return `<li class="flow-node__out${o.alt ? ' flow-node__out--alt' : ''}${state}"><span class="flow-node__label${o.label ? '' : ' is-blank'}">${esc(o.label || o.blank)}</span>${end}<span class="flow-port${state}" data-port="${o.key}" title="${esc(B.portTitle)}"></span></li>`;
    }).join('')}</ul>`;
  }

  /** "Mon–Fri 09:00–18:00 · Sat 10:00–13:00", with the days as the page's language shortens them. */
  function hoursSummary(hours = []) {
    let short;
    try { short = new Intl.DateTimeFormat(I18N.locale(), { weekday: 'short', timeZone: 'UTC' }); } catch { short = new Intl.DateTimeFormat('en', { weekday: 'short', timeZone: 'UTC' }); }
    // 1 January 2024 was a Monday, so day n of the week is the nth of that month.
    const day = n => short.format(new Date(Date.UTC(2024, 0, n)));
    const spans = new Map();
    for (const h of hours) {
      const span = `${h.from}–${h.to}`;
      if (!spans.has(span)) spans.set(span, new Set());
      spans.get(span).add(h.day);
    }
    return [...spans].map(([span, days]) => {
      const runs = [];
      for (const n of [...days].sort((a, b) => a - b)) {
        const last = runs[runs.length - 1];
        if (last && n === last[1] + 1) last[1] = n; else runs.push([n, n]);
      }
      return `${runs.map(([a, b]) => (a === b ? day(a) : b === a + 1 ? `${day(a)}, ${day(b)}` : `${day(a)}–${day(b)}`)).join(', ')} ${span}`;
    }).join(' · ');
  }

  function edgeClass(f, e) {
    return [e.alt ? 'flow-edge--alt' : '', ui.edge === e.id ? 'is-selected' : '', trailOf(f)?.links.has(e.id) ? 'is-trail' : ''].filter(Boolean).join(' ');
  }

  const arranged = f => Number.isFinite(f.startX) || (f.steps || []).some(s => Number.isFinite(s.x));

  /** Where each card goes: where someone put it, or, for a flow nobody arranged, the board's own layout. */
  function placesOf(f) {
    if (!arranged(f)) return autoPlaces(f);
    const pos = new Map();
    if (Number.isFinite(f.startX)) pos.set(START, { x: f.startX, y: f.startY });
    for (const s of f.steps) if (Number.isFinite(s.x)) pos.set(s.id, { x: s.x, y: s.y });
    // Cards without a place (a step added while the board was hidden) go in a row under the others, the start to the left.
    const boxes = [...pos].map(([id, p]) => ({ ...p, ...(board.box(id) || CARD) }));
    const left = Math.min(...boxes.map(b => b.x));
    const bottom = Math.max(...boxes.map(b => b.y + b.h));
    if (!pos.has(START)) {
      const first = pos.get(f.steps[0]?.id);
      pos.set(START, { x: FlowBoard.snap((first?.x ?? left) - (board.box(START)?.w || CARD.w) - GAP), y: first?.y ?? 0 });
    }
    let x = left;
    for (const s of f.steps) {
      if (pos.has(s.id)) continue;
      pos.set(s.id, { x: FlowBoard.snap(x), y: FlowBoard.snap(bottom + GAP / 2) });
      x += (board.box(s.id)?.w || CARD.w) + 32;
    }
    return pos;
  }

  function autoPlaces(f) {
    const sizes = board.sizes();
    const portDy = (id, port) => sizes.find(n => n.id === id)?.ports.get(port)?.dy || 0;
    return FlowBoard.layout({
      nodes: sizes.map(n => ({ id: n.id, w: n.w, h: n.h, inDy: n.inDy })),
      edges: edgesOf(f).map(e => ({ from: e.from, to: e.to, dy: portDy(e.from, e.port) })),
      roots: [START],
      gapX: GAP,
    });
  }

  /** Writes where every card is on the board into the draft: the flow is arranged by hand from now on. */
  function materialize(f) {
    if (!board) return false;
    const start = board.position(START);
    if (start) { f.startX = start.x; f.startY = start.y; }
    for (const s of f.steps) {
      const p = board.position(s.id);
      if (p) { s.x = p.x; s.y = p.y; }
    }
    return true;
  }

  function mountBoard(root) {
    board = null;
    const canvas = $('[data-board]', root);
    const f = current();
    if (!canvas || !f || !canvas.clientWidth || !window.FlowBoard) return;
    board = FlowBoard.create(canvas, {
      editable: canEdit(),
      view: ui.views[f.id],
      canTarget: id => id !== START,
      onView: v => { ui.views[f.id] = v; paintZoom(); paintUnlink(); closeMenu(); },
      onSelect: pick,
      onMove: moved,
      onConnect: link,
      onDropEmpty: (from, port, at, client) => openMenu({ from, port, at, client }),
      onPortClick: (from, port, client) => openMenu({ from, port, client }),
      onBackground: (at, client) => canEdit() && openMenu({ at, client }),
    });
    board.scan();
    placesOf(f).forEach((p, id) => board.place(id, p.x, p.y));
    board.setEdges(edgesOf(f).map(e => ({ ...e, cls: edgeClass(f, e) })));
    if (!ui.views[f.id]) board.home(START);
    paintZoom();
    paintUnlink();
  }

  /** Redraws cards [ids] from the draft where they are, and the lines that touch them. */
  function refreshNodes(ids) {
    const f = current();
    if (!board || !f) return;
    const i = flowIndex();
    const changed = [];
    for (const id of ids) {
      const el = $(`[data-node="${CSS.escape(id)}"]`);
      if (!el) continue;
      if (id === START) {
        el.className = startClass(f);
        el.innerHTML = startInner(f, i);
      } else {
        const j = f.steps.findIndex(s => s.id === id);
        if (j < 0) continue;
        el.className = nodeClass(f, f.steps[j]);
        el.innerHTML = nodeInner(f, i, f.steps[j], j);
      }
      changed.push(id);
    }
    if (!changed.length) return;
    const before = changed.map(id => board.box(id)?.h);
    board.measure(changed);
    // A flow the board lays out itself makes room when a card grows or shrinks.
    if (!arranged(f) && changed.some((id, n) => board.box(id)?.h !== before[n])) {
      autoPlaces(f).forEach((p, id) => board.place(id, p.x, p.y));
      board.route();
    } else {
      board.route(new Set(changed));
    }
    paintEdges();
    paintUnlink();
  }

  function paintEdges() {
    const f = current();
    if (!f) return;
    const byId = new Map(edgesOf(f).map(e => [e.id, e]));
    $$('[data-edge]').forEach(g => {
      const e = byId.get(g.dataset.edge);
      if (e) g.setAttribute('class', `flow-edge ${edgeClass(f, e)}`.trim());
    });
  }

  /** The × that unlinks the line picked, in the middle of it. */
  function paintUnlink() {
    const btn = $('[data-edge-remove]');
    if (!btn) return;
    const mid = ui.edge && !ui.edge.startsWith(`${START}|`) && canEdit() && board?.midpoint(ui.edge);
    btn.hidden = !mid;
    if (!mid) return;
    const p = board.toCanvas(mid.x, mid.y);
    btn.style.left = `${p.x}px`;
    btn.style.top = `${p.y}px`;
  }

  function paintZoom() {
    const label = $('[data-board-zoom-label]');
    if (label && board) label.textContent = new Intl.NumberFormat(I18N.locale(), { style: 'percent' }).format(board.view().k);
  }

  function paintTools() {
    const undoBtn = $('[data-board-undo]');
    const redoBtn = $('[data-board-redo]');
    if (undoBtn) undoBtn.disabled = !history.past.length;
    if (redoBtn) redoBtn.disabled = !history.future.length;
  }

  // ── Board gestures ─────────────────────────────────────────────────────────────────────────────

  /** A card, a line or the empty board was clicked: open the card in the side panel, or pick the line. */
  function pick(target) {
    closeMenu();
    if (target?.edge) {
      ui.edge = target.edge === ui.edge ? null : target.edge;
      paintEdges();
      paintUnlink();
      return;
    }
    const was = ui.edge;
    ui.edge = null;
    const id = target?.node || null;
    const next = id === START ? null : id;
    const phone = narrow() && !!target;
    if (next === ui.sel && ui.side === 'edit' && !phone) {
      if (was) { paintEdges(); paintUnlink(); }
      return;
    }
    ui.sel = next;
    ui.side = 'edit';
    if (phone) ui.sideOpen = true;
    d.render();
    if (phone) $('[data-side-close]')?.focus();
    else if (id) $(`[data-node-pick="${CSS.escape(id)}"]`)?.focus({ preventScroll: true });
  }

  /** A card was dragged to (x, y): from now on the flow keeps where its cards are. */
  function moved() {
    const f0 = current();
    if (!f0) return;
    checkpoint();
    materialize(editable()[flowIndex()]);
    settle();
    paintSavebar();
    paintTools();
    paintUnlink();
  }

  /** Output [port] of [from] now leads to step [to]. From the start card, [to] becomes the first step. */
  function link(from, port, to) {
    if (!current()) return;
    checkpoint();
    const f = editable()[flowIndex()];
    if (from === START) {
      const j = f.steps.findIndex(s => s.id === to);
      if (j > 0) f.steps.unshift(...f.steps.splice(j, 1));
    } else {
      setTarget(f.steps.find(s => s.id === from), port, to);
    }
    ui.edge = null;
    settle();
    d.render();
  }

  function unlink(edgeId) {
    if (!edgeId || edgeId.startsWith(`${START}|`) || !current()) return;
    const [from, port] = edgeId.split('|');
    checkpoint();
    setTarget(editable()[flowIndex()].steps.find(s => s.id === from), port, '');
    ui.edge = null;
    settle();
    d.render();
    $('[data-board]')?.focus({ preventScroll: true });
  }

  function setTarget(s, port, to) {
    if (!s) return;
    if (port.startsWith('o:')) {
      const o = s.options?.[Number(port.slice(2))];
      if (o) o.next = to;
    } else if (port === 'next') s.next = to;
    else if (port === 'yes') s.whenTrue = to;
    else if (port === 'no') s.whenFalse = to;
    else if (port === 'fail') s.failNext = to;
  }

  /** The add-a-step menu: at a point of the board, at the toolbar, or for an output (the new step is linked to it). */
  function openMenu({ from = null, port = null, at = null, client }) {
    const canvas = $('[data-board]');
    const menu = $('[data-board-menu]');
    const f = current();
    if (!canvas || !menu || !f || !canEdit()) return;
    ui.menu = { from, port, at };
    const max = limitsOf().steps || 60;
    const linked = from && from !== START && edgesOf(f).some(e => e.id === `${from}|${port}`);
    const items = f.steps.length >= max
      ? `<p class="hint">${esc(tr('stepsFull', { max: count(max) }))}</p>`
      : KINDS.map(k => `<button type="button" class="board-menu__item" role="menuitem" data-menu-kind="${k}">
          <span class="flow-node__icon flow-node__icon--${k.toLowerCase()}" aria-hidden="true">${ICON[k]}</span>
          <span class="board-menu__text"><span class="board-menu__name">${esc(tr(`kind.${k}`))}</span><span class="board-menu__desc">${esc(tr(`board.kindHint.${k}`))}</span></span>
        </button>`).join('');
    menu.innerHTML = `<p class="board-menu__title">${esc(from ? B.menuLink : T.addStep)}</p>${items}
      ${linked ? `<button type="button" class="board-menu__item board-menu__item--quiet" role="menuitem" data-menu-unlink><span class="board-menu__name">${esc(B.unlink)}</span></button>` : ''}`;
    menu.hidden = false;
    const r = canvas.getBoundingClientRect();
    menu.style.left = `${clamp(client.x - r.left + 8, 8, Math.max(8, r.width - menu.offsetWidth - 8))}px`;
    menu.style.top = `${clamp(client.y - r.top + 8, 8, Math.max(8, r.height - menu.offsetHeight - 8))}px`;
    $('[data-board-add]')?.setAttribute('aria-expanded', String(!from && !at));
    $('[role="menuitem"]', menu)?.focus({ preventScroll: true });
  }

  function closeMenu() {
    if (!ui.menu) return;
    ui.menu = null;
    const menu = $('[data-board-menu]');
    if (menu) { menu.hidden = true; menu.innerHTML = ''; }
    $('[data-board-add]')?.setAttribute('aria-expanded', 'false');
  }

  /** A new step of [kind]: where it was asked for, or beside the card it is linked from, or in view; then opened. */
  function addStep(kind, { at = null, from = null, port = null } = {}) {
    const i = flowIndex();
    const f0 = current();
    if (!f0 || f0.steps.length >= (limitsOf().steps || 60)) return;
    checkpoint();
    const f = editable()[i];
    const step = defaultStep(kind);
    if (materialize(f)) {
      const spot = at ? { x: at.x - 24, y: at.y - 24 } : from ? besideOf(from, port) : freeSpot();
      const free = clearOf(spot);
      step.x = FlowBoard.snap(free.x);
      step.y = FlowBoard.snap(free.y);
    }
    f.steps.push(step);
    if (from === START) f.steps.unshift(f.steps.pop());
    else if (from) setTarget(f.steps.find(s => s.id === from), port, step.id);
    ui.sel = step.id;
    ui.side = 'edit';
    ui.edge = null;
    closeMenu();
    settle();
    const j = f.steps.indexOf(step);
    redraw(kind === 'CONDITION' ? `cb-${i}-${j}-check` : kind === 'ACTION' ? `cb-${i}-${j}-action` : `cb-${i}-${j}-text`);
    board?.reveal(step.id);
  }

  function besideOf(from, port) {
    const b = board?.box(from);
    if (!b) return freeSpot();
    const dy = board.sizes().find(n => n.id === from)?.ports.get(port)?.dy ?? 24;
    return { x: b.x + b.w + GAP, y: b.y + dy - 24 };
  }

  /** Beside the card open in the side panel, or the middle of what the board shows. */
  function freeSpot() {
    const sel = ui.sel && board?.box(ui.sel);
    if (sel) return { x: sel.x + sel.w + GAP, y: sel.y };
    const r = $('[data-board]').getBoundingClientRect();
    const c = board.toWorld(r.left + r.width / 2, r.top + r.height / 2);
    return { x: c.x - CARD.w / 2, y: c.y - CARD.h / 2 };
  }

  /** [spot], moved down until a new card there covers no other. */
  function clearOf(spot) {
    const boxes = board.sizes().map(n => board.box(n.id));
    let { x, y } = spot;
    for (let n = 0; n < 60; n++) {
      const hit = boxes.find(b => x < b.x + b.w + 16 && x + CARD.w + 16 > b.x && y < b.y + b.h + 16 && y + CARD.h + 16 > b.y);
      if (!hit) break;
      y = hit.y + hit.h + 32;
    }
    return { x, y };
  }

  /** Back to the board's own layout: the places people gave the cards are dropped. */
  function tidy() {
    const f0 = current();
    if (!f0) return;
    closeMenu();
    if (arranged(f0)) {
      checkpoint();
      const f = editable()[flowIndex()];
      delete f.startX;
      delete f.startY;
      f.steps.forEach(s => { delete s.x; delete s.y; });
      settle();
    }
    delete ui.views[f0.id];
    d.render();
  }

  function nudge(id, dx, dy, far) {
    if (!board?.position(id)) return;
    if (ui.nudge !== id) checkpoint(id);
    const f = editable()[flowIndex()];
    const p = board.position(id);
    const step = far ? 40 : FlowBoard.GRID;
    board.moveNode(id, p.x + dx * step, p.y + dy * step);
    materialize(f);
    settle();
    paintSavebar();
    paintTools();
    paintUnlink();
    board.reveal(id);
  }

  function boardKeys(e) {
    const t = e.target;
    if (e.key === 'Escape') {
      if (ui.menu) { e.preventDefault(); closeMenu(); $('[data-board]')?.focus({ preventScroll: true }); }
      else if (ui.edge) { ui.edge = null; paintEdges(); paintUnlink(); }
      return;
    }
    const menu = t.closest('[data-board-menu]');
    if (menu) {
      if (!['ArrowDown', 'ArrowUp'].includes(e.key)) return;
      e.preventDefault();
      const items = $$('[role="menuitem"]', menu);
      const at = items.indexOf(t);
      items[(at + (e.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length]?.focus();
      return;
    }
    if (t.closest('[data-board-ui]') || e.ctrlKey || e.metaKey || e.altKey) return;
    const edit = canEdit();
    const nodeId = t.closest('[data-node]')?.dataset.node || null;
    if (e.key === 'Delete' || e.key === 'Backspace') {
      if (!edit) return;
      if (ui.edge) { e.preventDefault(); unlink(ui.edge); return; }
      if (nodeId && nodeId !== START) { e.preventDefault(); removeStepById(nodeId); }
      return;
    }
    const arrows = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    if (arrows[e.key]) {
      e.preventDefault();
      const [dx, dy] = arrows[e.key];
      if (nodeId && edit) return nudge(nodeId, dx, dy, e.shiftKey);
      const v = board?.view();
      if (v) board.setView({ ...v, x: v.x - dx * 48, y: v.y - dy * 48 });
      return;
    }
    if (e.key === '+' || e.key === '=') { e.preventDefault(); board?.zoomBy(1.2); }
    else if (e.key === '-') { e.preventDefault(); board?.zoomBy(1 / 1.2); }
    else if (e.key === '0') { e.preventDefault(); board?.zoomTo(1); }
  }

  // ── The side panel ─────────────────────────────────────────────────────────────────────────────

  function sideHtml(f, i, edit) {
    // The list edits every step itself, so beside it the side panel only tries the flows.
    if (ui.view === 'list' && f) {
      const back = narrow() ? `<div class="chatbot-side__top"><button type="button" class="btn btn--sm btn--ghost chatbot-side__back" data-side-close>${ICON.back}<span>${esc(tr('board.view.list'))}</span></button></div>` : '';
      return back + testPanel();
    }
    const tab = (key, label) => `<button type="button" class="chip${ui.side === key ? ' is-on' : ''}" role="tab" aria-selected="${ui.side === key}" data-side-go="${key}">${esc(label)}</button>`;
    const body = ui.side === 'try' ? testPanel()
      : !f ? introPanel()
      : `<form class="form chatbot-side__form" id="chatbot-form" novalidate>${ui.sel ? stepPanel(f, i, edit) : flowPanel(f, i, edit)}</form>`;
    return `<div class="chatbot-side__top">
        <button type="button" class="btn btn--sm btn--ghost chatbot-side__back" data-side-close>${ICON.back}<span>${esc(B.backToBoard)}</span></button>
        <div class="settings-tabs chatbot-side__tabs" role="tablist" aria-label="${esc(B.sideAria)}">${tab('edit', B.edit)}${tab('try', B.tryIt)}</div>
      </div>${body}`;
  }

  function introPanel() {
    const off = (saved().teamOnlyChannels || []).map(channelName);
    return `<section class="panel chatbot-side__panel"><header class="panel__head"><h2 class="panel__title">${esc(T.introTitle)}</h2></header>
      <div class="panel__body form chatbot-side__scroll" data-side-scroll="intro"><p class="hint">${esc(T.intro)}</p><p class="hint">${esc(T.orderHint)}</p>
        ${off.length ? `<p class="hint"><strong>${esc(tr('aiOff', { channels: off.join(', ') }))}</strong></p>` : ''}</div>
    </section>`;
  }

  const off = edit => (edit ? '' : ' disabled');

  /** A control bound to a field: its path (`data-c` kind and `data-b` positions) says what an edit changes. */
  const bind = (c, at, field, rr = false) => `data-c="${c}" data-b="${at}" data-f="${field}"${rr ? ' data-rr' : ''}`;

  const notice = issues => (issues.length ? `<div class="notice notice--warn"><div class="notice__text"><strong>${esc(B.toFixTitle)}</strong>
      <ul class="notice__list">${issues.map(code => `<li>${esc(issueText(code))}</li>`).join('')}</ul></div></div>` : '');

  /** A panel of the side panel, whose body scrolls, or a card of the list, which scrolls as a whole. */
  const panelClass = inList => (inList ? 'panel chatbot-list__card chatbot-inspector' : 'panel chatbot-side__panel chatbot-inspector');
  const bodyAttrs = (inList, key) => (inList ? 'class="panel__body chatbot-list__body"' : `class="panel__body chatbot-side__scroll" data-side-scroll="${esc(key)}"`);

  /** The flow's own settings, which the start card opens (or the list starts with): name, on or off, channels, keywords, and replies not understood. */
  function flowPanel(f, i, edit, inList = false) {
    const id = `cb-${i}`;
    const limits = limitsOf();
    const list = flows();
    const connected = saved().connected || [];
    const room = list.length < (limits.flows || 10);
    const offChannels = (saved().teamOnlyChannels || []).map(channelName);
    const order = edit && list.length > 1 ? `<div class="panel__tools">
        <button class="iconbtn" type="button" data-flow-up="${i}"${i ? '' : ' disabled'} aria-label="${esc(B.earlier)}" title="${esc(B.earlier)}">↑</button>
        <button class="iconbtn" type="button" data-flow-down="${i}"${i < list.length - 1 ? '' : ' disabled'} aria-label="${esc(B.later)}" title="${esc(B.later)}">↓</button>
      </div>` : '';
    const channelsRow = CHANNELS.map(ch => `<label class="form__check"><input type="checkbox" ${bind('c', i, ch)} data-channel="${ch}"${(f.channels || []).includes(ch) ? ' checked' : ''}${off(edit)} /> ${esc(channelName(ch))}${connected.includes(ch) ? '' : ` <span class="hint">· ${esc(T.notConnected)}</span>`}</label>`).join('');
    const foot = edit ? `<div class="flow-menu__foot"><span class="actions">${room ? `<button type="button" class="btn btn--sm btn--ghost" data-flow-duplicate="${i}">${esc(T.duplicate)}</button>` : ''}
        <button type="button" class="btn btn--sm btn--ghost" data-flow-remove="${i}">${esc(T.remove)}</button></span></div>` : '';
    return `<section class="${panelClass(inList)}" id="${id}" data-side-flow="${i}">
      <header class="panel__head">
        <h2 class="panel__title chatbot-inspector__title"><span class="flow-node__icon flow-node__icon--start" aria-hidden="true">${ICON.START}</span>${esc(B.flowSettings)}</h2>
        ${order}
      </header>
      <div ${bodyAttrs(inList, `flow:${f.id}`)}>
        <p class="flow-menu__summary" data-flow-summary="${i}">${flowSummary(f)}</p>
        ${list.length > 1 ? `<p class="hint">${esc(tr('board.order', { n: i + 1, total: list.length }))} ${esc(T.orderHint)}</p>` : ''}
        ${offChannels.length ? `<p class="hint"><strong>${esc(tr('aiOff', { channels: offChannels.join(', ') }))}</strong></p>` : ''}
        <div class="chatbot-side__issues" data-flow-issues>${notice(flowIssues(f))}</div>
        <div class="form__row"><label class="lbl" for="${id}-name">${esc(T.name)}</label>
          <input class="inp" id="${id}-name" ${bind('f', i, 'name')} maxlength="${limits.nameChars || 60}" autocomplete="off" value="${esc(f.name || '')}"${off(edit)} />
          <p class="hint">${esc(T.nameHint)}</p></div>
        <div class="form__row"><span class="lbl">${esc(T.state)}</span>
          <label class="form__check"><input type="checkbox" id="${id}-enabled" ${bind('f', i, 'enabled')}${f.enabled ? ' checked' : ''}${off(edit)} /> ${esc(T.enabled)}</label></div>
        <div class="form__row"><span class="lbl">${esc(T.channels)}</span>
          <div class="chip-picks" role="group" aria-label="${esc(T.channels)}">${channelsRow}</div>
          <p class="hint">${esc(T.channelsHint)}</p></div>
        <div class="form__row">
          <div class="form__row-head">${edit ? `<label class="lbl" for="${id}-keywords">${esc(T.keywords)}</label>` : `<span class="lbl">${esc(T.keywords)}</span>`}<span class="hint" data-kw-count="${i}"></span></div>
          <div class="tokens${edit ? '' : ' is-readonly'}" data-tokens="${i}">${tokenChips(f, i, edit)}${edit ? `<input class="tokens__input" id="${id}-keywords" data-kw-input="${i}" maxlength="${limits.keywordChars || 60}" autocomplete="off" enterkeyhint="enter" placeholder="${esc((f.keywords || []).length ? T.keywordsPhMore : T.keywordsPh)}" value="${esc(ui.pending[f.id] || '')}" />` : ''}</div>
          <p class="hint">${esc(edit ? T.keywordsHelp : T.keywordsHelpRead)}</p>
          <div class="flow-menu__notes" data-kw-notes="${i}" aria-live="polite">${ui.note?.flow === f.id ? `<p class="hint">${esc(ui.note.text)}</p>` : ''}</div></div>
        <div class="form__row"><span class="lbl">${esc(T.lostTitle)}</span>
          <label class="form__check"><input type="checkbox" id="${id}-ai" ${bind('f', i, 'aiReads')}${f.aiReads ? ' checked' : ''}${off(edit)} /> ${esc(T.aiReads)}</label>
          <p class="hint">${esc(T.aiReadsHelp)}</p></div>
        <div class="form__row"><label class="lbl" for="${id}-retries">${esc(T.retriesLabel)}</label>
          <select class="sel" id="${id}-retries" ${bind('f', i, 'retries')}${off(edit)}>${[0, 1, 2].map(v => `<option value="${v}"${Number(f.retries) === v ? ' selected' : ''}>${esc(tr(`retries.${v}`))}</option>`).join('')}</select>
          <p class="hint">${esc(T.retriesHint)}</p></div>
        <div class="form__row"><label class="lbl" for="${id}-after">${esc(T.afterMissesLabel)}</label>
          <select class="sel" id="${id}-after" ${bind('f', i, 'afterMisses', true)}${off(edit)}>${['HAND_OVER', 'LET_AI'].map(v => `<option value="${v}"${f.afterMisses === v ? ' selected' : ''}>${esc(tr(`afterMisses.${v}`))}</option>`).join('')}</select>
          <p class="hint">${esc(f.afterMisses === 'LET_AI' ? T.afterAiHint : T.afterTeamHint)}</p></div>
        ${f.afterMisses === 'HAND_OVER' ? `<div class="form__row"><label class="lbl" for="${id}-handover">${esc(T.handoverText)}</label>
          <textarea class="txt" id="${id}-handover" ${bind('f', i, 'handoverText')} rows="2" placeholder="${esc(T.handoverPh)}"${off(edit)}>${esc(f.handoverText || '')}</textarea>
          <p class="hint">${esc(T.handoverHint)}</p></div>` : ''}
        ${foot}
      </div>
    </section>`;
  }

  function tokenChips(f, i, edit) {
    return (f.keywords || []).map((w, n) => `<span class="token">${edit
      ? `<button type="button" class="token__text" tabindex="-1" data-kw-edit="${i}:${n}" aria-label="${esc(tr('keywordEdit', { keyword: w }))}">${esc(w)}</button><button type="button" class="token__rm" data-kw-remove="${i}:${n}" aria-label="${esc(tr('keywordRemove', { keyword: w }))}">×</button>`
      : `<span class="token__text">${esc(w)}</span>`}</span>`).join('');
  }

  // ── A step, open in the side panel ─────────────────────────────────────────────────────────────

  const stepLabel = (step, n) => { const line = firstLine(step.text, 34); return line ? `${n} · ${line}` : `${n} · ${tr(`kind.${step.kind}`)}`; };

  /** Where a step can lead: every step of the flow, the end of the flow when [end], and (for editors) a new step, added beside it. */
  function targetSelect(f, at, field, current, edit, { end = true, rr = false, id = '', c = 't' } = {}) {
    const steps = f.steps || [];
    const options = [
      end ? `<option value=""${current ? '' : ' selected'}>${esc(T.endHere)}</option>` : `<option value=""${current ? '' : ' selected'} disabled>${esc(T.pickStep)}</option>`,
      ...steps.map((s, n) => `<option value="${esc(s.id)}"${s.id === current ? ' selected' : ''}>${esc(stepLabel(s, n + 1))}</option>`),
      edit ? `<option value="__new">${esc(T.newStep)}</option>` : '',
    ].join('');
    return `<select class="sel" ${id ? `id="${id}"` : ''} ${bind(c, at, field, rr)}${off(edit)}>${options}</select>`;
  }

  /** Step [j] of the flow (in the side panel, the one open there): its kind, what to fix, its fields, and its tools. */
  function stepPanel(f, i, edit, j = f.steps.findIndex(s => s.id === ui.sel), inList = false) {
    const step = f.steps[j];
    const id = `cb-${i}-${j}`;
    const at = `${i}.${j}`;
    const limits = limitsOf();
    const sent = usageOf(f.id)?.steps?.[step.id];
    const kind = edit
      ? `<select class="sel" id="${id}-kind" ${bind('s', at, 'kind', true)} aria-label="${esc(T.kindLabel)}">${KINDS.map(k => `<option value="${k}"${step.kind === k ? ' selected' : ''}>${esc(tr(`kind.${k}`))}</option>`).join('')}</select>`
      : `<span class="pill">${esc(tr(`kind.${step.kind}`))}</span>`;
    const kindRow = `<div class="chatbot-inspector__kind">
          <span class="flow-node__icon flow-node__icon--${step.kind.toLowerCase()}" aria-hidden="true">${ICON[step.kind]}</span>
          <span class="flow__index" title="${esc(B.stepNumber)}">${j + 1}</span>${kind}
          ${j === 0 ? `<span class="pill">${esc(T.first)}</span>` : ''}
        </div>`;
    const foot = edit ? `<div class="flow-menu__foot"><span class="actions">
        ${j ? `<button type="button" class="btn btn--sm btn--ghost" data-step-first="${i}:${j}">${esc(B.makeFirst)}</button>` : ''}
        ${f.steps.length < (limits.steps || 60) ? `<button type="button" class="btn btn--sm btn--ghost" data-step-duplicate="${i}:${j}">${esc(B.duplicateStep)}</button>` : ''}
        <button type="button" class="btn btn--sm btn--ghost" data-step-remove="${i}:${j}"${f.steps.length > 1 ? '' : ' disabled'}>${esc(T.removeStep)}</button>
      </span></div>` : '';
    const body = `<div class="chatbot-side__issues" data-step-issues="${j}">${notice(stepIssues(f, step))}</div>
        ${stepBody(f, i, step, j, edit, id, at, limits)}
        ${foot}`;
    if (inList) {
      const t = trailOf(f);
      const reached = reachOf(f).has(step.id);
      const state = `${reached ? '' : ' is-unreached'}${t?.current === step.id ? ' is-current' : t?.seen.has(step.id) ? ' is-visited' : ''}`;
      return `<section class="${panelClass(true)}${state}" id="${id}" data-step="${i}:${j}" data-list-step="${esc(step.id)}">
        <header class="panel__head chatbot-list__head">
          ${kindRow}
          <span class="chatbot-list__flags">
            <span class="flow-node__here" data-list-here${t?.current === step.id ? '' : ' hidden'}>${esc(B.here)}</span>
            ${reached ? '' : `<span class="flow-node__note">${esc(B.unreached)}</span>`}
            ${sent ? `<span class="flow-menu__stat">${esc(tr('stepSent', { n: sent }))}</span>` : ''}
          </span>
        </header>
        <div ${bodyAttrs(true)}>${body}</div>
      </section>`;
    }
    return `<section class="${panelClass(false)}" id="${id}" data-step="${i}:${j}">
      <header class="panel__head">
        <button type="button" class="btn btn--sm btn--ghost chatbot-inspector__crumb" data-sel-flow title="${esc(B.flowSettings)}">${ICON.back}<span>${esc(flowName(f, i))}</span></button>
        ${sent ? `<span class="flow-menu__stat">${esc(tr('stepSent', { n: sent }))}</span>` : ''}
      </header>
      <div ${bodyAttrs(false, `step:${step.id}`)}>
        ${kindRow}
        ${body}
      </div>
    </section>`;
  }

  const textField = (id, at, field, value, edit, { rows = 3, ph = '', label = '', max = 1000 } = {}) => {
    const bytes = utf8Bytes(value);
    const near = bytes > max * 0.8;
    return `<div class="form__row form__row--full">${label ? `<label class="lbl" for="${id}">${esc(label)}</label>` : `<label class="visually-hidden" for="${id}">${esc(T.textLabel)}</label>`}
      <textarea class="txt" id="${id}" ${bind('s', at, field)} data-size-for="${field}" rows="${rows}" placeholder="${esc(ph)}"${off(edit)}>${esc(value || '')}</textarea>
      <p class="${bytes > max ? 'hint hint--bad' : 'hint hint--warn'}" data-size="${id}"${near ? '' : ' hidden'}>${esc(near ? tr('textSize', { n: count(bytes), max: count(max) }) : '')}</p>
      ${edit ? `<p class="hint">${esc(B.answersHint)}</p>` : ''}</div>`;
  };

  function stepBody(f, i, step, j, edit, id, at, limits) {
    switch (step.kind) {
      case 'ASK': return askBody(f, i, step, j, edit, id, at, limits);
      case 'CONDITION': return conditionBody(f, i, step, j, edit, id, at);
      case 'ACTION': return actionBody(f, i, step, j, edit, id, at, limits);
      case 'HANDOFF': return textField(`${id}-text`, at, 'text', step.text, edit, { ph: T.handoffPh, label: T.handoffText, max: limits.textBytes || 1000 }) + `<p class="hint">${esc(T.handoffHint)}</p>`;
      default: return messageBody(f, i, step, j, edit, id, at, limits);
    }
  }

  function messageBody(f, i, step, j, edit, id, at, limits) {
    const options = step.options || [];
    const max = limits.options || 13;
    const rows = options.map((o, k) => `<div class="line" data-option="${i}:${j}:${k}">
        <input class="inp" id="${id}-o${k}" ${bind('o', `${at}.${k}`, 'title')} maxlength="${limits.titleChars || 20}" autocomplete="off" aria-label="${esc(T.optionTitle)}" placeholder="${esc(T.optionTitlePh)}" value="${esc(o.title || '')}"${off(edit)} />
        ${targetSelect(f, `${at}.${k}`, 'next', o.next, edit, { end: false, c: 'o', id: `${id}-o${k}-next` })}
        <input class="inp" ${bind('w', `${at}.${k}`, 'words')} autocomplete="off" aria-label="${esc(T.optionWords)}" placeholder="${esc(T.optionWordsPh)}" value="${esc((o.words || []).join(', '))}"${off(edit)} />
        ${edit ? `<button class="iconbtn" type="button" data-option-remove="${i}:${j}:${k}" aria-label="${esc(T.removeOption)}" title="${esc(T.removeOption)}">×</button>` : '<span></span>'}
      </div>`).join('');
    const addOption = edit && options.length < max ? `<button type="button" class="btn btn--sm btn--ghost" data-option-add="${i}:${j}"><span class="btn__plus">+</span> ${esc(T.addOption)}</button>` : '<span></span>';
    const show = options.length ? `<div class="form__row"><label class="lbl" for="${id}-show">${esc(T.showLabel)}</label>
        <select class="sel" id="${id}-show" ${bind('s', at, 'show', true)}${off(edit)}>${SHOWS.map(v => `<option value="${v}"${step.show === v ? ' selected' : ''}>${esc(tr(`show.${v}`))}</option>`).join('')}</select>
        <p class="hint">${esc(tr(`showHint.${step.show}`))}</p></div>
      ${options.length > 3 && (step.show === 'AUTO' || step.show === 'TAPS_AND_NUMBERS') ? `<div class="form__row"><label class="lbl" for="${id}-list">${esc(T.listLabel)}</label>
        <input class="inp" id="${id}-list" ${bind('s', at, 'listLabel')} maxlength="${limits.listLabelChars || 20}" autocomplete="off" placeholder="${esc(T.listLabelPh)}" value="${esc(step.listLabel || '')}"${off(edit)} />
        <p class="hint">${esc(T.listLabelHint)}</p></div>` : ''}
      <div class="form__row"><label class="lbl" for="${id}-key">${esc(T.keepChoice)}</label>
        <input class="inp" id="${id}-key" ${bind('s', at, 'key', true)} list="${id}-key-list" maxlength="30" autocomplete="off" placeholder="business_type" value="${esc(step.key || '')}"${off(edit)} />
        <datalist id="${id}-key-list">${Object.keys(KEYS).map(k => `<option value="${k}"></option>`).join('')}</datalist>
        <p class="hint">${esc(T.keepChoiceHint)}</p></div>
      <div class="form__row"><label class="lbl" for="${id}-retry">${esc(T.retry)}</label>
        <input class="inp" id="${id}-retry" ${bind('s', at, 'retryText')} autocomplete="off" placeholder="${esc(T.retryPh)}" value="${esc(step.retryText || '')}"${off(edit)} /><p class="hint">${esc(T.retryHint)}</p></div>`
      : `<div class="form__row"><label class="lbl" for="${id}-next">${esc(T.thenGo)}</label>${targetSelect(f, at, 'next', step.next, edit, { end: true, id: `${id}-next` })}<p class="hint">${esc(T.noOptionsHint)}</p></div>`;
    return `${textField(`${id}-text`, at, 'text', step.text, edit, { ph: T.textPh, max: limits.textBytes || 1000 })}
      <div class="lines lines--options">
        <div class="lines__head"><span>${esc(T.thOption)}</span><span>${esc(T.thLeadsTo)}</span><span>${esc(T.thWords)}</span><span></span></div>
        ${rows}<p class="lines__empty"${options.length ? ' hidden' : ''}>${esc(T.noOptions)}</p>
        <div class="lines__foot">${addOption}<span class="hint">${esc(tr('optionsCount', { n: count(options.length), max: count(max), chars: count(limits.titleChars || 20) }))}</span></div>
      </div>
      ${show}`;
  }

  function askBody(f, i, step, j, edit, id, at, limits) {
    const keyId = `${id}-key`;
    return `${textField(`${id}-text`, at, 'text', step.text, edit, { ph: T.askPh, max: limits.textBytes || 1000 })}
      <div class="form__row"><label class="lbl" for="${keyId}">${esc(T.saveAs)}</label>
        <input class="inp" id="${keyId}" ${bind('s', at, 'key', true)} list="${keyId}-list" maxlength="30" autocomplete="off" placeholder="name" value="${esc(step.key || '')}"${off(edit)} />
        <datalist id="${keyId}-list">${Object.keys(KEYS).map(k => `<option value="${k}">${esc(tr(`key.${k}`))}</option>`).join('')}</datalist>
        <p class="hint">${esc(T.saveAsHint)}</p></div>
      <div class="form__row"><label class="lbl" for="${id}-expect">${esc(T.expectLabel)}</label>
        <select class="sel" id="${id}-expect" ${bind('s', at, 'expect')}${off(edit)}>${EXPECTS.map(v => `<option value="${v}"${step.expect === v ? ' selected' : ''}>${esc(tr(`expect.${v}`))}</option>`).join('')}</select>
        <p class="hint">${esc(tr(`expectHint.${step.expect}`))}</p></div>
      <div class="form__row"><label class="lbl" for="${id}-retry">${esc(T.retry)}</label>
        <input class="inp" id="${id}-retry" ${bind('s', at, 'retryText')} autocomplete="off" placeholder="${esc(T.retryAskPh)}" value="${esc(step.retryText || '')}"${off(edit)} /><p class="hint">${esc(T.retryHint)}</p></div>
      <div class="form__row"><label class="lbl" for="${id}-next">${esc(T.thenGo)}</label>${targetSelect(f, at, 'next', step.next, edit, { end: true, id: `${id}-next` })}</div>`;
  }

  function conditionBody(f, i, step, j, edit, id, at) {
    const limits = limitsOf();
    let detail = '';
    if (step.check === 'HOURS') {
      const rows = (step.hours || []).map((h, k) => `<div class="line line--hours" data-hours="${i}:${j}:${k}">
          <select class="sel" ${bind('h', `${at}.${k}`, 'day')} aria-label="${esc(T.day)}"${off(edit)}>${DAYS.map(n => `<option value="${n}"${h.day === n ? ' selected' : ''}>${esc(tr(`days.${n}`))}</option>`).join('')}</select>
          <input class="inp" type="time" ${bind('h', `${at}.${k}`, 'from')} aria-label="${esc(T.from)}" value="${esc(h.from || '')}"${off(edit)} />
          <input class="inp" type="time" ${bind('h', `${at}.${k}`, 'to')} aria-label="${esc(T.to)}" value="${esc(h.to || '')}"${off(edit)} />
          ${edit ? `<button class="iconbtn" type="button" data-hours-remove="${i}:${j}:${k}" aria-label="${esc(T.removeHours)}" title="${esc(T.removeHours)}">×</button>` : '<span></span>'}
        </div>`).join('');
      detail = `<div class="form__row"><span class="lbl">${esc(T.openHours)}</span><p class="hint">${esc(T.openHoursHint)}</p>
        <div class="lines lines--hours">${rows}<p class="lines__empty"${(step.hours || []).length ? ' hidden' : ''}>${esc(T.noHours)}</p>
          ${edit ? `<div class="lines__foot"><span class="actions">${(step.hours || []).length < (limits.hours || 21) ? `<button type="button" class="btn btn--sm btn--ghost" data-hours-add="${i}:${j}"><span class="btn__plus">+</span> ${esc(T.addHours)}</button>` : ''}<button type="button" class="btn btn--sm btn--ghost" data-hours-weekdays="${i}:${j}">${esc(T.weekdays)}</button></span></div>` : ''}
        </div></div>`;
    } else if (step.check === 'ANSWER') {
      detail = `<div class="form__row"><label class="lbl" for="${id}-akey">${esc(T.answerKey)}</label>
          <input class="inp" id="${id}-akey" ${bind('s', at, 'answerKey')} list="${id}-akey-list" maxlength="30" autocomplete="off" placeholder="service" value="${esc(step.answerKey || '')}"${off(edit)} />
          <datalist id="${id}-akey-list">${Object.keys(KEYS).map(k => `<option value="${k}"></option>`).join('')}</datalist></div>
        <div class="form__row"><label class="lbl" for="${id}-ais">${esc(T.answerIs)}</label>
          <input class="inp" id="${id}-ais" ${bind('s', at, 'answerIs')} maxlength="${limits.keywordChars || 60}" autocomplete="off" placeholder="yes" value="${esc(step.answerIs || '')}"${off(edit)} />
          <p class="hint">${esc(T.answerIsHint)}</p></div>`;
    } else {
      detail = `<p class="hint">${esc(T.knownHint)}</p>`;
    }
    return `<div class="form__row"><label class="lbl" for="${id}-check">${esc(T.checkLabel)}</label>
        <select class="sel" id="${id}-check" ${bind('s', at, 'check', true)}${off(edit)}>${CHECKS.map(v => `<option value="${v}"${step.check === v ? ' selected' : ''}>${esc(tr(`check.${v}`))}</option>`).join('')}</select></div>
      ${detail}
      <div class="form__row"><label class="lbl" for="${id}-yes">${esc(T.ifYes)}</label>${targetSelect(f, at, 'whenTrue', step.whenTrue, edit, { end: false, id: `${id}-yes` })}</div>
      <div class="form__row"><label class="lbl" for="${id}-no">${esc(T.ifNo)}</label>${targetSelect(f, at, 'whenFalse', step.whenFalse, edit, { end: false, id: `${id}-no` })}</div>`;
  }

  function actionBody(f, i, step, j, edit, id, at, limits) {
    const modules = saved();
    const blocked = (step.action === 'CREATE_CLIENT' && !modules.clientsModule) || (step.action === 'CREATE_BOOKING' && !modules.bookingsModule);
    const services = modules.services || [];
    const booking = step.action === 'CREATE_BOOKING' ? `<div class="form__row"><label class="lbl" for="${id}-service">${esc(T.service)}</label>
        <select class="sel" id="${id}-service" ${bind('s', at, 'serviceId', true)}${off(edit)}><option value=""${step.serviceId ? '' : ' selected'}>${esc(T.serviceFromAnswer)}</option>${services.map(s => `<option value="${esc(s.id)}"${s.id === step.serviceId ? ' selected' : ''}>${esc(s.name)}</option>`).join('')}</select>
        <p class="hint">${esc(T.serviceHint)}</p></div>
      ${step.serviceId ? '' : `<div class="form__row"><label class="lbl" for="${id}-key">${esc(T.serviceKey)}</label>
        <input class="inp" id="${id}-key" ${bind('s', at, 'key')} maxlength="30" autocomplete="off" placeholder="service" value="${esc(step.key || '')}"${off(edit)} /></div>`}` : '';
    return `<div class="form__row"><label class="lbl" for="${id}-action">${esc(T.actionLabel)}</label>
        <select class="sel" id="${id}-action" ${bind('s', at, 'action', true)}${off(edit)}>${ACTIONS.map(v => `<option value="${v}"${step.action === v ? ' selected' : ''}>${esc(tr(`action.${v}`))}</option>`).join('')}</select>
        <p class="hint">${esc(tr(`actionHint.${step.action}`))}</p>
        ${blocked ? `<p class="hint hint--warn">${esc(tr('actionNoModule', { module: step.action === 'CREATE_CLIENT' ? labels.clients : labels.bookings }))}</p>` : ''}</div>
      ${booking}
      ${textField(`${id}-text`, at, 'text', step.text, edit, { rows: 2, ph: T.actionTextPh, label: T.actionText, max: limits.textBytes || 1000 })}
      <div class="form__row"><label class="lbl" for="${id}-next">${esc(step.action === 'NOTIFY_TEAM' ? T.thenGo : B.workedGo)}</label>${targetSelect(f, at, 'next', step.next, edit, { end: true, id: `${id}-next` })}</div>
      ${step.action === 'NOTIFY_TEAM' ? '' : `<div class="form__row"><label class="lbl" for="${id}-fail">${esc(T.ifFails)}</label>${targetSelect(f, at, 'failNext', step.failNext, edit, { end: true, id: `${id}-fail` })}<p class="hint">${esc(T.ifFailsHint)}</p></div>`}`;
  }

  // ── Test chat ──────────────────────────────────────────────────────────────────────────────────

  function testPanel() {
    return `<section class="panel chatbot-side__panel chatbot-try">
      <header class="panel__head"><h2 class="panel__title">${esc(T.testTitle)}</h2>
        <div class="panel__tools"><span class="pill pill--warn" id="chatbot-test-draft"${isDirty() ? '' : ' hidden'}>${esc(T.testDraft)}</span><button class="btn btn--sm btn--ghost" type="button" id="chatbot-chat-clear">${esc(T.testRestart)}</button></div></header>
      <div class="panel__body form chatbot-try__body" data-side-scroll="try">
        <p class="hint">${esc(T.testHint)} ${esc(B.tryFollows)}</p>
        <div class="form__grid">
          <div class="form__row"><label class="lbl" for="chatbot-channel">${esc(T.testChannel)}</label>
            <select class="sel" id="chatbot-channel">${CHANNELS.map(ch => `<option value="${ch}"${ui.channel === ch ? ' selected' : ''}>${esc(channelName(ch))}</option>`).join('')}</select></div>
          <div class="form__row"><label class="lbl" for="chatbot-at">${esc(T.testAt)}</label>
            <input class="inp" id="chatbot-at" type="datetime-local" value="${esc(ui.at)}" /></div>
        </div>
        <label class="form__check"><input type="checkbox" id="chatbot-known"${ui.known ? ' checked' : ''} /> ${esc(T.testKnown)}</label>
        <div class="chat__log" id="chatbot-chat-log" role="log" aria-live="polite"></div>
        <form class="chat__form" id="chatbot-chat-form">
          <input class="inp chat__input" id="chatbot-chat-input" placeholder="${esc(STR.chatPlaceholder)}" aria-label="${esc(STR.chatPlaceholder)}" autocomplete="off" />
          <button class="btn btn--primary" type="submit" id="chatbot-chat-send"${ui.busy ? ' disabled' : ''}>${esc(STR.send)}</button>
        </form>
      </div>
    </section>`;
  }

  function renderChatLog() {
    const log = $('#chatbot-chat-log');
    if (!log) return;
    if (!ui.chat.length && !ui.busy) { log.innerHTML = `<div class="chat__empty">${esc(T.testEmpty)}</div>`; return; }
    log.innerHTML = ui.chat.map((m, index) => {
      if (m.role === 'note') return `<div class="chat__note">${esc(m.content)}</div>`;
      if (m.role === 'user') return `<div class="chat__msg chat__msg--user">${renderChatText(m.content)}</div>`;
      const last = index === ui.chat.length - 1;
      const replies = last && !ui.busy && m.replies?.length
        ? `<div class="chip-picks" role="group" aria-label="${esc(T.testRepliesAria)}">${m.replies.map((q, k) => `<button type="button" class="chip" data-reply="${index}:${k}">${esc(q.title)}</button>`).join('')}</div>` : '';
      const label = m.listLabel && last ? `<div class="chat__note">${esc(tr('testList', { label: m.listLabel }))}</div>` : '';
      return `<div class="chat__msg chat__msg--bot">${renderChatText(m.content)}</div>${replies}${label}`;
    }).join('') + (ui.busy ? `<div class="chat__msg chat__msg--bot chat__typing">${esc(STR.typing)}</div>` : '');
    log.scrollTop = log.scrollHeight;
  }

  function effectNote(e) {
    const action = tr(`effect.${e.action}`);
    return e.ok ? tr('effectOk', { action, detail: e.detail }) : tr('effectFailed', { action, detail: e.detail });
  }

  /** Sends a test turn: [text] typed or the title of a tapped option, whose payload [tapped] says where it leads. */
  async function sendTest(text, tapped) {
    const content = (text || '').trim();
    if (ui.busy || !content) return;
    ui.chat.push({ role: 'user', content });
    ui.busy = true;
    $('#chatbot-chat-send')?.setAttribute('disabled', '');
    renderChatLog();
    const before = ui.chatState;
    try {
      const body = { text: content, channel: ui.channel, knownClient: ui.known, ...(tapped && { payload: tapped }), ...(ui.chatState && { state: ui.chatState }), ...(ui.at && { at: ui.at }) };
      if (isDirty()) body.flows = payload(draft).flows;
      const res = await d.api('/app/api/chatbot/test', { method: 'POST', body: JSON.stringify(body) });
      ui.chatState = res.state || null;
      if (!res.handled) {
        ui.chat.push({ role: 'note', content: res.aiAnswers ? T.testNoFlowAi : T.testNoFlowTeam });
      } else {
        res.messages.forEach(m => ui.chat.push({ role: 'assistant', content: m.text, replies: m.replies || [], listLabel: m.listLabel }));
        (res.effects || []).forEach(e => ui.chat.push({ role: 'note', content: effectNote(e) }));
        if (res.handedOver) ui.chat.push({ role: 'note', content: tr('testHandedOver', { reason: res.reason || '' }) });
        else if (!res.state) ui.chat.push({ role: 'note', content: T.testEnded });
      }
      follow(res, before);
    } catch (err) {
      if (err?.message !== 'unauthorized') ui.chat.push({ role: 'note', content: err?.code === 'flow_problem' ? errorText(err.body) : T.testFailed });
    } finally {
      ui.busy = false;
      renderChatLog();
      $('#chatbot-chat-send')?.removeAttribute('disabled');
      $('#chatbot-chat-input')?.focus();
    }
  }

  /** Lights up on the board the way a test turn went, and the step where the customer now waits; shows its flow. */
  function follow(res, before) {
    const f = res.handled && res.flowId ? flows().find(x => x.id === res.flowId) : null;
    if (!f) {
      if (ui.trail) { ui.trail.current = null; paintTrail(); }
      return;
    }
    const t = ui.trail?.flowId === f.id ? ui.trail : { flowId: f.id, seen: new Set(), links: new Set(), current: null };
    const edges = edgesOf(f);
    const path = res.path || [];
    let prev = before?.flowId === f.id ? before.stepId : null;
    // A keyword starts over at the first step, from wherever the customer was.
    if (path[0] && path[0] === f.steps[0]?.id && !edges.some(e => e.from === prev && e.to === path[0])) {
      t.links.add(`${START}|start`);
      prev = null;
    }
    for (const id of path) {
      if (prev) edges.filter(e => e.from === prev && e.to === id).forEach(e => t.links.add(e.id));
      t.seen.add(id);
      prev = id;
    }
    t.current = res.state?.stepId || null;
    ui.trail = t;
    if (f.id !== ui.flowId) {
      ui.flowId = f.id;
      ui.sel = null;
      ui.edge = null;
      d.render();
    } else {
      paintTrail();
    }
    if (t.current) board ? board.reveal(t.current) : listCard(t.current)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  function paintTrail() {
    const f = current();
    if (!f) return;
    if (board) refreshNodes([START, ...f.steps.map(s => s.id)]);
    else paintListTrail(f);
  }

  function restartChat() {
    ui.chat = [];
    ui.chatState = null;
    ui.trail = null;
    renderChatLog();
    paintTrail();
  }

  // ── Saving ─────────────────────────────────────────────────────────────────────────────────────

  function savebarHtml() {
    return `<div class="savebar" data-chatbot-savebar>
      <p class="savebar__status" data-chatbot-status aria-live="polite"></p>
      <div class="actions"><button class="btn btn--ghost" type="button" data-chatbot-discard disabled>${esc(T.discard)}</button><button class="btn btn--primary" type="button" data-chatbot-save disabled>${esc(T.save)}</button></div>
    </div>`;
  }

  function paintSavebar() {
    const bar = $('[data-chatbot-savebar]');
    $('#chatbot-test-draft')?.toggleAttribute('hidden', !isDirty());
    if (!bar) return;
    const dirty = isDirty();
    bar.classList.toggle('is-dirty', dirty);
    const status = dirty ? `<span class="savebar__dot" aria-hidden="true"></span>${esc(T.unsaved)}` : esc(T.allSaved);
    const el = $('[data-chatbot-status]', bar);
    if (el.innerHTML !== status) el.innerHTML = status;
    const saveBtn = $('[data-chatbot-save]', bar);
    saveBtn.disabled = !dirty || ui.saving;
    saveBtn.textContent = ui.saving ? T.saving : T.save;
    $('[data-chatbot-discard]', bar).disabled = !dirty || ui.saving;
  }

  /** "Flow, step 2: …" for a problem the server found (`flow_problem`). */
  function errorText(problem = {}) {
    const flow = flows()[problem.flow];
    const key = `app.chatbot.errors.${problem.problem}`;
    const text = I18N.t(key, {
      flow: flow ? flowName(flow, problem.flow) : '',
      step: problem.step != null ? problem.step + 1 : '',
      limit: problem.limit != null ? count(problem.limit) : '',
    });
    return text === key ? T.errorGeneric : text;
  }

  /** The field a problem the server found is about, by the end of its id. */
  const PROBLEM_FIELD = { name: 'name', keywords: 'keywords', keywords_limit: 'keywords', keyword: 'keywords', retries: 'retries', text: 'text', key: 'key', hours: 'check', answer: 'check', branch: 'yes', service: 'action' };

  /** A save the server refused: says what is wrong and where, shows that flow, opens the step and puts the cursor there. */
  function refuse(problem) {
    d.toast(errorText(problem));
    const i = problem?.flow;
    const f = flows()[i];
    if (!f) return d.render();
    const s = problem.step != null ? f.steps[problem.step] : null;
    ui.flowId = f.id;
    ui.sel = s ? s.id : null;
    ui.side = 'edit';
    // On a phone the side panel opens over the board; the list has the step itself.
    ui.sideOpen = ui.view !== 'list';
    ui.edge = null;
    d.render();
    const suffix = PROBLEM_FIELD[problem.problem];
    const base = s ? `cb-${i}-${problem.step}` : `cb-${i}`;
    const panel = document.getElementById(base);
    const field = (suffix && document.getElementById(`${base}-${suffix}`)) || (panel && $('input:not([type=checkbox]), textarea, select', panel));
    panel?.classList.add('is-flash');
    if (s) {
      board?.reveal(s.id);
      $(`[data-node="${CSS.escape(s.id)}"]`)?.classList.add('is-flash');
    }
    if (field && !field.disabled) {
      field.focus({ preventScroll: true });
      field.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }

  async function save() {
    if (ui.saving || !isDirty()) return;
    commitPending();
    const sent = JSON.stringify(payload(draft));
    ui.saving = true;
    paintSavebar();
    try {
      d.state.chatbot = adopt(await d.api('/app/api/chatbot/flows', { method: 'PUT', body: sent }));
    } catch (err) {
      ui.saving = false;
      if (err?.message === 'unauthorized') return;
      if (err?.code === 'flow_problem') refuse(err.body);
      else d.toast(T.saveFailed);
      return paintSavebar();
    }
    ui.saving = false;
    // Typing goes on while the request is out; only what was sent is saved, the rest stays unsaved.
    if (JSON.stringify(payload(draft)) === sent) draft = null;
    d.toast(T.saved);
    d.renderKeepingFocus();
  }

  async function discard() {
    if (!(await d.confirmDialog({ title: T.discardTitle, body: T.discardBody, okLabel: T.discard, danger: false }))) return;
    draft = null;
    history.past = [];
    history.future = [];
    ui.pending = {};
    ui.note = null;
    ui.typing = null;
    d.render();
  }

  /** A keyword still being typed counts as added when the page saves. */
  function commitPending() {
    const el = document.activeElement;
    if (el?.dataset?.kwInput != null && canEdit()) commitKeyword(el);
  }

  // ── Editing ────────────────────────────────────────────────────────────────────────────────────

  function redraw(focusId) {
    d.render();
    const el = focusId && document.getElementById(focusId);
    if (el && !el.disabled) el.focus({ preventScroll: true });
    if (el) el.scrollIntoView({ block: 'nearest' });
  }

  const defaultStep = kind => ({
    id: newId('s'), kind, text: '', options: [], show: 'AUTO', listLabel: '', retryText: '', next: '', key: '', expect: 'TEXT', check: 'HOURS', hours: [],
    answerKey: '', answerIs: '', whenTrue: '', whenFalse: '', action: 'NOTIFY_TEAM', serviceId: '', failNext: '',
  });

  const newFlow = () => ({
    id: newId('flow'), name: '', enabled: false, channels: ['WHATSAPP'], keywords: [], aiReads: false, retries: 1, afterMisses: 'HAND_OVER', handoverText: '',
    steps: [{ ...defaultStep('MESSAGE'), text: '' }],
  });

  /** The object a control edits, from its kind (`data-c`) and positions (`data-b`, dotted). */
  function target(c, b) {
    const [i, j, k] = String(b).split('.').map(Number);
    const f = draft?.[i];
    if (c === 'f' || c === 'c') return f;
    const s = f?.steps?.[j];
    if (c === 's' || c === 't') return s;
    if (c === 'o' || c === 'w') return s?.options?.[k];
    if (c === 'h') return s?.hours?.[k];
    return null;
  }

  /** Cleans a step when its kind changes so nothing from the old kind lingers in what is saved. */
  function resetKind(step) {
    const fresh = defaultStep(step.kind);
    for (const key of Object.keys(fresh)) if (!['id', 'kind', 'text'].includes(key)) step[key] = fresh[key];
    if (step.kind === 'CONDITION') step.text = '';
  }

  /** A text box whose every keystroke is an edit, as opposed to a choice, a checkbox, a time or a box that redraws on change. */
  const typedField = el => el.hasAttribute('data-c') && ['INPUT', 'TEXTAREA'].includes(el.tagName) && !['checkbox', 'time'].includes(el.type) && !el.hasAttribute('data-rr');

  function onChange(el) {
    const { c, b, f: field } = el.dataset;
    if (!c) return;
    // One undo step per field typed in, and one per choice.
    if (!typedField(el)) checkpoint();
    else if (ui.typing !== el.id || !el.id) { checkpoint(); ui.typing = el.id; }
    editable();
    const obj = target(c, b);
    if (!obj) return;
    if (c === 'c') {
      const set = new Set(obj.channels || []);
      el.checked ? set.add(el.dataset.channel) : set.delete(el.dataset.channel);
      obj.channels = CHANNELS.filter(ch => set.has(ch));
    } else if (c === 'w') {
      obj.words = el.value.split(/[,;\n]/).map(w => w.trim()).filter(Boolean);
    } else if ((c === 't' || c === 'o') && el.value === '__new') {
      const [i, j] = b.split('.').map(Number);
      const f = draft[i];
      const from = f.steps[j];
      const step = { ...defaultStep('MESSAGE') };
      const spot = board?.box(from.id);
      if (spot && materialize(f)) {
        const free = clearOf({ x: spot.x + spot.w + GAP, y: spot.y });
        step.x = FlowBoard.snap(free.x);
        step.y = FlowBoard.snap(free.y);
      }
      f.steps.push(step);
      obj[field] = step.id;
      ui.sel = step.id;
      settle();
      return redraw(`cb-${i}-${f.steps.length - 1}-text`);
    } else if (el.type === 'checkbox') {
      obj[field] = el.checked;
    } else if (field === 'day' || field === 'retries') {
      obj[field] = Number(el.value);
    } else {
      obj[field] = el.value;
    }
    if (field === 'kind') resetKind(obj);
    if (field === 'key' && c === 's' && obj.kind === 'ASK' && KEYS[obj.key] && (obj.expect || 'TEXT') === 'TEXT') obj.expect = KEYS[obj.key];
    settle();
    if (el.hasAttribute('data-rr') || c === 't' || c === 'o' && field === 'next') return redraw(el.id);
    paint(c === 'f' || c === 'c' ? -1 : Number(String(b).split('.')[1]));
  }

  /**
   * Brings what summarises the draft in step with it (tabs, the card being edited, hints) without redrawing the fields
   * being typed in. [j] is the step edited (-1: the flow's settings); left out, the one open in the side panel.
   */
  function paint(j = null) {
    const f = current();
    const i = flowIndex();
    if (f) {
      const title = $(`[data-flow-title="${i}"]`);
      if (title) title.textContent = flowName(f, i);
      const state = $(`[data-flow-pills="${i}"]`);
      if (state) {
        state.classList.toggle('is-on', !!f.enabled);
        state.title = f.enabled ? T.on : T.off;
        state.innerHTML = `<span class="visually-hidden">${esc(f.enabled ? T.on : T.off)}</span>`;
      }
      $(`[data-flow-warn="${i}"]`)?.toggleAttribute('hidden', !flowHasIssues(f));
      const summary = $(`[data-flow-summary="${i}"]`);
      if (summary) summary.innerHTML = flowSummary(f);
      const counter = $(`[data-kw-count="${i}"]`);
      if (counter) counter.textContent = tr('keywordsCount', { n: count((f.keywords || []).length), max: count(limitsOf().keywords || 50) });
      const flowNotes = $('[data-flow-issues]');
      if (flowNotes) flowNotes.innerHTML = notice(flowIssues(f));
      if (j == null) j = ui.sel ? f.steps.findIndex(s => s.id === ui.sel) : -1;
      const step = f.steps[j];
      if (step) {
        const notes = $(`[data-step-issues="${j}"]`);
        if (notes) notes.innerHTML = notice(stepIssues(f, step));
        const hint = $(`[data-size="cb-${i}-${j}-text"]`);
        if (hint) {
          const max = limitsOf().textBytes || 1000;
          const bytes = utf8Bytes(step.text);
          hint.hidden = bytes <= max * 0.8;
          hint.className = bytes > max ? 'hint hint--bad' : 'hint hint--warn';
          hint.textContent = hint.hidden ? '' : tr('textSize', { n: count(bytes), max: count(max) });
        }
        // The step's label in its own "leads to" choices follows its text.
        $$(`#chatbot-form select[data-c="t"] option[value="${CSS.escape(step.id)}"], #chatbot-form select[data-c="o"] option[value="${CSS.escape(step.id)}"]`).forEach(o => { o.textContent = stepLabel(step, j + 1); });
      }
      refreshNodes([step ? step.id : START]);
    }
    paintSavebar();
    paintTools();
  }

  function addKeywords(i, texts) {
    checkpoint();
    const f = editable()[i];
    const limits = limitsOf();
    const max = limits.keywords || 50;
    const have = new Map((f.keywords || []).map(w => [words(w), w]));
    ui.note = null;
    f.keywords = f.keywords || [];
    for (const raw of texts) {
      const keyword = raw.trim().slice(0, limits.keywordChars || 60);
      if (!keyword) continue;
      const w = words(keyword);
      if (!w) { ui.note = { flow: f.id, text: tr('keywordNoWords', { keyword }) }; continue; }
      if (have.has(w)) { ui.note = { flow: f.id, text: tr('keywordRepeat', { keyword: have.get(w) }) }; continue; }
      if (f.keywords.length >= max) { ui.note = { flow: f.id, text: tr('keywordsFull', { max: count(max) }) }; break; }
      f.keywords.push(keyword);
      have.set(w, keyword);
    }
    delete ui.pending[f.id];
    settle();
    redraw(`cb-${i}-keywords`);
  }

  function commitKeyword(input) {
    const text = input.value;
    if (!text.trim()) return;
    input.value = '';
    addKeywords(Number(input.dataset.kwInput), [text]);
  }

  function move(list, from, to) {
    if (to < 0 || to >= list.length) return;
    list.splice(to, 0, ...list.splice(from, 1));
  }

  /** Removes step [j] of flow [i], and every link that led to it. */
  function removeStep(i, j) {
    checkpoint();
    const f = editable()[i];
    const [gone] = f.steps.splice(j, 1);
    for (const s of f.steps) {
      for (const key of ['next', 'whenTrue', 'whenFalse', 'failNext']) if (s[key] === gone.id) s[key] = '';
      (s.options || []).forEach(o => { if (o.next === gone.id) o.next = ''; });
    }
    if (ui.sel === gone.id) ui.sel = null;
    ui.edge = null;
    settle();
    d.toast(B.stepRemoved);
  }

  function removeStepById(id) {
    const i = flowIndex();
    const j = current()?.steps.findIndex(s => s.id === id) ?? -1;
    if (j < 0 || current().steps.length < 2) return;
    removeStep(i, j);
    d.render();
    $('[data-board]')?.focus({ preventScroll: true });
  }

  function wire(root) {
    const page = $('[data-chatbot]', root);
    const form = $('#chatbot-form', root);
    const canvas = $('[data-board]', root);
    const edit = canEdit();
    renderChatLog();
    paintSavebar();
    if (form) paint();

    // Anywhere on the page: save, undo and redo (fields keep their own undo); a click elsewhere closes the menu.
    page.addEventListener('keydown', e => {
      const mod = (e.ctrlKey || e.metaKey) && !e.altKey;
      if (!mod || !edit) return;
      const key = e.key.toLowerCase();
      if (key === 's') { e.preventDefault(); save(); return; }
      if (e.target.closest('input, textarea, select')) return;
      if (key === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo(); }
      else if (key === 'y') { e.preventDefault(); redo(); }
    });
    page.addEventListener('pointerdown', e => {
      if (ui.menu && !e.target.closest('[data-board-menu], [data-board-add]')) closeMenu();
    }, true);

    $('[data-chatbot-save]', root)?.addEventListener('click', save);
    $('[data-chatbot-discard]', root)?.addEventListener('click', discard);
    $$('[data-flow-add]', root).forEach(btn => btn.addEventListener('click', () => {
      checkpoint();
      const list = editable();
      const flow = newFlow();
      list.push(flow);
      ui.flowId = flow.id;
      ui.sel = null;
      ui.side = 'edit';
      ui.sideOpen = true;
      redraw(`cb-${list.length - 1}-name`);
    }));
    $$('[data-flow-pick]', root).forEach(btn => btn.addEventListener('click', () => pickFlow(Number(btn.dataset.flowPick))));
    $('.flow-tabs', root)?.addEventListener('keydown', e => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return;
      e.preventDefault();
      const n = flows().length;
      const at = flowIndex();
      pickFlow(e.key === 'Home' ? 0 : e.key === 'End' ? n - 1 : (at + (e.key === 'ArrowRight' ? 1 : n - 1)) % n);
    });
    $$('[data-side-go]', root).forEach(btn => btn.addEventListener('click', () => {
      ui.side = btn.dataset.sideGo;
      ui.sideOpen = true;
      closeMenu();
      d.render();
      if (ui.side === 'try' && !narrow()) $('#chatbot-chat-input')?.focus({ preventScroll: true });
      else $(`[data-side-go="${ui.side}"]`)?.focus({ preventScroll: true });
    }));
    $('[data-side-close]', root)?.addEventListener('click', () => {
      ui.sideOpen = false;
      d.render();
      ($('[data-board]') || $('[data-side-go="try"]'))?.focus({ preventScroll: true });
    });
    $$('[data-view]', root).forEach(btn => btn.addEventListener('click', () => setView(btn.dataset.view)));
    $$('[data-board-undo]', root).forEach(btn => btn.addEventListener('click', undo));
    $$('[data-board-redo]', root).forEach(btn => btn.addEventListener('click', redo));

    const listed = $('[data-list-scroll]', root);
    if (listed) {
      $$('[data-list-add]', listed).forEach(btn => btn.addEventListener('click', () => addStep(btn.dataset.listAdd)));
      // The card being worked on in the list is the one the board opens when it comes back.
      listed.addEventListener('focusin', e => {
        const card = e.target.closest('[data-list-step]');
        if (card) ui.sel = card.dataset.listStep;
        else if (e.target.closest('[data-side-flow]')) ui.sel = null;
      });
    }

    if (canvas) {
      canvas.addEventListener('keydown', boardKeys);
      $('[data-board-add]', canvas)?.addEventListener('click', e => {
        if (ui.menu) return closeMenu();
        const r = e.currentTarget.getBoundingClientRect();
        openMenu({ client: { x: r.left - 8, y: r.bottom - 4 } });
      });
      $('[data-board-tidy]', canvas)?.addEventListener('click', tidy);
      $('[data-board-fit]', canvas)?.addEventListener('click', () => board?.fit());
      $$('[data-board-zoom]', canvas).forEach(btn => btn.addEventListener('click', () => {
        const z = btn.dataset.boardZoom;
        if (z === 'in') board?.zoomBy(1.2);
        else if (z === 'out') board?.zoomBy(1 / 1.2);
        else board?.zoomTo(1);
      }));
      $('[data-edge-remove]', canvas)?.addEventListener('click', () => unlink(ui.edge));
      $('[data-board-menu]', canvas)?.addEventListener('click', e => {
        const kind = e.target.closest('[data-menu-kind]');
        if (kind) return addStep(kind.dataset.menuKind, ui.menu || {});
        if (e.target.closest('[data-menu-unlink]')) {
          const m = ui.menu;
          closeMenu();
          unlink(`${m.from}|${m.port}`);
        }
      });
    }

    const log = $('#chatbot-chat-log', root);
    if (log) {
      $('#chatbot-chat-clear', root).addEventListener('click', restartChat);
      $('#chatbot-channel', root).addEventListener('change', e => { ui.channel = e.target.value; restartChat(); });
      $('#chatbot-at', root).addEventListener('change', e => { ui.at = e.target.value; });
      $('#chatbot-known', root).addEventListener('change', e => { ui.known = e.target.checked; });
      log.addEventListener('click', e => {
        const chip = e.target.closest('[data-reply]');
        if (!chip) return;
        const [index, k] = chip.dataset.reply.split(':').map(Number);
        const reply = ui.chat[index]?.replies?.[k];
        if (reply) sendTest(reply.title, reply.payload);
      });
      $('#chatbot-chat-form', root).addEventListener('submit', e => {
        e.preventDefault();
        const input = $('#chatbot-chat-input');
        const text = input.value;
        input.value = '';
        sendTest(text);
      });
    }
    if (form) wireForm(form, edit);
  }

  function pickFlow(n) {
    const f = flows()[n];
    if (!f) return;
    if (f.id !== ui.flowId) {
      ui.flowId = f.id;
      ui.sel = null;
      ui.edge = null;
      closeMenu();
      d.render();
    }
    $(`#cb-tab-${n}`)?.focus();
  }

  /** The side panel's fields: a flow's settings or a step's. */
  function wireForm(form, edit) {
    form.addEventListener('click', e => {
      const hit = sel => e.target.closest(sel);
      let el;
      if (hit('[data-sel-flow]')) {
        ui.sel = null;
        d.render();
        $(`[data-node-pick="${START}"]`)?.focus({ preventScroll: true });
        return;
      }
      if (!edit) return;
      if ((el = hit('[data-flow-up]'))) { const i = Number(el.dataset.flowUp); checkpoint(); move(editable(), i, i - 1); settle(); return redraw(`cb-${i - 1}`); }
      if ((el = hit('[data-flow-down]'))) { const i = Number(el.dataset.flowDown); checkpoint(); move(editable(), i, i + 1); settle(); return redraw(`cb-${i + 1}`); }
      if ((el = hit('[data-flow-remove]'))) return removeFlow(Number(el.dataset.flowRemove));
      if ((el = hit('[data-flow-duplicate]'))) {
        const i = Number(el.dataset.flowDuplicate);
        checkpoint();
        const copy = clone(editable()[i]);
        const ids = new Map(copy.steps.map(s => [s.id, newId('s')]));
        copy.id = newId('flow');
        copy.name = tr('copyName', { name: flowName(copy, i) });
        copy.enabled = false;
        copy.steps.forEach(s => {
          s.id = ids.get(s.id);
          for (const key of ['next', 'whenTrue', 'whenFalse', 'failNext']) if (s[key]) s[key] = ids.get(s[key]) || '';
          s.options.forEach(o => { o.next = ids.get(o.next) || ''; });
        });
        draft.splice(i + 1, 0, copy);
        ui.flowId = copy.id;
        ui.sel = null;
        d.toast(T.duplicated);
        return redraw(`cb-${i + 1}-name`);
      }
      if ((el = hit('[data-step-remove]'))) {
        const [i, j] = el.dataset.stepRemove.split(':').map(Number);
        removeStep(i, j);
        if (ui.view === 'list') return redraw(j ? `cb-${i}-${j - 1}-kind` : `cb-${i}-0-kind`);
        d.render();
        return $('[data-board]')?.focus({ preventScroll: true });
      }
      if ((el = hit('[data-step-first]'))) {
        const [i, j] = el.dataset.stepFirst.split(':').map(Number);
        checkpoint();
        move(editable()[i].steps, j, 0);
        settle();
        return redraw(`cb-${i}-0-kind`);
      }
      if ((el = hit('[data-step-duplicate]'))) {
        const [i, j] = el.dataset.stepDuplicate.split(':').map(Number);
        checkpoint();
        const f = editable()[i];
        const copy = clone(f.steps[j]);
        copy.id = newId('s');
        if (materialize(f)) {
          const b = board.box(f.steps[j].id);
          const free = clearOf({ x: b.x + 32, y: b.y + b.h + 32 });
          copy.x = FlowBoard.snap(free.x);
          copy.y = FlowBoard.snap(free.y);
        }
        f.steps.splice(j + 1, 0, copy);
        ui.sel = copy.id;
        settle();
        return redraw(`cb-${i}-${j + 1}-kind`);
      }
      if ((el = hit('[data-option-add]'))) {
        const [i, j] = el.dataset.optionAdd.split(':').map(Number);
        checkpoint();
        editable()[i].steps[j].options.push({ title: '', next: '', words: [] });
        return redraw(`cb-${i}-${j}-o${draft[i].steps[j].options.length - 1}`);
      }
      if ((el = hit('[data-option-remove]'))) { const [i, j, k] = el.dataset.optionRemove.split(':').map(Number); checkpoint(); editable()[i].steps[j].options.splice(k, 1); settle(); return redraw(`cb-${i}-${j}-text`); }
      if ((el = hit('[data-hours-add]'))) {
        const [i, j] = el.dataset.hoursAdd.split(':').map(Number);
        checkpoint();
        editable()[i].steps[j].hours.push({ day: 1, from: '09:00', to: '18:00' });
        return redraw(`cb-${i}-${j}-check`);
      }
      if ((el = hit('[data-hours-weekdays]'))) {
        const [i, j] = el.dataset.hoursWeekdays.split(':').map(Number);
        checkpoint();
        editable()[i].steps[j].hours = [1, 2, 3, 4, 5].map(day => ({ day, from: '09:00', to: '18:00' }));
        return redraw(`cb-${i}-${j}-check`);
      }
      if ((el = hit('[data-hours-remove]'))) { const [i, j, k] = el.dataset.hoursRemove.split(':').map(Number); checkpoint(); editable()[i].steps[j].hours.splice(k, 1); settle(); return redraw(`cb-${i}-${j}-check`); }
      if ((el = hit('[data-kw-remove]'))) { const [i, n] = el.dataset.kwRemove.split(':').map(Number); checkpoint(); editable()[i].keywords.splice(n, 1); settle(); return redraw(`cb-${i}-keywords`); }
      if ((el = hit('[data-kw-edit]'))) {
        const [i, n] = el.dataset.kwEdit.split(':').map(Number);
        checkpoint();
        const [word] = editable()[i].keywords.splice(n, 1);
        ui.pending[draft[i].id] = word;
        settle();
        return redraw(`cb-${i}-keywords`);
      }
    });

    form.addEventListener('input', e => {
      const el = e.target;
      if (!edit) return;
      if (el.dataset.kwInput != null) { const f = flows()[Number(el.dataset.kwInput)]; if (f) ui.pending[f.id] = el.value; return; }
      if (typedField(el)) onChange(el);
    });
    form.addEventListener('change', e => {
      const el = e.target;
      // Fields typed into change the draft as they are typed; the others (and any that redraw the page) when they are left.
      if (edit && el.hasAttribute('data-c') && !typedField(el)) onChange(el);
    });
    form.addEventListener('keydown', e => {
      const el = e.target;
      if (el.dataset.kwInput != null) {
        if (e.key === 'Enter') { e.preventDefault(); commitKeyword(el); return; }
        if (e.key === 'Backspace' && !el.value) {
          const f = editable()[Number(el.dataset.kwInput)];
          if (!f.keywords?.length) return;
          e.preventDefault();
          checkpoint();
          ui.pending[f.id] = f.keywords.pop();
          settle();
          return redraw(el.id);
        }
        return;
      }
      if (e.key === 'Enter' && el.tagName === 'INPUT' && el.type !== 'checkbox') e.preventDefault();
    });
    form.addEventListener('focusout', e => {
      if (e.target.id === ui.typing) ui.typing = null;
      if (e.target.dataset?.kwInput != null && edit) commitKeyword(e.target);
    });
    form.addEventListener('mousedown', e => { if (e.target.closest('.token__rm, .token__text')) e.preventDefault(); });
    form.addEventListener('paste', e => {
      const el = e.target;
      if (!edit || el.dataset?.kwInput == null) return;
      const text = e.clipboardData?.getData('text') || '';
      if (!/[\n\r]/.test(text)) return;
      e.preventDefault();
      addKeywords(Number(el.dataset.kwInput), text.split(/\r?\n/));
    });
    form.addEventListener('submit', e => { e.preventDefault(); if (edit) save(); });
  }

  async function removeFlow(i) {
    const f = flows()[i];
    const ok = await d.confirmDialog({ title: tr('removeTitle', { name: flowName(f, i) }), body: T.removeBody, okLabel: T.remove, danger: true });
    if (!ok) return;
    checkpoint();
    editable().splice(i, 1);
    if (ui.flowId === f.id) { ui.flowId = flows()[Math.max(0, i - 1)]?.id || null; ui.sel = null; }
    delete ui.views[f.id];
    settle();
    d.render();
  }

  window.ChatbotUI = {
    init(deps) {
      d = deps;
      // Leaving the page with edits that aren't saved asks first.
      window.addEventListener('beforeunload', e => {
        if (guard || !ready() || !isDirty()) return;
        e.preventDefault();
        e.returnValue = '';
      });
      // Between a phone's layout (the side panel over the board) and a wide one (side by side) the page is drawn again;
      // other resizes, a phone's keyboard opening among them, leave it alone.
      let wasNarrow = narrow();
      window.addEventListener('resize', () => {
        if (narrow() === wasNarrow) return;
        wasNarrow = narrow();
        ui.sideOpen = false;
        if (d.state.active === 'chatbot' && ready() && !board?.busy()) d.renderKeepingFocus();
      });
    },
    load,
    failed,
    render,
    dirty: () => ready() && isDirty(),
  };
})();
